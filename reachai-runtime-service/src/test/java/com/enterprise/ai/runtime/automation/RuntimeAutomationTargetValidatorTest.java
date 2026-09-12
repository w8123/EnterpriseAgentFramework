package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigReader;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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
                new RuntimeAgentPublishedConfigReader(agents, versions),
                new RuntimeWorkflowExecutionReader(mock(RuntimeWorkflowDefinitionMapper.class), mock(RuntimeWorkflowVersionMapper.class)),
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
    void preservesWorkflowPublicationEvidenceAndExistingLookupFailureCodes() {
        var workflows = mock(RuntimeWorkflowDefinitionMapper.class);
        var versions = mock(RuntimeWorkflowVersionMapper.class);
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("workflow-1"); workflow.setStatus("ACTIVE");
        workflow.setProjectId(8L); workflow.setProjectCode("orders");
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(21L); version.setWorkflowId("workflow-1"); version.setStatus("RETIRED");
        version.setVersion("1.0.0"); version.setGraphSpecSnapshotJson("{}");
        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 6, 8, 0);
        version.setPublishedAt(publishedAt);
        when(workflows.selectBatchIds(any())).thenReturn(List.of(workflow));
        when(versions.selectBatchIds(any())).thenReturn(List.of(version));
        var validator = new RuntimeAutomationTargetValidator(mock(RuntimeAgentPublishedConfigQuery.class),
                new RuntimeWorkflowExecutionReader(workflows, versions),
                new RuntimeAutomationJsonSupport(new ObjectMapper().findAndRegisterModules()));
        var command = new RuntimeAutomationViews.TargetCommand("WORKFLOW", "workflow-1", 21L);

        var snapshot = validator.validate(command, 8L, "orders");

        assertEquals(21L, snapshot.versionId());
        assertEquals(publishedAt, snapshot.metadata().get("publishedAt"));
        assertEquals(64, snapshot.fingerprint().length());
        workflow.setStatus("DISABLED");
        assertEquals("AUTOMATION_WORKFLOW_UNAVAILABLE", assertThrows(RuntimeAutomationException.class,
                () -> validator.validate(command, 8L, "orders")).code());
        workflow.setStatus("ACTIVE"); version.setStatus("DRAFT");
        assertEquals("AUTOMATION_WORKFLOW_VERSION_INVALID", assertThrows(RuntimeAutomationException.class,
                () -> validator.validate(command, 8L, "orders")).code());
    }

    @Test
    void retainsAgentAvailabilityAndVersionErrorCodesAtTheAutomationBoundary() {
        var query = mock(RuntimeAgentPublishedConfigQuery.class);
        var validator = new RuntimeAutomationTargetValidator(query, mock(RuntimeWorkflowExecutionQuery.class),
                new RuntimeAutomationJsonSupport(new ObjectMapper()));
        var command = new RuntimeAutomationViews.TargetCommand("AGENT", "agent-1", 11L);
        when(query.resolve("agent-1", 11L)).thenThrow(new RuntimeAgentPublishedConfigQuery.LookupFailure(
                RuntimeAgentPublishedConfigQuery.Reason.AGENT_UNAVAILABLE, "unavailable"));
        assertEquals("AUTOMATION_AGENT_UNAVAILABLE", assertThrows(RuntimeAutomationException.class,
                () -> validator.validate(command, null, null)).code());
        org.mockito.Mockito.doThrow(new RuntimeAgentPublishedConfigQuery.LookupFailure(
                RuntimeAgentPublishedConfigQuery.Reason.VERSION_INVALID, "invalid"))
                .when(query).resolve("agent-1", 11L);
        assertEquals("AUTOMATION_AGENT_VERSION_INVALID", assertThrows(RuntimeAutomationException.class,
                () -> validator.validate(command, null, null)).code());
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
                new RuntimeAgentPublishedConfigReader(agents, versions),
                new RuntimeWorkflowExecutionReader(mock(RuntimeWorkflowDefinitionMapper.class), mock(RuntimeWorkflowVersionMapper.class)),
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
