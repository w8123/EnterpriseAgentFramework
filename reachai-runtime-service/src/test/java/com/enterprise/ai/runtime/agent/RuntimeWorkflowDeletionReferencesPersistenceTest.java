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

/** Real baseline-derived Agent storage and Spring port binding; Workflow cleanup is substituted. */
class RuntimeWorkflowDeletionReferencesPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeAgentWorkflowToolMapper tools;
    private RuntimeWorkflowDeletionReferences references;

    @BeforeEach
    void setup() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool"), RuntimeAgentMapper.class,
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
        var published = context.getBean(RuntimeAgentWorkflowUsageQuery.class).publishedBindings(List.of());
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
}
