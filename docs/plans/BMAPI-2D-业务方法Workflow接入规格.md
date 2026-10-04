# BMAPI-2D 业务方法 Workflow 接入规格

更新时间：2026-09-30。状态：BMAPI-2D-A（Studio 选择与映射）、2D-B（发布、受控执行与引用证据）和 2D-C（保存草稿的只读真实试运行）均已通过各自范围限定的主任务复核。2D-C 只覆盖一个只读标量方法 TOOL→变量图；这些隔离 H2/loopback 证据不代表独立部署、外部业务系统或生产 MySQL E2E。

关联[实施基线](./业务方法与API重构实施基线.md) U2 第 4–5 步以及 U1 第 4 步后的复用，事实依据为 [BMAPI-2D-0 接入契约核对](../../output/tasks/business-method-api/BMAPI-2D-0-接入契约核对.md)。当前完成情况见[实施进度](./业务方法与API重构实施进度.md)。

## 1. 用户要完成的任务

Workflow 实施人员在当前项目中找到一个明确开放的业务方法，看清它来自哪个项目、需要哪些输入、返回什么以及可能产生什么影响；把 Workflow 输入或前序节点输出映射到方法参数，将方法结果以明确别名和字段路径交给后续节点；保存、重新打开、调试和发布后，仍引用同一业务方法，并能从方法详情看到真实使用位置和调用证据。

这条路径继续使用现有 `TOOL` 技术节点、GraphSpec、Capability owner、Runtime 执行器、发布契约固定和引用索引。产品界面称“业务方法”，不新建业务方法节点类型、执行引擎、顶级 Tool 目录或第二份方法定义。

## 2. 分批边界

### BMAPI-2D-A：Studio 选择与映射

本批交付可独立复核的作者体验：项目内业务方法选择、输入映射、返回提示与字段引用、GraphSpec 保存/重开。只允许为明确零参数语义使用 Runtime 已支持的 `config.args={}`；不改变 Runtime 身份、ACL、发布 pin、Capability 调用协议或数据库。

### BMAPI-2D-B：首条完整纵向路径

2D-A 通过后，使用隔离环境串联真实 SDK 注册/同步与接纳、Studio 保存/回读、validate/publish、已发布 Workflow 受控执行、Trace 和引用反查。首条确定性执行入口选用现有 MCP 已发布 Workflow 路径，避免把模型是否选择 Workflow 混入方法接入验收；这只是首条验收入口，不改变 Agent 同样消费已发布 Workflow 的产品定位。

Studio debug 继续遵守现有 `DEBUG_UNTRUSTED` 边界。2D-B 必须用真实结果确认它能否在不伪造项目或业务用户身份的前提下调用该测试方法；若当前边界阻断，保留阻断并形成单独的身份方案与影响评审，不能把 Console 身份搬入 Workflow，也不能以模拟成功冒充调试闭环。

## 3. Studio 入口与选择体验

延续 [DESIGN.md](../../ai-admin-front/DESIGN.md) 的 Glass Workbench 和 [UX-CONTRACT.md](../../ai-admin-front/UX-CONTRACT.md) 的工作台交互，不改全局 token、Overlay owner 或页面骨架。

1. `ToolConfigPanel` 保留已有通用引用和项目 API 路径，在同一“调用对象”区域增加明确的“选择业务方法”动作；现有已保存 Tool/API/旧引用继续可读写，不能被自动改成业务方法。
2. 选择器使用管理端 canonical owner：Element Plus `el-dialog`、`el-input`、`el-table`、`el-pagination` 和 `el-button`。弹层标题为“选择业务方法”，显示当前项目范围，不新增全屏目录。
3. 候选只读 `GET /api/business-methods`，固定传当前 Workflow 的 `projectId`、`enabled=true`、服务端 `current/size` 和显式提交的 `keyword`。不得调用 `listAllTools` 后在浏览器猜 `assetType`，也不得用前 20 页结果冒充完整目录。
4. 搜索采用按钮或非 IME 提交的 Enter；输入可清空，清空后回到第一页并重新读取。列表有稳定的 loading 区、总数/分页、初始空、无搜索结果、持久错误与原位重试，不能只发 Toast 后显示“暂无数据”。
5. 每行至少显示标题/机器标识、用途摘要、输入数量、返回声明、副作用和来源状态。长文本允许换行或显式查看，不把身份藏在 hover 中。
6. Endpoint 已限定已接纳业务方法，前端仍只允许 `assetType=BUSINESS_METHOD`、`enabled=true`、`sourceAvailability=READY`、`qualifiedName` 非空且项目匹配的行被选中。违反这些条件的响应显示为数据不一致并禁止选择，不能自动修正或转型。
7. 选择完成后，在节点配置中常驻显示“业务方法”类型、标题、`qualifiedName`、项目、来源状态、副作用、输入和返回摘要。只读展示模型可重新查询，不写入 GraphSpec。

列表请求和当前选中方法详情分别维护请求世代。项目、账号作用域、节点引用或弹层会话变化时，旧响应、旧 loading 清理和旧错误都不能覆盖当前状态；A→B→A 同样视为新世代。

