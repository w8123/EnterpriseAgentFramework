package com.enterprise.ai.runtime.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RuntimeWorkflowExecutionReader implements RuntimeWorkflowExecutionQuery {
    private final RuntimeWorkflowDefinitionMapper workflows;
    private final RuntimeWorkflowVersionMapper versions;

    @Override
    @Transactional(readOnly = true)
    public List<Target> resolve(List<Reference> references) {
        if (references == null || references.isEmpty()) return List.of();
        var workflowIds = references.stream().map(Reference::workflowId).filter(StringUtils::hasText).distinct().toList();
        var versionIds = references.stream().map(Reference::versionId).filter(Objects::nonNull).distinct().toList();
        Map<String, RuntimeWorkflowExecutionView> byWorkflow = workflowIds.isEmpty() ? Map.of() : workflows.selectBatchIds(workflowIds).stream()
                .map(RuntimeWorkflowExecutionView::fromEntity).collect(Collectors.toMap(RuntimeWorkflowExecutionView::getId,
                        value -> value, (first, second) -> first, LinkedHashMap::new));
        Map<Long, RuntimeWorkflowPublishedVersionView> byVersion = versionIds.isEmpty() ? Map.of() : versions.selectBatchIds(versionIds).stream()
                .map(RuntimeWorkflowPublishedVersionView::fromEntity).collect(Collectors.toMap(RuntimeWorkflowPublishedVersionView::getId,
                        value -> value, (first, second) -> first, LinkedHashMap::new));
        List<Target> result = new ArrayList<>();
        for (var reference : references) {
            var workflow = StringUtils.hasText(reference.workflowId()) ? byWorkflow.get(reference.workflowId()) : null;
            if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                throw new LookupFailure(Reason.WORKFLOW_UNAVAILABLE,
                        "Configured Workflow tool is not ACTIVE: " + reference.workflowId());
            }
            var version = reference.versionId() == null ? null : byVersion.get(reference.versionId());
            if (version == null || !workflow.getId().equals(version.getWorkflowId())
                    || !Set.of("ACTIVE", "RETIRED").contains(String.valueOf(version.getStatus()).trim().toUpperCase(Locale.ROOT))
                    || !StringUtils.hasText(version.getGraphSpecSnapshotJson())) {
                throw new LookupFailure(Reason.VERSION_INVALID, "Configured Workflow tool has no executable pinned version: "
                        + workflow.getId() + "#" + reference.versionId());
            }
            result.add(new Target(workflow, version));
        }
        return List.copyOf(result);
    }
}
