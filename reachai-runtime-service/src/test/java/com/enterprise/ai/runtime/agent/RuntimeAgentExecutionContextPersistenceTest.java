package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner queries and transaction boundaries; no external execution or MySQL acceptance. */
class RuntimeAgentExecutionContextPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private DataSourceTransactionManager transactions;
    private RuntimeAgentExecutionContextResolver resolver;
    private RuntimeAgentExecutionContextResolver unscopedResolver;
    private final ReadProbe reads = new ReadProbe();

    @BeforeEach
    void realQueries() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool", "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding",
                "runtime_workflow", "runtime_workflow_version"), RuntimeAgentMapper.class,
                RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class,
                RuntimeAgentSkillBindingMapper.class, RuntimeAgentRemoteBindingMapper.class,
                RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class);
        transactions = new DataSourceTransactionManager(database.jdbc().getDataSource()) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(connection, definition);
                // H2 REPEATABLE_READ retains individual reads; MySQL InnoDB uses one consistent-read snapshot.
                // Translate only the owner's explicit read-only RR request to H2's SNAPSHOT for this fixture.
                // Missing annotations or joining the outer READ_COMMITTED transaction still fail the race cases.
                // https://www.h2database.com/html/advanced.html#transaction_isolation
                // https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html
                if (definition.isReadOnly()
                        && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SNAPSHOT");
                    }
                }
            }
        };
        var target = new RuntimeAgentExecutionContextResolver(new RuntimeAgentIdentityReader(database.mapper(RuntimeAgentMapper.class)),
                database.mapper(RuntimeAgentConfigVersionMapper.class), database.mapper(RuntimeAgentWorkflowToolMapper.class),
                database.mapper(RuntimeAgentSkillBindingMapper.class),
                new RuntimeWorkflowExecutionReader(database.mapper(RuntimeWorkflowDefinitionMapper.class),
                        database.mapper(RuntimeWorkflowVersionMapper.class)),
                new RuntimeAgentRemoteBindingReader(database.mapper(RuntimeAgentRemoteBindingMapper.class),
                        database.mapper(RuntimeAgentConfigVersionMapper.class)));
        var proxy = new ProxyFactory(target);
        unscopedResolver = target;
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        resolver = (RuntimeAgentExecutionContextResolver) proxy.getProxy();
        seed();
        database.addInterceptor(reads);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"START", "PUBLISHED", "EVALUATION"})
    void allEntryPointsReadOneCommittedPublication(String mode) {
        reads.after("runtime_agent", () -> independentlyCommit(
                "UPDATE runtime_agent_config_version SET status='ARCHIVED' WHERE id=11",
                "UPDATE runtime_agent_config_version SET status='ACTIVE' WHERE id=12",
                "UPDATE runtime_agent SET active_config_version_id=12, project_code='next-project' WHERE id='agent-1'"));

        RuntimeAgentExecutionContext context = switch (mode) {
            case "START" -> resolver.resolve(" orders-agent ").orElseThrow();
            case "PUBLISHED" -> resolver.resolvePublished("agent-1", 11L).orElseThrow();
            default -> resolver.resolveForEvaluation("agent-1", 11L).orElseThrow();
        };

        assertTrue(reads.fired);
        assertAll(
                () -> assertEquals(11L, context.agent().activeConfigVersionId()),
                () -> assertEquals("orders", context.agent().projectCode()),
                () -> assertEquals(11L, context.config().getId()),
                () -> assertEquals("ACTIVE", context.config().getStatus()),
                () -> assertEquals("原始配置", context.config().getSystemPrompt()));
        assertEquals(12L, database.jdbc().queryForObject(
                "SELECT active_config_version_id FROM runtime_agent WHERE id='agent-1'", Long.class));
        assertEquals(12L, resolver.resolve("agent-1").orElseThrow().config().getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void evaluationDoesNotMixDraftConfigurationAndLaterBindings(boolean existingWriteTransaction) {
        database.jdbc().update("UPDATE runtime_agent_config_version SET status='DRAFT' WHERE id=11");
        reads.after("runtime_agent_config_version", () -> independentlyCommit(
                "UPDATE runtime_agent_config_version SET system_prompt='later-prompt' WHERE id=11",
                "UPDATE runtime_agent_workflow_tool SET risk_level='WRITE', read_only=0 WHERE id=21",
                "UPDATE runtime_agent_skill_binding SET version='2.0' WHERE id=31",
                "UPDATE runtime_agent_remote_agent_binding SET remote_agent_revision_id=99 WHERE id=41",
                "UPDATE runtime_workflow SET name='later-name' WHERE id='wf-1'"));
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        RuntimeAgentExecutionContext context = existingWriteTransaction
                ? outer.execute(status -> resolver.resolveForEvaluation("agent-1", 11L).orElseThrow())
                : resolver.resolveForEvaluation("agent-1", 11L).orElseThrow();

        assertNotNull(context);
        assertTrue(reads.fired);
        assertOriginalValues(context);
        assertEquals("DRAFT", context.config().getStatus());
        assertEquals("later-prompt", database.jdbc().queryForObject(
                "SELECT system_prompt FROM runtime_agent_config_version WHERE id=11", String.class));
    }

    @Test
    void mapperResultAliasesCannotChangeTheExecutionSnapshot() {
        RuntimeAgentExecutionContext context = resolver.resolve("agent-1").orElseThrow();
        reads.first(RuntimeAgentConfigVersionEntity.class).setSystemPrompt("mutated-owner-row");
        reads.first(RuntimeAgentWorkflowToolEntity.class).setRiskLevel("WRITE");
        reads.first(RuntimeAgentWorkflowToolEntity.class).setReadOnly(false);
        reads.first(RuntimeAgentSkillBindingEntity.class).setVersion("2.0");

        assertOriginalValues(context);
        assertEquals("READ", context.resolvedTargets().get(0).tool().getRiskLevel());
    }

    @Test
    void exportedCatalogsCannotBeChangedByAConsumer() {
        RuntimeAgentExecutionContext context = resolver.resolve("agent-1").orElseThrow();
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> context.tools().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> context.skills().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> context.remoteAgents().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> context.resolvedTargets().clear()));
    }

    @Test
    void evaluationReadsCommittedConfigurationAndRestoresTheOuterTransaction() {
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        RuntimeAgentExecutionContext context = outer.execute(status -> {
            database.jdbc().update("UPDATE runtime_agent_config_version SET model_instance_id='outer-only' WHERE id=11");
            var captured = resolver.resolveForEvaluation("agent-1", 11L).orElseThrow();
            assertEquals("old-model", captured.config().getModelInstanceId());
            assertEquals("outer-only", database.jdbc().queryForObject(
                    "SELECT model_instance_id FROM runtime_agent_config_version WHERE id=11", String.class));
            status.setRollbackOnly();
            return captured;
        });
        assertNotNull(context);
        assertEquals("old-model", database.jdbc().queryForObject(
                "SELECT model_instance_id FROM runtime_agent_config_version WHERE id=11", String.class));
        assertEquals("old-model", context.config().getModelInstanceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ARCHIVED"})
    void explicitPublishedVersionDoesNotFollowTheCurrentPointer(String status) {
        database.jdbc().update("UPDATE runtime_agent_config_version SET status=? WHERE id=11", status);
        database.jdbc().update("UPDATE runtime_agent_config_version SET status='ACTIVE' WHERE id=12");
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id=12 WHERE id='agent-1'");

        RuntimeAgentExecutionContext context = resolver.resolvePublished("agent-1", 11L).orElseThrow();

        assertEquals(12L, context.agent().activeConfigVersionId());
        assertEquals(11L, context.config().getId());
        assertEquals(status, context.config().getStatus());
        assertOriginalValues(context);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "FOREIGN", "DRAFT"})
    void fixedVersionLookupKeepsOwnershipAndEvaluationRules(String scenario) {
        Long requested = "MISSING".equals(scenario) ? 999L : 12L;
        if ("FOREIGN".equals(scenario)) {
            database.jdbc().update("UPDATE runtime_agent_config_version SET agent_id='agent-2', status='ACTIVE' WHERE id=12");
        }
        assertNull(resolver.resolvePublished("agent-1", requested).orElseThrow().config());
        RuntimeAgentExecutionContext evaluation = resolver.resolveForEvaluation("agent-1", requested).orElseThrow();
        if ("DRAFT".equals(scenario)) {
            assertEquals(12L, evaluation.config().getId());
            assertEquals("DRAFT", evaluation.config().getStatus());
        } else {
            assertNull(evaluation.config());
        }
        assertTrue(evaluation.tools().isEmpty());
        assertTrue(evaluation.resolvedTargets().isEmpty());
        assertNull(reads.first(RuntimeAgentWorkflowToolEntity.class));
    }

    @Test
    void toolCatalogKeepsOwnerVersionEnabledFilteringAndStableOrder() {
        database.jdbc().update("""
                INSERT INTO runtime_workflow (id, key_slug, name, status) VALUES ('wf-2', 'second', '第二个查询', 'ACTIVE')
                """);
        database.jdbc().update("""
                INSERT INTO runtime_workflow_version (id, workflow_id, version, status, snapshot_json, graph_spec_snapshot_json)
                VALUES (52, 'wf-2', '1', 'RETIRED', '{"defaultModelInstanceId":null}', '{"nodes":[]}')
                """);
        database.jdbc().update("UPDATE runtime_agent_workflow_tool SET priority=10 WHERE id=21");
        database.jdbc().update("""
                INSERT INTO runtime_agent_workflow_tool (id, agent_id, agent_config_version_id, workflow_id,
                    workflow_version_id, tool_name, priority, enabled) VALUES
                    (22, 'agent-1', 11, 'wf-2', 52, 'second', 1, 1),
                    (23, 'agent-1', 11, 'missing-disabled', 99, 'disabled', 0, 0),
                    (24, 'agent-1', 12, 'missing-draft', 99, 'draft', 0, 1),
                    (25, 'agent-2', 11, 'missing-foreign', 99, 'foreign', 0, 1)
                """);

        RuntimeAgentExecutionContext context = resolver.resolve("agent-1").orElseThrow();

        assertEquals(List.of(22L, 21L), context.tools().stream().map(value -> value.getId()).toList());
        assertEquals(List.of("wf-2", "wf-1"), context.resolvedTargets().stream()
                .map(value -> value.workflow().getId()).toList());
        assertEquals(52L, context.resolvedTargets().get(0).version().getId());
    }

    @Test
    void disabledAgentIdentityRemainsAvailableForTheExecutionGate() {
        database.jdbc().update("UPDATE runtime_agent SET enabled=0 WHERE id='agent-1'");
        RuntimeAgentExecutionContext context = resolver.resolvePublished("agent-1", 11L).orElseThrow();
        assertEquals(false, context.agent().enabled());
        assertEquals(11L, context.config().getId());
    }

    @Test
    void failedTargetQueryCannotReturnAPartialExecutionContext() {
        database.jdbc().execute("DROP TABLE runtime_workflow_version");
        assertThrows(RuntimeException.class, () -> resolver.resolve("agent-1"));
        assertEquals(11L, database.jdbc().queryForObject(
                "SELECT active_config_version_id FROM runtime_agent WHERE id='agent-1'", Long.class));
    }

    @Test
    void supervisorRequestDetachesItsCatalogFromTheCallerList() {
        RuntimeAgentExecutionContext context = resolver.resolve("agent-1").orElseThrow();
        var supplied = new ArrayList<>(context.tools());
        var request = new SupervisorRuntimeAdapter.SupervisorRequest(
                context.agentView(), context.config(), supplied, Map.of());
        supplied.clear();
        assertEquals(1, request.workflowTools().size());
        assertEquals("READ", request.workflowTools().get(0).getRiskLevel());
        assertThrows(UnsupportedOperationException.class, () -> request.workflowTools().clear());
    }

    @Test
    void snapshotFixtureDoesNotHideAMissingOwnerTransaction() {
        reads.after("runtime_agent", () -> independentlyCommit(
                "UPDATE runtime_agent_config_version SET status='ARCHIVED' WHERE id=11",
                "UPDATE runtime_agent_config_version SET status='ACTIVE' WHERE id=12",
                "UPDATE runtime_agent SET active_config_version_id=12 WHERE id='agent-1'"));
        RuntimeAgentExecutionContext mixed = unscopedResolver.resolve("agent-1").orElseThrow();
        assertTrue(reads.fired);
        assertEquals(11L, mixed.agent().activeConfigVersionId());
        assertEquals(12L, mixed.config().getId());
    }

    private void assertOriginalValues(RuntimeAgentExecutionContext context) {
        assertAll(
                () -> assertEquals("原始配置", context.config().getSystemPrompt()),
                () -> assertEquals("READ", context.tools().get(0).getRiskLevel()),
                () -> assertEquals(true, context.tools().get(0).getReadOnly()),
                () -> assertEquals("1.0", context.skills().get(0).getVersion()),
                () -> assertEquals(91L, context.remoteAgents().get(0).getRemoteAgentRevisionId()),
                () -> assertEquals("订单查询", context.resolvedTargets().get(0).workflow().getName()));
    }

    private void independentlyCommit(String... statements) {
        try (var connection = database.jdbc().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                for (String sql : statements) statement.executeUpdate(sql);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Independent fixture commit failed", failure);
        }
    }

    private void seed() {
        database.jdbc().update("""
                INSERT INTO runtime_agent (id, key_slug, name, project_id, project_code, active_config_version_id)
                VALUES ('agent-1', 'orders-agent', '订单助手', 7, 'orders', 11)
                """);
        database.jdbc().update("""
                INSERT INTO runtime_agent_config_version (id, agent_id, version_no, status, system_prompt, model_instance_id)
                VALUES (11, 'agent-1', 1, 'ACTIVE', '原始配置', 'old-model'),
                       (12, 'agent-1', 2, 'DRAFT', '新配置', 'new-model')
                """);
        database.jdbc().update("""
                INSERT INTO runtime_workflow (id, key_slug, name, status) VALUES ('wf-1', 'orders-query', '订单查询', 'ACTIVE')
                """);
        database.jdbc().update("""
                INSERT INTO runtime_workflow_version (id, workflow_id, version, snapshot_json, graph_spec_snapshot_json)
                VALUES (51, 'wf-1', '1', '{"id":"wf-1","defaultModelInstanceId":null}', '{"nodes":[]}')
                """);
        database.jdbc().update("""
                INSERT INTO runtime_agent_workflow_tool (id, agent_id, agent_config_version_id, workflow_id, workflow_version_id, tool_name)
                VALUES (21, 'agent-1', 11, 'wf-1', 51, 'query_orders')
                """);
        database.jdbc().update("""
                INSERT INTO runtime_agent_skill_binding (id, agent_id, agent_config_version_id, skill_id, skill_version_id,
                    publisher, standard_name, visibility, version, source_sha256, content_tree_sha256, package_manifest_json)
                VALUES (31, 'agent-1', 11, 61, 71, 'reachai', 'orders-review', 'PROJECT', '1.0', ?, ?, '{}')
                """, "a".repeat(64), "b".repeat(64));
        database.jdbc().update("""
                INSERT INTO runtime_agent_remote_agent_binding (id, agent_id, agent_config_version_id, principal_id,
                    remote_agent_id, remote_agent_revision_id, remote_agent_key_snapshot, tool_name, description_snapshot,
                    input_modes_json, output_modes_json) VALUES
                    (41, 'agent-1', 11, 81, 82, 91, 'remote-orders', 'review_orders', '订单复核', '["text"]', '["text"]')
                """);
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    private static final class ReadProbe implements Interceptor {
        private final Map<Class<?>, Object> firstRows = new HashMap<>();
        private String table;
        private Runnable action;
        private boolean fired;

        void after(String table, Runnable action) {
            this.table = table;
            this.action = action;
        }

        <T> T first(Class<T> type) {
            return type.cast(firstRows.get(type));
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            for (Object row : (List<?>) result) firstRows.putIfAbsent(row.getClass(), row);
            String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql()
                    .replace('`', ' ').toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (action != null && sql.contains("from " + table + " ")) {
                Runnable pending = action;
                action = null;
                fired = true;
                pending.run();
            }
            return result;
        }
    }
}
