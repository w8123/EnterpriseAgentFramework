# Agent 配置与调试整理方案

> 2026-10-19 起草。依据：[平台定位与架构总览](../01-平台定位与架构总览.md)、[Workflow Studio 整理方案](Workflow%20Studio整理方案.md)、[知识模块整理方案](知识模块整理方案.md)、[运行中心整理方案](运行中心整理方案.md)。目标：实施人员从「新建 Agent」到「改配置、调通、发布、看效果」只在一个地方完成，调试时看到的就是马上要发布的配置；默认配置在生产环境也是安全的。

## 1. 现状（dev 6d73982f 核对）

| # | 问题 | 证据 | 影响 |
| --- | --- | --- | --- |
| P1 | 「调试」跑的是线上版本，不是刚保存的草稿 | 配置页顶部「保存草稿 / 调试 / 发布配置」并排；调试页走 `/api/runtime/agents/execute/stream`，请求不带配置版本；Runtime `RuntimeAgentExecutionContextResolver.resolve` 只加载 `ACTIVE` 配置。评测已经能对草稿做快照（`resolveForEvaluation` 允许 DRAFT），调试不能 | 改了提示词或 Workflow 保存后去调试，看到的仍是旧行为；只能先发布再试，等于拿线上用户做测试。从未发布过的新 Agent 根本无法调试 |
| P2 | 新建 Agent 默认「研发模式」 | 前端默认 `policyProfile: 'DEV_ALLOW_ALL'`，Runtime `RuntimeAgentConfigService` 缺省也补成它；`SupervisorToolPolicyService` 在该模式下跳过租户检查和 Workflow 所需权限检查 | 每个 Agent 上线后都处在放宽检查的状态，而这个开关藏在「高级运行设置」里，没人会去改。ERP 助手很可能也是（推断，未查线上） |
| P3 | 主文件太大 | `AgentEdit.vue` 3889 行（模板 1028、脚本 1120、样式 1740），`AgentDebug.vue` 2610 行，`AgentList.vue` 1545 行 | 每次给 Agent 加一项配置（记忆、嵌入都是这样加进来的）都在同一个文件里改，容易互相影响 |
| P4 | 有死代码和重复入口 | `AgentEdit.vue` 末尾的「Agent 配置版本」抽屉（`versionDrawerVisible`）没有任何地方打开，与「版本记录」页签重复；`AgentEval.vue` 已只作为运行中心评测页使用，却仍放在 `views/agent/` | 改版本逻辑要改两处，读代码的人容易被误导 |
| P5 | 知识库在 Agent 页上看不见 | 知识库只能在知识库详情页「接入 Agent」（`POST /api/agents/{id}/knowledge-qa`）；回到 Agent 页，它只是「可调用 Workflow」列表里一条 `kb-qa-…` | 想知道「ERP 助手能查哪些知识」要逐条看 Workflow 标识；在 Agent 页上加不了知识库 |
| P6 | 详情页到运行效果断开 | Agent 详情页（概览 / 配置 / 版本记录 / 嵌入）没有到「运行记录」「评测」的入口，只有列表页的行操作能跳过去；概览里没有最近运行和最近一次评测结果 | 改完发布后要回列表再进运行中心才能看效果 |
| P7 | 技术词和不该让人填的字段 | 列表列名 `keySlug`、「Supervisor 配置」；调试页「Supervisor Runtime」；版本表「运行时」列；每个 Workflow 都展开「调用标识 / 所需权限 / 自定义输入输出 Schema」；协作 Agent 的「可委派任务」显示原始 ID；高级设置里有「自定义策略（JSON）」 | 运营人员看不懂，大多数字段填了反而出错 |

记忆、嵌入、Workflow 版本过期提示和「更新并发布」、Skill 绑定、Agent 协作这些能力本身可用，这次保留，只调整位置和展示。

## 2. 分批

