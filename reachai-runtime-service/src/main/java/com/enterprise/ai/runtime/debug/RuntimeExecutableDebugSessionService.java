package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RuntimeExecutableDebugSessionService {

    private static final long DEBUG_STREAM_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 8_000L;

    private static final String REQUEST_PARAMS = "__requestParams";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<MessageView>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RuntimeWorkflowDebugService.DebugStepResult>> STEP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final RuntimeExecutableDebugSessionMapper mapper;
    private final RuntimeWorkflowDebugService workflowDebugService;
    private final ObjectMapper objectMapper;
    private final SseHeartbeatSupport heartbeatSupport;
    private final long debugStreamHeartbeatIntervalMs;

    @Autowired
    public RuntimeExecutableDebugSessionService(RuntimeExecutableDebugSessionMapper mapper,
                                                 RuntimeWorkflowDebugService workflowDebugService,
                                                ObjectMapper objectMapper,
                                                SseHeartbeatSupport heartbeatSupport,
                                                @Value("${reachai.runtime.debug-stream.heartbeat-interval-ms:8000}")
                                                long debugStreamHeartbeatIntervalMs) {
        this.mapper = mapper;
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
                                                ObjectMapper objectMapper) {
        this(mapper, workflowDebugService, objectMapper, new SseHeartbeatSupport(), DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    public SessionView create(CreateRequest request) {
        return create(request, RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public SessionView create(CreateRequest request,
                              RuntimeGraphSpecExecutionEventSink eventSink,
                              RuntimeGraphSpecExecutionCancellation cancellation) {
        if (request == null || request.draftDefinition() == null || request.draftDefinition().isEmpty()) {
            throw new IllegalArgumentException("draftDefinition is required");
        }
        String sessionId = UUID.randomUUID().toString();
        String runId = "studio-debug-session-" + sessionId;
        Map<String, Object> options = new LinkedHashMap<>(request.debugOptions() == null ? Map.of() : request.debugOptions());
        options.put("runId", runId);
        options.put("traceId", runId);
        options.put("sessionId", sessionId);

        RuntimeWorkflowDebugService.DebugRunResult run = workflowDebugService.debugRun(debugRunRequest(
                request.targetType(),
                request.draftDefinition(),
                nullToEmpty(request.message()),
                request.inputParams() == null ? Map.of() : request.inputParams(),
                options), eventSink, cancellation);

        List<MessageView> messages = new ArrayList<>();
        if (StringUtils.hasText(request.message())) {
            messages.add(message("user", request.message(), null, run.traceId(), null));
        }
        appendRuntimeMessage(messages, run);

        LocalDateTime now = LocalDateTime.now();
        RuntimeExecutableDebugSessionEntity entity = new RuntimeExecutableDebugSessionEntity();
        entity.setId(sessionId);
        entity.setRunId(firstText(run.runId(), runId));
        entity.setTraceId(firstText(run.traceId(), runId));
        entity.setTargetType(firstText(request.targetType(), "WORKFLOW_DRAFT"));
        entity.setStatus(normalizeStatus(run.status()));
        entity.setRevision(0);
        entity.setCurrentNodeId(run.currentNodeId());
        entity.setDraftDefinitionJson(writeJson(request.draftDefinition()));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setStateJson(writeJson(run.finalState()));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(run.steps()));
        entity.setUiRequestJson(writeJson(run.uiRequest()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setExpiresAt(now.plusHours(24));
        mapper.insert(entity);
        return toView(entity);
    }

    public SessionView get(String sessionId) {
        return toView(requireSession(sessionId));
    }

    public SessionView submit(String sessionId, SubmitRequest request) {
        return submit(sessionId, request,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public SessionView submit(String sessionId,
                              SubmitRequest request,
                              RuntimeGraphSpecExecutionEventSink eventSink,
                              RuntimeGraphSpecExecutionCancellation cancellation) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(sessionId);
        String status = normalizeStatus(entity.getStatus());
        Map<String, Object> submitted = request == null || request.values() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.values());
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        String submittedCanonical = writeJson(canonicalSubmitPayload(
                firstText(request == null ? null : request.action(), "submit"),
                submitted,
                request == null ? null : request.interactionId(),
                entity.getCurrentNodeId()));

        if (!"WAITING".equalsIgnoreCase(status)) {
            if (StringUtils.hasText(idempotencyKey)
                    && idempotencyKey.equals(entity.getIdempotencyKey())
                    && submittedCanonical.equals(entity.getSubmittedPayloadJson())) {
                return toView(entity);
            }
            if (StringUtils.hasText(idempotencyKey)
                    && idempotencyKey.equals(entity.getIdempotencyKey())
                    && !submittedCanonical.equals(nullToEmpty(entity.getSubmittedPayloadJson()))) {
                throw new IllegalStateException("duplicate debug submit with different payload: " + sessionId);
            }
            throw new IllegalArgumentException("debug session is not waiting: " + sessionId);
        }
        String action = firstText(request == null ? null : request.action(), "submit");
        if ("cancel".equalsIgnoreCase(action)) {
            return cancel(sessionId);
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

        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(entity.getIdempotencyKey())
                && submittedCanonical.equals(entity.getSubmittedPayloadJson())) {
            return toView(entity);
        }
        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(entity.getIdempotencyKey())
                && StringUtils.hasText(entity.getSubmittedPayloadJson())
                && !submittedCanonical.equals(entity.getSubmittedPayloadJson())) {
            throw new IllegalStateException("duplicate debug submit with different payload: " + sessionId);
        }

        // Atomic CAS: WAITING + revision -> RESUMING
        if (!claimDebugResuming(entity, idempotencyKey, submittedCanonical)) {
            RuntimeExecutableDebugSessionEntity latest = requireSession(sessionId);
            if (StringUtils.hasText(idempotencyKey)
                    && idempotencyKey.equals(latest.getIdempotencyKey())
                    && submittedCanonical.equals(latest.getSubmittedPayloadJson())) {
                return toView(latest);
            }
            if ("RESUMING".equalsIgnoreCase(normalizeStatus(latest.getStatus()))) {
                throw new IllegalStateException("debug submit in progress: " + sessionId);
            }
            throw new IllegalStateException("concurrent debug submit rejected: " + sessionId);
        }

        Map<String, Object> draft = readMap(entity.getDraftDefinitionJson());
        Map<String, Object> state = readMap(entity.getStateJson());
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("interactionId", firstText(requestInteractionId, waitingInteractionId));
        resume.put("nodeId", entity.getCurrentNodeId());
        resume.put("action", action);
        resume.put("values", submitted);
        if (StringUtils.hasText(idempotencyKey)) {
            resume.put("idempotencyKey", idempotencyKey);
        }
        Map<String, Object> params = new LinkedHashMap<>(asMap(state.get(REQUEST_PARAMS)));
        params.putAll(submitted);
        state.put(REQUEST_PARAMS, params);
        state.put("params", params);
        state.put(WorkflowInteractionCodes.RESUME_CONTEXT_KEY, resume);
        state.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY,
                firstText(requestInteractionId, waitingInteractionId));
        state.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, entity.getCurrentNodeId());
        // 禁止全局 submittedPayload 泄漏到后续 INTERACTION
        state.remove("submittedPayload");
        if (request != null && StringUtils.hasText(request.message())) {
            state.put("message", request.message());
            state.put("input", request.message());
        }

        Map<String, Object> options = readMap(entity.getDebugOptionsJson());
        options.put("entryNodeId", entity.getCurrentNodeId());
        options.put("runId", entity.getRunId());
        options.put("traceId", entity.getTraceId());
        options.put("sessionId", entity.getId());
        options.remove("submittedPayload");
        options.remove("submitLock");

        RuntimeWorkflowDebugService.DebugRunResult run;
        try {
            run = workflowDebugService.debugRun(debugRunRequest(
                    entity.getTargetType(),
                    draft,
                    request == null ? "" : nullToEmpty(request.message()),
                    state,
                    options), eventSink, cancellation);
        } catch (RuntimeException ex) {
            rollbackDebugWaiting(entity);
            throw ex;
        }

        List<MessageView> messages = readMessages(entity.getMessagesJson());
        messages.add(message("user", submitMessage(request, submitted), entity.getCurrentNodeId(), entity.getTraceId(), null));
        appendRuntimeMessage(messages, run);
        List<RuntimeWorkflowDebugService.DebugStepResult> steps = readSteps(entity.getStepsJson());
        steps.addAll(run.steps() == null ? List.of() : run.steps());

        // 校验失败仍 WAITING：保留原 interactionId / currentNodeId
        String nextStatus = normalizeStatus(run.status());
        entity.setStatus(nextStatus);
        entity.setCurrentNodeId(run.currentNodeId());
        Map<String, Object> finalState = run.finalState() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(run.finalState());
        finalState.remove("submittedPayload");
        finalState.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        entity.setStateJson(writeJson(finalState));
        entity.setMessagesJson(writeJson(messages));
        entity.setStepsJson(writeJson(steps));
        entity.setUiRequestJson(writeJson(run.uiRequest()));
        entity.setDebugOptionsJson(writeJson(options));
        entity.setResultJson(writeJson(Map.of(
                "code", firstText(run.errorCode(), nextStatus),
                "answer", nullToEmpty(run.answer()),
                "status", nextStatus)));
        if (StringUtils.hasText(idempotencyKey)) {
            entity.setIdempotencyKey(idempotencyKey);
        }
        entity.setSubmittedPayloadJson(submittedCanonical);
        entity.setRevision((entity.getRevision() == null ? 0 : entity.getRevision()) + 1);
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        return toView(entity);
    }

    public SessionView cancel(String sessionId) {
        RuntimeExecutableDebugSessionEntity entity = requireSession(sessionId);
        entity.setStatus("CANCELLED");
        entity.setCurrentNodeId(null);
        entity.setUiRequestJson(writeJson(null));
        entity.setUpdateTime(LocalDateTime.now());
        List<MessageView> messages = readMessages(entity.getMessagesJson());
        messages.add(message("system", "debug session cancelled", null, entity.getTraceId(), null));
        entity.setMessagesJson(writeJson(messages));
        mapper.updateById(entity);
        return toView(entity);
    }

    public SseEmitter streamCreate(CreateRequest request) {
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
                streamCreateInternal(emitter, request, cancellation, completed);
            } finally {
                heartbeatSupport.stop(heartbeat);
            }
        });
        return emitter;
    }

    public SseEmitter streamSubmit(String sessionId, SubmitRequest request) {
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
                streamSubmitInternal(emitter, sessionId, request, cancellation, completed);
            } finally {
                heartbeatSupport.stop(heartbeat);
            }
        });
        return emitter;
    }

    private void streamCreateInternal(SseEmitter emitter,
                                      CreateRequest request,
                                      RuntimeGraphSpecExecutionCancellation cancellation,
                                      AtomicBoolean completed) {
        try {
            runStreamCreate(emitterSink(emitter), request, cancellation);
            emitter.complete();
        } catch (Exception ex) {
            // 仅「未取消且未完成」的真实异常才能包装为 stream error；
            // 已取消 / 已完成时禁止再写 turn.failed（含取消后执行抛错、浏览器断开写失败）。
            if (shouldCompleteStreamError(cancellation.isCancelled(), completed.get())) {
                completeStreamError(emitter, ex);
            }
        }
    }

    private void streamSubmitInternal(SseEmitter emitter,
                                      String sessionId,
                                      SubmitRequest request,
                                      RuntimeGraphSpecExecutionCancellation cancellation,
                                      AtomicBoolean completed) {
        try {
            runStreamSubmit(emitterSink(emitter), sessionId, request, cancellation);
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
    void runStreamCreate(DebugSessionSseSink rawSink,
                         CreateRequest request,
                         RuntimeGraphSpecExecutionCancellation cancellation) throws Exception {
        AtomicBoolean businessTerminalSent = new AtomicBoolean(false);
        DebugSessionSseSink sink = onceBusinessTerminalSink(rawSink, businessTerminalSent);
        sink.send("turn.started", Map.of("phase", "create"));
        SessionView view = create(request, liveExecutionSink(sink), cancellation);
        // 节点事件已在执行中实时发出；此处只补尚未发出的业务终态，禁止按 steps 重放
        emitTerminalSessionEvents(view, sink);
        // session.completed 仅为流收尾信封，不是业务成功终态
        rawSink.send("session.completed", view);
    }

    /**
     * 生产路径核心：submit 流业务事件序列（不含 emitter.complete）。
     */
    void runStreamSubmit(DebugSessionSseSink rawSink,
                         String sessionId,
                         SubmitRequest request,
                         RuntimeGraphSpecExecutionCancellation cancellation) throws Exception {
        AtomicBoolean businessTerminalSent = new AtomicBoolean(false);
        DebugSessionSseSink sink = onceBusinessTerminalSink(rawSink, businessTerminalSent);
        sink.send("turn.started", Map.of("phase", "submit", "sessionId", sessionId));
        String action = firstText(request == null ? null : request.action(), "submit");
        SessionView view;
        if ("cancel".equalsIgnoreCase(action)) {
            view = submit(sessionId, request);
        } else {
            view = submit(sessionId, request, liveExecutionSink(sink), cancellation);
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
        if ("WAITING".equals(status)) {
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
     * ERROR/FAILED → turn.failed；CANCELLED → turn.cancelled；其它成功态 → turn.completed。
     */
    static String terminalSseEventName(String status) {
        if ("ERROR".equals(status) || "FAILED".equals(status)) {
            return "turn.failed";
        }
        if ("CANCELLED".equals(status)) {
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

    private RuntimeWorkflowDebugService.DebugRunRequest debugRunRequest(String targetType,
                                                                       Map<String, Object> draft,
                                                                       String message,
                                                                       Map<String, Object> inputParams,
                                                                       Map<String, Object> debugOptions) {
        return new RuntimeWorkflowDebugService.DebugRunRequest(
                text(draft.get("workflowId")),
                text(draft.get("workflowKeySlug")),
                text(draft.get("workflowName")),
                text(draft.get("workflowType")),
                text(draft.get("projectCode")),
                text(draft.get("runtimeType")),
                text(draft.get("modelInstanceId")),
                graphSpecJson(draft),
                text(draft.get("canvasJson")),
                message,
                inputParams,
                debugOptions);
    }

    private String graphSpecJson(Map<String, Object> draft) {
        Object graphSpecJson = draft.get("graphSpecJson");
        if (graphSpecJson instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        Object graphSpec = draft.get("graphSpec");
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
        String status = normalizeStatus(run.status());
        if ("WAITING".equals(status)) {
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
        return new SessionView(
                entity.getId(),
                entity.getRunId(),
                entity.getTraceId(),
                entity.getTargetType(),
                !"ERROR".equalsIgnoreCase(status) && !"CANCELLED".equalsIgnoreCase(status),
                status,
                entity.getCurrentNodeId(),
                lastAssistantContent(messages),
                messages,
                readSteps(entity.getStepsJson()),
                readMap(entity.getStateJson()),
                readObject(entity.getUiRequestJson()),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getExpiresAt());
    }

    private RuntimeExecutableDebugSessionEntity requireSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        RuntimeExecutableDebugSessionEntity entity = mapper.selectById(sessionId.trim());
        if (entity == null) {
            throw new IllegalArgumentException("debug session not found: " + sessionId);
        }
        return entity;
    }

    private boolean claimDebugResuming(RuntimeExecutableDebugSessionEntity entity,
                                       String idempotencyKey,
                                       String submittedCanonical) {
        RuntimeExecutableDebugSessionEntity latest = requireSession(entity.getId());
        int revision = latest.getRevision() == null ? 0 : latest.getRevision();
        UpdateWrapper<RuntimeExecutableDebugSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", latest.getId())
                .eq("status", "WAITING")
                .eq("revision", revision)
                .set("status", "RESUMING")
                .set("revision", revision + 1)
                .set("update_time", LocalDateTime.now());
        if (StringUtils.hasText(idempotencyKey)) {
            update.set("idempotency_key", idempotencyKey);
        }
        if (StringUtils.hasText(submittedCanonical)) {
            update.set("submitted_payload_json", submittedCanonical);
        }
        int rows = mapper.update(null, update);
        if (rows != 1) {
            return false;
        }
        entity.setStatus("RESUMING");
        entity.setRevision(revision + 1);
        if (StringUtils.hasText(idempotencyKey)) {
            entity.setIdempotencyKey(idempotencyKey);
        }
        entity.setSubmittedPayloadJson(submittedCanonical);
        return true;
    }

    private void rollbackDebugWaiting(RuntimeExecutableDebugSessionEntity entity) {
        UpdateWrapper<RuntimeExecutableDebugSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", entity.getId())
                .eq("status", "RESUMING")
                .set("status", "WAITING")
                .set("revision", (entity.getRevision() == null ? 0 : entity.getRevision()) + 1)
                .set("idempotency_key", null)
                .set("update_time", LocalDateTime.now());
        mapper.update(null, update);
        entity.setStatus("WAITING");
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
        if ("WAITING_USER".equalsIgnoreCase(status)) {
            return "WAITING";
        }
        return firstText(status, "ERROR").toUpperCase();
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
                                Map<String, Object> draftDefinition,
                                String message,
                                Map<String, Object> inputParams,
                                Map<String, Object> debugOptions) {
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
                              Map<String, Object> finalState,
                              Object uiRequest,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              LocalDateTime expiresAt) {
    }
}
