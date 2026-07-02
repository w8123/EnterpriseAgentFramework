package com.enterprise.ai.capability.catalog.graph;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityApiGraphSnapshotControllerTest {

    @Test
    void exposesApiGraphSnapshotOnCapabilityService() throws Exception {
        RequestMapping controllerMapping =
                CapabilityApiGraphSnapshotController.class.getAnnotation(RequestMapping.class);
        Method snapshot = CapabilityApiGraphSnapshotController.class.getDeclaredMethod("snapshot", Long.class);
        Method candidates = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "candidates", Long.class, String.class, Double.class);
        Method confirm = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "confirmCandidate", Long.class, Long.class, CapabilityApiGraphRequests.CandidateConfirmRequest.class);
        Method reject = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "rejectCandidate", Long.class, Long.class, CapabilityApiGraphRequests.CandidateRejectRequest.class);
        Method upsertEdge = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "upsertEdge", Long.class, CapabilityApiGraphRequests.EdgeUpsertRequest.class);
        Method deleteEdge = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "deleteEdge", Long.class, Long.class);
        Method saveLayout = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "saveLayout", Long.class, CapabilityApiGraphRequests.LayoutSaveRequest.class);
        Method paramHints = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "paramHints", Long.class, String.class);
        Method infer = CapabilityApiGraphSnapshotController.class.getDeclaredMethod("infer", Long.class);
        Method inferRequestResponse = CapabilityApiGraphSnapshotController.class.getDeclaredMethod(
                "inferRequestResponse", Long.class);
        Method regenerate = CapabilityApiGraphSnapshotController.class.getDeclaredMethod("regenerate", Long.class);
        Method rebuild = CapabilityApiGraphSnapshotController.class.getDeclaredMethod("rebuild", Long.class);

        assertArrayEquals(new String[] {"/api/api-graph/projects/{projectId}"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/snapshot"}, snapshot.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/candidates"}, candidates.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/candidates/{edgeId}/confirm"}, confirm.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/candidates/{edgeId}/reject"}, reject.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/edges"}, upsertEdge.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/edges/{edgeId}"}, deleteEdge.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/layout"}, saveLayout.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/tools/{toolName}/param-hints"}, paramHints.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/infer"}, infer.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/infer/request-response"},
                inferRequestResponse.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/regenerate"}, regenerate.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/rebuild"}, rebuild.getAnnotation(PostMapping.class).value());
    }

    @Test
    void returnsSnapshotFromCapabilityTables() {
        CapabilityApiGraphSnapshotService service = mock(CapabilityApiGraphSnapshotService.class);
        CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
        CapabilityApiGraphRegenerateService regenerateService = mock(CapabilityApiGraphRegenerateService.class);
        CapabilityApiGraphSnapshotController controller =
                new CapabilityApiGraphSnapshotController(service, operations, regenerateService);
        CapabilityApiGraphSnapshotView snapshot = new CapabilityApiGraphSnapshotView(
                List.of(new CapabilityApiGraphSnapshotView.NodeView(
                        1L, 7L, "API", 11L, null, "createOrder", null, "{}")),
                List.of(),
                List.of(new CapabilityApiGraphSnapshotView.LayoutView(1L, 10.0, 20.0, "{}")));
        when(service.loadSnapshot(7L)).thenReturn(snapshot);

        ResponseEntity<CapabilityApiGraphSnapshotView> response = controller.snapshot(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(snapshot, response.getBody());
        verify(service).loadSnapshot(7L);
    }

    @Test
    void delegatesCandidateAndLayoutOperations() {
        CapabilityApiGraphSnapshotService snapshots = mock(CapabilityApiGraphSnapshotService.class);
        CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
        CapabilityApiGraphRegenerateService regenerateService = mock(CapabilityApiGraphRegenerateService.class);
        CapabilityApiGraphSnapshotController controller =
                new CapabilityApiGraphSnapshotController(snapshots, operations, regenerateService);
        CapabilityApiGraphSnapshotView.EdgeView edge = new CapabilityApiGraphSnapshotView.EdgeView(
                2L, 7L, 1L, 3L, "REQUEST_REF", "auto", 0.9, "CANDIDATE",
                null, null, null, null, null, null, true);
        when(operations.listCandidates(7L, "CANDIDATE", 0.5)).thenReturn(List.of(edge));
        CapabilityApiGraphParamSourceHintView hint = new CapabilityApiGraphParamSourceHintView(
                "request.buyerId", "buyerId", "createOrder",
                "result.userId", "userId", "queryUser", 0.9);
        when(operations.listParamHints(7L, "createOrder")).thenReturn(List.of(hint));
        when(operations.inferModelRefEdges(7L)).thenReturn(new CapabilityApiGraphRequests.InferResultDTO(2));
        when(operations.inferRequestResponseEdges(7L)).thenReturn(new CapabilityApiGraphRequests.InferResultDTO(3));
        CapabilityApiGraphSnapshotView regenerated = new CapabilityApiGraphSnapshotView(List.of(), List.of(), List.of());
        when(regenerateService.regenerate(7L)).thenReturn(regenerated);
        when(regenerateService.rebuild(7L)).thenReturn(regenerated);

        ResponseEntity<List<CapabilityApiGraphSnapshotView.EdgeView>> candidates =
                controller.candidates(7L, "CANDIDATE", 0.5);
        ResponseEntity<Void> layout = controller.saveLayout(7L, new CapabilityApiGraphRequests.LayoutSaveRequest(List.of()));
        ResponseEntity<List<CapabilityApiGraphParamSourceHintView>> hints = controller.paramHints(7L, "createOrder");
        ResponseEntity<CapabilityApiGraphRequests.InferResultDTO> modelRef = controller.infer(7L);
        ResponseEntity<CapabilityApiGraphRequests.InferResultDTO> requestResponse = controller.inferRequestResponse(7L);
        ResponseEntity<?> regenerateResponse = controller.regenerate(7L);
        ResponseEntity<?> rebuildResponse = controller.rebuild(7L);

        assertEquals(List.of(edge), candidates.getBody());
        assertEquals(List.of(hint), hints.getBody());
        assertEquals(2, modelRef.getBody().generated());
        assertEquals(3, requestResponse.getBody().generated());
        assertEquals(regenerated, regenerateResponse.getBody());
        assertEquals(regenerated, rebuildResponse.getBody());
        assertEquals(HttpStatus.NO_CONTENT, layout.getStatusCode());
        verify(operations).saveLayout(7L, new CapabilityApiGraphRequests.LayoutSaveRequest(List.of()));
    }

    @Test
    void returnsBadRequestForInvalidEdgeWrite() {
        CapabilityApiGraphSnapshotService snapshots = mock(CapabilityApiGraphSnapshotService.class);
        CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
        CapabilityApiGraphRegenerateService regenerateService = mock(CapabilityApiGraphRegenerateService.class);
        CapabilityApiGraphSnapshotController controller =
                new CapabilityApiGraphSnapshotController(snapshots, operations, regenerateService);

        ResponseEntity<?> response = controller.upsertEdge(7L, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new CapabilityApiGraphRequests.ApiErrorResponse("请求体不能为空"), response.getBody());
    }
}
