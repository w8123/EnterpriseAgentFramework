package com.enterprise.ai.model.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ConnectionConfigSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ModelTemplateService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ModelTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    public List<ModelTemplateResponse> list(String keyword, String provider, String modelType, Boolean enabled) {
        LambdaQueryWrapper<ModelTemplateEntity> query = new LambdaQueryWrapper<ModelTemplateEntity>()
                .orderByAsc(ModelTemplateEntity::getSortOrder)
                .orderByAsc(ModelTemplateEntity::getName);
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            query.and(w -> w.like(ModelTemplateEntity::getName, like)
                    .or().like(ModelTemplateEntity::getProvider, like)
                    .or().like(ModelTemplateEntity::getModelName, like));
        }
        if (StringUtils.hasText(provider)) {
            query.eq(ModelTemplateEntity::getProvider, provider.trim());
        }
        if (StringUtils.hasText(modelType)) {
            query.eq(ModelTemplateEntity::getModelType, modelType.trim());
        }
        if (enabled != null) {
            query.eq(ModelTemplateEntity::getEnabled, enabled);
        }
        return mapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public ModelTemplateResponse get(String id) {
        return toResponse(getEntity(id));
    }

    public ModelTemplateEntity getEntity(String id) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(400, "Template id is required");
        }
        ModelTemplateEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "Model template not found: " + id);
        }
        return entity;
    }

    public ModelTemplateResponse toResponse(ModelTemplateEntity entity) {
        return ModelTemplateResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .provider(entity.getProvider())
                .modelType(entity.getModelType())
                .modelName(entity.getModelName())
                .protocol(entity.getProtocol())
                .connectionDefaults(ConnectionConfigSupport.normalize(readMap(entity.getConnectionDefaultsJson())))
                .credentialSchema(readObject(entity.getCredentialSchemaJson()))
                .defaultOptions(readMap(entity.getDefaultOptionsJson()))
                .paramsSchema(readObject(entity.getParamsSchemaJson()))
                .capabilities(readObject(entity.getCapabilitiesJson()))
                .iconKey(entity.getIconKey())
                .enabled(entity.getEnabled())
                .sortOrder(entity.getSortOrder())
                .remark(entity.getRemark())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new BizException(500, "Invalid model template JSON: " + e.getMessage());
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
}
