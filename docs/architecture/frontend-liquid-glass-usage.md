# 公共液态玻璃组件接入指南

本文对应 `ai-admin-front/src/components/common/liquid-glass/index.ts`。项目管理、MCP「对外发布」、Workflow、Agent、Skill 管理、知识库列表、业务能力目录和 API 市场使用同一套组件。材质和七套主题配色由现有 `_glass.scss`、`tokens/_semantic.scss` 管理。

## 组件入口

```ts
import {
  LiquidGlassButton,
  LiquidGlassInput,
  LiquidGlassSelect,
  LiquidGlassPagination,
  LiquidGlassTable,
  LiquidGlassPage,
  LiquidGlassFilterBar,
  LiquidGlassViewToggle,
  LiquidGlassNav,
  LiquidGlassTabs,
  LiquidGlassDropdown,
  LiquidGlassCard,
  LiquidGlassEmptyState,
  LiquidGlassNotice,
  PageHeaderTags,
} from '@/components/common/liquid-glass'
```

| 组件 | 用途 | 保留的原生能力 |
| --- | --- | --- |
| `LiquidGlassButton` | 胶囊按钮、圆形图标按钮 | `type`、`icon`、`loading`、`disabled`、`native-type`、点击事件及插槽 |
| `LiquidGlassInput` | 单行搜索、文本和密码输入 | `v-model`、前后图标、清除、键盘事件、校验属性及插槽 |
| `LiquidGlassSelect` | 选择器与水滴浮层 | `v-model`、`multiple`、`filterable`、`remote`、`clearable`、事件及原生插槽 |
| `LiquidGlassPagination` | 连体分页与每页条数 | 双向页码、双向条数、禁用状态、键盘焦点 |
| `LiquidGlassTable` | 筛选、表格与分页共用的磨砂阅读面 | 组合现有 `DataTableShell`，业务列、行操作、空态仍由页面传入 |
| `LiquidGlassFilterBar` | 列表筛选布局、搜索与重置 | 组合既有 `FilterBar`，Enter 和搜索按钮统一触发 `query`，重置触发 `reset`，可覆盖 `actions` 插槽 |
| `LiquidGlassViewToggle` | 表格/卡片视图切换 | `v-model="viewMode"`，原生按钮保留焦点和 `aria-pressed` 状态 |
| `LiquidGlassPage` | 撑满工作区的列表页布局 | 组合既有 `WorkbenchPage`，统一高度和页头收缩边界，属性继续转交；不在根节点注入材质色，避免影响兄弟弹窗 |
| `PageHeaderTags` | 标题栏的辅助标签信息行 | 保留标签插槽、动态计数和原生事件，自动换行，随页头收起 |
| `LiquidGlassNav` | 工作视图、功能区域的玻璃导航条 | 插槽保留原生链接和按钮；`columns` 可用于多列功能导航 |
| `LiquidGlassTabs` | 原生标签页的统一材质 | 转交 `v-model`、`before-leave`、事件和插槽；继续使用原生 `el-tab-pane` |
| `LiquidGlassDropdown` | 操作菜单与水滴浮层 | 原生 `trigger`、`placement`、`command` 和禁用项；菜单仍用 `el-dropdown-menu/item` |
| `LiquidGlassCard` | 目录卡片和内容分区的阅读面 | `as` 可选 `article/section/div`；`interactive` 提供悬停与焦点外观，业务事件由页面处理 |
| `LiquidGlassEmptyState` | 空目录、项目范围提示和加载失败反馈 | 标题、描述、图标和操作插槽；`ProjectScopeState` 继续处理原有状态与重试事件 |
| `LiquidGlassNotice` | 直接放在壁纸上的页面级提示条，替代 `el-alert` | `type`（info/success/warning/error）、`title`、`description`，默认插槽写正文，`actions` 插槽放操作；错误与警告使用 `role="alert"` |

按钮、输入框和选择器把 `class`、`style` 应用到外壳，其余原生属性和事件交给 Element Plus。可通过组件 ref 使用 `focus()`、`blur()`；输入框另提供 `select()`、`clear()`，`native` 可访问底层原生组件实例。

