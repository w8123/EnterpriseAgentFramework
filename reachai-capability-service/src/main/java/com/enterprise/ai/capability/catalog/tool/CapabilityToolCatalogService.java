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

    private static final String BUSINESS_METHOD_ASSET_TYPE = "BUSINESS_METHOD";

    private final ToolDefinitionMapper toolMapper;
    private final ScanProjectMapper projectMapper;
    private final ScanProjectToolMapper scanToolMapper;
    private final ObjectMapper objectMapper;

    public IPage<ToolDefinitionEntity> page(int current,
                                            int size,
                                            String keyword,
                                            String source,
                                            Boolean enabled,
                                            Long projectId) {
        return page(current, size, keyword, source, enabled, projectId, null);
    }

    /**
     * Reads the accepted Java business-method projection from the owner table.
     * The type condition belongs to the SQL wrapper so count and records share
     * the same pagination boundary.
     */
    public IPage<ToolDefinitionEntity> pageBusinessMethods(int current,
                                                            int size,
                                                            String keyword,
                                                            Boolean enabled,
                                                            Long projectId) {
        return page(current, size, keyword, null, enabled, projectId,
                BUSINESS_METHOD_ASSET_TYPE);
    }

    private IPage<ToolDefinitionEntity> page(int current,
                                             int size,
                                             String keyword,
                                             String source,
                                             Boolean enabled,
                                             Long projectId,
                                             String assetType) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<ToolDefinitionEntity> wrapper = new LambdaQueryWrapper<>();
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
        if (StringUtils.hasText(assetType)) {
            wrapper.eq(ToolDefinitionEntity::getAssetType, assetType);
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
                .last("limit 1")));
    }

    public Optional<ToolDefinitionEntity> findBusinessMethodByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        String reference = name.trim();
        return Optional.ofNullable(toolMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getAssetType, BUSINESS_METHOD_ASSET_TYPE)
                // Legacy callers use the catalog name; Workflow nodes persist
                // the stable project-qualified Runtime reference.  Both resolve
                // to the same Capability-owned accepted projection.
                .and(query -> query.eq(ToolDefinitionEntity::getName, reference)
                        .or()
                        .eq(ToolDefinitionEntity::getQualifiedName, reference))
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
