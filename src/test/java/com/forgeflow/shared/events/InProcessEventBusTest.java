package com.forgeflow.shared.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessEventBusTest {

    record Ping(int n) {
    }

    @Test
    void everyGroupGetsEveryEventAndAFailingHandlerHurtsNobody() {
        InProcessEventBus bus = new InProcessEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe("t", "a", Ping.class, p -> seen.add("a" + p.n()));
        bus.subscribe("t", "broken", Ping.class, p -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe("t", "b", Ping.class, p -> seen.add("b" + p.n()));
        bus.subscribe("other", "c", Ping.class, p -> seen.add("c" + p.n()));

        bus.publish("t", "k", new Ping(1));      // must not throw despite "broken"
        bus.publish("t", "k", new Ping(2));

        assertThat(seen).containsExactly("a1", "b1", "a2", "b2");
        assertThat(bus.transport()).isEqualTo("in-process");
    }

    @Test
    void deadLetterTopicsAreNamedByConvention() {
        assertThat(Topics.deadLetter(Topics.CODE_GENERATED)).isEqualTo("code.generated.DLT");
    }
}
