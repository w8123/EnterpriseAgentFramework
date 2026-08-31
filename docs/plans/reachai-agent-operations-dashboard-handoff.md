# ReachAI 智能体运营大屏实施交接方案

> 状态：前端运营首屏与本地布局编辑源码已实现；统一 BFF、服务端布局持久化、Screen/项目钻取、数据对账和最新部署验收仍未完成<br>
> 更新时间：2026-08-30<br>
> 适用范围：当前 ReachAI 仓库根目录<br>
> 交接目标：后续实施窗口可直接续做未完成阶段，不再重新猜测产品定位、视觉方向、数据口径和服务边界。<br>
> 当前证据：源码测试和前端构建已通过，浏览器已验证管理台首屏可加载；但当前运行服务并非全部为最新构建且 Knowledge 未运行，数据库口径对账、服务端布局、Screen/项目页和大屏设备验收仍为 `PENDING / NOT RUN`。

## 1. 给实施窗口的执行摘要

本任务不是继续美化现有“概览”或制作架构拓扑，而是建设一个以真实运营数据为核心的“ReachAI 智能体运营中心”。页面首先服务于领导和运营人员快速回答以下问题：

1. 平台有多少 Agent，当前有多少 Agent 正在真实执行任务。
2. 今天产生了多少业务调用、多少活跃运行用户、多少已记录 Token。
3. 哪些 Agent、业务系统和 Workflow 使用最多、效果最好或问题最多。
4. 某个业务系统的 Agent、使用量、Token、失败、延迟、热点意图分别是什么。
5. 数据是否新鲜、是否完整、是否受权限或服务故障影响。
6. 用户能否按自己的关注重点添加、删除、拖拽、缩放和保存模块。

实施时必须遵守以下硬约束：

- 先读根目录 `AGENTS.md`，再读本文，并重新执行 `git status --short`。
- 当前 `ai-admin-front/src/views/dashboard/Dashboard.vue` 在本次交接时为 `MM`，同时存在已暂存和未暂存修改；`Dashboard.test.ts` 与 `vitest.dashboard.config.ts` 为未跟踪文件。不得用 `git reset`、`git checkout --`、覆盖写入或格式化全仓来清理它们。
- 先检查现有 Dashboard 两层 diff，确认哪些是待替换原型、哪些是并行工作。只修改用户已经授权的运营大屏范围；保留其他并行 WIP。
- Control 是前端唯一公共 API/BFF；Runtime、Capability、Knowledge、Model 之间通过 internal API 协作。即使当前共用一个 MySQL，也不得跨服务直读写对方表。
- 页面不得使用生产 Mock、固定增长率、伪造活动流或“服务在线”等无法证明的文案。
- “空数据”与“加载中、无权限、服务不可用、功能未启用、数据覆盖不足”必须分开呈现。
- 默认大屏先交付真实、实用、可钻取的运营统计，再实现布局自由编辑；不要再放一个占据中央的大型架构环或装饰性拓扑。

## 2. 产品定义

### 2.1 页面名称与入口

- 产品名：`ReachAI 智能体运营中心`
- 管理台入口：`/dashboard`
- 平台大屏入口：`/dashboard/screen`
- 项目运营页：`/dashboard/projects/:projectCode`
- 项目大屏：`/dashboard/projects/:projectCode/screen`

其中：

- 管理台模式保留侧边栏和面包屑，允许纵向滚动，适合分析和编辑布局。
- 大屏模式隐藏侧边栏和面包屑，使用 16:9 虚拟画布，无纵向滚动，适合会议室、展厅和领导驾驶舱。
- 平台页展示跨项目汇总；点击业务系统卡片或排行后进入对应项目运营页。
- 项目页复用同一 Widget 系统，只是强制固定 `projectCode`，并提供 Agent、Workflow、意图、Token、失败和延迟的项目内视角。

### 2.2 页面不是什么

- 不是系统架构图、服务拓扑图或技术资产盘点页。
- 不是把现有几个列表接口拼在一起的“概览”。
- 不是监控 CPU、内存和 Pod 的基础设施监控页；没有真实遥测前不得展示资源利用率。
- 不是开放式低代码页面搭建器；用户只能组合平台注册过的安全 Widget。
- 不是直接读取会话正文的舆情/问答分析工具；高频问题必须经过隐私治理和聚类数据链路。

## 3. 当前仓库事实快照

以下事实截至 2026-08-26，实施窗口开始工作时应重新核对：

| 事实 | 当前状态 | 实施含义 |
| --- | --- | --- |
| 当前 Dashboard | `ai-admin-front/src/views/dashboard/Dashboard.vue`，约 1784 行 | 当前原型是单文件“指挥中心”，不应继续堆功能，应拆分 |
| 当前 Dashboard 数据 | 页面分别调用扫描项目、能力模块、Workflow、Agent 统计、RunOps 最近记录、服务健康共 6 类接口 | 请求分散，缺少统一口径、批量查询和部分失败模型 |
| 当前 Agent 统计 | `/api/agents/statistics` 返回总数、启用数、绑定 Workflow 的 Agent 数、启用 Workflow Tool 数 | 可复用为资产盘点，但不是“正在工作” |
| 当前运行事实 | `runtime_run` 保存 Trace、项目、用户、Agent、Workflow、状态、耗时、`token_cost` 和时间 | 是运营统计的主事实表，归 Runtime 所有 |
| 当前 Tool 子事件 | `runtime_tool_call_log` 保存意图类型、Tool、成功、耗时、Token 等 | 可做 Tool/意图技术统计，但要检查字段覆盖率 |
| 当前用户身份 | `runtime_run` 有 `user_id`、`external_user_id`、`global_user_id` | 当前部分路径把同一个可信 `userId` 同时写入三列，跨系统用户去重口径尚不可靠 |
| 当前 Token | `runtime_run.token_cost` 为累计 Token 数，默认值为 0 | 名称容易被误解为金额，且 0 无法区分真实 0 与未采集 |
| 当前 Knowledge 命中日志 | 有 `knowledge_hit_log` | 生产 internal retrieval 当前显式 `recordHit(false)`，不能据此承诺真实高频问题 |
| 当前模型实例 | `model_instance` 有供应商、模型、状态、测试耗时 | 没有调用用量账本和价格版本，无法计算货币成本 |
| 当前业务实例心跳 | `capability_project_instance.last_heartbeat_at` | 可展示“接入实例心跳”，不能外推为整个业务系统健康 |
| 当前前端依赖 | 有 Vue 3、Element Plus、G6、Vue Flow；没有图表库和 Dashboard Grid 库 | 图表建议新增 ECharts；布局引擎按本文自研，不使用 G6/Vue Flow |
| 当前路由布局 | `/dashboard` 是 `MainLayout` 的 `standard` 子路由 | 需新增 `screen` 布局模式，仍复用平台认证守卫 |
| 当前布局持久化 | 无 Dashboard 布局表 | 需由 Control 新建个人布局和模板版本表 |

当前没有以下已验证结果：

- 没有运行真实数据库聚合 SQL。
- 没有确认本地 18601–18605 服务是否为最新构建。
- 没有验证当前 Dashboard 的真实浏览器表现。
- 没有验证高频意图、Token 和全局用户字段在真实数据中的覆盖率。
- 没有验证 `x-hub` 当前最新提交和许可证文件；只借鉴交互思想，不直接复制代码。

## 4. 已确认的产品与技术决策

### 4.1 一套 Widget，两个展示模式

管理台和大屏共享以下内容：

- Widget 注册表。
- 数据查询协议。
- 指标口径。
- Widget 组件。
- 布局 schema 和迁移器。
- 权限、空态、覆盖率和刷新机制。

仅布局密度和页面外壳不同：

