package com.enterprise.ai.capability.catalog.graph;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/api-graph/projects/{projectId}")
public class CapabilityApiGraphSnapshotController {

    private final CapabilityApiGraphSnapshotService snapshotService;
    private final CapabilityApiGraphOperationsService operationsService;
    private final CapabilityApiGraphRegenerateService regenerateService;

    @GetMapping("/snapshot")
    public ResponseEntity<CapabilityApiGraphSnapshotView> snapshot(@PathVariable Long projectId) {
        return ResponseEntity.ok(snapshotService.loadSnapshot(projectId));
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<CapabilityApiGraphSnapshotView.EdgeView>> candidates(
            @PathVariable Long projectId,
            @RequestParam(required = false, defaultValue = "CANDIDATE") String status,
            @RequestParam(required = false) Double minConfidence) {
        return ResponseEntity.ok(operationsService.listCandidates(projectId, status, minConfidence));
    }

    @GetMapping("/tools/{toolName}/param-hints")
    public ResponseEntity<List<CapabilityApiGraphParamSourceHintView>> paramHints(
            @PathVariable Long projectId,
            @PathVariable String toolName) {
        return ResponseEntity.ok(operationsService.listParamHints(projectId, toolName));
    }

    @PostMapping("/infer")
    public ResponseEntity<CapabilityApiGraphRequests.InferResultDTO> infer(@PathVariable Long projectId) {
        return ResponseEntity.ok(operationsService.inferModelRefEdges(projectId));
    }

    @PostMapping("/infer/request-response")
    public ResponseEntity<CapabilityApiGraphRequests.InferResultDTO> inferRequestResponse(@PathVariable Long projectId) {
        return ResponseEntity.ok(operationsService.inferRequestResponseEdges(projectId));
    }

    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate(@PathVariable Long projectId) {
        try {
            return ResponseEntity.ok(regenerateService.regenerate(projectId));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "图谱重新生成失败" : ex.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new CapabilityApiGraphRequests.ApiErrorResponse(message));
        }
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable Long projectId) {
        try {
            return ResponseEntity.ok(regenerateService.rebuild(projectId));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "图谱重建失败" : ex.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new CapabilityApiGraphRequests.ApiErrorResponse(message));
        }
    }

    @PostMapping("/candidates/{edgeId}/confirm")
    public ResponseEntity<?> confirmCandidate(
            @PathVariable Long projectId,
            @PathVariable Long edgeId,
            @RequestBody(required = false) CapabilityApiGraphRequests.CandidateConfirmRequest request) {
        try {
            return ResponseEntity.ok(operationsService.confirmCandidate(projectId, edgeId, request));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/candidates/{edgeId}/reject")
    public ResponseEntity<?> rejectCandidate(
            @PathVariable Long projectId,
            @PathVariable Long edgeId,
            @RequestBody(required = false) CapabilityApiGraphRequests.CandidateRejectRequest request) {
        try {
            return ResponseEntity.ok(operationsService.rejectCandidate(projectId, edgeId, request));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/edges")
    public ResponseEntity<?> upsertEdge(
            @PathVariable Long projectId,
            @RequestBody(required = false) CapabilityApiGraphRequests.EdgeUpsertRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new CapabilityApiGraphRequests.ApiErrorResponse("请求体不能为空"));
        }
        try {
            return ResponseEntity.ok(operationsService.upsertEdge(projectId, request));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<Void> deleteEdge(@PathVariable Long projectId, @PathVariable Long edgeId) {
        return operationsService.deleteEdge(edgeId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/layout")
    public ResponseEntity<Void> saveLayout(
            @PathVariable Long projectId,
            @RequestBody(required = false) CapabilityApiGraphRequests.LayoutSaveRequest request) {
        operationsService.saveLayout(projectId, request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private ResponseEntity<CapabilityApiGraphRequests.ApiErrorResponse> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new CapabilityApiGraphRequests.ApiErrorResponse(ex.getMessage()));
    }
}
