package com.enterprise.ai.runtime.compat;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.enterprise.ai.runtime.chat.RuntimeChatRequest;
import com.enterprise.ai.runtime.chat.RuntimeChatResponse;
import com.enterprise.ai.runtime.chat.RuntimeLightweightChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RuntimeChatCompatibilityController {

    private final RuntimeLightweightChatService lightweightChatService;

    @PostMapping("/api/chat")
    public RuntimeChatResponse chat(@RequestBody RuntimeChatRequest request) {
        return lightweightChatService.chat(request);
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody RuntimeChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                RuntimeChatResponse response = lightweightChatService.chat(request);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(response.getAnswer() == null ? "" : response.getAnswer()));
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(response.getSessionId() == null ? "" : response.getSessionId()));
                emitter.complete();
            } catch (Exception e) {
                log.error("[RuntimeChat] stream failed", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("流式对话出错: " + e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[RuntimeChat] emitter error: {}", e.getMessage()));
        return emitter;
    }

    @DeleteMapping("/api/chat/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("sessionId") String sessionId, HttpServletRequest request) {
        lightweightChatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
