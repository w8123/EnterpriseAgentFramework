package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanDefaultFlags;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanSettings;
import com.enterprise.ai.agent.capability.catalog.scan.ScanSettingsJson;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.aicoding.AiCodingAccessKeys;
import com.enterprise.ai.capability.catalog.CapabilitySourceOwnership;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CapabilityScanProjectCatalogService {

    private final ScanProjectMapper scanProjectMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ScanModuleMapper scanModuleMapper;
    private final SemanticDocMapper semanticDocMapper;
    private final RegistryCredentialMapper registryCredentialMapper;
    private final RegistrySecurityService registrySecurityService;
    private final CapabilityScanProjectBlockerService scanProjectBlockerService;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityScannerClient scannerClient;
    private final ObjectMapper objectMapper;
    private final ScannerHttpApiIntakeService scannerHttpApiIntake;
    private final ScannerHttpApiSingleSourceIntakeService singleSourceIntake;
    private final CapabilityScanProjectReadinessService readinessService;
    private final CapabilityCatalogGroupingService groupingService;
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    private CapabilityScanRevisionService scanRevisions;

    @org.springframework.beans.factory.annotation.Autowired
    public void setScanTransactions(org.springframework.transaction.PlatformTransactionManager transactionManager,
                                    CapabilityScanRevisionService scanRevisions) {
        this.transactionManager = transactionManager;
        this.scanRevisions = scanRevisions;
    }

    public List<ScanProjectEntity> list() {
        return list(null, null, null);
    }

    public List<ScanProjectEntity> list(String keyword, String projectKind, String status) {
        String keywordText = trimToNull(keyword);
        String normalizedProjectKind = StringUtils.hasText(projectKind)
                ? projectKind.trim().toUpperCase(Locale.ROOT)
                : null;
        String normalizedStatus = StringUtils.hasText(status)
                ? status.trim().toLowerCase(Locale.ROOT)
                : null;
        return scanProjectMapper.selectList(new LambdaQueryWrapper<ScanProjectEntity>()
                .and(StringUtils.hasText(keywordText), wrapper -> wrapper
                        .like(ScanProjectEntity::getName, keywordText)
                        .or()
                        .like(ScanProjectEntity::getProjectCode, keywordText)
                        .or()
                        .like(ScanProjectEntity::getOwner, keywordText)
                        .or()
                        .like(ScanProjectEntity::getEnvironment, keywordText)
                        .or()
                        .like(ScanProjectEntity::getBaseUrl, keywordText))
                .eq(StringUtils.hasText(normalizedProjectKind), ScanProjectEntity::getProjectKind, normalizedProjectKind)
                .eq(StringUtils.hasText(normalizedStatus), ScanProjectEntity::getStatus, normalizedStatus)
                .orderByDesc(ScanProjectEntity::getUpdateTime)
                .orderByDesc(ScanProjectEntity::getId));
    }

    public ScanProjectEntity get(Long id) {
        ScanProjectEntity entity = scanProjectMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + id);
        }
        return entity;
    }

    public ScanProjectEntity create(ScanProjectUpsertRequest request) {
        validateRequest(request);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Scan project already exists: " + request.name());
        }
        ScanProjectEntity entity = applyRequest(new ScanProjectEntity(), request);
        entity.setToolCount(0);
        entity.setStatus("created");
        entity.setErrorMessage(null);
        entity.setAuthType("none");
        entity.setAuthApiKeyIn(null);
        entity.setAuthApiKeyName(null);
        entity.setAuthApiKeyValue(null);
        entity.setAiCodingAccessKey(AiCodingAccessKeys.generate());
        entity.setAiCodingAccessEnabled(true);
        scanProjectMapper.insert(entity);
        return entity;
    }

    public ScanProjectEntity update(Long id, ScanProjectUpsertRequest request) {
        ScanProjectEntity entity = get(id);
        validateRequest(request);
        java.util.Optional<ScanProjectEntity> duplicate = findByName(request.name());
        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            throw new IllegalArgumentException("Scan project already exists: " + request.name());
        }
        String previousProjectCode = entity.getProjectCode();
        applyRequest(entity, request);
        scanProjectMapper.updateById(entity);
        if (!String.valueOf(previousProjectCode).equals(String.valueOf(entity.getProjectCode()))) {
            registrySecurityService.syncCredentialProjectCode(entity.getId(), entity.getProjectCode());
        }
        return entity;
    }

    public ScanProjectEntity updateRegistryCredential(Long id, ScanProjectRegistryCredentialSaveRequest request) {
        ScanProjectEntity entity = get(id);
        if (request == null) {
            throw new IllegalArgumentException("Registry credential is required");
        }
        registrySecurityService.savePrimaryCredential(
                entity.getId(),
                entity.getProjectCode(),
                request.appKey(),
                request.appSecret());
        return entity;
    }

    public boolean hasActiveRegistryCredential(String projectCode) {
        return primaryCredential(projectCode) != null;
    }

    public ScanProjectEntity updateScanSettings(Long id, ScanSettings settings) {
        ScanProjectEntity entity = get(id);
        try {
            entity.setScanSettings(objectMapper.writeValueAsString(settings == null ? ScanSettings.defaults() : settings));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Scan settings serialize failed", ex);
        }
        scanProjectMapper.updateById(entity);
        return entity;
    }

    public Map<String, Object> readinessFacts(Long projectId) {
        return readinessService.readinessFacts(get(projectId));
    }

    public CapabilityScanProjectReadinessService.SdkAccessCheckResponse sdkAccessCheck(Long projectId) {
        return readinessService.sdkAccessCheck(get(projectId));
    }

    @Transactional
    public void delete(Long projectId) {
        scanProjectMapper.lockCapabilityChanges(projectId);
        ScanProjectEntity entity = get(projectId);
        ScanProjectBlockers blockers = scanProjectBlockerService.analyzeDeletion(projectId);
        if (blockers == null) throw new IllegalStateException("Project deletion blockers are unavailable");
        if (blockers.blocked()) {
            throw new ScanProjectBlockedException(blockers);
        }
        groupingService.deleteProject(projectId);
        semanticDocMapper.delete(Wrappers.<SemanticDocEntity>lambdaQuery()
                .eq(SemanticDocEntity::getProjectId, projectId));
        scanProjectToolMapper.delete(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
        scanModuleMapper.delete(Wrappers.<ScanModuleEntity>lambdaQuery()
                .eq(ScanModuleEntity::getProjectId, projectId));
        registryCredentialMapper.delete(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectId, projectId));
        scanProjectMapper.deleteById(entity.getId());
    }

    public List<ScanProjectToolEntity> listTools(Long projectId) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        return scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .orderByAsc(ScanProjectToolEntity::getId));
    }

    /** Filter/count before paging; only the requested page hydrates source/link details. */
    public SourcePage sourcePage(Long projectId, int current, int size, String keyword, Long moduleId,
                                 boolean ungrouped, List<Long> observationIds, Long focusObservationId) {
        get(projectId);
        if (moduleId != null && (moduleId <= 0 || ungrouped)) throw new IllegalArgumentException("来源模块筛选无效");
        if (observationIds != null && (observationIds.size() > 1000
                || observationIds.stream().anyMatch(id -> id == null || id <= 0))) {
            throw new IllegalArgumentException("来源标识筛选无效");
        }
        int page = Math.max(1, current), pageSize = Math.min(100, Math.max(1, size));
        var query = Wrappers.<ScanProjectToolEntity>lambdaQuery().eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(moduleId != null, ScanProjectToolEntity::getModuleId, moduleId)
                .isNull(ungrouped, ScanProjectToolEntity::getModuleId);
        if (observationIds != null && !observationIds.isEmpty()) query.in(ScanProjectToolEntity::getId, observationIds);
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
            query.apply("(LOWER(CONCAT_WS(' ', title, description, name, source_qualified_name, "
                    + "CONCAT(COALESCE(context_path, ''), COALESCE(endpoint_path, '')))) LIKE {0} ESCAPE '!' "
                    + "OR EXISTS (SELECT 1 FROM capability_tool_definition projection "
                    + "WHERE projection.id = capability_scan_project_tool.global_tool_definition_id "
                    + "AND LOWER(projection.name) LIKE {0} ESCAPE '!'))", "%" + term + "%");
        }
        List<Map<String, Object>> counts = scanProjectToolMapper.selectMaps(new QueryWrapper<ScanProjectToolEntity>()
                .select("module_id", "COUNT(1) AS source_count", "SUM(CASE WHEN removed_from_source = TRUE THEN 1 ELSE 0 END) AS removed_count")
                .eq("project_id", projectId).groupBy("module_id"));
        long all = counts.stream().mapToLong(row -> ((Number)row.get("source_count")).longValue()).sum();
        long removed = counts.stream().mapToLong(row -> ((Number)row.get("removed_count")).longValue()).sum();
        boolean filtered = moduleId != null || ungrouped || StringUtils.hasText(keyword)
                || (observationIds != null && !observationIds.isEmpty());
        long total = filtered ? scanProjectToolMapper.selectCount(query) : all;
        if (focusObservationId != null) {
            if (focusObservationId <= 0) throw new IllegalArgumentException("来源焦点标识无效");
            if (scanProjectToolMapper.selectCount(query.clone().eq(ScanProjectToolEntity::getId, focusObservationId)) > 0) {
                page = (int)(scanProjectToolMapper.selectCount(query.clone().lt(ScanProjectToolEntity::getId, focusObservationId)) / pageSize) + 1;
            }
        }
        long offset = (long)(page - 1) * pageSize;
        List<ScanProjectToolEntity> records = offset >= total ? List.of() : scanProjectToolMapper.selectList(query
                .orderByAsc(ScanProjectToolEntity::getId).last("LIMIT " + pageSize + " OFFSET " + offset));
        List<Long> moduleIds = counts.stream().map(row -> (Number)row.get("module_id")).filter(Objects::nonNull)
                .map(Number::longValue).toList();
        Map<Long, String> labels = moduleDisplayNames(projectId, moduleIds);
        List<SourceModule> modules = counts.stream().map(row -> {
            Number module = (Number)row.get("module_id");
            Long id = module == null ? null : module.longValue();
            return new SourceModule(id, id == null ? "未关联模块" : labels.getOrDefault(id, "模块 #" + id),
                    ((Number)row.get("source_count")).longValue());
        }).sorted(java.util.Comparator.comparing(SourceModule::moduleId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
        return new SourcePage(records, total, page, pageSize, new SourceStats(all, removed), modules);
    }

    public record SourceStats(long total, long removed) { }
    public record SourceModule(Long moduleId, String moduleDisplayName, long count) { }
    public record SourcePage(List<ScanProjectToolEntity> records, long total, int current, int size,
                             SourceStats stats, List<SourceModule> modules) { }

    public ScanProjectToolEntity getTool(Long projectId, Long scanToolId) {
        return getTool(projectId, scanToolId, false);
    }

    private ScanProjectToolEntity getTool(Long projectId, Long scanToolId, boolean forUpdate) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        ScanProjectToolEntity tool = scanProjectToolMapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getId, scanToolId)
                .last(forUpdate ? "limit 1 for update" : "limit 1"));
        if (tool == null) {
            throw new IllegalArgumentException("Scan project tool does not exist: " + scanToolId);
        }
        return tool;
    }


    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ScanResult rescan(Long projectId) {
        return scanProject(projectId);
    }

    private ScanResult scanProject(Long projectId) {
        ScanPreparation prepared = shortTransaction(() -> {
            scanProjectMapper.lockCapabilityChanges(projectId);
            ScanProjectEntity project = get(projectId);
            requireScanAllowed(projectId);
            return new ScanPreparation(project, scanRevision(project));
        });
        try {
            CapabilityScannerClient.ManifestData manifest = scanManifest(prepared.project(), null);
            return shortTransaction(() -> {
                ScanProjectEntity current = requireCurrentScan(projectId, prepared.revision());
                requireScanAllowed(projectId);
                return performScan(current, true, manifest);
            });
        } catch (ScanResultStaleException | ScanProjectBlockedException ex) {
            throw ex;
        } catch (RuntimeException failed) {
            // Only this prepared generation may be marked failed; an obsolete failure cannot
            // overwrite a newer successful sync, changed project, or different inventory.
            shortTransaction(() -> {
                requireCurrentScan(projectId, prepared.revision());
                markFailed(projectId, failed.getMessage());
                return null;
            });
            throw new ScanExecutionFailedException(failed.getMessage(), failed);
        }
    }

    private void requireScanAllowed(Long projectId) {
        ScanProjectBlockers blockers = scanProjectBlockerService.analyze(projectId);
        if (blockers != null && blockers.blocked()) throw new ScanProjectBlockedException(blockers);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SingleSourceRefreshResult rescanSingleTool(Long projectId, Long scanToolId) {
        ScanPreparation prepared = shortTransaction(() -> {
            scanProjectMapper.lockCapabilityChanges(projectId);
            ScanProjectEntity project = get(projectId);
            requireCatalogWritable(getTool(projectId, scanToolId, true));
            return new ScanPreparation(project, scanRevision(project));
        });
        CapabilityScannerClient.ManifestData manifest = scanManifest(prepared.project(), null);
        return shortTransaction(() -> {
        ScanProjectEntity project = requireCurrentScan(projectId, prepared.revision());
        ScanProjectToolEntity existing = getTool(projectId, scanToolId, true);
        requireCatalogWritable(existing);
        CapabilityScannerClient.ToolData matched = findManifestTool(project, existing, manifest);
        HttpApiSourceKind kind = usesControllerScanner(project) ? HttpApiSourceKind.CONTROLLER_SCAN : HttpApiSourceKind.OPENAPI_SCAN;
        ScannerHttpApiSingleSourceIntakeService.Plan plan = singleSourceIntake.prepare(kind, project, existing, manifest.httpApis());
        if (matched != null) {
            applyManifestTool(project, matched, existing, false, existing.getName());
            scanProjectToolMapper.updateById(existing);
        }
        ScannerHttpApiIntakeService.Summary summary = singleSourceIntake.observe(plan);
        bootstrapModulesFromTools(projectId);
        boolean confirmed = summary.observed() == 1;
        if (confirmed) clearStaleProjectErrorAfterSingleToolRefresh(project);
        return new SingleSourceRefreshResult(existing,
                new HttpApiRefreshResult(confirmed, summary.observed(), confirmed ? null : plan.reason(), !confirmed));
        });
    }

    private record ScanPreparation(ScanProjectEntity project, String revision) { }

    private ScanProjectEntity requireCurrentScan(Long projectId, String expectedRevision) {
        scanProjectMapper.lockCapabilityChanges(projectId);
        ScanProjectEntity current = get(projectId);
        if (!Objects.equals(expectedRevision, scanRevision(current))) {
            throw new ScanResultStaleException("项目接入或来源已变化，本次扫描结果已丢弃，请重新扫描");
        }
        return current;
    }

    private String scanRevision(ScanProjectEntity project) {
        if (scanRevisions == null) throw new IllegalStateException("Scan revision boundary is unavailable");
        return scanRevisions.revision(project);
    }

    private <T> T shortTransaction(java.util.function.Supplier<T> action) {
        if (transactionManager == null) throw new IllegalStateException("Scan transaction boundary is unavailable");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> action.get());
    }

    public static class ScanResultStaleException extends IllegalStateException {
        public ScanResultStaleException(String message) { super(message); }
    }

    public static class ScanExecutionFailedException extends IllegalStateException {
        public ScanExecutionFailedException(String message, Throwable cause) { super(message, cause); }
    }

    public record SingleSourceRefreshResult(ScanProjectToolEntity sourceObservation, HttpApiRefreshResult httpApiRefresh) { }
    public record HttpApiRefreshResult(boolean confirmed, int observed, String reason, boolean fullSyncRequired) { }

    public void markFailed(Long projectId, String errorMessage) {
        ScanProjectEntity project = scanProjectMapper.selectById(projectId);
        if (project == null) {
            return;
        }
        project.setStatus("failed");
        project.setErrorMessage(errorMessage);
        scanProjectMapper.updateById(project);
    }


    public ToolReconcileSummary reconcileTools(Long projectId) {
        List<ScanProjectToolEntity> tools = listTools(projectId);
        Map<Long, ToolDefinitionEntity> globals = globalToolsById(tools);
        int notLinked = 0;
        int inSync = 0;
        int pendingUpdate = 0;
        int apiRemovedStale = 0;
        int globalMissing = 0;
        for (ScanProjectToolEntity tool : tools) {
            Long globalId = tool.getGlobalToolDefinitionId();
            ToolLinkStatus status = resolveToolLink(tool, globalId == null ? null : globals.get(globalId));
            switch (status.status()) {
                case "IN_SYNC" -> inSync++;
                case "PENDING_UPDATE" -> pendingUpdate++;
                case "API_REMOVED_STALE" -> apiRemovedStale++;
                case "GLOBAL_MISSING" -> globalMissing++;
                default -> notLinked++;
            }
        }
        return new ToolReconcileSummary(
                0,
                notLinked,
                inSync,
                pendingUpdate,
                apiRemovedStale,
                globalMissing,
                0);
    }

    public ToolLinkStatus resolveToolLink(ScanProjectToolEntity tool) {
        if (tool == null || tool.getGlobalToolDefinitionId() == null) {
            return new ToolLinkStatus("NOT_LINKED", null, List.of());
        }
        return resolveToolLink(tool, toolDefinitionMapper.selectById(tool.getGlobalToolDefinitionId()));
    }

    /** Source lists share one projection read, including duplicate and missing projection references. */
    public Map<Long, ToolLinkStatus> resolveToolLinks(List<ScanProjectToolEntity> tools) {
        Map<Long, ToolDefinitionEntity> globals = globalToolsById(tools);
        Map<Long, ToolLinkStatus> result = new LinkedHashMap<>();
        for (ScanProjectToolEntity tool : tools) {
            result.put(tool.getId(), resolveToolLink(tool, globals.get(tool.getGlobalToolDefinitionId())));
        }
        return result;
    }

    public Map<Long, String> moduleDisplayNames(Long projectId, List<Long> moduleIds) {
        List<Long> ids = moduleIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> result = new LinkedHashMap<>();
        for (ScanModuleEntity module : scanModuleMapper.selectList(Wrappers.<ScanModuleEntity>lambdaQuery()
                .eq(ScanModuleEntity::getProjectId, projectId).in(ScanModuleEntity::getId, ids))) {
            if (Objects.equals(projectId, module.getProjectId()) && ids.contains(module.getId())) {
                result.put(module.getId(), StringUtils.hasText(module.getDisplayName()) ? module.getDisplayName() : module.getName());
            }
        }
        return result;
    }


    public ScanProjectBlockers operationBlockers(Long projectId, ScanProjectBlockers.Operation operation) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        if (operation == null) throw new IllegalArgumentException("Project operation is required");
        return operation == ScanProjectBlockers.Operation.DELETE
                ? scanProjectBlockerService.analyzeDeletion(projectId)
                : scanProjectBlockerService.analyze(projectId);
    }

    private ScanResult performScan(ScanProjectEntity project, boolean merge, CapabilityScannerClient.ManifestData manifest) {
        boolean controllerScan = usesControllerScanner(project);
        // Both Controller and OpenAPI HTTP facts reconcile only from a full inventory. Legacy Tool
        // rows remain on their own path; a partial/unchanged document delta must never tombstone
        // an unreported source binding.
        HttpApiSourceKind sourceKind = controllerScan ? HttpApiSourceKind.CONTROLLER_SCAN : HttpApiSourceKind.OPENAPI_SCAN;
        ScannerHttpApiIntakeService.Plan httpApiPlan = scannerHttpApiIntake.prepare(
                sourceKind, project, manifest.httpApis(), manifest.httpApiInventoryComplete());
        List<String> toolNames = persistManifestTools(project, manifest, merge);
        // Whole-inventory validation precedes source rows; both writes share the project lock/transaction.
        scannerHttpApiIntake.observe(httpApiPlan);
        project.setToolCount(toolNames.size());
        project.setLastScannedAt(LocalDateTime.now(ZoneId.systemDefault()));
        bootstrapModulesFromTools(project.getId());
        updateStatus(project, "scanned", null);
        return new ScanResult(project.getId(), project.getName(), toolNames.size(), List.copyOf(toolNames));
    }

    private CapabilityScannerClient.ManifestData scanManifest(ScanProjectEntity project, Long incrementalSinceMs) {
        ScanSettings settings = ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
        CapabilityScannerClient.ScanRequest request = new CapabilityScannerClient.ScanRequest(
                scannerProjectMetadataName(project),
                project.getBaseUrl(),
                normalizeContextPath(project.getContextPath()),
                project.getScanPath(),
                project.getSpecFile(),
                toScannerOptions(settings),
                incrementalSinceMs);
        String type = normalizeScanType(project.getScanType());
        ApiResult<CapabilityScannerClient.ManifestData> response;
        if (usesControllerScanner(project)) {
            response = scannerClient.scanController(request);
        } else {
            response = scannerClient.scanOpenApi(request);
        }
        if (response == null) {
            throw new IllegalArgumentException("Scanner service returned empty response");
        }
        if (response.getCode() != 200) {
            throw new IllegalArgumentException(response.getMessage() == null ? "Scanner service failed" : response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalArgumentException("Scanner service returned empty manifest");
        }
        return response.getData();
    }

    private boolean usesControllerScanner(ScanProjectEntity project) {
        String type = normalizeScanType(project.getScanType());
        return "controller".equals(type) || ("auto".equals(type) && shouldAutoUseController(project));
    }

    private List<String> persistManifestTools(ScanProjectEntity project,
                                              CapabilityScannerClient.ManifestData manifest,
                                              boolean merge) {
        List<CapabilityScannerClient.ToolData> manifestTools = manifest.tools() == null ? List.of() : manifest.tools();
        List<ScanProjectToolEntity> existing = merge ? listTools(project.getId()) : List.of();
        existing.forEach(this::requireCatalogWritable);
        Map<String, ScanProjectToolEntity> existingByName = new LinkedHashMap<>();
        for (ScanProjectToolEntity tool : existing) {
            existingByName.put(tool.getName(), tool);
        }
        boolean useProjectPrefix = !isControllerScannerManifest(manifestTools);
        List<String> names = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (CapabilityScannerClient.ToolData tool : manifestTools) {
            String name = buildUniqueToolName(project.getName(), tool.name(), useProjectPrefix, seen, existingByName);
            ScanProjectToolEntity entity = merge ? existingByName.get(name) : null;
            boolean inserting = entity == null;
            if (inserting) {
                entity = new ScanProjectToolEntity();
                entity.setProjectId(project.getId());
                entity.setName(name);
            }
            applyManifestTool(project, tool, entity, inserting, name);
            if (inserting) {
                scanProjectToolMapper.insert(entity);
            } else {
                scanProjectToolMapper.updateById(entity);
            }
            names.add(name);
            seen.add(name);
        }
        if (merge) {
            LocalDateTime now = LocalDateTime.now();
            for (ScanProjectToolEntity old : existing) {
                if (seen.contains(old.getName())) {
                    continue;
                }
                old.setRemovedFromSource(true);
                old.setRemovedAt(now);
                scanProjectToolMapper.updateById(old);
            }
        }
        return names;
    }

    private void applyManifestTool(ScanProjectEntity project,
                                   CapabilityScannerClient.ToolData tool,
                                   ScanProjectToolEntity entity,
                                   boolean inserting,
                                   String name) {
        CapabilitySourceOwnership.requireCatalogWritable(entity);
        CapabilitySourceOwnership.requireUnmanagedLocation(tool.source() == null ? null : tool.source().location());
        if (inserting) {
            entity.setName(name);
        }
        ScanSettings settings = ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
        ScanDefaultFlags flags = settings.getDefaultFlags() == null ? ScanDefaultFlags.defaults() : settings.getDefaultFlags();
        entity.setTitle(resolveManifestToolTitle(tool, name));
        entity.setDescription(tool.description());
        entity.setParametersJson(writeJson(toToolDefinitionParameters(tool.parameters())));
        entity.setSource("scanner");
        entity.setAssetType("HTTP_API");
        entity.setSourceLocation(tool.source() == null ? null : tool.source().location());
        entity.setHttpMethod(tool.method());
        entity.setBaseUrl(resolveManifestBaseUrl(project));
        entity.setContextPath(normalizeContextPath(project.getContextPath()));
        entity.setEndpointPath(tool.path());
        entity.setRequestBodyType(tool.requestBodyType());
        entity.setResponseType(tool.responseType());
        entity.setCapabilityMetadataJson(tool.capabilityMetadata() == null ? null : writeJson(tool.capabilityMetadata()));
        if (inserting) {
            entity.setEnabled(flags.isEnabled());
        }
        entity.setRemovedFromSource(false);
        entity.setRemovedAt(null);
    }

    private List<ToolDefinitionParameter> toToolDefinitionParameters(List<CapabilityScannerClient.ToolParameterData> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        return parameters.stream().map(parameter -> new ToolDefinitionParameter(
                parameter.name(),
                parameter.type(),
                parameter.description(),
                parameter.required(),
                parameter.location(),
                toToolDefinitionParameters(parameter.children()),
                parameter.metadata()
        )).toList();
    }

    private String resolveManifestToolTitle(CapabilityScannerClient.ToolData tool, String fallbackName) {
        if (tool.capabilityMetadata() instanceof Map<?, ?> metadata) {
            Object rawTitle = metadata.get("title");
            if (rawTitle != null && StringUtils.hasText(String.valueOf(rawTitle))) {
                return String.valueOf(rawTitle).trim();
            }
        }
        return StringUtils.hasText(tool.name()) ? tool.name().trim() : fallbackName;
    }

    private CapabilityScannerClient.ToolData findManifestTool(ScanProjectEntity project,
                                                              ScanProjectToolEntity existing,
                                                              CapabilityScannerClient.ManifestData manifest) {
        List<CapabilityScannerClient.ToolData> tools = manifest.tools() == null ? List.of() : manifest.tools();
        HttpApiSourceKind kind = usesControllerScanner(project) ? HttpApiSourceKind.CONTROLLER_SCAN : HttpApiSourceKind.OPENAPI_SCAN;
        String sourceKind = kind == HttpApiSourceKind.CONTROLLER_SCAN ? "controller" : "openapi";
        String method = ScannerHttpApiSourceIdentity.method(existing.getHttpMethod());
        String route = ScannerHttpApiSourceIdentity.route(existing.getContextPath(), existing.getEndpointPath());
        List<CapabilityScannerClient.ToolData> matches = tools.stream().filter(Objects::nonNull)
                .filter(tool -> tool.source() != null && sourceKind.equals(tool.source().scanner())
                        && method.equals(ScannerHttpApiSourceIdentity.method(tool.method()))
                        && route.equals(ScannerHttpApiSourceIdentity.route(project.getContextPath(), tool.path()))
                        && ScannerHttpApiSourceIdentity.matches(kind, existing.getSourceLocation(), tool.source().location()))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void bootstrapModulesFromTools(Long projectId) {
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
        groupingService.bootstrap(projectId, tools);
    }

    private CapabilityScannerClient.ScanOptions toScannerOptions(ScanSettings settings) {
        return new CapabilityScannerClient.ScanOptions(
                settings.getDescriptionSourceOrder(),
                settings.getParamDescriptionSourceOrder(),
                settings.getDescriptionSourceEnabled(),
                settings.getParamDescriptionSourceEnabled(),
                settings.isOnlyRestController(),
                settings.getHttpMethodWhitelist() == null ? List.of() : settings.getHttpMethodWhitelist(),
                settings.getClassIncludeRegex(),
                settings.getClassExcludeRegex(),
                settings.isSkipDeprecated(),
                settings.getIncrementalMode());
    }

    private boolean shouldAutoUseController(ScanProjectEntity project) {
        String scanPath = project.getScanPath();
        if (!StringUtils.hasText(scanPath)) {
            return true;
        }
        String lower = scanPath.trim().toLowerCase(Locale.ROOT);
        return !(lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml"));
    }

    private String scannerProjectMetadataName(ScanProjectEntity project) {
        String slug = normalizeName(project.getName());
        if (StringUtils.hasText(slug)) {
            return slug;
        }
        return "project_" + project.getId();
    }

    private String buildUniqueToolName(String projectName,
                                       String rawToolName,
                                       boolean useProjectPrefix,
                                       List<String> seen,
                                       Map<String, ScanProjectToolEntity> existingByName) {
        String base = useProjectPrefix
                ? normalizeName(projectName) + "__" + normalizeName(rawToolName)
                : normalizeName(rawToolName);
        if (!StringUtils.hasText(base) || base.endsWith("__")) {
            base = "tool";
        }
        String candidate = base;
        int suffix = 2;
        while (seen.contains(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private boolean isControllerScannerManifest(List<CapabilityScannerClient.ToolData> tools) {
        if (tools.isEmpty()) {
            return false;
        }
        for (CapabilityScannerClient.ToolData tool : tools) {
            if (tool.source() == null || !"controller".equals(tool.source().scanner())) {
                return false;
            }
        }
        return true;
    }

    private String resolveManifestBaseUrl(ScanProjectEntity project) {
        return StringUtils.hasText(project.getBaseUrl()) ? project.getBaseUrl().trim() : null;
    }

    private void updateStatus(ScanProjectEntity project, String status, String errorMessage) {
        project.setStatus(status);
        project.setErrorMessage(errorMessage);
        scanProjectMapper.updateById(project);
        if (errorMessage == null) {
            scanProjectMapper.update(null, Wrappers.<ScanProjectEntity>update()
                    .eq("id", project.getId())
                    .set("error_message", null));
        }
    }

    private void clearStaleProjectErrorAfterSingleToolRefresh(ScanProjectEntity project) {
        boolean failedStatus = "failed".equalsIgnoreCase(project.getStatus());
        if (!StringUtils.hasText(project.getErrorMessage()) && !failedStatus) {
            return;
        }
        project.setErrorMessage(null);
        if (failedStatus) {
            project.setStatus("scanned");
        }
        scanProjectMapper.update(null, Wrappers.<ScanProjectEntity>update()
                .eq("id", project.getId())
                .set("error_message", null)
                .set(failedStatus, "status", "scanned"));
    }

    private Long toEpochMs(LocalDateTime time) {
        if (time == null) {
            return 0L;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
    }

    private RegistryCredentialEntity primaryCredential(String projectCode) {
        return registrySecurityService.findPrimaryActiveCredential(projectCode).orElse(null);
    }

    private Map<Long, ToolDefinitionEntity> globalToolsById(List<ScanProjectToolEntity> tools) {
        List<Long> ids = tools.stream()
                .map(ScanProjectToolEntity::getGlobalToolDefinitionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ToolDefinitionEntity> result = new LinkedHashMap<>();
        for (ToolDefinitionEntity tool : toolDefinitionMapper.selectBatchIds(ids)) {
            result.put(tool.getId(), tool);
        }
        return result;
    }

    private ToolLinkStatus resolveToolLink(ScanProjectToolEntity scanTool, ToolDefinitionEntity globalTool) {
        if (scanTool == null || scanTool.getGlobalToolDefinitionId() == null) {
            return new ToolLinkStatus("NOT_LINKED", null, List.of());
        }
        if (Boolean.TRUE.equals(scanTool.getRemovedFromSource())) {
            return new ToolLinkStatus("API_REMOVED_STALE", "API source no longer reports this tool", List.of());
        }
        if (globalTool == null) {
            return new ToolLinkStatus("GLOBAL_MISSING", "Linked global Tool no longer exists", List.of());
        }
        List<String> diffFields = diffFields(scanTool, globalTool);
        if (diffFields.isEmpty()) {
            return new ToolLinkStatus("IN_SYNC", null, List.of());
        }
        return new ToolLinkStatus("PENDING_UPDATE", "Global Tool differs from scan row", diffFields);
    }

    private List<String> diffFields(ScanProjectToolEntity scanTool, ToolDefinitionEntity globalTool) {
        List<String> fields = new ArrayList<>();
        compare(fields, "name", scanTool.getName(), globalTool.getName());
        compare(fields, "title", scanTool.getTitle(), globalTool.getTitle());
        compare(fields, "description", scanTool.getDescription(), globalTool.getDescription());
        compare(fields, "parameters", scanTool.getParametersJson(), globalTool.getParametersJson());
        compare(fields, "httpMethod", scanTool.getHttpMethod(), globalTool.getHttpMethod());
        compare(fields, "baseUrl", scanTool.getBaseUrl(), globalTool.getBaseUrl());
        compare(fields, "contextPath", scanTool.getContextPath(), globalTool.getContextPath());
        compare(fields, "endpointPath", scanTool.getEndpointPath(), globalTool.getEndpointPath());
        compare(fields, "requestBodyType", scanTool.getRequestBodyType(), globalTool.getRequestBodyType());
        compare(fields, "responseType", scanTool.getResponseType(), globalTool.getResponseType());
        compare(fields, "enabled", scanTool.getEnabled(), globalTool.getEnabled());
        return fields;
    }

    private void compare(List<String> fields, String field, Object left, Object right) {
        if (!Objects.equals(left, right)) {
            fields.add(field);
        }
    }

    private void requireCatalogWritable(ScanProjectToolEntity tool) {
        CapabilitySourceOwnership.requireCatalogWritable(tool);
        if (tool.getGlobalToolDefinitionId() != null) {
            CapabilitySourceOwnership.requireCatalogWritable(
                    toolDefinitionMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                            .eq(ToolDefinitionEntity::getId, tool.getGlobalToolDefinitionId()).last("limit 1 for update")));
        }
    }


    private java.util.Optional<ScanProjectEntity> findByName(String name) {
        if (!StringUtils.hasText(name)) {
            return java.util.Optional.empty();
        }
        ScanProjectEntity entity = scanProjectMapper.selectOne(new LambdaQueryWrapper<ScanProjectEntity>()
                .eq(ScanProjectEntity::getName, name.trim())
                .last("limit 1"));
        return java.util.Optional.ofNullable(entity);
    }

    private void validateRequest(ScanProjectUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        if (!StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("Project name is required");
        }
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new IllegalArgumentException("Project baseUrl is required");
        }
        String normalizedBaseUrl = normalizeHttpBaseUrl(request.baseUrl());
        if (!isValidRestClientBaseUrl(normalizedBaseUrl)) {
            throw new IllegalArgumentException("Project baseUrl must be an absolute URL with host");
        }
        String kind = normalizeProjectKind(request.projectKind());
        if (!"REGISTERED".equals(kind) && !StringUtils.hasText(request.scanPath())) {
            throw new IllegalArgumentException("Scan path is required");
        }
        normalizeScanType(request.scanType());
    }


    private ScanProjectEntity applyRequest(ScanProjectEntity entity, ScanProjectUpsertRequest request) {
        entity.setName(request.name().trim());
        entity.setProjectCode(StringUtils.hasText(request.projectCode())
                ? normalizeProjectCode(request.projectCode())
                : normalizeProjectCode(request.name()));
        entity.setProjectKind(normalizeProjectKind(request.projectKind()));
        entity.setEnvironment(normalizeEnvironment(request.environment()));
        entity.setOwner(StringUtils.hasText(request.owner()) ? request.owner().trim() : null);
        entity.setVisibility(normalizeVisibility(request.visibility()));
        entity.setBaseUrl(normalizeHttpBaseUrl(request.baseUrl()));
        entity.setContextPath(normalizeContextPath(request.contextPath()));
        entity.setScanPath(StringUtils.hasText(request.scanPath()) ? request.scanPath().trim() : "");
        entity.setScanType(normalizeScanType(request.scanType()));
        entity.setSpecFile(StringUtils.hasText(request.specFile()) ? request.specFile().trim() : null);
        return entity;
    }

    private String normalizeProjectCode(String value) {
        String source = StringUtils.hasText(value) ? value.trim() : "project";
        String normalized = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "project" : normalized;
    }

    private String normalizeProjectKind(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "SCAN";
        if (!List.of("SCAN", "REGISTERED", "HYBRID").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported project kind: " + value);
        }
        return normalized;
    }

    private String normalizeEnvironment(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "default";
    }

    private String normalizeVisibility(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "PRIVATE";
        if (!List.of("PRIVATE", "PROJECT", "SHARED", "PUBLIC").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported visibility: " + value);
        }
        return normalized;
    }

    private String normalizeScanType(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "openapi";
        if (!List.of("openapi", "controller", "auto").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported scan type: " + value);
        }
        return normalized;
    }

    private String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath)) {
            return "";
        }
        String trimmed = contextPath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String normalizeHttpBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.trim()
                .replaceFirst("^http:(?!//)", "http://")
                .replaceFirst("^https:(?!//)", "https://");
    }

    private boolean isValidRestClientBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            return uri.isAbsolute()
                    && StringUtils.hasText(uri.getScheme())
                    && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Scan tool JSON serialize failed", ex);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String stableKey(ScanProjectToolEntity tool) {
        String method = tool.getHttpMethod() == null ? "" : tool.getHttpMethod().trim().toUpperCase(Locale.ROOT);
        String path = tool.getEndpointPath() == null ? "" : tool.getEndpointPath().trim();
        if (StringUtils.hasText(method) || StringUtils.hasText(path)) {
            return method + " " + path;
        }
        return StringUtils.hasText(tool.getSourceLocation()) ? tool.getSourceLocation() : tool.getName();
    }

    public record ScanProjectUpsertRequest(
            String name,
            String projectCode,
            String projectKind,
            String environment,
            String owner,
            String visibility,
            String baseUrl,
            String contextPath,
            String scanPath,
            String scanType,
            String specFile
    ) {
    }

    public record ScanProjectRegistryCredentialSaveRequest(String appKey, String appSecret) {
    }


    public record ToolLinkStatus(String status, String message, List<String> diffFields) {
    }

    public record ToolReconcileSummary(int sdkMirrorsEnsured,
                                       int notLinked,
                                       int inSync,
                                       int pendingUpdate,
                                       int apiRemovedStale,
                                       int globalMissing,
                                       int sdkReviewPendingRows) {
    }



    public record ScanResult(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }

    public static class ScanProjectBlockedException extends RuntimeException {

        private final ScanProjectBlockers blockers;

        public ScanProjectBlockedException(ScanProjectBlockers blockers) {
            super("Scan project is still referenced");
            this.blockers = blockers;
        }

        public ScanProjectBlockers blockers() {
            return blockers;
        }
    }
}
