package com.enterprise.ai.control.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProjectOnboardingFeignConfigTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void bindsTheProjectOnboardingClientToItsDedicatedSigningConfiguration() {
        FeignClient annotation = CapabilityProjectOnboardingClient.class.getAnnotation(FeignClient.class);

        assertArrayEquals(new Class<?>[] {CapabilityProjectOnboardingFeignConfig.class}, annotation.configuration());
    }

    @Test
    void signsOnlyTheProtectedProjectByIdReadWithTheServerAttestedPlatformIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID,
                "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = requestTemplate("GET", "/internal/capability/projects/by-id/7");

        CapabilityProjectOnboardingFeignConfig.signProjectByIdRead(
                template, new InternalServiceAuthSigner("test-internal-service-secret"));

        assertTrue(template.headers().containsKey(InternalServiceAuthHeaders.CALLER));
        assertTrue(template.headers().containsKey(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertTrue(template.headers().containsKey(InternalServiceAuthHeaders.SIGNATURE));
    }

    @Test
    void doesNotInventAnIdentityForBackgroundOrNonByIdRequests() {
        RequestTemplate background = requestTemplate("GET", "/internal/capability/projects/by-id/7");
        RequestTemplate onboarding = requestTemplate("GET", "/internal/capability/projects/by-id/7/onboarding");
        InternalServiceAuthSigner signer = new InternalServiceAuthSigner("test-internal-service-secret");

        CapabilityProjectOnboardingFeignConfig.signProjectByIdRead(background, signer);
        CapabilityProjectOnboardingFeignConfig.signProjectByIdRead(onboarding, signer);

        assertFalse(background.headers().containsKey(InternalServiceAuthHeaders.SIGNATURE));
        assertFalse(onboarding.headers().containsKey(InternalServiceAuthHeaders.SIGNATURE));
    }

    private static RequestTemplate requestTemplate(String method, String path) {
        RequestTemplate template = new RequestTemplate();
        template.method(method);
        template.uri(path);
        return template;
    }
}
