package com.agent4.backend; // Redis 只负责共享执行容量，不代替数据库执行权。
import org.springframework.data.redis.core.StringRedisTemplate; // 使用字符串编码，Lua 参数可直接审计。
import org.springframework.data.redis.core.script.DefaultRedisScript; // 脚本在服务端原子执行并缓存 SHA。
import org.springframework.stereotype.Component; // 平台可观测、Worker 可获取槽位。
import org.springframework.beans.factory.annotation.Value; // 注入容量配置。
import java.util.List; // 将用户和供应商槽位作为同一脚本的两个键。
@Component // 多 Worker 共用同一个 Redis 容量池。
public class RedisSlots { // 每个槽位使用唯一 token，避免误删别人的租约。
    private final StringRedisTemplate redis; // Redis 客户端由 Boot 管理。
    private final int userLimit, providerLimit; // 用户级与供应商级双重上限。
    private static final long TTL = 30000; // 每个槽位三十秒失效，心跳每五秒刷新。
    private static final String CLOCK = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); "; // 使用 Redis 时钟而非 Worker 时钟。
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(CLOCK + "for i=1,2 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now) end; if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) or redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[3]) then return 0 end; for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[4]),ARGV[1]); redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[4])*2) end; return 1", Long.class); // 清理过期成员、检查双重容量与占位不可拆开。
    private static final DefaultRedisScript<Long> REFRESH = new DefaultRedisScript<>(CLOCK + "for i=1,2 do local s=redis.call('ZSCORE',KEYS[i],ARGV[1]); if not s or tonumber(s)<=now then return 0 end end; for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[2]),ARGV[1]); redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2) end; return 1", Long.class); // 不允许已过期槽位自行复活。
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("return redis.call('ZREM',KEYS[1],ARGV[1])+redis.call('ZREM',KEYS[2],ARGV[1])", Long.class); // 精确删除自己的 token。
    public RedisSlots(StringRedisTemplate redis, @Value("${workbench.worker-concurrency:2}") int userLimit, @Value("${workbench.provider-concurrency:8}") int providerLimit) { // 所有实例使用相同限制才能形成全局约束。
        this.redis = redis; this.userLimit = userLimit; this.providerLimit = providerLimit; // 保存客户端和容量。
    } // 完成容量组件构造。
    private List<String> keys(String user) { // Redis Cluster 下两个键必须属于同一 hash slot。
        return List.of("slots:{execution}:user:" + user, "slots:{execution}:provider"); // 固定 hash tag 使双键脚本可原子执行。
    } // 结束键构造。
    public boolean acquire(String user, String token) { // Redis 故障向上传播，不能失效放行模型调用。
        return Long.valueOf(1).equals(redis.execute(ACQUIRE, keys(user), token, Integer.toString(userLimit), Integer.toString(providerLimit), Long.toString(TTL))); // 原子检查用户与模型并发上限。
    } // 结束槽位申请。
    public boolean refresh(String user, String token) { // 心跳必须同时保持用户和供应商槽位。
        return Long.valueOf(1).equals(redis.execute(REFRESH, keys(user), token, Long.toString(TTL))); // 任何一个失效都停止后续调用。
    } // 结束槽位续约。
    public void release(String user, String token) { // 任务结束后主动归还容量。
        redis.execute(RELEASE, keys(user), token); // 故障情况下仍由 TTL 最终清理。
    } // 结束槽位释放。
} // 结束共享并发控制。
