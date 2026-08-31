package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeAgentEvalService {

    private static final TypeReference<List<Map<String, Object>>> CASE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeAgentEvalDatasetMapper datasetMapper;
    private final RuntimeAgentEvalCaseMapper caseMapper;
    private final RuntimeAgentEvalRunMapper runMapper;
    private final RuntimeAgentEvalCaseResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeAgentExecutionService executionService;
    private final RuntimeEvalTargetSnapshotService targetSnapshotService;

    public List<RuntimeAgentEvalDatasetView> listDatasets(String agentId) {
        return datasetMapper.selectList(Wrappers.<RuntimeAgentEvalDatasetEntity>lambdaQuery()
                        .eq(StringUtils.hasText(agentId), RuntimeAgentEvalDatasetEntity::getAgentId, agentId)
                        .orderByDesc(RuntimeAgentEvalDatasetEntity::getUpdateTime)
                        .orderByDesc(RuntimeAgentEvalDatasetEntity::getId))
                .stream()
                .map(this::datasetView)
                .toList();
    }

    @Transactional
    public RuntimeAgentEvalDatasetView createDataset(Map<String, Object> request) {
        RuntimeAgentEvalDatasetEntity dataset = new RuntimeAgentEvalDatasetEntity();
        dataset.setAgentId(text(request, "agentId"));
        dataset.setAgentName(text(request, "agentName"));
        dataset.setName(requiredText(request, "name"));
        dataset.setDescription(text(request, "description"));
        dataset.setSource(defaultText(request, "source", "IMPORT"));
        dataset.setCaseCount(0);
        LocalDateTime now = LocalDateTime.now();
        dataset.setCreateTime(now);
        dataset.setUpdateTime(now);
        datasetMapper.insert(dataset);
        importCaseRows(dataset, cases(request));
        return datasetView(datasetMapper.selectById(dataset.getId()));
    }

    @Transactional
    public RuntimeAgentEvalDatasetView importCases(Long datasetId, Map<String, Object> request) {
        RuntimeAgentEvalDatasetEntity dataset = requiredDataset(datasetId);
        importCaseRows(dataset, cases(request));
        return datasetView(datasetMapper.selectById(datasetId));
    }

    public List<RuntimeAgentEvalCaseView> listCases(Long datasetId) {
        return caseMapper.selectList(Wrappers.<RuntimeAgentEvalCaseEntity>lambdaQuery()
                        .eq(RuntimeAgentEvalCaseEntity::getDatasetId, datasetId)
                        .orderByAsc(RuntimeAgentEvalCaseEntity::getId))
                .stream()
                .map(this::caseView)
                .toList();
    }

    public RuntimeAgentEvalRunView startRun(Map<String, Object> request) {
        Long datasetId = requiredLong(request, "datasetId");
        RuntimeAgentEvalDatasetEntity dataset = requiredDataset(datasetId);
        List<RuntimeAgentEvalCaseEntity> cases = caseMapper.selectList(Wrappers.<RuntimeAgentEvalCaseEntity>lambdaQuery()
                .eq(RuntimeAgentEvalCaseEntity::getDatasetId, datasetId)
                .eq(RuntimeAgentEvalCaseEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentEvalCaseEntity::getId));
        int repeatCount = Math.max(1, intValue(request.get("repeatCount"), 1));
        String agentId = defaultText(request, "agentId", dataset.getAgentId());
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("Agent eval agentId is required");
        }
        RuntimeEvalTargetSnapshotService.CapturedTarget target = targetSnapshotService.captureAgent(
                agentId,
                optionalLong(request == null ? null : request.get("configVersionId")),
                text(request, "tenantId"),
                text(request, "createdBy"));
        RuntimeAgentEvalRunEntity run = new RuntimeAgentEvalRunEntity();
        run.setDatasetId(datasetId);
        run.setAgentId(agentId);
        run.setAgentName(defaultText(request, "agentName", dataset.getAgentName()));
        run.setRunName(defaultText(request, "runName", dataset.getName()));
        run.setRepeatCount(repeatCount);
        run.setStatus("RUNNING");
        run.setTargetSnapshotId(target.snapshot().getId());
        run.setTargetConfigVersionId(target.snapshot().getAgentConfigVersionId());
        run.setTargetConfigStatus(target.snapshot().getSourceStatus());
        run.setTargetFingerprint(target.snapshot().getFingerprintSha256());
        run.setGraphSpecJson(toJson(request.get("graphSpec")));
        run.setCanvasSnapshotJson(toJson(request.get("canvasSnapshot")));
        LocalDateTime now = LocalDateTime.now();
        run.setStartedAt(now);
        run.setCreateTime(now);
        runMapper.insert(run);

        List<RuntimeAgentEvalCaseResultEntity> executed = new ArrayList<>();
        Map<String, Object> runtimeContext = mapValue(request == null ? null
                : firstValue(request, "runtimeContext", "graphRuntimeContext"));
        for (int round = 1; round <= repeatCount; round++) {
            for (RuntimeAgentEvalCaseEntity evalCase : cases) {
                RuntimeAgentEvalCaseResultEntity result = executeCase(
                        run, evalCase, round, runtimeContext, target);
                resultMapper.insert(result);
                executed.add(result);
            }
        }

        Map<String, Object> summary = summary(cases.size(), repeatCount, executed);
        Map<String, Object> suggestion = suggestion(executed);
        run.setStatus("COMPLETED");
        run.setSummaryJson(toJson(summary));
        run.setSuggestionJson(toJson(suggestion));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return new RuntimeAgentEvalRunView(runView(run), summary, suggestion, listRunResults(run.getId()));
    }

    public RuntimeAgentEvalRunDetail getRun(Long runId) {
        RuntimeAgentEvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Agent eval run not found: " + runId);
        }
        return runView(run);
    }

    public List<RuntimeAgentEvalCaseResultView> listRunResults(Long runId) {
        return resultMapper.selectList(Wrappers.<RuntimeAgentEvalCaseResultEntity>lambdaQuery()
                        .eq(RuntimeAgentEvalCaseResultEntity::getRunId, runId)
                        .orderByAsc(RuntimeAgentEvalCaseResultEntity::getCaseId)
                        .orderByAsc(RuntimeAgentEvalCaseResultEntity::getRoundNo))
                .stream()
                .map(this::resultView)
                .toList();
    }

    private void importCaseRows(RuntimeAgentEvalDatasetEntity dataset, List<Map<String, Object>> rows) {
        int index = dataset.getCaseCount() == null ? 0 : dataset.getCaseCount();
        for (Map<String, Object> row : rows) {
            RuntimeAgentEvalCaseEntity evalCase = new RuntimeAgentEvalCaseEntity();
            index++;
            evalCase.setDatasetId(dataset.getId());
            evalCase.setCaseNo(StringUtils.hasText(text(row, "caseNo")) ? text(row, "caseNo") : "CASE-" + index);
            evalCase.setMessage(text(row, "message"));
            evalCase.setInputParamsJson(toJson(row.get("inputParams")));
            evalCase.setExpectedJson(toJson(row.get("expected")));
            evalCase.setJudgeConfigJson(toJson(row.get("judgeConfig")));
            evalCase.setTags(text(row, "tags"));
            evalCase.setEnabled(true);
            evalCase.setCreateTime(LocalDateTime.now());
            caseMapper.insert(evalCase);
        }
        dataset.setCaseCount(countCases(dataset.getId()));
        dataset.setUpdateTime(LocalDateTime.now());
        datasetMapper.updateById(dataset);
    }

    private RuntimeAgentEvalDatasetEntity requiredDataset(Long datasetId) {
        RuntimeAgentEvalDatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("Agent eval dataset not found: " + datasetId);
        }
        return dataset;
    }

    private RuntimeAgentEvalCaseResultEntity executeCase(RuntimeAgentEvalRunEntity run,
                                                         RuntimeAgentEvalCaseEntity evalCase,
                                                         int round,
                                                         Map<String, Object> runtimeContext,
                                                         RuntimeEvalTargetSnapshotService.CapturedTarget target) {
        RuntimeAgentEvalCaseResultEntity result = new RuntimeAgentEvalCaseResultEntity();
        result.setRunId(run.getId());
        result.setDatasetId(run.getDatasetId());
        result.setCaseId(evalCase.getId());
        result.setCaseNo(evalCase.getCaseNo());
        result.setRoundNo(round);
        result.setCreateTime(LocalDateTime.now());
        long started = System.nanoTime();
        try {
            Map<String, Object> input = new LinkedHashMap<>(runtimeContext);
            input.putAll(readMap(evalCase.getInputParamsJson()));
            input.put("agentId", run.getAgentId());
            input.put("message", firstText(evalCase.getMessage(), text(input, "message"), ""));
            input.putIfAbsent("sessionId", "eval-" + run.getId() + "-" + evalCase.getId() + "-" + round);
            input.putIfAbsent("userId", "agent-eval");
            input.put("intentHint", "AGENT_EVAL");
            input.put("entryType", "EVAL");
            input.put("evalRunId", run.getId());
            input.put("evalCaseId", evalCase.getId());
            RuntimeEvalExecutionContext evaluation = RuntimeEvalExecutionContext.readOnly(
                    "legacy-run:" + run.getId(),
                    evalCase.getCaseNo() + ":round:" + round,
                    target.snapshot().getFingerprintSha256());
            Map<String, Object> response = executionService.executeEvaluation(
                    target.executionContext(), input, true, evaluation);
            boolean runtimeSuccess = Boolean.TRUE.equals(response.get("success"));
            String answer = text(response, "answer");
            Map<String, Object> metadata = mapValue(response.get("metadata"));
            Map<String, Object> expected = readMap(evalCase.getExpectedJson());
            AssertionResult assertion = assertResponse(response, expected, runtimeSuccess);

            result.setStatus("COMPLETED");
            result.setRuntimeSuccess(runtimeSuccess);
            result.setAssertionPassed(assertion.passed());
            result.setScore(assertion.score());
            result.setAnswer(answer);
            result.setTraceId(text(metadata, "traceId"));
            result.setStepResultsJson(toJson(Map.of(
                    "steps", listValue(response.get("steps")),
                    "metadata", metadata,
                    "uiRequest", response.get("uiRequest") == null ? Map.of() : response.get("uiRequest"))));
            result.setJudgeResultJson(toJson(Map.of(
                    "expected", expected,
                    "assertions", assertion.details(),
                    "passed", assertion.passed(),
                    "score", assertion.score())));
            if (!runtimeSuccess) {
                result.setErrorCode(firstText(text(metadata, "code"), "EVAL_RUNTIME_FAILED"));
                result.setErrorMessage(firstText(answer, "Agent runtime execution failed"));
            } else if (!assertion.passed()) {
                result.setErrorCode("EVAL_ASSERTION_FAILED");
                result.setErrorMessage(assertion.failedSummary());
            }
        } catch (Exception ex) {
            result.setStatus("COMPLETED");
            result.setRuntimeSuccess(false);
            result.setAssertionPassed(false);
            result.setScore(0.0);
            result.setErrorCode("EVAL_EXECUTION_EXCEPTION");
            result.setErrorMessage(firstText(ex.getMessage(), ex.getClass().getSimpleName()));
            result.setStepResultsJson(toJson(Map.of()));
            result.setJudgeResultJson(toJson(Map.of("reason", result.getErrorMessage())));
        }
        result.setElapsedMs((int) Math.min(Integer.MAX_VALUE,
                Math.max(0, (System.nanoTime() - started) / 1_000_000L)));
        return result;
    }

    private AssertionResult assertResponse(Map<String, Object> response,
                                           Map<String, Object> expected,
                                           boolean runtimeSuccess) {
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean expectedSuccess = booleanValue(expected.get("success"), true);
        check(checks, "success", expectedSuccess, runtimeSuccess);

        String answer = firstText(text(response, "answer"), "");
        for (String fragment : stringList(expected.get("answerContains"))) {
            check(checks, "answerContains:" + fragment, true, answer.contains(fragment));
        }
        for (String fragment : stringList(expected.get("answerNotContains"))) {
            check(checks, "answerNotContains:" + fragment, true, !answer.contains(fragment));
        }

        Map<String, Object> metadata = mapValue(response.get("metadata"));
        numericMinimum(checks, expected, metadata, "minWorkflowCalls", "workflowCallCount");
        numericMaximum(checks, expected, metadata, "maxWorkflowCalls", "workflowCallCount");
        numericMinimum(checks, expected, metadata, "minManagedExecutorCalls", "managedExecutorCallCount");
        numericMaximum(checks, expected, metadata, "maxManagedExecutorCalls", "managedExecutorCallCount");
        numericMinimum(checks, expected, metadata, "minPlanCount", "planCount");
        numericMaximum(checks, expected, metadata, "maxPlanCount", "planCount");
        numericMinimum(checks, expected, metadata, "minReplanCount", "replanCount");
        numericMaximum(checks, expected, metadata, "maxReplanCount", "replanCount");
        if (expected.containsKey("code")) {
            check(checks, "code", text(expected, "code"), text(metadata, "code"));
        }
        if (expected.containsKey("interactionPending")) {
            check(checks, "interactionPending", booleanValue(expected.get("interactionPending"), false),
                    Boolean.TRUE.equals(metadata.get("interactionPending")));
        }

        List<Map<String, Object>> steps = mapList(response.get("steps"));
        List<String> calledTools = steps.stream()
                .filter(step -> "workflow".equalsIgnoreCase(text(step, "name")))
                .map(step -> text(mapValue(step.get("detail")), "toolName"))
                .filter(StringUtils::hasText)
                .toList();
        for (String tool : stringList(expected.get("calledTools"))) {
            check(checks, "calledTool:" + tool, true, calledTools.contains(tool));
        }
        for (String tool : stringList(expected.get("forbiddenTools"))) {
            check(checks, "forbiddenTool:" + tool, true, !calledTools.contains(tool));
        }
        if (expected.containsKey("policyDecision")) {
            String expectedDecision = text(expected, "policyDecision");
            boolean found = steps.stream()
                    .filter(step -> "policy".equalsIgnoreCase(text(step, "name")))
                    .map(step -> text(mapValue(step.get("detail")), "decision"))
                    .anyMatch(expectedDecision::equalsIgnoreCase);
            check(checks, "policyDecision", true, found);
        }
        if (expected.containsKey("uiRequestComponent")) {
            check(checks, "uiRequestComponent", text(expected, "uiRequestComponent"),
                    text(mapValue(response.get("uiRequest")), "component"));
        }
        Map<String, Object> metadataEquals = mapValue(expected.get("metadataEquals"));
        metadataEquals.forEach((key, value) -> check(checks, "metadata." + key, value, metadata.get(key)));

        int passed = (int) checks.stream().filter(item -> Boolean.TRUE.equals(item.get("passed"))).count();
        double score = checks.isEmpty() ? (runtimeSuccess ? 1.0 : 0.0) : (double) passed / checks.size();
        boolean allPassed = passed == checks.size();
        String failedSummary = checks.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("passed")))
                .map(item -> String.valueOf(item.get("name")))
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
        return new AssertionResult(allPassed, score, List.copyOf(checks), failedSummary);
    }

    private Map<String, Object> summary(int caseCount,
                                        int repeatCount,
                                        List<RuntimeAgentEvalCaseResultEntity> results) {
        int total = results.size();
        int runtimeSuccessCount = (int) results.stream().filter(item -> Boolean.TRUE.equals(item.getRuntimeSuccess())).count();
        int passedCount = (int) results.stream().filter(item -> Boolean.TRUE.equals(item.getAssertionPassed())).count();
        List<Integer> latencies = results.stream().map(RuntimeAgentEvalCaseResultEntity::getElapsedMs)
                .filter(java.util.Objects::nonNull).sorted().toList();
        Map<String, Integer> failures = new LinkedHashMap<>();
        results.stream().filter(item -> !Boolean.TRUE.equals(item.getAssertionPassed()))
                .forEach(item -> failures.merge(firstText(item.getErrorCode(), "EVAL_ASSERTION_FAILED"), 1, Integer::sum));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", caseCount);
        summary.put("repeatCount", repeatCount);
        summary.put("totalExecutions", total);
        summary.put("runtimeSuccessCount", runtimeSuccessCount);
        summary.put("passedExecutions", passedCount);
        summary.put("runtimeSuccessRate", ratio(runtimeSuccessCount, total));
        summary.put("accuracyRate", ratio(passedCount, total));
        summary.put("avgScore", results.stream().map(RuntimeAgentEvalCaseResultEntity::getScore)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0));
        summary.put("p50LatencyMs", percentile(latencies, 0.50));
        summary.put("p95LatencyMs", percentile(latencies, 0.95));
        summary.put("biasCount", results.stream().filter(item -> Boolean.TRUE.equals(item.getRuntimeSuccess())
                && !Boolean.TRUE.equals(item.getAssertionPassed())).count());
        summary.put("failedNodeCounts", failures);
        return summary;
    }

    private Map<String, Object> suggestion(List<RuntimeAgentEvalCaseResultEntity> results) {
        Map<String, Long> failureCounts = results.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getAssertionPassed()))
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> firstText(item.getErrorCode(), "EVAL_ASSERTION_FAILED"),
                        LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<Map<String, Object>> items = failureCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(entry -> Map.<String, Object>of(
                        "nodeId", "supervisor",
                        "severity", entry.getValue() > 1 ? "HIGH" : "MEDIUM",
                        "reason", entry.getKey() + " x " + entry.getValue(),
                        "recommendation", recommendation(entry.getKey())))
                .toList();
        String text = results.isEmpty()
                ? "No enabled eval cases were found."
                : items.isEmpty()
                ? "All Agent runtime eval assertions passed."
                : failureCounts.values().stream().mapToLong(Long::longValue).sum()
                        + " Agent eval executions require attention.";
        return Map.of("summary", text, "items", items);
    }

    private int countCases(Long datasetId) {
        return Math.toIntExact(caseMapper.selectCount(Wrappers.<RuntimeAgentEvalCaseEntity>lambdaQuery()
                .eq(RuntimeAgentEvalCaseEntity::getDatasetId, datasetId)));
    }

    private RuntimeAgentEvalDatasetView datasetView(RuntimeAgentEvalDatasetEntity entity) {
        return new RuntimeAgentEvalDatasetView(entity.getId(), entity.getAgentId(), entity.getAgentName(),
                entity.getName(), entity.getDescription(), entity.getSource(), entity.getCaseCount(),
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private RuntimeAgentEvalCaseView caseView(RuntimeAgentEvalCaseEntity entity) {
        return new RuntimeAgentEvalCaseView(entity.getId(), entity.getDatasetId(), entity.getCaseNo(),
                entity.getMessage(), entity.getInputParamsJson(), entity.getExpectedJson(),
                entity.getJudgeConfigJson(), entity.getTags(), entity.getEnabled());
    }

    private RuntimeAgentEvalRunDetail runView(RuntimeAgentEvalRunEntity entity) {
        return new RuntimeAgentEvalRunDetail(entity.getId(), entity.getDatasetId(), entity.getAgentId(),
                entity.getAgentName(), entity.getRunName(), entity.getRepeatCount(), entity.getStatus(),
                entity.getTargetSnapshotId(), entity.getTargetConfigVersionId(),
                entity.getTargetConfigStatus(), entity.getTargetFingerprint(),
                entity.getSummaryJson(), entity.getSuggestionJson(), entity.getStartedAt(), entity.getFinishedAt());
    }

    private RuntimeAgentEvalCaseResultView resultView(RuntimeAgentEvalCaseResultEntity entity) {
        return new RuntimeAgentEvalCaseResultView(entity.getId(), entity.getRunId(), entity.getDatasetId(),
                entity.getCaseId(), entity.getCaseNo(), entity.getRoundNo(), entity.getStatus(),
                entity.getRuntimeSuccess(), entity.getAssertionPassed(), entity.getSemanticScore(),
                entity.getScore(), entity.getElapsedMs(), entity.getAnswer(), entity.getTraceId(),
                entity.getErrorCode(), entity.getErrorMessage());
    }

    private List<Map<String, Object>> cases(Map<String, Object> request) {
        Object value = request == null ? null : request.get("cases");
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, CASE_LIST_TYPE);
    }

    private String requiredText(Map<String, Object> request, String field) {
        String value = text(request, field);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Agent eval " + field + " is required");
        }
        return value;
    }

    private Long requiredLong(Map<String, Object> request, String field) {
        Object value = request == null ? null : request.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("Agent eval " + field + " is required");
    }

    private Long optionalLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text);
        }
        return null;
    }

    private String defaultText(Map<String, Object> request, String field, String fallback) {
        String value = text(request, field);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String text(Map<String, Object> request, String field) {
        Object value = request == null ? null : request.get(field);
        return value == null ? null : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private void numericMinimum(List<Map<String, Object>> checks,
                                Map<String, Object> expected,
                                Map<String, Object> actual,
                                String expectedKey,
                                String actualKey) {
        if (!expected.containsKey(expectedKey)) return;
        int threshold = intValue(expected.get(expectedKey), 0);
        int value = intValue(actual.get(actualKey), 0);
        check(checks, expectedKey, true, value >= threshold);
    }

    private void numericMaximum(List<Map<String, Object>> checks,
                                Map<String, Object> expected,
                                Map<String, Object> actual,
                                String expectedKey,
                                String actualKey) {
        if (!expected.containsKey(expectedKey)) return;
        int threshold = intValue(expected.get(expectedKey), 0);
        int value = intValue(actual.get(actualKey), 0);
        check(checks, expectedKey, true, value <= threshold);
    }

    private void check(List<Map<String, Object>> checks, String name, Object expected, Object actual) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("expected", expected);
        detail.put("actual", actual);
        detail.put("passed", java.util.Objects.equals(expected, actual));
        checks.add(detail);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    private double ratio(int value, int total) {
        return total == 0 ? 0 : (double) value / total;
    }

    private int percentile(List<Integer> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private String recommendation(String errorCode) {
        if (errorCode == null) return "Inspect the Agent trace and assertion details.";
        String normalized = errorCode.toUpperCase(Locale.ROOT);
        if (normalized.contains("POLICY") || normalized.contains("FORBIDDEN") || normalized.contains("DENY")) {
            return "Review project, tenant, roles, permissionKey, risk level, and active Agent allowlist.";
        }
        if (normalized.contains("ASSERTION")) {
            return "Compare selected Workflow tools, plan/replan counts, UI request, and final answer with the expected contract.";
        }
        if (normalized.contains("TIMEOUT")) {
            return "Inspect Workflow latency and the Agent total/workflow/page-bridge timeout budgets.";
        }
        return "Open the linked trace and inspect the failed Supervisor or Workflow step.";
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Agent Eval case JSON", ex);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) return new LinkedHashMap<>();
        return new LinkedHashMap<>(objectMapper.convertValue(value, MAP_TYPE));
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(this::mapValue).toList();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
        return values.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .filter(StringUtils::hasText).map(String::trim).toList();
    }

    private Object firstValue(Map<String, Object> source, String first, String second) {
        Object value = source == null ? null : source.get(first);
        return value == null && source != null ? source.get(second) : value;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Agent Eval JSON payload", ex);
        }
    }

    private record AssertionResult(boolean passed,
                                   double score,
                                   List<Map<String, Object>> details,
                                   String failedSummary) {
    }
}
