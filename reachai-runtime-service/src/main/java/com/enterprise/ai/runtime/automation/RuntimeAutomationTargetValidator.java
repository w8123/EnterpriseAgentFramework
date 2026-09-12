package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
final class RuntimeAutomationTargetValidator {

    private final RuntimeAgentPublishedConfigQuery agentTargets;
    private final RuntimeWorkflowExecutionQuery workflowTargets;
    private final RuntimeAutomationJsonSupport json;

    TargetSnapshot validate(RuntimeAutomationViews.TargetCommand command,
                            Long requestedProjectId,
                            String requestedProjectCode) {
        if (command == null || !StringUtils.hasText(command.id()) || command.versionId() == null) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_TARGET_REQUIRED", "target id and exact published versionId are required");
        }
        String type = RuntimeAutomationTypes.upper(command.type());
        if (!RuntimeAutomationTypes.TARGET_TYPES.contains(type)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_TARGET_INVALID", "target.type must be AGENT or WORKFLOW");
        }
        return "AGENT".equals(type)
                ? validateAgent(command.id().trim(), command.versionId(), requestedProjectId, requestedProjectCode)
                : validateWorkflow(command.id().trim(), command.versionId(), requestedProjectId, requestedProjectCode);
    }

    private TargetSnapshot validateAgent(String id,
                                         Long versionId,
                                         Long requestedProjectId,
                                         String requestedProjectCode) {
        RuntimeAgentPublishedConfigQuery.Target target;
        try {
            target = agentTargets.resolve(id, versionId);
        } catch (RuntimeAgentPublishedConfigQuery.LookupFailure failure) {
            throw switch (failure.reason()) {
                case AGENT_UNAVAILABLE -> RuntimeAutomationException.badRequest(
                        "AUTOMATION_AGENT_UNAVAILABLE", "Agent must exist and be enabled");
                case VERSION_INVALID -> RuntimeAutomationException.badRequest(
                        "AUTOMATION_AGENT_VERSION_INVALID", "Agent version must be an exact published version");
            };
        }
        verifyProject(target.projectId(), target.projectCode(), requestedProjectId, requestedProjectCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "AGENT");
        snapshot.put("id", target.agentId());
        snapshot.put("keySlug", target.keySlug());
        snapshot.put("name", target.name());
        snapshot.put("versionId", target.versionId());
        snapshot.put("versionNo", target.versionNo());
        snapshot.put("runtimeType", target.runtimeType());
        snapshot.put("publishedAt", target.publishedAt());
        return new TargetSnapshot("AGENT", target.agentId(), target.versionId(),
                target.projectId(), target.projectCode(), immutableSnapshot(snapshot), json.fingerprint(snapshot));
    }

    private TargetSnapshot validateWorkflow(String id,
                                            Long versionId,
                                            Long requestedProjectId,
                                            String requestedProjectCode) {
        RuntimeWorkflowExecutionQuery.Target target;
        try {
            target = workflowTargets.resolveOne(id, versionId);
        } catch (RuntimeWorkflowExecutionQuery.LookupFailure failure) {
            throw switch (failure.reason()) {
                case WORKFLOW_UNAVAILABLE -> RuntimeAutomationException.badRequest(
                        "AUTOMATION_WORKFLOW_UNAVAILABLE", "Workflow must exist and be active");
                case VERSION_INVALID -> RuntimeAutomationException.badRequest(
                        "AUTOMATION_WORKFLOW_VERSION_INVALID", "Workflow version must be an exact published version");
            };
        }
        var workflow = target.workflow();
        var version = target.version();
        verifyProject(workflow.getProjectId(), workflow.getProjectCode(), requestedProjectId, requestedProjectCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "WORKFLOW");
        snapshot.put("id", workflow.getId());
        snapshot.put("keySlug", workflow.getKeySlug());
        snapshot.put("name", workflow.getName());
        snapshot.put("versionId", version.getId());
        snapshot.put("version", version.getVersion());
        snapshot.put("executionEngine", workflow.getExecutionEngine());
        snapshot.put("publishedAt", version.getPublishedAt());
        return new TargetSnapshot("WORKFLOW", workflow.getId(), version.getId(),
                workflow.getProjectId(), workflow.getProjectCode(), immutableSnapshot(snapshot), json.fingerprint(snapshot));
    }

    private Map<String, Object> immutableSnapshot(Map<String, Object> snapshot) {
        // Published timestamps may be null in legacy development data; keep the evidence key without Map.copyOf's NPE.
        return Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    private void verifyProject(Long actualId,
                               String actualCode,
                               Long requestedId,
                               String requestedCode) {
        if (requestedId != null && actualId != null && !Objects.equals(requestedId, actualId)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_PROJECT_MISMATCH", "target does not belong to the requested project");
        }
        if (StringUtils.hasText(requestedCode) && StringUtils.hasText(actualCode)
                && !requestedCode.trim().equalsIgnoreCase(actualCode.trim())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_PROJECT_MISMATCH", "target does not belong to the requested project");
        }
    }

    record TargetSnapshot(
            String type,
            String id,
            Long versionId,
            Long projectId,
            String projectCode,
            Map<String, Object> metadata,
            String fingerprint) {
    }
}
