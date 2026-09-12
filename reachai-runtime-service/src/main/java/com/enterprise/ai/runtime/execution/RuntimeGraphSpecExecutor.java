package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointCodec;
import com.enterprise.ai.runtime.execution.kernel.RuntimeGraphSpecExecutionEngine;
import com.enterprise.ai.runtime.execution.trace.RuntimeExecutionTraceProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Stable GraphSpec Runtime facade.
 *
 * <p>Callers depend on this API while parsing, policies, handler dispatch, events and checkpoints
 * evolve inside {@link RuntimeGraphSpecExecutionEngine}. Keeping this boundary stable also allows
 * suspended executions to route by engine/checkpoint version without changing Agent, Studio or
 * Embed entry points.</p>
 */
@Service
public final class RuntimeGraphSpecExecutor {

    public static final String TRUSTED_IDENTITY_CONTEXT_KEY =
            RuntimeGraphSpecExecutionEngine.TRUSTED_IDENTITY_CONTEXT_KEY;
    public static final int LOOP_DEFAULT_MAX_ITERATIONS =
            RuntimeGraphSpecExecutionEngine.LOOP_DEFAULT_MAX_ITERATIONS;
    public static final int LOOP_HARD_MAX_ITERATIONS =
            RuntimeGraphSpecExecutionEngine.LOOP_HARD_MAX_ITERATIONS;

    private final RuntimeGraphSpecExecutionEngine engine;

    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient, null, null, null);
    }

    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient,
                                    RuntimeKnowledgeRetrievalClient knowledgeClient,
                                    WorkflowHttpClient httpClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient,
                knowledgeClient, httpClient, null);
    }

    @Autowired
    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient,
                                    @Autowired(required = false) RuntimeKnowledgeRetrievalClient knowledgeClient,
                                    @Autowired(required = false) WorkflowHttpClient httpClient,
                                    @Autowired(required = false)
                                    RuntimeBusinessMemoryHydrationPort businessMemoryHydrationPort) {
        this.engine = new RuntimeGraphSpecExecutionEngine(
                objectMapper,
                modelServiceClient,
                capabilityClient,
                controlClient,
                knowledgeClient,
                httpClient,
                businessMemoryHydrationPort);
    }

    public static Set<String> handledNodeTypes() {
        return RuntimeGraphSpecExecutionEngine.handledNodeTypes();
    }

    public static Integer parseLoopMaxIterations(Object raw) {
        return RuntimeGraphSpecExecutionEngine.parseLoopMaxIterations(raw);
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson, Map<String, Object> request) {
        return execute(graphSpecJson, request, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(), identity, RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), evalContext);
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation) {
        return execute(graphSpecJson, request, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, sink, cancellation, identity, RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation,
                                                   WorkflowExecutionIdentity identity,
                                                   RuntimeEvalExecutionContext evalContext) {
        RuntimeExecutionTraceProjector projector = new RuntimeExecutionTraceProjector(sink);
        return projector.project(engine.execute(
                graphSpecJson, request, projector, cancellation, identity, evalContext));
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId) {
        return executeFromNode(graphSpecJson, request, entryNodeId,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation) {
        return executeFromNode(graphSpecJson, request, entryNodeId, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           RuntimeEvalExecutionContext evalContext) {
        return executeFromNode(graphSpecJson, request, entryNodeId, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), evalContext);
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           WorkflowExecutionIdentity identity) {
        return executeFromNode(graphSpecJson, request, entryNodeId, sink, cancellation,
                identity, RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           WorkflowExecutionIdentity identity,
                                                           RuntimeEvalExecutionContext evalContext) {
        RuntimeExecutionTraceProjector projector = new RuntimeExecutionTraceProjector(sink);
        return projector.project(engine.executeFromNode(
                graphSpecJson, request, entryNodeId, projector, cancellation, identity, evalContext));
    }

    /** Explicit durable-checkpoint router. Legacy Map checkpoints are the only compatibility path. */
    public RuntimeGraphSpecExecutionResult executeFromCheckpoint(String graphSpecJson,
                                                                 Map<String, Object> state,
                                                                 String entryNodeId,
                                                                 int checkpointSchemaVersion,
                                                                 String executionEngineVersion,
                                                                 WorkflowExecutionIdentity identity) {
        boolean legacy = checkpointSchemaVersion == 0
                && "LEGACY".equalsIgnoreCase(executionEngineVersion);
        boolean current = checkpointSchemaVersion == WorkflowCheckpointCodec.SCHEMA_VERSION
                && WorkflowCheckpointCodec.ENGINE_VERSION.equals(executionEngineVersion);
        if (!legacy && !current) {
            return new RuntimeGraphSpecExecutionResult(
                    false,
                    "RUNTIME_CHECKPOINT_ENGINE_UNSUPPORTED",
                    "Checkpoint cannot be routed to a supported Runtime execution engine",
                    entryNodeId,
                    null,
                    java.util.List.of(),
                    Map.of("checkpointSchemaVersion", checkpointSchemaVersion,
                            "executionEngineVersion", executionEngineVersion == null ? "" : executionEngineVersion));
        }
        return executeFromNode(
                graphSpecJson, state, entryNodeId,
                RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(),
                identity == null ? WorkflowExecutionIdentity.untrustedDebug() : identity,
                RuntimeEvalExecutionContext.none());
    }
}
