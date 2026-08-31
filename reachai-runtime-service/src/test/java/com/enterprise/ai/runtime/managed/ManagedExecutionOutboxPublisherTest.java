package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionControlEventClient.DeliveryException;
import com.enterprise.ai.runtime.managed.ManagedExecutionControlEventClient.PublishRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionControlEventClient.PublishResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedExecutionOutboxPublisherTest {

    private final ManagedExecutionOutboxMapper mapper = mock(ManagedExecutionOutboxMapper.class);
    private final ManagedExecutionControlEventClient client =
            mock(ManagedExecutionControlEventClient.class);
    private final ManagedExecutionOutboxPublisher publisher = new ManagedExecutionOutboxPublisher(
            mapper, client, new ObjectMapper(), "publisher-test", 5, 60);

    @Test
    void publishesAClaimedMetadataOnlyEventAndCompletesItsFence() {
        ManagedExecutionOutboxEntity event = event();
        when(mapper.findPublishCandidateId(any())).thenReturn(17L).thenReturn(null);
        when(mapper.claimForPublish(eq(17L), eq("publisher-test"), anyString(), any(), any()))
                .thenReturn(1);
        when(mapper.selectById(17L)).thenReturn(event);
        when(client.publish(any())).thenReturn(new PublishResponse(
                "reachai.managed-execution.control-event-ack.v1", event.getEventId(), true, false));
        when(mapper.markPublished(eq(17L), anyString(), any())).thenReturn(1);

        publisher.publishDue();

        ArgumentCaptor<PublishRequest> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(client).publish(request.capture());
        assertThat(request.getValue().schema()).isEqualTo("reachai.managed-execution.control-event.v1");
        assertThat(request.getValue().eventId()).isEqualTo(event.getEventId());
        assertThat(request.getValue().payload().path("status").asText()).isEqualTo("RUNNING");
        assertThat(request.getValue().payload().toString())
                .doesNotContain("objective", "workerToken", "secret");
        verify(mapper).markPublished(eq(17L), anyString(), any());
        verify(mapper, never()).releaseAfterFailure(
                any(), anyString(), anyString(), any(), any());
    }

    @Test
    void uncertainDeliveryIsReleasedForIdempotentRetryUsingOnlyASafeCode() {
        ManagedExecutionOutboxEntity event = event();
        event.setAttemptCount(3);
        when(mapper.findPublishCandidateId(any())).thenReturn(17L).thenReturn(null);
        when(mapper.claimForPublish(eq(17L), eq("publisher-test"), anyString(), any(), any()))
                .thenReturn(1);
        when(mapper.selectById(17L)).thenReturn(event);
        when(client.publish(any())).thenThrow(new DeliveryException(
                "MANAGED_CONTROL_DELIVERY_UNCERTAIN", "Bearer secret-should-not-leak"));

        publisher.publishDue();

        verify(mapper, never()).markPublished(any(), anyString(), any());
        verify(mapper).releaseAfterFailure(
                eq(17L),
                anyString(),
                eq("MANAGED_CONTROL_DELIVERY_UNCERTAIN"),
                any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    private ManagedExecutionOutboxEntity event() {
        ManagedExecutionOutboxEntity event = new ManagedExecutionOutboxEntity();
        event.setId(17L);
        event.setEventId("mout_0123456789abcdef0123456789abcdef");
        event.setExecutionId("mex_outbox_1");
        event.setEventType("MANAGED_EXECUTION_STATUS_CHANGED");
        event.setPayloadJson("""
                {"schema":"reachai.managed-execution.outbox.v1","executionId":"mex_outbox_1",
                "tenantId":"tenant-a","projectCode":"PROJECT_A","sourceType":"AI_CODING_TASK",
                "sourceRef":"ait_1","status":"RUNNING"}
                """);
        event.setStatus("PUBLISHING");
        event.setAttemptCount(1);
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
