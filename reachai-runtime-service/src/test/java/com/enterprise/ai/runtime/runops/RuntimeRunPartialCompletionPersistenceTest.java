package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRunPartialCompletionPersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private RuntimeRunMapper runs;
    private RuntimeRunLifecycleService lifecycle;
    private RuntimeTraceSpanMapper spans;
    private SupervisorExecutionTraceService supervisor;
    private SupervisorExecutionTraceService.TraceHandle handle;
    private final Map<String,Object> metrics = Map.of("planCount",2,"replanCount",1,"workflowCallCount",3,
            "toolCallCount",3,"guardDenyCount",0,"approvalCount",0,"tokenCost",47);

    @BeforeEach void setup() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run","runtime_trace_span","runtime_tool_call_log","runtime_guard_decision_log"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class);
        runs = database.mapper(RuntimeRunMapper.class);
        spans = database.mapper(RuntimeTraceSpanMapper.class);
        lifecycle = new RuntimeRunLifecycleService(runs,json);
        var root = new RuntimeTraceRootService(spans,json);
        supervisor = new SupervisorExecutionTraceService(new RuntimeTraceEvidenceWriter(spans,database.mapper(RuntimeToolCallLogMapper.class)),
                lifecycle,json,root,new RuntimeTraceSpanTerminationService(spans));
        var started = LocalDateTime.now().minusMinutes(1);
        var span = root.start(RuntimeTraceRootService.Start.builder().traceId("partial").spanId("root")
                .spanType("SUPERVISOR").runtimeType("AGENTSCOPE").startedAt(started).metadataJson("{}").build());
        handle = new SupervisorExecutionTraceService.TraceHandle("partial","root",span.id(),started);
        var run = new RuntimeRunEntity();
        run.setTraceId("partial"); run.setRunType("AGENT"); run.setEntryType("API"); run.setStatus("RUNNING");
        run.setStartedAt(started); run.setRootSpanId("root"); run.setTokenCost(0);
        runs.insert(run);
        for (int i=0;i<3;i++) {
            var tool = new RuntimeToolCallLogEntity(); tool.setTraceId("partial"); tool.setToolName("call-"+i);
            tool.setSuccess(true); tool.setCreateTime(started); database.mapper(RuntimeToolCallLogMapper.class).insert(tool);
        }
        supervisor.finish(handle,false,"RUNTIME_GRAPH_INTERACTION_WAITING","wait",metrics);
        assertMetrics(runs.selectById(run.getId()),47,2,1,3);
    }

    @AfterEach void close() { if(database!=null) database.close(); }

    @Test void completingTheLastWorkflowAfterRecreationPreservesRecordedMetrics() throws Exception {
        // A reconstructed owner models the final continuation path after Runtime replacement.
        lifecycle = new RuntimeRunLifecycleService(runs,json);
        supervisor = new SupervisorExecutionTraceService(new RuntimeTraceEvidenceWriter(spans,database.mapper(RuntimeToolCallLogMapper.class)),
                lifecycle,json,new RuntimeTraceRootService(spans,json),new RuntimeTraceSpanTerminationService(spans));
        handle = supervisor.resume("partial");
        supervisor.finish(handle,true,"SUPERVISOR_COMPLETED","done",Map.of("continuationMode","LAST_WORKFLOW_STEP"));
        var run = runs.selectById(1L);
        assertEquals("COMPLETED",run.getStatus());
        assertMetrics(run,47,2,1,3);
        var rootMetadata = json.readTree(spans.selectById(handle.rootId()).getMetadataJson());
        assertEquals(47,rootMetadata.path("tokenCost").asInt());
        assertEquals(3,rootMetadata.path("workflowCallCount").asInt());
        assertEquals(3,json.readTree(run.getMetadataJson()).path("workflowCallCount").asInt());
        // A late completion must not replace the terminal outcome or its counters.
        lifecycle.finishAgent("partial",false,"LATE_FAILURE","late",Map.of("workflowCallCount",0),LocalDateTime.now());
        assertEquals("COMPLETED",runs.selectById(1L).getStatus());
        assertMetrics(runs.selectById(1L),47,2,1,3);
    }

    @Test void anotherInputPauseAndCancellationDoNotEraseMetrics() {
        lifecycle.finishAgent("partial",false,"RUNTIME_GRAPH_INTERACTION_WAITING","wait again",
                Map.of("sourceType","WORKFLOW_INTERACTION_RESUME","waiting",true,"privateData","must-not-persist"),LocalDateTime.now());
        assertEquals("SUSPENDED",runs.selectById(1L).getStatus());
        assertMetrics(runs.selectById(1L),47,2,1,3);
        assertFalse(runs.selectById(1L).getMetadataJson().contains("must-not-persist"));
        lifecycle.finishAgent("partial",false,"RUNTIME_INTERACTION_CANCELLED","cancelled",Map.of(),LocalDateTime.now());
        assertMetrics(runs.selectById(1L),47,2,1,3);
    }

    @Test void explicitZeroMetricsRemainAuthoritative() {
        supervisor.resume("partial");
        supervisor.finish(handle,true,"SUPERVISOR_COMPLETED","done",Map.of("planCount",0,"replanCount",0,
                "workflowCallCount",0,"toolCallCount",0,"guardDenyCount",0,"approvalCount",0,
                "usage",Map.of("totalTokens",0)));
        assertMetrics(runs.selectById(1L),0,0,0,0);
    }

    @Test void partialCountersDoNotDiscardKnownCountsWhenBestEffortAuditsAreMissing() {
        database.jdbc().update("DELETE FROM runtime_tool_call_log WHERE trace_id = ?","partial");
        lifecycle.finishAgent("partial",true,"SUPERVISOR_COMPLETED","done",Map.of("approvalCount",1),LocalDateTime.now());
        var run = runs.selectById(1L);
        assertMetrics(run,47,2,1,3);
        assertEquals(3,run.getToolCallCount());
        assertEquals(1,run.getApprovalCount());
    }

    private void assertMetrics(RuntimeRunEntity run,int tokens,int plans,int replans,int calls) {
        assertEquals(tokens,run.getTokenCost(),"token cost");
        assertEquals(plans,run.getPlanCount(),"plan count");
        assertEquals(replans,run.getReplanCount(),"replan count");
        assertEquals(calls,run.getWorkflowCallCount(),"Workflow call count");
    }
}
