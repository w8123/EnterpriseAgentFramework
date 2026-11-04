package com.enterprise.ai.runtime.client.model;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelInternalAuthFeignConfigTest {
    @Test
    void realRuntimeClientIncludesFeignTargetPrefixAndExactBodyInSignature() throws Exception {
        String secret = "runtime-model-transport-fixture";
        AtomicBoolean valid = new AtomicBoolean();
        AtomicReference<String> target = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var headers = exchange.getRequestHeaders();
            String digest = InternalServiceHmac.bodySha256Hex(exchange.getRequestBody().readAllBytes());
            target.set(exchange.getRequestURI().toString());
            String caller = headers.getFirst(InternalServiceAuthHeaders.CALLER);
            valid.set(InternalServiceAuthHeaders.CALLER_RUNTIME.equals(caller)
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
            ObjectMapper json = new ObjectMapper();
            RuntimeModelServiceClient client = Feign.builder().contract(new SpringMvcContract())
                    .encoder((body, type, template) -> {
                        try { template.body(json.writeValueAsBytes(body), StandardCharsets.UTF_8); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    })
                    .requestInterceptor(new ModelInternalAuthFeignConfig().modelInternalAuthInterceptor(secret))
                    .decoder((response, type) -> null)
                    .target(RuntimeModelServiceClient.class, "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
            client.chat(RuntimeModelServiceClient.ModelChatRequest.builder().modelInstanceId("fixture").messages(List.of()).build());
            assertEquals("/model/chat", target.get());
            assertTrue(valid.get());
        } finally { server.stop(0); }
    }
}
