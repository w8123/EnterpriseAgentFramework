package com.enterprise.ai.runtime.client.control;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.UUID;

/** HMAC-signs the exact Runtime → Control Agent Skill request. */
public class RuntimeAgentSkillInternalAuthFeignConfig {

    @Bean
    RequestInterceptor runtimeAgentSkillInternalAuthInterceptor(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}")
            String serviceSecret) {
        String secret = serviceSecret == null ? "" : serviceSecret.trim();
        return template -> {
            // Local installations without the shared secret may manage Skills, but execution fails closed in Control.
            if (!StringUtils.hasText(secret)) return;
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            byte[] body = template.body() == null ? new byte[0] : template.body();
            String bodySha256 = InternalServiceHmac.bodySha256Hex(body);
            String path = canonicalPath(template.url());
            String source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED;
            String tenantId = "default";
            String canonical = InternalServiceHmac.canonical(
                    template.method(), path, InternalServiceAuthHeaders.CALLER_RUNTIME,
                    source, tenantId, "", timestamp, nonce, bodySha256);
            String signature = InternalServiceHmac.sign(secret, canonical);
            template.header(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
            template.header(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
            template.header(InternalServiceAuthHeaders.NONCE, nonce);
            template.header(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
            template.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
            template.header(InternalServiceAuthHeaders.IDENTITY_USER_ID, "");
            template.header(InternalServiceAuthHeaders.BODY_SHA256, bodySha256);
            template.header(InternalServiceAuthHeaders.SIGNATURE, signature);
        };
    }

    static String canonicalPath(String value) {
        String url = value == null ? "" : value;
        String path = url.startsWith("http://") || url.startsWith("https://")
                ? URI.create(url).getRawPath()
                : url;
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
