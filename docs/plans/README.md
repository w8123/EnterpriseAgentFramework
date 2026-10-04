# 计划与验收

本目录保存仍在实施或仍缺部署态验收的阶段性计划。它们不是当前架构事实源；计划与源码冲突时，以当前代码、SQL、接口、测试和对应契约文档为准。

| 文档 | 当前用途 |
| --- | --- |
| [业务方法与API重构实施基线.md](./业务方法与API重构实施基线.md) | 用户已确认的产品方向、两类用户任务、方法 / API 边界、改造批次与证据标准；实际完成情况见专项进度 |
| [业务方法与API重构实施进度.md](./业务方法与API重构实施进度.md) | 本专项唯一当前批次、设计问题、验证结果和恢复下一步；不自动恢复旧治理批次 |
| [业务方法与API重构最终验收清单.md](./业务方法与API重构最终验收清单.md) | 对齐原实施基线的最终用户任务、旧入口、环境与证据核对项；不缩减范围或按条目计算百分比 |
| [业务方法与API重构设计决策.md](./业务方法与API重构设计决策.md) | 方法 / API 契约、明确类型、字段归属、试调用和数据演进的选定方案与分期 |
| [BMAPI-2A-资产类型贯通规格.md](./BMAPI-2A-资产类型贯通规格.md) | SDK、Starter、Capability 与 SQL 类型贯通的实现范围、反例测试和交付要求 |
| [BMAPI-2B-业务方法目录与详情规格.md](./BMAPI-2B-业务方法目录与详情规格.md) | 独立业务方法读取接口、目录与详情，限定类型和项目授权，复用输入输出与引用展示 |
| [BMAPI-2C-业务方法试调用规格.md](./BMAPI-2C-业务方法试调用规格.md) | 项目凭据身份、调用权限、已接纳目标、去重与未知结果、Run/Trace 及分步试调用交付 |
| [BMAPI-2C-B-业务方法试调用页面规格.md](./BMAPI-2C-B-业务方法试调用页面规格.md) | 方法详情内的输入、影响确认、一次提交、未知结果查询、账号/项目隔离及浏览器验收 |
| [BMAPI-2D-业务方法Workflow接入规格.md](./BMAPI-2D-业务方法Workflow接入规格.md) | Workflow Studio 的业务方法选择、输入/输出映射、保存回读，以及后续发布、受控执行、Trace 与引用验收 |
| [BMAPI-3-API接入与使用规格.md](./BMAPI-3-API接入与使用规格.md) | HTTP API 一等资产、操作身份、规范化契约、多来源关系，以及目录、验证、Workflow 和 API 市场受控绑定的分批边界 |
| [architecture-cleanup-closeout-20260912.md](./architecture-cleanup-closeout-20260912.md) | 本轮架构整理交付、必要回归结果，以及按用户要求转入后续的扩展验收清单 |
| [knowledge-index-execution-reclamation.md](./knowledge-index-execution-reclamation.md) | 索引执行、文件/集合及原件/解析工件生命周期已接入；检索与重解析已校验不可变记录身份；解析临时文件中断与同版本真实 MinIO 回收已验证；开发库历史登记为空；继续部署态与完整服务验收 |
| [architecture-cleanup-audit-20260905.md](./architecture-cleanup-audit-20260905.md) | 系统整理首轮体检、行为复现、架构债务、实施批次与验证边界 |
| [architecture-cleanup-acceptance-20260910.md](./architecture-cleanup-acceptance-20260910.md) | 原始 R1–R8 要求的当前实现、验证证据及剩余部署验收对照 |
| [platform-auth-remediation-implementation-plan.md](./platform-auth-remediation-implementation-plan.md) | 管理端认证、会话、RBAC 与 bootstrap 的剩余数据库/部署/浏览器验收 |
| [unified-conversation-surfaces-implementation-plan.md](./unified-conversation-surfaces-implementation-plan.md) | Agent、Workflow Studio 与 Embed 共享对话内核的剩余真实流式与浏览器验收 |
| [前端Glass-Workbench设计系统与UI重构.md](./前端Glass-Workbench设计系统与UI重构.md) | Glass Workbench 设计 Token、共享组件和分阶段 UI 迁移记录 |
| [managed-executor-implementation-plan.md](./managed-executor-implementation-plan.md) | Managed Executor P0-P5 源码交付状态及仍待镜像、集群、浏览器和攻击验收的门禁 |
| [reachai-agent-operations-dashboard-handoff.md](./reachai-agent-operations-dashboard-handoff.md) | 智能体运营大屏的数据口径、服务边界、Widget/布局体系、接口、SQL、实施阶段与验收交接 |
| [mcp-hub-implementation-plan.md](./mcp-hub-implementation-plan.md) | MCP Hub 分阶段实现、升级和部署/外部客户端验收门禁 |

## 维护规则

- 文件开头必须写明状态、更新时间和尚缺证据。
- 已完成的步骤不要继续作为“当前架构”重复描述；长期结论应回写 `docs/architecture/`、`docs/reference/` 或服务 README。
- 达到完成定义后，优先提炼仍有效的契约并删除计划正文；Git 历史负责保存执行过程。
- 不把构建通过改写成部署、浏览器或生产验收通过。
