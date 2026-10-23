# 液态玻璃 UI 改造迁移矩阵

> 本表随每批改造更新。来源：`ai-admin-front/src/router/index.ts`（2026-10-20，dev `25cf5730`）与各页面实际引用的弹层组件。
> 实施记录见 [液态玻璃UI改造实施记录](液态玻璃UI改造实施记录.md)。
> 第三轮（2026-10-21，R3-x）：把仍直接使用 WorkbenchPage、el-alert、实色卡片与原生筛选条的页面分批接入 `@/components/common/liquid-glass` 公共组件；“已迁移（R3-x 公共组件接入）”表示该页已完成这一轮。
> 第四轮（2026-10-22，R4-x）：剩余子页面、二级/三级页面、详情页、标签页、弹窗、抽屉与局部侧栏公共组件改造。全站弹窗与抽屉内分段标签收敛至 `LiquidGlassTabs`，弹层 footer 操作收敛至胶囊圆角与 `LiquidGlassButton`，代码配置区等宽与凹槽微质感对齐。
>
> R4-5 起（2026-10-22）：剩余弹窗/抽屉 footer 原生按钮批量换为 `LiquidGlassButton`（文字链接按钮除外），弹层内写死的实色输入/代码底改为玻璃与代码 token；明细见实施记录 R4-5 及之后条目。
> F 批（2026-10-20）：全部路由已在浅色与深色下本地走查（`F-dark-*.png`），明暗入口已开放；2026-10-20 线上验收通过（部署线程按步骤执行，截图在 `/mnt/project-files/acceptance/liquid-glass-f*/`）：浅色和深色下的主要页面、密度、主题色、芋道深色宿主的嵌入对话，都没有控制台错误和 4xx/5xx。逐行“验证”列保持本地证据；凡是线上截图覆盖到的页面，以线上截图为准。

状态说明：

- 迁移：`待处理` → `公共层覆盖`（只通过 token / 公共组件 / Element Plus 映射获得新主题，页面无需改动且已检查）→ `已迁移`（页面内硬编码颜色、玻璃底、阴影已改为语义 token）。
- 验证：`未验证` → `构建` → `本地浏览器` → `线上`（reachai.w8123.com / yudao.w8123.com）。
- 证据：截图在 `/mnt/project-files/screenshots/liquid-glass/`（项目共享目录），检查命令输出记录在实施记录。

## 1. 全局与访问状态

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 登录 | `/login` | 独立页 | — | 已迁移（登录卡与表单；左侧插画保留局部色板） | 本地浏览器 | `screenshots/liquid-glass/C-login.png` | |
| 认证不可用 | `/auth-unavailable` | 独立页 | — | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/C-auth-unavailable.png` | |
| 访问受限 | `/access-denied` | 状态页 | LiquidGlassCard（wallpaper）、LiquidGlassButton | 已迁移（R3-10：主题壁纸 + 玻璃卡） | 本地浏览器 | `screenshots/liquid-glass/round3-20261021/b10-*` | |
| 布局壳 | `MainLayout`（standard / project-workbench / edge-to-edge / studio） | 布局 | AppPageBackground | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/B-local-dashboard.png` | studio 布局在 E 批核对 |
| 侧栏与外观设置 | 全局 | 导航 | AppSidebar、sidebarMenu | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/B-local-appearance.png` | |
| 面包屑与返回 | 全局 | 导航 | AppBreadcrumb | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/C-knowledge.png` | |
| 项目范围选择 | 侧栏 | 导航 | AppSidebar、ProjectScopeState | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/C-agents.png` | |
| 探索阶段提示 | 全局弹窗 | 弹窗 | ExplorationStageDialog | 已迁移 | 构建 | | |
| 全局反馈 | Message / MessageBox / Notification / Loading | 弹层 | Element Plus 映射 | 公共层覆盖 | 构建 | | |

### 公共组件（C 批）

| 组件 | 迁移 | 验证 | 说明 |
| --- | --- | --- | --- |
| PageHeader | 已迁移 | 本地浏览器 | 标题区渐隐改用不透明阅读底；操作坞为导航玻璃 + 边缘折射；按钮回到全局配方；删除暗色补丁 |
| HeaderModeSwitch / HeaderMiniStat | 已迁移 | 本地浏览器 | 选中态用品牌渐变 token |
| GlassDialog / AppDialog / AppDrawer | 已迁移 | 构建 | 弹出层材质、阴影、模糊统一 token；减少透明度时回退实底 |
| WorkbenchPanel / FilterBar / DataTableShell / MetricStrip / WorkbenchPage | 公共层覆盖 | 本地浏览器 | 原本已用 token；全局密度偏好可覆盖默认 comfortable |
| StatusTag | 已迁移 | 构建 | 边框改为 30% 状态色，避免实线描边过重 |
| ProjectScopeState / GlassSectionHeader / GlassInfoItem | 公共层覆盖 | 本地浏览器 | |
| CodeSnippetBlock | 已迁移（例外） | 构建 | 固定深色终端底与语法色板，只在组件内使用 |

## 2. 概览

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 看板、指标、组件目录、布局编辑、快捷入口 | `/dashboard` | 看板 | DashboardGrid、widgets/*、DashboardLayoutToolbar | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/D-dashboard.png` | |

