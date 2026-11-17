package com.agent4.quota;

import com.agent4.store.TaskHistoryDao;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 配额服务：按会话（sessionId）做月度 token 配额限制。
 *
 * <p>提交闸门：每次任务开始前检查「已用 token 是否已超配额」，超限拒绝。
 * 采用内存计数 + SQLite 兜底双写：内存 map 快速读取，落库后重新计算校准。</p>
 */
public final class QuotaService {

    /** 配额超限异常。 */
    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    private final TaskHistoryDao taskHistoryDao;
    private final int monthlyQuota;
    /** sessionId -> 已用 token（内存缓存，落库时同步）。 */
    private record UsageKey(String sessionId, java.time.YearMonth month) {} // 按会话和月份区分用量缓存。
    private final Map<UsageKey, Integer> usedCache = new ConcurrentHashMap<>(); // 跨月使用新的缓存项。
    private final java.util.Set<String> running = ConcurrentHashMap.newKeySet(); // 每个会话仅允许一个任务运行。

    public QuotaService(TaskHistoryDao taskHistoryDao, int monthlyQuota) {
        this.taskHistoryDao = taskHistoryDao;
        this.monthlyQuota = monthlyQuota;
    }

    /** 提交闸门：超配额则抛异常拒绝。 */
    public void assertSubmitAllowed(String sessionId) {
        int used = currentUsage(sessionId);
        if (used >= monthlyQuota) {
            throw new QuotaExceededException(
                    "会话 " + sessionId + " 当月 token 配额已用尽（" + used + "/" + monthlyQuota + "）");
        }
    }

    /** 当前已用 token（内存优先，落库后回填）。 */
    public int currentUsage(String sessionId) {
        java.time.YearMonth month = java.time.YearMonth.now(); // 与任务入库使用相同的服务器时区。
        return usedCache.computeIfAbsent(new UsageKey(sessionId, month), key -> taskHistoryDao.monthlyTokens(key.sessionId(), key.month())); // 从数据库恢复本月用量。
    }

    /** 任务完成后结算：累加实际用量。 */
    public void settle(String sessionId, int tokens) {
        if (tokens < 0) throw new IllegalArgumentException("用量不能为负数"); // 防止负数结算抵扣额度。
        usedCache.merge(new UsageKey(sessionId, java.time.YearMonth.now()), tokens, Integer::sum); // 实际消耗归入结算月份。
    }

    public void acquire(String sessionId) { // 获取会话执行权后再检查配额。
        if (!running.add(sessionId)) throw new QuotaExceededException("当前会话已有任务运行，请等待完成"); // 阻止同会话并发绕过检查。
        try { // 检查失败时释放会话。
            assertSubmitAllowed(sessionId); // 在独占执行权后读取用量。
        } catch (RuntimeException e) { // 处理配额或数据库失败。
            running.remove(sessionId); // 失败请求不能占用执行权。
            throw e; // 将原始错误上报控制器。
        } // 成功后由任务结束路径释放。
    } // 结束执行权获取。
    public void release(String sessionId) { // 所有任务结束路径都应调用。
        running.remove(sessionId); // 允许该会话提交下一次任务。
    } // 结束执行权释放。

    public int monthlyQuota() {
        return monthlyQuota;
    }

    public Map<String, Object> snapshot(String sessionId) {
        return Map.of(
                "used", currentUsage(sessionId),
                "quota", monthlyQuota,
                "remaining", Math.max(0, monthlyQuota - currentUsage(sessionId)));
    }
}
