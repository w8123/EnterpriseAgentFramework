package com.enterprise.ai.runtime.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class RuntimeEvalOpsController {

    private final RuntimeEvalDatasetService datasetService;
    private final RuntimeEvalEvaluatorSuiteService suiteService;
    private final RuntimeEvalExperimentService experimentService;
    private final Environment environment;

    /** 评测执行器默认关闭；关闭时实验只会排队，管理端据此提示并禁止发起实验。 */
    @GetMapping("/api/runtime/evals/v2/readiness")
    public Map<String, Object> readiness() {
        boolean workerEnabled = environment.getProperty(
                RuntimeEvalTaskWorker.ENABLED_PROPERTY, Boolean.class, false);
        return Map.of("workerEnabled", workerEnabled);
    }

    @GetMapping("/api/runtime/evals/v2/datasets")
    public List<RuntimeEvalDatasetSummaryView> listDatasets(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String targetId) {
        return datasetService.list(tenantId, targetId);
    }

    @PostMapping("/api/runtime/evals/v2/datasets")
    public ResponseEntity<RuntimeEvalDatasetDetailView> createDataset(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(datasetService.create(body));
    }

    @GetMapping("/api/runtime/evals/v2/datasets/{datasetId}")
    public RuntimeEvalDatasetDetailView getDataset(@PathVariable Long datasetId) {
        return datasetService.get(datasetId);
    }

    @PostMapping("/api/runtime/evals/v2/datasets/{datasetId}/versions")
    public ResponseEntity<RuntimeEvalDatasetVersionView> createDatasetVersion(
            @PathVariable Long datasetId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(datasetService.createVersion(datasetId, body));
    }

    @PostMapping("/api/runtime/evals/v2/datasets/{datasetId}/versions/from-trace")
    public ResponseEntity<RuntimeEvalDatasetVersionView> createDatasetVersionFromTrace(
            @PathVariable Long datasetId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(datasetService.createVersionFromTrace(datasetId, body));
    }

    @GetMapping("/api/runtime/evals/v2/dataset-versions/{versionId}")
    public RuntimeEvalDatasetVersionView getDatasetVersion(@PathVariable Long versionId) {
        return datasetService.getVersion(versionId);
    }

    @GetMapping("/api/runtime/evals/v2/evaluator-suites")
    public List<RuntimeEvalEvaluatorSuiteVersionView> listEvaluatorSuites(
            @RequestParam(required = false) String tenantId) {
        return suiteService.list(tenantId);
    }

    @PostMapping("/api/runtime/evals/v2/evaluator-suites")
    public ResponseEntity<RuntimeEvalEvaluatorSuiteVersionView> createEvaluatorSuite(
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(suiteService.create(body));
    }

    @GetMapping("/api/runtime/evals/v2/experiments")
    public List<RuntimeEvalExperimentSummaryView> listExperiments(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String targetId) {
        return experimentService.list(tenantId, targetId);
    }

    @PostMapping("/api/runtime/evals/v2/experiments")
    public ResponseEntity<RuntimeEvalExperimentDetailView> createExperiment(
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(experimentService.create(body));
    }

    @GetMapping("/api/runtime/evals/v2/experiments/{experimentId}")
    public RuntimeEvalExperimentDetailView getExperiment(@PathVariable Long experimentId) {
        return experimentService.get(experimentId);
    }

    @GetMapping("/api/runtime/evals/v2/experiments/{experimentId}/items")
    public RuntimeEvalExperimentItemPage listExperimentItems(
            @PathVariable Long experimentId,
            @RequestParam(required = false) Long variantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return experimentService.listItems(experimentId, variantId, page, pageSize);
    }

    @PostMapping("/api/runtime/evals/v2/experiments/{experimentId}/cancel")
    public RuntimeEvalExperimentDetailView cancelExperiment(@PathVariable Long experimentId) {
        return experimentService.cancel(experimentId);
    }
}
