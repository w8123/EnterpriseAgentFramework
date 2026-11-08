package com.enterprise.ai.control.aicoding.retrofit;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractRef;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRetrofitTaskProviderTest {

    private final ObjectMapper json = new ObjectMapper();
    private CapabilityProjectOnboardingClient capability;
    private AiCodingTaskQuestionMapper questions;
    private CapabilityRetrofitTaskProvider provider;
    private TaskDescriptor task;

    @BeforeEach
    void setUp() {
        capability = mock(CapabilityProjectOnboardingClient.class);
        questions = mock(AiCodingTaskQuestionMapper.class);
        provider = new CapabilityRetrofitTaskProvider(capability, questions, json, new AiCodingContractResourceLoader(json));
        task = new TaskDescriptor("ait_1", 7L, "yudao-erp", "CAPABILITY_RETROFIT", "CAPABILITY_RETROFIT", "v1",
                "CODEX", "改造", "能查应收款", "READ_WRITE", "RUNNING", json.createObjectNode(), List.of(), null, null);
        when(capability.getReadinessFacts(7L)).thenReturn(Map.of("online", true, "sdkLanguage", "java", "lastHeartbeatAt", "2026-11-07T10:00"));
    }

    @Test
    void contextCarriesProtocolLanguageAndOnboardingState() {
        JsonNode context = provider.buildContext(task);
        assertEquals("java", context.path("sdkLanguage").asText());
        assertTrue(context.path("onboarding").path("instanceOnline").asBoolean());
        assertEquals("reachai.capability-retrofit-candidates.v1", context.path("candidateDocument").path("schema").asText());
        assertTrue(context.path("declarationGuide").path("example").asText().contains("@ReachCapability"));
        JsonNode materialized = provider.materializeContext(task, context, "https://reachai.example.com/");
        assertEquals("https://reachai.example.com/api/ai-assist/skills/reachai-capability-retrofit/latest.zip",
                materialized.path("skillPackageUrl").asText());
    }

    @Test
    void sdkSyncWaitsForTheDevelopersChoice() {
        when(questions.selectList(any())).thenReturn(List.of(question("OPEN", null)));
        assertThrows(IllegalStateException.class, () -> provider.requestVerification(task, "SDK_SYNC"));
        verify(capability, never()).triggerSdkSync(any());

        when(questions.selectList(any())).thenReturn(List.of(question("ANSWERED", "erp.receivable.page")));
        when(capability.triggerSdkSync(7L)).thenReturn(Map.of("syncId", 3));
        assertEquals(3, provider.requestVerification(task, "SDK_SYNC").path("syncId").asInt());
    }

    @Test
    void reportMayOnlyDeclareSelectedCandidates() {
        when(questions.selectList(any())).thenReturn(List.of(question("ANSWERED",
                "{\"schema\":\"reachai.capability-retrofit-selection.v1\",\"selected\":[\"erp.receivable.page\"]}")));
        ObjectNode outside = report("erp.sale.return.create", "erpSaleReturnCreate");
        assertThrows(IllegalArgumentException.class, () -> provider.applyArtifact(task, envelope(outside)));

        when(capability.getBusinessMethodStates(eq(7L), anyList())).thenReturn(Map.of("items",
                List.of(Map.of("name", "erpReceivablePage", "state", "PENDING_REVIEW"))));
        ArtifactApplyResult applied = provider.applyArtifact(task, envelope(report("erp.receivable.page", "erpReceivablePage")));
        assertTrue(applied.applied());
        assertEquals("PENDING_REVIEW", applied.domainResult().path("platformCheck").path("capabilityStates").get(0).path("state").asText());
    }

    @Test
    void readinessPassesOnlyWhenEveryDeclaredCapabilityIsAccepted() {
        when(questions.selectList(any())).thenReturn(List.of(question("ANSWERED", "erp.receivable.page")));
        ObjectNode result = json.createObjectNode();
        result.set("codingReport", report("erp.receivable.page", "erpReceivablePage"));

        when(capability.getBusinessMethodStates(eq(7L), anyList())).thenReturn(Map.of("items",
                List.of(Map.of("name", "erpReceivablePage", "state", "PENDING_REVIEW"))));
        assertEquals("PENDING", item(provider.readiness(task, result), "CAPABILITIES_ACCEPTED").status());

        when(capability.getBusinessMethodStates(eq(7L), anyList())).thenReturn(Map.of("items",
                List.of(Map.of("name", "erpReceivablePage", "state", "ACCEPTED"))));
        List<ReadinessItem> ready = provider.readiness(task, result);
        assertEquals("PASS", item(ready, "CAPABILITIES_ACCEPTED").status());
        assertEquals("PASS", item(ready, "SELECTION_CONFIRMED").status());
        assertEquals("PASS", item(ready, "RUNTIME_READY").status());
    }

    @Test
    void selectionParsingRejectsUnknownKeysAndAcceptsPlainLists() {
        List<String> allowed = List.of("a.one", "b.two");
        assertEquals(List.of("a.one", "b.two"), CapabilityRetrofitProtocol.parseSelection(json, "a.one，b.two", allowed));
        assertThrows(IllegalArgumentException.class, () -> CapabilityRetrofitProtocol.parseSelection(json, "c.three", allowed));
        String answer = CapabilityRetrofitProtocol.selectionAnswer(json, List.of("b.two"), "only one");
        assertEquals(List.of("b.two"), CapabilityRetrofitProtocol.parseSelection(json, answer, allowed));
    }

    @Test
    void candidateDocumentsAreValidated() {
        ObjectNode source = json.createObjectNode();
        source.put("summary", "s");
        ObjectNode row = source.putArray("candidates").addObject();
        row.put("candidateKey", "erp.receivable.page");
        row.put("title", "查询应收款");
        row.put("sideEffect", "read");
        row.put("sourceFile", "A.java");
        ObjectNode normalized = CapabilityRetrofitProtocol.normalizeCandidates(json, source);
        assertEquals("READ", normalized.path("candidates").get(0).path("sideEffect").asText());
        row.put("sideEffect", "DELETE");
        assertThrows(IllegalArgumentException.class, () -> CapabilityRetrofitProtocol.normalizeCandidates(json, source));
        row.put("sideEffect", "WRITE");
        row.put("candidateKey", "bad key!");
        assertThrows(IllegalArgumentException.class, () -> CapabilityRetrofitProtocol.normalizeCandidates(json, source));
    }

    private ReadinessItem item(List<ReadinessItem> items, String key) {
        return items.stream().filter(item -> key.equals(item.key())).findFirst().orElseThrow();
    }

    private AiCodingTaskQuestionEntity question(String status, String answer) {
        ObjectNode candidates = json.createObjectNode();
        candidates.put("schema", CapabilityRetrofitProtocol.CANDIDATES_SCHEMA);
        candidates.putArray("candidates").addObject().put("candidateKey", "erp.receivable.page");
        candidates.withArray("candidates").addObject().put("candidateKey", "erp.sale.return.create");
        AiCodingTaskQuestionEntity question = new AiCodingTaskQuestionEntity();
        question.setQuestionId("retrofit-select-1");
        question.setTaskId("ait_1");
        question.setBody(candidates.toString());
        question.setStatus(status);
        question.setAnswer(answer);
        question.setAskedAt(LocalDateTime.now());
        return question;
    }

    private ObjectNode report(String candidateKey, String name) {
        ObjectNode report = json.createObjectNode();
        report.put("schema", CapabilityRetrofitTaskProvider.REPORT_SCHEMA);
        report.put("summary", "done");
        report.put("selectionQuestionId", "retrofit-select-1");
        report.put("mode", "SDK_DECLARATION");
        ObjectNode capability = report.putArray("capabilities").addObject();
        capability.put("candidateKey", candidateKey);
        capability.put("name", name);
        capability.put("title", "t");
        capability.put("sideEffect", "READ");
        capability.put("sourceFile", "A.java");
        capability.put("status", "DECLARED");
        return report;
    }

    private ArtifactEnvelope envelope(JsonNode content) {
        return new ArtifactEnvelope("reachai.ai-coding.artifact.v1", "e1", "report",
                new ArtifactContractRef(CapabilityRetrofitTaskProvider.CONTRACT_KEY, "v1"), content, null);
    }
}
