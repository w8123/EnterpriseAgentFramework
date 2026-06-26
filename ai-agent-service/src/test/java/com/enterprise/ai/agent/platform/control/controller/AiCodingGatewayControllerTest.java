package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingGatewayControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void manifestReturnsUnifiedExternalAiCodingDiscoveryWithoutLeakingAccessKey() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders Service");
        project.setProjectCode("orders");
        project.setProjectKind("REGISTERED");
        project.setEnvironment("dev");
        project.setAiCodingAccessKey("rac_secret_should_not_echo");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");

        Object controller = newController(accessGuard);
        Method manifest = controller.getClass().getDeclaredMethod(
                "manifest",
                Long.class,
                jakarta.servlet.http.HttpServletRequest.class);

        @SuppressWarnings("unchecked")
        ResponseEntity<Object> response = (ResponseEntity<Object>) manifest.invoke(controller, 7L, request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> projectBody = objectMapper.convertValue(body.get("project"), new TypeReference<>() {
        });
        Map<String, Object> authBody = objectMapper.convertValue(body.get("auth"), new TypeReference<>() {
        });
        Map<String, Object> endpoints = objectMapper.convertValue(body.get("endpoints"), new TypeReference<>() {
        });
        Map<String, Object> contextCandidateSubmission = objectMapper.convertValue(
                body.get("contextCandidateSubmission"),
                new TypeReference<>() {
                });
        List<Map<String, Object>> capabilities = objectMapper.convertValue(
                body.get("capabilities"),
                new TypeReference<>() {
                });
        String serialized = objectMapper.writeValueAsString(body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("reachai.ai-coding.gateway.v1", body.get("schema"));
        assertEquals(7L, ((Number) projectBody.get("id")).longValue());
        assertEquals("orders", projectBody.get("projectCode"));
        assertEquals("X-ReachAI-AiCoding-Key", authBody.get("headerName"));
        assertFalse(authBody.containsKey("queryParam"));
        assertEquals("aiCodingKey:7", authBody.get("auditActor"));
        assertTrue(endpoints.get("contextCandidatesUrl").toString()
                .endsWith("/api/ai-coding/projects/7/context-candidates"));
        assertTrue(endpoints.get("contextCandidatesBatchUrl").toString()
                .endsWith("/api/ai-coding/projects/7/context-candidates/batch"));
        assertTrue(endpoints.get("contextCandidateStatusUrlTemplate").toString()
                .endsWith("/api/ai-coding/projects/7/context-candidates?traceId={submissionId}&status=PENDING"));
        assertTrue(endpoints.get("contextCandidateAuditUrlTemplate").toString()
                .endsWith("/context/governance?tab=audit&projectId=7&traceId={submissionId}"));
        assertTrue(endpoints.get("sdkAccessManifestUrl").toString()
                .endsWith("/api/ai-coding/projects/7/onboarding-manifest"));
        assertTrue(endpoints.get("pageAssistantManifestUrl").toString()
                .endsWith("/api/ai-coding/projects/7/page-assistant/onboarding-manifest"));
        assertTrue(endpoints.get("workflowCreateUrl").toString()
                .endsWith("/api/workflows/ai-coding/workflows"));
        assertTrue(endpoints.get("workflowPublishUrlTemplate").toString()
                .endsWith("/api/workflows/{workflowId}/ai-coding/publish"));
        assertEquals("reachai.context-candidate-submission.v1", contextCandidateSubmission.get("schema"));
        assertEquals("PENDING_HUMAN_REVIEW", contextCandidateSubmission.get("reviewMode"));
        assertEquals("PROJECT_DEV", contextCandidateSubmission.get("memoryLane"));
        assertEquals("default", contextCandidateSubmission.get("tenantId"));
        assertEquals("CODE", contextCandidateSubmission.get("defaultSourceType"));
        assertEquals("NOTE", contextCandidateSubmission.get("defaultCandidateType"));
        assertTrue(((List<?>) contextCandidateSubmission.get("requiredFields")).contains("content"));
        assertTrue(((List<?>) contextCandidateSubmission.get("candidateTypes")).contains("API_CONTEXT"));
        assertTrue(((List<?>) contextCandidateSubmission.get("sourceTypes")).contains("CODE"));
        Map<String, Object> traceMetadata = objectMapper.convertValue(
                contextCandidateSubmission.get("traceMetadata"),
                new TypeReference<>() {
                });
        assertEquals("aiCodingSubmission", traceMetadata.get("metadataKey"));
        assertEquals("ai-coding-submission-", traceMetadata.get("generatedSubmissionIdPrefix"));
        assertEquals("AI_CODING_CONTEXT_SCAN", traceMetadata.get("defaultOrigin"));
        assertEquals("traceId is generated by ReachAI as the AI Coding submissionId; client traceId is stored in metadata.aiCodingSubmission.clientTraceId",
                traceMetadata.get("traceIdPolicy"));
        assertEquals("sessionId is generated by ReachAI as the AI Coding submissionId; client sessionId is stored in metadata.aiCodingSubmission.clientSessionId",
                traceMetadata.get("sessionIdPolicy"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("tenantId"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("proposedBy"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("traceId"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("sessionId"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("origin"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("visibility"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("confidence"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("trustLevel"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("expiresAt"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("userId"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("globalUserId"));
        assertTrue(((List<?>) contextCandidateSubmission.get("serverControlledFields")).contains("externalUserId"));
        String guidanceText = objectMapper.writeValueAsString(contextCandidateSubmission.get("guidance"));
        assertTrue(guidanceText.contains("Batch submissions reject any item missing content"));
        assertTrue(guidanceText.contains("Status checks require ReachAI generated ai-coding-submission-* traceId/submissionId and return PENDING candidates only"));
        assertTrue(guidanceText.contains("WORKFLOW_CONTEXT requires workflowId or workflowKey"));
        assertTrue(guidanceText.contains("PAGE_CONTEXT requires pageInstanceId"));
        assertTrue(guidanceText.contains("API_CONTEXT requires sourceRef"));
        assertTrue(capabilities.stream().anyMatch(capability -> "SDK_ACCESS".equals(capability.get("key"))));
        assertTrue(capabilities.stream().anyMatch(capability -> "PAGE_ASSISTANT".equals(capability.get("key"))));
        assertTrue(capabilities.stream().anyMatch(capability -> "WORKFLOW_AI_CODING".equals(capability.get("key"))));
        assertTrue(capabilities.stream().anyMatch(capability -> "CONTEXT_CANDIDATES".equals(capability.get("key"))));
        assertFalse(serialized.contains("rac_secret_should_not_echo"));
        verify(accessGuard).requireProjectAccess(7L);
    }

    private Object newController(AiCodingAccessGuard accessGuard) throws Exception {
        Class<?> controllerType = Class.forName("com.enterprise.ai.agent.platform.control.controller.AiCodingGatewayController");
        Constructor<?> constructor = controllerType.getConstructor(AiCodingAccessGuard.class);
        return constructor.newInstance(accessGuard);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);
        return request;
    }
}
