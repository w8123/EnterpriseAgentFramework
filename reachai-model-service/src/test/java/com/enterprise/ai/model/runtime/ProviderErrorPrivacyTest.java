package com.enterprise.ai.model.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderErrorPrivacyTest {
    @Test
    void httpErrorsDoNotExposeProviderBodyInSyncStreamOrLogs() throws Exception {
        String secret = "REACHAI_PROVIDER_PRIVATE_MARKER";
        byte[] body = ("{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"" + secret + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleRuntimeClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ModelInstanceRuntime runtime = ModelInstanceRuntime.builder().id("privacy-fixture")
                    .provider("fixture").modelName("fixture")
                    .connectionConfig(Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())).build();
            ChatRequest request = ChatRequest.builder().messages(List.of(
                    ChatRequest.ChatMessage.builder().role("user").content("synthetic input").build())).build();
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());
            BizException sync = assertThrows(BizException.class, () -> client.chat(runtime, request));
            assertTrue(sync.getMessage().contains("上下文长度"));
            assertFalse(sync.getMessage().contains(secret));
            AtomicReference<Throwable> streamFailure = new AtomicReference<>();
            List<ModelStreamEvent> events = client.chatStreamEvents(runtime, request)
                    .onErrorResume(error -> { streamFailure.set(error); return Flux.empty(); })
                    .collectList().block();
            assertNotNull(streamFailure.get());
            assertFalse(streamFailure.get().getMessage().contains(secret));
            assertNotNull(events);
            assertTrue(events.stream().anyMatch(event -> ModelStreamEvent.ERROR.equals(event.getType())
                    && event.getMessage().contains("上下文长度")));
            assertFalse(new ObjectMapper().writeValueAsString(events).contains(secret));
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            server.stop(0);
        }
    }
}
