package com.forgeflow.workspace;

import org.junit.jupiter.api.Test;

import static com.forgeflow.workspace.Permission.ADMIN;
import static com.forgeflow.workspace.Permission.READ;
import static com.forgeflow.workspace.Permission.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/** The whole permission model is one table. This pins every cell of it. */
class ProjectRoleTest {

    @Test
    void ownerCanDoEverything() {
        assertThat(ProjectRole.OWNER.allows(READ)).isTrue();
        assertThat(ProjectRole.OWNER.allows(WRITE)).isTrue();
        assertThat(ProjectRole.OWNER.allows(ADMIN)).isTrue();
    }

    @Test
    void editorWritesButDoesNotAdminister() {
        assertThat(ProjectRole.EDITOR.allows(READ)).isTrue();
        assertThat(ProjectRole.EDITOR.allows(WRITE)).isTrue();
        assertThat(ProjectRole.EDITOR.allows(ADMIN)).isFalse();
    }

    @Test
    void viewerAndPublicOnlyRead() {
        for (ProjectRole r : new ProjectRole[]{ProjectRole.VIEWER, ProjectRole.PUBLIC}) {
            assertThat(r.allows(READ)).as(r.name()).isTrue();
            assertThat(r.allows(WRITE)).as(r.name()).isFalse();
            assertThat(r.allows(ADMIN)).as(r.name()).isFalse();
        }
    }

    @Test
    void onlyEditorAndViewerCanBeHandedOut() {
        assertThat(ProjectRole.EDITOR.isAssignable()).isTrue();
        assertThat(ProjectRole.VIEWER.isAssignable()).isTrue();
        assertThat(ProjectRole.OWNER.isAssignable()).isFalse();
        assertThat(ProjectRole.PUBLIC.isAssignable()).isFalse();
    }
}
