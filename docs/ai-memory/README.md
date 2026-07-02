# AI Memory

本目录存放给 Codex、Cursor、Claude Code、Copilot CLI 等 AI 编程工具看的项目记忆。它不是对外产品文档，也不是 `README.md` 的替代入口；目标是让后续工具在跨会话、跨窗口协作时快速继承当前事实。

## 阅读顺序

1. 根目录 `AGENTS.md`：最高优先级项目规则。
2. `.cursor/rules/reachai-project.mdc`：Cursor 自动加载规则。
3. 本目录文件：
   - `PROJECT-MEMORY.md`：产品定位、模块地图和当前事实。
   - `WORKING-RULES.md`：开发、SQL、验证和协作规则。
   - `DECISIONS.md`：已形成的架构和命名决策。
   - `KNOWN-PITFALLS.md`：历史问题、错误签名和诊断顺序。
   - `VERIFICATION.md`：常用验证命令。
   - `AI-TOOLS.md`：Playwright 浏览器调试和 DBHub MySQL 只读查询约定。

## 当前后端拓扑

当前主路径是五个后端部署单元：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。公共 `/api/**`、`/embed/**` 和 SDK 注册入口由 `reachai-control-service` 收口；前端不直接调用 Runtime 或 Capability 内部端口。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。

## 维护规则

- 当前代码、SQL、接口和启动配置永远优先于记忆文件。
- 架构、SQL、运行时、命名或重要工作流变化时，同步更新这里。
- 这里只记录会影响未来修改判断的事实和规则，不记录一次性讨论稿、密钥、真实生产凭据或个人机器路径。
- 如果内容与当前代码冲突，立即修正记忆；不要让后续 AI 继续沿用过期认知。
