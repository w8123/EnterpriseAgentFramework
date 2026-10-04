# ReachAI 管理端 UX 契约

## 产品上下文

- 受众：企业 Java 系统研发、AI 平台管理员、Workflow/Agent 构建者与运行治理人员。
- 核心任务：接入业务系统与能力、评审能力变化、编排 Workflow、配置 Agent、查看运行证据并治理权限。
- 产品语言：简体中文；框架与协议正式名保留 `Agent`、`Workflow`、`MCP`、`A2A` 等英文身份。
- 无障碍目标：WCAG 2.2 AA；键盘、焦点、非颜色状态提示和 200% 缩放为基础验收项。

## 业务依据

| 范围 | 权威来源 | 用途 |
|---|---|---|
| 产品定位与命名 | `../AGENTS.md`、`../docs/ai-memory/PROJECT-MEMORY.md` | Capability / Workflow / Agent 主链路与产品文案 |
| 权限投影 | `src/auth/platformAccess.ts`、`src/components/common/sidebarMenu.ts` | 菜单隐藏规则；服务端仍是授权边界 |
| 视觉系统 | `DESIGN.md`、`../docs/plans/前端Glass-Workbench设计系统与UI重构.md` | Glass Workbench 方向与运行时 token 映射 |
| 嵌入式交互 | `src/conversation/core/conversationTypes.ts`、`src/conversation/components/UnifiedInteractionRenderer.vue` | UI Request 渲染、提交与失败恢复 |

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Select/Listbox | 管理端使用 Element Plus `el-select`；Embed SDK 使用原生 `select` | `package.json`、`UnifiedInteractionRenderer.vue` | authored-admin / native-embed | 键盘、打开态、`npm run test:sdk` |
| Date | 管理端使用 Element Plus 日期组件；Embed SDK 接受浏览器原生日期控件 | `UnifiedInteractionRenderer.vue` | authored-admin / native-embed | 本地化、键盘、真实浏览器 |
| Form | Element Plus 表单与应用自有语义表单；应用负责验证文案 | `FilterBar.vue`、conversation components、页面测试 | filter / composer / interaction | `novalidate`、首个错误焦点、重复提交保护 |
| Scrollbar | 应用级全局样式 | `DESIGN.md`、`src/styles/index.scss` | 仅允许 stable gutter 等几何例外 | 严格审计、computed style、窄视口 |
| Toast | Element Plus `ElMessage` | 页面/领域 composable | success / warning / info / error | 状态测试与真实浏览器 |
| Dialog / Drawer | `AppDialog.vue` / `AppDrawer.vue` | `src/components/common/`、`src/styles/_overlays.scss` | 普通业务弹层；调试台保留非模态和内部滚动布局 | 共享容器、关闭事件、焦点、窄视口、滚动与固定页脚 |

原生 Select/Date 只属于无 Element Plus 依赖的 Embed SDK 变体；其弹层几何、语言与可访问行为明确交给浏览器/操作系统。管理端页面仍由 Element Plus 组件拥有弹层视觉与碰撞行为。

自动化、标准 Skill 管理／市场及 Studio 查询模板、调试、评测、源码弹层使用共享容器。定制 header／footer、关闭后清理、尺寸和非模态透传由所属页面保留；新增定制 header 时仍须提供可访问标题。页头检查按完整静态 class token 判断旧 `page-header`，不得误报共享 PageHeader 上的 `market-page-header` 等页面样式钩子。

## 交互与表单

