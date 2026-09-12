package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentIdentityQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates immutable-target experiments and aggregates durable asynchronous results. */
@Service
@RequiredArgsConstructor
public class RuntimeEvalExperimentService {

    private static final int MAX_VARIANTS = 5;
    private static final int MAX_REPEAT_COUNT = 20;
    private static final int MAX_TASKS = 10_000;
    private static final int DETAIL_RESULT_LIMIT = 500;

    private final RuntimeEvalExperimentMapper experimentMapper;
    private final RuntimeEvalExperimentVariantMapper variantMapper;
    private final RuntimeEvalExperimentItemMapper experimentItemMapper;
    private final RuntimeEvalScoreMapper scoreMapper;
    private final RuntimeEvalTaskMapper taskMapper;
    private final RuntimeEvalDatasetService datasetService;
    private final RuntimeEvalDatasetMapper datasetMapper;
    private final RuntimeEvalEvaluatorSuiteService suiteService;
    private final RuntimeEvalTargetSnapshotService targetSnapshotService;
    private final RuntimeAgentIdentityQuery agentIdentities;
    private final RuntimeEvalJsonSupport json;

    public List<RuntimeEvalExperimentSummaryView> list(String tenantId, String targetId) {
        String canonicalTargetId = canonicalAgentId(targetId);
        return experimentMapper.selectList(Wrappers.<RuntimeEvalExperimentEntity>lambdaQuery()
                        .eq(RuntimeEvalExperimentEntity::getTenantId, normalizedTenant(tenantId))
                        .eq(StringUtils.hasText(canonicalTargetId), RuntimeEvalExperimentEntity::getTargetId, canonicalTargetId)
                        .orderByDesc(RuntimeEvalExperimentEntity::getCreatedAt)
                        .orderByDesc(RuntimeEvalExperimentEntity::getId)
                        .last("LIMIT 200"))
                .stream().map(this::summaryView).toList();
    }

