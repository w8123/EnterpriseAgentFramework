package com.enterprise.ai.agent.mining;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.tool.log.ToolCallLogEntity;
import com.enterprise.ai.agent.tool.log.ToolCallLogMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillMiningService {
    private final ToolCallLogMapper toolCallLogMapper;
    private final ToolChainAggregator aggregator;
    private final PrefixSpanMiner miner;
    private final SkillDraftLlmWriter llmWriter;
    private final SkillDraftMapper draftMapper;
    private final ToolDefinitionService toolDefinitionService;

    public PrecheckResult precheck(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);
        List<ToolCallLogEntity> logs = toolCallLogMapper.selectList(new LambdaQueryWrapper<ToolCallLogEntity>()
                .ge(ToolCallLogEntity::getCreateTime, from));
        long traceCount = logs.stream().map(ToolCallLogEntity::getTraceId).filter(x -> x != null && !x.isBlank()).distinct().count();
        long multiStepTraceCount = aggregator.aggregate(logs).size();
        boolean ready = traceCount >= 100 && multiStepTraceCount >= 20;
        return new PrecheckResult(safeDays, logs.size(), traceCount, multiStepTraceCount, ready, recommendedScenarios());
    }

    public List<SkillDraftEntity> generateDrafts(int days, int minSupport, int limit) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(1, days));
        List<ToolCallLogEntity> logs = toolCallLogMapper.selectList(new LambdaQueryWrapper<ToolCallLogEntity>()
                .ge(ToolCallLogEntity::getCreateTime, from));
        List<ToolChainAggregator.ToolChain> chains = aggregator.aggregate(logs);
        List<PrefixSpanMiner.ChainPattern> patterns = miner.mine(chains, Math.max(2, minSupport));
        return patterns.stream().limit(Math.max(1, limit)).map(pattern -> {
            SkillDraftLlmWriter.DraftContent draft = llmWriter.write(pattern);
            String sourceTraceIds = String.join(",", pattern.traceIds().stream().limit(20).toList());
            // 去重：已存在同名 DRAFT（未发布/未丢弃）时，只刷新来源 trace 和 support，不重复插入
            SkillDraftEntity existing = draftMapper.selectOne(new LambdaQueryWrapper<SkillDraftEntity>()
                    .eq(SkillDraftEntity::getName, draft.name())
                    .in(SkillDraftEntity::getStatus, List.of("DRAFT", "APPROVED", "ROLLBACK_CANDIDATE"))
                    .last("limit 1"));
            if (existing != null) {
                existing.setDescription(draft.description());
                existing.setSourceTraceIds(sourceTraceIds);
                existing.setConfidenceScore((double) pattern.support());
                existing.setSpecJson(draft.specJson());
                existing.setUpdateTime(LocalDateTime.now());
                draftMapper.updateById(existing);
                return existing;
            }
            SkillDraftEntity entity = new SkillDraftEntity();
            entity.setName(draft.name());
            entity.setDescription(draft.description());
            entity.setStatus("DRAFT");
            entity.setSourceTraceIds(sourceTraceIds);
            entity.setSpecJson(draft.specJson());
            entity.setConfidenceScore((double) pattern.support());
            entity.setReviewNote("auto-generated");
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            draftMapper.insert(entity);
            return entity;
        }).toList();
    }

    public List<SkillDraftEntity> listDrafts() {
        return draftMapper.selectList(new LambdaQueryWrapper<SkillDraftEntity>().orderByDesc(SkillDraftEntity::getId));
    }

    /**
     * Phase 3.0 Trace → Skill 一键提取：
     * 给定 {@code traceId}，读取该 trace 的完整 tool 调用序列（可用 {@code toolNameFilter} 框选子集），
     * 复用 {@link SkillDraftLlmWriter} 生成草稿并写入 {@code skill_draft}。
     * <p>
     * 为了和自动挖掘路径保持同一个状态机（草稿 → 审批 → 发布），这里直接写一条 DRAFT，
     * confidenceScore 设为 1.0 表示"来自单条 trace 的精确重放"。
     *
     * @param traceId        必填
     * @param toolNameFilter 可空；若给了就只保留这里列出的 tool（保持原顺序），
     *                       场景：运营在 TraceTimeline 框选几个关键工具再点"抽取"。
     */
    public SkillDraftEntity extractDraftFromTrace(String traceId, List<String> toolNameFilter) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        List<ToolCallLogEntity> logs = toolCallLogMapper.selectList(
                new LambdaQueryWrapper<ToolCallLogEntity>()
                        .eq(ToolCallLogEntity::getTraceId, traceId)
                        .orderByAsc(ToolCallLogEntity::getCreateTime));
        if (logs.isEmpty()) {
            throw new IllegalArgumentException("trace 不存在或已清理: " + traceId);
        }
        List<String> sequence = new java.util.ArrayList<>();
        java.util.Set<String> filter = toolNameFilter == null ? null : new java.util.LinkedHashSet<>(toolNameFilter);
        for (ToolCallLogEntity log : logs) {
            String toolName = log.getToolName();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            if (filter != null && !filter.contains(toolName)) {
                continue;
            }
            sequence.add(toolName);
        }
        if (sequence.size() < 2) {
            throw new IllegalArgumentException("有效 tool 序列少于 2 步，无法生成 Skill 草稿");
        }

        PrefixSpanMiner.ChainPattern pattern = new PrefixSpanMiner.ChainPattern(sequence);
        pattern.addTraceId(traceId);
        SkillDraftLlmWriter.DraftContent draft = llmWriter.write(pattern);

        SkillDraftEntity existing = draftMapper.selectOne(new LambdaQueryWrapper<SkillDraftEntity>()
                .eq(SkillDraftEntity::getName, draft.name())
                .in(SkillDraftEntity::getStatus, List.of("DRAFT", "APPROVED", "ROLLBACK_CANDIDATE"))
                .last("limit 1"));
        if (existing != null) {
            existing.setDescription(draft.description() + "（trace: " + traceId + "）");
            existing.setSourceTraceIds(traceId);
            existing.setConfidenceScore(1.0);
            existing.setSpecJson(draft.specJson());
            existing.setUpdateTime(LocalDateTime.now());
            draftMapper.updateById(existing);
            return existing;
        }
        SkillDraftEntity entity = new SkillDraftEntity();
        entity.setName(draft.name());
        entity.setDescription(draft.description() + "（trace: " + traceId + "）");
        entity.setStatus("DRAFT");
        entity.setSourceTraceIds(traceId);
        entity.setSpecJson(draft.specJson());
        entity.setConfidenceScore(1.0);
        entity.setReviewNote("extracted-from-trace");
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        draftMapper.insert(entity);
        return entity;
    }

    public void markDraftStatus(Long id, String status, String reviewNote) {
        SkillDraftEntity entity = draftMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("草稿不存在: " + id);
        }
        entity.setStatus(status);
        entity.setReviewNote(reviewNote);
        entity.setUpdateTime(LocalDateTime.now());
        draftMapper.updateById(entity);
    }

    public void publishDraft(Long id) {
        SkillDraftEntity draft = draftMapper.selectById(id);
        if (draft == null) {
            throw new IllegalArgumentException("草稿不存在: " + id);
        }
        // 幂等：同名 Skill 已经存在时，不再重复创建，只把草稿状态刷成 PUBLISHED
        if (toolDefinitionService.findByName(draft.getName()).isPresent()) {
            markDraftStatus(id, "PUBLISHED", "already-published: skill exists");
            return;
        }
        ToolDefinitionUpsertRequest req = ToolDefinitionUpsertRequest.skill(
                draft.getName(),
                draft.getDescription(),
                List.of(),
                "manual",
                null,
                true,
                true,
                "WRITE",
                "SUB_AGENT",
                draft.getSpecJson()
        );
        toolDefinitionService.create(req);
        markDraftStatus(id, "PUBLISHED", "one-click publish");
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

    public record PrecheckResult(
            int days,
            int logCount,
            long traceCount,
            long multiStepTraceCount,
            boolean readyForMining,
            List<String> recommendedScenarios
    ) {}
}