### A1 调试跑草稿（Runtime + 管理端）
- Runtime：调试入口（`entryType=DEBUG`）可以带 `configVersionId`，允许本 Agent 的 DRAFT 或 ACTIVE 版本，复用评测已有的 `resolveForEvaluation` 校验；其他入口（嵌入、网关、A2A、自动化）仍只用线上版本，不变。运行记录写入实际使用的配置版本号，运行详情显示「草稿 v12」或「线上 v11」。
- 管理端：Agent 有草稿时调试默认用草稿，调试页顶部显示「正在调试：草稿 v12」并可切换到线上版本；配置页点「调试」前若有未保存修改，先提示保存。从未发布过的新 Agent 也能调试草稿。
- 「人工审批」页签保留；审批通过后继续执行时沿用同一个配置版本（`resolvePublished` 已按版本号取，补上草稿分支）。

### A2 安全的默认策略（Runtime + 管理端 + 线上 Agent）
- 新建 Agent 默认「标准策略」；Runtime 缺省值同步改为 `STANDARD`。研发模式改名「研发模式（放宽租户与权限检查）」，选中时配置页顶部和发布时都有明显提示。
- 添加 Workflow 时自动带上它的所需权限（来自 Workflow 发布信息），「所需权限」不再需要手填；发布校验在标准策略下列出缺权限的 Workflow，而不是等运行时拒绝。
- 线上：查一遍现有 Agent 的策略。对 ERP 助手，先在草稿里改为标准策略，用 A1 的草稿调试和运行中心对比实验「ERP 助手回归」确认通过，再发布；芋道嵌入对话复测一次。只走产品 API，不直接改库。

### A3 拆分页面并清理（管理端，行为不变）
- `AgentEdit.vue` 拆成：页头与页签、`AgentBasicSection`、`AgentDecisionSection`、`AgentWorkflowSection`、`AgentKnowledgeSection`（见 A4）、`AgentCollaborationSection`、`AgentSkillSection`、`AgentMemorySection`、`AgentAdvancedDrawer`、`AgentVersionsPanel`，以及对应的选择器对话框；表单状态放进 `useAgentConfigForm` 组合式函数。目标：主文件只负责组装，不超过 800 行。
- `AgentDebug.vue` 拆出对话区、执行阶段面板、人工审批面板、运行详情抽屉。
- 删除没有入口的「Agent 配置版本」抽屉；`AgentEval.vue` 移到 `views/runops/`。
- 现有 `AgentEdit.*.test.ts` 守卫测试全部通过，新增各分区渲染测试。

### A4 知识库、运行效果和文案（管理端 + 少量 Runtime 读接口）
- 配置页新增「知识库」分区：列出这个 Agent 已接入的知识库（从 `kb-qa-*` Workflow 识别），可直接添加或移除，复用现有 `knowledge-qa` 接口；「可调用 Workflow」列表里不再重复显示这些条目。
- 概览页加「最近运行」（最近 5 条，链接到运行详情）和「最近一次评测」（通过率，链接到评测页）；详情页页签加「运行记录」「评测」两个跳转入口。
- 文案：`keySlug` →「标识」，「Supervisor 配置」→「线上配置」，「Supervisor Runtime」→「本轮执行」；版本表去掉「运行时」列；Workflow 和协作 Agent 的「调用标识 / 所需权限 / 自定义 Schema」收进每行的「高级」折叠；「可委派任务」显示任务名称；「自定义策略（JSON）」只在研发模式或有内容时显示。
- 更新 `docs/03-Workflow-Studio与Runtime.md` 中 Agent 调试与策略的说明。

### 验收（芋道 ERP 助手）
- 在草稿里改一句工作要求 → 不发布直接调试，回答体现新要求，运行详情显示「草稿」；切换到线上版本调试，仍是旧行为。
- ERP 助手为标准策略，芋道嵌入对话问库存、问退货政策都正常；「ERP 助手回归」对比实验全部通过。
- 在 ERP 助手配置页的「知识库」分区看到 `erp_policy`，概览里看到刚才的运行记录和最近一次评测。

## 3. 影响与风险

- 线上库：预计不改表结构，不需要种子 SQL。运行记录如需新增「配置版本号」字段，先看现有 `runtime_run` / trace 是否已有可复用字段；确需改表时按规则加 upgrade SQL，并走部署线程的确认卡片和备份。
- 兼容：A2 只改新建 Agent 的默认值，已有 Agent 不自动切换策略；ERP 助手的切换走草稿 → 调试 → 评测 → 发布，可回退到上一版本。标准策略要求请求带租户：嵌入对话的 token 里有租户，调试和评测入口需要确认是否带上，A2 会先在草稿调试里验证。
- 模型调用：验收会调用若干次模型（草稿调试、嵌入对话、一次对比实验）。
- 每批一个 PR 合并到 dev，经「部署项目到宝塔」线程上线。

