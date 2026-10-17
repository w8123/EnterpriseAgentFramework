package com.enterprise.ai.model.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ConnectionConfigSupport;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceMapper;
import com.enterprise.ai.model.instance.ModelInstanceResponse;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceRuntimeCache;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelInstanceStatus;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.runtime.ModelApiUrl;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.runtime.UpstreamModel;
import com.enterprise.ai.model.security.CredentialCipher;
import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 厂商账号：同一厂商的地址与 Key 只存一次；用它实时获取厂商模型列表，并批量接入所选模型。
 */
@Service
@RequiredArgsConstructor
public class ModelProviderAccountService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    static final int MAX_BATCH = 50;

    private final ModelProviderAccountMapper mapper;
    private final ModelInstanceMapper instanceMapper;
    private final ModelInstanceService instanceService;
    private final ModelTemplateService templateService;
    private final OpenAiCompatibleRuntimeClient runtimeClient;
    private final CredentialCipher credentialCipher;
    private final ModelInstanceRuntimeCache runtimeCache;
    private final ObjectMapper objectMapper;

    public List<ModelProviderAccountResponse> list(String provider) {
        LambdaQueryWrapper<ModelProviderAccountEntity> query = new LambdaQueryWrapper<ModelProviderAccountEntity>()
                .orderByAsc(ModelProviderAccountEntity::getProvider)
                .orderByAsc(ModelProviderAccountEntity::getCreatedAt);
        if (StringUtils.hasText(provider)) {
            query.eq(ModelProviderAccountEntity::getProvider, provider.trim());
        }
        return mapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public ModelProviderAccountResponse get(String id) {
        return toResponse(require(id));
    }

    public ModelProviderAccountResponse create(ModelProviderAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new BizException(400, "账号名称不能为空");
        }
        if (!StringUtils.hasText(request.getProvider())) {
            throw new BizException(400, "厂商不能为空");
        }
        Map<String, Object> connection = ConnectionConfigSupport.accountOnly(request.getConnection());
        validateConnection(connection);
        assertNameUnique(request.getProvider().trim(), request.getName().trim(), null);
        LocalDateTime now = LocalDateTime.now();
        ModelProviderAccountEntity entity = new ModelProviderAccountEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(request.getName().trim());
        entity.setProvider(request.getProvider().trim());
        entity.setConnectionConfigJson(credentialCipher.encrypt(toJson(connection)));
        entity.setStatus("ACTIVE");
        entity.setRemark(request.getRemark());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toResponse(entity);
    }

    public ModelProviderAccountResponse update(String id, ModelProviderAccountRequest request) {
        ModelProviderAccountEntity entity = require(id);
        if (request == null) {
            throw new BizException(400, "Request body is required");
        }
        if (StringUtils.hasText(request.getProvider()) && !request.getProvider().trim().equals(entity.getProvider())) {
            throw new BizException(400, "账号的厂商不能修改");
        }
        if (StringUtils.hasText(request.getName())) {
            assertNameUnique(entity.getProvider(), request.getName().trim(), id);
            entity.setName(request.getName().trim());
        }
        if (request.getConnection() != null) {
            Map<String, Object> merged;
            try {
                // 地址换了域名时必须重新填写 Key，防止旧 Key 被发往新地址。
                merged = ConnectionConfigSupport.accountOnly(ConnectionConfigSupport.mergeForEdit(
                        readConnection(entity), ConnectionConfigSupport.accountOnly(request.getConnection())));
            } catch (IllegalArgumentException e) {
                throw new BizException(400, e.getMessage());
            }
            validateConnection(merged);
            entity.setConnectionConfigJson(credentialCipher.encrypt(toJson(merged)));
        }
        if (request.getRemark() != null) {
            entity.setRemark(request.getRemark());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        // 账号的地址/Key 是引用它的所有实例的有效连接。
        runtimeCache.invalidateAll();
        return toResponse(entity);
    }

    public void delete(String id) {
        ModelProviderAccountEntity entity = require(id);
        long count = countInstances(entity.getId());
        if (count > 0) {
            throw new BizException(400, "还有 " + count + " 个模型在使用这个账号，请先归档这些模型");
        }
        mapper.deleteById(entity.getId());
    }

    /** 不保存，先用填写的地址和 Key 试取模型列表。 */
    public ProviderModelsResponse discoverDraft(ModelProviderAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.getProvider())) {
            throw new BizException(400, "厂商不能为空");
        }
        Map<String, Object> connection = ConnectionConfigSupport.accountOnly(request.getConnection());
        try {
            ConnectionConfigSupport.requireBaseUrl(connection);
        } catch (IllegalArgumentException e) {
            return failure(request.getProvider(), e.getMessage());
        }
        return discover(request.getProvider().trim(), connection, null);
    }

    public ProviderModelsResponse discoverSaved(String id) {
        ModelProviderAccountEntity entity = require(id);
        return discover(entity.getProvider(), readConnection(entity), entity.getId());
    }

    public ProviderAccountInstancesResponse createInstances(String id, ProviderAccountInstancesRequest request) {
        ModelProviderAccountEntity account = require(id);
        List<ProviderAccountInstancesRequest.Item> items = request == null || request.models() == null
                ? List.of() : request.models();
        if (items.isEmpty()) {
            throw new BizException(400, "请至少选择一个模型");
        }
        if (items.size() > MAX_BATCH) {
            throw new BizException(400, "一次最多接入 " + MAX_BATCH + " 个模型");
        }
        Map<String, ModelTemplateEntity> templates = new LinkedHashMap<>();
        for (ModelTemplateEntity template : templateService.enabledEntitiesForProvider(account.getProvider())) {
            templates.putIfAbsent(template.getModelName() + "|" + template.getModelType(), template);
        }
        List<ProviderModelsResponse.ConnectedModel> connected = connectedModels(account.getId());
        List<ModelInstanceResponse> created = new ArrayList<>();
        List<ProviderAccountInstancesResponse.Skipped> skipped = new ArrayList<>();
        for (ProviderAccountInstancesRequest.Item item : items) {
            String modelName = item == null || item.modelName() == null ? "" : item.modelName().trim();
            if (modelName.isEmpty()) {
                continue;
            }
            ModelType modelType;
            try {
                modelType = ModelType.valueOf(String.valueOf(item.modelType()).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                skipped.add(new ProviderAccountInstancesResponse.Skipped(modelName, "模型类型无效"));
                continue;
            }
            boolean already = connected.stream().anyMatch(model ->
                    model.modelName().equals(modelName) && model.modelType().equals(modelType.name()));
            if (already) {
                skipped.add(new ProviderAccountInstancesResponse.Skipped(modelName, "已接入"));
                continue;
            }
            ModelTemplateEntity template = templates.get(modelName + "|" + modelType.name());
            Map<String, Object> instanceConnection = template == null
                    ? new LinkedHashMap<>()
                    : ConnectionConfigSupport.withoutAccountKeys(readMap(template.getConnectionDefaultsJson()));
            String pathKey = pathKey(modelType);
            instanceConnection.putIfAbsent(pathKey, defaultPath(modelType));
            Map<String, Object> options = template == null ? Map.of() : readMap(template.getDefaultOptionsJson());
            try {
                ModelInstanceResponse response = instanceService.createForAccount(
                        account, uniqueName(item.name(), modelName, account.getName()),
                        modelType, modelName, instanceConnection, options);
                created.add(response);
                connected.add(new ProviderModelsResponse.ConnectedModel(
                        modelName, modelType.name(), response.getId(), response.getName()));
            } catch (BizException e) {
                skipped.add(new ProviderAccountInstancesResponse.Skipped(modelName, e.getMessage()));
            }
        }
        return new ProviderAccountInstancesResponse(created, skipped);
    }

    private ProviderModelsResponse discover(String provider, Map<String, Object> connection, String accountId) {
        ModelInstanceRuntime runtime = ModelInstanceRuntime.builder()
                .provider(provider)
                .modelType(ModelType.LLM.name())
                .modelName("model-discovery")
                .protocol("OPENAI_COMPATIBLE")
                .connectionConfig(connection)
                .build();
        List<String> recommended = new ArrayList<>(new LinkedHashSet<>(templateService.enabledEntitiesForProvider(provider)
                .stream().map(ModelTemplateEntity::getModelName).toList()));
        List<ProviderModelsResponse.ConnectedModel> connected = accountId == null ? List.of() : connectedModels(accountId);
        try {
            List<UpstreamModel> models = runtimeClient.listModels(runtime);
            return new ProviderModelsResponse(true, models.isEmpty() ? "供应商返回了空的模型列表" : "ok",
                    provider, models.size(), models, recommended, connected);
        } catch (Exception e) {
            String message = e instanceof BizException be ? be.getMessage() : e.getMessage();
            return new ProviderModelsResponse(false,
                    friendlyMessage(e, ConnectionConfigSupport.sanitizeError(message)),
                    provider, 0, List.of(), recommended, connected);
        }
    }

    static String friendlyMessage(Exception e, String detail) {
        int status = e instanceof BizException be ? be.getCode() : 0;
        return switch (status) {
            case 401, 403 -> "API Key 无效或没有权限（HTTP " + status + "）。供应商原文：" + detail;
            case 404 -> "该服务地址没有提供模型列表接口（HTTP 404），请检查 Base URL。供应商原文：" + detail;
            default -> detail;
        };
    }

    private ProviderModelsResponse failure(String provider, String message) {
        return new ProviderModelsResponse(false, ConnectionConfigSupport.sanitizeError(message),
                provider, 0, List.of(), List.of(), List.of());
    }

    private List<ProviderModelsResponse.ConnectedModel> connectedModels(String accountId) {
        List<ProviderModelsResponse.ConnectedModel> out = new ArrayList<>();
        for (ModelInstanceEntity instance : instanceMapper.selectList(activeInstances(accountId))) {
            out.add(new ProviderModelsResponse.ConnectedModel(
                    instance.getModelName(), instance.getModelType(), instance.getId(), instance.getName()));
        }
        return out;
    }

    private long countInstances(String accountId) {
        return instanceMapper.selectCount(activeInstances(accountId));
    }

    private LambdaQueryWrapper<ModelInstanceEntity> activeInstances(String accountId) {
        return new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getProviderAccountId, accountId)
                .ne(ModelInstanceEntity::getStatus, ModelInstanceStatus.ARCHIVED.name());
    }

    private String uniqueName(String requested, String modelName, String accountName) {
        String base = StringUtils.hasText(requested) ? requested.trim() : modelName;
        if (!nameTaken(base)) {
            return base;
        }
        String withAccount = base + " · " + accountName;
        return nameTaken(withAccount) ? base + " · " + UUID.randomUUID().toString().substring(0, 6) : withAccount;
    }

    private boolean nameTaken(String name) {
        return instanceMapper.selectCount(new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getName, name)
                .eq(ModelInstanceEntity::getProjectScopeKey, "")) > 0;
    }

    private static String pathKey(ModelType type) {
        return switch (type) {
            case EMBEDDING -> ConnectionConfigSupport.EMBEDDING_PATH;
            case RERANKER -> ConnectionConfigSupport.RERANK_PATH;
            default -> ConnectionConfigSupport.CHAT_PATH;
        };
    }

    private static String defaultPath(ModelType type) {
        return switch (type) {
            case EMBEDDING -> ModelApiUrl.DEFAULT_EMBEDDING_PATH;
            case RERANKER -> ModelApiUrl.DEFAULT_RERANK_PATH;
            default -> ModelApiUrl.DEFAULT_CHAT_PATH;
        };
    }

    private void validateConnection(Map<String, Object> connection) {
        try {
            ConnectionConfigSupport.requireBaseUrl(connection);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }
    }

    private void assertNameUnique(String provider, String name, String excludeId) {
        LambdaQueryWrapper<ModelProviderAccountEntity> query = new LambdaQueryWrapper<ModelProviderAccountEntity>()
                .eq(ModelProviderAccountEntity::getProvider, provider)
                .eq(ModelProviderAccountEntity::getName, name);
        if (StringUtils.hasText(excludeId)) {
            query.ne(ModelProviderAccountEntity::getId, excludeId);
        }
        if (mapper.selectCount(query) > 0) {
            throw new BizException(400, "该厂商下已有同名账号：" + name);
        }
    }

    private ModelProviderAccountEntity require(String id) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(400, "账号 id 不能为空");
        }
        ModelProviderAccountEntity entity = mapper.selectById(id.trim());
        if (entity == null) {
            throw new BizException(404, "厂商账号不存在: " + id);
        }
        return entity;
    }

    private ModelProviderAccountResponse toResponse(ModelProviderAccountEntity entity) {
        Map<String, Object> connection = readConnection(entity);
        Object apiKey = connection.get(ConnectionConfigSupport.API_KEY);
        return ModelProviderAccountResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .connection(ConnectionConfigSupport.mask(connection))
                .apiKeyConfigured(apiKey instanceof String key && !key.isBlank())
                .status(entity.getStatus())
                .remark(entity.getRemark())
                .instanceCount(countInstances(entity.getId()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Map<String, Object> readConnection(ModelProviderAccountEntity entity) {
        return ConnectionConfigSupport.accountOnly(readMap(credentialCipher.decrypt(entity.getConnectionConfigJson())));
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            throw new BizException(500, "Invalid provider account JSON");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "Serialize provider account failed");
        }
    }
}
