package com.enterprise.ai.model.instance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelInstanceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ModelInstanceMapper mapper;
    private final ModelTemplateService modelTemplateService;
    private final ObjectMapper objectMapper;
    private final CredentialCipher credentialCipher;
    private final ModelInstanceRuntimeCache runtimeCache;

    public List<ModelInstanceResponse> list(String projectCode,
                                            String modelType,
                                            String provider,
                                            String keyword,
                                            boolean includeArchived) {
        LambdaQueryWrapper<ModelInstanceEntity> query = new LambdaQueryWrapper<ModelInstanceEntity>()
                .orderByDesc(ModelInstanceEntity::getUpdatedAt);
        if (StringUtils.hasText(projectCode)) {
            query.eq(ModelInstanceEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(modelType)) {
            query.eq(ModelInstanceEntity::getModelType, modelType.trim());
        }
        if (StringUtils.hasText(provider)) {
            query.eq(ModelInstanceEntity::getProvider, provider.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            query.and(w -> w.like(ModelInstanceEntity::getName, like)
                    .or().like(ModelInstanceEntity::getProvider, like)
                    .or().like(ModelInstanceEntity::getModelName, like));
        }
        if (!includeArchived) {
            query.ne(ModelInstanceEntity::getStatus, ModelInstanceStatus.ARCHIVED.name());
        }
        return mapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public ModelInstanceResponse get(String id) {
        return toResponse(requireEntity(id));
    }

    public ModelInstanceEntity getActiveEntity(String id) {
        ModelInstanceEntity entity = requireEntity(id);
        if (!ModelInstanceStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new BizException(400, "Model instance is not active: " + entity.getName());
        }
        return entity;
    }

    /** ACTIVE 或 DISABLED 可测；ARCHIVED 不可测。 */
    public ModelInstanceEntity getEntityForTest(String id) {
        ModelInstanceEntity entity = requireEntity(id);
        if (ModelInstanceStatus.ARCHIVED.name().equals(entity.getStatus())) {
            throw new BizException(400, "Archived model instance cannot be tested: " + entity.getName());
        }
        return entity;
    }

    public ModelInstanceResponse create(ModelInstanceRequest request) {
        validateCreateBasics(request, true);
        Map<String, Object> connection = ConnectionConfigSupport.normalize(request.getConnection());
        try {
            ConnectionConfigSupport.requireBaseUrl(connection);
            ModelProtectedOptions.assertAllowed(
                    request.getModelType(),
                    request.getDefaultOptions(),
                    "defaultOptions");
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }
        assertNameUnique(request.getName(), normalizeProjectCode(request.getProjectCode()), null);

        LocalDateTime now = LocalDateTime.now();
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(StringUtils.hasText(request.getId()) ? request.getId().trim() : UUID.randomUUID().toString());
        entity.setName(request.getName().trim());
        entity.setProvider(request.getProvider().trim());
        entity.setModelType(request.getModelType().name());
        entity.setModelName(request.getModelName().trim());
        entity.setProtocol((request.getProtocol() == null ? ModelProtocol.OPENAI_COMPATIBLE : request.getProtocol()).name());
        applyProjectScope(entity, request.getProjectCode());
        entity.setConnectionConfigJson(credentialCipher.encrypt(toJson(connection)));
        entity.setDefaultOptionsJson(toJson(request.getDefaultOptions() == null ? Map.of() : request.getDefaultOptions()));
        entity.setParamsSchemaJson(toJson(request.getParamsSchema() == null ? List.of() : request.getParamsSchema()));
        entity.setStatus(resolveCreateStatus(request.getStatus()).name());
        entity.setLastTestStatus(ModelTestStatus.UNKNOWN.name());
        entity.setRemark(request.getRemark());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        runtimeCache.invalidate(entity.getId());
        return toResponse(entity);
    }

    public ModelInstanceResponse createFromTemplate(String templateId, ModelInstanceRequest request) {
        if (request == null) {
            throw new BizException(400, "Request body is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BizException(400, "Model instance name is required");
        }
        ModelTemplateEntity template = modelTemplateService.getEntity(templateId);
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new BizException(400, "Template is disabled");
        }

        Map<String, Object> connection;
        Map<String, Object> defaultOptions;
        try {
            connection = ConnectionConfigSupport.mergeForEdit(
                    ConnectionConfigSupport.normalize(readMap(template.getConnectionDefaultsJson())),
                    request.getConnection());
            ConnectionConfigSupport.requireBaseUrl(connection);
            defaultOptions = ModelProtectedOptions.mergeOptions(
                    readMap(template.getDefaultOptionsJson()),
                    request.getDefaultOptions());
            ModelProtectedOptions.assertAllowed(
                    ModelType.valueOf(template.getModelType()),
                    defaultOptions,
                    "defaultOptions");
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }

        String projectCode = normalizeProjectCode(request.getProjectCode());
        assertNameUnique(request.getName(), projectCode, null);

        LocalDateTime now = LocalDateTime.now();
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(StringUtils.hasText(request.getId()) ? request.getId().trim() : UUID.randomUUID().toString());
        entity.setName(request.getName().trim());
        entity.setProvider(template.getProvider());
        entity.setModelType(template.getModelType());
        entity.setModelName(StringUtils.hasText(request.getModelName())
                ? request.getModelName().trim()
                : template.getModelName());
        entity.setProtocol(StringUtils.hasText(template.getProtocol())
                ? template.getProtocol()
                : ModelProtocol.OPENAI_COMPATIBLE.name());
        applyProjectScope(entity, projectCode);
        entity.setConnectionConfigJson(credentialCipher.encrypt(toJson(connection)));
        entity.setDefaultOptionsJson(toJson(defaultOptions));
        Object paramsSchema = request.getParamsSchema() != null
                ? request.getParamsSchema()
                : readObject(template.getParamsSchemaJson());
        entity.setParamsSchemaJson(toJson(paramsSchema == null ? List.of() : paramsSchema));
        entity.setStatus(resolveCreateStatus(request.getStatus()).name());
        entity.setLastTestStatus(ModelTestStatus.UNKNOWN.name());
        entity.setRemark(request.getRemark());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        runtimeCache.invalidate(entity.getId());
        return toResponse(entity);
    }

    public ModelInstanceResponse update(String id, ModelInstanceRequest request) {
        ModelInstanceEntity entity = requireEntity(id);
        if (ModelInstanceStatus.ARCHIVED.name().equals(entity.getStatus())) {
            throw new BizException(400, "Archived model instance cannot be edited");
        }
        if (request == null) {
            throw new BizException(400, "Request body is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BizException(400, "Model instance name is required");
        }
        if (request.getModelType() != null && !request.getModelType().name().equals(entity.getModelType())) {
            throw new BizException(400, "modelType is immutable");
        }
        if (request.getProtocol() != null && !request.getProtocol().name().equals(entity.getProtocol())) {
            throw new BizException(400, "protocol is immutable");
        }
        if (request.getStatus() == ModelInstanceStatus.ARCHIVED) {
            throw new BizException(400, "Use archive endpoint to set ARCHIVED status");
        }

        // 变更前有效运行配置快照（用明文规范化后比较，避免 aesgcm 密文每次重加密误判）
        String beforeProvider = entity.getProvider();
        String beforeModelName = entity.getModelName();
        Map<String, Object> beforeConnection = readConnection(entity);
        Map<String, Object> beforeOptions = readMap(entity.getDefaultOptionsJson());

        ModelType modelType = ModelType.valueOf(entity.getModelType());
        if (StringUtils.hasText(request.getProvider())) {
            entity.setProvider(request.getProvider().trim());
        }
        if (StringUtils.hasText(request.getModelName())) {
            entity.setModelName(request.getModelName().trim());
        }

        String projectCode = request.getProjectCode() != null
                ? normalizeProjectCode(request.getProjectCode())
                : entity.getProjectCode();
        assertNameUnique(request.getName(), projectCode, id);

        entity.setName(request.getName().trim());
        applyProjectScope(entity, projectCode);

        Map<String, Object> mergedConnection;
        Map<String, Object> afterOptions = beforeOptions;
        try {
            mergedConnection = ConnectionConfigSupport.mergeForEdit(
                    beforeConnection, request.getConnection());
            ConnectionConfigSupport.requireBaseUrl(mergedConnection);
            if (request.getDefaultOptions() != null) {
                ModelProtectedOptions.assertAllowed(modelType, request.getDefaultOptions(), "defaultOptions");
                afterOptions = new LinkedHashMap<>(request.getDefaultOptions());
            }
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }
        entity.setConnectionConfigJson(credentialCipher.encrypt(toJson(mergedConnection)));

        if (request.getDefaultOptions() != null) {
            entity.setDefaultOptionsJson(toJson(afterOptions));
        }
        if (request.getParamsSchema() != null) {
            entity.setParamsSchemaJson(toJson(request.getParamsSchema()));
        }
        if (request.getStatus() != null) {
            if (request.getStatus() != ModelInstanceStatus.ACTIVE && request.getStatus() != ModelInstanceStatus.DISABLED) {
                throw new BizException(400, "status must be ACTIVE or DISABLED");
            }
            entity.setStatus(request.getStatus().name());
        }
        entity.setRemark(request.getRemark());

        if (isEffectiveRuntimeConfigChanged(
                beforeProvider, entity.getProvider(),
                beforeModelName, entity.getModelName(),
                beforeConnection, mergedConnection,
                beforeOptions, afterOptions)) {
            clearLastTestResult(entity);
        }

        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        runtimeCache.invalidate(entity.getId());
        return toResponse(entity);
    }

    public ModelInstanceResponse archive(String id) {
        ModelInstanceEntity entity = requireEntity(id);
        entity.setStatus(ModelInstanceStatus.ARCHIVED.name());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        runtimeCache.invalidate(entity.getId());
        return toResponse(entity);
    }

    public void updateTestResult(String id, boolean success, long latencyMs, String error) {
        ModelInstanceEntity entity = requireEntity(id);
        entity.setLastTestStatus(success ? ModelTestStatus.SUCCESS.name() : ModelTestStatus.FAILED.name());
        entity.setLastTestAt(LocalDateTime.now());
        entity.setLastTestLatencyMs(latencyMs);
        entity.setLastTestError(success ? null : ConnectionConfigSupport.sanitizeError(error));
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    public ModelInstanceRuntime toRuntime(ModelInstanceEntity entity) {
        return ModelInstanceRuntime.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .modelType(entity.getModelType())
                .modelName(entity.getModelName())
                .protocol(entity.getProtocol())
                .connectionConfig(readConnection(entity))
                .defaultOptions(readMap(entity.getDefaultOptionsJson()))
                .build();
    }

    public ModelInstanceResponse toResponse(ModelInstanceEntity entity) {
        return ModelInstanceResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .modelType(entity.getModelType())
                .modelName(entity.getModelName())
                .protocol(entity.getProtocol())
                .projectCode(entity.getProjectCode())
                .connection(ConnectionConfigSupport.mask(readConnection(entity)))
                .defaultOptions(readMap(entity.getDefaultOptionsJson()))
                .paramsSchema(readObject(entity.getParamsSchemaJson()))
                .status(entity.getStatus())
                .lastTestStatus(entity.getLastTestStatus())
                .lastTestAt(entity.getLastTestAt())
                .lastTestLatencyMs(entity.getLastTestLatencyMs())
                .lastTestError(entity.getLastTestError())
                .remark(entity.getRemark())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public void assertNameUnique(String name, String projectCode, String excludeId) {
        String scopeKey = toProjectScopeKey(projectCode);
        LambdaQueryWrapper<ModelInstanceEntity> query = new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getName, name.trim())
                .eq(ModelInstanceEntity::getProjectScopeKey, scopeKey);
        if (StringUtils.hasText(excludeId)) {
            query.ne(ModelInstanceEntity::getId, excludeId);
        }
        if (mapper.selectCount(query) > 0) {
            throw new BizException(400, "Model instance name already exists in scope: " + name.trim());
        }
    }

    /**
     * 供草稿测试：按请求构建运行时（可合并已保存实例的掩码连接）。
     * 校验失败抛 {@link IllegalArgumentException}；资源不存在/已归档仍为 {@link BizException}。
     */
    public ModelInstanceRuntime buildRuntimeForDraft(ModelInstanceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        Map<String, Object> connection;
        String provider;
        String modelType;
        String modelName;
        String protocol;
        Map<String, Object> defaultOptions;
        String id = StringUtils.hasText(request.getId()) ? request.getId().trim() : null;

        if (id != null) {
            ModelInstanceEntity entity = requireEntity(id);
            if (ModelInstanceStatus.ARCHIVED.name().equals(entity.getStatus())) {
                throw new BizException(400, "Archived model instance cannot be tested");
            }
            if (request.getModelType() != null && !request.getModelType().name().equals(entity.getModelType())) {
                throw new IllegalArgumentException("modelType is immutable");
            }
            if (request.getProtocol() != null && !request.getProtocol().name().equals(entity.getProtocol())) {
                throw new IllegalArgumentException("protocol is immutable");
            }
            ModelType entityType = ModelType.valueOf(entity.getModelType());
            connection = ConnectionConfigSupport.mergeForEdit(readConnection(entity), request.getConnection());
            provider = StringUtils.hasText(request.getProvider()) ? request.getProvider().trim() : entity.getProvider();
            modelType = entity.getModelType();
            modelName = StringUtils.hasText(request.getModelName()) ? request.getModelName().trim() : entity.getModelName();
            protocol = entity.getProtocol();
            defaultOptions = ModelProtectedOptions.mergeOptions(
                    readMap(entity.getDefaultOptionsJson()),
                    request.getDefaultOptions());
            ModelProtectedOptions.assertAllowed(entityType, defaultOptions, "defaultOptions");
        } else {
            if (request.getModelType() == null) {
                throw new IllegalArgumentException("Model type is required");
            }
            if (!StringUtils.hasText(request.getProvider())) {
                throw new IllegalArgumentException("Provider is required");
            }
            if (!StringUtils.hasText(request.getModelName())) {
                throw new IllegalArgumentException("Model name is required");
            }
            connection = ConnectionConfigSupport.normalize(request.getConnection());
            provider = request.getProvider().trim();
            modelType = request.getModelType().name();
            modelName = request.getModelName().trim();
            protocol = (request.getProtocol() == null ? ModelProtocol.OPENAI_COMPATIBLE : request.getProtocol()).name();
            defaultOptions = request.getDefaultOptions() == null ? Map.of() : request.getDefaultOptions();
            ModelProtectedOptions.assertAllowed(request.getModelType(), defaultOptions, "defaultOptions");
        }
        ConnectionConfigSupport.requireBaseUrl(connection);
        return ModelInstanceRuntime.builder()
                .id(id)
                .name(StringUtils.hasText(request.getName()) ? request.getName().trim() : modelName)
                .provider(provider)
                .modelType(modelType)
                .modelName(modelName)
                .protocol(protocol)
                .connectionConfig(connection)
                .defaultOptions(defaultOptions)
                .build();
    }

    private ModelInstanceEntity requireEntity(String id) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(400, "Model instance id is required");
        }
        ModelInstanceEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "Model instance not found: " + id);
        }
        return entity;
    }

    private void validateCreateBasics(ModelInstanceRequest request, boolean requireProviderAndType) {
        if (request == null) {
            throw new BizException(400, "Request body is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BizException(400, "Model instance name is required");
        }
        if (requireProviderAndType) {
            if (!StringUtils.hasText(request.getProvider())) {
                throw new BizException(400, "Provider is required");
            }
            if (request.getModelType() == null) {
                throw new BizException(400, "Model type is required");
            }
            if (!StringUtils.hasText(request.getModelName())) {
                throw new BizException(400, "Model name is required");
            }
        }
    }

    private ModelInstanceStatus resolveCreateStatus(ModelInstanceStatus status) {
        if (status == null) {
            return ModelInstanceStatus.ACTIVE;
        }
        if (status == ModelInstanceStatus.ARCHIVED) {
            throw new BizException(400, "Cannot create instance as ARCHIVED");
        }
        return status;
    }

    /** 仅写入 projectCode；project_scope_key 为 DB 生成列，禁止由应用层写入。 */
    private void applyProjectScope(ModelInstanceEntity entity, String projectCode) {
        entity.setProjectCode(normalizeProjectCode(projectCode));
    }

    /**
     * 有效运行配置是否变化。元数据（name/remark/projectCode/status/paramsSchema）不参与。
     * connection / defaultOptions 比较规范化后的明文 Map，掩码凭证未变时不算变化。
     */
    static boolean isEffectiveRuntimeConfigChanged(String beforeProvider,
                                                   String afterProvider,
                                                   String beforeModelName,
                                                   String afterModelName,
                                                   Map<String, Object> beforeConnection,
                                                   Map<String, Object> afterConnection,
                                                   Map<String, Object> beforeOptions,
                                                   Map<String, Object> afterOptions) {
        if (!Objects.equals(beforeProvider, afterProvider)) {
            return true;
        }
        if (!Objects.equals(beforeModelName, afterModelName)) {
            return true;
        }
        if (!mapsEqual(ConnectionConfigSupport.normalize(beforeConnection),
                ConnectionConfigSupport.normalize(afterConnection))) {
            return true;
        }
        return !mapsEqual(
                beforeOptions == null ? Map.of() : beforeOptions,
                afterOptions == null ? Map.of() : afterOptions);
    }

    private static boolean mapsEqual(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> a = left == null ? Map.of() : left;
        Map<String, Object> b = right == null ? Map.of() : right;
        return a.equals(b);
    }

    private void clearLastTestResult(ModelInstanceEntity entity) {
        entity.setLastTestStatus(ModelTestStatus.UNKNOWN.name());
        entity.setLastTestAt(null);
        entity.setLastTestLatencyMs(null);
        entity.setLastTestError(null);
    }

    private String normalizeProjectCode(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            return null;
        }
        return projectCode.trim();
    }

    private String toProjectScopeKey(String projectCode) {
        return projectCode == null ? "" : projectCode;
    }

    private Map<String, Object> readConnection(ModelInstanceEntity entity) {
        return ConnectionConfigSupport.normalize(readMap(credentialCipher.decrypt(entity.getConnectionConfigJson())));
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new BizException(500, "Invalid model instance JSON: " + e.getMessage());
        }
    }

    private Object readObject(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(400, "Serialize model instance JSON failed: " + e.getMessage());
        }
    }
}
