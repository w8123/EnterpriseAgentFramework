package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.enterprise.ai.agent.capability.catalog.semantic.*;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticDocumentStore;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphGroupingService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphRegenerateService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphOperationsService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphSnapshotService;
import com.enterprise.ai.capability.catalog.scan.CapabilityModelClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.enterprise.ai.agent.capability.catalog.graph.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** New-system lifecycle against baseline SQL and real Mapper/transaction boundaries. */
class CatalogEnrichmentPersistenceTest {
    JdbcTemplate jdbc;
    SemanticDocMapper docs;
    ScanProjectToolMapper observations;
    ScanModuleMapper modules;
    CatalogGroupMemberMapper members;
    ApiGraphNodeMapper graphNodes;
    ApiGraphEdgeMapper graphEdges;
    ApiGraphLayoutMapper graphLayouts;
    ScanProjectMapper projects;
    DataSourceTransactionManager tx;
    CatalogSubjectResolver subjects;
    CapabilitySemanticDocumentStore store;
    CapabilityCatalogGroupingService grouping;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void database() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:enrichment_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("capability_scan_project", "capability_scan_module", "capability_scan_project_tool", "capability_semantic_doc", "capability_catalog_group_member", "capability_catalog_classification_state", "capability_api_graph_node", "capability_api_graph_edge", "capability_api_graph_layout")) {
            var match = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;").matcher(baseline);
            assertTrue(match.find(), table);
            jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");").replaceAll("(?i)CHARACTER SET \\w+|COLLATE \\w+", "")
                    .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`").replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
            var additions = Pattern.compile("CALL add_col_if_absent\\('" + table + "',\\s*'([^']+)',\\s*'((?:''|[^'])*)'\\);", Pattern.DOTALL).matcher(baseline);
            while (additions.find()) jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS `" + additions.group(1) + "` "
                    + additions.group(2).replace("''", "'").replaceAll("(?i)\\s+AFTER\\s+`[^`]+`", "").replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        }
        jdbc.update("INSERT INTO capability_scan_project(id,project_code,name,base_url,scan_path,scan_type) VALUES"
                + "(7,'orders','Orders','http://fixture.invalid','','controller'),(8,'other','Other','http://fixture.invalid','','controller')");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(ScanProjectMapper.class, ScanModuleMapper.class, ScanProjectToolMapper.class, SemanticDocMapper.class, CatalogGroupMemberMapper.class, CatalogClassificationStateMapper.class, ApiGraphNodeMapper.class, ApiGraphEdgeMapper.class, ApiGraphLayoutMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject()); docs = session.getMapper(SemanticDocMapper.class);
        observations = session.getMapper(ScanProjectToolMapper.class); modules = session.getMapper(ScanModuleMapper.class); members = session.getMapper(CatalogGroupMemberMapper.class);
        graphNodes = session.getMapper(ApiGraphNodeMapper.class); graphEdges = session.getMapper(ApiGraphEdgeMapper.class); graphLayouts = session.getMapper(ApiGraphLayoutMapper.class);
        projects = session.getMapper(ScanProjectMapper.class); subjects = mock(CatalogSubjectResolver.class);
        tx = new DataSourceTransactionManager(ds);
        grouping = transactional(new CapabilityCatalogGroupingService(modules, projects, observations, members,session.getMapper(CatalogClassificationStateMapper.class),
                transactional(new CapabilityApiGraphGroupingService(graphNodes), tx), json), tx);
        store = transactional(new CapabilitySemanticDocumentStore(docs, projects, subjects, grouping), tx);
    }

    @Test void typedSubjectsWithSameIdRemainSeparateAndMicrosecondVersionRejectsOldEdits() {
        CatalogSubject method = owner("BUSINESS_METHOD", List.of()); CatalogSubject api = owner("HTTP_API", List.of());
        when(subjects.resolve(7L, "BUSINESS_METHOD", 21L)).thenReturn(method); when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(api);
        var methodDoc = document(method, "Method", "hash"); var apiDoc = document(api, "API", "hash");
        store.save(methodDoc, null); store.save(apiDoc, null);
        assertEquals(2, store.list(7L).size());
        assertTrue(store.list(7L).stream().allMatch(row -> row.getContentMd() == null), "index SQL must not retrieve document bodies");
        String previous = CapabilitySemanticCatalogService.version(methodDoc);
        var edited = store.find(method).orElseThrow(); edited.setContentMd("manual edit");
        // Even a caller clock equal to the persisted tick must advance the DB revision.
        edited.setUpdateTime(methodDoc.getUpdateTime()); store.save(edited, previous);
        var current = store.find(method).orElseThrow(); assertTrue(current.getUpdateTime().isAfter(methodDoc.getUpdateTime()));
        var stale = document(method, "stale overwrite", "hash"); stale.setId(methodDoc.getId());
        assertThrows(IllegalStateException.class, () -> store.save(stale, previous));
        assertEquals("manual edit", store.find(method).orElseThrow().getContentMd());
        assertEquals("API", store.find(api).orElseThrow().getContentMd());
        jdbc.update("UPDATE capability_semantic_doc SET subject_key='foreign:identity' WHERE id=?", methodDoc.getId());
        assertThrows(IllegalStateException.class, () -> store.find(method));
        assertEquals("API", store.find(api).orElseThrow().getContentMd());
    }

    @Test void modelGenerationRunsOutsideShortDocumentTransactionsAndDatabaseVersionRoundTrips() {
        var owner = owner("BUSINESS_METHOD", List.of()); when(subjects.resolve(7L, owner.subjectType(), 21L)).thenReturn(owner);
        var model = mock(CapabilityModelClient.class);
        when(model.chat(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "model latency cannot hold the catalog project transaction");
            return com.enterprise.ai.common.dto.ApiResult.ok(new CapabilityModelClient.ChatResponse("生成说明", "model", null, null));
        });
        var semantic = new CapabilitySemanticCatalogService(store, subjects, grouping, model, json);
        var created = semantic.generate(7L, owner.subjectType(), 21L, true, "model");
        assertEquals(created.getUpdateTime(), store.find(owner).orElseThrow().getUpdateTime());
        var edited = semantic.edit(7L, owner.subjectType(), 21L, "人工确认说明", created.getUpdateTime().toString());
        assertEquals(edited.getUpdateTime(), store.find(owner).orElseThrow().getUpdateTime()); assertTrue(edited.getBasisAccepted());
        assertThrows(IllegalStateException.class, () -> semantic.edit(7L, owner.subjectType(), 21L, "陈旧覆盖", created.getUpdateTime().toString()));
    }

    private void adopt(CatalogSubject owner) {
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
            projects.lockCapabilityChanges(owner.projectId());
            store.adoptAcceptedOwner(owner.projectId(), owner.subjectType(), owner.subjectId());
        });
    }

    @Test void candidateProseIsAdoptedOnceUnderProjectTransactionAndKeepsOldFingerprint() {
        var observation = observation(41L, "OrdersController"); var owner = owner("HTTP_API", List.of(observation));
        when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(owner);
        var candidate = document(new CatalogSubject("SOURCE_OBSERVATION", 41L, 7L, "source-observation:41", "Candidate", "", "old", false, "GET", "/orders", List.of(), List.of(observation)), "candidate text", "old");
        docs.insert(candidate);
        assertTrue(store.find(owner).isEmpty(), "GET must not adopt a candidate");
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> adopt(owner)), CompletableFuture.runAsync(() -> adopt(owner))).join();
        var adopted = store.find(owner).orElseThrow();
        assertEquals(candidate.getId(), adopted.getId()); assertEquals("old", adopted.getBasisContractHash()); assertEquals("candidate text", adopted.getContentMd());
        assertEquals(1, store.list(7L).size());
    }

    @Test void mergingCandidateIntoExistingOwnerRetainsBothTextsAndMarksWholeProseStale() {
        var observation = observation(41L, "OrdersController"); var owner = owner("HTTP_API", List.of(observation));
        when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(owner);
        docs.insert(document(owner, "owner text", "hash"));
        docs.insert(document(new CatalogSubject("SOURCE_OBSERVATION", 41L, 7L, "source-observation:41", "Candidate", "", "old", false, "GET", "/orders", List.of(), List.of(observation)), "candidate text", "old"));
        assertEquals("owner text", store.find(owner).orElseThrow().getContentMd(), "GET must not merge candidate text");
        adopt(owner);
        var merged = store.find(owner).orElseThrow(); String combined = merged.getContentMd();
        assertTrue(combined.contains("owner text")); assertTrue(combined.contains("candidate text")); assertNull(merged.getBasisContractHash());
        assertEquals("edited", merged.getStatus()); var reread = store.find(owner).orElseThrow(); assertNull(reread.getBasisContractHash());
        assertEquals(combined, reread.getContentMd()); assertEquals(1, store.list(7L).size());
    }

    @Test void sdkClassFallbackAndDeclaredModuleKeepTheirGroupsWhileScannerUsesItsSourceClass() {
        var first = observation(41L, "unused"); first.setAssetType("BUSINESS_METHOD"); first.setSourceLocation("sdk:orders:read");
        first.setCapabilityMetadataJson("{\"module\":\"common\",\"className\":\"fixture.OrdersService\"}"); observations.insert(first);
        var fallback = observation(42L, "unused"); fallback.setAssetType("BUSINESS_METHOD"); fallback.setSourceLocation("sdk:orders:count");
        fallback.setCapabilityMetadataJson("{\"module\":\" \",\"className\":\"fixture.OrdersService\"}"); observations.insert(fallback);
        var scanned = observation(43L, "OrdersController"); scanned.setCapabilityMetadataJson("{\"module\":\"common\"}"); observations.insert(scanned);
        grouping.bootstrap(7L, List.of(first, fallback, scanned));
        assertEquals(3, grouping.list(7L).size());
        var declared = modules.selectById(first.getModuleId()); var byClass = modules.selectById(fallback.getModuleId());
        assertEquals("common", declared.getName()); assertEquals("fixture.OrdersService", byClass.getName());
        assertEquals("OrdersController", modules.selectById(scanned.getModuleId()).getName());
        grouping.rename(7L, declared.getId(), "公开订单实验");
        fallback = observations.selectById(42L); fallback.setModuleId(null);
        grouping.bootstrap(7L, List.of(fallback));
        assertEquals(byClass.getId(), observations.selectById(42L).getModuleId());
        assertEquals("公开订单实验", modules.selectById(declared.getId()).getDisplayName());
        assertEquals(3, grouping.list(7L).size());
    }

    @Test void renameKeepsSourceGroupsAndAdditionalMembershipAndRejectsCrossProjectChanges() {
        ScanModuleEntity first = group("OrdersController"), second = group("PaymentsController"); modules.insert(first); modules.insert(second);
        var row = observation(41L, "PaymentsController"); row.setModuleId(second.getId()); observations.insert(row);
        CatalogSubject method = owner("BUSINESS_METHOD", List.of(row)); grouping.assign(method, first.getId());
        grouping.rename(7L, first.getId(), "Order operations");
        assertNotNull(modules.selectById(second.getId()));
        assertEquals(List.of("OrdersController"), grouping.parseClasses(modules.selectById(first.getId()).getSourceClasses()));
        row = observations.selectById(41L); row.setModuleId(null); grouping.bootstrap(7L, List.of(row));
        assertEquals(second.getId(), observations.selectById(41L).getModuleId()); assertEquals(2, grouping.list(7L).size());
        assertEquals(List.of(first.getId()), grouping.groups(method).stream().map(ScanModuleEntity::getId).toList());
        assertThrows(IllegalArgumentException.class, () -> grouping.rename(8L, first.getId(), "foreign"));
        assertThrows(IllegalArgumentException.class, () -> grouping.assign(owner("BUSINESS_METHOD", List.of()), 999L));
        assertEquals("Order operations", modules.selectById(first.getId()).getDisplayName());
    }

    @Test void groupMemberReadUsesOnlyPersistedFormalOwnersInTheRequestedProjectAndGroup() {
        var selected = group("Orders"); var other = group("Reporting"); modules.insert(selected); modules.insert(other);
        for (var entry : List.of(new Object[]{7L, selected.getId(), "BUSINESS_METHOD", 11L},
                new Object[]{7L, selected.getId(), "HTTP_API", 21L},
                new Object[]{7L, selected.getId(), "SOURCE_OBSERVATION", 41L},
                new Object[]{7L, other.getId(), "BUSINESS_METHOD", 12L},
                new Object[]{8L, selected.getId(), "BUSINESS_METHOD", 13L})) {
            var member = new CatalogGroupMemberEntity(); member.setProjectId((Long) entry[0]); member.setGroupId((Long) entry[1]);
            member.setSubjectType((String) entry[2]); member.setSubjectId((Long) entry[3]); members.insert(member);
        }
        assertEquals(new CapabilityCatalogGroupingService.OwnerMemberIds(List.of(11L), List.of(21L)),
                grouping.formalMemberIds(7L, selected.getId()));
    }

    @Test void renameUpdatesOnlySelectedGraphLabelAndPreservesManualRelationsAndLayout() {
        var target = group("OrdersController"); var source = group("PaymentsController"); modules.insert(target); modules.insert(source);
        var targetNode = groupNode(target.getId()); var sourceNode = groupNode(source.getId()); graphNodes.insert(targetNode); graphNodes.insert(sourceNode);
        var operation = new ApiGraphNodeEntity(); operation.setProjectId(7L); operation.setKind("API"); operation.setNodeKey("a".repeat(64)); operation.setLabel("payment"); operation.setActive(true); operation.setParentId(sourceNode.getId()); graphNodes.insert(operation);
        var relation = new ApiGraphEdgeEntity(); relation.setProjectId(7L); relation.setSourceNodeId(sourceNode.getId()); relation.setTargetNodeId(operation.getId()); relation.setKind("BELONGS_TO"); relation.setSource("manual"); relation.setEnabled(true); relation.setStatus("CONFIRMED"); graphEdges.insert(relation);
        var layout = new ApiGraphLayoutEntity(); layout.setProjectId(7L); layout.setNodeId(sourceNode.getId()); layout.setX(11d); layout.setY(22d); graphLayouts.insert(layout);
        grouping.rename(7L, target.getId(), "Order operations");
        assertEquals("Order operations", graphNodes.selectById(targetNode.getId()).getLabel());
        assertEquals("Group", graphNodes.selectById(sourceNode.getId()).getLabel());
        assertEquals(sourceNode.getId(), graphNodes.selectById(operation.getId()).getParentId());
        var retained = graphEdges.selectById(relation.getId()); assertEquals(sourceNode.getId(), retained.getSourceNodeId()); assertTrue(retained.getEnabled()); assertEquals("CONFIRMED", retained.getStatus());
        assertTrue(graphNodes.selectById(sourceNode.getId()).getActive()); assertNotNull(graphLayouts.selectById(layout.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capability_api_graph_layout", Integer.class));
    }

    @Test void candidateManualGroupingBecomesOwnerReferenceAndSurvivesObservationCleanup() {
        var group = group("Order operations"); modules.insert(group); var observation = observation(41L, "OrdersController"); observations.insert(observation);
        var candidate = new CatalogSubject("SOURCE_OBSERVATION", 41L, 7L, "source-observation:41", "Candidate", "", "old", false, "GET", "/orders", List.of(), List.of(observation));
        grouping.assign(candidate, group.getId()); var owner = owner("HTTP_API", List.of(observation)); when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(owner);
        assertTrue(store.find(owner).isEmpty()); adopt(owner); observations.deleteById(41L);
        assertEquals(group.getId(), grouping.groups(owner("HTTP_API", List.of())).get(0).getId());
        assertEquals("HTTP_API", jdbc.queryForObject("SELECT subject_type FROM capability_catalog_group_member", String.class));
        assertEquals(21L, jdbc.queryForObject("SELECT subject_id FROM capability_catalog_group_member", Long.class));
    }

    @Test void legalGroupNameAndLongHttpOperationLabelRoundTripThroughRealGraphProjection() {
        var group = group("OrdersController"); modules.insert(group); var node = groupNode(group.getId()); graphNodes.insert(node);
        String fullName = "分".repeat(256); grouping.rename(7L, group.getId(), fullName);
        assertEquals(fullName, modules.selectById(group.getId()).getDisplayName()); assertEquals(fullName, graphNodes.selectById(node.getId()).getLabel());
        String route = "/orders/" + "segment/".repeat(50), label = "GET " + route;
        var api = new CatalogSubject("HTTP_API", 21L, 7L, "http-api:orders:default:long", label, "", "hash", true, "GET", route, List.of(), List.of());
        when(subjects.operations(7L)).thenReturn(List.of(api));
        var projection = transactional(new CapabilityApiGraphRegenerateService(graphNodes, graphEdges, graphLayouts, subjects, grouping, store, projects,
                mock(CapabilityApiGraphOperationsService.class), mock(CapabilityApiGraphSnapshotService.class), json), tx);
        projection.rebuild(7L);
        assertEquals(label, jdbc.queryForObject("SELECT label FROM capability_api_graph_node WHERE project_id=7 AND kind='API' AND active=1", String.class));
        assertEquals(fullName, graphNodes.selectById(node.getId()).getLabel());
    }

    @SuppressWarnings("unchecked")

    @Test void initialHttpClassificationSurvivesResyncAndManualEmptyIsAuthoritative() {
        grouping.seedHttpApi(7L, 21L, List.of("字典查询与统计"), "STARTER_MVC", "mvc:app.DictionaryController#count()");
        var id = grouping.ownerGroupIds(7L, "HTTP_API", 21L).get(0);
        assertEquals("字典查询与统计", modules.selectById(id).getDisplayName());
        grouping.seedHttpApi(7L, 21L, List.of("Changed source"), "STARTER_MVC", "mvc:app.NewController#count()");
        assertEquals(List.of(id), grouping.ownerGroupIds(7L, "HTTP_API", 21L));
        assertEquals(List.of(id), grouping.groups(owner("HTTP_API", List.of())).stream().map(ScanModuleEntity::getId).toList(), "no source is needed to retain classification");
        var editor = transactional(new CatalogClassificationEditorService(subjects, grouping, projects), tx);
        when(subjects.ownerIds(eq(7L), eq("HTTP_API"), anySet())).thenReturn(java.util.Set.of(21L));
        var before = editor.read(7L, "HTTP_API", 21L);
        editor.replace(7L, "HTTP_API", new CatalogClassificationEditorService.EditRequest(
                List.of(new CatalogClassificationEditorService.ExpectedSubject(21L, before.revision())), List.of(), List.of()));
        grouping.seedHttpApi(7L, 21L, List.of("字典查询与统计"), "STARTER_MVC", "mvc:app.DictionaryController#count()");
        assertEquals(List.of(), grouping.ownerGroupIds(7L, "HTTP_API", 21L));
        assertTrue(editor.read(7L, "HTTP_API", 21L).revision() > before.revision());
        assertEquals(1, grouping.list(7L).size());
    }

    @Test void batchValidatesAllOwnersAndRevisionsAndRollsBackCreatedCategories() {
        var first = owner("HTTP_API", List.of());
        var second = new CatalogSubject("HTTP_API", 22L, 7L, "second", "Second", "", "hash", true, "GET", "/second", List.of(), List.of());
        when(subjects.ownerIds(eq(7L), eq("HTTP_API"), anySet())).thenAnswer(call -> { var ids = new java.util.HashSet<Long>(call.getArgument(2)); ids.retainAll(java.util.Set.of(21L, 22L)); return ids; });
        var editor = transactional(new CatalogClassificationEditorService(subjects, grouping, projects), tx);
        var subjectsToEdit = List.of(new CatalogClassificationEditorService.ExpectedSubject(21L, 0L), new CatalogClassificationEditorService.ExpectedSubject(22L, 0L));
        assertThrows(IllegalArgumentException.class, () -> editor.replace(7L, "HTTP_API", new CatalogClassificationEditorService.EditRequest(
                subjectsToEdit, List.of(), List.of("created then rolled back", " "))));
        assertEquals(0, grouping.list(7L).size()); assertEquals(0, grouping.revision(7L, "HTTP_API", 21L));
        var saved = editor.replace(7L, "HTTP_API", new CatalogClassificationEditorService.EditRequest(subjectsToEdit, List.of(), List.of("共享业务", "共享业务")));
        assertEquals(1, grouping.list(7L).size()); assertEquals(saved.get(0).groupIds(), saved.get(1).groupIds());
        assertThrows(IllegalStateException.class, () -> editor.replace(7L, "HTTP_API", new CatalogClassificationEditorService.EditRequest(subjectsToEdit, List.of(), List.of("must not create"))));
        assertThrows(IllegalArgumentException.class, () -> editor.replace(7L, "HTTP_API", new CatalogClassificationEditorService.EditRequest(
                List.of(new CatalogClassificationEditorService.ExpectedSubject(99L, 0L)), List.of(), List.of("foreign"))));
        assertThrows(IllegalArgumentException.class, () -> editor.replace(7L, "BUSINESS_METHOD", new CatalogClassificationEditorService.EditRequest(subjectsToEdit, List.of(), List.of("wrong type"))));
        assertEquals(1, grouping.list(7L).size()); assertEquals(saved.get(0).groupIds(), grouping.ownerGroupIds(7L, "HTTP_API", 21L));
    }

    @Test void fallbackControllerAndExplicitLabelsShareExistingProjectCategories() {
        grouping.seedHttpApi(7L, 21L, List.of(), "STARTER_MVC", "mvc:app.DictionaryController#count()");
        assertEquals("DictionaryController", grouping.list(7L).get(0).getDisplayName());
        var existing = grouping.create(7L, "字典查询与统计");
        grouping.seedHttpApi(7L, 22L, List.of("字典查询与统计"), "OPENAPI_SCAN", "spec.json#/paths/~1dictionary/get");
        assertEquals(List.of(existing.getId()), grouping.ownerGroupIds(7L, "HTTP_API", 22L));
        grouping.seedOwner(7L, "BUSINESS_METHOD", 22L, List.of(existing.getId()));
        grouping.replaceOwner(7L, "HTTP_API", 22L, List.of(), true);
        assertEquals(List.of(existing.getId()), grouping.ownerGroupIds(7L, "BUSINESS_METHOD", 22L));
        assertEquals(2, grouping.list(7L).size());
        assertEquals(0, grouping.list(8L).size());
    }

    @Test void standaloneEmptyClassificationDeduplicatesConcurrentNamesAndRemainsProjectShared() {
        var first = CompletableFuture.supplyAsync(() -> grouping.create(7L, "  订单 Orders  "));
        var second = CompletableFuture.supplyAsync(() -> grouping.create(7L, "订单 orders"));
        var created = first.join(); var repeated = second.join();
        assertEquals(created.getId(), repeated.getId());
        assertEquals(1, grouping.list(7L).size());
        assertTrue(created.getName().startsWith("category:"));
        assertEquals("订单 orders", created.getDisplayName().toLowerCase(java.util.Locale.ROOT));
        assertEquals(List.of(), grouping.parseClasses(created.getSourceClasses()));
        var foreign = grouping.create(8L, "订单 orders");
        assertNotEquals(created.getId(), foreign.getId());
        assertEquals(1, grouping.list(8L).size());
        for (String table : List.of("capability_catalog_group_member", "capability_catalog_classification_state",
                "capability_scan_project_tool", "capability_api_graph_node")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
        }
    }

    @Test void standaloneClassificationAcceptsTrimmedBoundaryAndRejectsInvalidNamesBeforeInserting() {
        String boundary = "分".repeat(128);
        var created = grouping.create(7L, "  " + boundary + "  ");
        assertEquals(boundary, modules.selectById(created.getId()).getDisplayName(), "Chinese name must round trip without corruption");
        for (String invalid : java.util.Arrays.asList(null, "", "  ", "分".repeat(129), "分类\n名称", "\t分类", "分类\u007f")) {
            assertThrows(IllegalArgumentException.class, () -> grouping.create(7L, invalid));
        }
        assertEquals(1, grouping.list(7L).size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capability_catalog_group_member", Integer.class));
    }

    private <T> T transactional(T target, DataSourceTransactionManager tx) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(tx); interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }
    private CatalogSubject owner(String type, List<ScanProjectToolEntity> observations) { return new CatalogSubject(type, 21L, 7L, "orders:" + type, "Orders", "", "hash", true, "GET", "/orders", List.of(), observations); }
    private SemanticDocEntity document(CatalogSubject subject, String content, String basis) {
        var doc = new SemanticDocEntity(); doc.setProjectId(subject.projectId()); doc.setSubjectType(subject.subjectType()); doc.setSubjectId(subject.subjectId()); doc.setSubjectKey(subject.subjectKey());
        doc.setContentMd(content); doc.setBasisContractHash(basis); doc.setBasisAccepted(subject.accepted()); doc.setStatus("edited"); doc.setUpdateTime(LocalDateTime.of(2026,10,8,1,2,3,123456000)); doc.setCreateTime(doc.getUpdateTime()); return doc;
    }
    private ScanProjectToolEntity observation(Long id, String sourceClass) { var row = new ScanProjectToolEntity(); row.setId(id); row.setProjectId(7L); row.setName("source_" + id); row.setTitle("Source " + id); row.setDescription("来源说明"); row.setAssetType("HTTP_API"); row.setSourceLocation("src#" + sourceClass + "#find"); return row; }
    private ScanModuleEntity group(String name) { var group = new ScanModuleEntity(); group.setProjectId(7L); group.setName(name); group.setDisplayName(name); group.setSourceClasses("[\"" + name + "\"]"); return group; }
    private ApiGraphNodeEntity groupNode(Long groupId) { var node = new ApiGraphNodeEntity(); node.setProjectId(7L); node.setKind("MODULE"); node.setRefId(groupId); node.setNodeKey(CatalogSubjectResolver.digest("group:" + groupId)); node.setLabel("Group"); node.setActive(true); return node; }
}
