# 业务索引回源

> 2026-10-17 起由原「业务记忆」改名（记忆模块重设计 M4）。它不是 Agent 记忆：业务系统才是事实源。
> SDK / 契约改名：`ReachBusinessMemory*` → `ReachBusinessIndex*`（包 `com.enterprise.ai.reach.sdk.bizindex`），`BusinessMemoryReference/Resolution` → `BusinessIndexReference/Resolution`（包 `com.enterprise.ai.runtime.contract.bizindex`），契约 schema `reachai-business-memory-*-v1` → `reachai-business-index-*-v1`，索引字段 `agent_memory_enabled` → `agent_reference_enabled`，Runtime 配置 `reachai.runtime.business-memory.*` / `RUNTIME_BUSINESS_MEMORY_*` → `business-index.*` / `RUNTIME_BUSINESS_INDEX_*`。

业务系统把订单、客户等资源同步到 Knowledge 业务索引，供 Agent 检索定位；命中结果只是带版本的引用，Workflow 或 LLM 使用前，Runtime 用当前用户身份调用业务系统的 resolver Capability 取实时数据并重新鉴权。

## 契约

- Knowledge 的业务索引保留 `search_text`、字段 JSON 和附件文本是为了召回，不是事实副本。搜索响应中的业务内容始终标记为 `authoritative=false`。
- 历史索引默认 `agent_reference_enabled=0`，因此不会因为升级而自动暴露给 Agent。只有索引同时具备 tenant、project、source system 和 `resolver_capability_key`，且每条记录具备 `source_version` 时，才允许产生 Agent 可见引用；缺任何一项都失败关闭。
- Agent 可见引用使用 `reachai-business-index-reference-v1`，包含 tenant、project、source system、resource type/id、source version、resolver Capability 和 observed time，并明确 `hydrationRequired=true`。
- Resolver 参数只包含 `resourceType`、`resourceId` 和 `expectedSourceVersion`。调用身份不能从索引复制，必须由 Runtime 使用当前已认证业务身份调用 Capability；租户/项目不匹配、无权限、资源已删除或版本冲突都不能退回使用索引正文作答。
- Runtime 会在业务索引结果进入下一个 Workflow 节点或 LLM 之前递归识别引用。legacy/未启用索引、scope 不匹配、非法引用、resolver 失败、响应超限或返回资源不匹配时，只留下 `authoritative=false` 与低敏感安全状态码；原始 `matchContent`、附件名、metadata、资源 ID 和 resolver 引用均被剥离，不能降级使用陈旧投影，也不能在行级授权成功前泄露资源标识。
- Resolver 必须是引用指定的精确 qualified name、属于当前 Agent 项目、存在业务注册凭证、生成签名调用并携带当前可信 user；这些条件由 Capability 在实际调用前再次校验，而不是只靠 Runtime 预检查。
- 成功返回使用 `reachai-business-index-resolution-v1`。tenant、project、source system、resource type/id 必须与引用和当前身份一致；resolver 返回的当前 `sourceVersion` 可以与索引版本不同，此时仍使用实时结果并标记 `RESOLVED_VERSION_CHANGED / indexVersionMatched=false`，推动索引后续刷新。
- JDK 8 业务系统可直接使用 `ReachBusinessIndexResolverRequest`、`ReachBusinessIndexAccessScope` 和 `ReachBusinessIndexResolution`；前者字段携带 `@ReachParam`，后者字段携带 `@ReachOutput`。Starter 会把扁平 Tool 输入绑定到单个、字段带 `@ReachParam` 的 DTO，同时继续兼容显式的参数名包装对象。带 `business-index-resolver` 标签或使用标准 resolver 请求/返回类型的 Capability 在业务代码执行前强制要求签名 tenant/user 和完整请求，执行后强制要求 `createAuthorized(...)` 产生的行授权证明以及 tenant/project/resource 精确一致；漏写标签不能绕过标准 DTO 的守卫。完整示例位于 `reachai-spring-boot2-starter/README.md`。
- 每个节点默认最多解析 8 个引用，单条 resolution 默认不超过 256 KiB；可用 `RUNTIME_BUSINESS_INDEX_MAX_REFERENCES` 和 `RUNTIME_BUSINESS_INDEX_MAX_RESOLUTION_BYTES` 收紧。指标为 `reachai.business_index.resolution.references`、`latency` 和 `batch_references`，不包含 tenant、user 或 resource ID 标签。