## 一页列表的最小示例

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import {
  LiquidGlassPage, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable,
} from '@/components/common/liquid-glass'

const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(10)
const rows = ref<Array<{ id: number; name: string; status: string }>>([])
const filtered = computed(() => rows.value.filter(row =>
  row.name.includes(keyword.value) && (!status.value || row.status === status.value),
))
const visible = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))

function reset() {
  keyword.value = ''
  status.value = ''
  page.value = 1
}
</script>

<template>
  <LiquidGlassPage>
  <LiquidGlassTable
    v-model:current-page="page"
    v-model:page-size="size"
    :total="filtered.length"
    :page-sizes="[10, 20, 50]"
    pagination-label="资产分页"
  >
    <template #toolbar>
      <LiquidGlassFilterBar @query="page = 1" @reset="reset">
        <LiquidGlassInput v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索名称" aria-label="搜索名称" @input="page = 1" />
        <LiquidGlassSelect v-model="status" clearable placeholder="全部状态" dropdown-title="资产状态" aria-label="资产状态" @change="page = 1">
          <el-option label="已发布" value="published" />
        </LiquidGlassSelect>
      </LiquidGlassFilterBar>
    </template>
    <el-table :data="visible" row-key="id">
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column prop="status" label="状态" width="120" />
    </el-table>
  </LiquidGlassTable>
  </LiquidGlassPage>
</template>

```

`LiquidGlassTable` 默认采样工作台壁纸，并向其内部公共控件提供相同来源。页面只负责筛选布局和业务列，无需重复编写玻璃背景、焦散、圆角、表头、行分隔线或分页样式。

`LiquidGlassFilterBar` 默认自带圆形重置按钮和搜索按钮，也可通过 `:show-reset="false"` 隐藏重置，或用 `actions` 插槽保留原有操作。表单的 `query` 负责提交；不要再给输入框绑定调用同一查询的 `@keyup.enter`，避免一次 Enter 请求两次。

卡片目录使用 `body-layout="cards"`，共享容器负责内滚动与留白，页面只保留卡片网格和业务内容。可切换视图的页面使用 `:body-layout="viewMode === 'card' ? 'cards' : 'table'"`；卡片使用 `LiquidGlassCard` 复用 `.glass-surface-frost`。表格固定列在浮起时使用壁纸与面板的混合底色，落回本来位置后恢复表格材质，保证主题协调并遮住下方滚动的文字。

固定列按局部 `color-scheme` 选择阅读底：浅色表面使用壁纸着色，深色模式及深色壁纸采用不透明的深色面板底。浏览器不支持 `light-dark()` 时沿用既有 `--surface-reading-sticky` 降级。

卡片已经各自拥有阅读面时，可使用 `presentation="collection"`：保留筛选、内滚动、加载与分页，去掉整片外层玻璃，避免“玻璃大框里再套白卡片”。筛选直接落在壁纸上，卡片使用 `LiquidGlassCard`，分页仍由公共容器管理；默认 `panel` 外观保持现有表格布局。Skill 管理页的 `SkillCatalogCard` 是这一组合的接入示例，名称和用途居前，包标识及版本使用辅助信息层级。

概览只需少量计数时，可将 `MetricStrip presentation="inline" source="wallpaper"` 放进页头的 `meta` 插槽。它以轻量文字替代独立统计面板，前景色跟随壁纸，不受用户明暗模式影响；面板内仍沿用默认来源。

工作台列表页根节点使用 `LiquidGlassPage`，使卡片数量增加时在列表体内滚动、分页保持可见。嵌套在详情页内的局部表格仍可单独使用 `LiquidGlassTable`，由所属页面决定高度。

窄屏布局将统计压成两列，并为列表保留最小可用高度。页头标签缩小并自动换行；固定列恢复普通列顺序，用户可水平滚动查看，避免两侧固定列遮满整个列表。

## 公共页头标签

`PageHeader` 已内置 `PageHeaderTags`。现有页面继续使用 `#tags`，标签统一排列在标题和说明下方，右侧操作区保持独立。不要再给标签组添加整块背景或逐页覆盖位置。