- 看起来可点击的元素必须有真实动作；当前状态、范围和进度使用文本、状态芯片或语义列表，不伪装成按钮。
- 所有应用自有 `<form>` 使用 `novalidate`，错误由产品文案呈现；输入值在失败后保留。
- 文字输入区使用 `resize: none` 并提供足够高度或 autosize；`Ctrl/Cmd + Enter` 在 IME 组合输入期间不得提交。
- 长请求采用悲观提交：提交期间禁用重复动作，失败时保留上下文并提供原位重试。具体领域状态以对应 API 契约和页面 composable 为准。
- 业务方法试调用在 POST 前保存当前账号／项目／方法的调用 ID 与时间，不保存输入或结果。持久查询键不包含会话内的请求代数；刷新或重新打开仅查询原 ID，UNKNOWN／响应丢失提示可能已经执行。写入的新尝试必须新 ID 与新确认，请求失效和权限撤销仍清除敏感状态。
- API Console 的 POST/WRITE 仅支持已接纳的扁平标量 JSON，请求体复用业务方法的表单／JSON 编辑器。明确确认绑定目标、契约、来源、连接／凭据修订及完整输入；变化或刷新即失效，初始焦点落在取消。写操作不重试、不跟随重定向，HTTP 错误和 UNKNOWN 均不表示业务未执行。复用共享查询句柄，仅按稳定账号／项目／环境／API 保存 ID 和时间，重开与整页刷新只查询；显式新调用先警告上次可能生效，再准备新 ID 并重新确认。Studio 不开放写 API 的草稿真实试运行；已发布 Workflow 的受控 POST 按既有固定 pin、可信入口、风险／授权与确认机制执行，不借用 Console 的确认凭据。调用／查询错误使用原位安全反馈，平台 401 仍由共享层处理。
- API JSON body 区分可选字段缺省与显式 null：缺省省略，不可空字段的显式 null 在确认弹窗和派发前拒绝；false、0、允许的空串保持原值，不代填来源 default。敏感适配依据已接纳契约的 format: password／writeOnly: true 及既有名字规则，表单和 JSON 默认遮蔽；结果用本次已验证契约固定的内存敏感值集合脱敏，不信任客户端标记。此规则不改变 GET 或 Java 业务方法的 null 语义。
- Studio 发布请求显式使用 `errorFeedback: 'local'`，错误提示由发布领域 composable 负责，避免共享请求层先弹裸码或重复反馈；平台 401 的分类、会话清理与登录跳转仍由共享请求层处理，原错误继续返回。版本页等未选择本地反馈的请求保留全局默认行为。
- Workflow 发布遵循 [`发布生命周期契约`](../docs/architecture/workflow-semantic-contract.md)：Studio 与版本页均传已读取修订，发布人由登录身份记录。版本页发生冲突时保留版本号/说明，提供刷新状态后重新检查的入口。回滚确认明确说明切换活动版本并保留编辑草稿；当前只提供全量发布。
- Workflow 普通编辑与 Studio 保存必须携带已读取的草稿修订，缺失时保留输入并提示重新加载，不自动覆盖。SDK、SYSTEM 和 AI_QUICK_ACCESS 定义只读展示；定义来源、创建入口和生命周期状态由 Workflow 维护。USER／AI_CODING 草稿可继续在 Studio 编辑，保持原创建入口。
- Workflow 调试依据 [会话提交与恢复契约](../docs/architecture/runtime-audit-attribution.md#调试会话的提交与恢复)：接纳后立即沿用现有存储保存会话标识。断流不自动重发执行；“重试”和重新打开面板查询原会话。进行中记录显示结果尚未确认，临时查询失败保留标识和上下文。Tooltip 不得拦截其内实际操作按钮的 Enter／Space。
- 调试会话到期时显示服务端保存的结果无法确认说明，引导核对运行记录和业务系统；“重试”仍只查询。恢复终态不能重新开放历史交互，错误提示中的操作按钮不能被长文案挤成竖排。
- 创建调试请求发出前保存当前账号／Workflow 的一次尝试标识，不保存输入内容；首个响应丢失时按此标识只读查询。尚未查到接纳记录不能据此认定未执行或自动重新创建。取得会话后转换为会话 ID 引用，显式新调试使用新尝试标识。
- 调试会话引用按已登录账号和 Workflow 分开保存，旧无账号引用不能恢复。退出登录或切换账号时立即清空调试输入、结果和传输实例；旧请求的迟到响应不能出现在新账号界面、清除另一账号引用或将旧输入重新发出。同账号切走再切回也必须丢弃此前响应，服务端所有者授权始终是最终边界。

## 导航与响应式

- Studio 校验与冲突恢复遵循 [工作副本异步结果归属](../docs/architecture/workflow-semantic-contract.md#23-studio-工作副本异步结果归属)：切换或重新加载文档后，旧响应及旧确认不得覆盖当前内容；同 ID 往返也须失效。覆盖本地内容的冲突按钮不默认聚焦，取消与 Escape 保留输入，正常显式确认仍可重新加载。

- 主导航按用户资产路径排序：能力目录 → Workflow → Agent；能力变化是能力目录内的例外处理与历史入口，不作为常驻审批导航。
- 能力详情使用居中双栏检查面板：输入与返回优先，调用条件与来源常驻；窄屏合并为单一滚动区。打开时重读当前定义，关闭或切换能力后丢弃旧请求。列表的启用状态筛选仅筛选 `enabled`，不得称为可用状态。
- 能力的“使用位置”从 Control 项目授权接口查询，由 Capability 确认所属项目与真实标识，再复用 Runtime／MCP／A2A 的引用证据；仅完整证据允许展示“还没有被引用”。导航进入对应 Workflow、Agent 或 MCP 发布对象，不提供无上下文的 Workflow 列表按钮。
- 项目来源页只承担发现、同步与来源/语义证据展示，不要求人工复制到通用 Tool。按可信项目身份与平台读取权限导航到业务方法/API 所属目录；缺少身份时不猜单项资产。作者新建仅用 typed method/API picker；扫描 edit/toggle/test/promote/push/unpromote/module promote 不在普通 UI 请求中出现。旧交互 API binding 和旧引用原样保留并提示通过现有 API 节点调用、交互节点消费结果，再显式保存/发布；不静默改成业务方法，不创建另一条交互执行链。
- API 变化处理复用详情的字段比较与使用位置：已接纳/候选变化和跨来源冲突分别解释，缺少可比较契约或字段截断必须明确；contract hash 相同不代表来源已确认。sourceSetRevision 含来源与清单修订，展示变化或完整移除一个来源也可能使旧 pin 过期；用户重新校验并显式发布，不能通过文案判断绕过保护。接纳前展示已知引用及完整性，接纳只改变目录事实，不自动更改版本 pin、授权或 MCP/A2A 发布。Workflow 引用携带精确 versionId/nodeId，缺失证据不跳到当前版本；回草稿仅定位节点，修正/保存/校验/新版本发布和已有开放发布更新保持显式操作。
- 市场接入先进入所属项目 API，不生成原始 HTTP URL 节点。固定版本/Operation、已接纳、已配置和当前真实验证分别展示；技术 READY 文案为“来源已选择”。无认证、目录质量 VERIFIED 或已配置均不构成项目验证。响应媒体/成功状态和参数位置缺事实时明确阻断，目录地址仅作建议，不自动授予连接/凭据/出口/ACL。我的接入按项目读取并丢弃旧筛选请求；创建 Workflow 重读固定选择及 owner API，不能回退到最新目录版本。当前 proof 来自 Runtime Console/RunTrace 的 API、来源、契约、连接和凭据修订；失败/未知/旧修订不显示当前验证已确认。验证后作者使用稳定 API 引用和已接纳 schema 映射，仍需保存、只读试运行及显式发布；旧 raw HTTP_REQUEST+marketRef 保留 WIP 但最终发布/执行提示迁移。
- 实验室只承载尚未进入稳定主线的上下文、领域和诊断能力。
- 未完成跨页面验收的主题模式不展示为可选项；品牌色选择继续可用。
- 桌面端保留项目范围与侧栏折叠；窄视口必须保持主要内容和真实操作可达。路由标题中仍存在的旧英文值列入后续迁移，不在本契约中冒充已完成。

## 权限与反馈

- 缺少权限的菜单项默认隐藏，不能只靠禁用态制造可发现但不可执行的入口；403/接口鉴权由服务端与路由守卫兜底。
- 默认概览按数据域的实际响应处理读取受限：单个运营域或仅项目/健康域 403 不隐藏其他有效 200 内容和布局操作；成功空数组仍是已授权内容。卡片依赖由共享 widget 注册表声明，依赖拒绝域时原位说明读取受限，不冒充真实零值、接入失败或服务宕机。仅四个必要运营域全部 403 时使用受限任务入口；PROJECT 范围文案只在真实 grants 支持时使用，并复用 canonical sidebar menu 的真实授权生成业务方法/API/Workflow 入口。允许域继续自动和可见性刷新；已 403 域仅在显式刷新或会话身份/授权变化后重新检查。401、后端/网络故障、无可用项目或任务分别处理，旧身份或旧域请求的迟到响应不得覆盖当前界面。现有 GLOBAL 总览与精确项目拒绝保持不变；窄屏及键盘须保持新增状态、刷新和任务入口可达。
- 禁用态必须来自真实业务条件，并在相邻文案或 tooltip 中解释原因。
- 密钥、Token 和 Secret 不进入 URL、日志、Toast 或持久化浏览器文案。

## 当前迁移切片

- 已确立 owner：侧栏定位与菜单结构、应用级滚动条、应用自有表单校验归属、Embed 原生控件变体。
- 本轮迁移：能力资产导航、不可用主题入口、Dashboard 范围标签、SDK 只读进度、操作契约当前态、对话/交互表单。
- 未触碰的历史大页继续按风险逐步迁移；不得用一次性页面 CSS 复制新的系统级 owner。

## 验证

- 静态：Premium strict audit、`git diff --check`、项目 UI 契约脚本。
- 自动化：相关 Vitest、`npx vue-tsc --noEmit`、`npm run build`。
- 浏览器：桌面与窄视口检查侧栏层级、设置面板、Dashboard 范围标签、可见滚动条、键盘与 IME 提交；后端不可用时必须明确记录未验收的登录后业务流。
