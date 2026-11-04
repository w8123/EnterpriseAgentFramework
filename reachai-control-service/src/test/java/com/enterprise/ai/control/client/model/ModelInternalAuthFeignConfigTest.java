package com.enterprise.ai.control.client.model;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelInternalAuthFeignConfigTest {
    @Test
    void realCatalogClientSignsTheExactQuerySentToModel() throws Exception {
        String secret = "control-model-transport-fixture";
        AtomicBoolean valid = new AtomicBoolean();
        AtomicReference<String> target = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var headers = exchange.getRequestHeaders();
            String digest = InternalServiceHmac.bodySha256Hex(exchange.getRequestBody().readAllBytes());
            target.set(exchange.getRequestURI().toString());
            String caller = headers.getFirst(InternalServiceAuthHeaders.CALLER);
            valid.set(InternalServiceAuthHeaders.CALLER_CONTROL.equals(caller)
                    && digest.equals(headers.getFirst(InternalServiceAuthHeaders.BODY_SHA256))
                    && InternalServiceHmac.verifyConstantTime(secret, ModelServiceRequestAuth.canonical(
                    exchange.getRequestMethod(), target.get(), caller,
                    headers.getFirst(InternalServiceAuthHeaders.TIMESTAMP), headers.getFirst(InternalServiceAuthHeaders.NONCE), digest),
                    headers.getFirst(InternalServiceAuthHeaders.SIGNATURE)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            ControlModelCatalogClient client = Feign.builder().contract(new SpringMvcContract())
                    .requestInterceptor(new ModelInternalAuthFeignConfig().modelInternalAuthInterceptor(secret))
                    .decoder((response, type) -> null)
                    .target(ControlModelCatalogClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
            client.list("project-a", "LLM", null, false);
            assertTrue(valid.get());
            assertTrue(target.get().startsWith("/model/instances?"));
            assertTrue(target.get().contains("projectCode=project-a"));
        } finally { server.stop(0); }
    }
}
