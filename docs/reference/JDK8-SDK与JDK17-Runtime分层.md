# ReachAI JDK8 接入 SDK 与 JDK17 Runtime 分层方案

## 目标

ReachAI 同时支持两类运行环境：

- 业务系统侧：通常仍运行在 JDK8 或 Spring Boot 2，目标是低侵入声明能力、扫描能力并注册到平台。
- 平台侧：运行在 JDK17 和 Spring Boot 3，负责 Capability Catalog、Runtime Host、Knowledge / Retrieval、Model Gateway 和 Platform Control。

JDK8 侧不运行完整 Agent Runtime，不依赖 LangGraph4j，不承担平台治理职责。它只提供业务能力声明、SDK 图同步、实例心跳和本地能力调用入口。

## 当前模块

| 模块 | JDK 目标 | 职责 |
| --- | --- | --- |
| `reachai-capability-sdk` | JDK8 兼容 | 注解、能力描述、注册协议和业务系统侧契约。 |
| `reachai-spring-boot2-starter` | JDK8 / Spring Boot 2 | 扫描 `@ReachCapability`、同步项目和能力、发送实例心跳。 |
| `ai-runtime-contract` | 平台内部契约 | Tool / Skill 运行时兼容契约，后续可继续收敛。 |
| `reachai-control-service` | JDK17 | Platform Control public API/BFF 和 SDK 注册兼容入口。 |
| `reachai-runtime-service` | JDK17 | Runtime Host，执行 Agent、Workflow、GraphSpec、Trace、RunOps 和调试。 |
| `reachai-capability-service` | JDK17 | Capability Catalog，管理能力快照、diff、评审、扫描目录和能力资产。 |
| `reachai-knowledge-service` | JDK17 | Knowledge / Retrieval，管理知识库、RAG、向量检索、业务索引和扫描器实现。 |
| `reachai-model-service` | JDK17 | Model Gateway，管理模型实例、Chat、Embedding、Rerank 和 OpenAI 兼容代理。 |

## 接入链路

1. 业务系统引入 `reachai-spring-boot2-starter`。
2. 业务 Bean 使用 `@ReachCapability` 和 `@ReachParam` 声明能力。
3. Starter 启动时扫描本地 Bean，构造项目、实例、能力和参数描述。
4. SDK 注册请求进入 `reachai-control-service` 的公开兼容入口。
5. Control 将能力注册语义委托给 `reachai-capability-service`。
6. Capability Catalog 形成快照和 diff，并进入评审、apply/ignore 和资产目录。
7. Runtime 执行时通过 `reachai-runtime-service` 解析 Agent/Workflow/Capability，再按服务边界调用业务系统或平台服务。

## 服务边界

| 边界 | 规则 |
| --- | --- |
| SDK 注册入口 | 对外兼容入口由 `reachai-control-service` 保持，内部归属 `reachai-capability-service`。 |
| Runtime 执行 | Agent、Workflow、GraphSpec、Trace、RunOps 和调试归 `reachai-runtime-service`。 |
| 知识检索 | RAG、Embedding 前处理、向量检索和业务索引归 `reachai-knowledge-service`。 |
| 模型调用 | Chat、Embedding、Rerank 和 OpenAI 兼容代理归 `reachai-model-service`。 |
| 前端公共 API | 管理端 `/api/**` 不直接绕过 Control 打 Runtime 或 Capability 内部端口。 |

第一阶段保持同一个 MySQL 库，不拆库，但服务代码必须遵守 owning service 边界。

## Header 与兼容

`eaf.*`、`X-EAF-*`、`Eaf*` 属于技术身份兼容标识，不在品牌文案迁移时顺手修改。新文档和新 SDK 推荐使用 ReachAI 口径，但历史协议兼容头需要按明确迁移计划处理。

## 本地验证

常用命令：

```bash
node scripts/check-backend-boundary-naming.mjs
node scripts/check-backend-domain-dependencies.mjs
mvn -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
```

业务系统接入验证应同时关注：

- Starter 是否能扫描 `@ReachCapability`。
- 项目注册、实例心跳、能力同步是否进入 Control 兼容入口。
- Capability Catalog 是否形成能力快照和 diff。
- Runtime 调用业务能力时是否遵守 Tool / Capability 调用契约。

## 后续工作

- 继续清理历史 Skill 命名，只在兼容字段和历史表名中保留。
- 继续收敛 `ai-runtime-contract` 中的兼容契约。
- 为业务系统接入样例补齐五服务拓扑下的启动和联调说明。
