package com.enterprise.ai.capability.catalog.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityToolRetrievalControllerTest {

    @Test
    void keepsToolRetrievalRouteShapeOnCapabilityService() throws Exception {
        assertArrayEquals(new String[] {"/api/tool-retrieval"},
                CapabilityToolRetrievalController.class.getAnnotation(RequestMapping.class).value());

        Method search = CapabilityToolRetrievalController.class.getDeclaredMethod(
                "search", CapabilityToolRetrievalController.SearchRequest.class);
        Method rebuild = CapabilityToolRetrievalController.class.getDeclaredMethod(
                "rebuild", CapabilityToolRetrievalController.RebuildRequest.class);
        Method status = CapabilityToolRetrievalController.class.getDeclaredMethod("status", String.class);
        Method health = CapabilityToolRetrievalController.class.getDeclaredMethod("health");

        assertArrayEquals(new String[] {"/search"}, search.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/rebuild"}, rebuild.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/rebuild/status"}, status.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/health"}, health.getAnnotation(GetMapping.class).value());
    }

    @Test
    void rejectsBlankSearchQuery() {
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class),
                mock(CapabilityToolRetrievalRebuildManager.class));

        ResponseEntity<CapabilityToolRetrievalController.SearchResponse> response =
                controller.search(new CapabilityToolRetrievalController.SearchRequest(
                        "  ", 5, null, null, null, true, true, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("query 不能为空", response.getBody().message());
    }

    @Test
    void searchesToolCandidatesLocally() {
        CapabilityToolRetrievalService service = mock(CapabilityToolRetrievalService.class);
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(
                service,
                mock(CapabilityToolRetrievalRebuildManager.class));
        when(service.retrieve("订单", new CapabilityRetrievalScope(List.of(7L), null, null, true, false), 3, 0.1))
                .thenReturn(List.of(new CapabilityToolCandidate(11L, "order.create", 7L, 2L, 0.92f,
                        "创建订单\norder.create")));

        ResponseEntity<CapabilityToolRetrievalController.SearchResponse> response =
                controller.search(new CapabilityToolRetrievalController.SearchRequest(
                        "订单", 3, List.of(7L), null, null, true, false, 0.1));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().candidates().size());
        assertEquals("order.create", response.getBody().candidates().get(0).toolName());
    }

    @Test
    void startsRebuildTask() {
        CapabilityToolRetrievalRebuildManager rebuildManager = mock(CapabilityToolRetrievalRebuildManager.class);
        when(rebuildManager.start("embed-main")).thenReturn("task-1");
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class),
                rebuildManager);

        ResponseEntity<?> response = controller.rebuild(new CapabilityToolRetrievalController.RebuildRequest("embed-main"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        CapabilityToolRetrievalController.StartResponse body =
                (CapabilityToolRetrievalController.StartResponse) response.getBody();
        assertNotNull(body);
        assertEquals("task-1", body.taskId());
        verify(rebuildManager).start("embed-main");
    }

    @Test
    void getsLatestRebuildStatus() {
        CapabilityToolRetrievalRebuildManager rebuildManager = mock(CapabilityToolRetrievalRebuildManager.class);
        CapabilityToolEmbeddingRebuildTask task = new CapabilityToolEmbeddingRebuildTask();
        task.setTaskId("task-1");
        task.setStage(CapabilityToolEmbeddingRebuildTask.Stage.RUNNING);
        task.setTotalSteps(3);
        task.setCompletedSteps(1);
        when(rebuildManager.latest()).thenReturn(java.util.Optional.of(task));
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class),
                rebuildManager);

        ResponseEntity<CapabilityToolRetrievalController.TaskDTO> response = controller.status(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("task-1", response.getBody().taskId());
        assertEquals("RUNNING", response.getBody().stage());
        assertEquals(3, response.getBody().totalSteps());
        assertEquals(1, response.getBody().completedSteps());
    }
}
