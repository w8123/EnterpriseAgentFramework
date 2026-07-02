package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
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
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CapabilityScanProjectCatalogService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ScanProjectMapper scanProjectMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ScanModuleMapper scanModuleMapper;
    private final SemanticDocMapper semanticDocMapper;
    private final RegistryCredentialMapper registryCredentialMapper;
    private final RegistrySecurityService registrySecurityService;
    private final CapabilityScanProjectBlockerService scanProjectBlockerService;
    private final CapabilityToolExecutionService toolExecutionService;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityScannerClient scannerClient;
    private final ObjectMapper objectMapper;

    public List<ScanProjectEntity> list() {
        return scanProjectMapper.selectList(new LambdaQueryWrapper<ScanProjectEntity>()
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
        entity.setAiCodingAccessKey(generateAiCodingAccessKey());
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

    public ScanProjectEntity updateAuthSettings(Long id, ScanProjectAuthSaveRequest request) {
        ScanProjectEntity entity = get(id);
        if (request == null) {
            throw new IllegalArgumentException("Auth settings are required");
        }
        String authType = StringUtils.hasText(request.authType())
                ? request.authType().trim().toLowerCase(Locale.ROOT)
                : "none";
        if (!List.of("none", "api_key").contains(authType)) {
            throw new IllegalArgumentException("Unsupported auth type: " + request.authType());
        }
        entity.setAuthType(authType);
        if ("none".equals(authType)) {
            entity.setAuthApiKeyIn(null);
            entity.setAuthApiKeyName(null);
            entity.setAuthApiKeyValue(null);
        } else {
            String keyIn = StringUtils.hasText(request.authApiKeyIn())
                    ? request.authApiKeyIn().trim().toLowerCase(Locale.ROOT)
                    : "header";
            if (!List.of("header", "query").contains(keyIn)) {
                throw new IllegalArgumentException("Unsupported api key location: " + request.authApiKeyIn());
            }
            if (!StringUtils.hasText(request.authApiKeyName()) || !StringUtils.hasText(request.authApiKeyValue())) {
                throw new IllegalArgumentException("API key name/value are required");
            }
            entity.setAuthApiKeyIn(keyIn);
            entity.setAuthApiKeyName(request.authApiKeyName().trim());
            entity.setAuthApiKeyValue(request.authApiKeyValue().trim());
        }
        scanProjectMapper.updateById(entity);
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

    public SdkAccessCheckResponse sdkAccessCheck(Long projectId) {
        ScanProjectEntity entity = get(projectId);
        RegistryCredentialEntity credential = primaryCredential(entity.getProjectCode());
        boolean credentialConfigured = credential != null;
        boolean aiCodingEnabled = Boolean.TRUE.equals(entity.getAiCodingAccessEnabled())
                && StringUtils.hasText(entity.getAiCodingAccessKey());
        List<SdkAccessCheckItem> checks = List.of(
                new SdkAccessCheckItem(
                        "PROJECT",
                        "项目识别",
                        "PASS",
                        "已读取项目 " + entity.getProjectCode(),
                        null),
                new SdkAccessCheckItem(
                        "REGISTRY_CREDENTIAL",
                        "注册凭据",
                        credentialConfigured ? "PASS" : "WARN",
                        credentialConfigured ? "已配置 active registry credential" : "尚未配置 active registry credential",
                        null),
                new SdkAccessCheckItem(
                        "AI_CODING_ACCESS",
                        "AI Coding 接入",
                        aiCodingEnabled ? "PASS" : "WARN",
                        aiCodingEnabled ? "已启用 AI Coding 接入" : "AI Coding 接入未启用",
                        null));
        String overall = checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
                ? "FAIL"
                : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN" : "PASS";
        return new SdkAccessCheckResponse(
                entity.getId(),
                entity.getProjectCode(),
                overall,
                List.of(
                        new SdkAccessReadiness("CODE_READY", "代码接入", overall, "SDK onboarding route is available"),
                        new SdkAccessReadiness("RUNTIME_READY", "Runtime 就绪", overall, "Runtime readiness requires business service heartbeat"),
                        new SdkAccessReadiness("E2E_READY", "端到端", overall, "Run a business API call to complete final verification")),
                checks);
    }

    @Transactional
    public void delete(Long projectId) {
        ScanProjectEntity entity = get(projectId);
        ScanProjectBlockers blockers = scanProjectBlockerService.analyze(projectId);
        if (blockers != null && blockers.blocked()) {
            throw new IllegalStateException("Scan project is still referenced");
        }
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

    public ScanDiffSummary diffSummary(Long projectId) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(
                new LambdaQueryWrapper<ScanProjectToolEntity>()
                        .eq(ScanProjectToolEntity::getProjectId, projectId));
        Map<String, List<Long>> idsByStableKey = new LinkedHashMap<>();
        int missingDescription = 0;
        int missingAiDescription = 0;
        int promoted = 0;
        for (ScanProjectToolEntity tool : tools) {
            idsByStableKey.computeIfAbsent(stableKey(tool), ignored -> new ArrayList<>()).add(tool.getId());
            if (!StringUtils.hasText(tool.getDescription())) {
                missingDescription++;
            }
            if (!StringUtils.hasText(tool.getAiDescription())) {
                missingAiDescription++;
            }
            if (tool.getGlobalToolDefinitionId() != null) {
                promoted++;
            }
        }
        List<DuplicateStableKey> duplicates = idsByStableKey.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new DuplicateStableKey(entry.getKey(), entry.getValue()))
                .toList();
        return new ScanDiffSummary(
                projectId,
                tools.size(),
                promoted,
                missingDescription,
                missingAiDescription,
                duplicates.size(),
                duplicates);
    }

    public List<ScanProjectToolEntity> listTools(Long projectId) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        return scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .orderByAsc(ScanProjectToolEntity::getId));
    }

    public ScanProjectToolEntity getTool(Long projectId, Long scanToolId) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        ScanProjectToolEntity tool = scanProjectToolMapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getId, scanToolId)
                .last("limit 1"));
        if (tool == null) {
            throw new IllegalArgumentException("Scan project tool does not exist: " + scanToolId);
        }
        return tool;
    }

    public ScanProjectToolEntity updateTool(Long projectId, Long scanToolId, ScanProjectToolUpsertRequest request) {
        validateToolRequest(request);
        ScanProjectToolEntity tool = getTool(projectId, scanToolId);
        tool.setName(request.name().trim());
        tool.setDescription(request.description().trim());
        tool.setParametersJson(writeJson(request.parameters() == null ? List.of() : request.parameters()));
        tool.setSource(StringUtils.hasText(request.source()) ? request.source().trim() : "code");
        tool.setSourceLocation(trimToNull(request.sourceLocation()));
        tool.setHttpMethod(StringUtils.hasText(request.httpMethod())
                ? request.httpMethod().trim().toUpperCase(Locale.ROOT)
                : null);
        tool.setBaseUrl(trimToNull(request.baseUrl()));
        tool.setContextPath(normalizeContextPath(request.contextPath()));
        tool.setEndpointPath(trimToNull(request.endpointPath()));
        tool.setRequestBodyType(trimToNull(request.requestBodyType()));
        tool.setResponseType(trimToNull(request.responseType()));
        tool.setEnabled(Boolean.TRUE.equals(request.enabled()));
        tool.setAgentVisible(Boolean.TRUE.equals(request.agentVisible()));
        tool.setLightweightEnabled(Boolean.TRUE.equals(request.lightweightEnabled()));
        scanProjectToolMapper.updateById(tool);
        return tool;
    }

    public ScanProjectToolEntity toggleTool(Long projectId, Long scanToolId, boolean enabled) {
        ScanProjectToolEntity tool = getTool(projectId, scanToolId);
        tool.setEnabled(enabled);
        scanProjectToolMapper.updateById(tool);
        return tool;
    }

    @Transactional
    public ScanResult scan(Long projectId) {
        ScanProjectEntity project = get(projectId);
        if (!listTools(projectId).isEmpty()) {
            throw new IllegalArgumentException("Project already has scan results, use rescan instead");
        }
        return performScan(project, false);
    }

    @Transactional
    public ScanResult rescan(Long projectId) {
        ScanProjectEntity project = get(projectId);
        ScanProjectBlockers blockers = scanProjectBlockerService.analyze(projectId);
        if (blockers != null && blockers.blocked()) {
            throw new ScanProjectBlockedException(blockers);
        }
        return performScan(project, true);
    }

    @Transactional
    public ScanProjectToolEntity rescanSingleTool(Long projectId, Long scanToolId) {
        ScanProjectEntity project = get(projectId);
        ScanProjectToolEntity existing = getTool(projectId, scanToolId);
        CapabilityScannerClient.ManifestData manifest = scanManifest(project, null);
        CapabilityScannerClient.ToolData matched = findManifestTool(project, existing, manifest);
        if (matched == null) {
            throw new IllegalArgumentException("No matching endpoint found in scanner manifest for scan tool: " + scanToolId);
        }
        applyManifestTool(project, matched, existing, false, existing.getName());
        scanProjectToolMapper.updateById(existing);
        bootstrapModulesFromTools(projectId);
        clearStaleProjectErrorAfterSingleToolRefresh(project);
        return existing;
    }

    public void markFailed(Long projectId, String errorMessage) {
        ScanProjectEntity project = scanProjectMapper.selectById(projectId);
        if (project == null) {
            return;
        }
        project.setStatus("failed");
        project.setErrorMessage(errorMessage);
        scanProjectMapper.updateById(project);
    }

    public Map<String, Object> testTool(Long projectId, Long scanToolId, Map<String, Object> args) {
        ScanProjectToolEntity tool = getTool(projectId, scanToolId);
        return toolExecutionService.execute(tool, Map.of("input", args == null ? Map.of() : args));
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
            ToolLinkStatus status = resolveToolLink(tool, globals.get(tool.getGlobalToolDefinitionId()));
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

    @Transactional
    public PromotedGlobalTool promoteTool(Long projectId, Long scanToolId) {
        ScanProjectEntity project = get(projectId);
        ScanProjectToolEntity scanTool = getTool(projectId, scanToolId);
        if (Boolean.TRUE.equals(scanTool.getRemovedFromSource())) {
            throw new IllegalArgumentException("Removed scan tool cannot be promoted: " + scanToolId);
        }
        if (scanTool.getGlobalToolDefinitionId() != null) {
            ToolDefinitionEntity existing = toolDefinitionMapper.selectById(scanTool.getGlobalToolDefinitionId());
            if (existing != null) {
                return new PromotedGlobalTool(existing.getId(), existing.getName());
            }
        }
        ToolDefinitionEntity globalTool = new ToolDefinitionEntity();
        applyScanToolToGlobalTool(project, scanTool, globalTool);
        LocalDateTime now = LocalDateTime.now();
        globalTool.setCreateTime(now);
        globalTool.setUpdateTime(now);
        toolDefinitionMapper.insert(globalTool);
        scanTool.setGlobalToolDefinitionId(globalTool.getId());
        scanProjectToolMapper.updateById(scanTool);
        return new PromotedGlobalTool(globalTool.getId(), globalTool.getName());
    }

    @Transactional
    public ScanProjectToolEntity pushToolToGlobal(Long projectId, Long scanToolId) {
        ScanProjectEntity project = get(projectId);
        ScanProjectToolEntity scanTool = getTool(projectId, scanToolId);
        if (scanTool.getGlobalToolDefinitionId() == null) {
            throw new IllegalArgumentException("Scan project tool is not linked to global Tool: " + scanToolId);
        }
        ToolDefinitionEntity globalTool = toolDefinitionMapper.selectById(scanTool.getGlobalToolDefinitionId());
        if (globalTool == null) {
            throw new IllegalArgumentException("Linked global Tool does not exist: " + scanTool.getGlobalToolDefinitionId());
        }
        applyScanToolToGlobalTool(project, scanTool, globalTool);
        globalTool.setUpdateTime(LocalDateTime.now());
        toolDefinitionMapper.updateById(globalTool);
        return scanTool;
    }

    @Transactional
    public ScanProjectToolEntity unpromoteTool(Long projectId, Long scanToolId) {
        ScanProjectToolEntity scanTool = getTool(projectId, scanToolId);
        Long globalToolId = scanTool.getGlobalToolDefinitionId();
        if (globalToolId != null) {
            toolDefinitionMapper.deleteById(globalToolId);
        }
        scanTool.setGlobalToolDefinitionId(null);
        scanProjectToolMapper.updateById(scanTool);
        return scanTool;
    }

    @Transactional
    public BatchPromoteToToolsResult promoteModuleTools(Long projectId, Long moduleId) {
        ScanProjectEntity project = get(projectId);
        List<PromotedGlobalTool> promoted = new ArrayList<>();
        for (ScanProjectToolEntity scanTool : listTools(projectId)) {
            if (!Objects.equals(moduleId, scanTool.getModuleId())) {
                continue;
            }
            if (scanTool.getGlobalToolDefinitionId() != null || Boolean.TRUE.equals(scanTool.getRemovedFromSource())) {
                continue;
            }
            ToolDefinitionEntity globalTool = new ToolDefinitionEntity();
            applyScanToolToGlobalTool(project, scanTool, globalTool);
            LocalDateTime now = LocalDateTime.now();
            globalTool.setCreateTime(now);
            globalTool.setUpdateTime(now);
            toolDefinitionMapper.insert(globalTool);
            scanTool.setGlobalToolDefinitionId(globalTool.getId());
            scanProjectToolMapper.updateById(scanTool);
            promoted.add(new PromotedGlobalTool(globalTool.getId(), globalTool.getName()));
        }
        return new BatchPromoteToToolsResult(promoted.size(), promoted);
    }

    public ScanProjectBlockers operationBlockers(Long projectId) {
        if (scanProjectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        return scanProjectBlockerService.analyze(projectId);
    }

    private ScanResult performScan(ScanProjectEntity project, boolean merge) {
        Long sinceMs = merge ? toEpochMs(project.getLastScannedAt()) : null;
        CapabilityScannerClient.ManifestData manifest = scanManifest(project, sinceMs);
        List<String> toolNames = persistManifestTools(project, manifest, merge);
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
        if ("controller".equals(type)) {
            response = scannerClient.scanController(request);
        } else if ("auto".equals(type) && shouldAutoUseController(project)) {
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

    private List<String> persistManifestTools(ScanProjectEntity project,
                                              CapabilityScannerClient.ManifestData manifest,
                                              boolean merge) {
        List<CapabilityScannerClient.ToolData> manifestTools = manifest.tools() == null ? List.of() : manifest.tools();
        List<ScanProjectToolEntity> existing = merge ? listTools(project.getId()) : List.of();
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
        if (inserting) {
            entity.setName(name);
        }
        ScanSettings settings = ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
        ScanDefaultFlags flags = settings.getDefaultFlags() == null ? ScanDefaultFlags.defaults() : settings.getDefaultFlags();
        entity.setDescription(tool.description());
        entity.setParametersJson(writeJson(toToolDefinitionParameters(tool.parameters())));
        entity.setSource("scanner");
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
            entity.setAgentVisible(agentVisibleFromMetadata(tool.capabilityMetadata(), flags.isAgentVisible()));
            entity.setLightweightEnabled(flags.isLightweightEnabled());
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

    private CapabilityScannerClient.ToolData findManifestTool(ScanProjectEntity project,
                                                              ScanProjectToolEntity existing,
                                                              CapabilityScannerClient.ManifestData manifest) {
        List<CapabilityScannerClient.ToolData> tools = manifest.tools() == null ? List.of() : manifest.tools();
        String wantMethod = normalizeHttpMethod(existing.getHttpMethod());
        String wantPath = normalizePathForCompare(combineHttpPath(existing.getContextPath(), existing.getEndpointPath()));
        String wantProjectPath = normalizePathForCompare(combineHttpPath(project.getContextPath(), existing.getEndpointPath()));
        for (CapabilityScannerClient.ToolData tool : tools) {
            if (!wantMethod.equals(normalizeHttpMethod(tool.method()))) {
                continue;
            }
            String candidate = normalizePathForCompare(combineHttpPath(project.getContextPath(), tool.path()));
            if (wantPath.equals(candidate) || wantProjectPath.equals(candidate)) {
                return tool;
            }
        }
        if (!StringUtils.hasText(existing.getSourceLocation())) {
            return null;
        }
        String sourceLocation = existing.getSourceLocation().trim();
        for (CapabilityScannerClient.ToolData tool : tools) {
            if (tool.source() != null
                    && StringUtils.hasText(tool.source().location())
                    && sourceLocation.equals(tool.source().location().trim())) {
                return tool;
            }
        }
        return null;
    }

    private void bootstrapModulesFromTools(Long projectId) {
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
        if (tools.isEmpty()) {
            return;
        }
        Map<String, ScanModuleEntity> modulesByName = new LinkedHashMap<>();
        for (ScanModuleEntity module : scanModuleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                .eq(ScanModuleEntity::getProjectId, projectId))) {
            modulesByName.put(module.getName().toLowerCase(Locale.ROOT), module);
        }
        for (ScanProjectToolEntity tool : tools) {
            String moduleName = resolveModuleName(tool);
            ScanModuleEntity module = modulesByName.get(moduleName.toLowerCase(Locale.ROOT));
            if (module == null) {
                module = new ScanModuleEntity();
                module.setProjectId(projectId);
                module.setName(moduleName);
                module.setDisplayName(moduleName);
                module.setSourceClasses(writeJson(List.of(moduleName)));
                scanModuleMapper.insert(module);
                modulesByName.put(moduleName.toLowerCase(Locale.ROOT), module);
            }
            if (!Objects.equals(tool.getModuleId(), module.getId())) {
                tool.setModuleId(module.getId());
                scanProjectToolMapper.updateById(tool);
            }
        }
    }

    private String resolveModuleName(ScanProjectToolEntity tool) {
        String sourceLocation = tool.getSourceLocation();
        if (StringUtils.hasText(sourceLocation)) {
            String[] parts = sourceLocation.split("#");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) {
                return parts[1].trim();
            }
            String first = parts[0].trim();
            int dot = first.lastIndexOf('.');
            return dot > 0 ? first.substring(0, dot) : first;
        }
        return StringUtils.hasText(tool.getHttpMethod())
                ? tool.getHttpMethod().trim().toLowerCase(Locale.ROOT) + "_module"
                : "default";
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

    private boolean agentVisibleFromMetadata(Object metadata, boolean fallback) {
        if (metadata instanceof Map<?, ?> map) {
            Object raw = map.get("agentVisible");
            if (raw instanceof Boolean bool) {
                return bool;
            }
            if (raw != null) {
                String text = String.valueOf(raw).trim();
                if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                    return Boolean.parseBoolean(text);
                }
            }
        }
        return fallback;
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

    private String normalizeHttpMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private String combineHttpPath(String contextPath, String endpointPath) {
        String context = normalizeContextPath(contextPath);
        String endpoint = StringUtils.hasText(endpointPath) ? endpointPath.trim() : "";
        if (!endpoint.isEmpty() && !endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        if (!StringUtils.hasText(context)) {
            return endpoint.isEmpty() ? "/" : endpoint;
        }
        if (endpoint.isEmpty()) {
            return context;
        }
        return context.endsWith("/") ? context.substring(0, context.length() - 1) + endpoint : context + endpoint;
    }

    private String normalizePathForCompare(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
        if (!StringUtils.hasText(projectCode)) {
            return null;
        }
        return registryCredentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .last("limit 1"));
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
        compare(fields, "description", scanTool.getDescription(), globalTool.getDescription());
        compare(fields, "parameters", scanTool.getParametersJson(), globalTool.getParametersJson());
        compare(fields, "httpMethod", scanTool.getHttpMethod(), globalTool.getHttpMethod());
        compare(fields, "baseUrl", scanTool.getBaseUrl(), globalTool.getBaseUrl());
        compare(fields, "contextPath", scanTool.getContextPath(), globalTool.getContextPath());
        compare(fields, "endpointPath", scanTool.getEndpointPath(), globalTool.getEndpointPath());
        compare(fields, "requestBodyType", scanTool.getRequestBodyType(), globalTool.getRequestBodyType());
        compare(fields, "responseType", scanTool.getResponseType(), globalTool.getResponseType());
        compare(fields, "enabled", scanTool.getEnabled(), globalTool.getEnabled());
        compare(fields, "agentVisible", scanTool.getAgentVisible(), globalTool.getAgentVisible());
        compare(fields, "lightweightEnabled", scanTool.getLightweightEnabled(), globalTool.getLightweightEnabled());
        return fields;
    }

    private void compare(List<String> fields, String field, Object left, Object right) {
        if (!Objects.equals(left, right)) {
            fields.add(field);
        }
    }

    private void applyScanToolToGlobalTool(ScanProjectEntity project,
                                           ScanProjectToolEntity scanTool,
                                           ToolDefinitionEntity globalTool) {
        globalTool.setName(scanTool.getName());
        globalTool.setKind("TOOL");
        globalTool.setDescription(scanTool.getDescription());
        globalTool.setAiDescription(scanTool.getAiDescription());
        globalTool.setCapabilityMetadataJson(scanTool.getCapabilityMetadataJson());
        globalTool.setParametersJson(scanTool.getParametersJson());
        globalTool.setSource("scanner");
        globalTool.setSourceLocation(scanTool.getSourceLocation());
        globalTool.setHttpMethod(scanTool.getHttpMethod());
        globalTool.setBaseUrl(scanTool.getBaseUrl());
        globalTool.setContextPath(scanTool.getContextPath());
        globalTool.setEndpointPath(scanTool.getEndpointPath());
        globalTool.setRequestBodyType(scanTool.getRequestBodyType());
        globalTool.setResponseType(scanTool.getResponseType());
        globalTool.setProjectId(project.getId());
        globalTool.setProjectCode(project.getProjectCode());
        globalTool.setVisibility(StringUtils.hasText(project.getVisibility()) ? project.getVisibility() : "PRIVATE");
        globalTool.setQualifiedName(project.getProjectCode() + ":" + scanTool.getName());
        globalTool.setModuleId(scanTool.getModuleId());
        globalTool.setEnabled(Boolean.TRUE.equals(scanTool.getEnabled()));
        globalTool.setAgentVisible(Boolean.TRUE.equals(scanTool.getAgentVisible()));
        globalTool.setLightweightEnabled(Boolean.TRUE.equals(scanTool.getLightweightEnabled()));
        globalTool.setSideEffect("WRITE");
        globalTool.setDraft(false);
        globalTool.setSkillKind(null);
        globalTool.setSpecJson(null);
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

    private void validateToolRequest(ScanProjectToolUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Tool request is required");
        }
        if (!StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("Tool name is required");
        }
        if (!StringUtils.hasText(request.description())) {
            throw new IllegalArgumentException("Tool description is required");
        }
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

    private String generateAiCodingAccessKey() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return "aic_" + HexFormat.of().formatHex(bytes);
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

    public record ScanProjectAuthSaveRequest(
            String authType,
            String authApiKeyIn,
            String authApiKeyName,
            String authApiKeyValue
    ) {
    }

    public record ScanProjectRegistryCredentialSaveRequest(String appKey, String appSecret) {
    }

    public record ScanProjectToolUpsertRequest(
            String name,
            String description,
            List<ToolDefinitionParameter> parameters,
            String source,
            String sourceLocation,
            String httpMethod,
            String baseUrl,
            String contextPath,
            String endpointPath,
            String requestBodyType,
            String responseType,
            Boolean enabled,
            Boolean agentVisible,
            Boolean lightweightEnabled
    ) {
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

    public record PromotedGlobalTool(Long globalToolId, String globalToolName) {
    }

    public record BatchPromoteToToolsResult(int promotedCount, List<PromotedGlobalTool> items) {
    }

    public record ScanResult(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }

    public record ScanDiffSummary(Long projectId,
                                  int toolCount,
                                  int promotedCount,
                                  int missingDescriptionCount,
                                  int missingAiDescriptionCount,
                                  int duplicateStableKeyCount,
                                  List<DuplicateStableKey> duplicates) {
    }

    public record DuplicateStableKey(String stableKey, List<Long> scanToolIds) {
    }

    public record SdkAccessCheckResponse(
            Long projectId,
            String projectCode,
            String overallStatus,
            List<SdkAccessReadiness> readiness,
            List<SdkAccessCheckItem> checks
    ) {
    }

    public record SdkAccessReadiness(String key, String label, String status, String message) {
    }

    public record SdkAccessCheckItem(String key, String label, String status, String message, String evidence) {
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
