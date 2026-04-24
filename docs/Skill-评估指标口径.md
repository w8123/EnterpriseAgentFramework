# Skill 评估指标口径（Phase 2.0.1 / 2.1）

## 窗口定义
- 统计窗口默认 `最近7天`，按天滚动。
- 评估对象为 `tool_definition.kind='SKILL'` 且 `enabled=true` 的能力。

## 四个核心指标

1. 命中率（HitRate）
- 含义：Skill 被执行的广度（在窗口内是否有稳定调用）。
- 口径（MVP）：窗口内有调用的天数 / 窗口天数（0..1）。
- 参考 SQL（等价于代码里的 `ToolCallLogService.computeCoverageMetrics`）：
```sql
SELECT COUNT(DISTINCT DATE(create_time)) / 7 AS hit_rate
FROM tool_call_log
WHERE create_time >= NOW() - INTERVAL 7 DAY
  AND tool_name = :skill_name;
```

2. 替代率（ReplacementRate）
- 含义：同类任务里，Skill 替代原子 Tool 链的比例。
- 口径（MVP）：`skillCalls / (skillCalls + 同意图下多步 Tool 链 trace 数)`。
  - "多步 Tool 链 trace" = 同一 `trace_id` 内 `distinct tool_name ≥ 2`，且该 trace 不包含本 Skill；
  - "同意图" = Skill 自身历史调用中出现过的 `intent_type` 集合；Skill 从未被调用过返回 null。

3. 成功率差（SuccessRateDiff）
- 含义：Skill 跑法相对基线（原子 Tool 链）是否更稳。
- 口径（MVP）：`skill_success_rate - 0.80`（0.80 为当前基线阈值，可配置）。
- SQL:
```sql
SELECT tool_name,
       AVG(CASE WHEN success=1 THEN 1 ELSE 0 END) AS success_rate
FROM tool_call_log
WHERE created_time >= NOW() - INTERVAL 7 DAY
  AND tool_name IN (SELECT name FROM tool_definition WHERE kind='SKILL')
GROUP BY tool_name;
```

4. Token 节省（TokenSavings）
- 含义：Skill 相比多步调用是否降本。
- 口径（MVP）：`2000 - p95(token_cost)`，正值为节省，负值为超支。

## 自动标记规则（Scheduler）
- `call_count < 20` -> `OBSERVE`
- `success_rate < 0.70` 或 `p95_token_cost > 4000` -> `ROLLBACK_CANDIDATE`
- 其它 -> `HEALTHY`

评估快照写入 `skill_eval_snapshot`，草稿状态同步到 `skill_draft.status`（若存在同名草稿）。
