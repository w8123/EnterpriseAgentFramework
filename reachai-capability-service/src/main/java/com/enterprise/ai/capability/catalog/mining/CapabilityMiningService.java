package com.enterprise.ai.capability.catalog.mining;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeTraceClient;
import com.enterprise.ai.capability.catalog.composition.CapabilityCompositionCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class CapabilityMiningService {

    private static final int WINDOW = 3;
    private static final List<String> ACTIVE_DRAFT_STATUSES = List.of("DRAFT", "APPROVED", "ROLLBACK_CANDIDATE");

    private final CapabilityRuntimeTraceClient runtimeTraceClient;
    private final CapabilitySkillDraftMapper draftMapper;
    private final CapabilityCompositionCatalogService compositionCatalogService;
    private final ObjectMapper objectMapper;

    public PrecheckResult precheck(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs = recentLogs(safeDays);
        long traceCount = logs.stream()
                .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::traceId)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
        long multiStepTraceCount = aggregate(logs).size();
        boolean ready = traceCount >= 100 && multiStepTraceCount >= 20;
        return new PrecheckResult(
                safeDays,
                logs.size(),
                traceCount,
                multiStepTraceCount,
                ready,
                recommendedScenarios());
    }

    public List<CapabilitySkillDraftEntity> generateDrafts(int days, int minSupport, int limit) {
        List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs = recentLogs(Math.max(1, days));
        List<ChainPattern> patterns = mine(aggregate(logs), Math.max(2, minSupport));
        return patterns.stream()
                .limit(Math.max(1, limit))
                .map(this::upsertDraft)
                .toList();
    }

    public List<CapabilitySkillDraftEntity> listDrafts() {
        return draftMapper.selectList(new LambdaQueryWrapper<CapabilitySkillDraftEntity>()
                .orderByDesc(CapabilitySkillDraftEntity::getId));
    }

    public void markDraftStatus(Long id, String status, String reviewNote) {
        CapabilitySkillDraftEntity entity = requiredDraft(id);
        entity.setStatus(status);
        entity.setReviewNote(reviewNote);
        entity.setUpdateTime(LocalDateTime.now());
        draftMapper.updateById(entity);
    }

    public void publishDraft(Long id) {
        CapabilitySkillDraftEntity draft = requiredDraft(id);
        if (compositionCatalogService.findSkillByName(draft.getName()).isPresent()) {
            markDraftStatus(id, "PUBLISHED", "already-published: skill exists");
            return;
        }
        compositionCatalogService.create(ToolDefinitionUpsertRequest.skill(
                draft.getName(),
                draft.getDescription(),
                List.of(),
                "manual",
                null,
                true,
                true,
                "WRITE",
                CapabilityCompositionCatalogService.SKILL_KIND_SUB_AGENT,
                draft.getSpecJson()
        ));
        markDraftStatus(id, "PUBLISHED", "one-click publish");
    }

    public CapabilitySkillDraftEntity extractDraftFromTrace(String traceId, List<String> toolNameFilter) {
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId is required");
        }
        List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs = runtimeTraceClient.listToolCallsByTrace(traceId);
        if (logs.isEmpty()) {
            throw new IllegalArgumentException("trace does not exist: " + traceId);
        }
        Set<String> filter = toolNameFilter == null ? null : new LinkedHashSet<>(toolNameFilter);
        List<String> sequence = logs.stream()
                .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::toolName)
                .filter(StringUtils::hasText)
                .filter(name -> filter == null || filter.contains(name))
                .toList();
        if (sequence.size() < 2) {
            throw new IllegalArgumentException("effective tool sequence must contain at least 2 tools");
        }
        ChainPattern pattern = new ChainPattern(sequence);
        pattern.addTraceId(traceId);
        return upsertDraft(pattern, "extracted-from-trace", traceId, "（trace: " + traceId + "）", 1.0);
    }

    public CapabilitySkillDraftEntity extractDraftFromCanvas(String agentName, List<String> toolNames, String canvasJson) {
        List<String> sequence = toolNames == null ? List.of() : toolNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (sequence.size() < 2) {
            throw new IllegalArgumentException("effective canvas tool sequence must contain at least 2 tools");
        }
        ChainPattern pattern = new ChainPattern(sequence);
        pattern.addTraceId("canvas:" + (StringUtils.hasText(agentName) ? agentName.trim() : "agent"));
        String sourceKey = "canvas:" + String.join(">", sequence);
        String note = StringUtils.hasText(canvasJson)
                ? "extracted-from-canvas; canvasJson captured by caller"
                : "extracted-from-canvas";
        return upsertDraft(pattern, note, sourceKey, "（来自 Studio 画布）", 1.0);
    }

    public DemoTraceResult generateDemoTraces(String scenario, int traceCount, double successRate, double noiseRate) {
        String safeScenario = StringUtils.hasText(scenario) ? scenario.trim() : "order_after_sale";
        int safeTraceCount = Math.max(1, Math.min(traceCount <= 0 ? 120 : traceCount, 1000));
        double safeSuccessRate = clamp(successRate <= 0 ? 0.92 : successRate);
        double safeNoiseRate = clamp(noiseRate < 0 ? 0.08 : noiseRate);
        List<String> sequence = demoSequence(safeScenario);
        List<CapabilityRuntimeTraceClient.ToolCallLogCreateRequest> requests = new ArrayList<>();
        int inserted = 0;
        for (int i = 0; i < safeTraceCount; i++) {
            String traceId = "demo-" + safeScenario + "-" + UUID.randomUUID();
            LocalDateTime base = LocalDateTime.now().minusMinutes(safeTraceCount - i);
            int step = 0;
            for (String toolName : sequence) {
                requests.add(demoLog(traceId, safeScenario, toolName, base.plusSeconds(step++ * 2L), safeSuccessRate));
                inserted++;
                if (ThreadLocalRandom.current().nextDouble() < safeNoiseRate) {
                    requests.add(demoLog(traceId, safeScenario, "demo_noise_lookup", base.plusSeconds(step++ * 2L), 0.98));
                    inserted++;
                }
            }
        }
        CapabilityRuntimeTraceClient.AppendResult result = runtimeTraceClient.appendDemoToolCalls(requests);
        return new DemoTraceResult(safeScenario, safeTraceCount, result == null ? inserted : result.insertedCount(), sequence);
    }

    public int deleteDemoTraces() {
        CapabilityRuntimeTraceClient.DeleteResult result = runtimeTraceClient.deleteDemoToolCalls();
        return result == null ? 0 : result.deletedCount();
    }

    private List<CapabilityRuntimeTraceClient.ToolCallLogRecord> recentLogs(int days) {
        return runtimeTraceClient.listRecentToolCalls(days);
    }

    private List<ToolChain> aggregate(List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs) {
        Map<String, List<CapabilityRuntimeTraceClient.ToolCallLogRecord>> grouped = new LinkedHashMap<>();
        for (CapabilityRuntimeTraceClient.ToolCallLogRecord log : logs == null ? List.<CapabilityRuntimeTraceClient.ToolCallLogRecord>of() : logs) {
            if (StringUtils.hasText(log.traceId()) && StringUtils.hasText(log.toolName())) {
                grouped.computeIfAbsent(log.traceId(), key -> new ArrayList<>()).add(log);
            }
        }
        List<ToolChain> chains = new ArrayList<>();
        for (Map.Entry<String, List<CapabilityRuntimeTraceClient.ToolCallLogRecord>> entry : grouped.entrySet()) {
            List<String> sequence = entry.getValue().stream()
                    .sorted(Comparator.comparing(CapabilityRuntimeTraceClient.ToolCallLogRecord::createTime,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::toolName)
                    .toList();
            if (sequence.size() >= 2) {
                chains.add(new ToolChain(entry.getKey(), sequence));
            }
        }
        return chains;
    }

    private List<ChainPattern> mine(List<ToolChain> chains, int minSupport) {
        Map<String, ChainPattern> patterns = new HashMap<>();
        for (ToolChain chain : chains) {
            Set<String> seenInChain = new HashSet<>();
            for (int i = 0; i <= chain.sequence().size() - 2; i++) {
                List<String> gram = chain.sequence().subList(i, Math.min(i + WINDOW, chain.sequence().size()));
                if (gram.size() < 2) {
                    continue;
                }
                String key = String.join(" -> ", gram);
                if (!seenInChain.add(key)) {
                    continue;
                }
                patterns.computeIfAbsent(key, ignored -> new ChainPattern(new ArrayList<>(gram)))
                        .addTraceId(chain.traceId());
            }
        }
        return patterns.values().stream()
                .filter(pattern -> pattern.support() >= minSupport)
                .sorted(Comparator.comparingInt(ChainPattern::support).reversed())
                .toList();
    }

    private CapabilitySkillDraftEntity upsertDraft(ChainPattern pattern) {
        String sourceTraceIds = String.join(",", pattern.traceIds().stream().limit(20).toList());
        return upsertDraft(pattern, "auto-generated", sourceTraceIds, "", (double) pattern.support());
    }

    private CapabilitySkillDraftEntity upsertDraft(ChainPattern pattern,
                                                   String reviewNote,
                                                   String sourceTraceIds,
                                                   String descriptionSuffix,
                                                   double confidenceScore) {
        DraftContent content = writeDraft(pattern);
        CapabilitySkillDraftEntity existing = draftMapper.selectOne(new LambdaQueryWrapper<CapabilitySkillDraftEntity>()
                .eq(CapabilitySkillDraftEntity::getName, content.name())
                .in(CapabilitySkillDraftEntity::getStatus, ACTIVE_DRAFT_STATUSES)
                .last("limit 1"));
        if (existing != null) {
            existing.setDescription(content.description() + descriptionSuffix);
            existing.setSourceTraceIds(sourceTraceIds);
            existing.setConfidenceScore(confidenceScore);
            existing.setSpecJson(content.specJson());
            existing.setReviewNote(reviewNote);
            existing.setUpdateTime(LocalDateTime.now());
            draftMapper.updateById(existing);
            return existing;
        }
        CapabilitySkillDraftEntity entity = new CapabilitySkillDraftEntity();
        entity.setName(content.name());
        entity.setDescription(content.description() + descriptionSuffix);
        entity.setStatus("DRAFT");
        entity.setSourceTraceIds(sourceTraceIds);
        entity.setSpecJson(content.specJson());
        entity.setConfidenceScore(confidenceScore);
        entity.setReviewNote(reviewNote);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        draftMapper.insert(entity);
        return entity;
    }

    private DraftContent writeDraft(ChainPattern pattern) {
        List<String> sequence = pattern.sequence();
        String systemPrompt = "你是一个子 Agent，请严格按给定工具链处理任务，失败时返回原因，不要编造工具结果。";
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("systemPrompt", systemPrompt);
        spec.put("toolWhitelist", sequence);
        spec.put("maxSteps", 8);
        spec.put("handoffEnabled", false);
        spec.put("mining", Map.of(
                "support", pattern.support(),
                "sequence", sequence,
                "draftWriter", "template"));
        try {
            return new DraftContent(
                    buildName(sequence),
                    "自动挖掘链路：" + String.join(" -> ", sequence),
                    objectMapper.writeValueAsString(spec));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to create capability draft", ex);
        }
    }

    private CapabilitySkillDraftEntity requiredDraft(Long id) {
        CapabilitySkillDraftEntity entity = draftMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("draft does not exist: " + id);
        }
        return entity;
    }

    private CapabilityRuntimeTraceClient.ToolCallLogCreateRequest demoLog(String traceId,
                                                                          String scenario,
                                                                          String toolName,
                                                                          LocalDateTime createTime,
                                                                          double successRate) {
        boolean success = ThreadLocalRandom.current().nextDouble() <= successRate;
        return new CapabilityRuntimeTraceClient.ToolCallLogCreateRequest(
                traceId,
                "demo-session",
                "demo-capability-mining-agent",
                "DEMO_" + scenario.toUpperCase(),
                toolName,
                "{\"demo\":true,\"scenario\":\"" + scenario + "\"}",
                success ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"demo failure\"}",
                success,
                success ? null : "DEMO_ERROR",
                ThreadLocalRandom.current().nextInt(80, 900),
                ThreadLocalRandom.current().nextInt(80, 500),
                createTime
        );
    }

    private List<String> recommendedScenarios() {
        return List.of(
                "报销申请审批链路（查询余额 -> 校验预算 -> 提交审批）",
                "客户工单分诊链路（查询客户 -> 查历史工单 -> 分配处理人）",
                "订单售后链路（查订单 -> 校验状态 -> 发起退款）",
                "库存预警链路（查库存 -> 查阈值 -> 发送通知）",
                "月结对账链路（拉流水 -> 对账 -> 生成报表）"
        );
    }

    private List<String> demoSequence(String scenario) {
        return switch (scenario) {
            case "user_profile_update" -> List.of("query_user_profile", "validate_user_status", "update_user_profile");
            case "knowledge_to_ticket" -> List.of("knowledge_search", "classify_ticket", "create_ticket");
            case "inventory_warning" -> List.of("query_inventory", "query_warning_threshold", "send_inventory_notice");
            default -> List.of("query_order", "check_refund_policy", "create_refund_request");
        };
    }

    private static double clamp(double value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 1);
    }

    private static String buildName(List<String> sequence) {
        StringBuilder sb = new StringBuilder("skill");
        for (String toolName : sequence) {
            String sanitized = toolName == null ? "" : toolName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (StringUtils.hasText(sanitized)) {
                sb.append('_').append(sanitized);
            }
        }
        int hash = Math.floorMod(String.join("|", sequence).hashCode(), 1_000_000);
        return sb + "_" + String.format("%06d", hash);
    }

    private record ToolChain(String traceId, List<String> sequence) {
    }

    private static final class ChainPattern {
        private final List<String> sequence;
        private final Set<String> traceIds = new LinkedHashSet<>();

        private ChainPattern(List<String> sequence) {
            this.sequence = sequence;
        }

        private List<String> sequence() {
            return sequence;
        }

        private int support() {
            return traceIds.size();
        }

        private List<String> traceIds() {
            return new ArrayList<>(traceIds);
        }

        private void addTraceId(String traceId) {
            if (StringUtils.hasText(traceId)) {
                traceIds.add(traceId);
            }
        }
    }

    private record DraftContent(String name, String description, String specJson) {
    }

    public record PrecheckResult(
            int days,
            int logCount,
            long traceCount,
            long multiStepTraceCount,
            boolean readyForMining,
            List<String> recommendedScenarios
    ) {
    }

    public record DemoTraceResult(String scenario, int traceCount, int insertedLogCount, List<String> sequence) {
    }
}
