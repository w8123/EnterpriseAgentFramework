# Runtime Automation 架构契约

## 1. 定位与边界

Automation（自动化）是“在确定时间，以非人工主体执行一个精确已发布目标”的
Runtime 一等入口。它不是把现有 `@Scheduled` 注解包装成页面，也不是第六个物理服务。

- `reachai-runtime-service` 拥有定义版本、持久时钟、触发实例、执行租约、重试、审计与 RunOps 事实。
- `reachai-control-service` 只提供 `/api/automations/**` BFF、平台会话、RBAC 和项目作用域校验。
- 浏览器不得调用 `/internal/runtime/automations/**`，Control 以精确请求体 HMAC 委托 Runtime。
- 现有缓存清理、心跳扫描等服务内部维护任务继续使用局部 `@Scheduled`；只有需要用户配置、治理、历史和 RunOps 的业务执行进入 Automation。
- 首版目标仅为 `AGENT` 和 `WORKFLOW`，且必须固定精确的已发布版本；不接受 `latest`、草稿 GraphSpec 或客户端上传的执行快照。

## 2. 总体结构

```text
管理端自动化中心
        |
        | PLATFORM_SESSION + automation:* + project scope
        v
Control Automation BFF
        |
        | V2 exact-body HMAC, PLATFORM_SESSION identity
        v
Runtime Automation aggregate/version
        |
        +--> durable engine command --> db-scheduler cluster clock
        |                                  |
        |                                  +--> idempotent occurrence only
        |
        +--> MySQL leased worker --> exact Agent config / Workflow version
                                           |
                                           +--> RunOps root + Trace evidence
```

时钟线程不执行 Agent/Workflow。它只将名义触发时刻物化为具有唯一
`occurrence_key` 的记录。真正执行由独立有界线程池领取 MySQL 租约完成，因此慢模型、
外部 Tool、超时和重试不会占住时钟线程，也不会让调度库成为业务状态机。

## 3. 复用组件与适配决策

