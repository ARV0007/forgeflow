package com.forgeflow.shared.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffClockTest {

    @Test
    void waitsAddUpPerThreadAndDrainingResets() throws InterruptedException {
        BackoffClock.drain();
        BackoffClock.waited(700);
        BackoffClock.waited(300);
        AtomicLong other = new AtomicLong(-1);
        Thread t = Thread.ofVirtual().start(() -> other.set(BackoffClock.drain()));
        t.join();
        assertThat(other.get()).isZero();                 // another run's waits are its own
        assertThat(BackoffClock.drain()).isEqualTo(1000);
        assertThat(BackoffClock.drain()).isZero();
    }
}
