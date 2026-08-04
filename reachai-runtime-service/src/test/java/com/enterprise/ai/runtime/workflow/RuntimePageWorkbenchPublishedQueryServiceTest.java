package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimePageWorkbenchPublishedQueryServiceTest {

    @Test
    void returnsOnlyActivePublishedAndAttachedPageWorkflowsWithCurrentRunMetrics() {
        RuntimeWorkflowResourceBindingService bindingService =
                mock(RuntimeWorkflowResourceBindingService.class);
        RuntimeWorkflowDefinitionMapper workflowMapper =
                mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper =
                mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper =
                mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper configMapper =
                mock(RuntimeAgentConfigVersionMapper.class);

        when(bindingService.find("orders", "PAGE", null)).thenReturn(List.of(
                binding("orders.detail", "TARGET"),
                binding("orders.list", "RELATED")));
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow());
        when(versionMapper.selectOne(any())).thenReturn(version());
        when(workflowToolMapper.selectList(any())).thenReturn(List.of(tool()));
        when(agentMapper.selectById("agent-orders")).thenReturn(agent());
        when(configMapper.selectById(31L)).thenReturn(config());

        JdbcTemplate jdbc = jdbc();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO runtime_run(workflow_id, status, started_at, trace_id) VALUES (?, ?, ?, ?)",
                "wf-orders", "COMPLETED", now.minusMinutes(2), "trace-completed");
        jdbc.update(
                "INSERT INTO runtime_run(workflow_id, status, started_at, trace_id) VALUES (?, ?, ?, ?)",
                "wf-orders", "FAILED", now.minusMinutes(1), "trace-failed");
        jdbc.update("""
                        INSERT INTO runtime_trace_span(
                            trace_id, span_type, node_id, status, started_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                "trace-embed", "WORKFLOW_TOOL", "wf-orders", "SUCCESS", now);

        RuntimePageWorkbenchPublishedQueryService service =
                new RuntimePageWorkbenchPublishedQueryService(
                        bindingService,
                        workflowMapper,
                        versionMapper,
                        workflowToolMapper,
                        agentMapper,
                        configMapper,
                        jdbc);

        List<RuntimePageWorkbenchPublishedQueryService.PublishedWorkflowView> result =
                service.list("orders", null);

        assertEquals(1, result.size());
        assertEquals("orders.detail", result.get(0).pageKey());
        assertEquals("ACTIVE", result.get(0).workflowStatus());
        assertEquals(3, result.get(0).recentCallCount());
        assertEquals(66.67, result.get(0).successRate());
        assertEquals("COMPLETED", result.get(0).latestCallStatus());
        assertEquals("trace-embed", result.get(0).latestTraceId());
    }

    private JdbcTemplate jdbc() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:page_workbench_published;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE runtime_run (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP NOT NULL,
                    trace_id VARCHAR(64)
                )
                """);
        jdbc.execute("""
                CREATE TABLE runtime_trace_span (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trace_id VARCHAR(64) NOT NULL,
                    span_type VARCHAR(32) NOT NULL,
                    node_id VARCHAR(128),
                    status VARCHAR(16) NOT NULL,
                    started_at TIMESTAMP
                )
                """);
        return jdbc;
    }

    private BindingView binding(String pageKey, String role) {
        return new BindingView(
                null,
                "wf-orders",
                12L,
                "orders",
                "PAGE",
                pageKey,
                role,
                "ACTIVE",
                LocalDateTime.now());
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setKeySlug("orders-assistant");
        workflow.setName("Orders Assistant");
        workflow.setWorkflowKind("PAGE_ASSISTANT");
        workflow.setStatus("ACTIVE");
        return workflow;
    }

    private RuntimeWorkflowVersionEntity version() {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(21L);
        version.setWorkflowId("wf-orders");
        version.setVersion("v1.0.0");
        version.setStatus("ACTIVE");
        version.setPublishedBy("tester");
        version.setPublishedAt(LocalDateTime.now());
        return version;
    }

    private RuntimeAgentWorkflowToolEntity tool() {
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setAgentId("agent-orders");
        tool.setAgentConfigVersionId(31L);
        tool.setWorkflowId("wf-orders");
        tool.setWorkflowVersionId(21L);
        tool.setToolName("orders_assistant");
        tool.setRiskLevel("READ");
        tool.setEnabled(true);
        tool.setUpdatedAt(LocalDateTime.now());
        return tool;
    }

    private RuntimeAgentEntity agent() {
        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-orders");
        agent.setKeySlug("orders-copilot");
        agent.setName("Orders Copilot");
        agent.setActiveConfigVersionId(31L);
        agent.setEnabled(true);
        return agent;
    }

    private RuntimeAgentConfigVersionEntity config() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(31L);
        config.setAgentId("agent-orders");
        config.setVersionNo(3);
        config.setStatus("ACTIVE");
        return config;
    }
}
