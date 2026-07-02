package com.enterprise.ai.capability.catalog.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CapabilityToolRetrievalService {

    private static final int DEFAULT_TOP_K = 15;
    private static final float EXACT_NAME_SCORE = 0.98f;
    private static final float TEXT_MATCH_SCORE = 0.92f;

    private final ToolDefinitionMapper toolDefinitionMapper;

    public List<CapabilityToolCandidate> retrieve(String query,
                                                  CapabilityRetrievalScope scope,
                                                  int topK,
                                                  Double minScoreOverride) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        CapabilityRetrievalScope effectiveScope = scope == null
                ? new CapabilityRetrievalScope(null, null, null, true, true)
                : scope;
        if (effectiveScope.toolWhitelist() != null && effectiveScope.toolWhitelist().isEmpty()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : DEFAULT_TOP_K;
        double minScore = minScoreOverride == null ? 0.0 : Math.max(0.0, Math.min(1.0, minScoreOverride));
        List<ToolDefinitionEntity> tools = toolDefinitionMapper.selectList(new LambdaQueryWrapper<>());
        List<CapabilityToolCandidate> candidates = new ArrayList<>();
        for (ToolDefinitionEntity tool : tools) {
            if (!matchesScope(tool, effectiveScope)) {
                continue;
            }
            String text = buildText(tool);
            float score = score(tool, text, normalizedQuery);
            if (score <= 0 || score < minScore) {
                continue;
            }
            candidates.add(new CapabilityToolCandidate(
                    tool.getId(),
                    tool.getName(),
                    tool.getProjectId(),
                    tool.getModuleId(),
                    score,
                    text
            ));
        }
        return candidates.stream()
                .sorted(Comparator.comparing(CapabilityToolCandidate::score).reversed()
                        .thenComparing(candidate -> nullToEmpty(candidate.toolName())))
                .limit(limit)
                .toList();
    }

    static String buildText(ToolDefinitionEntity tool) {
        if (tool == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        append(sb, tool.getAiDescription());
        append(sb, tool.getDescription());
        append(sb, tool.getName());
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(value.trim());
    }

    private static boolean matchesScope(ToolDefinitionEntity tool, CapabilityRetrievalScope scope) {
        if (tool == null) {
            return false;
        }
        if (scope.enabledOnly() && !Boolean.TRUE.equals(tool.getEnabled())) {
            return false;
        }
        if (scope.agentVisibleOnly() && !Boolean.TRUE.equals(tool.getAgentVisible())) {
            return false;
        }
        if (scope.toolWhitelist() != null && !scope.toolWhitelist().contains(tool.getId())) {
            return false;
        }
        if (scope.projectIds() != null && !scope.projectIds().isEmpty()
                && !scope.projectIds().contains(tool.getProjectId())) {
            return false;
        }
        return scope.moduleIds() == null || scope.moduleIds().isEmpty()
                || scope.moduleIds().contains(tool.getModuleId());
    }

    private static float score(ToolDefinitionEntity tool, String text, String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String name = nullToEmpty(tool.getName()).toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && name.contains(lowerQuery)) {
            return EXACT_NAME_SCORE;
        }
        String lowerText = nullToEmpty(text).toLowerCase(Locale.ROOT);
        return lowerText.contains(lowerQuery) ? TEXT_MATCH_SCORE : 0.0f;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
