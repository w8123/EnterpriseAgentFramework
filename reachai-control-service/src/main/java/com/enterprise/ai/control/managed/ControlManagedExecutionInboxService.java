package com.enterprise.ai.control.managed;

import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.managed.ControlManagedExecutionContracts.EventRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionContracts.EventResponse;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionEvent;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Idempotent metadata-only inbox for Runtime Managed Execution domain events. */
@Service
@RequiredArgsConstructor
public class ControlManagedExecutionInboxService {

    private static final String REQUEST_SCHEMA = "reachai.managed-execution.control-event.v1";
    private static final String RESPONSE_SCHEMA = "reachai.managed-execution.control-event-ack.v1";
    private static final Set<String> EVENT_TYPES = Set.of(
            "MANAGED_EXECUTION_QUEUED",
            "MANAGED_EXECUTION_JOB_DISPATCHED",
            "MANAGED_EXECUTION_PROVISION_FAILED",
            "MANAGED_EXECUTION_PROVISION_OUTCOME_UNKNOWN",
            "MANAGED_EXECUTION_SANDBOX_CLEANED",
            "MANAGED_EXECUTION_SANDBOX_CLEANUP_FAILED",
            "MANAGED_EXECUTION_CANCEL_REQUESTED",
            "MANAGED_EXECUTION_APPROVAL_REQUESTED",
            "MANAGED_EXECUTION_APPROVAL_DECIDED",
            "MANAGED_EXECUTION_APPROVAL_RESOLVED",
            "MANAGED_EXECUTION_PROVISIONING",
            "MANAGED_EXECUTION_STATUS_CHANGED",
            "MANAGED_EXECUTION_TERMINAL",
            "MANAGED_EXECUTION_SUCCEEDED",
            "MANAGED_EXECUTION_FAILED",
            "MANAGED_EXECUTION_TIMED_OUT");
    private static final Set<String> SOURCE_TYPES =
            Set.of("AI_CODING_TASK", "AGENT_DELEGATION", "OPERATOR");
    private static final Set<String> RUNTIME_STATUSES = Set.of(
            "REQUESTED", "QUEUED", "PROVISIONING", "RUNNING", "WAITING_APPROVAL",
            "WAITING_USER", "FINALIZING", "CANCELLING", "SUCCEEDED", "FAILED",
            "TIMED_OUT", "CANCELLED");

    private final ControlManagedExecutionInboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final List<ControlManagedExecutionInboxProjector> projectors;

