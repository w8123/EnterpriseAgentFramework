# Managed Executor 生产运行手册

状态：源码、离线测试和 Kubernetes 前置清单已就绪；镜像构建、集群部署、网络拒绝、真实模型、浏览器和多租户攻击证据均为 `PENDING / NOT RUN`。

日期：2026-08-24

本手册是生产准入门禁，不是“复制后即可上线”的脚本。任何一项部署态证据缺失时，保持：

```text
REACHAI_MANAGED_EXECUTOR_ENABLED=false
REACHAI_MANAGED_EXECUTOR_AUTO_ROUTE_ENABLED=false
REACHAI_MANAGED_EXECUTOR_MAX_CLUSTER_CONCURRENCY=0
```

## 1. 已实现边界

- 每个 execution 创建独立 Kubernetes Job、Secret 和 NetworkPolicy；终态由 cleanup reconciler 删除。
- Pod 使用 gVisor/Kata allowlist、non-root、只读根文件系统、drop all capabilities、RuntimeDefault seccomp/AppArmor、无 host namespace、无 Kubernetes Token。
- Namespace 先安装静态 `default-deny-all`，Runtime 检查它存在后才允许创建 Job；每任务策略只开放固定 DNS proxy、Runtime、Model Relay、Git proxy 和依赖代理。
- Git 凭据仅挂载到一次性 workspace init container；Worker 看不到该卷。
- execution worker token 仅挂载到原生 sidecar 凭据 Broker。Worker/Codex 只能访问只读 Unix socket，不能读取原始 token。
- Broker 不是通用 HTTP 代理：只代理自己的 execution 的 claim、heartbeat、event batch、command polling、五类 Artifact 和 completion；它重写授权头、拒绝重定向、Transfer-Encoding、未知路由和超限正文，并固定限制 4 个在途请求、600 次/分钟、8 个 socket 连接和 120 秒请求/上游超时。
- Artifact 上传后由 Runtime 从对象存储重新读取并校验类型、大小、SHA-256、结构和安全内容；每次上传使用服务端唯一对象键，避免并发失败清理误删胜出对象；过期后先由 Runtime 拒绝读取，再由对象存储生命周期物理删除。
- `BROWSER_ACCEPTANCE`、`SKILL_SCRIPT` 和任意 MCP/Capability 网络访问当前未进入 Managed Executor profile allowlist，也未进入 NetworkPolicy；默认不可用。

这些是源码事实，不等于目标集群已经执行了相应控制。

## 2. 生产准入负责人

| 领域 | 必须签字的 owner | 证据 |
| --- | --- | --- |
| Runtime/Control 版本 | ReachAI 应用 owner | commit、构建、迁移、回归报告 |
| RuntimeClass/节点 | 集群平台 owner | RuntimeClass、节点标签/污点、逃逸测试 |
| NetworkPolicy/CNI | 网络安全 owner | CNI enforcement 与拒绝日志 |
| DNS/Model/Git/依赖代理 | 对应网关 owner | allowlist、身份、限流、审计和重定向测试 |
| S3/MinIO | 存储 owner | bucket policy、加密、生命周期、擦除记录 |
| 多租户与审批 | 安全 owner | 跨租户、同租户跨用户、token 重放、审批绑定攻击测试 |
| 业务验收 | 试点项目 owner | 真实 Patch/Test/Artifact 与领域验收 |

## 3. 集群硬前置

1. Kubernetes 至少 1.30。Job 使用原生 sidecar init container（`restartPolicy: Always`），Pod 使用结构化 AppArmor 字段。
2. CNI 必须真实执行 ingress/egress NetworkPolicy；仅“API 接受 YAML”不算通过。
3. 安装且验证一个 allowlisted RuntimeClass，例如 gVisor `gvisor` 或 Kata；普通 `runc` 不得放入 allowlist。
4. Sandbox 节点必须带 `reachai.ai/sandbox-runtime=<runtimeClass>` 标签和 `reachai.ai/sandbox=true:NoSchedule` 污点，并配置 kubelet/Runtime 的 Pod PID 上限。核心 PodSpec 没有可移植的 per-Pod PID 字段，不能遗漏节点侧 `podPidsLimit`。
5. 固定、受限制的 DNS proxy 使用静态 ClusterIP，只解析批准的服务 host，拒绝任意公网、RFC1918、loopback、link-local、metadata 和 DNS rebinding。
6. Runtime、Model Relay、Git proxy 和依赖代理必须各自使用稳定 namespace/app label 和 TCP 端口；NetworkPolicy 通过 label 与端口选择，不接受任意 CIDR。
7. Runtime worker API 和所有代理入口必须使用 HTTPS。证书信任链必须包含在 digest-pinned Worker image 中。

