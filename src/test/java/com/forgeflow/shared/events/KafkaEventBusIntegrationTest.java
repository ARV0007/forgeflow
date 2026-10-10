package com.forgeflow.shared.events;

import com.forgeflow.shared.tracing.SpanRecord;
import com.forgeflow.shared.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real broker. CI starts one (apache/kafka, KRaft) and sets
 * KAFKA_BOOTSTRAP_SERVERS; without it, these skip - the in-process bus is
 * covered everywhere else.
 */
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
class KafkaEventBusIntegrationTest {

    record Ping(int n, String note) {
    }

    private final String bootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
    private final List<SpanRecord> spans = new CopyOnWriteArrayList<>();
    private KafkaEventBus bus;
    private String topic;

    @BeforeEach
    void setUp() {
        bus = new KafkaEventBus(bootstrap, "it-" + UUID.randomUUID(), new Tracer(spans::add), 3, 2, Duration.ofMillis(50));
        topic = "it." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void everyGroupGetsEveryEventInKeyOrderWithTheTraceCarriedAcross() throws Exception {
        BlockingQueue<Ping> indexer = new LinkedBlockingQueue<>();
        BlockingQueue<Ping> execution = new LinkedBlockingQueue<>();
        bus.subscribe(topic, "indexer-" + topic, Ping.class, indexer::add);
        bus.subscribe(topic, "execution-" + topic, Ping.class, execution::add);

        Tracer tracer = new Tracer(spans::add);
        String traceId;
        try (Tracer.Span request = tracer.start("POST /generate")) {
            traceId = request.context().traceId();
            for (int i = 1; i <= 5; i++) {
                bus.publish(topic, "project-7", new Ping(i, "run " + i));
            }
        }

        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Ping p = indexer.poll(30, TimeUnit.SECONDS);
            assertThat(p).as("event %d reached the indexer group", i + 1).isNotNull();
            seen.add(p.n());
        }
        assertThat(seen).containsExactly(1, 2, 3, 4, 5);            // same key -> same partition -> in order
        for (int i = 0; i < 5; i++) {
            assertThat(execution.poll(30, TimeUnit.SECONDS)).isNotNull();
        }

        assertThat(spans).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("CONSUMER");
            assertThat(s.traceId()).isEqualTo(traceId);              // one trace: request -> publish -> consume
        });
        assertThat(spans).anySatisfy(s -> assertThat(s.kind()).isEqualTo("PRODUCER"));
    }

    @Test
    void aHandlerThatKeepsFailingSendsTheEventToTheDeadLetterTopicAndTheRestFlow() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BlockingQueue<Ping> handled = new LinkedBlockingQueue<>();
        bus.subscribe(topic, "picky-" + topic, Ping.class, p -> {
            if (p.n() == 1) {
                attempts.incrementAndGet();
                throw new IllegalStateException("cannot index this one");
            }
            handled.add(p);
        });

        bus.publish(topic, "k", new Ping(1, "poison"));
        bus.publish(topic, "k", new Ping(2, "fine"));

        assertThat(handled.poll(30, TimeUnit.SECONDS)).isEqualTo(new Ping(2, "fine"));   // not stuck behind the poison
        assertThat(attempts.get()).isEqualTo(2);                                           // maxAttempts

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + topic);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(p)) {
            dlt.subscribe(List.of(Topics.deadLetter(topic)));
            ConsumerRecord<String, String> parked = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (parked == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : dlt.poll(Duration.ofMillis(500))) {
                    parked = r;
                }
            }
            assertThat(parked).as("message on the DLT").isNotNull();
            assertThat(parked.value()).contains("poison");
            assertThat(KafkaEventBus.header(parked.headers(), "dlt-error")).contains("cannot index this one");
            assertThat(KafkaEventBus.header(parked.headers(), "dlt-attempts")).isEqualTo("2");
            assertThat(KafkaEventBus.header(parked.headers(), "dlt-origin")).startsWith(topic + "-");
        }
    }
}
