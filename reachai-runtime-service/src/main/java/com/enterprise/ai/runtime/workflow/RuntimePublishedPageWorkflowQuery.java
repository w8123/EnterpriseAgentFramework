package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Workflow-owned page publication selection; exposes values without persistence entities. */
@Service
@RequiredArgsConstructor
public class RuntimePublishedPageWorkflowQuery {
    private final RuntimeWorkflowResourceBindingService bindingService;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper versionMapper;

    public List<PublishedPageWorkflow> list(String projectCode, String pageKey) {
        List<BindingView> bindings = bindingService.find(
                projectCode,
                RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                pageKey);
        List<PublishedPageWorkflow> results = new ArrayList<>();
        for (BindingView binding : bindings) {
            if (!RuntimeWorkflowResourceBindingService.ROLE_TARGET.equals(binding.bindingRole())) {
                continue;
            }
            RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(binding.workflowId());
            if (workflow == null
                    || !WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())
                    || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                continue;
            }
            RuntimeWorkflowVersionEntity version = versionMapper.selectOne(
                    Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                            .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflow.getId())
                            .eq(RuntimeWorkflowVersionEntity::getStatus, "ACTIVE")
                            .orderByDesc(RuntimeWorkflowVersionEntity::getId)
                            .last("LIMIT 1"));
            if (version == null) {
                continue;
            }
            results.add(new PublishedPageWorkflow(
                    binding.resourceKey(), workflow.getId(), workflow.getKeySlug(), workflow.getName(),
                    workflow.getDescription(), workflow.getStatus(), version.getId(), version.getVersion(),
                    version.getPublishedBy(), version.getPublishedAt()));
        }
        return results;
    }

    public record PublishedPageWorkflow(String pageKey, String workflowId, String workflowKeySlug,
            String workflowName, String workflowDescription, String workflowStatus,
            Long workflowVersionId, String workflowVersion, String publishedBy, LocalDateTime publishedAt) { }
}
