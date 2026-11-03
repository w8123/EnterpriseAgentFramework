package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformPageAssistantSmokeGatewayTest {
    @Test void signsOnlyVerifiedPageIdentityAndNeverForwardsItsBearerToken() throws Exception {
        String secret = "test-internal-service-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new AtomicReference<String>();
        server.createContext(PlatformPageAssistantSmokeGateway.PATH, exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            String digest = InternalServiceHmac.bodySha256Hex(body);
            String canonical = InternalServiceHmac.canonical("POST", PlatformPageAssistantSmokeGateway.PATH,
                    headers.getFirst(InternalServiceAuthHeaders.CALLER), headers.getFirst(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    headers.getFirst(InternalServiceAuthHeaders.IDENTITY_TENANT_ID), headers.getFirst(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    headers.getFirst(InternalServiceAuthHeaders.TIMESTAMP), headers.getFirst(InternalServiceAuthHeaders.NONCE), digest);
            boolean valid = InternalServiceHmac.verifyConstantTime(secret, canonical, headers.getFirst(InternalServiceAuthHeaders.SIGNATURE));
            observed.set(new String(body, StandardCharsets.UTF_8));
            byte[] response = (valid && "tenant-a".equals(headers.getFirst(InternalServiceAuthHeaders.IDENTITY_TENANT_ID))
                    && "Alice".equals(headers.getFirst(InternalServiceAuthHeaders.IDENTITY_USER_ID)) ? "{\"status\":\"COMPLETED\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 401, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            var properties = new PlatformEmbedTokenProperties(); properties.setSecret("embed-test-secret");
            var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
            String token = tokens.issue(PlatformEmbedTokenIssueCommand.builder().tenantId("tenant-a").appId("erp")
                    .projectCode("erp").agentId("bot").pageInstanceId("page-1").origin("http://localhost")
                    .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("Alice", "Alice", "Alice", List.of(), Map.of()))
                    .build()).token();
            var mapper = mock(PlatformEmbedSessionMapper.class);
            var session = new PlatformEmbedSessionEntity();
            session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setAppId("erp");
            session.setProjectCode("erp"); session.setAgentId("bot"); session.setExternalUserId("Alice");
            session.setOrigin("http://localhost");
            session.setPageInstanceId("page-1"); session.setStatus("ACTIVE"); session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
            when(mapper.selectOne(any())).thenReturn(session);
            var gateway = new PlatformPageAssistantSmokeGateway(tokens, new PlatformEmbedSessionService(mapper, new ObjectMapper()),
                    new InternalServiceAuthSigner(secret), new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            var body = Map.<String, Object>of("message", "test", "runtimeContext", Map.of("embedSessionId", "embed-1"));
            assertEquals(200, gateway.execute("wf-1", "erp", token, body).getStatusCode().value());
            assertTrue(observed.get().contains("embed-1"));
            assertFalse(observed.get().contains(token));
            assertThrows(ResponseStatusException.class, () -> gateway.execute("wf-1", "another-project", token, body));
            session.setTenantId("tenant-b");
            assertThrows(ResponseStatusException.class, () -> gateway.execute("wf-1", "erp", token, body));
        } finally { server.stop(0); }
    }
}
