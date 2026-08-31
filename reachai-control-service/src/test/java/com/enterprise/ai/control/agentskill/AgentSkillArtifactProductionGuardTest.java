package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentSkillArtifactProductionGuardTest {

    @Test
    void productionRejectsRelativeAndTemporaryArtifactRoots() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"), "reachai-control", "skill-artifacts");

        assertThrows(IllegalStateException.class,
                () -> new AgentSkillArtifactProductionGuard("relative/skill-artifacts", production).validate());
        assertThrows(IllegalStateException.class,
                () -> new AgentSkillArtifactProductionGuard(temporary.toString(), production).validate());
    }

    @Test
    void productionAcceptsPersistentAbsoluteRootAndDevelopmentKeepsZeroConfigDefault() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        Path persistent = Path.of(System.getProperty("user.home"), ".reachai", "skill-artifacts")
                .toAbsolutePath();
        MockEnvironment development = new MockEnvironment();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"), "reachai-control", "skill-artifacts");

        assertDoesNotThrow(
                () -> new AgentSkillArtifactProductionGuard(persistent.toString(), production).validate());
        assertDoesNotThrow(
                () -> new AgentSkillArtifactProductionGuard(temporary.toString(), development).validate());
    }
}
