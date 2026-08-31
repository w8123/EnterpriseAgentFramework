package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAutomationTargetValidatorTest {

    @Test
    void acceptsAnExactPublishedAgentVersionAndPreservesNullLegacyMetadata() {
        RuntimeAgentMapper agents = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper versions = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        agent.setKeySlug("sales-reporter");
        agent.setName("Sales reporter");
        agent.setProjectId(7L);
        agent.setProjectCode("sales");
        agent.setEnabled(true);
        RuntimeAgentConfigVersionEntity version = new RuntimeAgentConfigVersionEntity();
        version.setId(11L);
        version.setAgentId("agent-1");
        version.setVersionNo(3);
        version.setStatus("ACTIVE");
        version.setPublishedAt(null);
        when(agents.selectById("agent-1")).thenReturn(agent);
        when(versions.selectById(11L)).thenReturn(version);
        RuntimeAutomationTargetValidator validator = new RuntimeAutomationTargetValidator(
                agents,
                versions,
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
                new RuntimeAutomationJsonSupport(new ObjectMapper()));

        RuntimeAutomationTargetValidator.TargetSnapshot snapshot = validator.validate(
                new RuntimeAutomationViews.TargetCommand("AGENT", "agent-1", 11L),
                7L,
                "sales");

        assertEquals("AGENT", snapshot.type());
        assertEquals(11L, snapshot.versionId());
        assertNull(snapshot.metadata().get("publishedAt"));
        assertEquals(64, snapshot.fingerprint().length());
    }

    @Test
    void rejectsCrossProjectTargetSelection() {
        RuntimeAgentMapper agents = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper versions = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        agent.setProjectCode("finance");
        agent.setEnabled(true);
        RuntimeAgentConfigVersionEntity version = new RuntimeAgentConfigVersionEntity();
        version.setId(11L);
        version.setAgentId("agent-1");
        version.setStatus("ACTIVE");
        when(agents.selectById("agent-1")).thenReturn(agent);
        when(versions.selectById(11L)).thenReturn(version);
        RuntimeAutomationTargetValidator validator = new RuntimeAutomationTargetValidator(
                agents,
                versions,
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
                new RuntimeAutomationJsonSupport(new ObjectMapper()));

        RuntimeAutomationException failure = assertThrows(
                RuntimeAutomationException.class,
                () -> validator.validate(
                        new RuntimeAutomationViews.TargetCommand("AGENT", "agent-1", 11L),
                        null,
                        "sales"));

        assertEquals("AUTOMATION_PROJECT_MISMATCH", failure.code());
    }
}
