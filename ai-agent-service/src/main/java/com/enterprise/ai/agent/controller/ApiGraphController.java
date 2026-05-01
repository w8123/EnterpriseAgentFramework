package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.graph.ApiGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 接口图谱 REST：扫描项目维度的节点 / 边 / 布局读写。
 * 路径前缀 {@code /api/api-graph/projects/{projectId}}，与 {@code /api/scan-projects/{id}} 平行。
 */
@RestController
@RequestMapping("/api/api-graph/projects/{projectId}")
@RequiredArgsConstructor
public class ApiGraphController {

    private final ApiGraphService apiGraphService;

    /** 拉取整张图（节点 + 边 + 布局）；规模 < 5K 节点时一次拉够，前端 G6 渲染。 */
    @GetMapping("/snapshot")
    public ResponseEntity<SnapshotDTO> snapshot(@PathVariable Long projectId) {
        ApiGraphService.ApiGraphSnapshot snap = apiGraphService.loadGraph(projectId);
        return ResponseEntity.ok(SnapshotDTO.from(snap));
    }

    /** 重新从 scan_project_tool / scan_module 投影节点（同时跑一次 MODEL_REF 自动推断）。 */
    @PostMapping("/rebuild")
    public ResponseEntity<SnapshotDTO> rebuild(@PathVariable Long projectId) {
        apiGraphService.rebuildForProject(projectId);
        return snapshot(projectId);
    }

    /** 仅触发 MODEL_REF 紫色虚线边重新推断（不动节点）。 */
    @PostMapping("/infer")
    public ResponseEntity<InferResultDTO> infer(@PathVariable Long projectId) {
        int generated = apiGraphService.inferModelRefEdges(projectId);
        return ResponseEntity.ok(new InferResultDTO(generated));
    }

