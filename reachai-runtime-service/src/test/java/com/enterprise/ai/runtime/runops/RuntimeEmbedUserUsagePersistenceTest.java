package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-person embedded chat summary behind Control's 「AI 使用情况」, against the baseline table. */
class RuntimeEmbedUserUsagePersistenceTest {
    private final LocalDateTime now = LocalDateTime.now();
    private RuntimeQueryTestDatabase database;
    private RuntimeRunMapper mapper;
    private int nextId = 1;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run"), RuntimeRunMapper.class);
        mapper = database.mapper(RuntimeRunMapper.class);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void countsOnlyEmbeddedAgentChatsPerBusinessAccount() {
        run("EMBED", "AGENT", "COMPLETED", "yudao-erp", "101", "s1", now.minusHours(1), 100);
        run("EMBED", "AGENT", "FAILED", "yudao-erp", "101", "s1", now.minusHours(2), 50);
        run("EMBED", "AGENT", "COMPLETED", "yudao-erp", "101", "s2", now.minusHours(3), 0);
        run("EMBED", "AGENT", "COMPLETED", "yudao-erp", "102", "s3", now.minusHours(1), 10);
        run("EMBED", "AGENT", "COMPLETED", "yudao-erp", "101", "s0", now.minusDays(40), 10);
        run("AUTOMATION", "AGENT", "COMPLETED", "yudao-erp", "101", "s4", now.minusHours(1), 10);
        run("EMBED", "WORKFLOW", "COMPLETED", "yudao-erp", "101", "s5", now.minusHours(1), 10);
        run("EMBED", "AGENT", "COMPLETED", "billing", "201", "s6", now.minusHours(1), 10);

        List<RuntimeEmbedUserUsageView> rows = mapper.selectEmbedUserUsage(now.minusDays(30), "yudao-erp");

        assertEquals(List.of("101", "102"), rows.stream().map(RuntimeEmbedUserUsageView::getExternalUserId).toList());
        RuntimeEmbedUserUsageView first = rows.get(0);
        assertEquals(3, first.getRunCount());
        assertEquals(2, first.getSessionCount());
        assertEquals(1, first.getFailedCount());
        assertEquals(150L, first.getTokenCost());
        assertEquals("yudao-erp", first.getProjectCode());
        assertEquals(3, mapper.selectEmbedUserUsage(now.minusDays(30), null).size());
    }

    private void run(String entryType, String runType, String status, String projectCode, String userId,
                     String sessionId, LocalDateTime startedAt, int tokens) {
        int id = nextId++;
        database.jdbc().update("""
                INSERT INTO runtime_run (id, trace_id, run_type, entry_type, status, project_code, app_id,
                    session_id, external_user_id, token_cost, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "trace-" + id, runType, entryType, status, projectCode, projectCode, sessionId, userId,
                tokens, startedAt);
    }
}