## Business Index 企业入口

- 历史匿名 `/ai/biz-index/**` 已退役。管理端调用 `/api/knowledge/biz-index/**`：Control 校验平台 session，并按操作要求全局 `platform:read` 或 `platform:write`，只记录不含查询正文/业务正文的审计元数据，再以 exact-byte V2 HMAC 调用 Knowledge。
- 控制台 multipart 附件在 Control 和 Knowledge 两端都有大小上限；签名 body 超过 1 MiB 时使用请求期临时文件重放，避免为了 HMAC 把 100 MiB 请求复制到 JVM 堆。临时文件在请求结束后删除，生产仍须把系统 temp 目录放在受控加密卷并配置异常退出后的陈旧文件清理。
- 业务系统结构化同步调用 `/api/knowledge-ingress/projects/{projectCode}/biz-index/{indexCode}/{upsert,batch,delete}`。Starter 的 `ReachAiBusinessIndexClient` 使用 `REACHAI_PROJECT_REQUEST_V1`，把方法、公共路径、projectCode、appKey、timestamp、nonce 和精确 JSON body SHA-256 全部入签。
- Control 不读取 Capability-owned appSecret。它先校验实际 body 摘要，再通过内部 HMAC 请求 Capability 验签；Capability 在 `capability_registry_request_nonce` 消费外部 nonce，只返回 project/credential 标识。Control 随后以独立的 `PROJECT_CREDENTIAL` 内部身份调用 Knowledge；Knowledge 再绑定签名 project 与内部路径，并要求索引记录的 `project_code` 完全一致。
- 自动项目入口只支持结构化 JSON upsert/batch/delete。附件自动同步在定义流式 detached-signature 契约前不开放；平台操作员仍可通过受 RBAC 保护的控制台 BFF 上传附件。

业务 resolver Capability 的输入：

```json
{
  "resourceType": "order",
  "resourceId": "O-20260814-1",
  "expectedSourceVersion": "12"
}
```

业务系统在校验 `X-ReachAI-Invocation-Token` 中的 tenant/user/Agent/session 后返回标准业务 envelope；其中 `data` 必须符合随 `ai-runtime-contract` 打包的 resolution JSON Schema：

```json
{
  "code": 0,
  "data": {
    "schema": "reachai-business-index-resolution-v1",
    "tenantId": "tenant-a",
    "projectCode": "mall",
    "sourceSystem": "mall-order",
    "resourceType": "order",
    "resourceId": "O-20260814-1",
    "sourceVersion": "13",
    "data": {
      "status": "PAID"
    },
    "resolvedAt": "2026-08-14T12:00:00Z"
  }
}
```

当前阶段已完成共享 reference/resolution/access-scope 契约、Knowledge fail-closed eligibility、Runtime 自动 hydration、Capability 调用约束、JDK 8 SDK DTO 和远程 MySQL schema。SDK 主类字节码为 Java 8 major version 52，并通过真实 `javac 1.8` 编译。真实业务仓库 `qmssmp-teams-construction-service` 的 `bzjs20:team.memory.resolve` 现在同时校验签名 tenant 与显式配置的单租户部署绑定、当前用户可见班组列表和当前详情回读，只返回无成员 PII 的最小字段，并以确定性 SHA-256 表示当前可见版本。当前 SDK 16 个测试、Starter 40 个测试和业务仓库 JDK 8 下 8 个签名身份/Resolver 测试通过；QMSSMP 父 POM 将 Surefire 写死为跳过，因此业务用例使用 Maven 编译后的真实 test classpath 通过 JUnit 4 直接执行，不能把 reactor 的 `Tests are skipped` 误报为通过。此前标准部署文件 `depart-construction-api-service.jar` 已验证为 Spring Boot fat JAR，包含 `BOOT-INF`、Resolver 类和 ReachAI Starter，而不是仅能编译的薄 JAR；本次 tenant 加固只完成 reactor compile 与目标测试，尚未重新 package/deploy。

