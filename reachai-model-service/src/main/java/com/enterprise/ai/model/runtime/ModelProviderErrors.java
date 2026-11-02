package com.enterprise.ai.model.runtime;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;

/**
 * 把调用模型供应商时的失败翻译成中文：发生了什么、下一步去哪里改。
 * 供应商响应体是不可信内容，只转换为已知错误分类，保留 HTTP 状态码供 Runtime 识别。
 */
final class ModelProviderErrors {

    private ModelProviderErrors() {
    }

    static String providerHttpError(int status, String detail) {
        String normalized = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (status == 402 || (status != 401 && status != 403 && (normalized.contains("insufficient balance")
                || normalized.contains("insufficient_balance") || normalized.contains("余额不足")))) {
            return "模型供应商账户余额不足（HTTP " + status + "）。请到供应商控制台检查额度，或换一个可用的模型。";
        }
        if (status != 401 && status != 403 && isContextLengthError(normalized)) {
            return "模型供应商拒绝了请求（HTTP " + status + "）：上下文长度超过模型限制，请缩短输入或清理会话后重试。";
        }
        if (status == 400 && normalized.contains("tool_choice") && normalized.contains("tools")
                && normalized.contains("must be set")) {
            return "模型供应商拒绝了请求（HTTP 400）：工具选择参数与工具列表不匹配，请检查模型调用协议。";
        }
        String message = switch (status) {
            case 401 -> "模型供应商拒绝了 API Key（HTTP 401）：Key 不对或已失效。请到模型中心修改这个模型的 API Key。";
            case 402 -> "模型供应商账户余额不足（HTTP 402）。请到供应商控制台充值，或在模型中心换一个可用的模型。";
            case 403 -> "模型供应商拒绝访问（HTTP 403）：账号可能没有这个模型的使用权限。请检查供应商账号权限，或换一个模型。";
            case 404 -> "模型供应商找不到这个接口或模型（HTTP 404）。请检查模型中心里的接口地址（通常以 /v1 结尾）和模型名称。";
            case 429 -> "模型供应商限流（HTTP 429）：请求太频繁或额度已用完。请稍后重试，或到供应商控制台查看额度。";
            default -> status >= 500
                    ? "模型供应商服务暂时异常（HTTP " + status + "），请稍后重试。"
                    : "模型供应商返回错误（HTTP " + status + "）。";
        };
        return message;
    }

    private static boolean isContextLengthError(String detail) {
        return detail.contains("context_length_exceeded") || detail.contains("context length exceeded")
                || detail.contains("maximum context length") || detail.contains("maximum context")
                || detail.contains("too many input tokens") || detail.contains("prompt is too long")
                || detail.contains("input token limit") || detail.contains("exceeds the model's maximum context")
                || detail.contains("reduce the length of the messages") || detail.contains("上下文长度")
                || detail.contains("输入 token 超限");
    }

    static String transportFailure(String what, String baseUrl, Throwable cause) {
        String target = origin(baseUrl);
        if (find(cause, UnknownHostException.class) != null || find(cause, UnresolvedAddressException.class) != null) {
            return "找不到模型服务地址" + target + "：域名无法解析。请检查模型中心里的接口地址是否写错，"
                    + "以及 ReachAI 所在服务器能否访问这个地址。";
        }
        if (find(cause, HttpConnectTimeoutException.class) != null) {
            return "连接模型服务" + target + "超时。请检查接口地址是否正确，以及服务器网络、代理和防火墙设置。";
        }
        if (find(cause, ConnectException.class) != null) {
            return "连不上模型服务" + target + "：对方拒绝连接。请检查模型中心里的接口地址和端口是否正确，"
                    + "本地模型（如 Ollama）请确认已经启动。";
        }
        if (find(cause, SSLException.class) != null) {
            return "和模型服务" + target + "建立 HTTPS 连接失败，可能是网络或代理中断、证书校验失败或协议不匹配。"
                    + "请检查接口地址、ReachAI 所在环境的网络与代理，以及证书配置。";
        }
        String type = cause == null ? "网络中断" : cause.getClass().getSimpleName();
        return what + "失败" + target + "（" + type + "）。请稍后重试；一直失败请到模型中心重新测试这个模型。";
    }

    static String timeout(String what, String baseUrl, Duration timeout) {
        return what + "超时" + origin(baseUrl) + "：" + seconds(timeout) + " 秒内没有返回。"
                + "请稍后重试；一直超时请检查网络，或换一个响应更快的模型。";
    }

    static String missingBaseUrl() {
        return "这个模型还没有填写接口地址（Base URL）。请到模型中心编辑模型，填写供应商的接口地址。";
    }

    private static String origin(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "";
        try {
            URI uri = URI.create(baseUrl.trim());
            if (uri.getHost() == null) return "";
            return "（" + uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "）";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static long seconds(Duration timeout) {
        return Math.max(1, (timeout.toMillis() + 999) / 1000);
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
