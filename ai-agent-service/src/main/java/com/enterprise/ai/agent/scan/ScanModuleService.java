package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模块（Controller 聚合）管理服务。
 * 支持：扫描完成后自动初始化、按项目查询、合并、重命名、删除。
 */
@Slf4j
@Service
public class ScanModuleService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ScanModuleMapper moduleMapper;
    private final ToolDefinitionMapper toolMapper;
    private final ObjectMapper objectMapper;

    public ScanModuleService(ScanModuleMapper moduleMapper,
                             ToolDefinitionMapper toolMapper,
                             ObjectMapper objectMapper) {
        this.moduleMapper = moduleMapper;
        this.toolMapper = toolMapper;
        this.objectMapper = objectMapper;
    }

    public List<ScanModuleEntity> listByProject(Long projectId) {
        return moduleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                .eq(ScanModuleEntity::getProjectId, projectId)
                .orderByAsc(ScanModuleEntity::getName));
    }

    public ScanModuleEntity getById(Long id) {
        ScanModuleEntity entity = moduleMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("模块不存在: " + id);
        }
        return entity;
    }

    /**
     * 扫描/重扫完成后调用：根据项目下的 tool_definition 自动按 Controller 类名初始化模块，
     * 并回写 tool_definition.module_id。幂等：已存在同名模块的工具会复用原模块。
     */
    @Transactional
    public void bootstrapFromTools(Long projectId) {
        List<ToolDefinitionEntity> tools = toolMapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getProjectId, projectId));
        if (tools.isEmpty()) {
            return;
        }

        Map<String, List<ToolDefinitionEntity>> grouped = new LinkedHashMap<>();
        for (ToolDefinitionEntity tool : tools) {
            String moduleName = resolveControllerModuleName(tool);
            grouped.computeIfAbsent(moduleName, key -> new ArrayList<>()).add(tool);
        }

        Map<String, ScanModuleEntity> existingByName = new LinkedHashMap<>();
        for (ScanModuleEntity existing : listByProject(projectId)) {
            existingByName.put(existing.getName(), existing);
        }

        for (Map.Entry<String, List<ToolDefinitionEntity>> entry : grouped.entrySet()) {
            String moduleName = entry.getKey();
            ScanModuleEntity module = existingByName.get(moduleName);
            if (module == null) {
                module = new ScanModuleEntity();
                module.setProjectId(projectId);
                module.setName(moduleName);
                module.setDisplayName(moduleName);
                module.setSourceClasses(serializeClasses(List.of(moduleName)));
                moduleMapper.insert(module);
            }
            for (ToolDefinitionEntity tool : entry.getValue()) {
                if (!Objects.equals(tool.getModuleId(), module.getId())) {
                    tool.setModuleId(module.getId());
                    toolMapper.updateById(tool);
                }
            }
        }

        existingByName.values().stream()
                .filter(existing -> !grouped.containsKey(existing.getName()))
                .filter(existing -> !hasMultipleSourceClasses(existing))
                .filter(existing -> toolMapper.selectCount(new LambdaQueryWrapper<ToolDefinitionEntity>()
                        .eq(ToolDefinitionEntity::getModuleId, existing.getId())) == 0)
                .forEach(orphan -> moduleMapper.deleteById(orphan.getId()));
    }

    @Transactional
    public ScanModuleEntity rename(Long moduleId, String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        ScanModuleEntity module = getById(moduleId);
        module.setDisplayName(displayName.trim());
        moduleMapper.updateById(module);
        return module;
    }

    /**
     * 将 sourceIds 合并进 targetId，被合并模块下的工具会迁移 module_id，
     * 之后被合并模块删除。若指定 mergedDisplayName，合并后以其为展示名。
     */
    @Transactional
    public ScanModuleEntity merge(Long targetId, List<Long> sourceIds, String mergedDisplayName) {
        ScanModuleEntity target = getById(targetId);
        List<ScanModuleEntity> sources = new ArrayList<>();
        for (Long sourceId : sourceIds == null ? List.<Long>of() : sourceIds) {
            if (Objects.equals(sourceId, targetId)) {
                continue;
            }
            ScanModuleEntity source = getById(sourceId);
            if (!Objects.equals(source.getProjectId(), target.getProjectId())) {
                throw new IllegalArgumentException("跨项目不能合并模块");
            }
            sources.add(source);
        }

        Set<String> mergedClasses = new TreeSet<>(parseClasses(target.getSourceClasses()));
        mergedClasses.add(target.getName());
        for (ScanModuleEntity source : sources) {
            mergedClasses.addAll(parseClasses(source.getSourceClasses()));
            mergedClasses.add(source.getName());

            toolMapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                            .eq(ToolDefinitionEntity::getModuleId, source.getId()))
                    .forEach(tool -> {
                        tool.setModuleId(target.getId());
                        toolMapper.updateById(tool);
                    });
            moduleMapper.deleteById(source.getId());
        }
        target.setSourceClasses(serializeClasses(new ArrayList<>(mergedClasses)));
        if (StringUtils.hasText(mergedDisplayName)) {
            target.setDisplayName(mergedDisplayName.trim());
        }
        moduleMapper.updateById(target);
        return target;
    }

    @Transactional
    public void deleteByProject(Long projectId) {
        moduleMapper.delete(new LambdaQueryWrapper<ScanModuleEntity>()
                .eq(ScanModuleEntity::getProjectId, projectId));
    }

    public List<String> parseClasses(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("[ScanModuleService] parse sourceClasses failed: {}", json, ex);
            return List.of();
        }
    }

    private String serializeClasses(List<String> classes) {
        try {
            List<String> sorted = new ArrayList<>(new TreeSet<>(classes));
            sorted.sort(Comparator.naturalOrder());
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 sourceClasses 失败", ex);
        }
    }

    private boolean hasMultipleSourceClasses(ScanModuleEntity module) {
        return parseClasses(module.getSourceClasses()).size() > 1;
    }

    /**
     * 从 {@link ToolDefinitionEntity#getSourceLocation()} 里尝试还原 Controller 类名。
     * Scanner 约定写法：{fileName}#{ClassName}#{methodName} 或 OpenAPI 场景只有 tag/file，回退取首段。
     */
    private String resolveControllerModuleName(ToolDefinitionEntity tool) {
        String loc = tool.getSourceLocation();
        if (StringUtils.hasText(loc)) {
            String[] parts = loc.split("#");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) {
                return parts[1].trim();
            }
            if (StringUtils.hasText(parts[0])) {
                String first = parts[0].trim();
                int dot = first.lastIndexOf('.');
                if (dot > 0) {
                    first = first.substring(0, dot);
                }
                return first.isBlank() ? "default" : first;
            }
        }
        return StringUtils.hasText(tool.getHttpMethod()) ? tool.getHttpMethod().toLowerCase(Locale.ROOT) + "_module" : "default";
    }
}
