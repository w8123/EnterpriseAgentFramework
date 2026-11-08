package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Signs Control's reads of Runtime's model usage and audit runs (/internal/runtime/usage/**) as the console user
 * who opened 「AI 使用情况」. Only called from that request's thread, after the usage permission check.
 */
@Component
public class RuntimeUsageRequestSigner {

    private final InternalServiceAuthSigner signer;
    private final PlatformRequestAuthorization requestAuthorization;

    public RuntimeUsageRequestSigner(InternalServiceAuthSigner signer, PlatformRequestAuthorization requestAuthorization) {
        this.signer = signer;
        this.requestAuthorization = requestAuthorization;
    }

    public Map<String, String> headers(String path) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("runtime usage reads need the console request");
        }
        PlatformAuthenticatedSession session = requestAuthorization.requireAuthenticated(attributes.getRequest());
        return signer.sign("GET", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default",
                String.valueOf(session.user().getId()), new byte[0]);
    }
}
