package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeRunResumePersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeRunMapper mapper;
    private RuntimeRunLifecycleService lifecycle;
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 6, 10, 0);

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run"), RuntimeRunMapper.class);
        mapper = spy(database.mapper(RuntimeRunMapper.class));
        lifecycle = new RuntimeRunLifecycleService(mapper, new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void resumeClearsPersistedSuspensionAndTerminalFields() {
        seed("SUSPENDED");
        lifecycle.resumeAgent("trace-1");
        var current = mapper.selectById(1L);
        assertEquals("RUNNING", current.getStatus());
        assertNull(current.getSuspensionReason());
        assertNull(current.getEndedAt());
        assertNull(current.getErrorCode());
        assertNull(current.getErrorMessage());
    }

    @Test
    void concurrentTerminalCommitCannotBeOverwrittenByStaleResumeRead() {
        seed("SUSPENDED");
        doAnswer(call -> {
            Object previous = call.callRealMethod();
            database.jdbc().update("UPDATE runtime_run SET status = 'TIMED_OUT' WHERE id = 1");
            return previous;
        }).when(mapper).selectOne(any());

        lifecycle.resumeAgent("trace-1");

        assertEquals("TIMED_OUT", mapper.selectById(1L).getStatus());
        assertEquals(END, mapper.selectById(1L).getEndedAt());
    }

    @Test
    void completedRunKeepsItsEvidence() {
        seed("COMPLETED");
        lifecycle.resumeAgent("trace-1");
        assertEquals("COMPLETED", mapper.selectById(1L).getStatus());
        assertEquals("original error", mapper.selectById(1L).getErrorMessage());
    }

    @Test
    void agentResumeDoesNotReopenASuspendedWorkflowRun() {
        seed("SUSPENDED");
        database.jdbc().update("UPDATE runtime_run SET run_type = 'WORKFLOW' WHERE id = 1");
        lifecycle.resumeAgent("trace-1");
        assertEquals("SUSPENDED", mapper.selectById(1L).getStatus());
        assertEquals(END, mapper.selectById(1L).getEndedAt());
    }

    private void seed(String status) {
        database.jdbc().update("""
                INSERT INTO runtime_run (id, trace_id, run_type, entry_type, status, suspension_reason,
                    ended_at, error_code, error_message)
                VALUES (1, 'trace-1', 'AGENT', 'API', ?, 'USER_INPUT', ?, 'ORIGINAL', 'original error')
                """, status, END);
    }
}
