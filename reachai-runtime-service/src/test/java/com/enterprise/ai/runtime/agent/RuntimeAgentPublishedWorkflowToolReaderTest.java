package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAgentPublishedWorkflowToolReaderTest {

    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeAgentMapper agents;
    private RuntimeAgentConfigVersionMapper configs;
    private RuntimeAgentWorkflowToolMapper tools;
    private RuntimeAgentPublishedWorkflowToolQuery query;

    @BeforeEach
    void owningTablesAndQuery() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version", "runtime_agent_workflow_tool"),
                RuntimeAgentMapper.class, RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RuntimeAgentMapper.class, () -> database.mapper(RuntimeAgentMapper.class));
        context.registerBean(RuntimeAgentConfigVersionMapper.class, () -> database.mapper(RuntimeAgentConfigVersionMapper.class));
        context.registerBean(RuntimeAgentWorkflowToolMapper.class, () -> database.mapper(RuntimeAgentWorkflowToolMapper.class));
        context.register(RuntimeAgentPublishedWorkflowToolReader.class);
        context.refresh();
        agents = context.getBean(RuntimeAgentMapper.class);
        configs = context.getBean(RuntimeAgentConfigVersionMapper.class);
        tools = context.getBean(RuntimeAgentWorkflowToolMapper.class);
        query = context.getBean(RuntimeAgentPublishedWorkflowToolQuery.class);
        insertBinding("agent-1", 31L, "agent-1", LocalDateTime.now().minusHours(1));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void skipsANewerBindingWhoseConfigBelongsToAnotherAgent() {
        insertBinding("wrong-owner", 32L, "another-agent", LocalDateTime.now());

        var binding = query.findPublishedBinding("wf-1", 21L).orElseThrow();

        assertEquals("agent-1", binding.agentId());
        assertEquals(31L, binding.agentConfigVersionId());
        assertEquals("model-agent-1", binding.modelInstanceId());
        var config = configs.selectById(31L);
        config.setModelInstanceId("later-model");
        configs.updateById(config);
        assertEquals("model-agent-1", binding.modelInstanceId());
        assertEquals("later-model", query.findPublishedBinding("wf-1", 21L).orElseThrow().modelInstanceId());
    }

    @Test
    void requiresAnEnabledAgentToolAndItsExactActiveConfiguration() {
        var tool = tools.selectById(31L);
        tool.setEnabled(false);
        tools.updateById(tool);
        assertTrue(query.findPublishedBinding("wf-1", 21L).isEmpty());
        tool.setEnabled(true);
        tools.updateById(tool);
        var agent = agents.selectById("agent-1");
        agent.setEnabled(false);
        agents.updateById(agent);
        assertTrue(query.findPublishedBinding("wf-1", 21L).isEmpty());
        agent.setEnabled(true);
        agent.setActiveConfigVersionId(99L);
        agents.updateById(agent);
        assertTrue(query.findPublishedBinding("wf-1", 21L).isEmpty());
        agent.setActiveConfigVersionId(31L);
        agents.updateById(agent);
        var config = configs.selectById(31L);
        config.setStatus("DRAFT");
        configs.updateById(config);
        assertTrue(query.findPublishedBinding("wf-1", 21L).isEmpty());
        config.setStatus("ACTIVE");
        configs.updateById(config);
        assertTrue(query.findPublishedBinding("wf-1", 21L).isPresent());
        assertTrue(query.findPublishedBinding("wf-other", 21L).isEmpty());
        assertTrue(query.findPublishedBinding("wf-1", 22L).isEmpty());
        assertTrue(query.findPublishedBinding("wf-1", null).isEmpty());
        assertTrue(query.findPublishedBinding(" ", 21L).isEmpty());
    }

    private void insertBinding(String agentId, Long configId, String configOwner, LocalDateTime updatedAt) {
        var agent = new RuntimeAgentEntity();
        agent.setId(agentId);
        agent.setKeySlug(agentId);
        agent.setName(agentId);
        agent.setEnabled(true);
        agent.setActiveConfigVersionId(configId);
        agents.insert(agent);
        var config = new RuntimeAgentConfigVersionEntity();
        config.setId(configId);
        config.setAgentId(configOwner);
        config.setVersionNo(1);
        config.setStatus("ACTIVE");
        config.setModelInstanceId("model-" + agentId);
        configs.insert(config);
        var tool = new RuntimeAgentWorkflowToolEntity();
        tool.setId(configId);
        tool.setAgentId(agentId);
        tool.setAgentConfigVersionId(configId);
        tool.setWorkflowId("wf-1");
        tool.setWorkflowVersionId(21L);
        tool.setToolName("workflow-" + agentId);
        tool.setEnabled(true);
        tool.setUpdatedAt(updatedAt);
        tools.insert(tool);
    }
}
