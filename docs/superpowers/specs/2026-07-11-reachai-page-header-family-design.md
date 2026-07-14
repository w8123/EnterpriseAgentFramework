# ReachAI 页面标题栏家族设计规范

**日期：** 2026-07-11
**状态：** 已确认，首批页面已实施
**适用范围：** `ai-admin-front` 普通业务页面与项目工作台页面

## 1. 背景与目标

当前页面标题栏已经形成玻璃质感，但页面之间缺少可解释的差异规则：高度从紧凑标题到大型 Hero 不等，按钮有的顶部对齐、有的垂直居中，状态、标签、指标和模式切换也会在不同位置出现。同一页面还可能受到遗留样式影响，在“玻璃标题卡”和“裸标题”之间漂移。

本次目标不是让全部标题栏长得一样，而是建立一套“统一骨架、角色分层、领域轻识别”的标题栏家族：

- 页面角色决定结构与高度。
- 业务领域只改变图标、短强调线和轻量色彩氛围。
- 标题、说明、标签、元数据和操作区使用固定的信息顺序。
- 页面不再自行拥有标题栏高度、外层间距、背景或按钮布局。
- 保持工作台产品的信息密度、扫描效率和可重复操作体验。

## 2. 已确认的设计方向

采用混合方向：

- **结构按页面角色统一。**
- **颜色和图标按业务领域轻量区分。**
- **桌面端采用三档固定高度：`96px / 120px / 144px`。**
- **仅 Overview 标题栏允许最多两个迷你指标。**
- **四种标题栏共享当前品牌主题的材质背景；信息密度与内容结构仍由页面角色区分。**
- **右侧操作统一收进中性玻璃 Dock，主按钮使用加深后的品牌色，避免与材质背景融为一体。**

不采用以下方案：

- 所有页面使用完全相同的标题栏。
- 每个业务域重新定义标题栏结构。
- 页面继续通过内容自然撑高，导致高度不可预测。
- 每个页面自行决定是否把指标、模式切换或按钮放入标题栏。

## 3. 标题栏家族

### 3.1 Overview 概览型

**桌面高度：** `144px`

适用于领域入口、资产目录和管理总览，例如：

- 项目管理
- Agent 管理
- Workflow 管理

结构：

- 左侧领域强调线或领域图标。
- eyebrow、标题、说明、最多三个标签。
- 右侧可选最多两个迷你指标。
- 右侧操作轨道包含主操作和次级操作。
- 允许淡背景图、品牌光晕或轻装饰网格。

迷你指标不是通用 KPI 面板，只用于帮助用户在进入列表前快速判断总量或启用状态。更完整的指标仍放在标题栏下方的 `MetricStrip`。

### 3.2 Entity 实体身份型

**桌面高度：** `120px`

适用于用户正在操作某个明确对象的页面，例如：

- 项目详情
- 知识库详情
- 其他有稳定实体身份的详情页

结构：

- `48–56px` 实体图标容器。
- 实体名称、状态、环境标签。
- 统一的关键元数据列表。
- 右侧紧凑操作按钮，超出数量进入“更多”。
- 使用统一主题材质，但优先保证实体名称、状态和操作区的识别度。

该类型必须优先回答“我正在操作哪个对象”，不能退化成普通页面标题。

### 3.3 Workbench 任务工作台型

**桌面高度：** `144px`

适用于有明确任务流程或模式切换的工作台，例如：

- SDK 接入工作台
- 页面助手
- 后续同类治理或调试工作台

结构：

- 任务标题和项目/实体上下文。
- 右侧允许一个模式切换器，或一组流程主操作。
- `mode` 与 `summary` 互斥，不能同时出现。
- 允许淡背景图、领域渐变和较明显的工作台氛围。

标题栏只表达任务和模式，不承载步骤进度；步骤进度继续放在标题栏下方的流程区域。

### 3.4 Standard 标准任务型

**桌面高度：**

- 默认：`120px`。
- 显式 `compact` 且只有标题与不超过一个操作时：`96px`。

适用于：

- Tool 管理
- Tool ACL
- Tool 检索测试
- 设置页
- 其他普通列表、配置和测试页

