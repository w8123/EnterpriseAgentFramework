package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.enterprise.ai.agent.capability.catalog.tool.definition.*;
import com.enterprise.ai.agent.registry.*;
import com.enterprise.ai.agent.registry.RegistryContracts.*;
import com.enterprise.ai.capability.internal.CapabilityHttpToolInvoker;
import com.enterprise.ai.capability.internal.CapabilityInvocationPolicyException;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.businessmethod.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises real MyBatis persistence and transactions with MySQL-mode H2, not mock mapper results. */
class CapabilityChangePersistenceTest {
    CapabilityRegistryService registry;
    RegistrySecurityService registrySecurity;
    CapabilityCatalogProjectionStore projections;
    CapabilitySourceIntakeService intake;
    CapabilityReviewEvidenceStore evidence;
    RegistryInstanceLifecycleService instanceLifecycle;
    AnnotationConfigApplicationContext context;

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @AfterEach void closeContext() { if (context != null) context.close(); }
    CapabilityChangeLifecycle lifecycle;
    CapabilityChangePolicy policy;
    CapabilitySourceContractGuard guard;
    ScanProjectMapper projects;
    ToolDefinitionMapper tools;
    CapabilityDiffItemMapper differences;
    CapabilitySnapshotMapper snapshots;
    CapabilitySourceStateMapper sources;
    BusinessMethodAssetStore methodAssets;
    BusinessMethodAssetMapper methodAssetMapper;
    RegistryCredentialMapper credentials;
    TransactionTemplate tx;
    JdbcTemplate jdbc;

