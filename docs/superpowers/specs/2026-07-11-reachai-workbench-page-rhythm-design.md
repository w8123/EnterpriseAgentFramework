# ReachAI 工作台页面节奏统一设计

状态：已确认

日期：2026-07-11

范围：`ai-admin-front` 中由 `MainLayout` 承载的业务页面外层间距、一级区块节奏与布局模式

关联方案：[`2026-07-10-reachai-glass-workbench-design-system-design.md`](./2026-07-10-reachai-glass-workbench-design-system-design.md)

## 1. 背景

ReachAI 已形成 PageHeader、MetricStrip、WorkbenchPanel、DataTableShell 等公共玻璃工作台组件，但页面外层仍存在三套并行规则：

- “项目管理”专页使用 `28px` 左右留白、`10px` 顶部留白和 `10px` 一级区块间距。
- 新公共组件的 density token 同时承担页面外部 gap 与卡片内部 gap，comfortable 默认值为 `20px`。
- 旧全局 `.page-container`、`.page-header`、相邻卡片 margin 和页面私有 CSS 继续使用 `16/18/24px`，部分规则带 `!important`。

这导致侧栏到内容、面包屑到标题、标题到指标条、卡片到卡片的距离无法通过一个公共入口调整。目标是以“项目管理”页面为几何标准，把页面外部节奏收敛为独立、可复用、可测试的布局契约。

## 2. 已确认决策

- 采用“公共页面节奏契约”，不做仅替换几个全局 CSS 数值的临时修补。
- 项目管理页面是本轮外层几何基准。
- 常规业务页面统一；Workflow Studio、全屏画布、调试工作台和嵌入式页面保留专用布局。
- 页面外部节奏与组件内部 density 必须分离。
- MainLayout 负责应用壳；WorkbenchPage 负责业务页面根容器；公共卡片组件只负责内部布局。
- 页面迁移不得改变接口调用、状态管理、表单校验、事件处理或业务文案。
- 当前工作保持暂存状态，不创建 Git commit，不 push。

## 3. 目标与非目标

### 3.1 目标

- 页面标题、指标条、筛选区和数据卡片使用一致的一级间距。
- 面包屑与标题卡左边缘严格对齐。
- 侧栏展开或收起时，侧栏玻璃卡到业务内容的可见间距保持稳定。
- 后续可仅修改公共 token 调整全站页面节奏。
- 通过路由元数据显式表达标准页、项目工作台、无边距页和 Studio，不再通过 `:has()` 反向修改父布局。
- 自动检查阻止页面重新引入私有外层 padding、一级卡片 margin 或旧全局补偿规则。

### 3.2 非目标

- 不重构 Workflow Studio 的画布结构。
- 不将 Agent Debug、Model Playground 或登录页套入普通页面容器。
- 不在本轮重写项目详情、接入向导等定制工作台的内部业务布局。
- 不借间距迁移修改颜色、玻璃配方、字段结构、接口契约或响应数据。

## 4. 公共布局 Token

布局 token 单独放入 layout foundation，不再混入 semantic color token。桌面端基准如下：

| Token | 默认值 | 所有者 | 用途 |
| --- | ---: | --- | --- |
| `--layout-breadcrumb-height` | `44px` | MainLayout | 面包屑轨高度 |
| `--layout-content-inline` | `28px` | MainLayout / WorkbenchPage | 面包屑和页面内容共同的左右对齐线 |
| `--layout-page-start` | `10px` | WorkbenchPage | 面包屑轨底部到首个一级区块顶部 |
| `--layout-page-end` | `16px` | WorkbenchPage | 页面内容到底部的留白 |
| `--layout-page-gap` | `10px` | WorkbenchPage | PageHeader、MetricStrip、WorkbenchPanel 等直接子区块之间的距离 |
| `--layout-page-header-padding-block` | `26px` | PageHeader | 标题卡上下内边距 |
| `--layout-page-header-padding-inline` | `28px` | PageHeader | 标题卡左右内边距 |
| `--layout-page-header-compact-padding-block` | `12px` | project-workbench 标题卡 | 滚动收起态上下内边距 |
| `--layout-page-header-compact-padding-inline` | `22px` | project-workbench 标题卡 | 滚动收起态左右内边距 |
| `--layout-sidebar-expanded-width` | `256px` | MainLayout | 展开侧栏占位宽度 |
| `--layout-sidebar-collapsed-width` | `84px` | MainLayout | 收起侧栏占位宽度 |
| `--layout-sidebar-inset-block` | `14px` | MainLayout | 侧栏玻璃卡上下呼吸空间 |
| `--layout-sidebar-inset-start` | `14px` | MainLayout | 侧栏玻璃卡左侧呼吸空间 |
| `--layout-sidebar-inset-end` | `10px` | MainLayout | 侧栏玻璃卡右侧到 workspace 的距离 |

移动端只在公共 foundation 中覆盖 token。页面不得通过媒体查询重新发明外层 padding。

## 5. 页面与组件边界

### 5.1 MainLayout

MainLayout 唯一负责：

- 侧栏展开/收起宽度和侧栏 inset。
- 面包屑轨高度、水平对齐和显示条件。
- 主内容滚动容器。
- 根据路由 `layoutMode` 添加布局类。

MainLayout 的 `.main-content` 不再为业务页面注入 padding，也不再根据子页面 class、路径或 `:has()` 推断布局。

### 5.2 WorkbenchPage

新增 `WorkbenchPage` 作为常规业务页面根组件，接口保持最小化：

```ts
interface WorkbenchPageProps {
  density?: WorkbenchDensity
  fullHeight?: boolean
}
```