## 4. 身份与 GraphSpec 保存契约

选择业务方法后只把既有运行语义写入节点：

```json
{
  "type": "TOOL",
  "ref": {
    "kind": "TOOL",
    "name": "queryOrder",
    "qualifiedName": "orders:queryOrder",
    "projectCode": "orders"
  },
  "config": {
    "inputMapping": {
      "orderNo": "params.orderNo"
    },
    "outputAlias": "order_result"
  }
}
```

- `ref` 保留兼容机器名，执行、发布固定、Trace 和引用索引以 owner 解析后的 `qualifiedName` 串联；`projectCode` 用于范围说明和既有解析。
- 不把 `assetType`、投影 `id`、`sourceQualifiedName`、前端取得的 contract hash 或目录快照写入 GraphSpec。发布时继续由 Runtime 从 Capability owner 读取当前 READY/enabled 定义并固定 hash。
- `canvas_json` 只保存布局；方法选择、输入映射、输出别名和明确零参数语义必须在 `graph_spec_json` 保存/回读后成立，不能只存在组件内存或画布展示数据。
- 切换到另一个方法是本地可撤销的 Studio 编辑。新目标保留完全同名的现有映射，补齐缺少项并移除旧目标不存在的键；界面说明保留/重建数量。零参数目标清空参数映射。
- 保存和重开不得改写既有通用 Tool/API 节点的身份、未知字段或非字符串映射值。只有用户实际编辑参数映射时才应用当前编辑器的表达式格式。

## 5. 输入映射语义

业务方法参数先排除 `location=OUTPUT/RETURN/RESPONSE`，再把 SDK 可能提供的点分声明组成仅用于展示和映射的逻辑树；不回写来源契约或改变 contract hash。

| 方法声明 | 默认目标与来源 | 必须避免 |
| --- | --- | --- |
| 无输入参数 | `inputMapping={}`，并在 GraphSpec 写入 Runtime 已支持的显式 `args={}` | 不能落入兼容分支自动发送 `{input: lastOutput}` |
| 一个或多个标量参数 | 每个顶层参数一行，例如 `orderNo ← params.orderNo` | 不能写裸 `orderNo`，否则会被当作字面量或业务别名 |
| 单 DTO / 有 children 的对象参数 | 整体参数一行，例如 `request ← params.request`；子字段作为结构提示 | 不能生成 `request.customer.id` 这样的点分目标键；Runtime 不会据此构造嵌套对象 |
| 点分来源声明 | 先形成逻辑根，例如 `request.customer.id` 归入 `request`，映射整个根对象 | 不能把扫描表示误当成 Runtime 的嵌套写入语法 |
| 数组、Map 或开放对象 | 映射整个值，例如 `items ← nodeOutput.load_items` | 不能拆字段或字符串化对象/数组 |

- 业务方法模式使用逐参数映射行：左侧显示目标参数名、类型、必填和说明，右侧使用现有 Element Plus 可搜索/可创建选择器选择 `params`、`nodeOutput`、`var`、`sys` 或高级表达式。通用 Tool/API 的现有高级文本映射继续可用。
- “补齐映射”只补缺失顶层参数，使用 `params.<参数名>`；不覆盖作者已经选择的前序节点或系统变量。
- 必填目标未映射、表达式为空或候选变量已不存在时，在行内给出可修正提示，并进入现有 Studio 校验/发布检查；不能静默补成 `lastOutput`。
- 裸表达式由 Runtime 保留原生 JSON 类型；不要为整个对象默认包 `{{ }}`，因为模板会变成字符串。手工的 Map/List/boolean/number/null 映射在无编辑的保存/重开中必须保真。
- 自动选择只建立目标参数提示，不自动添加或修改 Workflow 用户输入节点、全局 input schema、前序节点或边。作者可以把同一方法参数映射到入口、前序输出或系统上下文。

## 6. 返回与下游引用

1. 配置区展示来源声明的 `responseType`；存在 `location=OUTPUT/RETURN/RESPONSE` 字段时展示真实字段树。只有返回类型、没有字段声明时，明确写“来源仅声明返回类型，尚无可选字段”，不从 Java 类名或示例猜字段。
2. 保留 Studio 现有输出别名机制。新节点默认使用当前 `tool_output` 或基于方法名形成稳定、可编辑的别名；重新选择方法不覆盖作者已经修改的非空别名。
3. 对来源明确声明的返回字段，为下游映射增加 `nodeOutput.<nodeId>.<字段路径>` 和 `var.<outputAlias>.<字段路径>` 候选；根对象候选始终保留。没有字段声明时只提供根对象。
4. 字段候选是作者提示，不是运行时重塑。实际业务返回原样进入节点输出，字段是否存在仍由真实执行、GraphSpec 校验和调试结果证明。
5. 输出字段和别名在保存/重开后保持一致；不得为了展示字段把返回 schema 写成来源未声明的 JSON Schema。

## 7. 2D-A 实施范围

预期主要修改：