    @Transactional
    public EventResponse accept(EventRequest request, byte[] exactBody) {
        Validated event = validate(request, exactBody);
        ControlManagedExecutionInboxEntity entity = new ControlManagedExecutionInboxEntity();
        entity.setEventId(event.eventId());
        entity.setExecutionId(event.executionId());
        entity.setEventType(event.eventType());
        entity.setTenantId(event.tenantId());
        entity.setProjectCode(event.projectCode());
        entity.setSourceType(event.sourceType());
        entity.setSourceRef(event.sourceRef());
        entity.setRuntimeStatus(event.runtimeStatus());
        entity.setPayloadSha256(event.payloadSha256());
        entity.setPayloadJson(event.payloadJson());
        entity.setProjectionStatus("RECEIVED");
        entity.setProjectionAttemptCount(0);
        entity.setProjectionAvailableAt(LocalDateTime.now());
        entity.setReceivedAt(LocalDateTime.now());
        try {
            mapper.insert(entity);
            projectStored(entity.getEventId());
            return new EventResponse(RESPONSE_SCHEMA, event.eventId(), true, false);
        } catch (DuplicateKeyException duplicate) {
            ControlManagedExecutionInboxEntity existing = mapper.selectById(event.eventId());
            if (existing == null
                    || !event.executionId().equals(existing.getExecutionId())
                    || !event.eventType().equals(existing.getEventType())
                    || !InternalServiceHmac.digestEqualsConstantTime(
                            event.payloadSha256(), existing.getPayloadSha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Managed Execution event replay does not match the accepted event");
            }
            projectStored(existing.getEventId());
            return new EventResponse(RESPONSE_SCHEMA, event.eventId(), true, true);
        }
    }

    public java.util.List<String> projectionCandidates(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return mapper.findProjectionCandidates(LocalDateTime.now(), limit);
    }

    @Transactional
    public void projectOne(String eventId) {
        projectStored(eventId);
    }

    private void projectStored(String eventId) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.claimProjection(eventId, now, now.plusMinutes(2)) != 1) return;
        ControlManagedExecutionInboxEntity entity = mapper.selectById(eventId);
        if (entity == null) return;
        ProjectionResult result;
        try {
            result = project(event(entity));
        } catch (RuntimeException transientFailure) {
            result = ProjectionResult.pending(
                    "Managed Execution projection temporarily unavailable");
        }
        // claimProjection increments the persisted counter before this row is reloaded.
        int attempts = entity.getProjectionAttemptCount() == null
                ? 1 : Math.max(1, entity.getProjectionAttemptCount());
        long retrySeconds = Math.min(300L,
                2L * (1L << Math.min(7, Math.max(0, attempts - 1))));
        LocalDateTime availableAt = "PENDING".equals(result.status())
                ? now.plusSeconds(retrySeconds)
                : now;
        LocalDateTime appliedAt = Set.of("APPLIED", "IGNORED").contains(result.status())
                ? now : null;
        mapper.finishProjection(
                eventId,
                result.status(),
                boundedProjectionError(result.error()),
                availableAt,
                appliedAt);
    }

    private ProjectionResult project(ProjectionEvent event) {
        List<ControlManagedExecutionInboxProjector> supporting = projectors.stream()
                .filter(projector -> projector.supports(event))
                .toList();
        if (supporting.isEmpty()) {
            return ProjectionResult.ignored();
        }
        if (supporting.size() > 1) {
            return ProjectionResult.failed(
                    "Managed Execution event has multiple product projectors");
        }
        return supporting.get(0).project(event);
    }

    private ProjectionEvent event(ControlManagedExecutionInboxEntity entity) {
        return new ProjectionEvent(
                entity.getEventId(),
                entity.getExecutionId(),
                entity.getEventType(),
                entity.getTenantId(),
                entity.getProjectCode(),
                entity.getSourceType(),
                entity.getSourceRef(),
                entity.getRuntimeStatus(),
                entity.getPayloadJson());
    }

    private String boundedProjectionError(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() <= 1_000
                ? normalized
                : normalized.substring(0, 1_000);
    }

    private Validated validate(EventRequest request, byte[] exactBody) {
        if (request == null || !REQUEST_SCHEMA.equals(request.schema())) invalid();
        String eventId = identifier(request.eventId(), "eventId", 64);
        String executionId = identifier(request.executionId(), "executionId", 64);
        String eventType = enumValue(request.eventType(), EVENT_TYPES);
        JsonNode payload = request.payload();
        if (payload == null || !payload.isObject()
                || !"reachai.managed-execution.outbox.v1".equals(payload.path("schema").asText())
                || !executionId.equals(payload.path("executionId").asText())) {
            invalid();
        }
        String tenantId = identifier(payload.path("tenantId").asText(null), "tenantId", 96);
        String projectCode = identifier(payload.path("projectCode").asText(null), "projectCode", 128)
                .toUpperCase(Locale.ROOT);
        String sourceType = enumValue(payload.path("sourceType").asText(null), SOURCE_TYPES);
        String sourceRef = optionalIdentifier(payload.path("sourceRef").asText(null), "sourceRef", 128);
        String runtimeStatus = enumValue(payload.path("status").asText(null), RUNTIME_STATUSES);
        byte[] body = exactBody == null ? new byte[0] : exactBody;
        if (body.length == 0 || body.length > 1_048_576) invalid();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            invalid();
            return null;
        }
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > 262_144) invalid();
        return new Validated(eventId, executionId, eventType, tenantId, projectCode,
                sourceType, sourceRef, runtimeStatus,
                InternalServiceHmac.bodySha256Hex(body), payloadJson);
    }

    private String identifier(String value, String field, int maximum) {
        if (!StringUtils.hasText(value)) invalid();
        String normalized = value.trim();
        if (normalized.length() > maximum || !normalized.matches("[A-Za-z0-9._:-]+")) invalid();
        return normalized;
    }

    private String optionalIdentifier(String value, String field, int maximum) {
        return StringUtils.hasText(value) ? identifier(value, field, maximum) : null;
    }

    private String enumValue(String value, Set<String> allowed) {
        if (!StringUtils.hasText(value)) invalid();
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) invalid();
        return normalized;
    }

    private void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Managed Execution event is invalid");
    }

    private record Validated(
            String eventId,
            String executionId,
            String eventType,
            String tenantId,
            String projectCode,
            String sourceType,
            String sourceRef,
            String runtimeStatus,
            String payloadSha256,
            String payloadJson) {
    }
}
