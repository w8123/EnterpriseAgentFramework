# 管理端液态玻璃设计系统维护说明

本文是 `ai-admin-front` 液态玻璃（Liquid Glass）主题的长期维护说明。改造过程与批次证据见 [实施记录](../plans/液态玻璃UI改造实施记录.md) 和 [迁移矩阵](../plans/液态玻璃UI改造迁移矩阵.md)；早期 [Glass Workbench 方案](../plans/前端Glass-Workbench设计系统与UI重构.md) 只作历史参考，冲突时以本文为准。

## 1. 分层

| 层 | 文件 | 内容 | 规则 |
| --- | --- | --- | --- |
| 品牌原语 | `src/styles/tokens/_brand.scss` | 7 套主题色（默认 tech-purple）的主色、渐变、选中面，含深色选中面 | 新增品牌必须同时补浅色与深色选中面，并更新 `check:glass-theme` 的 `brandRuleOracles` |
| 语义 token | `src/styles/tokens/_semantic.scss` | `--text-*`、`--surface-*`、`--border-*`、`--status-*`、`--shadow-*`、`--blur-*`，浅色在 `:root`，深色在 `[data-theme='dark']` | 页面只用语义 token，不写字面色；深色只在这里切换 |
| 密度 | `src/styles/tokens/_density.scss` | `--control-height`、`--row-height`、`--section-gap`、`--panel-padding`、表格行内边距 | 全局密度写在 `html[data-density]`；页面 `.density-*` 类只做局部覆盖 |
| 布局节奏 | `src/styles/tokens/_layout.scss` | `--layout-*` 页面间距 | 受 `check:page-rhythm` 锁定，不随密度变化 |
| 材质 | `src/styles/_glass.scss` | `.glass-surface-shell / panel / control / overlay` 与小尺寸 `.glass-surface-lens`，含不支持 `backdrop-filter` 与减少透明度时的不透明降级 | 新组件选一种材质类，不自己写 `backdrop-filter` |
| Element Plus 映射 | `src/styles/_element-plus.scss` | `--el-*` 变量映射到语义 token | 不替换 Element Plus，只映射变量 |
| 组件配方 | `src/styles/_components.scss`、`_overlays.scss` | 按钮、表格、标签、弹层等全局配方 | 表格落在磨砂阅读玻璃上（见第 10 节），横向滚动时固定列改用近不透明底，避免透出重叠文字 |
| 边缘折射 | `src/components/common/LiquidGlassLayer.vue` | 圆角 SDF + 曲面高度 / Snell 折射查找表 + 法线高光（liquid-glass-react / liqui-design，MIT） | 父元素加 `liquid-glass-host`；仅 Chromium 生效，其它浏览器自动退回普通玻璃 |

## 2. 用户偏好

`src/composables/useTheme.ts` 管理 `themeMode`（浅色默认、深色、跟随系统）、`brandTheme`、`density`（紧凑 / 标准 / 宽松，默认标准）、`reduceTransparency`、`glassRefraction`，写到 `<html>` 的 `data-theme`、`data-brand`、`data-density`、`data-reduce-transparency`、`data-glass-refraction` 属性，深色同时加 `.dark`（Element Plus 暗色变量）。`index.html` 内联脚本在首屏前写入这些属性，避免刷新闪色。

偏好入口在侧栏「设置 → 外观」；明暗入口由 `DARK_THEME_ENTRY_READY` 控制，2026-10 全站走查后已打开。

## 3. 嵌入式 SDK 隔离

`src/conversation/styles/conversation-tokens.css` 是管理端与 SDK 共用的对话 token：

- 管理端内对话跟随 `html.dark` / `html[data-theme]` 和品牌色。
- SDK（`.eaf-chat`、`.reachai-chat-root`）只认自身根节点的 `data-theme`，不读取宿主页的 `html.dark` 或宿主定义的 `--text-*`、`--brand-*`。宿主（如芋道）切换深色不会染色对话框。
- `eaf.*`、`Eaf*`、`X-EAF-*` 是 SDK 契约身份，改样式时不要改这些标识。

## 4. 新增或修改页面时

