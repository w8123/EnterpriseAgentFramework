package com.enterprise.ai.agent.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/evals")
@RequiredArgsConstructor
public class AgentEvalController {

    private final AgentEvalService evalService;

    @GetMapping("/template")
    public ResponseEntity<List<Map<String, Object>>> template() {
        return ResponseEntity.ok(List.of(Map.of(
                "caseNo", "case-1",
                "message", "查询订单1001是否可退款",
                "inputParams", Map.of("question", "查询订单1001是否可退款"),
                "expected", Map.of("contains", List.of("订单1001")),
                "judgeConfig", Map.of("semanticEnabled", false),
                "tags", "smoke")));
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<AgentEvalDatasetEntity>> datasets(@RequestParam(required = false) String agentId) {
        return ResponseEntity.ok(evalService.listDatasets(agentId));
    }

    @PostMapping("/datasets")
    public ResponseEntity<AgentEvalDatasetEntity> createDataset(
            @RequestBody AgentEvalService.DatasetImportRequest request) {
        return ResponseEntity.ok(evalService.importDataset(request));
    }

    @PostMapping("/datasets/{datasetId}/cases/import")
    public ResponseEntity<AgentEvalDatasetEntity> importCases(@PathVariable Long datasetId,
                                                              @RequestBody AgentEvalService.DatasetImportRequest request) {
        return ResponseEntity.ok(evalService.importCases(datasetId, request == null ? List.of() : request.cases()));
    }

    @GetMapping("/datasets/{datasetId}/cases")
    public ResponseEntity<List<AgentEvalCaseEntity>> cases(@PathVariable Long datasetId) {
        return ResponseEntity.ok(evalService.listCases(datasetId));
    }

    @PostMapping("/runs")
    public ResponseEntity<AgentEvalService.RunView> startRun(@RequestBody AgentEvalService.StartRunRequest request) {
        return ResponseEntity.ok(evalService.startRun(request));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<AgentEvalRunEntity> run(@PathVariable Long runId) {
        return ResponseEntity.ok(evalService.getRun(runId));
    }

    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<List<AgentEvalCaseResultEntity>> results(@PathVariable Long runId) {
        return ResponseEntity.ok(evalService.listResults(runId));
    }
}
