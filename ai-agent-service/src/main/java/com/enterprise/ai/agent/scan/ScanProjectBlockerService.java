package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.agent.persist.AgentDefinitionEntity;
import com.enterprise.ai.agent.agent.persist.AgentDefinitionMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 检测扫描项目对应的全局 Tool/Skill 是否仍被 Agent 白名单引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanProjectBlockerService {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final AgentDefinitionMapper agentDefinitionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 分析给定扫描项目：若无全局 {@code tool_definition.project_id} 记录，或无任何 Agent 引用，则 {@code blocked=false}。
     */
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
        Map<String, String> nameToKind = new HashMap<>();
        for (ToolDefinitionEntity t : owned) {
            if (t.getName() != null && !t.getName().isBlank()) {
                nameToKind.put(t.getName().trim(), normalizeKind(t.getKind()));
            }
        }
        if (nameToKind.isEmpty()) {
            return ScanProjectBlockers.empty();
        }
        Set<String> ownedNames = nameToKind.keySet();
        List<AgentDefinitionEntity> agents = agentDefinitionMapper.selectList(null);

        LinkedHashSet<String> refTools = new LinkedHashSet<>();
        LinkedHashSet<String> refSkills = new LinkedHashSet<>();
        LinkedHashSet<ScanProjectBlockers.AgentRef> refAgents = new LinkedHashSet<>();

        for (AgentDefinitionEntity a : agents) {
            Set<String> mentioned = new HashSet<>();
            mentioned.addAll(parseStringList(a.getToolsJson()));
            mentioned.addAll(parseStringList(a.getSkillsJson()));
            boolean hit = false;
            for (String raw : mentioned) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String name = raw.trim();
                if (!ownedNames.contains(name)) {
                    continue;
                }
                hit = true;
                String kind = nameToKind.get(name);
                if ("SKILL".equalsIgnoreCase(kind)) {
                    refSkills.add(name);
                } else {
                    refTools.add(name);
                }
            }
            if (hit) {
                refAgents.add(new ScanProjectBlockers.AgentRef(a.getId(), a.getName()));
            }
        }

        boolean blocked = !refAgents.isEmpty();
        return new ScanProjectBlockers(
                blocked,
                new ArrayList<>(refTools),
                new ArrayList<>(refSkills),
                new ArrayList<>(refAgents));
    }

    private static String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return "TOOL";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[ScanProjectBlocker] 解析 Agent JSON 列表失败: {}", e.toString());
            return List.of();
        }
    }
}
