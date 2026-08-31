package com.enterprise.ai.runtime.managed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedExecutorPropertiesTest {

    @Test
    void defaultsRemainFailClosedWhenNoProjectsOrCapacityAreConfigured() {
        ManagedExecutorProperties properties = properties(false, "", "ANALYZE_READONLY", 0);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.autoRouteEnabled()).isFalse();
        assertThat(properties.maxClusterConcurrency()).isZero();
        assertThat(properties.allowsProject("project-a")).isFalse();
        assertThat(properties.allowsProfile("ANALYZE_READONLY")).isTrue();
        assertThat(properties.allowsProfile("WORKSPACE_PATCH")).isFalse();
    }

    @Test
    void explicitProjectAndProfileAllowlistsAreCaseInsensitive() {
        ManagedExecutorProperties properties = properties(
                true, "project-a, PROJECT_B", "analyze_readonly,workspace_patch", 2);

        assertThat(properties.allowsProject("PROJECT-A")).isTrue();
        assertThat(properties.allowsProject("project_b")).isTrue();
        assertThat(properties.allowsProject("project-c")).isFalse();
        assertThat(properties.allowsProfile("workspace_patch")).isTrue();
    }

    static ManagedExecutorProperties properties(boolean enabled,
                                                String projects,
                                                String profiles,
                                                int concurrency) {
        return new ManagedExecutorProperties(
                enabled, false, projects, profiles, concurrency,
                14_400, 90, 65_535, 100, 262_144, 1_048_576);
    }
}
