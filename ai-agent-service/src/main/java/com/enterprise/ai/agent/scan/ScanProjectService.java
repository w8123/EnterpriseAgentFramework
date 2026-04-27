package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.client.ScannerServiceClient;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

    @Autowired(required = false)
    private SemanticDocService semanticDocService;

    public ScanProjectService(ScanProjectMapper projectMapper,
                              ToolDefinitionService toolDefinitionService,
                              ScanProjectToolService scanProjectToolService,
                              ScannerServiceClient scannerServiceClient,
                              ScanModuleService scanModuleService,
                              ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.toolDefinitionService = toolDefinitionService;
        this.scanProjectToolService = scanProjectToolService;
        this.scannerServiceClient = scannerServiceClient;
        this.scanModuleService = scanModuleService;
        this.objectMapper = objectMapper;
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
        ScanProjectEntity updated = applyRequest(existing, request);
        projectMapper.updateById(updated);
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

    public List<ScanProjectToolEntity> listTools(Long projectId) {
        getById(projectId);
        return scanProjectToolService.listByProject(projectId);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        scanProjectToolService.deleteByProject(id);
        toolDefinitionService.deleteByProjectId(id);
        if (semanticDocService != null) {
            semanticDocService.deleteByProject(id);
        }
        scanModuleService.deleteByProject(id);
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
        return performScan(project, true);
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
                children
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
        String manifestBaseUrl = manifest.getProject() == null ? project.getBaseUrl() : manifest.getProject().getBaseUrl();
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
                    df.isAgentVisible(),
                    df.isLightweightEnabled()
            );
            if (merge) {
                scanProjectToolService.upsertScanned(project.getId(), upsert);
            } else {
                scanProjectToolService.insertScanned(project.getId(), upsert);
            }
            toolNames.add(scopedName);
        }
        return toolNames;
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
        if (request.scanPath() == null || request.scanPath().isBlank()) {
            throw new IllegalArgumentException("扫描路径不能为空");
        }
        normalizeScanType(request.scanType());
    }

    private ScanProjectEntity applyRequest(ScanProjectEntity entity, ScanProjectUpsertRequest request) {
        entity.setName(request.name().trim());
        entity.setBaseUrl(request.baseUrl().trim());
        entity.setContextPath(normalizeContextPath(request.contextPath()));
        entity.setScanPath(request.scanPath().trim());
        entity.setScanType(normalizeScanType(request.scanType()));
        entity.setSpecFile(request.specFile() == null || request.specFile().isBlank() ? null : request.specFile().trim());
        return entity;
    }

    private void updateStatus(ScanProjectEntity project, String status, String errorMessage) {
        project.setStatus(status);
        project.setErrorMessage(errorMessage);
        projectMapper.updateById(project);
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

    public record ScanResult(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }
}
