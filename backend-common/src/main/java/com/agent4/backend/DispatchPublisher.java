package com.agent4.backend; // 调度层只负责 Outbox 和恢复，不执行模型调用。
import com.agent4.contract.Contracts.Dispatch; // 消息版本可用于未来协议演进。
import com.fasterxml.jackson.databind.ObjectMapper; // 发布 JSON 而非 Java 对象序列化。
import org.springframework.amqp.core.*; // 设置持久化消息和追踪头。
import org.springframework.amqp.rabbit.core.RabbitTemplate; // 发布至工作交换机。
import org.springframework.amqp.rabbit.connection.CorrelationData; // 等待 broker 确认并检查退回。
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; // Worker 默认不承担发布轮询。
import org.springframework.scheduling.annotation.Scheduled; // 有界批次的定时发布。
import org.springframework.stereotype.Component; // 由平台服务启用。
import org.slf4j.*; // 输出结构化标识，便于跨进程追踪。
import java.util.concurrent.TimeUnit; // 限制发送确认等待时间。
@Component // 只有开启 dispatcher 的应用才注册。
@ConditionalOnProperty(name = "workbench.dispatcher-enabled", havingValue = "true") // 平台部署可以多实例安全竞争。
public class DispatchPublisher { // 在数据库事务外等待网络确认。
    private static final Logger log = LoggerFactory.getLogger(DispatchPublisher.class); // 故障不吞掉，记录 taskId 和 traceId。
    private final OutboxStore outbox; private final TaskStore tasks; private final RabbitTemplate rabbit; private final ObjectMapper json; // 保存调度依赖。
    public DispatchPublisher(OutboxStore outbox, TaskStore tasks, RabbitTemplate rabbit, ObjectMapper json) { this.outbox = outbox; this.tasks = tasks; this.rabbit = rabbit; this.json = json; } // 构造器注入，方便故障测试替换客户端。
    @Scheduled(fixedDelayString = "${workbench.publish-delay-ms:500}") // 上一批完成后再等待，避免同实例无限积压调度线程。
    public void publish() { // 每批最多处理二十条待发通知。
        for (String id : outbox.ready()) outbox.claim(id).ifPresent(event -> { // 条件领取失败时跳过。
            try { // 确认失败、超时与无路由均进入重试。
                CorrelationData correlation = new CorrelationData(event.id()); // 将确认绑定至 Outbox 标识。
                Message message = MessageBuilder.withBody(json.writeValueAsBytes(new Dispatch(1, event.taskId(), event.traceId()))).setContentType("application/json").setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(event.id()).setHeader("traceId", event.traceId()).build(); // 持久化 JSON 消息带完整追踪信息。
                rabbit.send(BackendConfiguration.EXCHANGE, "execute", message, correlation); // 此时没有持有业务事务和任务行锁。
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS); // 有界等待 broker 发布确认。
                if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("消息被拒绝或没有可路由队列"); // 发布确认不等同于路由成功。
                outbox.sent(event); // 仅成功确认后标记已发送。
            } catch (Exception e) { // 进程崩溃则由发布租约过期接管。
                if (e instanceof InterruptedException) Thread.currentThread().interrupt(); // 保留应用停止信号。
                outbox.failed(event, e); // 失败信息和重试时间持久化。
                log.warn("dispatch_failed taskId={} traceId={}", event.taskId(), event.traceId(), e); // 不输出用户密码或模型密钥。
            } // 完成单消息发送。
        }); // 完成一批发送。
    } // 结束发布任务。
    @Scheduled(fixedDelay = 5000) // 租约恢复不依赖 RabbitMQ 是否在线。
    public void recover() { // 多实例恢复时由任务行锁确定唯一状态转换。
        int scanned = tasks.recoverExpired(); // 回收失联执行尝试并安排有限重试。
        if (scanned > 0) log.info("expired_attempts_scanned count={}", scanned); // 输出恢复活动供观测。
    } // 结束恢复任务。
} // 结束调度器。
