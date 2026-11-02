package com.enterprise.ai.model.runtime;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderErrorsTest {

    /** Runtime 的 ModelStreamFailure 用同一个正则从消息里取上游状态码。 */
    private static final Pattern RUNTIME_STATUS = Pattern.compile("(?i)\\bHTTP\\s+(\\d{3})\\b");

    @Test
    void providerHttpErrorsKeepActionableClassificationWithoutProviderText() {
        String balance = ModelProviderErrors.providerHttpError(402, "{\"message\":\"Insufficient Balance SECRET_VALUE\"}");
        assertTrue(balance.startsWith("模型供应商账户余额不足（HTTP 402）"), balance);
        assertTrue(balance.toLowerCase().contains("http 402"));
        assertFalse(balance.contains("SECRET_VALUE"));
        assertTrue(RUNTIME_STATUS.matcher(balance).find());

        assertTrue(ModelProviderErrors.providerHttpError(401, null).contains("API Key"));
        assertTrue(ModelProviderErrors.providerHttpError(404, "").contains("/v1"));
        assertEquals("模型供应商服务暂时异常（HTTP 503），请稍后重试。", ModelProviderErrors.providerHttpError(503, " "));

        String context = ModelProviderErrors.providerHttpError(400,
                "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"SECRET_VALUE\"}}");
        assertTrue(context.contains("上下文长度"), context);
        assertTrue(context.contains("HTTP 400"), context);
        assertFalse(context.contains("SECRET_VALUE"));
        String unknown = ModelProviderErrors.providerHttpError(400, "API key SECRET_VALUE and user message");
        assertFalse(unknown.contains("SECRET_VALUE"));
        assertFalse(unknown.contains("user message"));
        assertTrue(ModelProviderErrors.providerHttpError(400, "insufficient_balance SECRET_VALUE").contains("余额不足"));
        assertTrue(ModelProviderErrors.providerHttpError(402, "context_length_exceeded").contains("余额不足"));
        String tools = ModelProviderErrors.providerHttpError(400,
                "When using tool_choice, tools must be set. PRIVATE_TOOL_MARKER");
        assertTrue(tools.contains("工具"));
        assertFalse(tools.contains("PRIVATE_TOOL_MARKER"));
    }

    @Test
    void transportFailuresNameTheServiceOriginAndTheNextStep() {
        String refused = ModelProviderErrors.transportFailure("读取模型列表",
                "http://127.0.0.1:11434/v1?key=secret", new ConnectException());
        assertEquals("连不上模型服务（http://127.0.0.1:11434）：对方拒绝连接。请检查模型中心里的接口地址和端口是否正确，"
                + "本地模型（如 Ollama）请确认已经启动。", refused);

        String unknownHost = ModelProviderErrors.transportFailure("调用模型", "https://api.example.invalid/v1",
                new IOException(new UnknownHostException("api.example.invalid")));
        assertTrue(unknownHost.startsWith("找不到模型服务地址（https://api.example.invalid）"), unknownHost);

        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new java.nio.channels.UnresolvedAddressException());
        assertTrue(ModelProviderErrors.transportFailure("调用模型", "http://no-such-host.invalid/v1", unresolved)
                .startsWith("找不到模型服务地址（http://no-such-host.invalid）"));

        String tls = ModelProviderErrors.transportFailure("调用模型", "https://api.example.invalid/v1?key=secret",
                new IOException(new SSLHandshakeException("Remote host terminated the handshake")));
        assertTrue(tls.contains("网络或代理中断"), tls);
        assertTrue(tls.contains("证书校验失败"), tls);
        assertTrue(!tls.contains("secret"), tls);

        String other = ModelProviderErrors.transportFailure("调用模型", null, new IOException("boom"));
        assertEquals("调用模型失败（IOException）。请稍后重试；一直失败请到模型中心重新测试这个模型。", other);

        assertEquals("读取模型列表超时（https://api.deepseek.com）：20 秒内没有返回。请稍后重试；一直超时请检查网络，或换一个响应更快的模型。",
                ModelProviderErrors.timeout("读取模型列表", "https://api.deepseek.com", Duration.ofSeconds(20)));
    }
}
