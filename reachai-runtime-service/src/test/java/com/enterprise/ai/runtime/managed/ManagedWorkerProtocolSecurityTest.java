package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.internalauth.InternalServiceAuthFilter;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthProperties;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ManagedWorkerProtocolSecurityTest {

    @Test
    void controlManagedExecutionRoutesUseHmacButWorkerRoutesUseTheirScopedToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                mock(InternalServiceAuthVerifier.class),
                new InternalServiceAuthProperties("development-secret", 300, 600, 100, 1_048_576));

        MockHttpServletRequest controlRequest = new MockHttpServletRequest(
                "POST", "/internal/runtime/managed-executions");
        controlRequest.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse controlResponse = new MockHttpServletResponse();
        MockFilterChain controlChain = new MockFilterChain();
        filter.doFilter(controlRequest, controlResponse, controlChain);
        assertThat(controlResponse.getStatus()).isEqualTo(401);
        assertThat(controlChain.getRequest()).isNull();

        MockHttpServletRequest workerRequest = new MockHttpServletRequest(
                "POST", "/internal/runtime/managed-worker/executions/mex_1:claim");
        MockHttpServletResponse workerResponse = new MockHttpServletResponse();
        MockFilterChain workerChain = new MockFilterChain();
        filter.doFilter(workerRequest, workerResponse, workerChain);
        assertThat(workerResponse.getStatus()).isEqualTo(200);
        assertThat(workerChain.getRequest()).isSameAs(workerRequest);
    }

    @Test
    void workerBodyFilterRejectsOversizedPayloadBeforeJsonDeserialization() throws Exception {
        ManagedExecutorProperties properties = new ManagedExecutorProperties(
                false, false, "", "ANALYZE_READONLY", 0,
                14_400, 90, 65_535, 100, 262_144, 4_096);
        ManagedWorkerBodyLimitFilter filter = new ManagedWorkerBodyLimitFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/managed-worker/executions/mex_1/events:batch");
        request.setContent("x".repeat(4_097).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("MANAGED_WORKER_REQUEST_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void workerBodyFilterBypassesCachingOnlyForTheExactPutArtifactRoute() throws Exception {
        ManagedExecutorProperties properties = new ManagedExecutorProperties(
                false, false, "", "ANALYZE_READONLY", 0,
                14_400, 90, 65_535, 100, 262_144, 4_096);
        ManagedWorkerBodyLimitFilter filter = new ManagedWorkerBodyLimitFilter(properties);
        byte[] oversized = "x".repeat(4_097).getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest artifactRequest = new MockHttpServletRequest(
                "PUT", "/internal/runtime/managed-worker/executions/mex_1/artifacts/PATCH");
        artifactRequest.setContent(oversized);
        MockHttpServletResponse artifactResponse = new MockHttpServletResponse();
        MockFilterChain artifactChain = new MockFilterChain();
        filter.doFilter(artifactRequest, artifactResponse, artifactChain);

        assertThat(artifactResponse.getStatus()).isEqualTo(200);
        assertThat(artifactChain.getRequest()).isSameAs(artifactRequest);

        MockHttpServletRequest lookalikeRequest = new MockHttpServletRequest(
                "POST", "/internal/runtime/managed-worker/executions/mex_1/artifacts/PATCH");
        lookalikeRequest.setContent(oversized);
        MockHttpServletResponse lookalikeResponse = new MockHttpServletResponse();
        MockFilterChain lookalikeChain = new MockFilterChain();
        filter.doFilter(lookalikeRequest, lookalikeResponse, lookalikeChain);

        assertThat(lookalikeResponse.getStatus()).isEqualTo(413);
        assertThat(lookalikeChain.getRequest()).isNull();
    }
}
