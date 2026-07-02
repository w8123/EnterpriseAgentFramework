package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapabilityScanProjectBlockerService {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ScanProjectAgentReferenceReader agentReferenceReader;

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
        for (ToolDefinitionEntity tool : owned) {
            if (tool.getName() != null && !tool.getName().isBlank()) {
                nameToKind.put(tool.getName().trim(), normalizeKind(tool.getKind()));
            }
        }
        if (nameToKind.isEmpty()) {
            return ScanProjectBlockers.empty();
        }

        Set<String> ownedNames = nameToKind.keySet();
        LinkedHashSet<String> refTools = new LinkedHashSet<>();
        LinkedHashSet<String> refSkills = new LinkedHashSet<>();
        LinkedHashSet<ScanProjectBlockers.AgentRef> refAgents = new LinkedHashSet<>();

        for (ScanProjectAgentReferenceReader.AgentToolReference agent : agentReferenceReader.listAgentToolReferences()) {
            Set<String> mentioned = new HashSet<>();
            mentioned.addAll(agent.tools() == null ? List.of() : agent.tools());
            mentioned.addAll(agent.skills() == null ? List.of() : agent.skills());
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
                refAgents.add(new ScanProjectBlockers.AgentRef(agent.agentId(), agent.agentName()));
            }
        }
        return new ScanProjectBlockers(
                !refAgents.isEmpty(),
                new ArrayList<>(refTools),
                new ArrayList<>(refSkills),
                new ArrayList<>(refAgents));
    }

    private String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return "TOOL";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