预检入口（本轮未执行）：

```bash
kubectl version
kubectl get runtimeclass
kubectl get nodes -L reachai.ai/sandbox-runtime
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.taints}{"\n"}{end}'
kubectl -n kube-system get pods
```

## 4. 镜像供应链

Worker 与 Git init 镜像必须：

- 使用 `@sha256:<64 hex>`，Runtime 会拒绝 tag-only image；
- Worker 固定 Node `20.12.2` 与 `@openai/codex 0.144.3`；
- 生成 SBOM、漏洞报告和签名；部署 admission policy 必须验证签名；
- 在目标 registry 做一次离线拉取和启动测试；
- 不包含 OpenAI、Git、S3、ReachAI 或代理凭据。

示例核验（替换 digest，不能提交凭据）：

```bash
docker build --build-arg CODEX_VERSION=0.144.3 -t registry.example/reachai/managed-executor-worker:0.1.0 managed-executor-worker
docker inspect --format '{{index .RepoDigests 0}}' registry.example/reachai/managed-executor-worker:0.1.0
syft registry.example/reachai/managed-executor-worker@sha256:<digest> -o spdx-json
cosign verify registry.example/reachai/managed-executor-worker@sha256:<digest>
```

`Image Built`、`SBOM Verified`、`Signature Verified` 必须分别记录。本仓库当前只有源码构建测试，没有上述目标 registry 证据。

## 5. 数据库升级

先备份并在同版本影子库演练：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260830-platform-consolidated.sql
```

该入口面向从 GitHub 基线 `ae9e1ce6` 的整版升级，Managed Executor 位于 Section 07；执行前还必须接受同一脚本中 A2A/MCP clean-slate 段的旧数据处置边界，不能只复制单段运行。

该脚本直接创建 Artifact 留存截止时间与索引。应用配置若不是默认 30 天，先由数据 owner 审批目标保留策略，并将对象存储生命周期设置为相同或更短期限。

只读核验：

```sql
SHOW CREATE TABLE runtime_managed_execution;
SHOW CREATE TABLE runtime_managed_execution_event;
SHOW CREATE TABLE runtime_managed_artifact;
SHOW INDEX FROM runtime_managed_artifact
  WHERE Key_name = 'idx_runtime_managed_artifact_retention';
SHOW CREATE TABLE runtime_managed_execution_outbox;
SHOW CREATE TABLE control_managed_execution_inbox;
```

本轮未连接或修改任何数据库。

## 6. 安装 Sandbox 控制面前置资源

清单：[deploy/k8s/managed-executor-sandbox.yml](../../deploy/k8s/managed-executor-sandbox.yml)。

先修改 Runtime ServiceAccount 所在 namespace。当前样例 subject 是 `default/reachai-runtime-managed-provisioner`，不能在实际 Runtime 位于其他 namespace 时照抄。

```bash
kubectl apply --server-side --dry-run=server -f deploy/k8s/managed-executor-sandbox.yml
kubectl apply -f deploy/k8s/managed-executor-sandbox.yml
kubectl -n reachai-managed-executor get networkpolicy default-deny-all -o yaml
kubectl -n reachai-managed-executor get resourcequota,limitrange
```

Runtime Deployment 启用 Kubernetes backend 时才显式设置：

```yaml
spec:
  template:
    spec:
      serviceAccountName: reachai-runtime-managed-provisioner
```

最小 RBAC 核验：

```bash
kubectl auth can-i create jobs.batch \
  --as=system:serviceaccount:default:reachai-runtime-managed-provisioner \
  -n reachai-managed-executor
kubectl auth can-i get runtimeclasses.node.k8s.io \
  --as=system:serviceaccount:default:reachai-runtime-managed-provisioner
kubectl auth can-i list pods \
  --as=system:serviceaccount:default:reachai-runtime-managed-provisioner \
  -n reachai-managed-executor
kubectl auth can-i create pods \
  --as=system:serviceaccount:reachai-managed-executor:reachai-managed-worker \
  -n reachai-managed-executor
