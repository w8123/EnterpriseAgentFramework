# ReachAI Managed Executor 威胁模型

状态：安全契约与源码防线已实现；生产放行前仍必须以部署态攻击测试逐项回填证据。

日期：2026-08-24

## 1. 保护资产

- 租户源码、文档和 Workspace；
- OpenAI/模型、Git、对象存储和 ReachAI 凭据；
- ReachAI Control/Runtime/Capability/Knowledge/Model 数据；
- Kubernetes 控制面、节点和其他 Pod；
- 任务 Artifact、审批决定、Trace 和审计记录；
- 预算、并发和基础设施容量；
- 用户和业务系统身份。

## 2. 信任假设

以下输入全部视为不可信：

- 用户目标和提示词；
- Git 仓库中的代码、构建脚本、依赖、AGENTS.md 和 Skill；
- Codex 模型输出、Tool 参数和最终答复；
- command stdout/stderr；
- Workspace 中生成的 Artifact；
- Worker 回传的产品状态声明。

Runtime、Control 和对象存储也不能仅凭 Worker 报告判定测试、发布或业务动作成功；必须使用 digest、exit code、服务端状态和领域 Provider 校验。

## 3. 信任区

1. 浏览器与外部调用方；
2. Control 公共控制面；
3. Runtime 执行控制面；
4. Sandbox launcher/Kubernetes API；
5. 单任务 Sandbox Job；
6. Worker 中的 Codex harness；
7. Codex 启动的命令和仓库代码；
8. Model Relay、对象存储、依赖代理和未来 Capability Gateway。

区 5 到区 7 均不获得数据库或服务间共享 Secret。

## 4. 必须控制的威胁

| 威胁 | 典型攻击 | 必须控制 |
| --- | --- | --- |
| 跨租户读取 | 猜测 execution/artifact ID、复用 Token、共享卷残留 | 不可猜测 ID、tenant 绑定、短期 audience Token、每任务卷、销毁和读取授权 |
| 路径逃逸 | `../`、绝对路径、symlink/junction、archive traversal | Workspace canonical root、拒绝逃逸链接、解包条目/大小上限、输出二次校验 |
| 宿主机/集群逃逸 | Docker socket、hostPath、特权容器、K8s Token、ptrace | Restricted Pod Security、无 host namespace、drop caps、seccomp、gVisor/Kata、无 SA Token |
| Secret 泄漏 | `env`、`/proc/*/environ`、日志、命令行、Artifact | 无长期 Secret、Model Relay/workload identity、子进程环境白名单、`/proc` 限制、日志净化 |
| 网络外传与 SSRF | metadata、localhost、内网、DNS rebinding、重定向 | deny-all egress、域名/IP 双校验、metadata/私网拒绝、重定向重验、代理审计 |
| 供应链执行 | Maven/npm install script、恶意编译器插件、依赖混淆 | 首期禁任意下载、批准代理、锁定镜像、SBOM、镜像签名、构建脚本视为不可信 |
| 配置注入 | Codex TOML quoted key、inline table、MCP/hook、伪 Model Relay | 首期字段白名单、单 provider、Relay host/port 精确绑定、ConfigMap 只读且 credential-free |
| 资源耗尽 | fork bomb、无限日志、磁盘填满、压缩炸弹、长时间运行 | PID/CPU/内存/磁盘/时间/事件/Artifact 上限、active deadline、强制终止 |
| Broker 放大 | 恶意 Workspace 通过本地 socket 并发轮询、上传或拖住 Runtime 连接 | 固定路由、4 个在途请求、600 次/分钟、8 个 socket 连接、120 秒请求/上游超时；Runtime 继续做 body/event/lease 边界，生产入口另需 execution/tenant 级限流 |
| 审批绕过 | 模型伪造“用户已同意”、acceptForSession、策略 amendment | Runtime Interaction 是唯一审批事实、一次性决定、参数 digest 绑定、默认 cancel |
| 状态伪造 | Worker 自报测试通过、重复或乱序 completion | sequence + event ID 幂等、Worker 采集 exit code/digest、Provider 服务端校验 |
| UI 注入 | ANSI 控制符、HTML/Markdown、超长路径、日志伪造 | 文本控制符清理、结构化渲染、长度/深度上限、默认纯文本 |
| 持久化逃逸 | 在缓存、PVC、Codex home 或镜像层留下数据 | 每任务 ephemeral home、加密快照、明确留存、TTL cleanup、残留扫描 |
| 预算滥用 | Worker/仓库直接调用模型 Relay、循环 turn | execution-scoped 配额、Relay 限速、Token/时间上限、取消和熔断 |

## 5. Sandbox 最低配置

生产 Worker Pod/VM 必须满足：

- non-root；
- `allowPrivilegeEscalation=false`；
- `readOnlyRootFilesystem=true`；
- capabilities 全部 drop；
- seccomp `RuntimeDefault` 或更严格；
- 禁止 hostPath、hostNetwork、hostPID、hostIPC；
- 禁止自动挂载 ServiceAccount Token；
- 每任务独立 Workspace 和 Codex home；
- CPU、memory、ephemeral storage、PID、wall time 和 Artifact 限额；
- deny-all ingress/egress，只开放 Runtime worker API、Model Relay、对象存储和批准依赖代理；
- 生产使用 gVisor、Kata 或等价微虚拟机隔离；普通 Docker 只能作为开发 PoC。

