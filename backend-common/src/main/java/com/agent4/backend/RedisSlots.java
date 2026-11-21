package com.agent4.backend; // Redis 只负责共享执行容量，不代替数据库执行权。
import org.springframework.data.redis.core.StringRedisTemplate; // 使用字符串编码，Lua 参数可直接审计。
import org.springframework.data.redis.core.script.DefaultRedisScript; // 脚本在服务端原子执行并缓存 SHA。
import org.springframework.stereotype.Component; // 平台可观测、Worker 可获取槽位。
import org.springframework.beans.factory.annotation.Value; // 注入容量配置。
import java.util.List; // 将用户和供应商槽位作为同一脚本的两个键。
@Component // 多 Worker 共用同一个 Redis 容量池。
public class RedisSlots { // 每个槽位使用唯一 token，避免误删别人的租约。
    public enum Admission { ACQUIRED, USER_LIMIT, PROVIDER_LIMIT }
    private final StringRedisTemplate redis; // Redis 客户端由 Boot 管理。
    private final int userLimit, providerLimit; // 用户级与供应商级双重上限。
    private static final long TTL = 30000; // 每个槽位三十秒失效，心跳每五秒刷新。
    private static final String CLOCK = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); "; // 使用 Redis 时钟而非 Worker 时钟。
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(CLOCK + "for i=1,2 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now) end; if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end; if redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[3]) then return -1 end; for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[4]),ARGV[1]); redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[4])*2) end; return 1", Long.class); // 清理、检查和占位原子完成，同时区分等待原因。
    private static final DefaultRedisScript<Long> REFRESH = new DefaultRedisScript<>(CLOCK + "for i=1,2 do local s=redis.call('ZSCORE',KEYS[i],ARGV[1]); if not s or tonumber(s)<=now then return 0 end end; for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[2]),ARGV[1]); redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2) end; return 1", Long.class); // 不允许已过期槽位自行复活。
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("if not redis.call('ZSCORE',KEYS[1],ARGV[1]) then return 0 end; return redis.call('ZREM',KEYS[1],ARGV[1])+redis.call('ZREM',KEYS[2],ARGV[1])", Long.class); // 用户和 token 必须同时匹配，不能误删其他用户的全局槽位。
    public RedisSlots(StringRedisTemplate redis, @Value("${workbench.user-concurrency:${workbench.worker-concurrency:2}}") int userLimit, @Value("${workbench.provider-concurrency:8}") int providerLimit) { // 新名称区分用户和实例并发，兼容原属性名。
        if (userLimit < 1 || providerLimit < 1) throw new IllegalArgumentException("用户与供应商并发上限必须为正数");
        this.redis = redis; this.userLimit = userLimit; this.providerLimit = providerLimit; // 保存客户端和容量。
    } // 完成容量组件构造。
    private List<String> keys(String user) { // Redis Cluster 下两个键必须属于同一 hash slot。
        return List.of("slots:{execution}:user:" + user, "slots:{execution}:provider"); // 固定 hash tag 使双键脚本可原子执行。
    } // 结束键构造。
    public boolean acquire(String user, String token) { // Redis 故障向上传播，不能失效放行模型调用。
        return tryAcquire(user, token) == Admission.ACQUIRED;
    } // 结束槽位申请。
    public Admission tryAcquire(String user, String token) {
        if (user == null || user.isBlank() || token == null || token.isBlank()) throw new IllegalArgumentException("容量归属和执行标识不能为空");
        Long result = redis.execute(ACQUIRE, keys(user), token, Integer.toString(userLimit), Integer.toString(providerLimit), Long.toString(TTL));
        if (Long.valueOf(1).equals(result)) return Admission.ACQUIRED;
        if (Long.valueOf(0).equals(result)) return Admission.USER_LIMIT;
        if (Long.valueOf(-1).equals(result)) return Admission.PROVIDER_LIMIT;
        throw new IllegalStateException("容量申请结果不确定，拒绝执行");
    }
    public boolean refresh(String user, String token) { // 心跳必须同时保持用户和供应商槽位。
        return Long.valueOf(1).equals(redis.execute(REFRESH, keys(user), token, Long.toString(TTL))); // 任何一个失效都停止后续调用。
    } // 结束槽位续约。
    public void release(String user, String token) { // 任务结束后主动归还容量。
        redis.execute(RELEASE, keys(user), token); // 故障情况下仍由 TTL 最终清理。
    } // 结束槽位释放。
} // 结束共享并发控制。