```

预期：前三项依次为 `yes`、`yes`、`no`，Worker 创建 Pod 为 `no`。Runtime 只通过 Job API 间接创建 Pod。

## 7. 环境拥有的信任锚

仓库不会生成以下资源，因为伪造一个“看似可用”的代理会绕过安全边界：

- `RuntimeClass` 及节点 runtime 安装；
- allowlist DNS proxy；
- execution-aware Model Relay；
- 只读 Git egress proxy；
- Maven/npm 等批准依赖代理；
- 项目 Git credential Secret；
- credential-free Codex ConfigMap。

Codex ConfigMap 只允许 `model`、`model_provider`，以及一个 `model_providers.<name>` 表中的 `name`、`base_url`、`wire_api="responses"`、`requires_openai_auth=false`。Worker 使用严格白名单而非 TOML 黑名单：quoted key、inline table、重复 key/table、credential-like key、MCP server、notify hook、profile/features、HTTP header、sandbox/approval 覆盖和未知字段全部拒绝；`base_url` 的 HTTPS host/port 必须与 Runtime operator policy 精确一致。模型身份必须由 Relay/网络工作负载身份在服务端建立，而不是写入 Worker 环境或 `config.toml`。

Git workspace source 必须是 40 位 commit SHA；仓库 URL host 必须与配置的 Git proxy host 完全相同。验收命令必须来自 operator-owned JSON，不接受用户或模型传入 shell 字符串。

## 8. Runtime 必填配置

保持全局开关关闭时先配置并启动 Runtime。以下每一项都必须有实际值：

```text
REACHAI_MANAGED_EXECUTOR_SANDBOX_BACKEND=kubernetes
REACHAI_MANAGED_EXECUTOR_ARTIFACT_STORE_TYPE=s3
REACHAI_MANAGED_EXECUTOR_ALLOWED_PROJECTS=<explicit project codes>
REACHAI_MANAGED_EXECUTOR_ALLOWED_PROFILES=ANALYZE_READONLY
REACHAI_MANAGED_EXECUTOR_WORKSPACE_SOURCES_JSON=<operator-owned JSON>
REACHAI_MANAGED_EXECUTOR_ACCEPTANCE_PROFILES_JSON=<operator-owned JSON>

REACHAI_MANAGED_EXECUTOR_K8S_NAMESPACE=reachai-managed-executor
REACHAI_MANAGED_EXECUTOR_WORKER_IMAGE=<image@sha256:digest>
REACHAI_MANAGED_EXECUTOR_GIT_INIT_IMAGE=<image@sha256:digest>
REACHAI_MANAGED_EXECUTOR_CODEX_CONFIG_MAP=<credential-free config map>
REACHAI_MANAGED_EXECUTOR_RUNTIME_CLASS=<gvisor-or-kata>
REACHAI_MANAGED_EXECUTOR_ALLOWED_RUNTIME_CLASSES=<explicit allowlist>
REACHAI_MANAGED_EXECUTOR_WORKER_SERVICE_ACCOUNT=reachai-managed-worker
REACHAI_MANAGED_EXECUTOR_NETWORK_POLICY_ENABLED=true
REACHAI_MANAGED_EXECUTOR_STATIC_DEFAULT_DENY_POLICY=default-deny-all
REACHAI_MANAGED_EXECUTOR_DNS_PROXY_IP=<fixed IPv4>

REACHAI_MANAGED_EXECUTOR_RUNTIME_BASE_URL=https://<runtime-service-host>:18604
REACHAI_MANAGED_EXECUTOR_RUNTIME_SERVICE_HOST=<same host>
REACHAI_MANAGED_EXECUTOR_RUNTIME_EGRESS_NAMESPACE=<namespace>
REACHAI_MANAGED_EXECUTOR_RUNTIME_EGRESS_APP=<app label>
REACHAI_MANAGED_EXECUTOR_RUNTIME_EGRESS_PORT=18604

REACHAI_MANAGED_EXECUTOR_MODEL_RELAY_SERVICE_HOST=<host>
REACHAI_MANAGED_EXECUTOR_MODEL_RELAY_EGRESS_NAMESPACE=<namespace>
REACHAI_MANAGED_EXECUTOR_MODEL_RELAY_EGRESS_APP=<app label>
REACHAI_MANAGED_EXECUTOR_MODEL_RELAY_EGRESS_PORT=443

