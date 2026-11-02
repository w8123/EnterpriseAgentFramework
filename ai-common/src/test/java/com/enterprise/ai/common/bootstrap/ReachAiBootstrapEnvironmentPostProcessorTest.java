package com.enterprise.ai.common.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReachAiBootstrapEnvironmentPostProcessorTest {

    private final ReachAiBootstrapEnvironmentPostProcessor processor =
            new ReachAiBootstrapEnvironmentPostProcessor(new DeferredLogs());

    @Test
    void configFileFillsPlaceholdersButProcessEnvironmentWins(@TempDir Path home) throws Exception {
        Path config = home.resolve("reachai.env");
        Files.writeString(config, "AI_MYSQL_PASSWORD=from-file\nAI_MYSQL_HOST=file-host\n");
        MockEnvironment environment = service("reachai-model-service", home)
                .withProperty("REACHAI_CONFIG_FILE", config.toString())
                .withProperty("spring.datasource.password", "${AI_MYSQL_PASSWORD}")
                .withProperty("db.host", "${AI_MYSQL_HOST:localhost}");
        systemEnvironment(environment, Map.of("AI_MYSQL_HOST", "env-host"));

        processor.postProcessEnvironment(environment, null);

        assertEquals("from-file", environment.getProperty("spring.datasource.password"));
        assertEquals("env-host", environment.getProperty("db.host"));
    }

    @Test
    void configFileUsesEnvironmentStyleNamesForRelaxedProperties(@TempDir Path home) throws Exception {
        Path config = home.resolve("reachai.env");
        Files.writeString(config, "EAF_EMBED_TOKEN_SECRET=from-file-embed\n");
        MockEnvironment environment = service("reachai-control-service", home)
                .withProperty("REACHAI_CONFIG_FILE", config.toString());

        processor.postProcessEnvironment(environment, null);

        assertEquals("from-file-embed", environment.getProperty("eaf.embed-token.secret"));
    }

    @Test
    void retiredEnvironmentVariablesStillApplyToTheirProperty(@TempDir Path home) {
        MockEnvironment environment = service("reachai-runtime-service", home)
                .withProperty("reachai.runtime.session-memory.max-context-messages", "40");
        systemEnvironment(environment, Map.of("RUNTIME_SESSION_MEMORY_MAX_CONTEXT_MESSAGES", "12"));

        processor.postProcessEnvironment(environment, null);

        assertEquals("12", environment.getProperty("reachai.runtime.session-memory.max-context-messages"));
    }

    @Test
    void generatedSecretsFillEmptyPlaceholdersAndExplicitValuesWin(@TempDir Path home) throws Exception {
        GeneratedSecrets.createIfAbsent(home.resolve(ReachAiHome.SECRETS_FILE_NAME));
        Map<String, Object> generated = GeneratedSecrets.load(home.resolve(ReachAiHome.SECRETS_FILE_NAME));
        MockEnvironment environment = service("reachai-control-service", home)
                .withProperty("reachai.internal.service-secret", "")
                .withProperty("eaf.embed-token.secret", "explicit-embed");

        processor.postProcessEnvironment(environment, null);

        assertEquals(generated.get("reachai.internal.service-secret"),
                environment.getProperty("reachai.internal.service-secret"));
        assertEquals("explicit-embed", environment.getProperty("eaf.embed-token.secret"));
    }

    @Test
    void kubernetesNeverUsesGeneratedSecrets(@TempDir Path home) throws Exception {
        GeneratedSecrets.createIfAbsent(home.resolve(ReachAiHome.SECRETS_FILE_NAME));
        MockEnvironment environment = service("reachai-model-service", home)
                .withProperty("KUBERNETES_SERVICE_HOST", "10.96.0.1");

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("model.credential-secret"));
    }

    @Test
    void doesNothingWithoutASecretsFileOrMysqlDatabase(@TempDir Path home) {
        MockEnvironment environment = service("reachai-model-service", home)
                .withProperty("spring.datasource.url", "jdbc:h2:mem:test");

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("model.credential-secret"));
        assertFalse(Files.exists(home.resolve(ReachAiHome.SECRETS_FILE_NAME)));
    }

    @Test
    void ignoresApplicationsThatAreNotReachAiServices(@TempDir Path home) throws Exception {
        GeneratedSecrets.createIfAbsent(home.resolve(ReachAiHome.SECRETS_FILE_NAME));
        MockEnvironment environment = service("some-test-app", home);

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("reachai.internal.service-secret"));
    }

    private static MockEnvironment service(String name, Path home) {
        Path emptyConfig = home.resolve("empty.env");
        try {
            Files.writeString(emptyConfig, "");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return new MockEnvironment()
                .withProperty("spring.application.name", name)
                .withProperty("REACHAI_HOME", home.toString())
                .withProperty("REACHAI_CONFIG_FILE", emptyConfig.toString());
    }

    private static void systemEnvironment(MockEnvironment environment, Map<String, Object> values) {
        environment.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(values)));
    }
}
