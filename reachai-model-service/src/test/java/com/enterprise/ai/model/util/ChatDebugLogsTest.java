package com.enterprise.ai.model.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.ai.model.service.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDebugLogsTest {
    @Test
    void debugLogsExcludeMessagesReasoningToolArgumentsAndSchemas() throws Exception {
        String secret = "REACHAI_PRIVATE_TEST_MARKER";
        ObjectMapper mapper = new ObjectMapper();
        ChatRequest.ChatMessage message = ChatRequest.ChatMessage.builder()
                .role("user").name(secret).toolCallId(secret)
                .content(secret).reasoningContent(secret)
                .toolCalls(mapper.readTree("[{\"function\":{\"arguments\":\"" + secret + "\"}}]"))
                .build();
        ChatRequest request = ChatRequest.builder().modelInstanceId("test-model")
                .messages(List.of(message)).tools(mapper.readTree("[{\"description\":\"" + secret + "\"}]"))
                .toolChoice(mapper.readTree("{\"name\":\"" + secret + "\"}"))
                .options(Map.of(secret, secret)).build();
        Logger logger = (Logger) LoggerFactory.getLogger("test.ChatDebugLogs");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            ChatDebugLogs.logChatRequest(logger, "test", request);
            String output = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertFalse(output.contains(secret));
            assertTrue(output.contains("role=user"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void embeddingDebugLogsExcludeInputText() {
        String secret = "REACHAI_PRIVATE_EMBEDDING_TEST_MARKER";
        Logger logger = (Logger) LoggerFactory.getLogger("test.EmbeddingDebugLogs");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            EmbeddingDebugLogs.logInputTexts(logger, "test", List.of(secret));
            String output = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertFalse(output.contains(secret));
            assertTrue(output.contains(Integer.toString(secret.length())));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
