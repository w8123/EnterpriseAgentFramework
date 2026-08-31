package com.enterprise.ai.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Supplies public, development-only cryptographic defaults for the five ReachAI services.
 * Explicit configuration always wins. Production profiles and Kubernetes never receive
 * these defaults, so their existing fail-fast validation remains effective.
 */
public final class ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "reachAiLocalDevelopmentDefaults";
    static final String DEVELOPMENT_MODEL_CREDENTIAL_SECRET = "dev-only-change-me-please-32-bytes";
    static final String DEVELOPMENT_WORKFLOW_CREDENTIAL_SECRET = "dev-only-change-me-please-32-bytes";
    static final String DEVELOPMENT_EMBED_TOKEN_SECRET = "dev-only-change-me-reachai-embed-token-secret";
    static final String DEVELOPMENT_AI_CODING_TASK_PEPPER = "reachai-local-dev-ai-coding-task-pepper";
    static final String DEVELOPMENT_INTERNAL_SERVICE_SECRET =
            "reachai-local-dev-public-internal-service-signing-key-v1";
    static final String DEVELOPMENT_PERSONAL_MEMORY_IDENTITY_SECRET =
            "reachai-local-dev-public-personal-memory-identity-key-v1";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String applicationName = environment.getProperty("spring.application.name", "").trim();
        if (!localDefaultsEnabled(environment) || isProductionLike(environment)) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        switch (applicationName) {
            case "reachai-model-service" -> addIfMissing(
                    environment, defaults, "model.credential-secret", DEVELOPMENT_MODEL_CREDENTIAL_SECRET);
            case "reachai-runtime-service" -> {
                addIfMissing(environment, defaults, "agent.workflow-credential-secret",
                        DEVELOPMENT_WORKFLOW_CREDENTIAL_SECRET);
                addIfMissing(environment, defaults, "reachai.internal.service-secret",
                        DEVELOPMENT_INTERNAL_SERVICE_SECRET);
            }
            case "reachai-control-service" -> {
                addIfMissing(environment, defaults, "eaf.embed-token.secret", DEVELOPMENT_EMBED_TOKEN_SECRET);
                addIfMissing(environment, defaults, "reachai.ai-coding-task.secret-pepper",
                        DEVELOPMENT_AI_CODING_TASK_PEPPER);
                addIfMissing(environment, defaults, "reachai.internal.service-secret",
                        DEVELOPMENT_INTERNAL_SERVICE_SECRET);
                addIfMissing(environment, defaults, "reachai.context.personal-memory.index-identity-secret",
                        DEVELOPMENT_PERSONAL_MEMORY_IDENTITY_SECRET);
            }
            case "reachai-capability-service" -> addIfMissing(
                    environment, defaults, "reachai.internal.service-secret",
                    DEVELOPMENT_INTERNAL_SERVICE_SECRET);
            case "reachai-knowledge-service" -> {
                addIfMissing(environment, defaults, "reachai.internal.service-secret",
                        DEVELOPMENT_INTERNAL_SERVICE_SECRET);
                addIfMissing(environment, defaults, "reachai.personal-memory.index-identity-secret",
                        DEVELOPMENT_PERSONAL_MEMORY_IDENTITY_SECRET);
            }
            default -> {
                return;
            }
        }

        if (!defaults.isEmpty()) {
            environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static void addIfMissing(
            ConfigurableEnvironment environment,
            Map<String, Object> defaults,
            String property,
            String developmentValue) {
        if (!StringUtils.hasText(environment.getProperty(property))) {
            defaults.put(property, developmentValue);
        }
    }

    private static boolean localDefaultsEnabled(ConfigurableEnvironment environment) {
        return environment.getProperty("reachai.local-development-defaults.enabled", Boolean.class, true);
    }

    private static boolean isProductionLike(ConfigurableEnvironment environment) {
        if (StringUtils.hasText(environment.getProperty("KUBERNETES_SERVICE_HOST"))) {
            return true;
        }
        return Stream.concat(
                        Arrays.stream(environment.getActiveProfiles()),
                        Arrays.stream(environment.getDefaultProfiles()))
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }
}