    @BeforeEach void database() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:changes_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("capability_scan_project", "capability_scan_project_tool", "capability_tool_definition",
                "capability_project_instance", "capability_sync_log", "capability_snapshot", "capability_sync_receipt", "capability_diff_item", "capability_apply_record", "capability_source_state",
                "capability_http_api_asset", "capability_http_api_source_binding",
                "capability_business_method_asset", "capability_business_method_revision", "capability_registry_project_credential")) {
            var match = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;", Pattern.DOTALL).matcher(baseline);
            assertTrue(match.find(), "baseline missing " + table);
            String sql = match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)CHARACTER SET \\w+", "").replaceAll("(?i)COLLATE \\w+", "")
                    .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                    .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"); // MySQL JDBC returns raw JSON; H2 JSON otherwise double-encodes setString.
            jdbc.execute(sql);
            var additions = Pattern.compile("CALL add_col_if_absent\\('" + table + "',\\s*'([^']+)',\\s*'((?:''|[^'])*)'\\);", Pattern.DOTALL).matcher(baseline);
            while (additions.find()) {
                String definition = additions.group(2).replace("''", "'").replaceAll("(?i)\\s+AFTER\\s+`[^`]+`", "")
                        .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
                jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS `" + additions.group(1) + "` " + definition);
            }
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(ScanProjectMapper.class, ScanProjectToolMapper.class, ToolDefinitionMapper.class,
                ProjectInstanceMapper.class, CapabilitySyncLogMapper.class, CapabilitySnapshotMapper.class,
                CapabilityDiffItemMapper.class, CapabilityApplyRecordMapper.class, CapabilitySourceStateMapper.class, CapabilitySyncReceiptMapper.class,
                HttpApiAssetMapper.class, HttpApiSourceBindingMapper.class,
                BusinessMethodAssetMapper.class, BusinessMethodRevisionMapper.class, RegistryCredentialMapper.class)) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(datasource); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        methodAssetMapper = session.getMapper(BusinessMethodAssetMapper.class);
        credentials = session.getMapper(RegistryCredentialMapper.class);
        projects = session.getMapper(ScanProjectMapper.class); tools = session.getMapper(ToolDefinitionMapper.class);
        differences = session.getMapper(CapabilityDiffItemMapper.class); snapshots = session.getMapper(CapabilitySnapshotMapper.class);
        sources = session.getMapper(CapabilitySourceStateMapper.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules(); policy = new CapabilityChangePolicy(json);
        lifecycle = new CapabilityChangeLifecycle(snapshots, differences, sources, policy, session.getMapper(CapabilitySyncReceiptMapper.class)); guard = new CapabilitySourceContractGuard(lifecycle, policy);
        var transactionManager = new DataSourceTransactionManager(datasource);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactionManager);
        context.registerBean(BusinessMethodAssetStore.class, () -> new BusinessMethodAssetStore(
                session.getMapper(BusinessMethodAssetMapper.class), session.getMapper(BusinessMethodRevisionMapper.class),
                snapshots, differences, policy, json));
        context.registerBean(CapabilityCatalogProjectionStore.class, () -> new CapabilityCatalogProjectionStore(
                session.getMapper(ScanProjectToolMapper.class), tools, json, policy,
                context.getBean(BusinessMethodAssetStore.class)));
        context.registerBean(RegistryInstanceLifecycleService.class, () -> new RegistryInstanceLifecycleService(
                session.getMapper(ProjectInstanceMapper.class), json));
        context.registerBean(CapabilityReviewEvidenceStore.class, () -> new CapabilityReviewEvidenceStore(
                snapshots, differences, session.getMapper(CapabilityApplyRecordMapper.class)));
        context.registerBean(HttpApiContractCanonicalizer.class, () -> new HttpApiContractCanonicalizer(json));
        context.registerBean(HttpApiAssetService.class, () -> new HttpApiAssetService(
                session.getMapper(HttpApiAssetMapper.class), session.getMapper(HttpApiSourceBindingMapper.class),
                context.getBean(HttpApiContractCanonicalizer.class)));
        context.registerBean(StarterMvcHttpApiIntakeService.class, () -> new StarterMvcHttpApiIntakeService(
                context.getBean(HttpApiAssetService.class), session.getMapper(HttpApiSourceBindingMapper.class), json));
        context.registerBean(CapabilitySourceIntakeService.class, () -> new CapabilitySourceIntakeService(
                projects, session.getMapper(ScanProjectToolMapper.class), tools, session.getMapper(CapabilitySyncLogMapper.class),
                snapshots, differences, json, policy, lifecycle, context.getBean(CapabilityCatalogProjectionStore.class),
                context.getBean(CapabilityReviewEvidenceStore.class), context.getBean(StarterMvcHttpApiIntakeService.class),
                context.getBean(BusinessMethodAssetStore.class)));
        context.refresh();
        projections = context.getBean(CapabilityCatalogProjectionStore.class);
        methodAssets = context.getBean(BusinessMethodAssetStore.class);
        assertTrue(AopUtils.isAopProxy(projections));
        instanceLifecycle = context.getBean(RegistryInstanceLifecycleService.class);
        assertTrue(AopUtils.isAopProxy(instanceLifecycle));
        intake = context.getBean(CapabilitySourceIntakeService.class);
        evidence = context.getBean(CapabilityReviewEvidenceStore.class);
        assertTrue(AopUtils.isAopProxy(intake)); assertTrue(AopUtils.isAopProxy(evidence));
        registrySecurity = mock(RegistrySecurityService.class);
        registry = new CapabilityRegistryService(projects, snapshots, differences, registrySecurity,
                new RegistryProjectRegistrationService(projects, registrySecurity, mock(RegistryEnrollmentService.class)), json, policy, lifecycle, projections, instanceLifecycle, intake, evidence);
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var project = new ScanProjectEntity(); project.setName("订单系统"); project.setProjectCode("orders");
        project.setBaseUrl("http://orders.local"); project.setScanPath("sdk:orders"); project.setScanType("auto"); projects.insert(project);
    }

    @Test void credentialRotationChangesExecutionBindingWithoutChangingTheMethodAndRejectsOldPins() {
        sync("binding-owner", typedRead("/orders", "BUSINESS_METHOD"));
        var asset = methodAssets.find("orders:read").orElseThrow();
        var catalog = new BusinessMethodCatalogService(methodAssetMapper, methodAssets, projects, lifecycle);
        var method = catalog.find("orders:read").orElseThrow();
        var security = new RegistrySecurityService(credentials, new ObjectMapper());
        var first = tx.execute(status -> security.savePrimaryCredential(asset.getProjectId(), "orders", "binding-app", "first-secret"));
        String before = BusinessMethodExecutionRevision.of(method, first);
        var same = tx.execute(status -> security.savePrimaryCredential(asset.getProjectId(), "orders", "binding-app", "first-secret"));
        assertEquals(first.getRevision(), same.getRevision(), "identical SDK registration must retain the binding");
        var changed = tx.execute(status -> security.savePrimaryCredential(asset.getProjectId(), "orders", "binding-app", "second-secret"));
        assertEquals(first.getId(), changed.getId());
        assertEquals(first.getRevision() + 1, changed.getRevision());
        assertNotEquals(before, BusinessMethodExecutionRevision.of(method, changed));
        assertEquals(method.revision().getContractHash(), catalog.find("orders:read").orElseThrow().revision().getContractHash());
        AtomicInteger sent = new AtomicInteger();
        var execution = new CapabilityToolExecutionService(catalog, invocation -> {
            sent.incrementAndGet(); return Map.of("statusCode", 200, "body", Map.of("success", true));
        }, security, guard);
        var constraints = new java.util.LinkedHashMap<String, Object>(Map.of("expectedAssetId", asset.getId().intValue(),
                "expectedAcceptedRevisionId", method.revision().getId().intValue(), "expectedContractHash", method.revision().getInvocationHash(),
                "expectedExecutionRevision", before, "requireSignedInvocation", true));
        assertEquals("BUSINESS_METHOD_EXECUTION_BINDING_CHANGED", assertThrows(CapabilityInvocationPolicyException.class,
                () -> execution.execute("orders:read", Map.of("constraints", constraints))).code());
        assertEquals(0, sent.get());
        constraints.put("expectedExecutionRevision", BusinessMethodExecutionRevision.of(method, changed));
        execution.execute("orders:read", Map.of("constraints", constraints));
        assertEquals(1, sent.get());
        tx.executeWithoutResult(status -> security.updateEmbedPolicy("orders", "binding-app", List.of("https://orders.test"), List.of(), 600));
        long policyRevision = security.findPrimaryActiveCredential("orders").orElseThrow().getRevision();
        assertEquals(changed.getRevision() + 1, policyRevision);
        tx.executeWithoutResult(status -> security.updateEmbedPolicy("orders", "binding-app", List.of("https://orders.test"), List.of(), 600));
        assertEquals(policyRevision, security.findPrimaryActiveCredential("orders").orElseThrow().getRevision());
        assertEquals(1L, methodAssetMapper.selectCount(null));
        assertEquals(asset.getAcceptedRevisionId(), methodAssets.find("orders:read").orElseThrow().getAcceptedRevisionId());
    }

    @Test void methodFactsRemainIndependentFromDeletedOrChangedInvocationProjection() {
        sync("method-owner", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity asset = methodAssets.find("orders:read").orElseThrow();
        assertNotNull(asset.getAcceptedRevisionId());
        assertEquals("/orders", methodAssets.acceptedDeclaration(asset).endpointPath());
        jdbc.update("UPDATE capability_tool_definition SET endpoint_path='/tampered', title='projection-only'");
        assertEquals("/orders", methodAssets.acceptedDeclaration(asset).endpointPath());
        assertEquals("自动订单查询", asset.getTitle());
        jdbc.update("DELETE FROM capability_tool_definition");
        assertEquals("/orders", methodAssets.acceptedDeclaration(methodAssets.find("orders:read").orElseThrow()).endpointPath());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_asset", Long.class));
    }

    @Test void identicalReportsKeepOneMethodAssetAndOneAcceptanceRevision() {
        sync("method-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity first = methodAssets.find("orders:read").orElseThrow();
        sync("method-repeat", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity repeated = methodAssets.find("orders:read").orElseThrow();
        assertEquals(first.getId(), repeated.getId());
        assertEquals(first.getAcceptedRevisionId(), repeated.getAcceptedRevisionId());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_revision", Long.class));
        assertEquals(1L, snapshots.selectCount(null));
    }

    @Test void sourceDiffUsesAcceptedMethodEvenWhenTheProjectionAlreadyMatchesTheCandidate() {
        sync("owner-before-projection-tamper", typedRead("/orders", "BUSINESS_METHOD"));
        String acceptedHash = methodAssets.acceptedRevision(methodAssets.find("orders:read").orElseThrow()).getInvocationHash();
        jdbc.update("UPDATE capability_tool_definition SET endpoint_path='/orders-v2'");
        jdbc.update("UPDATE capability_scan_project_tool SET endpoint_path='/orders-v2'");
        sync("owner-after-projection-tamper", typedRead("/orders-v2", "BUSINESS_METHOD"));
        assertEquals("CHANGED", latest().getChangeType());
        assertEquals("PENDING", latest().getReviewStatus());
        assertEquals(acceptedHash, sources.selectOne(null).getAcceptedContractHash());
        assertEquals("/orders", methodAssets.acceptedDeclaration(methodAssets.find("orders:read").orElseThrow()).endpointPath());
    }

    @Test void executionDerivesTheAcceptedAddressAndRiskEvenWhenBothProjectionRowsAreMissing() {
        sync("owner-execution-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity asset = methodAssets.find("orders:read").orElseThrow();
        String hash = methodAssets.acceptedRevision(asset).getInvocationHash();
        jdbc.update("DELETE FROM capability_tool_definition");
        jdbc.update("DELETE FROM capability_scan_project_tool");
        var credential = new com.enterprise.ai.agent.registry.RegistryCredentialEntity();
        credential.setAppKey("owner-fixture"); credential.setAppSecret("owner-fixture-secret");
        credential.setId(1L); credential.setRevision(1L); credential.setStatus("ACTIVE");
        credential.setProjectId(asset.getProjectId()); credential.setProjectCode(asset.getProjectCode());
        org.mockito.Mockito.when(registrySecurity.findPrimaryActiveCredential("orders")).thenReturn(java.util.Optional.of(credential));
        var catalog = new BusinessMethodCatalogService(methodAssetMapper, methodAssets, projects, lifecycle);
        var captured = new java.util.concurrent.atomic.AtomicReference<
                com.enterprise.ai.capability.internal.CapabilityHttpToolInvocation>();
        CapabilityHttpToolInvoker invoker = invocation -> {
            captured.set(invocation); return Map.of("statusCode", 200, "body", Map.of("success", true));
        };
        var execution = new CapabilityToolExecutionService(catalog, invoker, registrySecurity, guard);
        execution.execute("orders:read", Map.of("input", Map.of(), "context", Map.of(), "constraints", Map.of(
                "consoleCapabilityInvocation", true, "expectedQualifiedName", "orders:read", "expectedProjectCode", "orders",
                "expectedProjectId", asset.getProjectId(), "expectedContractHash", hash, "requireSignedInvocation", true,
                "expectedExecutionRevision", com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodExecutionRevision.of(
                        catalog.find("orders:read").orElseThrow(), credential))));
        assertEquals("http://orders.local/orders", captured.get().url());
        assertEquals("GET", captured.get().method());
        var descriptor = new com.enterprise.ai.capability.internal.CapabilityToolLookupService(catalog, registrySecurity).getToolDefinition("orders:read");
        assertEquals(asset.getId(), descriptor.get("assetId"));
        assertEquals(asset.getAcceptedRevisionId(), descriptor.get("acceptedRevisionId"));
        assertEquals(hash, descriptor.get("contractHash"));
    }

    @Test void repeatedAcceptedMethodRebuildsItsProjectionFromTheImmutableOwnerWithoutANewRevision() {
        sync("owner-repair-first", typedRead("/orders", "BUSINESS_METHOD"));
        var first = methodAssets.find("orders:read").orElseThrow();
        jdbc.update("DELETE FROM capability_tool_definition");
        jdbc.update("DELETE FROM capability_scan_project_tool");
        sync("owner-repair-repeat", typedRead("/orders", "BUSINESS_METHOD"));
        assertEquals("/orders", tool().getEndpointPath());
        assertEquals(first.getId(), methodAssets.find("orders:read").orElseThrow().getId());
        assertEquals(first.getAcceptedRevisionId(), methodAssets.find("orders:read").orElseThrow().getAcceptedRevisionId());
        assertEquals(1L, snapshots.selectCount(null));
        assertEquals(1L, differences.selectCount(null));
    }

    @Test void missingMethodIsObservedAndRemovedEvenIfBothDerivedRowsWereDeleted() {
        sync("owner-missing-first", typedRead("/orders", "BUSINESS_METHOD"));
        var first = methodAssets.find("orders:read").orElseThrow();
        jdbc.update("DELETE FROM capability_tool_definition");
        jdbc.update("DELETE FROM capability_scan_project_tool");
        sync("owner-missing-next");
        assertEquals("DELETED", latest().getChangeType());
        assertEquals("SOURCE_MISSING", sources.selectOne(null).getAvailability());
        review(latest().getId(), "APPLY");
        assertEquals(first.getId(), methodAssets.find("orders:read").orElseThrow().getId());
        assertEquals("REMOVED", methodAssets.find("orders:read").orElseThrow().getStatus());
    }

    @Test void methodDisplayChangeKeepsStableIdentityAndBusinessContract() {
        sync("method-display-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity first = methodAssets.find("orders:read").orElseThrow();
        BusinessMethodRevisionEntity firstRevision = methodAssets.acceptedRevision(first);
        CapabilityRegistration renamed = new CapabilityRegistration("read", "新的订单查询名称", "查询订单", "GET",
                "http://orders.local", null, "/orders", null, null, "READ_ONLY", true, List.of(),
                Map.of("assetType", "BUSINESS_METHOD"));
        sync("method-display-next", renamed);
        BusinessMethodAssetEntity next = methodAssets.find("orders:read").orElseThrow();
        BusinessMethodRevisionEntity nextRevision = methodAssets.acceptedRevision(next);
        assertEquals(first.getId(), next.getId());
        assertEquals("新的订单查询名称", next.getTitle());
        assertNotEquals(firstRevision.getId(), nextRevision.getId());
        assertEquals(firstRevision.getContractHash(), nextRevision.getContractHash());
        assertEquals(firstRevision.getBindingHash(), nextRevision.getBindingHash());
    }

    @Test void methodTransportChangeWaitsForAcceptanceAndRollbackRestoresTheOriginalRevision() {
        sync("method-binding-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity first = methodAssets.find("orders:read").orElseThrow();
        BusinessMethodRevisionEntity original = methodAssets.acceptedRevision(first);
        sync("method-binding-next", typedRead("/orders-v2", "BUSINESS_METHOD"));
        Long changeId = latest().getId();
        assertEquals("PENDING", latest().getReviewStatus());
        assertEquals(original.getId(), methodAssets.find("orders:read").orElseThrow().getAcceptedRevisionId());
        review(changeId, "APPLY");
        BusinessMethodAssetEntity accepted = methodAssets.find("orders:read").orElseThrow();
        BusinessMethodRevisionEntity changed = methodAssets.acceptedRevision(accepted);
        assertEquals(first.getId(), accepted.getId());
        assertEquals(original.getContractHash(), changed.getContractHash());
        assertNotEquals(original.getBindingHash(), changed.getBindingHash());
        assertNotEquals(original.getInvocationHash(), changed.getInvocationHash());
        tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore owner revision")));
        BusinessMethodAssetEntity restored = methodAssets.find("orders:read").orElseThrow();
        assertEquals(original.getId(), restored.getAcceptedRevisionId());
        assertEquals("/orders", methodAssets.acceptedDeclaration(restored).endpointPath());
        assertEquals("CONTRACT_DRIFT", sources.selectOne(null).getAvailability());
    }

    @Test void methodRemovalAndRollbackPreserveItsStableIdentityAndSourceEvidence() {
        sync("method-remove-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity first = methodAssets.find("orders:read").orElseThrow();
        sync("method-remove-next");
        Long deletedId = latest().getId();
        assertEquals("SOURCE_MISSING", sources.selectOne(null).getAvailability());
        review(deletedId, "APPLY");
        BusinessMethodAssetEntity removed = methodAssets.find("orders:read").orElseThrow();
        assertEquals(first.getId(), removed.getId());
        assertEquals("REMOVED", removed.getStatus());
        assertFalse(removed.getEnabled());
        tx.execute(status -> registry.rollbackDiffItem("orders", deletedId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore removed owner")));
        BusinessMethodAssetEntity restored = methodAssets.find("orders:read").orElseThrow();
        assertEquals(first.getAcceptedRevisionId(), restored.getAcceptedRevisionId());
        assertEquals("ACCEPTED", restored.getStatus());
        assertEquals("SOURCE_MISSING", sources.selectOne(null).getAvailability(), "rollback does not invent a new source observation");
    }

    @Test void projectionFailureRollsBackMethodAcceptanceAndSourceSnapshotTogether() {
        jdbc.execute("ALTER TABLE capability_tool_definition ADD CONSTRAINT reject_method_projection CHECK (endpoint_path <> '/orders')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sync("method-atomic-failure", typedRead("/orders", "BUSINESS_METHOD")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_asset", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_revision", Long.class));
        assertEquals(0L, snapshots.selectCount(null));
        assertEquals(0L, differences.selectCount(null));
    }

    @Test void methodAcceptanceRejectsUnrelatedDiffAndCorruptedImmutableSource() {
        sync("method-proof-first", typedRead("/orders", "BUSINESS_METHOD"));
        BusinessMethodAssetEntity asset = methodAssets.find("orders:read").orElseThrow();
        BusinessMethodRevisionEntity revision = methodAssets.acceptedRevision(asset);
        CapabilityRegistration declaration = methodAssets.acceptedDeclaration(asset);
        ScanProjectEntity project = projects.selectById(asset.getProjectId());
        assertThrows(IllegalArgumentException.class, () -> tx.execute(status -> methodAssets.accept(
                project, declaration, revision.getSnapshotId(), revision.getDiffItemId() + 1000L)));
        jdbc.update("UPDATE capability_snapshot SET payload_json='{}' WHERE id=?", revision.getSnapshotId());
        assertThrows(IllegalStateException.class, () -> methodAssets.acceptedDeclaration(asset));
    }

    @Test void sourceInventoryFixtureUsesTheProductionReadCommittedBoundary() {
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, tx.getIsolationLevel());
        Integer actualIsolation = tx.execute(status -> jdbc.execute(
                (ConnectionCallback<Integer>) Connection::getTransactionIsolation));
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, actualIsolation);
    }

    @Test void registrationCannotOverwriteConcurrentlyCommittedPlatformConfiguration() throws Exception {
        jdbc.update("UPDATE capability_scan_project SET scan_settings='old-settings', ai_coding_access_key='old-key', ai_coding_access_enabled=TRUE, tool_count=1");
        var headers = new RegistrySecurityService.RegistrySignatureHeaders("test-key", "1", "n", "sig");
        var writer = Executors.newSingleThreadExecutor();
        try {
            org.mockito.Mockito.when(registrySecurity.verifyRequired("orders", headers)).thenAnswer(call -> {
                writer.submit(() -> jdbc.update("UPDATE capability_scan_project SET scan_settings='new-settings', ai_coding_access_key='new-key', ai_coding_access_enabled=FALSE, tool_count=9")).get(5, TimeUnit.SECONDS);
                return null;
            });
            tx.execute(status -> registry.registerProject(registrationRequest(null), null, headers));
        } finally { writer.shutdownNow(); assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS)); }
        var saved = projects.selectOne(null);
        assertEquals("new-settings", saved.getScanSettings());
        assertEquals("new-key", saved.getAiCodingAccessKey());
        assertFalse(saved.getAiCodingAccessEnabled());
        assertEquals(9, saved.getToolCount());
        assertEquals("重新注册订单", saved.getName());
    }

    @Test void registrationCanClearItsNullableOwner() {
        jdbc.update("UPDATE capability_scan_project SET owner='previous-owner'");
        var headers = new RegistrySecurityService.RegistrySignatureHeaders("test-key", "1", "n", "sig");
        tx.execute(status -> registry.registerProject(registrationRequest(null), null, headers));
        assertNull(projects.selectOne(null).getOwner());
    }

    private ProjectRegisterRequest registrationRequest(String owner) {
        return new ProjectRegisterRequest("orders", "重新注册订单", "dev", owner, "PRIVATE", "http://orders.local",
                "", null, null, List.of(), List.of(), 300, Map.of());
    }

    @Test void sourceIntakeAndReviewEvidenceRequireTheRegistryTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> intake.receiveSource(null, null));
        assertThrows(IllegalTransactionStateException.class, () -> intake.diagnose(null, null));
        assertThrows(IllegalTransactionStateException.class, () -> evidence.refreshSnapshotReviewStatus(null));
        assertThrows(IllegalTransactionStateException.class,
                () -> evidence.recordReviewDecision(null, null, null, null, null, null, null, null, null));
        assertEquals(0, snapshots.selectCount(null));
    }

    @Test void failedAutomaticReviewAuditRollsBackSourceReplacementAndCatalogWrites() {
        sync("initial", read("/orders", null));
        sync("pending", read("/orders-v2", null));
        var pending = latest();
        var priorSource = sources.selectOne(null);
        long snapshotCount = snapshots.selectCount(null);
        long differenceCount = differences.selectCount(null);
        Long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class);
        Long syncLogCount = jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_log", Long.class);
        Long receiptCount = jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Long.class);
        jdbc.execute("ALTER TABLE capability_apply_record ADD CONSTRAINT reject_new_auto_apply CHECK (snapshot_id <= "
                + pending.getSnapshotId() + " OR action <> 'AUTO_APPLY')");
        var candidate = new CapabilityRegistration("read", "更新订单查询", "查询订单", "GET", "http://orders.local",
                null, "/orders", null, null, "READ_ONLY", true, List.of(), Map.of("assetType", "BUSINESS_METHOD"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sync("rejected", candidate));
        assertEquals(snapshotCount, snapshots.selectCount(null));
        assertEquals(differenceCount, differences.selectCount(null));
        assertEquals("PENDING", differences.selectById(pending.getId()).getReviewStatus());
        assertEquals("自动订单查询", tool().getTitle());
        assertEquals(priorSource.getSnapshotId(), sources.selectOne(null).getSnapshotId());
        assertEquals(priorSource.getSourceContractHash(), sources.selectOne(null).getSourceContractHash());
        assertEquals(priorSource.getAcceptedContractHash(), sources.selectOne(null).getAcceptedContractHash());
        assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
        assertEquals(auditCount, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
        assertEquals(syncLogCount, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_log", Long.class));
        assertEquals(receiptCount, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Long.class));
        jdbc.execute("ALTER TABLE capability_apply_record DROP CONSTRAINT reject_new_auto_apply");
        assertEquals(1, sync("rejected", candidate).applied());
        assertEquals("更新订单查询", tool().getTitle());
        assertEquals("READY", guard.availability(tool()));
        assertEquals(auditCount + 1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
        assertEquals(receiptCount + 1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Long.class));
    }

    @Test void instanceMutationsRequireTheRegistryTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> instanceLifecycle.heartbeat(null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> instanceLifecycle.offline(null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> instanceLifecycle.updateInstanceStatus(null, null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> instanceLifecycle.purgeOfflineInstances(null, 0));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM capability_project_instance", Integer.class));
    }

    @Test void instanceCleanupRespectsProjectStatusAndLastObservation() {
        var project = projects.selectOne(null);
        var other = new ScanProjectEntity(); other.setName("其他系统"); other.setProjectCode("other");
        other.setBaseUrl("http://other.local");
        other.setScanPath("sdk:other"); other.setScanType("auto"); projects.insert(other);
        var old = java.time.LocalDateTime.now().minusHours(2);
        for (String status : List.of("OFFLINE", "STALE", "DISABLED", "ONLINE")) {
            jdbc.update("INSERT INTO capability_project_instance (project_id, project_code, instance_id, status, last_heartbeat_at) VALUES (?, ?, ?, ?, ?)",
                    project.getId(), "orders", status, status, old);
        }
        jdbc.update("INSERT INTO capability_project_instance (project_id, project_code, instance_id, status, last_heartbeat_at) VALUES (?, ?, ?, ?, ?)",
                project.getId(), "orders", "recent", "OFFLINE", java.time.LocalDateTime.now());
        jdbc.update("INSERT INTO capability_project_instance (project_id, project_code, instance_id, status, last_heartbeat_at) VALUES (?, ?, ?, ?, ?)",
                other.getId(), "other", "OFFLINE", "OFFLINE", old);
        Integer removed = tx.execute(status -> registry.purgeOfflineInstances("orders", 30));
        assertEquals(2, removed);
        assertEquals(java.util.Set.of("DISABLED", "ONLINE", "recent"), registry.listInstances("orders").stream()
                .map(ProjectInstanceEntity::getInstanceId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, registry.listInstances("other").size());
    }

    @Test void sdkShutdownAndRestartCannotReenableAnAdministrativelyDisabledInstance() {
        var heartbeat = new InstanceHeartbeatRequest("worker-1", null, "orders-host", 8080,
                "1.0", "0.3", Map.of("zone", "测试"));
        tx.executeWithoutResult(status -> registry.heartbeat("orders", heartbeat));
        tx.executeWithoutResult(status -> registry.updateInstanceStatus("orders", "worker-1", "DISABLED"));
        tx.executeWithoutResult(status -> registry.offline("orders", "worker-1"));
        assertEquals("DISABLED", registry.listInstances("orders").get(0).getStatus());
        var observed = tx.execute(status -> registry.heartbeat("orders", heartbeat));
        assertEquals("DISABLED", observed.instance().getStatus());
        assertTrue(observed.instance().getMetadataJson().contains("测试"));
        tx.executeWithoutResult(status -> registry.updateInstanceStatus("orders", "worker-1", "OFFLINE"));
        assertEquals("ONLINE", tx.execute(status -> registry.heartbeat("orders", heartbeat)).instance().getStatus());
    }

    @Test void rollbackDerivesTheProjectionFromTheAcceptedOwnerEvenIfThePriorProjectionWasCorrupted() {
        sync("owner-before-corruption", typedRead("/orders", "BUSINESS_METHOD"));
        Long originalRevision = methodAssets.find("orders:read").orElseThrow().getAcceptedRevisionId();
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED', endpoint_path = '/forged'");
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED', endpoint_path = '/forged'");
        sync("owner-change", typedRead("/orders-v2", "BUSINESS_METHOD"));
        Long changeId = latest().getId();
        review(changeId, "APPLY");
        tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore accepted owner")));
        assertEquals(originalRevision, methodAssets.find("orders:read").orElseThrow().getAcceptedRevisionId());
        assertEquals("/orders", methodAssets.acceptedDeclaration(methodAssets.find("orders:read").orElseThrow()).endpointPath());
        assertProjectionAssetType("BUSINESS_METHOD");
        assertEquals("/orders", tool().getEndpointPath());
        assertEquals("/orders", jdbc.queryForObject("SELECT endpoint_path FROM capability_scan_project_tool", String.class));
    }

    @Test void projectionWritesRequireTheRegistryTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> projections.acceptBusinessMethod(null, null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> projections.applySdkCapabilityCatalogRow(null, null, null, null, null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> projections.markCatalogRowRemoved(null, null));
        assertThrows(IllegalTransactionStateException.class,
                () -> projections.restoreCatalogState(null, null));
        assertEquals(0, tools.selectCount(null));
    }

    @Test void deduplicatedSyncIdentityCannotBeReusedForDifferentContentOrMode() {
        sync("first", read("/orders", null));
        sync("alias", read("/orders", null));
        assertEquals(1, snapshots.selectCount(null));
        assertEquals(2, snapshots.selectList(null).get(0).getReportCount());
        assertThrows(IllegalArgumentException.class, () -> sync("alias", read("/changed", null)));
        assertThrows(IllegalArgumentException.class,
                () -> tx.execute(status -> registry.diff("orders", request("alias", read("/orders", null)))));
        assertEquals(1, snapshots.selectCount(null));
        assertEquals(2, snapshots.selectList(null).get(0).getReportCount());
        assertEquals("READY", guard.availability(tool()));
    }

    @Test void aliasRetryAfterNewObservationKeepsOriginalSnapshotBinding() {
        sync("first", read("/orders", null));
        Long originalSnapshot = snapshots.selectOne(null).getId();
        sync("alias", read("/orders", null));
        sync("new-observation", read("/orders-v2", null));
        Long currentSnapshot = sources.selectOne(null).getSnapshotId();
        assertEquals(0, sync("alias", read("/orders", null)).applied());
        assertEquals(2, snapshots.selectCount(null));
        assertEquals(3, snapshots.selectById(originalSnapshot).getReportCount());
        assertEquals(currentSnapshot, sources.selectOne(null).getSnapshotId());
        assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Integer.class));
    }

    @Test void rejectedAliasReceiptRollsBackDeduplicationAndCanBeRetried() {
        sync("first", read("/orders", null));
        jdbc.execute("ALTER TABLE capability_sync_receipt ADD CONSTRAINT reject_alias CHECK (sync_id <> 'alias')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sync("alias", read("/orders", null)));
        assertEquals(1, snapshots.selectOne(null).getReportCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Integer.class));
        jdbc.execute("ALTER TABLE capability_sync_receipt DROP CONSTRAINT reject_alias");
        sync("alias", read("/orders", null));
        assertEquals(2, snapshots.selectOne(null).getReportCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Integer.class));
    }

    @Test void sameSyncIdentityInDifferentProjectsHasIndependentLogsAndSnapshots() {
        sync("shared-id", read("/orders", null));
        var other = new ScanProjectEntity(); other.setName("另一个项目"); other.setProjectCode("other");
        other.setBaseUrl("http://other.local"); other.setScanPath("sdk:other"); other.setScanType("auto");
        projects.insert(other);
        var response = tx.execute(status -> registry.sync("other", request("shared-id", read("/other", null))));
        assertEquals("other", response.projectCode());
        assertEquals(2, snapshots.selectCount(null));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_log WHERE sync_id='shared-id'", Integer.class));
        assertEquals(2, tools.selectCount(null));
    }

    @Test void automaticAdmissionIsIdempotentAndDiagnosticCannotBecomeSource() {
        var first = sync("first", read("/orders", null));
        assertEquals(1, first.applied()); assertEquals(1, tools.selectCount(null));
        assertEquals("READY", guard.availability(tool()));
        assertEquals("自动订单查询", tool().getTitle());
        sync("repeat", read("/orders", null));
        assertEquals(1, snapshots.selectCount(null)); assertEquals(2, snapshots.selectList(null).get(0).getReportCount());
        assertEquals(1, differences.selectCount(null));
        tx.execute(status -> registry.diff("orders", request("diagnostic", read("/untrusted", null))));
        assertEquals("READY", guard.availability(tool()));
        assertEquals("DIAGNOSTIC", latest().getReviewStatus());
        assertThrows(IllegalArgumentException.class, () -> review(latest().getId(), "APPLY"));
        assertThrows(IllegalArgumentException.class, () -> sync("first", read("/different", null)));
        assertEquals(2, snapshots.selectCount(null));
    }

    @Test void missingAssetTypeIsRejectedBeforeAnySourceOrAssetWrite() {
        var missing = new CapabilityRegistration("read", "查询订单", "查询订单", "GET", "http://orders.local", null,
                "/orders", null, null, "READ_ONLY", true, List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> sync("missing-type", missing));
        assertEquals(0, snapshots.selectCount(null));
        assertEquals(0, differences.selectCount(null));
        assertEquals(0, tools.selectCount(null));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_business_method_asset", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_source_state", Long.class));
    }

    @Test void repeatedAcceptedSourceSyncRepairsCorruptedProjectionWithoutNewObservation() {
        sync("accepted-business-method", typedRead("/orders", "BUSINESS_METHOD"));
        Long acceptedSnapshotId = snapshots.selectOne(null).getId();
        CapabilitySourceStateEntity beforeRepeat = sources.selectOne(null);
        Long acceptedDiffItemId = beforeRepeat.getDiffItemId();
        String acceptedHash = beforeRepeat.getAcceptedContractHash();
        String sourceHash = beforeRepeat.getSourceContractHash();
        String acceptedMetadata = tool().getCapabilityMetadataJson();
        Long appliedRecords = jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class);

        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");
        assertProjectionAssetType("CORRUPTED");

        CapabilitySyncResponse repeated = sync("accepted-business-method-repeat",
                typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(0, repeated.applied());
        assertEquals(1, snapshots.selectCount(null));
        assertEquals(1, differences.selectCount(null));
        assertEquals(2, snapshots.selectById(acceptedSnapshotId).getReportCount());
        assertProjectionAssetType("BUSINESS_METHOD");
        assertEquals(acceptedMetadata, tool().getCapabilityMetadataJson());
        CapabilitySourceStateEntity afterRepeat = sources.selectOne(null);
        assertEquals(acceptedSnapshotId, afterRepeat.getSnapshotId());
        assertEquals(acceptedDiffItemId, afterRepeat.getDiffItemId());
        assertEquals(sourceHash, afterRepeat.getSourceContractHash());
        assertEquals(acceptedHash, afterRepeat.getAcceptedContractHash());
        assertEquals(appliedRecords, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_sync_receipt", Integer.class));
    }

    @Test void repeatedPendingAndIgnoredCandidateCannotRepairAcceptedProjection() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        String acceptedMetadata = tool().getCapabilityMetadataJson();
        String acceptedHash = sources.selectOne(null).getAcceptedContractHash();
        sync("http-candidate", read("/orders-v2", null));
        Long candidateId = latest().getId();
        assertEquals("PENDING", latest().getReviewStatus());
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse pendingRepeat = sync("http-candidate-repeat-pending",
                read("/orders-v2", null));

        assertEquals(0, pendingRepeat.applied());
        assertEquals("PENDING", differences.selectById(candidateId).getReviewStatus());
        assertProjectionAssetType("CORRUPTED");
        review(candidateId, "IGNORE");
        assertEquals("IGNORED", differences.selectById(candidateId).getReviewStatus());

        CapabilitySyncResponse ignoredRepeat = sync("http-candidate-repeat-ignored",
                read("/orders-v2", null));

        assertEquals(0, ignoredRepeat.applied());
        assertEquals("IGNORED", differences.selectById(candidateId).getReviewStatus());
        assertProjectionAssetType("CORRUPTED");
        assertEquals(acceptedMetadata, tool().getCapabilityMetadataJson());
        assertEquals(acceptedHash, sources.selectOne(null).getAcceptedContractHash());
    }

    @Test void ownerExecutionBlocksDisabledMethodsCredentialsAndSourceDriftBeforeAnyOutboundAttempt() {
        sync("accepted-owner-guard", typedRead("/orders", "BUSINESS_METHOD"));
        ToolDefinitionEntity accepted = tool();
        String acceptedHash = policy.contractHash(accepted);
        guard.validateConsoleAcceptedBusinessMethod(accepted, Map.of("expectedProjectId", accepted.getProjectId()));

        AtomicInteger outbound = new AtomicInteger();
        CapabilityHttpToolInvoker invoker = invocation -> {
            outbound.incrementAndGet();
            return Map.of("statusCode", 200, "body", Map.of("success", true));
        };
        CapabilityToolExecutionService execution = new CapabilityToolExecutionService(
                new BusinessMethodCatalogService(methodAssetMapper, methodAssets, projects, lifecycle), invoker, registrySecurity, guard);

        CapabilityInvocationPolicyException credentialGone = assertThrows(CapabilityInvocationPolicyException.class,
                () -> execution.execute("orders:read", consoleRequest(acceptedHash)));
        assertEquals("CAPABILITY_PROJECT_CREDENTIAL_REQUIRED", credentialGone.code());
        assertEquals(0, outbound.get());

        jdbc.update("UPDATE capability_business_method_asset SET enabled=FALSE");
        assertThrows(IllegalStateException.class, () -> execution.execute("orders:read", consoleRequest(acceptedHash)));
        assertEquals(0, outbound.get());

        jdbc.update("UPDATE capability_business_method_asset SET enabled=TRUE");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'HTTP_API' WHERE qualified_name = 'orders:read'");
        CapabilityInvocationPolicyException typeChanged = assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validateConsoleAcceptedBusinessMethod(tool(), Map.of("expectedProjectId", tool().getProjectId())));
        assertEquals("CAPABILITY_CONSOLE_BUSINESS_METHOD_REQUIRED", typeChanged.code());
        assertThrows(CapabilityInvocationPolicyException.class,
                () -> execution.execute("orders:read", consoleRequest(acceptedHash)));
        assertEquals(0, outbound.get());
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'BUSINESS_METHOD' WHERE qualified_name = 'orders:read'");

        jdbc.update("UPDATE capability_source_state SET accepted_contract_hash = ?", "b".repeat(64));
        CapabilityInvocationPolicyException acceptedHashChanged = assertThrows(CapabilityInvocationPolicyException.class,
                () -> execution.execute("orders:read", consoleRequest(acceptedHash)));
        assertEquals("CAPABILITY_CONSOLE_CONTRACT_DRIFT", acceptedHashChanged.code());
        assertEquals(0, outbound.get());
        jdbc.update("UPDATE capability_source_state SET accepted_contract_hash = ?", acceptedHash);

        sync("candidate-source-drift", read("/orders-v2", null));
        assertEquals("/orders", tool().getEndpointPath(), "pending source must not switch the accepted outbound address");
        CapabilityInvocationPolicyException drift = assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validateConsoleAcceptedBusinessMethod(tool(), Map.of("expectedProjectId", tool().getProjectId())));
        assertEquals("CAPABILITY_CONSOLE_CONTRACT_DRIFT", drift.code());
        assertThrows(CapabilityInvocationPolicyException.class,
                () -> execution.execute("orders:read", consoleRequest(acceptedHash)));
        assertEquals(0, outbound.get());
    }

    @Test void historicalReceiptCannotRepairPendingSemanticsCandidateWithMatchingContractHash() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        Long originalSnapshotId = snapshots.selectOne(null).getId();
        CapabilityRegistration changedDescription = new CapabilityRegistration("read", "自动订单查询", "更新后的业务说明",
                "GET", "http://orders.local", null, "/orders", null, null, "READ_ONLY", true, List.of(),
                Map.of("assetType", "BUSINESS_METHOD"));
        sync("description-candidate", changedDescription);
        Long candidateId = latest().getId();
        assertEquals("PENDING", latest().getReviewStatus());
        CapabilitySourceStateEntity currentCandidate = sources.selectOne(null);
        assertEquals(currentCandidate.getSourceContractHash(), currentCandidate.getAcceptedContractHash());
        assertNotEquals(originalSnapshotId, currentCandidate.getSnapshotId());
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse historicalReplay = sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(0, historicalReplay.applied());
        assertEquals("PENDING", differences.selectById(candidateId).getReviewStatus());
        assertProjectionAssetType("CORRUPTED");
        assertEquals(currentCandidate.getSnapshotId(), sources.selectOne(null).getSnapshotId());
        assertEquals(currentCandidate.getDiffItemId(), sources.selectOne(null).getDiffItemId());
    }

    @Test void historicalReceiptCannotRepairLaterAcceptedContract() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        Long originalSnapshotId = snapshots.selectOne(null).getId();
        sync("http-candidate", read("/orders-v2", null));
        Long httpCandidateId = latest().getId();
        review(httpCandidateId, "APPLY");
        assertProjectionAssetType("BUSINESS_METHOD");
        String currentMetadata = tool().getCapabilityMetadataJson();
        CapabilitySourceStateEntity beforeReplay = sources.selectOne(null);
        Long appliedRecords = jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class);
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse historicalReplay = sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(0, historicalReplay.applied());
        assertEquals(2, snapshots.selectCount(null));
        assertEquals(2, snapshots.selectById(originalSnapshotId).getReportCount());
        assertProjectionAssetType("CORRUPTED");
        assertEquals(currentMetadata, tool().getCapabilityMetadataJson());
        CapabilitySourceStateEntity afterReplay = sources.selectOne(null);
        assertEquals(beforeReplay.getSnapshotId(), afterReplay.getSnapshotId());
        assertEquals(beforeReplay.getDiffItemId(), afterReplay.getDiffItemId());
        assertEquals(beforeReplay.getSourceContractHash(), afterReplay.getSourceContractHash());
        assertEquals(beforeReplay.getAcceptedContractHash(), afterReplay.getAcceptedContractHash());
        assertEquals(appliedRecords, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
    }

    @Test void repeatedDiagnosticDoesNotRepairAcceptedProjection() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        CapabilitySourceStateEntity beforeDiagnostic = sources.selectOne(null);
        tx.execute(status -> registry.diff("orders", request("diagnostic-first",
                typedRead("/orders", "BUSINESS_METHOD"))));
        CapabilitySnapshotEntity diagnosticSnapshot = snapshots.selectOne(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                .eq(CapabilitySnapshotEntity::getIntakeMode, "DIAGNOSTIC").last("limit 1"));
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse repeatedDiagnostic = tx.execute(status -> registry.diff("orders", request(
                "diagnostic-repeat", typedRead("/orders", "BUSINESS_METHOD"))));

        assertEquals(0, repeatedDiagnostic.applied());
        assertEquals(2, snapshots.selectCount(null));
        assertEquals(2, snapshots.selectById(diagnosticSnapshot.getId()).getReportCount());
        assertProjectionAssetType("CORRUPTED");
        CapabilitySourceStateEntity afterDiagnostic = sources.selectOne(null);
        assertEquals(beforeDiagnostic.getSnapshotId(), afterDiagnostic.getSnapshotId());
        assertEquals(beforeDiagnostic.getDiffItemId(), afterDiagnostic.getDiffItemId());
        assertEquals(beforeDiagnostic.getSourceContractHash(), afterDiagnostic.getSourceContractHash());
        assertEquals(beforeDiagnostic.getAcceptedContractHash(), afterDiagnostic.getAcceptedContractHash());
    }

    @Test void historicalReceiptCannotRepairRemovedSource() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        sync("source-removed");
        CapabilitySourceStateEntity beforeReplay = sources.selectOne(null);
        assertNull(beforeReplay.getSourceContractHash());
        String acceptedHash = beforeReplay.getAcceptedContractHash();
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse historicalReplay = sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(0, historicalReplay.applied());
        assertProjectionAssetType("CORRUPTED");
        assertNull(sources.selectOne(null).getSourceContractHash());
        assertEquals(acceptedHash, sources.selectOne(null).getAcceptedContractHash());
    }

    @Test void historicalReceiptCannotRepairRolledBackSource() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        sync("http-candidate", read("/orders-v2", null));
        Long httpCandidateId = latest().getId();
        review(httpCandidateId, "APPLY");
        tx.execute(status -> registry.rollbackDiffItem("orders", httpCandidateId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore business method type")));
        CapabilitySourceStateEntity beforeReplay = sources.selectOne(null);
        assertNotEquals(beforeReplay.getSourceContractHash(), beforeReplay.getAcceptedContractHash());
        String restoredMetadata = tool().getCapabilityMetadataJson();
        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");

        CapabilitySyncResponse historicalReplay = sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(0, historicalReplay.applied());
        assertProjectionAssetType("CORRUPTED");
        assertEquals(restoredMetadata, tool().getCapabilityMetadataJson());
        CapabilitySourceStateEntity afterReplay = sources.selectOne(null);
        assertEquals(beforeReplay.getSnapshotId(), afterReplay.getSnapshotId());
        assertEquals(beforeReplay.getDiffItemId(), afterReplay.getDiffItemId());
        assertEquals(beforeReplay.getSourceContractHash(), afterReplay.getSourceContractHash());
        assertEquals(beforeReplay.getAcceptedContractHash(), afterReplay.getAcceptedContractHash());
    }

    @Test void unchangedAcceptedAssetTypeSyncRepairsCorruptedProjection() {
        sync("accepted-business-method", typedRead("/orders", "BUSINESS_METHOD"));
        assertProjectionAssetType("BUSINESS_METHOD");
        String acceptedHash = jdbc.queryForObject(
                "SELECT accepted_contract_hash FROM capability_source_state", String.class);
        String acceptedMetadata = tool().getCapabilityMetadataJson();

        sync("ignored-http-api", read("/orders-v2", null));
        Long ignoredChange = latest().getId();
        assertEquals("PENDING", latest().getReviewStatus());
        review(ignoredChange, "IGNORE");
        assertEquals("IGNORED", latest().getReviewStatus());
        assertProjectionAssetType("BUSINESS_METHOD");
        assertEquals(acceptedHash, jdbc.queryForObject(
                "SELECT accepted_contract_hash FROM capability_source_state", String.class));

        jdbc.update("UPDATE capability_scan_project_tool SET asset_type = 'CORRUPTED'");
        jdbc.update("UPDATE capability_tool_definition SET asset_type = 'CORRUPTED'");
        assertProjectionAssetType("CORRUPTED");
        assertEquals(acceptedMetadata, tool().getCapabilityMetadataJson());
        assertEquals(acceptedHash, jdbc.queryForObject(
                "SELECT accepted_contract_hash FROM capability_source_state", String.class));

        CapabilitySyncResponse response = sync("accepted-business-method-repeat",
                typedRead("/orders", "BUSINESS_METHOD"));

        assertEquals(1, response.unchanged());
        assertProjectionAssetType("BUSINESS_METHOD");
        assertEquals(acceptedHash, jdbc.queryForObject(
                "SELECT accepted_contract_hash FROM capability_source_state", String.class));
        assertEquals(acceptedHash, jdbc.queryForObject(
                "SELECT source_contract_hash FROM capability_source_state", String.class));
    }

    @Test void apiDeclarationCannotChangeAnAcceptedMethodOrCreateLegacyProjection() {
        sync("business-method", typedRead("/orders", "BUSINESS_METHOD"));
        Long assetId = methodAssetMapper.selectOne(null).getId();
        Long revisionId = methodAssetMapper.selectOne(null).getAcceptedRevisionId();
        Long snapshotCount = snapshots.selectCount(null);
        assertThrows(IllegalArgumentException.class, () -> sync("wrong-channel", typedRead("/orders-v2", "HTTP_API")));
        assertEquals(snapshotCount, snapshots.selectCount(null));
        assertEquals(assetId, methodAssetMapper.selectOne(null).getId());
        assertEquals(revisionId, methodAssetMapper.selectOne(null).getAcceptedRevisionId());
        assertProjectionAssetType("BUSINESS_METHOD");
        assertEquals("/orders", tool().getEndpointPath());
    }

    @Test void invalidAssetTypeIsRejectedBeforeSnapshotOrProjectionWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> sync("invalid-asset-type", typedRead("/orders", "INVALID")));

        assertEquals(0, snapshots.selectCount(null));
        assertEquals(0, differences.selectCount(null));
        assertEquals(0, tools.selectCount(null));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_scan_project_tool", Long.class));
    }


    @Test void newObservationSupersedesAndIgnoringDoesNotRestoreDrift() {
        sync("initial", read("/orders", null));
        sync("changed", read("/orders-v2", null)); var old = latest();
        assertEquals("PENDING", old.getReviewStatus()); assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
        sync("newer", read("/orders-v3", null));
        assertEquals("SUPERSEDED", differences.selectById(old.getId()).getReviewStatus());
        assertThrows(IllegalArgumentException.class, () -> review(old.getId(), "APPLY"));
        var current = latest(); review(current.getId(), "IGNORE");
        sync("same-again", read("/orders-v3", null));
        assertEquals("IGNORED", latest().getReviewStatus()); assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
        assertThrows(com.enterprise.ai.capability.internal.CapabilityInvocationPolicyException.class, () -> guard.validate(tool(), Map.of()));
        sync("restored-source", read("/orders", null)); assertEquals("READY", guard.availability(tool()));
        assertEquals(0, differences.selectCount(Wrappers.<CapabilityDiffItemEntity>lambdaQuery().eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING")));
    }

    @Test void acceptingClearsNullableFieldsAndOldPublishedContractRemainsBlocked() {
        sync("initial", read("/orders", "OldResponse")); String publishedHash = policy.contractHash(tool());
        sync("updated", read("/orders-v2", null)); var update = latest(); review(update.getId(), "APPLY");
        assertNull(tool().getResponseType()); assertEquals("/orders-v2", tool().getEndpointPath());
        assertEquals("READY", guard.availability(tool()));
        assertThrows(com.enterprise.ai.capability.internal.CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool(), Map.of("expectedContractHash", publishedHash)));
        tx.execute(status -> registry.rollbackDiffItem("orders", update.getId(), new CapabilityReviewRequest("ROLLBACK", "tester", "restore")));
        assertEquals("OldResponse", tool().getResponseType()); assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
        sync("repeat-after-rollback", read("/orders-v2", null));
        assertEquals("PENDING", latest().getReviewStatus()); review(latest().getId(), "APPLY");
        assertEquals("READY", guard.availability(tool()));
    }

    @Test void retractedUnacceptedAdditionCannotBeAppliedAndDeletedSourceIsBlocked() {
        sync("initial", read("/orders", null)); sync("absent");
        assertEquals("SOURCE_MISSING", guard.availability(tool())); assertTrue(tool().getEnabled());
        review(latest().getId(), "APPLY"); assertFalse(tool().getEnabled());
        var write = new CapabilityRegistration("newWrite", "新增写入", "write", "POST", "http://orders.local", null,
                "/write", null, null, "WRITE", true, List.of(), Map.of("assetType", "BUSINESS_METHOD"));
        sync("write-proposal", write); Long proposed = latest().getId(); sync("withdrawn");
        assertEquals("SUPERSEDED", differences.selectById(proposed).getReviewStatus());
        assertThrows(IllegalArgumentException.class, () -> review(proposed, "APPLY"));
    }

    @Test void concurrentIdenticalSyncsProduceOneSnapshotAndOneDecision() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<CapabilitySyncResponse> work = () -> { gate.await(); return sync(UUID.randomUUID().toString(), read("/orders", null)); };
            var one = pool.submit(work); var two = pool.submit(work); gate.countDown();
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
            assertEquals(1, snapshots.selectCount(null)); assertEquals(1, tools.selectCount(null)); assertEquals(1, differences.selectCount(null));
        } finally { pool.shutdownNow(); }
    }

    @Test void sourceOwnershipSurvivesLocationChangesAndCatalogRollback() {
        sync("initial", read("/orders", null));
        assertEquals("orders:read", tool().getSourceQualifiedName());
        assertEquals("orders:read", jdbc.queryForObject(
                "SELECT source_qualified_name FROM capability_scan_project_tool", String.class));
        sync("changed", read("/orders-v2", null));
        Long changeId = latest().getId();
        review(changeId, "APPLY");
        tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore")));
        assertEquals("orders:read", tool().getSourceQualifiedName());
        jdbc.update("UPDATE capability_tool_definition SET source_location = NULL");
        assertNull(tool().getSourceLocation());
        assertEquals("CONTRACT_DRIFT", guard.availability(tool()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"global", "scan", "both"})
    void rollbackRestoresMissingProjectionRowsWithOriginalIdentity(String missing) {
        sync("initial", read("/orders", null));
        Long globalId = tool().getId();
        Long scanId = jdbc.queryForObject("SELECT id FROM capability_scan_project_tool", Long.class);
        sync("changed", read("/orders-v2", null));
        Long changeId = latest().getId();
        review(changeId, "APPLY");
        if (!"scan".equals(missing)) jdbc.update("DELETE FROM capability_tool_definition WHERE id = ?", globalId);
        if (!"global".equals(missing)) jdbc.update("DELETE FROM capability_scan_project_tool WHERE id = ?", scanId);
        tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "restore missing projection")));
        assertNotNull(tool(), "rollback must restore the missing invocation projection");
        assertEquals(globalId, tool().getId());
        assertEquals("/orders", tool().getEndpointPath());
        assertEquals(scanId, jdbc.queryForObject("SELECT id FROM capability_scan_project_tool", Long.class));
        assertEquals(globalId, jdbc.queryForObject("SELECT global_tool_definition_id FROM capability_scan_project_tool", Long.class));
        assertEquals("/orders", jdbc.queryForObject("SELECT endpoint_path FROM capability_scan_project_tool", String.class));
        assertEquals("orders:read", tool().getSourceQualifiedName());
        assertEquals("ROLLED_BACK", latest().getReviewStatus());
        assertEquals("CONTRACT_DRIFT", guard.availability(tool()),
                "restoring the catalog must not pretend the newer observed source contract was reverted");
    }

    @Test void secondProjectionFailureRollsBackFirstRestorationAndReviewEvidence() {
        sync("initial", read("/orders", null));
        sync("changed", read("/orders-v2", null));
        Long changeId = latest().getId();
        review(changeId, "APPLY");
        Long decisions = jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class);
        String acceptedHash = jdbc.queryForObject("SELECT accepted_contract_hash FROM capability_source_state", String.class);
        jdbc.update("DELETE FROM capability_tool_definition");
        jdbc.update("DELETE FROM capability_scan_project_tool");
        jdbc.execute("ALTER TABLE capability_scan_project_tool ADD CONSTRAINT reject_old_projection CHECK (endpoint_path <> '/orders')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                        new CapabilityReviewRequest("ROLLBACK", "tester", "atomic recovery"))));
        assertNull(tool(), "first projection insertion must roll back when the second insertion fails");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_scan_project_tool", Long.class));
        assertEquals("APPLIED", latest().getReviewStatus());
        assertEquals(decisions, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
        assertEquals(acceptedHash, jdbc.queryForObject("SELECT accepted_contract_hash FROM capability_source_state", String.class));
        jdbc.execute("ALTER TABLE capability_scan_project_tool DROP CONSTRAINT reject_old_projection");
        tx.execute(status -> registry.rollbackDiffItem("orders", changeId,
                new CapabilityReviewRequest("ROLLBACK", "tester", "retry atomic recovery")));
        assertEquals("/orders", tool().getEndpointPath());
        assertEquals("ROLLED_BACK", latest().getReviewStatus());
        assertEquals(decisions + 1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_apply_record", Long.class));
    }

    @Test void starterMvcInventoryCreatesDiscoveredHttpFactsWithoutBusinessToolProjectionAndRefreshesReplays() {
        HttpApiRegistration operation = mvcApi("mvc:orders#get", "/mvc/orders/{id}");

        CapabilitySyncResponse first = syncHttp("mvc-first", List.of(operation));

        assertEquals(0, first.received());
        assertEquals(0, first.items().size());
        assertTrue(first.httpApis().supported());
        assertEquals(1, first.httpApis().received());
        assertEquals(1, first.httpApis().observed());
        assertEquals(0L, tools.selectCount(null));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_scan_project_tool", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));
        assertEquals("DISCOVERED", jdbc.queryForObject("SELECT status FROM capability_http_api_asset", String.class));
        assertEquals("STARTER_MVC", jdbc.queryForObject("SELECT source_kind FROM capability_http_api_source_binding", String.class));
        assertEquals("DISCOVERED", jdbc.queryForObject("SELECT status FROM capability_http_api_source_binding", String.class));
        assertEquals("mvc-first", jdbc.queryForObject("SELECT source_revision FROM capability_http_api_source_binding", String.class));
        assertTrue(snapshots.selectOne(null).getPayloadJson().contains("\"httpApis\""));
        assertTrue(snapshots.selectOne(null).getPayloadJson().contains("\"location\":\"BODY\""));
        assertFalse(snapshots.selectOne(null).getPayloadJson().contains("baseUrl"));
        assertEquals(snapshots.selectOne(null).getContentHash(), jdbc.queryForObject(
                "SELECT content_hash FROM capability_sync_receipt WHERE sync_id='mvc-first'", String.class));

        CapabilitySyncResponse replay = syncHttp("mvc-replay", List.of(operation));
        assertEquals(1, replay.httpApis().observed());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Long.class));
        assertEquals("mvc-replay", jdbc.queryForObject("SELECT source_revision FROM capability_http_api_source_binding", String.class));

        syncHttp("mvc-first", List.of(operation));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Long.class));
    }

    @Test void starterMvcFullInventoryDifferentiatesMissingAndEmptyAndRemovesOnlyAfterValidObservation() {
        HttpApiRegistration first = mvcApi("mvc:orders#first", "/mvc/orders/first");
        syncHttp("mvc-initial", List.of(first));

        sync("legacy-without-http-apis");
        assertEquals("DISCOVERED", jdbc.queryForObject("SELECT status FROM capability_http_api_source_binding", String.class));

        CapabilitySyncResponse empty = syncHttp("mvc-empty", List.of());
        assertEquals(1, empty.httpApis().removed());
        assertEquals("REMOVED", jdbc.queryForObject("SELECT status FROM capability_http_api_source_binding", String.class));
        assertEquals("SOURCE_MISSING", jdbc.queryForObject("SELECT status FROM capability_http_api_asset", String.class));

        assertThrows(IllegalArgumentException.class, () -> syncHttp("mvc-invalid", List.of(
                mvcApi("mvc:orders#valid", "/mvc/orders/valid"),
                invalidMvcApi("mvc:orders#invalid"))));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class),
                "validation occurs before new inventory rows or removals are written");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Long.class));
    }

    @Test void starterMvcSameSyncIdRejectsChangedHttpInventoryAndRouteChangesRetireThePriorBinding() {
        HttpApiRegistration oldRoute = mvcApi("mvc:orders#route", "/mvc/orders/old");
        syncHttp("mvc-same", List.of(oldRoute));

        assertThrows(IllegalArgumentException.class, () -> syncHttp("mvc-same", List.of(
                mvcApi("mvc:orders#route", "/mvc/orders/new"))));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));

        HttpApiRegistration newRoute = mvcApi("mvc:orders#route", "/mvc/orders/new");
        syncHttp("mvc-route-change", List.of(newRoute));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));
        assertEquals("DISCOVERED", jdbc.queryForObject(
                "SELECT status FROM capability_http_api_source_binding WHERE source_key='mvc:orders#route'", String.class));
        assertEquals("/mvc/orders/new", jdbc.queryForObject(
                "SELECT route_template FROM capability_http_api_asset WHERE status='DISCOVERED'", String.class));
    }

    @Test void starterMvcConditionChangesRetireTheOldSourceBindingRatherThanLeavingTwoActiveFacts() {
        HttpApiRegistration north = mvcApi("mvc:orders#tenant-north", "/mvc/orders/current", List.of(
                new HttpApiMappingConditionRegistration("PARAM", "tenant", "EQUALS", "north")));
        syncHttp("mvc-condition-north", List.of(north));

        HttpApiRegistration south = mvcApi("mvc:orders#tenant-south", "/mvc/orders/current", List.of(
                new HttpApiMappingConditionRegistration("PARAM", "tenant", "EQUALS", "south")));
        syncHttp("mvc-condition-south", List.of(south));

        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_http_api_source_binding WHERE status='DISCOVERED'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_http_api_source_binding WHERE status='REMOVED'", Long.class));
    }

    @Test void httpApiWireProtocolKeepsMissingAndExplicitEmptyInventoriesDistinct() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CapabilitySyncRequest legacy = mapper.readValue(
                "{\"syncId\":\"legacy-wire\",\"source\":\"starter\",\"apply\":false,\"capabilities\":[]}",
                CapabilitySyncRequest.class);
        CapabilitySyncRequest supportedEmpty = mapper.readValue(
                "{\"syncId\":\"empty-wire\",\"source\":\"starter\",\"apply\":false,\"capabilities\":[],\"httpApis\":[]}",
                CapabilitySyncRequest.class);

        assertNull(legacy.httpApis());
        assertEquals(List.of(), supportedEmpty.httpApis());
    }

    @Test void newProtocolRejectsHttpApiMetadataInTheBusinessMethodArrayBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> tx.execute(status -> registry.sync("orders",
                new CapabilitySyncRequest("wrong-array", "starter", false,
                        List.of(typedRead("/legacy-http", "HTTP_API")), List.of()))));

        assertEquals(0L, snapshots.selectCount(null));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Long.class));
        assertEquals(0L, tools.selectCount(null));
    }

    private CapabilitySyncResponse sync(String id, CapabilityRegistration... registrations) {
        return tx.execute(status -> registry.sync("orders", request(id, registrations)));
    }
    private CapabilitySyncResponse syncHttp(String id, List<HttpApiRegistration> httpApis) {
        return tx.execute(status -> registry.sync("orders", new CapabilitySyncRequest(id, "starter", false,
                List.of(), httpApis)));
    }
    private CapabilitySyncRequest request(String id, CapabilityRegistration... registrations) {
        return new CapabilitySyncRequest(id, "sdk", false, List.of(registrations));
    }
    private CapabilityRegistration read(String path, String response) {
        return new CapabilityRegistration("read", "自动订单查询", "查询订单", "GET", "http://orders.local", null,
                path, null, response, "READ_ONLY", true, List.of(), Map.of("assetType", "BUSINESS_METHOD"));
    }
    private CapabilityRegistration typedRead(String path, String assetType) {
        return new CapabilityRegistration("read", "自动订单查询", "查询订单", "GET", "http://orders.local", null,
                path, null, null, "READ_ONLY", true, List.of(), Map.of("assetType", assetType));
    }
    private HttpApiRegistration mvcApi(String sourceKey, String endpointPath) {
        return mvcApi(sourceKey, endpointPath, List.of());
    }
    private HttpApiRegistration mvcApi(String sourceKey, String endpointPath,
                                       List<HttpApiMappingConditionRegistration> mappingConditions) {
        return new HttpApiRegistration(sourceKey, "mvc:OrdersController#get(java.lang.String)", "GET", null,
                endpointPath, List.of(), List.of(), mappingConditions, List.of(
                new HttpApiParameterRegistration("id", "PATH", true,
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "string"), List.of())),
                new HttpApiRequestBodyRegistration("BODY", true,
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"),
                        List.of("application/json")), List.of(new HttpApiResponseRegistration("DEFAULT",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"), List.of())),
                "UNKNOWN", List.of(), List.of(), "READ_ONLY");
    }
    private HttpApiRegistration invalidMvcApi(String sourceKey) {
        return new HttpApiRegistration(sourceKey, "mvc:OrdersController#invalid()", "INVALID", null,
                "/mvc/orders/invalid", List.of(), List.of(), List.of(), List.of(), null, List.of(),
                "UNKNOWN", List.of(), List.of(), "READ_ONLY");
    }
    private Map<String, Object> consoleRequest(String hash) {
        ToolDefinitionEntity current = tool();
        return Map.of("input", Map.of(), "context", Map.of(), "constraints", Map.of(
                "consoleCapabilityInvocation", true,
                "expectedQualifiedName", current.getQualifiedName(),
                "expectedProjectCode", current.getProjectCode(),
                "expectedProjectId", current.getProjectId(),
                "expectedContractHash", hash,
                "expectedExecutionRevision", "d".repeat(64),
                "requireSignedInvocation", true));
    }
    private void assertProjectionAssetType(String expected) {
        assertEquals(expected, tool().getAssetType());
        assertEquals(expected, jdbc.queryForObject(
                "SELECT asset_type FROM capability_scan_project_tool WHERE project_id = 1", String.class));
        assertEquals(expected, jdbc.queryForObject(
                "SELECT asset_type FROM capability_tool_definition WHERE qualified_name = 'orders:read'", String.class));
    }
    private CapabilityDiffItemEntity latest() { return differences.selectOne(Wrappers.<CapabilityDiffItemEntity>lambdaQuery().orderByDesc(CapabilityDiffItemEntity::getId).last("limit 1")); }
    private ToolDefinitionEntity tool() { return tools.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery().eq(ToolDefinitionEntity::getQualifiedName, "orders:read")); }
    private Object review(Long id, String action) { return tx.execute(status -> registry.reviewDiffItem("orders", id, new CapabilityReviewRequest(action, "tester", "test"))); }
}
