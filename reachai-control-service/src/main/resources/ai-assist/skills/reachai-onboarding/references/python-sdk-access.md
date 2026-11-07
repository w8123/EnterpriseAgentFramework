# Python FastAPI 接入

在接入清单中选择 `language=python`、`packageName=reachai-fastapi` 的 wheel。从清单提供的平台制品地址下载到受控位置，按 `integritySha256` 对下载字节计算 SHA-256，缺少摘要或不一致时停止安装。使用当前项目虚拟环境执行 `python -m pip install <已校验的wheel路径>`；依赖由配置好的可信 Python 包源安装。不要把平台地址当作 pip 包源。适用 Python 3.10–3.13、FastAPI 0.115 至 1.0 之前及 Pydantic 2。

从 `reachai_fastapi` 导入 `ReachAI`、`ReachAIOptions`、`EncryptedFileCredentialStore`。由业务应用显式读取配置并传入 `ReachAIOptions(registry_url=..., project_code=..., base_url=..., enrollment_token=..., allowed_origins=(...,))`。`base_url` 必须能从 ReachAI 服务访问，网关路径需要与部署一致；`allowed_origins` 仅包含允许嵌入的业务站点来源。注册使用一次性 enrollment token 加 `credential_store`，或安全注入项目 `app_key` 和 `app_secret`；凭据和 Fernet 密钥由业务系统的秘密管理器或安全环境提供，不得提交仓库或写入浏览器。`EncryptedFileCredentialStore(path, fernet_key)` 的文件目录权限由宿主管理。

使用 `ReachAI(options, credential_store=store, principal_resolver=..., security_bridge=...)` 初始化后，在真实业务服务上通过 `@reach.capability(name="order.query", side_effect="READ", business_identity_required=True)` 显式声明能力，再调用 `reach.mount(app)`。不要自动扫描或在启动时同步业务 API。业务系统必须根据自身登录态实现异步 `principal_resolver(request, agent_id)`，返回已验证的 `EmbedPrincipal`；按现有业务权限实现异步 `security_bridge(context, call_next)`，调用受保护方法前建立和恢复业务身份。没有真实身份或权限实现时保持功能不可用，不要伪造用户。

服务端同步回调为 `POST /reachai/registry/capabilities/sync`，业务调用为 `POST /reachai/capabilities/{name}/invoke`，嵌入 token broker 为 `POST /reachai/embed/token`。前两条路径是签名服务端调用，按原业务网关配置正确转发并保留签名头；broker 保留业务登录保护并检查来源。仅在正式 SDK 同步动作触发时同步显式声明能力，核对实际签名回调、注册心跳及快照；若需导入 HTTP API，应使用平台现有 OpenAPI 流程。共享前端 Embed SDK、页面桥接、Workflow 和 MCP 的业务身份及授权要求仍然适用，分别验证后再报告，项目级旧会话不能证明 Python 的验收。
