package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentIdentityReader;
import com.enterprise.ai.runtime.agent.RuntimeAgentIdentityQuery;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingQuery;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeSqlQueryRecorder;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual Agent/Eval SQL and dataset transactions; experiment execution and config resolution are not invoked. */
class RuntimeEvalAgentIdentityPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeEvalDatasetService datasets;
    private RuntimeEvalExperimentService experiments;
    private RuntimeAgentExecutionContextResolver execution;
    private RuntimeAgentIdentityQuery identities;
    private final RuntimeSqlQueryRecorder queries = new RuntimeSqlQueryRecorder();

    @BeforeEach
    void realOwnerAndEvalQueries() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_eval_dataset",
                "runtime_eval_dataset_version", "runtime_eval_dataset_item", "runtime_eval_experiment"),
                RuntimeAgentMapper.class, RuntimeEvalDatasetMapper.class, RuntimeEvalDatasetVersionMapper.class,
                RuntimeEvalDatasetItemMapper.class, RuntimeEvalExperimentMapper.class);
        var transactions = new DataSourceTransactionManager(database.jdbc().getDataSource());
        var ownerProxy = new ProxyFactory(new RuntimeAgentIdentityReader(database.mapper(RuntimeAgentMapper.class)));
        ownerProxy.setProxyTargetClass(true);
        ownerProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        RuntimeAgentIdentityQuery agents = (RuntimeAgentIdentityQuery) ownerProxy.getProxy();
        identities = agents;
        var json = new RuntimeEvalJsonSupport(new ObjectMapper());
        var datasetTarget = new RuntimeEvalDatasetService(database.mapper(RuntimeEvalDatasetMapper.class),
                database.mapper(RuntimeEvalDatasetVersionMapper.class), database.mapper(RuntimeEvalDatasetItemMapper.class),
                agents, mock(RuntimeRunOpsQueryService.class), json);
        var proxy = new ProxyFactory(datasetTarget);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        datasets = (RuntimeEvalDatasetService) proxy.getProxy();
        experiments = new RuntimeEvalExperimentService(database.mapper(RuntimeEvalExperimentMapper.class),
                mock(RuntimeEvalExperimentVariantMapper.class), mock(RuntimeEvalExperimentItemMapper.class),
                mock(RuntimeEvalScoreMapper.class), mock(RuntimeEvalTaskMapper.class), datasets,
                database.mapper(RuntimeEvalDatasetMapper.class), mock(RuntimeEvalEvaluatorSuiteService.class),
                mock(RuntimeEvalTargetSnapshotService.class), agents, json);
        execution = new RuntimeAgentExecutionContextResolver(agents, mock(RuntimeAgentConfigVersionMapper.class),
                mock(RuntimeAgentWorkflowToolMapper.class), mock(RuntimeAgentSkillBindingMapper.class),
                mock(RuntimeWorkflowExecutionQuery.class), mock(RuntimeAgentRemoteBindingQuery.class));
        database.jdbc().update("""
                INSERT INTO runtime_agent (id, key_slug, name, project_code, enabled) VALUES
                  ('agent-a', 'target-id', '别名持有者', 'alias-project', 1),
                  ('target-id', 'real-agent', '目标助手', 'real-project', 1)
                """);
        database.addInterceptor(queries);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "DATASETS", "EXPERIMENTS", "EXECUTION", "ADMIN"})
    void stableIdTakesPrecedenceOverAnotherAgentsAlias(String entry) {
        if ("CREATE".equals(entry)) {
            var detail = datasets.create(request("target-id"));
            assertEquals("target-id", detail.dataset().targetId());
            assertEquals("real-project", detail.dataset().projectCode());
            assertEquals("中文回归数据集", detail.dataset().name());
        } else if ("EXECUTION".equals(entry)) {
            var identity = execution.resolve("target-id").orElseThrow().agent();
            assertEquals("target-id", identity.id());
            assertEquals("real-project", identity.projectCode());
        } else if ("ADMIN".equals(entry)) {
            var service = new RuntimeAgentService(database.mapper(RuntimeAgentMapper.class), mock(RuntimeAgentConfigService.class));
            var identity = service.findByIdOrKeySlug("target-id").orElseThrow();
            assertEquals("target-id", identity.id());
            assertEquals("real-project", identity.projectCode());
        } else {
            catalogRow(10L, "target-id");
            catalogRow(20L, "agent-a");
            List<Long> ids = "DATASETS".equals(entry)
                    ? datasets.list("tenant-a", "target-id").stream().map(value -> value.id()).toList()
                    : experiments.list("tenant-a", "target-id").stream().map(value -> value.id()).toList();
            assertEquals(List.of(10L), ids);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void datasetCreationAcceptsAnExistingAliasWithoutRequiringEnabledOrPublished(boolean enabled) {
        database.jdbc().update("UPDATE runtime_agent SET enabled=? WHERE id='target-id'", enabled);
        var detail = datasets.create(request(" real-agent "));
        assertEquals("target-id", detail.dataset().targetId());
        assertEquals("real-project", detail.dataset().projectCode());
        assertEquals(1, detail.currentVersion().itemCount());
        assertEquals("检查订单", detail.currentVersion().items().get(0).message());
        assertEquals(64, detail.currentVersion().fingerprintSha256().length());
        assertEquals("target-id", database.jdbc().queryForObject(
                "SELECT target_id FROM runtime_eval_dataset WHERE id=?", String.class, detail.dataset().id()));
    }

    @Test
    void missingAgentDoesNotHideHistoricalCatalogRows() {
        catalogRow(10L, "deleted-agent");
        catalogRow(20L, "target-id");
        assertEquals(List.of(10L), datasets.list("tenant-a", " deleted-agent ").stream().map(value -> value.id()).toList());
        assertEquals(List.of(10L), experiments.list("tenant-a", " deleted-agent ").stream().map(value -> value.id()).toList());
    }

    @Test
    void aMissingCreationTargetCannotLeaveDatasetOrVersionRows() {
        var failure = assertThrows(IllegalArgumentException.class, () -> datasets.create(request("absent-agent")));
        assertEquals("Eval dataset Agent target not found: absent-agent", failure.getMessage());
        for (String table : List.of("runtime_eval_dataset", "runtime_eval_dataset_version", "runtime_eval_dataset_item")) {
            assertEquals(0L, database.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void absentFilterDoesNotResolveAnAgentOrHideCatalogs(String filter) {
        catalogRow(10L, "target-id");
        catalogRow(20L, "deleted-agent");
        assertEquals(2, datasets.list("tenant-a", filter).size());
        assertEquals(2, experiments.list("tenant-a", filter).size());
        assertTrue(identities.find(filter).isEmpty());
        assertTrue(queries.queries().stream().noneMatch(query -> query.sql().toLowerCase(Locale.ROOT).contains("from runtime_agent")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"target-id", " real-agent "})
    void identityQueryUsesOneBoundedStatementAndReturnsDetachedValues(String lookup) {
        var identity = identities.find(lookup).orElseThrow();
        assertEquals("target-id", identity.id());
        assertEquals("real-project", identity.projectCode());
        assertNull(identity.activeConfigVersionId());
        assertEquals(1, queries.queries().size());
        var query = queries.queries().get(0);
        assertEquals(1, query.rowCount());
        assertTrue(query.sql().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).contains("LIMIT 1"));
        assertFalse(query.sql().contains(lookup.trim()));
        database.jdbc().update("UPDATE runtime_agent SET name='后续修改', project_code='later-project' WHERE id='target-id'");
        assertEquals("目标助手", identity.name());
        assertEquals("real-project", identity.projectCode());
        assertEquals("后续修改", identities.find(lookup).orElseThrow().name());
    }

    @Test
    void renamingAnAliasKeepsHistoricalTargetsOnTheirStableId() {
        catalogRow(10L, "target-id");
        database.jdbc().update("UPDATE runtime_agent SET key_slug='updated-alias' WHERE id='target-id'");
        assertEquals(List.of(10L), datasets.list("tenant-a", "updated-alias").stream().map(value -> value.id()).toList());
        assertEquals(List.of(10L), experiments.list("tenant-a", "target-id").stream().map(value -> value.id()).toList());
        assertTrue(datasets.list("tenant-a", "real-agent").isEmpty());
        assertTrue(experiments.list("tenant-a", "real-agent").isEmpty());
    }

    @Test
    void ownerReadFailureIsTechnicalAndLeavesNoDataset() {
        database.jdbc().execute("DROP TABLE runtime_agent");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> datasets.create(request("target-id")));
        assertFalse(failure instanceof IllegalArgumentException);
        assertThrows(RuntimeException.class, () -> datasets.list("tenant-a", "target-id"));
        assertThrows(RuntimeException.class, () -> experiments.list("tenant-a", "target-id"));
        assertEquals(0L, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_eval_dataset", Long.class));
    }

    @Test
    void validationAfterIdentityLookupStillRollsBackTheDatasetInsert() {
        var invalid = new java.util.LinkedHashMap<>(request("target-id"));
        invalid.put("items", List.of());
        assertThrows(IllegalArgumentException.class, () -> datasets.create(invalid));
        assertEquals(0L, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_eval_dataset", Long.class));
        assertEquals(0L, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_eval_dataset_version", Long.class));
    }

    @Test
    void sqlLookingInputRemainsAnOrdinaryMissingIdentity() {
        String lookup = "missing' OR 1=1 --";
        catalogRow(10L, "target-id");
        assertTrue(identities.find(lookup).isEmpty());
        assertTrue(datasets.list("tenant-a", lookup).isEmpty());
        assertTrue(experiments.list("tenant-a", lookup).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> datasets.create(request(lookup)));
        assertTrue(queries.queries().stream().noneMatch(query -> query.sql().contains(lookup)));
        assertEquals(1L, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_eval_dataset", Long.class));
    }

    @Test
    void catalogTenantAndStatusFiltersRemainOwnedByEval() {
        catalogRow(10L, "target-id");
        catalogRow(20L, "target-id");
        catalogRow(30L, "target-id");
        database.jdbc().update("UPDATE runtime_eval_dataset SET tenant_id='tenant-b' WHERE id=20");
        database.jdbc().update("UPDATE runtime_eval_experiment SET tenant_id='tenant-b' WHERE id=20");
        database.jdbc().update("UPDATE runtime_eval_dataset SET status='ARCHIVED' WHERE id=30");
        database.jdbc().update("UPDATE runtime_eval_experiment SET status='CANCELLED' WHERE id=30");
        assertEquals(List.of(10L), datasets.list("tenant-a", "real-agent").stream().map(value -> value.id()).toList());
        assertEquals(Set.of(10L, 30L), experiments.list("tenant-a", "real-agent").stream()
                .map(value -> value.id()).collect(Collectors.toSet()));
    }

    private Map<String, Object> request(String target) {
        return Map.of("tenantId", "tenant-a", "targetId", target, "name", "中文回归数据集",
                "projectCode", "caller-forged-project", "items", List.of(Map.of(
                        "itemKey", "orders", "message", "检查订单", "input", Map.of("message", "检查订单"),
                        "expected", Map.of("success", true))));
    }

    private void catalogRow(Long id, String targetId) {
        database.jdbc().update("""
                INSERT INTO runtime_eval_dataset (id, tenant_id, target_id, name)
                VALUES (?, 'tenant-a', ?, ?)
                """, id, targetId, "数据集-" + id);
        database.jdbc().update("""
                INSERT INTO runtime_eval_experiment (id, tenant_id, target_id, name, dataset_version_id,
                    evaluator_suite_version_id, variant_count, task_count, gate_config_json, summary_json)
                VALUES (?, 'tenant-a', ?, ?, 1, 1, 2, 2, '{}', '{}')
                """, id, targetId, "实验-" + id);
    }
}
