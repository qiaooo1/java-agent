package com.agent4.platform.api; // SSE 是任务事件的订阅者，断开连接不自动取消后台任务。
import com.agent4.backend.*; // 权限和事件补读统一交给仓储。
import com.agent4.contract.Contracts; // 使用统一终态判定。
import org.springframework.web.bind.annotation.*; // 定义 fetch 可读取的 SSE 接口。
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter; // 异步 HTTP 连接不独占普通请求线程。
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 获取已验签身份。
import org.springframework.security.oauth2.jwt.Jwt; // SSE 仍然校验 Bearer 令牌。
import org.springframework.scheduling.annotation.Scheduled; // 有界轮询数据库事件。
import jakarta.annotation.PreDestroy; // 应用退出时清理连接。
import java.time.Instant; // 连接存活期间也检查令牌过期。
import java.util.concurrent.*; // 使用独立有界事件发送池。
import java.util.concurrent.atomic.*; // 控制连接数和每连接的单次发送任务。
@RestController // 不引入原生 EventSource 的票据认证复杂度。
public class TaskStreamController { // 第一版主动选择数据库补读，后续以压测决定是否加入事件总线。
    private final TaskStore tasks; // 事件和任务终态来自同一数据库。
    private final ConcurrentMap<SseEmitter, Subscription> clients = new ConcurrentHashMap<>(); // 每个实例仅保存自己的连接。
    private final AtomicInteger connections = new AtomicInteger(); // 限制每个实例的长连接数量。
    private final ExecutorService senders = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100)); // 不让慢客户端占用 Outbox 调度线程。
    private static final class Subscription { // 每条连接维护独立补读位置。
        final String user, task; final Instant expiry; final AtomicBoolean sending = new AtomicBoolean(); int seq; int idle; // seq 只由单个发送任务推进。
        Subscription(String user, String task, Instant expiry, int seq) { this.user = user; this.task = task; this.expiry = expiry; this.seq = seq; } // 保存已授权的订阅范围。
    } // 结束连接状态。
    public TaskStreamController(TaskStore tasks) { this.tasks = tasks; } // 注入事件仓储。
    @GetMapping(value = "/api/tasks/{id}/stream", produces = "text/event-stream") // 前端用 fetch 添加 Authorization 和 Last-Event-ID。
    public SseEmitter stream(@AuthenticationPrincipal Jwt user, @PathVariable String id, @RequestHeader(value = "Last-Event-ID", defaultValue = "0") int after) { // 使用业务序号补读，不依赖 Redis Stream ID。
        var task = tasks.owned(user.getSubject(), id); // 在创建连接前完成资源授权。
        if (after < 0 || after > task.nextSeq()) throw new BusinessException(400, "事件序号无效"); // 拒绝越界游标。
        if (connections.incrementAndGet() > 100) { connections.decrementAndGet(); throw new BusinessException(503, "事件连接已满，请稍后重试"); } // 实例容量必须显式限制。
        SseEmitter emitter = new SseEmitter(120000L); // 每两分钟重连并重新经过认证，避免永久连接。
        clients.put(emitter, new Subscription(user.getSubject(), id, user.getExpiresAt(), after)); // 保存该连接的补读位置。
        emitter.onCompletion(() -> remove(emitter)); // 正常关闭时释放容量。
        emitter.onTimeout(() -> { remove(emitter); emitter.complete(); }); // 超时允许客户端带序号续读。
        emitter.onError(error -> remove(emitter)); // 发送失败或客户端断开同样清理。
        return emitter; // 后台轮询发送事件，无需为每条连接创建常驻线程。
    } // 结束订阅创建。
    @Scheduled(fixedDelay = 500) // 本地默认最多约半秒发现新事件，不承诺尚未测得的性能。
    public void poll() { // 只安排发送，不在调度线程执行慢网络写入。
        clients.forEach((emitter, subscription) -> { // 每条连接独立推进游标。
            if (!subscription.sending.compareAndSet(false, true)) return; // 上次发送未完成时不堆积任务。
            try { senders.execute(() -> send(emitter, subscription)); } catch (RejectedExecutionException e) { subscription.sending.set(false); remove(emitter); emitter.complete(); } // 过载主动断开，依靠持久化游标补读。
        }); // 完成当前批次调度。
    } // 结束轮询安排。
    private void send(SseEmitter emitter, Subscription subscription) { // 所有网络写入在有界发送池进行。
        try { // 令牌到期、数据库故障和网络故障都关闭订阅。
            if (!clients.containsKey(emitter)) return; // 已移除连接不能继续发送。
            if (subscription.expiry == null || !subscription.expiry.isAfter(Instant.now())) { remove(emitter); emitter.complete(); return; } // 长连接也遵守认证有效期。
            var events = tasks.events(subscription.user, subscription.task, subscription.seq); // 始终按数据库业务序号增量读取。
            for (var event : events) { // 不跳过未成功发送的事件。
                emitter.send(SseEmitter.event().id(Integer.toString(event.seq())).name("task").data(event)); // 以完整事件 JSON 推送，文本由浏览器安全渲染。
                subscription.seq = event.seq(); // 仅在成功写入后推进服务端连接游标。
            } // 完成当前事件批次。
            if (events.isEmpty() && ++subscription.idle % 20 == 0) emitter.send(SseEmitter.event().comment("keepalive")); // 空闲连接每约十秒发送心跳。
            var latest = tasks.owned(subscription.user, subscription.task); // 与事件查询之间产生的新事件会在下一批继续补读。
            if (Contracts.terminal(latest.state()) && subscription.seq >= latest.nextSeq()) { remove(emitter); emitter.complete(); } // 读完终态事件后才关闭，避免漏掉最后一批。
        } catch (Exception e) { remove(emitter); emitter.complete(); } finally { subscription.sending.set(false); } // 故障不影响后台任务的执行。
    } // 结束单连接发送。
    private void remove(SseEmitter emitter) { if (clients.remove(emitter) != null) connections.decrementAndGet(); } // 多个关闭回调只释放一次容量。
    @PreDestroy public void close() { clients.keySet().forEach(emitter -> { remove(emitter); emitter.complete(); }); senders.shutdownNow(); } // 应用退出时停止发送并释放所有连接。
} // 结束 SSE 订阅。
