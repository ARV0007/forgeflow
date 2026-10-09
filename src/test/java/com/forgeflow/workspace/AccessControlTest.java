package com.forgeflow.workspace;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access model end to end: real security chain, real database.
 *
 * The rule under test above all others: a stranger gets 404 - the same answer
 * as for a project that does not exist - while someone who can see the project
 * but lacks the permission gets 403.
 */
class AccessControlTest extends ApiTestSupport {

    private String project(long id) {
        return "/api/v1/projects/" + id;
    }

    @Test
    void strangerGetsTheSame404AsForAProjectThatDoesNotExist() {
        Account owner = signup("owner");
        Account stranger = signup("stranger");
        long id = createProject(owner, "private");

        JsonNode someoneElses = get(project(id), stranger, 404);
        JsonNode nonexistent = get(project(Long.MAX_VALUE), stranger, 404);

        // Identical bodies: probing ids must teach a stranger nothing.
        assertThat(someoneElses.path("detail").asText()).isEqualTo(nonexistent.path("detail").asText());
        post(project(id) + "/build", stranger, null, 404);
        get(project(id) + "/members", stranger, 404);
    }

    @Test
    void viewerCanReadButNotWriteOrAdminister() {
        Account owner = signup("owner");
        Account viewer = signup("viewer");
        long id = createProject(owner, "shared");
        post(project(id) + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        assertThat(get(project(id), viewer, 200).path("role").asText()).isEqualTo("VIEWER");
        get(project(id) + "/files", viewer, 200);
        assertThat(get(project(id) + "/members", viewer, 200)).hasSize(2);

        // Knows it exists, so 403 - not 404.
        post(project(id) + "/build", viewer, null, 403);
        post(project(id) + "/preview", viewer, null, 403);
        call(HttpMethod.PUT, project(id), viewer, Map.of("name", "renamed"), 403);
        call(HttpMethod.DELETE, project(id), viewer, null, 403);
    }

    @Test
    void editorCanWriteButNotAdminister() {
        Account owner = signup("owner");
        Account editor = signup("editor");
        Account third = signup("third");
        long id = createProject(owner, "team");
        post(project(id) + "/members", owner, Map.of("email", editor.email(), "role", "EDITOR"), 201);

        post(project(id) + "/build", editor, null, 200);

        call(HttpMethod.PUT, project(id), editor, Map.of("name", "renamed"), 403);
        call(HttpMethod.DELETE, project(id), editor, null, 403);
        post(project(id) + "/members", editor, Map.of("email", third.email(), "role", "VIEWER"), 403);
    }

    @Test
    void sharedProjectsAppearInTheMembersListWithTheirRole() {
        Account owner = signup("owner");
        Account member = signup("member");
        long id = createProject(owner, "listed");
        post(project(id) + "/members", owner, Map.of("email", member.email(), "role", "EDITOR"), 201);

        JsonNode mine = get("/api/v1/projects", member, 200);
        JsonNode row = null;
        for (JsonNode p : mine) {
            if (p.path("id").asLong() == id) {
                row = p;
            }
        }
        assertThat(row).as("shared project in member's list").isNotNull();
        assertThat(row.path("role").asText()).isEqualTo("EDITOR");
        assertThat(row.path("ownerId").asLong()).isEqualTo(owner.id());
    }

    @Test
    void ownerManagesMembership() {
        Account owner = signup("owner");
        Account member = signup("member");
        long id = createProject(owner, "managed");
        String members = project(id) + "/members";

        post(members, owner, Map.of("email", member.email(), "role", "VIEWER"), 201);
        post(members, owner, Map.of("email", member.email(), "role", "EDITOR"), 409);     // already in
        post(members, owner, Map.of("email", owner.email(), "role", "EDITOR"), 409);      // owns it
        post(members, owner, Map.of("email", "nobody-" + System.nanoTime() + "@x.dev", "role", "VIEWER"), 404);
        post(members, owner, Map.of("email", member.email(), "role", "OWNER"), 403);      // not grantable

        JsonNode promoted = call(HttpMethod.PATCH, members + "/" + member.id(), owner, Map.of("role", "EDITOR"), 200);
        assertThat(promoted.path("role").asText()).isEqualTo("EDITOR");
        post(project(id) + "/build", member, null, 200);

        call(HttpMethod.DELETE, members + "/" + member.id(), owner, null, 204);
        get(project(id), member, 404);   // back to being a stranger
    }

    @Test
    void aMemberCanLeaveWithoutAnyonesPermission() {
        Account owner = signup("owner");
        Account member = signup("member");
        long id = createProject(owner, "leavable");
        post(project(id) + "/members", owner, Map.of("email", member.email(), "role", "VIEWER"), 201);

        call(HttpMethod.DELETE, project(id) + "/members/" + member.id(), member, null, 204);
        get(project(id), member, 404);
    }

    @Test
    void aPublicProjectIsReadableByAnyoneSignedInButWritableByNoOneNew() {
        Account owner = signup("owner");
        Account passerby = signup("passerby");
        long id = createProject(owner, "open");

        get(project(id), passerby, 404);
        call(HttpMethod.PUT, project(id), owner, Map.of("name", "open", "isPublic", true), 200);

        assertThat(get(project(id), passerby, 200).path("role").asText()).isEqualTo("PUBLIC");
        post(project(id) + "/build", passerby, null, 403);

        // Readable by link, not pushed into everyone's sidebar.
        for (JsonNode p : get("/api/v1/projects", passerby, 200)) {
            assertThat(p.path("id").asLong()).isNotEqualTo(id);
        }
    }

    @Test
    void invitesFindPeopleRegardlessOfEmailCase() {
        Account owner = signup("owner");
        Account member = signup("Mixed.Case");
        long id = createProject(owner, "cased");

        post(project(id) + "/members", owner, Map.of("email", member.email().toUpperCase(), "role", "VIEWER"), 201);
        get(project(id), member, 200);
    }

    @Test
    void anonymousCallersAreUnauthorisedNotForbidden() {
        get("/api/v1/projects", null, 401);
        get("/api/v1/me", null, 401);
    }
}