集群时钟采用
[db-scheduler 16.11.0](https://github.com/kagkarlsson/db-scheduler)，通过
`RuntimeAutomationEnginePort` 隔离。选择依据：

- Java 17 兼容、Apache-2.0、单张数据库表、无需 Redis/独立控制台或新增运维服务；
- 支持持久化一次性任务、动态 recurring schedule、心跳与多实例抢占；
- 官方提供 MySQL DDL，ReachAI 只将表名收口为 `runtime_scheduler_task`；
- 引擎只承担 cluster clock，未来替换为 Quartz、XXL-JOB 或云调度器时，不改变
  Automation 领域表、公开 API、状态机和 RunOps 契约。

不直接采用 XXL-JOB/PowerJob 作为首版核心，是因为它们会引入独立服务、控制台、
注册发现和另一套任务模型；ReachAI 仍需自行实现目标版本钉住、服务身份、交互策略、
RunOps 和项目 RBAC，不能因此省掉核心开发与验收。

## 4. 领域模型

| 表 | 语义 |
| --- | --- |
| `runtime_automation` | 稳定 `automation_key`、租户/项目、状态、当前版本与下一次触发投影 |
| `runtime_automation_version` | 不可变目标版本、计划、误触发、并发、超时、重试、输入与主体快照 |
| `runtime_automation_occurrence` | 一个名义触发时刻对应的幂等业务执行实例及可恢复租约 |
| `runtime_automation_attempt` | 每次领取租约的执行证据、Trace、结果摘要和错误分类 |
| `runtime_automation_event` | 无凭据的定义、操作与状态转换审计事件 |
| `runtime_automation_engine_command` | 领域事务提交后同步 cluster clock 的 durable outbox |
| `runtime_automation_execution_slot` | 跨 Runtime 副本实施并发策略的租约槽 |
| `runtime_scheduler_task` | db-scheduler 时钟事实；不保存业务结果 |

不创建数据库外键。当前同库阶段由 owning service、唯一键、事务和定期一致性检查维护，
避免服务拆库时形成物理耦合。

### 4.1 定义状态

```text
DRAFT --> ACTIVE <--> PAUSED --> ARCHIVED
             |
             +-- ONCE fired --> COMPLETED --> ARCHIVED
```

- 编辑永远创建新的 immutable version，并以 `expectedRevision` 做乐观锁。
- `ARCHIVED` 不可恢复、不可编辑、不可手工运行。
- 已过期的 ONCE 版本不能恢复；必须创建新版本，防止对历史计划作含糊解释。
- pause/archive/update 会取消旧 cluster-clock 实例，并取消尚未执行的计划 occurrence；
  已经运行的执行保留事实，不做隐式终止。

### 4.2 occurrence 状态

```text
PENDING --> LEASED --> RUNNING --> SUCCEEDED
   |          |          |
   |          +----------+--> RETRY --> LEASED
   |                         FAILED / DEAD
   +--> SKIPPED / CANCELLED
```

- 唯一 `occurrence_key` 是时钟副本、回调重放和故障恢复的幂等边界。
- `attempt_count` 在原子领取时增加；租约过期可由其他副本恢复。
- 最后一次租约过期后进入 `DEAD`，不能无限重试。
- 操作员重试会创建新的 `source_type=RETRY` occurrence，保留原失败事实。

## 5. 计划语义

### 5.1 类型与时区

- `CRON` 使用 Spring 六段 cron（含秒），并保存显式 IANA time zone。
- `ONCE` 接受 ISO-8601 UTC instant，创建时必须在未来。
- 数据库与调度表统一写 UTC；时区只用于解释 CRON。API 返回带 `Z` 的时间。
- 夏令时跳变由 Java `ZoneId` 和 cron 计划解释，不通过数据库服务器本地时区猜测。

### 5.2 misfire

- `SKIP`：超过 grace 的名义触发写入 `SKIPPED` 证据，不执行。
- `FIRE_ONCE`：恢复后只物化一次名义触发。
- `CATCH_UP`：按 cron 补齐到当前时刻，受 `maxCatchUp`（1-100）硬限制。

misfire 只决定“物化哪些 occurrence”，不绕过并发、重试、版本或身份策略。

### 5.3 concurrency

- `QUEUE`：同一 Automation 同时最多一个运行，后续 occurrence 排队。
- `SKIP`：已有运行时，新 occurrence 终结为 `SKIPPED`。
- `ALLOW`：允许 1-32 个并行槽；槽由数据库租约跨副本协调。

## 6. 执行语义

### 6.1 目标固定

- Agent 固定 `runtime_agent_config_version.id`，允许已发布的 `ACTIVE/ARCHIVED` 快照。
- Workflow 固定 `runtime_workflow_version.id`，使用该版本的 GraphSpec snapshot。
- 创建、编辑、恢复和手工运行都会重新确认目标仍存在、属于目标项目且为可执行发布版本。
- 版本运行期间被删除或失效时，occurrence 以不可重试错误结束，不漂移到新版本。

### 6.2 身份与输入

- 每个版本创建 `AUTOMATION_SERVICE_ACCOUNT` 主体快照，`userTrusted=false`。
- 它只继承创建时已确认的 tenant/project 边界，不冒充创建人或最终用户。
- 输入最大 64 KiB；递归拒绝名称疑似 password/token/secret/credential/apiKey 的字段。
- 凭据只能由 Runtime credential vault、Capability 调用边界或目标自身的受控配置注入，
  不得写入计划定义、事件、attempt、Trace 或调度表。

### 6.3 人工交互

V1 为 `FAIL_CLOSED`：Agent/Workflow 请求用户输入或审批时，Automation occurrence 失败，
记录交互证据并保留 RunOps Trace，不自动点击确认，也不把服务主体当成人类审批人。
未来若支持“等待人工后继续”，必须新增显式 `WAIT_FOR_OPERATOR` 状态、截止时间、通知和
同一 checkpoint 恢复协议，不能复用普通重试伪装。

### 6.4 超时、重试和取消

- 每个版本保存 10 秒到 24 小时的 timeout。
- 重试 1-20 次，指数退避受 initial/max backoff 上限控制。
- 参数非法、无权限、目标版本不可用、交互请求、Guard 拒绝不可重试；网络和临时执行故障可重试。
- 操作员只能取消 `PENDING/RETRY`。运行中取消需要目标执行器的强一致取消协议，首版不作虚假承诺。

## 7. 一致性与故障恢复

| 故障点 | 恢复机制 |
| --- | --- |
| 定义事务成功、时钟同步失败 | `runtime_automation_engine_command` 重试；20 次后 `DEAD` 并告警 |
| 两个时钟副本同时回调 | occurrence 唯一键去重 |
| worker 领取后进程退出 | occurrence 与 execution slot 租约过期，其他副本重领 |
| 执行完成后落库前退出 | 可能重试目标；目标 Capability 应使用 occurrence/trace id 做下游幂等 |
| 旧版本时钟回调晚到 | materializer 校验 `ACTIVE + current_version_id` 后丢弃 |
| pause/update 与待执行 occurrence 竞争 | worker 开始前再次校验状态和 current version；旧计划取消 |
| Control 不可达 | 已发布 Automation 继续运行；仅管理操作不可用 |
| Runtime/API 不可达 | Control 返回明确 503，不制造空成功状态 |

本模块提供 at-least-once 执行和业务 occurrence 幂等，不宣称对任意外部副作用实现
exactly-once。具有副作用的 Capability 必须接受并持久化幂等键。

## 8. API 与授权

公开契约为 `/api/automations/**`：

- `automation:read`：列表、详情、readiness、occurrence/attempt 历史；
- `automation:write`：创建新版本、pause、resume、archive；
- `automation:operate`：run now、retry、取消待执行 occurrence。

除 GLOBAL grant 外，Control 要求调用者对明确的 `projectCode` 具有 PROJECT grant。
项目级用户列举时必须传 projectCode；详情和动作先读取安全投影再执行项目 scope 校验。
Runtime 还以 HMAC attested tenant 对所有 key 查询进行租户围栏。

Runtime internal API 返回领域错误码和 HTTP 状态；Control 保留状态与 JSON body，不把
Runtime unavailable 映射为空列表或成功。

## 9. RunOps、指标与告警

每次实际目标执行创建 `entryType=AUTOMATION` 的 RunOps 根运行，并将 trace id 写回
occurrence/attempt。定义页面只显示摘要，完整节点、Tool、Guard 与错误调查跳转 RunOps。

生产至少采集：

- active Automation 数、下次触发滞后；
- PENDING/RETRY 队列深度与最老 `available_at`；
- RUNNING 租约数、租约丢失和恢复次数；
- 成功率、P95/P99 延迟、重试率、DEAD 数；
- engine command backlog/DEAD；
- misfire、concurrency skip、interaction fail-closed 数；
- 按 target/project/version 的失败聚类。

告警不得包含输入、输出正文、凭据字段或原始最终用户身份。

## 10. 扩展点

- 新 trigger：事件、Webhook 或依赖完成触发必须实现同一 materializer 端口，仍产出 occurrence。
- 新 target：实现 target validator/executor，并定义精确版本与服务身份，不在时钟回调内直接调用。
- 日历：节假日/维护窗口作为版本化 calendar reference，不把日期散落进 worker。
- 通知：使用 occurrence terminal event/outbox 接入，不耦合执行事务。
- 更大规模：保留公开/领域契约，替换 `RuntimeAutomationEnginePort` 或 occurrence queue 实现。

扩展不得破坏四条不变量：精确版本、服务主体、幂等 occurrence、RunOps 可追溯。