`WorkbenchDensity` 复用 `glassWorkbench.ts` 已有的 `compact | comfortable | spacious` 类型，不新增重复枚举。默认 density 为 comfortable。组件负责：

- `display: flex`、纵向排列、最小宽高约束。
- 使用 `--layout-content-inline`、`--layout-page-start`、`--layout-page-end` 设置页面 padding。
- 使用 `--layout-page-gap` 设置直接子区块间距。
- 将 density 仅传递给内部控件和面板，不改变页面一级 gap。

标准结构为：

```vue
<WorkbenchPage>
  <PageHeader />
  <MetricStrip />
  <WorkbenchPanel />
</WorkbenchPage>
```

### 5.3 公共子组件

- PageHeader、MetricStrip、WorkbenchPanel、FilterBar 和 DataTableShell 不拥有外部 margin。
- PageHeader 使用专用标题卡 padding token。
- 其余组件继续通过 density 控制内部 `--section-gap` 和 `--panel-padding`。
- WorkbenchPanel 内嵌 DataTableShell 时使用明确的 embedded 配方，避免双层 padding。
- 页面不得通过深层选择器修改公共组件根节点的 padding、gap 或 margin。

## 6. 路由布局模式

路由 `meta.layoutMode` 使用以下显式枚举：

| 模式 | 含义 | 页面行为 |
| --- | --- | --- |
| `standard` | 常规列表、详情、表单、配置页 | 使用 WorkbenchPage 完整节奏 |
| `project-workbench` | 项目详情、接入向导、运行时等定制工作台 | 保留内部结构，外层消费同一组 layout token |
| `edge-to-edge` | 调试页、Model Playground 等视口型页面 | MainLayout 不注入业务页 padding |
| `studio` | Workflow Studio 等全屏画布 | 隐藏普通侧栏/面包屑并保持画布布局 |

登录页不经过 MainLayout，不参与该枚举。新增 MainLayout 子路由时必须声明 layoutMode。

## 7. 迁移范围

当前路由盘点为 59 个组件路由、54 个唯一 Vue 页面：

- 39 个常规页面迁移到 `standard`，其中 5 个已经使用公共工作台组件，34 个仍有私有 header/card。
- 9 个项目域定制工作台迁移到 `project-workbench`，先移除父壳耦合和补偿值，再统一外层 token。
- 6 个登录、全屏、画布、调试或基础壳页面保持例外模式。

迁移顺序：

1. 新增 layout foundation、WorkbenchPage、路由模式和契约检查。
2. 以项目管理为黄金基准，校准已使用公共组件的 5 个页面。
3. 迁移 34 个普通业务页面并删除页面私有外层间距。
4. 迁移 9 个项目域工作台的外层契约，保留其内部业务结构。
5. 删除旧 `.page-container`、`.page-header`、相邻卡片 margin、`:has()` 父壳控制和负 margin 补偿。

## 8. 响应式规则

- 桌面端保持项目管理的 `28px / 10px / 16px` 页面节奏。
- `900px` 及以下统一覆盖为：content inline `16px`、page start `10px`、page end `16px`、page gap `10px`、标题卡 padding `18px`。
- `760px` 及以下统一覆盖为：content inline `12px`、page start `8px`、page end `12px`、page gap `8px`、标题卡 padding `16px`；PageHeader、筛选器和操作区允许换行。
- 侧栏展开和收起只改变侧栏宽度，不改变 workspace 内部 `--layout-content-inline`。
- 页面根、表格和操作区不得产生横向滚动；确需横向滚动的宽表必须把滚动限制在 DataTableShell 内。

## 9. 自动化契约与浏览器验收

### 9.1 静态契约

新增或扩展前端检查器，至少验证：

- 所有 MainLayout 子路由声明合法的 `layoutMode`。
- `standard` 页面直接使用 WorkbenchPage。
- 标准页面不得定义根 padding、一级子区块 margin 或公共组件根样式覆写。
- 项目工作台不得使用 `:global(.main-layout ... :has(...))` 或负 margin 抵消应用壳。
- 公共 layout token 只能有一个定义源。
- Studio、edge-to-edge 页面不会被标准页面选择器命中。

检查器遵循 TDD：先加入会失败的页面几何/源码契约，再实现 token 和迁移。

### 9.2 浏览器几何验收

代表页面：

- 项目管理。
- Agent 编辑。
- Tool 列表。
- 知识库详情。
- 项目详情。
- 一个普通 CRUD 页面。

每个代表页在浅色与暗色、侧栏展开与收起、`1600 / 1280 / 900 / 760px` 视口下验证：

- 面包屑左边缘与首个一级区块左边缘相等。
- 面包屑轨底部到首个一级区块顶部为 `10px`，移动端取对应公共 token 的计算值。
- 相邻一级区块的垂直距离为 `10px`，移动端取对应公共 token 的计算值。
- 页面右侧留白与左侧内容 inset 对称。
- 无页面级横向溢出。
- 页面原有按钮、表单、分页、弹窗和跳转仍可工作。

### 9.3 技术验证

- `npm run check:glass-theme`
- `npm run check:dark-theme-compatibility`
- `npm run check:glass-components`
- 新增页面布局契约命令。
- `npx vue-tsc --noEmit`
- `npm run build`
- `git diff --check`

## 10. 完成定义

- 48 个纳入范围的业务页面均声明布局模式并消费公共外层 token。
- 39 个 standard 页面使用 WorkbenchPage。
- 9 个 project-workbench 页面不再反向控制 MainLayout。
- 6 个例外页面保持原有视口或画布行为。
- 旧外层间距双轨规则被删除，没有页面新增私有补偿值。
- 自动契约、类型检查、生产构建和代表页浏览器几何验收全部通过。
- 所有改动保持暂存，不创建 commit，不 push。
