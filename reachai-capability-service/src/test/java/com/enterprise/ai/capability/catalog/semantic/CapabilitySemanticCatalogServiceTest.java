package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
import com.enterprise.ai.capability.catalog.scan.CapabilityModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilitySemanticCatalogServiceTest {
    final CapabilitySemanticDocumentStore docs = mock(CapabilitySemanticDocumentStore.class);
    final CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
    final CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
    final CapabilityModelClient model = mock(CapabilityModelClient.class);
    final CapabilitySemanticCatalogService service = new CapabilitySemanticCatalogService(docs, subjects, grouping, model, new ObjectMapper());

    @Test void groupReadsUseTheReadOnlyDocumentPath() {
        var group = subject(CatalogSubject.GROUP, "hash-a");
        var doc = doc("edited");
        when(docs.findGroup(group)).thenReturn(java.util.Optional.of(doc));
        assertSame(doc, service.find(group).orElseThrow());
        verify(docs).findGroup(group);
        verify(docs, never()).find(group);
    }

    @Test void documentListReadsPersistedProseWithoutResolvingContractsOrAdoptingCandidates() {
        var doc = doc("edited");
        when(docs.list(7L)).thenReturn(List.of(doc));
        assertEquals(List.of(doc), service.listProjectDocs(7L));
        verify(subjects).requireProject(7L);
        verify(subjects, never()).operations(any());
        verify(docs).list(7L);
        verifyNoMoreInteractions(docs);
        verifyNoInteractions(grouping, model);
    }

    @Test void generatesTypedSubjectsWithCorrectAggregateCandidateAndAcceptedInstructionsAndNoContractMutationDependency() {
        record Scenario(String type, long id, boolean accepted) { }
        for (Scenario scenario : List.of(new Scenario(CatalogSubject.BUSINESS_METHOD, 21L, true), new Scenario(CatalogSubject.HTTP_API, 21L, true),
                new Scenario(CatalogSubject.PROJECT, 7L, false), new Scenario(CatalogSubject.GROUP, 21L, false),
                new Scenario(CatalogSubject.SOURCE_OBSERVATION, 21L, false), new Scenario(CatalogSubject.HTTP_API, 21L, false))) {
            String type = scenario.type(); boolean accepted = scenario.accepted(); long id = scenario.id();
            CatalogSubject subject = new CatalogSubject(type, id, 7L, "orders:query", "Orders", "Source prose", "hash-a", accepted, "GET", "/orders", List.of(), List.of());
            when(subjects.resolve(7L, type, id)).thenReturn(subject);
            doAnswer(call -> {
                CapabilityModelClient.ChatRequest request = call.getArgument(0); String prompt = request.messages().get(1).content();
                if (CatalogSubject.PROJECT.equals(type) || CatalogSubject.GROUP.equals(type)) {
                    assertTrue(prompt.contains("聚合整理对象")); assertFalse(prompt.contains("来源候选")); assertFalse(prompt.contains("已接纳："));
                } else if (CatalogSubject.SOURCE_OBSERVATION.equals(type)) assertTrue(prompt.contains("未接纳的来源候选"));
                else if (accepted) assertTrue(prompt.contains("正式资产"));
                else assertTrue(prompt.contains("未接纳的 API 资产候选"));
                return com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityModelClient.ChatResponse("# Orders\n\nGenerated prose", "model-main", null, new CapabilityModelClient.Usage(2, 3, 5)));
            }).when(model).chat(any());
            SemanticDocEntity result = service.generate(7L, type, id, true, "model-main");
            assertEquals(type, result.getSubjectType()); assertEquals("hash-a", result.getBasisContractHash());
            assertEquals(id, result.getSubjectId()); assertEquals(5, result.getTokenUsage()); assertEquals(accepted, result.getBasisAccepted());
        }
        verify(docs, times(6)).save(any(SemanticDocEntity.class), isNull());
    }

    @Test void missingModePreservesGeneratedAndEditedProseWithoutModelCost() {
        for (String status : List.of("generated", "edited")) {
            CatalogSubject subject = subject(CatalogSubject.BUSINESS_METHOD, "hash-a");
            when(subjects.resolve(7L, subject.subjectType(), 21L)).thenReturn(subject);
            SemanticDocEntity existing = doc(status); when(docs.find(any())).thenReturn(java.util.Optional.of(existing));
            assertSame(existing, service.generate(7L, subject.subjectType(), 21L, false, "model-main"));
        }
        verifyNoInteractions(model); verify(docs, never()).save(any(), any());
    }

    @Test void rejectsSourceContractChangeDuringGenerationAndKeepsPriorProse() {
        when(subjects.resolve(7L, CatalogSubject.HTTP_API, 21L)).thenReturn(subject(CatalogSubject.HTTP_API, "hash-a"), subject(CatalogSubject.HTTP_API, "hash-b"));
        when(model.chat(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityModelClient.ChatResponse("generated", "model", null, null)));
        assertThrows(IllegalStateException.class, () -> service.generate(7L, CatalogSubject.HTTP_API, 21L, true, "model"));
        verify(docs, never()).save(any(), any());
    }

    @Test void rejectsStaleManualEditAndAllowsCurrentVersion() {
        CatalogSubject subject = subject(CatalogSubject.BUSINESS_METHOD, "hash-a"); when(subjects.resolve(7L, subject.subjectType(), 21L)).thenReturn(subject);
        SemanticDocEntity existing = doc("generated"); when(docs.find(any())).thenReturn(java.util.Optional.of(existing));
        assertThrows(IllegalStateException.class, () -> service.edit(7L, subject.subjectType(), 21L, "new", "2020-01-01T00:00"));
        String version = existing.getUpdateTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertEquals("edited", service.edit(7L, subject.subjectType(), 21L, "new", version).getStatus());
        verifyNoInteractions(model);
    }

    @Test void rejectsCandidateAcceptanceDuringGenerationEvenWhenContractHashDoesNotChange() {
        var accepted = subject(CatalogSubject.HTTP_API, "hash-a");
        var candidate = new CatalogSubject(accepted.subjectType(), accepted.subjectId(), accepted.projectId(), accepted.subjectKey(), accepted.title(), accepted.description(), accepted.contractHash(), false,
                accepted.httpMethod(), accepted.endpointPath(), accepted.parameters(), accepted.observations());
        when(subjects.resolve(7L, CatalogSubject.HTTP_API, 21L)).thenReturn(candidate, accepted);
        when(model.chat(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityModelClient.ChatResponse("候选说明", "model", null, null)));
        assertThrows(IllegalStateException.class, () -> service.generate(7L, CatalogSubject.HTTP_API, 21L, true, "model"));
        verify(docs, never()).save(any(), any());
    }

    @Test void refusesEmptyModelAndNeverReturnsAnotherProjectsTask() {
        assertThrows(IllegalArgumentException.class, () -> service.startProjectBatch(7L, false, ""));
        assertTrue(service.task(8L, "missing").isEmpty());
    }

    @Test void pageReloadRecoversRunningBatchAndFailedParallelStartCannotReplaceIt() throws Exception {
        var project = new CatalogSubject("PROJECT", 7L, 7L, "project:orders", "Orders", "", "project-hash", false, "", "", List.of(), List.of());
        when(subjects.resolve(7L, "PROJECT", 7L)).thenReturn(project);
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        when(model.chat(any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityModelClient.ChatResponse("project prose", "model", null, null));
        });
        String taskId = service.startProjectBatch(7L, false, "model");
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(taskId, service.task(7L, null).orElseThrow().getTaskId()); assertEquals(CapabilitySemanticGenerationTask.Stage.RUNNING, service.task(7L, taskId).orElseThrow().getStage());
        assertThrows(IllegalStateException.class, () -> service.startProjectBatch(7L, false, "model")); assertTrue(service.task(8L, taskId).isEmpty());
        release.countDown();
        for (int retry = 0; retry < 200 && service.task(7L, taskId).orElseThrow().getFinishedAt() == null; retry++) Thread.sleep(10);
        assertEquals(CapabilitySemanticGenerationTask.Stage.DONE, service.task(7L, taskId).orElseThrow().getStage());
        assertEquals(1, service.task(7L, taskId).orElseThrow().getCompletedSteps());
    }

    private CatalogSubject subject(String type, String hash) {
        return new CatalogSubject(type, 21L, 7L, "orders:query", "Orders", "Source prose", hash, true, "GET", "/orders", List.of(), List.of());
    }
    private SemanticDocEntity doc(String status) {
        SemanticDocEntity doc = new SemanticDocEntity(); doc.setId(11L); doc.setProjectId(7L); doc.setSubjectType(CatalogSubject.BUSINESS_METHOD);
        doc.setSubjectId(21L); doc.setContentMd("existing"); doc.setStatus(status); doc.setUpdateTime(LocalDateTime.of(2026, 10, 8, 1, 2)); return doc;
    }
}
