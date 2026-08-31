# Managed Executor 路由评测

`managed-executor-explicit-routing-v1.json` 可直接作为 `/api/runtime/evals/datasets` 的创建请求体，运行时通过 `/api/runtime/evals/runs` 指定 `agentId` 与待测 `configVersionId`。目标配置应设置 `managedExecutor.enabled=true`、`autoRouteEnabled=false`。

`managed-executor-auto-route-shadow-v1.json` 用于自动路由放量前的影子评测。目标 DRAFT/PUBLISHED 配置可设置 `autoRouteEnabled=true`，即使生产全局自动路由开关仍为 `false`，Eval 也只调用模拟 `start`：不会创建 `runtime_managed_execution`、Kubernetes Job、审批或 Artifact。

建议每个数据集至少重复 5 轮，并将以下条件作为灰度门禁：

- 正例 Managed Executor 路由召回率不低于 95%；
- 生产写、发布、删除、确定性业务任务和普通问答的误路由率为 0；
- 所有 Managed Executor 正例的 `managedExecutorCallCount` 恰好为 1；
- Eval 结果中不出现真实 `mex_` execution，只允许 `mex_eval_` 影子引用；
- 门禁通过后仍需人工开启 `REACHAI_MANAGED_EXECUTOR_AUTO_ROUTE_ENABLED=true`，配置版本自身的开关不能越过全局生产开关。
