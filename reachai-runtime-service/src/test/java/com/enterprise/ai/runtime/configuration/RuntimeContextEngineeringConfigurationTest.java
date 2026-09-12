package com.enterprise.ai.runtime.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextEngineeringConfigurationTest {

    @Test
    void bindsArtifactMaintenanceSettingsIndependentlyOfTheSupervisorAndMasterSwitch() {
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeContextEngineeringConfiguration.class)
                .withPropertyValues(
                        "reachai.runtime.context-engineering.enabled=false",
                        "reachai.runtime.context-engineering.compaction-enabled=true",
                        "reachai.runtime.context-engineering.tool-result-offload-enabled=true",
                        "reachai.runtime.context-engineering.tool-result-max-chars=10000",
                        "reachai.runtime.context-engineering.artifact-read-chunk-chars=6000",
                        "reachai.runtime.context-engineering.artifact-retention-hours=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuntimeContextEngineeringProperties.class);
                    RuntimeContextEngineeringProperties properties =
                            context.getBean(RuntimeContextEngineeringProperties.class);
                    assertThat(properties.compactionActive()).isFalse();
                    assertThat(properties.toolResultOffloadActive()).isFalse();
                    assertThat(properties.toolResultArtifactStoreConfigured()).isTrue();
                    assertThat(properties.artifactReadChunkChars()).isEqualTo(6000);
                    assertThat(properties.artifactRetentionHours()).isEqualTo(720);
                });
    }
}
