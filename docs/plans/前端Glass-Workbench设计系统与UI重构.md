# 前端 Glass Workbench 设计系统与 UI 重构实施方案

本文档是 ReachAI 管理端（`ai-admin-front`）本轮 UI 重构的事实源，包含两部分内容：

1. **设计语言与前端设计系统规范**（长期有效，落地后持续维护）。
2. **分阶段实施计划与验收标准**（本轮重构的执行依据；全部落地后，阶段清单可以裁剪，规范部分保留）。

Figma 事实源：[Glass Workbench 设计稿](https://www.figma.com/design/dLSJOki5yBoBeEx2GEZHU5/Untitled)

- `基础风格` 页：公共规范、组件、图标、样式基线（Logo PNG Assets、Icon Background Styles、Icon Components、Foundations、Core Components、Layout Templates、Component Inventory、Component Masters、Glass Workbench Language、ReachAI 基础风格）。
- `典型页面` 页：标准项目详情等样板稿。
- `系统页面重绘 / v1` 页：已完成重绘的系统页面（含 56 个已同步 logo 居中的侧栏副本）。

---

## 1. 设计方向（已确认，不再讨论）

### 1.1 Glass Workbench 主体风格

ReachAI 是企业级 AI 能力中台，UI 是**工作台型 B 端产品**，不是营销页、不是炫光大屏、不是普通后台模板。

必须遵守：

- 浅蓝灰背景 + 柔和蓝/青背景光。
- 轻玻璃卡片、薄边界、克制阴影。
- 信息密度偏 B 端工作台，适合长时间工作。

必须避免：

- 大面积深色。
- 过度渐变。
- 营销页式大 hero。
- 按钮感过重的导航选中态。

### 1.2 主题策略

- **浅色 Glass Workbench 是默认主体验**。
- 暗色降级为兼容模式：本轮只保证"不坏"（无白底黑字、无对比度崩坏），不做精修。
- 品牌色换肤能力保留：现有 7 套 `data-brand` 色板（科技紫 / 极光青 / 星云紫 / 珊瑚玫 / 翡翠绿 / 日冕金 / 深海蓝）在新体系下必须全部可用。

### 1.3 范围与非目标

- 本轮范围：Token 基线、布局壳（侧栏 + 顶栏）、公共组件、项目详情样板页。
- 后续批次：其余系统页面按 Figma `系统页面重绘 / v1` 逐页迁移。
- **非目标**：Workflow Studio / Agent Studio 相关页面（`isStudioPage` 分支）本轮完全不动；暗色精修不做；不引入新 UI 框架，继续基于 Element Plus + SCSS + CSS 变量。

---

## 2. 代码现状盘点（重构动机）

以下是当前代码事实，作为各阶段改造的依据。

### 2.1 主题文件 `src/styles/theme.scss`（约 1770 行）

- `:root` 直接承载**暗色**值（`--bg-primary: #0a0a0f` 等），浅色靠 `[data-theme="light"]` 整块覆盖。任何漏写 light 覆盖的样式会静默渲染成暗色——与"浅色优先"目标相反。
- `[data-theme="dark"]` 和 `[data-theme="light"]` 各有约 700 行 Element Plus 组件级覆盖，结构重复、只是色值不同。
- 文件底部的 `data-brand` 品牌层已经具备玻璃语义原料：`--brand-page-bg`、`--brand-glass-bg`、`--brand-glass-card-bg`、`--brand-soft-bg`、`--brand-decorative-grid` 等，可直接作为原语层沿用。

### 2.2 全局样式 `src/styles/index.scss`（约 1100 行）

- 已有 `.page-container`、`.page-header`、`.section-card`、`.glass-card`、`.filter-bar`、`.card-grid` 等全局工具类，部分引用 token、部分硬编码 `rgba(255,255,255,…)` 暗色值。
- 重构时需要审计：工具类保留但值全部走语义 token。

### 2.3 布局壳 `src/views/layout/MainLayout.vue`（约 1230 行）

- 侧栏当前是**深色**玻璃（`linear-gradient(180deg, #07111f …)`），并且在基础样式之上又叠了一层带大量 `!important` 的 "SDK wizard aligned" 皮肤（`.main-layout:not(.studio-shell) .sidebar`），两套背景定义互相覆盖。
- 菜单结构约 300 行模板硬编码；`activeMenu` 是几十行的 `if (path.startsWith(...))` 链。
- 选中态是渐变色块 + 发光投影 + 左侧光条，是"按钮感"，不符合"导航定位感"要求。
- Logo 使用 `/reachai-icon.svg`（SVG），且 `logo-icon-wrap` 给容器加了投影和内描边环。
- 注册中心页面单独走 `registry-shell` 浅色皮肤特例（浅色顶栏、独立按钮样式）——这是没有统一浅色基线时的局部先行试验，本轮结束后应删除。
- 顶栏面包屑已从 `route.meta.breadcrumb` / `route.meta.title` 渲染，机制可复用，但缺"返回"按钮，且大多数路由没配 `breadcrumb` meta。

### 2.4 项目详情页三件套

- `src/views/registry/RegistryProjectDetail.vue`（515 行）：逻辑层已拆成六个 composable（`useRegistryProjectDetailData/Actions/UiState/Navigation`、`useRegistryProjectAiCodingAccess`、`useRegistryProjectWorkbench`）+ `registryProjectDetailViewModel.ts`，本轮基本不动逻辑。
- `styles/RegistryProjectDetail.scss`（1195 行）+ `RegistryProjectDetail.global.scss`（215 行）：所有视觉逻辑堆在页面级 SCSS 里，是"设计系统缺失"的典型样本。
- 模板问题：返回按钮内嵌在 hero 卡片里；`health-card`（项目接入健康）价值低要删；"清理离线 / 刷新实例"是带文字按钮；心跳时间是绝对时间格式。

### 2.5 公共能力缺口

- `src/components/` 仅约 25 个组件，`components/icons/` 只有 2 个图标组件；无 PageHeader、无图标体系、无相对时间工具（全仓库无相对时间实现，`formatHeartbeatDisplay` 是项目详情自己的 viewModel 函数）。
- `public/` 只有 SVG logo（`reachai-icon.svg` 等），**没有 PNG logo 资产**；且存在两个 GBK 乱码文件名的 SVG 需要清理。
- `src/main.ts` 全局引入了 `element-plus/theme-chalk/dark/css-vars.css`，Element Plus 暗色依赖 `html.dark` class（`useTheme.ts` 已同步维护该 class）。
- `useTheme.ts` 默认主题为 `'dark'`。
- 语义变量（`--bg-card`、`--border-glass`、`--text-primary` 等）已被 25+ 个视图直接引用——**变量名必须保留，只换值**，这是未迁移页面不坏的关键。

---

## 3. 目标架构

### 3.1 Design Token 三层模型

Token 全部为 CSS 变量，SCSS 只做组织，不做颜色计算。文件组织：

```
src/styles/
  tokens/
    brand.scss        # 原语层：7 套 data-brand 色板（现有内容迁入，基本不改）
    semantic.scss     # 语义层：Glass Workbench 语义变量，:root = 浅色基线
    dark.scss         # 暗色覆盖层：[data-theme="dark"] 重定义语义层
  element-plus.scss   # 组件映射层：--el-* 从语义层推导（只写一份）
  index.scss          # 全局工具类（审计后保留，值全部走语义层）
  theme.scss          # 过渡期入口，@use 上述文件；迁移完成后可移除
```

**原语层（primitive）**：现有 `data-brand` 的 7 套色板原样保留，`--brand-primary`、`--brand-*-rgb`、`--brand-page-bg`、`--brand-glass-bg`、`--brand-glass-card-bg`、`--brand-soft-bg`、`--brand-decorative-grid`、`--brand-primary-gradient` 等继续作为换肤基础。品牌层与明暗模式解耦的现有设计（`data-brand` 管品牌色、`data-theme` 管表面和文字）保持不变。

**语义层（semantic）**：这是本轮新落的一层，对应 Figma `基础风格` 页的 Glass Workbench Language / Foundations。`:root` 直接写浅色值（基线倒置），变量清单：

| 类别 | 变量 | 说明 |
| --- | --- | --- |
| 页面背景 | `--bg-primary` `--bg-secondary` `--bg-tertiary` | 沿用现有名，值换成浅蓝灰基线；页面级背景光引用 `--brand-page-bg` |
| 玻璃表面 | `--surface-glass` `--surface-glass-strong` `--bg-card` `--bg-card-hover` | `--bg-card` 沿用现有名保证兼容；新增 `--surface-*` 对应 Figma 玻璃卡片两档 |
| 边界 | `--border-glass` `--border-glass-hover` `--border-divider` | 沿用现有名 + 新增分隔线 |
| 阴影 | `--shadow-card` `--shadow-card-hover` `--shadow-pop` | 克制阴影三档；暗色的 glow 类变量（`--glow-*`）保留但浅色下弱化 |
| 文本层级 | `--text-primary` `--text-secondary` `--text-muted` | 沿用现有名 |
| 状态色 | `--success-color` `--warning-color` `--danger-color` `--info-color` | 沿用现有名 |
| 图标背景 | `--icon-bg-brand-solid` `--icon-fg-brand-solid` `--icon-bg-soft-glass` `--icon-fg-soft-glass` `--icon-bg-neutral` `--icon-fg-neutral` `--icon-bg-danger-soft` `--icon-fg-danger-soft` | 对应 Figma Icon Background Styles 四种变体，成对定义背景/前景 |
| 间距 | `--space-1` 至 `--space-8`（4/8/12/16/20/24/32/40px） | 新增；页面留白、卡片内边距统一走这套 |
| 圆角 | `--radius-sm` `--radius-md` `--radius-lg` `--radius-xl` | 沿用现有名 |
| 动效 | `--transition-fast` `--transition-normal` `--transition-slow` | 沿用现有名 |

命名规则：**已被视图引用的变量名一律不改**（`--bg-card`、`--border-glass`、`--text-*`、`--radius-*` 等），只把 `:root` 的值从暗色换成 Glass Workbench 浅色；新概念（surface、icon-bg、space）才用新名。这样 25+ 个未迁移视图自动获得浅色基线，不需要逐个改。

**组件映射层（Element Plus）**：`--el-*` 变量只写一份，从语义层推导（例如 `--el-card-bg-color: var(--bg-card)`）。暗浅色差异全部收敛到语义层，删除现有暗/浅两份各 700 行的重复覆盖。确实无法用变量表达的结构性覆盖（如表格固定列不透明底）保留，但两个主题合并成引用语义变量的一份。

**暗色覆盖层**：`[data-theme="dark"]` 只重定义语义层变量，不再写组件级选择器（除非回归发现必须保留的结构性补丁）。Element Plus 的 `dark/css-vars.css` 继续依赖 `html.dark` class，`useTheme.ts` 的 class 同步逻辑不变。

### 3.2 公共组件（本轮只做这五个）

新组件统一放 `src/components/common/`，图标资产放 `src/components/icons/`。原则：先做有明确消费方的高复用组件，不造大而全 UI kit。GlassCard 之类的容器组件本轮**不**抽象，用 token + `index.scss` 工具类支撑，等第二、三个页面迁移出现重复模式后再决定。

#### AppSidebar（统一玻璃侧栏）

- 从 `MainLayout.vue` 拆出独立组件 `src/components/common/AppSidebar.vue`。
- 视觉：一个统一的浅色玻璃侧栏（`--surface-glass` + 薄边界），不是多个白卡片拼接；删除深色渐变背景、网格发光、`!important` 皮肤层。
- 图标弱化为导航辅助（无独立底色块），文字负责结构。
- 选中态是**导航定位感**：左侧细指示条 + 文字变主色 + 极浅底色（`rgb(var(--brand-primary-rgb) / 0.08)` 量级），删除渐变色块、发光投影。
- 菜单结构改为配置数组（`src/components/common/sidebarMenu.ts`，含 index/label/icon/children），模板用 `v-for` 渲染；折叠态行为保留。
- Logo 区：使用 PNG logo（见 3.4），logo 图标 + 光晕与 "ReachAI / 企业AI智能体中台" 主副标题**垂直居中**对齐。

#### PageHeader + 外置面包屑

- 顶部导航统一为：`< 返回   首页 / 注册中心 / 项目管理 / 项目详情`，位于**页面最顶部（顶栏区域）**，不放在标题卡片里。
- 实现：复用现有 topbar 面包屑机制，在 `breadcrumb-area` 增加返回按钮（有上级时显示，`router.back()` 或 meta 指定的目标路由）；为本轮涉及的路由补齐 `meta.breadcrumb` 配置（当前几乎只有 EmbedOpsMonitor 配了）。
- `PageHeader.vue`：统一的页面头卡片（标题、标签、meta 行、右上动作区插槽），供项目详情、API 管理等页面复用。注意与 `index.scss` 现有 `.page-header` 工具类名冲突——组件落地时全局类改名或删除，避免样式互相干扰。

#### AppIcon（公共图标组件）

- `src/components/common/AppIcon.vue`，props：
  - `name`：图标名，映射到 `src/components/icons/` 下的线性 SVG 组件（按需从 Figma Icon Components 导出补充）。
  - `variant`：`brand-solid | soft-glass | neutral | danger-soft | none`，映射语义层的 `--icon-bg-* / --icon-fg-*` 成对变量。
  - `size`：图标容器尺寸档位。
- 图标 SVG 规范（对应 Figma `基础风格` 页 Icon Components）：
  - 形状本身是**透明线性 SVG**，`stroke="currentColor"`、`fill="none"`，不烙任何颜色/背景/阴影进文件。
  - 统一 24 viewBox，图形内容控制在中央约 20×20 安全区内，**齿轮类图标留足安全边距防裁切**；安全边距在导出规范和组件容器 padding 里解决，不逐个图标文件打补丁。
- 颜色、背景、圆角、阴影全部由主题变量控制，换品牌色时图标背景自动跟随。

#### IconActionButton（图标动作按钮）

- 图标按钮 + 强制 tooltip（`aria-label` 同步），供"清理离线 / 刷新实例 / 编辑项目"等纯图标动作使用。
- 支持 `danger` 语气和 loading/disabled 状态；徽标数字（如"清理离线（3）"）转为 tooltip 文案或角标。

#### RelativeTime（相对时间）

- `src/utils/relativeTime.ts`：纯函数 `formatRelativeTime(input)`，输出 `X 秒前 / X 分钟前 / X 小时前 / X 天前 / X 年前`（未来时间输出 `X 秒后` 等；无效输入输出 `-`）。
- `src/components/common/RelativeTime.vue`：包一层组件——hover tooltip 显示绝对时间（B 端排查问题需要精确值），内置定时刷新（分钟内每秒、小时内每分钟刷新），避免"3 秒前"停在页面上失真。
- 项目详情的 `formatHeartbeatDisplay` 迁移到该公共能力；"最近上报 / 最近心跳"等时间字段统一替换。

### 3.3 页面规范（所有重绘页面必须遵守）

1. 顶部结构统一：外置返回 + 面包屑（顶栏）→ PageHeader 页面头卡片 → 内容区。breadcrumb / 返回**不进卡片**。
2. 各页面顶部卡片样式统一走 PageHeader，不允许页面私有 hero 变体。
3. 卡片、表格、表单样式只消费语义 token 和全局工具类，页面级 SCSS 只写布局（grid/间距/响应式），不写颜色和阴影字面量。
4. 卡片内不加三级小状态标题（已确认过重）。
5. 时间字段默认用 RelativeTime；纯图标动作用 IconActionButton。
6. 图标一律走 AppIcon 四种背景变体，不在页面里手写图标底色。

### 3.4 Logo 资产规范

- 使用自有 **PNG logo**（来源：Figma `基础风格` 页 Logo PNG Assets / Logo PNG Source），不再使用 SVG logo。
- PNG 保持透明背景；**容器不得叠加背景色、投影环**（现有 `logo-icon-wrap` 的 box-shadow + 内描边环必须去掉）。
- 光晕效果做成独立装饰元素（伪元素/独立 div），颜色由品牌 token 控制。
- Logo 图标 + 光晕与主副标题文字组合垂直居中。
- 资产落地：PNG 放入 `ai-admin-front/public/`（建议 `reachai-logo.png`，如需适配暗色再加 `reachai-logo-reverse.png`）；同时清理 `public/` 下两个 GBK 乱码文件名的 SVG 和不再引用的旧 SVG logo。

---

## 4. 分阶段实施计划

依赖顺序：Phase 0 → 1 → 2 → 3 → 4 → 5，不建议并行跨阶段（Phase 3 各组件之间可并行）。

### Phase 0：资产与基线准备

| # | 任务 | 产出 |
| --- | --- | --- |
| 0.1 | 从 Figma 导出 PNG logo（透明背景），落地 `public/reachai-logo.png` | PNG 资产入库 |
| 0.2 | 清理 `public/` 乱码文件名 SVG；盘点旧 SVG logo 引用点 | 干净的静态资产目录 |
| 0.3 | 对照 Figma Foundations，填写语义层变量的浅色值对照表（背景/表面/边界/阴影/文本/状态/图标背景/间距/圆角） | token 值清单（直接写进 `semantic.scss` 注释或本文件附录） |
| 0.4 | 从 Figma Icon Components 导出本轮需要的线性 SVG（项目详情 + 侧栏所需），按 3.2 规范检查 stroke/currentColor/安全区 | 图标 SVG 组件初始集 |

### Phase 1：Token 基线倒置

| # | 任务 | 要点 |
| --- | --- | --- |
| 1.1 | 建 `styles/tokens/` 结构，现有 `data-brand` 色板迁入 `brand.scss` | 纯搬运，不改值 |
| 1.2 | 写 `semantic.scss`：`:root` = Glass Workbench 浅色基线 | 沿用现有变量名 + 新增 surface/icon-bg/space；此步完成后 `[data-theme="light"]` 的自定义 token 段可删 |
| 1.3 | 写 `dark.scss`：`[data-theme="dark"]` 只重定义语义变量 | 值取自现有 `:root` 暗色段 |
| 1.4 | 合并 Element Plus 覆盖为一份 `element-plus.scss`，`--el-*` 从语义层推导 | 结构性补丁（表格固定列等）保留一份、引用语义变量 |
| 1.5 | `useTheme.ts` 默认主题 `'dark'` → `'light'` | 已存 localStorage 的用户选择继续尊重 |
| 1.6 | 审计 `index.scss`：工具类中的硬编码暗色值替换为语义 token | `.glass-card`、`.section-card` 等保留 |
| 1.7 | 暗色回归走查（见 Phase 5 清单），修"坏"不修"丑" | 暗色兼容底线 |

风险控制：此阶段不动任何页面模板。完成后所有未迁移页面自动进入浅色基线，允许出现"浅色但不够精致"，不允许出现不可读。

### Phase 2：布局壳与主菜单

| # | 任务 | 要点 |
| --- | --- | --- |
| 2.1 | 拆出 `AppSidebar.vue` + `sidebarMenu.ts` 配置 | 菜单硬编码模板 → 配置数组 |
| 2.2 | 按 3.2 落浅色玻璃侧栏视觉与导航定位感选中态 | 对照 Figma `系统页面重绘 / v1` 侧栏稿 |
| 2.3 | Logo 区换 PNG + 光晕独立元素 + 与主副标题垂直居中 | 对照 Figma 已完成的 logo 居中稿 |
| 2.4 | `activeMenu` if 链改为路由 meta 驱动（`meta.activeMenu`），旧 `/skill/*` 兜底逻辑保留 | 路由文件补 meta |
| 2.5 | 顶栏加返回按钮；为本轮页面补 `meta.breadcrumb`（项目详情：首页 / 注册中心 / 项目管理 / 项目详情） | 面包屑外置机制成型 |
| 2.6 | 删除 `registry-shell` 特例和 "SDK wizard aligned" `!important` 皮肤层 | **删除而非兼容保留**；统一浅色基线取代局部试验 |
| 2.7 | 顶栏本体按浅色玻璃规范重做（主题切换/品牌色/通知/头像不动功能） | `.topbar` 走语义 token |

#### Phase 2 进度记录（2026-07-03）

已完成：

- `AppSidebar.vue` + `sidebarMenu.ts` 落地（`src/components/common/`）：菜单硬编码模板改为配置数组；浅色玻璃侧栏、图标弱化、导航定位感选中态（左侧指示条 + 品牌色文字 + 极浅底色）；暗色通过组件内 `[data-theme='dark']` 变量覆盖保证不坏。
- Logo PNG 资产已从 Figma 导出落地：`public/reachai-logo.png`（透明全标）、`public/reachai-logo-tile.png`（侧栏 app tile，来自 Figma variant / sidebar 44）；侧栏品牌区 logo + 光晕（独立装饰元素）与 "ReachAI / 企业AI智能体中台" 垂直居中。
- `MainLayout.vue` 中旧深色侧栏样式与 "SDK wizard aligned" `!important` 皮肤层已删除（约 700 行）。
- 默认主题已切浅色（`useTheme.ts`，Phase 1.5 提前完成）；已存 localStorage 的用户选择继续尊重。
- 已用 Playwright 验证：浅色/暗色/翡翠绿品牌色下侧栏渲染正常，`npm run build` 通过。

#### Phase 2 收尾记录（2026-07-03，第二批）

- 2.7 顶栏本体已按浅色玻璃规范重做（`MainLayout.vue`）：浅色为 `:root` 基线（半透明白 + blur + 薄下边界 + 克制阴影），暗色通过 `.main-layout.is-dark` 覆盖；面包屑文字层级（灰字 + 尾项深色 + hover 品牌色）统一在布局层定义。
- 2.5 顶栏新增外置返回按钮（`< 返回` + 竖分隔线），仅当面包屑存在可导航父级时显示；点击优先 `router.back()`，无历史记录时跳最近的父级面包屑。已用 Playwright 点击验证：项目详情 → 返回 → 项目管理。
- 2.5 注册中心族路由已补 `meta.breadcrumb` + `meta.activeMenu`（项目管理/项目详情/API 管理/能力变更评审/前端页面管理/业务页面工作台/SDK 接入向导/嵌入式会话审计/扫描 API 目录）；面包屑 `to` 支持函数形式，携带 `projectCode` 的父级（如 页面管理 → 项目详情）可正确回跳。
- 2.4 `resolveActiveMenu` 已支持 `meta.activeMenu` 优先，未配置的路由继续走原 if 链兜底；后续页面迁移时逐步补 meta 即可收敛 if 链。
- 2.6 顶栏视觉特例已全部删除：`registry-shell` 顶栏皮肤（`MainLayout.vue`）、`RegistryProjectDetail.scss` / `EmbedOpsMonitor.vue` / `PageAssistantWizard.scss` / `SdkAccessWizard.scss` 里 6 处 `:has(...) .topbar` `!important` 皮肤块、`theme.scss` / `index.scss` 的旧顶栏覆盖，以及已无消费者的 `--brand-topbar-bg` 品牌变量（7 套）。`registry-shell` / `studio-shell` 类仅保留结构差异（main 区零内边距）。
- 4.2 的一小步顺带完成：项目详情 hero 卡片内嵌返回按钮及其样式已删（顶栏返回取代）；`SdkAccessWizard` / `PageAssistantHeader` 页面内的"返回项目详情"按钮待各自页面迁移时处理。
- 验证：`npm run build`（含 vue-tsc）通过；Playwright 截图确认浅色/暗色顶栏、项目列表（无返回）、项目详情（4 级面包屑）、前端页面管理（5 级 + 动态 to）、SDK 接入向导（旧深蓝渐变顶栏已消失）渲染正常。

### Phase 3：公共组件

| # | 任务 | 消费方 |
| --- | --- | --- |
| 3.1 | `AppIcon.vue` + 图标 SVG 组件集 + 四种背景变体 token 接线 | 侧栏、项目详情业务卡片 |
| 3.2 | `IconActionButton.vue` | 项目详情动作区、实例表格头 |
| 3.3 | `relativeTime.ts` + `RelativeTime.vue`（含单测级别的边界：秒/分/时/天/年、未来时间、空值） | 项目详情心跳列、后续所有时间字段 |
| 3.4 | `PageHeader.vue`（标题/标签/meta/动作区插槽）；处理与 `index.scss` `.page-header` 类名冲突 | 项目详情、API 管理 |

### Phase 4：项目详情样板页迁移

目标模板结构（自上而下）：

```
[顶栏] < 返回   首页 / 注册中心 / 项目管理 / 项目详情     ← Phase 2 已就位
[PageHeader] logo/项目标识 + 项目名 + 编码/环境标签 + 状态/可见性/负责人 meta
             右上：设为当前项目 / 刷新 / 编辑 / 删除（全部 IconActionButton，仅图标）
[业务动作区] workbenchGroups 卡片，图标走 AppIcon 新背景标准
[实例心跳表格] 表头动作：清理离线、刷新实例（IconActionButton，无文字）
               最近心跳列：RelativeTime
[治理入口卡片] （现有 workbench 分组中治理相关入口）
```

| # | 任务 | 要点 |
| --- | --- | --- |
| 4.1 | 删除 `health-card`（项目接入健康）区块，**连同 `useRegistryProjectWorkbench` 里 `healthMetrics` 的派生逻辑一起删**，不留死代码 | 右侧也不放"项目档案树" |
| 4.2 | hero 卡片改造为 PageHeader；删除卡片内嵌 `back-btn`（返回已外置到顶栏） | 统一顶部结构 |
| 4.3 | 动作按钮全部换 IconActionButton；"编辑项目"只保留图标 | 带 tooltip |
| 4.4 | 实例表"清理离线 / 刷新实例"换图标按钮；心跳列换 RelativeTime；`formatHeartbeatDisplay` 下线并迁移 | 相对时间公共化 |
| 4.5 | 业务卡片图标换 AppIcon 四种背景变体（对照 Figma 项目详情稿已完成的图标标准） | 删除页面私有 tone 类 |
| 4.6 | `RegistryProjectDetail.scss` 重写：只保留页面布局（grid、间距、响应式），颜色/阴影/玻璃效果全部走 token 与工具类 | 1195 行 → 预期 ≤ 300 行；`RegistryProjectDetail.global.scss` 内容并入或删除 |

### Phase 5：回归与验收

见第 5 节验收标准，全部通过后本轮完成。

---

## 5. 验证与验收标准

### 5.1 构建验证（每个 Phase 结束都要跑）

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
npm run build
```

### 5.2 视觉与功能验收（Phase 5 走查清单，优先用 Playwright MCP 截图对照）

| 项 | 标准 |
| --- | --- |
| 默认体验 | 新用户（无 localStorage）进入即浅色 Glass Workbench；已选暗色的老用户保持暗色 |
| 品牌换肤 | 7 套 `data-brand` 逐个切换：侧栏、顶栏、PageHeader、图标背景、项目详情卡片颜色全部正确跟随，无残留硬编码色 |
| 侧栏 | 统一玻璃面（非白卡片拼接）；选中态为导航定位感；折叠态正常；logo PNG 透明背景无叠加底色，logo+光晕与主副标题垂直居中 |
| 顶部结构 | 项目详情、API 管理顶部为"< 返回 + 面包屑"外置结构，标题卡片内无返回/面包屑 |
| 项目详情 | 无"项目接入健康"、无项目档案树、无三级小状态标题；动作全部为图标按钮带 tooltip；心跳时间为相对时间且 hover 显示绝对时间 |
| 图标 | 业务卡片图标走四种背景变体；齿轮类图标无裁切 |
| 暗色兜底 | 暗色下走查：概览、项目管理、项目详情、API 管理、模型实例——无白底黑字、无不可读对比度（允许不精致） |
| Studio 隔离 | Workflow Studio / Agent Studio 页面视觉与交互与重构前一致 |
| 代码指标 | `RegistryProjectDetail.scss` ≤ 300 行且无颜色字面量；`MainLayout.vue` 无 `registry-shell` 与 `!important` 皮肤层；`theme.scss` 暗浅两份 EP 覆盖合并为一份 |

### 5.3 明确的完成定义（Definition of Done）

1. `registry-shell`、"SDK wizard aligned" 覆盖层被**删除**（不是保留兼容）。
2. 语义 token 成为唯一颜色来源：本轮触及的文件中不新增颜色字面量。
3. 五个公共组件（AppSidebar、PageHeader、AppIcon、IconActionButton、RelativeTime）各自至少有一个真实消费方在线上路径运行。
4. 项目详情页作为样板通过 5.2 全部验收项，作为后续页面迁移的对照标准。

---

## 6. 风险与约束

| 风险 | 应对 |
| --- | --- |
| 基线倒置后未迁移页面出现暗色残留（页面私有 SCSS 里写死了暗色 rgba） | Phase 1 完成后全量页面快速走查一遍浅色表现；发现写死值的页面记入后续批次清单，只修"不可读"级别的问题 |
| Element Plus `dark/css-vars.css` 与自研 token 双轨 | `html.dark` class 同步逻辑保留；暗色回归以"能用"为准，不追求两轨完全一致 |
| `index.scss` `.page-header` 类与 PageHeader 组件混用期冲突 | Phase 3.4 一次性处理：全局类改名 `.legacy-page-header` 或直接删除并修复引用 |
| 相对时间定时刷新造成大表格性能问题 | RelativeTime 用单例共享 ticker（一个 interval 广播），不是每实例一个 timer |
| 品牌色 7 套 × 明暗 2 套组合爆炸 | 语义层设计保证组合正交：`data-brand` 只供原语，`data-theme` 只改语义值；验收时抽查 2~3 套品牌 × 暗色即可，不做全矩阵精修 |
| 老用户 localStorage 存量 `theme=dark` | 尊重用户选择，不做强制迁移 |

命名与边界约束（沿用仓库规则）：

- `eaf.*`、`X-EAF-*`、`Eaf*`、Maven artifactId 等技术身份标识不因品牌文案调整而改动。
- 产品文案默认 `Capability / 能力`；侧栏菜单文案本轮不调整语义，只调整视觉。
- 本轮不改后端、不改 SQL；纯前端 + 静态资产 + 本文档。

---

## 7. 后续批次（本轮之外，仅记录方向)

1. 按 Figma `系统页面重绘 / v1` 逐页迁移：优先注册中心族（项目管理列表、API 管理），其次概览、模型实例，再次能力内核、知识检索族。
2. 每迁移 2~3 个页面复盘一次公共组件缺口，再决定是否抽象 GlassCard / FilterBar / StatPill 等容器组件。
3. 暗色精修：语义层就位后，暗色只需重调 `dark.scss` 一个文件。
4. Workflow Studio 重绘作为独立专项，不混入系统页面批次。
