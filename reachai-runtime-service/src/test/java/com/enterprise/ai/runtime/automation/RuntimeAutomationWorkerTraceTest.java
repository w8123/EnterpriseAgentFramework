package com.enterprise.ai.runtime.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeAutomationWorkerTraceTest {
    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "SCHEDULE"})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void businessTraceUsesThePersistedAttemptRootInsteadOfCallerInput(String source) {
        var occurrences = mock(RuntimeAutomationOccurrenceMapper.class);
        var slots = mock(RuntimeAutomationExecutionSlotMapper.class);
        var automations = mock(RuntimeAutomationMapper.class);
        var versions = mock(RuntimeAutomationVersionMapper.class);
        var target = mock(RuntimeAutomationTargetExecutor.class);
        var persistence = mock(RuntimeAutomationExecutionPersistenceService.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var automation = new RuntimeAutomationEntity();
        automation.setId(101L);
        automation.setAutomationKey("aut-trace");
        automation.setStatus("ACTIVE");
        automation.setCurrentVersionId(201L);
        automation.setTenantId("trusted-tenant");
        automation.setProjectCode("trusted-project");
        var version = new RuntimeAutomationVersionEntity();
        version.setId(201L);
        version.setAutomationId(101L);
        version.setConcurrencyPolicy("QUEUE");
        version.setTriggerType("ONCE");
        var occurrence = new RuntimeAutomationOccurrenceEntity();
        occurrence.setId(301L);
        occurrence.setAutomationId(101L);
        occurrence.setAutomationVersionId(201L);
        occurrence.setSourceType(source);
        occurrence.setAttemptCount(1);
        occurrence.setInputSnapshotJson("""
                {"sku":"SKU-001","traceId":"caller-trace","supervisorTraceId":"caller-root",
                 "tenantId":"caller-tenant","projectCode":"caller-project",
                 "metadata":{"supervisorTraceId":"caller-metadata-root"}}
                """);
        var attempt = new RuntimeAutomationAttemptEntity();
        attempt.setId(401L);
        when(occurrences.findLeaseCandidateId()).thenReturn(301L);
        when(occurrences.claim(eq(301L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(call -> { occurrence.setLeaseToken(call.getArgument(2)); return 1; });
        when(occurrences.selectById(301L)).thenReturn(occurrence);
        when(versions.selectById(201L)).thenReturn(version);
        when(automations.selectById(101L)).thenReturn(automation);
        when(slots.owns(eq(101L), eq(0), eq(301L), anyString())).thenReturn(1);
        when(persistence.start(same(occurrence), anyString(), anyString(), anyString()))
                .thenAnswer(call -> { attempt.setTraceId(call.getArgument(3)); return attempt; });
        when(target.execute(same(automation), same(version), same(occurrence), anyString(), anyMap()))
                .thenAnswer(call -> new RuntimeAutomationTargetExecutor.ExecutionOutcome(
                        true, false, call.getArgument(3), null, "done", Map.of(), false));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));

        new RuntimeAutomationWorker(occurrences, slots, automations, versions, target, persistence,
                new RuntimeAutomationJsonSupport(new ObjectMapper()), executor).poll();

        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass((Class) Map.class);
        verify(target).execute(same(automation), same(version), same(occurrence), trace.capture(), input.capture());
        assertNotEquals("caller-trace", trace.getValue());
        assertEquals(attempt.getTraceId(), trace.getValue());
        assertEquals(trace.getValue(), input.getValue().get("traceId"));
        assertEquals(trace.getValue(), input.getValue().get("supervisorTraceId"),
                "The SDK signed trace context must use the server-owned Automation attempt root");
        assertEquals("trusted-tenant", input.getValue().get("tenantId"));
        assertEquals("trusted-project", input.getValue().get("projectCode"));
        assertEquals("SKU-001", input.getValue().get("sku"));
        verify(persistence).complete(same(occurrence), same(attempt), eq(occurrence.getLeaseToken()), any());
        verify(slots).release(101L, 0, 301L, occurrence.getLeaseToken());
    }
}
