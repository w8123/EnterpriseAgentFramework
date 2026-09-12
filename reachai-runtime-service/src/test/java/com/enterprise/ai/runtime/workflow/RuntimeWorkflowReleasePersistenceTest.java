package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real MyBatis and transactions exercise locking, revision checks and publication audit together. */
class RuntimeWorkflowReleasePersistenceTest {
    private com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase mysql;
    protected boolean useMysql() { return false; }
    private static final String REVISION = "2026-09-05T10:00:00";
    private static final String GRAPH = "{\"nodes\":[{\"id\":\"read\",\"type\":\"TOOL\",\"ref\":{\"name\":\"orders_read\",\"contractHash\":\""
            + "a".repeat(64) + "\"}}],\"entryNodeId\":\"read\"}";
    private RuntimeWorkflowDefinitionMapper workflows;
    private RuntimeWorkflowVersionMapper versions;
    private RuntimeWorkflowReleaseEventMapper events;
    private RuntimeWorkflowReferenceIndex references;
    private RuntimeWorkflowReleaseValidationService validation;
    private RuntimeWorkflowVersionService releases;
    private RuntimeWorkflowDefinitionService definitions;
    private RuntimeCapabilityContractPins pins;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void database() throws Exception {
        javax.sql.DataSource source;
        if (useMysql()) {
            mysql = new com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase(
                    "reachai.mysql.workflowReleaseVerification", "audit_release", "runtime_",
                    List.of("runtime_workflow", "runtime_workflow_version", "runtime_workflow_release_event",
                            "runtime_workflow_capability_reference"));
            source = mysql;
            try (var connection = source.getConnection()) {
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            }
        } else {
            source = new DriverManagerDataSource("jdbc:h2:mem:releases_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        jdbc = new JdbcTemplate(source);
        if (!useMysql()) {
            String baseline = Files.readString(Path.of("../sql/initV2.sql"));
            for (String table : List.of("runtime_workflow", "runtime_workflow_version", "runtime_workflow_release_event",
                    "runtime_workflow_capability_reference")) {
                var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?;").matcher(baseline);
                assertTrue(match.find());
                jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                        .replaceAll("(?i)COLLATE \\w+", "")
                        .replaceAll("`reference_key`\\(191\\)", "`reference_key`")
                        .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`"));
            }
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class,
                RuntimeWorkflowReleaseEventMapper.class, RuntimeWorkflowReferenceMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        workflows = session.getMapper(RuntimeWorkflowDefinitionMapper.class);
        versions = session.getMapper(RuntimeWorkflowVersionMapper.class);
        events = session.getMapper(RuntimeWorkflowReleaseEventMapper.class);
        references = new RuntimeWorkflowReferenceIndex(session.getMapper(RuntimeWorkflowReferenceMapper.class),
                workflows, versions, new ObjectMapper());
        definitions = new RuntimeWorkflowDefinitionService(workflows, versions,
                mock(RuntimeWorkflowDeletionReferences.class), mock(RuntimeWorkflowDocumentCanonicalizer.class),
                mock(RuntimeWorkflowResourceBindingService.class), references);
        validation = mock(RuntimeWorkflowReleaseValidationService.class);
        when(validation.validate(any())).thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validation.validateProposed(any(), any())).thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validation.readGraph(anyString(), any())).thenAnswer(call ->
                new ObjectMapper().readValue((String) call.getArgument(0), com.enterprise.ai.agent.graph.GraphSpec.class));
        pins = mock(RuntimeCapabilityContractPins.class);
        when(pins.pin(anyString())).thenAnswer(call -> call.getArgument(0));
        releases = new RuntimeWorkflowVersionService(versions, definitions, validation, new ObjectMapper(), pins, events, references);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setKeySlug("orders-query");
        workflow.setName("Orders");
        workflow.setGraphSpecJson(GRAPH);
        workflow.setDefaultModelInstanceId("published-model");
        workflow.setUpdatedAt(LocalDateTime.parse(REVISION));
        tx.execute(status -> { workflows.insert(workflow); references.indexDraft(workflow); return null; });
    }

    @AfterEach void cleanup() {
        if (mysql != null) mysql.close();
        else if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void concurrentPublishCommandsForOneRevisionCommitOneActiveReleaseAndOneEvent() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Boolean> command = () -> {
                gate.await();
                try {
                    tx.execute(status -> releases.publish("wf-orders", UUID.randomUUID().toString().substring(0, 8),
                            100, null, "platform:42", REVISION));
                    return true;
                } catch (RuntimeWorkflowRevisionConflictException conflict) { return false; }
            };
            var first = pool.submit(command);
            var second = pool.submit(command);
            gate.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, versions.selectCount(null));
            assertEquals(1, versions.listActive("wf-orders").size());
            assertEquals(1, events.selectCount(null));
            assertEquals("PUBLISH", events.selectList(null).get(0).getAction());
            var evidence = references.inspect(Set.of("orders_read"));
            assertTrue(evidence.warnings().isEmpty());
            assertEquals(2, evidence.hits().size());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void rollbackPreservesWorkingCopyAndOriginalPublisherAndAppendsSeparateEvent() {
        var original = publish("v1", "platform:42", REVISION);
        var originallyStored = versions.selectById(original.getId());
        var latest = publish("v2", "platform:55", revision());
        jdbc.update("UPDATE runtime_workflow SET graph_spec_json = ?, default_model_instance_id = ? WHERE id = ?",
                "{\"nodes\":[]}", "new-draft-model", "wf-orders");
        String draft = workflows.selectById("wf-orders").getGraphSpecJson();
        tx.execute(status -> releases.rollback("wf-orders", original.getId(), "platform:77", revision()));
        var restored = versions.selectById(original.getId());
        assertEquals("platform:42", restored.getPublishedBy());
        assertEquals(originallyStored.getPublishedAt(), restored.getPublishedAt());
        assertEquals(original.getSnapshotJson(), restored.getSnapshotJson());
        assertEquals(draft, workflows.selectById("wf-orders").getGraphSpecJson());
        assertEquals("new-draft-model", workflows.selectById("wf-orders").getDefaultModelInstanceId());
        var event = events.selectOne(Wrappers.<RuntimeWorkflowReleaseEventEntity>lambdaQuery()
                .eq(RuntimeWorkflowReleaseEventEntity::getAction, "ROLLBACK"));
        assertEquals("platform:77", event.getActor());
        assertEquals(latest.getId(), event.getPreviousVersionId());
        assertEquals(original.getId(), event.getTargetVersionId());
        verify(validation).validateProposed(argThat(value -> "published-model".equals(value.getDefaultModelInstanceId())), any());
        assertEquals(1, versions.listActive("wf-orders").size());
        var evidence = references.inspect(Set.of("orders_read"));
        assertTrue(evidence.warnings().isEmpty());
        assertEquals(2, evidence.hits().size());
        assertTrue(evidence.hits().stream().allMatch(hit -> hit.versionId() != null));
    }

    @Test
    void missingOrStaleRevisionCannotMutatePublicationHistory() {
        assertThrows(IllegalArgumentException.class, () -> publish("v1", "platform:42", null));
        var original = publish("v1", "platform:42", REVISION);
        assertThrows(RuntimeWorkflowRevisionConflictException.class,
                () -> tx.execute(status -> releases.rollback("wf-orders", original.getId(), "platform:77", REVISION)));
        assertEquals(1, events.selectCount(null));
        assertEquals(1, versions.selectCount(null));
    }

    @Test
    void failedAuditWriteRollsBackActivationAndRevision() {
        var failingEvents = mock(RuntimeWorkflowReleaseEventMapper.class);
        when(failingEvents.insert(any(RuntimeWorkflowReleaseEventEntity.class))).thenThrow(new IllegalStateException("audit unavailable"));
        var service = new RuntimeWorkflowVersionService(versions, definitions, validation, new ObjectMapper(), pins, failingEvents, references);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                service.publish("wf-orders", "v1", 100, null, "platform:42", REVISION)));
        assertEquals(0, versions.selectCount(null));
        assertEquals(LocalDateTime.parse(REVISION), workflows.selectById("wf-orders").getUpdatedAt());
        var evidence = references.inspect(Set.of("orders_read"));
        assertTrue(evidence.warnings().isEmpty());
        assertEquals(1, evidence.hits().size());
        assertNull(evidence.hits().get(0).versionId());
    }

    @Test
    void failedReferenceWriteRollsBackNewReleaseAndPreviousActivation() {
        var first = publish("v1", "platform:42", REVISION);
        String baseRevision = revision();
        var failingIndex = spy(references);
        doThrow(new IllegalStateException("reference store unavailable")).when(failingIndex).indexVersion(any());
        var service = new RuntimeWorkflowVersionService(versions, definitions, validation, new ObjectMapper(), pins,
                events, failingIndex);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                service.publish("wf-orders", "v2", 100, null, "platform:55", baseRevision)));
        assertEquals(1, versions.selectCount(null));
        assertEquals(first.getId(), versions.listActive("wf-orders").get(0).getId());
        assertEquals(1, events.selectCount(null));
        assertEquals(baseRevision, revision());
        var evidence = references.inspect(Set.of("orders_read"));
        assertTrue(evidence.warnings().isEmpty());
        assertEquals(2, evidence.hits().size());
    }

    private RuntimeWorkflowVersionEntity publish(String version, String actor, String baseRevision) {
        return tx.execute(status -> releases.publish("wf-orders", version, 100, null, actor, baseRevision));
    }

    private String revision() { return workflows.selectById("wf-orders").getUpdatedAt().toString(); }
}
