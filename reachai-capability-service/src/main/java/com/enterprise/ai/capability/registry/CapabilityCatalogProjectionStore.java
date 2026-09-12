package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/** Persists the scan catalog and invocation definition within the Registry decision transaction. */
@Component
@RequiredArgsConstructor
public class CapabilityCatalogProjectionStore {
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final CapabilityChangePolicy changePolicy;

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindUnchangedSource(ScanProjectToolEntity row, ToolDefinitionEntity tool, String sourceKey) {
        if (row != null && row.getSourceQualifiedName() == null) {
            row.setSourceQualifiedName(sourceKey);
            scanProjectToolMapper.updateById(row);
        }
        if (tool != null && tool.getSourceQualifiedName() == null) {
            tool.setSourceQualifiedName(sourceKey);
            toolDefinitionMapper.updateById(tool);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applySdkCapabilityCatalogRow(ScanProjectEntity project,
                                              CapabilityRegistration registration,
                                              String storageName,
                                              String qualifiedName,
                                              String capabilityName) {
        changePolicy.requireComplete(registration);
        String sourceLocation = "sdk:" + project.getProjectCode().trim() + ":" + capabilityName;
        ScanProjectToolEntity existing = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, project.getId())
                .eq(ScanProjectToolEntity::getSourceLocation, sourceLocation)
                .last("limit 1"));
        if (existing == null) {
            existing = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getName, storageName)
                    .last("limit 1"));
        }
        boolean inserting = existing == null;
        ScanProjectToolEntity row = inserting ? new ScanProjectToolEntity() : existing;
        if (inserting) {
            row.setProjectId(project.getId());
            row.setModuleId(null);
            row.setName(storageName);
            row.setGlobalToolDefinitionId(null);
            row.setCreateTime(LocalDateTime.now());
        }
        row.setTitle(firstText(registration.title(), registration.name()));
        row.setDescription(firstText(registration.description(), registration.title(), registration.name()));
        row.setParametersJson(writeJson(registration.parameters()));
        row.setSource("scanner");
        row.setSourceLocation(sourceLocation);
        row.setSourceQualifiedName(qualifiedName);
        row.setHttpMethod(firstText(registration.httpMethod(), "POST"));
        row.setBaseUrl(firstText(registration.baseUrl(), project.getBaseUrl()));
        row.setContextPath(registration.contextPath());
        row.setEndpointPath(registration.endpointPath());
        row.setRequestBodyType(registration.requestBodyType());
        row.setResponseType(registration.responseType());
        row.setEnabled(registration.enabled() == null || Boolean.TRUE.equals(registration.enabled()));
        row.setCapabilityMetadataJson(writeJson(changePolicy.mergeSdkMetadata(registration)));
        row.setRemovedFromSource(false);
        row.setRemovedAt(null);
        row.setUpdateTime(LocalDateTime.now());
        if (inserting) {
            scanProjectToolMapper.insert(row);
        } else {
            scanProjectToolMapper.updateById(row);
            clearSdkNullableFields(row);
        }
        ensureSdkCapabilityGlobalTool(project, row, registration, qualifiedName);
    }

    /**
     * SDK 同步的 apply 语义不能只停留在扫描目录。已应用的 SDK 能力必须同时具备可执行的
     * ToolDefinition，否则 Workflow / Agent 会把“已上报”误当成“可运行”。
     */
    private void ensureSdkCapabilityGlobalTool(ScanProjectEntity project,
                                                ScanProjectToolEntity scanTool,
                                                CapabilityRegistration registration,
                                                String qualifiedName) {
        ToolDefinitionEntity globalTool = scanTool.getGlobalToolDefinitionId() == null
                ? null
                : toolDefinitionMapper.selectById(scanTool.getGlobalToolDefinitionId());
        if (globalTool == null) {
            globalTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getQualifiedName, qualifiedName)
                    .last("limit 1"));
        }
        if (globalTool == null) {
            globalTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getProjectId, project.getId())
                    .eq(ToolDefinitionEntity::getSourceLocation, scanTool.getSourceLocation())
                    .last("limit 1"));
        }

        boolean inserting = globalTool == null;
        if (inserting) {
            globalTool = new ToolDefinitionEntity();
            globalTool.setCreateTime(LocalDateTime.now());
        }
        applySdkCapabilityToGlobalTool(project, scanTool, registration, qualifiedName, globalTool);
        globalTool.setUpdateTime(LocalDateTime.now());
        if (inserting) {
            toolDefinitionMapper.insert(globalTool);
        } else {
            toolDefinitionMapper.updateById(globalTool);
            clearSdkNullableFields(globalTool);
        }

        if (!Objects.equals(scanTool.getGlobalToolDefinitionId(), globalTool.getId())) {
            scanTool.setGlobalToolDefinitionId(globalTool.getId());
            scanTool.setUpdateTime(LocalDateTime.now());
            scanProjectToolMapper.updateById(scanTool);
        }
    }

    private void clearSdkNullableFields(ScanProjectToolEntity row) {
        scanProjectToolMapper.update(null, Wrappers.<ScanProjectToolEntity>lambdaUpdate()
                .eq(ScanProjectToolEntity::getId, row.getId())
                .set(ScanProjectToolEntity::getContextPath, row.getContextPath())
                .set(ScanProjectToolEntity::getEndpointPath, row.getEndpointPath())
                .set(ScanProjectToolEntity::getRequestBodyType, row.getRequestBodyType())
                .set(ScanProjectToolEntity::getResponseType, row.getResponseType())
                .set(ScanProjectToolEntity::getAiDescription, row.getAiDescription())
                .set(ScanProjectToolEntity::getRemovedAt, row.getRemovedAt()));
    }

    private void clearSdkNullableFields(ToolDefinitionEntity tool) {
        toolDefinitionMapper.update(null, Wrappers.<ToolDefinitionEntity>lambdaUpdate()
                .eq(ToolDefinitionEntity::getId, tool.getId())
                .set(ToolDefinitionEntity::getContextPath, tool.getContextPath())
                .set(ToolDefinitionEntity::getEndpointPath, tool.getEndpointPath())
                .set(ToolDefinitionEntity::getRequestBodyType, tool.getRequestBodyType())
                .set(ToolDefinitionEntity::getResponseType, tool.getResponseType())
                .set(ToolDefinitionEntity::getAiDescription, tool.getAiDescription()));
    }

    private void applySdkCapabilityToGlobalTool(ScanProjectEntity project,
                                                 ScanProjectToolEntity scanTool,
                                                 CapabilityRegistration registration,
                                                 String qualifiedName,
                                                 ToolDefinitionEntity globalTool) {
        globalTool.setName(scanTool.getName());
        globalTool.setTitle(scanTool.getTitle());
        globalTool.setDescription(scanTool.getDescription());
        globalTool.setAiDescription(scanTool.getAiDescription());
        globalTool.setCapabilityMetadataJson(scanTool.getCapabilityMetadataJson());
        globalTool.setParametersJson(scanTool.getParametersJson());
        globalTool.setSource("scanner");
        globalTool.setSourceLocation(scanTool.getSourceLocation());
        globalTool.setSourceQualifiedName(qualifiedName);
        globalTool.setHttpMethod(scanTool.getHttpMethod());
        globalTool.setBaseUrl(scanTool.getBaseUrl());
        globalTool.setContextPath(scanTool.getContextPath());
        globalTool.setEndpointPath(scanTool.getEndpointPath());
        globalTool.setRequestBodyType(scanTool.getRequestBodyType());
        globalTool.setResponseType(scanTool.getResponseType());
        globalTool.setProjectId(project.getId());
        globalTool.setProjectCode(project.getProjectCode());
        globalTool.setQualifiedName(qualifiedName);
        globalTool.setModuleId(scanTool.getModuleId());
        globalTool.setEnabled(Boolean.TRUE.equals(scanTool.getEnabled()));
        globalTool.setSideEffect(CapabilityChangePolicy.sideEffect(registration.sideEffect()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markCatalogRowRemoved(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        markCatalogRowRemoved(project, item, null);
    }

    private void markCatalogRowRemoved(ScanProjectEntity project,
                                       CapabilityDiffItemEntity item,
                                       ScanProjectToolEntity knownRow) {
        ScanProjectToolEntity row = knownRow == null ? findCatalogRow(project, item) : knownRow;
        if (row == null) {
            return;
        }
        row.setEnabled(false);
        row.setRemovedFromSource(true);
        row.setRemovedAt(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        scanProjectToolMapper.updateById(row);

        ToolDefinitionEntity globalTool = findGlobalTool(row, item.getQualifiedName(), item.getExistingToolId());
        if (globalTool != null && !Boolean.FALSE.equals(globalTool.getEnabled())) {
            globalTool.setEnabled(false);
            globalTool.setUpdateTime(LocalDateTime.now());
            toolDefinitionMapper.updateById(globalTool);
        }
    }

    ScanProjectToolEntity findCatalogRow(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        String capabilityName = StringUtils.hasText(item.getName()) ? item.getName().trim() : null;
        String sourceLocation = StringUtils.hasText(capabilityName)
                ? "sdk:" + project.getProjectCode().trim() + ":" + capabilityName
                : null;
        ScanProjectToolEntity row = null;
        if (StringUtils.hasText(sourceLocation)) {
            row = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getSourceLocation, sourceLocation)
                    .last("limit 1"));
        }
        if (row == null && StringUtils.hasText(item.getStorageName())) {
            row = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getName, item.getStorageName())
                    .last("limit 1"));
        }
        return row;
    }

    ToolDefinitionEntity findGlobalTool(ScanProjectToolEntity row,
                                                 String qualifiedName,
                                                 Long fallbackToolId) {
        Long toolId = row != null && row.getGlobalToolDefinitionId() != null
                ? row.getGlobalToolDefinitionId()
                : fallbackToolId;
        ToolDefinitionEntity tool = toolId == null ? null : toolDefinitionMapper.selectById(toolId);
        if (tool == null && StringUtils.hasText(qualifiedName)) {
            tool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getQualifiedName, qualifiedName)
                    .last("limit 1"));
        }
        return tool;
    }

    void assertCatalogStateUnchanged(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        if (!StringUtils.hasText(item.getBeforeStateJson())) {
            return;
        }
        JsonNode expected;
        JsonNode current;
        try {
            expected = objectMapper.readTree(item.getBeforeStateJson());
            ScanProjectToolEntity currentScan = findCatalogRow(project, item);
            ToolDefinitionEntity currentGlobal = findGlobalTool(
                    currentScan,
                    item.getQualifiedName(),
                    item.getExistingToolId());
            current = objectMapper.readTree(CapabilityCatalogStateCodec.capture(objectMapper, currentScan, currentGlobal));
        } catch (Exception ex) {
            throw new IllegalArgumentException("评审项应用前状态无法校验", ex);
        }
        if (!Objects.equals(expected, current)) {
            throw new IllegalArgumentException("能力目录在生成差异后已变化，请刷新 SDK 快照后重新评审");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreCatalogState(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        JsonNode root;
        try {
            root = objectMapper.readTree(item.getBeforeStateJson());
        } catch (Exception ex) {
            throw new IllegalArgumentException("评审项回滚状态无法解析", ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("评审项回滚状态无法解析");
        }

        ScanProjectToolEntity currentScan = findCatalogRow(project, item);
        ToolDefinitionEntity currentGlobal = findGlobalTool(currentScan, item.getQualifiedName(), item.getExistingToolId());
        JsonNode globalState = root.get("globalTool");
        if (globalState != null && globalState.isObject()) {
            ToolDefinitionEntity restored = currentGlobal == null ? new ToolDefinitionEntity() : currentGlobal;
            CapabilityCatalogStateCodec.restoreGlobalTool(restored, globalState);
            restored.setSourceQualifiedName(item.getQualifiedName());
            restored.setUpdateTime(LocalDateTime.now());
            if (currentGlobal == null || restored.getId() == null) {
                restored.setCreateTime(LocalDateTime.now());
                toolDefinitionMapper.insert(restored);
            } else {
                toolDefinitionMapper.updateById(restored);
                clearSdkNullableFields(restored);
            }
        } else if (currentGlobal != null) {
            currentGlobal.setEnabled(false);
            currentGlobal.setUpdateTime(LocalDateTime.now());
            toolDefinitionMapper.updateById(currentGlobal);
        }

        JsonNode scanState = root.get("scanTool");
        if (scanState != null && scanState.isObject()) {
            ScanProjectToolEntity restored = currentScan == null ? new ScanProjectToolEntity() : currentScan;
            CapabilityCatalogStateCodec.restoreScanTool(restored, scanState);
            restored.setSourceQualifiedName(item.getQualifiedName());
            restored.setUpdateTime(LocalDateTime.now());
            if (currentScan == null || restored.getId() == null) {
                restored.setCreateTime(LocalDateTime.now());
                scanProjectToolMapper.insert(restored);
            } else {
                scanProjectToolMapper.updateById(restored);
                clearSdkNullableFields(restored);
            }
        } else if (currentScan != null) {
            currentScan.setEnabled(false);
            currentScan.setRemovedFromSource(true);
            currentScan.setRemovedAt(LocalDateTime.now());
            currentScan.setUpdateTime(LocalDateTime.now());
            scanProjectToolMapper.updateById(currentScan);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstText(String... values) {
        String fallback = null;
        for (String value : values) {
            if (value != null) {
                fallback = value;
            }
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return fallback;
    }

}
