package com.forgeflow.shared.events;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app with code.generated on a real Kafka broker: an agent run is
 * published, the indexer group consumes it from the broker (not from a method
 * call) and the project's chunks appear - without any search triggering it.
 */
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@TestPropertySource(properties = {
        "forgeflow.events.transport=kafka",
        "forgeflow.events.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}",
        "forgeflow.events.kafka.client-id=e2e"
})
class KafkaModeEndToEndTest extends ApiTestSupport {

    @Autowired
    EventBus bus;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRunIsIndexedByAConsumerReadingFromKafka() throws Exception {
        assertThat(bus.transport()).isEqualTo("kafka");

        Account a = signup("kafka");
        long id = createProject(a, "via-kafka");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "build"), 200);

        int chunks = 0;
        for (int i = 0; i < 60 && chunks == 0; i++) {
            Thread.sleep(500);
            chunks = jdbc.queryForObject("SELECT count(*) FROM file_chunks WHERE project_id = ?", Integer.class, id);
        }
        assertThat(chunks).as("chunks written by the Kafka-fed indexer").isGreaterThanOrEqualTo(3);
    }
}