## 6. Codex app-server 最低配置

- stdio transport；
- ephemeral thread；
- `approvalPolicy=on-request`；
- `approvalsReviewer=user`；
- `sandbox=read-only|workspace-write`；
- turn 级显式 `sandboxPolicy` 且命令网络默认关闭；
- 不启用 experimental capabilities；
- 不调用 `process/*`；
- 不允许 `dangerFullAccess`；
- 不自动返回 `acceptForSession`；
- 不应用 exec/network policy amendment；
- raw reasoning 不进入事件、日志或 Artifact。

## 7. Secret 设计

### 禁止

- 在任务目标、Workspace、命令行、Kubernetes YAML、事件、Trace、Artifact 或 Git 中保存凭据；
- 把 `REACHAI_INTERNAL_SERVICE_SECRET` 下发给 Worker；
- 把长期 OpenAI API Key 作为 Worker 普通环境变量；
- 让 Git credential helper 在任务结束后保留凭据。

### 推荐

- Worker 使用 execution-scoped、短期、audience 限定身份；
- 原始 Worker token 只投影给同 Pod 的凭据 Broker sidecar；Worker/Codex 只拿只读 Unix socket，Broker 仅代理自己的 execution 的固定 Runtime 路由；
- Broker 在鉴权代理前执行固定并发与 60 秒计数窗口门禁，所有未知/畸形请求也消耗配额；该门禁只是纵深防御，不能替代 Runtime、Model Relay 和集群入口的 execution/tenant 级限流；
- 模型访问使用 Workload Identity 或任务级 Model Relay；
- Git checkout 在受信任 init container 中完成，凭据不进入执行容器；
- 首期 Artifact 经 execution-scoped Broker 上传到 Runtime；若后续改成预签名 URL，必须绑定单对象、短期、大小和 digest；
- Runtime 只记录 Secret 引用和 digest，不记录原文。

## 8. 审批约束

审批请求必须绑定：

- execution、thread、turn 和 item ID；
- 请求类型；
- 规范化 command/file/network 参数 digest；
- 创建时间、过期时间；
- tenant/user；
- Sandbox Profile 和当前 policy digest。

首版不提供共享审批角色或代审批委托：Runtime 必须同时校验签名调用用户、Execution 的 `requested_by_user_id` 与 Interaction 的 `user_id` 完全一致；同租户的其他用户也不能代为提交决定。未来若引入代审批，必须建立独立的 RBAC、委托范围、有效期和审计契约，不能放宽该默认规则。

用户批准不能：

- 扩大外层容器/VM 边界；
- 访问其他 Workspace；
- 解除网络、预算或运行时长上限；
- 直接获得生产写能力；
- 自动批准后续不同参数的操作。

## 9. 攻击测试清单

生产放行前至少自动化验证：

1. `../`、绝对路径、NTFS junction、Linux symlink 逃逸；
2. ZIP/TAR path traversal、重复路径、大小写冲突、Unicode 混淆和压缩炸弹；
3. 读取 `/proc/1/environ`、父进程环境、Secret mount、K8s Token；
   必须额外证明 Worker 看不到 `/run/reachai/broker-secret/token`，且跨 execution Broker 路由被拒绝；
4. 访问 Docker socket、container runtime socket、Kubernetes API；
5. 访问 `169.254.169.254`、localhost、RFC1918、IPv6 私网和 DNS rebinding；
6. HTTP redirect 从允许域跳到禁止域；
7. fork bomb、进程泄漏、CPU busy loop、内存和磁盘填满；
8. 无限 stdout/stderr、ANSI 控制符、伪造换行和超大 JSON；
9. 恶意 Maven/npm lifecycle script；
10. Codex ConfigMap 使用 quoted key、inline table、重复 key/table、MCP/hook、凭据字段或非批准 Relay origin；
11. Worker Token 跨 execution/tenant 重放；
12. Broker 第 5 个在途请求、单个 60 秒窗口第 601 个请求、慢请求和连接耗尽；
13. event sequence 重复、跳号、乱序、completion 重放；
14. approval decision 过期、参数 digest 不匹配和跨用户提交；
15. Runtime 重启、Worker 崩溃、网络分区和 cleanup 失败；
16. 终止后 Workspace、Codex home、Artifact 临时文件和进程残留；
17. 模型输出声称成功但测试实际失败时，Task Kernel 必须拒绝完成。

## 10. 生产放行证据

以下任何一项缺失都不能标记生产安全：

- 固定镜像 digest、SBOM、漏洞扫描和签名；
- 实际 RuntimeClass 与 Pod Security 配置；
- NetworkPolicy/egress proxy 的拒绝日志；
- Secret 泄漏测试；
- 资源炸弹强制终止证据；
- 跨租户读取拒绝证据；
- cleanup 与存储擦除审计；
- 真实浏览器审批/取消流程；
- 真实项目 Patch/Test/Artifact 领域验收；
- 回滚演练。

源码中的 [Kubernetes 前置清单](../../deploy/k8s/managed-executor-sandbox.yml) 与
[生产运行手册](../operations/managed-executor-production-runbook.md) 只是执行入口；未在目标集群运行时不得将上述证据标为通过。