- `CONSOLE`：12 列流式网格，可滚动，适合编辑和分析。
- `SCREEN`：24 列 × 22 行有界网格，虚拟分辨率 1920×1080，不滚动。

不得维护两套各自演进的 Dashboard 组件。

### 4.2 自定义能力边界

用户可以：

- 添加、删除、移动、缩放和复制已注册 Widget。
- 修改 Widget 支持的配置，如指标、时间范围、TOP N、项目过滤、排序和展示样式。
- 保存个人布局、恢复默认布局、撤销/重做。
- 在平台运营模板和项目运营模板之间切换。

用户不可以：

- 输入任意 SQL。
- 注入脚本、Vue 组件名、iframe URL 或 API URL。
- 配置凭据、请求头、模型密钥或数据库连接。
- 通过布局绕过项目和数据权限。

`x-hub` 只作为以下交互思路的参考：网格坐标、Pointer Events、拖拽/缩放草稿、碰撞处理、预设布局和本地预览。ReachAI 必须拥有自己的布局 schema、服务端持久化、权限和数据状态模型；不要整体引入其 iframe 扩展机制，也不要在许可证未复核前复制源码。

### 4.3 默认大屏必须统计优先

视觉可以有科技感，但科技感来自以下内容：

- 高密度、可扫描的真实指标。
- 克制的蓝青色发光边界和状态脉冲。
- 实时刷新时间、趋势和可钻取反馈。
- 不同业务系统与 Agent 的动态排行和活动。

不得依靠巨大圆环、宇宙背景、3D 架构节点或无业务含义的动画占据主视觉。

## 5. 默认大屏视觉与布局规格

### 5.1 视觉基调

- 背景：接近黑蓝的深色底，允许非常弱的网格纹理和径向渐变。
- 面板：深蓝半透明，但避免大面积高成本 `backdrop-filter`。
- 主色：青蓝；辅助色：紫、绿、橙；红色只表示明确失败或高风险。
- 边框：1px 低对比度边框，选中或编辑态使用双层描边。
- 字体：数字优先使用等宽数字特性；标题和中文沿用现有字体栈。
- 动画：数字更新、折线流光和状态点轻微呼吸；尊重 `prefers-reduced-motion`。
- 大屏常态不显示滚动条、悬浮抽屉和侧边栏。

### 5.2 1920×1080 虚拟舞台

`DashboardScreen.vue` 使用固定 1920×1080 虚拟舞台：

1. 外层测量实际可用宽高。
2. `scale = min(actualWidth / 1920, actualHeight / 1080)`。
3. 舞台居中并使用 `transform: scale(...)`。
4. 非 16:9 屏幕允许留出黑色安全边，不拉伸内容。
5. Pointer 坐标进入布局引擎前除以 `scale`，确保拖拽和缩放准确。
6. 4K 屏仍渲染同一逻辑舞台，浏览器负责高分辨率绘制；图表在容器 Resize 后调用 `resize()`。

建议大屏结构：

- 顶栏：56px，包含产品名、平台/项目范围、日期范围、自动刷新状态、编辑布局、退出大屏。
- 内容安全边距：左右 22px，底部 18px。
- 网格：24 列 × 22 行，间距 10px。
- 每个 Widget 使用统一 `DashboardWidgetFrame`，标题栏 34px。

### 5.3 默认 `SCREEN` 布局

下表坐标均为 24×22 网格，原点在内容区左上角：

| Widget 实例 | `widgetKey` | x | y | w | h | 说明 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| Agent 总数 | `kpi.agent-inventory` | 0 | 0 | 4 | 3 | 总数、启用数、发布配置覆盖 |
| 正在工作 | `kpi.active-executions` | 4 | 0 | 4 | 3 | 基于活跃执行租约，不用 Agent enabled 冒充 |
| 今日调用 | `kpi.business-runs` | 8 | 0 | 4 | 3 | 仅业务流量根运行 |
| 活跃用户 | `kpi.active-runtime-users` | 12 | 0 | 4 | 3 | 显示身份覆盖率 |
| Token 消耗 | `kpi.recorded-tokens` | 16 | 0 | 4 | 3 | 明确“已记录 Token”及采集覆盖率 |
| 运行完成率 | `kpi.technical-completion-rate` | 20 | 0 | 4 | 3 | 技术完成率，不冒充业务成功率 |
| 今日 TOP Agent | `ranking.agent-top` | 0 | 3 | 7 | 10 | 排名、状态、完成数、调用、完成率 |
| 今日使用趋势 | `trend.usage` | 7 | 3 | 10 | 10 | 调用、Token、活跃用户切换/叠加 |
| 业务系统排行 | `ranking.project-top` | 17 | 3 | 7 | 5 | 调用量与占比，点击进入项目运营页 |
| 异常与待处理 | `quality.attention` | 17 | 8 | 7 | 5 | 失败、超时、等待审批、Guard 拒绝 |
| 业务系统运营入口 | `projects.operations-cards` | 0 | 13 | 18 | 7 | 每项目 Agent、调用、Token、用户、完成率 |
| Token 分布 | `distribution.token-by-project` | 18 | 13 | 6 | 7 | 项目维度；只统计已记录 Token |
| 实时动态 | `activity.runtime-stream` | 0 | 20 | 24 | 2 | 脱敏元数据，不展示正文和参数 |

“高频问题 TOP”是目标 Widget，但不在数据基础未完成时伪造。数据链路成熟后，可将 `quality.attention` 替换为 `insight.question-clusters-top`，或由用户从 Widget 目录自行添加。若当前只有 `runtime_tool_call_log.intent_type` 且覆盖率合格，可先提供名为“高频意图”的 `insight.intent-top`，不得把意图标签包装成用户原始问题。

### 5.4 管理台默认布局

`CONSOLE` 使用 12 列：

- 六个 KPI 默认每个宽 2、高 2。
- Agent TOP 宽 4、高 8。
- 使用趋势宽 5、高 8。
- 项目排行宽 3、高 4。
- 异常与待处理宽 3、高 4。
- 项目卡片宽 9、高 7。
- Token 分布宽 3、高 7。
- 实时动态宽 12、高 3。

在小于 1200px 的内容宽度下，不强求一屏展示；按 Widget 最小宽度重新排布并允许纵向滚动。大屏路由不做这种重排，只做整体缩放。

## 6. 用户流程与钻取

### 6.1 平台运营流程

1. 用户进入 `/dashboard`。
2. 页面 Bootstrap 返回其权限、默认/个人布局、Widget 可用性和服务器时间。
3. 前端按布局收集可见 Widget，一次批量请求数据。
4. 用户点击“业务系统排行”或项目卡片，进入 `/dashboard/projects/:projectCode`。
5. 项目运营页自动把所有查询限定到该项目，并显示项目接入心跳和数据覆盖。
6. 点击 Agent、失败类型、趋势点或活动项，跳转 RunOps 并携带可验证过滤条件。

### 6.2 布局编辑流程

1. 用户点击“编辑布局”。
2. 当前已保存布局复制为本地 Draft，显示网格、拖拽把手和工具栏。
3. 编辑过程中只修改 Draft；服务端布局不变。
4. 用户可以添加 Widget、拖拽、缩放、删除、复制、撤销和重做。
5. 点击“取消”直接丢弃 Draft；点击“保存”以当前 `revision` 乐观锁提交。
6. 服务端成功后返回新 revision；冲突返回 `409`，前端让用户选择重新加载或复制当前草稿，不静默覆盖。
7. 大屏模式的任何 Widget 都不能超出 24×22；溢出时显示无效预览并禁止保存。

禁止“拖出画布即删除”。删除必须通过 Widget 菜单或键盘 Delete，并支持撤销。

