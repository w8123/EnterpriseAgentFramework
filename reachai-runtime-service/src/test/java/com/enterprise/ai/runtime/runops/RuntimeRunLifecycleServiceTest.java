package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRunLifecycleServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RuntimeRunEntity.class);
    }

    @Test
    void finishSuccessWritesTokenCostAndTerminalFieldsWithoutNullTokenCost() {
        FinishCapture capture = finishCapture(1);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 14, 9, 30, 0);
        capture.service().beginAgent(
                "trace-direct",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                Map.of("entryType", "EMBED", "sessionId", "session-before", "message", "hi"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", "session-final");
        metadata.put("planCount", 0);
        metadata.put("replanCount", 0);
        metadata.put("workflowCallCount", 0);
        metadata.put("toolCallCount", 0);
        metadata.put("guardDenyCount", 0);
        metadata.put("approvalCount", 0);
        metadata.put("usage", Map.of("inputTokens", 12, "outputTokens", 8));

        capture.service().finishAgent(
                "trace-direct",
                true,
                "SUPERVISOR_COMPLETED",
                "这是无需调用 Workflow 的直接回答",
                metadata,
                startedAt.plusNanos(1_500_000_000L),
                null,
                1500,
                startedAt);

        Map<String, Object> sets = capture.lastSetValues();
        assertEquals("COMPLETED", sets.get("status"));
        assertEquals("[omitted]", sets.get("output_summary"));
        assertEquals(1500, ((Number) sets.get("latency_ms")).intValue());
        assertEquals(20, ((Number) sets.get("token_cost")).intValue());
        assertNotNull(sets.get("ended_at"));
        assertEquals("session-final", sets.get("session_id"));
        assertFalse(sets.containsKey("token_cost") && sets.get("token_cost") == null);
    }

    @Test
    void finishWithoutTokenMetadataWritesZeroTokenCostNotNull() {
        FinishCapture capture = finishCapture(1);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 14, 10, 0, 0);
        capture.service().beginAgent(
                "trace-zero-token",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                Map.of("entryType", "API", "message", "hi"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planCount", 0);
        metadata.put("workflowCallCount", 0);
        metadata.put("toolCallCount", 0);
        metadata.put("guardDenyCount", 0);
        metadata.put("approvalCount", 0);

        capture.service().finishAgent(
                "trace-zero-token",
                true,
                "SUPERVISOR_COMPLETED",
                "ok",
                metadata,
                startedAt.plusSeconds(1),
                null,
                1000,
                startedAt);

        Map<String, Object> sets = capture.lastSetValues();
        assertEquals(0, ((Number) sets.get("token_cost")).intValue());
        assertEquals("COMPLETED", sets.get("status"));
        assertNotNull(sets.get("ended_at"));
    }

    @Test
    void finishWaitingApprovalKeepsEndedAtNull() {
        FinishCapture capture = finishCapture(1);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 14, 9, 31, 0);
        capture.service().beginAgent(
                "trace-waiting",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                Map.of("message", "请执行高风险操作", "sessionId", "session-approval"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planCount", 1);
        metadata.put("workflowCallCount", 0);
        metadata.put("toolCallCount", 0);
        metadata.put("guardDenyCount", 0);
        metadata.put("approvalCount", 1);

        capture.service().finishAgent(
                "trace-waiting",
                false,
                "SUPERVISOR_CONFIRMATION_REQUIRED",
                "需要人工确认后继续",
                metadata,
                startedAt.plusSeconds(2));

        Map<String, Object> sets = capture.lastSetValues();
        assertEquals("SUSPENDED", sets.get("status"));
        assertEquals("APPROVAL", sets.get("suspension_reason"));
        assertNull(sets.get("ended_at"));
        assertNull(sets.get("latency_ms"));
        assertEquals(0, ((Number) sets.get("token_cost")).intValue());
        assertEquals(1, ((Number) sets.get("approval_count")).intValue());
    }

    @Test
    void finishUpdateReturningZeroLogsNoMatchingRow() {
        FinishCapture capture = finishCapture(0);
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeRunLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            capture.service().finishAgent(
                    "trace-missing",
                    true,
                    "SUPERVISOR_COMPLETED",
                    "ok",
                    Map.of("toolCallCount", 0, "guardDenyCount", 0, "approvalCount", 0),
                    LocalDateTime.now(),
                    null,
                    10,
                    LocalDateTime.now().minusSeconds(1));
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getFormattedMessage().contains("no runtime_run row matched")
                            && event.getFormattedMessage().contains("trace-missing")));
            assertTrue(appender.list.stream().noneMatch(event ->
                    event.getFormattedMessage().contains("runtime_run is missing")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void resolveTokenCostPrefersExplicitIntegerAndWalksUsageTrees() {
        assertEquals(0, RuntimeRunLifecycleService.resolveTokenCost(null));
        assertEquals(0, RuntimeRunLifecycleService.resolveTokenCost(Map.of()));
        assertEquals(42, RuntimeRunLifecycleService.resolveTokenCost(Map.of("tokenCost", 42)));
        assertEquals(20, RuntimeRunLifecycleService.resolveTokenCost(Map.of(
                "usage", Map.of("inputTokens", 12, "outputTokens", 8))));
        assertEquals(30, RuntimeRunLifecycleService.resolveTokenCost(Map.of(
                "model", Map.of("_chat_usage", Map.of("totalTokens", 30)))));
        assertEquals(40, RuntimeRunLifecycleService.resolveTokenCost(Map.of(
                "model", Map.of("usage", Map.of("promptTokens", 25, "completionTokens", 15)))));
        // Prefer canonical model usage over duplicated supervisor.modelRounds token fields.
        assertEquals(909, RuntimeRunLifecycleService.resolveTokenCost(Map.of(
                "model", Map.of(
                        "usage", Map.of("inputTokens", 862, "outputTokens", 47, "totalTokens", 909),
                        "supervisor.modelRounds", List.of(Map.of(
                                "promptTokens", 862, "completionTokens", 47))),
                "supervisor.modelRounds", List.of(Map.of(
                        "promptTokens", 862, "completionTokens", 47)))));
    }

    @Test
    void persistsTrustedIdentityUserIdAndIgnoresAttackerBodyUserId() {
        FinishCapture capture = finishCapture(1);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 19, 12, 0, 0);
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAgent(7L, "orders", "42");
        capture.service().beginAgent(
                "trace-trusted",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                Map.of("entryType", "AGENT", "userId", "attacker", "message", "spoof me"),
                identity);

        RuntimeRunEntity saved = capture.inserted().get();
        assertEquals("42", saved.getUserId());
        assertFalse(saved.getInputSummary() != null && saved.getInputSummary().contains("attacker"));
    }

    @Test
    void mapsWorkflowInteractionWaitingToWaitingUserWithoutEndingRun() {
        FinishCapture capture = finishCapture(1);
        capture.service().beginWorkflow(
                "trace-wfi",
                "span-root",
                "WORKFLOW_STUDIO",
                "wf-1",
                "demo-flow",
                "Demo Flow",
                "demo",
                "GRAPH_SPEC",
                "{\"entryNodeId\":\"form\"}",
                Map.of("message", "start"));
        capture.service().finishWorkflow(
                "trace-wfi",
                false,
                "RUNTIME_GRAPH_INTERACTION_WAITING",
                "Interaction node is waiting for user input: form",
                1,
                Map.of("interactionId", "wfi_abc", "nodeCount", 1));

        RuntimeRunEntity saved = capture.inserted().get();
        assertEquals("SUSPENDED", saved.getStatus());
        assertEquals("USER_INPUT", saved.getSuspensionReason());
        assertEquals("GRAPH_SPEC", saved.getRuntimeType());
        assertNull(saved.getEndedAt());
        assertNull(saved.getErrorCode());
        assertEquals("[omitted]", saved.getOutputSummary());
        assertEquals(0, saved.getTokenCost());
    }

    private FinishCapture finishCapture(int updateRows) {
        RuntimeRunMapper runMapper = mock(RuntimeRunMapper.class);
        AtomicReference<RuntimeRunEntity> inserted = new AtomicReference<>();
        AtomicReference<LambdaUpdateWrapper<RuntimeRunEntity>> lastUpdate = new AtomicReference<>();
        AtomicInteger updateCalls = new AtomicInteger();
        when(runMapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        when(runMapper.insert(any())).thenAnswer(invocation -> {
            RuntimeRunEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            inserted.set(entity);
            return 1;
        });
        when(runMapper.update(isNull(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaUpdateWrapper<RuntimeRunEntity> wrapper = invocation.getArgument(1);
            lastUpdate.set(wrapper);
            updateCalls.incrementAndGet();
            return updateRows;
        });
        when(runMapper.selectFinishCounts(any())).thenReturn(finishCounts(0L, 0L, 0L));
        RuntimeRunLifecycleService service = new RuntimeRunLifecycleService(runMapper, new ObjectMapper());
        return new FinishCapture(service, runMapper, inserted, lastUpdate, updateCalls);
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "orders", "orders-agent", "Orders Agent", null,
                "PROJECT", null, true,
                91L, 91L, 4, "ARCHIVED", "AGENTSCOPE", 0, null, null);
    }

    private RuntimeAgentConfigVersionEntity publishedConfig() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(91L);
        config.setAgentId("agent-1");
        config.setVersionNo(4);
        config.setStatus("ARCHIVED");
        config.setRuntimeType("AGENTSCOPE");
        return config;
    }

    private RuntimeRunFinishCounts finishCounts(long toolCalls, long guardDenies, long approvals) {
        RuntimeRunFinishCounts counts = new RuntimeRunFinishCounts();
        counts.setToolCallCount(toolCalls);
        counts.setGuardDenyCount(guardDenies);
        counts.setApprovalCount(approvals);
        return counts;
    }

    private static final class FinishCapture {
        private final RuntimeRunLifecycleService service;
        private final RuntimeRunMapper runMapper;
        private final AtomicReference<RuntimeRunEntity> inserted;
        private final AtomicReference<LambdaUpdateWrapper<RuntimeRunEntity>> lastUpdate;
        private final AtomicInteger updateCalls;

        private FinishCapture(RuntimeRunLifecycleService service,
                              RuntimeRunMapper runMapper,
                              AtomicReference<RuntimeRunEntity> inserted,
                              AtomicReference<LambdaUpdateWrapper<RuntimeRunEntity>> lastUpdate,
                              AtomicInteger updateCalls) {
            this.service = service;
            this.runMapper = runMapper;
            this.inserted = inserted;
            this.lastUpdate = lastUpdate;
            this.updateCalls = updateCalls;
        }

        RuntimeRunLifecycleService service() {
            return service;
        }

        AtomicReference<RuntimeRunEntity> inserted() {
            return inserted;
        }

        Map<String, Object> lastSetValues() {
            assertTrue(updateCalls.get() > 0, "finish must invoke conditional update");
            LambdaUpdateWrapper<RuntimeRunEntity> wrapper = lastUpdate.get();
            assertNotNull(wrapper);
            verify(runMapper).update(isNull(), any());
            return extractSetValues(wrapper);
        }
    }

    /**
     * Extracts column→value pairs from a MyBatis-Plus LambdaUpdateWrapper SET clause.
     * Fails loudly if token_cost is absent or explicitly null.
     */
    private static Map<String, Object> extractSetValues(LambdaUpdateWrapper<RuntimeRunEntity> wrapper) {
        String sqlSet = wrapper.getSqlSet();
        assertNotNull(sqlSet);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String fragment : sqlSet.split(",")) {
            String part = fragment.trim();
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String column = part.substring(0, eq).trim().replace("`", "");
            String placeholder = part.substring(eq + 1).trim();
            if ("null".equalsIgnoreCase(placeholder)) {
                out.put(column, null);
                continue;
            }
            int start = placeholder.indexOf("#{");
            int end = placeholder.lastIndexOf('}');
            if (start < 0 || end < 0) {
                continue;
            }
            String path = placeholder.substring(start + 2, end);
            // ew.paramNameValuePairs.MPGENVAL1
            String key = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            out.put(column, params.get(key));
        }
        assertTrue(out.containsKey("token_cost"),
                "finish UPDATE must set token_cost; sqlSet=" + sqlSet);
        assertNotNull(out.get("token_cost"),
                "token_cost must not be written as null; sqlSet=" + sqlSet);
        return out;
    }
}
