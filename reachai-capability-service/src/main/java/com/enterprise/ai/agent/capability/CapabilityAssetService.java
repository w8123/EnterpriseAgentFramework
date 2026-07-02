package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CapabilityAssetService {

    private final CapabilityModuleMapper moduleMapper;
    private final ToolAssetMapper toolMapper;
    private final CompositionDefinitionMapper compositionMapper;
    private final InteractionDefinitionMapper interactionMapper;
    private final ObjectMapper objectMapper;

    public CapabilityAssetService(CapabilityModuleMapper moduleMapper,
                                  ToolAssetMapper toolMapper,
                                  CompositionDefinitionMapper compositionMapper,
                                  InteractionDefinitionMapper interactionMapper,
                                  ObjectMapper objectMapper) {
        this.moduleMapper = moduleMapper;
        this.toolMapper = toolMapper;
        this.compositionMapper = compositionMapper;
        this.interactionMapper = interactionMapper;
        this.objectMapper = objectMapper;
    }

    public List<CapabilityModuleEntity> listModules() {
        return moduleMapper.selectList(new LambdaQueryWrapper<CapabilityModuleEntity>()
                .orderByAsc(CapabilityModuleEntity::getCode));
    }

    public Optional<CapabilityModuleEntity> findModule(String code) {
        return Optional.ofNullable(moduleMapper.selectOne(new LambdaQueryWrapper<CapabilityModuleEntity>()
                .eq(CapabilityModuleEntity::getCode, code)
                .last("LIMIT 1")));
    }

    public CapabilityModuleEntity saveModule(CapabilityModuleEntity entity) {
        if (!StringUtils.hasText(entity.getCode())) {
            throw new IllegalArgumentException("capability code is required");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!StringUtils.hasText(entity.getVersion())) {
            entity.setVersion("1.0.0");
        }
        if (!StringUtils.hasText(entity.getSourceType())) {
            entity.setSourceType("BUILTIN");
        }
        if (!StringUtils.hasText(entity.getStatus())) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        CapabilityModuleEntity existing = findModule(entity.getCode()).orElse(null);
        entity.setUpdateTime(now);
        if (existing == null) {
            entity.setCreateTime(now);
            moduleMapper.insert(entity);
            return entity;
        }
        entity.setId(existing.getId());
        entity.setCreateTime(existing.getCreateTime());
        moduleMapper.updateById(entity);
        return entity;
    }

    public List<ToolAssetEntity> listTools(String capabilityCode) {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolAssetEntity>()
                .eq(ToolAssetEntity::getCapabilityCode, capabilityCode)
                .orderByAsc(ToolAssetEntity::getToolCode));
    }

    public Optional<ToolAssetEntity> findToolByQualifiedName(String qualifiedName) {
        return Optional.ofNullable(toolMapper.selectOne(new LambdaQueryWrapper<ToolAssetEntity>()
                .eq(ToolAssetEntity::getQualifiedName, qualifiedName)
                .last("LIMIT 1")));
    }

    public ToolAssetEntity saveTool(String capabilityCode, ToolAssetEntity entity) {
        CapabilityModuleEntity module = findModule(capabilityCode)
                .orElseThrow(() -> new IllegalArgumentException("capability module not found: " + capabilityCode));
        if (!StringUtils.hasText(entity.getToolCode())) {
            throw new IllegalArgumentException("tool code is required");
        }
        entity.setCapabilityModuleId(module.getId());
        entity.setCapabilityCode(capabilityCode);
        entity.setQualifiedName(qualified(capabilityCode, entity.getToolCode()));
        if (!StringUtils.hasText(entity.getExecutorType())) {
            entity.setExecutorType("BEAN");
        }
        if (!StringUtils.hasText(entity.getSideEffect())) {
            entity.setSideEffect("WRITE");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getAgentVisible() == null) {
            entity.setAgentVisible(true);
        }
        LocalDateTime now = LocalDateTime.now();
        ToolAssetEntity existing = findToolByQualifiedName(entity.getQualifiedName()).orElse(null);
        entity.setUpdateTime(now);
        if (existing == null) {
            entity.setCreateTime(now);
            toolMapper.insert(entity);
            return entity;
        }
        entity.setId(existing.getId());
        entity.setCreateTime(existing.getCreateTime());
        toolMapper.updateById(entity);
        return entity;
    }

    public List<CompositionDefinitionEntity> listCompositions(String capabilityCode) {
        return compositionMapper.selectList(new LambdaQueryWrapper<CompositionDefinitionEntity>()
                .eq(CompositionDefinitionEntity::getCapabilityCode, capabilityCode)
                .orderByAsc(CompositionDefinitionEntity::getCompositionCode));
    }

    public Optional<CompositionDefinitionEntity> findCompositionByQualifiedName(String qualifiedName) {
        return Optional.ofNullable(compositionMapper.selectOne(new LambdaQueryWrapper<CompositionDefinitionEntity>()
                .eq(CompositionDefinitionEntity::getQualifiedName, qualifiedName)
                .last("LIMIT 1")));
    }

    public CompositionDefinitionEntity saveComposition(String capabilityCode, CompositionDefinitionEntity entity) {
        CapabilityModuleEntity module = findModule(capabilityCode)
                .orElseThrow(() -> new IllegalArgumentException("capability module not found: " + capabilityCode));
        if (!StringUtils.hasText(entity.getCompositionCode())) {
            throw new IllegalArgumentException("composition code is required");
        }
        entity.setCapabilityModuleId(module.getId());
        entity.setCapabilityCode(capabilityCode);
        entity.setQualifiedName(qualified(capabilityCode, entity.getCompositionCode()));
        if (!StringUtils.hasText(entity.getSideEffect())) {
            entity.setSideEffect("WRITE");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getAgentVisible() == null) {
            entity.setAgentVisible(true);
        }
        LocalDateTime now = LocalDateTime.now();
        CompositionDefinitionEntity existing = findCompositionByQualifiedName(entity.getQualifiedName()).orElse(null);
        entity.setUpdateTime(now);
        if (existing == null) {
            entity.setCreateTime(now);
            compositionMapper.insert(entity);
            return entity;
        }
        entity.setId(existing.getId());
        entity.setCreateTime(existing.getCreateTime());
        compositionMapper.updateById(entity);
        return entity;
    }

    public List<InteractionDefinitionEntity> listInteractions(String capabilityCode) {
        return interactionMapper.selectList(new LambdaQueryWrapper<InteractionDefinitionEntity>()
                .eq(InteractionDefinitionEntity::getCapabilityCode, capabilityCode)
                .orderByAsc(InteractionDefinitionEntity::getInteractionCode));
    }

    public Optional<InteractionDefinitionEntity> findInteractionByQualifiedName(String qualifiedName) {
        return Optional.ofNullable(interactionMapper.selectOne(new LambdaQueryWrapper<InteractionDefinitionEntity>()
                .eq(InteractionDefinitionEntity::getQualifiedName, qualifiedName)
                .last("LIMIT 1")));
    }

    public InteractionDefinitionEntity saveInteraction(String capabilityCode, InteractionDefinitionEntity entity) {
        CapabilityModuleEntity module = findModule(capabilityCode)
                .orElseThrow(() -> new IllegalArgumentException("capability module not found: " + capabilityCode));
        if (!StringUtils.hasText(entity.getInteractionCode())) {
            throw new IllegalArgumentException("interaction code is required");
        }
        entity.setCapabilityModuleId(module.getId());
        entity.setCapabilityCode(capabilityCode);
        entity.setQualifiedName(qualified(capabilityCode, entity.getInteractionCode()));
        if (!StringUtils.hasText(entity.getInteractionType())) {
            entity.setInteractionType("COLLECT_INPUT");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getAgentVisible() == null) {
            entity.setAgentVisible(true);
        }
        LocalDateTime now = LocalDateTime.now();
        InteractionDefinitionEntity existing = findInteractionByQualifiedName(entity.getQualifiedName()).orElse(null);
        entity.setUpdateTime(now);
        if (existing == null) {
            entity.setCreateTime(now);
            interactionMapper.insert(entity);
            return entity;
        }
        entity.setId(existing.getId());
        entity.setCreateTime(existing.getCreateTime());
        interactionMapper.updateById(entity);
        return entity;
    }

    public String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("json serialization failed: " + ex.getMessage(), ex);
        }
    }

    private static String qualified(String capabilityCode, String assetCode) {
        return capabilityCode + "." + assetCode;
    }
}
