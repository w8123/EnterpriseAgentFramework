package com.enterprise.ai.runtime.route;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeSqlQueryRecorder;
import com.enterprise.ai.runtime.trace.RuntimeToolUsageStatisticsMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolUsageStatisticsQuery;
import com.enterprise.ai.runtime.trace.RuntimeToolUsageStatisticsReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeRouteEvaluationPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeSqlQueryRecorder queries;
    private RuntimeRouteEvaluationService evaluation;
    private RuntimeToolUsageStatisticsQuery statistics;
    private RuntimeToolUsageStatisticsMapper mapper;
    private final LocalDateTime now = LocalDateTime.now().withNano(0);

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_tool_call_log"), RuntimeToolUsageStatisticsMapper.class);
        queries = new RuntimeSqlQueryRecorder();
        database.addInterceptor(queries);
        mapper = spy(database.mapper(RuntimeToolUsageStatisticsMapper.class));
        var proxy = new org.springframework.aop.framework.ProxyFactory(new RuntimeToolUsageStatisticsReader(mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.jdbc().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        statistics = (RuntimeToolUsageStatisticsQuery) proxy.getProxy();
        evaluation = new RuntimeRouteEvaluationService(statistics);
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void aLargeEvidenceWindowIsReadInBoundedPagesWithoutTruncatingCounts() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 1001; index++) {
            rows.add(new Object[] {index, "trace-" + index, "intent-" + index % 2, "agent-" + index % 3,
                    index % 2 == 0 ? "[]" : null, now});
        }
        insert(rows);
        var result = evaluation.evaluate(30);
        assertEquals(1001, result.logCount());
        assertEquals(1001, result.traceCount());
        assertEquals(500, result.retrievalTraceCount());
        assertTrue(result.intentClassifierReady());
        assertTrue(result.domainClassifierReady());
        assertTrue(queries.queries().stream().allMatch(query -> query.rowCount() <= 500),
                "No evidence SELECT may materialize more than 500 rows");
    }

    @Test
    void statisticsNeverSelectUnrelatedInvocationBodiesOrIdentityDetails() {
        insert(List.<Object[]>of(new Object[] {1, "trace-1", "意图", "Agent", "[]", now}));
        database.jdbc().update("UPDATE runtime_tool_call_log SET args_json = ?, result_summary = ?, user_id = ?",
                "private-arguments".repeat(500), "private-result".repeat(500), "private-user");
        assertEquals(1, evaluation.evaluate(30).logCount());
        for (var query : queries.queries()) {
            String sql = query.sql().toLowerCase(Locale.ROOT);
            assertFalse(sql.contains("args_json") || sql.contains("result_summary") || sql.contains("user_id"),
                    "Statistics must not load call bodies or user identity columns");
        }
    }

    @Test
    void preservesExactJavaKeysAndHasTextSemantics() {
        insert(List.of(
                new Object[] {1, "Trace", "Intent", "Agent", null, now},
                new Object[] {2, "trace", "intent", "agent", "[]", now},
                new Object[] {3, " Trace ", " Intent ", " Agent ", "\t\n\u3000", now},
                new Object[] {4, "Trace", "Intent", "Agent", "{}", now},
                new Object[] {5, "\t\u3000", "\t", "\u3000", "{}", now},
                new Object[] {6, "\u00a0", "\u00a0", "\u00a0", "\u00a0", now}));
        var result = evaluation.evaluate(30);
        assertEquals(6, result.logCount());
        assertEquals(4, result.traceCount());
        assertEquals(3, result.retrievalTraceCount());
        assertEquals(Map.of("Intent", 2L, "intent", 1L, " Intent ", 1L, "\u00a0", 1L), result.intentCounts());
        assertEquals(Map.of("Agent", 2L, "agent", 1L, " Agent ", 1L, "\u00a0", 1L), result.agentCounts());
    }

    @Test
    void emptyWindowReturnsAnEmptyImmutableEvidenceSummary() {
        var result = evaluation.evaluate(30);
        assertEquals(0, result.logCount());
        assertEquals(0, result.traceCount());
        assertFalse(result.intentClassifierReady());
        assertFalse(result.domainClassifierReady());
        assertThrows(UnsupportedOperationException.class, () -> result.intentCounts().put("new", 1L));
    }

    @Test
    void clampsTheWindowToOneThroughNinetyDaysAndKeepsTheExistingFutureTimeRule() {
        insert(List.of(
                new Object[] {1, "old", "a", "a", null, now.minusDays(95)},
                new Object[] {2, "recent", "b", "b", null, now.minusDays(89)},
                new Object[] {3, "two-days", "c", "c", null, now.minusDays(2)},
                new Object[] {4, "future", "d", "d", null, now.plusDays(1)}));
        var maximum = evaluation.evaluate(1000);
        assertEquals(90, maximum.days());
        assertEquals(3, maximum.logCount());
        var minimum = evaluation.evaluate(-1);
        assertEquals(1, minimum.days());
        assertEquals(1, minimum.logCount());
    }

    @Test
    void completedPagesAreNotRetainedInTheTransactionLocalCache() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 1001; index++) {
            rows.add(new Object[] {index, "trace-" + index, "intent", "agent", null, now});
        }
        insert(rows);
        var probe = new PageCacheProbe();
        database.addInterceptor(probe);
        assertEquals(1001, evaluation.evaluate(30).logCount());
        assertEquals(2, probe.previousPageRetained.size());
        assertTrue(probe.previousPageRetained.stream().noneMatch(Boolean::booleanValue),
                "A page boundary must release the preceding MyBatis result page");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500, 501, 1000, 1500})
    void visitsEveryEvidenceRowAcrossExactAndPartialPageBoundaries(int size) {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            rows.add(new Object[] {index, "trace-" + index, "intent", "agent", null, now.minusHours(index % 7)});
        }
        insert(rows);
        var summary = statistics.summarizeSince(now.minusDays(1));
        assertEquals(size, summary.logCount());
        assertEquals(size, summary.traceCount());
        assertEquals((long) size, summary.intentCounts().get("intent"));
        assertEquals(2 + size / 500, queries.queries().size());
        assertEquals(size, queries.queries().stream().skip(1).mapToInt(RuntimeSqlQueryRecorder.Query::rowCount).sum());
        assertTrue(queries.queries().stream().allMatch(query -> query.rowCount() <= 500));
    }

    @Test
    void sqlRetrievalPresenceMatchesAllJavaWhitespaceCharactersAndNonWhitespaceUnicode() {
        List<String> values = new ArrayList<>();
        StringBuilder allWhitespace = new StringBuilder();
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            if (Character.isWhitespace((char) code)) {
                values.add(String.valueOf((char) code));
                allWhitespace.append((char) code);
            }
        }
        values.add(allWhitespace.toString());
        values.add(""); values.add(null);
        values.add("\u00a0"); values.add("\u2007"); values.add("\u202f"); values.add("\u200b");
        values.add(allWhitespace + "{}" + allWhitespace);
        values.add("{\"hit\":\"" + "large-retrieval".repeat(40_000) + "\"}");
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            rows.add(new Object[] {index + 1, "trace-" + index, "intent", "agent", values.get(index), now});
        }
        insert(rows);
        var result = statistics.summarizeSince(now.minusDays(1));
        assertEquals(values.size(), result.logCount());
        assertEquals(6, result.retrievalTraceCount());
    }

    @Test
    void aRepeatedTraceGainsRetrievalEvidenceOnceAcrossDifferentPages() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 1203; index++) {
            rows.add(new Object[] {index, "trace-" + index % 601, "intent", "agent",
                    index % 3 == 0 ? "[]" : null, now.minusHours(index % 7)});
        }
        insert(rows);
        var result = statistics.summarizeSince(now.minusDays(1));
        assertEquals(1203, result.logCount());
        assertEquals(601, result.traceCount());
        assertEquals(401, result.retrievalTraceCount());
    }

    @Test
    void includesTheExactLowerTimeBoundaryAndExcludesNullOrEarlierTimes() {
        insert(List.of(
                new Object[] {1, "earlier", "intent", "agent", null, now.minusSeconds(1)},
                new Object[] {2, "exact", "intent", "agent", null, now},
                new Object[] {3, "missing", "intent", "agent", null, null}));
        var result = statistics.summarizeSince(now);
        assertEquals(1, result.logCount());
        assertEquals(1, result.traceCount());
    }

    @Test
    void aCommittedInsertDeleteAndUpdateDuringPagingDoNotChangeTheReadSnapshot() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 600; index++) {
            rows.add(new Object[] {index, "trace-" + index, "intent", "agent", null, now});
        }
        insert(rows);
        var mutation = new AfterFirstPage(() -> {
            try (var connection = database.jdbc().getDataSource().getConnection();
                 var statement = connection.createStatement()) {
                assertTrue(connection.getAutoCommit());
                statement.executeUpdate("INSERT INTO runtime_tool_call_log (id, trace_id, tool_name, create_time) VALUES (10000, 'late', 'test.tool', '" + now.minusHours(1) + "')");
                statement.executeUpdate("DELETE FROM runtime_tool_call_log WHERE id = 550");
                statement.executeUpdate("UPDATE runtime_tool_call_log SET trace_id = 'trace-1' WHERE id = 551");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        database.addInterceptor(mutation);
        var result = statistics.summarizeSince(now.minusDays(1));
        assertTrue(mutation.called);
        assertEquals(600, result.logCount());
        assertEquals(600, result.traceCount());
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_tool_call_log WHERE id = 10000", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_tool_call_log WHERE id = 550", Integer.class));
    }

    @Test
    void aLaterPageFailurePropagatesInsteadOfReturningACompleteLookingPartialSummary() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 501; index++) rows.add(new Object[] {index, "trace-" + index, "intent", "agent", null, now});
        insert(rows);
        doThrow(new IllegalStateException("page unavailable")).when(mapper)
                .findUsagePage(any(), anyLong(), notNull(), notNull(), anyList());
        assertThrows(IllegalStateException.class, () -> statistics.summarizeSince(now.minusDays(1)));
        assertEquals(2, queries.queries().size());
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class AfterFirstPage implements Interceptor {
        private final Runnable mutation;
        boolean called;
        AfterFirstPage(Runnable mutation) { this.mutation = mutation; }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (!called && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".findUsagePage")) {
                called = true;
                mutation.run();
            }
            return result;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class PageCacheProbe implements Interceptor {
        private CacheKey previous;
        final List<Boolean> previousPageRetained = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Object result = invocation.proceed();
            if (statement.getId().endsWith(".findUsagePage")) {
                Executor executor = (Executor) invocation.getTarget();
                if (previous != null) previousPageRetained.add(executor.isCached(statement, previous));
                previous = executor.createCacheKey(statement, invocation.getArgs()[1],
                        (RowBounds) invocation.getArgs()[2], statement.getBoundSql(invocation.getArgs()[1]));
            }
            return result;
        }
    }

    private void insert(List<Object[]> rows) {
        database.jdbc().batchUpdate("""
                INSERT INTO runtime_tool_call_log (id, trace_id, intent_type, agent_name, retrieval_trace_json, create_time, tool_name)
                VALUES (?, ?, ?, ?, ?, ?, 'test.tool')
                """, rows);
    }
}
