package com.enterprise.ai.control.context;

import com.enterprise.ai.control.client.runtime.RuntimeSessionRetentionGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryErasureOrchestrationServiceTest {

    @Test
    void createPersistsNineDomainPlanAndReturnsNoRawOwner() {
        Fixture fixture = fixture();
        List<MemoryErasureDomainEntity> domains = new ArrayList<>();
        when(fixture.store.createOrGet(any(MemoryErasureRequestEntity.class), anyList()))
                .thenAnswer(invocation -> {
                    MemoryErasureRequestEntity request = invocation.getArgument(0);
                    request.setId(1L);
                    List<MemoryErasureStore.DomainSpec> specs = invocation.getArgument(1);
                    for (MemoryErasureStore.DomainSpec spec : specs) {
                        domains.add(domain(1L, spec.domainCode(), spec.executionMode(),
                                spec.initialStatus()));
                    }
                    return new MemoryErasureStore.CreateResult(request, true);
                });
        when(fixture.store.domains(1L)).thenAnswer(ignored -> domains);

        MemoryErasureOrchestrationService.RequestView result = fixture.service.create(
                new MemoryErasureOrchestrationService.CreateCommand(
                        MemoryErasureOrchestrationService.CONFIRMATION,
                        "client-42", "Tenant-A", "raw-user-42",
                        "PRIVACY_REQUEST", "ticket/42"),
                "platform-admin-7");

        assertTrue(result.created());
        assertEquals("tenant-a", result.tenantId());
        assertEquals(9, result.domains().size());
        assertEquals(5, result.domains().stream()
                .filter(value -> "AUTOMATED".equals(value.executionMode())).count());
        assertEquals(4, result.domains().stream()
                .filter(value -> "MANUAL_EVIDENCE".equals(value.executionMode())).count());
        assertTrue(result.runtimeUserHash().matches("[0-9a-f]{64}"));
        assertFalse(result.toString().contains("raw-user-42"));
    }

    @Test
    void automatedDomainsFinishAsActionRequiredAndScrubRawOwner() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        wireProcessStore(fixture, request, domains);
        when(fixture.personalMemoryService.eraseAllForOrchestration(any(), any(), eq("request-42")))
                .thenReturn(new PersonalMemoryService.EraseAllView(
                        "CONTROL_PERSONAL_MEMORY_ERASED", 2, 3,
                        LocalDateTime.now(), false, List.of()));
        PersonalMemoryErasureOutboxStatusRow outbox = new PersonalMemoryErasureOutboxStatusRow();
        outbox.setTotalCount(2L);
        outbox.setPublishedCount(2L);
        outbox.setSupersededCount(0L);
        outbox.setPendingCount(0L);
        outbox.setDeadCount(0L);
        outbox.setOtherCount(0L);
        when(fixture.outboxMapper.selectErasureDeliveryStatus("request-42")).thenReturn(outbox);
        when(fixture.knowledgeClient.status("default", "user-1"))
                .thenReturn(new PersonalMemoryKnowledgeErasureClient.OwnerProjectionStatus(
                        fixture.identity.hash("default", "user-1"),
                        2, 0, 2, 0, 17L, LocalDateTime.now(), true));
        when(fixture.runtimeGateway.eraseOwner(
                eq("default"), eq("user-1"), eq("PRIVACY_REQUEST"), eq("ticket/42"),
                eq(100), eq(request.getRequestedByHash())))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "complete", true,
                        "remaining", 0,
                        "erased", 4,
                        "legalHoldBlocked", 0,
                        "failed", 0)));

        MemoryErasureOrchestrationService.ProcessingResult result =
                fixture.service.processDue(10);

        assertEquals(1, result.claimed());
        assertEquals(0, result.completed());
        assertEquals(1, result.notCompleted());
        assertEquals("ACTION_REQUIRED", request.getStatus());
        assertNull(request.getRuntimeUserId());
        assertTrue(request.getAutomatedCompletedAt() != null);
        assertEquals(5, domains.stream().filter(MemoryErasureOrchestrationServiceTest::completed).count());
        assertEquals(4, domains.stream().filter(
                value -> "WAITING_EVIDENCE".equals(value.getStatus())).count());
    }

    @Test
    void runtimeLegalHoldBlocksCompletionAndRetainsRetryTarget() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        complete(domains, MemoryErasureOrchestrationService.CONTROL_PERSONAL_MEMORY);
        complete(domains, MemoryErasureOrchestrationService.CONTROL_CANDIDATE_OUTBOX);
        complete(domains, MemoryErasureOrchestrationService.KNOWLEDGE_PERSONAL_PROJECTION);
        wireProcessStore(fixture, request, domains);
        when(fixture.runtimeGateway.eraseOwner(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "complete", false,
                        "remaining", 1,
                        "erased", 0,
                        "legalHoldBlocked", 1,
                        "failed", 0)));

        fixture.service.processDue(10);

        assertEquals("BLOCKED_LEGAL_HOLD", request.getStatus());
        assertEquals("user-1", request.getRuntimeUserId());
        assertNull(request.getAutomatedCompletedAt());
        assertEquals("BLOCKED_LEGAL_HOLD", find(domains,
                MemoryErasureOrchestrationService.RUNTIME_SESSION_STATE).getStatus());
        verify(fixture.personalMemoryService, never())
                .eraseAllForOrchestration(any(), any(), anyString());
    }

    @Test
    void deadDeleteOutboxFailsClosedBeforeKnowledgeProof() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        complete(domains, MemoryErasureOrchestrationService.CONTROL_PERSONAL_MEMORY);
        wireProcessStore(fixture, request, domains);
        PersonalMemoryErasureOutboxStatusRow outbox = new PersonalMemoryErasureOutboxStatusRow();
        outbox.setTotalCount(1L);
        outbox.setPublishedCount(0L);
        outbox.setSupersededCount(0L);
        outbox.setPendingCount(0L);
        outbox.setDeadCount(1L);
        outbox.setOtherCount(0L);
        when(fixture.outboxMapper.selectErasureDeliveryStatus("request-42")).thenReturn(outbox);
        when(fixture.runtimeGateway.eraseOwner(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "complete", true,
                        "remaining", 0,
                        "erased", 0,
                        "legalHoldBlocked", 0,
                        "failed", 0)));

        fixture.service.processDue(10);

        assertEquals("FAILED", request.getStatus());
        assertEquals("user-1", request.getRuntimeUserId());
        assertEquals("OUTBOX_DEAD", find(domains,
                MemoryErasureOrchestrationService.CONTROL_CANDIDATE_OUTBOX)
                .getLastFailureCode());
        verify(fixture.knowledgeClient, never()).status(anyString(), anyString());
    }

    @Test
    void incompleteDomainInventoryPreventsAnyDestructiveCall() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        domains.remove(domains.size() - 1);
        wireProcessStore(fixture, request, domains);

        fixture.service.processDue(10);

        assertEquals("RETRY", request.getStatus());
        assertEquals("user-1", request.getRuntimeUserId());
        verify(fixture.personalMemoryService, never())
                .eraseAllForOrchestration(any(), any(), anyString());
        verify(fixture.runtimeGateway, never()).eraseOwner(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void manualEvidenceClosesRequestAndPreservesLegalRetentionResult() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        request.setStatus("ACTION_REQUIRED");
        request.setRuntimeUserId(null);
        request.setAutomatedCompletedAt(LocalDateTime.now().minusMinutes(1));
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        domains.stream()
                .filter(value -> "AUTOMATED".equals(value.getExecutionMode()))
                .forEach(value -> {
                    value.setStatus("COMPLETED");
                    value.setResultCode("ERASED");
                });
        when(fixture.store.findByRequestId("request-42")).thenReturn(request);
        when(fixture.store.domains(1L)).thenAnswer(ignored -> domains);
        doAnswer(invocation -> {
            MemoryErasureDomainEntity domain = find(domains, invocation.getArgument(1));
            domain.setStatus("COMPLETED");
            domain.setResultCode(invocation.getArgument(2));
            domain.setEvidenceReference(invocation.getArgument(3));
            domain.setCompletedAt(invocation.getArgument(4));
            return null;
        }).when(fixture.store).attestDomain(eq(1L), anyString(), anyString(),
                anyString(), any(LocalDateTime.class));
        doAnswer(invocation -> {
            request.setStatus(invocation.getArgument(1));
            if ((boolean) invocation.getArgument(2)) {
                request.setCompletedAt(invocation.getArgument(3));
            }
            return null;
        }).when(fixture.store).updateRequestStatus(eq(1L), anyString(),
                anyBoolean(), any(LocalDateTime.class));

        fixture.service.attest("request-42",
                MemoryErasureOrchestrationService.RUNOPS_TRACE_INTERACTION,
                new MemoryErasureOrchestrationService.EvidenceCommand(
                        "ERASED", "evidence://ticket/42/runops"));
        fixture.service.attest("request-42",
                MemoryErasureOrchestrationService.BUSINESS_INDEX,
                new MemoryErasureOrchestrationService.EvidenceCommand(
                        "NOT_APPLICABLE", "evidence://ticket/42/index"));
        fixture.service.attest("request-42",
                MemoryErasureOrchestrationService.BUSINESS_SOURCE_SYSTEM,
                new MemoryErasureOrchestrationService.EvidenceCommand(
                        "RETAINED_LEGAL", "evidence://ticket/42/source"));
        MemoryErasureOrchestrationService.RequestView result = fixture.service.attest(
                "request-42", MemoryErasureOrchestrationService.BACKUP_EXPIRY_AND_RESTORE,
                new MemoryErasureOrchestrationService.EvidenceCommand(
                        "ERASED", "evidence://ticket/42/backup"));

        assertEquals("COMPLETED_WITH_RETENTION", result.status());
        assertTrue(result.completedAt() != null);
        assertTrue(result.domains().stream().allMatch(
                value -> "COMPLETED".equals(value.status())));
        assertFalse(result.toString().contains("user-1"));
    }

    @Test
    void completedManualEvidenceIsIdempotentButCannotBeRewritten() {
        Fixture fixture = fixture();
        MemoryErasureRequestEntity request = request(fixture.identity);
        request.setStatus("ACTION_REQUIRED");
        request.setRuntimeUserId(null);
        request.setAutomatedCompletedAt(LocalDateTime.now().minusMinutes(1));
        List<MemoryErasureDomainEntity> domains = domainInventory(1L);
        MemoryErasureDomainEntity runops = find(domains,
                MemoryErasureOrchestrationService.RUNOPS_TRACE_INTERACTION);
        runops.setStatus("COMPLETED");
        runops.setResultCode("ERASED");
        runops.setEvidenceReference("evidence://ticket/42/runops");
        when(fixture.store.findByRequestId("request-42")).thenReturn(request);
        when(fixture.store.domains(1L)).thenReturn(domains);

        MemoryErasureOrchestrationService.RequestView replay = fixture.service.attest(
                "request-42", MemoryErasureOrchestrationService.RUNOPS_TRACE_INTERACTION,
                new MemoryErasureOrchestrationService.EvidenceCommand(
                        "ERASED", "evidence://ticket/42/runops"));
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> fixture.service.attest(
                        "request-42",
                        MemoryErasureOrchestrationService.RUNOPS_TRACE_INTERACTION,
                        new MemoryErasureOrchestrationService.EvidenceCommand(
                                "RETAINED_LEGAL", "evidence://ticket/42/rewrite")));

        assertEquals("ACTION_REQUIRED", replay.status());
        assertEquals(409, conflict.getStatusCode().value());
        verify(fixture.store, never()).attestDomain(
                anyLong(), anyString(), anyString(), anyString(), any(LocalDateTime.class));
    }

    private static Fixture fixture() {
        MemoryErasureStore store = mock(MemoryErasureStore.class);
        PersonalMemoryService personalMemoryService = mock(PersonalMemoryService.class);
        ContextMemoryOutboxMapper outboxMapper = mock(ContextMemoryOutboxMapper.class);
        PersonalMemoryKnowledgeErasureClient knowledgeClient =
                mock(PersonalMemoryKnowledgeErasureClient.class);
        RuntimeSessionRetentionGateway runtimeGateway = mock(RuntimeSessionRetentionGateway.class);
        PersonalMemoryErasureIdentity identity =
                new PersonalMemoryErasureIdentity("dedicated-erasure-test-key");
        return new Fixture(new MemoryErasureOrchestrationService(
                store, personalMemoryService, outboxMapper, knowledgeClient,
                runtimeGateway, identity), store, personalMemoryService,
                outboxMapper, knowledgeClient, runtimeGateway, identity);
    }

    private static MemoryErasureRequestEntity request(PersonalMemoryErasureIdentity identity) {
        MemoryErasureRequestEntity request = new MemoryErasureRequestEntity();
        request.setId(1L);
        request.setRequestId("request-42");
        request.setClientRequestId("client-42");
        request.setTenantId("default");
        request.setRuntimeUserId("user-1");
        request.setRuntimeUserHash(identity.hash("default", "user-1"));
        request.setReasonCode("PRIVACY_REQUEST");
        request.setReferenceId("ticket/42");
        request.setRequestedByHash(identity.hash("default", "actor:platform-admin-7"));
        request.setStatus("RUNNING");
        request.setAttemptCount(1);
        request.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        request.setUpdatedAt(LocalDateTime.now());
        return request;
    }

    private static void wireProcessStore(Fixture fixture,
                                         MemoryErasureRequestEntity request,
                                         List<MemoryErasureDomainEntity> domains) {
        when(fixture.store.dueRequestIds(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(1L));
        when(fixture.store.claim(eq(1L), anyString(), any(LocalDateTime.class), eq(300)))
                .thenReturn(request);
        when(fixture.store.domains(1L)).thenAnswer(ignored -> domains);
        doAnswer(invocation -> {
            MemoryErasureDomainEntity domain = find(domains, invocation.getArgument(1));
            domain.setStatus(invocation.getArgument(2));
            domain.setResultCode(invocation.getArgument(3));
            domain.setAffectedCount(invocation.getArgument(4));
            domain.setLastFailureCode(invocation.getArgument(5));
            domain.setEvidenceReference(invocation.getArgument(6));
            domain.setAttemptCount((domain.getAttemptCount() == null ? 0 : domain.getAttemptCount()) + 1);
            domain.setUpdatedAt(invocation.getArgument(7));
            domain.setCompletedAt("COMPLETED".equals(domain.getStatus())
                    ? invocation.getArgument(7) : null);
            return null;
        }).when(fixture.store).updateDomain(eq(1L), anyString(), anyString(),
                any(), any(), any(), any(), any(LocalDateTime.class));
        doAnswer(invocation -> {
            request.setStatus(invocation.getArgument(2));
            request.setNextAttemptAt(invocation.getArgument(3));
            request.setLastFailureCode(invocation.getArgument(4));
            if ((boolean) invocation.getArgument(5)) request.setRuntimeUserId(null);
            if ((boolean) invocation.getArgument(6)) {
                request.setAutomatedCompletedAt(invocation.getArgument(8));
            }
            if ((boolean) invocation.getArgument(7)) {
                request.setCompletedAt(invocation.getArgument(8));
            }
            request.setUpdatedAt(invocation.getArgument(8));
            return null;
        }).when(fixture.store).finishAttempt(eq(1L), anyString(), anyString(),
                any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(LocalDateTime.class));
    }

    private static List<MemoryErasureDomainEntity> domainInventory(Long requestId) {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put(MemoryErasureOrchestrationService.CONTROL_PERSONAL_MEMORY, "AUTOMATED");
        modes.put(MemoryErasureOrchestrationService.CONTROL_CANDIDATE_OUTBOX, "AUTOMATED");
        modes.put(MemoryErasureOrchestrationService.KNOWLEDGE_PERSONAL_PROJECTION, "AUTOMATED");
        modes.put(MemoryErasureOrchestrationService.RUNTIME_SESSION_STATE, "AUTOMATED");
        modes.put(MemoryErasureOrchestrationService.RUNTIME_CONVERSATION_LEDGER, "AUTOMATED");
        modes.put(MemoryErasureOrchestrationService.RUNOPS_TRACE_INTERACTION, "MANUAL_EVIDENCE");
        modes.put(MemoryErasureOrchestrationService.BUSINESS_INDEX, "MANUAL_EVIDENCE");
        modes.put(MemoryErasureOrchestrationService.BUSINESS_SOURCE_SYSTEM, "MANUAL_EVIDENCE");
        modes.put(MemoryErasureOrchestrationService.BACKUP_EXPIRY_AND_RESTORE, "MANUAL_EVIDENCE");
        return modes.entrySet().stream()
                .map(entry -> domain(requestId, entry.getKey(), entry.getValue(),
                        "AUTOMATED".equals(entry.getValue()) ? "PENDING" : "WAITING_EVIDENCE"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static MemoryErasureDomainEntity domain(Long requestId,
                                                     String code,
                                                     String mode,
                                                     String status) {
        MemoryErasureDomainEntity domain = new MemoryErasureDomainEntity();
        domain.setId((long) Math.abs(code.hashCode()));
        domain.setErasureRequestId(requestId);
        domain.setDomainCode(code);
        domain.setOwnerService("owner");
        domain.setExecutionMode(mode);
        domain.setStatus(status);
        domain.setAttemptCount(0);
        domain.setCreatedAt(LocalDateTime.now());
        domain.setUpdatedAt(LocalDateTime.now());
        return domain;
    }

    private static void complete(List<MemoryErasureDomainEntity> domains, String code) {
        MemoryErasureDomainEntity domain = find(domains, code);
        domain.setStatus("COMPLETED");
        domain.setResultCode("ERASED");
        domain.setCompletedAt(LocalDateTime.now());
    }

    private static boolean completed(MemoryErasureDomainEntity domain) {
        return "COMPLETED".equals(domain.getStatus());
    }

    private static MemoryErasureDomainEntity find(
            List<MemoryErasureDomainEntity> domains, String code) {
        return domains.stream()
                .filter(value -> code.equals(value.getDomainCode()))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(
            MemoryErasureOrchestrationService service,
            MemoryErasureStore store,
            PersonalMemoryService personalMemoryService,
            ContextMemoryOutboxMapper outboxMapper,
            PersonalMemoryKnowledgeErasureClient knowledgeClient,
            RuntimeSessionRetentionGateway runtimeGateway,
            PersonalMemoryErasureIdentity identity) {
    }
}
