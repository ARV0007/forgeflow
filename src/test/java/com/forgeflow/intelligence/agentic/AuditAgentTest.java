package com.forgeflow.intelligence.agentic;

import com.forgeflow.intelligence.audit.AuditAgent;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.read;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** The audit agent: read-only, files findings, each one fixable in one click; nightly for projects that opt in. */
class AuditAgentTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    @Autowired
    AuditAgent audits;

    @Autowired
    JdbcTemplate jdbc;

    private long site(Account a) {
        long id = createProject(a, "audit");
        files.write(id, "index.html", INDEX.replace("<h1>Hello</h1>", "<h1>Hello</h1><div class=\"btn\" onclick=\"go()\">Go</div>"), a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS + "\nfunction show(q) { out.innerHTML = q; }", a.id());
        return id;
    }

    static ToolCall issue(String severity, String category, String file, String problem, String suggestion) {
        Map<String, Object> args = new HashMap<>(Map.of("severity", severity, "category", category, "problem", problem));
        if (file != null) {
            args.put("file", file);
        }
        if (suggestion != null) {
            args.put("suggestion", suggestion);
        }
        return new ToolCall("report_issue", args);
    }

    private String auditsOf(long id) {
        return "/api/v1/projects/" + id + "/audits";
    }

    @Test
    void theAuditorReadsReportsAndCannotWrite() {
        Account a = signup("audit");
        long id = site(a);
        llm.then(calls(new ToolCall("list_files", Map.of()), read("index.html"), read("app.js")))
           .then(calls(issue("high", "security", "app.js", "show() puts user input into innerHTML", "Use textContent"),
                        issue("medium", "accessibility", "index.html", "The Go button is a div: no keyboard access", "Use a <button>"),
                        issue("medium", "accessibility", "index.html", "The Go button is a div: no keyboard access", "dup"),
                        issue("urgent", "bug", "app.js", "bad severity", null),
                        write("app.js", "// the auditor tries to fix it itself")))
           .then(calls(finish("Two problems: one security, one accessibility.")));

        JsonNode audit = post(auditsOf(id), a, null, 202);

        assertThat(audit.path("status").asText()).isEqualTo("DONE");
        assertThat(audit.path("summary").asText()).isEqualTo("Two problems: one security, one accessibility.");
        JsonNode findings = audit.path("findings");
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).path("severity").asText()).isEqualTo("high");          // most severe first
        assertThat(findings.get(0).path("problem").asText()).contains("innerHTML");
        var out = llm.seen().get(2).get(llm.seen().get(2).size() - 1).toolResults();
        assertThat(out.get(2).output()).isEqualTo("Already reported.");
        assertThat(out.get(3).output()).contains("severity must be high, medium or low");
        assertThat(out.get(4).output()).contains("can only read and report");
        assertThat(files.read(id, "app.js").orElseThrow()).contains("innerHTML = q");       // untouched
        // Its own instructions and only read tools + report_issue + finish.
        assertThat(llm.systemPrompts().get(0)).startsWith("You audit a small web project");
        assertThat(audit.path("tokens").asInt()).isEqualTo(360);
        assertThat(get(auditsOf(id) + "/latest", a, 200).path("id").asLong()).isEqualTo(audit.path("id").asLong());
    }

    @Test
    void aFindingIsFixedWithOneClickOrDismissed() {
        Account a = signup("audit-fix");
        long id = site(a);
        llm.then(calls(issue("high", "security", "app.js", "show() puts user input into innerHTML", "Use textContent"),
                        issue("low", "ux", null, "No empty state", null)))
           .then(calls(finish("Two.")));
        JsonNode audit = post(auditsOf(id), a, null, 202);
        long security = audit.path("findings").get(0).path("id").asLong();
        long ux = audit.path("findings").get(1).path("id").asLong();

        llm.reset();
        llm.then(calls(write("app.js", JS + "\nfunction show(q) { out.textContent = q; }"))).then(calls(finish("Used textContent.")));
        JsonNode fixed = post(auditsOf(id) + "/findings/" + security + "/fix", a, null, 200);

        assertThat(llm.seen().get(0).get(0).text()).startsWith("Fix this security issue found by an audit in app.js: "
                + "show() puts user input into innerHTML").contains("Suggested fix: Use textContent");
        assertThat(fixed.path("finding").path("status").asText()).isEqualTo("FIXED");
        assertThat(fixed.path("finding").path("fixRunId").asLong()).isEqualTo(fixed.path("run").path("runId").asLong());
        post(auditsOf(id) + "/findings/" + security + "/fix", a, null, 400);               // not twice

        assertThat(post(auditsOf(id) + "/findings/" + ux + "/dismiss", a, null, 200).path("status").asText()).isEqualTo("DISMISSED");
        post(auditsOf(id) + "/findings/" + ux + "/dismiss", a, null, 400);
    }

    @Test
    void viewersSeeAuditsButCantStartOrActOnThem() {
        Account owner = signup("audit-owner");
        Account viewer = signup("audit-viewer");
        long id = site(owner);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        get(auditsOf(id) + "/latest", viewer, 204);
        post(auditsOf(id), viewer, null, 403);
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/audit-settings", viewer, Map.of("nightly", true), 403);

        llm.then(calls(issue("low", "ux", null, "No empty state", null))).then(calls(finish("One.")));
        long f = post(auditsOf(id), owner, null, 202).path("findings").get(0).path("id").asLong();
        assertThat(get(auditsOf(id) + "/latest", viewer, 200).path("findings")).hasSize(1);
        post(auditsOf(id) + "/findings/" + f + "/dismiss", viewer, null, 403);
    }

    @Test
    void nightlyAuditsRunOnlyForProjectsThatOptedInAndChanged() {
        Account a = signup("audit-nightly");
        long optedIn = site(a);
        long notOptedIn = site(a);
        call(HttpMethod.PUT, "/api/v1/projects/" + optedIn + "/audit-settings", a, Map.of("nightly", true), 200);
        // Other tests' projects may be opted in too: make this the only one due.
        jdbc.update("UPDATE audit_settings SET nightly = false WHERE project_id <> ?", optedIn);

        llm.then(calls(issue("low", "ux", null, "No empty state", null))).then(calls(finish("One.")));
        assertThat(audits.auditChangedProjects()).isEqualTo(1);
        JsonNode first = get(auditsOf(optedIn) + "/latest", a, 200);
        assertThat(first.path("trigger").asText()).isEqualTo("NIGHTLY");
        get(auditsOf(notOptedIn) + "/latest", a, 204);

        // Nothing changed since: the next night skips it. A change makes it due again.
        assertThat(audits.auditChangedProjects()).isZero();
        files.write(optedIn, "app.js", JS + "\n// changed", a.id());
        llm.then(calls(finish("Clean.")));
        assertThat(audits.auditChangedProjects()).isEqualTo(1);
    }
}
