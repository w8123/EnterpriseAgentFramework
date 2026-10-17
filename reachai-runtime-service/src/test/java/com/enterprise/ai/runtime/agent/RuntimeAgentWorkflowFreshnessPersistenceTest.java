package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Agent persistence; the Workflow catalog is a test double whose current versions can move. */
class RuntimeAgentWorkflowFreshnessPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeAgentConfigService configs;
    private RuntimeAgentWorkflowFreshnessService freshness;
    private RuntimeAgentMapper agents;
    private RuntimeAgentConfigVersionMapper versions;
    private final Map<String, Long> currentVersions = new HashMap<>();
    private final Map<Long, String> riskFloors = new HashMap<>();
    private Long initialActiveId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool", "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding"),
                RuntimeAgentMapper.class, RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class,
                RuntimeAgentSkillBindingMapper.class, RuntimeAgentRemoteBindingMapper.class);
        agents = database.mapper(RuntimeAgentMapper.class);
        versions = database.mapper(RuntimeAgentConfigVersionMapper.class);
        var tools = database.mapper(RuntimeAgentWorkflowToolMapper.class);
        var catalog = mock(RuntimeWorkflowToolCatalogQuery.class);
        when(catalog.current(anyList())).thenAnswer(call -> {
            Map<String, RuntimeWorkflowToolCatalogQuery.Entry> result = new LinkedHashMap<>();
            for (String id : (List<String>) call.getArgument(0)) result.put(id, entry(id, currentVersions.getOrDefault(id, 100L)));
            return result;
        });
        when(catalog.pinned(anyList())).thenAnswer(call -> {
            Map<RuntimeWorkflowToolCatalogQuery.Reference, RuntimeWorkflowToolCatalogQuery.Entry> result = new LinkedHashMap<>();
            for (var ref : (List<RuntimeWorkflowToolCatalogQuery.Reference>) call.getArgument(0)) {
                result.put(ref, entry(ref.workflowId(), ref.versionId()));
            }
            return result;
        });
        configs = transactional(new RuntimeAgentConfigService(versions, tools,
                database.mapper(RuntimeAgentSkillBindingMapper.class), database.mapper(RuntimeAgentRemoteBindingMapper.class),
                agents, catalog, new ObjectMapper().findAndRegisterModules()));
        freshness = new RuntimeAgentWorkflowFreshnessService(agents, versions, tools, configs, catalog);
        database.jdbc().update("""
                INSERT INTO runtime_agent (id, key_slug, name, project_id, project_code, enabled)
                VALUES ('agent-1', 'orders-agent', '订单助手', 7, 'orders', true)
                """);
        var draft = configs.saveDraft("agent-1", new AgentConfigDraftRequest("AGENTSCOPE", "approved prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true, "DEV_ALLOW_ALL", "ALLOW_LIST", null,
                List.of(tool("workflow_one", true), tool("workflow_two", true), tool("workflow_off", false))));
        initialActiveId = configs.publish("agent-1", draft.id(), "reviewer").id();
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void reportsStalePinsForAgentWorkflowAndAgentList() {
        currentVersions.put("workflow_one", 200L);
        currentVersions.put("workflow_off", 300L);

        var agent = freshness.agentFreshness("agent-1");
        var references = freshness.workflowReferences("workflow_one");
        var unaffected = freshness.workflowReferences("workflow_two");
        var service = new RuntimeAgentService(agents, configs);
        service.setFreshnessService(freshness);

        var one = agent.tools().stream().filter(t -> t.workflowId().equals("workflow_one")).findFirst().orElseThrow();
        var reference = references.agents().get(0);
        assertAll(
                () -> assertEquals(initialActiveId, agent.activeConfigVersionId()),
                () -> assertEquals(1, agent.staleCount(), "disabled tools never run, so they are not reported"),
                () -> assertEquals(2, agent.tools().size()),
                () -> assertEquals(100L, one.pinnedWorkflowVersionId()),
                () -> assertEquals("v100", one.pinnedWorkflowVersion()),
                () -> assertEquals(200L, one.latestWorkflowVersionId()),
                () -> assertEquals("v200", one.latestWorkflowVersion()),
                () -> assertTrue(one.stale()),
                () -> assertEquals(200L, references.latestWorkflowVersionId()),
                () -> assertEquals(1, references.staleCount()),
                () -> assertEquals("agent-1", reference.agentId()),
                () -> assertEquals("订单助手", reference.agentName()),
                () -> assertEquals("orders-agent", reference.keySlug()),
                () -> assertEquals("orders", reference.projectCode()),
                () -> assertEquals(initialActiveId, reference.activeConfigVersionId()),
                () -> assertEquals(1, reference.activeConfigVersionNo()),
                () -> assertEquals("v100", reference.pinnedWorkflowVersion()),
                () -> assertEquals("v200", reference.latestWorkflowVersion()),
                () -> assertTrue(reference.stale()),
                () -> assertEquals(0, unaffected.staleCount()),
                () -> assertFalse(unaffected.agents().get(0).stale()),
                () -> assertTrue(freshness.workflowReferences("workflow_off").agents().isEmpty()),
                () -> assertEquals(1, service.list(null, null).get(0).staleWorkflowToolCount()));
    }

    @Test
    void draftReferencesAreNotReportedAsRunningAgents() {
        configs.upsertWorkflowToolInDraft("agent-1", tool("draft_only", true));
        assertTrue(freshness.workflowReferences("draft_only").agents().isEmpty());
    }

    @Test
    void publishLatestMovesOnlyStaleToolsAndPreservesTheDraft() {
        var draft = configs.saveDraft("agent-1", new AgentConfigDraftRequest(null, "unreviewed prompt", null,
                null, null, null, null, null, null, null, null, null, null, null));
        var draftBefore = database.jdbc().queryForMap("SELECT * FROM runtime_agent_config_version WHERE id = ?", draft.id());
        currentVersions.put("workflow_one", 200L);
        currentVersions.put("workflow_off", 300L);

        var result = configs.publishLatestWorkflowVersions("agent-1", initialActiveId, "admin-ui");

        Long activeId = agents.selectById("agent-1").getActiveConfigVersionId();
        Map<String, Long> pins = new HashMap<>();
        configs.listTools("agent-1", activeId).forEach(t -> pins.put(t.workflowId(), t.workflowVersionId()));
        assertAll(
                () -> assertTrue(result.published()),
                () -> assertEquals(activeId, result.config().id()),
                () -> assertNotEquals(initialActiveId, activeId),
                () -> assertEquals("ACTIVE", result.config().status()),
                () -> assertEquals("approved prompt", result.config().systemPrompt()),
                () -> assertEquals("admin-ui", result.config().publishedBy()),
                () -> assertEquals(1, result.updatedTools().size()),
                () -> assertEquals("v100", result.updatedTools().get(0).pinnedWorkflowVersion()),
                () -> assertEquals("v200", result.updatedTools().get(0).latestWorkflowVersion()),
                () -> assertEquals(Map.of("workflow_one", 200L, "workflow_two", 100L, "workflow_off", 100L), pins),
                () -> assertEquals("ARCHIVED", versions.selectById(initialActiveId).getStatus()),
                () -> assertEquals(draftBefore,
                        database.jdbc().queryForMap("SELECT * FROM runtime_agent_config_version WHERE id = ?", draft.id())),
                () -> assertEquals(0, freshness.agentFreshness("agent-1").staleCount()));
    }

    @Test
    void publishLatestRejectsAChangedActiveConfigWithoutWriting() {
        currentVersions.put("workflow_one", 200L);
        int before = countConfigs();

        assertThrows(RuntimeAgentConfigConflictException.class,
                () -> configs.publishLatestWorkflowVersions("agent-1", initialActiveId + 99, "admin-ui"));
        assertThrows(IllegalArgumentException.class,
                () -> configs.publishLatestWorkflowVersions("agent-1", null, "admin-ui"));
        assertEquals(before, countConfigs());
        assertEquals(initialActiveId, agents.selectById("agent-1").getActiveConfigVersionId());
    }

    @Test
    void publishLatestIsANoOpWhenNothingIsStale() {
        int before = countConfigs();
        var result = configs.publishLatestWorkflowVersions("agent-1", initialActiveId, "admin-ui");
        assertAll(
                () -> assertFalse(result.published()),
                () -> assertEquals(initialActiveId, result.config().id()),
                () -> assertTrue(result.updatedTools().isEmpty()),
                () -> assertEquals(before, countConfigs()));
    }

    @Test
    void publishLatestRunsPublishValidationAndRollsBackOnFailure() {
        currentVersions.put("workflow_one", 200L);
        riskFloors.put(200L, "WRITE");
        int before = countConfigs();

        assertThrows(RuntimeAgentConfigService.HttpApiRiskDowngradeException.class,
                () -> configs.publishLatestWorkflowVersions("agent-1", initialActiveId, "admin-ui"));
        assertEquals(before, countConfigs());
        assertEquals(initialActiveId, agents.selectById("agent-1").getActiveConfigVersionId());
        assertEquals("ACTIVE", versions.selectById(initialActiveId).getStatus());
    }

    private int countConfigs() {
        return database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version", Integer.class);
    }

    private WorkflowToolRequest tool(String workflowId, boolean enabled) {
        return new WorkflowToolRequest(workflowId, workflowId, null, null, null,
                "READ", "workflow:" + workflowId, true, enabled, null);
    }

    private RuntimeWorkflowToolCatalogQuery.Entry entry(String id, Long versionId) {
        return new RuntimeWorkflowToolCatalogQuery.Entry(
                new RuntimeWorkflowToolCatalogQuery.Workflow(id, id, id, "Description", true),
                new RuntimeWorkflowToolCatalogQuery.Version(versionId, "v" + versionId, true, "{}", "{}",
                        riskFloors.get(versionId)));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T service) {
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
