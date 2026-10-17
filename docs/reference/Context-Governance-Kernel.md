# Context Governance Kernel（已退役）

> 状态：RETIRED（2026-10-17）

上下文治理内核已完整退役，本页只作历史记录。

- 2026-10-17（PR #4）：管理端「上下文治理」页面和 PROJECT_DEV 项目上下文接口下线，原因是 Runtime 从未消费这些数据。
- 2026-10-17（记忆模块重设计 M2）：剩余只服务个人记忆的 `control_context_*` 表（namespace、item、binding、evidence、audit_event、memory_candidate、memory_outbox）删除，个人记忆改用 Control 自有的 `control_personal_memory*` 表；`/api/context/**` 路由全部移除。

当前替代：

| 原能力 | 现在的位置 |
| --- | --- |
| 个人记忆自助治理、候选确认 | `/api/me/memories/**`，管理端「个人 / 我的记忆」 |
| 跨域记忆擦除 | `/api/compliance/erasure-requests/**`，管理端「合规 / 跨域数据擦除」 |
| 运行时用户映射 | `/api/runtime-user-mappings/**`，管理端「身份与权限 / 运行时用户映射」，表 `control_runtime_user_mapping` |

设计依据见 [记忆模块重设计方案](../plans/记忆模块重设计方案.md)，个人记忆现状见 [personal-agent-memory.md](../architecture/personal-agent-memory.md)。
