package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanProjectBlockerService {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final AgentEntryMapper agentEntryMapper;
    private final ObjectMapper objectMapper;

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
        List<AgentEntryEntity> agents = agentEntryMapper.selectList(null);

        LinkedHashSet<String> refTools = new LinkedHashSet<>();
        LinkedHashSet<String> refSkills = new LinkedHashSet<>();
        LinkedHashSet<ScanProjectBlockers.AgentRef> refAgents = new LinkedHashSet<>();

        for (AgentEntryEntity agent : agents) {
            AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(agent, objectMapper);
            Set<String> mentioned = new HashSet<>();
            mentioned.addAll(profile.getTools() == null ? List.of() : profile.getTools());
            mentioned.addAll(profile.getSkills() == null ? List.of() : profile.getSkills());
            boolean hit = false;
            for (String name : mentioned) {
                if (name == null || name.isBlank() || !ownedNames.contains(name.trim())) {
                    continue;
                }
                hit = true;
                String kind = nameToKind.get(name.trim());
                if ("SKILL".equalsIgnoreCase(kind)) {
                    refSkills.add(name.trim());
                } else {
                    refTools.add(name.trim());
                }
            }
            if (hit) {
                refAgents.add(new ScanProjectBlockers.AgentRef(agent.getId(), agent.getName()));
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
}
