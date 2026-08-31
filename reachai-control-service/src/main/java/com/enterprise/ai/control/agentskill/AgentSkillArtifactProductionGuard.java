package com.enterprise.ai.control.agentskill;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

/** Prevents production catalogs from pointing immutable Skill versions at an ephemeral temp directory. */
@Component
public class AgentSkillArtifactProductionGuard {

    private final String configuredRoot;
    private final Environment environment;

    public AgentSkillArtifactProductionGuard(
            @Value("${reachai.skill.artifact-root:${java.io.tmpdir}/reachai-control/skill-artifacts}")
            String configuredRoot,
            Environment environment) {
        this.configuredRoot = configuredRoot;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!isProduction(environment)) return;
        if (!StringUtils.hasText(configuredRoot)) {
            throw new IllegalStateException(
                    "reachai.skill.artifact-root must be configured to a persistent absolute path in production");
        }
        Path configured = Path.of(configuredRoot.trim());
        if (!configured.isAbsolute()) {
            throw new IllegalStateException(
                    "reachai.skill.artifact-root must be an absolute path in production");
        }
        Path artifactRoot = configured.normalize();
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (artifactRoot.startsWith(temporaryRoot)) {
            throw new IllegalStateException(
                    "reachai.skill.artifact-root must not use the operating-system temp directory in production");
        }
    }

    private static boolean isProduction(Environment environment) {
        if (environment == null) return false;
        return Stream.concat(
                        Arrays.stream(environment.getActiveProfiles()),
                        Arrays.stream(environment.getDefaultProfiles()))
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }
}