### 6.3 大屏展示流程

- 默认每 30 秒刷新一次。
- 页面不可见时暂停轮询；恢复可见后立即刷新一次。
- 用户正在拖拽或缩放时暂停布局动画和图表过渡，不暂停必要的数据状态更新。
- 顶栏展示最近成功刷新时间；部分服务失败时展示“部分数据不可用”，不把其他 Widget 清零。
- 进入浏览器全屏失败时仍保持页面大屏布局，不阻断使用。

## 7. 全局架构

```mermaid
flowchart LR
    UI[Vue Dashboard / Screen] -->|Public API| BFF[Control Operations Dashboard BFF]
    BFF --> LAYOUT[(Control Layout and Template Tables)]
    BFF -->|HMAC internal API| RUNTIME[Runtime Operations Query]
    BFF -->|HMAC internal API| CAP[Capability Project Summaries]
    BFF -. future .->|HMAC internal API| KNOW[Knowledge Insight Query]
    BFF -. future .->|HMAC internal API| MODEL[Model Usage Query]
    RUNTIME --> RR[(runtime_run)]
    RUNTIME --> LEASE[(runtime_active_execution)]
    RUNTIME --> TOOL[(runtime_tool_call_log)]
    CAP --> PROJECT[(capability_scan_project)]
    CAP --> INSTANCE[(capability_project_instance)]
```

职责划分：

| 服务 | 职责 | 禁止事项 |
| --- | --- | --- |
| Control | 公共 Dashboard API、用户/权限、布局、模板、聚合、缓存、部分失败编排 | 不直查 Runtime/Capability/Knowledge/Model 表 |
| Runtime | Agent/Workflow/RunOps 运营聚合、活跃执行租约、技术完成率、Token 根汇总、活动流 | 不保存 Dashboard 个人布局，不读取 Capability 项目表 |
| Capability | 项目名称、接入状态、实例心跳、能力资产摘要 | 不查询 `runtime_run` 计算使用量 |
| Knowledge | 未来的检索覆盖、知识命中和治理后的问题聚类 | 不向大屏暴露原始查询或文档正文 |
| Model | 未来的模型调用用量、供应商/模型维度和货币成本 | 不保存 Prompt/响应正文到运营账本 |

Control 的 Dashboard 聚合器必须使用专用、强类型 internal clients，不要继续向当前庞大的 `ControlRuntimePublicController` 和通用 `RuntimeProxyClient` 堆 Dashboard 逻辑。

## 8. 数据可用性分级

### 8.1 当前有事实源，可直接或经聚合提供

| 数据 | 来源 | 当前可用程度 | 页面文案 |
| --- | --- | --- | --- |
| Agent 总数/启用数 | `runtime_agent`、现有 Agent statistics | 可直接提供 | 智能体总数、已启用 |
| Workflow 总数/发布状态 | `runtime_workflow`、版本表 | 可聚合 | Workflow 资产/已发布 |
| 根运行数量和趋势 | `runtime_run` | 可聚合，但必须先排除测试/Eval/内部流量 | 今日业务调用 |
| 技术完成/失败/超时 | `runtime_run.status` | 可聚合 | 运行完成率、失败率、超时数 |
| Agent TOP | `runtime_run.agent_id/name` | 可聚合 | 今日 TOP Agent |
| 项目 TOP/项目卡片使用量 | `runtime_run.project_code` | 可聚合；项目名称由 Capability 补齐 | 业务系统排行 |
| 延迟 | `runtime_run.latency_ms` | 可聚合，需忽略 NULL 并显示覆盖率 | P50/P95 耗时 |
| Guard/审批/Tool 调用数 | `runtime_run` 计数字段 | 可聚合 | 风险与待处理摘要 |
| 最近活动 | `runtime_run` 和安全元数据 | 可提供 | 实时动态 |
| 项目接入实例心跳 | `capability_project_instance` | 可提供 | 接入实例心跳，不称系统健康 |

### 8.2 有字段，但先补数据质量契约才可准确展示

| 数据 | 当前问题 | 必须补齐 |
| --- | --- | --- |
| “正在工作” | `RUNNING` 可能因异常退出长期残留，Agent 也不是常驻进程 | 活跃执行租约和心跳 |
| 今日业务调用 | 当前只有 `entry_type`，业务、测试、评测和内部调用容易混入 | 持久化 `traffic_class` |
| 活跃用户 | 三种用户 ID 在部分路径被写成同一个值 | 规范可信身份传递；返回 identity coverage |
| Token | `token_cost=0` 无法区分未采集，字段名易被理解为金额 | Token 分项、总量和采集状态 |
| 高频意图 | `intent_type` 可能为空或粒度不一致 | 统计覆盖率、规范化意图字典 |
| 项目“在线” | 只有 Starter 实例心跳 | 只展示心跳事实，定义过期阈值 |

### 8.3 当前不能承诺，必须作为后续能力

| 数据 | 不能承诺的原因 | 后续方案 |
| --- | --- | --- |
| 高频用户问题原文 | 生产 Knowledge 命中记录关闭；会话正文受隐私和留存治理 | 新建脱敏、规范化的意图/问题聚类事件链路 |
| Token 货币成本 | 无调用级账本、价格版本和币种 | Model 服务增加无正文用量账本和定价版本 |
| CPU/内存/Pod 资源利用率 | 当前没有项目归属明确的 OTel/Prometheus 遥测 | 接入指标平台后由专用 telemetry provider 提供 |
| 业务成功率 | `COMPLETED` 只证明技术执行完成 | 定义业务 Outcome 回写协议和证据引用 |
| Agent 在线率 | Agent 不是常驻服务 | 删除该概念，使用正在执行、近期活跃、可用配置 |

## 9. 指标口径

所有时间查询必须由 Control 接收 IANA 时区，默认 `Asia/Shanghai`，在服务端计算 `[startInclusive, endExclusive)` UTC 边界。禁止由浏览器自行拼“今天”的数据库时间条件。

### 9.1 公共过滤条件

- `tenantId`：从登录会话派生，不信任请求体。
- `projectCode`：平台页可为空，项目页强制为路由项目；必须经过权限校验。
- `trafficClass`：默认仅 `BUSINESS`。
- `timeRange`：默认 `TODAY`，可选 `LAST_24_HOURS`、`LAST_7_DAYS`、自定义范围。
- `timezone`：默认 `Asia/Shanghai`。
- 原始查询最大跨度建议 31 天；更长跨度必须走汇总或限制粒度。

### 9.2 KPI 定义

| `metricKey` | 公式 | 备注 |
| --- | --- | --- |
| `agent.inventory.total` | 当前作用域 `runtime_agent` 总数 | 同时返回 enabled、active config coverage |
| `execution.active.count` | `runtime_active_execution.expires_at > now()` 的根执行数 | `SUSPENDED` 不计入，另计等待交互 |
| `run.business.count` | 时间窗口内 `traffic_class=BUSINESS` 的根运行数 | 不统计 DEBUG/EVAL/REPLAY/内部委派 |
| `user.runtime.active.distinct` | 优先 `COUNT(DISTINCT global_user_id)` | 只统计可靠身份；返回覆盖率和 fallback 数，不错误混合 ID |
| `token.recorded.total` | `SUM(total_tokens)`，仅 usage status 可用的运行 | 返回完整/部分/未知运行数与 coverage |
| `run.technical.completion_rate` | `COMPLETED / (COMPLETED + FAILED + TIMED_OUT)` | CANCELLED、RUNNING、SUSPENDED 不进分母；名称不得写业务成功率 |

### 9.3 排行与趋势

