package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedSandboxPolicyRegistryTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void resolvesOnlyOperatorConfiguredImmutableSourcesAndFixedArgv() throws Exception {
        ManagedSandboxPolicyRegistry registry = registry(
                """
                        {"PROJECT_A":{"type":"GIT","repositoryUrl":"https://git.example.com/acme/app.git",
                        "revision":"%s","credentialSecretName":"git-project-a"}}
                        """.formatted(SHA),
                """
                        {
                          "PROJECT_DEFAULT":[{"name":"unit","argv":["mvn","test"],"timeoutMs":60000}],
                          "PROJECT_A:PROJECT_DEFAULT":[{"name":"scoped","argv":["npm","test","--","--runInBand"],"timeoutMs":120000}]
                        }
                        """);

        ManagedSandboxPolicyRegistry.GitWorkspaceSource source =
                registry.requireWorkspaceSource("project_a");
        assertThat(source.repositoryUrl()).isEqualTo("https://git.example.com/acme/app.git");
        assertThat(source.revision()).isEqualTo(SHA);
        assertThat(source.credentialSecretName()).isEqualTo("git-project-a");

        List<ManagedSandboxPolicyRegistry.AcceptanceCommand> scoped =
                new ObjectMapper().readerForListOf(ManagedSandboxPolicyRegistry.AcceptanceCommand.class)
                        .readValue(registry.acceptanceCommandsJson("PROJECT_A", "PROJECT_DEFAULT"));
        assertThat(scoped).singleElement().satisfies(command -> {
            assertThat(command.name()).isEqualTo("scoped");
            assertThat(command.argv()).containsExactly("npm", "test", "--", "--runInBand");
            assertThat(command.timeoutMs()).isEqualTo(120000);
        });

        List<ManagedSandboxPolicyRegistry.AcceptanceCommand> fallback =
                registry.requireAcceptanceProfile("project_default");
        assertThat(fallback).singleElement().extracting(
                ManagedSandboxPolicyRegistry.AcceptanceCommand::argv)
                .isEqualTo(List.of("mvn", "test"));
    }

    @Test
    void rejectsCredentialBearingOrNonHttpsRepositoryUrls() {
        assertThatThrownBy(() -> registry(
                source("https://user:password@git.example.com/acme/app.git", SHA), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Managed repository URL must be credential-free HTTPS");

        assertThatThrownBy(() -> registry(
                source("ssh://git@git.example.com/acme/app.git", SHA), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Managed repository URL must be credential-free HTTPS");
    }

    @Test
    void rejectsMutableRevisionsAndUnboundedAcceptanceCommands() {
        assertThatThrownBy(() -> registry(
                source("https://git.example.com/acme/app.git", "main"), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Managed workspace revision must be an immutable commit SHA");

        assertThatThrownBy(() -> registry("{}", """
                {"PROJECT_DEFAULT":[{"name":"bad","argv":["mvn","test"],"timeoutMs":999}]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Managed acceptance timeout is invalid");
    }

    @Test
    void failsClosedForUnknownProjectsAndProfiles() {
        ManagedSandboxPolicyRegistry registry = registry("{}", "{}");

        assertThatThrownBy(() -> registry.requireWorkspaceSource("PROJECT_A"))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_WORKSPACE_SOURCE_UNAVAILABLE"));
        assertThatThrownBy(() -> registry.requireAcceptanceProfile("PROJECT_DEFAULT"))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_ACCEPTANCE_PROFILE_UNAVAILABLE"));
    }

    private ManagedSandboxPolicyRegistry registry(String sources, String profiles) {
        return new ManagedSandboxPolicyRegistry(new ObjectMapper(), sources, profiles);
    }

    private String source(String repositoryUrl, String revision) {
        return """
                {"PROJECT_A":{"type":"GIT","repositoryUrl":"%s","revision":"%s"}}
                """.formatted(repositoryUrl, revision);
    }
}
