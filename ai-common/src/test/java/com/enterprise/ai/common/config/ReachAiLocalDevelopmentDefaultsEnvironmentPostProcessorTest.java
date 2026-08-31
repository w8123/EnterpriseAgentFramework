package com.enterprise.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessorTest {

    private final ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor processor =
            new ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor();

    @Test
    void suppliesZeroConfigurationDefaultsToTheFiveLocalServices() {
        MockEnvironment model = localEnvironment("reachai-model-service");
        processor.postProcessEnvironment(model, null);
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_MODEL_CREDENTIAL_SECRET,
                model.getProperty("model.credential-secret"));

        MockEnvironment runtime = localEnvironment("reachai-runtime-service");
        processor.postProcessEnvironment(runtime, null);
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_WORKFLOW_CREDENTIAL_SECRET,
                runtime.getProperty("agent.workflow-credential-secret"));

        MockEnvironment control = localEnvironment("reachai-control-service");
        processor.postProcessEnvironment(control, null);
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_EMBED_TOKEN_SECRET,
                control.getProperty("eaf.embed-token.secret"));
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_AI_CODING_TASK_PEPPER,
                control.getProperty("reachai.ai-coding-task.secret-pepper"));

        MockEnvironment capability = localEnvironment("reachai-capability-service");
        processor.postProcessEnvironment(capability, null);
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_INTERNAL_SERVICE_SECRET,
                capability.getProperty("reachai.internal.service-secret"));

        MockEnvironment knowledge = localEnvironment("reachai-knowledge-service");
        processor.postProcessEnvironment(knowledge, null);
        assertEquals(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.DEVELOPMENT_PERSONAL_MEMORY_IDENTITY_SECRET,
                knowledge.getProperty("reachai.personal-memory.index-identity-secret"));
    }

    @Test
    void explicitConfigurationAlwaysWins() {
        MockEnvironment environment = localEnvironment("reachai-control-service")
                .withProperty("eaf.embed-token.secret", "explicit-embed-secret")
                .withProperty("reachai.ai-coding-task.secret-pepper", "explicit-ai-coding-pepper");

        processor.postProcessEnvironment(environment, null);

        assertEquals("explicit-embed-secret", environment.getProperty("eaf.embed-token.secret"));
        assertEquals(
                "explicit-ai-coding-pepper",
                environment.getProperty("reachai.ai-coding-task.secret-pepper"));
    }

    @Test
    void productionProfilesNeverReceiveDevelopmentDefaults() {
        MockEnvironment environment = localEnvironment("reachai-model-service");
        environment.setActiveProfiles("prod");

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("model.credential-secret"));
    }

    @Test
    void kubernetesNeverReceivesDevelopmentDefaults() {
        MockEnvironment environment = localEnvironment("reachai-runtime-service")
                .withProperty("KUBERNETES_SERVICE_HOST", "10.96.0.1");

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("agent.workflow-credential-secret"));
        assertNull(environment.getProperty("reachai.internal.service-secret"));
    }

    @Test
    void localDefaultsCanBeDisabledForSecurityValidation() {
        MockEnvironment environment = localEnvironment("reachai-control-service")
                .withProperty("reachai.local-development-defaults.enabled", "false");

        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("eaf.embed-token.secret"));
        assertNull(environment.getProperty("reachai.ai-coding-task.secret-pepper"));
    }

    private static MockEnvironment localEnvironment(String applicationName) {
        return new MockEnvironment().withProperty("spring.application.name", applicationName);
    }
}
