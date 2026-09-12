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
- Workflow 发布遵循 [`发布生命周期契约`](../docs/architecture/workflow-semantic-contract.md)：Studio 与版本页均传已读取修订，发布人由登录身份记录。版本页发生冲突时保留版本号/说明，提供刷新状态后重新检查的入口。回滚确认明确说明切换活动版本并保留编辑草稿；当前只提供全量发布。
- Workflow 普通编辑与 Studio 保存必须携带已读取的草稿修订，缺失时保留输入并提示重新加载，不自动覆盖。SDK、SYSTEM 和 AI_QUICK_ACCESS 定义只读展示；定义来源、创建入口和生命周期状态由 Workflow 维护。USER／AI_CODING 草稿可继续在 Studio 编辑，保持原创建入口。
- Workflow 调试依据 [会话提交与恢复契约](../docs/architecture/runtime-audit-attribution.md#调试会话的提交与恢复)：接纳后立即沿用现有存储保存会话标识。断流不自动重发执行；“重试”和重新打开面板查询原会话。进行中记录显示结果尚未确认，临时查询失败保留标识和上下文。Tooltip 不得拦截其内实际操作按钮的 Enter／Space。
- 调试会话到期时显示服务端保存的结果无法确认说明，引导核对运行记录和业务系统；“重试”仍只查询。恢复终态不能重新开放历史交互，错误提示中的操作按钮不能被长文案挤成竖排。
- 创建调试请求发出前保存当前账号／Workflow 的一次尝试标识，不保存输入内容；首个响应丢失时按此标识只读查询。尚未查到接纳记录不能据此认定未执行或自动重新创建。取得会话后转换为会话 ID 引用，显式新调试使用新尝试标识。
- 调试会话引用按已登录账号和 Workflow 分开保存，旧无账号引用不能恢复。退出登录或切换账号时立即清空调试输入、结果和传输实例；旧请求的迟到响应不能出现在新账号界面、清除另一账号引用或将旧输入重新发出。同账号切走再切回也必须丢弃此前响应，服务端所有者授权始终是最终边界。

## 导航与响应式

- Studio 校验与冲突恢复遵循 [工作副本异步结果归属](../docs/architecture/workflow-semantic-contract.md#23-studio-工作副本异步结果归属)：切换或重新加载文档后，旧响应及旧确认不得覆盖当前内容；同 ID 往返也须失效。覆盖本地内容的冲突按钮不默认聚焦，取消与 Escape 保留输入，正常显式确认仍可重新加载。

- 主导航按用户资产路径排序：能力目录 → Workflow → Agent；能力变化是能力目录内的例外处理与历史入口，不作为常驻审批导航。
- 实验室只承载尚未进入稳定主线的上下文、领域和诊断能力。
- 未完成跨页面验收的主题模式不展示为可选项；品牌色选择继续可用。
- 桌面端保留项目范围与侧栏折叠；窄视口必须保持主要内容和真实操作可达。路由标题中仍存在的旧英文值列入后续迁移，不在本契约中冒充已完成。

## 权限与反馈

- 缺少权限的菜单项默认隐藏，不能只靠禁用态制造可发现但不可执行的入口；403/接口鉴权由服务端与路由守卫兜底。
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
