package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEvent;
import com.enterprise.ai.runtime.execution.WorkflowExecutionStatus;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RuntimeExecutableDebugSessionService {

    private static final long DEBUG_STREAM_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 8_000L;
    private static final Set<String> TARGET_TYPES = Set.of(
            "WORKFLOW_WORKING_COPY", "WORKFLOW_VERSION", "AGENT_WORKING_COPY",
            "COMPOSITION_WORKING_COPY", "EXECUTABLE_WORKING_COPY");

    private static final String REQUEST_PARAMS = "__requestParams";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<MessageView>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RuntimeWorkflowDebugService.DebugStepResult>> STEP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final RuntimeDebugSessionStore store;
    private final RuntimeWorkflowDebugService workflowDebugService;
    private final ObjectMapper objectMapper;
    private final SseHeartbeatSupport heartbeatSupport;
    private final long debugStreamHeartbeatIntervalMs;

    @Autowired
    public RuntimeExecutableDebugSessionService(RuntimeDebugSessionStore store,
                                                 RuntimeWorkflowDebugService workflowDebugService,
                                                ObjectMapper objectMapper,
                                                SseHeartbeatSupport heartbeatSupport,
                                                @Value("${reachai.runtime.debug-stream.heartbeat-interval-ms:8000}")
                                                long debugStreamHeartbeatIntervalMs) {
        this.store = store;
        this.workflowDebugService = workflowDebugService;
        this.objectMapper = objectMapper;
        this.heartbeatSupport = heartbeatSupport == null ? new SseHeartbeatSupport() : heartbeatSupport;
        this.debugStreamHeartbeatIntervalMs = debugStreamHeartbeatIntervalMs > 0
                ? debugStreamHeartbeatIntervalMs
                : DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    /** 测试构造：默认 heartbeat 周期与调度器。 */
    public RuntimeExecutableDebugSessionService(RuntimeExecutableDebugSessionMapper mapper,
                                                RuntimeWorkflowDebugService workflowDebugService,
                                                ObjectMapper objectMapper,
                                                RuntimeDebugExecutionLifecycle executionLifecycle) {
        this(new RuntimeDebugSessionStore(mapper, objectMapper, executionLifecycle), workflowDebugService, objectMapper, new SseHeartbeatSupport(), DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    public SessionView create(RuntimeDebugSessionOwner owner, CreateRequest request) {
        return create(owner, request, RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public SessionView create(RuntimeDebugSessionOwner owner, CreateRequest request,
                              RuntimeGraphSpecExecutionEventSink eventSink,
                              RuntimeGraphSpecExecutionCancellation cancellation) {
        return create(owner, request, eventSink, cancellation, admitted -> { });
    }

    private SessionView create(RuntimeDebugSessionOwner owner, CreateRequest request,
                               RuntimeGraphSpecExecutionEventSink eventSink,
                               RuntimeGraphSpecExecutionCancellation cancellation,
                               java.util.function.Consumer<SessionView> onAdmitted) {
        java.util.Objects.requireNonNull(owner, "owner");
        if (request == null || request.workingCopyDefinition() == null || request.workingCopyDefinition().isEmpty()) {
            throw new IllegalArgumentException("workingCopyDefinition is required");
        }
        String targetType = requireTargetType(request.targetType());
        var creationIdentity=RuntimeDebugCreationIdentity.from(owner,request,objectMapper);
        if(creationIdentity!=null){
            var existing=store.findCreation(owner,creationIdentity.sessionId(),creationIdentity.requestHash());
            if(existing!=null){
                var view=toView(existing);onAdmitted.accept(view);return view;
            }
        }
        var definition = workflowDebugService.captureDefinition(debugRunRequest(request.workingCopyDefinition()));
        String sessionId = creationIdentity==null ? UUID.randomUUID().toString() : creationIdentity.sessionId();
        // Creation keys produce 64-character session IDs; Run/Trace have their own 64-character limit.
        String runId = "studio-debug-session-" + UUID.randomUUID();
        Map<String, Object> options = executionOptions(request.debugOptions());

        List<MessageView> messages = new ArrayList<>();
        if (StringUtils.hasText(request.message())) {
            messages.add(message("user", request.message(), null, runId, null));
        }
        LocalDateTime now = LocalDateTime.now();
        RuntimeExecutableDebugSessionEntity entity = new RuntimeExecutableDebugSessionEntity();
        entity.setId(sessionId);
        entity.setRunId(runId);
        entity.setTraceId(runId);
        entity.setTargetType(targetType);
        entity.setStatus("RUNNING");
        entity.setRevision(0);
        entity.setCreationRequestHash(creationIdentity==null ? null : creationIdentity.requestHash());
        entity.setWorkingCopyDefinitionJson(writeJson(definition));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setStateSnapshotJson(writeJson(request.inputParams() == null ? Map.of() : request.inputParams()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson("[]");
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setExpiresAt(now.plusHours(24));
        if(creationIdentity==null)store.create(owner,entity);
        else {
            var admission=store.reserveCreation(owner,entity);
            if(!admission.created()){
                var view=toView(admission.session());onAdmitted.accept(view);return view;
            }
        }

        RuntimeWorkflowDebugService.DebugRunResult run;
        try {
            onAdmitted.accept(toView(entity));
            run = workflowDebugService.startSessionDebug(definition,
                    new RuntimeWorkflowDebugService.DebugInput(nullToEmpty(request.message()),
                            request.inputParams() == null ? Map.of() : request.inputParams(), options),
                    new RuntimeWorkflowDebugService.DebugRunReference(runId, runId), eventSink, cancellation);
            if (run == null) throw new IllegalStateException("debug execution returned no result");
        } catch (RuntimeException failure) {
            recordExecutionFailure(owner, entity, "RUNNING", 0, cancellation, failure);
            throw failure;
        }
        return completeExecution(owner, entity, run, messages, new ArrayList<>(), options, "RUNNING", 0);
    }

    public SessionView get(RuntimeDebugSessionOwner owner, String sessionId) {
        return toView(requireSession(owner, sessionId));
    }

    public SessionView getByCreationKey(RuntimeDebugSessionOwner owner, String key) {
        java.util.Objects.requireNonNull(owner,"owner");
        return get(owner,RuntimeDebugCreationIdentity.sessionId(owner,key,objectMapper));
    }

    public SessionView submit(RuntimeDebugSessionOwner owner, String sessionId, SubmitRequest request) {
        return submit(owner, sessionId, request,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public SessionView submit(RuntimeDebugSessionOwner owner, String sessionId,
                              SubmitRequest request,
                              RuntimeGraphSpecExecutionEventSink eventSink,
                              RuntimeGraphSpecExecutionCancellation cancellation) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(owner, sessionId);
        String status = normalizeStatus(entity.getStatus());
        Map<String, Object> submitted = request == null || request.values() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.values());
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        boolean repeatingSubmission = StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(entity.getIdempotencyKey());
        // The execution cursor can advance after submission. Replay belongs to the committed checkpoint.
        String submittedNodeId = repeatingSubmission
                ? text(readMap(entity.getSubmittedPayloadJson()).get("nodeId"))
                : entity.getCurrentNodeId();
        String submittedCanonical = writeJson(canonicalSubmitPayload(
                firstText(request == null ? null : request.action(), "submit"),
                submitted,
                request == null ? null : request.interactionId(),
                submittedNodeId));

        if (RuntimeDebugSessionStatus.RESUMING.name().equals(status)) {
            throw new IllegalStateException("debug submit in progress: " + sessionId);
        }
        // Check the previous receipt before validating a new waiting interaction.
        if (repeatingSubmission) {
            if (submittedCanonical.equals(entity.getSubmittedPayloadJson())) {
                return toView(entity);
            }
            throw new IllegalStateException("duplicate debug submit with different payload: " + sessionId);
        }
        if (!RuntimeDebugSessionStatus.SUSPENDED.name().equals(status)) {
            throw new IllegalArgumentException("debug session is not suspended: " + sessionId);
        }
        String action = firstText(request == null ? null : request.action(), "submit");
        if ("cancel".equalsIgnoreCase(action)) {
            return cancel(owner, sessionId);
        }
        if (!StringUtils.hasText(entity.getCurrentNodeId())) {
            throw new IllegalArgumentException("debug session has no waiting node: " + sessionId);
        }

        Map<String, Object> currentUi = readMap(entity.getUiRequestJson());
        String waitingInteractionId = text(currentUi.get("interactionId"));
        String requestInteractionId = request == null ? null : text(request.interactionId());
        if (StringUtils.hasText(waitingInteractionId)
                && StringUtils.hasText(requestInteractionId)
                && !waitingInteractionId.equals(requestInteractionId)) {
            throw new IllegalArgumentException(
                    "interactionId does not match waiting interaction: " + requestInteractionId);
        }
        if (!StringUtils.hasText(requestInteractionId) && StringUtils.hasText(waitingInteractionId)) {
            requestInteractionId = waitingInteractionId;
        }

        Map<String, Object> workingCopy = readMap(entity.getWorkingCopyDefinitionJson());
        Map<String, Object> stateSnapshot = readMap(entity.getStateSnapshotJson());
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("interactionId", firstText(requestInteractionId, waitingInteractionId));
        resume.put("nodeId", entity.getCurrentNodeId());
        resume.put("action", action);
        resume.put("values", submitted);
        if (StringUtils.hasText(idempotencyKey)) {
            resume.put("idempotencyKey", idempotencyKey);
        }
        Map<String, Object> params = new LinkedHashMap<>(asMap(stateSnapshot.get(REQUEST_PARAMS)));
        params.putAll(submitted);
        stateSnapshot.put(REQUEST_PARAMS, params);
        stateSnapshot.put("params", params);
        stateSnapshot.put(WorkflowInteractionCodes.RESUME_CONTEXT_KEY, resume);
        stateSnapshot.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY,
                firstText(requestInteractionId, waitingInteractionId));
        stateSnapshot.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, entity.getCurrentNodeId());
        // 禁止全局 submittedPayload 泄漏到后续 INTERACTION
        stateSnapshot.remove("submittedPayload");
        if (request != null && StringUtils.hasText(request.message())) {
            stateSnapshot.put("message", request.message());
            stateSnapshot.put("input", request.message());
        }

        Map<String, Object> options = executionOptions(readMap(entity.getDebugOptionsJson()));

        var definition = restoreSessionDefinition(workingCopy);
        var continuation = new RuntimeWorkflowDebugService.DebugContinuation(
                new RuntimeWorkflowDebugService.DebugRunReference(entity.getRunId(), entity.getTraceId()),
                entity.getCurrentNodeId());
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        List<RuntimeWorkflowDebugService.DebugStepResult> steps = readSteps(entity.getStepsJson());
        // Atomic CAS: SUSPENDED + revision -> RESUMING
        if (!store.claim(owner, entity, idempotencyKey, submittedCanonical)) {
            RuntimeExecutableDebugSessionEntity latest = requireSession(owner, sessionId);
            if (RuntimeDebugSessionStatus.RESUMING.name()
                    .equalsIgnoreCase(normalizeStatus(latest.getStatus()))) {
                throw new IllegalStateException("debug submit in progress: " + sessionId);
            }
            if (StringUtils.hasText(idempotencyKey)
                    && idempotencyKey.equals(latest.getIdempotencyKey())
                    && submittedCanonical.equals(latest.getSubmittedPayloadJson())) {
                return toView(latest);
            }
            throw new IllegalStateException("concurrent debug submit rejected: " + sessionId);
        }

        int revision = entity.getRevision();
        RuntimeWorkflowDebugService.DebugRunResult run;
        try {
            run = workflowDebugService.resumeSessionDebug(definition,
                    new RuntimeWorkflowDebugService.DebugInput(request == null ? "" : nullToEmpty(request.message()),
                            stateSnapshot, options), continuation, eventSink, cancellation);
            if (run == null) throw new IllegalStateException("debug execution returned no result");
        } catch (RuntimeException failure) {
            recordExecutionFailure(owner, entity, "RESUMING", revision, cancellation, failure);
            throw failure;
        }
        messages.add(message("user", submitMessage(request, submitted), entity.getCurrentNodeId(), entity.getTraceId(), null));
        return completeExecution(owner, entity, run, messages, steps, options, "RESUMING", revision);
    }

    public SessionView cancel(RuntimeDebugSessionOwner owner, String sessionId) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(owner, sessionId);
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        messages.add(message("system", "debug session cancelled", null, entity.getTraceId(), null));
        return toView(store.cancel(owner, entity, writeJson(messages)));
    }

    public SseEmitter streamCreate(RuntimeDebugSessionOwner owner, CreateRequest request) {
        java.util.Objects.requireNonNull(owner, "owner");
        SseEmitter emitter = new SseEmitter(DEBUG_STREAM_TIMEOUT_MS);
        RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
        ScheduledFuture<?> heartbeat = heartbeatSupport.start(
                emitter, debugStreamHeartbeatIntervalMs, cancellation::cancel);
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable stopHeartbeatAndCancel = () -> {
            heartbeatSupport.stop(heartbeat);
            cancellation.cancel();
        };
        emitter.onCompletion(() -> {
            completed.set(true);
            stopHeartbeatAndCancel.run();
        });
        emitter.onTimeout(() -> {
            stopHeartbeatAndCancel.run();
            emitter.complete();
        });
        emitter.onError(error -> stopHeartbeatAndCancel.run());
        CompletableFuture.runAsync(() -> {
            try {
                streamCreateInternal(owner, emitter, request, cancellation, completed);
            } finally {
                heartbeatSupport.stop(heartbeat);
            }
        });
        return emitter;
    }

    public SseEmitter streamSubmit(RuntimeDebugSessionOwner owner, String sessionId, SubmitRequest request) {
        requireSession(owner, sessionId);
        SseEmitter emitter = new SseEmitter(DEBUG_STREAM_TIMEOUT_MS);
        RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
        ScheduledFuture<?> heartbeat = heartbeatSupport.start(
                emitter, debugStreamHeartbeatIntervalMs, cancellation::cancel);
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable stopHeartbeatAndCancel = () -> {
            heartbeatSupport.stop(heartbeat);
            cancellation.cancel();
        };
        emitter.onCompletion(() -> {
            completed.set(true);
            stopHeartbeatAndCancel.run();
        });
        emitter.onTimeout(() -> {
            stopHeartbeatAndCancel.run();
            emitter.complete();
        });
        emitter.onError(error -> stopHeartbeatAndCancel.run());
        CompletableFuture.runAsync(() -> {
            try {
                streamSubmitInternal(owner, emitter, sessionId, request, cancellation, completed);
            } finally {
                heartbeatSupport.stop(heartbeat);
            }
        });
        return emitter;
    }

    private void streamCreateInternal(RuntimeDebugSessionOwner owner, SseEmitter emitter,
                                      CreateRequest request,
                                      RuntimeGraphSpecExecutionCancellation cancellation,
                                      AtomicBoolean completed) {
        try {
            runStreamCreate(owner, emitterSink(emitter), request, cancellation);
            emitter.complete();
        } catch (Exception ex) {
            // 仅「未取消且未完成」的真实异常才能包装为 stream error；
            // 已取消 / 已完成时禁止再写 turn.failed（含取消后执行抛错、浏览器断开写失败）。
            if (shouldCompleteStreamError(cancellation.isCancelled(), completed.get())) {
                completeStreamError(emitter, ex);
            }
        }
    }

    private void streamSubmitInternal(RuntimeDebugSessionOwner owner, SseEmitter emitter,
                                      String sessionId,
                                      SubmitRequest request,
                                      RuntimeGraphSpecExecutionCancellation cancellation,
                                      AtomicBoolean completed) {
        try {
            runStreamSubmit(owner, emitterSink(emitter), sessionId, request, cancellation);
            emitter.complete();
        } catch (Exception ex) {
            if (shouldCompleteStreamError(cancellation.isCancelled(), completed.get())) {
                completeStreamError(emitter, ex);
            }
        }
    }

    /**
     * 生产路径核心：create 流业务事件序列（不含 emitter.complete）。
     * 包可见供单测直接断言唯一业务终态，不经旁路辅助函数。
     */
    void runStreamCreate(RuntimeDebugSessionOwner owner, DebugSessionSseSink rawSink,
                         CreateRequest request,
                         RuntimeGraphSpecExecutionCancellation cancellation) throws Exception {
        AtomicBoolean businessTerminalSent = new AtomicBoolean(false);
        DebugSessionSseSink sink = onceBusinessTerminalSink(rawSink, businessTerminalSent);
        sink.send("turn.started", Map.of("phase", "create"));
        SessionView view = create(owner, request, liveExecutionSink(sink), cancellation, admitted -> {
            try {
                sink.send("session.created", admitted);
            } catch (IOException | RuntimeException failure) {
                cancellation.cancel();
                throw new IllegalStateException("Failed to deliver debug session admission", failure);
            }
        });
        // 节点事件已在执行中实时发出；此处只补尚未发出的业务终态，禁止按 steps 重放
        emitTerminalSessionEvents(view, sink);
        // session.completed 仅为流收尾信封，不是业务成功终态
        rawSink.send("session.completed", view);
    }

    /**
     * 生产路径核心：submit 流业务事件序列（不含 emitter.complete）。
     */
    void runStreamSubmit(RuntimeDebugSessionOwner owner, DebugSessionSseSink rawSink,
                         String sessionId,
                         SubmitRequest request,
                         RuntimeGraphSpecExecutionCancellation cancellation) throws Exception {
        AtomicBoolean businessTerminalSent = new AtomicBoolean(false);
        DebugSessionSseSink sink = onceBusinessTerminalSink(rawSink, businessTerminalSent);
        sink.send("turn.started", Map.of("phase", "submit", "sessionId", sessionId));
        String action = firstText(request == null ? null : request.action(), "submit");
        SessionView view;
        if ("cancel".equalsIgnoreCase(action)) {
            view = submit(owner, sessionId, request);
        } else {
            view = submit(owner, sessionId, request, liveExecutionSink(sink), cancellation);
        }
        emitTerminalSessionEvents(view, sink);
        rawSink.send("session.completed", view);
    }

    private DebugSessionSseSink emitterSink(SseEmitter emitter) {
        return (eventName, data) -> sendEvent(emitter, eventName, data);
    }

    /**
     * 一轮 Debug turn 只允许一个业务终态事件：
     * turn.completed / turn.waiting / turn.cancelled / turn.failed。
     */
    static DebugSessionSseSink onceBusinessTerminalSink(DebugSessionSseSink delegate,
                                                        AtomicBoolean businessTerminalSent) {
        return (eventName, data) -> {
            if (isBusinessTerminalEvent(eventName)
                    && !businessTerminalSent.compareAndSet(false, true)) {
                return;
            }
            delegate.send(eventName, data);
        };
    }

    static boolean isBusinessTerminalEvent(String eventName) {
        return "turn.completed".equals(eventName)
                || "turn.waiting".equals(eventName)
                || "turn.cancelled".equals(eventName)
                || "turn.failed".equals(eventName);
    }

    /**
     * 流 catch 是否允许调用 completeStreamError。
     * 已取消或流已完成时禁止再写错误终态。
     */
    static boolean shouldCompleteStreamError(boolean cancelled, boolean completed) {
        return !cancelled && !completed;
    }

    /** 包可见：单测直接断言安全最终输出的 node.output.delta + message.delta 各一次。 */
    RuntimeGraphSpecExecutionEventSink liveExecutionSink(DebugSessionSseSink sink) {
        return new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onExecutionEvent(RuntimeExecutionEvent event) {
                // Additive V1 channel for new consumers. Legacy node.* callbacks stay unchanged
                // during rolling upgrades and public message deltas are never inferred here.
                sendQuietly(sink, "runtime.execution.v1", event);
            }

            @Override
            public void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                sendQuietly(sink, "node.started", safePayload);
            }

            @Override
            public void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
                Map<String, Object> payload = new LinkedHashMap<>(safePayload == null ? Map.of() : safePayload);
                payload.put("text", text == null ? "" : text);
                sendQuietly(sink, "node.output.delta", payload);
                // 仅安全最终输出节点会调用 onNodeDelta；同步映射为公共 message.delta
                if (Boolean.TRUE.equals(payload.get("publicUserOutput")) && StringUtils.hasText(text)) {
                    sendQuietly(sink, "message.delta", Map.of("text", text));
                }
            }

            @Override
            public void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                sendQuietly(sink, "node.completed", safePayload);
            }

            @Override
            public void onNodeWaiting(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                sendQuietly(sink, "node.waiting", safePayload);
            }

            @Override
            public void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                sendQuietly(sink, "node.failed", safePayload);
            }

            @Override
            public void onExecutionCancelled(Map<String, Object> safePayload) {
                sendQuietly(sink, "turn.cancelled", safePayload == null ? Map.of() : safePayload);
            }
        };
    }

    private void sendQuietly(DebugSessionSseSink sink, String eventName, Object data) {
        try {
            sink.send(eventName, data);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to emit debug SSE event: " + eventName, ex);
        }
    }

    /**
     * 生产路径与单测共用的 SSE 写出接口：streamCreate/streamSubmit 的 emitSessionEvents 必须走此 sink。
     */
    @FunctionalInterface
    interface DebugSessionSseSink {
        void send(String eventName, Object data) throws IOException;
    }

    /**
     * 将会话终态写为 SSE 事件。包内可见，供测试捕获真实事件名与 payload（非辅助函数旁路）。
     * <p>
     * 注意：流式路径已在执行中实时发出 node.*，此处默认只发终态，避免结束后重放重复节点。
     * 兼容旧测试可传 {@code replayNodes=true}。
     */
    void emitSessionEvents(SessionView view, DebugSessionSseSink sink) throws IOException {
        emitSessionEvents(view, sink, false);
    }

    void emitSessionEvents(SessionView view, DebugSessionSseSink sink, boolean replayNodes) throws IOException {
        if (replayNodes) {
            List<RuntimeWorkflowDebugService.DebugStepResult> steps =
                    view.steps() == null ? List.of() : view.steps();
            for (RuntimeWorkflowDebugService.DebugStepResult step : steps) {
                Map<String, Object> nodePayload = new LinkedHashMap<>();
                putIfText(nodePayload, "nodeId", step.nodeId());
                putIfText(nodePayload, "nodeType", step.nodeType());
                putIfText(nodePayload, "nodeName", step.nodeName());
                putIfText(nodePayload, "status", step.status());
                sink.send("node.started", nodePayload);
                String nodeEvent = nodeEventName(step.status());
                sink.send(nodeEvent, nodePayload);
            }
        }
        emitTerminalSessionEvents(view, sink);
    }

    void emitTerminalSessionEvents(SessionView view, DebugSessionSseSink sink) throws IOException {
        String status = normalizeStatus(view.status());
        if (RuntimeDebugSessionStatus.SUSPENDED.name().equals(status)) {
            sink.send("turn.waiting", Map.of(
                    "sessionId", view.sessionId(),
                    "currentNodeId", firstText(view.currentNodeId(), ""),
                    "uiRequest", view.uiRequest()));
            if (view.uiRequest() != null) {
                sink.send("ui.requested", view.uiRequest());
            }
            return;
        }
        String terminalEvent = terminalSseEventName(status);
        if ("turn.failed".equals(terminalEvent)) {
            sink.send("turn.failed", Map.of(
                    "sessionId", view.sessionId(),
                    "status", status,
                    "answer", firstText(view.answer(), ""),
                    "message", firstText(view.answer(), "Workflow 调试失败")));
            return;
        }
        if ("turn.cancelled".equals(terminalEvent)) {
            sink.send("turn.cancelled", Map.of(
                    "sessionId", view.sessionId(),
                    "status", "CANCELLED",
                    "answer", firstText(view.answer(), ""),
                    "message", "debug session cancelled"));
            return;
        }
        sink.send("turn.completed", Map.of(
                "sessionId", view.sessionId(),
                "status", status,
                "answer", firstText(view.answer(), "")));
    }

    /**
     * FAILED/EXPIRED → turn.failed；CANCELLED → turn.cancelled；COMPLETED → turn.completed。
     */
    static String terminalSseEventName(String status) {
        RuntimeDebugSessionStatus normalized = RuntimeDebugSessionStatus.parse(status);
        if (normalized == RuntimeDebugSessionStatus.FAILED || normalized == RuntimeDebugSessionStatus.EXPIRED) {
            return "turn.failed";
        }
        if (normalized == RuntimeDebugSessionStatus.CANCELLED) {
            return "turn.cancelled";
        }
        return "turn.completed";
    }
    private String nodeEventName(String status) {
        if (!StringUtils.hasText(status)) {
            return "node.completed";
        }
        return switch (status.trim().toUpperCase()) {
            case "WAITING", "WAITING_USER" -> "node.waiting";
            case "ERROR", "FAILED" -> "node.failed";
            default -> "node.completed";
        };
    }

    private void completeStreamError(SseEmitter emitter, Exception ex) {
        try {
            sendEvent(emitter, "turn.failed", Map.of(
                    "code", "DEBUG_SESSION_STREAM_FAILED",
                    "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(ex);
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) throws IOException {
        synchronized (emitter) {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        }
    }

    private void putIfText(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value);
        }
    }

    private Map<String, Object> executionOptions(Map<String, Object> supplied) {
        Map<String, Object> options = new LinkedHashMap<>(supplied == null ? Map.of() : supplied);
        for (String reserved : List.of("runId", "traceId", "sessionId", "entryNodeId", "submittedPayload", "submitLock")) {
            options.remove(reserved);
        }
        return options;
    }

    private RuntimeWorkflowDebugService.DebugRunRequest debugRunRequest(Map<String, Object> workingCopy) {
        return new RuntimeWorkflowDebugService.DebugRunRequest(
                text(workingCopy.get("workflowId")),
                text(workingCopy.get("workflowKeySlug")),
                text(workingCopy.get("workflowName")),
                text(workingCopy.get("workflowKind")),
                text(workingCopy.get("projectCode")),
                text(workingCopy.get("executionEngine")),
                text(workingCopy.get("modelInstanceId")),
                graphSpecJson(workingCopy),
                text(workingCopy.get("canvasJson")),
                null, Map.of(), Map.of());
    }

    /** Only the persisted server snapshot supplies projectId; create requests cannot set it. */
    private RuntimeWorkflowDebugService.DebugDefinition restoreSessionDefinition(Map<String, Object> workingCopy) {
        var stored = debugRunRequest(workingCopy);
        Object projectId = workingCopy.get("projectId");
        return new RuntimeWorkflowDebugService.DebugDefinition(stored.workflowId(), stored.workflowKeySlug(),
                stored.workflowName(), stored.workflowKind(), projectId == null ? null : Long.valueOf(projectId.toString()),
                stored.projectCode(), stored.executionEngine(), stored.modelInstanceId(),
                stored.graphSpecJson(), stored.canvasJson());
    }

    private String graphSpecJson(Map<String, Object> workingCopy) {
        Object graphSpecJson = workingCopy.get("graphSpecJson");
        if (graphSpecJson instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        Object graphSpec = workingCopy.get("graphSpec");
        if (graphSpec instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        if (graphSpec != null) {
            return writeJson(graphSpec);
        }
        return null;
    }

    private void appendRuntimeMessage(List<MessageView> messages, RuntimeWorkflowDebugService.DebugRunResult run) {
        if (run == null) {
            return;
        }
        String status = debugStatusFromExecution(run.status());
        if (RuntimeDebugSessionStatus.SUSPENDED.name().equals(status)) {
            messages.add(message("assistant",
                    firstText(run.answer(), run.errorMessage(), "Waiting for user input"),
                    run.currentNodeId(),
                    run.traceId(),
                    run.uiRequest()));
            return;
        }
        messages.add(message(run.success() ? "assistant" : "system",
                firstText(run.answer(), run.errorMessage(), run.success() ? "debug completed" : "debug failed"),
                run.currentNodeId(),
                run.traceId(),
                null));
    }

    private SessionView toView(RuntimeExecutableDebugSessionEntity entity) {
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        String status = normalizeStatus(entity.getStatus());
        Map<String, Object> definition = readMap(entity.getWorkingCopyDefinitionJson());
        String projectId = text(definition.get("projectId"));
        String answer = ("FAILED".equals(status) || "EXPIRED".equals(status))
                ? firstText(text(readMap(entity.getResultJson()).get("answer")), lastAssistantContent(messages))
                : lastAssistantContent(messages);
        return new SessionView(
                entity.getId(),
                entity.getRunId(),
                entity.getTraceId(),
                entity.getTargetType(),
                statusIsSuccessful(status),
                status,
                entity.getCurrentNodeId(),
                answer,
                messages,
                readSteps(entity.getStepsJson()),
                readMap(entity.getStateSnapshotJson()),
                readObject(entity.getUiRequestJson()),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getExpiresAt(),
                projectId == null ? null : Long.valueOf(projectId), text(definition.get("projectCode")));
    }

    private RuntimeExecutableDebugSessionEntity requireSession(RuntimeDebugSessionOwner owner, String sessionId) {
        return store.requireOwned(owner, sessionId);
    }

    private SessionView completeExecution(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity entity,
                                          RuntimeWorkflowDebugService.DebugRunResult run,
                                          List<MessageView> messages,
                                          List<RuntimeWorkflowDebugService.DebugStepResult> steps,
                                          Map<String, Object> options, String expectedStatus, int revision) {
        appendRuntimeMessage(messages, run);
        steps.addAll(run.steps() == null ? List.of() : run.steps());
        String nextStatus = debugStatusFromExecution(run.status());
        Map<String, Object> state = new LinkedHashMap<>(run.stateSnapshot() == null ? Map.of() : run.stateSnapshot());
        state.remove("submittedPayload");
        state.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        entity.setStatus(nextStatus);
        entity.setCurrentNodeId(run.currentNodeId());
        entity.setStateSnapshotJson(writeJson(state));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(steps));
        entity.setUiRequestJson(writeJson(run.uiRequest()));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setResultJson(writeJson(Map.of("code", firstText(run.errorCode(), nextStatus),
                "answer", nullToEmpty(run.answer()), "status", nextStatus)));
        entity.setUpdateTime(LocalDateTime.now());
        try {
            store.stageCompletion(owner, entity, expectedStatus, revision);
            return toView(requireSession(owner, entity.getId()));
        } catch (RuntimeException pending) {
            throw new IllegalStateException("DEBUG_SESSION_COMPLETION_PENDING: sessionId=" + entity.getId()
                    + "; recover this session without repeating execution", pending);
        }
    }

    private void recordExecutionFailure(RuntimeDebugSessionOwner owner, RuntimeExecutableDebugSessionEntity entity, String expectedStatus, int revision,
                                         RuntimeGraphSpecExecutionCancellation cancellation, RuntimeException failure) {
        // Execution may have produced side effects before throwing. Never reopen this checkpoint for another attempt.
        try {
            boolean cancelled = cancellation != null && cancellation.isCancelled();
            String status = cancelled ? "CANCELLED" : "FAILED";
            String code = cancelled ? "RUNTIME_GRAPH_CANCELLED" : "DEBUG_EXECUTION_OUTCOME_UNKNOWN";
            String summary = cancelled ? "Debug execution cancelled" : "Debug execution interrupted; outcome is unknown";
            var messages = readMessages(entity.getMessagesJson());
            messages.add(message("system", summary, entity.getCurrentNodeId(), entity.getTraceId(), null));
            entity.setStatus(status);
            entity.setUiRequestJson(null);
            entity.setMessagesJson(writeJson(messages));
            entity.setResultJson(writeJson(Map.of("code", code, "answer", summary, "status", status)));
            entity.setUpdateTime(LocalDateTime.now());
            store.stageCompletion(owner, entity, expectedStatus, revision);
            requireSession(owner, entity.getId());
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private Map<String, Object> canonicalSubmitPayload(String action,
                                                       Map<String, Object> values,
                                                       String interactionId,
                                                       String nodeId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", firstText(action, "submit"));
        payload.put("values", values == null ? Map.of() : values);
        payload.put("interactionId", nullToEmpty(interactionId));
        payload.put("nodeId", nullToEmpty(nodeId));
        return payload;
    }

    private MessageView message(String role, String content, String nodeId, String traceId, Object uiRequest) {
        return new MessageView(
                UUID.randomUUID().toString(),
                role,
                nullToEmpty(content),
                nodeId,
                traceId,
                uiRequest,
                LocalDateTime.now());
    }

    private String submitMessage(SubmitRequest request, Map<String, Object> submitted) {
        if (request != null && StringUtils.hasText(request.message())) {
            return request.message();
        }
        return firstText(request == null ? null : request.action(), "submit") + " " + submitted;
    }

    private String lastAssistantContent(List<MessageView> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageView message = messages.get(i);
            if ("assistant".equalsIgnoreCase(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private List<MessageView> readMessages(String json) {
        return readList(json, MESSAGE_LIST_TYPE);
    }

    private List<RuntimeWorkflowDebugService.DebugStepResult> readSteps(String json) {
        return readList(json, STEP_LIST_TYPE);
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            if (!StringUtils.hasText(json)) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(json, type));
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private Object readObject(String json) {
        try {
            if (!StringUtils.hasText(json) || "null".equals(json)) {
                return null;
            }
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json parse failed: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("debug session json serialization failed: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
        }
        return out;
    }

    private static String normalizeStatus(String status) {
        return RuntimeDebugSessionStatus.parse(status).name();
    }

    private static String debugStatusFromExecution(String status) {
        return RuntimeDebugSessionStatus.fromExecutionStatus(WorkflowExecutionStatus.parse(status)).name();
    }

    private static String requireTargetType(String targetType) {
        if (!StringUtils.hasText(targetType) || !TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("Unsupported debug targetType: " + targetType);
        }
        return targetType;
    }

    private static boolean statusIsSuccessful(String status) {
        RuntimeDebugSessionStatus normalized = RuntimeDebugSessionStatus.parse(status);
        return normalized != RuntimeDebugSessionStatus.FAILED
                && normalized != RuntimeDebugSessionStatus.CANCELLED
                && normalized != RuntimeDebugSessionStatus.EXPIRED;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public record CreateRequest(String targetType,
                                Map<String, Object> workingCopyDefinition,
                                String message,
                                Map<String, Object> inputParams,
                                Map<String, Object> debugOptions,
                                String idempotencyKey) {
        public CreateRequest(String targetType, Map<String, Object> workingCopyDefinition,
                             String message, Map<String, Object> inputParams, Map<String, Object> debugOptions) {
            this(targetType, workingCopyDefinition, message, inputParams, debugOptions, null);
        }
    }

    public record SubmitRequest(String action,
                                Map<String, Object> values,
                                String message,
                                String interactionId,
                                String idempotencyKey) {
        public SubmitRequest(String action, Map<String, Object> values, String message) {
            this(action, values, message, null, null);
        }
    }

    public record MessageView(String id,
                              String role,
                              String content,
                              String nodeId,
                              String traceId,
                              Object uiRequest,
                              LocalDateTime createdAt) {
    }

    public record SessionView(String sessionId,
                              String runId,
                              String traceId,
                              String targetType,
                              boolean success,
                              String status,
                              String currentNodeId,
                              String answer,
                              List<MessageView> messages,
                              List<RuntimeWorkflowDebugService.DebugStepResult> steps,
                              Map<String, Object> stateSnapshot,
                              Object uiRequest,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              LocalDateTime expiresAt,
                              Long projectId,
                              String projectCode) {
    }
}
