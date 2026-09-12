package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractRef;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.persistence.*;
import com.enterprise.ai.control.aicoding.provider.*;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.config.pageworkbench.PageWorkbenchClientAdapter;
import com.enterprise.ai.control.config.pageworkbench.PageWorkbenchClientConfiguration;
import com.enterprise.ai.control.pageworkbench.application.*;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateTaskProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.Client;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in HTTP recovery proof: Control owns its transaction; a separate Runtime JVM owns its writes. */
@EnabledIfEnvironmentVariable(named = "REACHAI_WORKFLOW_DRAFT_RUNTIME_CLASSPATH", matches = ".+")
class AiCodingWorkflowDraftRecoveryIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final boolean MYSQL = Boolean.getBoolean("reachai.mysql.workflowRecoveryVerification");
    private static Process runtime;
    private static String runtimeUrl;
    private static Path evidence;

    @BeforeAll
    static void startOwnedRuntimeProcess() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        evidence = root.resolve("output/tasks/architecture-audit-20260905/workflow-draft-recovery-" + UUID.randomUUID());
        Files.createDirectories(evidence);
        String classpath = Files.readString(Path.of(System.getenv("REACHAI_WORKFLOW_DRAFT_RUNTIME_CLASSPATH"))).trim();
        assertFalse(classpath.isBlank());
        Path ready = evidence.resolve("ready.json");
        runtime = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dfile.encoding=UTF-8", "-Dreachai.mysql.workflowRecoveryVerification=" + MYSQL, "-cp", classpath,
                "com.enterprise.ai.runtime.workflow.RuntimeWorkflowDraftRecoveryTestHost", ready.toString())
                .directory(root.resolve("reachai-runtime-service").toFile())
                .redirectErrorStream(true).redirectOutput(evidence.resolve("runtime.log").toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!Files.exists(ready) && runtime.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(ready), "Runtime did not start; see " + evidence.resolve("runtime.log"));
        JsonNode metadata = JSON.readTree(Files.readString(ready));
        assertEquals(runtime.pid(), metadata.path("pid").asLong());
        assertEquals(MYSQL ? "MYSQL" : "H2", metadata.path("database").asText());
        runtimeUrl = metadata.path("url").asText();
        assertEquals("127.0.0.1", URI.create(runtimeUrl).getHost());
        assertNotEquals(ProcessHandle.current().pid(), runtime.pid());
        System.out.println("WORKFLOW_DRAFT_RUNTIME_STARTED pid=" + runtime.pid() + " independentJvm=true database=" + (MYSQL ? "MYSQL" : "H2"));
    }

    @AfterAll
    static void stopOnlyOwnedRuntimeProcess() throws Exception {
        if (runtime == null) return;
        try { runtime.getOutputStream().close(); }
        finally {
            if (!runtime.waitFor(20, TimeUnit.SECONDS)) {
                runtime.destroyForcibly();
                assertTrue(runtime.waitFor(5, TimeUnit.SECONDS));
            }
            boolean stopped = !runtime.isAlive();
            Files.writeString(evidence.resolve("cleanup.json"), JSON.writeValueAsString(Map.of(
                    "pid", runtime.pid(), "stopped", stopped, "exitCode", stopped ? runtime.exitValue() : -1)), StandardCharsets.UTF_8);
            assertTrue(stopped);
            System.out.println("WORKFLOW_DRAFT_RUNTIME_CLEANUP stopped=" + stopped);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "MISSING_ID", "WRONG_TASK"})
    void unconfirmedReceiptRollsBackControlAndSameArtifactRecoversCommittedDraft(String mode) throws Exception {
        withControl(fixture -> {
            probe("POST", "/probe/fault/" + mode, "{}");
            RuntimeException failure = null;
            try { fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()); }
            catch (RuntimeException thrown) { failure = thrown; }
            JsonNode committed = assertSingleRuntimeDraft(fixture.taskId);
            System.out.println("WORKFLOW_DRAFT_RECEIPT_PROBE mode=" + mode + " runtimeReceipts=1 controlArtifacts="
                    + fixture.count("control_ai_coding_task_artifact") + " taskStatus=" + fixture.status()
                    + " propagatedFailure=" + (failure != null));
            assertNotNull(failure, "A missing or mismatched receipt must not finalize an Artifact");
            fixture.assertRolledBack();
            var recovered = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
            assertEquals("APPLIED", recovered.artifact().getProcessingStatus());
            assertEquals(committed.path("workflows").get(0).path("id").asText(),
                    recovered.applicationResult().path("workflowEngineering").path("workflow").path("id").asText());
            assertEquals(committed, assertSingleRuntimeDraft(fixture.taskId), "Retry must not rewrite the Runtime draft");
            fixture.assertAppliedOnce();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUNCATE", "MALFORMED"})
    void brokenHttpBodyAfterCommitCanBeRetriedWithoutAnotherWorkflow(String mode) throws Exception {
        withControl(fixture -> {
            probe("POST", "/probe/fault/" + mode, "{}");
            assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            JsonNode committed = assertSingleRuntimeDraft(fixture.taskId);
            fixture.assertRolledBack();
            assertEquals("APPLIED", fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()).artifact().getProcessingStatus());
            assertEquals(committed, assertSingleRuntimeDraft(fixture.taskId));
            fixture.assertAppliedOnce();
        });
    }

    @Test
    void failureBeforeRuntimeWriteLeavesBothServicesUnchangedAndRetrySucceeds() throws Exception {
        withControl(fixture -> {
            probe("POST", "/probe/fault/BEFORE", "{}");
            assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            JsonNode unchanged = probe("GET", "/probe/state/" + fixture.taskId, null);
            assertEquals(0, unchanged.path("receipts").asInt());
            assertTrue(unchanged.path("workflows").isEmpty());
            fixture.assertRolledBack();
            assertEquals("APPLIED", fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()).artifact().getProcessingStatus());
            assertSingleRuntimeDraft(fixture.taskId);
            fixture.assertAppliedOnce();
        });
    }

    @Test
    void recoveringLostReceiptPreservesLaterHumanEdit() throws Exception {
        withControl(fixture -> {
            probe("POST", "/probe/fault/EMPTY", "{}");
            var missingReceipt = assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            recordExpectedFailure("human-edit-initial", missingReceipt);
            fixture.assertRolledBack();
            String id = assertSingleRuntimeDraft(fixture.taskId).path("workflows").get(0).path("id").asText();
            String humanName = "人工调整后的订单助手";
            probe("POST", "/probe/edit/" + id, JSON.writeValueAsString(Map.of("name", humanName)));
            JsonNode edited = assertSingleRuntimeDraft(fixture.taskId);
            var recovered = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
            assertEquals(humanName, recovered.applicationResult().path("workflowEngineering").path("workflow").path("name").asText());
            assertEquals(edited, assertSingleRuntimeDraft(fixture.taskId));
            fixture.assertAppliedOnce();
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "reachai.mysql.workflowRecoveryVerification", matches = "true")
    void controlConnectionLossAfterRuntimeCommitRollsBackAndRetryPreservesHumanEdit() {
        withControl(fixture -> {
            var aborted = new AtomicBoolean();
            var artifacts = fixture.context.getBean(AiCodingTaskArtifactMapper.class);
            doAnswer(call -> {
                Object updated = call.callRealMethod();
                if (aborted.compareAndSet(false, true)) {
                    assertEquals("APPLIED", ((AiCodingTaskArtifactEntity) call.getArgument(0)).getProcessingStatus());
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertSingleRuntimeDraft(fixture.taskId);
                    // Abort only this fixture's active connection after actual SQL and remote commit evidence.
                    DataSourceUtils.getConnection(fixture.jdbc.getDataSource()).abort(Runnable::run);
                    System.out.println("MYSQL_CONTROL_CONNECTION_ABORTED afterRuntimeCommit=true afterArtifactWrite=true");
                }
                return updated;
            }).when(artifacts).updateById(any(AiCodingTaskArtifactEntity.class));

            var lostConnection = assertThrows(RuntimeException.class,
                    () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            recordExpectedFailure("control-connection-abort", lostConnection);
            assertTrue(aborted.get());
            fixture.assertRolledBack();
            String id = assertSingleRuntimeDraft(fixture.taskId).path("workflows").get(0).path("id").asText();
            String humanName = "连接恢复前人工保留的订单助手";
            probe("POST", "/probe/edit/" + id, JSON.writeValueAsString(Map.of("name", humanName)));
            JsonNode edited = assertSingleRuntimeDraft(fixture.taskId);
            var recovered = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
            assertEquals("APPLIED", recovered.artifact().getProcessingStatus());
            assertEquals(humanName, recovered.applicationResult().path("workflowEngineering").path("workflow").path("name").asText());
            assertEquals(edited, assertSingleRuntimeDraft(fixture.taskId));
            fixture.assertAppliedOnce();
            System.out.println("MYSQL_CONTROL_CONNECTION_RECOVERED sameArtifact=true humanEditPreserved=true utf8=true");
        });
    }

    @Test
    void successfulDuplicateIsAnsweredByControlWithoutCallingRuntimeAgain() throws Exception {
        withControl(fixture -> {
            var first = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
            assertEquals("APPLIED", first.artifact().getProcessingStatus());
            JsonNode committed = assertSingleRuntimeDraft(fixture.taskId);
            probe("POST", "/probe/fault/BEFORE", "{}");
            try {
                var duplicate = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
                assertEquals(first.artifact().getArtifactId(), duplicate.artifact().getArtifactId());
                assertEquals(first.applicationResult(), duplicate.applicationResult());
                assertEquals(committed, assertSingleRuntimeDraft(fixture.taskId));
                fixture.assertAppliedOnce();
            } finally {
                // Consume the still-armed pre-write fault so it cannot affect a following case.
                HttpResponse<String> consumed = HTTP.send(HttpRequest.newBuilder(URI.create(runtimeUrl
                        + "/internal/runtime/page-workbench/projects/orders/workflow-drafts"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, consumed.statusCode(), "Control replay must leave the Runtime fault untouched");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE_CREATE", "TRACE_VALIDATION", "TRACE_READBACK", "TRACE_READBACK_ID"})
    void traceCandidateRecoversAfterEachPostCreationResponseFailure(String mode) {
        withControl(true, fixture -> {
            probe("POST", "/probe/fault/" + mode, "{}");
            assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            JsonNode committed = assertSingleRuntimeDraft(fixture.taskId, 0);
            fixture.assertRolledBack();
            assertRecoveredTrace(fixture, committed);
            System.out.println("TRACE_DRAFT_RECOVERED mode=" + mode + " realRunOpsQueries=true realValidation=true");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE_DETAIL", "TRACE_VERSIONS", "TRACE_PREFLIGHT"})
    void dependencyFailureDuringRetryDoesNotPermanentlyRejectAnAlreadySavedCandidate(String mode) {
        withControl(true, fixture -> {
            probe("POST", "/probe/fault/TRACE_CREATE", "{}");
            var missingReceipt = assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            recordExpectedFailure(mode + "-initial", missingReceipt);
            JsonNode committed = assertSingleRuntimeDraft(fixture.taskId, 0);
            fixture.assertRolledBack();
            probe("POST", "/probe/fault/" + mode, "{}");
            RuntimeException failure = null;
            try { fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()); }
            catch (RuntimeException thrown) { failure = thrown; }
            assertEquals(committed, assertSingleRuntimeDraft(fixture.taskId, 0));
            System.out.println("TRACE_DRAFT_RETRY_PROBE mode=" + mode + " runtimeReceipts=1 controlArtifacts="
                    + fixture.count("control_ai_coding_task_artifact") + " propagatedFailure=" + (failure != null));
            assertNotNull(failure, "Dependency uncertainty must not finalize a candidate Artifact");
            fixture.assertRolledBack();
            assertRecoveredTrace(fixture, committed);
        });
    }

    @Test
    void traceRecoveryPreservesHumanChangesAndStillRequiresARealReplay() {
        withControl(true, fixture -> {
            probe("POST", "/probe/fault/TRACE_CREATE", "{}");
            assertThrows(RuntimeException.class, () -> fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope()));
            String id = assertSingleRuntimeDraft(fixture.taskId, 0).path("workflows").get(0).path("id").asText();
            String humanName = "人工审阅后的只读候选";
            probe("POST", "/probe/edit/" + id, JSON.writeValueAsString(Map.of("name", humanName)));
            JsonNode edited = assertSingleRuntimeDraft(fixture.taskId, 0);
            var recovered = assertRecoveredTrace(fixture, edited);
            assertEquals(humanName, recovered.applicationResult().path("workflowCandidate").path("name").asText());
            System.out.println("TRACE_DRAFT_HUMAN_EDIT_PRESERVED utf8=true acceptanceReady=false");
        });
    }

    @Test
    void realPreflightGraphRejectionPersistsAsRejectionWithoutCreatingACandidate() {
        withControl(true, fixture -> {
            var original = fixture.envelope();
            ((ObjectNode) original.content().path("workflow").path("graphSpec")).put("entryNodeId", "absent");
            var rejected = fixture.delivery.submitArtifact(fixture.taskId, original);
            assertEquals("REJECTED", rejected.artifact().getProcessingStatus());
            assertFalse(rejected.applicationResult().path("preflightValidation").path("valid").asBoolean());
            assertEquals(0, probe("GET", "/probe/state/" + fixture.taskId, null).path("receipts").asInt());
            assertEquals("RUNNING", fixture.status());
            assertEquals(1, fixture.count("control_ai_coding_task_artifact"));
            System.out.println("TRACE_DRAFT_REAL_VALIDATION_REJECTED runtimeWrites=0");
        });
    }

    private static AiCodingTaskDeliveryService.ArtifactOutcome assertRecoveredTrace(ControlFixture fixture, JsonNode committed) throws Exception {
        var recovered = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
        assertEquals("APPLIED", recovered.artifact().getProcessingStatus());
        assertEquals(committed.path("workflows").get(0).path("id").asText(),
                recovered.applicationResult().path("workflowCandidate").path("workflowId").asText());
        assertEquals(committed, assertSingleRuntimeDraft(fixture.taskId, 0));
        assertEquals("RESULT_APPLIED", fixture.status(), "A saved candidate still needs replay acceptance");
        var duplicate = fixture.delivery.submitArtifact(fixture.taskId, fixture.envelope());
        assertEquals(recovered.artifact().getArtifactId(), duplicate.artifact().getArtifactId());
        // Persisted JSON parses a small LongNode as IntNode; compare the actual JSON protocol value.
        assertEquals(JSON.readTree(recovered.applicationResult().toString()), duplicate.applicationResult());
        fixture.assertAppliedOnce();
        return recovered;
    }

    private static JsonNode assertSingleRuntimeDraft(String taskId) throws Exception {
        return assertSingleRuntimeDraft(taskId, 1);
    }

    private static JsonNode assertSingleRuntimeDraft(String taskId, int bindings) throws Exception {
        JsonNode state = probe("GET", "/probe/state/" + taskId, null);
        assertEquals(1, state.path("receipts").asInt());
        assertEquals(1, state.path("workflows").size());
        assertEquals(bindings, state.path("bindings").asInt());
        assertTrue(state.path("references").asInt() > 0, "The real reference index must be maintained");
        assertFalse(state.path("workflows").get(0).path("id").asText().isBlank());
        assertFalse(state.path("workflows").get(0).path("graph_spec_json").asText().contains("????"));
        return state;
    }

    private static JsonNode probe(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(runtimeUrl + path)).timeout(Duration.ofSeconds(5));
        if (method.equals("POST")) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else request.GET();
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "Runtime probe " + path);
        return JSON.readTree(response.body());
    }

    private static void recordExpectedFailure(String phase, RuntimeException failure) {
        var types = new java.util.ArrayList<String>();
        for (Throwable cause = failure; cause != null && types.size() < 8; cause = cause.getCause()) {
            String detail = cause.getClass().getSimpleName();
            if (cause instanceof feign.FeignException response) detail += "(http=" + response.status() + ")";
            if (cause instanceof java.sql.SQLException database) detail += "(state=" + database.getSQLState() + ",code=" + database.getErrorCode() + ")";
            types.add(detail);
        }
        System.out.println("WORKFLOW_DRAFT_EXPECTED_FAILURE phase=" + phase + " causes=" + types);
    }

    private void withControl(Scenario scenario) {
        withControl(false, scenario);
    }

    private void withControl(boolean traceCandidate, Scenario scenario) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withUserConfiguration(PageWorkbenchClientConfiguration.class, PageWorkbenchClientAdapter.class, TraceClientConfiguration.class)
                .withPropertyValues("services.runtime-service.url=" + runtimeUrl,
                        "spring.cloud.openfeign.client.config.default.connectTimeout=3000",
                        "spring.cloud.openfeign.client.config.default.readTimeout=5000")
                .withBean(Client.class, () -> new Client.Default(null, null))
                .withBean(CapabilityProjectOnboardingClient.class, () -> mock(CapabilityProjectOnboardingClient.class))
                .withBean(ControlModelCatalogClient.class, () -> mock(ControlModelCatalogClient.class))
                .run(clientContext -> {
                    assertNull(clientContext.getStartupFailure());
                    try (var fixture = traceCandidate ? new ControlFixture(clientContext.getBean(RuntimeProxyClient.class))
                            : new ControlFixture(clientContext.getBean(PageWorkbenchRuntimePort.class))) {
                        scenario.run(fixture);
                    }
                });
    }

    @FunctionalInterface
    private interface Scenario { void run(ControlFixture fixture) throws Exception; }

    private static final class ControlFixture implements AutoCloseable {
        private final String taskId = "retry-" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate jdbc;
        private final ClonedDevelopmentMysqlDatabase mysql;
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final AiCodingTaskKindProvider provider;
        private final AiCodingTaskDeliveryService delivery;
        private final String targetType;
        private final String resource;

        ControlFixture(PageWorkbenchRuntimePort runtimePort) throws Exception {
            this(pageProvider(runtimePort), "PAGE", "orders.detail", "workflow-engineering-report-v1.example.json");
        }

        ControlFixture(RuntimeProxyClient runtimeClient) throws Exception {
            this(new TraceWorkflowCandidateTaskProvider(new TraceWorkflowCandidateEligibility(runtimeClient, JSON),
                    runtimeClient, JSON, new AiCodingContractResourceLoader(JSON)), "RUNTIME_RUN", "trace-1",
                    "trace-workflow-candidate-report-v1.example.json");
        }

        private ControlFixture(AiCodingTaskKindProvider provider, String targetType, String targetKey, String resource) throws Exception {
            this.provider = provider; this.targetType = targetType; this.resource = resource;
            var tables = List.of("control_ai_coding_task", "control_ai_coding_task_target",
                    "control_ai_coding_task_event", "control_ai_coding_task_artifact", "control_ai_coding_task_question");
            mysql = MYSQL ? new ClonedDevelopmentMysqlDatabase("reachai.mysql.workflowRecoveryVerification",
                    "audit_draft_http_control", "control_", tables) : null;
            DataSource source = mysql == null ? new DriverManagerDataSource("jdbc:h2:mem:draft_recovery_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "") : mysql;
            jdbc = new JdbcTemplate(source);
            try {
                if (mysql == null) {
                    String baseline = Files.readString(Path.of("../sql/initV2.sql"));
                    for (String table : tables) {
                        var definition = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table
                                + "`\\s*\\(.*?\\)\\s*ENGINE=.*?;").matcher(baseline);
                        assertTrue(definition.find(), table);
                        jdbc.execute(definition.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                                .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
                    }
                }
                var configuration = new MybatisConfiguration();
                configuration.setMapUnderscoreToCamelCase(true);
                for (Class<?> mapper : List.of(AiCodingTaskMapper.class, AiCodingTaskTargetMapper.class,
                        AiCodingTaskEventMapper.class, AiCodingTaskArtifactMapper.class, AiCodingTaskQuestionMapper.class)) {
                    configuration.addMapper(mapper);
                }
                var optimisticLocks = new MybatisPlusInterceptor();
                optimisticLocks.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
                var factory = new MybatisSqlSessionFactoryBean();
                factory.setDataSource(source); factory.setConfiguration(configuration); factory.setPlugins(optimisticLocks);
                var sql = new SqlSessionTemplate(factory.getObject());
                context.register(TransactionConfiguration.class);
                context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
                context.registerBean(ObjectMapper.class, () -> JSON);
                context.registerBean(AiCodingTaskMapper.class, () -> sql.getMapper(AiCodingTaskMapper.class));
                context.registerBean(AiCodingTaskTargetMapper.class, () -> sql.getMapper(AiCodingTaskTargetMapper.class));
                context.registerBean(AiCodingTaskEventMapper.class, () -> sql.getMapper(AiCodingTaskEventMapper.class));
                context.registerBean(AiCodingTaskArtifactMapper.class, () -> spy(sql.getMapper(AiCodingTaskArtifactMapper.class)));
                context.registerBean(AiCodingTaskQuestionMapper.class, () -> sql.getMapper(AiCodingTaskQuestionMapper.class));
                context.registerBean(AiCodingTaskProviderRegistry.class, () -> new AiCodingTaskProviderRegistry(List.of(provider)));
                context.registerBean(AiCodingHandoffApplicationService.class, () -> mock(AiCodingHandoffApplicationService.class));
                context.register(AiCodingTaskJsonSupport.class, AiCodingTaskStateChanges.class, AiCodingTaskDescriptorReader.class,
                        AiCodingTaskDeliveryService.class, AiCodingArtifactProviderExecutor.class, AiCodingArtifactContractValidator.class,
                        AiCodingContractResourceLoader.class, AiCodingSensitiveJsonSanitizer.class);
                context.refresh();
                delivery = context.getBean(AiCodingTaskDeliveryService.class);
                var contract = provider.contract();
                jdbc.update("""
                        INSERT INTO control_ai_coding_task
                          (task_id,project_id,project_code,capability_key,task_kind,executor_provider,title,objective,
                           access_mode,execution_status,result_contract_key,result_contract_version,context_snapshot_json)
                        VALUES (?,7,'orders',?,?,'CODEX','Recovery','Recover submission',?,'RUNNING',?,?,'{}')
                        """, taskId, contract.capabilityKey(), provider.kind(), contract.accessMode(), contract.resultContractKey(), contract.resultContractVersion());
                jdbc.update("""
                        INSERT INTO control_ai_coding_task_target (task_id,target_type,target_key,target_role,access_mode,snapshot_json)
                        VALUES (?,?,?,'PRIMARY',?,'{}')
                        """, taskId, targetType, targetKey, contract.accessMode());
            } catch (Exception | Error failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        ArtifactEnvelope envelope() throws Exception {
            try (var content = AiCodingWorkflowDraftRecoveryIT.class.getResourceAsStream(
                    "/ai-coding/contracts/" + resource)) {
                assertNotNull(content);
                JsonNode payload = JSON.readTree(content);
                if (targetType.equals("RUNTIME_RUN")) {
                    ((ObjectNode) payload).put("sourceTraceId", "trace-1");
                    ((ObjectNode) payload.path("workflow")).set("graphSpec", JSON.readTree("""
                            {"schemaVersion":2,"nodes":[{"id":"read","type":"TOOL","ref":{"qualifiedName":"orders.read"}}],
                             "edges":[],"entryNodeId":"read","exitNodeIds":["read"]}
                            """));
                }
                return new ArtifactEnvelope(AiCodingTaskValues.ARTIFACT_SCHEMA, "draft-submission", "workflow-draft",
                        new ArtifactContractRef(provider.contract().resultContractKey(), provider.contract().resultContractVersion()),
                        payload, null);
            }
        }

        private static AiCodingTaskKindProvider pageProvider(PageWorkbenchRuntimePort runtimePort) {
            var catalog = mock(PageCatalogApplicationService.class);
            when(catalog.findAction("orders", "orders.detail", "getPageState")).thenReturn(Optional.of(
                    new PageWorkbenchContract.ActionView(11L, 1L, 7L, "orders", "orders.detail", "getPageState",
                            "读取页面状态", "读取当前页面状态", "QUERY", "LOW", false, null, JSON.createObjectNode(),
                            JSON.createObjectNode(), JSON.createObjectNode(), List.of(), "src/views/orders/Detail.vue",
                            "MANUAL", "ACTIVE", JSON.createObjectNode(), null)));
            return new PageWorkbenchTaskProviderConfiguration().workflowEngineeringTaskProvider(catalog,
                    mock(PageAnalysisApplicationService.class), mock(PageWorkbenchProjectPort.class),
                    mock(PageWorkbenchModelPort.class), runtimePort, mock(PageWorkbenchBrowserReadinessApplicationService.class),
                    mock(PageWorkbenchAgentModelReadinessApplicationService.class), mock(PageWorkbenchWorkflowTraceReadinessApplicationService.class),
                    mock(PageWorkbenchReleaseReadinessApplicationService.class), JSON, new AiCodingContractResourceLoader(JSON));
        }

        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        String status() { return jdbc.queryForObject("SELECT execution_status FROM control_ai_coding_task", String.class); }
        void assertRolledBack() {
            assertEquals("RUNNING", status());
            assertEquals(0, count("control_ai_coding_task_artifact"));
            assertEquals(0, count("control_ai_coding_task_event"));
            assertEquals(0L, jdbc.queryForObject("SELECT lock_version FROM control_ai_coding_task", Long.class));
        }
        void assertAppliedOnce() {
            assertEquals(1, count("control_ai_coding_task_artifact"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='RESULT_APPLIED'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_ai_coding_task_event WHERE event_type='ARTIFACT_REJECTED'", Integer.class));
        }
        @Override public void close() {
            try { context.close(); }
            finally { if (mysql == null) jdbc.execute("SHUTDOWN"); else mysql.close(); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = RuntimeProxyClient.class)
    static class TraceClientConfiguration { }
}
