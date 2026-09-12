package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 任务草稿投递的事务凭据；重复请求不写草稿，修正只覆盖上次自动投递的修订。 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowDraftSubmissionService {
    private final RuntimeWorkflowDraftSubmissionMapper submissions;
    private final RuntimeWorkflowDefinitionService workflows;
    private final ObjectMapper json;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T apply(Scope scope, Object content, Function<Attempt, Applied<T>> writer,
                       Function<String, T> readCurrent) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(readCurrent, "readCurrent");
        String workflowId = digest(scope).substring(0, 32);
        String requestHash = digest(Map.of("scope", scope, "content", content));
        var receipt = new RuntimeWorkflowDraftSubmissionEntity();
        receipt.setWorkflowId(workflowId);
        receipt.setRequestHash(requestHash);
        receipt.setSourceType(scope.sourceType());
        receipt.setProjectId(scope.projectId());
        receipt.setProjectCode(scope.projectCode());
        receipt.setTaskId(scope.taskId());
        receipt.setTargetKey(scope.targetKey());
        receipt.setCreatedAt(LocalDateTime.now(Clock.systemUTC()));
        try {
            submissions.insert(receipt);
        } catch (DuplicateKeyException duplicate) {
            var previous = submissions.findForUpdate(workflowId, requestHash);
            if (previous == null) throw duplicate;
            if (previous.getWorkflowRevision() == null) {
                throw new IllegalStateException("Workflow draft submission has no committed revision");
            }
            requireScope(workflows.lockForWrite(workflowId), scope);
            return Objects.requireNonNull(readCurrent.apply(workflowId), "current workflow context");
        }

        RuntimeWorkflowDefinitionEntity current = workflows.findById(workflowId).isPresent()
                ? workflows.lockForWrite(workflowId) : null;
        if (current == null && submissions.latestApplied(workflowId) != null) {
            throw new IllegalArgumentException("task-scoped workflow was removed; automatic recreation is unavailable");
        }
        String baseRevision = null;
        if (current != null) {
            requireScope(current, scope);
            var lastApplied = submissions.latestApplied(workflowId);
            if (lastApplied == null) {
                throw new IllegalStateException("Workflow has no task draft submission history; automatic replacement is unavailable");
            }
            baseRevision = lastApplied.getWorkflowRevision().toString();
            workflows.assertRevision(current, baseRevision);
            if (!"DRAFT".equalsIgnoreCase(current.getStatus())) {
                throw new IllegalArgumentException("task-scoped workflow is no longer a draft");
            }
        }
        Applied<T> applied = Objects.requireNonNull(writer.apply(new Attempt(workflowId, current, baseRevision)), "applied");
        if (!workflowId.equals(applied.workflowId()) || applied.revision() == null || applied.value() == null) {
            throw new IllegalStateException("Workflow draft writer returned an invalid applied identity or revision");
        }
        var persisted = workflows.findById(workflowId)
                .orElseThrow(() -> new IllegalStateException("Workflow draft writer did not persist the target"));
        requireScope(persisted, scope);
        if (!applied.revision().equals(persisted.getUpdatedAt())) {
            throw new IllegalStateException("Workflow draft writer returned a different persisted revision");
        }
        if (submissions.complete(receipt.getId(), applied.revision()) != 1) {
            throw new IllegalStateException("Workflow draft submission could not record its applied revision");
        }
        return applied.value();
    }

    private void requireScope(RuntimeWorkflowDefinitionEntity workflow, Scope scope) {
        if (!Objects.equals(workflow.getProjectId(), scope.projectId())
                || !scope.projectCode().equalsIgnoreCase(workflow.getProjectCode())) {
            throw new IllegalArgumentException("workflow does not belong to the requested project");
        }
    }

    private String digest(Object value) {
        try {
            byte[] encoded = json.writeValueAsString(canonical(json.valueToTree(value))).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Workflow draft submission cannot be fingerprinted", failure);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isValueNode()) return value;
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        var object = json.createObjectNode();
        var fields = new ArrayList<String>();
        value.fieldNames().forEachRemaining(fields::add);
        fields.stream().sorted().forEach(field -> object.set(field, canonical(value.get(field))));
        return object;
    }

    public record Scope(String sourceType, Long projectId, String projectCode, String taskId, String targetKey) {
        public Scope {
            sourceType = required(sourceType, "sourceType", 48).toUpperCase(Locale.ROOT);
            projectCode = required(projectCode, "projectCode", 96).toLowerCase(Locale.ROOT);
            taskId = required(taskId, "taskId", 64);
            targetKey = required(targetKey, "targetKey", 255);
        }

        private static String required(String value, String field, int limit) {
            if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " is required");
            String normalized = value.trim();
            if (normalized.codePointCount(0, normalized.length()) > limit) {
                throw new IllegalArgumentException(field + " is too long");
            }
            return normalized;
        }
    }

    public record Attempt(String workflowId, RuntimeWorkflowDefinitionEntity current, String baseRevision) { }
    public record Applied<T>(String workflowId, LocalDateTime revision, T value) { }
}
