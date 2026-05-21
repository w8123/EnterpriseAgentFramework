# Agent Studio 智能体评测闭环 MVP

## 目标

在 Agent Studio 内直接评测当前画布草稿：导入一批用例，设置重复次数，系统自动沙箱执行并输出准确率、运行成功率、响应时间、结果偏差和修复建议。评测结果只给建议，不自动修改画布，发布仍由用户手动决定。

## 数据集导入

前端支持 `.xlsx`、`.xls`、`.csv`。第一行必须是列名：

| 列名 | 必填 | 说明 |
| --- | --- | --- |
| `caseNo` | 否 | 用例编号，空值时自动生成 `case-1`、`case-2` |
| `message` | 是 | 本轮评测输入给智能体的用户消息 |
| `inputParams` | 否 | JSON 对象，作为工作流输入参数 |
| `expected` | 否 | JSON 对象，定义硬断言 |
| `judgeConfig` | 否 | JSON 对象，预留 LLM 语义评分配置 |
| `tags` | 否 | 标签字符串，例如 `smoke,refund` |

```csv
caseNo,message,inputParams,expected,judgeConfig,tags
refund-001,查询订单1001是否可以退款,"{""question"":""查询订单1001是否可以退款""}","{""contains"":[""订单1001""],""jsonPath"":{""finalState.orderId"":""1001""}}","{""semanticEnabled"":false}",smoke
```

## 断言规则

`expected` 支持：

```json
{
  "contains": ["订单1001"],
  "regex": ["订单\\d+"],
  "jsonPath": {
    "finalState.orderId": "1001",
    "answer": "订单1001"
  },
  "minSemanticScore": 0.75
}
```

`contains`、`regex`、`jsonPath` 是硬断言，任一失败即结果偏差。没有配置硬断言时，非空答案按弱通过处理，得分为 `0.6`。

## 指标口径

| 指标 | 口径 |
| --- | --- |
| 准确率 | `assertionPassed / totalExecutions` |
| 运行成功率 | `runtimeSuccess / totalExecutions` |
| P50/P95 响应时间 | 仅统计运行成功的执行耗时 |
| 结果偏差 | `totalExecutions - passedExecutions`，包含断言失败和运行失败 |
| 平均得分 | 所有执行的 `score` 平均值 |

重复次数表示整套数据集重复执行 N 轮。例如 2 条用例、重复 3 次，会产生 6 条结果。

## 沙箱行为

评测默认把 `AgentDefinition.allowIrreversible` 强制改为 `false`，并向运行时传入：

```json
{
  "evalMode": true,
  "sandboxSideEffects": true
}
```

MVP 不提供“允许真实副作用”的开关。涉及不可逆操作的工具应被运行时护栏拦截，避免评测污染业务系统。

## 修复建议

系统会先按失败节点聚类：

- 运行失败时优先定位失败 step 的 `nodeId`。
- 断言失败但运行成功时，优先归因到最后一个执行节点；没有节点时归为 `final_answer`。
- 建议只描述问题和可改方向，不自动改画布。

## API 摘要

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/agent/evals/datasets?agentId=` | 数据集列表 |
| `POST` | `/api/agent/evals/datasets` | 创建数据集并导入用例 |
| `POST` | `/api/agent/evals/datasets/{datasetId}/cases/import` | 向已有数据集追加用例 |
| `GET` | `/api/agent/evals/datasets/{datasetId}/cases` | 用例列表 |
| `POST` | `/api/agent/evals/runs` | 启动评测 |
| `GET` | `/api/agent/evals/runs/{runId}` | 评测运行详情 |
| `GET` | `/api/agent/evals/runs/{runId}/results` | 评测结果明细 |
