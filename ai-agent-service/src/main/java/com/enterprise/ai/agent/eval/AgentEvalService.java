package com.enterprise.ai.agent.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.host.LangGraph4jRuntimeAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AgentEvalService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentEvalDatasetMapper datasetMapper;
    private final AgentEvalCaseMapper caseMapper;
    private final AgentEvalRunMapper runMapper;
    private final AgentEvalCaseResultMapper resultMapper;
    private final LangGraph4jRuntimeAdapter runtimeAdapter;
    private final AgentEvalJudgeService judgeService;
    private final AgentEvalSuggestionService suggestionService;
    private final ObjectMapper objectMapper;

    public AgentEvalService(AgentEvalDatasetMapper datasetMapper,
                            AgentEvalCaseMapper caseMapper,
                            AgentEvalRunMapper runMapper,
                            AgentEvalCaseResultMapper resultMapper,
                            LangGraph4jRuntimeAdapter runtimeAdapter,
                            AgentEvalJudgeService judgeService,
                            AgentEvalSuggestionService suggestionService,
                            ObjectMapper objectMapper) {
        this.datasetMapper = datasetMapper;
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.runtimeAdapter = runtimeAdapter;
        this.judgeService = judgeService;
        this.suggestionService = suggestionService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Transactional
    public AgentEvalDatasetEntity importDataset(DatasetImportRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("dataset name is required");
        }
        List<CaseImportRow> cases = request.cases() == null ? List.of() : request.cases();
        AgentEvalDatasetEntity dataset = new AgentEvalDatasetEntity();
        dataset.setAgentId(request.agentId());
        dataset.setAgentName(request.agentName());
        dataset.setName(request.name());
        dataset.setDescription(request.description());
        dataset.setSource("IMPORT");
        dataset.setCaseCount(cases.size());
        datasetMapper.insert(dataset);

        for (int index = 0; index < cases.size(); index++) {
            CaseImportRow source = cases.get(index);
            AgentEvalCaseEntity row = new AgentEvalCaseEntity();
            row.setDatasetId(dataset.getId());
            row.setCaseNo(source.caseNo() == null || source.caseNo().isBlank() ? "case-" + (index + 1) : source.caseNo());
            row.setMessage(source.message());
            row.setInputParamsJson(writeJson(source.inputParams() == null ? Map.of() : source.inputParams()));
            row.setExpectedJson(writeJson(source.expected() == null ? Map.of() : source.expected()));
            row.setJudgeConfigJson(writeJson(source.judgeConfig() == null ? Map.of() : source.judgeConfig()));
            row.setTags(source.tags());
            row.setEnabled(true);
            caseMapper.insert(row);
        }
        return dataset;
    }

    @Transactional
    public AgentEvalDatasetEntity importCases(Long datasetId, List<CaseImportRow> cases) {
        if (datasetId == null) {
            throw new IllegalArgumentException("datasetId is required");
        }
        AgentEvalDatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("dataset not found: " + datasetId);
        }
        List<CaseImportRow> rows = cases == null ? List.of() : cases;
        int existingCount = dataset.getCaseCount() == null ? 0 : dataset.getCaseCount();
        for (int index = 0; index < rows.size(); index++) {
            CaseImportRow source = rows.get(index);
            AgentEvalCaseEntity row = new AgentEvalCaseEntity();
            row.setDatasetId(datasetId);
            row.setCaseNo(source.caseNo() == null || source.caseNo().isBlank()
                    ? "case-" + (existingCount + index + 1)
                    : source.caseNo());
            row.setMessage(source.message());
            row.setInputParamsJson(writeJson(source.inputParams() == null ? Map.of() : source.inputParams()));
            row.setExpectedJson(writeJson(source.expected() == null ? Map.of() : source.expected()));
            row.setJudgeConfigJson(writeJson(source.judgeConfig() == null ? Map.of() : source.judgeConfig()));
            row.setTags(source.tags());
            row.setEnabled(true);
            caseMapper.insert(row);
        }
        dataset.setCaseCount(existingCount + rows.size());
        datasetMapper.updateById(dataset);
        return dataset;
    }

    public List<AgentEvalDatasetEntity> listDatasets(String agentId) {
        LambdaQueryWrapper<AgentEvalDatasetEntity> query = new LambdaQueryWrapper<AgentEvalDatasetEntity>()
                .orderByDesc(AgentEvalDatasetEntity::getCreateTime);
        if (agentId != null && !agentId.isBlank()) {
            query.eq(AgentEvalDatasetEntity::getAgentId, agentId);
        }
        return datasetMapper.selectList(query);
    }

    public List<AgentEvalCaseEntity> listCases(Long datasetId) {
        if (datasetId == null) {
            throw new IllegalArgumentException("datasetId is required");
        }
        return caseMapper.selectList(new LambdaQueryWrapper<AgentEvalCaseEntity>()
                .eq(AgentEvalCaseEntity::getDatasetId, datasetId)
                .orderByAsc(AgentEvalCaseEntity::getId));
    }

    public AgentEvalRunEntity getRun(Long runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        return runMapper.selectById(runId);
    }

    public List<AgentEvalCaseResultEntity> listResults(Long runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        return resultMapper.selectList(new LambdaQueryWrapper<AgentEvalCaseResultEntity>()
                .eq(AgentEvalCaseResultEntity::getRunId, runId)
                .orderByAsc(AgentEvalCaseResultEntity::getRoundNo)
                .orderByAsc(AgentEvalCaseResultEntity::getCaseId));
    }

    @Transactional
    public RunView startRun(StartRunRequest request) {
        validateStartRequest(request);
        AgentEvalRunEntity run = createRun(request);
        runMapper.insert(run);

        List<AgentEvalCaseEntity> cases = caseMapper.selectList(new LambdaQueryWrapper<AgentEvalCaseEntity>()
                .eq(AgentEvalCaseEntity::getDatasetId, request.datasetId())
                .eq(AgentEvalCaseEntity::getEnabled, true)
                .orderByAsc(AgentEvalCaseEntity::getId));
        List<AgentEvalCaseResultEntity> results = new ArrayList<>();

        for (int roundNo = 1; roundNo <= request.repeatCount(); roundNo++) {
            for (AgentEvalCaseEntity evalCase : cases) {
                AgentEvalCaseResultEntity result = runCase(run, evalCase, roundNo, request.graphSpec(), request.graphRuntimeContext());
                resultMapper.insert(result);
                results.add(result);
            }
        }

        RunSummary summary = summarize(cases.size(), request.repeatCount(), results);
        AgentEvalSuggestionService.Suggestion suggestion = suggestionService.suggest(
                summary.failedNodeCounts(),
                summary.biasCount());
        run.setStatus("DONE");
        run.setSummaryJson(writeJson(summary));
        run.setSuggestionJson(writeJson(suggestion));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return new RunView(run, summary, suggestion, results);
    }

    private AgentEvalRunEntity createRun(StartRunRequest request) {
        AgentEvalRunEntity run = new AgentEvalRunEntity();
        run.setDatasetId(request.datasetId());
        run.setAgentId(request.agentId());
        run.setAgentName(request.agentName());
        run.setRunName(request.runName());
        run.setRepeatCount(request.repeatCount());
        run.setStatus("RUNNING");
        run.setCanvasSnapshotJson(writeJson(request.canvasSnapshot()));
        run.setGraphSpecJson(writeJson(request.graphSpec()));
        run.setStartedAt(LocalDateTime.now());
        return run;
    }

    private AgentEvalCaseResultEntity runCase(AgentEvalRunEntity run,
                                              AgentEvalCaseEntity evalCase,
                                              int roundNo,
                                              GraphSpec graphSpec,
                                              GraphRuntimeContext runtimeContext) {
        AgentEvalCaseResultEntity row = baseResult(run, evalCase, roundNo);
        try {
            LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug = runtimeAdapter.debugRun(
                    graphSpec,
                    sandboxedContext(runtimeContext),
                    evalCase.getMessage(),
                    readMap(evalCase.getInputParamsJson()),
                    Map.of("evalMode", true, "sandboxSideEffects", true));
            boolean runtimeSuccess = debug != null && debug.isSuccess();
            row.setRuntimeSuccess(runtimeSuccess);
            row.setAnswer(debug == null ? null : debug.getAnswer());
            row.setTraceId(debug == null ? null : debug.getTraceId());
            row.setStepResultsJson(writeJson(debug == null ? List.of() : debug.getSteps()));
            row.setElapsedMs(elapsedMs(debug));
            row.setErrorCode(debug == null ? "DEBUG_RUN_EMPTY" : debug.getErrorCode());
            row.setErrorMessage(debug == null ? "Debug run returned no result." : debug.getErrorMessage());

            if (runtimeSuccess) {
                AgentEvalJudgeService.JudgeResult judge = judgeService.judge(
                        debug.getAnswer(),
                        judgeContext(debug),
                        evalCase.getExpectedJson(),
                        evalCase.getJudgeConfigJson());
                row.setAssertionPassed(judge.passed());
                row.setSemanticScore(judge.semanticScore());
                row.setScore(judge.score());
                row.setJudgeResultJson(writeJson(judge));
                row.setStatus(judge.passed() ? "PASSED" : "FAILED");
            } else {
                row.setAssertionPassed(false);
                row.setScore(0D);
                row.setStatus("ERROR");
                row.setJudgeResultJson(writeJson(Map.of("failures", List.of("runtime execution failed"))));
            }
        } catch (Exception ex) {
            row.setRuntimeSuccess(false);
            row.setAssertionPassed(false);
            row.setScore(0D);
            row.setStatus("ERROR");
            row.setErrorCode(ex.getClass().getSimpleName());
            row.setErrorMessage(ex.getMessage());
            row.setJudgeResultJson(writeJson(Map.of("failures", List.of("runtime exception: " + ex.getMessage()))));
        }
        return row;
    }

    private AgentEvalCaseResultEntity baseResult(AgentEvalRunEntity run, AgentEvalCaseEntity evalCase, int roundNo) {
        AgentEvalCaseResultEntity row = new AgentEvalCaseResultEntity();
        row.setRunId(run.getId());
        row.setDatasetId(run.getDatasetId());
        row.setCaseId(evalCase.getId());
        row.setCaseNo(evalCase.getCaseNo());
        row.setRoundNo(roundNo);
        row.setStatus("PENDING");
        row.setRuntimeSuccess(false);
        row.setAssertionPassed(false);
        row.setScore(0D);
        row.setElapsedMs(0);
        return row;
    }

    private RunSummary summarize(int caseCount, int repeatCount, List<AgentEvalCaseResultEntity> rows) {
        int total = rows.size();
        int runtimeSuccessCount = (int) rows.stream().filter(row -> Boolean.TRUE.equals(row.getRuntimeSuccess())).count();
        int passed = (int) rows.stream().filter(row -> Boolean.TRUE.equals(row.getAssertionPassed())).count();
        int biasCount = total - passed;
        List<Integer> successLatencies = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getRuntimeSuccess()))
                .map(AgentEvalCaseResultEntity::getElapsedMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Map<String, Long> failedNodeCounts = failedNodeCounts(rows);
        double avgScore = rows.stream()
                .map(AgentEvalCaseResultEntity::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0D);
        return new RunSummary(
                caseCount,
                repeatCount,
                total,
                runtimeSuccessCount,
                passed,
                rate(runtimeSuccessCount, total),
                rate(passed, total),
                avgScore,
                percentile(successLatencies, 0.5D),
                percentile(successLatencies, 0.95D),
                biasCount,
                failedNodeCounts);
    }

    private Map<String, Long> failedNodeCounts(List<AgentEvalCaseResultEntity> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentEvalCaseResultEntity row : rows) {
            if (Boolean.TRUE.equals(row.getAssertionPassed())) {
                continue;
            }
            String nodeId = firstFailedNode(row.getStepResultsJson());
            counts.put(nodeId, counts.getOrDefault(nodeId, 0L) + 1);
        }
        return counts;
    }

    private String firstFailedNode(String stepResultsJson) {
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepResultsJson, new TypeReference<>() {
            });
            return steps.stream()
                    .filter(step -> !"SUCCESS".equalsIgnoreCase(String.valueOf(step.get("status"))))
                    .map(step -> String.valueOf(step.getOrDefault("nodeId", "final_answer")))
                    .filter(value -> !value.isBlank() && !"null".equals(value))
                    .findFirst()
                    .orElseGet(() -> steps.stream()
                            .max(Comparator.comparingInt(step -> numberValue(step.get("index"))))
                            .map(step -> String.valueOf(step.getOrDefault("nodeId", "final_answer")))
                            .orElse("final_answer"));
        } catch (Exception ignored) {
            return "final_answer";
        }
    }

    private int elapsedMs(LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug) {
        if (debug == null || debug.getSteps() == null) {
            return 0;
        }
        long total = debug.getSteps().stream()
                .mapToLong(LangGraph4jRuntimeAdapter.WorkflowDebugStepResult::getElapsedMs)
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
    }

    private Map<String, Object> judgeContext(LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("answer", debug.getAnswer());
        context.put("finalState", debug.getFinalState() == null ? Map.of() : debug.getFinalState());
        context.put("steps", debug.getSteps() == null ? List.of() : debug.getSteps());
        return context;
    }

    private GraphRuntimeContext sandboxedContext(GraphRuntimeContext context) {
        if (context == null) {
            return GraphRuntimeContext.builder().allowIrreversible(false).build();
        }
        GraphRuntimeContext copied = objectMapper.convertValue(context, GraphRuntimeContext.class);
        copied.setAllowIrreversible(false);
        return copied;
    }

    private void validateStartRequest(StartRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.datasetId() == null) {
            throw new IllegalArgumentException("datasetId is required");
        }
        if (request.graphSpec() == null) {
            throw new IllegalArgumentException("graphSpec is required");
        }
        if (request.graphRuntimeContext() == null) {
            throw new IllegalArgumentException("graphRuntimeContext is required");
        }
        if (request.repeatCount() < 1 || request.repeatCount() > 20) {
            throw new IllegalArgumentException("repeatCount must be between 1 and 20");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    private int percentile(List<Integer> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sortedValues.size() * percentile) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    public record StartRunRequest(
            Long datasetId,
            String agentId,
            String agentName,
            String runName,
            int repeatCount,
            GraphSpec graphSpec,
            GraphRuntimeContext graphRuntimeContext,
            Map<String, Object> canvasSnapshot
    ) {
    }

    public record DatasetImportRequest(
            String agentId,
            String agentName,
            String name,
            String description,
            List<CaseImportRow> cases
    ) {
    }

    public record CaseImportRow(
            String caseNo,
            String message,
            Map<String, Object> inputParams,
            Map<String, Object> expected,
            Map<String, Object> judgeConfig,
            String tags
    ) {
    }

    public record RunView(
            AgentEvalRunEntity run,
            RunSummary summary,
            AgentEvalSuggestionService.Suggestion suggestion,
            List<AgentEvalCaseResultEntity> results
    ) {
    }

    public record RunSummary(
            int caseCount,
            int repeatCount,
            int totalExecutions,
            int runtimeSuccessCount,
            int passedExecutions,
            double runtimeSuccessRate,
            double accuracyRate,
            double avgScore,
            int p50LatencyMs,
            int p95LatencyMs,
            int biasCount,
            Map<String, Long> failedNodeCounts
    ) {
    }
}
