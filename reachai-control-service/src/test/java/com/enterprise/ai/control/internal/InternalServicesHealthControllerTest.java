package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.client.capability.CapabilityHealthClient;
import com.enterprise.ai.control.client.runtime.RuntimeHealthClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalServicesHealthControllerTest {

    @Test
    void aggregatesRuntimeAndCapabilityHealth() {
        RuntimeHealthClient runtimeHealthClient = mock(RuntimeHealthClient.class);
        CapabilityHealthClient capabilityHealthClient = mock(CapabilityHealthClient.class);
        when(runtimeHealthClient.health()).thenReturn(Map.of(
                "service", "reachai-runtime-service",
                "status", "UP"
        ));
        when(capabilityHealthClient.health()).thenReturn(Map.of(
                "service", "reachai-capability-service",
                "status", "UP"
        ));

        InternalServicesHealthController controller = new InternalServicesHealthController(
                runtimeHealthClient,
                capabilityHealthClient
        );

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));
        assertEquals(Map.of(
                "runtime", Map.of("service", "reachai-runtime-service", "status", "UP"),
                "capability", Map.of("service", "reachai-capability-service", "status", "UP")
        ), body.get("services"));
    }
}
