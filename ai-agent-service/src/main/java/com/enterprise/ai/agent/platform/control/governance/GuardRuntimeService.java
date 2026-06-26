package com.enterprise.ai.agent.platform.control.governance;

import com.enterprise.ai.agent.agent.CapabilityReference;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GuardRuntimeService {

    private final AgentEntryService agentEntryService;
    private final ToolDefinitionService toolDefinitionService;
    private final GuardDecisionLogService guardDecisionLogService;
    private final ObjectMapper objectMapper;

    public PreflightResult preflightAgent(String agentId, String operator) {
        AgentEntryEntity entry = agentEntryService.findById(agentId)
                .or(() -> agentEntryService.findByKeySlug(agentId))
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agentId));
        AgentRuntimeProfile agent = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        List<PreflightIssue> issues = new ArrayList<>();
        checkRefs(agent, "TOOL", agent.getTools(), issues);
        checkRefs(agent, "SKILL", agent.getSkills(), issues);
        boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", agentId);
        metadata.put("operator", operator);
        metadata.put("issueCount", issues.size());
        guardDecisionLogService.record(null, "PREFLIGHT", "AGENT", agentId,
                passed ? "ALLOW" : "DENY", passed ? "preflight passed" : "preflight failed", metadata);
        return new PreflightResult(passed, issues);
    }

    private void checkRefs(AgentRuntimeProfile agent,
                           String kind,
                           List<String> names,
                           List<PreflightIssue> issues) {
        List<CapabilityReference> effectiveRefs = legacyToRefs(kind, names);
        for (CapabilityReference ref : effectiveRefs) {
            String lookup = firstText(ref.getQualifiedName(), ref.getName());
            ToolDefinitionEntity tool = toolDefinitionService.findByQualifiedName(lookup)
                    .or(() -> toolDefinitionService.findByName(lookup))
                    .orElse(null);
            if (tool == null) {
                issues.add(new PreflightIssue("ERROR", kind, lookup, "能力不存在"));
                continue;
            }
            if (!Boolean.TRUE.equals(tool.getEnabled())) {
                issues.add(new PreflightIssue("ERROR", kind, lookup, "能力已禁用"));
            }
            if (tool.getProjectId() != null && agent.getProjectId() != null
                    && !tool.getProjectId().equals(agent.getProjectId())
                    && !"SHARED".equalsIgnoreCase(tool.getVisibility())
                    && !"PUBLIC".equalsIgnoreCase(tool.getVisibility())) {
                issues.add(new PreflightIssue("ERROR", kind, lookup, "跨项目引用未开放共享"));
            }
            if ("IRREVERSIBLE".equalsIgnoreCase(tool.getSideEffect()) && !agent.isAllowIrreversible()) {
                issues.add(new PreflightIssue("ERROR", kind, lookup, "不可逆副作用能力未授权"));
            }
            if (tool.getSideEffect() == null || tool.getSideEffect().isBlank()) {
                issues.add(new PreflightIssue("WARN", kind, lookup, "未声明 sideEffect"));
            }
        }
    }

    private List<CapabilityReference> legacyToRefs(String kind, List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .map(name -> CapabilityReference.builder().kind(kind).name(name).qualifiedName(name).build())
                .toList();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record PreflightIssue(String severity, String kind, String target, String message) {
    }

    public record PreflightResult(boolean passed, List<PreflightIssue> issues) {
    }
}
