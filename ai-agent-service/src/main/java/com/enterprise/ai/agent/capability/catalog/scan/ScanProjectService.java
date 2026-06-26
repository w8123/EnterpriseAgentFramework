package com.enterprise.ai.agent.capability.catalog.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.client.ScannerServiceClient;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocService;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpToolBaseUrlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class ScanProjectService {

    private static final List<String> DEFAULT_OPENAPI_FILES = List.of(
            "swagger.json",
            "openapi.json",
            "openapi.yaml",
            "openapi.yml",
            "api-docs.json"
    );

    private final ScanProjectMapper projectMapper;
    private final ToolDefinitionService toolDefinitionService;
    private final ScanProjectToolService scanProjectToolService;
    private final ScannerServiceClient scannerServiceClient;
    private final ScanModuleService scanModuleService;
    private final ObjectMapper objectMapper;
    private final ScanProjectBlockerService scanProjectBlockerService;

    @Autowired(required = false)
    private SemanticDocService semanticDocService;

    @Autowired(required = false)
    private ApiGraphService apiGraphService;

    @Autowired(required = false)
    private RegistrySecurityService registrySecurityService;

    @Autowired(required = false)
    private ProjectInstanceMapper projectInstanceMapper;

    /** 扫描完成后是否自动重建接口图谱；默认关闭，需在管理端手动重建或设环境变量开启。 */
    @Value("${ai.api-graph.rebuild-on-scan:false}")
    private boolean apiGraphRebuildOnScan;

    public ScanProjectService(ScanProjectMapper projectMapper,
                              ToolDefinitionService toolDefinitionService,
                              ScanProjectToolService scanProjectToolService,
                              ScannerServiceClient scannerServiceClient,
                              ScanModuleService scanModuleService,
                              ObjectMapper objectMapper,
                              ScanProjectBlockerService scanProjectBlockerService) {
        this.projectMapper = projectMapper;
        this.toolDefinitionService = toolDefinitionService;
        this.scanProjectToolService = scanProjectToolService;
        this.scannerServiceClient = scannerServiceClient;
        this.scanModuleService = scanModuleService;
        this.objectMapper = objectMapper;
        this.scanProjectBlockerService = scanProjectBlockerService;
    }

    public ScanProjectEntity create(ScanProjectUpsertRequest request) {
        validateRequest(request);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("扫描项目已存在: " + request.name());
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
        projectMapper.insert(entity);
        return entity;
    }

    public ScanProjectEntity update(Long id, ScanProjectUpsertRequest request) {
        validateRequest(request);
        ScanProjectEntity existing = getById(id);
        findByName(request.name())
                .filter(entity -> !Objects.equals(entity.getId(), id))
                .ifPresent(entity -> {
                    throw new IllegalArgumentException("扫描项目已存在: " + request.name());
                });
        String oldProjectCode = existing.getProjectCode();
        ScanProjectEntity updated = applyRequest(existing, request);
        projectMapper.updateById(updated);
        String newCode = updated.getProjectCode();
        if (!sameProjectCode(oldProjectCode, newCode)) {
            if (registrySecurityService != null) {
                registrySecurityService.syncCredentialProjectCode(id, newCode);
            }
            if (projectInstanceMapper != null) {
                projectInstanceMapper.update(null, Wrappers.<ProjectInstanceEntity>lambdaUpdate()
                        .eq(ProjectInstanceEntity::getProjectId, id)
                        .set(ProjectInstanceEntity::getProjectCode, newCode));
            }
        }
        return updated;
    }

    /**
     * 更新扫描项目 HTTP 鉴权配置（与项目基本信息独立保存）。
     */
    @Transactional
    public ScanProjectEntity updateScanSettings(Long id, ScanSettings request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        ScanSettings merged = ScanSettingsJson.fromRequest(request);
        ScanSettingsJson.validate(merged);
        String json = ScanSettingsJson.toJson(merged, objectMapper);
        ScanProjectEntity existing = getById(id);
        existing.setScanSettings(json);
        projectMapper.updateById(existing);
        return existing;
    }

    public ScanSettings parseSettingsForProject(ScanProjectEntity project) {
        if (project == null) {
            return ScanSettings.defaults();
        }
        return ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
    }

    public ScanSettings parseSettingsById(Long id) {
        return parseSettingsForProject(getById(id));
    }

    public ScanProjectEntity updateAuthSettings(Long id, ScanProjectAuthSettingsUpdate request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        ScanProjectEntity existing = getById(id);
        String authType = normalizeAuthType(request.authType());
        existing.setAuthType(authType);
        if ("none".equals(authType)) {
            existing.setAuthApiKeyIn(null);
            existing.setAuthApiKeyName(null);
            existing.setAuthApiKeyValue(null);
        } else {
            existing.setAuthApiKeyIn(normalizeAuthApiKeyIn(request.authApiKeyIn()));
            if (request.authApiKeyName() == null || request.authApiKeyName().isBlank()) {
                throw new IllegalArgumentException("API Key 参数名不能为空");
            }
            if (request.authApiKeyValue() == null || request.authApiKeyValue().isBlank()) {
                throw new IllegalArgumentException("API Key 参数值不能为空");
            }
            existing.setAuthApiKeyName(request.authApiKeyName().trim());
            existing.setAuthApiKeyValue(request.authApiKeyValue());
        }
        projectMapper.updateById(existing);
        return existing;
    }

    public ScanProjectEntity updateAiCodingAccess(Long id, AiCodingAccessUpdate request) {
        if (request == null) {
            throw new IllegalArgumentException("璇锋眰涓嶈兘涓虹┖");
        }
        ScanProjectEntity existing = getById(id);
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String accessKey = request.accessKey() == null ? "" : request.accessKey().trim();
        if (enabled && accessKey.isBlank()) {
            accessKey = generateAiCodingAccessKey();
        }

        LambdaUpdateWrapper<ScanProjectEntity> update = Wrappers.lambdaUpdate();
        update.eq(ScanProjectEntity::getId, id)
                .set(ScanProjectEntity::getAiCodingAccessEnabled, enabled)
                .set(ScanProjectEntity::getAiCodingAccessKey, enabled ? accessKey : null);
        projectMapper.update(null, update);

        existing.setAiCodingAccessEnabled(enabled);
        existing.setAiCodingAccessKey(enabled ? accessKey : null);
        return existing;
    }

    public boolean matchesAiCodingAccessKey(Long id, String accessKey) {
        if (id == null || accessKey == null || accessKey.isBlank()) {
            return false;
        }
        ScanProjectEntity project = projectMapper.selectById(id);
        if (project == null || !Boolean.TRUE.equals(project.getAiCodingAccessEnabled())) {
            return false;
        }
        return accessKey.trim().equals(project.getAiCodingAccessKey());
    }

    public String generateAiCodingAccessKey() {
        return "rac_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeAuthType(String value) {
        String v = value == null || value.isBlank() ? "none" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("none", "api_key").contains(v)) {
            throw new IllegalArgumentException("不支持的鉴权类型: " + value);
        }
        return v;
    }

    private String normalizeAuthApiKeyIn(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择 API Key 放在 Header 或 URL 参数");
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("header", "query").contains(v)) {
            throw new IllegalArgumentException("API Key 位置仅支持 header 或 query");
        }
        return v;
    }

    public List<ScanProjectEntity> list() {
        return projectMapper.selectList(new LambdaQueryWrapper<ScanProjectEntity>()
                .orderByDesc(ScanProjectEntity::getUpdateTime)
                .orderByDesc(ScanProjectEntity::getId));
    }

    public ScanProjectEntity getById(Long id) {
        ScanProjectEntity entity = projectMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("扫描项目不存在: " + id);
        }
        return entity;
    }

    /**
     * 根据扫描项目主键解析显示名称，不存在或入参为 null 时返回 null（供 Tool 展示「来源项目」用）。
     */
    public String getProjectNameOrNull(Long projectId) {
        if (projectId == null) {
            return null;
        }
        ScanProjectEntity entity = projectMapper.selectById(projectId);
        return entity == null ? null : entity.getName();
    }

    public java.util.Optional<ScanProjectEntity> findByName(String name) {
        return java.util.Optional.ofNullable(projectMapper.selectOne(new LambdaQueryWrapper<ScanProjectEntity>()
                .eq(ScanProjectEntity::getName, name)
                .last("limit 1")));
    }

    public java.util.Optional<ScanProjectEntity> findByProjectCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(projectMapper.selectOne(new LambdaQueryWrapper<ScanProjectEntity>()
                .eq(ScanProjectEntity::getProjectCode, projectCode.trim())
                .last("limit 1")));
    }

    public List<ScanProjectToolEntity> listTools(Long projectId) {
        getById(projectId);
        return scanProjectToolService.listByProject(projectId);
    }

    /**
     * 删除或重新扫描前的引用检测（全局 Tool / 粗粒度能力是否仍挂在 Agent 白名单上）。
     */
    public ScanProjectBlockers getOperationBlockers(Long projectId) {
        getById(projectId);
        return scanProjectBlockerService.analyze(projectId);
    }

    private void assertNotBlockedByAgentReferences(Long projectId) {
        ScanProjectBlockers b = scanProjectBlockerService.analyze(projectId);
        if (b.blocked()) {
            throw new ScanProjectBlockedException(b);
        }
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        assertNotBlockedByAgentReferences(id);
        scanProjectToolService.deleteByProject(id);
        toolDefinitionService.deleteByProjectId(id);
        if (semanticDocService != null) {
            semanticDocService.deleteByProject(id);
        }
        scanModuleService.deleteByProject(id);
        if (apiGraphService != null) {
            apiGraphService.deleteByProject(id);
        }
        projectMapper.deleteById(id);
    }

    @Transactional
    public ScanResult scan(Long projectId) {
        ScanProjectEntity project = getById(projectId);
        if (!scanProjectToolService.listByProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目已有扫描结果，请使用重新扫描");
        }
        return performScan(project, false);
    }

    @Transactional
    public ScanResult rescan(Long projectId) {
        ScanProjectEntity project = getById(projectId);
        assertNotBlockedByAgentReferences(projectId);
        return performScan(project, true);
    }

    /**
     * 针对单条扫描接口重新跑扫描器，按 HTTP 方法 + 路径（及必要时来源定位）匹配 manifest 中的端点，
     * 用 {@link ScanProjectToolService#update} 覆盖当前行字段，保留主键、工具名、启用与可见性开关。
     * 不修改项目 {@code last_scanned_at}（仍由全量重扫维护增量基线）。
     */
    @Transactional
    public ScanProjectToolEntity rescanSingleTool(Long projectId, Long scanToolId) {
        ScanProjectEntity project = getById(projectId);
        ScanProjectToolEntity st = scanProjectToolService.findByProjectAndId(projectId, scanToolId)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + scanToolId));
        ScanSettings settings = parseSettingsForProject(project);
        // 单条刷新必须全量扫描，避免 OpenAPI mtime 增量返回空列表导致无法匹配
        ScannerServiceClient.ManifestData manifest = scanManifest(project, null, settings);
        List<ScannerServiceClient.ToolData> tools = manifest.getTools() == null ? List.of() : manifest.getTools();
        ScannerServiceClient.ToolData matched = findManifestToolMatchingScanRow(project, st, tools);
        if (matched == null) {
            throw new IllegalArgumentException(
                    "在当前源码或 OpenAPI 解析结果中未找到与该接口匹配的端点，请确认路径、方法或重新执行全量扫描");
        }
        String manifestBaseUrl = resolveManifestBaseUrl(manifest, project);
        String manifestContextPath = normalizeContextPath(project.getContextPath());
        var upsert = new ToolDefinitionUpsertRequest(
                st.getName(),
                matched.getDescription(),
                (matched.getParameters() == null ? List.<ScannerServiceClient.ToolParameterData>of() : matched.getParameters()).stream()
                        .map(ScanProjectService::toToolDefinitionParameter)
                        .toList(),
                "scanner",
                matched.getSource() == null ? null : matched.getSource().getLocation(),
                matched.getMethod(),
                manifestBaseUrl,
                manifestContextPath,
                matched.getPath(),
                matched.getRequestBodyType(),
                matched.getResponseType(),
                null,
                Boolean.TRUE.equals(st.getEnabled()),
                agentVisibleFromMetadata(matched.getCapabilityMetadata(), Boolean.TRUE.equals(st.getAgentVisible())),
                Boolean.TRUE.equals(st.getLightweightEnabled())
        ).withCapabilityMetadata(matched.getCapabilityMetadata());
        ScanProjectToolEntity updated = scanProjectToolService.update(projectId, scanToolId, upsert);
        scanModuleService.bootstrapFromTools(project.getId());
        clearStaleProjectErrorAfterSingleToolRefresh(project);
        return updated;
    }

    /**
     * 在 manifest 工具列表中定位与当前扫描行对应的条目：优先 HTTP 方法 + 规范化完整路径；
     * 若无匹配且存在 {@code sourceLocation}，则按来源定位精确匹配。
     */
    private ScannerServiceClient.ToolData findManifestToolMatchingScanRow(ScanProjectEntity project,
                                                                         ScanProjectToolEntity st,
                                                                         List<ScannerServiceClient.ToolData> tools) {
        String wantMethod = normalizeHttpMethod(st.getHttpMethod());
        String wantPath = normalizePathForCompare(combineHttpPath(st.getContextPath(), st.getEndpointPath()));
        String wantPathFromProject = normalizePathForCompare(
                combineHttpPath(project.getContextPath(), st.getEndpointPath()));
        ScannerServiceClient.ToolData firstPathMatch = null;
        for (ScannerServiceClient.ToolData t : tools) {
            if (t == null) {
                continue;
            }
            if (!wantMethod.equals(normalizeHttpMethod(t.getMethod()))) {
                continue;
            }
            String cand = normalizePathForCompare(combineHttpPath(project.getContextPath(), t.getPath()));
            if (wantPath.equals(cand) || wantPathFromProject.equals(cand)) {
                if (firstPathMatch == null) {
                    firstPathMatch = t;
                }
            }
        }
        if (firstPathMatch != null) {
            return firstPathMatch;
        }
        String src = st.getSourceLocation();
        if (src == null || src.isBlank()) {
            return null;
        }
        String wantLoc = src.trim();
        for (ScannerServiceClient.ToolData t : tools) {
            if (t == null || t.getSource() == null || t.getSource().getLocation() == null) {
                continue;
            }
            if (wantLoc.equals(t.getSource().getLocation().trim())) {
                return t;
            }
        }
        return null;
    }

    private static String normalizeHttpMethod(String method) {
        if (method == null || method.isBlank()) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static String combineHttpPath(String contextPath, String endpointPath) {
        String ctx = contextPath == null ? "" : contextPath.trim();
        String ep = endpointPath == null ? "" : endpointPath.trim();
        if (!ctx.isEmpty() && !ctx.startsWith("/")) {
            ctx = "/" + ctx;
        }
        if (!ep.isEmpty() && !ep.startsWith("/")) {
            ep = "/" + ep;
        }
        if (ctx.isEmpty()) {
            return ep.isEmpty() ? "/" : ep;
        }
        if (ep.isEmpty()) {
            return ctx;
        }
        if (ctx.endsWith("/") && ep.startsWith("/")) {
            return ctx.substring(0, ctx.length() - 1) + ep;
        }
        if (!ctx.endsWith("/") && !ep.startsWith("/")) {
            return ctx + "/" + ep;
        }
        return ctx + ep;
    }

    private static String normalizePathForCompare(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private ScanResult performScan(ScanProjectEntity project, boolean deleteOldTools) {
        updateStatus(project, "scanning", null);
        ScanSettings settings = parseSettingsForProject(project);
        boolean incrementalMerge = deleteOldTools
                && ScanSettingsJson.isIncrementalOn(settings)
                && project.getLastScannedAt() != null;
        if (deleteOldTools && !incrementalMerge) {
            scanProjectToolService.deleteByProject(project.getId());
            toolDefinitionService.deleteByProjectId(project.getId());
        }
        Long sinceMs = incrementalMerge
                ? toEpochMs(project.getLastScannedAt())
                : null;
        ScannerServiceClient.ManifestData manifest = scanManifest(project, sinceMs, settings);
        List<String> toolNames = persistTools(project, manifest, incrementalMerge);
        project.setToolCount(toolNames.size());
        project.setLastScannedAt(java.time.LocalDateTime.now(ZoneId.systemDefault()));
        scanModuleService.bootstrapFromTools(project.getId());
        if (apiGraphRebuildOnScan && apiGraphService != null) {
            apiGraphService.rebuildForProject(project.getId());
        }
        updateStatus(project, "scanned", null);
        return new ScanResult(project.getId(), project.getName(), toolNames.size(), List.copyOf(toolNames));
    }

    private static long toEpochMs(java.time.LocalDateTime t) {
        if (t == null) {
            return 0L;
        }
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private ScannerServiceClient.ScanRequestOptions toFeignOptions(ScanSettings s) {
        if (s == null) {
            s = ScanSettings.defaults();
        }
        ScannerServiceClient.ScanRequestOptions o = new ScannerServiceClient.ScanRequestOptions();
        o.setDescriptionSourceOrder(s.getDescriptionSourceOrder());
        o.setParamDescriptionSourceOrder(s.getParamDescriptionSourceOrder());
        o.setDescriptionSourceEnabled(s.getDescriptionSourceEnabled());
        o.setParamDescriptionSourceEnabled(s.getParamDescriptionSourceEnabled());
        o.setOnlyRestController(s.isOnlyRestController());
        o.setHttpMethodWhitelist(s.getHttpMethodWhitelist() == null ? List.of() : s.getHttpMethodWhitelist());
        o.setClassIncludeRegex(s.getClassIncludeRegex());
        o.setClassExcludeRegex(s.getClassExcludeRegex());
        o.setSkipDeprecated(s.isSkipDeprecated());
        o.setIncrementalMode(ScanSettingsJson.normalizeIncremental(s.getIncrementalMode()));
        return o;
    }

    private ScannerServiceClient.ManifestData scanManifest(ScanProjectEntity project,
                                                            Long incrementalSinceMs,
                                                            ScanSettings settings) {
        String scanType = normalizeScanType(project.getScanType());
        Path scanRoot = Path.of(project.getScanPath());
        ScannerServiceClient.ScanRequest request = new ScannerServiceClient.ScanRequest();
        request.setProjectName(scannerProjectMetadataName(project));
        request.setBaseUrl(project.getBaseUrl());
        request.setContextPath(normalizeContextPath(project.getContextPath()));
        request.setScanPath(project.getScanPath());
        request.setSpecFile(project.getSpecFile());
        request.setOptions(toFeignOptions(settings == null ? ScanSettings.defaults() : settings));
        request.setIncrementalSinceEpochMs(incrementalSinceMs);

        if ("openapi".equals(scanType)) {
            Path specPath = resolveOpenApiSpec(scanRoot, project.getSpecFile());
            request.setScanPath(specPath.toString());
            request.setSpecFile(null);
            return requireSuccess(scannerServiceClient.scanOpenApi(request));
        }
        if ("controller".equals(scanType)) {
            return requireSuccess(scannerServiceClient.scanController(request));
        }

        Path detectedSpec = tryResolveOpenApiSpec(scanRoot, project.getSpecFile());
        if (detectedSpec != null) {
            request.setScanPath(detectedSpec.toString());
            request.setSpecFile(null);
            return requireSuccess(scannerServiceClient.scanOpenApi(request));
        }
        return requireSuccess(scannerServiceClient.scanController(request));
    }

    /**
     * 把扫描服务返回的参数数据递归映射成 {@link ToolDefinitionParameter}（保留 body 子字段树）。
     */
    private static ToolDefinitionParameter toToolDefinitionParameter(ScannerServiceClient.ToolParameterData parameter) {
        List<ScannerServiceClient.ToolParameterData> rawChildren = parameter.getChildren();
        List<ToolDefinitionParameter> children = rawChildren == null || rawChildren.isEmpty()
                ? List.of()
                : rawChildren.stream().map(ScanProjectService::toToolDefinitionParameter).toList();
        return new ToolDefinitionParameter(
                parameter.getName(),
                parameter.getType(),
                parameter.getDescription(),
                parameter.isRequired(),
                parameter.getLocation(),
                children,
                parameter.getMetadata()
        );
    }

    private ScannerServiceClient.ManifestData requireSuccess(ScannerServiceClient.ScanManifestResult result) {
        if (result == null) {
            throw new IllegalArgumentException("扫描服务返回为空");
        }
        if (result.getCode() != 200) {
            throw new IllegalArgumentException(result.getMessage() == null ? "扫描服务调用失败" : result.getMessage());
        }
        if (result.getData() == null) {
            throw new IllegalArgumentException("扫描服务未返回扫描结果");
        }
        return result.getData();
    }

    private List<String> persistTools(ScanProjectEntity project, ScannerServiceClient.ManifestData manifest, boolean merge) {
        List<String> toolNames = new ArrayList<>();
        List<ScannerServiceClient.ToolData> tools = manifest.getTools() == null ? List.of() : manifest.getTools();
        boolean useProjectPrefix = !isControllerScannerManifest(tools);
        String manifestBaseUrl = resolveManifestBaseUrl(manifest, project);
        // 以项目表配置为准；扫描服务 manifest 中 project.contextPath 可能带默认值（如 /api），不可覆盖用户留空
        String manifestContextPath = normalizeContextPath(project.getContextPath());
        ScanSettings s = parseSettingsForProject(project);
        ScanDefaultFlags df = s.getDefaultFlags() == null ? ScanDefaultFlags.defaults() : s.getDefaultFlags();
        for (ScannerServiceClient.ToolData tool : tools) {
            String scopedName = buildUniqueToolName(project.getId(), project.getName(), tool.getName(), useProjectPrefix);
            var upsert = new ToolDefinitionUpsertRequest(
                    scopedName,
                    tool.getDescription(),
                    (tool.getParameters() == null ? List.<ScannerServiceClient.ToolParameterData>of() : tool.getParameters()).stream()
                            .map(ScanProjectService::toToolDefinitionParameter)
                            .toList(),
                    "scanner",
                    tool.getSource() == null ? null : tool.getSource().getLocation(),
                    tool.getMethod(),
                    manifestBaseUrl,
                    manifestContextPath,
                    tool.getPath(),
                    tool.getRequestBodyType(),
                    tool.getResponseType(),
                    null,
                    df.isEnabled(),
                    agentVisibleFromMetadata(tool.getCapabilityMetadata(), df.isAgentVisible()),
                    df.isLightweightEnabled()
            ).withCapabilityMetadata(tool.getCapabilityMetadata());
            if (merge) {
                scanProjectToolService.upsertScanned(project.getId(), upsert);
            } else {
                scanProjectToolService.insertScanned(project.getId(), upsert);
            }
            toolNames.add(scopedName);
        }
        return toolNames;
    }

    /**
     * manifest 里 {@code project.baseUrl} 可能为空；落库 / 单条刷新时回退扫描项目「项目域名」，避免 HTTP 调用无 authority。
     */
    private static String resolveManifestBaseUrl(ScannerServiceClient.ManifestData manifest, ScanProjectEntity project) {
        if (manifest != null && manifest.getProject() != null) {
            String m = manifest.getProject().getBaseUrl();
            if (m != null && !m.isBlank()) {
                return m.trim();
            }
        }
        if (project == null || project.getBaseUrl() == null) {
            return null;
        }
        return project.getBaseUrl().trim();
    }

    private Path resolveOpenApiSpec(Path scanRoot, String specFile) {
        Path detected = tryResolveOpenApiSpec(scanRoot, specFile);
        if (detected != null) {
            return detected;
        }
        throw new IllegalArgumentException("未找到 OpenAPI 规范文件: " + scanRoot);
    }

    private Path tryResolveOpenApiSpec(Path scanRoot, String specFile) {
        if (!Files.exists(scanRoot)) {
            throw new IllegalArgumentException("扫描路径不存在: " + scanRoot);
        }
        if (Files.isRegularFile(scanRoot)) {
            return scanRoot;
        }

        if (specFile != null && !specFile.isBlank()) {
            Path candidate = Path.of(specFile);
            if (!candidate.isAbsolute()) {
                candidate = scanRoot.resolve(specFile);
            }
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
            throw new IllegalArgumentException("OpenAPI 规范文件不存在: " + candidate);
        }

        for (String filename : DEFAULT_OPENAPI_FILES) {
            Path direct = scanRoot.resolve(filename);
            if (Files.exists(direct)) {
                return direct.normalize();
            }
            Path resource = scanRoot.resolve("src/main/resources").resolve(filename);
            if (Files.exists(resource)) {
                return resource.normalize();
            }
        }
        return null;
    }

    /**
     * 代码扫描（Controller）结果不再加 {@code 项目名__} 前缀，与扫描器生成的工具名一致；OpenAPI 等仍加前缀以降低全局重名概率。
     */
    private boolean isControllerScannerManifest(List<ScannerServiceClient.ToolData> tools) {
        if (tools.isEmpty()) {
            return false;
        }
        for (ScannerServiceClient.ToolData tool : tools) {
            if (tool.getSource() == null || !"controller".equals(tool.getSource().getScanner())) {
                return false;
            }
        }
        return true;
    }

    private boolean agentVisibleFromMetadata(Object metadata, boolean fallback) {
        if (metadata instanceof java.util.Map<?, ?> map) {
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

    private String buildUniqueToolName(Long projectId, String projectName, String rawToolName, boolean useProjectPrefix) {
        String baseName = useProjectPrefix
                ? scopeToolName(projectName, rawToolName)
                : controllerScanToolBaseName(rawToolName);
        String candidate = baseName;
        int suffix = 2;
        while (scanProjectToolService.existsByProjectAndName(projectId, candidate)) {
            candidate = baseName + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String controllerScanToolBaseName(String rawToolName) {
        String normalized = normalizeName(rawToolName);
        return normalized.isBlank() ? "tool" : normalized;
    }

    private String scopeToolName(String projectName, String rawToolName) {
        return normalizeProjectName(projectName) + "__" + normalizeName(rawToolName);
    }

    private String normalizeProjectName(String value) {
        return normalizeName(value);
    }

    /**
     * 传给扫描服务写入 ToolManifest.project.name 的名称。
     * {@link #normalizeProjectName} 仅保留字母数字，纯中文等名称会变成空串，导致扫描端校验失败。
     */
    private String scannerProjectMetadataName(ScanProjectEntity project) {
        String slug = normalizeProjectName(project.getName());
        if (slug != null && !slug.isBlank()) {
            return slug;
        }
        if (project.getName() != null) {
            String trimmed = project.getName().trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "project_" + project.getId();
    }

    private static boolean sameProjectCode(String a, String b) {
        String ta = a == null ? "" : a.trim();
        String tb = b == null ? "" : b.trim();
        return ta.equals(tb);
    }

    private String normalizeName(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeScanType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("openapi", "controller", "auto").contains(normalized)) {
            throw new IllegalArgumentException("不支持的扫描方式: " + value);
        }
        return normalized;
    }

    private String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank()) {
            return "";
        }
        return contextPath.startsWith("/") ? contextPath : "/" + contextPath;
    }

    private void validateRequest(ScanProjectUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new IllegalArgumentException("项目域名不能为空");
        }
        DynamicHttpToolBaseUrlSupport.requireValidRestClientBaseUrl(
                DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl(request.baseUrl()));
        String kind = normalizeProjectKind(request.projectKind());
        if (!"REGISTERED".equals(kind) && (request.scanPath() == null || request.scanPath().isBlank())) {
            throw new IllegalArgumentException("扫描路径不能为空");
        }
        normalizeScanType(request.scanType());
    }

    private ScanProjectEntity applyRequest(ScanProjectEntity entity, ScanProjectUpsertRequest request) {
        entity.setName(request.name().trim());
        String code = request.projectCode() == null || request.projectCode().isBlank()
                ? normalizeProjectCode(request.name())
                : normalizeProjectCode(request.projectCode());
        entity.setProjectCode(code);
        entity.setProjectKind(normalizeProjectKind(request.projectKind()));
        entity.setEnvironment(normalizeEnvironment(request.environment()));
        entity.setOwner(request.owner() == null || request.owner().isBlank() ? null : request.owner().trim());
        entity.setVisibility(normalizeVisibility(request.visibility()));
        entity.setBaseUrl(DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl(request.baseUrl()));
        entity.setContextPath(normalizeContextPath(request.contextPath()));
        entity.setScanPath(request.scanPath() == null || request.scanPath().isBlank() ? "" : request.scanPath().trim());
        entity.setScanType(normalizeScanType(request.scanType()));
        entity.setSpecFile(request.specFile() == null || request.specFile().isBlank() ? null : request.specFile().trim());
        return entity;
    }

    private String normalizeProjectCode(String value) {
        String source = value == null || value.isBlank() ? "project" : value.trim();
        String normalized = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "project" : normalized;
    }

    private String normalizeProjectKind(String value) {
        String normalized = value == null || value.isBlank() ? "SCAN" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("SCAN", "REGISTERED", "HYBRID").contains(normalized)) {
            throw new IllegalArgumentException("不支持的项目形态: " + value);
        }
        return normalized;
    }

    private String normalizeEnvironment(String value) {
        return value == null || value.isBlank() ? "default" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeVisibility(String value) {
        String normalized = value == null || value.isBlank() ? "PRIVATE" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PRIVATE", "PROJECT", "SHARED", "PUBLIC").contains(normalized)) {
            throw new IllegalArgumentException("不支持的可见性: " + value);
        }
        return normalized;
    }

    private void updateStatus(ScanProjectEntity project, String status, String errorMessage) {
        project.setStatus(status);
        project.setErrorMessage(errorMessage);
        projectMapper.updateById(project);
        // updateById 默认忽略 null，无法清空 error_message；扫描成功后需显式写入 NULL
        if (errorMessage == null) {
            projectMapper.update(null, Wrappers.<ScanProjectEntity>lambdaUpdate()
                    .eq(ScanProjectEntity::getId, project.getId())
                    .set(ScanProjectEntity::getErrorMessage, null));
        }
    }

    /**
     * 单条接口「扫描更新」成功后：清除全量扫描遗留的项目级错误文案，
     * 并将 {@code failed} 恢复为 {@code scanned}。
     */
    private void clearStaleProjectErrorAfterSingleToolRefresh(ScanProjectEntity project) {
        boolean hadMessage = project.getErrorMessage() != null && !project.getErrorMessage().isBlank();
        boolean failedStatus = "failed".equalsIgnoreCase(project.getStatus());
        if (!hadMessage && !failedStatus) {
            return;
        }
        LambdaUpdateWrapper<ScanProjectEntity> uw = Wrappers.lambdaUpdate();
        uw.eq(ScanProjectEntity::getId, project.getId()).set(ScanProjectEntity::getErrorMessage, null);
        if (failedStatus) {
            uw.set(ScanProjectEntity::getStatus, "scanned");
        }
        projectMapper.update(null, uw);
        project.setErrorMessage(null);
        if (failedStatus) {
            project.setStatus("scanned");
        }
    }

    public void markFailed(Long projectId, String errorMessage) {
        ScanProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return;
        }
        project.setStatus("failed");
        project.setErrorMessage(errorMessage);
        projectMapper.updateById(project);
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

    public record ScanProjectAuthSettingsUpdate(
            String authType,
            String authApiKeyIn,
            String authApiKeyName,
            String authApiKeyValue
    ) {
    }

    public record AiCodingAccessUpdate(
            Boolean enabled,
            String accessKey
    ) {
    }

    public record ScanResult(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }
}
