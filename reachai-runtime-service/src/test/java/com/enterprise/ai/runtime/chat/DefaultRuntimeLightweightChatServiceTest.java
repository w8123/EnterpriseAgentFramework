package com.enterprise.ai.runtime.chat;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRuntimeLightweightChatServiceTest {

    @Test
    void callsModelGatewayAndStoresConversationHistory() {
        CapturingModelClient modelClient = new CapturingModelClient("first answer", "second answer");
        DefaultRuntimeLightweightChatService service =
                new DefaultRuntimeLightweightChatService(modelClient, new RuntimeChatMemoryStore(20));

        RuntimeChatResponse first = service.chat(RuntimeChatRequest.builder()
                .sessionId("session-1")
                .message("hello")
                .modelInstanceId("model-1")
                .build());
        RuntimeChatResponse second = service.chat(RuntimeChatRequest.builder()
                .sessionId("session-1")
                .message("again")
                .modelInstanceId("model-1")
                .build());

        assertEquals("first answer", first.getAnswer());
        assertEquals("second answer", second.getAnswer());
        assertEquals("session-1", second.getSessionId());
        assertEquals(2, modelClient.requests.size());
        ModelChatRequest secondRequest = modelClient.requests.get(1);
        assertEquals("model-1", secondRequest.getModelInstanceId());
        assertMessage(secondRequest.getMessages().get(0), "system");
        assertMessage(secondRequest.getMessages().get(1), "user", "hello");
        assertMessage(secondRequest.getMessages().get(2), "assistant", "first answer");
        assertMessage(secondRequest.getMessages().get(3), "user", "again");
    }

    @Test
    void requiresExplicitModelInstanceId() {
        CapturingModelClient modelClient = new CapturingModelClient("unused");
        DefaultRuntimeLightweightChatService service =
                new DefaultRuntimeLightweightChatService(modelClient, new RuntimeChatMemoryStore(20));

        RuntimeChatResponse response = service.chat(RuntimeChatRequest.builder()
                .message("hello")
                .build());

        assertTrue(response.getAnswer().contains("modelInstanceId is required"));
        assertTrue(modelClient.requests.isEmpty());
    }

    @Test
    void clearSessionDropsConversationHistory() {
        CapturingModelClient modelClient = new CapturingModelClient("first answer", "second answer");
        DefaultRuntimeLightweightChatService service =
                new DefaultRuntimeLightweightChatService(modelClient, new RuntimeChatMemoryStore(20));
        service.chat(RuntimeChatRequest.builder()
                .sessionId("session-1")
                .message("hello")
                .modelInstanceId("model-1")
                .build());

        service.clearSession("session-1");
        service.chat(RuntimeChatRequest.builder()
                .sessionId("session-1")
                .message("again")
                .modelInstanceId("model-1")
                .build());

        ModelChatRequest secondRequest = modelClient.requests.get(1);
        assertEquals(2, secondRequest.getMessages().size());
        assertMessage(secondRequest.getMessages().get(0), "system");
        assertMessage(secondRequest.getMessages().get(1), "user", "again");
        assertFalse(secondRequest.getMessages().stream()
                .anyMatch(message -> "first answer".equals(message.getContent())));
    }

    private void assertMessage(ChatMessage message, String role) {
        assertEquals(role, message.getRole());
    }

    private void assertMessage(ChatMessage message, String role, String content) {
        assertEquals(role, message.getRole());
        assertEquals(content, message.getContent());
    }

    private static final class CapturingModelClient implements RuntimeModelServiceClient {
        private final List<String> answers;
        private final List<ModelChatRequest> requests = new ArrayList<>();
        private int nextAnswer;

        private CapturingModelClient(String... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public ModelChatResult chat(ModelChatRequest request) {
            requests.add(request);
            String answer = answers.get(Math.min(nextAnswer, answers.size() - 1));
            nextAnswer++;
            return new ModelChatResult(0, "ok",
                    new ModelChatData(answer, "gpt-test", "openai", null, null, null, "stop"));
        }
    }
}