结构：

- 标题、可选说明、可选少量标签。
- 右侧统一操作轨道。
- 默认不显示领域图标，不为装饰预留空位。
- 使用统一主题材质与左侧文字安全渐隐层，不额外引入页面私有背景图。

紧凑档是 Standard 的受控语义变体，不是任意高度开关。只有没有说明、标签、元数据且最多一个操作的页面可以声明 `compact`；自动化检查必须拒绝其他用法。

## 4. 页面归类基线

| 页面 | 标题栏类型 | 高度 | 特殊内容 |
| --- | --- | ---: | --- |
| 项目管理 | Overview | 144px | 标签、主操作；完整指标在下方 MetricStrip |
| 项目详情 | Entity | 120px | 实体图标、环境/状态、元数据、紧凑操作 |
| SDK 接入工作台 | Workbench | 144px | 手动接入 / AI Coding 模式切换 |
| Agent 管理 | Overview | 144px | 最多两个迷你指标、视图切换、主操作 |
| Workflow 管理 | Overview | 144px | 标签、主操作；完整指标在下方 MetricStrip |
| Tool 管理 | Standard | 120px | 说明、创建、刷新 |
| Tool 检索测试 | Standard | 120px | 副标题、用途说明、重建索引操作 |
| Tool ACL | Standard | 120px | 说明或决策上下文、规则操作 |

其他普通业务路由在实施计划中逐一分类。Workflow Studio、Agent Debug、嵌入式页面和全屏画布继续使用特殊工作区布局，不接入普通标题栏家族。

## 5. 公共信息架构

所有标题栏使用相同的三段式骨架。

### 5.1 Leading 区

- 可选实体图标、领域图标或短强调线。
- Entity 使用固定尺寸实体图标。
- Overview / Workbench 使用短强调线或领域图标。
- Standard 默认不渲染 Leading 占位。

### 5.2 Main 信息区

信息顺序固定为：

1. eyebrow
2. 标题与标签
3. 说明
4. 元数据

内容约束：

- 标题桌面端单行省略，窄屏最多两行。
- 标签最多直接展示三个，其余折叠为 `+N`。
- 说明最多两行。
- Entity 元数据使用统一键值与状态表达，不允许自由拼接成不可扫描的长文本。
- Standard 默认使用 `120px`；满足紧凑资格并显式声明 `compact` 时使用 `96px`。

### 5.3 Trailing 操作区

- 始终右对齐并垂直居中。
- 主操作放在最右侧，次级操作位于其左侧。
- 最多展示一个主操作和三个次级操作。
- 更多操作进入统一“更多”菜单。
- 图标按钮必须提供 Tooltip、`aria-label` 和可见焦点态。
- Overview 迷你指标位于操作按钮之前，最多两个。
- Workbench 模式切换器占用独立 `mode` 插槽，并与迷你指标互斥。

“返回”优先归属面包屑。仅沉浸式编辑或特殊流程允许标题栏返回按钮，普通页面不得同时在面包屑和标题栏重复返回入口。

## 6. 领域识别规则

标题栏表面、主要文字和按钮继续使用品牌与语义变量。领域差异只进入：

- Leading 图标或短强调线。
- eyebrow。
- 极淡背景光晕。

领域色调集中映射，不在页面内硬编码：

| Domain | 色调来源 |
| --- | --- |
| `project` | `var(--status-success)` |
| `agent` | `var(--brand-primary)` |
| `workflow` | `var(--status-info)` |
| `tool` | `color-mix(in srgb, var(--status-success) 55%, var(--status-info))` |
| `knowledge` | `color-mix(in srgb, var(--status-info) 70%, var(--brand-primary))` |
| `governance` | `var(--status-warning)` |
| `platform` | `var(--status-neutral)` |

主按钮不跟随领域色调，继续使用当前品牌主色，避免形成多套操作语义。

## 7. 公共组件契约

继续扩展现有 `src/components/common/PageHeader.vue`，不创建按业务域命名的平行标题栏组件。

建议公共属性：

