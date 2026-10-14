package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

/** Real baseline-derived Agent storage and Spring port binding; Workflow cleanup is substituted. */
class RuntimeWorkflowDeletionReferencesPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeAgentWorkflowToolMapper tools;
    private RuntimeWorkflowDeletionReferences references;

    @BeforeEach
    void setup() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool", "runtime_workflow_version"), RuntimeAgentMapper.class,
                RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class);
        tools = spy(database.mapper(RuntimeAgentWorkflowToolMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RuntimeAgentMapper.class, () -> database.mapper(RuntimeAgentMapper.class));
        context.registerBean(RuntimeAgentConfigVersionMapper.class, () -> database.mapper(RuntimeAgentConfigVersionMapper.class));
        context.registerBean(RuntimeAgentWorkflowToolMapper.class, () -> tools);
        context.register(RuntimeAgentWorkflowUsageReader.class);
        context.refresh();
        references = context.getBean(RuntimeWorkflowDeletionReferences.class);
        database.jdbc().update("INSERT INTO runtime_agent(id, key_slug, name, enabled) VALUES ('a1', 'disabled', 'Disabled', 0)");
        database.jdbc().update("INSERT INTO runtime_agent_config_version(id, agent_id, version_no, status) VALUES (1, 'a1', 1, 'DRAFT'), (2, 'a1', 2, 'ARCHIVED')");
        database.jdbc().update("""
                INSERT INTO runtime_agent_workflow_tool
                    (agent_id, agent_config_version_id, workflow_id, workflow_version_id, tool_name, enabled)
                VALUES ('a1', 1, 'draft-ref', 11, 'draft', 0),
                       ('a1', 2, 'history-ref', 12, 'history', 1),
                       ('a1', 2, 'draft-ref', 11, 'retained', 0)
                """);
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void deletionEvidenceIncludesDisabledDraftAndArchivedBindingsIndependentlyOfPublishedEvidence() {
        var result = references.referencedWorkflowIds(List.of("draft-ref", "history-ref", "unused"));
        assertEquals(Set.of("draft-ref", "history-ref"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("forged"));
        var published = context.getBean(RuntimeAgentWorkflowUsageQuery.class).publishedBindings(List.of(), java.util.Map.of(11L, "draft-ref", 12L, "history-ref"));
        assertEquals(List.of(), published.bindings());
        assertSame(references, context.getBean(RuntimeAgentWorkflowUsageQuery.class));
    }

    @Test
    void retainedDraftReferencePreventsWorkflowCleanupThroughTheDeletionContract() {
        var workflows = mock(RuntimeWorkflowDefinitionMapper.class);
        var versions = mock(RuntimeWorkflowVersionMapper.class);
        var resources = mock(RuntimeWorkflowResourceBindingService.class);
        var index = mock(RuntimeWorkflowReferenceIndex.class);
        var draft = new RuntimeWorkflowDefinitionEntity();
        draft.setId("draft-ref");
        draft.setStatus("DRAFT");
        when(workflows.selectForRelease("draft-ref")).thenReturn(draft);
        var definitions = new RuntimeWorkflowDefinitionService(workflows, versions, references,
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()), resources, index);
        var ex = assertThrows(IllegalArgumentException.class, () -> definitions.delete("draft-ref"));
        assertTrue(ex.getMessage().contains("Workflow-as-Tool"));
        verify(workflows, never()).deleteById("draft-ref");
        verifyNoInteractions(versions, resources, index);
    }

    @Test
    void emptyScopeDoesNotQueryAndUnrelatedWorkflowDoesNotInheritReferences() {
        assertEquals(Set.of(), references.referencedWorkflowIds(List.of()));
        assertEquals(Set.of(), references.referencedWorkflowIds(null));
        verifyNoInteractions(tools);
        assertEquals(Set.of(), references.referencedWorkflowIds(List.of("unused")));
    }

    @Test void publishedUsageLoadsOnlyMatchingVersionsAndRetainsDisabledHistoricalExternalPins() {
        database.jdbc().update("INSERT INTO runtime_workflow_version(id,workflow_id,version,snapshot_json) VALUES (12,'history-ref','v1','{}'),(21,'current-ref','v1','{}'),(22,'other-ref','v1','{}')");
        database.jdbc().update("INSERT INTO runtime_agent(id,key_slug,name,enabled,active_config_version_id) VALUES ('a2','active','Active',1,3)");
        database.jdbc().update("INSERT INTO runtime_agent_config_version(id,agent_id,version_no,status) VALUES (3,'a2',1,'ACTIVE')");
        database.jdbc().update("INSERT INTO runtime_agent_workflow_tool(agent_id,agent_config_version_id,workflow_id,workflow_version_id,tool_name,enabled) VALUES ('a2',3,'current-ref',21,'current',1)");
        for (int item = 0; item < 40; item++) {
            String id = "other-" + item; long config = 100 + item;
            database.jdbc().update("INSERT INTO runtime_agent(id,key_slug,name,enabled,active_config_version_id) VALUES (?,?,?,1,?)", id, id, id, config);
            database.jdbc().update("INSERT INTO runtime_agent_config_version(id,agent_id,version_no,status,config_json) VALUES (?,?,1,'ACTIVE',?)", config, id, "x".repeat(10_000));
            database.jdbc().update("INSERT INTO runtime_agent_workflow_tool(agent_id,agent_config_version_id,workflow_id,workflow_version_id,tool_name,enabled) VALUES (?,?,'other-ref',22,?,1)", id, config, id);
        }
        var usage = context.getBean(RuntimeAgentWorkflowUsageQuery.class);
        var active = usage.publishedBindings(List.of(), java.util.Map.of(21L, "current-ref"));
        assertEquals(Set.of(), active.warnings()); assertEquals(1, active.bindings().size());
        assertEquals("a2", active.bindings().get(0).agentId()); assertTrue(active.bindings().get(0).active());
        verify(tools).selectList(argThat(query -> query.getSqlSegment().contains("workflow_version_id IN")
                && query.getSqlSelect().equals("id,agent_id,agent_config_version_id,workflow_id,workflow_version_id")));
        assertEquals(List.of(), usage.publishedBindings(List.of(), java.util.Map.of(12L, "history-ref")).bindings());
        var pinned = usage.publishedBindings(List.of(2L), java.util.Map.of(12L, "history-ref"));
        assertEquals(Set.of(), pinned.warnings()); assertEquals(1, pinned.bindings().size());
        assertEquals("a1", pinned.bindings().get(0).agentId()); assertFalse(pinned.bindings().get(0).active());
        database.jdbc().update("DELETE FROM runtime_workflow_version WHERE id=21");
        assertTrue(usage.publishedBindings(List.of(), java.util.Map.of()).warnings().contains("AGENT_WORKFLOW_VERSION_MISSING"));
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id=99999 WHERE id='a2'");
        assertTrue(usage.publishedBindings(List.of(), java.util.Map.of()).warnings().contains("AGENT_CONFIG_VERSION_MISSING"));
        assertTrue(usage.publishedBindings(List.of(99999L), java.util.Map.of()).warnings().contains("AGENT_CONFIG_VERSION_MISSING"));
    }

    @Test void aggregatePinCoverageKeepsMissingOwnersAndIgnoresUnrelatedMalformedConfigurationBodies() {
        var configs = spy(database.mapper(RuntimeAgentConfigVersionMapper.class));
        var agents = spy(database.mapper(RuntimeAgentMapper.class));
        var usage = new RuntimeAgentWorkflowUsageReader(agents, tools, configs);
        database.jdbc().update("INSERT INTO runtime_workflow_version(id,workflow_id,version,snapshot_json) VALUES (12,'history-ref','v1','{}')");
        database.jdbc().update("UPDATE runtime_agent_config_version SET config_json='malformed JSON' WHERE id=2");
        var valid = usage.publishedBindings(List.of(2L), java.util.Map.of());
        assertEquals(Set.of(), valid.warnings()); assertEquals(List.of(), valid.bindings());
        verify(configs).inspectPinnedCoverage(argThat(ids -> Set.copyOf(ids).equals(Set.of(2L)))); verify(configs, never()).selectList(any());
        database.jdbc().update("DELETE FROM runtime_agent WHERE id='a1'");
        var incomplete = usage.publishedBindings(List.of(2L, 99999L), java.util.Map.of());
        assertTrue(incomplete.warnings().contains("AGENT_CONFIG_OWNER_MISSING"));
        assertTrue(incomplete.warnings().contains("AGENT_CONFIG_VERSION_MISSING"));
        assertEquals(List.of(), incomplete.bindings());
    }
}
