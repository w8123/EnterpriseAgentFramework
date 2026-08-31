package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreatedView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactDescriptor;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCompleteRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventBatchRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventV1;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerMutationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedExecutionServiceTest {

    @Mock
    private ManagedExecutionMapper executionMapper;
    @Mock
    private ManagedExecutionEventMapper eventMapper;
    @Mock
    private ManagedArtifactMapper artifactMapper;
    @Mock
    private ManagedExecutionOutboxMapper outboxMapper;
    @Mock
    private ManagedExecutionApprovalService approvalService;
    @Mock
    private ManagedExecutionRunProjector runProjector;
    @Mock
    private ManagedArtifactVerifier artifactVerifier;
    @Mock
    private ManagedSandboxProvisioner sandboxProvisioner;
    @Mock
    private ManagedArtifactStore artifactStore;

    private ManagedWorkerTokenService tokens;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        tokens = new ManagedWorkerTokenService();
        objectMapper = new ObjectMapper();
        org.mockito.Mockito.lenient().when(sandboxProvisioner.available()).thenReturn(true);
        org.mockito.Mockito.lenient().when(artifactStore.available()).thenReturn(true);
    }

    @Test
    void creationIsFailClosedWhenFeatureOrCapacityIsDisabled() {
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(false, "PROJECT_A", "ANALYZE_READONLY", 0));

        assertThatThrownBy(() -> service.create("tenant-a", "user-a", createRequest()))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(503);
                    assertThat(failure.code()).isEqualTo("MANAGED_EXECUTOR_DISABLED");
                });
        verify(executionMapper, never()).countActive();
    }

    @Test
    void createsAQueuedExecutionWithoutExposingOrPersistingAWorkerToken() {
        when(executionMapper.countActive()).thenReturn(0);
        when(executionMapper.insert(any())).thenReturn(1);
        when(outboxMapper.insert(any())).thenReturn(1);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        CreatedView created = service.create("tenant-a", "user-a", createRequest());

        ArgumentCaptor<ManagedExecutionEntity> execution = ArgumentCaptor.forClass(ManagedExecutionEntity.class);
        verify(executionMapper).insert(execution.capture());
        assertThat(created.execution().status()).isEqualTo("QUEUED");
        assertThat(execution.getValue().getWorkerTokenDigest()).isNull();
        assertThat(execution.getValue().getWorkerTokenExpiresAt()).isNull();
        assertThat(execution.getValue().getProvisionAttemptCount()).isZero();
        assertThat(execution.getValue().getObjectiveText()).isEqualTo("Inspect the repository.");

        ArgumentCaptor<ManagedExecutionOutboxEntity> outbox =
                ArgumentCaptor.forClass(ManagedExecutionOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getPayloadJson())
                .contains("reachai.managed-execution.outbox.v1", "PROJECT_A", "\"status\":\"QUEUED\"")
                .doesNotContain("Inspect the repository.")
                .doesNotContain("workerBootstrapToken");
    }

    @Test
    void replaysTheSameSourceRequestWithoutCreatingAnotherSandboxExecution() {
        ManagedExecutionEntity existing = claimedExecution(tokens.generate());
        existing.setExecutionId("mex_existing_1");
        existing.setStatus("QUEUED");
        existing.setLeaseOwner(null);
        existing.setWorkerTokenDigest(null);
        existing.setWorkerTokenExpiresAt(null);
        existing.setRequestedByUserId("user-a");
        existing.setModelRef(null);
        existing.setMaxWallTimeSeconds(600);
        existing.setApprovalTimeoutSeconds(120);
        existing.setObjectiveSha256(java.util.HexFormat.of().formatHex(digest("Inspect the repository.")));
        when(executionMapper.selectOne(any())).thenReturn(existing);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        CreatedView replay = service.create("tenant-a", "user-a", createRequest());

        assertThat(replay.execution().executionId()).isEqualTo("mex_existing_1");
        verify(executionMapper, never()).insert(any());
        verify(executionMapper, never()).countActive();
        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void creationFailsClosedWhenSandboxOrArtifactInfrastructureIsUnavailable() {
        ManagedExecutorProperties properties =
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2);
        when(sandboxProvisioner.available()).thenReturn(false);
        ManagedExecutionService service = service(properties);

        assertThatThrownBy(() -> service.create("tenant-a", "user-a", createRequest()))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MANAGED_SANDBOX_BACKEND_UNAVAILABLE"));

        when(sandboxProvisioner.available()).thenReturn(true);
        when(artifactStore.available()).thenReturn(false);
        assertThatThrownBy(() -> service.create("tenant-a", "user-a", createRequest()))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MANAGED_ARTIFACT_STORE_UNAVAILABLE"));
        verify(executionMapper, never()).insert(any());
    }

    @Test
    void provisioningIssuesPlaintextOnlyInMemoryAndPersistsItsDigest() {
        ManagedExecutionEntity candidate = claimedExecution(tokens.generate());
        candidate.setId(11L);
        candidate.setStatus("QUEUED");
        candidate.setLeaseOwner(null);
        candidate.setWorkerTokenDigest(null);
        candidate.setWorkerTokenExpiresAt(null);
        candidate.setProvisionAttemptCount(0);
        candidate.setProvisionMaxAttempts(3);
        candidate.setProvisionAvailableAt(LocalDateTime.now().minusSeconds(1));
        when(executionMapper.findProvisionCandidateId(any())).thenReturn(11L);
        when(executionMapper.selectById(11L)).thenReturn(candidate);
        when(executionMapper.issueWorkerToken(any(), anyString(), any(), any())).thenReturn(1);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        ManagedExecutionService.ProvisioningReservation reservation = service.reserveProvisioning();

        assertThat(reservation).isNotNull();
        assertThat(reservation.request().workerBootstrapToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(reservation.tokenDigest()).isEqualTo(tokens.digest(
                reservation.request().workerBootstrapToken()));
        assertThat(reservation.request().toString())
                .contains("workerBootstrapToken=<redacted>")
                .doesNotContain(reservation.request().workerBootstrapToken());
    }

    @Test
    void appendsContiguousEventsAndMovesCompletedTurnsOnlyToFinalizing() {
        String workerToken = tokens.generate();
        ManagedExecutionEntity entity = claimedExecution(workerToken);
        when(executionMapper.selectOne(any())).thenReturn(entity);
        when(executionMapper.advanceEvent(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString(), any(), any()))
                .thenReturn(1);
        when(eventMapper.insert(any())).thenReturn(1);
        when(outboxMapper.insert(any())).thenReturn(1);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        WorkerMutationView result = service.appendEvents(
                entity.getExecutionId(),
                workerToken,
                entity.getLeaseOwner(),
                new WorkerEventBatchRequest(List.of(
                        event(entity.getExecutionId(), 1, "TURN_STARTED", "RUNNING"),
                        event(entity.getExecutionId(), 2, "TURN_COMPLETED", "SUCCEEDED"))));

        assertThat(result.lastEventSequence()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("FINALIZING");
        ArgumentCaptor<ManagedExecutionEventEntity> persisted =
                ArgumentCaptor.forClass(ManagedExecutionEventEntity.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).insert(persisted.capture());
        assertThat(persisted.getAllValues()).extracting(ManagedExecutionEventEntity::getSequence)
                .containsExactly(1, 2);
        assertThat(persisted.getAllValues()).allMatch(event -> event.getPayloadSha256().matches("[a-f0-9]{64}"));
    }

    @Test
    void successfulCompletionRequiresPreUploadedEvidenceAndRuntimeBundleVerification() {
        String workerToken = tokens.generate();
        ManagedExecutionEntity execution = claimedExecution(workerToken);
        execution.setStatus("FINALIZING");
        List<ManagedArtifactEntity> artifacts = verifiedArtifacts(execution.getExecutionId());
        when(executionMapper.selectOne(any())).thenReturn(execution);
        when(artifactMapper.selectOne(any())).thenReturn(
                artifacts.get(0), artifacts.get(1), artifacts.get(2), artifacts.get(3), artifacts.get(4));
        when(artifactMapper.selectList(any())).thenReturn(artifacts);
        when(executionMapper.completeByWorker(anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenAnswer(invocation -> {
                    execution.setLeaseOwner(null);
                    execution.setLeaseExpiresAt(null);
                    return 1;
                });
        when(executionMapper.markArtifactsVerified(anyString(), any())).thenAnswer(invocation -> {
            execution.setStatus("SUCCEEDED");
            execution.setWorkerTokenDigest(null);
            execution.setWorkerTokenExpiresAt(null);
            return 1;
        });
        when(outboxMapper.insert(any())).thenReturn(1);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        WorkerMutationView completed = service.complete(
                execution.getExecutionId(),
                workerToken,
                execution.getLeaseOwner(),
                new WorkerCompleteRequest("SUCCEEDED", null, null,
                        artifacts.stream().map(this::descriptor).toList()));

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        verify(artifactVerifier).verifyBundle(execution.getExecutionId(), artifacts);
        verify(executionMapper).markArtifactsVerified(anyString(), any());
        verify(artifactMapper, never()).insert(any());
    }

    @Test
    void successfulCompletionRejectsWorkerOnlyDescriptorsWithoutRuntimeUpload() {
        String workerToken = tokens.generate();
        ManagedExecutionEntity execution = claimedExecution(workerToken);
        execution.setStatus("FINALIZING");
        when(executionMapper.selectOne(any())).thenReturn(execution);
        when(artifactMapper.selectOne(any())).thenReturn(null);
        ManagedExecutionService service = service(
                ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2));

        assertThatThrownBy(() -> service.complete(
                execution.getExecutionId(), workerToken, execution.getLeaseOwner(),
                new WorkerCompleteRequest("SUCCEEDED", null, null,
                        verifiedArtifacts(execution.getExecutionId()).stream().map(this::descriptor).toList())))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MANAGED_ARTIFACT_NOT_UPLOADED"));
        verify(executionMapper, never()).completeByWorker(
                anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    private ManagedExecutionService service(ManagedExecutorProperties properties) {
        return new ManagedExecutionService(
                executionMapper,
                eventMapper,
                artifactMapper,
                outboxMapper,
                properties,
                tokens,
                new ManagedExecutionPayloadSanitizer(objectMapper, properties),
                approvalService,
                runProjector,
                artifactVerifier,
                sandboxProvisioner,
                artifactStore,
                objectMapper);
    }

    private CreateRequest createRequest() {
        return new CreateRequest(
                "project_a",
                "AI_CODING_TASK",
                "task_1",
                "CODEX",
                "ANALYZE_READONLY",
                null,
                "PROJECT_DEFAULT",
                "Inspect the repository.",
                0,
                600,
                120);
    }

    private ManagedExecutionEntity claimedExecution(String workerToken) {
        ManagedExecutionEntity entity = new ManagedExecutionEntity();
        entity.setExecutionId("mex_service_1");
        entity.setTenantId("tenant-a");
        entity.setProjectCode("PROJECT_A");
        entity.setRequestedByUserId("user-a");
        entity.setSourceType("AI_CODING_TASK");
        entity.setSourceRef("task_1");
        entity.setExecutorProvider("CODEX");
        entity.setSandboxProfile("ANALYZE_READONLY");
        entity.setAcceptanceProfile("PROJECT_DEFAULT");
        entity.setObjectiveText("Inspect the repository.");
        entity.setObjectiveSha256("a".repeat(64));
        entity.setStatus("PROVISIONING");
        entity.setCleanupStatus("PENDING");
        entity.setPriority(0);
        entity.setMaxWallTimeSeconds(600);
        entity.setApprovalTimeoutSeconds(120);
        entity.setLastEventSequence(0);
        entity.setProvisionAttemptCount(1);
        entity.setProvisionMaxAttempts(3);
        entity.setProvisionAvailableAt(LocalDateTime.now());
        entity.setWorkerTokenDigest(tokens.digest(workerToken));
        entity.setWorkerTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
        entity.setLeaseOwner("worker-1");
        entity.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(90));
        entity.setVersion(1L);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private WorkerEventV1 event(String executionId, int sequence, String type, String phase) {
        return new WorkerEventV1(
                "reachai.managed-execution.event.v1",
                executionId,
                sequence,
                executionId + ":" + sequence,
                Instant.now(),
                type,
                phase,
                "OPERATOR",
                "DURABLE",
                type,
                Map.of());
    }

    private List<ManagedArtifactEntity> verifiedArtifacts(String executionId) {
        return List.of("PATCH", "TEST_REPORT", "EXECUTION_SUMMARY", "EVIDENCE_MANIFEST", "EVENT_LOG")
                .stream()
                .map(type -> {
                    ManagedArtifactEntity artifact = new ManagedArtifactEntity();
                    artifact.setArtifactId("artifact-" + type.toLowerCase());
                    artifact.setExecutionId(executionId);
                    artifact.setArtifactType(type);
                    artifact.setObjectKey("managed-executions/" + executionId + "/artifacts/"
                            + artifact.getArtifactId() + "/evidence");
                    artifact.setSha256("b".repeat(64));
                    artifact.setSizeBytes(10L);
                    artifact.setMediaType(switch (type) {
                        case "PATCH" -> "text/x-diff";
                        case "EVENT_LOG" -> "application/x-ndjson";
                        default -> "application/json";
                    });
                    artifact.setValidationStatus("VERIFIED");
                    artifact.setScanStatus("CLEAN");
                    return artifact;
                })
                .toList();
    }

    private ArtifactDescriptor descriptor(ManagedArtifactEntity artifact) {
        return new ArtifactDescriptor(
                artifact.getArtifactId(),
                artifact.getArtifactType(),
                artifact.getObjectKey(),
                artifact.getSha256(),
                artifact.getSizeBytes(),
                artifact.getMediaType());
    }

    private byte[] digest(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