    @Transactional
    public RuntimeEvalExperimentDetailView create(Map<String, Object> request) {
        String tenantId = normalizedTenant(text(request, "tenantId"));
        String idempotencyKey = text(request, "idempotencyKey");
        if (StringUtils.hasText(idempotencyKey)) {
            RuntimeEvalExperimentEntity existing = experimentMapper.selectOne(
                    Wrappers.<RuntimeEvalExperimentEntity>lambdaQuery()
                            .eq(RuntimeEvalExperimentEntity::getTenantId, tenantId)
                            .eq(RuntimeEvalExperimentEntity::getIdempotencyKey, idempotencyKey)
                            .last("LIMIT 1"));
            if (existing != null) return get(existing.getId());
        }

        String targetType = upper(defaultText(request, "targetType", "AGENT"));
        if (!"AGENT".equals(targetType)) {
            throw new IllegalArgumentException("Eval experiment targetType currently supports AGENT only");
        }
        String requestedTargetId = requiredText(request, "targetId", "agentId");
        RuntimeEvalDatasetVersionEntity datasetVersion = resolveDatasetVersion(request);
        RuntimeEvalDatasetEntity dataset = datasetMapper.selectById(datasetVersion.getDatasetId());
        if (dataset == null
                || !tenantId.equals(dataset.getTenantId())
                || !targetType.equalsIgnoreCase(dataset.getTargetType())) {
            throw new IllegalArgumentException("Eval dataset version does not belong to the requested target");
        }
        List<RuntimeEvalDatasetItemEntity> datasetItems = datasetService.enabledItems(datasetVersion.getId());
        if (datasetItems.isEmpty()) {
            throw new IllegalArgumentException("Eval dataset version has no enabled items");
        }

        String actor = text(request, "createdBy");
        RuntimeEvalEvaluatorSuiteVersionEntity suite = suiteService.resolve(
                optionalLong(request == null ? null : request.get("evaluatorSuiteVersionId")), tenantId, actor);
        int repeatCount = boundedInt(request == null ? null : request.get("repeatCount"), 1,
                1, MAX_REPEAT_COUNT, "repeatCount");
        List<PreparedVariant> variants = captureVariants(request, requestedTargetId, tenantId, actor);
        String targetId = variants.stream()
                .filter(value -> "BASELINE".equals(value.role()))
                .map(value -> value.target().executionContext().agent().id())
                .findFirst().orElseThrow();
        if (!targetId.equals(dataset.getTargetId())) {
            throw new IllegalArgumentException("Eval dataset version does not belong to the resolved Agent target");
        }
        String resolvedProjectCode = variants.stream()
                .filter(value -> "BASELINE".equals(value.role()))
                .map(value -> value.target().executionContext().agent().projectCode())
                .findFirst().orElse(null);
        if (StringUtils.hasText(dataset.getProjectCode())
                && !Objects.equals(dataset.getProjectCode(), resolvedProjectCode)) {
            throw new IllegalArgumentException("Eval dataset project does not match the resolved Agent target");
        }
        long taskCount = (long) variants.size() * datasetItems.size() * repeatCount;
        if (taskCount > MAX_TASKS) {
            throw new IllegalArgumentException("Eval experiment exceeds " + MAX_TASKS + " tasks");
        }

        RuntimeEvalExperimentEntity experiment = new RuntimeEvalExperimentEntity();
        experiment.setTenantId(tenantId);
        experiment.setProjectCode(resolvedProjectCode);
        experiment.setTargetType(targetType);
        experiment.setTargetId(targetId);
        experiment.setName(defaultText(request, "name", dataset.getName() + " experiment"));
        experiment.setDatasetVersionId(datasetVersion.getId());
        experiment.setEvaluatorSuiteVersionId(suite.getId());
        experiment.setRepeatCount(repeatCount);
        experiment.setStatus("QUEUED");
        experiment.setIdempotencyKey(idempotencyKey);
        experiment.setVariantCount(variants.size());
        experiment.setTaskCount(Math.toIntExact(taskCount));
        experiment.setCompletedTaskCount(0);
        experiment.setFailedTaskCount(0);
        experiment.setGateStatus("PENDING");
        experiment.setGateConfigJson(json.canonicalJson(gateConfig(request)));
        experiment.setSummaryJson(json.canonicalJson(Map.of()));
        experiment.setCreatedBy(actor);
        LocalDateTime now = LocalDateTime.now();
        experiment.setCreatedAt(now);
        experiment.setUpdatedAt(now);
        try {
            experimentMapper.insert(experiment);
        } catch (DuplicateKeyException duplicate) {
            if (!StringUtils.hasText(idempotencyKey)) throw duplicate;
            RuntimeEvalExperimentEntity winner = experimentMapper.selectOne(
                    Wrappers.<RuntimeEvalExperimentEntity>lambdaQuery()
                            .eq(RuntimeEvalExperimentEntity::getTenantId, tenantId)
                            .eq(RuntimeEvalExperimentEntity::getIdempotencyKey, idempotencyKey)
                            .last("LIMIT 1"));
            if (winner == null) throw duplicate;
            return get(winner.getId());
        }

        for (PreparedVariant prepared : variants) {
            RuntimeEvalExperimentVariantEntity variant = new RuntimeEvalExperimentVariantEntity();
            variant.setExperimentId(experiment.getId());
            variant.setVariantKey(prepared.key());
            variant.setDisplayName(prepared.displayName());
            variant.setVariantRole(prepared.role());
            variant.setTargetSnapshotId(prepared.target().snapshot().getId());
            variant.setTargetConfigVersionId(prepared.target().snapshot().getAgentConfigVersionId());
            variant.setTargetConfigStatus(prepared.target().snapshot().getSourceStatus());
            variant.setTargetFingerprint(prepared.target().snapshot().getFingerprintSha256());
            variant.setStatus("QUEUED");
            variant.setSummaryJson(json.canonicalJson(Map.of()));
            variant.setCreatedAt(now);
            variantMapper.insert(variant);

            for (RuntimeEvalDatasetItemEntity datasetItem : datasetItems) {
                for (int repeatNo = 1; repeatNo <= repeatCount; repeatNo++) {
                    RuntimeEvalExperimentItemEntity item = new RuntimeEvalExperimentItemEntity();
                    item.setExperimentId(experiment.getId());
                    item.setVariantId(variant.getId());
                    item.setDatasetItemId(datasetItem.getId());
                    item.setRepeatNo(repeatNo);
                    item.setStatus("PENDING");
                    item.setRuntimeSuccess(false);
                    item.setAssertionPassed(false);
                    item.setCreatedAt(now);
                    experimentItemMapper.insert(item);

                    RuntimeEvalTaskEntity task = new RuntimeEvalTaskEntity();
                    task.setTaskType("EXECUTE_ITEM");
                    task.setExperimentId(experiment.getId());
                    task.setExperimentItemId(item.getId());
                    task.setStatus("PENDING");
                    task.setPriority(0);
                    task.setAttemptCount(0);
                    task.setMaxAttempts(3);
                    task.setAvailableAt(now);
                    task.setCreatedAt(now);
                    task.setUpdatedAt(now);
                    taskMapper.insert(task);
                }
            }
        }
        return get(experiment.getId());
    }

