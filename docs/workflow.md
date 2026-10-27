# 业务能力与 Workflow

## 资产与来源

“业务能力”工作台包含 Java 业务方法和 HTTP API 两类资产。SDK、Controller、OpenAPI 与 API 市场是来源机制；正式 owner 维护已接纳契约，Tool 定义由 owner 派生，不能反向成为第二份可编辑资产。

```text
来源观察 → 接纳决定 → 方法/API 资产 → Workflow 草稿
                                      → 发布时固定契约 → Runtime 执行与 Trace
```

来源拥有类型、参数、路由、响应和副作用声明；平台维护接纳、允许的使用配置、业务分类与补充说明。人工或 AI 说明不覆盖来源契约。完整清单才能判定成员移除，单条重扫不能删除其他成员；身份歧义、未知或不完整来源应明确报出。

目录按 owner 分页，分类是项目共享的可选元数据，不改变执行权限和发布依赖。Java / HTTP API 统一使用平铺列表、分类列与筛选；人工移除或清空的归属不被同步自动覆盖。

## 试调用与连接

Console 使用服务端核实的项目身份、已接纳契约、操作权限与精确 ACL ALLOW；客户端不能选择任意地址、用户、角色或凭据。写操作每个新尝试都需确认；同一 invocationId 用于去重和查询原结果，超时或响应丢失后的 UNKNOWN 不自动重发。

HTTP API 生效连接取单 API 有效覆盖，否则取项目与环境的默认连接。origin、认证方式与凭据按统一策略检查；连接来源、修订或策略变化都会使旧批准与发布引用失效。

## GraphSpec 与发布

| 内容 | 事实源 |
| --- | --- |
| 运行语义 | `runtime_workflow.graph_spec_json`，GraphSpec schemaVersion 2 |
| 画布布局 | `runtime_workflow.canvas_json` |
| 前端类型 | [workflow.ts](../ai-admin-front/src/types/workflow.ts) |
| 执行、编译、Handler 与事件 | Runtime 的 `execution/kernel` |

草稿使用乐观修订，发布产生不可变版本并固定方法/API 身份、接纳修订、契约、来源与执行绑定。契约漂移或来源移除时旧发布先拒绝派发；升级需显式读取、校验和重新发布，不能静默修改 pin。

Handler 注册表、发布校验、Studio 可配置节点与 AI 编辑共用能力边界。枚举存在不等于节点已经可用；新增节点必须同时实现执行、校验、配置和必要恢复语义。

发布校验由 [RuntimeWorkflowReleaseValidationService](../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowReleaseValidationService.java)编排，图结构、节点配置、交互与资源各自维护校验规则。发布端与执行端通过 [GraphSpecLoopConfig](../reachai-runtime-service/src/main/java/com/enterprise/ai/agent/graph/GraphSpecLoopConfig.java)共用 LOOP 的新旧配置解析、循环体识别与迭代上限，避免两端解释不同。

## 编辑与调试

- Studio 直接编辑当前工作副本；保存、校验、冲突确认和异步响应都绑定当前文档、路由与编辑代次，旧结果不能覆盖新页面。
- AI 生成使用 `/api/workflows/studio/proposals/generate`，编辑使用 `/api/workflows/studio/proposals/edit`；候选必须经过确定性 mutation 和 GraphSpec 校验，再预览应用。
- 参数取值必须来自已声明的输入、变量或真实候选列表；找不到时返回明确的无结果，不让模型补造业务 ID。
- 连线的成功、失败、为空、非空和分支名须对应实际执行语义；知识无依据时按证据策略回答。

提案生成分别维护模型 JSON 解码、提示词与页面助手约束；生成服务负责候选编排、资源归一化、校验和修复。返回的画布布局统一由布局服务从修复后的 GraphSpec 派生，新增或移除节点以最终图为准，不再维护第二份画布执行配置。

普通沙箱 Debug 不自动获得真实业务调用身份。整稿只读试运行由 Control 核实平台权限、项目、每个目标的只读声明、连接与 ACL，再签名工作副本修订、图 hash、目标与截止时间；Runtime 复读并逐次核对，以 `STUDIO_PROJECT_TEST` 执行。

只读试运行允许符合政策的整稿及已发布 Workflow 的已保存工作副本，不创建发布版本。写方法/API、页面动作、人工审批及不支持的副作用节点在任何派发前整体拒绝；目标、派发次数和时间上限以 [WorkflowReadOnlyTrialPolicy](../ai-common/src/main/java/com/enterprise/ai/common/capability/WorkflowReadOnlyTrialPolicy.java)为准。

## 执行、交互和恢复

编译器固定入口、出口、节点和边，Engine 负责推进、错误策略、取消与 Trace，Handler 通过类型化 Port 执行业务调用。统一变量解析器处理输入、路径和模板，节点不复制解析规则。

WAITING 交互必须保存 GraphSpec、运行状态、所有者和固定执行上下文。提交时验证真实身份与原交互，原子领取恢复权并支持幂等重放；缺少快照在派发前失败，不从当前草稿猜测。LOOP 保持迭代变量隔离，恢复后不得重新执行已完成的外部副作用。

流式公共文本只接受后端明确的 `message.delta`，节点输出用于轨迹，内部规划不转成公共回答。取消是受支持资源的协作式停止，不承诺撤销已发生的业务写入。Trace、自动化与评测见[运维](operations.md)，支持限制见[当前边界](status.md)。
