package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
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
                new ArrayList<>(refAgents));
    }
}
