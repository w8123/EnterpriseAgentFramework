package com.enterprise.ai.control.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-client signing for the legacy project-by-id read that Capability now
 * protects. It only forwards the already-attested platform-session identity;
 * background callers without that identity are deliberately not impersonated.
 */
public class CapabilityProjectOnboardingFeignConfig {

    private static final String PROJECT_BY_ID_PREFIX = "/internal/capability/projects/by-id/";

    @Bean
    RequestInterceptor capabilityProjectOnboardingInternalAuthInterceptor(InternalServiceAuthSigner signer) {
        return template -> signProjectByIdRead(template, signer);
    }

    static void signProjectByIdRead(RequestTemplate template, InternalServiceAuthSigner signer) {
        String path = template == null ? null : template.path();
        if (!isProjectByIdRead(template, path) || signer == null || !signer.secretConfigured()) {
            return;
        }
        String actorId = currentPlatformSessionActorId();
        if (actorId == null) {
            return;
        }
        signer.sign("GET", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                        actorId, new byte[0])
                .forEach(template::header);
    }

    private static boolean isProjectByIdRead(RequestTemplate template, String path) {
        if (template == null || !"GET".equalsIgnoreCase(template.method())
                || path == null || !path.startsWith(PROJECT_BY_ID_PREFIX)) {
            return false;
        }
        String projectId = path.substring(PROJECT_BY_ID_PREFIX.length());
        return !projectId.isBlank() && projectId.indexOf('/') < 0 && projectId.indexOf('?') < 0;
    }

    private static String currentPlatformSessionActorId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object candidate = request == null
                ? null
                : request.getAttribute(InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID);
        return candidate instanceof String actorId && !actorId.isBlank() ? actorId : null;
    }
}