```vue
<PageHeader title="Agent 管理" description="管理智能体和可调用 Workflow。" presentation="plain">
  <template #tags>
    <el-tag type="info">范围：全部 Agent</el-tag>
    <el-tag type="success">当前 {{ total }} 个</el-tag>
  </template>
  <template #actions><!-- 原有操作 --></template>
</PageHeader>
```

标签沿用现有主题和成功、警告等语义色；面板内的单个标签保留控件阅读底，壁纸上的标签采用薄着色，标签组本身保持透明。公共信息行负责间距、圆角、换行和收起，不修改插槽中的业务文字或事件。收起的标签组设置 `inert` 和 `aria-hidden`，避免隐藏控件继续获得键盘焦点。独立布局可从公共入口导入 `PageHeaderTags`，用 `source="wallpaper"` 适配壁纸；在阅读面中省略来源即可。

## 材质来源和主题

- 工作台壁纸上的独立按钮使用 `source="wallpaper"`。在 `LiquidGlassTable` 内部可省略这一属性；显式传入仍可覆盖继承值。
- 公共控件单独使用时默认为 `source="backdrop"`，适合对话框和嵌套阅读面。浮层保留高浓度阅读底，避免底下的业务文字透入选项。多行文本、复杂表单和长内容继续使用现有 `el-input`、`AppDialog`、`AppDrawer` 阅读配方。
- `LiquidGlassTable source="backdrop"` 可用于嵌套内容。来源上下文只作用于组件内部，不会改变兄弟组件或用户的全局明暗设置。
- 壁纸来源自动启用 `.glass-workspace-adaptive`：科技紫、星云紫、珊瑚玫、深海蓝使用烟色材质和浅色文字；翡翠绿等浅色壁纸沿用浅色材质。页面不添加品牌判断或局部深色选择器。
- 统计区域可继续使用 `MetricStrip`，在壁纸工作台上按需添加 `class="glass-workspace-adaptive"`；已有 `.glass-surface-frost`、`.glass-surface-panel` 和 `WorkbenchPanel` 仍是公共材质入口。
- 减少透明度、减少动态效果、强制颜色以及关闭折射由共享实现处理。业务页面不直接挂载光学贴图，也不逐帧重建贴图。

## 导航、菜单与内容状态

`LiquidGlassNav` 内传入 `RouterLink` 或原生 `button`，保留路由和权限判断，选中项提供 `aria-current="page"` 或 `aria-pressed`。它只负责外观，不增加一套导航状态。原生标签页用 `LiquidGlassTabs` 包装，`before-leave` 继续负责未保存内容的离开确认。

```vue
<LiquidGlassDropdown source="wallpaper" title="整理能力目录" trigger="click" @command="openTool">
  <LiquidGlassButton source="wallpaper">整理</LiquidGlassButton>
  <template #dropdown>
    <el-dropdown-menu>
      <el-dropdown-item command="classification" :disabled="!canAdjust">调整分类</el-dropdown-item>
      <el-dropdown-item command="graph">关联分析</el-dropdown-item>
    </el-dropdown-menu>
  </template>
</LiquidGlassDropdown>
```

菜单沿用选择器的异形玻璃表面，选项拥有稳定的阅读底。空状态用 `LiquidGlassEmptyState`，将既有重试或切换范围动作放在默认插槽；不要在公共外观组件中读取业务 API。可交互卡片应保留原生按钮操作，并让卡片的 Enter 只处理自身焦点，避免点击或键盘激活嵌套按钮时重复打开详情。

## 页面级提示

管理端已不再使用 `el-alert`，新页面也不要引入。页面级说明、规则提示直接落在壁纸上时用 `LiquidGlassNotice source="wallpaper"`，不要再放 `el-alert`：后者是实色底，在深色壁纸上会成为一块白条或灰条。提示条复用卡片的磨砂面和壁纸自适应配色，图标按 `type` 取状态色。

