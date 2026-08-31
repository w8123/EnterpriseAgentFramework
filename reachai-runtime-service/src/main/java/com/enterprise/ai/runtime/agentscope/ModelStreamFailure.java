package com.enterprise.ai.runtime.agentscope;

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
    public static final String MODEL_PROVIDER_BALANCE_INSUFFICIENT =
            "MODEL_PROVIDER_BALANCE_INSUFFICIENT";
    public static final String MODEL_CONTEXT_LENGTH_EXCEEDED = "MODEL_CONTEXT_LENGTH_EXCEEDED";
    public static final String MODEL_PROVIDER_REQUEST_FAILED = "MODEL_PROVIDER_REQUEST_FAILED";

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
     * 将模型供应商错误转换为稳定、可操作且不泄露上游响应体的错误。
     * 结构化流已经收到供应商终态时，调用方不得再用同步请求重复消费额度。
     */
    public static ModelStreamFailure providerError(
            String upstreamCode,
            String upstreamMessage,
            ModelStreamDiagnostics diagnostics) {
        String combined = ((upstreamCode == null ? "" : upstreamCode) + " "
                + (upstreamMessage == null ? "" : upstreamMessage)).toLowerCase(Locale.ROOT);
        boolean balanceInsufficient = combined.contains("http 402")
                || combined.contains("insufficient balance")
                || combined.contains("insufficient_balance")
                || combined.contains("余额不足");
        if (balanceInsufficient) {
            return new ModelStreamFailure(
                    MODEL_PROVIDER_BALANCE_INSUFFICIENT,
                    "模型供应商余额不足，当前对话无法执行。请在 ReachAI 模型中心补充额度，或切换到测试通过的模型后重试。",
                    "provider_http_402",
                    diagnostics);
        }
        if (isContextLengthExceeded(combined)) {
            return new ModelStreamFailure(
                    MODEL_CONTEXT_LENGTH_EXCEEDED,
                    "当前会话上下文超过模型可接受长度，ReachAI 无法安全完成本轮请求。请缩短输入或清理会话后重试。",
                    "provider_context_length_exceeded",
                    diagnostics);
        }
        return new ModelStreamFailure(
                MODEL_PROVIDER_REQUEST_FAILED,
                "模型供应商调用失败，请到 ReachAI 模型中心重新测试并检查连接配置后重试。",
                "provider_error",
                diagnostics);
    }

    public boolean isDefinitiveProviderFailure() {
        return MODEL_PROVIDER_BALANCE_INSUFFICIENT.equals(code)
                || MODEL_CONTEXT_LENGTH_EXCEEDED.equals(code)
                || MODEL_PROVIDER_REQUEST_FAILED.equals(code);
    }

    public boolean isContextLengthExceeded() {
        return MODEL_CONTEXT_LENGTH_EXCEEDED.equals(code);
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

    private static boolean isContextLengthExceeded(String combined) {
        return combined.contains("context_length_exceeded")
                || combined.contains("context length exceeded")
                || combined.contains("maximum context length")
                || combined.contains("maximum context")
                || combined.contains("too many input tokens")
                || combined.contains("prompt is too long")
                || combined.contains("input token limit")
                || combined.contains("exceeds the model's maximum context")
                || combined.contains("reduce the length of the messages")
                || combined.contains("上下文长度")
                || combined.contains("输入 token 超限");
    }
}
