# ReachAI Managed Executor Worker

这是 ReachAI 托管开放式执行的隔离 Worker。它通过 Codex app-server 的 stdio JSON-RPC 协议执行单个 ephemeral turn，并独立生成 patch、测试报告和摘要证据。

当前状态：P1 Worker 与 P2 Runtime 协议源码已接通，包括 claim、heartbeat、事件、命令、Artifact 上传和完成；Kubernetes Job/NetworkPolicy、凭据 Broker 与清理协调器已有离线测试。尚未构建并部署镜像、在目标 CNI 验证策略，也未完成真实模型、浏览器或多租户攻击 E2E，因此不能作为生产多租户执行器放行。

## 已实现边界

- Codex app-server `initialize -> thread/start -> turn/start -> turn/completed` 生命周期；
- 显式 `ANALYZE_READONLY` / `WORKSPACE_PATCH` sandbox policy，命令网络关闭；
- 本地 CLI 的 command/file approval 默认 fail closed；远程 Worker 只接受 Runtime 与当前 app-server request ID 匹配的一次性 `accept|decline`，取消和异常均回落为 `cancel`；
- 丢弃 reasoning 与 stdout/stderr delta，事件持久化前做大小限制、Secret 和路径脱敏；
- 任务配置严格拒绝未知字段，目标通过文件传入，不进入命令行；
- 通过备用 Git index 收集 tracked/untracked 最终 patch，不暂存真实 workspace index；
- 验收命令使用结构化 `argv`、`shell=false`、固定超时和输出上限；
- 生成 `events.ndjson`、`workspace.patch`、`test-report.json`、`execution-summary.json` 和 SHA-256 `evidence-manifest.json`；
- 只读 Profile 最终 diff 非空时判定失败。
- 生产远程模式通过 Unix socket 调用同 Pod 凭据 Broker；原始 execution token 不进入 Worker/Codex 挂载、环境、日志或命令行；Broker 只代理当前 execution 的固定 Runtime 路由，并固定限制为最多 4 个在途请求、每分钟 600 个请求、8 个 socket 连接和 120 秒请求/上游超时。
- Codex ConfigMap 启动前按首期专用白名单解析，只接受 `model`、`model_provider` 和单个 `model_providers.<name>` 下的 `name/base_url/wire_api/requires_openai_auth`；`base_url` 必须精确命中 operator 下发的 Model Relay host/port。quoted key、inline table、凭据、MCP、hook 和策略覆盖全部拒绝。

`FINAL_MESSAGE` 只是非可信模型输出。只有独立采集的 patch、命令退出码和 Artifact digest 可以进入平台验收证据。

## 本地验证

```powershell
npm install --ignore-scripts
npm run check
npm test
npm run schema:generate
```

`schema:generate` 要求本机 `codex-cli` 与 `package.json` 中固定的版本完全一致。它调用：

```text
codex app-server generate-json-schema --out <generated-directory>
```

不会传入 `--experimental`。生成结果位于忽略 Git 的 `schemas/generated/<codex-version>/`，用于 CI 协议漂移检查。

## Runtime Worker 输入

容器默认入口运行 remote Worker。Kubernetes 模式只给它 `/run/reachai/broker/runtime.sock`，由独立 `reachai-managed-runtime-broker` sidecar 持有 execution-scoped token；直接 token 文件只保留给受控的本地/兼容测试模式。Worker 从 Runtime claim 取得 objective 和固定策略，并把事件与五件证据经 Broker 上传回 Runtime。Git 来源、不可变 revision、验收 argv、镜像、资源和 Codex 配置均由 Runtime operator policy 生成。

本地离线入口只接受一个参数：

```text
reachai-managed-executor-worker --job <absolute-job-config.json>
```

契约见 `schemas/reachai.managed-executor.job.v1.schema.json` 和 `examples/job.example.json`。以下字段必须由 Runtime 的可信策略生成，不能来自模型自由参数：

- `sandboxProfile`；
- `model`；
- `codexBinary`；
- `acceptanceCommands`；
- 所有路径、时限和预算。

`outputDirectory` 必须位于 Workspace 外且启动时为空，防止证据输出混入源码 patch。

## 镜像边界

Dockerfile 固定 Node 和 Codex 版本并使用非 root 用户，但镜像本身不是完整沙箱。生产调度还必须强制：

- 每 execution 新建 disposable Workspace 和 Job/microVM；
- read-only root filesystem、禁用提权、丢弃 Linux capabilities、seccomp/AppArmor；
- 不挂载宿主目录、Docker socket 或 Kubernetes ServiceAccount Token；
- egress 默认拒绝，仅允许受限 DNS、Runtime、任务级 Model Relay、Git proxy 和依赖代理；Worker 不直连对象存储；
- CPU、内存、PID、磁盘、wall-time、日志和 Artifact 配额；
- 任务结束后销毁 Workspace 与短期身份。

最终部署应固定构建产物 image digest；仓库中的基础镜像 tag 只用于可复现构建输入，不能替代部署 digest。

本地直接运行只能用于 disposable 测试仓库，不能证明宿主机隔离。当前测试不会调用模型，也不读取或输出 API Key。

## 官方协议依据

- [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)
- [Codex Sandboxing](https://learn.chatgpt.com/docs/sandboxing)