1. 颜色只用语义 token；需要新语义时先加到 `_semantic.scss`（浅色 + 深色），再使用。
2. 不在页面里写 `[data-theme='dark']`、`html.dark`、`.is-dark*` 覆盖块，`check:dark-theme-compatibility` 会拦截项目列表的回退。
3. 外壳和浮层用材质类；弹层统一用 `AppDialog` / `AppDrawer`（`check:overlay-migration`）。
4. 页头用 `PageHeader`，节奏遵守 `check:page-rhythm` / `check:page-header-family`。
5. 允许保留的局部色板：Workflow 节点分类色、`CodeSnippetBlock` 终端色、登录页插画色；新增例外要写进迁移矩阵。
6. 验证：`npm run build`，跑 `check:glass-theme`、`check:dark-theme-compatibility`、`check:overlay-migration`、`check:page-rhythm`、`check:page-header-family` 和相关 `test:*`；浅色、深色、至少一种非默认品牌各看一遍页面。

## 5. 第三方代码

见 `ai-admin-front/THIRD_PARTY_NOTICES.md`（MIT 的液态玻璃算法，以及 Unsplash License 的主题壁纸）。新增移植代码或随包分发的视觉素材必须同步登记来源和许可证。

## 6. 清透透镜材质（2026-10-20）

- 先采样背景并做约 1px 模糊与曲面位移，再叠加着色和法线高光，文字与控件始终位于光学层上方。宿主启用折射后背景必须透明；把高不透明度背景留在宿主上会先遮住被采样的内容。
- `LiquidGlassLayer` 使用独立的 `liquid-glass-layer / tint / specular` 装饰层，默认边缘带 24px、最大采样偏移 32px。小工具栏可适度缩小；不要对表格行或每个节点都生成贴图。
- 侧栏、外观弹出面板、页头操作坞、Studio 工具栏和 AI 编辑条复用同一实现；`AppDialog` / `AppDrawer` 使用 `host-selector` 和 Teleport 将光学层挂到外壳，避免误作用于默认 slot 内的表单。
- Dialog / Drawer 正文直接落在弹层的磨砂玻璃上（28px 模糊 + 高浓度着色），分组卡片用 `--surface-glass-inset`。共享 `--surface-glass-overlay` 在浅色、深色模式均保持 96%–98% 不透明度，让弹窗、抽屉、确认框和弹出菜单覆盖底层业务文字；开启折射后着色层仍遵守这一浓度，边缘高光与双层亮边保留玻璃质感。遮罩只负责轻度压暗背景，不先模糊整页，否则透镜采样时已经没有可辨认的边界。
- 普通模糊：外壳 16px、内容面板 18px、磨砂面 22px、弹层 28px；折射层浅色 1px、深色 1.5px。CSS 变量仍是参数入口，页面不私写材质。
- 贴图按布局尺寸生成，不使用进入动画中的 `getBoundingClientRect()` 缩放值。最大边与面积受限，16 项 LRU 支撑重复打开；减少透明度、关闭折射和卸载会移除实际光学层并释放监听。
- 「减少透明度」会隐藏透镜并使用不透明表面。验收清透效果前应确认该偏好关闭，同时验证它开启时仍能正确降级；不得用清除用户全部偏好替代正常切换。
- 视觉验收使用条纹、明暗边界和彩色背景：边缘可见弯折，中部保留背景轮廓，开关折射有清晰差别；随后回到真实业务页面核对正文、菜单、表单、焦点和滚动。
- 默认背景使用用户确认的曲面壁纸，提供可辨认的折射边界；主内容区仍以独立阅读面承载数据。

## 7. 主题壁纸（2026-10-20）

- 六张 WebP 位于 `ai-admin-front/public/theme-wallpapers/`，合计 936,664 字节。`manifest.json` 记录原作者、原图、导出 URL、SHA-256 与七套品牌映射；科技紫、星云紫复用紫色曲面，其余五套分别使用各自选图。资源随构建分发，浏览器不请求外部图片服务。
- `_brand.scss` 统一定义 `--theme-wallpaper-image`、`--theme-wallpaper-position` 和壁纸前景原语 `--wallpaper-ink / ink-muted / ink-shadow`。深色壁纸采用浅色前景，浅色壁纸采用深色前景；这一对比关系独立于内容界面的明暗模式。
- `_semantic.scss` 的 `--surface-page-background` 直接绘制原始壁纸，渐变只位于图片下面作为加载失败时的回退。`--wallpaper-fallback` 与原图保持相同明暗，确保回退时裸露文字仍有对比。不叠加全屏白色或压暗蒙层，不对整张背景加模糊。此前按明暗模式遮色会让深海蓝、紫色和玫紫色发灰，已取消该处理。
- `AppPageBackground` 使用 `cover`、品牌位置和 `no-repeat`，跟随所在工作台尺寸，页面滚动不平铺图片。页头通过 `--page-header-material: var(--surface-page-background)` 复用同一背景与明暗处理，保留既有标题渐隐和操作坞。
- Studio 页面也铺主题壁纸，画布使用较薄的 `--surface-glass-canvas` 白纱，节点卡片、配置面板保留各自的阅读底（见第 10 节）。
- 阅读保护放在局部表面：导航、控件与浮层的着色在各自玻璃层内完成，正文保留既有阅读底。裸露的 PageHeader 标题、说明和面包屑消费 `--text-on-wallpaper*`；plain 页头不再模糊其背后的壁纸，操作坞保留独立阅读底，范围标签由 `PageHeaderTags` 提供薄着色与语义色。
- 更新背景时先维护来源清单与素材，再更新品牌映射和主题 / 页头契约。验证七套明暗、窄屏、表单阅读底与减少透明度；不要以远程热链替代随包资源。

