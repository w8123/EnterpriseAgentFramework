package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trace-owned projection of a committed Workflow interaction receipt, in the receipt owner's transaction. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowInteractionTraceService {
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "ERROR", "TIMEOUT", "CANCELLED", "SKIPPED", "BUSINESS_TERMINAL");
    private final RuntimeTraceSpanMapper spans;
    private final ObjectMapper json;

    @Transactional
    public void record(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.status() == null || !Set.of("WAITING_USER", "COMPLETED", "FAILED", "CANCELLED").contains(receipt.status())) {
            throw new IllegalArgumentException("Workflow trace receipt requires a supported status");
        }
        Objects.requireNonNull(receipt.endedAt(), "endedAt");
        if (!StringUtils.hasText(receipt.traceId()) || !StringUtils.hasText(receipt.interactionId())) return;
        var candidates = spans.selectList(Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getTraceId, receipt.traceId())
                .eq(RuntimeTraceSpanEntity::getNodeId, receipt.nodeId())
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_NODE")
                .eq(RuntimeTraceSpanEntity::getStatus, "WAITING_USER")
                .isNull(RuntimeTraceSpanEntity::getEndedAt).orderByAsc(RuntimeTraceSpanEntity::getId).last("FOR UPDATE"));
        var matching = candidates.stream().filter(row -> owned(row, receipt)
                && receipt.interactionId().equals(read(row.getMetadataJson()).get("interactionId"))).toList();
        // Trace creation is best effort, so a missing original span must not invent a different call.
        if (matching.isEmpty()) return;
        if (matching.size() != 1) throw new IllegalStateException("Ambiguous Workflow interaction trace");
        var waiting = matching.get(0);
        var parents = spans.selectList(Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getTraceId, receipt.traceId())
                .eq(RuntimeTraceSpanEntity::getSpanId, waiting.getParentSpanId())
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_TOOL").last("FOR UPDATE"));
        if (parents.size() != 1 || !owned(parents.get(0), receipt)) {
            throw new IllegalStateException("Workflow interaction trace parent changed");
        }
        var parent = parents.get(0);
        if (!"WAITING_USER".equals(parent.getStatus()) || parent.getEndedAt() != null) {
            throw new IllegalStateException("Workflow interaction trace parent is terminal");
        }
        Map<String, Object> safeResult = WorkflowTraceSanitizer.sanitizeWorkflowResult(receipt.metadata());
        List<?> nodes = safeResult.get("workflowNodeTraces") instanceof List<?> list ? list : List.of();
        String currentStatus = nodes.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(node -> receipt.nodeId().equals(node.get("nodeId")))
                .map(node -> text(node.get("status"))).filter(Objects::nonNull).filter(TERMINAL::contains).findFirst()
                .orElse(terminalStatus(receipt.status(), safeResult));
        finish(waiting, currentStatus, receipt.code(), receipt.endedAt());
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> node = WorkflowTraceSanitizer.sanitizeNodeTrace(raw);
            String nodeId = text(node.get("nodeId")), status = text(node.get("status"));
            if (!StringUtils.hasText(nodeId) || status == null || !(TERMINAL.contains(status) || "WAITING_USER".equals(status))) continue;
            var child = new RuntimeTraceSpanEntity();
            child.setTraceId(parent.getTraceId()); child.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            child.setParentSpanId(parent.getSpanId()); child.setSpanType("WORKFLOW_NODE"); child.setRuntimeType("LANGGRAPH4J");
            child.setAgentId(parent.getAgentId()); child.setAgentName(parent.getAgentName()); child.setModelInstanceId(parent.getModelInstanceId());
            child.setProjectCode(parent.getProjectCode()); child.setTenantId(parent.getTenantId()); child.setAppId(parent.getAppId()); child.setPageInstanceId(parent.getPageInstanceId());
            child.setNodeId(nodeId); child.setToolName(text(node.get("qualifiedName"))); child.setStatus(status);
            var metadata = new LinkedHashMap<>(node);
            metadata.put("workflowId", receipt.workflowId()); metadata.put("workflowVersionId", receipt.workflowVersionId());
            metadata.put("workflowVersion", read(parent.getMetadataJson()).get("workflowVersion"));
            child.setMetadataJson(write(metadata));
            child.setErrorCode(text(node.get("failureCode")));
            child.setStartedAt(time(node.get("startedAt"), receipt.endedAt()));
            child.setEndedAt("WAITING_USER".equals(status) ? null : time(node.get("endedAt"), receipt.endedAt()));
            child.setLatencyMs(node.get("latencyMs") instanceof Number n ? (int) Math.min(Integer.MAX_VALUE, Math.max(0, n.longValue())) : 0);
            child.setCreatedAt(receipt.endedAt()); spans.insert(child);
        }
        var parentMetadata = new LinkedHashMap<>(read(parent.getMetadataJson()));
        parentMetadata.put("workflowResult", safeResult);
        parent.setMetadataJson(write(parentMetadata));
        if ("WAITING_USER".equals(receipt.status())) {
            spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate().eq(RuntimeTraceSpanEntity::getId, parent.getId())
                    .set(RuntimeTraceSpanEntity::getMetadataJson, parent.getMetadataJson()));
        } else {
            finish(parent, terminalStatus(receipt.status(), safeResult), receipt.code(), receipt.endedAt());
        }
    }

    private boolean owned(RuntimeTraceSpanEntity row, Receipt receipt) {
        Map<String, Object> metadata = read(row.getMetadataJson());
        return Objects.equals(receipt.workflowId(), metadata.get("workflowId"))
                && Objects.equals(text(receipt.workflowVersionId()), text(metadata.get("workflowVersionId")))
                && Objects.equals(receipt.tenantId(), row.getTenantId()) && Objects.equals(receipt.appId(), row.getAppId());
    }

    private void finish(RuntimeTraceSpanEntity row, String status, String code, LocalDateTime endedAt) {
        boolean failed = Set.of("FAILED", "ERROR", "TIMEOUT").contains(status);
        int changed = spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getId, row.getId()).eq(RuntimeTraceSpanEntity::getStatus, "WAITING_USER")
                .isNull(RuntimeTraceSpanEntity::getEndedAt).set(RuntimeTraceSpanEntity::getStatus, status)
                .set(RuntimeTraceSpanEntity::getEndedAt, endedAt).set(RuntimeTraceSpanEntity::getErrorCode, failed ? code : null)
                .set(RuntimeTraceSpanEntity::getMetadataJson, row.getMetadataJson()));
        if (changed != 1) throw new IllegalStateException("Workflow waiting trace changed during receipt commit");
    }

    private String terminalStatus(String status, Map<String, Object> result) {
        if ("CANCELLED".equals(status)) return "CANCELLED";
        if ("FAILED".equals(status)) return "FAILED";
        return "BUSINESS_TERMINAL".equals(result.get("outcomeClass")) ? "BUSINESS_TERMINAL" : "SUCCESS";
    }

    private Map<String, Object> read(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalStateException("Invalid Workflow trace metadata", e); }
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode Workflow trace metadata", e); }
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static LocalDateTime time(Object value, LocalDateTime fallback) {
        return value instanceof Number n ? LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault()) : fallback;
    }

    public record Receipt(String traceId, String interactionId, String nodeId, String workflowId, Long workflowVersionId,
                          String tenantId, String appId, String status, String code, Map<String, Object> metadata, LocalDateTime endedAt) { }
}
