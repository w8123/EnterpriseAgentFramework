package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.CapabilitySourceOwnership;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CapabilityToolCatalogService {

    private final ToolDefinitionMapper toolMapper;
    private final ScanProjectMapper projectMapper;
    private final ScanProjectToolMapper scanToolMapper;
    private final ObjectMapper objectMapper;

    /** Technical invocation projection; business asset reads use their owner catalog. */
    public IPage<ToolDefinitionEntity> page(int current, int size, String keyword, String source,
                                           Boolean enabled, Long projectId) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<ToolDefinitionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ToolDefinitionEntity::getAssetType, List.of("BUSINESS_METHOD", "HTTP_API"));
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            wrapper.and(q -> q.like(ToolDefinitionEntity::getName, term)
                    .or()
                    .like(ToolDefinitionEntity::getQualifiedName, term)
                    .or()
                    .like(ToolDefinitionEntity::getTitle, term)
                    .or()
                    .like(ToolDefinitionEntity::getDescription, term));
        }
        if (StringUtils.hasText(source)) {
            wrapper.eq(ToolDefinitionEntity::getSource, source.trim().toLowerCase(Locale.ROOT));
        }
        if (enabled != null) {
            wrapper.eq(ToolDefinitionEntity::getEnabled, enabled);
        }
        if (projectId != null) {
            wrapper.eq(ToolDefinitionEntity::getProjectId, projectId);
        }
        wrapper.orderByAsc(ToolDefinitionEntity::getTitle)
                .orderByAsc(ToolDefinitionEntity::getName);
        return toolMapper.selectPage(new Page<>(pageNum, pageSize, true), wrapper);
    }

    public Optional<ToolDefinitionEntity> findByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name.trim())
                .in(ToolDefinitionEntity::getAssetType, List.of("BUSINESS_METHOD", "HTTP_API"))
                .last("limit 1")));
    }

    public List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        return CapabilityParameterContractCodec.parse(objectMapper, parametersJson);
    }

    public String getProjectNameOrNull(Long projectId) {
        if (projectId == null) {
            return null;
        }
        ScanProjectEntity project = projectMapper.selectById(projectId);
        return project == null ? null : project.getName();
    }

    public Optional<ScanProjectToolEntity> findCatalogScanTool(ToolDefinitionEntity entity) {
        if (entity == null || entity.getProjectId() == null || entity.getId() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(scanToolMapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, entity.getProjectId())
                .eq(ScanProjectToolEntity::getGlobalToolDefinitionId, entity.getId())
                .last("limit 1")));
    }

    public boolean isSdkBackedTool(ToolDefinitionEntity entity) {
        return entity != null
                && (StringUtils.hasText(entity.getSourceQualifiedName())
                || CapabilitySourceOwnership.isSdkLocation(entity.getSourceLocation()));
    }

}
