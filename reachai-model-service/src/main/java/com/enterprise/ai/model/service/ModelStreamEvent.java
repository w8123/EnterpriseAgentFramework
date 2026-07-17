package com.enterprise.ai.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化模型流事件，由 {@code POST /model/chat/stream/events} 输出。
 * 旧 {@code /model/chat/stream} 文本流已删除，不再提供兼容入口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelStreamEvent {

    public static final String CONTENT_DELTA = "content.delta";
    public static final String REASONING_DELTA = "reasoning.delta";
    public static final String TOOL_CALL_DELTA = "tool_call.delta";
    public static final String USAGE = "usage";
    public static final String COMPLETED = "completed";
    public static final String ERROR = "error";

    /** 供应商 SSE 提前 EOF、无 [DONE] 且无非空 finish_reason */
    public static final String CODE_STREAM_INTERRUPTED = "MODEL_STREAM_INTERRUPTED";

    private String type;
    private String text;
    private ToolCallDelta toolCall;
    private ChatResponse.Usage usage;
    private String finishReason;
    private String message;
    /** 稳定业务错误码，如 MODEL_STREAM_INTERRUPTED；禁止靠 message contains 判断 */
    private String code;
    private JsonNode raw;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallDelta {
        private Integer index;
        private String id;
        private String type;
        private String name;
        private String arguments;
    }

    public static ModelStreamEvent contentDelta(String text) {
        return ModelStreamEvent.builder().type(CONTENT_DELTA).text(text).build();
    }

    public static ModelStreamEvent reasoningDelta(String text) {
        return ModelStreamEvent.builder().type(REASONING_DELTA).text(text).build();
    }

    public static ModelStreamEvent toolCallDelta(ToolCallDelta delta) {
        return ModelStreamEvent.builder().type(TOOL_CALL_DELTA).toolCall(delta).build();
    }

    public static ModelStreamEvent usage(ChatResponse.Usage usage) {
        return ModelStreamEvent.builder().type(USAGE).usage(usage).build();
    }

    public static ModelStreamEvent completed(String finishReason) {
        return ModelStreamEvent.builder().type(COMPLETED).finishReason(finishReason).build();
    }

    public static ModelStreamEvent error(String message) {
        return error(null, message);
    }

    public static ModelStreamEvent error(String code, String message) {
        return ModelStreamEvent.builder().type(ERROR).code(code).message(message).build();
    }

    public static ModelStreamEvent streamInterrupted() {
        return error(CODE_STREAM_INTERRUPTED, "模型流在返回最终结果前中断，请重试。");
    }
}
