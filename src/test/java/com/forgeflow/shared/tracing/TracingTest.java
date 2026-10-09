package com.forgeflow.shared.tracing;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TracingTest.RecordingReporterConfig.class)
class TracingTest extends ApiTestSupport {

    /** Collects spans instead of sending them to Zipkin. */
    static class Recorder implements SpanReporter {
        final List<SpanRecord> spans = new CopyOnWriteArrayList<>();

        @Override
        public void report(SpanRecord span) {
            spans.add(span);
        }
    }

    @TestConfiguration
    static class RecordingReporterConfig {
        @Bean
        @Primary
        Recorder recordingReporter() {
            return new Recorder();
        }
    }

    @Autowired
    Recorder recorder;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        recorder.spans.clear();
    }

    private MockHttpServletResponse getRaw(String path, Account as, String traceparent) throws Exception {
        var req = MockMvcRequestBuilders.get(path);
        if (as != null) {
            req.header("Authorization", "Bearer " + as.token());
        }
        if (traceparent != null) {
            req.header("traceparent", traceparent);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void everyResponseCarriesATraceEvenA401() throws Exception {
        MockHttpServletResponse r = getRaw("/api/v1/projects", null, null);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getHeader("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(r.getHeader("traceparent")).matches("00-" + r.getHeader("X-Trace-Id") + "-[0-9a-f]{16}-01");
        assertThat(getRaw("/api/v1/projects", null, null).getHeader("X-Trace-Id")).isNotEqualTo(r.getHeader("X-Trace-Id"));
    }

    @Test
    void aCallersTraceIsContinuedAndGarbageIsIgnored() throws Exception {
        Account a = signup("tracer");
        String theirs = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        MockHttpServletResponse r = getRaw("/api/v1/projects", a, theirs);
        assertThat(r.getHeader("X-Trace-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(r.getHeader("traceparent")).doesNotContain("00f067aa0ba902b7");    // our own span id

        SpanRecord server = recorder.spans.stream().filter(s -> "SERVER".equals(s.kind())).reduce((x, y) -> y).orElseThrow();
        assertThat(server.parentId()).isEqualTo("00f067aa0ba902b7");
        assertThat(server.name()).isEqualTo("GET /api/v1/projects");

        for (String bad : List.of("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", "nonsense", "01-abc")) {
            assertThat(getRaw("/api/v1/projects", a, bad).getHeader("X-Trace-Id"))
                    .isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736").matches("[0-9a-f]{32}");
        }
    }

    @Test
    void errorBodiesNameTheirTrace() throws Exception {
        Account a = signup("lost");
        MockHttpServletResponse r = getRaw("/api/v1/projects/" + Long.MAX_VALUE, a, null);
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(json.readTree(r.getContentAsString()).path("traceId").asText()).isEqualTo(r.getHeader("X-Trace-Id"));
    }

    @Test
    void aGenerationIsOneTraceFromRequestToEveryModelCallAndBuild() throws Exception {
        Account a = signup("spans");
        long id = createProject(a, "traced");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        recorder.spans.clear();

        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/projects/" + id + "/generate")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("prompt", "build"))))
                .andReturn().getResponse();
        String traceId = r.getHeader("X-Trace-Id");

        // The run row points back at the trace.
        assertThat(jdbc.queryForObject("SELECT trace_id FROM generation_runs WHERE project_id = ?", String.class, id))
                .isEqualTo(traceId);

        List<SpanRecord> mine = recorder.spans.stream().filter(s -> s.traceId().equals(traceId)).toList();
        SpanRecord server = one(mine, "POST /api/v1/projects/{id}/generate");
        SpanRecord run = one(mine, "agent.run");
        assertThat(server.kind()).isEqualTo("SERVER");
        assertThat(server.tags()).containsEntry("http.status_code", "200");
        assertThat(run.parentId()).isEqualTo(server.id());
        assertThat(run.tags()).containsEntry("run.status", "SUCCEEDED");

        List<SpanRecord> calls = mine.stream().filter(s -> s.name().equals("llm.chat")).toList();
        assertThat(calls).hasSize(2).allMatch(c -> c.parentId().equals(run.id()));
        assertThat(one(mine, "sandbox.build").parentId()).isEqualTo(run.id());
        // The build gate's span sits INSIDE the run's time window.
        SpanRecord build = one(mine, "sandbox.build");
        assertThat(build.timestamp()).isGreaterThanOrEqualTo(run.timestamp());
    }

    private static SpanRecord one(List<SpanRecord> spans, String name) {
        List<SpanRecord> found = spans.stream().filter(s -> s.name().equals(name)).toList();
        assertThat(found).as("spans named %s in %s", name, spans.stream().map(SpanRecord::name).toList()).hasSize(1);
        return found.get(0);
    }
}
