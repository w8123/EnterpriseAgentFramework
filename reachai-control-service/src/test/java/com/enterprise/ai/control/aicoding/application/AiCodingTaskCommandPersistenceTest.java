package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.*;
import com.enterprise.ai.control.aicoding.persistence.*;
import com.enterprise.ai.control.aicoding.provider.*;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiCodingTaskCommandPersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AiCodingTaskApplicationService application;
    private AiCodingTaskEventMapper events;
    private AiCodingHandoffApplicationService handoffs;
    private AiCodingTaskKindProvider provider;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:creation_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("control_ai_coding_task", "control_ai_coding_task_target",
                "control_ai_coding_task_event", "control_ai_coding_task_question", "control_ai_coding_task_artifact",
                "control_ai_coding_task_handoff")) {
            var ddl = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table
                    + "`\\s*\\(.*?\\)\\s*ENGINE=.*?;").matcher(baseline);
            assertTrue(ddl.find(), table);
            jdbc.execute(ddl.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
        }
        var config = new MybatisConfiguration();
        jdbc.execute("CREATE TABLE verification_probe (message VARCHAR(100))");
        config.setMapUnderscoreToCamelCase(true);
        for (var type : List.of(AiCodingTaskMapper.class, AiCodingTaskTargetMapper.class,
                AiCodingTaskEventMapper.class, AiCodingTaskQuestionMapper.class, AiCodingTaskArtifactMapper.class,
                AiCodingTaskHandoffMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var plugins = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());
        factory.setPlugins(plugins);
        var sql = new SqlSessionTemplate(factory.getObject());
        events = spy(sql.getMapper(AiCodingTaskEventMapper.class));
        handoffs = spy(new AiCodingHandoffApplicationService(sql.getMapper(AiCodingTaskMapper.class),
                sql.getMapper(AiCodingTaskHandoffMapper.class), events, new AiCodingTaskProperties(),
                mock(AiCodingCredentialPolicyService.class),
                mock(com.enterprise.ai.control.aicoding.security.AiCodingSecretDigester.class),
                mock(com.enterprise.ai.control.aicoding.security.AiCodingActivationAttemptService.class),
                mock(AiCodingHandoffPromptFactory.class), mock(AiCodingPowerShellBootstrapFactory.class)));
        var projects = mock(CapabilityProjectOnboardingClient.class);
        when(projects.getProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        provider = mock(AiCodingTaskKindProvider.class);
        when(provider.kind()).thenReturn("CREATE_TEST");
        when(provider.contract()).thenReturn(new TaskContract("CREATE_TEST", "CREATE_TEST", "READ_WRITE",
                "PROJECT", "create.test", "v1", json.createObjectNode(), json.createObjectNode()));
        when(provider.buildContext(any())).thenReturn(json.createObjectNode().put("password", "test-placeholder").put("purpose", "创建测试"));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(AiCodingTaskMapper.class, () -> sql.getMapper(AiCodingTaskMapper.class));
        context.registerBean(AiCodingTaskTargetMapper.class, () -> sql.getMapper(AiCodingTaskTargetMapper.class));
        context.registerBean(AiCodingTaskEventMapper.class, () -> events);
        context.registerBean(AiCodingTaskQuestionMapper.class, () -> sql.getMapper(AiCodingTaskQuestionMapper.class));
        context.registerBean(AiCodingTaskArtifactMapper.class, () -> sql.getMapper(AiCodingTaskArtifactMapper.class));
        context.registerBean(AiCodingHandoffApplicationService.class, () -> handoffs);
        context.registerBean(CapabilityProjectOnboardingClient.class, () -> projects);
        context.registerBean(AiCodingTaskProviderRegistry.class, () -> new AiCodingTaskProviderRegistry(List.of(provider)));
        context.register(AiCodingTaskDeliveryService.class, AiCodingArtifactProviderExecutor.class,
                AiCodingArtifactContractValidator.class);
        context.register(AiCodingTaskApplicationService.class, AiCodingTaskQueryService.class, AiCodingTaskCreationService.class, AiCodingTaskEventService.class, AiCodingTaskQuestionService.class,
                AiCodingTaskStateChanges.class, AiCodingTaskJsonSupport.class, AiCodingTaskDescriptorReader.class,
                AiCodingContractResourceLoader.class, AiCodingSensitiveJsonSanitizer.class);
        context.refresh();
        application = context.getBean(AiCodingTaskApplicationService.class);
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.close();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void createsTaskTargetsAndInitialEventWithSanitizedContext() {
        var result = application.create(command());
        assertEquals("READY", result.executionStatus());
        assertEquals("EXTERNAL_CLIENT", result.executionMode());
        assertEquals("创建测试", result.title());
        assertEquals(1, result.targets().size());
        assertEquals("orders", result.targets().get(0).targetKey());
        assertEquals("CREATED", jdbc.queryForObject("SELECT event_type FROM control_ai_coding_task_event", String.class));
        String snapshot = jdbc.queryForObject("SELECT context_snapshot_json FROM control_ai_coding_task", String.class);
        assertFalse(snapshot.contains("test-placeholder"));
        assertTrue(snapshot.contains("创建测试"));
    }

    @Test
    void initialEventFailureRollsBackTaskAndTargets() {
        doThrow(new IllegalStateException("event write failed")).when(events).insert(any(AiCodingTaskEventEntity.class));
        assertThrows(IllegalStateException.class, () -> application.create(command()));
        assertEmptyCreationTables();
    }

    @Test
    void responseProjectionFailureStillRollsBackAllInitialWrites() {
        doThrow(new IllegalStateException("connection projection failed")).when(handoffs).connection(any());
        assertThrows(IllegalStateException.class, () -> application.create(command()));
        assertEmptyCreationTables();
    }

    @Test
    void creationComponentRequiresTheFacadeTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> context.getBean(AiCodingTaskCreationService.class).create(command()));
        assertEmptyCreationTables();
    }

    @Test
    void paddedIdentityReadsTheSameEvents() {
        String id = seedQueryEvidence();
        assertEquals(application.events(id, null), application.events(" " + id + " ", null));
    }

    @Test
    void paddedIdentityReadsTheSameQuestions() {
        String id = seedQueryEvidence();
        assertEquals(application.questions(id, null), application.questions(" " + id + " ", null));
    }

    @Test
    void paddedIdentityReadsTheSameArtifacts() {
        String id = seedQueryEvidence();
        assertEquals(application.artifacts(id), application.artifacts(" " + id + " "));
    }

    @Test
    void paddedIdentityDetailKeepsItsActivityEvidence() {
        String id = seedQueryEvidence();
        var expected = application.detail(id);
        var actual = application.detail(" " + id + " ");
        assertAll(() -> assertEquals(expected.events(), actual.events()),
                () -> assertEquals(expected.questions(), actual.questions()),
                () -> assertEquals(expected.artifacts(), actual.artifacts()));
    }

    @Test
    void contextEndpointsUseTheResolvedTaskIdentity() {
        String id = seedQueryEvidence();
        var view = application.context(" " + id + " ", "https://example.invalid/reachai/");
        assertEquals("https://example.invalid/reachai/api/ai-coding/tasks/" + id + "/context", view.endpoints().contextUrl());
    }

    @Test
    void paddedIdentityContextKeepsQuestions() {
        String id = seedQueryEvidence();
        assertEquals(application.questions(id, null), application.context(" " + id + " ", "https://example.invalid").questions());
    }

    @Test
    void activityCursorsRemainExclusive() {
        String id = seedQueryEvidence();
        var events = application.events(id, null);
        assertTrue(events.size() >= 2);
        assertEquals(events.subList(1, events.size()), application.events(id, events.get(0).id()));
        var question = application.questions(id, null).get(0);
        assertTrue(application.questions(id, question.updatedAt()).isEmpty());
    }

    @Test
    void taskQueryEvidenceDoesNotIncludeAnotherTask() {
        String id = seedQueryEvidence();
        String other = application.create(command()).taskId();
        assertFalse(application.artifacts(id).isEmpty());
        assertFalse(application.questions(id, null).isEmpty());
        assertTrue(application.artifacts(other).isEmpty());
        assertTrue(application.questions(other, null).isEmpty());
        assertEquals(1, application.events(other, null).size());
    }

    private String seedQueryEvidence() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("query-start", "STARTED", null));
        application.askQuestion(id, question());
        jdbc.update("INSERT INTO control_ai_coding_task_artifact "
                + "(task_id, artifact_key, contract_key, contract_version, content_hash, content_json, processing_status, application_result_json) "
                + "VALUES (?, 'query-evidence', 'create.test', 'v1', ?, '{}', 'APPLIED', '{\"result\":\"query evidence\"}')", id, "a".repeat(64));
        when(provider.materializeContext(any(), any(), anyString())).thenReturn(json.createObjectNode());
        return id;
    }

    private CreateTaskCommand command() {
        return new CreateTaskCommand(7L, "orders", "CREATE_TEST", "CODEX", "创建测试", "创建任务并校验回滚", "tester",
                List.of(new TaskTargetCommand("PROJECT", "orders", "PRIMARY", "READ_WRITE", json.createObjectNode())));
    }

    @Test
    void identicalEventReplayDoesNotAdvanceRevisionOrAppendAnotherEvent() {
        String id = application.create(command()).taskId();
        var payload = json.createObjectNode().put("a", 1).put("b", 2);
        application.recordEvent(id, event("start", "STARTED", payload));
        Long revision = jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class);
        var replay = application.recordEvent(id, event("start", "STARTED", json.createObjectNode().put("b", 2).put("a", 1)));
        assertEquals("RUNNING", replay.executionStatus());
        assertEquals(revision, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
        assertThrows(IllegalStateException.class,
                () -> application.recordEvent(id, event("start", "STARTED", json.createObjectNode().put("a", 3))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void progressEventFailureRollsBackMessageAndRevision() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        Long revision = jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class);
        doThrow(new IllegalStateException("event unavailable")).when(events).insert(any(AiCodingTaskEventEntity.class));
        assertThrows(IllegalStateException.class, () -> application.recordEvent(id, event("progress", "PROGRESS", null)));
        assertEquals("STARTED", jdbc.queryForObject("SELECT last_message FROM control_ai_coding_task", String.class));
        assertEquals(revision, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
    }

    @Test
    void failedHandoffClosureRollsBackTerminalTransitionAndEvent() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        doThrow(new IllegalStateException("handoff unavailable")).when(handoffs).closeTaskHandoffs(id, "TASK_FAILED");
        assertThrows(IllegalStateException.class, () -> application.recordEvent(id, event("failed", "FAILED", null)));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM control_ai_coding_task", java.sql.Timestamp.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void eventResponseFailureRollsBackTransitionAndEvent() {
        String id = application.create(command()).taskId();
        doThrow(new IllegalStateException("view unavailable")).when(handoffs).connection(any());
        assertThrows(IllegalStateException.class, () -> application.recordEvent(id, event("start", "STARTED", null)));
        assertEquals("READY", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void protocolComponentRejectsCallsOutsideTheFacadeTransaction() {
        String id = application.create(command()).taskId();
        assertThrows(IllegalTransactionStateException.class,
                () -> context.getBean(AiCodingTaskEventService.class).recordEvent(id, event("start", "STARTED", null)));
        assertEquals("READY", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
    }

    private EventCommand event(String clientId, String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new EventCommand("reachai.ai-coding.event.v1", clientId, type, type, payload, "CODEX");
    }

    @Test
    void answeringAndReplayRemainWaitingUntilExplicitResume() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        application.askQuestion(id, question());
        application.askQuestion(id, question());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
        assertThrows(IllegalStateException.class, () -> application.recordEvent(id, event("resume", "RESUMED", null)));
        application.answerQuestion(id, "question-1", new AnswerQuestionCommand("继续", "first-user"));
        var replay = application.answerQuestion(id, "question-1", new AnswerQuestionCommand("继续", "second-user"));
        assertEquals("first-user", replay.answeredBy());
        assertEquals("WAITING_USER", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
        assertThrows(IllegalStateException.class,
                () -> application.answerQuestion(id, "question-1", new AnswerQuestionCommand("不同回答", "first-user")));
        assertEquals("RUNNING", application.recordEvent(id, event("resume", "RESUMED", null)).executionStatus());
    }

    @Test
    void questionEventFailureRollsBackQuestionAndWaitingState() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        doThrow(new IllegalStateException("question event unavailable")).when(events).insert(any(AiCodingTaskEventEntity.class));
        assertThrows(IllegalStateException.class, () -> application.askQuestion(id, question()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_question", Integer.class));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void answerEventFailureRollsBackTheConditionalQuestionUpdate() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        application.askQuestion(id, question());
        doThrow(new IllegalStateException("answer event unavailable")).when(events).insert(any(AiCodingTaskEventEntity.class));
        assertThrows(IllegalStateException.class,
                () -> application.answerQuestion(id, "question-1", new AnswerQuestionCommand("继续", "user")));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM control_ai_coding_task_question", String.class));
        assertNull(jdbc.queryForObject("SELECT answer FROM control_ai_coding_task_question", String.class));
        assertEquals("WAITING_USER", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void questionCommandsRequireTheOuterTransaction() {
        String id = application.create(command()).taskId();
        var questions = context.getBean(AiCodingTaskQuestionService.class);
        assertThrows(IllegalTransactionStateException.class, () -> questions.askQuestion(id, question()));
        assertThrows(IllegalTransactionStateException.class,
                () -> questions.answerQuestion(id, "question-1", new AnswerQuestionCommand("继续", "user")));
    }

    private AskQuestionCommand question() {
        return new AskQuestionCommand("reachai.ai-coding.question.v1", "ask", "question-1", "确认", "是否继续？", List.of("继续"), "CODEX");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationUsesResolvedIdentityAndClosesOnlyActiveHandoffs(boolean paddedId) {
        String id = application.create(command()).taskId();
        seedHandoffs(id);
        var result = application.cancel(paddedId ? "  " + id + "  " : id, " tester ");
        assertEquals("CANCELLED", result.executionStatus());
        assertEquals(List.of("REVOKED", "REVOKED", "EXPIRED", "ISSUED"),
                jdbc.queryForList("SELECT activation_status FROM control_ai_coding_task_handoff ORDER BY handoff_id", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_handoff WHERE close_reason='TASK_CANCELLED'", Integer.class));
        assertEquals("tester", jdbc.queryForObject("SELECT actor_name FROM control_ai_coding_task_event WHERE event_type='CANCELLED'", String.class));
    }

    @Test
    void cancellationFailureRollsBackTaskQuestionEventAndActualHandoffWrite() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        application.askQuestion(id, question());
        seedHandoffs(id);
        Long revision = jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("handoff write failed after SQL"); })
                .when(handoffs).closeTaskHandoffs(id, "TASK_CANCELLED");
        assertThrows(IllegalStateException.class, () -> application.cancel(id, "tester"));
        assertEquals("WAITING_USER", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM control_ai_coding_task_question", String.class));
        assertEquals(revision, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='CANCELLED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_handoff WHERE closed_at IS NOT NULL", Integer.class));
    }

    @Test
    void repeatedCancellationDoesNotAddEventsOrAdvanceRevision() {
        String id = application.create(command()).taskId();
        seedHandoffs(id);
        application.cancel(id, "tester");
        Long revision = jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class);
        application.cancel(id, "second user");
        assertEquals(revision, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='CANCELLED'", Integer.class));
        verify(handoffs).closeTaskHandoffs(id, "TASK_CANCELLED");
    }

    @Test
    void verificationRecordsSanitizedProviderResultAndCanonicalKey() {
        String id = startTask();
        when(provider.requestVerification(any(), org.mockito.ArgumentMatchers.eq("SDK_SYNC")))
                .thenReturn(json.createObjectNode().put("password", "verification-secret-fixture").put("说明", "验证成功"));
        var result = application.requestVerification(id, " SDK_SYNC ");
        assertEquals("SDK_SYNC", result.verificationKey());
        assertEquals("RUNNING", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        String payload = jdbc.queryForObject("SELECT payload_json FROM control_ai_coding_task_event WHERE event_type='VERIFICATION_COMPLETED'", String.class);
        assertFalse(payload.contains("verification-secret-fixture"));
        assertTrue(payload.contains("验证成功"));
        assertFalse(json.valueToTree(result).toString().contains("verification-secret-fixture"));
    }

    @Test
    void verificationEventFailureRollsBackProviderWritesAndRevision() {
        String id = startTask();
        Long revision = jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class);
        when(provider.requestVerification(any(), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO verification_probe VALUES (?)", "已验证");
            return json.createObjectNode().put("ok", true);
        });
        doThrow(new IllegalStateException("event write failed")).when(events).insert(any(AiCodingTaskEventEntity.class));
        assertThrows(IllegalStateException.class, () -> application.requestVerification(id, "SDK_SYNC"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM verification_probe", Integer.class));
        assertEquals(revision, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    @Test
    void concurrentCancellationIsNotOverwrittenByVerification() {
        String id = startTask();
        when(provider.requestVerification(any(), any())).thenAnswer(call -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> application.cancel(id, "another connection"))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            jdbc.update("INSERT INTO verification_probe VALUES (?)", "旧请求的验证");
            return json.createObjectNode().put("ok", true);
        });
        assertThrows(IllegalStateException.class, () -> application.requestVerification(id, "SDK_SYNC"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM verification_probe", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='VERIFICATION_COMPLETED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='CANCELLED'", Integer.class));
    }

    @Test
    void verificationRejectsNonRunningTaskBeforeCallingProvider() {
        String id = application.create(command()).taskId();
        assertThrows(IllegalStateException.class, () -> application.requestVerification(id, "SDK_SYNC"));
        verify(provider, never()).requestVerification(any(), any());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event", Integer.class));
    }

    private String startTask() {
        String id = application.create(command()).taskId();
        application.recordEvent(id, event("start", "STARTED", null));
        return id;
    }

    private void seedHandoffs(String id) {
        String[] states = {"ISSUED", "ACTIVATED", "EXPIRED", "ISSUED"};
        for (int index = 0; index < states.length; index++) {
            jdbc.update("INSERT INTO control_ai_coding_task_handoff (handoff_id,task_id,activation_code_hash,activation_status,activation_expires_at) VALUES (?,?,?,?,DATEADD('DAY',1,CURRENT_TIMESTAMP))",
                    "handoff-" + index, index == 3 ? "other-task" : id, "fixture-hash", states[index]);
        }
    }

    private void assertEmptyCreationTables() {
        for (String table : List.of("control_ai_coding_task", "control_ai_coding_task_target", "control_ai_coding_task_event"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }
}