## 8. 菜单选中透镜（2026-10-20）

- 左侧主菜单的当前项使用 `.glass-surface-lens` 胶囊，移除原来的色块与左侧色条。菜单高度、缩进、分组、路由和键盘操作保持原有规则；展开的父级仅强调文字，收起时所属父级复用胶囊。
- 材质消费 `_semantic.scss` 中明暗两套 `--surface-glass-lens / border-glass-lens / shadow-glass-lens / glass-lens-rim`。薄着色、定向亮边、细微品牌色边缘和局部投影共同表现厚度；不改变全屏壁纸颜色。
- `LiquidGlassLayer source="wallpaper"` 用同一张本地壁纸及相同裁切位置生成视口对齐的光学底，执行现有 SDF / Snell 位移与法线高光。这样小透镜不会只采样到父侧栏已经磨白的着色。它用于全屏壁纸工作台的导航；弹窗、抽屉及覆盖业务内容的操作层继续使用默认 `source="backdrop"`。
- 光学底按菜单位置、侧栏滚动和视口尺寸同步；只有尺寸或光学参数变化才重建位移图。文字和图标是独立上层，不进入滤镜。当前项以块级装饰容器承载透镜，避免 Element Plus 在收起时把它当作文字 span 隐藏。
- 开启光学层时使用 `--text-on-wallpaper`；不透明或普通玻璃降级时使用 `--brand-on-surface`，确保浅色和深色阅读底均有对比。减少透明度、强制颜色和不支持折射时保留可辨认的当前项。

## 9. 磨砂材质与水滴选中（2026-10-20）

- `.glass-surface-frost` 是半透明磨砂面（`--surface-glass-frost / shadow-glass-frost / glass-blur-frost`），用于统计条 `MetricStrip` 和侧栏项目选择胶囊；不支持模糊或减少透明度时退回 `--surface-fallback-panel`。
- 白纱浓度由品牌原语 `--frost-veil-alpha` 决定：深色壁纸（科技紫、星云紫、珊瑚玫、深海蓝）为 0.7，浅色壁纸为 0.42，保证磨砂面上的文字在七套壁纸上都清楚。
- `CollapsibleHeaderRegion` 的吸顶容器背景保持透明。容器是直角的，给它铺背景会在圆角页头和统计条外露出方形底，看起来像“没抠干净”。
- 菜单选中透镜叠加 `--glass-droplet-highlight / caustic`（顶部高光、底部品牌焦散）和渐变亮边，切换时用 520ms 的形变回弹动画模拟水滴落定；减少动态效果时不播放动画，强制颜色模式隐藏装饰层。
- 项目选择下拉使用 `popper-class="sidebar-project-popper"` 的玻璃弹层，焦点提示由整颗胶囊的描边承担。

## 10. 全站通透化（2026-10-20 第二轮）

参考 liquidglassdesign.com：透明着色的面板、双层亮边、玻璃里再叠一层玻璃、更强的模糊。

