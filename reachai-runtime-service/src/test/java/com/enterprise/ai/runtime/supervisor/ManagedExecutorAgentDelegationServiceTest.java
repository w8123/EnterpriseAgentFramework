package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.managed.ManagedArtifactReadService;
import com.enterprise.ai.runtime.managed.ManagedExecutionService;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreatedView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ExecutionView;
import com.enterprise.ai.runtime.managed.ManagedExecutorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedExecutorAgentDelegationServiceTest {

    private final ManagedExecutionService executionService = mock(ManagedExecutionService.class);
    private final ManagedArtifactReadService artifactReadService = mock(ManagedArtifactReadService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ManagedExecutorAgentDelegationService service;

    @BeforeEach
    void setUp() {
        service = service(false);
    }

    @Test
    void resolvesOnlyAnExplicitStrictPublishedVersionPolicyForATrustedInteractiveUser() {
        var policy = service.resolvePolicy(config(validPolicy(false)), identity());

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.autoRouteEnabled()).isFalse();
        assertThat(policy.allowedTools()).containsExactlyInAnyOrder(
                ManagedExecutorAgentDelegationService.START_TOOL,
                ManagedExecutorAgentDelegationService.STATUS_TOOL,
                ManagedExecutorAgentDelegationService.READ_RESULT_TOOL);
        assertThat(policy.sandboxProfile()).isEqualTo("ANALYZE_READONLY");
        assertThat(policy.maxWallTimeSeconds()).isEqualTo(900);
        assertThat(policy.approvalTimeoutSeconds()).isEqualTo(300);

        RuntimeAgentConfigSnapshot draft = config(validPolicy(false)).toBuilder()
                .status("DRAFT")
                .build();
        assertThat(service.resolvePolicy(draft, identity()).enabled()).isFalse();
        assertThat(service.resolvePolicy(config("{\"managedExecutor\":{\"enabled\":true,\"unknown\":1}}"),
                identity()).enabled()).isFalse();
        assertThat(service.resolvePolicy(config("not-json"), identity()).enabled()).isFalse();
        assertThat(service.resolvePolicy(config(validPolicy(false)),
                WorkflowExecutionIdentity.fromA2aRemoteAgent("tenant-a", 7L, "QMS", "remote"))
                .enabled()).isFalse();
    }

    @Test
    void hidesStartWithoutExplicitUserSelectionWhenAutomaticRoutingIsOff() {
        var policy = service.resolvePolicy(config(validPolicy(false)), identity());

        assertThat(service.availableTools(policy, Map.of()))
                .containsExactlyInAnyOrder(
                        ManagedExecutorAgentDelegationService.STATUS_TOOL,
                        ManagedExecutorAgentDelegationService.READ_RESULT_TOOL);
        assertThat(service.availableTools(policy, Map.of("managedExecutorRequested", true)))
                .containsExactlyInAnyOrderElementsOf(policy.allowedTools());
        assertThatThrownBy(() -> service.invoke(
                ManagedExecutorAgentDelegationService.START_TOOL,
                policy, config(validPolicy(false)), identity(), Map.of(),
                Map.of("objective", "inspect the project"), "trace-1"))
                .isInstanceOfSatisfying(
                        ManagedExecutorAgentDelegationService.DelegationException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_EXECUTOR_EXPLICIT_REQUEST_REQUIRED"));
        verify(executionService, never()).create(any(), any(), any());
    }

    @Test
    void automaticRoutingRequiresBothGlobalAndVersionSwitches() {
        var globallyDisabled = service.resolvePolicy(config(validPolicy(true)), identity());
        assertThat(globallyDisabled.autoRouteEnabled()).isFalse();

        ManagedExecutorAgentDelegationService autoService = service(true);
        var versionDisabled = autoService.resolvePolicy(config(validPolicy(false)), identity());
        var enabled = autoService.resolvePolicy(config(validPolicy(true)), identity());

        assertThat(versionDisabled.autoRouteEnabled()).isFalse();
        assertThat(enabled.autoRouteEnabled()).isTrue();
        assertThat(autoService.availableTools(enabled, Map.of()))
                .contains(ManagedExecutorAgentDelegationService.START_TOOL);
    }

    @Test
    void evalShadowCanMeasureVersionRoutingWithoutStartingARealExecution() {
        ManagedExecutorAgentDelegationService evalService = service(false, false);
        RuntimeAgentConfigSnapshot draft = config(validPolicy(true)).toBuilder()
                .status("DRAFT")
                .build();
        var policy = evalService.resolveEvaluationPolicy(draft, "QMS");

        Map<String, Object> card = evalService.simulate(
                ManagedExecutorAgentDelegationService.START_TOOL,
                policy,
                draft,
                "QMS",
                Map.of(),
                Map.of("objective", "analyze a novel repository failure"),
                "eval-trace");

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.autoRouteEnabled()).isTrue();
        assertThat(card).containsEntry("simulated", true)
                .containsEntry("status", "QUEUED")
                .containsEntry("productionMutationApplied", false);
        assertThat(String.valueOf(card.get("executionId"))).startsWith("mex_eval_");
        verifyNoInteractions(executionService, artifactReadService);
    }

    @Test
    void startReturnsImmediatelyAndUsesOnlyTrustedIdentityAndImmutablePolicyAuthority() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());
        AtomicReference<com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest> captured =
                new AtomicReference<>();
        when(executionService.create(eq("tenant-a"), eq("user-a"), any()))
                .thenAnswer(invocation -> {
                    var request = (com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest)
                            invocation.getArgument(2);
                    captured.set(request);
                    return new CreatedView(execution(
                            "mex_1", "user-a", request.sourceRef(), "QUEUED"));
                });

        Map<String, Object> card = service.invoke(
                ManagedExecutorAgentDelegationService.START_TOOL,
                policy,
                config,
                identity(),
                Map.of("managedExecutorRequested", true),
                Map.of("objective", "inspect and test the repository"),
                "trace-sensitive-value");

        assertThat(card).containsEntry("executionId", "mex_1")
                .containsEntry("status", "QUEUED")
                .containsEntry("async", true)
                .containsEntry("productionMutationApplied", false);
        assertThat(captured.get().projectCode()).isEqualTo("QMS");
        assertThat(captured.get().sourceType()).isEqualTo("AGENT_DELEGATION");
        assertThat(captured.get().sourceRef()).startsWith("acv:42:trace:")
                .doesNotContain("trace-sensitive-value");
        assertThat(captured.get().sandboxProfile()).isEqualTo("ANALYZE_READONLY");
        assertThat(captured.get().modelRef()).isEqualTo("codex-reviewed");
        assertThat(captured.get().acceptanceProfile()).isEqualTo("PROJECT_DEFAULT");
        assertThat(captured.get().maxWallTimeSeconds()).isEqualTo(900);
        assertThat(captured.get().approvalTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void rejectsAnyModelAttemptToAddProjectProfileBudgetOrNetworkAuthority() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());

        assertThatThrownBy(() -> service.invoke(
                ManagedExecutorAgentDelegationService.START_TOOL,
                policy,
                config,
                identity(),
                Map.of("managedExecutorRequested", true),
                Map.of(
                        "objective", "inspect",
                        "projectCode", "OTHER",
                        "sandboxProfile", "UNRESTRICTED",
                        "network", "ALLOW_ALL",
                        "maxWallTimeSeconds", 14_400),
                "trace-1"))
                .isInstanceOfSatisfying(
                        ManagedExecutorAgentDelegationService.DelegationException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_EXECUTOR_ARGUMENTS_INVALID"));
        verify(executionService, never()).create(any(), any(), any());
    }

    @Test
    void productionMutationIntentNeverExposesOrStartsManagedExecutor() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());
        Map<String, Object> input = Map.of(
                "managedExecutorRequested", true,
                "message", "请把这个版本直接部署到生产环境");

        assertThat(service.availableTools(policy, input))
                .doesNotContain(ManagedExecutorAgentDelegationService.START_TOOL);
        assertThatThrownBy(() -> service.invoke(
                ManagedExecutorAgentDelegationService.START_TOOL,
                policy,
                config,
                identity(),
                input,
                Map.of("objective", "deploy to production"),
                "trace-1"))
                .isInstanceOfSatisfying(
                        ManagedExecutorAgentDelegationService.DelegationException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_EXECUTOR_PRODUCTION_MUTATION_REQUIRES_WORKFLOW"));
        verify(executionService, never()).create(any(), any(), any());
    }

    @Test
    void statusIsFencedByTenantProjectUserSourceAndExactConfigVersion() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());
        when(executionService.get("mex_other", "tenant-a"))
                .thenReturn(execution("mex_other", "user-b", "acv:42:trace:abc", "RUNNING"));

        assertThatThrownBy(() -> service.invoke(
                ManagedExecutorAgentDelegationService.STATUS_TOOL,
                policy, config, identity(), Map.of(), Map.of("executionId", "mex_other"), "trace-2"))
                .isInstanceOfSatisfying(
                        ManagedExecutorAgentDelegationService.DelegationException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_EXECUTOR_REFERENCE_DENIED"));
    }

    @Test
    void readResultReturnsOnlyVerifiedReferencesAndBoundedOutcomeCounts() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());
        ExecutionView execution = execution("mex_1", "user-a", "acv:42:trace:abc", "SUCCEEDED");
        when(executionService.get("mex_1", "tenant-a")).thenReturn(execution);
        ArtifactView summary = artifact("art_summary", "EXECUTION_SUMMARY", "application/json");
        ArtifactView tests = artifact("art_tests", "TEST_REPORT", "application/json");
        ArtifactView patch = artifact("art_patch", "PATCH", "text/x-diff");
        when(artifactReadService.list("mex_1", "tenant-a"))
                .thenReturn(List.of(summary, tests, patch));
        when(artifactReadService.read("mex_1", "art_summary", "tenant-a"))
                .thenReturn(content(summary, """
                        {"schema":"reachai.managed-executor.execution-summary.v1","executionId":"mex_1",
                         "outcome":"SUCCEEDED","verificationOutcome":"PASSED","readonlyViolation":false,
                         "appServer":{"threadId":"must-not-leak","turnId":"secret-turn","status":"completed"},
                         "patch":{"empty":false,"bytes":123,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                         "error":"must-not-leak"}
                        """));
        when(artifactReadService.read("mex_1", "art_tests", "tenant-a"))
                .thenReturn(content(tests, """
                        {"schema":"reachai.managed-executor.test-report.v1","executionId":"mex_1","outcome":"FAILED",
                         "commands":[{"name":"secret command","outcome":"PASSED","stdout":"token=secret"},
                                     {"name":"other","outcome":"FAILED","stderr":"secret"}]}
                        """));

        Map<String, Object> result = service.invoke(
                ManagedExecutorAgentDelegationService.READ_RESULT_TOOL,
                policy, config, identity(), Map.of(), Map.of("executionId", "mex_1"), "trace-2");

        assertThat(result).containsEntry("executionId", "mex_1")
                .containsEntry("productionMutationApplied", false);
        assertThat(objectMapper.valueToTree(result).toString())
                .doesNotContain("must-not-leak", "secret command", "token=secret", "stderr");
        assertThat(result.get("patchReference")).isEqualTo(Map.of(
                "artifactId", "art_patch",
                "artifactType", "PATCH",
                "sha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sizeBytes", 123L,
                "mediaType", "text/x-diff",
                "validationStatus", "VERIFIED",
                "scanStatus", "CLEAN"));
        verify(artifactReadService).read("mex_1", "art_summary", "tenant-a");
        verify(artifactReadService).read("mex_1", "art_tests", "tenant-a");
    }

    @Test
    void readResultRefusesNonSuccessfulExecutionsBeforeOpeningArtifacts() {
        RuntimeAgentConfigSnapshot config = config(validPolicy(false));
        var policy = service.resolvePolicy(config, identity());
        when(executionService.get("mex_1", "tenant-a"))
                .thenReturn(execution("mex_1", "user-a", "acv:42:trace:abc", "RUNNING"));

        assertThatThrownBy(() -> service.invoke(
                ManagedExecutorAgentDelegationService.READ_RESULT_TOOL,
                policy, config, identity(), Map.of(), Map.of("executionId", "mex_1"), "trace-2"))
                .isInstanceOfSatisfying(
                        ManagedExecutorAgentDelegationService.DelegationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MANAGED_EXECUTOR_RESULT_NOT_READY"));
        verify(artifactReadService, never()).list(any(), any());
    }

    private ManagedExecutorAgentDelegationService service(boolean autoRouteEnabled) {
        return service(autoRouteEnabled, true);
    }

    private ManagedExecutorAgentDelegationService service(boolean autoRouteEnabled, boolean enabled) {
        ManagedExecutorProperties properties = new ManagedExecutorProperties(
                enabled,
                autoRouteEnabled,
                "QMS",
                "ANALYZE_READONLY,WORKSPACE_PATCH",
                10,
                14_400,
                90,
                65_535,
                100,
                262_144,
                1_048_576);
        return new ManagedExecutorAgentDelegationService(
                executionService, artifactReadService, properties, objectMapper);
    }

    private RuntimeAgentConfigSnapshot config(String json) {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(42L)
                .status("PUBLISHED")
                .configJson(json)
                .build();
        return config;
    }

    private WorkflowExecutionIdentity identity() {
        return WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "QMS", "user-a");
    }

    private String validPolicy(boolean autoRouteEnabled) {
        return """
                {"managedExecutor":{
                  "enabled":true,
                  "autoRouteEnabled":%s,
                  "allowedTools":["managed_executor.start","managed_executor.status","managed_executor.read_result"],
                  "sandboxProfile":"ANALYZE_READONLY",
                  "modelRef":"codex-reviewed",
                  "acceptanceProfile":"PROJECT_DEFAULT",
                  "priority":0,
                  "maxWallTimeSeconds":900,
                  "approvalTimeoutSeconds":300,
                  "maxDelegationsPerRun":1
                }}
                """.formatted(autoRouteEnabled);
    }

    private ExecutionView execution(String id, String user, String sourceRef, String status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        return new ExecutionView(
                id,
                "tenant-a",
                "QMS",
                user,
                "AGENT_DELEGATION",
                sourceRef,
                "CODEX",
                "ANALYZE_READONLY",
                "codex-reviewed",
                "PROJECT_DEFAULT",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                status,
                "PENDING",
                null,
                null,
                0,
                0,
                900,
                300,
                0,
                false,
                null,
                null,
                now,
                now,
                null,
                null,
                "SUCCEEDED".equals(status) ? now : null);
    }

    private ArtifactView artifact(String id, String type, String mediaType) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        return new ArtifactView(
                "reachai.managed-executor.artifact.v1",
                "mex_1",
                id,
                type,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                123L,
                mediaType,
                "VERIFIED",
                "CLEAN",
                now,
                now,
                now.plusDays(30));
    }

    private ManagedArtifactReadService.ArtifactContent content(ArtifactView metadata, String json) {
        return new ManagedArtifactReadService.ArtifactContent(
                metadata,
                metadata.artifactId() + ".json",
                json.getBytes(StandardCharsets.UTF_8));
    }
}
