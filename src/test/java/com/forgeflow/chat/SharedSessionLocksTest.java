package com.forgeflow.chat;

import com.forgeflow.shared.redis.RespClient;
import com.forgeflow.support.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** One reply per session even when two sends land on two instances. */
class SharedSessionLocksTest {

    private RespClient redis;
    private String prefix;

    @BeforeEach
    void setUp() {
        assumeTrue(RedisTestSupport.available(), "needs Redis");
        redis = new RespClient(RedisTestSupport.URL, 4, 2000);
        prefix = "ff:test:lock:" + UUID.randomUUID() + ":";
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            redis.close();
        }
    }

    @Test
    void theSecondInstanceWaitsItsTurnAndCannotReleaseSomeoneElsesLock() {
        SessionLocks a = new SessionLocks(redis, prefix, Duration.ofMinutes(1));
        SessionLocks b = new SessionLocks(redis, prefix, Duration.ofMinutes(1));

        assertThat(a.tryAcquire(1L)).isTrue();
        assertThat(b.tryAcquire(1L)).isFalse();        // a different instance, the same session
        assertThat(b.tryAcquire(2L)).isTrue();         // other sessions are unaffected

        b.release(1L);                                  // b never held it: nothing happens
        assertThat(a.tryAcquire(1L)).isFalse();

        a.release(1L);
        assertThat(b.tryAcquire(1L)).isTrue();
    }

    @Test
    void aLockFromAnInstanceThatDiedExpiresOnItsOwn() throws InterruptedException {
        SessionLocks crashed = new SessionLocks(redis, prefix, Duration.ofMillis(300));
        SessionLocks survivor = new SessionLocks(redis, prefix, Duration.ofMinutes(1));
        assertThat(crashed.tryAcquire(5L)).isTrue();   // ...and never releases
        assertThat(survivor.tryAcquire(5L)).isFalse();
        Thread.sleep(500);
        assertThat(survivor.tryAcquire(5L)).isTrue();

        crashed.release(5L);                            // the late release must not free the survivor's lock
        assertThat(new SessionLocks(redis, prefix, Duration.ofMinutes(1)).tryAcquire(5L)).isFalse();
    }
}
