package com.agent4.backend;

import java.util.concurrent.ThreadLocalRandom;

/** 容量等待独立于执行失败重试；半窗抖动避免所有任务同时再次争抢。 */
public final class CapacityBackoff {
    private CapacityBackoff() {}
    public static long delayMillis(int previousDeferrals) {
        if (previousDeferrals < 0) throw new IllegalArgumentException("容量等待次数不能为负数");
        long ceiling = Math.min(30000L, 2000L << Math.min(previousDeferrals, 4));
        return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
    }
}
