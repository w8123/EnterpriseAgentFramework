# 生产运行手册

这里存放需要由目标环境运维、平台管理员和数据 owner 共同执行的发布与演练步骤。文档中的命令默认是验证入口，不代表已在任何远程环境执行。

| 文档 | 用途 |
| --- | --- |
| [agent-skill-production-runbook.md](./agent-skill-production-runbook.md) | Agent Skill Center 的五表迁移、真实外部包、角色权限、AgentScope、缓存篡改与部署证据门禁 |
| [personal-memory-production-runbook.md](./personal-memory-production-runbook.md) | Agent 个人/会话/业务记忆的迁移、shadow、负载、擦除、备份恢复和生产准入 |
| [managed-executor-production-runbook.md](./managed-executor-production-runbook.md) | Managed Executor 的 Kubernetes 沙箱、凭据 Broker、网络、容量、留存、攻击测试、事故与灰度门禁 |
| [runtime-automation-runbook.md](./runtime-automation-runbook.md) | Automation 迁移、灰度、双实例、故障恢复、安全、观测与回滚门禁 |