REACHAI_MANAGED_EXECUTOR_GIT_PROXY_SERVICE_HOST=<host>
REACHAI_MANAGED_EXECUTOR_GIT_PROXY_EGRESS_NAMESPACE=<namespace>
REACHAI_MANAGED_EXECUTOR_GIT_PROXY_EGRESS_APP=<app label>
REACHAI_MANAGED_EXECUTOR_GIT_PROXY_EGRESS_PORT=443

REACHAI_MANAGED_EXECUTOR_DEPENDENCY_PROXY_SERVICE_HOST=<host>
REACHAI_MANAGED_EXECUTOR_DEPENDENCY_PROXY_EGRESS_NAMESPACE=<namespace>
REACHAI_MANAGED_EXECUTOR_DEPENDENCY_PROXY_EGRESS_APP=<app label>
REACHAI_MANAGED_EXECUTOR_DEPENDENCY_PROXY_EGRESS_PORT=443
```

Runtime 启动时会 fail closed 校验 digest、仅 origin 形式的 HTTPS Runtime URL、URL host/有效端口与 Runtime NetworkPolicy target 精确一致、RuntimeClass allowlist、DNS IPv4、所有 egress label/port、Worker ServiceAccount 和静态默认拒绝策略。若目标环境在 443 做 TLS 终止，则 URL 和 `RUNTIME_EGRESS_PORT` 必须同时改为 443；缺失或不一致不会创建 Job。

S3/MinIO access key 只存在 Runtime Secret，不进入 Sandbox。生产禁止 `auto-create-bucket=true`，bucket、加密、版本、policy 与 lifecycle 由存储 owner 预建。

## 9. 分阶段放量

### 阶段 A：控制面暗装

- `enabled=false`、`auto-route=false`、`max-cluster-concurrency=0`；
- 启动 Control/Runtime；
- 验证迁移、健康、路由、RBAC 和前置资源；
- 确认没有 Job 被创建。

### 阶段 B：单项目只读 canary

- allowlist 仅一个非生产项目；
- profiles 仅 `ANALYZE_READONLY`；
- cluster concurrency 为 `1`；
- `enabled=true`，`auto-route=false`；
- 只允许 UI 显式启动，验证取消、超时、cleanup 和 Artifact 到期时间。

### 阶段 C：Workspace Patch

- 先通过全部只读攻击测试；
- 单项目加入 `WORKSPACE_PATCH`；
- Git 仍只读、Worker 不持有 Git credential，不 commit/push/PR/deploy；
- 真实执行至少 20 个任务，比较外部 Codex、Managed Executor 与 AgentScope 的正确率、时延、成本和人工接管率。

### 阶段 D：AgentScope shadow 与灰度

- Agent 配置版本显式启用 managedExecutor；
- 全局 auto-route 仍关闭，先跑 Eval shadow；shadow 只能生成模拟任务卡，不创建 DB execution 或 Job；
- 通过生产写误路由为 0、简单问题误路由率、复杂任务完成率和成本门禁后，再对单 Agent/项目打开全局 auto-route。

## 10. 网络和 Secret 攻击验收

以下命令只在隔离测试集群的 canary Job 中执行并留存拒绝证据，不要在生产业务 Pod 临时试探：

```bash
test ! -e /var/run/secrets/kubernetes.io/serviceaccount/token
test ! -e /run/reachai/broker-secret/token
test ! -e /run/reachai/git-home/.netrc
test -S /run/reachai/broker/runtime.sock

