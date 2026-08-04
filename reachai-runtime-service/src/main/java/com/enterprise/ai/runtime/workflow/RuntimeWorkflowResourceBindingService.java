package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowResourceBindingService {

    public static final String RESOURCE_PAGE = "PAGE";
    public static final String ROLE_TARGET = "TARGET";
    public static final String ROLE_RELATED = "RELATED";

    private final RuntimeWorkflowResourceBindingMapper mapper;

    public List<BindingView> list(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<RuntimeWorkflowResourceBindingEntity>lambdaQuery()
                        .eq(RuntimeWorkflowResourceBindingEntity::getWorkflowId, workflowId.trim())
                        .eq(RuntimeWorkflowResourceBindingEntity::getStatus, "ACTIVE")
                        .orderByAsc(RuntimeWorkflowResourceBindingEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public List<BindingView> find(
            String projectCode,
            String resourceType,
            String resourceKey) {
        return mapper.selectList(Wrappers.<RuntimeWorkflowResourceBindingEntity>lambdaQuery()
                        .eq(RuntimeWorkflowResourceBindingEntity::getProjectCode,
                                requireText(projectCode, "projectCode"))
                        .eq(RuntimeWorkflowResourceBindingEntity::getResourceType,
                                normalizeResourceType(resourceType))
                        .eq(StringUtils.hasText(resourceKey),
                                RuntimeWorkflowResourceBindingEntity::getResourceKey,
                                StringUtils.hasText(resourceKey) ? resourceKey.trim() : null)
                        .eq(RuntimeWorkflowResourceBindingEntity::getStatus, "ACTIVE")
                        .orderByDesc(RuntimeWorkflowResourceBindingEntity::getUpdatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public List<BindingView> replace(
            RuntimeWorkflowDefinitionEntity workflow,
            List<BindingInput> inputs) {
        if (workflow == null || !StringUtils.hasText(workflow.getId())) {
            throw new IllegalArgumentException("workflow is required for resource binding");
        }
        List<ValidatedBinding> bindings = validate(workflow, inputs);
        mapper.delete(Wrappers.<RuntimeWorkflowResourceBindingEntity>lambdaQuery()
                .eq(RuntimeWorkflowResourceBindingEntity::getWorkflowId, workflow.getId()));
        LocalDateTime now = LocalDateTime.now();
        for (ValidatedBinding binding : bindings) {
            RuntimeWorkflowResourceBindingEntity entity = new RuntimeWorkflowResourceBindingEntity();
            entity.setWorkflowId(workflow.getId());
            entity.setProjectId(workflow.getProjectId());
            entity.setProjectCode(workflow.getProjectCode());
            entity.setResourceType(binding.resourceType());
            entity.setResourceKey(binding.resourceKey());
            entity.setBindingRole(binding.bindingRole());
            entity.setStatus("ACTIVE");
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
        }
        return list(workflow.getId());
    }

    private List<ValidatedBinding> validate(
            RuntimeWorkflowDefinitionEntity workflow,
            List<BindingInput> inputs) {
        List<BindingInput> normalized = inputs == null ? List.of() : inputs;
        if (!normalized.isEmpty()
                && (workflow.getProjectId() == null || !StringUtils.hasText(workflow.getProjectCode()))) {
            throw new IllegalArgumentException(
                    "workflow project ownership is required for resource binding");
        }
        List<ValidatedBinding> bindings = new ArrayList<>(normalized.size());
        Set<String> identities = new HashSet<>();
        int targetPages = 0;
        for (BindingInput input : normalized) {
            if (input == null) {
                throw new IllegalArgumentException("resource binding is required");
            }
            String resourceType = normalizeResourceType(input.resourceType());
            String resourceKey = requireText(input.resourceKey(), "resource binding resourceKey");
            String bindingRole = normalizeBindingRole(input.bindingRole());
            if (input.projectId() != null && !Objects.equals(input.projectId(), workflow.getProjectId())) {
                throw new IllegalArgumentException("resource binding projectId does not match workflow");
            }
            if (StringUtils.hasText(input.projectCode())
                    && !workflow.getProjectCode().equalsIgnoreCase(input.projectCode().trim())) {
                throw new IllegalArgumentException("resource binding projectCode does not match workflow");
            }
            if (!identities.add(resourceType + "\u0000" + resourceKey)) {
                throw new IllegalArgumentException(
                        "duplicate workflow resource binding: " + resourceType + "/" + resourceKey);
            }
            if (RESOURCE_PAGE.equals(resourceType) && ROLE_TARGET.equals(bindingRole)) {
                targetPages++;
            }
            bindings.add(new ValidatedBinding(resourceType, resourceKey, bindingRole));
        }
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())
                && targetPages != 1) {
            throw new IllegalArgumentException(
                    "PAGE_ASSISTANT workflow requires exactly one TARGET PAGE resource binding");
        }
        return bindings;
    }

    @Transactional
    public void deleteForWorkflow(String workflowId) {
        if (StringUtils.hasText(workflowId)) {
            mapper.delete(Wrappers.<RuntimeWorkflowResourceBindingEntity>lambdaQuery()
                    .eq(RuntimeWorkflowResourceBindingEntity::getWorkflowId, workflowId.trim()));
        }
    }

    private BindingView toView(RuntimeWorkflowResourceBindingEntity entity) {
        return new BindingView(
                entity.getId(),
                entity.getWorkflowId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getResourceType(),
                entity.getResourceKey(),
                entity.getBindingRole(),
                entity.getStatus(),
                entity.getUpdatedAt());
    }

    private static String normalizeResourceType(String value) {
        String normalized = requireText(value, "resource binding resourceType")
                .toUpperCase(Locale.ROOT);
        if (!RESOURCE_PAGE.equals(normalized)) {
            throw new IllegalArgumentException("unsupported workflow resource type: " + value);
        }
        return normalized;
    }

    private static String normalizeBindingRole(String value) {
        String normalized = firstText(value, ROLE_TARGET).toUpperCase(Locale.ROOT);
        if (!ROLE_TARGET.equals(normalized) && !ROLE_RELATED.equals(normalized)) {
            throw new IllegalArgumentException("unsupported workflow resource binding role: " + value);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public record BindingInput(
            Long projectId,
            String projectCode,
            String resourceType,
            String resourceKey,
            String bindingRole) {
    }

    public record BindingView(
            Long id,
            String workflowId,
            Long projectId,
            String projectCode,
            String resourceType,
            String resourceKey,
            String bindingRole,
            String status,
            LocalDateTime updatedAt) {
    }

    private record ValidatedBinding(
            String resourceType,
            String resourceKey,
            String bindingRole) {
    }
}
