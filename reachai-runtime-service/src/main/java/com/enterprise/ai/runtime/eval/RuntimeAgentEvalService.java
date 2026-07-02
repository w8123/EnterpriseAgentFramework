package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeAgentEvalService {

    private static final String RUNTIME_NOT_ATTACHED = "EVAL_RUNTIME_NOT_ATTACHED";
    private static final TypeReference<List<Map<String, Object>>> CASE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeAgentEvalDatasetMapper datasetMapper;
    private final RuntimeAgentEvalCaseMapper caseMapper;
    private final RuntimeAgentEvalRunMapper runMapper;
    private final RuntimeAgentEvalCaseResultMapper resultMapper;
    private final ObjectMapper objectMapper;

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

    @Transactional
    public RuntimeAgentEvalRunView startRun(Map<String, Object> request) {
        Long datasetId = requiredLong(request, "datasetId");
        RuntimeAgentEvalDatasetEntity dataset = requiredDataset(datasetId);
        List<RuntimeAgentEvalCaseEntity> cases = caseMapper.selectList(Wrappers.<RuntimeAgentEvalCaseEntity>lambdaQuery()
                .eq(RuntimeAgentEvalCaseEntity::getDatasetId, datasetId)
                .eq(RuntimeAgentEvalCaseEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentEvalCaseEntity::getId));
        int repeatCount = Math.max(1, intValue(request.get("repeatCount"), 1));
        RuntimeAgentEvalRunEntity run = new RuntimeAgentEvalRunEntity();
        run.setDatasetId(datasetId);
        run.setAgentId(defaultText(request, "agentId", dataset.getAgentId()));
        run.setAgentName(defaultText(request, "agentName", dataset.getAgentName()));
        run.setRunName(defaultText(request, "runName", dataset.getName()));
        run.setRepeatCount(repeatCount);
        run.setStatus("RUNNING");
        run.setGraphSpecJson(toJson(request.get("graphSpec")));
        run.setCanvasSnapshotJson(toJson(request.get("canvasSnapshot")));
        LocalDateTime now = LocalDateTime.now();
        run.setStartedAt(now);
        run.setCreateTime(now);
        runMapper.insert(run);

        for (int round = 1; round <= repeatCount; round++) {
            for (RuntimeAgentEvalCaseEntity evalCase : cases) {
                resultMapper.insert(notAttachedResult(run, evalCase, round));
            }
        }

        int total = cases.size() * repeatCount;
        Map<String, Object> summary = summary(cases.size(), repeatCount, total);
        Map<String, Object> suggestion = suggestion(total);
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

    private RuntimeAgentEvalCaseResultEntity notAttachedResult(RuntimeAgentEvalRunEntity run,
                                                               RuntimeAgentEvalCaseEntity evalCase,
                                                               int round) {
        RuntimeAgentEvalCaseResultEntity result = new RuntimeAgentEvalCaseResultEntity();
        result.setRunId(run.getId());
        result.setDatasetId(run.getDatasetId());
        result.setCaseId(evalCase.getId());
        result.setCaseNo(evalCase.getCaseNo());
        result.setRoundNo(round);
        result.setStatus("COMPLETED");
        result.setRuntimeSuccess(false);
        result.setAssertionPassed(false);
        result.setScore(0.0);
        result.setElapsedMs(0);
        result.setErrorCode(RUNTIME_NOT_ATTACHED);
        result.setErrorMessage("Runtime eval execution is not attached yet");
        result.setStepResultsJson(toJson(Map.of()));
        result.setJudgeResultJson(toJson(Map.of("reason", RUNTIME_NOT_ATTACHED)));
        result.setCreateTime(LocalDateTime.now());
        return result;
    }

    private Map<String, Object> summary(int caseCount, int repeatCount, int total) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", caseCount);
        summary.put("repeatCount", repeatCount);
        summary.put("totalExecutions", total);
        summary.put("runtimeSuccessCount", 0);
        summary.put("passedExecutions", 0);
        summary.put("runtimeSuccessRate", 0);
        summary.put("accuracyRate", 0);
        summary.put("avgScore", 0);
        summary.put("p50LatencyMs", 0);
        summary.put("p95LatencyMs", 0);
        summary.put("biasCount", 0);
        summary.put("failedNodeCounts", total == 0 ? Map.of() : Map.of(RUNTIME_NOT_ATTACHED, total));
        return summary;
    }

    private Map<String, Object> suggestion(int total) {
        return Map.of(
                "summary", total == 0
                        ? "No enabled eval cases were found."
                        : "Runtime eval execution is not attached yet; run records and case results were created.",
                "items", total == 0 ? List.of() : List.of(Map.of(
                        "nodeId", "runtime",
                        "severity", "MEDIUM",
                        "reason", RUNTIME_NOT_ATTACHED,
                        "recommendation", "Attach Agent Eval to the Runtime GraphSpec executor before using pass rates.")));
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : objectMapper.convertValue(value, MAP_TYPE));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Agent Eval JSON payload", ex);
        }
    }
}