- Agent TOP 默认按业务根运行数降序，最多 10 条。
- 每条同时返回完成数、调用数、活跃用户、已记录 Token、技术完成率和 P95 延迟；字段不可用时单字段标记缺失。
- 项目 TOP 默认按业务调用数排序；项目名称、负责人和接入心跳由 Capability 补齐。
- 使用趋势：窗口不超过 48 小时按小时；超过 48 小时按天。调用、Token 和活跃用户可切换，不用双轴堆叠制造误读。
- Token 分布的分母仅为已记录 Token；图例必须显示 coverage。
- 实时动态只展示状态、Agent/Workflow/项目快照、时间和耗时；不展示输入摘要、会话正文、Tool 参数或错误堆栈。

### 9.4 覆盖率统一定义

每个结果都可以携带：

```json
{
  "coverage": {
    "eligibleRecords": 12846,
    "observedRecords": 12120,
    "ratio": 0.9435,
    "quality": "PARTIAL",
    "notes": ["726 runs have unknown token usage"]
  }
}
```

前端显示规则：

- `ratio >= 0.95`：正常展示，可在详情显示覆盖率。
- `0.70 <= ratio < 0.95`：展示 `部分数据` 标识。
- `ratio < 0.70`：默认不做强结论，Widget 状态为 `PARTIAL` 或 `UNAVAILABLE`。
- 阈值由具体指标配置，不应在每个组件内散落。

## 10. Runtime 数据基础改造

### 10.1 业务流量分类

在 `runtime_run` 增加：

```sql
traffic_class VARCHAR(16) NOT NULL DEFAULT 'INTERNAL'
```

合法值：

- `BUSINESS`：Embed、Gateway、对外 API、业务 Automation、业务 A2A 根任务。
- `TEST`：DEBUG、WORKFLOW_STUDIO 等设计/调试流量。
- `EVAL`：EVAL、REPLAY。
- `INTERNAL`：AI Coding、Agent Delegation 和平台内部任务。

分类必须在入口创建根运行时确定并写入，不要在 Dashboard 查询时长期依赖 `CASE entry_type` 猜测。升级已有开发库时可以按 `entry_type` 做一次最佳努力回填，同时将历史覆盖状态标记为推断。

建议索引：

- `(traffic_class, started_at, status)`
- `(project_code, traffic_class, started_at)`
- 根据真实 `EXPLAIN` 再决定是否增加 Agent 维度索引，避免先堆大量索引。

### 10.2 活跃执行租约

新增 Runtime 所有表 `runtime_active_execution`：

```text
trace_id                PK
tenant_id
project_id
project_code
agent_id
workflow_id
run_type
entry_type
traffic_class
runtime_instance_id
started_at
last_heartbeat_at
expires_at
created_at
updated_at
```

行为：

- 根运行启动时插入或幂等更新。
- 执行中每 10 秒心跳一次，建议租约 TTL 35 秒。
- `COMPLETED/FAILED/CANCELLED/TIMED_OUT/SUSPENDED` 时删除租约。
- 节点崩溃后不需要清理任务即可自然过期。
- “正在工作”只统计未过期租约。
- “等待用户/审批”从 `runtime_run.status=SUSPENDED` 与 interaction 数据单独统计。

不要直接靠 `runtime_run.updated_at` 充当心跳，它是历史根事实，不应被周期性写放大。

### 10.3 Token 语义修复

建议在 `runtime_run` 增加：

```text
prompt_tokens BIGINT
completion_tokens BIGINT
reasoning_tokens BIGINT
cached_tokens BIGINT
total_tokens BIGINT
token_usage_status VARCHAR(16)  // COMPLETE / PARTIAL / UNAVAILABLE
```

迁移策略：

- `token_cost` 暂时保留用于现有代码兼容，但新代码不再把它解释为金额。
- 对历史 `token_cost > 0` 的记录可回填 `total_tokens=token_cost`、`token_usage_status=PARTIAL`。
- `token_cost=0` 的历史记录不能自动断言为真实 0，应标记 `UNAVAILABLE`，除非来源明确证明没有模型调用。
- 所有模型调用适配器逐步返回标准 Token Usage，由 Runtime 汇总到根运行。
- 金额不放在这些字段中，货币成本由未来 Model 用量账本负责。

### 10.4 可信身份

当前 Runtime 的部分路径把可信 `userId` 同时写入 `user_id`、`external_user_id` 和 `global_user_id`。实施活跃用户前应完成：

- Control 到 Runtime 的签名身份信封分别携带 runtime、external、global user ID。
- Runtime 只写入已验证存在的字段，不复制填充其他语义。
- Dashboard 返回 `globalIdentityCoverage`、`runtimeIdentityCoverage`。
- 平台级跨系统 DAU 只使用 `global_user_id`；项目内可以额外展示“活跃运行用户”，但必须标明口径。

### 10.5 业务 Outcome（后续）

在没有统一 Outcome 前，只展示技术完成率。未来可在 Runtime 根运行增加或建立投影：

```text
outcome_status    UNKNOWN / SUCCEEDED / FAILED / PARTIAL
outcome_code
outcome_evidence_ref
outcome_recorded_at
```

Outcome 必须由业务能力返回或异步确认，并关联可审计证据；不能由 LLM 文本“看起来成功”推断。

### 10.6 高频意图/问题（后续）

第一阶段可对 `runtime_tool_call_log.intent_type` 做覆盖率检查并提供“高频意图”。真正的高频问题需要 Runtime 所有的治理事件，例如：

```text
runtime_intent_event
  trace_id
  tenant_id / project_code / agent_id
  intent_key / intent_label
  query_fingerprint
  redacted_example        nullable and policy controlled
  classifier_version
  confidence
  occurred_at
```

要求：

- 默认不持久化原始问题。
- 聚类前做 PII/敏感字段脱敏。
- 支持租户留存策略和删除。
- 低置信度归入“未分类”，不强行归类。
- Dashboard 展示聚类标签、次数、趋势和覆盖率；查看样例需要单独权限和审计。

## 11. Control 布局数据模型

至少新增以下 Control 所有表，并同步修改 `sql/initV2.sql`、新增当日 upgrade SQL、更新 `sql/README.md` 和 `docs/architecture/service-table-ownership.md`。

### 11.1 `control_dashboard_layout`

个人当前布局，建议字段：

```text
id BIGINT PK
layout_key VARCHAR(64) UNIQUE
tenant_id VARCHAR(96)
owner_user_id BIGINT
dashboard_key VARCHAR(96)
view_mode VARCHAR(16)          // CONSOLE / SCREEN
breakpoint VARCHAR(24)         // DESKTOP / FHD 等稳定逻辑名
base_template_key VARCHAR(96)
base_template_version INT
schema_version INT
widget_catalog_version INT
revision BIGINT
layout_json MEDIUMTEXT
status VARCHAR(16)             // ACTIVE / ARCHIVED
created_by / updated_by
created_at / updated_at
```

唯一约束至少覆盖：`tenant_id + owner_user_id + dashboard_key + view_mode + breakpoint + status`。如果使用活动标记避免 MySQL NULL 唯一性问题，应沿用仓库现有生成列/active marker 模式。

### 11.2 `control_dashboard_template`

模板身份：

```text
template_key
name
dashboard_key
view_mode
scope_type       // SYSTEM / TENANT / ROLE
scope_key
status           // DRAFT / PUBLISHED / ARCHIVED
current_version
created_by / updated_by / timestamps
```

### 11.3 `control_dashboard_template_version`

不可变模板版本：

```text
template_id
version_no
schema_version
widget_catalog_version
layout_json
change_note
published_by
published_at
created_at
```

个人布局采用 copy-on-write：首次打开时读取最匹配的已发布模板；只有用户保存个性化布局时才创建个人行。模板升级不能静默覆盖个人布局，Bootstrap 只返回“有新模板可用”。