curl --connect-timeout 2 http://169.254.169.254/latest/meta-data/
curl --connect-timeout 2 http://127.0.0.1:18604/
curl --connect-timeout 2 https://example.com/
```

预期：三个敏感文件均不可见，只有 Broker socket 存在，metadata/loopback/公网均失败。还必须验证：

- Worker 通过 socket 只能访问自己的 execution；跨 execution path 返回拒绝；
- Worker 自带伪 Authorization header 时 Broker 仍覆盖为 execution token；
- Runtime redirect、chunked request、未知 Artifact type、超限 body 被 Broker 拒绝；
- 同一 Artifact 的并发同内容上传只保留胜出对象，失败方清理不能删除胜出对象；不同内容必须返回冲突；
- Codex ConfigMap 的 quoted key、inline table、未知字段和错误 Model Relay origin 在执行任何仓库代码前被 Worker 拒绝；
- Broker 第 5 个并发在途请求返回 `MANAGED_BROKER_CONCURRENCY_EXCEEDED`，单个 60 秒计数窗口的第 601 个请求返回 `MANAGED_BROKER_RATE_EXCEEDED`，慢请求不超过 120 秒占用窗口；
- Git init 能访问 Git proxy，但 Worker 无 Git credential；
- Model Relay 拒绝越过任务预算、其他 tenant 和未经允许的模型；
- DNS proxy 对未批准域、CNAME 跳转、rebinding、IPv6 私网和 trailing-dot 变体均拒绝；
- 删除单任务 allow NetworkPolicy 后，Pod 因 namespace `default-deny-all` 立即失去所有网络。

将 CNI flow log、代理审计、Runtime event、execution ID 和镜像 digest 放入同一验收记录。仅 `curl failed` 不足以证明失败原因是目标 NetworkPolicy。

## 11. 资源、容量与成本

默认单任务 steady-state request 约为 Worker `500m/1Gi` 加 Broker `25m/64Mi`；Git init 也有 Worker 级 limit，并在主容器前运行。调度容量必须按 Kubernetes 对 init/sidecar 的实际 effective request 计算，不能只乘 Worker request。

同时存在三层门禁：

1. Runtime `max-cluster-concurrency`；
2. namespace ResourceQuota（样例最多 20 个 Job）；
3. 节点池 capacity/Pod PID/ephemeral storage。

建议首期：每用户 1、每项目 2、全局 2。当前源码硬门禁只有全局 `max-cluster-concurrency`；每用户/每项目配额和 Model Relay Token/费用硬预算未实现，不得仅靠监控或文档宣称生效。排队 p95 超过 2 分钟或节点余量低于 30% 时停止放量。需要采集：

- queue、provisioning、running、waiting approval 和 cleanup 各状态数量/时长；
- lease expiry、provision retry、cleanup failure、Broker restart；
- Broker 并发/速率 429、上游超时和 socket 连接耗尽；
- CPU、memory、PID、ephemeral storage 和网络拒绝；
- model tokens/费用、Job 分钟、Artifact 字节与 S3 请求；
- 成功率、验证失败率、取消率、人工审批率和 AgentScope 误路由率。

成本按任务记录：

```text
任务成本 = 模型输入/输出成本
         + Sandbox 节点 CPU/内存/时长
         + Artifact 存储与请求
         + Git/依赖/Model Relay 流量
```

预算超限必须取消 execution，而不是静默降级到无沙箱本地执行。

## 12. 留存与擦除

- Sandbox Job、Secret、每任务 NetworkPolicy 和所有 emptyDir 由 Runtime cleanup；Job 另有 `ttlSecondsAfterFinished` 兜底。
- Runtime Artifact 元数据保存 `retention_expires_at`。到期或字段缺失时 list/read fail closed；下载返回 `MANAGED_ARTIFACT_EXPIRED`。
- 生产 bucket lifecycle 必须在相同或更短期限物理删除 `managed-executions/` 对象；开启服务端加密，禁止公共访问和跨 bucket copy。
- `local` Artifact store 仅供单机开发，没有生产物理生命周期保证。
- 数据 owner 必须定义 execution/event/outbox 元数据的长期审计期限；当前实现不会自动删除这些审计记录，也不会将 objective/event 原文转存到 Artifact。
- cleanup `FAILED`、到期对象仍存在、对象提前丢失、digest 不匹配都要告警并形成擦除工单。

## 13. 监控与告警

最低告警：

- `PROVISIONING` 超过 2 分钟；
- heartbeat/lease 连续过期；
- `cleanup_status=FAILED` 或 RUNNING lease 超时；
- Broker restart count > 0；
- Namespace ResourceQuota > 80%；
- NetworkPolicy 拒绝到 metadata、API server、数据库、Control 或未知公网；
- Runtime Artifact integrity/scan failure；
- Control outbox/inbox 投递持续失败；
- AgentScope 自动路由生产变更意图；
- Model Relay 单 execution 预算异常。

只读排障查询：

```sql
SELECT status, cleanup_status, COUNT(*)
FROM runtime_managed_execution
GROUP BY status, cleanup_status;

SELECT execution_id, status, lease_expires_at, last_heartbeat_at,
       cleanup_status, cleanup_attempt_count, cleanup_error
