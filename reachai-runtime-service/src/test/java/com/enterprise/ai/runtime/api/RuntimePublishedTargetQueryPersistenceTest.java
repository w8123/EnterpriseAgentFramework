package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigQuery;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigReader;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePublishedTargetQueryPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeAgentMapper agents;
    private RuntimeAgentConfigVersionMapper configs;
    private RuntimeWorkflowDefinitionMapper workflows;
    private RuntimeWorkflowVersionMapper versions;
    private RuntimeAgentPublishedConfigQuery agentQuery;
    private RuntimeWorkflowExecutionQuery workflowQuery;
    private RuntimeAgentEntity agent;
    private RuntimeAgentConfigVersionEntity config;
    private RuntimeWorkflowDefinitionEntity workflow;
    private RuntimeWorkflowVersionEntity published;
    private final LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 6, 8, 0);

    @BeforeEach
    void owningQueriesWithActualPersistence() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_workflow", "runtime_workflow_version"), RuntimeAgentMapper.class,
                RuntimeAgentConfigVersionMapper.class, RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RuntimeAgentMapper.class, () -> database.mapper(RuntimeAgentMapper.class));
        context.registerBean(RuntimeAgentConfigVersionMapper.class, () -> database.mapper(RuntimeAgentConfigVersionMapper.class));
        context.registerBean(RuntimeWorkflowDefinitionMapper.class, () -> database.mapper(RuntimeWorkflowDefinitionMapper.class));
        context.registerBean(RuntimeWorkflowVersionMapper.class, () -> database.mapper(RuntimeWorkflowVersionMapper.class));
        context.register(RuntimeAgentPublishedConfigReader.class, RuntimeWorkflowExecutionReader.class);
        context.refresh();
        agents = context.getBean(RuntimeAgentMapper.class);
        configs = context.getBean(RuntimeAgentConfigVersionMapper.class);
        workflows = context.getBean(RuntimeWorkflowDefinitionMapper.class);
        versions = context.getBean(RuntimeWorkflowVersionMapper.class);
        agentQuery = context.getBean(RuntimeAgentPublishedConfigQuery.class);
        workflowQuery = context.getBean(RuntimeWorkflowExecutionQuery.class);
        agent = new RuntimeAgentEntity();
        agent.setId("agent-1"); agent.setKeySlug("agent-one"); agent.setName("Agent One");
        agent.setProjectId(8L); agent.setProjectCode("orders"); agent.setEnabled(true);
        agent.setActiveConfigVersionId(99L);
        agents.insert(agent);
        config = new RuntimeAgentConfigVersionEntity();
        config.setId(11L); config.setAgentId("agent-1"); config.setVersionNo(1);
        config.setStatus(" archived "); config.setRuntimeType("AGENTSCOPE"); config.setPublishedAt(publishedAt);
        configs.insert(config);
        workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("workflow-1"); workflow.setKeySlug("workflow-one"); workflow.setName("Workflow One");
        workflow.setStatus("ACTIVE"); workflow.setProjectId(8L); workflow.setProjectCode("orders");
        workflow.setExecutionEngine("LANGGRAPH4J"); workflow.setGraphSpecJson("{}");
        workflows.insert(workflow);
        published = new RuntimeWorkflowVersionEntity();
        published.setId(21L); published.setWorkflowId("workflow-1"); published.setVersion("1.0.0");
        published.setStatus(" retired "); published.setPublishedAt(publishedAt);
        published.setGraphSpecSnapshotJson("{\"nodes\":[]}");
        published.setSnapshotJson("{\"defaultModelInstanceId\":null}");
        versions.insert(published);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void exactHistoricalTargetsKeepTheirPublishedIdentityAndDetachedMetadata() {
        var agentTarget = agentQuery.resolve("agent-1", 11L);
        var workflowTarget = workflowQuery.resolveOne("workflow-1", 21L);
        assertEquals(11L, agentTarget.versionId());
        assertEquals(8L, agentTarget.projectId());
        assertEquals("orders", agentTarget.projectCode());
        assertEquals(publishedAt, agentTarget.publishedAt());
        assertEquals(21L, workflowTarget.version().getId());
        assertEquals(publishedAt, workflowTarget.version().getPublishedAt());

        agent.setName("Changed Agent"); agents.updateById(agent);
        config.setRuntimeType("CHANGED"); configs.updateById(config);
        published.setGraphSpecSnapshotJson("{\"changed\":true}");
        published.setPublishedAt(publishedAt.plusDays(1)); versions.updateById(published);

        assertEquals("Agent One", agentTarget.name());
        assertEquals("AGENTSCOPE", agentTarget.runtimeType());
        assertEquals("{\"nodes\":[]}", workflowTarget.version().getGraphSpecSnapshotJson());
        assertEquals(publishedAt, workflowTarget.version().getPublishedAt());
        assertEquals("Changed Agent", agentQuery.resolve("agent-1", 11L).name());
    }

    @Test
    void agentLookupSeparatesDisabledOwnersFromInvalidPublishedVersions() {
        agent.setEnabled(false); agents.updateById(agent);
        assertEquals(RuntimeAgentPublishedConfigQuery.Reason.AGENT_UNAVAILABLE,
                assertThrows(RuntimeAgentPublishedConfigQuery.LookupFailure.class,
                        () -> agentQuery.resolve("agent-1", 11L)).reason());
        agent.setEnabled(true); agents.updateById(agent);
        for (String status : List.of("DRAFT", "UNKNOWN")) {
            config.setStatus(status); configs.updateById(config);
            assertAgentVersionInvalid(11L);
        }
        config.setStatus("ACTIVE"); config.setAgentId("another-agent"); configs.updateById(config);
        assertAgentVersionInvalid(11L);
        assertAgentVersionInvalid(null);
        assertAgentVersionInvalid(999L);
    }

    @Test
    void workflowLookupRequiresAnActiveOwnerAndItsExactExecutablePublishedVersion() {
        for (String id : new String[]{null, "", "missing-workflow"}) {
            assertEquals(RuntimeWorkflowExecutionQuery.Reason.WORKFLOW_UNAVAILABLE,
                    assertThrows(RuntimeWorkflowExecutionQuery.LookupFailure.class,
                            () -> workflowQuery.resolveOne(id, 21L)).reason());
        }
        workflow.setStatus("DISABLED"); workflows.updateById(workflow);
        assertEquals(RuntimeWorkflowExecutionQuery.Reason.WORKFLOW_UNAVAILABLE,
                assertThrows(RuntimeWorkflowExecutionQuery.LookupFailure.class,
                        () -> workflowQuery.resolveOne("workflow-1", 21L)).reason());
        workflow.setStatus("ACTIVE"); workflows.updateById(workflow);
        for (String status : List.of("DRAFT", "UNKNOWN")) {
            published.setStatus(status); versions.updateById(published);
            assertWorkflowVersionInvalid(21L);
        }
        published.setStatus("ACTIVE"); published.setGraphSpecSnapshotJson(""); versions.updateById(published);
        assertWorkflowVersionInvalid(21L);
        published.setGraphSpecSnapshotJson("{}"); published.setWorkflowId("another-workflow"); versions.updateById(published);
        assertWorkflowVersionInvalid(21L);
        assertWorkflowVersionInvalid(null);
        assertWorkflowVersionInvalid(999L);
    }

    private void assertAgentVersionInvalid(Long id) {
        assertEquals(RuntimeAgentPublishedConfigQuery.Reason.VERSION_INVALID,
                assertThrows(RuntimeAgentPublishedConfigQuery.LookupFailure.class,
                        () -> agentQuery.resolve("agent-1", id)).reason());
    }

    private void assertWorkflowVersionInvalid(Long id) {
        assertEquals(RuntimeWorkflowExecutionQuery.Reason.VERSION_INVALID,
                assertThrows(RuntimeWorkflowExecutionQuery.LookupFailure.class,
                        () -> workflowQuery.resolveOne("workflow-1", id)).reason());
    }
}