### 11.4 布局 JSON v1

```json
{
  "schema": "reachai.dashboard.layout.v1",
  "dashboardKey": "operations.overview",
  "mode": "SCREEN",
  "columns": 24,
  "rows": 22,
  "instances": [
    {
      "id": "agent-top-main",
      "widgetKey": "ranking.agent-top",
      "widgetVersion": 1,
      "x": 0,
      "y": 3,
      "w": 7,
      "h": 10,
      "config": { "limit": 5, "sortBy": "RUNS" }
    }
  ]
}
```

严禁在 JSON 中保存组件名、SQL、URL、权限、用户身份或密钥。权限和 Widget 元数据来自代码注册表。

## 12. 公共 API 设计

公共路径统一放在 Control：`/api/operations/dashboard`。

### 12.1 Bootstrap

`GET /api/operations/dashboard/bootstrap`

查询参数：

- `dashboardKey=operations.overview|operations.project`
- `mode=CONSOLE|SCREEN`
- `breakpoint=DESKTOP|FHD`
- `projectCode` 可选；项目 Dashboard 必填。

响应示例：

```json
{
  "schema": "reachai.operations.dashboard.bootstrap.v1",
  "serverTime": "2026-08-26T15:30:00Z",
  "timezone": "Asia/Shanghai",
  "scope": {
    "kind": "PLATFORM",
    "projectCode": null
  },
  "permissions": {
    "view": true,
    "editOwnLayout": true,
    "manageTemplates": false,
    "viewSensitiveSamples": false
  },
  "layout": {
    "source": "PERSONAL",
    "revision": 7,
    "etag": "7",
    "document": {}
  },
  "widgetCatalogVersion": 1,
  "availability": {
    "insight.question-clusters-top": {
      "state": "DISABLED",
      "reasonCode": "QUESTION_CLUSTERING_NOT_ENABLED"
    }
  }
}
```

Bootstrap 不需要把 Vue 组件、内部服务地址或全部指标数据发给浏览器。

### 12.2 批量查询

`POST /api/operations/dashboard/query`

```json
{
  "dashboardKey": "operations.overview",
  "scope": { "projectCode": null },
  "timeRange": {
    "preset": "TODAY",
    "timezone": "Asia/Shanghai"
  },
  "widgets": [
    {
      "instanceId": "agent-top-main",
      "widgetKey": "ranking.agent-top",
      "widgetVersion": 1,
      "config": { "limit": 5, "sortBy": "RUNS" }
    }
  ]
}
```

响应：

```json
{
  "schema": "reachai.operations.dashboard.query.v1",
  "requestId": "...",
  "asOf": "2026-08-26T15:30:00Z",
  "results": [
    {
      "instanceId": "agent-top-main",
      "widgetKey": "ranking.agent-top",
      "state": "READY",
      "freshness": {
        "status": "FRESH",
        "sourceAsOf": "2026-08-26T15:29:58Z"
      },
      "coverage": {
        "eligibleRecords": 12846,
        "observedRecords": 12846,
        "ratio": 1.0,
        "quality": "COMPLETE"
      },
      "data": {}
    }
  ]
}
```

服务端 Widget 状态固定为：

- `READY`
- `EMPTY`
- `PARTIAL`
- `UNAVAILABLE`
- `FORBIDDEN`
- `DISABLED`
- `UNSUPPORTED_VERSION`

客户端还可以有 `LOADING` 和网络级 `ERROR`，但不得把它们持久化。

### 12.3 保存布局

`PUT /api/operations/dashboard/layout`

- 请求头：`If-Match: "7"`
- Body：完整布局 JSON，不做局部坐标 PATCH。
- 成功：返回 revision 8，并设置 `ETag: "8"`。
- revision 冲突：`409 DASHBOARD_LAYOUT_REVISION_CONFLICT`。
- Widget 未注册、版本不支持、越界、碰撞无法解决或配置不合法：`422`，返回具体 instanceId。

### 12.4 重置布局

`POST /api/operations/dashboard/layout:reset`

只删除/归档当前用户当前 dashboard/mode/breakpoint 的个人布局，然后重新解析模板。必须审计，不删除模板。

### 12.5 后续模板管理 API

模板管理不阻塞第一版个人布局，可后续增加：

- `GET /api/operations/dashboard/templates`
- `POST /api/operations/dashboard/templates`
- `POST /api/operations/dashboard/templates/{key}/versions`
- `POST /api/operations/dashboard/templates/{key}/versions/{version}:publish`

仅具有模板管理权限的用户可调用。

## 13. Internal API 设计

### 13.1 Runtime

`POST /internal/runtime/operations/query`

一次请求传入需要的 Runtime 数据集，而不是每个 Widget 发一个 internal 请求。建议请求 `datasets`：

- `KPI_SUMMARY`
- `USAGE_TREND`
- `AGENT_RANKING`
- `PROJECT_RANKING`
- `TOKEN_DISTRIBUTION`
- `ATTENTION_SUMMARY`
- `RUNTIME_ACTIVITY`
- `INTENT_RANKING`

Runtime 在一个只读事务/一致的 `asOf` 下复用中间聚合，返回 typed records。请求最大 TOP N、时间跨度和项目数量必须有上限。

### 13.2 Capability

`POST /internal/capability/projects/summaries`

输入为 Control 已授权的 `projectCodes`，返回：

- 项目 ID、项目编码、名称、环境、负责人。
- 接入类型与最近同步时间。
- 实例数、最近心跳、心跳是否过期。
- 能力资产数量摘要。

不要返回项目密钥、扫描凭据或 `base_url` 中的敏感信息。

### 13.3 Knowledge（后续）

`POST /internal/knowledge/operations/query`

只返回聚合、脱敏后的检索覆盖和问题聚类，不返回 query/body/file name。Knowledge 未准备好时 Control 返回 Widget `DISABLED/UNAVAILABLE`，不返回空数组冒充“没有问题”。

### 13.4 Model（后续）

`POST /internal/model/usage/query`

返回模型实例/供应商维度的 Token 和成本聚合、币种、价格版本、usage coverage。禁止返回连接配置和 Prompt。

### 13.5 internal 安全

所有新增 internal POST 必须复用现有 HMAC 机制：

- 签名最终请求字节。
- 校验 body SHA-256、timestamp、nonce、source 和签名。
- 防重放存储必须多实例安全。
- 失败时 401/403，并确保业务查询未执行。
- Control 对下游设置独立超时并允许部分结果返回。

## 14. Widget 系统设计

### 14.1 前端代码注册表

建议类型：

```ts
interface DashboardWidgetDefinition {
  key: string
  version: number
  category: 'KPI' | 'TREND' | 'RANKING' | 'QUALITY' | 'PROJECT' | 'ACTIVITY'
  title: string
  description: string
  component: Component
  requiredPermissions: string[]
  supportedDashboards: string[]
  supportedModes: Array<'CONSOLE' | 'SCREEN'>
  dataQueryKey: string
  configSchema: DashboardWidgetConfigSchema
  grid: {
    console: WidgetGridConstraints
    screen: WidgetGridConstraints
  }
}
```

`component` 只存在于前端代码，不从服务端反序列化。`widgetKey` 一旦发布不得随意改名；改 schema 时提升 version 并提供迁移器。

### 14.2 初始 Widget 目录

P0/P1 应实现：

- `kpi.agent-inventory`
- `kpi.active-executions`
- `kpi.business-runs`
- `kpi.active-runtime-users`
- `kpi.recorded-tokens`
- `kpi.technical-completion-rate`
- `ranking.agent-top`
- `trend.usage`
- `ranking.project-top`
- `quality.attention`
- `projects.operations-cards`
- `distribution.token-by-project`
- `activity.runtime-stream`

