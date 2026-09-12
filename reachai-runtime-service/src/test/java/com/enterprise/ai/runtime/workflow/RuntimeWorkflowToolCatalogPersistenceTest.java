package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeSqlQueryRecorder;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeWorkflowToolCatalogPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeWorkflowToolCatalogQuery catalog;
    private RuntimeSqlQueryRecorder queries;

    @BeforeEach
    void actualBaselineTablesAndMyBatisQueries() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_workflow", "runtime_workflow_version"),
                RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class);
        queries = new RuntimeSqlQueryRecorder();
        database.addInterceptor(queries);
        catalog = new RuntimeWorkflowToolCatalogReader(database.mapper(RuntimeWorkflowDefinitionMapper.class),
                database.mapper(RuntimeWorkflowVersionMapper.class), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void currentVersionRetainsRolloutThenIdPreferenceAndIgnoresRetiredVersions() {
        workflow("orders", "ACTIVE");
        version(1, "orders", "ACTIVE", 100, "{}", "{}");
        version(2, "orders", "ACTIVE", 10, "{}", "{}");
        version(3, "orders", "ACTIVE", 100, "{}", "{}");
        version(4, "orders", "RETIRED", 100, "{}", "{}");

        var result = catalog.current(List.of("orders", "missing", "orders"));

        assertEquals(3L, result.get("orders").version().id());
        assertTrue(result.get("orders").activePublishedWorkflow());
        assertTrue(result.get("orders").version().executable());
        assertNull(result.get("missing").workflow());
        assertNull(result.get("missing").version());
        assertEquals(2, queries.queries().size());
    }

    @Test
    void historicalContractUsesOnlyItsExactPublishedVersionAndPreservesEmptySchemas() throws Exception {
        workflow("orders", "ACTIVE");
        version(1, "orders", "RETIRED", 100,
                "{\"inputSchemaJson\":{\"title\":\"已发布输入\"}}",
                "{\"inputSchema\":{\"title\":\"旧图输入\"},\"outputSchema\":{\"title\":\"已发布输出\"}}");
        version(2, "orders", "ACTIVE", 100, "{}", "{}");
        var old = new Reference("orders", 1L);
        var latest = new Reference("orders", 2L);

        var result = catalog.pinned(List.of(old, latest));

        assertEquals("已发布输入", new ObjectMapper().readTree(result.get(old).version().inputSchemaJson()).path("title").asText());
        assertEquals("已发布输出", new ObjectMapper().readTree(result.get(old).version().outputSchemaJson()).path("title").asText());
        assertEquals(1L, result.get(old).version().id());
        assertNull(result.get(latest).version().inputSchemaJson());
        assertNull(result.get(latest).version().outputSchemaJson());
        assertEquals(2, queries.queries().size());
    }

    @Test
    void wrongOwnerMissingUnpinnedAndUnpublishedReferencesHaveNoPublishedContract() {
        workflow("orders", "ACTIVE");
        workflow("foreign", "ACTIVE");
        version(1, "foreign", "ACTIVE", 100, "{}", "{\"inputSchema\":{\"title\":\"foreign\"}}");
        version(2, "orders", "DRAFT", 100, "{}", "{}");
        version(3, "orders", "UNKNOWN", 100, "{}", "{}");
        var references = List.of(new Reference("orders", 1L), new Reference("orders", 99L),
                new Reference("orders", null), new Reference("orders", 2L), new Reference("orders", 3L),
                new Reference("missing", 1L));

        var result = catalog.pinned(references);

        assertEquals(references.size(), result.size());
        result.values().forEach(entry -> assertNull(entry.version()));
        assertEquals("orders", result.get(references.get(0)).workflow().id());
        assertNull(result.get(references.get(5)).workflow());
    }

    @Test
    void inactiveWorkflowAndEmptyGraphRemainUnavailableForPublication() {
        workflow("disabled", "DISABLED");
        workflow("empty", "ACTIVE");
        version(1, "disabled", "ACTIVE", 100, "{}", "{}");
        version(2, "empty", "ACTIVE", 100, "{}", null);
        var result = catalog.current(List.of("disabled", "empty"));
        assertFalse(result.get("disabled").activePublishedWorkflow());
        assertFalse(result.get("empty").version().executable());
    }

    @Test
    void emptyLookupsDoNotIssueSql() {
        assertTrue(catalog.current(List.of()).isEmpty());
        assertTrue(catalog.current(null).isEmpty());
        assertTrue(catalog.pinned(List.of()).isEmpty());
        assertTrue(catalog.pinned(null).isEmpty());
        assertTrue(queries.queries().isEmpty());
    }

    @Test
    void largeCatalogUsesBatchesOfFiveHundredIdsForBothCurrentAndPinnedReads() {
        var ids = IntStream.range(0, 1001).mapToObj(index -> "wf-" + index).toList();
        var references = new ArrayList<Reference>();
        for (int index = 0; index < ids.size(); index++) {
            workflow(ids.get(index), "ACTIVE");
            version(index + 1, ids.get(index), "ACTIVE", 100, "{}", "{}");
            references.add(new Reference(ids.get(index), (long) index + 1));
        }

        assertEquals(1001, catalog.current(ids).size());
        assertEquals(List.of(500, 500, 1, 500, 500, 1),
                queries.queries().stream().map(RuntimeSqlQueryRecorder.Query::rowCount).toList());
        queries.clear();
        assertEquals(1001, catalog.pinned(references).size());
        assertEquals(List.of(500, 500, 1, 500, 500, 1),
                queries.queries().stream().map(RuntimeSqlQueryRecorder.Query::rowCount).toList());
    }

    @Test
    void returnedContractsAreImmutableSnapshots() {
        workflow("orders", "ACTIVE");
        version(1, "orders", "ACTIVE", 100, "{}", "{\"inputSchema\":{\"title\":\"published\"}}");
        var result = catalog.current(List.of("orders"));
        database.jdbc().update("UPDATE runtime_workflow SET name = 'changed', status = 'DISABLED' WHERE id = 'orders'");
        database.jdbc().update("UPDATE runtime_workflow_version SET graph_spec_snapshot_json = '{}' WHERE id = 1");
        assertEquals("orders", result.get("orders").workflow().name());
        assertTrue(result.get("orders").activeWorkflow());
        assertTrue(result.get("orders").version().inputSchemaJson().contains("published"));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    private void workflow(String id, String status) {
        database.jdbc().update("""
                INSERT INTO runtime_workflow (id, key_slug, name, status, input_schema_json, output_schema_json)
                VALUES (?, ?, ?, ?, '{"title":"mutable input"}', '{"title":"mutable output"}')
                """, id, id, id, status);
    }

    private void version(long id, String workflowId, String status, int rollout, String snapshot, String graph) {
        database.jdbc().update("""
                INSERT INTO runtime_workflow_version
                (id, workflow_id, version, status, rollout_percent, snapshot_json, graph_spec_snapshot_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, workflowId, "v" + id, status, rollout, snapshot, graph);
    }
}
