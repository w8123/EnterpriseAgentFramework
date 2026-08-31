package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
final class RuntimeAutomationTargetValidator {

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper agentVersionMapper;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;
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
        RuntimeAgentEntity agent = agentMapper.selectById(id);
        RuntimeAgentConfigVersionEntity version = agentVersionMapper.selectById(versionId);
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_AGENT_UNAVAILABLE", "Agent must exist and be enabled");
        }
        if (version == null || !id.equals(version.getAgentId())
                || !Set.of("ACTIVE", "ARCHIVED").contains(RuntimeAutomationTypes.upper(version.getStatus()))) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_AGENT_VERSION_INVALID", "Agent version must be an exact published version");
        }
        verifyProject(agent.getProjectId(), agent.getProjectCode(), requestedProjectId, requestedProjectCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "AGENT");
        snapshot.put("id", agent.getId());
        snapshot.put("keySlug", agent.getKeySlug());
        snapshot.put("name", agent.getName());
        snapshot.put("versionId", version.getId());
        snapshot.put("versionNo", version.getVersionNo());
        snapshot.put("runtimeType", version.getRuntimeType());
        snapshot.put("publishedAt", version.getPublishedAt());
        return new TargetSnapshot("AGENT", agent.getId(), version.getId(),
                agent.getProjectId(), agent.getProjectCode(), immutableSnapshot(snapshot), json.fingerprint(snapshot));
    }

    private TargetSnapshot validateWorkflow(String id,
                                            Long versionId,
                                            Long requestedProjectId,
                                            String requestedProjectCode) {
        RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(id);
        RuntimeWorkflowVersionEntity version = workflowVersionMapper.selectById(versionId);
        if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_WORKFLOW_UNAVAILABLE", "Workflow must exist and be active");
        }
        if (version == null || !id.equals(version.getWorkflowId())
                || !Set.of("ACTIVE", "RETIRED").contains(RuntimeAutomationTypes.upper(version.getStatus()))
                || !StringUtils.hasText(version.getGraphSpecSnapshotJson())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_WORKFLOW_VERSION_INVALID", "Workflow version must be an exact published version");
        }
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
