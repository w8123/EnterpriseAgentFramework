package com.enterprise.ai.model.controller;

import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ModelRoutingService;
import com.enterprise.ai.model.service.ModelStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
