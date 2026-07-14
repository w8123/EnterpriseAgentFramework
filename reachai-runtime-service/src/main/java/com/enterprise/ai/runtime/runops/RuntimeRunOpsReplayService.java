package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeRunOpsReplayService {

    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeAgentExecutionService agentExecutionService;

    public ReplayResult replay(String traceId, ReplayRequest request) {
        String originalTraceId = requiredText(traceId, "traceId is required");
        RuntimeRunOpsDetailView detail = runOpsQueryService.detail(originalTraceId);
        RuntimeRunOpsSummaryView source = detail == null ? null : detail.summary();
        if (source == null) {
            throw new IllegalArgumentException("RunOps trace detail is empty: " + originalTraceId);
        }
        if (!StringUtils.hasText(source.agentId())) {
            throw new IllegalArgumentException("Replay requires an Agent root run: " + originalTraceId);
        }
        if (source.agentConfigVersionId() == null) {
            throw new IllegalArgumentException("Replay source has no published Agent config version: " + originalTraceId);
        }

        String message = firstText(request == null ? null : request.messageOverride(), source.inputSummary());
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("Unable to restore replay input from runtime_run; provide messageOverride");
        }
        String sessionId = firstText(request == null ? null : request.sessionId(), replaySessionId());
        String userId = firstText(request == null ? null : request.userId(), source.userId(), "runops-replay");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("replay", true);
        metadata.put("entryType", "REPLAY");
        metadata.put("replayOfTraceId", originalTraceId);
        putIfText(metadata, "projectCode", source.projectCode());
        putIfText(metadata, "tenantId", source.tenantId());

        Map<String, Object> executionRequest = new LinkedHashMap<>();
        executionRequest.put("agentId", source.agentId());
        executionRequest.put("message", message);
        executionRequest.put("input", message);
        executionRequest.put("sessionId", sessionId);
        executionRequest.put("userId", userId);
        executionRequest.put("entryType", "REPLAY");
        executionRequest.put("replayOfTraceId", originalTraceId);
        executionRequest.put("metadata", metadata);
        putIfText(executionRequest, "projectCode", source.projectCode());
        putIfText(executionRequest, "tenantId", source.tenantId());
        if (request != null && request.roles() != null && !request.roles().isEmpty()) {
            executionRequest.put("roles", List.copyOf(request.roles()));
        }

        Map<String, Object> execution = agentExecutionService.executePublishedConfig(
                source.agentId(), source.agentConfigVersionId(), executionRequest, true);
        Map<String, Object> resultMetadata = mapValue(execution == null ? null : execution.get("metadata"));
        return new ReplayResult(
                originalTraceId,
                firstText(text(resultMetadata.get("traceId")), text(resultMetadata.get("replayTraceId"))),
                sessionId,
                userId,
                source.agentId(),
                source.agentName(),
                source.agentConfigVersionId(),
                source.agentConfigVersion(),
                message,
                Boolean.TRUE.equals(execution == null ? null : execution.get("success")),
                text(execution == null ? null : execution.get("answer")),
                resultMetadata.isEmpty() ? Map.of() : resultMetadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String replaySessionId() {
        return "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String requiredText(String value, String error) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(error);
        }
        return value.trim();
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value);
        return StringUtils.hasText(normalized) ? normalized.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record ReplayRequest(String messageOverride,
                                String sessionId,
                                String userId,
                                List<String> roles) {
    }

    public record ReplayResult(String originalTraceId,
                               String replayTraceId,
                               String sessionId,
                               String userId,
                               String agentId,
                               String agentName,
                               Long agentConfigVersionId,
                               Integer agentConfigVersion,
                               String message,
                               boolean success,
                               String answer,
                               Map<String, Object> metadata) {
    }
}
