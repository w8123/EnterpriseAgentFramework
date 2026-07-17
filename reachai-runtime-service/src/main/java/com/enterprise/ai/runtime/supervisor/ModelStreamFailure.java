package com.enterprise.ai.runtime.supervisor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型流终态业务失败。用稳定 code 表达根因，禁止靠字符串 contains 判断。
 */
public final class ModelStreamFailure extends RuntimeException {

    public static final String MODEL_OUTPUT_TOKEN_LIMIT = "MODEL_OUTPUT_TOKEN_LIMIT";
    public static final String MODEL_CONTENT_FILTERED = "MODEL_CONTENT_FILTERED";
    public static final String MODEL_INSUFFICIENT_SYSTEM_RESOURCE = "MODEL_INSUFFICIENT_SYSTEM_RESOURCE";
    public static final String MODEL_EMPTY_RESPONSE = "MODEL_EMPTY_RESPONSE";
    public static final String MODEL_STREAM_INTERRUPTED = "MODEL_STREAM_INTERRUPTED";

    private final String code;
    private final String safeMessage;
    private final String finishReason;
    private final ModelStreamDiagnostics diagnostics;

    public ModelStreamFailure(String code,
                              String safeMessage,
                              String finishReason,
                              ModelStreamDiagnostics diagnostics) {
        super(safeMessage);
        this.code = code;
        this.safeMessage = safeMessage;
        this.finishReason = finishReason;
        this.diagnostics = diagnostics;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public String finishReason() {
        return finishReason;
    }

    public ModelStreamDiagnostics diagnostics() {
        return diagnostics;
    }

    public Map<String, Object> toSafeMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", code);
        if (finishReason != null) {
            metadata.put("finishReason", finishReason);
        }
        if (diagnostics != null) {
            metadata.putAll(diagnostics.toSafeMap());
        }
        return metadata;
    }

    public static ModelStreamFailure interrupted(ModelStreamDiagnostics diagnostics) {
        return new ModelStreamFailure(
                MODEL_STREAM_INTERRUPTED,
                "模型流在返回最终结果前中断，请重试。",
                diagnostics == null ? null : diagnostics.finishReason(),
                diagnostics);
    }

    /**
     * 空正文且无工具调用时，按 finishReason / 是否收到 completed 映射业务错误。
     */
    public static ModelStreamFailure emptyResponse(ModelStreamDiagnostics diagnostics) {
        String finishReason = diagnostics == null ? null : diagnostics.finishReason();
        String normalized = finishReason == null ? "" : finishReason.trim().toLowerCase(Locale.ROOT);

        if (diagnostics != null && diagnostics.consumedAnyEvent() && !diagnostics.completedReceived()) {
            return new ModelStreamFailure(
                    MODEL_STREAM_INTERRUPTED,
                    "模型流在返回最终结果前中断，请重试。",
                    finishReason,
                    diagnostics);
        }

        return switch (normalized) {
            case "length" -> new ModelStreamFailure(
                    MODEL_OUTPUT_TOKEN_LIMIT,
                    "模型在生成最终答案前达到最大输出长度，请提高最大输出或关闭思考模式后重试。",
                    finishReason,
                    diagnostics);
            case "content_filter" -> new ModelStreamFailure(
                    MODEL_CONTENT_FILTERED,
                    "模型响应被内容安全策略截断，请调整输入后重试。",
                    finishReason,
                    diagnostics);
            case "insufficient_system_resource" -> new ModelStreamFailure(
                    MODEL_INSUFFICIENT_SYSTEM_RESOURCE,
                    "模型服务当前资源不足，请稍后重试。",
                    finishReason,
                    diagnostics);
            default -> new ModelStreamFailure(
                    MODEL_EMPTY_RESPONSE,
                    "模型本轮未返回有效答案，请重试或检查模型兼容配置。",
                    finishReason,
                    diagnostics);
        };
    }

    public static ModelStreamFailure findIn(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ModelStreamFailure failure) {
                return failure;
            }
            current = current.getCause();
        }
        return null;
    }
}
