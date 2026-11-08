package com.enterprise.ai.runtime.client.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.UUID;

/** Signs Runtime → Knowledge workflow writes; without the shared secret Knowledge rejects them (fail closed). */
public class RuntimeKnowledgeWriteAuthFeignConfig {

    static final String SOURCE = "WORKFLOW_KNOWLEDGE_WRITE";

    @Bean
    RequestInterceptor runtimeKnowledgeWriteAuthInterceptor(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String serviceSecret) {
        String secret = serviceSecret == null ? "" : serviceSecret.trim();
        return template -> {
            if (!StringUtils.hasText(secret)) return;
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            byte[] body = template.body() == null ? new byte[0] : template.body();
            String bodySha256 = InternalServiceHmac.bodySha256Hex(body);
            String url = template.url() == null ? "" : template.url();
            String path = url.startsWith("http://") || url.startsWith("https://") ? URI.create(url).getRawPath() : url;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            // Knowledge verifies the path without its servlet context.
            if (path.startsWith("/ai/")) path = path.substring(3);
            String canonical = InternalServiceHmac.canonical(template.method(), path,
                    InternalServiceAuthHeaders.CALLER_RUNTIME, SOURCE, "default", "", timestamp, nonce, bodySha256);
            template.header(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
            template.header(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
            template.header(InternalServiceAuthHeaders.NONCE, nonce);
            template.header(InternalServiceAuthHeaders.IDENTITY_SOURCE, SOURCE);
            template.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, "default");
            template.header(InternalServiceAuthHeaders.IDENTITY_USER_ID, "");
            template.header(InternalServiceAuthHeaders.BODY_SHA256, bodySha256);
            template.header(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(secret, canonical));
        };
    }
}
