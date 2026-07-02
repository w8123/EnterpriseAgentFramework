package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.agent.RuntimeAgentToolReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAgentReferenceInternalControllerTest {

    @Test
    void keepsAgentReferenceInternalRouteShape() throws Exception {
        Method method = RuntimeAgentReferenceInternalController.class.getDeclaredMethod("listAgentToolReferences");

        assertArrayEquals(new String[] {"/internal/runtime/agent-tool-references"},
                method.getAnnotation(GetMapping.class).value());
    }

    @Test
    void returnsRuntimeAgentToolReferences() {
        RuntimeAgentToolReferenceService service = mock(RuntimeAgentToolReferenceService.class);
        RuntimeAgentReferenceInternalController controller = new RuntimeAgentReferenceInternalController(service);
        when(service.listAgentToolReferences()).thenReturn(List.of(
                new RuntimeAgentToolReferenceService.AgentToolReference(
                        "agent-1",
                        "Team Assistant",
                        List.of("orders_create"),
                        List.of("orders_skill"))
        ));

        ResponseEntity<List<RuntimeAgentReferenceInternalController.AgentToolReferenceView>> response =
                controller.listAgentToolReferences();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("agent-1", response.getBody().get(0).agentId());
        assertEquals(List.of("orders_create"), response.getBody().get(0).tools());
    }
}
