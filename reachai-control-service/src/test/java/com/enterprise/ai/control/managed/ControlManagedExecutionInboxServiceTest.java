package com.enterprise.ai.control.managed;

import com.enterprise.ai.control.managed.ControlManagedExecutionContracts.EventRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionEvent;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlManagedExecutionInboxServiceTest {

    private final ControlManagedExecutionInboxMapper mapper =
            mock(ControlManagedExecutionInboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ControlManagedExecutionInboxProjector projectionService =
            mock(ControlManagedExecutionInboxProjector.class);
    private final ControlManagedExecutionInboxService service =
            new ControlManagedExecutionInboxService(
                    mapper, objectMapper, List.of(projectionService));

    @Test
    void storesOnlyValidatedRuntimeMetadata() throws Exception {
        EventRequest request = request();
        byte[] exactBody = objectMapper.writeValueAsBytes(request);
        when(mapper.insert(any())).thenReturn(1);

        var response = service.accept(request, exactBody);

        assertThat(response.accepted()).isTrue();
        assertThat(response.idempotentReplay()).isFalse();
        ArgumentCaptor<ControlManagedExecutionInboxEntity> persisted =
                ArgumentCaptor.forClass(ControlManagedExecutionInboxEntity.class);
        verify(mapper).insert(persisted.capture());
        assertThat(persisted.getValue().getSourceRef()).isEqualTo("ait_task_1");
        assertThat(persisted.getValue().getRuntimeStatus()).isEqualTo("RUNNING");
        assertThat(persisted.getValue().getPayloadSha256()).matches("[a-f0-9]{64}");
        assertThat(persisted.getValue().getPayloadJson())
                .doesNotContain("objective", "workerToken", "secret");
    }

    @Test
    void acknowledgesAnExactDuplicateButRejectsEventIdSubstitution() throws Exception {
        EventRequest request = request();
        byte[] exactBody = objectMapper.writeValueAsBytes(request);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        ControlManagedExecutionInboxEntity existing = new ControlManagedExecutionInboxEntity();
        existing.setEventId(request.eventId());
        existing.setExecutionId(request.executionId());
        existing.setEventType(request.eventType());
        existing.setPayloadSha256(com.enterprise.ai.common.internalauth.InternalServiceHmac
                .bodySha256Hex(exactBody));
        when(mapper.selectById(request.eventId())).thenReturn(existing);

        assertThat(service.accept(request, exactBody).idempotentReplay()).isTrue();

        existing.setExecutionId("mex_substituted");
        assertThatThrownBy(() -> service.accept(request, exactBody))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("replay does not match");
    }

    @Test
    void rejectsMismatchedEnvelopeAndPayloadExecutionIds() throws Exception {
        EventRequest valid = request();
        ((ObjectNode) valid.payload()).put("executionId", "mex_other");
        byte[] exactBody = objectMapper.writeValueAsBytes(valid);

        assertThatThrownBy(() -> service.accept(valid, exactBody))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("event is invalid");
    }

    @Test
    void projectsClaimedEventThroughTheProductPort() {
        ControlManagedExecutionInboxEntity stored = new ControlManagedExecutionInboxEntity();
        stored.setEventId("mout_1");
        stored.setExecutionId("mex_1");
        stored.setEventType("MANAGED_EXECUTION_STATUS_CHANGED");
        stored.setTenantId("tenant-a");
        stored.setProjectCode("PROJECT_A");
        stored.setSourceType("AI_CODING_TASK");
        stored.setSourceRef("ait_task_1");
        stored.setRuntimeStatus("RUNNING");
        stored.setPayloadJson("{\"schema\":\"reachai.managed-execution.outbox.v1\"}");
        stored.setProjectionAttemptCount(1);
        when(mapper.claimProjection(eq("mout_1"), any(), any())).thenReturn(1);
        when(mapper.selectById("mout_1")).thenReturn(stored);
        when(projectionService.supports(any())).thenReturn(true);
        when(projectionService.project(any())).thenReturn(ProjectionResult.applied());

        service.projectOne("mout_1");

        ArgumentCaptor<ProjectionEvent> event = ArgumentCaptor.forClass(ProjectionEvent.class);
        verify(projectionService).project(event.capture());
        assertThat(event.getValue().sourceType()).isEqualTo("AI_CODING_TASK");
        assertThat(event.getValue().sourceRef()).isEqualTo("ait_task_1");
        verify(mapper).finishProjection(
                eq("mout_1"), eq("APPLIED"), isNull(), any(), any());
    }

    private EventRequest request() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema", "reachai.managed-execution.outbox.v1");
        payload.put("executionId", "mex_inbox_1");
        payload.put("tenantId", "tenant-a");
        payload.put("projectCode", "PROJECT_A");
        payload.put("sourceType", "AI_CODING_TASK");
        payload.put("sourceRef", "ait_task_1");
        payload.put("status", "RUNNING");
        return new EventRequest(
                "reachai.managed-execution.control-event.v1",
                "mout_0123456789abcdef0123456789abcdef",
                "mex_inbox_1",
                "MANAGED_EXECUTION_STATUS_CHANGED",
                payload);
    }
}