- `ai-admin-front/src/views/workflow/studio-panels/ToolConfigPanel.vue`：业务方法入口、分页弹层、当前方法摘要、逐参数映射和错误/空状态。
- `ai-admin-front/src/views/workflow/studio-panels/NodeConfigPanel.vue`、`ai-admin-front/src/views/workflow/WorkflowStudio.vue`：复用现有变量选项并传递必要上下文；只做方法路径直接需要的接线。
- `ai-admin-front/src/views/workflow/composables/useWorkflowStudioResources.ts` 或一个单一领域 composable：方法列表/详情的请求世代与项目隔离。不要在多个组件重复请求状态机。
- `ai-admin-front/src/utils/studio.ts`、`ai-admin-front/src/types/studio.ts`：GraphSpec 往返、显式零参数 `args={}` 和字段候选所需的最小类型支持。
- 参数树/映射纯函数及测试可以放在 Workflow 领域 helper；若复用 2C 的逻辑树代码，应抽取为共享纯工具并保持 2C 回归，不让 Workflow 反向依赖 Capability 页面组件。

不修改业务方法后端目录、数据库、Runtime 身份与执行策略、ACL、Console 试调用、通用节点类型或全局视觉 token。发现后端返回不能满足本规格时，先以具体反例报告主任务，不自行扩展为新目录接口。

## 8. 2D-A 验收证据

执行任务必须先写失败反例，再实现并保留修正前后日志；测试和构建由执行任务运行。

1. 纯函数/组件：仅显示当前项目 READY/enabled 的业务方法；API、UNCLASSIFIED、跨项目、缺稳定身份行不可选；分页、显式搜索、清空、错误重试和旧响应隔离成立。
2. 映射：标量、多参数、单 DTO、点分 DTO、数组/Map、返回参数排除、零参数分别有反例。DTO 只产生顶层目标；零参数 GraphSpec 带 `args={}`，Runtime 目标测试证明最终输入为 `{}`。
3. 编辑保护：换方法时同名映射保留、无关键移除、缺失项补齐；已有自定义来源不被覆盖；普通 Tool、项目 API 和旧引用的选择、保存、重开不回归。
4. 输出：声明字段能形成根和字段路径候选；仅有返回类型时没有虚构字段；别名与字段提示保存/重开一致，下游节点能选择正确表达式。
5. GraphSpec：保存/回读只增加本规格允许的已有运行字段，不含 `assetType`、投影 ID、来源 hash 或前端 pin；发布前 GraphSpec 仍无伪造 contract hash。
6. 异步范围：项目/账号/节点切换、关闭弹层、A→B→A 和迟到失败均不能污染当前候选、摘要、映射或 loading。
7. 浏览器：在实际 Workflow Studio 覆盖成功、空、无搜索结果、错误重试、键盘、打开态选择器、窄视口和 200% 缩放；证明业务方法、项目 API 和旧引用三条入口仍可达。页面拦截只算 UI 证据，不算完整业务 E2E。
8. 工程检查：相关 Vitest、`npx vue-tsc --noEmit`、`npm run build`、Premium strict audit、项目 UI/架构/文档检查和 `git diff --check`。不为通过本批修理无关 WIP。

交付 `output/tasks/business-method-api/BMAPI-2D-A-实施结果.md`，附变更文件、修正前失败、修正后命令/计数、浏览器截图和请求日志、GraphSpec 样例、已证明与未证明边界。完成后回调主任务并停在 2D-B 之前。

## 9. BMAPI-2D-B 完整路径验收门槛

2D-A 复核通过后，执行任务另按主任务下发的隔离夹具实施，不在 2D-A 提前拼装伪 E2E。至少证明：

1. 实际 SDK scanner/registry 报告一个无副作用 BUSINESS_METHOD，Capability 接纳后 `/api/business-methods` 返回同一 `qualifiedName` 和 READY 状态。
2. 浏览器从 Studio 选择它，配置入口或前序输出映射，保存并重新读取同一 GraphSpec；validate/publish 从 owner 固定 64 位 hash，前端不提交 hash。
3. 首条受控运行通过已发布 MCP Workflow 路径，实际到达 SDK Endpoint；输入类型、零参数、DTO 整体对象和返回字段至少各有一条真实证据，不用彼此断开的 mock 夹具冒充完整链路。
4. Run/Trace 中的 TOOL span 保留同一 `qualifiedName`；业务方法详情“使用位置”显示草稿和已发布 Workflow 的真实证据。引用覆盖不完整时继续显示 PARTIAL/UNKNOWN，不能显示零引用。
5. 来源 contract drift、停用或移除后，已发布快照按现有 source guard 拒绝执行；重新接纳但未重新发布时 hash 不匹配，不能静默漂移。
6. Studio debug 的实际身份和结果单列证明。若 `DEBUG_UNTRUSTED` 阻止真实方法执行，本批不得通过“完整调试闭环”，也不得放宽信任；主任务根据现有安全契约决定后续最小方案。
7. 使用独立端口、隔离 H2/测试凭据和无副作用方法；不重启已有服务、升级开发库、变更真实授权或调用真实业务写操作。

BMAPI-2D 只有在 2D-A 作者体验和 2D-B 真实纵向证据都通过主任务复核后，才能计为首条完整业务方法路径完成。