- 内容面板 `--surface-glass-panel` 与外壳 `--surface-glass-shell` 的白纱浓度改为跟随 `--frost-veil-alpha`（深色壁纸 0.62、浅色壁纸 0.42），浅色壁纸上更通透，深色壁纸上文字仍清楚。
- 表格：`.el-table` 自身是磨砂阅读玻璃（`--surface-glass-reading` + `--glass-rim-double`），行透明，表头 `--surface-reading-header` 轻提亮，悬停 / 当前行 / 斑马纹用 `--surface-reading-*`。表格没有横向滚动时固定列也透明；横向滚动（`.el-table:not(.is-scrolling-none)`）时固定列改用 `--surface-reading-sticky`。
- 嵌套玻璃：面板或弹窗里的分组卡片、运营中心卡片使用 `--surface-glass-inset` + `--shadow-glass-inset`（双层亮边），不再铺白底。
- 卡片 `el-card` 与 MessageBox 使用磨砂 / 弹层玻璃；弹层（AppDialog、AppDrawer、GlassDialog）顶部叠 `--glass-droplet-highlight` 高光和双层亮边，遮罩由 0.42 调浅到 0.26。
- 页面不要在 scoped 样式里把 `.glass-surface-*` 覆盖成实色背景（此前「我的记忆」「跨域数据擦除」因此整页发白）；需要磨砂卡片直接用 `.glass-surface-frost`。
- Studio：`.studio-page` 铺壁纸，画布 `--surface-glass-canvas`，顶栏与状态栏为外壳玻璃，配置面板为内容面板玻璃，小地图为磨砂面。

## 11. 运营看板的独立玻璃卡片（2026-10-20）

- 看板外壳 `.ops-dashboard` 保持透明，不再整页铺磨砂底、网格、彩色光晕或顶部白色反光；每张卡片直接采样工作台壁纸，避免多层白纱与模糊叠加后发灰。
- KPI 复用 `.glass-surface-panel`，数据面板与页头操作坞复用 `.glass-surface-frost`；页面不覆盖这些材质的背景与模糊，保留深色、不支持模糊和减少透明度时的共享降级行为。页头标题使用壁纸前景语义色，大屏模式在自身根节点铺同一张壁纸。
- KPI 以数值为主，图标缩为右上角小方块，说明最多显示两行；趋势工具条保持透明，内部汇总和项目卡片只用轻量 inset 表面，不再重复模糊或叠加大块控件白底。统计语义、数据来源与用户保存的布局保持原有规则。
- 看板通过 `.glass-workspace-adaptive` 消费壁纸配色：浅色模式遇到科技紫、星云紫、珊瑚玫和深海蓝的深色壁纸时，局部使用烟色玻璃、浅色正文和柔和亮边，避免高浓度白纱形成灰紫色块。翡翠绿、极光青、日冕金保持原有浅色玻璃；用户选择的明暗模式与看板外的弹层配色保持独立。
- 适配规则只在 `_semantic.scss` 绑定局部语义色，页面不写品牌/深色选择器。运营别名在看板根节点重新绑定，确保正文、状态色和数据强调色一起适配；`--color-data-accent` 用于玻璃上的图标与图表，在深色表面提亮。减少透明度时使用配套的不透明深色阅读底。

## 12. Workflow 节点的厚磨砂玻璃（2026-10-20）

- `StudioNodeCard` 统一使用 `.glass-surface-node`，浅色底保持 94%–98%、深色底保持 96%–98% 不透明度。玻璃质感由柔和高光、双层亮边、底缘暗部和浮起阴影共同体现，正文不直接透出背景网格或连线。
- 材质在 `_glass.scss` 维护，明暗底色与边缘、阴影分别由 `--surface-glass-node / glass-node-rim / shadow-glass-node` 控制；说明区和端口区使用 inset 表面。节点分类色、选中提示与运行状态继续表达原有语义，叠加状态时保留玻璃边缘。
- 节点不挂载逐卡折射贴图，减少透明度和不支持模糊时使用共享不透明降级。未配置节点保留虚线提示和相同的阅读底，不能用低浓度警告色替换整个材质。

## 13. 小尺寸液态控件预览（2026-10-20）