```ts
type PageHeaderVariant = 'overview' | 'entity' | 'workbench' | 'standard'
type PageHeaderDomain =
  | 'project'
  | 'agent'
  | 'workflow'
  | 'tool'
  | 'knowledge'
  | 'governance'
  | 'platform'

interface PageHeaderProps {
  variant: PageHeaderVariant
  domain: PageHeaderDomain
  title: string
  eyebrow?: string
  description?: string
  compact?: boolean
  backLabel?: string
  showBack?: boolean
}
```

受控插槽：

- `leading`
- `tags`
- `meta`
- `summary`
- `mode`
- `actions`

变体限制：

- `summary` 仅 Overview 可用，最多两个 `HeaderMiniStat`。
- `mode` 仅 Workbench 可用。
- `summary` 和 `mode` 互斥。
- `compact` 仅 Standard 可用，并要求没有说明、标签、元数据且操作不超过一个。
- 页面不得通过运行时 `class`、内联 `style` 或私有深层选择器覆盖公共高度、padding、表面、圆角和操作轨道。

新增三个小型公共组件：

- `HeaderMiniStat`：概览型迷你指标。
- `HeaderMetaList`：实体型元数据列表。
- `HeaderModeSwitch`：工作台模式切换。

这些组件只拥有布局与表现，不拥有 API、store、路由或业务事件逻辑。

## 8. 设计变量

在现有布局变量中增加：

```css
--layout-page-header-height-compact: 96px;
--layout-page-header-height-standard: 120px;
--layout-page-header-height-emphasis: 144px;
--layout-page-header-leading-size: 52px;
--layout-page-header-action-gap: 8px;
--layout-page-header-tag-gap: 8px;
--layout-page-header-art-opacity: 0.92;
```

继续复用：

- `--layout-page-header-padding-block`
- `--layout-page-header-padding-inline`
- `--radius-xl`
- 现有玻璃表面、文本、边框、阴影和品牌变量

页面不得声明新的标题栏高度变量或遗留别名。

## 9. 响应式规则

### 大于 900px

- 严格使用三档固定高度。
- Trailing 区保持单行。
- 标题桌面端保持单行。

### 761–900px

- 固定高度改为相同数值的 `min-height`。
- Trailing 区允许有限换行。
- 标题最多两行。

### 小于等于 760px

- Main 信息区占满第一行。
- summary、mode 和 actions 进入第二行。
- 主操作保持可见并保留最高视觉优先级。
- 迷你指标空间不足时移动到标题下方，不缩成不可读的小块。
- 不允许页面产生 document 级横向溢出。

## 10. 迁移策略

1. 扩展公共 `PageHeader` 与标题栏变量，建立自动化契约。
2. 迁移已经使用 `PageHeader` 的 Standard 页面。
3. 迁移项目管理、Workflow、Agent 管理等 Overview 页面。
4. 迁移项目详情和其他 Entity 页面。
5. 迁移 SDK 接入、页面助手等 Workbench 页面。
6. 扫描所有普通业务路由，清理裸标题、私有 `.page-header`、`.page-hero`、Hero 高度和按钮定位样式。
7. 保持 Studio、Debug、Embed 等例外工作区不变。

迁移不得改变 API 调用、store、事件处理、表单校验、路由路径、用户可见业务文案和运行时行为。

## 11. 自动化与浏览器验收

### 自动化契约

- 每个普通业务页面必须声明一种标题栏类型。
- 禁止页面重新拥有标题栏高度、外层 margin、padding、背景和操作轨道布局。
- 禁止页面对公共标题栏进行运行时 `class/style` 覆盖。
- 验证 `summary`、`mode` 的变体限制与互斥关系。
- 验证三档高度和响应式 token。
- 验证例外工作区不渲染普通标题栏。

所有检查必须有 mutation/self-test，证明删除变体、改错高度、恢复私有 margin 或将 MainLayout/页面职责重新耦合时会失败。

### 浏览器验收

重点覆盖本次九张截图对应页面，并抽查其他领域页面：

- 桌面端标题栏高度精确为 `96 / 120 / 144px`。
- 标题、Leading 和操作区垂直对齐稳定。
- 主操作顺序一致。
- Overview 迷你指标数量不超过两个。
- 900px、760px 下无裁切、遮挡或 document 横向溢出。
- 浅色/暗色、侧栏展开/折叠组合不改变标题栏结构。
- 键盘焦点、Tooltip 和可访问名称可用。

