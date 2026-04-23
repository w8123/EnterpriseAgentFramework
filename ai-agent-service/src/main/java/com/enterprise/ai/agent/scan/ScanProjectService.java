package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.client.ScannerServiceClient;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ScannerServiceClient scannerServiceClient;

    /** 可选依赖：仅当 ai-model-service 语义能力启用时需要。延迟注入避免循环。 */
    @Autowired(required = false)
    private ScanModuleService scanModuleService;

    @Autowired(required = false)
    private SemanticDocService semanticDocService;

    public ScanProjectService(ScanProjectMapper projectMapper,
                              ToolDefinitionService toolDefinitionService,
                              ScannerServiceClient scannerServiceClient) {
        this.projectMapper = projectMapper;
        this.toolDefinitionService = toolDefinitionService;
        this.scannerServiceClient = scannerServiceClient;
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

    public List<ToolDefinitionEntity> listTools(Long projectId) {
        getById(projectId);
        return toolDefinitionService.listByProjectId(projectId);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        toolDefinitionService.deleteByProjectId(id);
        if (semanticDocService != null) {
            semanticDocService.deleteByProject(id);
        }
        if (scanModuleService != null) {
            scanModuleService.deleteByProject(id);
        }
        projectMapper.deleteById(id);
    }

    @Transactional
    public ScanResult scan(Long projectId) {
        ScanProjectEntity project = getById(projectId);
        if (!toolDefinitionService.listByProjectId(projectId).isEmpty()) {
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
        if (deleteOldTools) {
            toolDefinitionService.deleteByProjectId(project.getId());
        }
        ScannerServiceClient.ManifestData manifest = scanManifest(project);
        List<String> toolNames = persistTools(project, manifest);
        project.setToolCount(toolNames.size());
        if (scanModuleService != null) {
            scanModuleService.bootstrapFromTools(project.getId());
        }
        updateStatus(project, "scanned", null);
        return new ScanResult(project.getId(), project.getName(), toolNames.size(), List.copyOf(toolNames));
    }

    private ScannerServiceClient.ManifestData scanManifest(ScanProjectEntity project) {
        String scanType = normalizeScanType(project.getScanType());
        Path scanRoot = Path.of(project.getScanPath());
        ScannerServiceClient.ScanRequest request = new ScannerServiceClient.ScanRequest(
                scannerProjectMetadataName(project),
                project.getBaseUrl(),
                normalizeContextPath(project.getContextPath()),
                project.getScanPath(),
                project.getSpecFile()
        );

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

    private List<String> persistTools(ScanProjectEntity project, ScannerServiceClient.ManifestData manifest) {
        List<String> toolNames = new ArrayList<>();
        List<ScannerServiceClient.ToolData> tools = manifest.getTools() == null ? List.of() : manifest.getTools();
        String manifestBaseUrl = manifest.getProject() == null ? project.getBaseUrl() : manifest.getProject().getBaseUrl();
        String manifestContextPath = manifest.getProject() == null ? normalizeContextPath(project.getContextPath()) : manifest.getProject().getContextPath();
        for (ScannerServiceClient.ToolData tool : tools) {
            String scopedName = buildUniqueToolName(project.getName(), tool.getName());
            toolDefinitionService.create(new ToolDefinitionUpsertRequest(
                    scopedName,
                    tool.getDescription(),
                    (tool.getParameters() == null ? List.<ScannerServiceClient.ToolParameterData>of() : tool.getParameters()).stream()
                            .map(parameter -> new ToolDefinitionParameter(
                                    parameter.getName(),
                                    parameter.getType(),
                                    parameter.getDescription(),
                                    parameter.isRequired(),
                                    parameter.getLocation()
                            ))
                            .toList(),
                    "scanner",
                    tool.getSource() == null ? null : tool.getSource().getLocation(),
                    tool.getMethod(),
                    manifestBaseUrl,
                    manifestContextPath,
                    tool.getPath(),
                    tool.getRequestBodyType(),
                    tool.getResponseType(),
                    project.getId(),
                    false,
                    false,
                    false
            ));
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

    private String buildUniqueToolName(String projectName, String rawToolName) {
        String baseName = scopeToolName(projectName, rawToolName);
        String candidate = baseName;
        int suffix = 2;
        while (toolDefinitionService.findByName(candidate).isPresent()) {
            candidate = baseName + "_" + suffix;
            suffix++;
        }
        return candidate;
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

    public record ScanResult(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }
}