2026-08-15 已使用隔离 canary 完成 `Runtime Supervisor -> 已发布 Workflow -> Knowledge Business Index -> Capability -> QMSSMP resolver` 的受控运行态闭环。最终验收链路不经过诊断代理；模型和 embedding 使用本机确定性 OpenAI-compatible stub，业务 resolver 和行级可见性查询使用真实 QMSSMP 代码及其远程开发 MySQL。正向用户得到 `RESOLVED_VERSION_CHANGED`、`authoritative=true` 和实时 resolution；负向用户得到 `RESOLVER_REJECTED`，且两条路径都没有把原始 `matchContent` 暴露给 Supervisor。Runtime -> Capability 使用 exact-byte V2 HMAC，Capability -> QMSSMP 使用项目凭证签发的短期调用 Token；重放和正文篡改均返回 401。索引记录删除后 Knowledge 搜索为零命中，Runtime 不再得到业务 resolution 或索引正文。停止权限依赖 stub、但保持 QMSSMP resolver 进程存活时，正向用户同样降级为 `RESOLVER_REJECTED`，证明依赖故障不会回退使用索引快照。

该结果标记为 `CONTROLLED_DEPENDENCY_E2E`，不是完整生产外部依赖验收：在这轮受控验收发生时，Consul 中原有 `qmssmp-basic-service` 的注册地址不可达，因此 canary 使用只返回空组织权限的本地 stub；用户直接班组成员/周期关系和班组详情仍来自真实远程开发库。验收后，临时 Agent、Workflow、Tool 和 Knowledge index 已定向删除，两个临时模型实例已归档，所有 canary 进程已停止；原有 8080/8089 和 18601-18605 服务未被重启或停止。

随后又完成了更强的 `REAL_BASIC_AND_LLM_E2E_WITH_DETERMINISTIC_EMBEDDING`：从 Consul 解析到且仅解析到一个健康、非 loopback 的真实 `qmssmp-basic-service`，QMSSMP Resolver 通过 Feign load balancing 调用它并继续使用远程开发 MySQL；Runtime Agent 绑定真实模型实例 `seed-deepseek-v4-flash`，完整 Supervisor/Workflow 执行中存在真实首事件、连续 Tool 调用轮次、完成原因和 token 用量，且未触发 fallback。正向用户得到 `RESOLVED_VERSION_CHANGED`、`authoritative=true` 和实时 resolution；负向用户得到 `RESOLVER_REJECTED`，业务 reference、resource ID、resolution 和 `matchContent` 均未进入 Supervisor state。两条 RunOps 均为 `COMPLETED` 且包含 Workflow span。临时 Agent、Workflow、Tool、业务索引和 embedding model 均完成定向清理，签名 session state 清零；Milvus 写入后的首次查询存在短暂不可见，验收器使用有上限的重试而没有放宽最终断言。收口时五个隔离 canary 端口均已停止，原有 8089 和 18601 服务仍保持监听。

这仍不是完整生产外部依赖验收：该轮 embedding 是维度固定的确定性 OpenAI-compatible canary，不是真实 embedding 供应商。正式发布门槛还包括在目标环境使用真实 embedding、正式 TLS/mTLS 和生产密钥重跑同一矩阵。

这次 E2E 还发现并修复了一个独立 Runtime 缺陷：当 `var` 命名空间存在时，普通 Tool 字符串参数会被误判为变量表达式并渲染成 `null`。现在只有真实存在的 bare business variable 才按变量解析，普通字面量保持原值；对应 `RuntimeGraphSpecExecutorTest` 回归已通过。
