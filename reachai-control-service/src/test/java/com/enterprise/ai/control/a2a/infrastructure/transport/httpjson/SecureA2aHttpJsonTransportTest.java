package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialCipher;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SecureA2aHttpJsonTransportTest {

    private final SecureA2aHttpJsonTransport transport = new SecureA2aHttpJsonTransport(
            mock(A2aOutboundTargetPolicy.class), mock(A2aCredentialCipher.class),
            new A2aHubProperties(), Clock.systemUTC());

    @Test
    void buildsTaskGetUriWithOpaqueIdTenantAndHistoryExactlyOnce() {
        A2aRemoteInterface target = new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a/v1/", "HTTP+JSON", "1.0",
                "tenant / east");

        var uri = transport.taskOperationUri(target, "task /?% 汉字", false, 20);

        assertEquals(
                "https://agent.example/a2a/v1/tasks/task%20%2F%3F%25%20%E6%B1%89%E5%AD%97"
                        + "?tenant=tenant%20%2F%20east&historyLength=20",
                uri.toASCIIString());
    }

    @Test
    void buildsTaskCancelUriWithoutGetQueryParameters() {
        A2aRemoteInterface target = new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a", "HTTP+JSON", "1.0", "tenant-a");

        var uri = transport.taskOperationUri(target, "remote-task-1", true, null);

        assertEquals("https://agent.example/a2a/tasks/remote-task-1:cancel", uri.toString());
    }

    @Test
    void preservesReviewedPercentEncodedBasePathExactlyOnce() {
        var uri = transport.operationUri(
                "https://agent.example/a2a/%E6%B1%89%E5%AD%97", "message:send");

        assertEquals(
                "https://agent.example/a2a/%E6%B1%89%E5%AD%97/message:send",
                uri.toASCIIString());
    }

    @Test
    void remoteInterfaceRejectsAuthorityOrRoutingAmbiguity() {
        assertThrows(RuntimeException.class, () -> new A2aRemoteInterface(
                "http-json", "https://user@agent.example/a2a", "HTTP+JSON", "1.0", null));
        assertThrows(RuntimeException.class, () -> new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a?route=other", "HTTP+JSON", "1.0", null));
        assertThrows(RuntimeException.class, () -> new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a#fragment", "HTTP+JSON", "1.0", null));
    }
}
