package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractRef;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactReporter;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceWorkflowCandidateTaskProviderTest {

    private final RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
    private final TraceWorkflowCandidateEligibility eligibility =
            mock(TraceWorkflowCandidateEligibility.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceWorkflowCandidateTaskProvider provider =
            new TraceWorkflowCandidateTaskProvider(
                    eligibility,
                    runtimeClient,
                    objectMapper,
                    new AiCodingContractResourceLoader(objectMapper));

    @BeforeEach
    void eligibleTrace() {
        when(eligibility.evaluate("trace-1")).thenReturn(
                new TraceWorkflowCandidateEligibility.EligibilityView(
                        TraceWorkflowCandidateEligibility.ELIGIBILITY_SCHEMA,
                        "trace-1", "orders", true, List.of(), List.of(),
                        "wf-source", 22L, "v1.0.1",
                        objectMapper.createObjectNode()));
        when(runtimeClient.runOpsDetail("trace-1")).thenReturn(
                ResponseEntity.ok(Map.of(
                        "executionPath", List.of(Map.of(
                                "spanType", "WORKFLOW_NODE",
                                "status", "SUCCESS",
                                "nodeId", "read")))));
        when(runtimeClient.listWorkflowVersions("wf-source")).thenReturn(
                ResponseEntity.ok(List.of(Map.of(
                        "id", 22L,
                        "graphSpecSnapshotJson",
                        "{\"nodes\":[{\"id\":\"read\",\"type\":\"TOOL\","
                                + "\"ref\":{\"qualifiedName\":\"orders.read\"}}],"
                                + "\"edges\":[],\"entryNodeId\":\"read\","
                                + "\"exitNodeIds\":[\"read\"]}"))));
    }

    @Test
    void rejectsToolNotPresentInExecutedSourcePath() throws Exception {
        JsonNode context = context("orders.read");
        JsonNode content = candidate("orders.write", "TOOL");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> provider.applyArtifact(
                        task(context), artifact(content)));

        assertFalse(error.getMessage().isBlank());
    }

    @Test
    void rejectsUnsafeNodeTypeBeforeRuntimeWrite() throws Exception {
        JsonNode context = context("orders.read");
        JsonNode content = candidate("orders.read", "HTTP_REQUEST");

        assertThrows(IllegalArgumentException.class,
                () -> provider.applyArtifact(task(context), artifact(content)));
    }

    @Test
    void rejectsLoopThatCouldAmplifyObservedReadCalls() throws Exception {
        JsonNode content = candidate("orders.read", "LOOP");

        assertThrows(IllegalArgumentException.class,
                () -> provider.applyArtifact(
                        task(objectMapper.createObjectNode()),
                        artifact(content)));
    }

    @Test
    void createsDraftThroughRuntimeOwnedIdempotentEndpoint() throws Exception {
        JsonNode context = objectMapper.createObjectNode();
        JsonNode content = candidate("orders.read", "TOOL");
        when(runtimeClient.validateWorkflowRuntime(any())).thenReturn(
                ResponseEntity.ok(Map.of(
                        "valid", true,
                        "errors", List.of(),
                        "warnings", List.of())));
        when(runtimeClient.createTraceWorkflowCandidateDraft(any())).thenReturn(
                ResponseEntity.ok(Map.of("workflow", Map.of(
                        "id", "wf-draft",
                        "keySlug", "candidate-ait123",
                        "name", "候选",
                        "status", "DRAFT"))));
        when(runtimeClient.validateWorkflowAiCoding(
                eq("wf-draft"), any())).thenReturn(
                ResponseEntity.ok(Map.of(
                        "valid", true,
                        "errors", List.of(),
                        "warnings", List.of())));
        when(runtimeClient.getWorkflow("wf-draft")).thenReturn(
                ResponseEntity.ok(Map.of(
                        "id", "wf-draft",
                        "updatedAt", "2026-08-13T12:00:00",
                        "graphSpecJson", content.path("workflow")
                                .path("graphSpec").toString())));

        var result = provider.applyArtifact(task(context), artifact(content));

        assertEquals("wf-draft",
                result.domainResult().path("workflowCandidate")
                        .path("workflowId").asText());
        assertEquals("2026-08-13T12:00:00",
                result.domainResult().path("workflowCandidate")
                        .path("workflowUpdatedAt").asText());
        verify(runtimeClient).createTraceWorkflowCandidateDraft(any());
    }

    @Test
    void replayReadinessIgnoresSuccessFromAnOlderDraftDigest() throws Exception {
        String currentGraph = "{\"nodes\":[],\"edges\":[]}";
        String currentDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(currentGraph.getBytes(StandardCharsets.UTF_8)));
        JsonNode applicationResult = objectMapper.readTree("""
                {
                  "workflowCandidate": {
                    "workflowId": "wf-draft",
                    "graphSpecDigest": "%s",
                    "workflowUpdatedAt": "2026-08-13T12:00:00",
                    "validation": {"valid": true}
                  }
                }
                """.formatted(currentDigest));
        when(runtimeClient.getWorkflow("wf-draft")).thenReturn(
                ResponseEntity.ok(Map.of(
                        "id", "wf-draft",
                        "status", "DRAFT",
                        "updatedAt", "2026-08-13T12:00:00",
                        "graphSpecJson", currentGraph)));
        when(runtimeClient.validateWorkflowAiCoding(
                eq("wf-draft"), any())).thenReturn(
                ResponseEntity.ok(Map.of("valid", true)));
        when(runtimeClient.workflowAiCodingRuns("wf-draft", 20, 30))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "workflowId", "wf-draft",
                         "runs", List.of(Map.of(
                                 "traceId", "old-trace",
                                 "startedAt", "2026-08-13T12:01:00",
                                 "status", "COMPLETED")))));
        when(runtimeClient.workflowAiCodingRunDetail(
                "wf-draft", "old-trace"))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "detail", Map.of(
                                "snapshot", Map.of(
                                        "snapshot", Map.of(
                                                "graphSpecDigest",
                                                "old-digest"))))));

        var readiness = provider.readiness(
                task(objectMapper.createObjectNode()), applicationResult);

        assertEquals("PENDING", readiness.stream()
                .filter(item -> "CANDIDATE_REPLAY".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    @Test
    void readinessBlocksWhenWorkflowRevisionChangesWithoutGraphChange()
            throws Exception {
        String currentGraph = "{\"nodes\":[],\"edges\":[]}";
        String currentDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(currentGraph.getBytes(StandardCharsets.UTF_8)));
        JsonNode applicationResult = objectMapper.readTree("""
                {
                  "workflowCandidate": {
                    "workflowId": "wf-draft",
                    "graphSpecDigest": "%s",
                    "workflowUpdatedAt": "2026-08-13T12:00:00"
                  }
                }
                """.formatted(currentDigest));
        when(runtimeClient.getWorkflow("wf-draft")).thenReturn(
                ResponseEntity.ok(Map.of(
                        "id", "wf-draft",
                        "status", "DRAFT",
                        "updatedAt", "2026-08-13T12:05:00",
                        "graphSpecJson", currentGraph)));
        when(runtimeClient.workflowAiCodingRuns("wf-draft", 20, 30))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "workflowId", "wf-draft",
                        "runs", List.of())));

        var readiness = provider.readiness(
                task(objectMapper.createObjectNode()), applicationResult);

        assertEquals("BLOCKED", readiness.stream()
                .filter(item -> "DRAFT_CREATED".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    private TaskDescriptor task(JsonNode context) {
        return new TaskDescriptor(
                "ait_123", 7L, "orders", "RUNOPS_TRACE_TO_WORKFLOW",
                TraceWorkflowCandidateTaskProvider.TASK_KIND, "v1", "CODEX",
                "候选", "候选", "READ_WRITE", "RUNNING", context,
                List.of(new TaskTargetView(
                        null, "RUNTIME_RUN", "trace-1", "PRIMARY",
                        "READ_WRITE", objectMapper.createObjectNode())),
                null, null);
    }

    private ArtifactEnvelope artifact(JsonNode content) {
        return new ArtifactEnvelope(
                "reachai.ai-coding.artifact.v1",
                "event-1",
                "candidate-v1",
                new ArtifactContractRef(
                        TraceWorkflowCandidateTaskProvider.CONTRACT_KEY,
                        TraceWorkflowCandidateTaskProvider.CONTRACT_VERSION),
                content,
                new ArtifactReporter("CODEX", "session-1"));
    }

    private JsonNode context(String qualifiedName) throws Exception {
        String graphJson = """
                {"nodes":[{"id":"read","type":"TOOL","ref":{"qualifiedName":"%s"}}],
                 "edges":[],"entryNodeId":"read","exitNodeIds":["read"]}
                """.formatted(qualifiedName).replaceAll("\\s+", "");
        return objectMapper.readTree("""
                {
                  "source": {
                    "executionPath": [
                      {"spanType":"WORKFLOW_NODE","status":"SUCCESS","nodeId":"read"}
                    ],
                    "sourceWorkflowVersion": {
                      "id":22,
                      "graphSpecSnapshotJson": %s
                    }
                  }
                }
                """.formatted(objectMapper.writeValueAsString(graphJson)));
    }

    private JsonNode candidate(String qualifiedName, String type) throws Exception {
        String node = "TOOL".equals(type)
                ? "{\"id\":\"read\",\"type\":\"TOOL\",\"ref\":{\"qualifiedName\":\""
                + qualifiedName + "\"}}"
                : "{\"id\":\"request\",\"type\":\"" + type
                + "\",\"config\":{\"url\":\"https://example.invalid\"}}";
        return objectMapper.readTree("""
                {
                  "sourceTraceId":"trace-1",
                  "summary":"候选",
                  "workflow":{
                    "name":"候选",
                    "keySlug":"candidate",
                    "workflowKind":"GENERAL",
                    "graphSpec":{
                      "nodes":[%s],
                      "edges":[],
                      "entryNodeId":"read",
                      "exitNodeIds":["read"]
                    }
                  },
                  "acceptanceCriteria":["成功"],
                  "remainingQuestions":[]
                }
                """.formatted(node));
    }
}
