package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.config.ToolCallLogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 包装 {@link Model}，在每次 {@code stream()}（ReAct 单轮推理）落库审计，供管理端 Trace 回放查看 LLM 入参/出参。
 */
@Slf4j
public final class TracingModel implements Model {

    private static final ThreadLocal<String> LAST_TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LLM_ROUND = new ThreadLocal<>();

    private final Model delegate;
    private final ToolCallLogService toolCallLogService;
    private final ToolCallLogProperties toolCallLogProperties;
    private final ObjectMapper objectMapper;

    public TracingModel(Model delegate,
                        ToolCallLogService toolCallLogService,
                        ToolCallLogProperties toolCallLogProperties,
                        ObjectMapper objectMapper) {
        this.delegate = Objects.requireNonNull(delegate);
        this.toolCallLogService = Objects.requireNonNull(toolCallLogService);
        this.toolCallLogProperties = Objects.requireNonNull(toolCallLogProperties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages,
                                       List<ToolSchema> toolSchemas,
                                       GenerateOptions options) {
        if (!toolCallLogProperties.isEnabled()) {
            return delegate.stream(messages, toolSchemas, options);
        }
        ToolExecutionContext ctx = ToolExecutionContextHolder.get();
        if (ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            return delegate.stream(messages, toolSchemas, options);
        }

        int round = nextRound(ctx.getTraceId());
        long started = System.currentTimeMillis();
        Map<String, Object> inputSnapshot = buildLlmInputSnapshot(messages, toolSchemas, options, round);

        List<ChatResponse> buffer = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> errorRef = new AtomicReference<>();

        return delegate.stream(messages, toolSchemas, options)
                .doOnNext(buffer::add)
                .doOnError(ex -> errorRef.set(ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage())))
                .doFinally(signal -> {
                    long elapsed = System.currentTimeMillis() - started;
                    try {
                        String err = errorRef.get();
                        if (err != null) {
                            Map<String, Object> partial = aggregateLlmOutput(buffer);
                            partial.put("error", err);
                            toolCallLogService.record(ctx, "_trace:llm.stream#" + round, inputSnapshot, partial,
                                    false, "LLM_STREAM", elapsed, null);
                        } else {
                            Map<String, Object> output = aggregateLlmOutput(buffer);
                            Integer tokens = extractTotalTokens(buffer);
                            toolCallLogService.record(ctx, "_trace:llm.stream#" + round, inputSnapshot, output,
                                    true, null, elapsed, tokens);
                        }
                    } catch (Exception ex) {
                        log.debug("[TracingModel] trace 落库失败（忽略）: {}", ex.toString());
                    }
                });
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    private static int nextRound(String traceId) {
        if (!Objects.equals(traceId, LAST_TRACE_ID.get())) {
            LAST_TRACE_ID.set(traceId);
            LLM_ROUND.set(0);
        }
        int n = LLM_ROUND.get() + 1;
        LLM_ROUND.set(n);
        return n;
    }

    private Map<String, Object> buildLlmInputSnapshot(List<Msg> messages,
                                                      List<ToolSchema> toolSchemas,
                                                      GenerateOptions options,
                                                      int round) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reactRound", round);
        m.put("delegateModelName", delegate.getModelName());
        if (options != null) {
            Map<String, Object> gen = new LinkedHashMap<>();
            gen.put("modelName", options.getModelName());
            gen.put("temperature", options.getTemperature());
            gen.put("topP", options.getTopP());
            gen.put("maxTokens", options.getMaxTokens());
            gen.put("stream", options.getStream());
            m.put("generateOptions", gen);
        }
        m.put("messages", summarizeMessages(messages));
        m.put("tools", summarizeTools(toolSchemas));
        return m;
    }

    private List<Map<String, Object>> summarizeMessages(List<Msg> messages) {
        if (messages == null) {
            return List.of();
        }
        int cap = 40;
        List<Map<String, Object>> rows = new ArrayList<>();
        int from = Math.max(0, messages.size() - cap);
        for (int i = from; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("role", msg.getRole() == null ? null : msg.getRole().name());
            row.put("name", msg.getName());
            row.put("text", truncate(msg.getTextContent(), toolCallLogProperties.getArgsMaxChars() / Math.max(4, messages.size())));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> summarizeTools(List<ToolSchema> toolSchemas) {
        if (toolSchemas == null || toolSchemas.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolSchema ts : toolSchemas) {
            if (ts == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", ts.getName());
            row.put("description", truncate(ts.getDescription(), 500));
            row.put("strict", ts.getStrict());
            putParametersSnapshot(row, ts);
            list.add(row);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private void putParametersSnapshot(Map<String, Object> row, ToolSchema ts) {
        Map<?, ?> raw = ts.getParameters();
        if (raw == null || raw.isEmpty()) {
            row.put("parameters", Map.of());
            return;
        }
        Map<String, Object> params = (Map<String, Object>) (Map<?, ?>) raw;
        int max = Math.max(0, toolCallLogProperties.getToolParametersSnapshotMaxChars());
        try {
            String json = objectMapper.writeValueAsString(params);
            if (max <= 0 || json.length() <= max) {
                row.put("parameters", params);
            } else {
                Map<String, Object> truncated = new LinkedHashMap<>();
                truncated.put("_truncated", true);
                truncated.put("preview", truncate(json, max));
                row.put("parameters", truncated);
            }
        } catch (Exception ex) {
            row.put("parameters", Map.of("_serializationError", ex.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> aggregateLlmOutput(List<ChatResponse> chunks) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (chunks == null || chunks.isEmpty()) {
            out.put("assistantText", "");
            out.put("toolCallsPlanned", List.of());
            return out;
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        String finishReason = null;
        for (ChatResponse r : chunks) {
            if (r.getFinishReason() != null) {
                finishReason = r.getFinishReason();
            }
            if (r.getContent() == null) {
                continue;
            }
            for (ContentBlock b : r.getContent()) {
                if (b instanceof TextBlock tb) {
                    if (tb.getText() != null) {
                        text.append(tb.getText());
                    }
                } else if (b instanceof ToolUseBlock tub) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", tub.getId());
                    tc.put("name", tub.getName());
                    tc.put("input", tub.getInput());
                    toolCalls.add(tc);
                }
            }
        }
        out.put("assistantText", truncate(text.toString(), toolCallLogProperties.getResultMaxChars()));
        out.put("toolCallsPlanned", toolCalls);
        out.put("finishReason", finishReason);
        out.put("chunkCount", chunks.size());
        return out;
    }

    private static Integer extractTotalTokens(List<ChatResponse> chunks) {
        if (chunks == null) {
            return null;
        }
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ChatUsage u = chunks.get(i).getUsage();
            if (u != null) {
                int t = u.getTotalTokens();
                return t > 0 ? t : (u.getInputTokens() + u.getOutputTokens());
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }

    private static String safeMsg(String m) {
        return m == null ? "" : m;
    }
}