可作为下一批：

- `ranking.workflow-top`
- `ranking.tool-top`
- `quality.failure-profile`
- `quality.latency-distribution`
- `quality.guard-decisions`
- `quality.waiting-interactions`
- `insight.intent-top`
- `insight.question-clusters-top`
- `distribution.model-usage`
- `cost.model-usage`

### 14.3 统一 Widget Frame

每个 Widget 统一处理：

- 标题、说明、刷新时间和数据口径提示。
- Loading skeleton。
- Empty、Partial、Unavailable、Forbidden、Disabled、Unsupported 状态。
- 编辑态拖拽把手、缩放把手、设置、复制和删除。
- 钻取事件。
- `ResizeObserver` 与图表 resize。
- Error Boundary，单个 Widget 崩溃不得让整页白屏。

业务 Widget 只消费标准结果，不直接散落调用 API。

## 15. 布局引擎设计

### 15.1 核心模型

```ts
interface DashboardPlacement {
  id: string
  widgetKey: string
  widgetVersion: number
  x: number
  y: number
  w: number
  h: number
  config: Record<string, unknown>
}
```

持久化只保存整数网格坐标。像素坐标、舞台缩放比例、drag offset 和 hover 状态均为运行时状态。

### 15.2 拖拽和缩放

- 使用 Pointer Events 和 `setPointerCapture`，统一鼠标、触控和手写笔。
- 拖拽开始时保存 `beforeSnapshot`，建立本地 Draft。
- Pointer 坐标先转换到虚拟舞台，再换算到网格。
- 候选位置必须经过 clamp、尺寸约束、碰撞和边界校验。
- 缩放第一版只提供右下角把手，降低误操作；后续可扩展四边。
- 拖动过程中使用 requestAnimationFrame 合并更新，避免每个 pointermove 重排。

### 15.3 碰撞规则

采用可预测的“向下推移”策略：

1. 移动物体先放入候选位置。
2. 与其相交的 Widget 按 `y、x、id` 稳定排序。
3. 依次向下推到第一个不冲突位置，递归处理后续碰撞。
4. `SCREEN` 中任一 Widget 超出第 22 行则候选无效，显示红色预览并禁止提交。
5. 不做自动横向交换，不在 drop 后大范围自动紧凑，避免用户布局跳变。
6. 删除后可显式点击“整理布局”执行纵向紧凑，而不是自动发生。

几何、碰撞和迁移函数必须是无 DOM 的纯函数，并做密集单元测试。

### 15.4 编辑历史

- Undo/Redo 保存最多 50 个布局快照。
- 一次完整 drag/resize 只形成一个历史步骤，不记录每帧。
- 添加、删除、复制、设置更新各形成一个步骤。
- 切换路由、刷新页面或退出编辑前存在未保存 Draft 时给出保护提示。

### 15.5 可访问性

- 编辑态 Widget 可聚焦。
- 方向键移动一格；`Shift + 方向键` 调整尺寸。
- Delete 请求明确删除；Ctrl/Cmd+Z 撤销，Ctrl/Cmd+Shift+Z 重做。
- 所有操作有中文 `aria-label` 和屏幕阅读器状态播报。
- Reduced Motion 下关闭发光扫描和大幅图表过渡。

## 16. 前端模块拆分

建议目录：

```text
ai-admin-front/src/
  api/operationsDashboard.ts
  types/operationsDashboard.ts
  views/dashboard/
    Dashboard.vue
    DashboardScreen.vue
    ProjectDashboard.vue
    ProjectDashboardScreen.vue
    components/
      DashboardToolbar.vue
      DashboardStage.vue
      DashboardGrid.vue
      DashboardWidgetFrame.vue
      DashboardWidgetCatalogDrawer.vue
      DashboardWidgetSettingsDrawer.vue
      DashboardDataState.vue
    composables/
      useDashboardBootstrap.ts
      useDashboardQuery.ts
      useDashboardAutoRefresh.ts
      useDashboardLayoutEditor.ts
      useDashboardDrilldown.ts
    engine/
      geometry.ts
      collision.ts
      history.ts
      layoutValidation.ts
      migrations.ts
    registry/
      widgetRegistry.ts
      defaultLayouts.ts
    widgets/
      kpi/
      ranking/
      trend/
      quality/
      projects/
      distribution/
      activity/
```

具体要求：

- `Dashboard.vue` 只负责 Console 壳、scope 和共享容器，不再包含所有业务计算与 CSS。
- `DashboardScreen.vue` 负责无 Chrome 大屏壳和 Fullscreen API，不复制 Widget。
- 新增 ECharts 作为统计图表实现；不要使用 G6 或 Vue Flow 模拟柱状图/折线图。
- 图表主题集中在 `dashboardChartTheme.ts`，颜色使用现有 CSS 变量解析后的值。
- API 层只访问 Control `/api/**`。
- `useProjectStore()` 与路由 scope 同步，但服务端仍独立做权限校验。

### 16.1 MainLayout 改造

当前 `LayoutMode` 为 `standard | project-workbench | edge-to-edge | studio`。新增 `screen`：

- `screen` 与 `studio` 一样隐藏 Sidebar 和 Breadcrumb，但样式独立。
- `layout-screen .main-content` 使用 `overflow: hidden`、全尺寸和深色背景。
- 不修改全局主题为 dark；大屏在自身作用域使用运营大屏 Token，退出后不影响用户管理台主题。
- 路由仍位于认证保护范围，避免另建绕过认证的公共路由。

### 16.2 前端测试文件

当前已有未跟踪 `Dashboard.test.ts` 和 `vitest.dashboard.config.ts`。实施窗口先检查其归属，再决定扩展或替换。最终至少覆盖：

- Bootstrap 的个人/模板布局解析。
- 所有 Widget 状态渲染。
- 批量查询和请求取消。
- 可见性暂停刷新。
- 几何、碰撞、边界、Undo/Redo、迁移。
- 保存成功、revision 冲突、取消编辑。
- 项目卡片和 Agent 排行钻取。
- 1920×1080 stage scale 与 pointer 反算。

Mounted Vue 测试使用 `happy-dom` 配置，不要用默认 Node 环境导致 `document is not defined`。

## 17. 后端模块拆分

### 17.1 Control

建议新包：

```text
com.enterprise.ai.control.dashboard
  api/
    OperationsDashboardController
    OperationsDashboardContracts
    OperationsDashboardExceptionHandler
  application/
    OperationsDashboardService
    DashboardQueryOrchestrator
    DashboardLayoutService
    DashboardTemplateResolver
    DashboardScopeAccess
  domain/
    DashboardLayoutDocument
    DashboardWidgetCatalog
    DashboardResultState
  persistence/
    DashboardLayoutEntity/Mapper
    DashboardTemplateEntity/Mapper
    DashboardTemplateVersionEntity/Mapper
  client/
    RuntimeOperationsClient/Gateway
    CapabilityProjectSummaryClient/Gateway
```

重点：

- 新建 `DashboardScopeAccess`，从 `PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE` 读取 `PlatformAuthenticatedSession`，复用现有权限模型。
- 建议权限：`dashboard:read`、`dashboard:layout:write`、`dashboard:template:manage`、未来 `dashboard:sensitive-sample:read`。
- 布局写入和重置写平台审计，审计只保存布局 ID、revision、Widget key 集合摘要，不保存数据结果。
- Query Orchestrator 按 owning service 合并 Widget 需求，最多各发一次下游请求。
- 任一下游失败只影响依赖它的 Widget；HTTP 总响应仍可 200，并在结果中标记 `UNAVAILABLE`。若认证/请求整体非法才返回 4xx。

### 17.2 Runtime

建议新包：