FROM runtime_managed_execution
WHERE status NOT IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED')
   OR cleanup_status = 'FAILED'
ORDER BY updated_at ASC;
```

不要在工单或聊天中复制 objective、token、raw stdout/stderr、Artifact body 或 Secret。

## 14. 事故处置

### 疑似外传或逃逸

1. 将 `enabled=false`、`auto-route=false`、`max-cluster-concurrency=0`，滚动 Runtime 使配置生效；这只停止新任务。
2. 通过受治理取消 API 取消活跃 execution。
3. 删除可疑 execution 的 allow NetworkPolicy；保留 namespace `default-deny-all`，不要先删除默认拒绝策略。
4. 隔离 Sandbox 节点池，保留节点/CNI/Relay/Kubernetes audit 证据。
5. 轮换 Git proxy credential、Model Relay 工作负载身份和对象存储 Runtime credential。Worker token 只存 digest 且会过期，但仍要确认没有其他信任锚泄漏。
6. 核对跨 tenant Artifact 读取、Runtime token 重放和 Control 投递记录。
7. 在根因、补丁、攻击复测和擦除审计完成前保持功能关闭。

### Cleanup 堆积

1. 不直接删除 DB 记录；它们是 reconciler 的恢复事实。
2. 核对 Runtime ServiceAccount 的 delete 权限、API 可用性和 sandbox ref 一致性。
3. 先删除 Job，再删除单任务 NetworkPolicy，最后删除 execution Secret；源码 cleanup 使用相同顺序。
4. 回读 namespace，确认没有 Workspace/Codex home emptyDir、sidecar 或孤儿 Pod。
5. 标记工单与 execution ID，不将 Secret 内容写入记录。

## 15. 回滚

- 关闭新建和自动路由；外部 AI Coding、GraphSpec 和 AgentScope 其他工具继续工作。
- 允许安全任务完成，或逐个取消；不要删表、清空 outbox 或盲目删除所有 Artifact。
- 保持静态默认拒绝、Worker SA 和审计记录，直到所有 Job/Secret/策略清理完成。
- 应用回滚前确认数据库 schema 是 additive；无需回滚表结构。
- 回滚后执行一次孤儿资源和对象存储前缀审计。

## 16. 扩展 Profile 的独立门禁

### Browser Acceptance

当前 Managed Executor 只接受 `ANALYZE_READONLY` 和 `WORKSPACE_PATCH`。启用浏览器前必须新增独立 browser image、固定 preview origin、一次性业务测试身份、浏览器上下文销毁、截图/Network 证据 schema，以及不同于依赖代理的网络策略。现有 Page Workbench 的 `BROWSER_ACCEPTANCE` 任务类型不能当作该沙箱已实现。

### MCP/Capability Gateway

当前 per-execution NetworkPolicy 没有 MCP/Capability destination，Codex ConfigMap 也禁止 direct MCP server。未来只能新增 execution-aware Gateway：Runtime 依据 tenant/project/user、Agent 配置版本、Capability ACL、参数 digest 和预算签发一次性调用；Broker 只能代理固定 Gateway 路由。不能把内部 HMAC Secret 或普通 Capability 服务 URL交给 Worker。

### Skill Script

当前 Managed Executor 拒绝 `SKILL_SCRIPT` profile。未来项目必须绑定 Skill package digest、发布评审版本、脚本/依赖 allowlist、独立镜像与网络、输入输出 schema、病毒/归档攻击测试和更低预算。不能因 Agent 配置里出现 `scriptPolicy=SANDBOX_REVIEWED` 就自动获得 `WORKSPACE_PATCH` 权限。

## 17. 证据记录模板

每次准入记录至少包含：

```text
ReachAI commit:
Control/Runtime image digest:
Worker/Git image digest:
Codex version/schema digest:
Kubernetes/CNI/RuntimeClass version:
Namespace/ServiceAccount/NetworkPolicy fingerprint:
SQL migration checksum:
试点 tenant/project/execution IDs:
功能/取消/审批/Artifact E2E:
网络/Secret/资源/跨租户攻击报告:
cleanup/擦除报告:
成本与容量结果:
回滚演练:
未完成项:
批准人和时间:
```

只有全部生产门禁有目标环境证据后，状态才能从 `PENDING / NOT RUN` 改为 `VERIFIED`。
