package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiBusinessIndexClientTest {

    @Test
    void signsAndSendsTheExactSerializedJsonBytes() {
        ReachAiRegistryProperties properties = properties();
        RecordingTransport transport = new RecordingTransport();
        ReachAiBusinessIndexClient client = new ReachAiBusinessIndexClient(properties, transport);

        client.upsert("orders_idx", Collections.singletonMap("bizId", "O-1"));

        assertEquals("POST", transport.method);
        String path = "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/upsert";
        assertTrue(transport.url.endsWith(path));
        assertTrue(transport.body instanceof byte[]);
        byte[] exactBody = (byte[]) transport.body;
        assertEquals(ReachAiProjectRequestSigner.bodySha256Hex(exactBody),
                transport.headers.get(ReachAiProjectRequestHeaders.BODY_SHA256));
        String canonical = ReachAiProjectRequestSigner.canonical(
                "POST", path, "orders", "rak_orders",
                transport.headers.get(ReachAiProjectRequestHeaders.TIMESTAMP),
                transport.headers.get(ReachAiProjectRequestHeaders.NONCE),
                transport.headers.get(ReachAiProjectRequestHeaders.BODY_SHA256));
        assertEquals(ReachAiSigner.sign("ras_orders", canonical),
                transport.headers.get(ReachAiProjectRequestHeaders.SIGNATURE));
    }

    @Test
    void deleteBindsBizIdInsideSignedJsonInsteadOfAnAmbiguousPathSegment() {
        RecordingTransport transport = new RecordingTransport();
        ReachAiBusinessIndexClient client = new ReachAiBusinessIndexClient(properties(), transport);

        client.deleteRecord("orders_idx", "O/1");

        assertEquals("POST", transport.method);
        assertTrue(transport.url.endsWith("/delete"));
        assertTrue(new String((byte[]) transport.body, StandardCharsets.UTF_8).contains("O/1"));
        assertEquals(ReachAiProjectRequestSigner.bodySha256Hex((byte[]) transport.body),
                transport.headers.get(ReachAiProjectRequestHeaders.BODY_SHA256));
    }

    private static ReachAiRegistryProperties properties() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("rak_orders");
        properties.getRegistry().setAppSecret("ras_orders");
        properties.getProject().setCode("orders");
        return properties;
    }

    private static final class RecordingTransport implements ReachAiRegistryTransport {
        private String method;
        private String url;
        private Map<String, String> headers;
        private Object body;

        @Override
        public String exchange(String method, String url, Map<String, String> headers, Object body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
            return "{}";
        }
    }
}