```text
com.enterprise.ai.runtime.operations
  api/RuntimeOperationsInternalController
  application/RuntimeOperationsQueryService
  application/RuntimeMetricCalculator
  domain/RuntimeOperationsContracts
  persistence/RuntimeOperationsMapper
  lease/RuntimeActiveExecutionLeaseService
```

要求：

- 聚合查询只访问 Runtime 自有表。
- 使用 `Clock` 和 `ZoneId` 可注入设计，测试“今日”边界。
- 输入限制：时间范围、TOP N、项目数、dataset 数、活动条数。
- 返回统一 `asOf`、coverage 和 SQL 数据时间。
- 活跃租约写入接入 `RuntimeRunLifecycleService` 的根运行生命周期；不得在 Dashboard 查询时临时修补状态。
- 不返回输入/输出摘要和 Tool 参数。

### 17.3 Capability

新增专用 internal 项目摘要 Controller/Service，复用项目与实例 Mapper，但响应只提供安全摘要。Control 不应为了方便直接访问 `capability_scan_project` 或 `capability_project_instance`。

### 17.4 Model 与 Knowledge

第一版可以只定义 Widget 可用性和接口契约，不强行实现假数据。真正实施时各自新增 owning service 查询，不把其表 Mapper 引入 Control 或 Runtime。

## 18. Model 用量与成本后续模型

货币成本需要 Model 服务拥有调用级无正文账本，建议未来表 `model_invocation_usage`：

```text
invocation_id
trace_id / span_id
tenant_id / project_code
agent_id / workflow_id
model_instance_id
provider_snapshot / model_name_snapshot
prompt_tokens / completion_tokens / reasoning_tokens / cached_tokens / total_tokens
usage_status
currency
estimated_cost
pricing_version
started_at / ended_at
```

要求：

- 不保存 Prompt、响应正文和密钥。
- Runtime 调用 Model 时传递签名/可信的 Trace 和项目上下文。
- 价格变更必须有版本，历史成本不能按当前价格重算后冒充原始账单。
- 页面文案区分“估算成本”和“供应商账单成本”。

## 19. 缓存、性能与可靠性

### 19.1 前端

- 一次刷新只发 Bootstrap（必要时）和一个 Batch Query。
- 取消过期请求，防止旧时间范围覆盖新结果。
- 自动刷新增加小幅 jitter，避免多个大屏同秒打满后端。
- 不可见页面暂停；恢复后立即刷新。
- Widget 数据 key 包含 scope、timeRange、timezone、widgetKey、config hash。
- 图表实例按需创建并在卸载时 dispose。

### 19.2 Control

- 当前态 KPI 缓存 10–15 秒。
- 趋势/排行缓存 30–60 秒。
- Cache key 必须包含 tenant、授权项目集合、时间窗口、时区、traffic class 和配置 hash。
- 下游各自设置超时；建议单服务 800–1200ms，总编排预算不超过 2 秒。
- 缓存中不得放跨用户未裁剪的敏感数据。
- 服务失败时可返回过期缓存，但 freshness 必须标记 `STALE`，并携带原始 `sourceAsOf`。

### 19.3 Runtime 查询

- 初期直接聚合 `runtime_run`，先用真实数据做 `EXPLAIN` 和耗时证据。
- 查询跨度、TOP N 和活动条数均有硬上限。
- 不在第一版盲目增加小时汇总表。
- 当真实数据量或 P95 证明原始聚合无法满足目标后，再由 Runtime 增加小时汇总投影；汇总表仍归 Runtime。

建议目标：

- 热缓存 Dashboard Query P95 < 800ms。
- 冷查询 P95 < 2s。
- 单次响应建议 < 1MB。
- 1920×1080 大屏连续运行 8 小时无明显内存增长。

## 20. 安全、权限与隐私

- `dashboard:read` 控制页面和 API，不只隐藏菜单。
- `dashboard:layout:write` 只允许修改本人布局。
- `dashboard:template:manage` 才能发布系统/角色模板。
- 数据权限独立于布局权限：用户保存了项目 Widget，不代表可以查询该项目。
- Control 根据登录会话解析租户和项目访问范围；忽略或拒绝越权 scope。
- Fullscreen 活动流默认不显示用户姓名、问题正文、Tool 参数、错误详情、Prompt 或响应。
- 如未来支持无人值守电视墙，使用短期、只读、范围固定、可撤销的 display token；不要把长期平台会话 Token 写入 URL 或本地配置。
- 布局 JSON 做大小、数量、深度、key、版本和配置 schema 校验，防止持久化 XSS/资源耗尽。
- ECharts tooltip 只渲染结构化文本，不使用不受控 HTML。

## 21. 分阶段实施顺序

### Phase 0：保护现场与冻结契约

交付：

- 核对 Dashboard staged/unstaged diff 和未跟踪测试。
- 在实现分支/工作树中只接管确认属于本任务的文件。
- 固化本文中的 Widget key、布局 v1、API v1 和指标口径。
- 记录当前数据库、服务和浏览器证据为 `NOT RUN`，不沿用旧验收。

门禁：没有确认重叠 WIP 前，不改 `Dashboard.vue`。

### Phase 1：数据基础与 BFF

交付：

- `traffic_class`、Token usage 状态、活跃执行租约。
- Control 三张布局/模板表。
- Runtime operations internal query。
- Capability project summaries internal API。
- Control Bootstrap、Batch Query、Layout Save/Reset。
- RBAC、乐观锁、部分失败和缓存。
- `sql/initV2.sql`、upgrade SQL、SQL README、table ownership 同步。

门禁：用真实 MySQL 对照 SQL 和 API 数值；只通过单元测试不算完成。

### Phase 2：真实数据默认大屏

交付：

- Dashboard 前端拆分。
- ECharts 主题和统一 Widget Frame。
- 六个 KPI、Agent TOP、趋势、项目排行/卡片、Token 分布、异常/待处理、活动流。
- Console、Screen、Project 四个路由视图。
- 所有状态、覆盖率和钻取。

门禁：生产构建、真实 API、浏览器 1366/1920/2560/4K 视觉和交互验收。

### Phase 3：自由布局

交付：

- Pointer Events 网格引擎。
- 拖拽、缩放、碰撞、边界、添加/删除/复制。
- Draft、保存/取消、Undo/Redo、重置、冲突处理和 schema migration。
- Widget 目录和设置抽屉。
- 个人布局服务端持久化与模板继承。

门禁：刷新后布局一致、双窗口 revision 冲突不丢数据、键盘可操作、大屏不越界。

### Phase 4：高级洞察

交付：

- 高频意图覆盖治理。
- 脱敏问题聚类。
- Model 调用用量与成本账本。
- 业务 Outcome。
- 如有真实遥测，再接入资源利用率。

门禁：隐私、安全、留存、数据覆盖和业务口径均有证据后才进入默认模板。

## 22. 文件级变更清单

实施窗口应预期触及以下范围，但开始前必须重新核对实际结构：

### 必改

- `ai-admin-front/src/router/index.ts`
- `ai-admin-front/src/views/layout/MainLayout.vue`
- `ai-admin-front/src/views/dashboard/**`
- `ai-admin-front/src/api/operationsDashboard.ts`
- `ai-admin-front/src/types/operationsDashboard.ts`
- `ai-admin-front/package.json` 和 lockfile（新增图表依赖时）
- `reachai-control-service/src/main/java/com/enterprise/ai/control/dashboard/**`
- `reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/operations/**`
- `reachai-capability-service/src/main/java/**/operations/**`
- `sql/initV2.sql`
- `sql/upgrade-YYYYMMDD-operations-dashboard.sql`
- `sql/README.md`
- `docs/architecture/service-table-ownership.md`

### 可能修改

