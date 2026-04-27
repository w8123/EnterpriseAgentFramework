package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
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
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ObjectMapper objectMapper;
    private final ScanProjectMapper projectMapper;

    public ScanModuleService(ScanModuleMapper moduleMapper,
                             ScanProjectToolMapper scanProjectToolMapper,
                             ObjectMapper objectMapper,
                             ScanProjectMapper projectMapper) {
        this.moduleMapper = moduleMapper;
        this.scanProjectToolMapper = scanProjectToolMapper;
        this.objectMapper = objectMapper;
        this.projectMapper = projectMapper;
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
     * 扫描/重扫完成后调用：根据项目下的 scan_project_tool 自动按 Controller 类名初始化模块，
     * 并回写 module_id。幂等：已存在同名模块的工具会复用原模块。
     */
    @Transactional
    public void bootstrapFromTools(Long projectId) {
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
        if (tools.isEmpty()) {
            return;
        }

        ScanSettings scanSettings = loadScanSettings(projectId);
        Path scanRoot = resolveScanRoot(projectId);

        Map<String, List<ScanProjectToolEntity>> grouped = new LinkedHashMap<>();
        for (ScanProjectToolEntity tool : tools) {
            String moduleName = resolveControllerModuleName(tool);
            grouped.computeIfAbsent(moduleName, key -> new ArrayList<>()).add(tool);
        }

        Map<String, ScanModuleEntity> existingByName = new LinkedHashMap<>();
        for (ScanModuleEntity existing : listByProject(projectId)) {
            existingByName.put(existing.getName(), existing);
        }

        for (Map.Entry<String, List<ScanProjectToolEntity>> entry : grouped.entrySet()) {
            String moduleName = entry.getKey();
            ScanModuleEntity module = existingByName.get(moduleName);
            if (module == null) {
                module = new ScanModuleEntity();
                module.setProjectId(projectId);
                module.setName(moduleName);
                module.setDisplayName(resolveModuleDisplayName(scanRoot, moduleName, entry.getValue().get(0), scanSettings));
                module.setSourceClasses(serializeClasses(List.of(moduleName)));
                moduleMapper.insert(module);
            } else {
                refreshDisplayNameIfDefault(scanRoot, moduleName, module, entry.getValue().get(0), scanSettings);
            }
            for (ScanProjectToolEntity tool : entry.getValue()) {
                if (!Objects.equals(tool.getModuleId(), module.getId())) {
                    tool.setModuleId(module.getId());
                    scanProjectToolMapper.updateById(tool);
                }
            }
        }

        existingByName.values().stream()
                .filter(existing -> !grouped.containsKey(existing.getName()))
                .filter(existing -> !hasMultipleSourceClasses(existing))
                .filter(existing -> scanProjectToolMapper.selectCount(new LambdaQueryWrapper<ScanProjectToolEntity>()
                        .eq(ScanProjectToolEntity::getModuleId, existing.getId())) == 0)
                .forEach(orphan -> moduleMapper.deleteById(orphan.getId()));
    }

    private Path resolveScanRoot(Long projectId) {
        ScanProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !StringUtils.hasText(project.getScanPath())) {
            return null;
        }
        try {
            return Path.of(project.getScanPath().trim());
        } catch (Exception ex) {
            log.warn("[ScanModuleService] invalid scanPath for project {}: {}", projectId, project.getScanPath(), ex);
            return null;
        }
    }

    private ScanSettings loadScanSettings(Long projectId) {
        ScanProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return ScanSettings.defaults();
        }
        return ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
    }

    /**
     * 展示名：与「接口说明来源」设置一致，从类 Javadoc、类上 @Api / @Tag 等按优先级与开关解析，否则为类名。
     */
    private String resolveModuleDisplayName(Path scanRoot, String moduleName, ScanProjectToolEntity sample, ScanSettings settings) {
        return ControllerModuleDisplayNameResolver.resolve(
                        scanRoot, moduleName, sample.getSourceLocation(), settings)
                .filter(StringUtils::hasText)
                .orElse(moduleName);
    }

    /**
     * 在重扫时按当前「接口说明来源」更新模块展示名：
     * 展示名仍为类名、或与「全默认项开启」时的自动解析结果相同时，用当前设置重算，以免关闭 Javadoc 后仍显示旧 Javadoc 文案。
     * 若用户已手动重命名（与类名、且与全默认下的自动名均不同）则不再覆盖。
     */
    private void refreshDisplayNameIfDefault(
            Path scanRoot, String moduleName, ScanModuleEntity module, ScanProjectToolEntity sample, ScanSettings settings) {
        if (scanRoot == null) {
            return;
        }
        String resolved = resolveModuleDisplayName(scanRoot, moduleName, sample, settings);
        if (!StringUtils.hasText(resolved)) {
            return;
        }
        String autoWithAllSourceDefaults = ControllerModuleDisplayNameResolver
                .resolve(scanRoot, moduleName, sample.getSourceLocation(), ScanSettings.defaults())
                .filter(StringUtils::hasText)
                .orElse(moduleName);
        String name = module.getName();
        String current = module.getDisplayName();
        boolean stillClassName = Objects.equals(current, name);
        boolean matchesLegacyAuto = Objects.equals(current, autoWithAllSourceDefaults);
        if (!stillClassName && !matchesLegacyAuto) {
            return;
        }
        if (Objects.equals(resolved, current)) {
            return;
        }
        module.setDisplayName(resolved);
        moduleMapper.updateById(module);
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

            scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                            .eq(ScanProjectToolEntity::getModuleId, source.getId()))
                    .forEach(tool -> {
                        tool.setModuleId(target.getId());
                        scanProjectToolMapper.updateById(tool);
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
     * 从 {@link ScanProjectToolEntity#getSourceLocation()} 里尝试还原 Controller 类名。
     * Scanner 约定写法：{fileName}#{ClassName}#{methodName} 或 OpenAPI 场景只有 tag/file，回退取首段。
     */
    private String resolveControllerModuleName(ScanProjectToolEntity tool) {
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
