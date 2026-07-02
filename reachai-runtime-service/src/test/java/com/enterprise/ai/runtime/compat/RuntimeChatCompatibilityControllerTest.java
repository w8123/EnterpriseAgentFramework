package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.chat.RuntimeChatRequest;
import com.enterprise.ai.runtime.chat.RuntimeChatResponse;
import com.enterprise.ai.runtime.chat.RuntimeLightweightChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeChatCompatibilityControllerTest {

    @Test
    void keepsPublicChatRoutesOnRuntimeService() throws Exception {
        Method chat = RuntimeChatCompatibilityController.class
                .getDeclaredMethod("chat", RuntimeChatRequest.class);
        Method stream = RuntimeChatCompatibilityController.class
                .getDeclaredMethod("chatStream", RuntimeChatRequest.class);
        Method clearSession = RuntimeChatCompatibilityController.class
                .getDeclaredMethod("clearSession", String.class, HttpServletRequest.class);

        assertArrayEquals(new String[] {"/api/chat"}, chat.getAnnotation(PostMapping.class).value());
        PostMapping streamMapping = stream.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/api/chat/stream"}, streamMapping.value());
        assertArrayEquals(new String[] {MediaType.TEXT_EVENT_STREAM_VALUE}, streamMapping.produces());
        assertArrayEquals(new String[] {"/api/chat/session/{sessionId}"},
                clearSession.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void handlesChatRoutesInRuntimeService() {
        RuntimeChatResponse expected = RuntimeChatResponse.builder()
                .sessionId("session-1")
                .answer("hello from runtime")
                .build();
        StubRuntimeLightweightChatService chatService = new StubRuntimeLightweightChatService(expected);
        RuntimeChatCompatibilityController controller = new RuntimeChatCompatibilityController(chatService);
        RuntimeChatRequest request = RuntimeChatRequest.builder()
                .message("hello")
                .modelInstanceId("model-1")
                .build();

        RuntimeChatResponse response = controller.chat(request);

        assertEquals(expected, response);
        assertEquals(request, chatService.lastRequest);
    }

    @Test
    void clearsRuntimeChatSession() {
        StubRuntimeLightweightChatService chatService = new StubRuntimeLightweightChatService(null);
        RuntimeChatCompatibilityController controller = new RuntimeChatCompatibilityController(chatService);

        controller.clearSession("session-1", new MockHttpServletRequest("DELETE", "/api/chat/session/session-1"));

        assertEquals("session-1", chatService.clearedSessionId);
    }

    private static final class StubRuntimeLightweightChatService implements RuntimeLightweightChatService {
        private final RuntimeChatResponse response;
        private RuntimeChatRequest lastRequest;
        private String clearedSessionId;

        private StubRuntimeLightweightChatService(RuntimeChatResponse response) {
            this.response = response;
        }

        @Override
        public RuntimeChatResponse chat(RuntimeChatRequest request) {
            this.lastRequest = request;
            return response;
        }

        @Override
        public void clearSession(String sessionId) {
            this.clearedSessionId = sessionId;
        }
    }
}
