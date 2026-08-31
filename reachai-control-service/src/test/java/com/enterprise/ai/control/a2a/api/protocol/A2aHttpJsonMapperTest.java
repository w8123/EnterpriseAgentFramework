package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;
import com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.TaskResource;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels;

import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Message;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Part;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageConfiguration;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aHttpJsonMapperTest {

    private A2aHttpJsonMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new A2aHttpJsonMapper(new ObjectMapper(), new A2aHubProperties());
    }

    @Test
    void createsStableCamelCaseMessageWithoutLegacyKindAndIgnoresIdentityMetadata() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> metadata = new LinkedHashMap<>();
        metadata.put("userId", TextNode.valueOf("self-asserted-user"));
        metadata.put("dataClassification", TextNode.valueOf("internal"));
        SendMessageRequest request = new SendMessageRequest(null,
                new Message("msg-1", null, null, "ROLE_USER",
                        List.of(new Part("hello", null, null, null, null, null, "text/plain")),
                        metadata, null, null),
                new SendMessageConfiguration(List.of("text/plain"), null, 5, true),
                Map.of());

        SendCommand first = mapper.toCommand(request);
        SendCommand second = mapper.toCommand(request);
        String canonical = new String(first.canonicalMessage(), StandardCharsets.UTF_8);

        assertEquals(first.payloadSha256(), second.payloadSha256());
        assertEquals("INTERNAL", first.contentClassification());
        assertEquals("{}", first.safeMetadataJson());
        assertTrue(canonical.contains("\"messageId\":\"msg-1\""));
        assertTrue(canonical.contains("\"userId\":\"self-asserted-user\""));
        assertFalse(canonical.contains("\"kind\""));
    }

    @Test
    void enforcesPartOneOfBase64AndHttpsRules() {
        A2aDomainException oneOf = assertThrows(A2aDomainException.class, () -> mapper.toCommand(
                request(new Part("text", "dGV4dA==", null, null, null, null, null))));
        assertEquals("A2A_INVALID_ARGUMENT", oneOf.code());

        A2aDomainException base64 = assertThrows(A2aDomainException.class, () -> mapper.toCommand(
                request(new Part(null, "not-base64", null, null, null, null, null))));
        assertEquals("A2A_INVALID_ARGUMENT", base64.code());

        A2aDomainException url = assertThrows(A2aDomainException.class, () -> mapper.toCommand(
                request(new Part(null, null, "http://127.0.0.1/file", null, null, null, null))));
        assertEquals("A2A_INVALID_ARGUMENT", url.code());
    }

    @Test
    void preservesArtifactOmissionAndProducesOpaquePaginationTokens() {
        TaskResource resource = new TaskResource("task-1", "ctx-1",
                A2aTaskState.TASK_STATE_SUBMITTED, LocalDateTime.of(2026, 8, 23, 10, 0),
                null, null, null, "NONE", List.of(), null);

        A2aProtocolModels.Task task = mapper.toTask(resource);
        A2aProtocolModels.ListTasksResponse response = mapper.toListResponse(
                List.of(resource), 2, 1, 0);

        assertEquals(null, task.artifacts());
        assertNotNull(response.nextPageToken());
        assertFalse(response.nextPageToken().isBlank());
        assertEquals(1, mapper.decodeOffset(response.nextPageToken()));
    }

    private SendMessageRequest request(Part part) {
        return new SendMessageRequest(null,
                new Message("msg-1", null, null, "ROLE_USER", List.of(part), null, null, null),
                null, null);
    }
}
