package com.kong.tickjob.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("一致性哈希散列工具")
class HashUtilsTest {

    @Test
    @DisplayName("同一个键永远得到同一个值")
    void isDeterministic() {
        for (String key : new String[]{"a", "job-1", "10.0.0.1:9999#VN7", "", "中文键"}) {
            assertEquals(HashUtils.md5Hash(key), HashUtils.md5Hash(key));
        }
    }

    @Test
    @DisplayName("返回值落在无符号 32 位区间内，不会出现负数键")
    void isAlwaysNonNegative() {
        for (long i = 0; i < 5_000; i++) {
            long hash = HashUtils.md5Hash("job-" + i);
            assertTrue(hash >= 0, "散列值不应为负：" + hash);
            assertTrue(hash <= 0xFFFFFFFFL, "散列值不应超过 32 位：" + hash);
        }
    }

    @Test
    @DisplayName("雪崩效应足够好：键分布均匀，没有把大量键挤进同一个桶")
    void distributesEvenly() {
        int bucketCount = 100;
        int keys = 20_000;
        Map<Long, Integer> buckets = new HashMap<>();

        for (long i = 0; i < keys; i++) {
            long bucket = HashUtils.md5Hash("job-" + i) % bucketCount;
            buckets.merge(bucket, 1, Integer::sum);
        }

        assertEquals(bucketCount, buckets.size(), "每个桶都应当被覆盖到");
        double average = (double) keys / bucketCount;
        buckets.forEach((bucket, count) ->
                assertTrue(count > average * 0.5 && count < average * 1.5,
                        "桶 " + bucket + " 偏载：" + count + "，期望均值 " + average));
    }

    @Test
    @DisplayName("不同的键基本不碰撞")
    void hasNoObviousCollisions() {
        Map<Long, String> seen = new HashMap<>();
        int collisions = 0;
        int keys = 20_000;
        for (long i = 0; i < keys; i++) {
            String key = "key-" + i;
            String previous = seen.put(HashUtils.md5Hash(key), key);
            if (previous != null) {
                collisions++;
            }
        }
        // 20000 个键散到 2^32 空间，期望碰撞数不到 1，这里留足余量
        assertTrue(collisions < 10, "碰撞数偏高：" + collisions);
    }
}
