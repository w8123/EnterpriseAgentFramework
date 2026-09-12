package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Trace owns root-span creation, resumption and terminal-state protection. */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeTraceRootService {
    private static final List<String> OPEN = List.of("RUNNING", "WAITING_USER", "WAITING_APPROVAL");
    private static final List<String> WAITING = List.of("WAITING_USER", "WAITING_APPROVAL");
    private static final Set<String> COMPLETION = Set.of("SUCCESS", "FAILED", "ERROR", "CANCELLED", "TIMEOUT",
            "WAITING_USER", "WAITING_APPROVAL");
    private final RuntimeTraceSpanMapper spans;
    private final ObjectMapper json;

    public Handle start(Start request) {
        return start(request, Set.of("WORKFLOW", "SUPERVISOR"), false);
    }

    /** MCP must persist its root before starting a governed external call. */
    public Handle startMcp(Start request) {
        return start(request, Set.of("MCP_TOOLS_CALL"), true);
    }

    private Handle start(Start request, Set<String> rootTypes, boolean requirePersistence) {
        Objects.requireNonNull(request, "request");
        required(request.traceId(), "traceId");
        required(request.spanId(), "spanId");
        Objects.requireNonNull(request.startedAt(), "startedAt");
        if (request.spanType() == null || !rootTypes.contains(request.spanType())) {
            throw new IllegalArgumentException("Unsupported Trace root type: " + request.spanType());
        }
        var root = new RuntimeTraceSpanEntity();
        root.setTraceId(request.traceId());
        root.setSpanId(request.spanId());
        root.setSpanType(request.spanType());
        root.setRuntimeType(request.runtimeType());
        root.setAgentId(request.agentId());
        root.setAgentName(request.agentName());
        root.setNodeId(request.nodeId());
        root.setToolName(request.toolName());
        root.setModelInstanceId(request.modelInstanceId());
        root.setProjectCode(request.projectCode());
        root.setTenantId(request.tenantId());
        root.setAppId(request.appId());
        root.setPageInstanceId(request.pageInstanceId());
        root.setStatus("RUNNING");
        root.setInputSummary(encode(WorkflowTraceSanitizer.sanitizeInputSummary(request.input())));
        root.setMetadataJson(request.metadataJson());
        root.setStartedAt(request.startedAt());
        root.setCreatedAt(request.startedAt());
        int inserted = spans.insert(root);
        if (requirePersistence && (inserted != 1 || root.getId() == null)) {
            throw new IllegalStateException("MCP Trace root was not persisted");
        }
        return handle(root);
    }

    public Handle startBestEffort(Start request) {
        try { return start(request); }
        catch (Exception failure) {
            log.warn("Cannot persist Trace root: {}", failure.getClass().getSimpleName());
            return new Handle(null, request.traceId(), request.spanId(), request.startedAt(),
                    new Scope(request.projectCode(), request.tenantId(), request.appId()));
        }
    }

    @Transactional
    public boolean finishBestEffort(Handle handle, Completion completion) {
        try { return finish(handle, completion); }
        catch (Exception failure) {
            log.warn("Cannot finish Trace root: {}", failure.getClass().getSimpleName());
            return true;
        }
    }

    /** Missing persistence remains best effort; a known closed/mismatched root rejects late completion. */
    @Transactional
    public boolean finish(Handle handle, Completion completion) {
        return finish(handle, completion, Set.of("WORKFLOW", "SUPERVISOR"), COMPLETION, null);
    }

    /** Preserve MCP status names and safe rejection summaries without rewriting the root identity. */
    @Transactional
    public boolean finishMcp(Handle handle, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        return finish(handle, completion, Set.of("MCP_TOOLS_CALL"), Set.of("SUCCESS", "ERROR", "TIMED_OUT"),
                WorkflowTraceSanitizer.sanitizeRejectionSummary(completion.code()));
    }

    private boolean finish(Handle handle, Completion completion, Set<String> rootTypes,
                           Set<String> completionStatuses, String errorSummaryOverride) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(completion, "completion");
        String status = required(completion.status(), "status").toUpperCase(Locale.ROOT);
        if (!completionStatuses.contains(status)) throw new IllegalArgumentException("Unsupported Trace completion: " + status);
        Objects.requireNonNull(completion.endedAt(), "endedAt");
        if (handle.id() == null) return true;
        String metadataJson = null;
        if (completion.metadataJson() != null) {
            var current = locked(handle);
            if (!matches(current, handle, rootTypes) || !OPEN.contains(current.getStatus())) return false;
            ObjectNode metadata = metadata(current.getMetadataJson());
            metadata.setAll(metadata(completion.metadataJson()));
            metadataJson = metadata.toString();
        }
        boolean waiting = WAITING.contains(status);
        String answer = WorkflowTraceSanitizer.sanitizeAnswer(completion.answer());
        var update = Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getId, handle.id())
                .eq(RuntimeTraceSpanEntity::getTraceId, handle.traceId())
                .eq(RuntimeTraceSpanEntity::getSpanId, handle.spanId())
                .isNull(RuntimeTraceSpanEntity::getParentSpanId)
                .in(RuntimeTraceSpanEntity::getSpanType, rootTypes)
                .in(RuntimeTraceSpanEntity::getStatus, OPEN)
                .set(RuntimeTraceSpanEntity::getStatus, status)
                .set(RuntimeTraceSpanEntity::getOutputSummary, answer)
                .set(RuntimeTraceSpanEntity::getErrorCode, waiting || "SUCCESS".equals(status) ? null : completion.code())
                .set(RuntimeTraceSpanEntity::getErrorMessage, waiting || "SUCCESS".equals(status) ? null
                        : errorSummaryOverride == null ? answer : errorSummaryOverride)
                .set(RuntimeTraceSpanEntity::getLatencyMs, waiting ? null : latency(handle.startedAt(), completion.endedAt()))
                .set(RuntimeTraceSpanEntity::getEndedAt, waiting ? null : completion.endedAt());
        if (metadataJson != null) update.set(RuntimeTraceSpanEntity::getMetadataJson, metadataJson);
        return spans.update(null, update) == 1;
    }

    /** Merge only binding evidence; root lifecycle and concurrently committed metadata stay intact. */
    @Transactional
    public boolean recordSkillBindings(Handle handle, String bindingsJson) {
        Objects.requireNonNull(handle, "handle");
        var current = locked(handle);
        if (current == null) return handle.id() == null;
        if (!matches(current, handle) || !"SUPERVISOR".equals(current.getSpanType())) return false;
        ObjectNode metadata = metadata(current.getMetadataJson());
        try {
            var bindings = json.readTree(bindingsJson);
            if (bindings == null || !bindings.isArray()) throw new IllegalArgumentException("Skill bindings must be an array");
            metadata.put("skillBindingCount", bindings.size());
            metadata.set("skillBindings", bindings);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid Skill binding snapshot", invalid);
        }
        return spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getId, current.getId())
                .eq(RuntimeTraceSpanEntity::getTraceId, handle.traceId())
                .eq(RuntimeTraceSpanEntity::getSpanId, handle.spanId())
                .isNull(RuntimeTraceSpanEntity::getParentSpanId)
                .eq(RuntimeTraceSpanEntity::getSpanType, "SUPERVISOR")
                .set(RuntimeTraceSpanEntity::getMetadataJson, metadata.toString())) == 1;
    }

    private RuntimeTraceSpanEntity locked(Handle handle) {
        return handle.id() == null
                ? spans.selectByIdentityForUpdate(handle.traceId(), handle.spanId())
                : spans.selectByIdForUpdate(handle.id());
    }

    private boolean matches(RuntimeTraceSpanEntity root, Handle handle) {
        return matches(root, handle, Set.of("SUPERVISOR", "WORKFLOW"));
    }

    private boolean matches(RuntimeTraceSpanEntity root, Handle handle, Set<String> rootTypes) {
        return root != null && (handle.id() == null || Objects.equals(root.getId(), handle.id()))
                && Objects.equals(root.getTraceId(), handle.traceId())
                && Objects.equals(root.getSpanId(), handle.spanId())
                && root.getParentSpanId() == null
                && root.getSpanType() != null && rootTypes.contains(root.getSpanType());
    }

    private ObjectNode metadata(String value) {
        if (!StringUtils.hasText(value)) return json.createObjectNode();
        try {
            var node = json.readTree(value);
            if (node instanceof ObjectNode object) return object;
            throw new IllegalArgumentException("Root metadata must be an object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid root metadata", invalid);
        }
    }

    public Handle resumeSupervisor(String traceId) {
        return resumeRoot(traceId, "SUPERVISOR");
    }

    public Handle resumeWorkflow(String traceId) {
        return resumeRoot(traceId, "WORKFLOW");
    }

    private Handle resumeRoot(String traceId, String spanType) {
        if (!StringUtils.hasText(traceId)) return null;
        var root = spans.selectOne(Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId.trim())
                .eq(RuntimeTraceSpanEntity::getSpanType, spanType)
                .isNull(RuntimeTraceSpanEntity::getParentSpanId)
                .orderByAsc(RuntimeTraceSpanEntity::getId).last("LIMIT 1"));
        if (root == null) return null;
        if ("RUNNING".equals(root.getStatus())) return handle(root);
        if (!WAITING.contains(root.getStatus())) throw closed(root.getTraceId());
        int changed = spans.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getId, root.getId())
                .eq(RuntimeTraceSpanEntity::getTraceId, root.getTraceId())
                .eq(RuntimeTraceSpanEntity::getSpanId, root.getSpanId())
                .eq(RuntimeTraceSpanEntity::getSpanType, spanType)
                .isNull(RuntimeTraceSpanEntity::getParentSpanId)
                .in(RuntimeTraceSpanEntity::getStatus, WAITING)
                .set(RuntimeTraceSpanEntity::getStatus, "RUNNING")
                .set(RuntimeTraceSpanEntity::getEndedAt, null)
                .set(RuntimeTraceSpanEntity::getLatencyMs, null)
                .set(RuntimeTraceSpanEntity::getErrorCode, null)
                .set(RuntimeTraceSpanEntity::getErrorMessage, null));
        if (changed != 1) throw closed(root.getTraceId());
        return handle(root);
    }

    private Handle handle(RuntimeTraceSpanEntity root) {
        return new Handle(root.getId(), root.getTraceId(), root.getSpanId(),
                root.getStartedAt() == null ? LocalDateTime.now() : root.getStartedAt(),
                new Scope(root.getProjectCode(), root.getTenantId(), root.getAppId()));
    }

    private IllegalStateException closed(String traceId) {
        return new IllegalStateException("TRACE_ROOT_RESUME_CONFLICT: root is no longer waiting: " + traceId);
    }

    private Integer latency(LocalDateTime start, LocalDateTime end) {
        return start == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, ChronoUnit.MILLIS.between(start, end)));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { return "{}"; }
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    /** Stored attribution for child evidence; this is not an authorization or credential identity. */
    public record Scope(String projectCode, String tenantId, String appId) { }

    public record Handle(Long id, String traceId, String spanId, LocalDateTime startedAt, Scope scope) {
        /** Lifecycle-only handles need row identity but do not invent historical attribution. */
        public Handle(Long id, String traceId, String spanId, LocalDateTime startedAt) {
            this(id, traceId, spanId, startedAt, null);
        }
    }
    public record Completion(String status, String code, String answer, LocalDateTime endedAt, String metadataJson) { }

    @Builder
    public record Start(String traceId, String spanId, String spanType, String runtimeType,
                        String agentId, String agentName, String nodeId, String toolName, String modelInstanceId,
                        String projectCode, String tenantId, String appId, String pageInstanceId,
                        Map<String, Object> input, String metadataJson, LocalDateTime startedAt) { }
}
