package com.enterprise.ai.agent.platform.control.market;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.agent.CapabilityReference;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketService {

    private final MarketItemMapper marketItemMapper;
    private final AgentEntryService agentEntryService;
    private final ToolDefinitionService toolDefinitionService;
    private final ObjectMapper objectMapper;

    public List<MarketItemEntity> list(String assetKind, String status) {
        var query = Wrappers.<MarketItemEntity>lambdaQuery().orderByDesc(MarketItemEntity::getUpdatedAt);
        if (assetKind != null && !assetKind.isBlank()) {
            query.eq(MarketItemEntity::getAssetKind, assetKind);
        }
        if (status != null && !status.isBlank()) {
            query.eq(MarketItemEntity::getStatus, status);
        }
        return marketItemMapper.selectList(query);
    }

    @Transactional
    public MarketItemEntity submitAgent(String agentId, String version, String submittedBy) {
        AgentEntryEntity agent = agentEntryService.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agentId));
        if (!isMarketVisible(agent.getVisibility())) {
            throw new IllegalArgumentException("只有 SHARED / PUBLIC Agent 可以上架");
        }
        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(agent, objectMapper);
        MarketItemEntity item = baseItem("AGENT", agent.getId(), agent.getKeySlug(), agent.getProjectId(),
                agent.getProjectCode(), agent.getName(), agent.getDescription(), version, agent.getVisibility(),
                submittedBy);
        item.setDependencyManifestJson(writeJson(agentDependencyManifest(profile)));
        item.setSnapshotJson(writeJson(profile));
        marketItemMapper.insert(item);
        return item;
    }

    @Transactional
    public MarketItemEntity submitSkill(String qualifiedName, String version, String submittedBy) {
        ToolDefinitionEntity skill = toolDefinitionService.findByQualifiedName(qualifiedName)
                .or(() -> toolDefinitionService.findByName(qualifiedName))
                .orElseThrow(() -> new IllegalArgumentException("粗粒度能力不存在: " + qualifiedName));
        if (!"SKILL".equalsIgnoreCase(skill.getKind())) {
            throw new IllegalArgumentException("只能上架 kind=SKILL 的能力资产（legacy 存储类型）");
        }
        if (!isMarketVisible(skill.getVisibility())) {
            throw new IllegalArgumentException("只有 SHARED / PUBLIC 能力可以上架");
        }
        MarketItemEntity item = baseItem("SKILL", String.valueOf(skill.getId()), skill.getQualifiedName(),
                skill.getProjectId(), skill.getProjectCode(), skill.getName(), skill.getDescription(), version,
                skill.getVisibility(), submittedBy);
        item.setDependencyManifestJson(skill.getSpecJson());
        item.setSnapshotJson(writeJson(skill));
        marketItemMapper.insert(item);
        return item;
    }

    @Transactional
    public MarketItemEntity approve(Long id, String operator) {
        MarketItemEntity item = requireItem(id);
        ImportCheckResult check = checkDependencies(id);
        if (!check.missing().isEmpty()) {
            item.setStatus("REJECTED");
            item.setUpdatedAt(LocalDateTime.now());
            marketItemMapper.updateById(item);
            throw new IllegalArgumentException("依赖缺失，不能上架: " + check.missing());
        }
        item.setStatus("LISTED");
        item.setApprovedBy(operator);
        item.setApprovedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        marketItemMapper.updateById(item);
        return item;
    }

    public ImportCheckResult checkDependencies(Long id) {
        MarketItemEntity item = requireItem(id);
        if (!"AGENT".equalsIgnoreCase(item.getAssetKind())) {
            return new ImportCheckResult(List.of(), List.of());
        }
        try {
            AgentDependencyManifest manifest = objectMapper.readValue(item.getDependencyManifestJson(),
                    AgentDependencyManifest.class);
            List<String> missing = manifest.capabilities().stream()
                    .map(CapabilityReference::getQualifiedName)
                    .filter(qn -> toolDefinitionService.findByQualifiedName(qn)
                            .or(() -> toolDefinitionService.findByName(qn))
                            .isEmpty())
                    .toList();
            return new ImportCheckResult(manifest.capabilities(), missing);
        } catch (Exception ex) {
            return new ImportCheckResult(List.of(), List.of("dependencyManifestJson 无法解析"));
        }
    }

    public Map<String, Object> exportPackage(Long id) {
        MarketItemEntity item = requireItem(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marketItem", item);
        out.put("dependencyCheck", checkDependencies(id));
        out.put("snapshot", item.getSnapshotJson());
        return out;
    }

    private MarketItemEntity baseItem(String kind, String assetId, String assetKey, Long projectId, String projectCode,
                                      String name, String description, String version, String visibility,
                                      String submittedBy) {
        MarketItemEntity item = new MarketItemEntity();
        item.setAssetKind(kind);
        item.setAssetId(assetId);
        item.setAssetKey(assetKey);
        item.setProjectId(projectId);
        item.setProjectCode(projectCode);
        item.setName(name);
        item.setDescription(description);
        item.setVersion(version == null || version.isBlank() ? "1.0.0" : version);
        item.setVisibility(visibility);
        item.setStatus("PENDING_APPROVAL");
        item.setSubmittedBy(submittedBy);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        return item;
    }

    private AgentDependencyManifest agentDependencyManifest(AgentRuntimeProfile agent) {
        List<CapabilityReference> refs = new java.util.ArrayList<>();
        if (agent.getTools() != null) {
            for (String name : agent.getTools()) {
                refs.add(CapabilityReference.builder().kind("TOOL").name(name).qualifiedName(name).build());
            }
        }
        if (agent.getSkills() != null) {
            for (String name : agent.getSkills()) {
                refs.add(CapabilityReference.builder().kind("SKILL").name(name).qualifiedName(name).build());
            }
        }
        return new AgentDependencyManifest(agent.getId(), agent.getKeySlug(), refs);
    }

    private boolean isMarketVisible(String visibility) {
        return "SHARED".equalsIgnoreCase(visibility) || "PUBLIC".equalsIgnoreCase(visibility);
    }

    private MarketItemEntity requireItem(Long id) {
        MarketItemEntity item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new IllegalArgumentException("市场资产不存在: " + id);
        }
        return item;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    public record AgentDependencyManifest(String agentId, String keySlug, List<CapabilityReference> capabilities) {
    }

    public record ImportCheckResult(List<CapabilityReference> capabilities, List<String> missing) {
    }
}