## 12. 非目标

- 不重新设计页面主体卡片、表格、筛选器或弹窗。
- 不改变面包屑和此前确认的页面外层间距契约。
- 不修改业务数据结构或接口。
- 不在本轮同步 Figma；先完成前端公共标题栏系统与真实页面验收，再决定是否同步为 Figma 组件库。

## 13. 完成标准

- 普通业务页面全部归入已确认的标题栏家族或明确列入例外。
- 截图中的标题高度、按钮位置和同页样式漂移问题消失。
- 页面之间具有明确层次，但视觉上仍属于同一套 ReachAI 玻璃工作台系统。
- 公共组件、tokens、自动化检查和浏览器证据共同成为唯一事实源。

## 14. 分批实施结果

首批已接入公共标题栏家族的页面：

- Overview：项目管理、Agent 管理、Workflow 管理。
- Entity：项目详情；已有公共标题栏使用方继续按 Entity 归类。
- Workbench：SDK 接入工作台、创建页面助手。
- Standard：Tool 管理、Tool 检索测试、Tool ACL。

公共实现包括 `PageHeader`、`HeaderMiniStat`、`HeaderMetaList`、`HeaderModeSwitch`、三档高度 token、七套品牌材质映射与独立的标题栏家族契约检查。后续普通业务页面按同一契约逐批迁移，不再增加页面私有标题卡组件。

第二批已接入并强化信息层级的页面：

- Overview：知识库管理、模型中心；标题栏包含用途说明与两个迷你指标。
- Workbench：文件入库、MCP 接入向导；标题栏表达任务目标，步骤和进度继续留在主体区域。
- Standard：Tool 检索测试、Tool ACL、MCP 暴露白名单、MCP Client、MCP 调用流水、A2A 暴露 Agent、A2A 会话监控。

Standard 页面只要承载明确的管理、测试或治理任务，就使用 `120px` 并提供 eyebrow 与 description；仅真正只有标题和一个动作的轻任务页才使用 `96px compact`。标题栏 action dock 不承载输入框、下拉框、日期选择器等筛选控件，这些控件统一下沉到标题栏后的控制面板。

第三批覆盖剩余知识、模型、用户与治理运维页面：

- Knowledge：召回测试实验室、业务索引管理使用 enriched Standard；业务索引详情、文档段落运营使用 Entity，并把编码、状态、来源与统计放入 tags / meta。
- Model：模型调试台保留 edge-to-edge 工作区，使用 `96px compact` Standard，不用大标题压缩对话空间。
- Platform Users：平台用户、业务用户、认证源统一使用 enriched Standard；刷新使用图标按钮，主要创建操作保持最右。
- Governance：领域定义使用 enriched Standard；领域归属画布与上下文治理使用 Workbench；分类器测试使用 compact Standard；RunOps 使用 Overview；运行详情使用 Entity。

Overview 页面允许把高密度指标继续留在标题栏下方的指标带中，不强制塞入 `HeaderMiniStat`。Entity 页面用 tags / meta 表达对象身份，Workbench 页面强调当前治理任务；筛选输入、Trace 定位和测试参数始终属于标题栏下方的控制层。

### 可复用滚动收起

长列表型 Overview 页面可以接入公共滚动收起能力：

- `useCollapsiblePageHeader` 统一监听主内容区、页面内部表格滚动区和鼠标滚轮。
- 向下滚动或滚轮超过阈值后，`PageHeader` 进入 `collapsed` 状态，只保留主标题与操作区。
- `CollapsibleHeaderRegion` 统一动画收起标题栏下方的指标带，不允许页面重新实现私有 `.is-compact` 规则。
- 回到顶部并向上滚动时恢复完整标题、标签、说明和指标带。
- 收起状态变化前后必须通知列表页面重新计算表格可用高度。

首批复用页面为项目管理和 Workflow 编排列表。其他具有“标题栏 + 指标带 + 长列表”结构的 Overview 页面可以按同一组合接入。
