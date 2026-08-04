package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionInput;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ManualPageCommand;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageReport;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ResourceInput;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ResourceView;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageLifecycle;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.ResourceAccess;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.ResourceType;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionMapper;
import com.enterprise.ai.control.pageworkbench.persistence.PageResourceEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageResourceMapper;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageEntity;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PageCatalogApplicationService {

    private final ProjectPageMapper pageMapper;
    private final PageResourceMapper resourceMapper;
    private final PageActionMapper actionMapper;
    private final ObjectMapper objectMapper;
    private final CapabilityProjectOnboardingClient capabilityClient;

    public List<PageView> listPages(String projectCode, boolean includeArchived) {
        var query = Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(StringUtils.hasText(projectCode), ProjectPageEntity::getProjectCode,
                        StringUtils.hasText(projectCode) ? projectCode.trim() : null)
                .eq(!includeArchived, ProjectPageEntity::getLifecycleStatus, PageLifecycle.ACTIVE.name())
                .orderByAsc(ProjectPageEntity::getModuleName)
                .orderByAsc(ProjectPageEntity::getName);
        return hydrate(pageMapper.selectList(query));
    }

    public Optional<PageView> findPage(Long pageId) {
        if (pageId == null) {
            return Optional.empty();
        }
        ProjectPageEntity page = pageMapper.selectById(pageId);
        return page == null ? Optional.empty() : Optional.of(hydrate(List.of(page)).get(0));
    }

    public Optional<PageView> findPage(String projectCode, Long pageId) {
        if (pageId == null) {
            return Optional.empty();
        }
        ProjectPageEntity page = pageMapper.selectOne(Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(ProjectPageEntity::getId, pageId)
                .eq(ProjectPageEntity::getProjectCode, requireText(projectCode, "projectCode"))
                .last("LIMIT 1"));
        return page == null ? Optional.empty() : Optional.of(hydrate(List.of(page)).get(0));
    }

    public Optional<PageView> findPageByKey(String projectCode, String pageKey) {
        if (!StringUtils.hasText(pageKey)) {
            return Optional.empty();
        }
        ProjectPageEntity page = findEntityByKey(requireText(projectCode, "projectCode"), pageKey.trim());
        return page == null ? Optional.empty() : Optional.of(hydrate(List.of(page)).get(0));
    }

    @Transactional
    public PageView createManualPage(String projectCode, ManualPageCommand command) {
        if (command == null || command.projectId() == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        String normalizedProjectCode = requireText(projectCode, "projectCode");
        requireProjectIdentity(command.projectId(), normalizedProjectCode);
        PageReport report = new PageReport(
                command.pageKey(),
                command.moduleKey(),
                command.moduleName(),
                command.name(),
                command.description(),
                command.routePattern(),
                command.componentPath(),
                command.resources(),
                command.actions());
        ProjectPageEntity page = upsertPage(
                command.projectId(),
                normalizedProjectCode,
                report,
                PageSource.MANUAL,
                LocalDateTime.now());
        if (report.resources() != null) {
            replaceResources(page, report.resources());
        }
        if (report.actions() != null) {
            synchronizeActions(page, report.actions(), PageSource.MANUAL);
        }
        return findPage(projectCode, page.getId()).orElseThrow();
    }

    @Transactional
    public int applyPageMap(
            Long projectId,
            String projectCode,
            String scanMode,
            LocalDateTime scannedAt,
            List<PageReport> reports) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        String normalizedProjectCode = requireText(projectCode, "projectCode");
        LocalDateTime discoveryTime = scannedAt == null ? LocalDateTime.now() : scannedAt;
        List<PageReport> normalizedReports = reports == null ? List.of() : reports;
        Set<String> reportKeys = normalizedReports.stream()
                .filter(Objects::nonNull)
                .map(PageReport::pageKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toSet());

        for (PageReport report : normalizedReports) {
            ProjectPageEntity page = upsertPage(
                    projectId,
                    normalizedProjectCode,
                    report,
                    PageSource.AI_SCAN,
                    discoveryTime);
            replaceResources(page, report.resources());
            synchronizeActions(page, report.actions(), PageSource.AI_SCAN);
        }

        if ("FULL".equalsIgnoreCase(scanMode)) {
            List<ProjectPageEntity> currentPages = pageMapper.selectList(Wrappers.<ProjectPageEntity>lambdaQuery()
                    .eq(ProjectPageEntity::getProjectCode, normalizedProjectCode)
                    .eq(ProjectPageEntity::getSourceType, PageSource.AI_SCAN.name())
                    .eq(ProjectPageEntity::getLifecycleStatus, PageLifecycle.ACTIVE.name()));
            for (ProjectPageEntity current : currentPages) {
                if (!reportKeys.contains(current.getPageKey())) {
                    current.setLifecycleStatus(PageLifecycle.ARCHIVED.name());
                    current.setUpdatedAt(LocalDateTime.now());
                    pageMapper.updateById(current);
                }
            }
        }
        return normalizedReports.size();
    }

    public Optional<ActionView> findAction(String projectCode, String pageKey, String actionKey) {
        PageActionEntity action = actionMapper.selectOne(Wrappers.<PageActionEntity>lambdaQuery()
                .eq(PageActionEntity::getProjectCode, requireText(projectCode, "projectCode"))
                .eq(PageActionEntity::getPageKey, requireText(pageKey, "pageKey"))
                .eq(PageActionEntity::getActionKey, requireText(actionKey, "actionKey"))
                .last("LIMIT 1"));
        return action == null ? Optional.empty() : Optional.of(toActionView(action));
    }

    public Optional<ActionView> findAction(Long actionId) {
        if (actionId == null) {
            return Optional.empty();
        }
        PageActionEntity action = actionMapper.selectById(actionId);
        return action == null ? Optional.empty() : Optional.of(toActionView(action));
    }

    public Optional<PageView> findPageForAction(Long actionId) {
        if (actionId == null) {
            return Optional.empty();
        }
        PageActionEntity action = actionMapper.selectById(actionId);
        return action == null ? Optional.empty() : findPage(action.getPageId());
    }

    public List<ActionView> listActions(
            String projectCode,
            String pageKey,
            String status,
            int limit) {
        return actionMapper.selectList(Wrappers.<PageActionEntity>lambdaQuery()
                        .eq(StringUtils.hasText(projectCode), PageActionEntity::getProjectCode,
                                StringUtils.hasText(projectCode) ? projectCode.trim() : null)
                        .eq(StringUtils.hasText(pageKey), PageActionEntity::getPageKey, pageKey)
                        .eq(StringUtils.hasText(status), PageActionEntity::getStatus, status)
                        .orderByDesc(PageActionEntity::getUpdatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 1000)))
                .stream()
                .map(this::toActionView)
                .toList();
    }

    @Transactional
    public PageView upsertPageCatalog(
            Long projectId,
            String projectCode,
            PageReport report,
            PageSource source,
            boolean replaceActions) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        ProjectPageEntity page = upsertPage(
                projectId,
                requireText(projectCode, "projectCode"),
                report,
                source,
                LocalDateTime.now());
        if (report.resources() != null) {
            replaceResources(page, report.resources());
        }
        if (replaceActions) {
            synchronizeActions(page, report.actions(), source);
        } else {
            for (ActionInput action : report.actions() == null ? List.<ActionInput>of() : report.actions()) {
                upsertAction(page, action, source);
            }
        }
        return findPage(projectCode, page.getId()).orElseThrow();
    }

    @Transactional
    public boolean deletePage(String projectCode, Long pageId) {
        ProjectPageEntity page = pageMapper.selectOne(Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(ProjectPageEntity::getId, pageId)
                .eq(ProjectPageEntity::getProjectCode, requireText(projectCode, "projectCode"))
                .last("LIMIT 1"));
        if (page == null) {
            return false;
        }
        resourceMapper.delete(Wrappers.<PageResourceEntity>lambdaQuery()
                .eq(PageResourceEntity::getPageId, pageId));
        actionMapper.delete(Wrappers.<PageActionEntity>lambdaQuery()
                .eq(PageActionEntity::getPageId, pageId));
        return pageMapper.deleteById(pageId) > 0;
    }

    @Transactional
    public boolean deletePage(Long pageId) {
        ProjectPageEntity page = pageId == null ? null : pageMapper.selectById(pageId);
        return page != null && deletePage(page.getProjectCode(), pageId);
    }

    private ProjectPageEntity upsertPage(
            Long projectId,
            String projectCode,
            PageReport report,
            PageSource source,
            LocalDateTime discoveredAt) {
        if (report == null) {
            throw new IllegalArgumentException("page report is required");
        }
        String pageKey = requireText(report.pageKey(), "pageKey");
        ProjectPageEntity page = findEntityByKey(projectCode, pageKey);
        LocalDateTime now = LocalDateTime.now();
        boolean create = page == null;
        if (create) {
            page = new ProjectPageEntity();
            page.setProjectId(projectId);
            page.setProjectCode(projectCode);
            page.setPageKey(pageKey);
            page.setCreatedAt(now);
        } else if (!Objects.equals(page.getProjectId(), projectId)) {
            throw new IllegalArgumentException("page project ownership mismatch: " + pageKey);
        }
        int incomingPriority = sourcePriority(source);
        int currentPriority = sourcePriority(page.getSourceType());
        boolean completeSnapshot = create
                || (source == PageSource.AI_SCAN
                && incomingPriority == currentPriority);
        if (completeSnapshot) {
            page.setModuleKey(textOrNull(report.moduleKey()));
            page.setModuleName(textOrNull(report.moduleName()));
            page.setName(firstText(report.name(), pageKey));
            page.setDescription(textOrNull(report.description()));
            page.setRoutePattern(textOrNull(report.routePattern()));
            page.setComponentPath(textOrNull(report.componentPath()));
            page.setSourceType(source.name());
        } else if (incomingPriority >= currentPriority) {
            if (StringUtils.hasText(report.moduleKey())) {
                page.setModuleKey(report.moduleKey().trim());
            }
            if (StringUtils.hasText(report.moduleName())) {
                page.setModuleName(report.moduleName().trim());
            }
            if (StringUtils.hasText(report.name())) {
                page.setName(report.name().trim());
            }
            if (StringUtils.hasText(report.description())) {
                page.setDescription(report.description().trim());
            }
            if (StringUtils.hasText(report.routePattern())) {
                page.setRoutePattern(report.routePattern().trim());
            }
            if (StringUtils.hasText(report.componentPath())) {
                page.setComponentPath(report.componentPath().trim());
            }
            page.setSourceType(source.name());
        } else {
            page.setModuleKey(firstText(page.getModuleKey(), textOrNull(report.moduleKey())));
            page.setModuleName(firstText(page.getModuleName(), textOrNull(report.moduleName())));
            page.setName(firstText(page.getName(), firstText(report.name(), pageKey)));
            page.setDescription(firstText(
                    page.getDescription(),
                    textOrNull(report.description())));
            page.setRoutePattern(firstText(
                    page.getRoutePattern(),
                    textOrNull(report.routePattern())));
            page.setComponentPath(firstText(
                    page.getComponentPath(),
                    textOrNull(report.componentPath())));
        }
        page.setLifecycleStatus(PageLifecycle.ACTIVE.name());
        if (source == PageSource.AI_SCAN) {
            page.setLastDiscoveredAt(discoveredAt);
        } else {
            page.setLastVerifiedAt(now);
        }
        page.setUpdatedAt(now);
        if (create) {
            pageMapper.insert(page);
        } else {
            pageMapper.updateById(page);
        }
        return page;
    }

    private ProjectPageEntity findEntityByKey(String projectCode, String pageKey) {
        return pageMapper.selectOne(Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(ProjectPageEntity::getProjectCode, projectCode)
                .eq(ProjectPageEntity::getPageKey, pageKey)
                .last("LIMIT 1"));
    }

    private void requireProjectIdentity(Long projectId, String projectCode) {
        Map<String, Object> project = capabilityClient.getProjectById(projectId);
        Object actualProjectCodeValue = project.get("projectCode");
        String actualProjectCode = actualProjectCodeValue == null
                ? null
                : textOrNull(String.valueOf(actualProjectCodeValue));
        if (!StringUtils.hasText(actualProjectCode)
                || !projectCode.equalsIgnoreCase(actualProjectCode)) {
            throw new IllegalArgumentException(
                    "projectId and projectCode do not refer to the same project");
        }
    }

    private void replaceResources(ProjectPageEntity page, List<ResourceInput> resources) {
        resourceMapper.delete(Wrappers.<PageResourceEntity>lambdaQuery()
                .eq(PageResourceEntity::getPageId, page.getId()));
        LocalDateTime now = LocalDateTime.now();
        for (ResourceInput input : resources == null ? List.<ResourceInput>of() : resources) {
            if (input == null) {
                continue;
            }
            PageResourceEntity entity = new PageResourceEntity();
            entity.setPageId(page.getId());
            entity.setProjectCode(page.getProjectCode());
            entity.setResourceType(requiredResourceType(input.resourceType()).name());
            entity.setResourceKey(requireText(input.resourceKey(), "resourceKey"));
            entity.setDisplayName(textOrNull(input.displayName()));
            entity.setLocation(textOrNull(input.location()));
            entity.setHttpMethod(textOrNull(input.httpMethod()));
            entity.setAccessMode(resourceAccess(input.accessMode()).name());
            entity.setMetadataJson(writeJsonOrNull(input.metadata()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            resourceMapper.insert(entity);
        }
    }

    private void synchronizeActions(
            ProjectPageEntity page,
            List<ActionInput> actions,
            PageSource source) {
        List<ActionInput> normalized = actions == null ? List.of() : actions;
        Set<String> reportedKeys = normalized.stream()
                .filter(Objects::nonNull)
                .map(ActionInput::actionKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toSet());
        List<PageActionEntity> existingForSource = actionMapper.selectList(
                Wrappers.<PageActionEntity>lambdaQuery()
                        .eq(PageActionEntity::getPageId, page.getId())
                        .eq(PageActionEntity::getSourceType, source.name()));
        for (PageActionEntity existing : existingForSource) {
            if (source.name().equalsIgnoreCase(existing.getSourceType())
                    && !reportedKeys.contains(existing.getActionKey())) {
                actionMapper.deleteById(existing.getId());
            }
        }
        for (ActionInput input : normalized) {
            if (input == null) {
                continue;
            }
            upsertAction(page, input, source);
        }
    }

    private void upsertAction(ProjectPageEntity page, ActionInput input, PageSource source) {
        if (input == null) {
            throw new IllegalArgumentException("page action is required");
        }
        String actionKey = requireText(input.actionKey(), "actionKey");
        PageActionEntity existing = actionMapper.selectOne(Wrappers.<PageActionEntity>lambdaQuery()
                .eq(PageActionEntity::getPageId, page.getId())
                .eq(PageActionEntity::getActionKey, actionKey)
                .last("LIMIT 1"));
        if (existing != null
                && sourcePriority(source) < sourcePriority(existing.getSourceType())) {
            return;
        }
        insertAction(page, input, source, existing);
    }

    private int sourcePriority(PageSource source) {
        return switch (source) {
            case AI_SCAN -> 1;
            case SDK -> 2;
            case MANUAL -> 3;
        };
    }

    private int sourcePriority(String source) {
        if (!StringUtils.hasText(source)) {
            return 0;
        }
        try {
            return sourcePriority(PageSource.valueOf(source.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    private void insertAction(
            ProjectPageEntity page,
            ActionInput input,
            PageSource source,
            PageActionEntity existing) {
        LocalDateTime now = LocalDateTime.now();
        boolean create = existing == null;
        PageActionEntity entity = create ? new PageActionEntity() : existing;
        entity.setPageId(page.getId());
        entity.setProjectId(page.getProjectId());
        entity.setProjectCode(page.getProjectCode());
        entity.setPageKey(page.getPageKey());
        entity.setActionKey(requireText(input.actionKey(), "actionKey"));
        entity.setTitle(firstText(input.title(), entity.getActionKey()));
        entity.setDescription(textOrNull(input.description()));
        entity.setActionType(firstText(input.actionType(), "PAGE_ACTION"));
        entity.setRiskLevel(firstText(input.riskLevel(), "READ").toUpperCase());
        entity.setConfirmRequired(Boolean.TRUE.equals(input.confirmRequired()));
        entity.setPermissionKey(textOrNull(input.permissionKey()));
        entity.setInputSchemaJson(writeJson(input.inputSchema()));
        entity.setOutputSchemaJson(writeJson(input.outputSchema()));
        entity.setSampleArgsJson(writeJson(input.sampleArgs()));
        entity.setAllowedAgentIdsJson(writeJson(
                input.allowedAgentIds() == null ? List.of() : input.allowedAgentIds()));
        entity.setImplementationRef(textOrNull(input.implementationRef()));
        entity.setSourceType(source.name());
        entity.setStatus("ACTIVE");
        entity.setMetadataJson(writeJsonOrNull(input.metadata()));
        entity.setLastVerifiedAt(now);
        entity.setUpdatedAt(now);
        if (create) {
            entity.setCreatedAt(now);
            actionMapper.insert(entity);
        } else {
            actionMapper.updateById(entity);
        }
    }

    private List<PageView> hydrate(List<ProjectPageEntity> pages) {
        if (pages.isEmpty()) {
            return List.of();
        }
        List<Long> pageIds = pages.stream().map(ProjectPageEntity::getId).toList();
        Map<Long, List<PageResourceEntity>> resources = resourceMapper.selectList(
                        Wrappers.<PageResourceEntity>lambdaQuery()
                                .in(PageResourceEntity::getPageId, pageIds)
                                .orderByAsc(PageResourceEntity::getResourceType)
                                .orderByAsc(PageResourceEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(
                        PageResourceEntity::getPageId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, List<PageActionEntity>> actions = actionMapper.selectList(
                        Wrappers.<PageActionEntity>lambdaQuery()
                                .in(PageActionEntity::getPageId, pageIds)
                                .orderByAsc(PageActionEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(
                        PageActionEntity::getPageId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return pages.stream()
                .map(page -> toPageView(
                        page,
                        resources.getOrDefault(page.getId(), List.of()),
                        actions.getOrDefault(page.getId(), List.of())))
                .toList();
    }

    private PageView toPageView(
            ProjectPageEntity page,
            List<PageResourceEntity> resources,
            List<PageActionEntity> actions) {
        return new PageView(
                page.getId(),
                page.getProjectId(),
                page.getProjectCode(),
                page.getPageKey(),
                page.getModuleKey(),
                page.getModuleName(),
                page.getName(),
                page.getDescription(),
                page.getRoutePattern(),
                page.getComponentPath(),
                page.getSourceType(),
                page.getLifecycleStatus(),
                page.getLastDiscoveredAt(),
                page.getLastVerifiedAt(),
                resources.stream().map(this::toResourceView).toList(),
                actions.stream().map(this::toActionView).toList());
    }

    private ResourceView toResourceView(PageResourceEntity entity) {
        return new ResourceView(
                entity.getId(),
                entity.getResourceType(),
                entity.getResourceKey(),
                entity.getDisplayName(),
                entity.getLocation(),
                entity.getHttpMethod(),
                entity.getAccessMode(),
                readNode(entity.getMetadataJson()));
    }

    private ActionView toActionView(PageActionEntity entity) {
        return new ActionView(
                entity.getId(),
                entity.getPageId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getPageKey(),
                entity.getActionKey(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getActionType(),
                entity.getRiskLevel(),
                Boolean.TRUE.equals(entity.getConfirmRequired()),
                entity.getPermissionKey(),
                readNode(entity.getInputSchemaJson()),
                readNode(entity.getOutputSchemaJson()),
                readNode(entity.getSampleArgsJson()),
                readStringList(entity.getAllowedAgentIdsJson()),
                entity.getImplementationRef(),
                entity.getSourceType(),
                entity.getStatus(),
                readNode(entity.getMetadataJson()),
                entity.getLastVerifiedAt());
    }

    private ResourceType requiredResourceType(String value) {
        return PageWorkbenchValuesAdapter.requiredEnum(ResourceType.class, value, "resourceType");
    }

    private ResourceAccess resourceAccess(String value) {
        return StringUtils.hasText(value)
                ? PageWorkbenchValuesAdapter.requiredEnum(ResourceAccess.class, value, "accessMode")
                : ResourceAccess.READ_ONLY;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("page catalog value is not JSON serializable", ex);
        }
    }

    private String writeJsonOrNull(Object value) {
        return value == null ? null : writeJson(value);
    }

    private JsonNode readNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
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

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * Keeps enum parsing in one obvious place without leaking controller string handling
     * through the catalog methods.
     */
    private static final class PageWorkbenchValuesAdapter {
        private PageWorkbenchValuesAdapter() {
        }

        private static <E extends Enum<E>> E requiredEnum(Class<E> type, String value, String field) {
            return com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.requiredEnum(
                    type, value, field);
        }
    }
}
