package com.enterprise.ai.runtime.client.control;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePageBridgeAuthFeignTest {
    @Test void signsExactSerializedBridgeBodyAndPath() {
        String secret = "runtime-control-test-secret";
        var template = new RequestTemplate();
        template.method("POST"); template.uri("/internal/control/page-bridge/execute");
        template.body("{\"identityUserId\":\"用户1\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        new RuntimeAgentSkillInternalAuthFeignConfig().runtimeAgentSkillInternalAuthInterceptor(secret).apply(template);
        var headers = template.headers();
        var digest = headers.get(InternalServiceAuthHeaders.BODY_SHA256).iterator().next();
        assertEquals(InternalServiceHmac.bodySha256Hex(template.body()), digest);
        var canonical = InternalServiceHmac.canonical("POST", "/internal/control/page-bridge/execute",
                headers.get(InternalServiceAuthHeaders.CALLER).iterator().next(),
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE).iterator().next(),
                headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID).iterator().next(), "",
                headers.get(InternalServiceAuthHeaders.TIMESTAMP).iterator().next(),
                headers.get(InternalServiceAuthHeaders.NONCE).iterator().next(), digest);
        assertTrue(InternalServiceHmac.verifyConstantTime(secret, canonical,
                headers.get(InternalServiceAuthHeaders.SIGNATURE).iterator().next()));
    }
}
