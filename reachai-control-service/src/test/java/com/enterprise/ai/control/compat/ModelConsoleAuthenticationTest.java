package com.enterprise.ai.control.compat;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelConsoleAuthenticationTest {
    private static final String SECRET = "model-console-auth-test-secret";

    @Test
    void replacesBrowserSuppliedSignaturesAndSignsExactForwardedQuery() throws Exception {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer upstream = MockRestServiceServer.createServer(http);
        var controller = new KnowledgeModelConsoleProxyController(http, HttpClient.newHttpClient(), "http://knowledge", "http://model");
        controller.configureModelServiceSecret(SECRET);
        upstream.expect(requestTo("http://model/model/templates?modelType=LLM"))
                .andExpect(request -> assertTrue(valid(request.getMethod().name(), request.getURI().toString(),
                        request.getHeaders()::getFirst, new byte[0])))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/model/templates?modelType=LLM")
                .header(InternalServiceAuthHeaders.CALLER, "browser")
                .header(InternalServiceAuthHeaders.SIGNATURE, "forged")) .andExpect(status().isOk());
        upstream.verify();
    }

    @Test
    void signsStreamingTransportBeforeSendingAnyProviderRequest() throws Exception {
        AtomicBoolean signed = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model/chat/stream/events", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            signed.set(valid(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders()::getFirst, body));
            byte[] event = "data: {\"type\":\"completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, event.length);
            exchange.getResponseBody().write(event);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var controller = new KnowledgeModelConsoleProxyController(new RestTemplate(), HttpClient.newHttpClient(), "http://knowledge", base);
            controller.configureModelServiceSecret(SECRET);
            var response = controller.modelStream(RequestEntity.post(URI.create(base + "/model/chat/stream/events"))
                    .body("{}".getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);
            assertTrue(signed.get());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("completed"));
        } finally { server.stop(0); }
    }

    private static boolean valid(String method, String target, java.util.function.Function<String, String> header, byte[] body) {
        String digest = InternalServiceHmac.bodySha256Hex(body);
        return InternalServiceAuthHeaders.CALLER_CONTROL.equals(header.apply(InternalServiceAuthHeaders.CALLER))
                && digest.equals(header.apply(InternalServiceAuthHeaders.BODY_SHA256))
                && InternalServiceHmac.verifyConstantTime(SECRET, ModelServiceRequestAuth.canonical(method, target,
                InternalServiceAuthHeaders.CALLER_CONTROL, header.apply(InternalServiceAuthHeaders.TIMESTAMP),
                header.apply(InternalServiceAuthHeaders.NONCE), digest), header.apply(InternalServiceAuthHeaders.SIGNATURE));
    }
}
