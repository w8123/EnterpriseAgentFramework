package com.enterprise.ai.control.market;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ControlMarketService {

    private final ControlMarketItemMapper marketItemMapper;
    private final RuntimeProxyClient runtimeClient;
    private final CapabilityProxyClient capabilityClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ControlMarketItemEntity> list(String assetKind, String status) {
        var query = Wrappers.<ControlMarketItemEntity>lambdaQuery()
                .eq(StringUtils.hasText(assetKind), ControlMarketItemEntity::getAssetKind, assetKind)
                .eq(StringUtils.hasText(status), ControlMarketItemEntity::getStatus, status)
                .orderByDesc(ControlMarketItemEntity::getUpdatedAt);
        return marketItemMapper.selectList(query);
    }

    @Transactional
    public ControlMarketItemEntity submitAgent(String agentId, String version, String submittedBy) {
        Map<String, Object> agent = requireMap(runtimeClient.getAgent(agentId), "Agent not found: " + agentId);
        String visibility = firstText(text(agent.get("visibility")), "PRIVATE");
        if (!isMarketVisible(visibility)) {
            throw new IllegalArgumentException("Only SHARED / PUBLIC Agent can be listed");
        }
        ControlMarketItemEntity item = baseItem(
                "AGENT",
                firstText(text(agent.get("id")), agentId),
                firstText(text(agent.get("keySlug")), text(agent.get("key"))),
                longValue(agent.get("projectId")),
                text(agent.get("projectCode")),
                firstText(text(agent.get("name")), agentId),
                text(agent.get("description")),
                version,
                visibility,
                submittedBy);
        item.setDependencyManifestJson(toJson(agentDependencyManifest(agent)));
        item.setSnapshotJson(toJson(agent));
        marketItemMapper.insert(item);
        return item;
    }

    @Transactional
    public ControlMarketItemEntity submitSkill(String qualifiedName, String version, String submittedBy) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("qualifiedName is required");
        }
        Map<String, Object> skill = requireMap(capabilityClient.getToolDefinition(qualifiedName),
                "Capability not found: " + qualifiedName);
        if (!"SKILL".equalsIgnoreCase(text(skill.get("kind")))) {
            throw new IllegalArgumentException("Only kind=SKILL capability assets can be listed");
        }
        String visibility = firstText(text(skill.get("visibility")), "PRIVATE");
        if (!isMarketVisible(visibility)) {
            throw new IllegalArgumentException("Only SHARED / PUBLIC capability assets can be listed");
        }
        ControlMarketItemEntity item = baseItem(
                "SKILL",
                firstText(text(skill.get("id")), qualifiedName),
                firstText(text(skill.get("qualifiedName")), qualifiedName),
                longValue(skill.get("projectId")),
                text(skill.get("projectCode")),
                firstText(text(skill.get("name")), qualifiedName),
                text(skill.get("description")),
                version,
                visibility,
                submittedBy);
        item.setDependencyManifestJson(text(skill.get("specJson")));
        item.setSnapshotJson(toJson(skill));
        marketItemMapper.insert(item);
        return item;
    }

    @Transactional
    public ControlMarketItemEntity approve(Long id, String operator) {
        ControlMarketItemEntity item = requireItem(id);
        ImportCheckResult check = checkDependencies(id);
        LocalDateTime now = LocalDateTime.now();
        if (!check.missing().isEmpty()) {
            item.setStatus("REJECTED");
            item.setUpdatedAt(now);
            marketItemMapper.updateById(item);
            throw new IllegalArgumentException("Dependencies are missing: " + check.missing());
        }
        item.setStatus("LISTED");
        item.setApprovedBy(operator);
        item.setApprovedAt(now);
        item.setUpdatedAt(now);
        marketItemMapper.updateById(item);
        return item;
    }

    public ImportCheckResult checkDependencies(Long id) {
        ControlMarketItemEntity item = requireItem(id);
        List<Map<String, Object>> capabilities = capabilitiesFromManifest(item.getDependencyManifestJson());
        if (!"AGENT".equalsIgnoreCase(item.getAssetKind())) {
            return new ImportCheckResult(capabilities, List.of());
        }
        List<String> missing = capabilities.stream()
                .map(capability -> text(capability.get("qualifiedName")))
                .filter(StringUtils::hasText)
                .filter(name -> !capabilityExists(name))
                .toList();
        return new ImportCheckResult(capabilities, missing);
    }

    public Map<String, Object> exportPackage(Long id) {
        ControlMarketItemEntity item = requireItem(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marketItem", item);
        body.put("dependencyCheck", checkDependencies(id));
        body.put("snapshot", item.getSnapshotJson());
        return body;
    }

    private ControlMarketItemEntity baseItem(String kind,
                                             String assetId,
                                             String assetKey,
                                             Long projectId,
                                             String projectCode,
                                             String name,
                                             String description,
                                             String version,
                                             String visibility,
                                             String submittedBy) {
        LocalDateTime now = LocalDateTime.now();
        ControlMarketItemEntity item = new ControlMarketItemEntity();
        item.setAssetKind(kind);
        item.setAssetId(assetId);
        item.setAssetKey(assetKey);
        item.setProjectId(projectId);
        item.setProjectCode(projectCode);
        item.setName(name);
        item.setDescription(description);
        item.setVersion(StringUtils.hasText(version) ? version : "1.0.0");
        item.setVisibility(visibility);
        item.setStatus("PENDING_APPROVAL");
        item.setSubmittedBy(submittedBy);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return item;
    }

    private Map<String, Object> agentDependencyManifest(Map<String, Object> agent) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", agent.get("id"));
        manifest.put("keySlug", firstText(text(agent.get("keySlug")), text(agent.get("key"))));
        manifest.put("capabilities", capabilityRefs(agent));
        return manifest;
    }

    private List<Map<String, Object>> capabilityRefs(Map<String, Object> agent) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Object source : new Object[] {agent.get("tools"), agent.get("skills")}) {
            if (!(source instanceof Collection<?> collection)) {
                continue;
            }
            for (Object value : collection) {
                String name = String.valueOf(value);
                if (StringUtils.hasText(name)) {
                    refs.add(Map.of("qualifiedName", name, "name", name));
                }
            }
        }
        return refs;
    }

    private List<Map<String, Object>> capabilitiesFromManifest(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("capabilities") instanceof Collection<?> collection) {
                return collection.stream()
                        .filter(Map.class::isInstance)
                        .map(value -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) value;
                            return typed;
                        })
                        .toList();
            }
        } catch (JsonProcessingException ignored) {
            return List.of(Map.of("qualifiedName", "dependencyManifestJson parse failed"));
        }
        return List.of();
    }

    private boolean capabilityExists(String qualifiedName) {
        ResponseEntity<Map<String, Object>> response = capabilityClient.getToolDefinition(qualifiedName);
        return response.getStatusCode().is2xxSuccessful() && response.getBody() != null;
    }

    private ControlMarketItemEntity requireItem(Long id) {
        ControlMarketItemEntity item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new IllegalArgumentException("Market item not found: " + id);
        }
        return item;
    }

    private boolean isMarketVisible(String visibility) {
        return "SHARED".equalsIgnoreCase(visibility) || "PUBLIC".equalsIgnoreCase(visibility);
    }

    private Map<String, Object> requireMap(ResponseEntity<?> response, String message) {
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException(message);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) body;
        return typed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Long.parseLong(String.valueOf(value));
        }
        return null;
    }

    public record ImportCheckResult(List<Map<String, Object>> capabilities, List<String> missing) {
    }
}