    /** Phase 4.1：推断 REQUEST_REF / RESPONSE_REF 候选边，默认只进入候选池。 */
    @PostMapping("/infer/request-response")
    public ResponseEntity<InferResultDTO> inferRequestResponse(@PathVariable Long projectId) {
        int generated = apiGraphService.inferRequestResponseEdges(projectId);
        return ResponseEntity.ok(new InferResultDTO(generated));
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<EdgeDTO>> candidates(@PathVariable Long projectId,
                                                    @RequestParam(required = false, defaultValue = "CANDIDATE") String status,
                                                    @RequestParam(required = false) Double minConfidence) {
        List<EdgeDTO> edges = apiGraphService.listCandidates(projectId, status, minConfidence)
                .stream()
                .map(EdgeDTO::from)
                .toList();
        return ResponseEntity.ok(edges);
    }

    @PostMapping("/candidates/{edgeId}/confirm")
    public ResponseEntity<?> confirmCandidate(@PathVariable Long projectId,
                                              @PathVariable Long edgeId,
                                              @RequestBody(required = false) CandidateConfirmRequest request) {
        try {
            ApiGraphEdgeEntity saved = apiGraphService.confirmCandidate(
                    projectId,
                    edgeId,
                    request == null ? null : request.confirmedBy()
            );
            return ResponseEntity.ok(EdgeDTO.from(saved));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/candidates/{edgeId}/reject")
    public ResponseEntity<?> rejectCandidate(@PathVariable Long projectId,
                                             @PathVariable Long edgeId,
                                             @RequestBody(required = false) CandidateRejectRequest request) {
        try {
            ApiGraphEdgeEntity saved = apiGraphService.rejectCandidate(
                    projectId,
                    edgeId,
                    request == null ? null : request.rejectReason()
            );
            return ResponseEntity.ok(EdgeDTO.from(saved));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/tools/{toolName}/param-hints")
    public ResponseEntity<List<ParamSourceHintDTO>> paramHints(@PathVariable Long projectId,
                                                               @PathVariable String toolName) {
        List<ParamSourceHintDTO> hints = apiGraphService.buildParamSourceHints(projectId, toolName)
                .stream()
                .map(ParamSourceHintDTO::from)
                .toList();
        return ResponseEntity.ok(hints);
    }

    /** 运营手动加边（蓝色 REQUEST_REF / 绿色 RESPONSE_REF / 紫色 MODEL_REF）。 */
    @PostMapping("/edges")
    public ResponseEntity<?> upsertEdge(@PathVariable Long projectId,
                                        @RequestBody EdgeUpsertRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse("请求体不能为空"));
        }
        try {
            ApiGraphEdgeEntity saved = apiGraphService.upsertManualEdge(
                    projectId,
                    request.sourceNodeId(),
                    request.targetNodeId(),
                    request.kind(),
                    request.note()
            );
            return ResponseEntity.ok(EdgeDTO.from(saved));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<Void> deleteEdge(@PathVariable Long projectId,
                                           @PathVariable Long edgeId) {
        boolean ok = apiGraphService.deleteEdge(edgeId);
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 批量保存运营布局坐标。 */
    @PutMapping("/layout")
    public ResponseEntity<Void> saveLayout(@PathVariable Long projectId,
                                           @RequestBody LayoutSaveRequest request) {
        if (request == null || request.positions() == null) {
            return ResponseEntity.noContent().build();
        }
        List<ApiGraphService.LayoutPosition> positions = request.positions().stream()
                .map(p -> new ApiGraphService.LayoutPosition(p.nodeId(), p.x(), p.y(), p.extJson()))
                .toList();
        apiGraphService.saveLayout(projectId, positions);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // DTO
    // ============================================================

    record SnapshotDTO(List<NodeDTO> nodes, List<EdgeDTO> edges, List<LayoutDTO> layouts) {
        static SnapshotDTO from(ApiGraphService.ApiGraphSnapshot snap) {
            return new SnapshotDTO(
                    snap.nodes().stream().map(NodeDTO::from).toList(),
                    snap.edges().stream().map(EdgeDTO::from).toList(),
                    snap.layouts().stream().map(LayoutDTO::from).toList()
            );
        }
    }

    record NodeDTO(Long id, Long projectId, String kind, Long refId, Long parentId,
                   String label, String typeName, String propsJson) {
        static NodeDTO from(ApiGraphNodeEntity entity) {
            return new NodeDTO(
                    entity.getId(), entity.getProjectId(), entity.getKind(),
                    entity.getRefId(), entity.getParentId(),
                    entity.getLabel(), entity.getTypeName(), entity.getPropsJson()
            );
        }
    }

    record EdgeDTO(Long id, Long projectId, Long sourceNodeId, Long targetNodeId,
                   String kind, String source, Double confidence,
                   String status, String inferStrategy, String confirmedBy,
                   String confirmedAt, String rejectReason,
                   String evidenceJson, String note, boolean enabled) {
        static EdgeDTO from(ApiGraphEdgeEntity entity) {
            return new EdgeDTO(
                    entity.getId(), entity.getProjectId(),
                    entity.getSourceNodeId(), entity.getTargetNodeId(),
                    entity.getKind(), entity.getSource(), entity.getConfidence(),
                    entity.getStatus(), entity.getInferStrategy(), entity.getConfirmedBy(),
                    entity.getConfirmedAt() == null ? null : entity.getConfirmedAt().toString(),
                    entity.getRejectReason(),
                    entity.getEvidenceJson(), entity.getNote(),
                    Boolean.TRUE.equals(entity.getEnabled())
            );
        }
    }

    record LayoutDTO(Long nodeId, Double x, Double y, String extJson) {
        static LayoutDTO from(ApiGraphLayoutEntity entity) {
            return new LayoutDTO(entity.getNodeId(), entity.getX(), entity.getY(), entity.getExtJson());
        }
    }

    record ParamSourceHintDTO(String targetPath, String targetField, String targetApi,
                              String sourcePath, String sourceField, String sourceApi,
                              Double confidence) {
        static ParamSourceHintDTO from(ApiGraphService.ParamSourceHint hint) {
            return new ParamSourceHintDTO(
                    hint.targetPath(), hint.targetField(), hint.targetApi(),
                    hint.sourcePath(), hint.sourceField(), hint.sourceApi(),
                    hint.confidence()
            );
        }
    }

    record EdgeUpsertRequest(Long sourceNodeId, Long targetNodeId, String kind, String note) {
    }

    record CandidateConfirmRequest(String confirmedBy) {
    }

    record CandidateRejectRequest(String rejectReason) {
    }

    record LayoutSaveRequest(List<LayoutPositionDTO> positions) {
    }

    record LayoutPositionDTO(Long nodeId, Double x, Double y, String extJson) {
    }

    record InferResultDTO(int generated) {
    }

    record ApiErrorResponse(String message) {
    }
}
