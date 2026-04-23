package com.enterprise.ai.agent.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 三层语义文档基础存取：按唯一键 upsert、按层级/引用读取、手动编辑、批量删除。
 */
@Service
public class SemanticDocService {

    private final SemanticDocMapper mapper;

    public SemanticDocService(SemanticDocMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<SemanticDocEntity> findByRef(String level, Long projectId, Long moduleId, Long toolId) {
        LambdaQueryWrapper<SemanticDocEntity> w = new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getLevel, level);
        applyRef(w, projectId, moduleId, toolId);
        return Optional.ofNullable(mapper.selectOne(w.last("limit 1")));
    }

    public List<SemanticDocEntity> listByProject(Long projectId) {
        return mapper.selectList(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getProjectId, projectId)
                .orderByAsc(SemanticDocEntity::getLevel)
                .orderByAsc(SemanticDocEntity::getModuleId)
                .orderByAsc(SemanticDocEntity::getToolId));
    }

    public SemanticDocEntity getById(Long id) {
        SemanticDocEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("语义文档不存在: " + id);
        }
        return entity;
    }

    /**
     * 按 (level, projectId, moduleId, toolId) upsert；{@code force=false} 时若已存在 edited 文档会保留不覆盖。
     */
    @Transactional
    public SemanticDocEntity upsertGenerated(SemanticDocEntity incoming, boolean force) {
        Optional<SemanticDocEntity> existingOpt = findByRef(
                incoming.getLevel(), incoming.getProjectId(), incoming.getModuleId(), incoming.getToolId());
        if (existingOpt.isPresent()) {
            SemanticDocEntity existing = existingOpt.get();
            if (!force && SemanticDocEntity.STATUS_EDITED.equals(existing.getStatus())) {
                return existing;
            }
            existing.setContentMd(incoming.getContentMd());
            existing.setPromptVersion(incoming.getPromptVersion());
            existing.setModelName(incoming.getModelName());
            existing.setTokenUsage(incoming.getTokenUsage() == null ? 0 : incoming.getTokenUsage());
            existing.setStatus(SemanticDocEntity.STATUS_GENERATED);
            mapper.updateById(existing);
            return existing;
        }
        incoming.setStatus(SemanticDocEntity.STATUS_GENERATED);
        mapper.insert(incoming);
        return incoming;
    }

    @Transactional
    public SemanticDocEntity edit(Long id, String contentMd) {
        SemanticDocEntity existing = getById(id);
        if (!StringUtils.hasText(contentMd)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        existing.setContentMd(contentMd);
        existing.setStatus(SemanticDocEntity.STATUS_EDITED);
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void deleteByProject(Long projectId) {
        mapper.delete(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getProjectId, projectId));
    }

    @Transactional
    public void deleteByModule(Long moduleId) {
        mapper.delete(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getModuleId, moduleId)
                .eq(SemanticDocEntity::getLevel, SemanticDocEntity.LEVEL_MODULE));
    }

    @Transactional
    public void deleteByTool(Long toolId) {
        mapper.delete(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getToolId, toolId)
                .eq(SemanticDocEntity::getLevel, SemanticDocEntity.LEVEL_TOOL));
    }

    private void applyRef(LambdaQueryWrapper<SemanticDocEntity> w, Long projectId, Long moduleId, Long toolId) {
        if (projectId != null) {
            w.eq(SemanticDocEntity::getProjectId, projectId);
        } else {
            w.isNull(SemanticDocEntity::getProjectId);
        }
        if (moduleId != null) {
            w.eq(SemanticDocEntity::getModuleId, moduleId);
        } else {
            w.isNull(SemanticDocEntity::getModuleId);
        }
        if (toolId != null) {
            w.eq(SemanticDocEntity::getToolId, toolId);
        } else {
            w.isNull(SemanticDocEntity::getToolId);
        }
        Objects.requireNonNull(w);
    }
}
