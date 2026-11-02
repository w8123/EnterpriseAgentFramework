package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The unique baseline Run key claims an AI trial before external execution. */
class RuntimePlatformAiWorkflowTrialPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeRunLifecycleService lifecycle;
    private static final String TRACE = "mcp-trial-" + "a".repeat(32);
    private static final RuntimeRunSnapshots.Workflow WORKFLOW = new RuntimeRunSnapshots.Workflow(
            "wf-1", "orders-workflow", "订单流程", 7L, "orders", "GRAPH_SPEC", "{}");
    private static final WorkflowExecutionIdentity ACTOR = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(7L, "orders", "42");

    @BeforeEach void open() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run"), RuntimeRunMapper.class);
        lifecycle = new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), new ObjectMapper());
    }

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void concurrentAttemptsWithOneTrialIdHaveExactlyOneClaim() throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                ready.countDown(); start.await();
                try {
                    lifecycle.beginPlatformAiWorkflowTrial(TRACE, "span-" + i, WORKFLOW,
                            Map.of("inputKeys", List.of("orderNo")), ACTOR, Map.of("platformActorId", "42"));
                    return true;
                } catch (RuntimeRunLifecycleService.PlatformTrialAlreadyClaimedException duplicate) {
                    return false;
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run WHERE trace_id=?", Integer.class, TRACE));
            var row = database.jdbc().queryForMap("SELECT entry_type, project_code, workflow_id, snapshot_json FROM runtime_run WHERE trace_id=?", TRACE);
            assertEquals("STUDIO_READ_ONLY_TRIAL", row.get("entry_type"));
            assertEquals("orders", row.get("project_code"));
            assertEquals("wf-1", row.get("workflow_id"));
            assertFalse(String.valueOf(row.get("snapshot_json")).contains("private input"));
        } finally { pool.shutdownNow(); }
    }

    @Test void unavailablePersistenceFailsClosed() {
        var mapper = mock(RuntimeRunMapper.class);
        when(mapper.insert(any(RuntimeRunEntity.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var service = new RuntimeRunLifecycleService(mapper, new ObjectMapper());
        assertThrows(RuntimeRunLifecycleService.PlatformTrialClaimUnavailableException.class,
                () -> service.beginPlatformAiWorkflowTrial(TRACE, "span", WORKFLOW, Map.of(), ACTOR, Map.of()));
    }
}