```vue
<LiquidGlassNotice source="wallpaper" title="决策规则：DENY 优先；无命中默认拒绝">
  角色为空时诊断返回 SKIPPED，该结果不表示允许调用。
</LiquidGlassNotice>
```

位于玻璃面板、弹窗或抽屉内部的提示用 `LiquidGlassNotice source="backdrop"`：它取面板背后的材质，不会在深色主题下出现浅色实底块。原来放在 `el-alert` 默认插槽里的按钮（重试、跳转）移到 `#actions` 插槽；默认插槽渲染在 `<p>` 里，列表类内容用 `<span>` 逐行输出（例如 Studio 发布弹窗的“发布前检查”），不要嵌套 `<ul>` 或 `<p>`。单测里原先给 `el-alert` 写的存根，改为同名模板的 `LiquidGlassNotice` 存根即可，不要删断言。

`WorkbenchPanel` 的根节点类受组件契约约束，直接放在壁纸上时由页面传入 `class="glass-workspace-adaptive"`，深色壁纸下会切换为烟色玻璃与浅色文字，不需要在页面里重写背景。

`HeaderMetaList` 可以直接放进 `presentation="plain"` 的页头，组件会自动改用壁纸墨色。直接放在壁纸上的 `LiquidGlassCard`、`LiquidGlassEmptyState` 需要传 `source="wallpaper"`，否则在深色壁纸上会保持浅色磨砂。代码块统一使用 `--code-surface` / `--code-text`，不要写死深色背景。

页面私有的颜色令牌（例如项目接入工作台的 `--sdk-*`）不要写死白色或深色渐变，应指向 `--surface-glass-frost`（面板）、`--surface-glass-inset`（卡片、输入、步骤）和 `--surface-glass-selected`（选中项），这样同一套样式在浅色和深色壁纸上都成立。前端本地分页的表格同样用 `LiquidGlassTable` 的 `v-model:current-page` / `v-model:page-size` 并在页面里按页切片，不要放一个不绑定数据的分页器。

契约要求保留原生 `el-button` 的地方（例如 A2A 子页标题区的刷新按钮），用 `<LiquidGlassControl source="wallpaper">` 包住原生按钮即可得到与 `LiquidGlassButton` 相同的外观。子页根节点带 `v-loading` 且不能改静态 class 时，在父布局用 `<router-view v-slot="{ Component }">` 给它合并 `glass-loading-surface`。

`LiquidGlassButton` 的 `type="danger"` / `type="warning"` 会保留语义色字（不加色底），不可逆操作直接用它，不要退回原生 `el-button`。

页头的少量计数用 `MetricStrip presentation="inline" source="wallpaper"` 放进 `meta` 插槽（见上文），不再单独铺一排大统计卡。壁纸自适应面板里的禁用输入框、单选按钮使用 `--surface-solid-disabled`，该 token 已在深色壁纸块中改为半透明，页面不需要单独覆盖。

## 加载状态

`DataTableShell`（包含 `LiquidGlassTable`）在加载时保留原阅读面和已有内容，覆盖低强度薄雾，并在中央显示小型加载提示，避免原生大面积白色遮罩破坏壁纸与深色主题。用 `loading-text` 覆盖默认“正在加载”，`aria-busy` 随 `loading` 更新；完成或失败后由原业务逻辑结束加载。

`LiquidGlassCard` 同样带有 `glass-loading-surface` 公共入口，直接绑定 `v-loading` 即可复用。减少透明度时关闭模糊，强制颜色下使用系统颜色；弹窗、全屏阻断等其他遮罩继续沿用各自配方。

## 服务端分页

`LiquidGlassTable` 和 `LiquidGlassPagination` 的 `change(page, pageSize)` 在用户操作后的值稳定时触发一次。切换每页条数会回到第一页；父组件主动更新页码或条数不会反向触发查询。

