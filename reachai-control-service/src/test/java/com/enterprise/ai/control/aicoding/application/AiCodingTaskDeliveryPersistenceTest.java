package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractRef;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactNextAction;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingArtifactContractValidator;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCodingTaskDeliveryPersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private AiCodingTaskMapper tasks;
    private AiCodingTaskEventMapper events;
    private AiCodingTaskArtifactMapper artifacts;
    private AiCodingTaskKindProvider provider;
    private AiCodingTaskDeliveryService delivery;

    @BeforeEach
    void owningCommandsWithActualTransactionsAndOptimisticLocks() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:task_delivery_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("control_ai_coding_task", "control_ai_coding_task_target",
                "control_ai_coding_task_event", "control_ai_coding_task_artifact", "control_ai_coding_task_question")) {
            var definition = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table
                    + "`\\s*\\(.*?\\)\\s*ENGINE=.*?;").matcher(baseline);
            assertTrue(definition.find(), table);
            jdbc.execute(definition.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
        }
        jdbc.execute("CREATE TABLE delivery_probe (task_id VARCHAR(40) PRIMARY KEY, content_value VARCHAR(100))");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AiCodingTaskMapper.class, AiCodingTaskTargetMapper.class,
                AiCodingTaskEventMapper.class, AiCodingTaskArtifactMapper.class, AiCodingTaskQuestionMapper.class)) {
            configuration.addMapper(mapper);
        }
        var optimisticLocks = new MybatisPlusInterceptor();
        optimisticLocks.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(optimisticLocks);
        var sql = new SqlSessionTemplate(factory.getObject());
        tasks = spy(sql.getMapper(AiCodingTaskMapper.class));
        events = spy(sql.getMapper(AiCodingTaskEventMapper.class));
        artifacts = sql.getMapper(AiCodingTaskArtifactMapper.class);
        provider = mock(AiCodingTaskKindProvider.class);
        when(provider.kind()).thenReturn("DELIVERY_TEST");
        when(provider.contract()).thenReturn(new TaskContract("DELIVERY_TEST", "DELIVERY_TEST", "READ_WRITE",
                "PROJECT", "delivery.test", "v1", json.readTree("{\"type\":\"object\"}"), json.createObjectNode()));
        when(provider.applyArtifact(any(), any())).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO delivery_probe VALUES (?, ?)", "task-1", "applied");
            return new ArtifactApplyResult(true, "Applied", ArtifactNextAction.STAY_APPLIED,
                    json.createObjectNode().put("recordId", "domain-1"));
        });
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(AiCodingTaskMapper.class, () -> tasks);
        context.registerBean(AiCodingTaskTargetMapper.class, () -> sql.getMapper(AiCodingTaskTargetMapper.class));
        context.registerBean(AiCodingTaskEventMapper.class, () -> events);
        context.registerBean(AiCodingTaskArtifactMapper.class, () -> artifacts);
        context.registerBean(AiCodingTaskQuestionMapper.class, () -> sql.getMapper(AiCodingTaskQuestionMapper.class));
        context.registerBean(AiCodingTaskProviderRegistry.class, () -> new AiCodingTaskProviderRegistry(List.of(provider)));
        context.registerBean(AiCodingHandoffApplicationService.class, () -> mock(AiCodingHandoffApplicationService.class));
        context.register(AiCodingTaskJsonSupport.class, AiCodingTaskStateChanges.class, AiCodingTaskDescriptorReader.class,
                AiCodingTaskDeliveryService.class, AiCodingArtifactProviderExecutor.class,
                AiCodingArtifactContractValidator.class, AiCodingContractResourceLoader.class, AiCodingSensitiveJsonSanitizer.class);
        context.refresh();
        delivery = context.getBean(AiCodingTaskDeliveryService.class);
        jdbc.update("""
                INSERT INTO control_ai_coding_task
                  (task_id,project_id,project_code,capability_key,task_kind,executor_provider,title,objective,
                   access_mode,execution_status,result_contract_key,result_contract_version,context_snapshot_json)
                VALUES ('task-1',7,'orders','DELIVERY_TEST','DELIVERY_TEST','CODEX','Delivery','Deliver result',
                        'READ_WRITE','RUNNING','delivery.test','v1','{}')
                """);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void appliedArtifactIsIdempotentAcrossCanonicalContentOrderAndRejectsChangedContent() throws Exception {
        var applied = delivery.submitArtifact("task-1", envelope("event-1", "result", "{\"a\":1,\"b\":2}"));
        assertEquals("RESULT_APPLIED", applied.task().getExecutionStatus());
        assertEquals("APPLIED", applied.artifact().getProcessingStatus());
        assertEquals(2L, tasks.selectById("task-1").getLockVersion());
        var replay = delivery.submitArtifact("task-1", envelope("event-retry", "result", "{\"b\":2,\"a\":1}"));
        assertEquals(applied.artifact().getArtifactId(), replay.artifact().getArtifactId());
        assertEquals(applied.applicationResult(), replay.applicationResult());
        assertThrows(IllegalStateException.class,
                () -> delivery.submitArtifact("task-1", envelope("event-conflict", "result", "{\"a\":3}")));
        verify(provider).applyArtifact(any(), any());
        assertEquals(1, probeCount());
        assertEquals(1L, artifacts.selectCount(null));
        assertEquals(List.of("RESULT_SUBMITTED", "RESULT_APPLIED"),
                jdbc.queryForList("SELECT event_type FROM control_ai_coding_task_event ORDER BY id", String.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOrThrowingProviderRollsBackOnlyItsDomainWrites(boolean throwsFailure) throws Exception {
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO delivery_probe VALUES (?, ?)", "task-1", "partial");
            if (throwsFailure) throw new IllegalStateException("domain rejected");
            return ArtifactApplyResult.rejected("domain rejected");
        }).when(provider).applyArtifact(any(), any());
        var rejected = delivery.submitArtifact("task-1", envelope("event-1", "result", "{}"));
        assertEquals("REJECTED", rejected.artifact().getProcessingStatus());
        assertEquals("domain rejected", rejected.artifact().getValidationMessage());
        assertEquals("RUNNING", tasks.selectById("task-1").getExecutionStatus());
        assertEquals(2L, tasks.selectById("task-1").getLockVersion());
        assertEquals(0, probeCount());
        assertEquals(1L, artifacts.selectCount(null));
        assertEquals(List.of("RESULT_SUBMITTED", "ARTIFACT_REJECTED"),
                jdbc.queryForList("SELECT event_type FROM control_ai_coding_task_event ORDER BY id", String.class));
        assertThrows(IllegalStateException.class,
                () -> delivery.submitArtifact("task-1", envelope("event-1", "different-result", "{}")));
        verify(provider).applyArtifact(any(), any());
    }

    @Test
    void failedAppliedAuditRollsBackArtifactDomainWritesAndTaskTransitionTogether() {
        doAnswer(invocation -> {
            AiCodingTaskEventEntity event = invocation.getArgument(0);
            if ("RESULT_APPLIED".equals(event.getEventType())) throw new IllegalStateException("audit-write-failed");
            return invocation.callRealMethod();
        }).when(events).insert(any(AiCodingTaskEventEntity.class));
        var failure = assertThrows(IllegalStateException.class,
                () -> delivery.submitArtifact("task-1", envelope("event-1", "result", "{}")));
        assertEquals("audit-write-failed", failure.getMessage());
        assertRolledBack(0L);
        verify(provider).applyArtifact(any(), any());
    }

    @Test
    void sharedStateWritesRequireACallerTransaction() {
        var stateChanges = context.getBean(AiCodingTaskStateChanges.class);
        var task = tasks.selectById("task-1");
        task.setLastMessage("outside transaction");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> stateChanges.requireUpdated(task));
        assertNull(tasks.selectById("task-1").getLastMessage());
        assertRolledBack(0L);
    }

    @Test
    void concurrentTaskChangeRejectsTheWholeSubmissionBeforeProviderApplication() throws Exception {
        var beforeStateWrite = new CountDownLatch(1);
        var allowStateWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforeStateWrite.countDown();
            assertTrue(allowStateWrite.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(tasks).updateById(any(AiCodingTaskEntity.class));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var submission = executor.submit(() -> delivery.submitArtifact("task-1", envelope("event-1", "result", "{}")));
            assertTrue(beforeStateWrite.await(5, TimeUnit.SECONDS));
            assertEquals(1, jdbc.update("UPDATE control_ai_coding_task SET lock_version = lock_version + 1 WHERE task_id = 'task-1'"));
            allowStateWrite.countDown();
            var failure = assertThrows(ExecutionException.class, () -> submission.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("concurrently modified"));
            assertRolledBack(1L);
            verify(provider, never()).applyArtifact(any(), any());
        } finally {
            allowStateWrite.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertRolledBack(Long expectedVersion) {
        var task = tasks.selectById("task-1");
        assertEquals("RUNNING", task.getExecutionStatus());
        assertEquals(expectedVersion, task.getLockVersion());
        assertEquals(0L, artifacts.selectCount(null));
        assertEquals(0L, events.selectCount(null));
        assertEquals(0, probeCount());
    }

    private int probeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_probe", Integer.class);
    }

    private ArtifactEnvelope envelope(String eventId, String key, String content) throws Exception {
        return new ArtifactEnvelope(AiCodingTaskValues.ARTIFACT_SCHEMA, eventId, key,
                new ArtifactContractRef("delivery.test", "v1"), json.readTree(content), null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }
}
