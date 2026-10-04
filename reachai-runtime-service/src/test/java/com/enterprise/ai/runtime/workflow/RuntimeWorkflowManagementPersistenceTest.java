package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.enterprise.ai.runtime.api.*;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.UnexpectedRollbackException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HTTP binding, MyBatis SQL and Spring transactions; release policy/transport dependencies are substituted. */
class RuntimeWorkflowManagementPersistenceTest {
    private static final String GRAPH = """
            {"schemaVersion":2,"nodes":[{"id":"answer","type":"ANSWER","config":{"content":"完成"}}],
             "edges":[],"entryNodeId":"answer","exitNodeIds":["answer"]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeWorkflowDefinitionMapper mapper;
    private RuntimeWorkflowVersionMapper versions;
    private RuntimeWorkflowReferenceMapper references;
    private RuntimeWorkflowReleaseEventMapper events;
    private RuntimeWorkflowDefinitionService definitions;
    private RuntimeWorkflowVersionService releases;
    private RuntimeWorkflowManagementService management;
    private RuntimeWorkflowReleaseValidationService validation;
    private MockMvc http;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_workflow", "runtime_workflow_version", "runtime_workflow_release_event"),
                RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class,
                RuntimeWorkflowReferenceMapper.class, RuntimeWorkflowReleaseEventMapper.class);
        var table = Pattern.compile("(?ism)CREATE TABLE IF NOT EXISTS `runtime_workflow_capability_reference`\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;")
                .matcher(Files.readString(Path.of("../sql/initV2.sql")));
        assertTrue(table.find());
        db.jdbc().execute(table.group().replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replace("`reference_key`(191)", "`reference_key`"));
        mapper = spy(db.mapper(RuntimeWorkflowDefinitionMapper.class));
        versions = db.mapper(RuntimeWorkflowVersionMapper.class);
        references = spy(db.mapper(RuntimeWorkflowReferenceMapper.class));
        events = spy(db.mapper(RuntimeWorkflowReleaseEventMapper.class));
        var index = transactional(new RuntimeWorkflowReferenceIndex(references, mapper, versions, json));
        definitions = transactional(new RuntimeWorkflowDefinitionService(mapper, versions,
                mock(RuntimeWorkflowDeletionReferences.class), new RuntimeWorkflowDocumentCanonicalizer(json),
                mock(RuntimeWorkflowResourceBindingService.class), index));
        validation = mock(RuntimeWorkflowReleaseValidationService.class);
        when(validation.validate(any())).thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validation.validateProposed(any(), any())).thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validation.readGraph(anyString(), any())).thenAnswer(call -> json.readValue(call.getArgument(0, String.class), GraphSpec.class));
        var pins = mock(RuntimeCapabilityContractPins.class);
        when(pins.pin(anyString(), any())).thenAnswer(call -> call.getArgument(0, String.class));
        releases = transactional(new RuntimeWorkflowVersionService(versions, definitions, validation, json, pins, events, index));
        management = transactional(new RuntimeWorkflowManagementService(definitions, releases, validation));
        var studio = transactional(new RuntimeWorkflowStudioService(management, releases, json));
        var controller = new RuntimeWorkflowPublicController(management, studio,
                mock(RuntimeWorkflowDebugService.class), mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class));
        // Cross-service test fixtures bring an XML converter onto the classpath;
        // this JSON public-boundary suite must not depend on converter ordering.
        // Spring Boot's MVC mapper ignores unknown request properties (the
        // forged publishedBy field is still rejected as an actor by the controller).
        // Keep this compatibility setting on the test HTTP converter only; the
        // GraphSpec canonicalizer and production validation retain their mapper.
        var mvcJson = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        http = MockMvcBuilders.standaloneSetup(controller, new RuntimeWorkflowVersionPublicController(management))
                .setControllerAdvice(new RuntimeWorkflowRevisionExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mvcJson)).build();
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void callerCannotAssignThePersistedWorkflowIdentity() throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("keySlug", "manual", "name", "人工草稿",
                "id", "caller-selected", "createdAt", "2000-01-01T00:00:00", "updatedAt", "2000-01-01T00:00:00"));
        var response = result(http.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isOk()));
        assertNotEquals("caller-selected", response.path("id").asText());
        assertNull(mapper.selectById("caller-selected"));
        var stored = mapper.selectById(response.path("id").asText());
        assertEquals("DRAFT", stored.getStatus());
        assertEquals("USER", stored.getDefinitionAuthority());
        assertEquals("STUDIO", stored.getCreationChannel());
        assertNotEquals(2000, stored.getCreatedAt().getYear());
        assertEquals(stored.getCreatedAt(), stored.getUpdatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SDK", "SYSTEM"})
    void publicCreationCannotClaimAnotherDefinitionAuthority(String authority) throws Exception {
        http.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("keySlug", "manual", "name", "伪造来源", "definitionAuthority", authority))))
                .andExpect(status().isBadRequest());
        assertEquals(0L, mapper.selectCount(null));
        assertEquals(0L, references.selectCount(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SDK", "SYSTEM"})
    void publicUpdateCannotEditAnOwnedDefinition(String authority) throws Exception {
        var workflow = seed(authority);
        update(workflow.getId(), Map.of("name", "覆盖 SDK", "baseRevision", revision(workflow)))
                .andExpect(status().isBadRequest());
        assertEquals(workflow, mapper.selectById(workflow.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SDK", "SYSTEM"})
    void studioCannotEditAnOwnedDefinition(String authority) throws Exception {
        var workflow = seed(authority);
        save(workflow.getId(), Map.of("graphSpecJson", GRAPH, "name", "覆盖 SDK", "baseRevision", revision(workflow)))
                .andExpect(status().isBadRequest());
        assertEquals(workflow, mapper.selectById(workflow.getId()));
    }

    @Test
    void editingCannotConvertAUserWorkflowIntoAnSdkSource() throws Exception {
        var workflow = seed("USER");
        update(workflow.getId(), Map.of("definitionAuthority", "SDK", "creationChannel", "SDK_SYNC",
                "baseRevision", revision(workflow))).andExpect(status().isBadRequest());
        assertEquals(workflow, mapper.selectById(workflow.getId()));
    }

    @Test
    void publicUpdateRequiresThePreviouslyReadRevision() throws Exception {
        var workflow = seed("USER");
        update(workflow.getId(), Map.of("name", "无修订覆盖")).andExpect(status().isBadRequest());
        assertEquals(workflow, mapper.selectById(workflow.getId()));
    }

    @Test
    void studioSaveRequiresThePreviouslyReadRevision() throws Exception {
        var workflow = seed("USER");
        save(workflow.getId(), Map.of("graphSpecJson", GRAPH, "name", "无修订覆盖")).andExpect(status().isBadRequest());
        assertEquals(workflow, mapper.selectById(workflow.getId()));
    }

    @Test
    void studioWorkingCopyPersistsHttpApiAuthorReferenceAndMappingsWithoutRuntimePins() throws Exception {
        var workflow = seed("USER");
        var apiRef = "http-api:orders:dev:" + "b".repeat(64);
        var graph = """
                {"schemaVersion":2,"entryNodeId":"api","exitNodeIds":["api"],
                 "nodes":[{"id":"api","type":"TOOL",
                   "ref":{"kind":"TOOL","name":"%s","qualifiedName":"%s","projectCode":"orders"},
                   "config":{"qualifiedName":"%s","httpApiAssetId":41,"outputAlias":"order",
                     "inputMapping":{"pathParams.orderId":"params.orderId",
                       "queryParams.expanded":"params.expanded"}}}],"edges":[]}
                """.formatted(apiRef, apiRef, apiRef);

        var saved = result(save(workflow.getId(), Map.of("graphSpecJson", graph,
                "baseRevision", revision(workflow))).andExpect(status().isOk()));
        var stored = mapper.selectById(workflow.getId());
        var storedNode = json.readTree(stored.getGraphSpecJson()).path("nodes").get(0);
        assertEquals(apiRef, storedNode.path("ref").path("qualifiedName").asText());
        assertEquals(41, storedNode.path("config").path("httpApiAssetId").asInt());
        assertEquals("params.orderId", storedNode.path("config").path("inputMapping")
                .path("pathParams.orderId").asText());
        // GraphSpec's typed CapabilityRef may serialize an absent pin as JSON null.
        assertTrue(storedNode.path("ref").path("contractHash").isMissingNode()
                || storedNode.path("ref").path("contractHash").isNull());
        assertFalse(stored.getGraphSpecJson().contains("credentialRef"));
        assertFalse(stored.getGraphSpecJson().contains("origin"));
        var reopened = result(http.perform(get("/api/workflows/{id}/working-copy", workflow.getId()))
                .andExpect(status().isOk()));
        assertEquals(stored.getGraphSpecJson(), reopened.path("graphSpecJson").asText());
        assertEquals(saved.path("revision").asText(), reopened.path("revision").asText());
    }

    @Test
    void browserSavedHttpApiGraphRoundTripsThroughPublicWorkingCopyAndMyBatis() throws Exception {
        // This resource is the complete graph/canvas captured after the Studio
        // browser's PUT, not a graph assembled by this persistence test.
        JsonNode browser;
        try (var stream = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(stream, "browser-saved working copy fixture is required");
            browser = json.readTree(stream);
        }
        assertEquals("fixture-r3", browser.path("browserRevision").asText());
        String browserGraph = browser.path("graphSpecJson").asText();
        String browserCanvas = browser.path("canvasJson").asText();
        assertEquals("1a058c9307076855668b19adc39b89ccf198a6726fdb0daabfdf70c28808516e",
                sha256(browserGraph));
        assertEquals("3fb3fc451dea10f41ee3ce9b2860c3f0706a6329f7112fbd3f3fbcc1180dab03",
                sha256(browserCanvas));
        assertEquals(browser.path("graphSha256").asText(), sha256(browserGraph));
        assertEquals(browser.path("canvasSha256").asText(), sha256(browserCanvas));
        var workflow = seed("USER", browser.path("projectId").asLong());

        var saved = result(save(workflow.getId(), Map.of("graphSpecJson", browserGraph,
                "canvasJson", browserCanvas, "baseRevision", revision(workflow)))
                .andExpect(status().isOk()));
        var stored = mapper.selectById(workflow.getId());
        var reopened = result(http.perform(get("/api/workflows/{id}/working-copy", workflow.getId()))
                .andExpect(status().isOk()));
        var canonicalizer = new RuntimeWorkflowDocumentCanonicalizer(json);
        assertEquals(canonicalizer.canonicalizeGraphSpecJson(browserGraph), stored.getGraphSpecJson());
        assertEquals(canonicalizer.canonicalizeCanvasJson(browserCanvas), stored.getCanvasJson());
        assertEquals(stored.getGraphSpecJson(), saved.path("graphSpecJson").asText());
        assertEquals(stored.getGraphSpecJson(), reopened.path("graphSpecJson").asText());
        assertEquals(stored.getCanvasJson(), reopened.path("canvasJson").asText());
        assertEquals(saved.path("revision").asText(), reopened.path("revision").asText());
        assertEquals(browser.path("projectId").asLong(), reopened.path("projectId").asLong());
        assertEquals(browser.path("projectCode").asText(), reopened.path("projectCode").asText());

        var sourceGraph = json.readTree(browserGraph);
        var sourceCanvas = json.readTree(browserCanvas);
        var readbackGraph = json.readTree(reopened.path("graphSpecJson").asText());
        var readbackCanvas = json.readTree(reopened.path("canvasJson").asText());
        assertExecutableApiToVariableEnd(sourceGraph, sourceCanvas);
        assertExecutableApiToVariableEnd(readbackGraph, readbackCanvas);
        var sourceApi = sourceGraph.path("nodes").get(0);
        var readbackApi = readbackGraph.path("nodes").get(0);
        assertEquals(sourceApi.path("ref").path("qualifiedName").asText(),
                readbackApi.path("ref").path("qualifiedName").asText());
        assertEquals(sourceApi.path("config").path("httpApiAssetId").asLong(),
                readbackApi.path("config").path("httpApiAssetId").asLong());
        assertEquals(sourceApi.path("config").path("inputMapping"),
                readbackApi.path("config").path("inputMapping"));
        assertEquals(sourceApi.path("config").path("outputAlias").asText(),
                readbackApi.path("config").path("outputAlias").asText());
        assertEquals(sourceGraph.path("nodes").get(1).path("config").path("assignments"),
                readbackGraph.path("nodes").get(1).path("config").path("assignments"));
        assertEquals(sourceGraph.path("edges").size(), readbackGraph.path("edges").size());
        for (int index = 0; index < sourceGraph.path("edges").size(); index++) {
            var sourceEdge = sourceGraph.path("edges").get(index);
            var readbackEdge = readbackGraph.path("edges").get(index);
            for (String field : List.of("id", "from", "to", "condition")) {
                assertEquals(sourceEdge.path(field), readbackEdge.path(field));
            }
        }
        assertEquals(sourceGraph.path("entryNodeId"), readbackGraph.path("entryNodeId"));
        assertEquals(sourceGraph.path("exitNodeIds"), readbackGraph.path("exitNodeIds"));
        assertFalse(browserGraph.contains("contractHash"));
        assertFalse(browserGraph.contains("origin"));
        assertTrue(readbackApi.path("ref").path("contractHash").isMissingNode()
                || readbackApi.path("ref").path("contractHash").isNull());
        System.out.println("BMAPI-3C-A browser-to-runtime: sourceGraphSha256=" + sha256(browserGraph)
                + ", readbackGraphSha256=" + sha256(reopened.path("graphSpecJson").asText())
                + ", sourceCanvasSha256=" + sha256(browserCanvas)
                + ", readbackCanvasSha256=" + sha256(reopened.path("canvasJson").asText()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SDK_SYNC", "AI_CODING", "SYSTEM_SEED"})
    void publicCreationCannotForgeACreationChannel(String channel) throws Exception {
        http.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("keySlug", "manual", "name", "来源", "creationChannel", channel))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WORKFLOW_FIELD_OWNED"));
        assertEquals(0L, mapper.selectCount(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DISABLED"})
    void creationCannotManufactureALifecycleStatus(String lifecycle) throws Exception {
        http.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("keySlug", "manual", "name", "状态", "status", lifecycle))))
                .andExpect(status().isBadRequest());
        assertEquals(0L, mapper.selectCount(null));
        assertEquals(0L, versions.selectCount(null));
    }

    @Test
    void updateCannotChangeOriginOrManufactureAPublication() throws Exception {
        var workflow = seed("USER");
        for (var change : List.of(Map.of("creationChannel", "AI_CODING"), Map.of("status", "ACTIVE"))) {
            var body = new LinkedHashMap<String, Object>(change);
            body.put("baseRevision", revision(workflow));
            update(workflow.getId(), body).andExpect(status().isBadRequest());
            assertEquals(workflow, mapper.selectById(workflow.getId()));
        }
        assertEquals(0L, versions.selectCount(null));
        assertEquals(0L, events.selectCount(null));
    }

    @Test
    void editableMetadataAndProjectFieldsKeepTheirWireNamesAndSourceEchoesAreReadOnly() throws Exception {
        var workflow = seed("USER");
        var changes = new LinkedHashMap<String, Object>();
        changes.put("baseRevision", revision(workflow));
        changes.put("id", "replacement-id"); changes.put("createdAt", "2000-01-01T00:00:00");
        changes.put("updatedAt", "2099-01-01T00:00:00"); changes.put("deletable", false);
        changes.put("projectId", 9L); changes.put("projectCode", "accounting");
        changes.put("name", "核算流程"); changes.put("description", "可编辑说明"); changes.put("keySlug", "accounting-manual");
        changes.put("workflowKind", "PAGE_ASSISTANT"); changes.put("executionEngine", "GRAPH_SPEC");
        changes.put("definitionAuthority", " user "); changes.put("creationChannel", "studio"); changes.put("status", "draft");
        changes.put("inputSchemaJson", "{\"type\":\"object\"}"); changes.put("outputSchemaJson", "{\"type\":\"string\"}");
        changes.put("defaultModelInstanceId", "model-new"); changes.put("defaultResourceConfigJson", "{\"mode\":\"query\"}");
        changes.put("extraJson", "{\"presentation\":\"中文说明\"}");
        var response = result(update(workflow.getId(), changes).andExpect(status().isOk()));
        assertEquals("核算流程", response.path("name").asText());
        assertEquals("PAGE_ASSISTANT", response.path("workflowKind").asText());
        var stored = mapper.selectById(workflow.getId());
        assertEquals(9L, stored.getProjectId()); assertEquals("accounting", stored.getProjectCode());
        assertEquals("USER", stored.getDefinitionAuthority()); assertEquals("STUDIO", stored.getCreationChannel());
        assertEquals("model-new", stored.getDefaultModelInstanceId());
        assertEquals("{\"presentation\":\"中文说明\"}", stored.getExtraJson());
        assertEquals(workflow.getCreatedAt(), stored.getCreatedAt());
        assertTrue(stored.getUpdatedAt().isAfter(workflow.getUpdatedAt()));
        assertNotEquals(2099, stored.getUpdatedAt().getYear());
        assertNull(mapper.selectById("replacement-id"));
        assertEquals(Set.of("id", "projectId", "projectCode", "keySlug", "name", "description", "workflowKind",
                "executionEngine", "graphSpecJson", "canvasJson", "inputSchemaJson", "outputSchemaJson", "defaultModelInstanceId",
                "defaultResourceConfigJson", "status", "definitionAuthority", "creationChannel", "extraJson", "createdAt", "updatedAt", "deletable"), fields(response));
    }

    @Test
    void listDetailAndSearchUseDetachedViewsWithTheExistingFilterAndPagingContract() throws Exception {
        var manual = seed("USER"); seed("SDK");
        var detail = result(http.perform(get("/api/workflows/{id}", manual.getId())).andExpect(status().isOk()));
        var list = result(http.perform(get("/api/workflows").param("projectId", "7").param("projectCode", "orders")
                .param("definitionAuthority", "USER").param("workflowKind", "GENERAL").param("status", "DRAFT"))
                .andExpect(status().isOk()));
        assertEquals(1, list.size()); assertEquals(manual.getId(), list.get(0).path("id").asText());
        assertTrue(list.get(0).path("deletable").asBoolean()); assertEquals(fields(detail), fields(list.get(0)));
        var page = result(http.perform(get("/api/workflows/search").param("keyword", "orders-user")
                .param("current", "0").param("size", "999")).andExpect(status().isOk()));
        assertEquals(1, page.path("total").asLong()); assertEquals(1, page.path("current").asInt());
        assertEquals(100, page.path("size").asInt()); assertEquals(fields(detail), fields(page.path("records").get(0)));
        var empty = result(http.perform(get("/api/workflows/search").param("keyword", "absent")));
        assertEquals(0, empty.path("total").asLong()); assertTrue(empty.path("records").isEmpty());
        var captured = management.findById(manual.getId()).orElseThrow();
        var capturedPage = management.search(7L, "orders", null, "USER", null, null, 1, 10);
        assertThrows(UnsupportedOperationException.class, () -> capturedPage.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> management.list(null, null, null, null, null).clear());
        update(manual.getId(), Map.of("name", "新内容", "baseRevision", revision(manual))).andExpect(status().isOk());
        assertEquals("原始定义", captured.getName()); assertEquals("原始定义", capturedPage.records().get(0).getName());
        http.perform(get("/api/workflows/missing")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothEditingRoutesRejectStaleAndMalformedRevisions(boolean studio) throws Exception {
        var workflow = seed("USER");
        update(workflow.getId(), Map.of("name", "已保存的新内容", "baseRevision", revision(workflow))).andExpect(status().isOk());
        var latest = mapper.selectById(workflow.getId());
        var body = new LinkedHashMap<String, Object>(Map.of("name", "过期覆盖", "graphSpecJson", GRAPH, "baseRevision", revision(workflow)));
        (studio ? save(workflow.getId(), body) : update(workflow.getId(), body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_WORKING_COPY_CONFLICT"))
                .andExpect(header().string("ETag", "\"" + revision(latest) + "\""));
        body.put("baseRevision", "invalid");
        (studio ? save(workflow.getId(), body) : update(workflow.getId(), body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKFLOW_BASE_REVISION_INVALID"));
        assertEquals(latest, mapper.selectById(workflow.getId()));
    }

    @Test
    void concurrentPublicAndStudioSavesAcceptOneRevisionOnlyOnce() throws Exception {
        var workflow = seed("USER");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return update(workflow.getId(), Map.of("name", "普通编辑", "baseRevision", revision(workflow))).andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return save(workflow.getId(), Map.of("name", "Studio 编辑", "graphSpecJson", GRAPH, "baseRevision", revision(workflow))).andReturn().getResponse().getStatus(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(Set.of(200, 409), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            assertTrue(Set.of("普通编辑", "Studio 编辑").contains(mapper.selectById(workflow.getId()).getName()));
            assertEquals(1L, references.selectCount(null));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void ownershipIsCheckedAgainstTheFreshLockedRowEvenWithAnEarlierMyBatisRead() {
        var workflow = seed("USER");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
        assertThrows(UnexpectedRollbackException.class, () -> tx.execute(status -> {
            assertEquals("USER", definitions.findById(workflow.getId()).orElseThrow().getDefinitionAuthority());
            try (var connection = db.jdbc().getDataSource().getConnection();
                 var sql = connection.prepareStatement("UPDATE runtime_workflow SET definition_authority = 'SDK', creation_channel = 'SDK_SYNC' WHERE id = ?")) {
                sql.setString(1, workflow.getId()); sql.executeUpdate();
                update(workflow.getId(), Map.of("name", "旧读覆盖", "baseRevision", revision(workflow)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WORKFLOW_DEFINITION_READ_ONLY"));
            } catch (Exception ex) { throw new RuntimeException(ex); }
            return null;
        }));
        assertEquals("SDK", mapper.selectById(workflow.getId()).getDefinitionAuthority());
        assertEquals("原始定义", mapper.selectById(workflow.getId()).getName());
    }

    @Test
    void referenceSqlFailureRollsBackTheDraftAndRevisionThroughThePublicBoundary() {
        var workflow = seed("USER");
        doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("reference write failed"); })
                .when(references).insertBatch(anyList());
        assertThrows(jakarta.servlet.ServletException.class, () -> update(workflow.getId(),
                Map.of("name", "未提交", "graphSpecJson", GRAPH.replace("完成", "新内容"), "baseRevision", revision(workflow))));
        assertEquals(workflow, mapper.selectById(workflow.getId()));
        assertEquals(1L, references.selectCount(null)); assertTrue(references.missingDraftIds(10).isEmpty());
    }

    @Test
    void publishedHistorySurvivesFurtherEditingAndRollbackWithTrustedActors() throws Exception {
        var workflow = seed("USER");
        var first = result(publish(workflow.getId(), "v1", revision(workflow)).andExpect(status().isOk()));
        var original = versions.selectById(first.path("id").asLong());
        assertEquals("platform:42", original.getPublishedBy());
        assertEquals(Set.of("id", "workflowId", "version", "snapshotJson", "graphSpecSnapshotJson", "canvasSnapshotJson",
                "rolloutPercent", "status", "publishedBy", "publishedAt", "note", "createdAt"), fields(first));
        var state = result(save(workflow.getId(), Map.of("graphSpecJson", GRAPH.replace("完成", "新草稿"), "name", "发布后编辑",
                "defaultModelInstanceId", "draft-model", "definitionAuthority", "USER", "creationChannel", "STUDIO",
                "baseRevision", revision(mapper.selectById(workflow.getId())))).andExpect(status().isOk()));
        assertEquals("ACTIVE", state.path("status").asText()); assertEquals("v1", state.path("activeVersion").path("version").asText());
        assertTrue(state.path("hasUnpublishedChanges").asBoolean());
        assertEquals(original, versions.selectById(original.getId()));
        var second = result(publish(workflow.getId(), "v2", state.path("revision").asText()).andExpect(status().isOk()));
        var draftBeforeRollback = mapper.selectById(workflow.getId());
        var rolledBack = result(http.perform(post("/api/workflows/{id}/versions/{version}/rollback", workflow.getId(), original.getId())
                .requestAttr(VerifiedInternalServiceAuth.REQUEST_ATTR, attested())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("baseRevision", revision(draftBeforeRollback)))))
                .andExpect(status().isOk()));
        assertEquals(original.getId(), rolledBack.path("id").asLong()); assertEquals(fields(first), fields(rolledBack));
        assertEquals(original, versions.selectById(original.getId()));
        assertEquals(draftBeforeRollback.getGraphSpecJson(), mapper.selectById(workflow.getId()).getGraphSpecJson());
        assertEquals("RETIRED", versions.selectById(second.path("id").asLong()).getStatus());
        var history = result(http.perform(get("/api/workflows/{id}/versions", workflow.getId())).andExpect(status().isOk()));
        assertEquals(2, history.size()); assertEquals(fields(first), fields(history.get(0)));
        assertEquals(3L, events.selectCount(null)); assertTrue(events.selectList(null).stream().allMatch(event -> "platform:42".equals(event.getActor())));
        http.perform(delete("/api/workflows/{id}", workflow.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void userDefinitionCreatedThroughAiCodingRetainsItsOriginWhenEditedInStudio() throws Exception {
        var workflow = seed("USER");
        db.jdbc().update("UPDATE runtime_workflow SET creation_channel = 'AI_CODING' WHERE id = ?", workflow.getId());
        save(workflow.getId(), Map.of("graphSpecJson", GRAPH, "creationChannel", "AI_CODING", "baseRevision", revision(mapper.selectById(workflow.getId()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.creationChannel").value("AI_CODING"));
        assertEquals("AI_CODING", mapper.selectById(workflow.getId()).getCreationChannel());
    }

    @Test
    void releaseEventFailureStillRollsBackVersionDraftAndReferences() {
        var workflow = seed("USER");
        doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("audit unavailable"); }).when(events).insert(any());
        assertThrows(jakarta.servlet.ServletException.class, () -> publish(workflow.getId(), "v1", revision(workflow)));
        assertEquals(workflow, mapper.selectById(workflow.getId())); assertEquals(0L, versions.selectCount(null));
        assertEquals(0L, events.selectCount(null)); assertEquals(1L, references.selectCount(null));
    }

    @Test
    void quickAccessDefinitionsRemainReadOnlyThroughBothManualEditingRoutes() throws Exception {
        var workflow = seed("USER");
        db.jdbc().update("UPDATE runtime_workflow SET creation_channel = 'AI_QUICK_ACCESS' WHERE id = ?", workflow.getId());
        var stored = mapper.selectById(workflow.getId());
        var body = Map.<String, Object>of("name", "越过只读入口", "graphSpecJson", GRAPH, "baseRevision", revision(stored));
        update(workflow.getId(), body).andExpect(status().isBadRequest());
        save(workflow.getId(), body).andExpect(status().isBadRequest());
        assertEquals(stored, mapper.selectById(workflow.getId()));
    }

    @Test
    void bodyActorCannotAuthorizeAReleaseWithoutVerifiedPlatformIdentity() throws Exception {
        var workflow = seed("USER");
        http.perform(post("/api/workflows/{id}/versions/publish", workflow.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("version", "v1", "baseRevision", revision(workflow), "publishedBy", "platform:42"))))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/workflows/{id}/versions/1/rollback", workflow.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("baseRevision", revision(workflow)))))
                .andExpect(status().isUnauthorized());
        assertEquals(0L, versions.selectCount(null)); assertEquals(0L, events.selectCount(null));
    }

    @Test
    void eligibleDraftDeletionKeepsTheExistingHttpAndIndexCleanupContract() throws Exception {
        var workflow = seed("USER");
        http.perform(delete("/api/workflows/{id}", workflow.getId())).andExpect(status().isNoContent());
        assertNull(mapper.selectById(workflow.getId())); assertEquals(0L, references.selectCount(null));
        http.perform(delete("/api/workflows/{id}", workflow.getId())).andExpect(status().isNotFound());
    }

    @Test
    void proposedValidationUsesCandidateDefaultsWithoutMutatingTheStoredDefinition() throws Exception {
        var workflow = seed("USER");
        when(validation.validateProposed(any(), any())).thenAnswer(call -> {
            var candidate = call.getArgument(0, RuntimeWorkflowDefinitionEntity.class);
            assertEquals("candidate-model", candidate.getDefaultModelInstanceId());
            candidate.setName("仅候选");
            return RuntimeWorkflowReleaseValidationResult.builder().warn("CANDIDATE_WARNING", "answer", "候选提示").build();
        });
        for (String id : List.of(workflow.getId(), "")) {
            http.perform(post("/api/workflows/runtime-validation").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("workflowId", id, "graphSpecJson", GRAPH, "executionEngine", "GRAPH_SPEC", "defaultModelInstanceId", "candidate-model"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.warnings[0].target").value("answer"));
        }
        assertEquals(workflow, mapper.selectById(workflow.getId())); assertEquals(1L, references.selectCount(null));
    }

    @Test
    void malformedCandidateReturnsTheOwnedValidationReportWithoutLoadingOrWritingAWorkflow() throws Exception {
        when(validation.readGraph(eq("broken"), any())).thenAnswer(call -> {
            call.getArgument(1, RuntimeWorkflowReleaseValidationResult.Builder.class).error("GRAPH_INVALID", null, "图定义无效");
            return null;
        });
        http.perform(post("/api/workflows/runtime-validation").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("workflowId", "missing", "graphSpecJson", "broken"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("GRAPH_INVALID"));
        verify(mapper, never()).selectById(any(java.io.Serializable.class));
        assertEquals(0L, mapper.selectCount(null));
    }

    private Set<String> fields(JsonNode node) {
        var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }

    private VerifiedInternalServiceAuth attested() {
        return new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "42");
    }

    private ResultActions publish(String id, String version, String baseRevision) throws Exception {
        return http.perform(post("/api/workflows/{id}/versions/publish", id)
                .requestAttr(VerifiedInternalServiceAuth.REQUEST_ATTR, attested()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("version", version, "baseRevision", baseRevision, "note", "发布说明", "publishedBy", "forged"))));
    }

    private RuntimeWorkflowDefinitionEntity seed(String authority) {
        return seed(authority, 7L);
    }

    private RuntimeWorkflowDefinitionEntity seed(String authority, long projectId) {
        var entity = new RuntimeWorkflowDefinitionEntity();
        entity.setKeySlug("orders-" + authority.toLowerCase()); entity.setName("原始定义");
        entity.setProjectId(projectId); entity.setProjectCode("orders");
        entity.setDefinitionAuthority(authority);
        entity.setCreationChannel("SDK".equals(authority) ? "SDK_SYNC" : "SYSTEM".equals(authority) ? "SYSTEM_SEED" : "STUDIO");
        entity.setGraphSpecJson(GRAPH);
        entity.setCanvasJson("{\"schemaVersion\":1,\"layoutVersion\":1,\"nodes\":[],\"edges\":[]}");
        return definitions.create(entity);
    }

    private String revision(RuntimeWorkflowDefinitionEntity workflow) { return workflow.getUpdatedAt().toString(); }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertExecutableApiToVariableEnd(JsonNode graph, JsonNode canvas) {
        assertEquals(2, graph.path("nodes").size());
        String apiId = graph.path("nodes").get(0).path("id").asText();
        String variableId = graph.path("nodes").get(1).path("id").asText();
        assertEquals("api-node", apiId);
        assertEquals("VARIABLE_ASSIGN", graph.path("nodes").get(1).path("type").asText());
        assertEquals(apiId, graph.path("entryNodeId").asText());
        assertEquals(1, graph.path("exitNodeIds").size());
        assertEquals(variableId, graph.path("exitNodeIds").get(0).asText());
        assertNotEquals(apiId, graph.path("exitNodeIds").get(0).asText(),
                "the executor stops immediately when the current node is an exit");

        var reachable = new TreeSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(graph.path("entryNodeId").asText());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!reachable.add(current)) continue;
            for (JsonNode edge : graph.path("edges")) {
                if (current.equals(edge.path("from").asText())) pending.add(edge.path("to").asText());
            }
        }
        assertEquals(Set.of(apiId, variableId), reachable,
                "the downstream assignment must be reachable before the Workflow exits");
        assertEquals(1, graph.path("edges").size());
        String apiToVariableEdgeId = graph.path("edges").get(0).path("id").asText();

        var canvasEdgeIds = new TreeSet<String>();
        for (JsonNode edge : canvas.path("edges")) canvasEdgeIds.add(edge.path("id").asText());
        assertEquals(3, canvasEdgeIds.size());
        assertTrue(canvasEdgeIds.contains("graph-entry-" + apiId));
        assertTrue(canvasEdgeIds.contains(apiToVariableEdgeId));
        assertTrue(canvasEdgeIds.stream().anyMatch(id -> id.startsWith("e-" + variableId + "-end-")));
        assertFalse(canvasEdgeIds.contains("graph-finish-" + apiId),
                "the canvas must not offer a shortcut that bypasses the assignment");
    }

    private ResultActions update(String id, Map<String, Object> body) throws Exception {
        return http.perform(put("/api/workflows/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private ResultActions save(String id, Map<String, Object> body) throws Exception {
        return http.perform(put("/api/workflows/{id}/working-copy", id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private JsonNode result(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
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