    public RuntimeEvalExperimentDetailView get(Long experimentId) {
        RuntimeEvalExperimentEntity experiment = requiredExperiment(experimentId);
        List<RuntimeEvalExperimentVariantEntity> variants = variants(experimentId);
        long resultCount = experimentItemMapper.selectCount(
                Wrappers.<RuntimeEvalExperimentItemEntity>lambdaQuery()
                        .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId));
        List<RuntimeEvalExperimentItemEntity> items = experimentItemMapper.selectList(
                Wrappers.<RuntimeEvalExperimentItemEntity>lambdaQuery()
                        .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId)
                        .orderByAsc(RuntimeEvalExperimentItemEntity::getVariantId)
                        .orderByAsc(RuntimeEvalExperimentItemEntity::getDatasetItemId)
                        .orderByAsc(RuntimeEvalExperimentItemEntity::getRepeatNo)
                        .last("LIMIT " + DETAIL_RESULT_LIMIT));
        List<Long> itemIds = items.stream().map(RuntimeEvalExperimentItemEntity::getId).toList();
        List<RuntimeEvalScoreEntity> scores = itemIds.isEmpty() ? List.of()
                : scoreMapper.selectList(Wrappers.<RuntimeEvalScoreEntity>lambdaQuery()
                        .in(RuntimeEvalScoreEntity::getExperimentItemId, itemIds)
                        .orderByAsc(RuntimeEvalScoreEntity::getExperimentItemId)
                        .orderByAsc(RuntimeEvalScoreEntity::getId));
        return new RuntimeEvalExperimentDetailView(
                summaryView(experiment), datasetService.getVersion(experiment.getDatasetVersionId()),
                variants.stream().map(this::variantView).toList(),
                items.stream().map(this::itemView).toList(),
                scores.stream().map(this::scoreView).toList(),
                resultCount, resultCount > DETAIL_RESULT_LIMIT);
    }

    public RuntimeEvalExperimentItemPage listItems(Long experimentId,
                                                    Long variantId,
                                                    int page,
                                                    int pageSize) {
        requiredExperiment(experimentId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, pageSize));
        var countQuery = Wrappers.<RuntimeEvalExperimentItemEntity>lambdaQuery()
                .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId)
                .eq(variantId != null, RuntimeEvalExperimentItemEntity::getVariantId, variantId);
        long total = experimentItemMapper.selectCount(countQuery);
        int offset = (safePage - 1) * safeSize;
        List<RuntimeEvalExperimentItemView> items = experimentItemMapper.selectList(
                        Wrappers.<RuntimeEvalExperimentItemEntity>lambdaQuery()
                                .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId)
                                .eq(variantId != null, RuntimeEvalExperimentItemEntity::getVariantId, variantId)
                                .orderByAsc(RuntimeEvalExperimentItemEntity::getVariantId)
                                .orderByAsc(RuntimeEvalExperimentItemEntity::getDatasetItemId)
                                .orderByAsc(RuntimeEvalExperimentItemEntity::getRepeatNo)
                                .last("LIMIT " + offset + "," + safeSize))
                .stream().map(this::itemView).toList();
        return new RuntimeEvalExperimentItemPage(total, safePage, safeSize, items);
    }

    @Transactional
    public RuntimeEvalExperimentDetailView cancel(Long experimentId) {
        RuntimeEvalExperimentEntity experiment = requiredExperiment(experimentId);
        if (isTerminal(experiment.getStatus())) return get(experimentId);
        experiment.setStatus("CANCEL_REQUESTED");
        experiment.setUpdatedAt(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        taskMapper.cancelPending(experimentId);
        experimentItemMapper.update(null, Wrappers.<RuntimeEvalExperimentItemEntity>lambdaUpdate()
                .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId)
                .eq(RuntimeEvalExperimentItemEntity::getStatus, "PENDING")
                .set(RuntimeEvalExperimentItemEntity::getStatus, "CANCELLED")
                .set(RuntimeEvalExperimentItemEntity::getErrorCode, "EVAL_EXPERIMENT_CANCELLED")
                .set(RuntimeEvalExperimentItemEntity::getErrorMessage, "Experiment cancellation was requested")
                .set(RuntimeEvalExperimentItemEntity::getFinishedAt, LocalDateTime.now()));
        refreshAndFinalize(experimentId);
        return get(experimentId);
    }

    void markRunning(Long experimentId) {
        RuntimeEvalExperimentEntity experiment = requiredExperiment(experimentId);
        if ("QUEUED".equals(experiment.getStatus())) {
            experiment.setStatus("RUNNING");
            experiment.setStartedAt(LocalDateTime.now());
            experiment.setUpdatedAt(LocalDateTime.now());
            experimentMapper.updateById(experiment);
        }
    }

    @Transactional
    void refreshAndFinalize(Long experimentId) {
        RuntimeEvalExperimentEntity experiment = requiredExperiment(experimentId);
        long completed = countTasks(experimentId, "COMPLETED");
        long dead = countTasks(experimentId, "DEAD");
        long cancelled = countTasks(experimentId, "CANCELLED");
        experiment.setCompletedTaskCount(Math.toIntExact(completed));
        experiment.setFailedTaskCount(Math.toIntExact(dead));
        experiment.setUpdatedAt(LocalDateTime.now());
        long terminal = completed + dead + cancelled;
        if (terminal < experiment.getTaskCount()) {
            experimentMapper.updateById(experiment);
            return;
        }

        List<RuntimeEvalExperimentVariantEntity> variants = variants(experimentId);
        List<RuntimeEvalExperimentItemEntity> items = experimentItemMapper.selectList(
                Wrappers.<RuntimeEvalExperimentItemEntity>lambdaQuery()
                        .eq(RuntimeEvalExperimentItemEntity::getExperimentId, experimentId));
        Map<Long, VariantMetrics> metrics = new LinkedHashMap<>();
        for (RuntimeEvalExperimentVariantEntity variant : variants) {
            List<RuntimeEvalExperimentItemEntity> variantItems = items.stream()
                    .filter(item -> Objects.equals(item.getVariantId(), variant.getId())).toList();
            VariantMetrics value = metrics(variant, variantItems);
            metrics.put(variant.getId(), value);
            variant.setStatus(value.failedExecutions() == 0 ? "COMPLETED" : "PARTIAL");
            variant.setSummaryJson(json.canonicalJson(value.asMap()));
            variantMapper.updateById(variant);
        }

        Map<String, Object> gate = evaluateGate(variants, metrics, json.readMap(experiment.getGateConfigJson()));
        boolean cancellation = "CANCEL_REQUESTED".equals(experiment.getStatus()) || cancelled > 0;
        if (dead > 0) {
            gate.put("status", "NOT_EVALUATED");
            gate.put("reason", "One or more infrastructure tasks exhausted retries");
        }
        experiment.setStatus(cancellation ? "CANCELLED" : dead > 0 ? "PARTIAL" : "COMPLETED");
        experiment.setGateStatus(cancellation || dead > 0
                ? "NOT_EVALUATED" : String.valueOf(gate.get("status")));
        experiment.setSummaryJson(json.canonicalJson(json.ordered(
                "totalTasks", experiment.getTaskCount(),
                "completedTasks", completed,
                "failedTasks", dead,
                "cancelledTasks", cancelled,
                "variants", variants.stream().map(value -> metrics.get(value.getId()).asMap()).toList(),
                "gate", gate)));
        experiment.setFinishedAt(LocalDateTime.now());
        experiment.setUpdatedAt(LocalDateTime.now());
        experimentMapper.updateById(experiment);
    }

    void refreshOpenExperiments(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<RuntimeEvalExperimentEntity> open = experimentMapper.selectList(
                Wrappers.<RuntimeEvalExperimentEntity>lambdaQuery()
                        .in(RuntimeEvalExperimentEntity::getStatus,
                                List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED"))
                        .orderByAsc(RuntimeEvalExperimentEntity::getCreatedAt)
                        .last("LIMIT " + safeLimit));
        open.forEach(value -> refreshAndFinalize(value.getId()));
    }

    RuntimeEvalExperimentEntity requiredExperiment(Long experimentId) {
        RuntimeEvalExperimentEntity value = experimentId == null ? null : experimentMapper.selectById(experimentId);
        if (value == null) throw new IllegalArgumentException("Eval experiment not found: " + experimentId);
        return value;
    }

    private RuntimeEvalDatasetVersionEntity resolveDatasetVersion(Map<String, Object> request) {
        Long versionId = optionalLong(request == null ? null : request.get("datasetVersionId"));
        if (versionId != null) return datasetService.requiredVersion(versionId);
        Long datasetId = optionalLong(request == null ? null : request.get("datasetId"));
        RuntimeEvalDatasetEntity dataset = datasetService.requiredDataset(datasetId);
        if (dataset.getCurrentVersionId() == null) {
            throw new IllegalArgumentException("Eval dataset has no current published version: " + datasetId);
        }
        return datasetService.requiredVersion(dataset.getCurrentVersionId());
    }

    private List<PreparedVariant> captureVariants(Map<String, Object> request,
                                                  String targetId,
                                                  String tenantId,
                                                  String actor) {
        List<Map<String, Object>> requested = json.mapList(request == null ? null : request.get("variants"));
        if (requested.isEmpty()) {
            requested = List.of(
                    json.ordered("key", "baseline", "displayName", "Baseline", "role", "BASELINE",
                            "configVersionId", optionalLong(request == null ? null : request.get("baselineConfigVersionId"))),
                    json.ordered("key", "candidate", "displayName", "Candidate", "role", "CANDIDATE",
                            "configVersionId", optionalLong(firstValue(request, "candidateConfigVersionId", "configVersionId"))));
        }
        if (requested.size() < 2 || requested.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("Eval experiment requires 2 to " + MAX_VARIANTS + " variants");
        }
        Set<String> keys = new HashSet<>();
        int baselineCount = 0;
        List<PreparedVariant> result = new ArrayList<>();
        String resolvedAgentId = null;
        for (Map<String, Object> value : requested) {
            String key = requiredText(value, "key", "variantKey");
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) {
                throw new IllegalArgumentException("Eval variant key is invalid: " + key);
            }
            if (!keys.add(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate Eval variant key: " + key);
            }
            String role = upper(defaultText(value, "role", "CANDIDATE"));
            if (!List.of("BASELINE", "CANDIDATE").contains(role)) {
                throw new IllegalArgumentException("Eval variant role is invalid: " + role);
            }
            if ("BASELINE".equals(role)) baselineCount++;
            Long configVersionId = optionalLong(value.get("configVersionId"));
            RuntimeEvalTargetSnapshotService.CapturedTarget target = targetSnapshotService.captureAgent(
                    targetId, configVersionId, tenantId, actor);
            String currentAgentId = target.executionContext().agent().id();
            if (resolvedAgentId == null) {
                resolvedAgentId = currentAgentId;
            } else if (!resolvedAgentId.equals(currentAgentId)) {
                throw new IllegalArgumentException("Eval variants resolved to different Agent targets: " + key);
            }
            result.add(new PreparedVariant(key, defaultText(value, "displayName", key), role, target));
        }
        if (baselineCount != 1 || result.stream().noneMatch(value -> "CANDIDATE".equals(value.role()))) {
            throw new IllegalArgumentException("Eval experiment requires exactly one baseline and at least one candidate");
        }
        PreparedVariant baseline = result.stream().filter(value -> "BASELINE".equals(value.role())).findFirst().orElseThrow();
        boolean allSame = result.stream().filter(value -> "CANDIDATE".equals(value.role()))
                .allMatch(value -> baseline.target().snapshot().getFingerprintSha256()
                        .equals(value.target().snapshot().getFingerprintSha256()));
        if (allSame && !booleanValue(request == null ? null : request.get("allowSameTarget"), false)) {
            throw new IllegalArgumentException("Baseline and candidate resolve to the same target fingerprint");
        }
        return List.copyOf(result);
    }

    private Map<String, Object> gateConfig(Map<String, Object> request) {
        Map<String, Object> supplied = json.map(request == null ? null : request.get("gateConfig"));
        return json.ordered(
                "minCandidateScore", doubleValue(supplied.get("minCandidateScore"), 0.8),
                "maxScoreRegression", doubleValue(supplied.get("maxScoreRegression"), 0.0),
                "maxLatencyRegressionRatio", doubleValue(supplied.get("maxLatencyRegressionRatio"), 0.25),
                "requireNoNewFailures", booleanValue(supplied.get("requireNoNewFailures"), true));
    }

    private Map<String, Object> evaluateGate(List<RuntimeEvalExperimentVariantEntity> variants,
                                             Map<Long, VariantMetrics> metrics,
                                             Map<String, Object> config) {
        RuntimeEvalExperimentVariantEntity baseline = variants.stream()
                .filter(value -> "BASELINE".equals(value.getVariantRole())).findFirst().orElseThrow();
        VariantMetrics base = metrics.get(baseline.getId());
        double minimum = doubleValue(config.get("minCandidateScore"), 0.8);
        double maxRegression = doubleValue(config.get("maxScoreRegression"), 0.0);
        double maxLatencyRatio = doubleValue(config.get("maxLatencyRegressionRatio"), 0.25);
        boolean noNewFailures = booleanValue(config.get("requireNoNewFailures"), true);
        List<Map<String, Object>> comparisons = new ArrayList<>();
        boolean passed = true;
        for (RuntimeEvalExperimentVariantEntity variant : variants) {
            if (!"CANDIDATE".equals(variant.getVariantRole())) continue;
            VariantMetrics candidate = metrics.get(variant.getId());
            double scoreDelta = candidate.averageScore() - base.averageScore();
            double latencyRatio = base.p95LatencyMs() <= 0 ? 0
                    : (double) candidate.p95LatencyMs() / base.p95LatencyMs() - 1;
            boolean scorePass = candidate.averageScore() >= minimum && scoreDelta >= -maxRegression;
            boolean latencyPass = latencyRatio <= maxLatencyRatio;
            boolean failurePass = !noNewFailures || candidate.failedExecutions() <= base.failedExecutions();
            boolean candidatePass = scorePass && latencyPass && failurePass;
            passed &= candidatePass;
            comparisons.add(json.ordered(
                    "variantId", variant.getId(),
                    "variantKey", variant.getVariantKey(),
                    "passed", candidatePass,
                    "scoreDelta", scoreDelta,
                    "p95LatencyRegressionRatio", latencyRatio,
                    "failedExecutionDelta", candidate.failedExecutions() - base.failedExecutions(),
                    "checks", json.ordered("score", scorePass, "latency", latencyPass, "failures", failurePass)));
        }
        return json.ordered("status", passed ? "PASSED" : "FAILED", "config", config,
                "baselineVariantId", baseline.getId(), "comparisons", comparisons);
    }

    private VariantMetrics metrics(RuntimeEvalExperimentVariantEntity variant,
                                   List<RuntimeEvalExperimentItemEntity> items) {
        List<RuntimeEvalExperimentItemEntity> completed = items.stream()
                .filter(value -> "COMPLETED".equals(value.getStatus())).toList();
        int passed = (int) completed.stream().filter(value -> Boolean.TRUE.equals(value.getAssertionPassed())).count();
        int runtimeSuccess = (int) completed.stream().filter(value -> Boolean.TRUE.equals(value.getRuntimeSuccess())).count();
        int failed = items.size() - completed.size();
        double average = items.isEmpty() ? 0 : completed.stream().map(RuntimeEvalExperimentItemEntity::getScore)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum() / items.size();
        List<Integer> latencies = completed.stream().map(RuntimeEvalExperimentItemEntity::getElapsedMs)
                .filter(Objects::nonNull).sorted().toList();
        return new VariantMetrics(variant.getId(), variant.getVariantKey(), variant.getVariantRole(),
                variant.getTargetFingerprint(), items.size(), completed.size(), failed,
                ratio(runtimeSuccess, items.size()), ratio(passed, items.size()), average,
                percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    private List<RuntimeEvalExperimentVariantEntity> variants(Long experimentId) {
        return variantMapper.selectList(Wrappers.<RuntimeEvalExperimentVariantEntity>lambdaQuery()
                .eq(RuntimeEvalExperimentVariantEntity::getExperimentId, experimentId)
                .orderByAsc(RuntimeEvalExperimentVariantEntity::getId));
    }

    private long countTasks(Long experimentId, String status) {
        return taskMapper.selectCount(Wrappers.<RuntimeEvalTaskEntity>lambdaQuery()
                .eq(RuntimeEvalTaskEntity::getExperimentId, experimentId)
                .eq(RuntimeEvalTaskEntity::getStatus, status));
    }

    private RuntimeEvalExperimentSummaryView summaryView(RuntimeEvalExperimentEntity value) {
        return new RuntimeEvalExperimentSummaryView(
                value.getId(), value.getTenantId(), value.getProjectCode(), value.getTargetType(), value.getTargetId(),
                value.getName(), value.getDatasetVersionId(), value.getEvaluatorSuiteVersionId(),
                value.getRepeatCount(), value.getStatus(), value.getVariantCount(), value.getTaskCount(),
                value.getCompletedTaskCount(), value.getFailedTaskCount(), value.getGateStatus(),
                json.readMap(value.getSummaryJson()), value.getCreatedBy(), value.getCreatedAt(),
                value.getStartedAt(), value.getFinishedAt());
    }

    private RuntimeEvalExperimentVariantView variantView(RuntimeEvalExperimentVariantEntity value) {
        return new RuntimeEvalExperimentVariantView(
                value.getId(), value.getExperimentId(), value.getVariantKey(), value.getDisplayName(),
                value.getVariantRole(), value.getTargetSnapshotId(), value.getTargetConfigVersionId(),
                value.getTargetConfigStatus(), value.getTargetFingerprint(), value.getStatus(),
                json.readMap(value.getSummaryJson()));
    }

    private RuntimeEvalExperimentItemView itemView(RuntimeEvalExperimentItemEntity value) {
        return new RuntimeEvalExperimentItemView(
                value.getId(), value.getExperimentId(), value.getVariantId(), value.getDatasetItemId(),
                value.getRepeatNo(), value.getStatus(), value.getRuntimeSuccess(), value.getAssertionPassed(),
                value.getScore(), value.getElapsedMs(), value.getAnswer(), value.getTraceId(),
                json.readMap(value.getExecutionMetadataJson()), json.mapList(json.readValue(value.getEvaluatorResultsJson())),
                value.getErrorCode(), value.getErrorMessage(), value.getStartedAt(), value.getFinishedAt());
    }

    private RuntimeEvalScoreView scoreView(RuntimeEvalScoreEntity value) {
        return new RuntimeEvalScoreView(
                value.getId(), value.getExperimentItemId(), value.getEvaluatorKey(), value.getEvaluatorType(),
                value.getScore(), value.getPassed(), value.getWeight(), value.getReason(),
                json.readMap(value.getMetadataJson()));
    }

    private int boundedInt(Object value, int fallback, int minimum, int maximum, String field) {
        int parsed = value instanceof Number number ? number.intValue()
                : value instanceof String text && StringUtils.hasText(text) ? Integer.parseInt(text) : fallback;
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("Eval experiment " + field + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private Long optionalLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Long.parseLong(text);
        return null;
    }

    private String requiredText(Map<String, Object> values, String... fields) {
        for (String field : fields) {
            String value = text(values, field);
            if (StringUtils.hasText(value)) return value;
        }
        throw new IllegalArgumentException("Eval experiment " + String.join("/", fields) + " is required");
    }

    private Object firstValue(Map<String, Object> values, String... fields) {
        if (values == null) return null;
        for (String field : fields) if (values.containsKey(field) && values.get(field) != null) return values.get(field);
        return null;
    }

    private String text(Map<String, Object> values, String field) {
        Object value = values == null ? null : values.get(field);
        return value == null ? null : trim(String.valueOf(value));
    }

    private String defaultText(Map<String, Object> values, String field, String fallback) {
        return firstText(text(values, field), fallback);
    }

    private String normalizedTenant(String value) {
        return firstText(value, "default");
    }

    private String canonicalAgentId(String value) {
        if (!StringUtils.hasText(value)) return null;
        String lookup = value.trim();
        return agentIdentities.find(lookup).map(agent -> agent.id())
                .filter(StringUtils::hasText).orElse(lookup);
    }

    private String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text && StringUtils.hasText(text)) return Boolean.parseBoolean(text);
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Double.parseDouble(text);
        return fallback;
    }

    private boolean isTerminal(String status) {
        return List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(status);
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }

    private int percentile(List<Integer> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private record PreparedVariant(
            String key,
            String displayName,
            String role,
            RuntimeEvalTargetSnapshotService.CapturedTarget target) {
    }

    private record VariantMetrics(
            Long variantId,
            String variantKey,
            String variantRole,
            String targetFingerprint,
            int totalExecutions,
            int completedExecutions,
            int failedExecutions,
            double runtimeSuccessRate,
            double passRate,
            double averageScore,
            int p50LatencyMs,
            int p95LatencyMs) {

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("variantId", variantId);
            result.put("variantKey", variantKey);
            result.put("variantRole", variantRole);
            result.put("targetFingerprint", targetFingerprint);
            result.put("totalExecutions", totalExecutions);
            result.put("completedExecutions", completedExecutions);
            result.put("failedExecutions", failedExecutions);
            result.put("runtimeSuccessRate", runtimeSuccessRate);
            result.put("passRate", passRate);
            result.put("averageScore", averageScore);
            result.put("p50LatencyMs", p50LatencyMs);
            result.put("p95LatencyMs", p95LatencyMs);
            return result;
        }
    }
}
