package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimePolicyTest {

    @Test
    void defaultPolicyEnablesLocalRuntimesOnly() {
        AgentRuntimePolicy policy = new AgentRuntimePolicy(new AgentRuntimePolicyProperties());

        AgentRuntimeCapability langGraph4j = policy.apply(langGraph4jCapability());
        AgentRuntimeCapability openAiAgents = policy.apply(openAiAgentsCapability());

        assertTrue(langGraph4j.isAvailable());
        assertFalse(openAiAgents.isAvailable());
        assertTrue(openAiAgents.getUnavailableReason().contains("enabled-runtimes"));
    }

    @Test
    void rejectsCloudRuntimeWhenCloudExecutionIsDisabled() {
        AgentRuntimePolicyProperties props = new AgentRuntimePolicyProperties();
        props.setEnabledRuntimes(List.of(AgentRuntimeAdapter.OPENAI_AGENTS_RUNTIME_TYPE));
        AgentRuntimePolicy policy = new AgentRuntimePolicy(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> policy.validate(openAiAgentsCapability(), request("CRM")));

        assertTrue(ex.getMessage().contains("云端执行未开启"));
    }

    @Test
    void allowsCloudRuntimeForWhitelistedProject() {
        AgentRuntimePolicyProperties props = new AgentRuntimePolicyProperties();
        props.setEnabledRuntimes(List.of(AgentRuntimeAdapter.OPENAI_AGENTS_RUNTIME_TYPE));
        props.setAllowCloudExecution(true);
        props.setCloudAllowedProjectCodes(List.of("CRM"));
        AgentRuntimePolicy policy = new AgentRuntimePolicy(props);

        assertDoesNotThrow(() -> policy.validate(openAiAgentsCapability(), request("crm")));
    }

    @Test
    void rejectsCodeWorkspaceRuntimeUntilProjectIsWhitelisted() {
        AgentRuntimePolicyProperties props = new AgentRuntimePolicyProperties();
        props.setEnabledRuntimes(List.of(AgentRuntimeAdapter.CURSOR_CODE_AGENT_RUNTIME_TYPE));
        props.setAllowCloudExecution(true);
        props.setCloudAllowedProjectCodes(List.of("CRM"));
        props.setAllowCodeWorkspace(true);
        props.setCodeWorkspaceAllowedProjectCodes(List.of("CORE"));
        AgentRuntimePolicy policy = new AgentRuntimePolicy(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> policy.validate(cursorCapability(), request("CRM")));

        assertTrue(ex.getMessage().contains("代码工作区白名单"));
    }

    private AgentRuntimeRequest request(String projectCode) {
        return AgentRuntimeRequest.builder()
                .agentRuntimeProfile(AgentRuntimeProfile.builder()
                        .projectCode(projectCode)
                        .build())
                .build();
    }

    private AgentRuntimeCapability openAiAgentsCapability() {
        return AgentRuntimeCapability.builder()
                .runtimeType(AgentRuntimeAdapter.OPENAI_AGENTS_RUNTIME_TYPE)
                .displayName("OpenAI Agents")
                .available(true)
                .supportsCloudExecution(true)
                .build();
    }

    private AgentRuntimeCapability langGraph4jCapability() {
        return AgentRuntimeCapability.builder()
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .displayName("LangGraph4j")
                .available(true)
                .build();
    }

    private AgentRuntimeCapability cursorCapability() {
        return AgentRuntimeCapability.builder()
                .runtimeType(AgentRuntimeAdapter.CURSOR_CODE_AGENT_RUNTIME_TYPE)
                .displayName("Cursor Code Agent")
                .available(true)
                .supportsCodeWorkspace(true)
                .supportsCloudExecution(true)
                .build();
    }
}
