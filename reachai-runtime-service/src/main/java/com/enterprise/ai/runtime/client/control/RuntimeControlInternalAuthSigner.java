package com.enterprise.ai.runtime.client.control;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Signs exact Runtime-to-Control request bytes for fail-closed internal APIs. */
@Component
public class RuntimeControlInternalAuthSigner {

    private final String serviceSecret;

    public RuntimeControlInternalAuthSigner(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}")
            String serviceSecret) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
    }

    public Map<String, String> sign(String method, String path, byte[] exactBody) {
        if (!StringUtils.hasText(serviceSecret)) {
            throw new IllegalStateException(
                    "REACHAI_INTERNAL_SERVICE_SECRET is required for Runtime-to-Control A2A calls");
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodySha256 = InternalServiceHmac.bodySha256Hex(exactBody);
        String source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED;
        String tenantId = "default";
        String canonical = InternalServiceHmac.canonical(
                method, path, InternalServiceAuthHeaders.CALLER_RUNTIME,
                source, tenantId, "", timestamp, nonce, bodySha256);
        String signature = InternalServiceHmac.sign(serviceSecret, canonical);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, "");
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, bodySha256);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, signature);
        return Map.copyOf(headers);
    }
}
