package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only facts used by a capability retrofit task: for each declared name, whether the project
 * already has an accepted business method, whether a change still waits for review, or nothing yet.
 * Names are matched only inside the given project, never across projects.
 */
@RestController
@RequiredArgsConstructor
public class CapabilityProjectBusinessMethodStatesInternalController {

    static final int MAX_NAMES = 50;

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;
    private final BusinessMethodAssetMapper assets;
    private final CapabilityDiffItemMapper differences;

    @GetMapping("/internal/capability/projects/by-id/{projectId}/business-method-states")
    public ResponseEntity<?> states(@PathVariable("projectId") Long projectId,
                                    @RequestParam(value = "names", required = false) List<String> names) {
        try {
            scanProjectCatalogService.get(projectId);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_PROJECT_NOT_FOUND");
            body.put("projectId", projectId);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (StringUtils.hasText(name)) wanted.add(name.trim());
            }
        }
        if (wanted.size() > MAX_NAMES) {
            return ResponseEntity.badRequest().body(Map.of("message", "names must contain at most " + MAX_NAMES + " entries"));
        }
        List<BusinessMethodAssetEntity> projectAssets = wanted.isEmpty() ? List.of()
                : assets.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .eq(BusinessMethodAssetEntity::getProjectId, projectId));
        List<CapabilityDiffItemEntity> pending = wanted.isEmpty() ? List.of()
                : differences.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getProjectId, projectId)
                .eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : wanted) {
            BusinessMethodAssetEntity asset = projectAssets.stream()
                    .filter(candidate -> matches(name, candidate.getMethodCode(), candidate.getQualifiedName(),
                            candidate.getInvocationName()))
                    .findFirst().orElse(null);
            CapabilityDiffItemEntity change = pending.stream()
                    .filter(candidate -> matches(name, candidate.getName(), candidate.getQualifiedName(),
                            candidate.getStorageName()))
                    .findFirst().orElse(null);
            rows.add(row(name, asset, change));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "capability.project-business-method-states.v1");
        body.put("projectId", projectId);
        body.put("items", rows);
        return ResponseEntity.ok(body);
    }

    static Map<String, Object> row(String name, BusinessMethodAssetEntity asset, CapabilityDiffItemEntity change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        boolean accepted = asset != null && asset.getAcceptedRevisionId() != null;
        boolean enabled = asset != null && !Boolean.FALSE.equals(asset.getEnabled());
        String state;
        if (change != null) {
            state = "PENDING_REVIEW";
        } else if (accepted && enabled) {
            state = "ACCEPTED";
        } else if (accepted) {
            state = "DISABLED";
        } else {
            state = "NOT_SYNCED";
        }
        row.put("state", state);
        row.put("qualifiedName", asset != null ? asset.getQualifiedName() : change == null ? null : change.getQualifiedName());
        row.put("invocationName", asset == null ? null : asset.getInvocationName());
        row.put("title", asset == null ? null : asset.getTitle());
        row.put("sideEffect", asset == null ? null : asset.getSideEffect());
        row.put("pendingChangeId", change == null ? null : change.getId());
        row.put("pendingChangeType", change == null ? null : change.getChangeType());
        return row;
    }

    static boolean matches(String name, String... values) {
        for (String value : values) {
            if (value == null) continue;
            if (Objects.equals(name, value)) return true;
            int colon = value.lastIndexOf(':');
            if (colon >= 0 && Objects.equals(name, value.substring(colon + 1))) return true;
        }
        return false;
    }
}
