package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSpanView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsToolCallView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeAgentExecutionService agentExecutionService;

    public ReplayResult replay(String traceId, ReplayRequest request) {
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId is required");
        }
        String normalizedTraceId = traceId.trim();
        RuntimeRunOpsDetailView detail = runOpsQueryService.detail(normalizedTraceId);
        RuntimeRunOpsSummaryView summary = detail == null ? null : detail.summary();
        if (summary == null) {
            throw new IllegalArgumentException("RunOps trace detail is empty: " + normalizedTraceId);
        }

        boolean useSnapshot = request == null || request.useSnapshot() == null || request.useSnapshot();
        String message = firstText(request == null ? null : request.messageOverride(), replayMessage(detail));
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("Unable to restore replay input from trace; provide messageOverride");
        }
        String sessionId = firstText(request == null ? null : request.sessionId(), replaySessionId());
        String userId = firstText(request == null ? null : request.userId(), summary.userId(), firstToolUserId(detail), "runops-replay");
        String agentLookup = firstText(summary.entryAgentId(), summary.entryAgentKeySlug(), summary.agentId());
        if (!StringUtils.hasText(agentLookup)) {
            throw new IllegalArgumentException("Unable to resolve replay Agent entry from trace: " + normalizedTraceId);
        }

        Map<String, Object> replayMetadata = replayMetadata(normalizedTraceId, summary, useSnapshot);
        Map<String, Object> executionRequest = new LinkedHashMap<>();
        executionRequest.put("agentDefinitionId", agentLookup);
        executionRequest.put("message", message);
        executionRequest.put("input", message);
        executionRequest.put("sessionId", sessionId);
        executionRequest.put("userId", userId);
        executionRequest.put("metadata", replayMetadata);
        if (request != null && request.roles() != null) {
            executionRequest.put("roles", request.roles());
        }
        putIfText(executionRequest, "projectCode", text(summary.metadata() == null ? null : summary.metadata().get("projectCode")));
        putIfText(executionRequest, "intentHint", summary.intentType());

        Map<String, Object> execution = agentExecutionService.execute(executionRequest, true);
        Map<String, Object> resultMetadata = mapValue(execution == null ? null : execution.get("metadata"));
        return new ReplayResult(
                normalizedTraceId,
                firstText(text(resultMetadata.get("traceId")), text(resultMetadata.get("replayTraceId"))),
                sessionId,
                userId,
                agentLookup,
                summary.agentName(),
                summary.version(),
                summary.versionId(),
                message,
                Boolean.TRUE.equals(execution == null ? null : execution.get("success")),
                text(execution == null ? null : execution.get("answer")),
                resultMetadata.isEmpty() ? Map.of() : resultMetadata,
                summary.workflowId(),
                summary.workflowKeySlug(),
                summary.workflowVersion(),
                summary.workflowVersionId(),
                summary.entryAgentId(),
                summary.entryAgentKeySlug(),
                summary.sourceType(),
                summary.sourceId(),
                "RUNTIME_AGENT_EXECUTION",
                null);
    }

    private Map<String, Object> replayMetadata(String traceId, RuntimeRunOpsSummaryView summary, boolean useSnapshot) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("replay", true);
        metadata.put("replayOfTraceId", traceId);
        metadata.put("replayUseSnapshot", useSnapshot);
        putIfText(metadata, "workflowId", summary.workflowId());
        putIfText(metadata, "workflowKeySlug", summary.workflowKeySlug());
        putIfText(metadata, "workflowVersion", summary.workflowVersion());
        putIfPresent(metadata, "workflowVersionId", summary.workflowVersionId());
        putIfText(metadata, "entryAgentId", summary.entryAgentId());
        putIfText(metadata, "entryAgentKeySlug", summary.entryAgentKeySlug());
        putIfText(metadata, "sourceType", summary.sourceType());
        putIfText(metadata, "sourceId", summary.sourceId());
        putIfText(metadata, "replaySourceVersion", summary.version());
        putIfPresent(metadata, "replaySourceVersionId", summary.versionId());
        return metadata;
    }

    private String replayMessage(RuntimeRunOpsDetailView detail) {
        if (detail == null) {
            return null;
        }
        if (detail.spans() != null) {
            for (RuntimeRunOpsSpanView span : detail.spans()) {
                if ("AGENT_RUN".equalsIgnoreCase(span.spanType()) && StringUtils.hasText(span.inputSummary())) {
                    return span.inputSummary().trim();
                }
            }
        }
        if (detail.toolCalls() != null) {
            for (RuntimeRunOpsToolCallView tool : detail.toolCalls()) {
                Map<String, Object> args = parseMap(tool.argsJson());
                String message = firstText(text(args.get("userInput")), text(args.get("input")), text(args.get("message")));
                if (StringUtils.hasText(message)) {
                    return message;
                }
            }
        }
        return null;
    }

    private String firstToolUserId(RuntimeRunOpsDetailView detail) {
        if (detail == null || detail.toolCalls() == null) {
            return null;
        }
        return detail.toolCalls().stream()
                .map(RuntimeRunOpsToolCallView::userId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> parseMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String replaySessionId() {
        return "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
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
                                List<String> roles,
                                Boolean useSnapshot) {
    }

    public record ReplayResult(String originalTraceId,
                               String replayTraceId,
                               String sessionId,
                               String userId,
                               String agentId,
                               String agentName,
                               String version,
                               Long versionId,
                               String message,
                               boolean success,
                               String answer,
                               Map<String, Object> metadata,
                               String workflowId,
                               String workflowKeySlug,
                               String workflowVersion,
                               Long workflowVersionId,
                               String entryAgentId,
                               String entryAgentKeySlug,
                               String sourceType,
                               String sourceId,
                               String executionPath,
                               String fallbackReason) {
    }
}