```vue
<LiquidGlassTable
  v-model:current-page="page"
  v-model:page-size="size"
  :total="total"
  :loading="loading"
  pagination-label="订单分页"
  @change="loadPage"
>
  <el-table :data="rows"><!-- 原有业务列 --></el-table>
</LiquidGlassTable>
```

```ts
async function loadPage(page: number, pageSize: number) {
  // 按既有 API 契约转换，不改变后端分页边界。
  const offset = (page - 1) * pageSize
  // await existingApi({ offset, limit: pageSize })
}
```

同一次加载绑定 `change` 即可。分页器还提供 `current-change` 和 `size-change` 供局部响应使用，不要把这三个事件同时绑定到同一个加载函数。

`LiquidGlassTable` 支持 `toolbar`、默认内容、`empty` 和 `pagination` 插槽，可用 `:show-pagination="false"` 隐藏分页。自定义分页插槽后，由页面连接自己的分页事件。其余 `loading`、`empty`、`density`、`preserve-empty-body` 等属性继续转交 `DataTableShell`。

默认总数为 0 时隐藏分页；需要成功加载后仍显示“共 0 条”的列表可加 `show-empty-pagination`，并用 `:show-pagination="hasLoadedList"` 区分尚未加载与成功空结果。

## 弹窗、抽屉与二级页面分段导航

弹窗（`AppDialog` / `GlassDialog`）与抽屉（`AppDrawer`）内部的分段导航与表单统一遵循以下规范：

1. **分段标签页**：使用 `LiquidGlassTabs` + 原生 `el-tab-pane`。弹窗/抽屉内默认采用 `source="backdrop"`，壁纸上的顶层标签可使用 `source="wallpaper"`。保留原有的 `before-leave` 异步拦截、`lazy` 加载和自定义标签插槽。
2. **操作按钮**：弹窗与抽屉 footer 内的操作按钮优先使用 `LiquidGlassButton`，次要操作为默认 `tone="neutral"`，主要操作为 `type="primary"`（自动映射为 `tone="primary"`）。底层在 `_overlays.scss` 与 `GlassDialog.vue` 中统一了全局胶囊圆角（`var(--radius-pill)`）与 40px 高度规范。
3. **代码与配置文本**：类似 SDK 一次性接入令牌中的配置代码，使用等宽字体（`var(--font-family-mono)`）、凹槽微质感底（`var(--surface-glass-inset)`）与高对比度文字，保证开发者复制与查看清晰。

```vue
<AppDialog v-model="visible" title="接入向导" width="760px">
  <LiquidGlassTabs v-model="activeTab">
    <el-tab-pane label="SDK 接入" name="sdk">
      <el-form label-width="120px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" placeholder="输入项目名称" />
        </el-form-item>
        <LiquidGlassNotice type="success" title="配置说明" description="提交后自动生成配置。" source="backdrop" />
      </el-form>
    </el-tab-pane>
    <el-tab-pane label="扫描接入" name="scan">
      <!-- 扫描表单 -->
    </el-tab-pane>
  </LiquidGlassTabs>
  <template #footer>
    <LiquidGlassButton @click="visible = false">取消</LiquidGlassButton>
    <LiquidGlassButton type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
  </template>
</AppDialog>
```

## 迁移与验证

1. 保留原有 API、过滤顺序、权限和业务列，先把列表外壳换成 `LiquidGlassTable`。
2. 将单行输入、筛选和主要按钮换为公共组件；图标按钮使用 `circle`，填写 `aria-label`。
3. 将原有分页参数连接到双向模型；服务端列表连接单一 `change` 加载回调。
4. 移除页面中重复的控件背景、边缘、表格材质和分页样式，保留布局、业务状态和交互样式。
5. 核对翡翠绿与深色壁纸、明暗模式、减少透明度、空结果、键盘操作和窄屏。

回归入口：`npm run test:liquid-glass`、对应页面测试与 `npm run build`。现有接入可参考 `RegistryProjectList.vue`（本地分页）和 `McpPublicationList.vue`（服务端分页）。
