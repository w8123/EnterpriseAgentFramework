package com.enterprise.ai.runtime.agent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeAgentSkillCacheProductionGuardTest {

    @Test
    void productionRejectsBlankRelativeInvalidAndTemporaryCacheRoots() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"), "reachai-runtime", "skill-cache");

        assertThrows(IllegalStateException.class,
                () -> new RuntimeAgentSkillCacheProductionGuard(" ", production).validate());
        assertThrows(IllegalStateException.class,
                () -> new RuntimeAgentSkillCacheProductionGuard("relative/skill-cache", production).validate());
        assertThrows(IllegalStateException.class,
                () -> new RuntimeAgentSkillCacheProductionGuard("invalid\u0000path", production).validate());
        assertThrows(IllegalStateException.class,
                () -> new RuntimeAgentSkillCacheProductionGuard(temporary.toString(), production).validate());
    }

    @Test
    void productionAcceptsPersistentAbsoluteRootAndDevelopmentKeepsZeroConfigDefault() {
        MockEnvironment production = new MockEnvironment();
        production.setDefaultProfiles("production");
        Path persistent = Path.of(System.getProperty("user.home"), ".reachai", "runtime-skill-cache")
                .toAbsolutePath();
        MockEnvironment development = new MockEnvironment();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"), "reachai-runtime", "skill-cache");

        assertDoesNotThrow(
                () -> new RuntimeAgentSkillCacheProductionGuard(persistent.toString(), production).validate());
        assertDoesNotThrow(
                () -> new RuntimeAgentSkillCacheProductionGuard(temporary.toString(), development).validate());
    }
}
