package com.forgeflow.shared.events;

import com.forgeflow.shared.tracing.TraceContext;
import com.forgeflow.shared.tracing.Tracer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Domain events over Kafka, with the plain client.
 *
 * Producing: JSON value, the key chosen by the publisher (project id for
 * code.generated - one project's events stay on one partition, in order),
 * idempotent producer with acks=all, and the current trace in a
 * {@code traceparent} header. Sending is asynchronous: a request never waits
 * for the broker, and {@code max.block.ms} is short so a broker that is down
 * costs two seconds, not sixty.
 *
 * Consuming: one platform thread and one KafkaConsumer per subscription
 * (consumers are not thread-safe; {@code wakeup()} is the only call another
 * thread may make). Auto-commit is OFF: offsets are committed after the
 * handler returns, so a crash mid-handler means the event is delivered again
 * - at-least-once, which is why handlers must be idempotent.
 *
 * Failures: a handler gets {@code maxAttempts} tries with growing backoff;
 * after that the message goes to {@code <topic>.DLT} with the error and its
 * origin in headers, and the partition moves on. A message that can't even be
 * parsed skips the retries - it will never parse. One bad event can't stall
 * a partition forever, and none is silently dropped.
 */
public class KafkaEventBus implements EventBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);
    static final String TRACEPARENT = "traceparent";
    static final String EVENT_TYPE = "event-type";

    private final String bootstrapServers;
    private final String clientId;
    private final Tracer tracer;
    private final int partitions;
    private final int maxAttempts;
    private final Duration backoff;
    private final ObjectMapper json = new ObjectMapper();
    private final KafkaProducer<String, String> producer;
    private final Set<String> knownTopics = ConcurrentHashMap.newKeySet();
    private final List<Subscriber<?>> subscribers = new CopyOnWriteArrayList<>();

    public KafkaEventBus(String bootstrapServers, String clientId, Tracer tracer,
                         int partitions, int maxAttempts, Duration backoff) {
        this.bootstrapServers = bootstrapServers;
        this.clientId = clientId;
        this.tracer = tracer;
        this.partitions = partitions;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-producer");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        this.producer = new KafkaProducer<>(p);
        log.info("Kafka event bus: {} as {}", bootstrapServers, clientId);
    }

    // ------------------------------------------------------------ publish

    @Override
    public void publish(String topic, String key, Object event) {
        ensureTopic(topic);
        try (Tracer.Span span = tracer.startProducer("publish " + topic)) {
            span.tag("messaging.system", "kafka").tag("messaging.destination", topic).tag("messaging.key", key);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json.writeValueAsString(event));
            record.headers().add(TRACEPARENT, span.context().traceparent().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EVENT_TYPE, event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
            producer.send(record, (meta, error) -> {
                if (error != null) {
                    log.warn("publishing to {} (key {}) failed: {}", topic, key, error.toString());
                } else {
                    log.debug("published to {}-{}@{}", meta.topic(), meta.partition(), meta.offset());
                }
            });
        } catch (RuntimeException e) {
            // Broker unreachable past max.block.ms, or the event wouldn't
            // serialise. The publisher's work is done and committed; losing
            // this notice costs a slower next search, so log, don't throw.
            log.warn("could not publish to {} (key {}): {}", topic, key, e.toString());
        }
    }

    // ---------------------------------------------------------- subscribe

    @Override
    public <T> void subscribe(String topic, String group, Class<T> type, Consumer<T> handler) {
        ensureTopic(topic);
        ensureTopic(Topics.deadLetter(topic));
        Subscriber<T> s = new Subscriber<>(topic, group, type, handler);
        subscribers.add(s);
        s.thread = Thread.ofPlatform().name("kafka-" + group + "-" + topic).daemon(true).start(s);
        log.info("subscribed to {} as group {}", topic, group);
    }

    @Override
    public String transport() {
        return "kafka";
    }

    private final class Subscriber<T> implements Runnable {

        final String topic;
        final String group;
        final Class<T> type;
        final Consumer<T> handler;
        final KafkaConsumer<String, String> consumer;
        volatile boolean running = true;
        Thread thread;

        Subscriber(String topic, String group, Class<T> type, Consumer<T> handler) {
            this.topic = topic;
            this.group = group;
            this.type = type;
            this.handler = handler;
            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId + "-" + group);
            p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
            this.consumer = new KafkaConsumer<>(p);
        }

        @Override
        public void run() {
            try {
                consumer.subscribe(List.of(topic));
                while (running) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (TopicPartition partition : records.partitions()) {
                        List<ConsumerRecord<String, String>> batch = records.records(partition);
                        for (ConsumerRecord<String, String> r : batch) {
                            handle(r);
                        }
                        long next = batch.get(batch.size() - 1).offset() + 1;
                        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(next)));
                    }
                }
            } catch (WakeupException e) {
                if (running) {
                    throw e;
                }
            } catch (RuntimeException e) {
                log.error("consumer {} on {} stopped: {}", group, topic, e.toString(), e);
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }

        void handle(ConsumerRecord<String, String> r) {
            TraceContext incoming = TraceContext.fromTraceparent(header(r.headers(), TRACEPARENT));
            T event;
            try {
                event = json.readValue(r.value(), type);
            } catch (RuntimeException e) {
                deadLetter(r, "unreadable: " + e.getMessage(), 0);
                return;
            }
            RuntimeException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try (Tracer.Span span = tracer.startConsumer("consume " + topic, incoming)) {
                    span.tag("messaging.system", "kafka").tag("messaging.destination", topic)
                        .tag("messaging.consumer_group", group).tag("messaging.partition", r.partition())
                        .tag("messaging.offset", r.offset()).tag("attempt", attempt);
                    try {
                        handler.accept(event);
                        return;
                    } catch (RuntimeException e) {
                        span.error(e);
                        last = e;
                    }
                }
                log.warn("{} handler for {}-{}@{} failed (attempt {}/{}): {}", group, topic, r.partition(),
                        r.offset(), attempt, maxAttempts, last.toString());
                if (attempt < maxAttempts) {
                    sleep(backoff.multipliedBy(attempt));
                }
            }
            deadLetter(r, last.toString(), maxAttempts);
        }

        void deadLetter(ConsumerRecord<String, String> r, String error, int attempts) {
            String dlt = Topics.deadLetter(topic);
            ProducerRecord<String, String> out = new ProducerRecord<>(dlt, r.key(), r.value());
            for (Header h : r.headers()) {
                out.headers().add(h);
            }
            out.headers().add("dlt-error", error.getBytes(StandardCharsets.UTF_8));
            out.headers().add("dlt-origin", (topic + "-" + r.partition() + "@" + r.offset()).getBytes(StandardCharsets.UTF_8));
            out.headers().add("dlt-group", group.getBytes(StandardCharsets.UTF_8));
            out.headers().add("dlt-attempts", String.valueOf(attempts).getBytes(StandardCharsets.UTF_8));
            try {
                producer.send(out).get(10, TimeUnit.SECONDS);      // wait: committing past a message we failed to park would lose it
                log.error("{}-{}@{} moved to {} after {} attempt(s): {}", topic, r.partition(), r.offset(), dlt, attempts, error);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while dead-lettering", e);
            } catch (ExecutionException | TimeoutException e) {
                // Can't park it: don't commit. Throwing stops this consumer;
                // on restart the message is delivered again.
                throw new IllegalStateException("could not write to " + dlt, e);
            }
        }

        void stop() {
            running = false;
            consumer.wakeup();
        }
    }

    // ------------------------------------------------------------ helpers

    private void ensureTopic(String topic) {
        if (knownTopics.contains(topic)) {
            return;
        }
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        try (Admin admin = Admin.create(p)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(5, TimeUnit.SECONDS);
            log.info("created topic {} ({} partitions)", topic, partitions);
            knownTopics.add(topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                knownTopics.add(topic);
            } else {
                log.warn("could not create topic {}: {}", topic, e.getCause().toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            log.warn("could not create topic {}: broker not answering", topic);
        }
    }

    static String header(Headers headers, String name) {
        Header h = headers.lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        for (Subscriber<?> s : subscribers) {
            s.stop();
        }
        for (Subscriber<?> s : subscribers) {
            try {
                s.thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        producer.close(Duration.ofSeconds(5));
    }
}