## 3. 项目管理（智能化改造）

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 项目列表 + 新建/编辑弹窗 | `/registry/projects` | 列表 | `_project-list.scss`、AppDialog、LiquidGlassTabs、LiquidGlassButton | 已迁移（R4-1 接入双标签页 + 令牌弹窗 LiquidGlassTabs/Button 与等宽配置） | 本地浏览器 | `screenshots/liquid-glass/D-projects.png` | |
| 项目详情（实例、同步诊断、来源变化、能力评审） | `/registry/projects/:projectCode` | 详情 | CapabilityReviewPanel、ProjectSourceChanges、ProjectBizIndexCard、GlassDialog：plain 页头 + 玻璃按钮、壁纸 LiquidGlassCard 入口组、实例 / 业务索引 LiquidGlassTable、LiquidGlassNotice、LiquidGlassTabs、LiquidGlassButton | 已迁移（R4-1 AI Coding 弹窗 3 标签页 + 编辑弹窗 LiquidGlassTabs/Button） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b8-*` | |
| 前端页面管理 | `/registry/projects/:projectCode/page-actions` | 工作区 | EmbedOpsMonitor：plain 页头 + 内联指标、LiquidGlassTable（卡片）、LiquidGlassFilterBar | 已迁移（R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 业务页面工作台 + 发布/验收/分析/历史弹层 | `/registry/projects/:projectCode/page-assistant` | 工作区 | page-workbench/*、PageAssistantWizard：plain 页头、LiquidGlassNav 工作区页签、磨砂玻璃页面区、LiquidGlassNotice、LiquidGlassTabs | 已迁移（R4-4 页面来源弹窗 LiquidGlassTabs） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b8-*` | |
| SDK 接入向导 | `/registry/projects/:projectCode/sdk-access` | 向导 | SdkAccessWizard、ai-coding/*：plain 页头、本地 `--sdk-*` 令牌改指向玻璃令牌、LiquidGlassNotice | 已迁移（R3-8 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b8-*` | |
| 嵌入式会话审计 | `/registry/projects/:projectCode/page-actions/sessions` | 列表 | EmbedSessionAudit：PageHeader、LiquidGlassTable（前端分页）、AppDrawer（LiquidGlassTabs） | 已迁移（R4-3 详情抽屉事件/动作 LiquidGlassTabs） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 兼容重定向 | `/registry/projects/:projectCode/sync-diagnostics` → 详情 | 重定向 | — | 不重建 | — | | |

## 4. 业务能力

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 统一工作台外壳 | `/business-capabilities` | 工作台 | BusinessCapabilityWorkbench | 已迁移 | 构建 | | |
| Java 业务方法目录 + 详情/调用弹窗 | `/business-capabilities/java-methods` | 列表 | BusinessCapabilityCatalogList、BusinessMethodDetailDialog、调用面板与入参编辑；LiquidGlassNotice（目录加载失败用 wallpaper，面板/弹窗内用 backdrop）、LiquidGlassTabs | 已迁移（R4-2 详情弹窗 5 标签页 LiquidGlassTabs） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b11-*` | |
| HTTP API 目录 | `/business-capabilities/http-apis` | 列表 | HttpApiCatalog | 已迁移 | 构建 | | |
| HTTP API 详情 | `/business-capabilities/http-apis/:id` | 详情 | HttpApiDetail、CredentialCreateDialog：LiquidGlassTabs、壁纸 LiquidGlassCard、LiquidGlassNotice、LiquidGlassButton，参数表透明行 | 已迁移（R3-7 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b7-*` | |
| 来源 / 语义 / 图 | `/business-capabilities/:category/(sources\|semantics\|graph)` | 工作区 | source/*、graph/*（G6）；LiquidGlassNotice（带重试操作） | 已迁移（R3-11 提示条） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b11-*` | |
| 分类编辑 | 弹窗 | 弹窗 | CatalogClassificationEditor、CatalogClassificationSections；LiquidGlassNotice | 已迁移（R3-11 提示条） | 构建 | | |
| 兼容重定向 | `/business-methods`、`/apis`、`/apis/:id`、`…/groups` | 重定向 | — | 不重建 | — | | |

## 5. Workflow

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Workflow 列表 + 新建弹窗 | `/workflows` | 列表 | WorkflowList | 已迁移 | 构建 | | |
| Studio 顶栏、画布、节点、添加入口、AI 编辑条 | `/workflows/:workflowId/studio` | 全屏工作区 | studio-canvas/* | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/E-studio.png` | |
| Studio 属性面板 | 同上 | 面板 | studio-panels/*、ConfigSection、studio-panels.scss；LiquidGlassNotice（backdrop，带操作） | 已迁移（R3-10 提示条与页签栏） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b10-*` | |
| Studio 版本/发布/调试/试运行/来源/评测弹层 | 同上 | 弹层 | studio-overlays/*；LiquidGlassNotice（backdrop）、WorkflowStudioSourceDrawer（LiquidGlassTabs/Button）、WorkflowStudioDebugDrawer（内联 LiquidGlassTabs） | 已迁移（R4-4 源码抽屉与调试步骤内联 LiquidGlassTabs/Button） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b10-*` | |
| 兼容重定向 | `/workflows/:workflowId/versions` → Studio | 重定向 | — | 不重建 | — | | |

## 6. Agent

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Agent 列表 | `/agent` | 列表 | AgentList | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/C-agents.png` | |
| Agent 配置（概览、Workflow 白名单、知识、Skill、协作、决策、记忆、嵌入、版本、高级抽屉） | `/agent/:id/edit` | 详情/表单 | components/agent/*、views/agent/edit/*：LiquidGlassNav 分区页签、LiquidGlassNotice（带操作） | 已迁移（R3-9 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b9-*` | |
| Agent 调试对话 + Trace 抽屉 | `/agent/:id/debug` | 调试工作区 | AgentDebug（执行阶段/审批 LiquidGlassTabs）、AgentDebugTraceDrawer（执行链路/原始响应 LiquidGlassTabs + LiquidGlassButton）、debug/*、conversation/* | 已迁移（R4-3 调试阶段/审批 LiquidGlassTabs 与 Trace 抽屉 LiquidGlassTabs/Button） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b9-*` | |
| 兼容重定向 | `/agent/:id/evals` → `/runops/evals/:id` | 重定向 | — | 不重建 | — | | |

## 7. 知识

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 知识库列表 + 新建弹窗 | `/knowledge` | 列表 | KnowledgeList | 已迁移 | 本地浏览器 | `screenshots/liquid-glass/D-knowledge.png` | |
| 知识库详情（文件、检索测试、命中分析、设置、关联 Agent） | `/knowledge/:code` | 详情 | KnowledgeDetail（内联指标、LiquidGlassTabs）、文件/命中分析（LiquidGlassTable）、检索测试（LiquidGlassCard）、设置（自适应 WorkbenchPanel） | 已迁移（R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |
| 文件详情（分段） | `/knowledge/:code/file/:fileId` | 详情 | FileDetail：LiquidGlassTable + 前端分页 | 已迁移（R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |
| 业务索引列表 / 详情 | `/biz-index`、`/biz-index/:code` | 列表/详情 | BizIndexList（筛选 + 分页 + 内联指标）、BizIndexDetail（搜索测试主栏 + 信息/接入侧栏） | 已迁移（R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |
| 兼容重定向 | `/knowledge/import`、`/retrieval` | 重定向 | — | 不重建 | — | | |

## 8. 集成与标准 Skill

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Skill 市场 | `/skill-market` | 卡片列表 | SkillMarket：内联指标、LiquidGlassTabs、LiquidGlassTable(cards) + FilterBar、LiquidGlassNotice；玻璃 SkillMarketCard | 已迁移（R3-4 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b4-*` | |
| Skill 管理 | `/skills` | 列表 | SkillCenter（制品证据 LiquidGlassTabs、导入/评审弹窗 LiquidGlassButton） | 已迁移（R4-4 制品证据 5 标签页 LiquidGlassTabs + 弹窗 LiquidGlassButton） | 构建 | | |
| API 市场 + 详情抽屉 | `/api-market` | 卡片列表 | ApiMarket、ApiMarketDetailDrawer（概览/Operation/治理 LiquidGlassTabs）；LiquidGlassNotice（接入弹窗、接入列表读取失败） | 已迁移（R4-2 详情抽屉 3 标签页 LiquidGlassTabs） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/D-api-market.png`、`screenshots/liquid-glass/round3-20261021/b11-*` | |
| MCP 互联中心（总览、对外发布、发布详情、调用流水） | `/mcp-hub/*` | 子页集合 | McpHubLayout（plain 页头 + LiquidGlassNav）、McpHubOverview（玻璃指标卡、周期切换）、McpPublicationList（FilterBar）、McpPublicationDetail（LiquidGlassTabs + 卡片、端点列表）、McpCallLogList（R3-2） | 已迁移（R3-12 提示条；R3-4 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b4-*` | |
| A2A 互联中心（总览、本地发布、远程 Agent、任务、信任、开发诊断） | `/a2a-hub/*` | 子页集合 | A2aHubLayout（plain 页头 + 原生 nav 透镜胶囊、子页玻璃加载层）、各子页 LiquidGlassTable + FilterBar（服务端分页单一 change）、A2aTaskCenter（调查抽屉时间线/消息/产物 LiquidGlassTabs）、LiquidGlassNotice / EmptyState、信任页 LiquidGlassNav 类型切换、总览磨砂指标卡 | 已迁移（R4-3 调查抽屉时间线/消息/产物 LiquidGlassTabs；R3-5 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b5-*` | |

## 9. 运行与自动化

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 运行记录 | `/runops` | 列表 | RunOpsList：内联指标、LiquidGlassCard 筛选、LiquidGlassTable ×3 | 已迁移（R3-2 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b2-*` | |
| 运行详情 / Trace | `/runops/:traceId` | 详情 | RunOpsDetail、RunOpsInvestigationWorkbench（差异对比 LiquidGlassTabs）、RunOpsDiffTable：plain 页头 + 玻璃按钮、LiquidGlassNotice、LiquidGlassEmptyState，调查工作台改为磨砂玻璃 | 已迁移（R4-3 对比区差异 LiquidGlassTabs；R3-7 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b7-*` | |
| 对话 | `/runops/conversations` | 列表 | RunConversations：壁纸筛选条、LiquidGlassCard 列表/详情、LiquidGlassNotice | 已迁移（R3-2 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b2-*` | |
| 评测总览 / Agent 评测 + 加入评测弹窗 | `/runops/evals`、`/runops/evals/:id` | 列表/详情 | EvalOverview（LiquidGlassTable）、AgentEval（自适应 WorkbenchPanel）、EvalWorkerNotice（LiquidGlassNotice）、AddToEvalDialog | 已迁移（R3-2 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b2-*` | |
| 自动化中心 | `/automations` | 列表 | AutomationCenter：内联指标、LiquidGlassNotice、LiquidGlassTable、LiquidGlassFilterBar | 已迁移（R3-12 提示条；R3-2 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b2-*` | |
| 兼容重定向 | `/run-center`、`/runops/automations` | 重定向 | — | 不重建 | — | | |

## 10. 身份与治理

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 账号与权限 | `/settings/platform-users` | 列表/表单 | platform-identity/*：内联指标、LiquidGlassTabs、LiquidGlassTable、LiquidGlassCard、LiquidGlassNotice | 已迁移（R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 业务用户目录 | `/settings/business-users` | 列表 | BusinessUserDirectory：LiquidGlassTable（服务端分页 `change`）、LiquidGlassFilterBar | 已迁移（R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 认证源配置 | `/settings/auth-providers` | 表单 | AuthProviderSettings：plain 页头标签、LiquidGlassTable | 已迁移（R3-12 提示条；R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 调用权限 | `/settings/tool-acl` | 列表 | ToolAclList：LiquidGlassNotice、LiquidGlassCard 角色栏、LiquidGlassTable（服务端分页 `change`） | 已迁移（R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 运行时用户映射 | `/settings/runtime-user-mappings` | 列表 | RuntimeUserMapping：LiquidGlassNotice、LiquidGlassTable、LiquidGlassFilterBar | 已迁移（R3-1 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b1-*` | |
| 跨域数据擦除 | `/compliance/erasure` | 表单 | DataErasure：plain 页头、LiquidGlassNotice、两张壁纸 LiquidGlassCard、查询用 FilterBar、结果元数据 dl + LiquidGlassTable | 已迁移（R3-6 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b6-*` | |
| 我的记忆 | `/me/memories` | 列表 | MyMemories：plain 页头 + 内联指标、LiquidGlassNav 视图切换、LiquidGlassTable(cards) + FilterBar、LiquidGlassEmptyState | 已迁移（R3-6 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b6-*` | |
| 兼容重定向 | `/settings/personal-memory` → `/me/memories` | 重定向 | — | 不重建 | — | | |

## 11. 模型

| 页面/入口 | 路由 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 模型中心 + 接入/目录设置弹窗 | `/model/instances` | 卡片列表 | LiquidGlassTable(cards) + LiquidGlassFilterBar + LiquidGlassNav 类型页签、玻璃 ModelInstanceCard、ModelOnboardingDialog、ModelCatalogSettingsDialog | 已迁移（R3-12 提示条；R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |
| 实例详情 | `/model/instances/:id` | 详情 | ModelInstanceDetail：两列自适应面板、LiquidGlassNotice、玻璃操作条 | 已迁移（R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |
| 模型调试台 | `/model/playground` | 调试工作区 | ModelPlayground：配置合并为一张 LiquidGlassCard、对话区 LiquidGlassCard；ModelTestResultPanel | 已迁移（R3-3 公共组件接入） | 本地浏览器（模拟数据） | `screenshots/liquid-glass/round3-20261021/b3-*` | |

## 12. 共享对话与嵌入

| 页面/入口 | 位置 | 类型 | 公共组件 | 迁移 | 验证 | 证据 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 对话视图（消息、Markdown、代码块、工具调用、流式、错误重试、输入区、附件） | Agent 调试、Studio 调试、嵌入 | 共享组件 | conversation/components/*、conversation-tokens.css | 公共层覆盖（共享对话 token） | 构建 | | |
| 嵌入式对话 SDK（浮动入口、记忆面板、主题预设） | 宿主页面（芋道） | SDK | sdk/EmbedChatHost、EmbedMemoryPanel、themePresets | 已迁移（宿主隔离） | 本地浏览器 | `screenshots/liquid-glass/E-sdk-host-dark.png`、`E-sdk-host-light.png` | |
