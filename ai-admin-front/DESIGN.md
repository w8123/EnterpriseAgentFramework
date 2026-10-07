# ReachAI 管理端设计上下文

本文件是管理端设计上下文入口，不复制既有规范。完整视觉方向与迁移原则以 [`../docs/plans/前端Glass-Workbench设计系统与UI重构.md`](../docs/plans/前端Glass-Workbench设计系统与UI重构.md) 为准。

## 产品气质

- 面向企业研发、平台管理员和运行治理人员，是高频工作台，不是营销落地页。
- 延续 Glass Workbench：浅蓝灰画布、克制的蓝青环境光、轻玻璃表面、薄边框和高信息密度。
- 页面首屏优先呈现范围、状态、主要任务和下一步；装饰不能压过数据与操作。
- 来源变化以自动处理为默认路径，所属项目内只提示需要业务判断的当前例外；批次、策略与审计放入详情和处理记录，不组织成用户必须逐步完成的流程。

## 运行时映射

- Token 所有权：运行时 CSS 为事实源。
- 品牌原语：`src/styles/tokens/_brand.scss`。
- 语义颜色、表面、文字和状态：`src/styles/tokens/_semantic.scss`。
- 密度与布局：`src/styles/tokens/_density.scss`、`src/styles/tokens/_layout.scss`。
- 全局基线与滚动条：`src/styles/index.scss`。
- Element Plus 映射：`src/styles/_element-plus.scss`。

当前正式入口只提供亮色主题和品牌色切换。暗色 token 作为兼容层保留，在完成跨页面验收前不向用户展示不可用的明暗切换控件。

## 变更约束

- 新页面优先复用共享页面骨架、标题、筛选、表格、反馈和 Overlay；不在单页复制等价视觉角色。
- Durable token 的修改必须同时更新运行时 owner、相关契约测试与浏览器证据。
- 可滚动内容必须继承全局可见滚动条；局部只允许声明 `scrollbar-gutter` 等几何例外，不得隐藏滚动条。
