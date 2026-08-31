# ReachAI Model Service

`reachai-model-service` 是 ReachAI Model Gateway，默认端口 `18601`。它统一管理模型模板与实例，并向 Runtime、Knowledge 和管理端提供 Chat、结构化流、Embedding 与 Rerank。

## 职责边界

本服务拥有：

- `model_template`：Provider/协议模板和参数 Schema，不保存用户凭据；
- `model_instance`：稳定实例 ID、连接配置、加密凭据、测试与归档状态；
- Provider adapter、请求归一化、模型选择后的真实调用与错误映射；
- Chat、SSE 结构化流、Embedding、Rerank；
- Runtime 使用的实例查询 internal API。

本服务不拥有 Agent/Workflow、Knowledge/RAG 编排或 Capability 目录。已删除的 `/model/openai-proxy` 不再提供；调用方应使用稳定 `modelInstanceId` 和当前结构化接口。

## 接口

| 方法与路径 | 用途 |
| --- | --- |
| `GET /model/templates`、`GET /model/templates/{id}` | 查询模型模板 |
| `GET/POST /model/instances` | 查询或创建模型实例 |
| `GET/PUT /model/instances/{id}` | 读取或更新实例 |
| `POST /model/instances/from-template/{templateId}` | 从模板创建实例 |
| `POST /model/instances/test-draft` | 在保存前测试草稿配置 |
| `POST /model/instances/{id}/test` | 测试已保存实例 |
| `POST /model/instances/{id}/archive` | 归档实例 |
| `POST /model/chat` | Chat 调用 |
| `POST /model/chat/stream/events` | 结构化 SSE Chat 事件流 |
| `POST /model/embedding` | Embedding |
| `POST /model/rerank` | Rerank |
| `/internal/model/**` | Runtime 等服务的实例查询 internal contract |

精确 DTO、Provider 支持和错误语义以 Controller、service 与当前测试为准。

## 主要代码区域

| Package | 责任 |
| --- | --- |
| `model.template` | 模板目录 |
| `model.instance` | 实例生命周期、测试和归档 |
| `model.runtime` | Provider runtime adapter |
| `model.service` | Chat/Embedding/Rerank 应用服务 |
| `model.security` | 凭据加解密和脱敏 |
| `model.internal` | 服务间实例查询 |

## 配置

| 变量 | 说明 |
| --- | --- |
| `AI_MYSQL_*` | 共享 MySQL 连接；本服务只访问 Model-owned 表 |
| `MODEL_CREDENTIAL_SECRET` | 模型实例凭据加密 key；默认本地模式使用公开开发值，显式配置优先；生产/Kubernetes 必须注入 |
| `MODEL_INSTANCE_RUNTIME_CACHE_TTL_MS` | 多实例状态/凭据变更的短缓存 TTL，代码限制最大值 |

不要在日志、错误响应、README 或前端状态中输出真实 API Key。归档/禁用和凭据更新在多实例环境中还需验证缓存收敛。

## 构建、启动与验证

```powershell
mvn -pl reachai-model-service -am -DskipTests compile
mvn -pl reachai-model-service -am test
Set-Location reachai-model-service
mvn spring-boot:run
```

健康检查：`GET http://localhost:18601/actuator/health`。

```powershell
node scripts/check-model-center-v2-sql.mjs
node scripts/check-physical-service-route-contracts.mjs
```

接口测试通过不代表每个外部 Provider 在目标网络可用；需对实际配置的实例分别执行连接测试和最小业务调用。

## 进一步阅读

- [模型中心 V2](../docs/architecture/model-center-v2.md)
- [知识、模型与企业资产](../docs/05-知识模型与企业资产.md)
- [五服务边界与本地启动](../docs/architecture/service-boundaries.md)
