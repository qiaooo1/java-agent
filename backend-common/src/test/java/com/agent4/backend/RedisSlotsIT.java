package com.agent4.backend;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 默认 integration profile 使用隔离容器，也可由脚本传入其独立临时 Redis 的端口。 */
class RedisSlotsIT {
    static GenericContainer<?> container;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static final String PROVIDER = "slots:{execution}:provider";
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final String prefix = UUID.randomUUID().toString();
    @BeforeAll static void connect() {
        String port = System.getProperty("slots.test.port"), host = "127.0.0.1";
        if (port == null) {
            container = new GenericContainer<>("redis:7.2.4-alpine").withExposedPorts(6379); container.start();
            host = container.getHost(); port = container.getMappedPort(6379).toString();
        }
        factory = new LettuceConnectionFactory(host, Integer.parseInt(port)); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        assertEquals(0L, redis.opsForZSet().zCard(PROVIDER), "请只使用隔离的空 Redis，测试不会清空已有全局容量");
    }
    @AfterAll static void close() { if (factory != null) factory.destroy(); if (container != null) container.stop(); }
    String token(String user) { String token = UUID.randomUUID().toString(); tokens.put(token, user); return token; }
    String key(String user) { return "slots:{execution}:user:" + user; }
    @AfterEach void cleanup() {
        // 只清理本测试生成的随机 token，不使用 FLUSHDB 或删除全局键。
        tokens.forEach((token, user) -> { redis.opsForZSet().remove(key(user), token); redis.opsForZSet().remove(PROVIDER, token); });
    }
    int burst(int calls, int users, int userLimit, int globalLimit) throws Exception {
        RedisSlots first = new RedisSlots(redis, userLimit, globalLimit), second = new RedisSlots(redis, userLimit, globalLimit);
        var pool = Executors.newFixedThreadPool(calls); var ready = new CountDownLatch(calls); var start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                String user = prefix + "-u" + (i % users), token = token(user); RedisSlots slots = i % 2 == 0 ? first : second;
                results.add(pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); if (slots.tryAcquire(user, token) == RedisSlots.Admission.ACQUIRED) accepted.incrementAndGet(); return null; }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            for (var result : results) result.get(15, TimeUnit.SECONDS);
            assertEquals((long) accepted.get(), redis.opsForZSet().zCard(PROVIDER));
            for (int i = 0; i < users; i++) assertTrue(redis.opsForZSet().zCard(key(prefix + "-u" + i)) <= userLimit);
            return accepted.get();
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void sixtyFourContendersAcrossWorkersCannotExceedGlobalCapacity() throws Exception {
        assertEquals(8, burst(64, 8, 2, 8));
    }
    @Test void oneUserCannotOccupyAllGlobalCapacity() throws Exception {
        assertEquals(2, burst(32, 1, 2, 8));
        var slots = new RedisSlots(redis, 2, 8); String user = prefix + "-another";
        assertEquals(RedisSlots.Admission.ACQUIRED, slots.tryAcquire(user, token(user)));
    }
    @Test void releaseRequiresBothUserAndTokenAndAllowsNextWaiter() {
        var slots = new RedisSlots(redis, 1, 1); String a = prefix + "a", b = prefix + "b", one = token(a), two = token(b);
        assertEquals(RedisSlots.Admission.ACQUIRED, slots.tryAcquire(a, one));
        assertEquals(RedisSlots.Admission.USER_LIMIT, slots.tryAcquire(a, token(a)));
        slots.release(b, one); slots.release(a, "wrong-token");
        assertEquals(RedisSlots.Admission.PROVIDER_LIMIT, slots.tryAcquire(b, two)); assertTrue(slots.refresh(a, one));
        slots.release(a, one); slots.release(a, one);
        assertEquals(RedisSlots.Admission.ACQUIRED, slots.tryAcquire(b, two));
    }
    @Test void expiredSlotsCannotBeRenewedAndCanBeReclaimed() {
        var slots = new RedisSlots(redis, 1, 1); String a = prefix + "a", b = prefix + "b", one = token(a);
        assertTrue(slots.acquire(a, one));
        Long ttl = redis.getExpire(PROVIDER, TimeUnit.MILLISECONDS); assertTrue(ttl > 0 && ttl <= 60000);
        redis.opsForZSet().add(key(a), one, 0); redis.opsForZSet().add(PROVIDER, one, 0);
        assertFalse(slots.refresh(a, one)); assertTrue(slots.acquire(b, token(b))); assertFalse(slots.refresh(a, one));
    }
    @Test void missingOneSideOfTheLeaseCannotRestoreOwnership() {
        var slots = new RedisSlots(redis, 1, 1); String a = prefix + "a", b = prefix + "b", one = token(a);
        assertTrue(slots.acquire(a, one)); redis.opsForZSet().remove(key(a), one);
        assertFalse(slots.refresh(a, one)); slots.release(b, one);
        assertEquals(RedisSlots.Admission.PROVIDER_LIMIT, slots.tryAcquire(b, token(b)));
        // 模拟到期，不用睡眠等待三十秒；下一次申请按 Redis 时钟清理过期项。
        redis.opsForZSet().add(PROVIDER, one, 0); assertTrue(slots.acquire(b, token(b)));
    }
}
