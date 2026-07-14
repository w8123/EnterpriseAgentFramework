package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentStatisticsService;
import com.enterprise.ai.runtime.agent.RuntimeAgentStatisticsView;
import com.enterprise.ai.runtime.agent.RuntimeAgentIdentityRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentControllerTest {

    @Test
    void keepsPublicAgentRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeAgentController.class
                .getDeclaredMethod("list", Long.class, String.class);
        Method create = RuntimeAgentController.class
                .getDeclaredMethod("create", RuntimeAgentIdentityRequest.class);
        Method statistics = RuntimeAgentController.class
                .getDeclaredMethod("statistics", Long.class, String.class);
        Method get = RuntimeAgentController.class.getDeclaredMethod("get", String.class);
        Method update = RuntimeAgentController.class
                .getDeclaredMethod("update", String.class, RuntimeAgentIdentityRequest.class);
        Method delete = RuntimeAgentController.class.getDeclaredMethod("delete", String.class);

        assertArrayEquals(new String[] {"/api/agents"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/statistics"}, statistics.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void delegatesAgentCrudToRuntimeService() {
        RuntimeAgentService service = mock(RuntimeAgentService.class);
        RuntimeAgentStatisticsService statisticsService = mock(RuntimeAgentStatisticsService.class);
        RuntimeAgentController controller = new RuntimeAgentController(service, statisticsService);
        RuntimeAgentView view = view("agent-1");
        RuntimeAgentIdentityRequest request = request("agent-1");
        RuntimeAgentStatisticsView statistics = new RuntimeAgentStatisticsView(3, 2, 1, 2);
        when(service.list(7L, "orders")).thenReturn(List.of(view));
        when(statisticsService.statistics(7L, "orders")).thenReturn(statistics);
        when(service.create(request)).thenReturn(view);
        when(service.findById("agent-1")).thenReturn(Optional.of(view));
        when(service.update("agent-1", request)).thenReturn(view);
        when(service.delete("agent-1")).thenReturn(true);

        assertEquals(List.of(view), controller.list(7L, "orders").getBody());
        assertEquals(statistics, controller.statistics(7L, "orders").getBody());
        assertEquals(view, controller.create(request).getBody());
        assertEquals(view, controller.get("agent-1").getBody());
        assertEquals(view, controller.update("agent-1", request).getBody());
        ResponseEntity<Void> deleted = controller.delete("agent-1");

        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).list(7L, "orders");
        verify(statisticsService).statistics(7L, "orders");
        verify(service).create(request);
        verify(service).findById("agent-1");
        verify(service).update("agent-1", request);
        verify(service).delete("agent-1");
    }

    @Test
    void deleteReturnsNotFoundWhenAgentDoesNotExist() {
        RuntimeAgentService service = mock(RuntimeAgentService.class);
        RuntimeAgentController controller = new RuntimeAgentController(
                service,
                mock(RuntimeAgentStatisticsService.class));
        when(service.delete("missing")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private RuntimeAgentView view(String id) {
        return new RuntimeAgentView(
                id,
                7L,
                "orders",
                "orders-agent",
                "Orders Agent",
                null,
                "PROJECT",
                null,
                true,
                3L,
                3L,
                1,
                "ACTIVE",
                "AGENTSCOPE",
                2,
                null,
                null);
    }

    private RuntimeAgentIdentityRequest request(String id) {
        return new RuntimeAgentIdentityRequest(
                id, 7L, "orders", "orders-agent", "Orders Agent", null,
                "PROJECT", null, true);
    }
}
