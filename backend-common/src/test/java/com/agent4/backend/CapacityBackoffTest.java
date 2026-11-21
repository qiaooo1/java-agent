package com.agent4.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapacityBackoffTest {
    @Test void delayGrowsWithinAJitterWindowAndStopsAtThirtySeconds() {
        for (int count : new int[] {0, 1, 2, 3, 4, 30, Integer.MAX_VALUE}) {
            long ceiling = Math.min(30000L, 2000L << Math.min(count, 4));
            for (int i = 0; i < 100; i++) {
                long delay = CapacityBackoff.delayMillis(count);
                assertTrue(delay >= ceiling / 2 && delay <= ceiling);
            }
        }
    }
    @Test void invalidLimitsAndUnknownRedisResponsesFailClosed() {
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        assertThrows(IllegalArgumentException.class, () -> new RedisSlots(redis, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new RedisSlots(redis, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> CapacityBackoff.delayMillis(-1));
        assertThrows(IllegalStateException.class, () -> new RedisSlots(redis, 2, 8).tryAcquire("u", "token"));
    }
}