- `LiquidGlassControl` 以现有 `.glass-surface-lens` 和 `LiquidGlassLayer` 装饰 Element Plus 按钮、输入框和选择器。当前仅在项目管理页预览，材质方向确认后再评估其他页面；不全局替换控件配方。
- 按钮和字段共用曲面边缘、非均匀亮边与底缘焦散。主操作叠加薄品牌着色，移除内部按钮、输入框的不透明底色；文字与图标始终位于光学层上方。指针只改变高光位置，不重新生成折射贴图。
- 项目管理的统计条、列表与主操作按表面选择 `.glass-workspace-adaptive`。科技紫、星云紫、珊瑚玫和深海蓝在浅色模式下采用烟色阅读底、浅色正文与柔和亮边，表头和悬停行只轻微提亮；控件降低白色高光和品牌焦散，保留背景曲面。翡翠绿、极光青、日冕金沿用现有配方，页面不增加品牌选择器，也不切换用户的明暗模式；接入对话框保留独立的浮层配色。
- 工作台壁纸上的小控件可选 `source="wallpaper"`，按视口坐标采样同一张本地壁纸，避免先采到父面板的磨砂白纱；其他场景默认采样实际背板。小控件边缘带 7px、最大采样位移 12px，复用现有缓存和释放机制。
- `LiquidGlassSelect` 统一闭合字段与展开浮层：外层复用磨砂材质与壁纸配色，内部不再叠加白底、模糊或原生三角尖角。项目管理页的展开层独立采样壁纸，避免底下的表格文字透进选项；选项用轻量 inset 表面、字重和勾号表达选中，键盘、清除、加载、禁用和表单语义继续由 Element Plus 负责。普通场景默认采样实际背板。
- 下拉外缘由 `LiquidGlassDropdownSurface` 承载左上角连体水滴：小圆形水滴与列表平滑融合，保留轻微收颈和不对称肩部。`liquidSurfaceSdf` 同时生成可见轮廓、折射位移和法线高光，避免只改裁切形状后光线仍沿矩形走；文字、选项和点击区域保持正常布局。关闭折射或减少透明度时，轮廓内使用不透明阅读底；形状保持静止，展开沿用原生过渡。
- `LiquidGlassPagination` 将前后翻页与页码放进连续玻璃胶囊，当前页的内部选中块随页码平滑移动；每页条数复用同一选择器。原生分页器继续处理页码、总数与禁用状态，更改每页条数后回到第一页。选中块只做 CSS 位移，不随翻页重建折射贴图；窄屏可换行。
- 关闭折射、减少透明度、强制颜色和减少动态效果分别沿用共享降级；按钮焦点落在整颗玻璃外缘，字段聚焦时保留整体焦点提示。验收同时观察真实页面和有明暗边界的背景，不能用只有圆角、渐变和阴影的效果代替曲面折射。

## 14. 公共组件接入

统一入口为 `@/components/common/liquid-glass`，提供按钮、输入框、选择器、分页、筛选栏、视图切换，以及组合现有 `WorkbenchPage` / `DataTableShell` 的页面壳与列表容器。项目管理与 MCP 对外发布列表分别验证本地分页和服务端分页；Workflow、Agent、Skill 管理和知识库列表复用相同组件，并统一卡片内滚动、窄屏统计和固定列处理。`PageHeader` 内置公共 `PageHeaderTags`，原有 `#tags` 自动排列在标题与说明下方，采用轻量语义色、窄屏换行和可访问的收起行为，不再给整个标签组叠加背景。属性、事件、插槽、材质来源及迁移示例见[公共组件接入指南](./frontend-liquid-glass-usage.md)。

- `LiquidGlassNav / Tabs / Dropdown` 统一业务能力与 API 市场的导航、标签页和整理菜单，继续使用原生链接、离开确认、菜单命令及禁用语义。菜单复用选择器的水滴表面，不在页面中复制异形光学层。
- `LiquidGlassCard / EmptyState` 统一目录卡片、内容分区和项目范围提示，采用共享阅读玻璃；业务内容与重试动作仍由页面传入。不要给长内容或每张卡片生成折射贴图。
- 公共 `DataTableShell` 和 `LiquidGlassCard` 带 `glass-loading-surface`。原生加载层使用约 13% 的主题薄雾、轻模糊与小型中央提示，保留原材质和已有内容；深色壁纸自动采用烟色，减少透明度关闭模糊，强制颜色使用系统色。只针对这些阅读容器，不改变对话框和全屏遮罩。
- 独立卡片目录可用 `LiquidGlassTable presentation="collection"`，由卡片承担阅读底，外层保留筛选、滚动、加载和分页而不叠加整片玻璃。Skill 管理页采用这一组合，并用 `SkillCatalogCard` 分离名称、用途、包标识、治理状态和版本信息，避免多个相同分量的标签与统计块抢占首屏。
- 少量计数使用 `MetricStrip presentation="inline" source="wallpaper"` 作为页头辅助摘要，颜色始终跟随壁纸；手机布局保留行内字号和换行，不套用面板式统计的放大规则。
