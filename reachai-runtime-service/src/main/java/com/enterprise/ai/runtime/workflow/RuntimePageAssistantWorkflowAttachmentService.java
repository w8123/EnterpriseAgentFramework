package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.PageAssistantAttachRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Page Assistant-specific facade over the generic Supervisor Workflow-as-Tool attach service.
 */
@Service
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowAttachmentService {

    private final RuntimeAgentSupervisorWorkflowAttachmentService attachmentService;

    @Transactional
    public RuntimePageAssistantWorkflowAttachment attachPublishedPageWorkflow(
            String workflowId,
            RuntimePageAssistantWorkflowAttachRequest request) {
        AttachmentResult result = attachmentService.attachPageAssistantOnly(
                workflowId,
                new PageAssistantAttachRequest(
                        request == null ? null : request.projectId(),
                        request == null ? null : request.projectCode(),
                        request == null ? null : request.agentId(),
                        request == null ? null : request.modelInstanceId(),
                        request == null ? null : request.publishedBy(),
                        request == null ? null : request.replaceWorkflowId()));
        String toolName = StringUtils.hasText(result.toolName())
                ? result.toolName().trim()
                : fallbackToolName(result.workflow().keySlug());
        return new RuntimePageAssistantWorkflowAttachment(
                result.agent().id(),
                result.agent().keySlug(),
                result.workflow().id(),
                result.workflow().keySlug(),
                toolName,
                result.activeConfig().id(),
                result.activeConfig().version(),
                result.activeConfig().status(),
                "ACTIVE".equalsIgnoreCase(result.activeConfig().status()),
                result.replacedWorkflowId());
    }

    private static String fallbackToolName(String keySlug) {
        String source = StringUtils.hasText(keySlug) ? keySlug.trim() : "workflow_tool";
        String normalized = source.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "workflow_" + normalized;
        }
        if (normalized.length() < 2) {
            normalized = "workflow_tool";
        }
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
