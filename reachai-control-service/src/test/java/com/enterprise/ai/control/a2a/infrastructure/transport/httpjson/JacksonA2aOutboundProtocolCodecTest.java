package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonA2aOutboundProtocolCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonA2aOutboundProtocolCodec codec =
            new JacksonA2aOutboundProtocolCodec(objectMapper);

    @Test
    void encodesA2a10MessageAndKeepsIdempotencyStableAcrossRemoteResourceBinding() throws Exception {
        var first = codec.encodeTextMessage(
                "tenant-a", "message-1", null, null, "Review this change",
                "review", "INTERNAL", List.of("text/plain"), 8);
        var continuation = codec.encodeTextMessage(
                "tenant-a", "message-1", "remote-context", "remote-task",
                "Review this change", "review", "INTERNAL", List.of("text/plain"), 8);

        JsonNode body = objectMapper.readTree(first.requestBody());
        assertEquals("message-1", body.path("message").path("messageId").asText());
        assertEquals("ROLE_USER", body.path("message").path("role").asText());
        assertEquals("text/plain", body.path("message").path("parts").get(0)
                .path("mediaType").asText());
        assertEquals("review", body.path("metadata")
                .path("reachai.a2a.protocolSkillId").asText());
        assertEquals("INTERNAL", body.path("metadata")
                .path("reachai.a2a.contentClassification").asText());
        assertFalse(body.path("configuration").path("returnImmediately").asBoolean(true));
        assertEquals(first.idempotencySha256(), continuation.idempotencySha256());
        assertNotEquals(first.messageSha256(), continuation.messageSha256());
    }

    @Test
    void decodesExactlyOneTaskOrDirectAgentMessage() {
        var task = codec.decodeSendResponse(json("""
                {
                  "task": {
                    "id": "remote-task-1",
                    "contextId": "remote-context-1",
                    "status": {
                      "state": "TASK_STATE_COMPLETED",
                      "timestamp": "2026-08-24T01:02:03Z",
                      "message": {
                        "messageId": "agent-message-1",
                        "role": "ROLE_AGENT",
                        "parts": [{"text": "Approved", "mediaType": "text/plain"}]
                      }
                    },
                    "artifacts": [{
                      "artifactId": "report-1",
                      "name": "Review report",
                      "parts": [{"data": {"decision": "approved"}, "mediaType": "application/json"}]
                    }]
                  }
                }
                """));
        assertEquals(A2aTaskState.TASK_STATE_COMPLETED, task.state());
        assertEquals("remote-task-1", task.remoteTaskId());
        assertEquals("remote-context-1", task.remoteContextId());
        assertEquals(1, task.messages().size());
        assertEquals(1, task.artifacts().size());

        var direct = codec.decodeSendResponse(json("""
                {
                  "message": {
                    "messageId": "agent-message-direct",
                    "contextId": "remote-context-2",
                    "role": "ROLE_AGENT",
                    "parts": [{"text": "Done"}]
                  }
                }
                """));
        assertEquals(A2aTaskState.TASK_STATE_COMPLETED, direct.state());
        assertEquals("remote-context-2", direct.remoteContextId());
        assertEquals(1, direct.messages().size());
    }

    @Test
    void rejectsAmbiguousDuplicateOrUnsafeRemoteResponses() {
        assertInvalid("""
                {"task":{"id":"t","contextId":"c","status":{"state":"TASK_STATE_COMPLETED"}},
                 "message":{"messageId":"m","role":"ROLE_AGENT","parts":[{"text":"x"}]}}
                """);
        assertInvalid("""
                {"message":{"messageId":"m","messageId":"shadow","role":"ROLE_AGENT",
                 "parts":[{"text":"x"}]}}
                """);
        assertInvalid("""
                {"message":{"messageId":"m","role":"ROLE_AGENT",
                 "parts":[{"raw":"not-base64***","mediaType":"application/octet-stream"}]}}
                """);
        assertInvalid("""
                {"message":{"messageId":"m","role":"ROLE_AGENT",
                 "parts":[{"url":"https://user:secret@example.com/file","mediaType":"text/uri-list"}]}}
                """);
        assertInvalid("""
                {"message":{"messageId":"m","role":"ROLE_USER","parts":[{"text":"x"}]}}
                """);
    }

    @Test
    void encodesCanonicalCancelAndDecodesDirectTaskOperations() throws Exception {
        var encoded = codec.encodeCancelTask("tenant-a", "remote-task-1");
        JsonNode cancel = objectMapper.readTree(encoded.requestBody());

        assertEquals("tenant-a", cancel.path("tenant").asText());
        assertEquals("remote-task-1", cancel.path("id").asText());
        assertTrue(cancel.path("metadata").isObject());
        assertEquals(64, encoded.requestSha256().length());

        var decoded = codec.decodeTaskResponse(json("""
                {
                  "id": "remote-task-1",
                  "contextId": "remote-context-1",
                  "status": {
                    "state": "TASK_STATE_WORKING",
                    "timestamp": "2026-08-24T01:02:03Z"
                  },
                  "history": [{
                    "messageId": "agent-message-1",
                    "role": "ROLE_AGENT",
                    "parts": [{"text": "Still working", "mediaType": "text/plain"}]
                  }]
                }
                """));

        assertEquals(A2aTaskState.TASK_STATE_WORKING, decoded.state());
        assertEquals("remote-task-1", decoded.remoteTaskId());
        assertEquals("remote-context-1", decoded.remoteContextId());
        assertEquals(1, decoded.messages().size());
    }

    @Test
    void rejectsWrappedOrNonTaskTaskOperationResponses() {
        A2aDomainException wrapped = assertThrows(A2aDomainException.class,
                () -> codec.decodeTaskResponse(json("""
                        {"task":{"id":"t","contextId":"c",
                         "status":{"state":"TASK_STATE_WORKING"}}}
                        """)));
        assertEquals("A2A_INVALID_AGENT_RESPONSE", wrapped.code());

        A2aDomainException message = assertThrows(A2aDomainException.class,
                () -> codec.decodeTaskResponse(json("""
                        {"message":{"messageId":"m","role":"ROLE_AGENT",
                         "parts":[{"text":"x"}]}}
                        """)));
        assertEquals("A2A_INVALID_AGENT_RESPONSE", message.code());
    }

    private void assertInvalid(String value) {
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> codec.decodeSendResponse(json(value)));
        assertEquals("A2A_INVALID_AGENT_RESPONSE", failure.code());
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