## 4. 进度

| 批次 | 状态 | 说明 |
| --- | --- | --- |
| A1 调试跑草稿 | 已上线 | Control 只在控制台登录且有该项目 `agent:debug` 时，把 `debugConfigVersionId` 转成 Runtime 保留字段 `__debugConfigVersionId`；Runtime 只在 HMAC 验证过的控制台身份（AGENT）下读取它，按版本号取配置（允许草稿），入口固定为 DEBUG，返回 `agentConfigStatus`；人工审批和 Workflow 交互的续跑按服务端记录的版本号取（允许草稿）。调试页有草稿时默认调草稿，可切到线上版本（切换后开新会话）；配置页「调试」改为「保存并调试」。不改表 |
| A2 安全的默认策略 | 已上线，ERP 助手已切换 | 新建 Agent、项目页面助手、AI Coding 建 Agent 默认 `STANDARD`（AI Coding 也允许显式选研发模式）；`initV2.sql` 列默认值改为 `STANDARD`（代码总会显式写入，已有库无需升级 SQL）；研发模式的 Agent 在详情页顶部持续提示并可一键打开高级设置，发布确认框也会提示。Workflow 所需权限后端本来就缺省为 `workflow:<keySlug>`，不需要手填 |
| A3 拆分页面并清理 | 已上线 | `AgentEdit.vue` 3889 → 420 行：表单状态和操作在 `views/agent/edit/useAgentEditor.ts`（provide/inject），分区组件 `AgentConfigNav`、`AgentBasicSection`、`AgentDecisionSection`、`AgentWorkflowSection`、`AgentCollaborationSection`、`AgentSkillSection`、`AgentMemorySection`、`AgentAdvancedDrawer`、`AgentVersionsPanel` 各带自己的对话框和样式；删除无入口的版本抽屉和无用样式。`AgentDebug.vue` 拆出 `views/agent/debug/` 下的执行阶段、人工审批面板和运行详情抽屉（脚本 763 行，其余为对话区样式）。`AgentEval.vue` 移到 `views/runops/`。守卫测试改为读取主文件加分区文件，新增调试面板渲染测试；行为不变 |
| A4 知识库、运行效果和文案 | 已上线 | 配置页新增「知识库」分区，按 `kb-qa-*` 识别已接入的知识库，可以添加或移除。添加时调 `knowledge-qa` 并带 `attachToAgent=false`，只准备并发布知识问答 Workflow，然后加入草稿，不直接发布 Agent，所以只需 `agent:write`。「可调用 Workflow」列表和计数不再包含这些条目。概览新增「知识库」入口、「最近运行」（30 天内 5 条，链接到运行详情）和「最近一次评测」（结论和完成数，链接到评测页）。详情页签加「运行记录」「评测」两个跳转。文案调整：列表列名改为「标识」「线上配置」；调试页去掉 Supervisor 字样；版本表去掉「运行时」列；Workflow 和协作 Agent 的技术字段收进每行的「高级」；可委派任务显示任务名称（按远程 Agent 详情补全）；「自定义策略（JSON）」只在研发模式或已有内容时显示。不改表 |

2026-10-19 芋道验收通过（线上 Control/Runtime 等同 dev 4d7e5a96，前端 784c310a）。草稿 v12 只把策略改成 STANDARD；不发布直接调试，库存和退货政策都答对，运行记录为 DEBUG、配置 v12。对比实验「ERP 助手回归」v11 和 v12 都是 6/6，门禁通过，随后发布 v12。芋道嵌入对话复测正常，tenantId=default。每日自动化「ERP 助手每日库存预警」原先固定在配置 v5（自动化按版本固定，属于设计行为），已改到 v12，手动触发一次成功。上线中修了两个问题：内置 Skill 内容改了但版本号没升，导致 Control 启动失败（#53，升到 0.1.1）；概览「最近运行」标题显示了输入元数据 JSON（#54）。
