package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Workflow, version and reference tables; only the remote project directory is substituted. */
class RuntimeSdkWorkflowSyncPersistenceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeWorkflowDefinitionMapper mapper;
    private RuntimeWorkflowVersionMapper versions;
    private RuntimeWorkflowReferenceMapper references;
    private RuntimeWorkflowDefinitionService workflows;
    private RuntimeCapabilityCatalogClient projects;
    private RuntimeAgentGraphSyncService registry;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_workflow", "runtime_workflow_version"),
                RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class, RuntimeWorkflowReferenceMapper.class);
        var table = Pattern.compile("(?ism)CREATE TABLE IF NOT EXISTS `runtime_workflow_capability_reference`\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;")
                .matcher(Files.readString(Path.of("../sql/initV2.sql")));
        assertTrue(table.find());
        db.jdbc().execute(table.group().replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replace("`reference_key`(191)", "`reference_key`"));
        mapper = spy(db.mapper(RuntimeWorkflowDefinitionMapper.class));
        versions = db.mapper(RuntimeWorkflowVersionMapper.class);
        references = spy(db.mapper(RuntimeWorkflowReferenceMapper.class));
        var index = transactional(new RuntimeWorkflowReferenceIndex(references, mapper, versions, json));
        workflows = transactional(new RuntimeWorkflowDefinitionService(mapper, versions,
                mock(RuntimeWorkflowDeletionReferences.class), new RuntimeWorkflowDocumentCanonicalizer(json),
                mock(RuntimeWorkflowResourceBindingService.class), index));
        projects = mock(RuntimeCapabilityCatalogClient.class);
        when(projects.getProject(anyString())).thenAnswer(call -> {
            String projectCode = call.getArgument(0, String.class);
            return Map.<String, Object>of("projectId", "orders".equals(projectCode) ? 7L : 8L,
                    "projectCode", projectCode);
        });
        var owner = transactional(new RuntimeSdkWorkflowSyncService(workflows, new RuntimeWorkflowDocumentCanonicalizer(json), json));
        registry = transactional(new RuntimeAgentGraphSyncService(projects, owner, json));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SYSTEM"})
    void aMatchingSlugCannotConvertAnotherAuthorityIntoAnSdkWorkflow(String authority) {
        var existing = new RuntimeWorkflowDefinitionEntity();
        existing.setKeySlug("orders_helper"); existing.setName("人工维护");
        existing.setProjectId(7L); existing.setProjectCode("orders");
        existing.setDefinitionAuthority(authority); existing.setCreationChannel("STUDIO");
        existing = workflows.create(existing);
        String id = existing.getId();
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph("helper"), true));
        assertEquals("人工维护", mapper.selectById(id).getName());
        assertEquals(authority, mapper.selectById(id).getDefinitionAuthority());
    }

    @Test
    void aMatchingSlugCannotMoveASdkWorkflowAcrossProjects() {
        String id = sync("orders", graph("east_total"), true);
        assertThrows(IllegalArgumentException.class, () -> sync("orders_east", graph("total"), true));
        assertEquals(7L, mapper.selectById(id).getProjectId());
        assertEquals("orders", mapper.selectById(id).getProjectCode());
    }

    @Test
    void normalizedGraphCodeCollisionCannotReplaceAnotherSdkSource() throws Exception {
        String id = sync("orders", graph("order.total"), true);
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph("order_total"), true));
        assertEquals("order.total", json.readTree(mapper.selectById(id).getExtraJson()).path("sdkGraph").path("graphCode").asText());
    }

    @Test
    void syncPreservesOtherMetadataWithoutRecursivelyEmbeddingPreviousDocuments() throws Exception {
        String id = sync("orders", graph("helper"), true);
        Map<String, Object> extra = json.readValue(mapper.selectById(id).getExtraJson(), Map.class);
        extra.put("presentation", Map.of("title", "保留人工展示信息"));
        db.jdbc().update("UPDATE runtime_workflow SET extra_json = ? WHERE id = ?", json.writeValueAsString(extra), id);
        for (int i = 0; i < 3; i++) sync("orders", graph("helper"), true);
        var saved = json.readTree(mapper.selectById(id).getExtraJson());
        assertEquals("保留人工展示信息", saved.path("presentation").path("title").asText());
        assertFalse(saved.has("previousExtraJson"));
        assertEquals("SDK", saved.path("sdkGraph").path("source").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "entry", "exit", "model"})
    void previewRejectsTheSameInvalidDefinitionsAsApply(String invalid) {
        var graph = graph("helper");
        switch (invalid) {
            case "schema" -> graph.graphSpec().setSchemaVersion(1);
            case "entry" -> graph.graphSpec().setEntryNodeId("");
            case "exit" -> graph.graphSpec().setExitNodeIds(List.of());
            case "model" -> graph.graphSpec().getNodes().get(0).setConfig(Map.of());
        }
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph, false));
        assertEquals(0L, mapper.selectCount(null));
        assertEquals(0L, references.selectCount(null));
    }

    @Test
    void remoteProjectResolutionHappensBeforeOpeningTheWorkflowWriteTransaction() {
        when(projects.getProject("orders")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Map.of("projectId", 7L, "projectCode", "orders");
        });
        sync("orders", graph("helper"), true);
        assertEquals(1L, mapper.selectCount(null));
    }

    @Test
    void aConcurrentPublicationCannotBeRevertedByAnEarlierSyncRead() throws Exception {
        String id = sync("orders", graph("helper"), true);
        var publishOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            RuntimeWorkflowDefinitionEntity read = (RuntimeWorkflowDefinitionEntity) call.callRealMethod();
            if (read != null && id.equals(read.getId()) && publishOnce.compareAndSet(true, false)) {
                try (var connection = db.jdbc().getDataSource().getConnection();
                     var update = connection.prepareStatement("UPDATE runtime_workflow SET status = 'ACTIVE', updated_at = DATEADD('SECOND', 2, updated_at) WHERE id = ?")) {
                    update.setString(1, id); update.executeUpdate();
                }
            }
            return read;
        }).when(mapper).selectOne(any());
        sync("orders", graph("helper"), true);
        assertEquals("ACTIVE", mapper.selectById(id).getStatus());
    }

    @Test
    void aNewDefinitionUsesCurrentSdkSemanticsAndLayoutOnlyCanvas() throws Exception {
        String id = sync("orders", graph("helper"), true);
        var saved = mapper.selectById(id);
        assertEquals("GENERAL", saved.getWorkflowKind());
        assertEquals("GRAPH_SPEC", saved.getExecutionEngine());
        assertEquals("SDK", saved.getDefinitionAuthority());
        assertEquals("SDK_SYNC", saved.getCreationChannel());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals("model-a", saved.getDefaultModelInstanceId());
        assertEquals("订单助手", saved.getName());
        var document = json.readTree(saved.getGraphSpecJson());
        assertEquals(2, document.path("schemaVersion").asInt());
        assertEquals("answer", document.path("entryNodeId").asText());
        assertEquals("answer", document.path("exitNodeIds").get(0).asText());
        var canvas = json.readTree(saved.getCanvasJson());
        assertEquals(1, canvas.path("schemaVersion").asInt());
        assertEquals(3, canvas.path("nodes").size());
        assertTrue(canvas.findValues("data").isEmpty());
        assertTrue(canvas.findValues("type").isEmpty());
        assertTrue(references.missingDraftIds(100).isEmpty());
        assertEquals(1L, references.selectCount(null));
    }

    @Test
    void anEmptyBatchKeepsItsProtocolResponseWithoutCreatingAnything() {
        var response = registry.sync("orders", null);
        assertFalse(response.syncId().isBlank());
        assertEquals(7L, response.projectId());
        assertEquals(0, response.received());
        assertTrue(response.items().isEmpty());
        assertEquals(0L, mapper.selectCount(null));
        assertEquals(0L, references.selectCount(null));
    }

    @Test
    void previewReturnsCurrentIdentityButDoesNotChangeDraftRevisionMetadataOrIndex() {
        String id = sync("orders", graph("helper"), true);
        var before = mapper.selectById(id);
        var proposed = graph("helper");
        proposed.graphSpec().getNodes().get(0).setConfig(Map.of("modelInstanceId", "replacement"));
        var response = registry.sync("orders", new AgentGraphSyncRequest("preview", "SDK", false, List.of(proposed)));
        assertEquals(id, response.items().get(0).workflowId());
        assertEquals("WOULD_UPDATE", response.items().get(0).changeType());
        assertEquals(0, response.updated());
        assertEquals(before, mapper.selectById(id));
        assertTrue(references.missingDraftIds(100).isEmpty());
        assertEquals(1L, references.selectCount(null));
    }

    @Test
    void draftSyncKeepsPublishedSnapshotsStatusAndUnrelatedRuntimeDefaults() {
        String id = sync("orders", graph("helper"), true);
        var before = mapper.selectById(id);
        var published = new RuntimeWorkflowVersionEntity();
        published.setWorkflowId(id); published.setVersion("v1"); published.setStatus("ACTIVE");
        published.setSnapshotJson("{\"defaultModelInstanceId\":\"published-model\"}");
        published.setGraphSpecSnapshotJson(before.getGraphSpecJson());
        published.setCanvasSnapshotJson(before.getCanvasJson()); published.setPublishedBy("original-publisher");
        versions.insert(published);
        var savedVersion = versions.selectById(published.getId());
        db.jdbc().update("UPDATE runtime_workflow SET status = 'DISABLED', output_schema_json = '{}', default_resource_config_json = '{\"resource\":\"pinned\"}' WHERE id = ?", id);
        var changed = graph("helper");
        changed.graphSpec().getNodes().get(0).setConfig(Map.of("modelInstanceId", "draft-model"));
        sync("orders", changed, true);
        var draft = mapper.selectById(id);
        assertEquals("DISABLED", draft.getStatus());
        assertEquals("draft-model", draft.getDefaultModelInstanceId());
        assertEquals("{}", draft.getOutputSchemaJson());
        assertEquals("{\"resource\":\"pinned\"}", draft.getDefaultResourceConfigJson());
        assertEquals(savedVersion, versions.selectById(published.getId()));
        assertTrue(draft.getUpdatedAt().isAfter(before.getUpdatedAt()));
    }

    @Test
    void referenceReplacementAndDraftWriteRollBackIfIndexPersistenceFailsAfterSql() {
        String id = sync("orders", graphWithTool("helper", "orders.read"), true);
        var before = mapper.selectById(id);
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("reference index unavailable");
        }).when(references).insertBatch(anyList());
        assertThrows(DataAccessResourceFailureException.class,
                () -> sync("orders", graphWithTool("helper", "orders.update"), true));
        assertEquals(before, mapper.selectById(id));
        assertEquals(List.of("orders.read"), db.jdbc().queryForList(
                "SELECT reference_key FROM runtime_workflow_capability_reference WHERE node_ordinal >= 0", String.class));
        assertTrue(references.missingDraftIds(100).isEmpty());
    }

    @Test
    void aLaterOwnershipConflictRollsBackEarlierDraftsAndReferenceHeadersInTheBatch() {
        var existing = new RuntimeWorkflowDefinitionEntity();
        existing.setKeySlug("orders_zzz"); existing.setName("保留人工草稿");
        workflows.create(existing);
        assertThrows(IllegalArgumentException.class, () -> registry.sync("orders",
                new AgentGraphSyncRequest("batch", "SDK", true, List.of(graph("aaa"), graph("zzz")))));
        assertEquals(1L, mapper.selectCount(null));
        assertEquals(1L, references.selectCount(null));
        assertTrue(workflows.findByKeySlug("orders_aaa").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "normalized"})
    void collidingGraphCodesInOneBatchAreRejectedBeforeWriting(String collision) {
        var first = graph("helper.part");
        var second = graph("duplicate".equals(collision) ? "helper.part" : "helper_part");
        assertThrows(IllegalArgumentException.class, () -> registry.sync("orders",
                new AgentGraphSyncRequest("batch", "SDK", true, List.of(first, second))));
        assertEquals(0L, mapper.selectCount(null));
        assertEquals(0L, references.selectCount(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "other_source", "other_channel", "other_project_id"})
    void missingOrInconsistentSourceEvidenceDoesNotAuthorizeReplacement(String evidence) {
        String id = sync("orders", graph("helper"), true);
        switch (evidence) {
            case "missing" -> db.jdbc().update("UPDATE runtime_workflow SET extra_json = '{}' WHERE id = ?", id);
            case "malformed" -> db.jdbc().update("UPDATE runtime_workflow SET extra_json = 'invalid' WHERE id = ?", id);
            case "other_source" -> db.jdbc().update("UPDATE runtime_workflow SET extra_json = REPLACE(extra_json, 'SDK', 'OTHER') WHERE id = ?", id);
            case "other_channel" -> db.jdbc().update("UPDATE runtime_workflow SET creation_channel = 'STUDIO' WHERE id = ?", id);
            default -> db.jdbc().update("UPDATE runtime_workflow SET project_id = 99 WHERE id = ?", id);
        }
        var before = mapper.selectById(id);
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph("helper"), true));
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph("helper"), false));
        assertEquals(before, mapper.selectById(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownership", "rename"})
    void sourceIsRevalidatedAfterAcquiringTheCurrentDefinitionLock(String change) throws Exception {
        String id = sync("orders", graph("helper"), true);
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            RuntimeWorkflowDefinitionEntity read = (RuntimeWorkflowDefinitionEntity) call.callRealMethod();
            if (read != null && id.equals(read.getId()) && once.compareAndSet(true, false)) {
                try (var connection = db.jdbc().getDataSource().getConnection();
                     var update = connection.prepareStatement("ownership".equals(change)
                             ? "UPDATE runtime_workflow SET definition_authority = 'USER' WHERE id = ?"
                             : "UPDATE runtime_workflow SET key_slug = 'renamed_by_operator' WHERE id = ?")) {
                    update.setString(1, id); update.executeUpdate();
                }
            }
            return read;
        }).when(mapper).selectOne(any());
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph("helper"), true));
        var saved = mapper.selectById(id);
        assertEquals("ownership".equals(change) ? "USER" : "SDK", saved.getDefinitionAuthority());
        assertEquals("rename".equals(change) ? "renamed_by_operator" : "orders_helper", saved.getKeySlug());
    }

    @Test
    void concurrentMetadataEditsBeforeTheLockAreMergedFromTheFreshRow() throws Exception {
        String id = sync("orders", graph("helper"), true);
        Map<String, Object> metadata = json.readValue(mapper.selectById(id).getExtraJson(), Map.class);
        metadata.put("presentation", Map.of("title", "刚保存的设置"));
        String changed = json.writeValueAsString(metadata);
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            RuntimeWorkflowDefinitionEntity read = (RuntimeWorkflowDefinitionEntity) call.callRealMethod();
            if (read != null && id.equals(read.getId()) && once.compareAndSet(true, false)) {
                try (var connection = db.jdbc().getDataSource().getConnection();
                     var update = connection.prepareStatement("UPDATE runtime_workflow SET extra_json = ? WHERE id = ?")) {
                    update.setString(1, changed); update.setString(2, id); update.executeUpdate();
                }
            }
            return read;
        }).when(mapper).selectOne(any());
        sync("orders", graph("helper"), true);
        assertEquals("刚保存的设置", json.readTree(mapper.selectById(id).getExtraJson()).path("presentation").path("title").asText());
    }

    @Test
    void concurrentFirstCreationKeepsOneIdentityAndReportsTheLosingBatchAsConflict() throws Exception {
        var firstReads = ConcurrentHashMap.<Long>newKeySet();
        var gate = new CyclicBarrier(2);
        doAnswer(call -> {
            Object read = call.callRealMethod();
            if (read == null && firstReads.add(Thread.currentThread().getId())) gate.await(10, TimeUnit.SECONDS);
            return read;
        }).when(mapper).selectOne(any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(this::createOrConflict);
            var b = workers.submit(this::createOrConflict);
            assertEquals(java.util.Set.of("created", "conflict"), java.util.Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            assertEquals(1L, mapper.selectCount(null));
            assertEquals(1L, references.selectCount(null));
            assertTrue(references.missingDraftIds(100).isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oppositeBatchOrdersUseOneLockOrderButPreserveEachResponseOrder() throws Exception {
        sync("orders", graph("a"), true); sync("orders", graph("b"), true);
        var firstReads = ConcurrentHashMap.<Long>newKeySet();
        var gate = new CyclicBarrier(2);
        doAnswer(call -> {
            Object read = call.callRealMethod();
            if (firstReads.add(Thread.currentThread().getId())) gate.await(10, TimeUnit.SECONDS);
            return read;
        }).when(mapper).selectOne(any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> registry.sync("orders", new AgentGraphSyncRequest("ab", "SDK", true, List.of(graph("a"), graph("b")))));
            var b = workers.submit(() -> registry.sync("orders", new AgentGraphSyncRequest("ba", "SDK", true, List.of(graph("b"), graph("a")))));
            assertEquals(List.of("a", "b"), a.get(15, TimeUnit.SECONDS).items().stream().map(item -> item.graphCode()).toList());
            assertEquals(List.of("b", "a"), b.get(15, TimeUnit.SECONDS).items().stream().map(item -> item.graphCode()).toList());
            assertEquals(2L, mapper.selectCount(null));
            assertEquals(2L, references.selectCount(null));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"no_llm", "missing_node_id", "invalid_key"})
    void invalidGraphInputsCannotPassPreviewOrCreatePartialDefinitions(String invalid) {
        var graph = graph("invalid_key".equals(invalid) ? "x".repeat(140) : "helper");
        if ("no_llm".equals(invalid)) graph.graphSpec().getNodes().get(0).setType("INTERACTION");
        if ("missing_node_id".equals(invalid)) graph.graphSpec().getNodes().get(0).setId(null);
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph, false));
        assertThrows(IllegalArgumentException.class, () -> sync("orders", graph, true));
        assertEquals(0L, mapper.selectCount(null));
    }

    private String createOrConflict() {
        try { sync("orders", graph("helper"), true); return "created"; }
        catch (IllegalArgumentException conflict) {
            assertTrue(conflict.getMessage().contains("占用"));
            return "conflict";
        }
    }

    private AgentGraphRegistration graphWithTool(String code, String tool) {
        var graph = graph(code);
        var nodes = new java.util.ArrayList<>(graph.graphSpec().getNodes());
        nodes.add(GraphSpec.Node.builder().id("lookup").type("TOOL")
                .ref(GraphSpec.CapabilityRef.builder().kind("TOOL").qualifiedName(tool).build()).build());
        graph.graphSpec().setNodes(nodes);
        return graph;
    }

    private String sync(String project, AgentGraphRegistration graph, boolean apply) {
        return registry.sync(project, new AgentGraphSyncRequest("sync-1", "SDK", apply, List.of(graph)))
                .items().get(0).workflowId();
    }

    private AgentGraphRegistration graph(String code) {
        var spec = GraphSpec.builder().node(GraphSpec.Node.builder().id("answer").type("LLM")
                .config(new LinkedHashMap<>(Map.of("modelInstanceId", "model-a"))).build())
                .entryNodeId("answer").exitNodeIds(List.of("answer")).build();
        return new AgentGraphRegistration(code, "订单助手", "来自业务声明", null, null, null, null,
                spec, Map.of("team", "运维"));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