- `reachai-runtime-service/.../runops/RuntimeRunLifecycleService.java`
- Runtime 各根入口创建 `runtime_run` 的服务。
- Control/Runtime/Capability internal auth client 与 route contract 检查器。
- 平台权限种子和角色绑定。
- Sidebar 菜单标题（“概览”改为“运营中心”需产品最终确认；路由可保持 `/dashboard`）。
- 部署配置中的 internal API timeout/cache 参数。

### 禁止顺带修改

- Workflow GraphSpec 语义和 Studio 画布。
- Agent Skill、A2A、API Market、Managed Executor 等并行 WIP。
- 登录页和全局 Glass 设计系统，除非大屏新增的共享 Token 有明确必要。
- 任何凭据、端口、数据库初始化策略和旧服务兼容逻辑。

## 23. 测试与验收矩阵

### 23.1 后端单元与契约

- 指标时间边界：上海时区今天、跨日、空窗口、自定义范围。
- Traffic class：业务、调试、Eval、Replay、内部委派严格隔离。
- 完成率：RUNNING/SUSPENDED/CANCELLED 不进入既定分母。
- Token：真实 0、未知、部分、完整四种情况。
- 用户：global identity coverage、缺失身份、项目内 fallback。
- 活跃租约：创建、续租、结束删除、暂停删除、节点崩溃过期、多实例。
- 排行：同分稳定排序、TOP N 限制、名称快照。
- BFF：Runtime 失败、Capability 失败、超时、过期缓存、部分结果。
- 权限：无登录、只读、布局写、模板管理、越权 projectCode。
- 布局：schema、Widget key、配置、边界、大小、revision 冲突。
- HMAC：无签、错签、过期、重放、篡改 body 均失败且不执行查询。

### 23.2 SQL

- 新库只执行 `sql/initV2.sql` 即具备全部新表、列和索引。
- 已有开发库 upgrade SQL 可重复执行或按项目幂等规范安全执行。
- MySQL 5.7 与 8 兼容。
- 升级后回读字段、索引、默认值和 owner 登记。
- 使用真实数据执行 `EXPLAIN`，记录关键查询扫描行数和耗时。
- 不允许新增跨服务表访问例外来快速实现 Dashboard。

### 23.3 前端单元

- Widget registry 重复 key 和不支持版本失败。
- Geometry/collision 使用纯函数覆盖边界和递归碰撞。
- Draft 保存/取消、Undo/Redo、Reset、Migration。
- Query dedupe、取消旧请求、visibility 自动刷新。
- READY/EMPTY/PARTIAL/UNAVAILABLE/FORBIDDEN/DISABLED 全状态。
- ECharts ResizeObserver 和组件卸载 dispose。
- Route scope 与项目钻取参数。

### 23.4 浏览器

至少验证：

- 1366×768 管理台。
- 1920×1080 大屏。
- 2560×1440 大屏。
- 3840×2160 大屏。
- 非 16:9 窗口安全留边。
- 浏览器缩放 80%、100%、125%、150%。
- 浏览器全屏进入/退出。
- 拖拽、缩放、碰撞、溢出拒绝、添加、删除、复制、取消、保存。
- 刷新后布局保持。
- 两个浏览器窗口产生 revision 冲突。
- 单个服务停止时相关 Widget 降级，其他 Widget 保持。
- 真实无数据、无权限和功能关闭时文案准确。
- 点击项目、Agent、失败、活动可进入正确钻取页。
- 控制台 0 error；warning 需逐条判定，不接受忽略关键 warning。

### 23.5 数据对账

为同一时间窗口准备受控运行样本：

- BUSINESS completed/failed/timed out。
- DEBUG、EVAL、REPLAY。
- 有/无 Token usage。
- 有/无 global user ID。
- 两个项目、多个 Agent。
- 一个有效活跃租约和一个过期租约。

分别执行数据库 SQL、Runtime internal query、Control public query，并逐项核对：调用数、完成率、用户、Token、TOP 排名、项目占比和活动流。HTTP 200 但数值不一致视为失败。

## 24. 建议验证命令

根据实际改动选择目标测试，最终至少执行：

```powershell
$env:JAVA_HOME='<JDK17_HOME>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -pl reachai-control-service,reachai-runtime-service,reachai-capability-service -am test

Set-Location ai-admin-front
npx vitest run --config vitest.dashboard.config.ts
npm run build

Set-Location ..
node scripts/check-service-table-ownership.test.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-internal-api-contracts.test.mjs
node scripts/check-internal-api-contracts.mjs
node scripts/check-frontend-public-api-routes.test.mjs
node scripts/check-frontend-public-api-routes.mjs
git diff --check
```

如果执行了服务部署或重启，再补：

- 构建产物新鲜度。
- 18601–18605 进程/端口所有权。
- 五服务 Health 与 Control 聚合 Health。
- 登录后的真实 Dashboard API。
- Playwright/人工浏览器全流程。

任何未执行的层级必须明确写 `PENDING / NOT RUN`，不能用 build/test 代替数据库、部署和浏览器验收。

## 25. 完成定义

只有同时满足以下条件，才可以声明“智能体运营大屏完成”：

1. 默认大屏与本文布局一致，统计信息为主，无大型无意义架构图。
2. 六个核心 KPI 使用本文口径，且有覆盖率/新鲜度。
3. 调试、Eval、Replay 和内部调用不污染业务统计。
4. “正在工作”来自未过期租约，不来自 Agent enabled 或陈旧 RUNNING。
5. Token 明确区分已记录、部分和未知，不显示虚假金额。
6. 项目和 Agent 支持真实钻取。
7. 单服务失败只影响依赖 Widget，不把页面清零或伪装成空数据。
8. 用户可以安全地添加、删除、拖拽、缩放、撤销、保存和重置布局。
9. 个人布局服务端持久化，并使用 revision 防止覆盖。
10. 权限在 API 层生效，布局不能扩大数据范围。
11. SQL baseline、upgrade、表 ownership、internal API 契约一致。
12. 后端测试、前端测试、构建、真实数据库对账和浏览器验收都有独立证据。

## 26. 明确延期项

以下内容不应阻塞第一版真实运营大屏，但必须保持 Widget 状态诚实：

- 用户问题正文聚类。
- Token 货币成本。
- 供应商账单对账。
- 业务 Outcome 成功率。
- CPU、内存、Pod 和 GPU 利用率。
- 租户级模板可视化管理后台。
- 无人值守 display token。
- 移动端大屏编辑。

## 27. 实施窗口可直接使用的开场指令

```text
请在当前 ReachAI 仓库根目录实施
docs/plans/reachai-agent-operations-dashboard-handoff.md。

先读 AGENTS.md 和交接文档，执行 git status --short，并重点核对
ai-admin-front/src/views/dashboard/Dashboard.vue 的 staged/unstaged diff，以及未跟踪的
Dashboard.test.ts、vitest.dashboard.config.ts。不要 reset、checkout、清理、提交、推送或覆盖并行 WIP。

按 Phase 0 → Phase 1 → Phase 2 → Phase 3 顺序推进。Control 必须作为前端公共 BFF；Runtime、
Capability、Knowledge、Model 坚守 owning service 表边界。生产页面不得使用 Mock 或固定假数据。

先交付 traffic_class、活跃执行租约、Token usage 状态、Dashboard 布局表、Runtime/Capability
internal API 和 Control Bootstrap/Batch Query/Layout API，再实现共享 Widget 的 Console/Screen 页面，
最后实现拖拽、自定义布局和模板继承。

每一阶段都独立报告源码、测试、SQL/数据库、运行服务、浏览器证据；未跑项写 NOT RUN。
遇到现有脏文件归属不清或需要扩大范围时停止并说明，不要自行覆盖。
```
