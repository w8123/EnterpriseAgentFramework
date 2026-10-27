# Managed Executor Worker

Worker 在受限 Workspace 中驱动 Codex app-server，向 Runtime 报告状态、事件与 Artifact；Runtime 拥有租约、审批、取消和执行事实，Control 拥有产品任务与权限。

本模块的启动、接口和依赖以本目录代码与部署配置为准。Worker 不持有平台主密钥、数据库凭据或集群管理权限，不接受不受范围约束的文件、网络或命令能力。

控制面与任务关系见 [AI Coding](../docs/ai-coding.md)，隔离要求见[安全](../docs/security.md)，镜像、集群、容量、攻击与恢复验收见[运维](../docs/operations.md)。
