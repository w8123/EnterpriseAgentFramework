package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapabilityScanProjectBlockerService {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ScanProjectAgentReferenceReader agentReferenceReader;
    private final BusinessMethodAssetMapper businessMethodAssetMapper;
    private final HttpApiAssetMapper httpApiAssetMapper;

    /** Deleting a project must never orphan source assets or their immutable accepted revisions. */
    public ScanProjectBlockers analyzeDeletion(Long projectId) {
        if (projectId == null) return ScanProjectBlockers.empty();
        ScanProjectBlockers references = analyze(projectId);
        List<ScanProjectBlockers.AssetRef> assets = new ArrayList<>();
        for (BusinessMethodAssetEntity method : businessMethodAssetMapper.selectList(
                Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                        .eq(BusinessMethodAssetEntity::getProjectId, projectId)
                        .orderByAsc(BusinessMethodAssetEntity::getId))) {
            requireOwnedAsset(projectId, method.getProjectId(), method.getId(), method.getQualifiedName());
            assets.add(new ScanProjectBlockers.AssetRef("BUSINESS_METHOD", method.getId(),
                    method.getQualifiedName(), method.getTitle()));
        }
        for (HttpApiAssetEntity api : httpApiAssetMapper.selectList(
                Wrappers.<HttpApiAssetEntity>lambdaQuery()
                        .eq(HttpApiAssetEntity::getProjectId, projectId)
                        .orderByAsc(HttpApiAssetEntity::getId))) {
            requireOwnedAsset(projectId, api.getProjectId(), api.getId(), api.getQualifiedName());
            assets.add(new ScanProjectBlockers.AssetRef("HTTP_API", api.getId(),
                    api.getQualifiedName(), api.getHttpMethod() + " " + api.getRouteTemplate()));
        }
        return new ScanProjectBlockers(references.blocked() || !assets.isEmpty(),
                references.tools(), references.agents(), List.copyOf(assets));
    }

    private static void requireOwnedAsset(Long expectedProject, Long projectId, Long assetId, String qualifiedName) {
        if (!expectedProject.equals(projectId) || assetId == null || assetId <= 0
                || qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalStateException("Project asset ownership is incomplete");
        }
    }

    public ScanProjectBlockers analyze(Long projectId) {
        if (projectId == null) {
            return ScanProjectBlockers.empty();
        }
        List<ToolDefinitionEntity> owned = toolDefinitionMapper.selectList(
                Wrappers.<ToolDefinitionEntity>lambdaQuery()
                        .eq(ToolDefinitionEntity::getProjectId, projectId));
        if (owned.isEmpty()) {
            return ScanProjectBlockers.empty();
        }
        Set<String> ownedNames = new HashSet<>();
        for (ToolDefinitionEntity tool : owned) {
            if (tool.getName() != null && !tool.getName().isBlank()) {
                ownedNames.add(tool.getName().trim());
            }
        }
        if (ownedNames.isEmpty()) {
            return ScanProjectBlockers.empty();
        }

        LinkedHashSet<String> refTools = new LinkedHashSet<>();
        LinkedHashSet<ScanProjectBlockers.AgentRef> refAgents = new LinkedHashSet<>();

        for (ScanProjectAgentReferenceReader.AgentToolReference agent : agentReferenceReader.listAgentToolReferences()) {
            boolean hit = false;
            for (String name : agent.tools() == null ? List.<String>of() : agent.tools()) {
                if (name == null || name.isBlank() || !ownedNames.contains(name.trim())) {
                    continue;
                }
                hit = true;
                refTools.add(name.trim());
            }
            if (hit) {
                refAgents.add(new ScanProjectBlockers.AgentRef(agent.agentId(), agent.agentName()));
            }
        }
        return new ScanProjectBlockers(
                !refAgents.isEmpty(),
                new ArrayList<>(refTools),
                new ArrayList<>(refAgents),
                List.of());
    }
}
