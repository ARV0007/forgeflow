package com.forgeflow.shared.ratelimit;

import com.forgeflow.support.RedisTestSupport;
import com.forgeflow.shared.redis.RespClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** One bucket algorithm, two homes: this JVM and a Redis script. Both must behave the same. */
class TokenBucketTest {

    private static final Limit THREE_PER_MINUTE = new Limit("t", 3, Duration.ofMinutes(1));

    @Test
    void inMemoryAllowsTheBurstThenRefillsSteadily() {
        AtomicLong now = new AtomicLong(1_000_000);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(now::get);

        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isTrue();

        Decision refused = limiter.tryAcquire("a", THREE_PER_MINUTE);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMs()).isBetween(19_000L, 20_000L);    // one token every 20s

        assertThat(limiter.tryAcquire("b", THREE_PER_MINUTE).allowed()).isTrue();   // keys are separate

        now.addAndGet(20_000);
        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isFalse();

        now.addAndGet(10 * 60_000);                                        // a long wait never overfills
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("a", THREE_PER_MINUTE).allowed()).isFalse();
    }

    @Test
    void redisGivesTheSameAnswers() {
        assumeTrue(RedisTestSupport.available(), "no Redis at " + RedisTestSupport.URL);
        String key = UUID.randomUUID().toString();
        try (RespClient redis = new RespClient(RedisTestSupport.URL, 2, 2000)) {
            RedisRateLimiter limiter = new RedisRateLimiter(redis, failIfUsed(), "ff:test:");

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire(key, THREE_PER_MINUTE).allowed()).isTrue();
            }
            Decision refused = limiter.tryAcquire(key, THREE_PER_MINUTE);
            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfterMs()).isBetween(1L, 20_000L);
            assertThat(limiter.tryAcquire(key + "-other", THREE_PER_MINUTE).allowed()).isTrue();
        }
    }

    @Test
    void redisRecoversWhenItForgetsTheScript() throws IOException {
        assumeTrue(RedisTestSupport.available(), "no Redis at " + RedisTestSupport.URL);
        try (RespClient redis = new RespClient(RedisTestSupport.URL, 2, 2000)) {
            RedisRateLimiter limiter = new RedisRateLimiter(redis, failIfUsed(), "ff:test:");
            assertThat(limiter.tryAcquire(UUID.randomUUID().toString(), THREE_PER_MINUTE).allowed()).isTrue();

            redis.call("SCRIPT", "FLUSH");          // what a Redis restart does to the script cache

            assertThat(limiter.tryAcquire(UUID.randomUUID().toString(), THREE_PER_MINUTE).allowed()).isTrue();
        }
    }

    @Test
    void whenRedisIsDownTheLimitStillHoldsPerInstance() {
        try (RespClient dead = new RespClient("redis://127.0.0.1:1", 1, 300)) {
            RedisRateLimiter limiter = new RedisRateLimiter(dead, new InMemoryRateLimiter(), "ff:test:");
            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire("k", THREE_PER_MINUTE).allowed()).isTrue();
            }
            assertThat(limiter.tryAcquire("k", THREE_PER_MINUTE).allowed()).isFalse();
        }
    }

    private static RateLimiter failIfUsed() {
        return (key, limit) -> {
            throw new AssertionError("fell back to memory while Redis was up");
        };
    }
}
