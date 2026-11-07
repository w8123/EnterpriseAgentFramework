package com.enterprise.ai.reach.sdk.auth;

import com.enterprise.ai.reach.sdk.client.ReachAiClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java and other language SDKs consume the same, independently generated examples. */
class ReachAiSharedProtocolVectorsTest {
    private static final Path VECTORS = Paths.get("..", "ai-runtime-contract", "src", "main", "resources",
            "contracts", "reachai-sdk-protocol-v1.vectors.json");

    @Test
    void javaSdkMatchesSharedProtocolVectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(VECTORS));
        assertEquals("reachai-sdk-protocol-v1-test-vectors", root.path("schema").asText());
        assertTrue(root.path("registry").isArray() && root.path("registry").size() > 0);
        assertTrue(root.path("projectRequest").isArray() && root.path("projectRequest").size() > 0);
        assertTrue(root.path("invocation").isArray() && root.path("invocation").size() > 0);

        for (JsonNode vector : root.path("registry")) {
            ReachAiClientConfig config = ReachAiClientConfig.builder()
                    .endpoint("https://reachai.example.com")
                    .projectCode(text(vector, "projectCode"))
                    .appKey(text(vector, "appKey"))
                    .appSecret(text(vector, "appSecret")).build();
            assertEquals(text(vector, "signature"), ReachAiSigner.sign(config,
                    text(vector, "timestamp"), text(vector, "nonce")).getSignature(), text(vector, "id"));
        }

        for (JsonNode vector : root.path("projectRequest")) {
            byte[] body = text(vector, "body").getBytes(StandardCharsets.UTF_8);
            ReachAiProjectRequestHeaders headers = ReachAiProjectRequestSigner.sign(
                    text(vector, "appKey"), text(vector, "appSecret"), text(vector, "projectCode"),
                    text(vector, "method"), text(vector, "path"), body,
                    text(vector, "timestamp"), text(vector, "nonce"));
            assertEquals(text(vector, "bodySha256"), headers.getBodySha256(), text(vector, "id"));
            assertEquals(text(vector, "signature"), headers.getSignature(), text(vector, "id"));
            assertNotEquals(headers.getBodySha256(), ReachAiProjectRequestSigner.bodySha256Hex(
                    (text(vector, "body") + " ").getBytes(StandardCharsets.UTF_8)));
        }

        for (JsonNode vector : root.path("invocation")) {
            JsonNode input = vector.path("claims");
            ReachAiInvocationClaims claims = ReachAiInvocationClaims.builder()
                    .projectCode(text(input, "projectCode"))
                    .appKey(text(input, "appKey"))
                    .capabilityName(text(input, "capabilityName"))
                    .tenantId(text(input, "tenantId"))
                    .externalUserId(text(input, "externalUserId"))
                    .userName(text(input, "userName"))
                    .roles(Collections.singletonList(input.path("roles").get(0).asText()))
                    .traceId(text(input, "traceId"))
                    .jti(text(input, "jti")).build();
            String token = ReachAiInvocationToken.sign(text(vector, "appSecret"), claims,
                    vector.path("issuedAtMillis").asLong(), vector.path("ttlSeconds").asInt());
            assertEquals(text(vector, "token"), token, text(vector, "id"));
            ReachAiInvocationClaims verified = ReachAiInvocationToken.verify(text(vector, "appSecret"), token,
                    text(input, "projectCode"), text(input, "capabilityName"), vector.path("issuedAtMillis").asLong());
            assertEquals(text(input, "userName"), verified.getUserName());
            assertThrows(IllegalArgumentException.class, () -> ReachAiInvocationToken.verify(
                    text(vector, "appSecret"), token, text(input, "projectCode"), "order.delete",
                    vector.path("issuedAtMillis").asLong()));
            assertThrows(IllegalArgumentException.class, () -> ReachAiInvocationToken.verify(
                    text(vector, "appSecret"), token, text(input, "projectCode"), text(input, "capabilityName"),
                    vector.path("issuedAtMillis").asLong() + (vector.path("ttlSeconds").asLong() + 1) * 1000));
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }
}
