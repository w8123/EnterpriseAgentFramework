package com.enterprise.ai.model.controller;

import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ModelRoutingService;
import com.enterprise.ai.model.service.ModelStreamEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelControllerStreamHeadersTest {

    @Test
    void chatStreamEventsUsesSingleCanonicalCacheControl() {
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.chatStreamEvents(any())).thenReturn(
                Flux.just(ModelStreamEvent.completed("stop"))
        );
        ModelController controller = new ModelController(routingService);

        ChatRequest request = new ChatRequest();
        request.setModelInstanceId("mi-1");
        request.setMessages(List.of(ChatRequest.ChatMessage.builder().role("user").content("hi").build()));

        ResponseEntity<Flux<ModelStreamEvent>> response = controller.chatStreamEvents(request);

        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        String cacheControl = response.getHeaders().getCacheControl();
        assertEquals("no-cache, no-transform", cacheControl);
        assertFalse(String.valueOf(cacheControl).contains("no-cache, no-cache"));
    }

    @Test
    void unexpectedStreamFailureDoesNotExposeExceptionTextOrStackInLogs() {
        ModelRoutingService routingService = mock(ModelRoutingService.class);
        when(routingService.chatStreamEvents(any())).thenReturn(Flux.error(new IllegalStateException("PRIVATE_STREAM_MARKER")));
        Logger logger = (Logger) LoggerFactory.getLogger(ModelController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatRequest request = new ChatRequest();
            request.setModelInstanceId("mi-test");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new ModelController(routingService).chatStreamEvents(request).getBody()
                    .onErrorResume(error -> { failure.set(error); return Flux.empty(); }).blockLast();
            assertNotNull(failure.get());
            assertFalse(failure.get().getMessage().contains("PRIVATE_STREAM_MARKER"));
            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("[ChatStreamEvents] failed")).toList();
            assertEquals(1, failures.size());
            assertFalse(failures.get(0).getFormattedMessage().contains("PRIVATE_STREAM_MARKER"));
            assertTrue(failures.get(0).getThrowableProxy() == null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
