package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.acl.ToolAclDecision;
import com.enterprise.ai.agent.acl.ToolAclService;
import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.tool.governance.ToolRateLimiter;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tool.retrieval.ToolRetrievalService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFactoryToolAclAuditTest {

    @Test
    void createToolkitRecordsAuditWhenToolAclDeniesAssembly() {
        AiTool tool = new FakeTool("delete_order");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        ToolAclService toolAclService = mock(ToolAclService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        LLMConfig llmConfig = new LLMConfig();
        llmConfig.setMaxSteps(5);

        when(toolDefinitionService.isAgentCallable("delete_order")).thenReturn(true);
        when(toolAclService.decide(List.of("auditor"), "delete_order", false, "bzsdk"))
                .thenReturn(ToolAclDecision.DENY_NO_MATCH);

        AgentFactory factory = new AgentFactory(
                mock(AgentScopeConfig.class),
                toolRegistry,
                toolDefinitionService,
                mock(ToolDefinitionMapper.class),
                mock(ToolRetrievalService.class),
                mock(ToolCallLogService.class),
                new ToolRetrievalProperties(),
                new ObjectMapper(),
                toolAclService,
                mock(ToolRateLimiter.class),
                guardDecisionLogService,
                llmConfig);

        ToolExecutionContext context = ToolExecutionContext.builder()
                .traceId("trace-001")
                .sessionId("session-001")
                .projectCode("bzsdk")
                .agentId("agent-001")
                .agentName("订单助手")
                .externalUserId("u-001")
                .roles(List.of("auditor"))
                .build();

        factory.createToolkit(List.of("delete_order"), context);

        verify(guardDecisionLogService).record(
                eq("trace-001"),
                eq("TOOL_ACL"),
                eq("TOOL"),
                eq("delete_order"),
                eq("DENY"),
                eq("DENY_NO_MATCH"),
                any(Map.class));
    }

    private record FakeTool(String name) implements AiTool {
        @Override
        public String description() {
            return "fake tool";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of();
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return Map.of();
        }
    }
}
