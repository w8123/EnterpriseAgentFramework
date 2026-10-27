# 管理端设计与组件

管理端是工作台，优先信息密度、扫描效率与可重复操作。视觉用于表达当前任务、数据和操作层级，状态、身份与业务约束来自后端。

## 页面结构

- 先核对路由、`MainLayout.vue`、共享导航与项目作用域，再调整页面；内容切换保持布局壳稳定。
- `PageHeader` 统一标题、说明与主操作，不另建重复 hero、返回栏或范围卡片。
- 列表使用连续工作面，搜索、筛选、视图切换、表格与分页形成一条任务路径。空、加载、失败、无权限和局部数据缺失分别表达。
- 项目、账号、TAB 或路由变化使旧请求与确认失效；未保存输入、冲突和写操作保持明确保护。
- 窄屏自然换行，宽表局部滚动；键盘、焦点、Escape、关闭回焦和可访问名称随改动一起验证。

## 主题的唯一维护位置

| 层 | 位置 |
| --- | --- |
| 品牌 | `src/styles/tokens/_brand.scss` |
| 语义颜色、表面、边框和状态 | `src/styles/tokens/_semantic.scss` |
| 控件密度 | `src/styles/tokens/_density.scss` |
| 页面间距与布局节奏 | `src/styles/tokens/_layout.scss` |
| 材质与回退 | `src/styles/_glass.scss` |
| Element Plus 映射与组件配方 | `_element-plus.scss`、`_components.scss`、`_overlays.scss` |
| 折射层 | `src/components/common/LiquidGlassLayer.vue` |
| 用户偏好 | `src/composables/useTheme.ts` |

业务页面使用现有 token，不散落硬编码颜色，不单独判断减少透明度，也不复制模糊、高光、厚边、阴影或实色回退。新材质先扩展公共语义与组件；减少透明度、暗色、强制颜色和不支持滤镜的回退由公共层处理。

## 公共入口

```ts
import {
  LiquidGlassPage, LiquidGlassFilterBar, LiquidGlassTable,
  LiquidGlassButton, LiquidGlassInput, LiquidGlassSelect,
  LiquidGlassTabs, LiquidGlassDropdown,
} from '@/components/common/liquid-glass'
```

| 组件 | 用途 |
| --- | --- |
| LiquidGlassPage / PageHeaderTags | 页面布局与页头辅助信息 |
| LiquidGlassFilterBar / Input / Select | 搜索、筛选、表单输入 |
| LiquidGlassTable / Pagination | 表格、空态与分页；业务列由使用方提供 |
| LiquidGlassButton / Dropdown | 主次操作、图标按钮和菜单 |
| LiquidGlassNav / Tabs / ViewToggle | 工作区、标签和视图切换 |
| LiquidGlassCard / EmptyState / Notice | 内容分区、状态与页面提示 |

属性、事件和插槽遵循包装组件与其原生 Element Plus 控件。业务页面继续提供 loading、disabled、权限和业务处理，不因换皮肤重写操作。`class/style` 的外壳应用、原生 ref 与扩展属性以 [公共导出](src/components/common/liquid-glass/index.ts)及组件定义为准。

## 弹层与对话

普通弹窗和抽屉使用 `AppDialog` / `AppDrawer`，保留挂载、宽度、事件、焦点循环和关闭语义。非模态 Studio 面板按原挂载方式处理，不为样式统一改变交互。材质覆盖只证明外观，不代表保存、发布或认证业务已验收。

管理端与 SDK 共用的对话样式在 `src/conversation/styles/conversation-tokens.css`。SDK 只读取自身根节点主题，不被宿主的 `html.dark` 或同名变量污染；`eaf.*` 与 `Eaf*` 是兼容技术身份。

## 业务能力与 Studio

Java 方法和 HTTP API 目录均为平铺分页表，分类是可选元数据。关键词与下拉分开保存，刷新或分类变化不能丢失未提交输入。普通模式隐藏批量调整控件，进入“整理 → 调整分类”后就地维护成员；失权、未知分类与确认未分类分开处理。

Studio 节点选中后在右侧直接编辑，保留一层配置/测试/调试记录；主配置优先，高级契约按需展开。名称、变量插入、提示词预览与当前节点操作贴近。运行语义与发布约束见 [Workflow](../docs/workflow.md)，不能靠画布表现新增节点能力。

## 验证

运行相关组件/行为测试和 `npm run build`，按改动检查 `check:glass-theme`、`check:dark-theme-compatibility`、`check:glass-components`、`check:overlay-migration`、`check:page-rhythm` 与 `check:page-header-family`。新断言不削弱既有契约。

浏览器覆盖桌面/窄屏、亮暗与品牌、减少透明度、加载/空/失败、键盘、焦点和取消。夹具注明范围；发布使用的 SDK 另验证宿主隔离。新增第三方算法或素材同步 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。
