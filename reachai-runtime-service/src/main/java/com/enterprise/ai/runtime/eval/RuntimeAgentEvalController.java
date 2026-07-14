package com.enterprise.ai.runtime.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentEvalController {

    private final RuntimeAgentEvalService evalService;

    @GetMapping("/api/runtime/evals/datasets")
    public List<RuntimeAgentEvalDatasetView> listDatasets(
            @RequestParam(required = false) String agentId) {
        return evalService.listDatasets(agentId);
    }

    @PostMapping("/api/runtime/evals/datasets")
    public RuntimeAgentEvalDatasetView createDataset(@RequestBody Map<String, Object> body) {
        return evalService.createDataset(body);
    }

    @PostMapping("/api/runtime/evals/datasets/{datasetId}/cases/import")
    public RuntimeAgentEvalDatasetView importCases(@PathVariable Long datasetId,
                                                   @RequestBody Map<String, Object> body) {
        return evalService.importCases(datasetId, body);
    }

    @GetMapping("/api/runtime/evals/datasets/{datasetId}/cases")
    public List<RuntimeAgentEvalCaseView> listCases(@PathVariable Long datasetId) {
        return evalService.listCases(datasetId);
    }

    @PostMapping("/api/runtime/evals/runs")
    public RuntimeAgentEvalRunView startRun(@RequestBody Map<String, Object> body) {
        return evalService.startRun(body);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}")
    public RuntimeAgentEvalRunDetail getRun(@PathVariable Long runId) {
        return evalService.getRun(runId);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}/results")
    public List<RuntimeAgentEvalCaseResultView> listRunResults(@PathVariable Long runId) {
        return evalService.listRunResults(runId);
    }
}
