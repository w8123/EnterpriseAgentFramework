package com.enterprise.ai.model.instance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.security.CredentialCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelInstanceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ModelInstanceMapper mapper;
    private final ObjectMapper objectMapper;
    private final CredentialCipher credentialCipher;

    public List<ModelInstanceResponse> list(String workspaceId, String modelType, String provider) {
        LambdaQueryWrapper<ModelInstanceEntity> query = new LambdaQueryWrapper<ModelInstanceEntity>()
                .orderByDesc(ModelInstanceEntity::getUpdatedAt);
        if (StringUtils.hasText(workspaceId)) {
            query.eq(ModelInstanceEntity::getWorkspaceId, workspaceId);
        }
        if (StringUtils.hasText(modelType)) {
            query.eq(ModelInstanceEntity::getModelType, modelType);
        }
        if (StringUtils.hasText(provider)) {
            query.eq(ModelInstanceEntity::getProvider, provider);
        }
        return mapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public ModelInstanceEntity getActiveEntity(String id) {
        ModelInstanceEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "Model instance not found: " + id);
        }
        if (!ModelInstanceStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new BizException(400, "Model instance is not active: " + entity.getName());
        }
        return entity;
    }

    public ModelInstanceResponse get(String id) {
        ModelInstanceEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "Model instance not found: " + id);
        }
        return toResponse(entity);
    }

    public ModelInstanceResponse create(ModelInstanceRequest request) {
        validate(request, true);
        LocalDateTime now = LocalDateTime.now();
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setId(StringUtils.hasText(request.getId()) ? request.getId() : UUID.randomUUID().toString());
        fillEntity(entity, request, false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toResponse(entity);
    }

    public ModelInstanceResponse update(String id, ModelInstanceRequest request) {
        ModelInstanceEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "Model instance not found: " + id);
        }
        validate(request, false);
        fillEntity(entity, request, true);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toResponse(entity);
    }

    public boolean delete(String id) {
        mapper.deleteById(id);
        return true;
    }

    public ModelInstanceRuntime toRuntime(ModelInstanceEntity entity) {
        return ModelInstanceRuntime.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .modelType(entity.getModelType())
                .modelName(entity.getModelName())
                .endpointType(entity.getEndpointType())
                .credential(readCredential(entity))
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
                .endpointType(entity.getEndpointType())
                .workspaceId(entity.getWorkspaceId())
                .credential(mask(readCredential(entity)))
                .defaultOptions(readMap(entity.getDefaultOptionsJson()))
                .paramsSchema(readObject(entity.getParamsSchemaJson()))
                .status(entity.getStatus())
                .remark(entity.getRemark())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void fillEntity(ModelInstanceEntity entity, ModelInstanceRequest request, boolean preserveMaskedCredential) {
        entity.setName(request.getName());
        entity.setProvider(request.getProvider());
        entity.setModelType(request.getModelType().name());
        entity.setModelName(request.getModelName());
        entity.setEndpointType((request.getEndpointType() == null ? EndpointType.BUILT_IN : request.getEndpointType()).name());
        entity.setWorkspaceId(StringUtils.hasText(request.getWorkspaceId()) ? request.getWorkspaceId() : "default");
        Map<String, Object> credential = preserveMaskedCredential
                ? mergeCredential(readCredential(entity), request.getCredential())
                : (request.getCredential() == null ? Map.of() : request.getCredential());
        entity.setCredentialJson(credentialCipher.encrypt(toJson(credential)));
        entity.setDefaultOptionsJson(toJson(request.getDefaultOptions() == null ? Map.of() : request.getDefaultOptions()));
        entity.setParamsSchemaJson(toJson(request.getParamsSchema() == null ? List.of() : request.getParamsSchema()));
        entity.setStatus((request.getStatus() == null ? ModelInstanceStatus.ACTIVE : request.getStatus()).name());
        entity.setRemark(request.getRemark());
    }

    private void validate(ModelInstanceRequest request, boolean create) {
        if (request == null) {
            throw new BizException(400, "Request body is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BizException(400, "Model instance name is required");
        }
        if (!StringUtils.hasText(request.getProvider())) {
            throw new BizException(400, "Provider is required");
        }
        if (request.getModelType() == null) {
            throw new BizException(400, "Model type is required");
        }
        if (!StringUtils.hasText(request.getModelName())) {
            throw new BizException(400, "Model name is required");
        }
        String workspaceId = StringUtils.hasText(request.getWorkspaceId()) ? request.getWorkspaceId() : "default";
        LambdaQueryWrapper<ModelInstanceEntity> query = new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getName, request.getName())
                .eq(ModelInstanceEntity::getWorkspaceId, workspaceId);
        if (!create && StringUtils.hasText(request.getId())) {
            query.ne(ModelInstanceEntity::getId, request.getId());
        }
        if (mapper.selectCount(query) > 0) {
            throw new BizException(400, "Model instance name already exists in workspace: " + request.getName());
        }
    }

    private Map<String, Object> readCredential(ModelInstanceEntity entity) {
        return readMap(credentialCipher.decrypt(entity.getCredentialJson()));
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

    private Map<String, Object> mask(Map<String, Object> source) {
        Map<String, Object> masked = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof String s && isSensitive(key)) {
                masked.put(key, maskString(s));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private Map<String, Object> mergeCredential(Map<String, Object> original, Map<String, Object> incoming) {
        if (incoming == null) {
            return original == null ? Map.of() : original;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (original != null) {
            merged.putAll(original);
        }
        incoming.forEach((key, value) -> {
            if (value instanceof String s && isSensitive(key) && isMaskedValue(s) && original != null && original.containsKey(key)) {
                merged.put(key, original.get(key));
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private boolean isMaskedValue(String value) {
        return value != null && value.contains("******");
    }

    private boolean isSensitive(String key) {
        String k = key == null ? "" : key.toLowerCase();
        return k.contains("key") || k.contains("secret") || k.contains("token") || k.contains("password");
    }

    private String maskString(String value) {
        if (value == null || value.length() <= 8) {
            return "******";
        }
        return value.substring(0, 4) + "******" + value.substring(value.length() - 4);
    }
}
