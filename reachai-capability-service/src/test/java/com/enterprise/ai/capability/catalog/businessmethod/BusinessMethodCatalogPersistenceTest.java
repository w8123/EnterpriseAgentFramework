package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.*;
import com.enterprise.ai.agent.registry.RegistryContracts.*;
import com.enterprise.ai.capability.config.CapabilityMybatisPlusConfiguration;
import com.enterprise.ai.capability.registry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner persistence/pagination; technical projection rows cannot create or edit methods. */
class BusinessMethodCatalogPersistenceTest {
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private TransactionTemplate tx;
    private BusinessMethodAssetStore store;
    private BusinessMethodCatalogService catalog;
    private CapabilityChangePolicy policy;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void database() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:method_catalog_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource); tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("capability_scan_project", "capability_snapshot", "capability_diff_item",
                "capability_source_state", "capability_business_method_asset", "capability_business_method_revision",
                "capability_tool_definition", "capability_http_api_asset")) {
            var match = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;").matcher(baseline);
            assertTrue(match.find(), "baseline missing " + table);
            jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)CHARACTER SET \\w+|COLLATE \\w+", "")
                    .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                    .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
            var additions = Pattern.compile("CALL add_col_if_absent\\('" + table + "',\\s*'([^']+)',\\s*'((?:''|[^'])*)'\\);", Pattern.DOTALL).matcher(baseline);
            while (additions.find()) jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS `"
                    + additions.group(1) + "` " + additions.group(2).replace("''", "'")
                    .replaceAll("(?i)\\s+AFTER\\s+`[^`]+`", "").replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        }
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new CapabilityMybatisPlusConfiguration().capabilityMybatisPlusInterceptor());
        for (var type : List.of(BusinessMethodAssetMapper.class, BusinessMethodRevisionMapper.class, ScanProjectMapper.class,
                CapabilitySnapshotMapper.class, CapabilityDiffItemMapper.class, CapabilitySourceStateMapper.class,
                CapabilitySyncReceiptMapper.class,
                com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper.class,
                com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper.class)) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(datasource); factory.setConfiguration(configuration);
        session = new SqlSessionTemplate(factory.getObject()); policy = new CapabilityChangePolicy(json);
        store = new BusinessMethodAssetStore(session.getMapper(BusinessMethodAssetMapper.class), session.getMapper(BusinessMethodRevisionMapper.class),
                session.getMapper(CapabilitySnapshotMapper.class), session.getMapper(CapabilityDiffItemMapper.class), policy, json);
        var lifecycle = new CapabilityChangeLifecycle(session.getMapper(CapabilitySnapshotMapper.class), session.getMapper(CapabilityDiffItemMapper.class),
                session.getMapper(CapabilitySourceStateMapper.class), policy, session.getMapper(CapabilitySyncReceiptMapper.class));
        catalog = new BusinessMethodCatalogService(session.getMapper(BusinessMethodAssetMapper.class), store,
                session.getMapper(ScanProjectMapper.class), lifecycle);
        project(7L, "orders"); project(8L, "other");
    }

    @Test void ownerPagesCountFilterAndIsolateProjectsEvenWithFakeProjectionRows() throws Exception {
        seed(7L, "read", "A 查询订单", true); seed(7L, "cancel", "B 取消订单", false); seed(7L, "drift", "C 漂移订单", true);
        seed(8L, "cancel", "B 取消订单", true);
        jdbc.update("INSERT INTO capability_tool_definition(name,title,description,source,enabled,asset_type,project_id,qualified_name)"
                + " VALUES ('orders_fake','伪造方法','仅存在于投影','scanner',TRUE,'BUSINESS_METHOD',7,'orders:fake')");
        var first = catalog.page(1, 2, null, null, 7L); var second = catalog.page(2, 2, null, null, 7L);
        assertEquals(3L, first.getTotal()); assertEquals(List.of("read", "cancel"), first.getRecords().stream().map(v -> v.asset().getMethodCode()).toList());
        assertEquals("drift", second.getRecords().get(0).asset().getMethodCode());
        assertEquals("cancel", catalog.page(1, 20, null, false, 7L).getRecords().get(0).asset().getMethodCode());
        assertEquals(2L, catalog.page(1, 20, "取消订单", null, null).getTotal());
        assertEquals(1L, catalog.page(1, 20, "漂移", null, 7L).getTotal());
        assertTrue(catalog.find("orders:fake").isEmpty());
        assertEquals(HttpStatus.NOT_FOUND, new BusinessMethodCatalogInternalController(catalog).get("orders_fake").getStatusCode());
        assertNotEquals(catalog.find("orders:cancel").orElseThrow().asset().getId(), catalog.find("other:cancel").orElseThrow().asset().getId());
    }

    @Test void removingAllProjectionsKeepsMethodFactsAndSourceDriftVisible() throws Exception {
        seed(7L, "read", "查询订单", true);
        jdbc.update("DELETE FROM capability_tool_definition");
        var detail = new BusinessMethodCatalogInternalController(catalog).get("orders_read").getBody();
        assertNotNull(detail); assertEquals("查询订单", detail.title()); assertEquals("orders:read", detail.qualifiedName());
        assertEquals("/sdk/read", detail.endpointPath()); assertEquals("READY", detail.sourceAvailability());
        jdbc.update("UPDATE capability_source_state SET source_contract_hash=?", "b".repeat(64));
        assertEquals("CONTRACT_DRIFT", catalog.find("orders:read").orElseThrow().sourceAvailability());
        assertEquals(1L, catalog.page(1, 20, null, null, 7L).getTotal());
        jdbc.update("UPDATE capability_business_method_asset SET accepted_revision_id=NULL,status='UNACCEPTED'");
        assertEquals(0L, catalog.page(1, 20, null, null, 7L).getTotal());
    }

    @Test void summaryAggregatesAcceptedOwnersWithoutLoadingSourceRevisionsOrCountingProjections() throws Exception {
        seed(7L, "read", "查询订单", true);
        seed(7L, "cancel", "取消订单", false);
        seed(7L, "drift", "来源漂移的订单", true);
        seed(7L, "pending", "待接纳订单", true);
        seed(7L, "unaccepted", "未接纳订单", false);
        seed(8L, "read", "另一项目订单", true);
        jdbc.update("UPDATE capability_business_method_asset SET accepted_revision_id=NULL WHERE method_code='pending'");
        jdbc.update("UPDATE capability_business_method_asset SET status='UNACCEPTED' WHERE method_code='unaccepted'");
        jdbc.update("INSERT INTO capability_tool_definition(name,title,description,source,enabled,asset_type,project_id,qualified_name)"
                + " VALUES ('orders_fake','投影方法','无 owning asset','scanner',TRUE,'BUSINESS_METHOD',7,'orders:fake')");
        jdbc.update("UPDATE capability_business_method_revision SET invocation_hash=?", "b".repeat(64));

        assertEquals(new BusinessMethodCatalogSummary(3, 2, 1), catalog.summary(7L));
        assertEquals(new BusinessMethodCatalogSummary(1, 1, 0), catalog.summary(8L));
        assertEquals(new BusinessMethodCatalogSummary(4, 3, 1), catalog.summary(null));
        assertEquals(new BusinessMethodCatalogSummary(0, 0, 0), catalog.summary(99L));
        assertThrows(IllegalStateException.class, () -> catalog.find("orders:read"),
                "summary must not decode a source revision that the detail read correctly rejects");
        jdbc.update("DELETE FROM capability_tool_definition");
        assertEquals(new BusinessMethodCatalogSummary(3, 2, 1), catalog.summary(7L));
    }

    @Test void corruptedSourceRevisionIsRejectedInsteadOfFallingBackToAProjection() throws Exception {
        seed(7L, "read", "查询订单", true);
        jdbc.update("UPDATE capability_business_method_revision SET invocation_hash=?", "b".repeat(64));
        assertThrows(IllegalStateException.class, () -> catalog.find("orders:read"));
    }

    @Test void projectDeletionReadsOwnersWithoutProjectionsAndDoesNotBlockSourceRescans() throws Exception {
        seed(7L, "read", "查询订单", true);
        jdbc.update("UPDATE capability_business_method_asset SET status='REMOVED',enabled=FALSE WHERE project_id=7");
        jdbc.update("DELETE FROM capability_tool_definition");
        jdbc.update("INSERT INTO capability_http_api_asset(project_id,project_code,environment,identity_hash,qualified_name,http_method,route_template,mapping_conditions_json,status)"
                + " VALUES(7,'orders','dev',?,'orders:http:get','GET','/orders','{}','DISCOVERED')", "c".repeat(64));
        var referenceReader = org.mockito.Mockito.mock(com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader.class);
        var blockers = new com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectBlockerService(
                session.getMapper(com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper.class), referenceReader,
                session.getMapper(BusinessMethodAssetMapper.class),
                session.getMapper(com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper.class));

        var deletion = blockers.analyzeDeletion(7L);
        assertTrue(deletion.blocked());
        assertEquals(List.of("BUSINESS_METHOD", "HTTP_API"), deletion.assets().stream().map(a -> a.assetType()).toList());
        assertEquals(List.of("orders:read", "orders:http:get"), deletion.assets().stream().map(a -> a.qualifiedName()).toList());
        assertFalse(blockers.analyze(7L).blocked(), "owned assets must not prevent normal source rescanning");
        assertFalse(blockers.analyzeDeletion(8L).blocked(), "other projects must not inherit an asset blocker");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_asset WHERE project_id=7", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_revision", Integer.class));
        org.mockito.Mockito.verifyNoInteractions(referenceReader);
    }

    private void project(Long id, String code) {
        var entity = new ScanProjectEntity(); entity.setId(id); entity.setProjectCode(code); entity.setName(code);
        entity.setBaseUrl("http://orders.local"); entity.setScanPath("sdk:" + code); entity.setScanType("auto");
        session.getMapper(ScanProjectMapper.class).insert(entity);
    }

    private void seed(Long projectId, String code, String title, boolean enabled) throws Exception {
        var project = session.getMapper(ScanProjectMapper.class).selectById(projectId);
        var declaration = policy.normalize(project, List.of(new CapabilityRegistration(code, title, "查询业务数据", "POST",
                "http://orders.local", null, "/sdk/" + code, null, null, "READ_ONLY", enabled, List.of(), Map.of("assetType", "BUSINESS_METHOD")))).get(0);
        var snapshot = new CapabilitySnapshotEntity(); snapshot.setProjectId(projectId); snapshot.setProjectCode(project.getProjectCode());
        snapshot.setSyncId(UUID.randomUUID().toString()); snapshot.setIntakeMode("SOURCE");
        snapshot.setPayloadJson(json.writeValueAsString(new CapabilitySyncRequest(snapshot.getSyncId(), "sdk", false, List.of(declaration))));
        session.getMapper(CapabilitySnapshotMapper.class).insert(snapshot);
        var diff = new CapabilityDiffItemEntity(); diff.setSnapshotId(snapshot.getId()); diff.setSyncId(snapshot.getSyncId());
        diff.setProjectId(projectId); diff.setProjectCode(project.getProjectCode()); diff.setQualifiedName(project.getProjectCode() + ":" + code);
        diff.setName(code); diff.setStorageName(BusinessMethodAssetStore.invocationName(project.getProjectCode(), code));
        diff.setChangeType("ADDED"); diff.setIntakeMode("SOURCE"); diff.setReviewStatus("PENDING");
        session.getMapper(CapabilityDiffItemMapper.class).insert(diff);
        tx.execute(status -> store.accept(project, declaration, snapshot.getId(), diff.getId()));
        var source = new CapabilitySourceStateEntity(); source.setProjectId(projectId); source.setProjectCode(project.getProjectCode());
        source.setQualifiedName(diff.getQualifiedName()); source.setSnapshotId(snapshot.getId()); source.setDiffItemId(diff.getId());
        source.setSourceContractHash(policy.contractHash(declaration)); source.setAcceptedContractHash(policy.contractHash(declaration)); source.setAvailability("READY");
        source.setObservedAt(java.time.LocalDateTime.now());
        session.getMapper(CapabilitySourceStateMapper.class).insert(source);
    }
}
