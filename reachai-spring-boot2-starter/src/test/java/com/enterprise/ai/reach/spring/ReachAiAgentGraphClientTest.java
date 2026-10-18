package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.graph.ReachAgentGraph;
import com.enterprise.ai.reach.sdk.graph.ReachGraphSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReachAiAgentGraphClientTest {

    @Test
    void postsGraphsWithRequestBoundProjectSignature() throws Exception {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com/");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        AtomicReference<String> url = new AtomicReference<String>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<Map<String, String>>();
        AtomicReference<byte[]> body = new AtomicReference<byte[]>();
        ReachAiAgentGraphClient client = new ReachAiAgentGraphClient(properties, (method, target, sent, payload) -> {
            url.set(target);
            headers.set(sent);
            body.set((byte[]) payload);
            return "{}";
        });

        client.sync(Collections.singletonList(new ReachAgentGraph("order-helper", "订单助手", null, null, null,
                null, null, new ReachGraphSpec(), null)));

        assertEquals("https://reachai.example.com/api/registry/projects/demo/agent-graphs/sync", url.get());
        assertEquals("demo-key", headers.get().get(ReachAiProjectRequestHeaders.APP_KEY));
        assertNotNull(headers.get().get(ReachAiProjectRequestHeaders.SIGNATURE));
        assertEquals(sha256Hex(body.get()), headers.get().get(ReachAiProjectRequestHeaders.BODY_SHA256));
        JsonNode json = new ObjectMapper().readTree(body.get());
        assertEquals("STARTER", json.get("source").asText());
        assertEquals("order-helper", json.get("graphs").get(0).get("code").asText());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
