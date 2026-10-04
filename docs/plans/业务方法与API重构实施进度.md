# 业务方法与 API 重构实施进度

更新时间：2026-10-04。状态：5G-R1 与原 F01–F13 当前技术集合已限定复核，原74映射/23个后续变化有对应证据，无已知待实现专项技术缺陷。按用户最新明确授权，BMAPI-UAT-START已完成原远程开发库可恢复备份、七份增量升级、真实五服务与前端启动及正常登录目录读取，主任务限定复核通过；未新建本地数据库。前端 http://127.0.0.1:5200/ 与五服务保持运行，供本人查看。目录当前真实为空，Knowledge曾有Redis间歇超时和旧集合后台告警，保留运行限制。G05真人任务反馈与最终整体签收仍未完成，不标100%。续办配置实际PAUSED，已更新授权范围并保持原暂停状态。

目标与范围统一见[实施基线](./业务方法与API重构实施基线.md)。本文件只维护当前位置、设计决策、批次范围与证据，不复制产品规则。

## 1. 当前执行位置

- 最近复核：[BMAPI-5G-R1 主任务复核](../../output/tasks/business-method-api/BMAPI-5G-R1-主任务复核.md)限定签收四个源码变化、真实菜单按键/焦点/局部滚动、当前首页/Agent原生200%与清理。38项只读核对、3243源码/486WIP/19保护文件/6JAR/1143旧材料/347本批材料匹配，32份结果对应原CLI；两份前态和旧5G来源无断链。旧侧栏失败、执行者失败、观察器错误及报告一处错误路径均保留并准确勘误，主任务未重跑产品测试/build/浏览器/业务。
- 当前验收位置：R1及[原F01–F13当前技术证据集合](../../output/tasks/business-method-api/BMAPI-最终技术证据主任务复核.md)已复核，11项来源核对通过；技术集合不代替真人签收。[BMAPI-UAT-START主任务复核](../../output/tasks/business-method-api/BMAPI-UAT-START-主任务复核.md)限定签收七份远程升级与启动：178表备份可恢复回读、全部增量exit0、当前186表/117项类型/32项索引/10项中文及八表原列摘要保持。六个监听进程与18个本批进程身份匹配，正常已有账号me/方法/API项目目录读取成功；空态与知识服务运行告警如实保留。
- 唯一下一步：保持[前端](http://127.0.0.1:5200/)和五服务供本人查看，按[两角色试用步骤](../../output/tasks/business-method-api/BMAPI-5D-两角色试用步骤.md)取得D1–D4/I1–I6反馈，再对具体问题作必要修正和原清单整体签收。正常目录空态不自动接纳历史投影，不为展示调用客户业务或补种；此次服务就绪不替代真人反馈。本人查看与本次远程增量已获明确授权，不再逐批问继续；不新建本地库、不跑全量initV2、不改无关真实授权、不恢复旧CLOSED或重放once/UNKNOWN。G05未取得，不预填；续办原PAUSED保持。
- 当前停止点：守卫不得删断言/跳过/放宽allowlist制造通过，也不恢复旧页面/文案以凑静态结构。部分授权逻辑已限定签收，Studio只读、Tool投影与授权语义保持。Console收据UNKNOWN/UNCONFIRMED与整体Run/Trace FAILED分层记录，失败不等于未生效。5C-R1“已提交”只表示确认提交。[3C-C](../../output/tasks/business-method-api/BMAPI-3C-C-主任务复核.md)、[2D-C](../../output/tasks/business-method-api/BMAPI-2D-C-主任务复核.md)与3D保持只读代表边界。旧环境全部CLOSED，不恢复库/profile/凭据或重发once/UNKNOWN。仅按用户本次授权升级已确认远程开发库，DDL前须有可恢复备份，单份失败止后续；不自动授予角色、改凭据或运行客户业务。保留共享WIP，不提交/推送/部署；成功预览进程保留供本人查看，结束后按自有PID停止。
- 已确认：同时服务 Java 开发者和实施人员；业务方法与 API 分别管理；现有服务内重构并复用基础机制。
- 设计方向：Q1–Q6 的选定方案及分期见[设计决策](./业务方法与API重构设计决策.md)。API 操作身份、多来源关系和实施分批已在[BMAPI-3 规格](./BMAPI-3-API接入与使用规格.md)具体化；API 受控验证、外部 binding 和开发库数据处置仍须在对应批次完成，不能提前宣称实现。

### 按用户任务判断进度

| 用户能完成什么 | 当前证据 | 尚未完成 |
| --- | --- | --- |
| U1/U2：接入业务方法，查看、试用并用于 Workflow | 5B接入/DTO/受控写、5C固定SDK及R1回复；5E独立Java写后UNKNOWN原ID查询、READ契约演进/移除；5E-R1首页、5G-R1侧栏及当前Agent原生200%已限定复核，当前技术来源对应成立 | 真人开发者/实施人员任务反馈和原清单整体签收；客户业务系统未访问，不由合成角色推断好用 |
| U3：发现 API，查看、配置、试用并用于 Workflow | 5B双来源/GET/POST、5C市场/授权消费者；5E同内容重扫、冲突详情及Console/发布拒绝；5E-R1部分授权、5G-R1真实键盘/当前首页原生200%已限定复核 | 真人任务反馈和整体签收；复杂认证/客户API不由代表范围推断完成 |
| U4：处理来源变化并定位草稿/发布影响 | 5B API变化/移除、5C授权入口保护；5E同身份Java value→key→声明移除、具体引用和显式Workflow/MCP发布；最新侧栏支撑及技术集合已复核 | 真人处理变更/限制理解及整体签收；代表链不等于所有环境与场景 |

2026-09-22 主任务对照会话历史、基线和当前实现进行方向复核：产品边界仍一致，现有服务复用也未改变；但近期连续拆分来源适配与契约验证，API 用户操作迟迟未闭合，实施节奏偏重底层。主任务负责调整拆批与验收重心，保留已有有效实现，不改变已确认产品边界。

后续以可演示的用户操作和明确缺口报告进度。此前会话中的“约 64%”等粗估缺少按用户任务计算的依据，不再用作整体完成度。3A-2B2 收口后，只有阻塞上述代表操作的缺口才作为新增前置工作；正确性与必要保护照常验证，扩展解析形式按具体用户需求安排。Controller/OpenAPI 两种代表来源、变化保护及旧入口去留仍须完成，不因调整交付节奏而取消。

## 2. 批次状态

| 批次 | 目标 | 状态 | 尚缺证据 |
| --- | --- | --- | --- |
| BMAPI-0 | 基线、命名与恢复入口 | 已完成 | 无；仅证明文档和规则入口已建立，不证明业务功能 |
| BMAPI-1 | 现状映射与设计收敛 | 已完成本轮映射与方向决策 | 后续 API / 试调用 / 数据处置子规格仍需逐批具体化 |
| BMAPI-2 | 业务方法使用路径 | 前序、5B/R1、5C/R1及5E独立方法代表事实与当前来源已限定复核 | 真人业务用户任务反馈、整体签收；客户系统未访问，不宣称生产部署 |
| BMAPI-3 | API 使用路径 | 5B GET/POST/UNKNOWN、5C/R1市场/消费者、5E重扫/冲突及当前技术集合已限定复核 | 真人与原清单整体签收；复杂认证/契约不推断完成 |
| BMAPI-4 | 变化、引用与已有发布 | 4A、5B、5C、5E方法及API来源保护/显式发布与当前来源已限定复核 | 真人变更任务反馈及整体签收 |
| BMAPI-5 | 退场与整体验收 | 5A至5G/R1已按实际范围复核；原74映射与当前23个变化技术集合核对通过，当前无待实施技术批次 | G05真人两角色任务反馈、F11人工体验与F13原F01–F13整体签收 |

## 3. 已完成批次记录：BMAPI-0

- 用户任务：为 U1–U4 建立可跨会话恢复的实施边界，回应用户对长任务跑偏的担忧。
- 交付：实施基线、单一进度入口、AGENTS 专项阅读规则；同步当前维护文档中的旧预留命名。
- 范围：Markdown 文档和仓库规则。
- 预期结果：后续执行者能够回答目标、已决定事项、当前批次、下一步和真实验收缺口。
- 验证环境：2026-09-15，Windows PowerShell，共享工作树基于 `253bf6a8`，保留原有未提交内容；均为本地静态检查。
- 验证结果：本轮已跟踪文档的 `git diff --check` 通过；7 份文档的 41 个本地链接有效，新增文档无尾部空白或替换字符；维护文档中无仍生效的旧预留命名指令；`node scripts/verify-architecture.mjs` 的 13 项检查通过。
- WIP 保持：对本轮开始前 49 个已修改或未跟踪文件比较 SHA-256，全部一致。Git 给出 LF / CRLF 转换提示，未改变相关内容或进行格式化。
- 材料：本专项会话的命令与检查输出；文档检查只作本批证据，不计入后续真实业务验收。
- 未执行：业务实现、模块测试与构建、浏览器验收、真实 SDK / HTTP / Workflow 调用。

## 4. 协作分工与当前执行批次

2026-09-15 用户指定另一任务承担实现与验证，以减少主任务重复投入。

- 主任务：`01a0a4b4-c265-71b0-b680-d14ef4442b86`，负责产品与技术设计、规格、范围、监督与结果复核；维护基线、设计决策和本进度文件。
- 执行任务：`01a0a535-211c-7232-9125-0d6c85801687`，与主任务共享工作目录；负责按规格实现、测试、构建、浏览器 / 真实调用验证和修复。
- 主任务查看必要的差异、契约与证据进行复核；运行测试和复测交由执行任务，除非用户另行调整分工。
- 执行任务不自行改变产品边界，不另行分派任务，不编辑主任务维护的基线与进度；用紧凑的批次结果文件报告证据、缺口和所需设计决策。
- 一次下发一个有明确完成点的批次。执行任务完成后结束该回合，主任务复核并下发下一批；范围内的正常推进无需用户逐批确认。
- 执行任务完成或遇到具体设计阻塞时，主动向主任务发送一次紧凑回报并结束该回合；回报包含批次、结果文件、实际证据、缺口与待决策项。主任务据此恢复复核与下发，日常不持续轮询。

2026-10-02 用户明确要求“继续干，直到进度达到100%”。主任务在本对话启用自动续办，按当前批次的结果文件与紧凑状态快照接续复核/下发。执行窗口完成报告并结束回合即可，不要求向另一对话发送消息，也不再为回传另请用户授权。保持一次一个实施批次、普通选择自主推进及真实证据边界；完成标准逐项见[最终验收清单](./业务方法与API重构最终验收清单.md)。

### BMAPI-1A：现状映射

- 关联任务：U1–U4；为 Q1–Q6 提供代码事实。
- 下发状态：已完成；主任务已读取报告并抽查关键源码。
- 写入范围：仅允许新增 `output/tasks/business-method-api/BMAPI-1A-现状映射.md`；其余为只读检查。
- 内容：方法 / MVC / OpenAPI 的发现至执行链路、稳定身份、Workflow / MCP 引用与契约、试调用入口、来源字段所有权、API 接入缺口、相关 WIP 和最小实现切片。
- 证据要求：关键文件与行号，事实与未知分别标注；不运行无改动的全量测试，不读取凭据或写数据库。
- 结果：[现状映射](../../output/tasks/business-method-api/BMAPI-1A-现状映射.md)。源代码映射可作为设计依据；报告建议的仅重跑现有链路不作为首个产品交付，改为 BMAPI-2A–2D 分步实现。
- 证据边界：执行任务只做静态与文档检查；主任务复核 Starter 注册、来源 intake/projection、ACL 与 MCP 执行核心，未运行测试或真实业务调用。

### BMAPI-2A：资产类型贯通

- 规格：[BMAPI-2A](./BMAPI-2A-资产类型贯通规格.md)。关联 U1/U2，为可靠目录分类提供来源事实。
- 范围：SDK、Starter、Capability 与类型投影 SQL；不修改 Control/Runtime/前端或恢复 A2d0。
- 状态：主体实现、普通 UNCHANGED 和重复快照修正已完成；2026-09-16 主任务复核通过本批代码与模块证据。
- 结果：[执行任务实施结果](../../output/tasks/business-method-api/BMAPI-2A-实施结果.md)、[主任务复核](../../output/tasks/business-method-api/BMAPI-2A-主任务复核.md)。
- 已核对证据：SDK 50/50、Starter 85/85；Capability 首次 353/353、普通分支修正 354/354、最终 361/361。最终包括 34 项真实 H2/MyBatis 持久化测试，架构 13/13、受控 diff/文档检查通过。日志位于 `output/tasks/business-method-api/BMAPI-2A/`，主任务读取源码及日志，没有重复运行测试；目标测试不与模块总数重复累计。
- R1 收尾：最短重复上报反例先失败后通过；重复修复限定当前已接纳来源和快照 / diff 身份，不新增接纳记录，不改变 metadata/hash；候选、历史回执、诊断、移除与回滚保护均有持久化测试。
- 未验证：真实 MySQL 升级、真实 SDK 同步、浏览器及外部执行；这些缺口不因模块测试通过而消失。

### BMAPI-2B：业务方法目录与详情

- 规格：[BMAPI-2B](./BMAPI-2B-业务方法目录与详情规格.md)。关联 U1 第 2 步 / U2 第 1–2 步，目标是按项目找到并看懂明确开放的方法。
- 状态：主体代码及 R1–R3 修正已通过主任务复核，限代码 / 模块 / 隔离页面证据。见[实施结果](../../output/tasks/business-method-api/BMAPI-2B-实施结果.md)与[主任务复核](../../output/tasks/business-method-api/BMAPI-2B-主任务复核.md)。
- 范围：Capability 专用类型查询、Control 签名读取与项目授权、前端目录 / 详情及必要共享组件；不改 SDK/Runtime/schema，不执行开发库升级。
- 必要支撑：现有目录读取和 by-id 签名兼容直接影响本批项目归属读取；仅验证并修复这些具体调用者，保留 A2d0 其余暂停状态，不铺开通用权限治理。
- 预期行为：只返回 BUSINESS_METHOD，分页总数准确；项目与账号切换不串数据；输入输出与来源状态真实；使用位置未知时不显示为零。
- 验证与交付：相关 Maven、前端目标测试/build、架构与文档检查、桌面 / 窄视口浏览器记录；结果写入 `output/tasks/business-method-api/BMAPI-2B-实施结果.md`，模拟与真实环境分别说明。
- 最终已核对证据：Capability 目标 42、全量 368，Control 34、前端 28 项通过；前端 vue-tsc/build、架构 13 项与 diff 通过。主任务读取源码、日志、H2 查询测试与正常列表 / 详情 / 768px 截图，没有重复运行测试。
- 修正结果：范围直接切换同步关闭并清空旧详情；参数声明补充展示；查询已用实际 H2/MyBatis 验证混合类型与分页。浏览器采用受控响应夹具，已覆盖正常列表、详情与失败恢复；不是实际登录后端 E2E。
- 证据整理已完成：窄屏夹具按 enabled 条件返回，总数 3 / 启用 2 / 停用 1，已重录并记录[操作说明](../../output/tasks/business-method-api/BMAPI-2B-窄屏夹具复录说明.md)；未改生产计数。正在运行的旧服务尚未更新，真实 MySQL / SDK / 跨服务调用仍未验收。
- 无关检查缺口：全局 glass/header 检查被现有 AgentEdit / ToolRetrievalTest 与缺失 A2aEndpointList 基线阻断，保持单列，不纳入本批修复范围。
- 旧入口：保留现有混合只读目录及 API 入口；2B 不改变旧维护入口语义，后续 3/5 批次统一接替与退场。

### BMAPI-2C：业务方法试调用

- 关联：U1 第 4 步 / U2 第 3 步；依照 Q4 使用独立 Console 入口，复用可信身份、明确 ALLOW、来源契约保护和 Run/Trace。
- 设计准备已完成：[执行契约核对](../../output/tasks/business-method-api/BMAPI-2C-执行契约核对.md)。主任务抽查 SDK token / endpoint、Control ACL、Runtime typed gateway、source guard 与含 baseUrl 的 contract hash 后确定[2C 规格](./BMAPI-2C-业务方法试调用规格.md)。
- 已决定：项目凭据执行，平台身份只作平台授权 / 审计；capability:invoke + 项目读取 + ACL ALLOW；沿用已接纳目标；CONSOLE_CAPABILITY Run、持久去重、受控结果查询与 UNKNOWN；不创建业务用户映射产品或实例路由机制。
- 2C-A 范围：Control / Runtime / Capability 和必要共享契约、权限种子、Runtime 记录 SQL 及测试；不执行开发库升级、不改真实授权。目标为本地隔离 HTTP 确认真实派发、拒绝、去重、超时未知与结果归属。
- 2C-A 首版已交付：[实施结果](../../output/tasks/business-method-api/BMAPI-2C-A-实施结果.md)、[验证记录](../../output/tasks/business-method-api/BMAPI-2C-A/验证记录.md)。主任务核对 Surefire：Capability 9 / Runtime 17 / Control 5 项通过；其中真实本地 HTTP 2 项，Runtime H2 6 项，其余是目标单元回归，不是完整 SDK 或授权链路验收。
- 首版未通过：[复核 R1–R6](../../output/tasks/business-method-api/BMAPI-2C-A-主任务复核.md)指出空 requiredRoles 误判、未校验来源参数、敏感输入异名回显、Trace null handle 不结束、全量恢复及终态事务不一致等；执行任务修正并补实际链路反例。2C-B 尚未开始，完整方法路径仍待 2D。
- 第二次复核：R1 空角色与 R4 Trace handle / 同事务终态 / 到期恢复已有修正；但 SDK 实际输出并列 dotted path，当前 validator 使用理想 children 夹具，真实单 DTO 平铺/包装和 Map 等会被错误拒绝，敏感路径也须同步解释。按同一复核文档的 S1–S4 收口。修正版报告记录 Capability 23 / Control 8 / Runtime 14 项通过；测试仍缺 SDK 实参、真实发送后超时/断连、公开路由授权反例等，不能宣布本批完成。
- 第三次交付：[S1–S4 报告及原始 Surefire](../../output/tasks/business-method-api/BMAPI-2C-A/S1-S4-第二次复核实施结果.md)记录 Capability 51 / Control 13 / Runtime 14 项。主任务已读实际 scanner→registry→owner→SDK Endpoint、Runtime H2→HTTP signer/verifier 和真实 MVC 授权组件测试；此前缺口已实质补齐。剩余两个窄协议/敏感路径问题及原有回归已下发，完成后进入 2C-B。真实 credential DB 并发撤销仍为后续部署态验收缺口。
- 最终收口：ACCEPTED/PERSISTED 与递归 flat/wrapped 敏感路径问题已修正。主任务核对当前源码和 33 份 Surefire：SDK 51、Starter 85、Capability 26、Control 14、Runtime 10，共 186 项且失败/错误/跳过均为 0；架构与差异检查由执行任务完成。本批代码与隔离验证通过，上述历次退回状态已被本结论取代；真实环境缺口继续保留。

### BMAPI-2C-B：业务方法试调用页面

- 规格：[2C-B 页面规格](./BMAPI-2C-B-业务方法试调用页面规格.md)。关联 U1 第 4 步 / U2 第 3 步。
- 状态：R1–R6 和 S1–S3 均已修正，[主任务最终复核通过](../../output/tasks/business-method-api/BMAPI-2C-B-主任务复核.md)，限代码 / 组件 / 隔离浏览器证据。主任务核对当前源码、组合交互测试与原始日志，没有重复运行测试。
- 范围：现有业务方法详情、方法领域组件 / composable、类型化 API 与必要权限展示；复用现有会话、CSRF、组件和主题。不增加独立调试目录，不展开全平台改造。
- 预期行为：看清目标与调用条件、准备输入、确认写操作、只提交一次、理解业务结果；响应不确定时用原 invocationId 查询，账号 / 项目 / 方法切换不串数据。
- 验证：目标前端测试、类型检查 / build、真实浏览器状态与窄屏 / 键盘检查，以及至少一条浏览器到隔离后端 / SDK 的路径。区分页面 fixture、真实组件组合与部署态证据。
- 交付与停止点：执行任务输出 `output/tasks/business-method-api/BMAPI-2C-B-实施结果.md` 和可复核日志 / 截图 / 请求次数，检查主任务本轮文档，完成后回调一次并停在 2D 之前。
- 首版证据：[实施结果](../../output/tasks/business-method-api/BMAPI-2C-B-实施结果.md)报告 Vitest 43/43、类型 / build / strict UI audit / 架构 / diff 通过；现有隔离浏览器主机实际覆盖 Control、H2 Runtime、HTTP HMAC 验证和合成业务响应，未执行 SDK Endpoint，不能称为完整 SDK 浏览器链路。原始回归日志、缺失页面状态和真实交互反例随修正版补齐。
- 修正版证据：已归档修正前 4 个失败反例和修正后 Vitest 60/60、类型 / build / strict audit / 架构 / diff 日志；新增 H2 未派发 / 过期、浏览器无效 JSON 零提交和 UNKNOWN 固定 ID 证据。S1–S3 仍是输入编辑范围内的真实缺口，未宣称页面整批完成。
- 最终收口：S1–S3 已归档修正前 6 个失败反例和修正后完整 Capability Vitest 67/67；普通 / flat / wrapped 删除字段、无效草稿保留、完整 DTO 来源树脱敏均有实际 Panel 测试。浏览器计数与截图证明取消字段和零新增 POST 行为；类型 / build / strict audit / 架构 / 文档 / diff 通过。前述历史缺口已被本结论取代，2C-B 整批通过；完整真实业务环境验收仍保留。

### BMAPI-2D-0：Workflow 接入契约核对

- 关联 U2 第 4–5 步以及 U1 第 4 步后的复用：实施人员从明确的业务方法进入 Workflow，映射输入 / 输出、调试、发布，并能回到方法和调用记录。
- 本小批仅为实施准备；执行任务读取现有 Studio / Control / Runtime / Capability 真实路径，输出 `output/tasks/business-method-api/BMAPI-2D-0-接入契约核对.md`，不新增产品边界或默认运行身份。
- 核对重点：选择器是否能区分业务方法 / API，已接纳类型 / 契约如何传递，节点 GraphSpec 与映射真实语义，发布 pin 与来源漂移阻断，设计期调试和受控已发布执行入口的权限 / 身份差异，现有引用 / RunOps 入口及未提交 WIP。
- 预期结果：用一条无副作用方法明确最小端到端切片、可复用代码、确切缺口和隔离验收方案。不得把 Console 试调用身份直接移植到 Workflow、让类型展示替代运行契约，或让全平台权限治理成为前置。
- 验证与停止点：本小批只做代码 / 路径 / 报告检查，不无改动运行全量测试。完成后回调主任务，再依据具体规格实施 2D。

### BMAPI-2D-A：Workflow Studio 选择与映射

- 规格：[BMAPI-2D](./BMAPI-2D-业务方法Workflow接入规格.md)。关联 U2 第 4–5 步，先交付作者侧选择、输入/输出映射和保存回读，再做完整纵向验收。
- 范围：`ai-admin-front` 的 Tool 节点配置、方法目录查询状态、变量候选与 GraphSpec 往返；只为明确零参数复用 Runtime 已支持的 `config.args={}`。不改 Runtime 身份/ACL、Capability 目录、SQL 或全局视觉系统。
- 预期行为：只从当前项目分页选择 READY/enabled 业务方法；标量、DTO、Map/数组和零参数形成真实 Runtime 语义；来源声明的返回字段可被下游选择，未声明字段不虚构；普通 Tool、项目 API 和旧引用继续工作。
- 验证与停止点：执行任务保留失败反例、相关 Vitest/typecheck/build、Premium/项目守卫、真实浏览器和请求/GraphSpec 证据，输出 `output/tasks/business-method-api/BMAPI-2D-A-实施结果.md` 并回调主任务，停在 2D-B 之前。
- 最终状态：首轮复核发现动态路径兼容、通用节点未知字段保护、详情失败编辑保护和浏览器证据失配；R1–R4 已修正。主任务核对当前源码、失败前后日志、最终截图和载荷后[复核通过](../../output/tasks/business-method-api/BMAPI-2D-A-主任务复核.md)。定向 16/16、Workflow 158/158、Capability 67/67、Runtime 58/58、前端构建与相关守卫通过；回环浏览器不计作真实 SDK / 发布 / MCP 验收。

### BMAPI-2D-B：首条完整纵向路径

- 规格与任务：[BMAPI-2D](./BMAPI-2D-业务方法Workflow接入规格.md)、[执行任务](../../output/tasks/business-method-api/BMAPI-2D-B-执行任务.md)。关联 U1 第 1–4 步和 U2 第 4–5 步。
- 范围：独立端口、隔离 H2、测试凭据和无副作用 SDK 方法；复用真实 SDK / Starter、Capability owner、Control、Runtime、Studio、发布 pin、MCP、Trace 与引用索引。只允许修复该链直接暴露的契约缺口。
- 预期行为：同一稳定 `qualifiedName` 从 SDK 声明进入 READY 目录和 Studio GraphSpec，由服务端发布固定 64 位 hash；已发布 MCP Workflow 实际到达 SDK Endpoint，并在 Trace 和草稿 / 发布引用中可反查。
- 验证与停止点：标量、零参数、DTO 整体对象与返回字段都有真实证据；来源漂移、接纳后未重发、停用 / 移除不会静默执行。Studio debug 单列实测；遇到身份阻断时不改信任边界。完成或阻塞后主动回调主任务，不进入 BMAPI-3。
- 最终状态：R1–R6 已完成并[通过主任务复核](../../output/tasks/business-method-api/BMAPI-2D-B-主任务复核.md)。隔离高保真集成覆盖 SDK / Starter、Capability、Control、Runtime、Studio、发布 pin、MCP、SDK Endpoint、Trace 与引用；浏览器对零参数、标量和 DTO 根对象三项方法逐一重新选择、保存重开、校验并发布 `v1.0.1`，引用证据包含草稿、历史版本、当前版本和 MCP publication。原始验证包括纵向 7/7、Control 22/22、Workflow 28 个文件 / 161 个测试、前端构建和 launcher 1/1。
- 证据边界：同一 JVM、隔离 H2 与 loopback bridge，不等于独立部署、外部业务系统或生产 MySQL E2E；Studio debug 保持 `DEBUG_UNTRUSTED` 信任边界，未为验收放宽身份。

### BMAPI-2D-C：业务方法草稿只读真实试运行

- 关联 U2 第 4 步与 U1 第 4 步；[执行任务](../../output/tasks/business-method-api/BMAPI-2D-C-业务方法草稿只读真实试运行任务.md)复用 3C-C 的项目测试身份和签名/审计链，接入明确开放的只读 Java 业务方法。
- 最终状态：依据[实施结果](../../output/tasks/business-method-api/BMAPI-2D-C-实施结果.md)与[主任务复核](../../output/tasks/business-method-api/BMAPI-2D-C-主任务复核.md)，按一个 `READ_ONLY` 标量方法 TOOL→变量的保存草稿真实试运行限定签收。浏览器输入 `A-1024` 后实际 SDK/Starter Endpoint 命中一次，方法与下游变量返回 `N-A-1024`，同一修订的 Run/Trace 和方法详情草稿引用成立；撤销精确方法 ACL 后 owner 预检 403 且零额外派发，POST ACL 403 另由集成测试证明。
- 执行证据：相关 Maven 30 类 214/214（H2/SDK 集成 18/18）、前端 Workflow/Agent 187/187、RunOps 21/21、浏览器 launcher 1/1，以及类型/构建、布局/画布、架构 13/13 和差异检查通过。主任务核对源码、断言、最终 manifest、日志与截图，未重复跑测试。旧普通 `DEBUG_UNTRUSTED` 实际派发漏洞已有失败复现和零 SDK 回归；响应丢失后仅一次实际派发并留下 FAILED Run。
- 证据边界：首次浏览器夹具超时不计入最终通过，完整结果仅来自第二夹具；单 JVM/H2/loopback、单只读标量方法与两节点图不等于独立部署、外部业务、写方法、复杂图或用户易用性验收。RunOps 夹具辅助接口和日期显示仍有限制；原生 200% 缩放未验证。

### BMAPI-3-0：API 使用路径契约核对

- 关联 U3 全部四步；只读核对 Starter MVC、Controller/OpenAPI 文件扫描、API 市场、Tool 投影、Workflow 与 Runtime 的真实代码和 SQL。
- 结论：当前存在三条未闭合路径。只有 Starter MVC 经 SDK sync 进入来源接纳；文件扫描停在扫描项和人工 `promote-to-tool`；API 市场停在目录/接入意图和前端 raw `HTTP_REQUEST.marketRef`，Runtime 不解析该引用。
- 主任务定案：HTTP API 是 Capability-owned 一等资产；多来源仅在服务范围、操作身份和规范化契约一致时自动关联；API 市场在受控 external binding 前不构成运行授权或发布固定；发现、接纳、验证、调用成功和授权可用分别表达。
- 范围调整：原建议的跨 Scanner/Capability/Runtime/前端单批拆为 3A-1 领域基础、3A-2 来源适配、3B 管理/验证、3C Workflow 和 3D 市场 binding。见[核对报告](../../output/tasks/business-method-api/BMAPI-3-0-API使用路径契约核对.md)、[主任务复核](../../output/tasks/business-method-api/BMAPI-3-0-主任务复核.md)与[BMAPI-3 规格](./BMAPI-3-API接入与使用规格.md)。
- 证据边界：未运行测试、数据库、服务、浏览器或外部 HTTP；只作为设计输入，不作为功能通过证据。

### BMAPI-3A-1：HTTP API 身份、契约与来源关系基础

- 关联 U3 第 1、4 步；在 Capability Service 新增 HTTP API asset/source binding、稳定 operation identity、规范化 contract 和多来源聚合状态，没有接入任何现有发现写链。
- 最终状态：首轮 R1–R5 和第二轮 R6–R7 已全部修正并[通过主任务复核](../../output/tasks/business-method-api/BMAPI-3A-1-主任务复核.md)。稳定引用排除 `projectId`；Spring mapping 条件和 Schema 安全处理保真；重复观察刷新新鲜度；数据库唯一约束、行锁和显式 `READ_COMMITTED` 支撑并发幂等与聚合。
- 验证：R1–R7 组合 17/17、canonicalizer 4/4、H2/MyBatis 10/10、Mapper scan 3/3、BUSINESS_METHOD 回归 35/35、架构/表所有权守卫 13/13；新库与 upgrade DDL 一致，diff check 通过。详见[实施结果](../../output/tasks/business-method-api/BMAPI-3A-1-实施结果.md)。
- 证据边界：只证明本地源码、MySQL-mode H2 和静态约束；未执行真实 MySQL upgrade、生产并发、独立部署、接纳、授权、外部 HTTP 或执行验证。
- 分批调整：原 3A-2 拆为 3A-2A Starter MVC 和 3A-2B Controller/OpenAPI 文件扫描，先做一条可验证纵向来源链，避免三种适配与过渡治理同时展开。

### BMAPI-3A-2A：Starter MVC 来源接入

- 关联 U3 第 1 步与 Q1/Q2；新增独立 `httpApis` inventory，通过既有签名 registry sync 把普通 Spring MVC operation 观察为 `STARTER_MVC` HTTP API asset/source binding，业务方法仍只走 `capabilities`。
- 最终状态：实施、R1–R4 修正及本地回归已[通过主任务复核](../../output/tasks/business-method-api/BMAPI-3A-2A-主任务复核.md)；支持 class/method mapping 展开、条件、参数位置、双重声明、空清单移除、旧客户端字段缺失兼容、同 syncId 内容冲突拒绝与 source binding 重放新鲜度。
- 首轮复核修正：R1 将 `sync`、`syncFromProject`、`apply` 的真实 SOURCE 入口显式收口到 `READ_COMMITTED`；R2 改为 Spring condition combine；R3 补 defaultValue/Optional/未命名 Map；R4 补固定响应状态与常见响应包装 schema。未改变产品边界或拆分原子事务。
- 验证：Starter 83/83、Capability 全量 415/415、隔离 H2/MyBatis + canonicalizer 47/47、R1 事务定向回归 67/67、Runtime test-compile 与架构/表所有权/秘密/mojibake 守卫通过。详见[实施结果](../../output/tasks/business-method-api/BMAPI-3A-2A-实施结果.md)。
- 证据边界：未运行真实 MySQL、真实签名网络往返、运行中 Starter 应用、部署、外部 HTTP 或浏览器；本批不进入 3A-2B 文件扫描、管理面、接纳/环境 binding 或 HTTP Tool/Runtime 投影。

### BMAPI-3A-2B1：Controller 文件扫描来源接入

- 关联 U3 第 1、4 步；复用扫描项目和 Knowledge Controller scanner，在同次源码扫描中产出独立 HTTP operation inventory，由 Capability 以保存的项目/环境 scope 写入 `CONTROLLER_SCAN` 来源。
- 预期行为：同 scope、同 identity 且规范化契约一致的 Starter/Controller 来源等价，契约不同明确冲突；全量重扫只移除缺失的 Controller binding，不影响 Starter；旧扫描项与人工 promote 保持兼容但不自动成为已接纳 API。
- 最终状态：首轮 R1–R3 已修正并[通过主任务复核](../../output/tasks/business-method-api/BMAPI-3A-2B1-主任务复核.md)。同一真实 Controller 的 Starter/文件扫描整型与 DTO 契约经 canonicalizer 得到 `EQUIVALENT`，字段类型漂移得到 `CONFLICT`；解析失败日志不回显源片段；可识别的组合 mapping 使 inventory 降为 partial 而不误删绑定。见[实施结果](../../output/tasks/business-method-api/BMAPI-3A-2B1-实施结果.md)。
- 最终证据：Starter 定向 13/13、Knowledge 定向 17/17、Capability H2 定向 10/10；全 reactor AI Common 67/67、SDK 51/51、Runtime Contract 5/5、Starter 84/84、Knowledge 416/416、Capability 429/429，架构 13 项及相关守卫、diff check 通过。主任务核对代码和原始日志，未重复跑 Maven。
- 剩余限制：扫描源码外、名称不表明 mapping 意图的任意外部组合注解无法静态识别，可能被当成来源缺失；本批不把它列为受支持输入。这仍是未关闭的来源移除正确性问题，后续扫描完整性与 3B 状态表达须处理，不能用本批通过代替 U3/U4 的该项验收。真实跨服务扫描、MySQL 和浏览器仍未验收。
- 证据边界：本批不包含 OpenAPI 文件扫描、公开管理面、真实 HTTP 验证、Workflow 或生产环境验收；局部重扫不得作为全量 HTTP 来源移除依据。

### BMAPI-3A-2B2：OpenAPI 文件扫描来源接入

- 关联 U3 第 1、4 步；复用既有扫描项目与 Knowledge OpenAPI scanner，在同次文档扫描中产出独立 HTTP operation inventory，由 Capability 以保存的项目/环境 scope 写入 `OPENAPI_SCAN` 来源。
- 预期行为：完整文档、明确空文档、旧 wire、增量/不完整文档各有不同的重扫语义；请求/响应与 security 的可证明事实进入规范化契约；同 scope 的等价/冲突可解释，旧 `promote-to-tool` 仍只是兼容入口。
- 首版交付：[实施结果](../../output/tasks/business-method-api/BMAPI-3A-2B2-实施结果.md)及[主任务复核](../../output/tasks/business-method-api/BMAPI-3A-2B2-主任务复核.md)。Knowledge 定向 24 项、Capability 定向 55 项、Capability 全依赖 reactor、Runtime test-compile 和 13 项架构守卫成功，均为本地隔离证据。
- 最终结论：R1/R2 已修正并通过本批复核。真实 scanner 的认证关系互换反例先失败；修正后多 scheme requirement 按现有模型标 partial，实际扫描输出经 H2 intake 保留旧 binding。最终 Knowledge/Controller 25 项、Capability 编排/H2 56 项、13 项架构守卫与 diff 通过。R2 已明确 3B 包含受控 HTTP 试调用，3C 承担 Workflow；首版全量与修正后定向证据按实际时序分开。
- 证据边界：主任务核对代码和日志，未重复跑测试；真实部署/业务 API、页面与用户易用性仍未验收，3A-2B1 的既有来源移除问题未关闭。

### BMAPI-3B1：API 目录与首条受控试调用

- 关联 U3 第 1–2 步；[执行任务](../../output/tasks/business-method-api/BMAPI-3B1-API目录与首条受控试调用任务.md)已将页面、owner 接纳、Runtime 连接/凭据和实际 HTTP 试调用作为同一用户交付。
- 代表行为：从 OpenAPI 发现带 API Key 的 GET 查询，在 API 目录找到并理解参数/来源，接纳当前契约，配置地址与项目凭据，通过页面看到真实本地返回、状态/耗时和 Run/Trace；无需先造 Workflow/MCP/人工 Tool。
- 必要支撑：来源新鲜度/扫描不完整的可见性、接纳修订保护、Runtime 环境配置与凭据归属、Control 项目授权与显式 ACL、持久尝试与安全结果查询。每项直接服务该流程，不扩建解析器、权限系统或全站页面。
- 范围：本条 Capability/Control/Runtime/前端和必要 SQL；复用现有 HTTP 客户端、凭据、Console 调用记录与共享 UI。第一条调用限定已明确的 GET 参数/认证形式，写方法、其他序列化及 Controller 完整代表流程继续留在 3B 后续覆盖。
- 已交付证据：执行任务报告的隔离浏览器流程贯通真实 OpenAPI scanner 候选、H2 owner、页面接纳/项目凭据/连接、loopback HTTP 200 与 Run/Trace，同 ID 丢失响应回查及项目切换迟到响应也有页面证据；目标 Maven 75/75、前端相关 71/71、auth 168/168、类型检查/构建和架构守卫由执行任务运行。主任务抽查代码、截图与报告，未重复跑测试；这纠正了近期只有来源适配而无 API 用户操作交付的节奏偏移。
- 最终复核与证据边界：[R1–R3 最终结论](../../output/tasks/business-method-api/BMAPI-3B1-主任务复核.md)确认生产 inventory observe 夹具、数据库有界分页、账号切换迟到响应、RunOps 实际导航及 720px 关键状态可见性；修正后目标 Maven 22/22，执行任务报告相关前端类型检查/构建、Vitest 与守卫通过。主任务核对原始 Maven 日志、源码和浏览器材料，未重跑测试。**3B1 的首条功能流程通过，原生 200% 缩放仍未验证**：Computer Use 无法识别目标 Chrome URL 后停止，Playwright 快捷键未改变缩放；不以 CSS 窄屏代替。现有证据仅是同 JVM 隔离 bridge 与本地 HTTP，不是独立服务网络、真实 MySQL、外部业务系统或真实用户易用性验收。

### BMAPI-3B2：Controller 来源 API 代表流程

- 关联 U3 第 1–2 步；[执行任务](../../output/tasks/business-method-api/BMAPI-3B2-Controller来源API代表流程任务.md)要求同一 Controller 操作从真实源码扫描进入 API 目录，实施人员核对来源和请求/响应、接纳、显式保存认证选择与连接、完成隔离 HTTP 试调用并进入 Run/Trace。
- 必要支撑只针对该操作暴露的阻塞；复用 3B1 API 目录/Console/权限/凭据/结果机制，不另建 Controller 目录、人工 Tool 或 Workflow。UNKNOWN 认证不静默降为 NONE；外部不透明组合注解的已知来源移除风险仍需如实说明，不能因本批普通 Controller 成功就宣称关闭。
- 最终复核：[主任务结论](../../output/tasks/business-method-api/BMAPI-3B2-主任务复核.md)依据同一 Controller 源码的 scanner→H2 owner→页面接纳/显式认证→MockMvc 派发的 loopback HTTP→Run/Trace 和 35 条 smoke 签收第二条隔离功能流程；执行任务目标 Maven 64/64、前端构建/Vitest 与架构检查通过。主任务核对关键源码、日志和截图，未重跑。原生 200% 缩放、360px 展开侧栏、外部不透明组合注解误移除、多来源关系、写方法和真实环境验收仍开放。

### BMAPI-3C-A：API 进入 Workflow 作者流程

- 关联 U3 第 3 步；[执行任务](../../output/tasks/business-method-api/BMAPI-3C-A-API进入Workflow作者流程任务.md)以已接纳、当前可用的代表 GET API 为对象，让实施人员在 Studio 选择、映射 path/query、理解响应、保存/重开和下游引用。用户页面操作是本批交付；不得仅以增加 Runtime 投影或契约测试结批。
- 排序理由：3B1/3B2 已分别交付 OpenAPI 和 Controller 的可试用 GET，下一步验证实施人员能否在 Workflow 真正使用 API，以持续纠正此前过久停留扫描/契约支撑工作的节奏偏移。3B 的多来源、写方法和复杂契约仍属范围内后续验收，不据此宣称 3B 整体完成。
- 最终复核：[实施结果](../../output/tasks/business-method-api/BMAPI-3C-A-实施结果.md)和[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-A-主任务复核.md)证明真实 3B2 owner 数据进入独立 API 选择器、path/query 映射、声明响应字段下游选择；浏览器最终图 START→API→变量→END，经隔离 Studio 保存/重开及同图 Runtime 公共草稿 PUT→H2/MyBatis→GET 回读。R1–R3 均收口，完整 Runtime 类 33/33；主任务按隔离作者流程签收，未重跑执行任务的测试。发布 pin、身份/ACL、真实 HTTP Workflow 执行、Trace 与引用反查仍在 3C-B，不能用本批草稿证据冒充。

### BMAPI-3C-B：API Workflow 发布与受控执行

- 关联 U3 第 3 步与 U4 第 2–3 步；[执行任务](../../output/tasks/business-method-api/BMAPI-3C-B-APIWorkflow发布与受控执行任务.md)将 3C-A 的代表 API 和可达图接入真实发布与已发布 Workflow 受控调用，同一 Controller 接收 HTTP，变量节点读取 `state`，Run/Trace 和草稿/发布引用指向 owner。
- 必要支撑只针对发布 pin、owner/连接再校验、可信 Workflow 身份、调用 ACL、受控 HTTP 和引用证据的具体阻断；复用现有 Capability/Runtime/Control 边界，不调用 Console 根服务假装 Workflow，也不另建人工 Tool 事实源。
- 最终结果与停止点：[实施结果](../../output/tasks/business-method-api/BMAPI-3C-B-实施结果.md)提供隔离 Vite→Control→Runtime 浏览器发布、MCP 已发布调用、Controller 一次命中、下游 `PAID`、Run/Trace 和 API 草稿/发布引用；Eval 防护 R1 有先失败后通过的同图测试，外层 MCP ACL 的证据层级 R2 已澄清。[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-B-主任务复核.md)按已发布受控执行范围签收；扩展 Runtime/Eval/MCP 115/115 由执行任务运行，主任务未重复。Studio debug 的实际拒绝不被写成通过；用户已确认的真实试运行进入 3C-C。

### BMAPI-3C-C：API 草稿只读真实试运行

- 关联 U3 第 3 步；按用户确认的[身份方案](../../output/tasks/business-method-api/BMAPI-3C-Studio真实调试身份方案.md)和[执行任务](../../output/tasks/business-method-api/BMAPI-3C-C-API草稿只读真实试运行任务.md)，对已保存未发布的只读代表 API 图建立专用签名试运行入口、项目测试身份、逐目标授权和审计，让实施人员从 Studio 主动试跑并查看下游变量、Run/Trace。
- 范围：先收 API 代表图，保留普通 `DEBUG_UNTRUSTED`、Eval 与已发布 MCP 的原边界；只读业务方法使用同一机制的后续验收尚未完成。不访问真实业务系统或开发 MySQL，不扩展全平台权限模型。
- 最终状态：2026-09-30 依据[实施结果](../../output/tasks/business-method-api/BMAPI-3C-C-实施结果.md)与[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-C-主任务复核.md)，按保存草稿的 GET API→变量代表图限定签收。浏览器一次 GET 得到 `state=PAID` / `order_state=PAID`、Run/Trace 回查，并在撤销逐 API ACL 后明确拒绝，H2 确认上游仍只命中一次、无发布版本或 pin 写入。普通 debug、可信 Eval 与已发布 MCP 边界保留。
- 执行证据：后端定向与扩展 Runtime/Eval/MCP 140/140；Workflow/Agent 前端 177/177（含试运行 9）、RunOps 21/21；类型/构建、严格 UI 审计、架构 13 项与差异/本批链接检查通过。专用交互夹具另计 1/1，不重复累计。主任务抽查源码、断言、日志与浏览器材料，未重复运行这些测试。
- 证据边界：实际执行仅 GET 与明确 READ_ONLY/NONE 的两执行节点子集；HEAD、业务方法试运行、更多节点/写操作和真实环境未验收。360px、键盘焦点/IME 检查通过，原生 200% 快捷键未生效，继续保留缺口。RunOps 主查询成功，夹具未装载的候选辅助接口 404 与时间数组警告单列，不宣称 RunOps 全部动作验收。保留 WIP，未提交/推送/部署/升级开发库。

### BMAPI-3B3：多来源同 API 与冲突展示

- 关联 U3 第 1、4 步及 U4 第 1 步的最小来源状态验证；按[执行任务](../../output/tasks/business-method-api/BMAPI-3B3-多来源同API与冲突展示任务.md)，让同一项目/逻辑服务的 Controller 与 OpenAPI GET 正常发现链落到一个 API 资产、两个来源绑定。
- 当前结果：按[实施结果](../../output/tasks/business-method-api/BMAPI-3B3-实施结果.md)，正常扫描形成一资产两来源；浏览器完成接纳、显式 NONE 连接、真实 GET、Run/Trace 和同一 Workflow picker 引用。响应字段冲突在详情显示双方事实，新调用 owner 409、新发布 Runtime 400，零额外上游；unknown inventory 保留已知冲突与未确认状态。最终后端目标 225/225、前端 289/289、第四浏览器 launcher 1/1，主任务抽查证据，未重复跑测试。
- [主任务复核](../../output/tasks/business-method-api/BMAPI-3B3-主任务复核.md)按代表范围签收：R1 补 Studio 领域指引，R1b 使 Studio 发布请求由领域层接管错误反馈；穿过实际 Axios 拦截器的目标测试证明目标 400 只有一条可操作提示，401 会话清理和版本页默认行为保留。目标 26/26、当前 Workflow 204/204、认证 168/168、类型/构建通过，均由执行任务运行，主任务读源码/原始日志未重复跑；集合有交叉。发布前图校验仍未识别冲突，最终发布保护有效；前三次浏览器取消/失败不计入最终通过。
- 范围与停止点：仅一个已支持 GET 和一个明确契约冲突；写 API、复杂 schema、市场 binding、U4 完整影响和旧入口退场不混入本批。R1b 是 adapter 请求层验证，未重新跑浏览器或 Maven；原生 200%、真实环境及用户易用性继续保留。

### BMAPI-2E：业务方法写入试调用代表流程

- 关联 U1 第 1、2、4 步、U2 第 1–3 步与 Q4；按[执行任务](../../output/tasks/business-method-api/BMAPI-2E-业务方法写入试调用代表流程任务.md)，使用正常注解/Starter 接入的一条有状态 `WRITE` 方法，从业务方法详情完成明确确认和一次真实隔离写入，核对安全结果、Run/Trace 与原调用查询。
- 必要反例：取消/旧确认、缺确认的直接请求、来源或契约变化、权限/ACL/业务身份阻断均零方法命中；重复同 ID 和已派发后结果未知不自动重写。只修本流程暴露的阻断，复用现有 Console ledger 与 SDK Endpoint。
- 当前结果：按[实施结果](../../output/tasks/business-method-api/BMAPI-2E-实施结果.md)和[主任务复核](../../output/tasks/business-method-api/BMAPI-2E-主任务复核.md)限定签收。取消零派发，首次确认实际写入一次；第二次写入后丢失响应为 UNKNOWN，查询/整页刷新没有第三次写入；显式新尝试再确认后第三次写入，撤销方法 ACL 后无第四次。同 ID 并发、body/actor 冲突、来源漂移、缺确认、输入与业务身份拒绝另有纵向证据。
- 必要修正：正常 Console 被普通 debug 保护误拦，改为仅由经验证的内部 Console 命令在持久 claim 后提供 typed 证明并重建约束，业务身份仍为空，Studio/普通 debug 没有写授权；持久查询 key 去除请求代数，仍保护迟到响应。UNKNOWN 文案、RunOps 类型及窄屏确认同步修正。
- 新跑证据：后端 22 类/198、业务方法/API 前端 85、RunOps 22、Workflow 204、认证 168、类型/构建、严格 UI 0 findings、架构 13、文档/diff 通过；前端集合有交叉。只有第四浏览器宿主 1/1 正常完成，前三轮取消/失败不累计。均由执行任务运行，主任务读源码/日志/manifest/截图未重复测试。
- 停止点：只签收有状态 WRITE 方法的单 JVM/H2/loopback Console 代表流程；复杂图、业务用户桥接、独立部署、真实 MySQL/业务系统、原生 200% 和真实用户易用性未验收。RunOps 日期数组警告继续保留。

### BMAPI-3B4：写 API 受控试调用代表流程

- 关联 U3 第 1–3 步与 Q4；按[执行任务](../../output/tasks/business-method-api/BMAPI-3B4-写API受控试调用代表流程任务.md)，交付正常 Controller/OpenAPI 扫描的一条 POST + WRITE + JSON 扁平请求体 API，从目录/详情到确认写入、安全结果和原记录查询。
- 必要支撑：公共 GET policy、无 body/确认的 Console 契约和硬编码 GET 阻塞该操作；现有 HTTP client 手动重定向可能重复写入。补最小调用语义，复用 owner、连接、项目凭据、ACL、ledger 与 Run/Trace，并分开 Console 支持和 Studio/Workflow 只读边界。
- 首版结果：按[实施结果](../../output/tasks/business-method-api/BMAPI-3B4-实施结果.md)，正常 OpenAPI 发现/接纳/项目凭据连接、取消零派发、实际写入后 EOF/UNKNOWN、重开/刷新原 ID 查询、新 ID/确认及 ACL 拒绝已有真实浏览器证据；最终公开 POST 4、查询 4，上游写入/ledger/Run/根 Trace 各 3。新 Console/严格 GET policy 分离，真实 307/503/401/超时不重发，Studio/debug/最终发布拒绝写。执行任务后端 26 类/252、前端能力/API 97、Workflow 204、auth 168、RunOps 22、类型/构建/UI/架构及材料检查通过；集合有交叉，仅第二浏览器宿主 1/1 完整通过。没有独立 ToolCall 行，不混称或制造证据。
- 当前状态：R1/R2 已按[最终主任务复核](../../output/tasks/business-method-api/BMAPI-3B4-主任务复核.md)闭合并限定签收。真实公开 HTTP optional null 从旧版 200/写入一次变为 400/零派发；缺省、false、0、允许的空串保持原义。正常 OpenAPI 的普通命名 password-format/writeOnly 字段默认遮蔽，初响应/原 ID 查询/持久结果脱敏；响应前正常重扫接纳去掉声明仍按本次 prepare 固定敏感值保护。精确格式元数据的扫描过滤修正直接服务该场景，未扩展通用秘密模型。
- 修正证据：新后端 27 类/259、前端能力/API 101、Workflow 204、认证 168，类型/构建、严格 UI 0 findings、架构 13 与材料清理通过；定向 18 项包含在宽回归，不累计。聚焦真实浏览器 1/1，null 阶段公开 POST/上游/ledger 均零；成功写入各一，查询/刷新两次 GET 后各一且同 ID。首版完整 UNKNOWN/ACL 与 RunOps 22 保留为历史事实，本轮未机械复跑。主任务读源码/日志/manifest/截图，没有重复执行测试。
- 停止点：一个有状态 POST JSON Console 用户流程。写 API 的 Workflow 路径、复杂 schema、市场 binding、U4 完整影响、旧入口退场及真实环境继续保留，不自动进入其他批次。

### BMAPI-4A：API 变化与引用影响代表流程

- 关联 U4 第 1–3 步；已按[主任务复核](../../output/tasks/business-method-api/BMAPI-4A-主任务复核.md)限定签收，[实施结果](../../output/tasks/business-method-api/BMAPI-4A-实施结果.md)保留完整步骤与证据。3B4 收口后补齐此前尚未整体交付的变更处理和具体影响操作；写 API Workflow 仍作为明确缺口保留。
- 预期行为：正常双来源 GET API 及真实草稿/发布/已有 MCP 使用位置；区分展示变化、执行契约变化和来源未确认；候选字段差异可读，未知引用不显示为零；漂移时零派发，接纳不改旧 pin/授权/开放发布；从草稿修正并显式重新发布后新版本成功。一个/全部来源移除分别解释剩余来源和来源缺失，旧引用不自动迁移。
- 范围与必要支撑：现有 API 来源/accepted 事实、多来源比较、Control 引用读取、Runtime 索引/发布 pin 及对应页面的直接缺口。sourceSetRevision 含清单 token，不混称为 contract hash；保留当前一致性保护，不为展示比较放宽授权或来源确认。不扩展全站 UI、通用 RBAC、新资产模型或复杂契约。
- 实际结果：正常双来源、初始 v1/MCP r1 成功一次；展示/冲突/漂移、接纳和只发新 Workflow 均不更新旧绑定；显式 v2/MCP r2 后第二次成功，固定 v1 仍拒绝。UNKNOWN 引用恢复到草稿、v1/v2、MCP r1/r2 五处；历史链接定位原节点/hash。一个/全部来源移除、部分/失败清单及路由新身份都保留正确来源和旧引用，拒绝阶段上游零新增。
- 证据更正：原浏览器“草稿 409”为已发布状态前置门禁，不算来源保护。补充正常 Control POST/PUT 创建/回读真实无发布版本 DRAFT，全源移除/新路由均 HTTP_API_TRIAL_SOURCE_CHANGED，Console 为 CONTRACT_CHANGED，原 pin/ACL/MCP 不变；独立 HTTP 运行六处引用与浏览器五处分列，不覆盖旧证据。
- 验证与停止点：后端目标 16 类/97、新 DRAFT 补充 5/5、API 前端 105、Workflow 209、类型/构建/严格 UI 0/架构 13/文档/diff 与隔离清理通过，集合不加总；实际浏览器宿主 1/1。主任务读源码/日志/manifest/三张关键截图未重跑测试。签收限 H2/单 JVM/签名 loopback 与真实浏览器，原生 200%、独立部署/真实 MySQL/业务/用户、A2A 新流程及其余 U4 未验收。4A 结束，当前移至 3D。

### BMAPI-3D：API 市场受控绑定代表流程

- 关联 U3、U4 第 2–3 步与 Q5；2026-10-02 按[执行任务](../../output/tasks/business-method-api/BMAPI-3D-API市场受控绑定代表流程任务.md)、[实施结果](../../output/tasks/business-method-api/BMAPI-3D-实施结果.md)和[主任务复核](../../output/tasks/business-method-api/BMAPI-3D-主任务复核.md)限定签收一个已发布无认证只读 GET 的项目 API/Workflow 流程，不扩建市场或另造维护资产。
- 改动前阻塞：市场只保存项目/版本/Operation 意图，READY 冒充不了验证；作者生成 raw HTTP_REQUEST/marketRef，且不同外部服务同路径缺少身份隔离。当前实现由服务端固定目录事实进入同一 API owner，保持内部 identity/hash 字节语义；配置和实际 Console 当前验证分别成立。
- 已核对行为：来源选择→接纳/显式连接/目标 ACL→Console→正常 Studio 保存/只读试运行→显式发布→实际已发布 MCP 调用，各一次上游请求，共三次；来源下线旧发布 HTTP_API_SOURCE_NOT_READY 后仍三次。目录 v2 不自动改选，显式改选和恢复使旧 proof/pin 失效；引用 COMPLETE/UNKNOWN 分别真实显示；旧 raw marketRef 拒绝，普通 HTTP 路径保留。
- 验证：后端 32 类/326 项、市场 HTTP 7 项、最终第六轮浏览器 1/1 均零失败/错误/跳过；前端 API 106、Workflow 209、身份/权限 168、RunOps 22、builder 4 分别通过，交叉集合不加总。类型/构建、严格 UI、架构 13、文档与 SQL 静态核对通过；主任务读取源码/日志/JSON、目视六张截图并核对清理，不重复运行测试。
- 停止点与缺口：单 JVM/隔离 H2/签名 HTTP/loopback/browser 证据不替代真实环境。390px 需折叠侧栏操作，展开遮挡和原生 200% 未验收；RunOps 辅助 404/日期警告保留。复杂认证/schema、写 API Workflow、其余协议、旧入口退场、MySQL、独立部署与两类用户整体验收未完成。本轮复核结束，没有开启下一批，也未提交/推送/部署/操作真实库或授权。

### BMAPI-3C-D：写 API 已发布 Workflow 代表流程

- 关联 U3 第 3 步及 U4 发布保护，见[代表流程任务](../../output/tasks/business-method-api/BMAPI-3C-D-写API已发布Workflow代表流程任务.md)。3B4 Console 写入不能代替 Workflow 使用，本批将一个正常扫描 POST + WRITE JSON 扁平请求体从 Studio 映射、保存/重开、显式发布连接到实际写执行和 Run/Trace。
- 当前状态：按[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-D-主任务复核.md)已限定签收。[原结果](../../output/tasks/business-method-api/BMAPI-3C-D-实施结果.md)及[R1 结果](../../output/tasks/business-method-api/BMAPI-3C-D-R1-实施结果.md)的源码、失败/最终通过日志、manifest 与真实截图均已核对；合法必填空对象实际发送 {}，可选缺省/null/必填字段保护保持，390px 映射/下拉/保存重开与无摘要/回查说明收口，主任务未重复测试。
- 边界：Studio 继续只读；复用 API pin、可信入口、项目凭据、HTTP client 与风险/Guard。MCP 外层 ACL 和逐 API ACL 分别陈述，WRITE 不被 READ/投影覆盖降级，不另建授权/Console ledger 产品。
- 验收：同一浏览器作者图、实际写入/下游输出、正式入口授权/风险拒绝、Agent 既有确认、派发后未知不重发、原运行读取零追加写入，以及本条 POST 的来源/连接/凭据变化与显式恢复。隔离证据不代替独立部署、真实 MySQL/业务系统、原生 200% 或用户易用性验收。
- 当前证据：原目标 Common 12/Capability 16/Control 10/Runtime 203，共享 8 类/71；R1 Common policy 5/Runtime 56、Workflow 213/RunOps 32、类型/build、最终浏览器第二轮 1/1及[静态收口](../../output/tasks/business-method-api/BMAPI-3C-D-R1-静态收口结果.md)的 overlay/documentation/architecture 13/13。最终 Workflow 538c33692585 两次显式 POST（成功/UNKNOWN），查询后累计仍 2，三处引用 COMPLETE；交叉集合不加总。原生 200% 实际尝试未确认、流式夹具 500/历史 403、20 个浏览器警告及独立环境/真实用户等缺口继续保留。
- 停止点：R1 及静态收口已结束并经主任务复核，当前移至 5A；不自行扩建写调试/复杂认证/市场。保留 WIP，不提交/推送/部署/升级已有开发库或修改真实授权。

### BMAPI-5A：旧入口退场与正常接入接替

- 用户任务：U1 第 3 步、U3 第 1–3 步、U4 第 2–4 步；见[具体任务](../../output/tasks/business-method-api/BMAPI-5A-旧入口退场与正常接入接替任务.md)。开发者正常 SDK/Controller/OpenAPI 接入，实施人员直接从对应目录查看/试用/用于 Workflow，不维护第二份 Tool。
- 当前状态：已按[主任务复核](../../output/tasks/business-method-api/BMAPI-5A-主任务复核.md)限定签收；[实施结果](../../output/tasks/business-method-api/BMAPI-5A-实施结果.md)与[最终静态收口](../../output/tasks/business-method-api/BMAPI-5A-静态收口结果.md)均已读取，主任务核对源码/原始日志/manifest/五张关键图片，没有重复执行者测试。
- 范围：扫描来源 UI/API/服务、两个作者消费者、旧引用的准确处置及直接受影响协议回归。保留合法 SDK 自动投影、只读聚合、PAGE_ACTION、MCP/Tool ACL/Skill 技术契约和 owning service 边界，不做全平台权限/页面改造。
- 验证：七条公开/直接路由 scoped 410、缺失/跨项目 404；历史 ref 实际签名/Runtime 拒绝，旧草稿保留、发布零 version/pin；正常 API owner 1/来源 2、SDK 投影 3，Console/trial/发布后 HTTP 3 次、SDK 1 次、退场请求 0，四个 Run COMPLETED。来源核对 null Map 500 同签名修正，重启后同页面 200、十张表不变。
- 回归：后端受影响集合 348，来源核对最后目标 83 与其重叠不加总；前端 Workflow 212/退场 12/Market 4，build 成功；基线死 helper 链接由主任务修正后，架构 13 项、85 文档/5 服务 README、diff 通过。此前真实失败材料保留，非零浏览器 auxiliary/日期数组诊断没有删掉。
- 边界：同 JVM/H2/合成身份，不是独立 MySQL/五服务；390px 需正常折叠侧栏，原生 200% 和真实用户实测未完成。A2A/Embed 专用全量 E2E 没有冒称已跑。本批自有资源已关闭，临时来源/认证文件已清理，共享 WIP 保留。

### BMAPI-5B：独立环境与两类用户任务验收

- 用户任务：U1–U4，复核当前最终工作树的 Java 接入、两类用户交接、方法/API 使用、变化处理、旧入口与必要协议；见[具体任务](../../output/tasks/business-method-api/BMAPI-5B-独立环境与两类用户任务验收任务.md)。
- 当前状态：执行者已交回[实施结果](../../output/tasks/business-method-api/BMAPI-5B-实施结果.md)并关闭自有环境；[主任务复核](../../output/tasks/business-method-api/BMAPI-5B-主任务复核.md)确认主体代表事实，发现的保存竞态经 R1 修正复核。5B/R1 已限定签收，不包含下述保留缺口。
- 实际环境：MySQL 8.4.11、独立 datadir/13316、当前 initV2 无 --force 完整成功，186 表与中文回读；五服务与两个 SDK 独立进程、JDK17、生产 signer/verifier 和正常 LOCAL 登录。主任务只读核对 1761 源码与记录 JAR/SQL 的 SHA，零漂移。Control/Knowledge 可选 Redis health DOWN、Milvus Lite 非完整向量环境的边界保留。
- 代表结果：DTO→follow 正式 Run 5、标量 readonly draft Run 6；GET Run 7–9、POST Console Run 10、UNKNOWN Run 11 与查询零重发、固定 MCP 新运行 Run 12；CHANGED/显式 Workflow 新版/显式 MCP 新修订后 Run 16，REMOVED 后 Run 17/Console 零派发。引用中断 UNKNOWN/恢复五引用、跨项目与 actor 隔离、七旧路由退场有持久证据。
- 页面与回归：六张实际图片已目视复核；自有 Chrome tabs.setZoom/getZoom 原生 200% 读回 2，390px/键盘代表流程有材料。已读执行者 Control 17、Runtime 78、Workflow 223、RunOps 35、build/Premium strict/架构 13 项通过，不加总或重复执行。5A 的 H2 日期数组问题在 MySQL 代表页面未复现；模型/知识 BFF 辅助目录仍缺有相应节点的真实验收。
- 清理：十个自有根进程关闭，库/profile/随机凭据删除、CLOSED guard 生效；主任务只读复查敏感路径和十端口为空。脱敏证据与[重放/人工试用说明](../../output/tasks/business-method-api/BMAPI-5B/BMAPI-5B-环境重放与人工试用.md)保留，未来从模板另建新环境，不恢复已关闭环境或重复 once/UNKNOWN 调用。
- 保留缺口：两角色自动走查不等于真人；有限 Embed/A2A 拒绝反例不等于有权正例；本批正式 POST 为 MCP 新运行，未替代当前 Agent execute/resume 证据。市场 raw ref 拒绝未替代当前 owner 固定 GET 接入回归；复杂认证/客户系统没有冒称已验收。

### BMAPI-5B-R1：保存期间资源加载竞态修正

- 用户任务：U2/U3 在同一 Workflow 中保存草稿时仍能获得有效候选；保护直接受影响的共享 Studio 消费者。
- 问题：工作副本正常保存替换对象，model/knowledge 请求按对象身份失效，已请求标志却不重置，同作用域 watcher 不再触发加载。见[具体修正任务](../../output/tasks/business-method-api/BMAPI-5B-R1-保存期间资源加载竞态修正任务.md)。
- 当前状态与范围：已交回并[限定复核](../../output/tasks/business-method-api/BMAPI-5B-R1-主任务复核.md)。仅 hook/测试改稳定作用域+序号、同步切换重置和销毁保护；未改后端目录、权限、写调用或产品边界，旧 5B CLOSED 保持。
- 验证：真实 saveStudio 两分支的前失败 2/9 对应后通过；最终资源/WorkingCopy/Persistence 33、完整 Workflow 236、类型/build/Premium/diff 通过，重叠不加总。主任务只读核对当前两个源码 SHA 和其他 457 个 WIP 零漂移，没有重复执行者测试。未新增实际模型/知识 BFF 或真人正例。

### BMAPI-5C：市场固定 GET 与授权入口验收

- 用户任务：U2/U3 将市场固定 GET 验证后用于 Workflow/Agent；U4 的旧 pin 与已发布开放入口受当前来源保护，关联 F07/F08/F10/F13。见[具体任务](../../output/tasks/business-method-api/BMAPI-5C-市场固定GET与授权入口验收任务.md)。
- 当前状态：[技术范围通过主任务复核](../../output/tasks/business-method-api/BMAPI-5C-主任务复核.md)，体验缺口保留，不是整体签收。
- 实际操作：正常 market→同一 API owner→Console VERIFIED→Studio 保存重开/只读 trial/显式发布；平台 Agent 固定调用 API 与 SDK、Embed 正常业务会话/Starter Broker、A2A 有限 principal/publication/Task 实际成功。平台/Embed 写确认、取消、重复确认和 null/缺失输入有实际派发/回执，最终订单 9、方法 1、写派发/实际写各 2。
- 变化保护：停用后平台/Embed/A2A API 节点拒绝且零派发；恢复后旧 proof 拒绝，重新验证后旧 pin 仍拒绝。Workflow 4/Agent config 3/A2A revision 3 分别显式新发布，原版本/快照/引用保留，新的平台与 A2A 调用实际使用版本 4。
- 必要支撑：模型 BFF 复用 owner 且保留会话/CSRF/项目权限；服务端发布快照的明确空 remote bindings 不要求无关权限，非空继续核实；A2A tenant 取自可信签名身份而非 metadata。受控 loopback 模型只证明生产 Gateway/AgentScope 集成，不冒称外部智能或客户系统。
- 验证与资源：当前源码/10 个支撑改动/其他 459 WIP/6 JAR 零不一致；已读最终目标、构建、Premium/架构原始记录，重叠集合不加总，主任务未重跑。10 个敏感路径和 14 端口只读复查为空，原环境 CLOSED。六图中准确完成提示及关闭/链接可见焦点不满足体验签收。

### BMAPI-5C-R1：确认结果与键盘焦点收口

- 用户任务：U2/U3 确认受控写后看懂实际结果，键盘操作市场详情时看见焦点；关联 F06/F10/F11/F13。见[任务书](../../output/tasks/business-method-api/BMAPI-5C-R1-确认结果与键盘焦点收口任务.md)。
- 当前状态：[通过本批范围限定复核](../../output/tasks/business-method-api/BMAPI-5C-R1-主任务复核.md)，整体与真人验收未签收。
- 实际闭环：新正常会话只读实际客户编码、WRITE 当前待确认零派发、confirm-3 原回执/Trace COMPLETED 且写 1→2，规范重复计数 2→2/Span 5→5，cancel-4 零派发且 CANCELLED。诊断 confirm-1 的原成功/误报保留，不再点旧确认；总写 2 包含诊断 1，未冒称全环境只执行一次。
- 产品与夹具：产品仅 AppDrawer 共享 focus-visible 13 行，链接内偏移避免裁切；回复修正在受控模型的本次 TOOL/批准恢复结果读取，字段来自实际返回，生产 Conversation/权限/状态未改，“已提交”不改成业务完成。六组桌面/390px/原生 200% 的浅色/暗色兼容记录成立，暗色探针不是用户开关。
- 验证与资源：最终 SDK 101、市场 4、结果转换器 7、类型/前端与 SDK 构建、Premium/架构通过，集合不加总；Java 产品未改，未重复广泛后端测试。源码/WIP/产物/图片零不一致，initV2/新 MySQL UUID/中文成立；11 敏感路径和 14 端口只读复查为空、环境 CLOSED。未知转换器单元断言不算真实 UNKNOWN 写，未启动 Redis 和初始部分 health DOWN 不混称全平台就绪。

### BMAPI-5D：最终证据映射与试用交接

- 用户任务：U1–U4、原 F01–F13 整体收口；见[任务书](../../output/tasks/business-method-api/BMAPI-5D-最终证据映射与试用交接任务.md)。
- 当前状态：[通过本批范围限定复核](../../output/tasks/business-method-api/BMAPI-5D-主任务复核.md)，只签收证据映射、两文档窄修与两角色试用步骤；不签收整体/真人或四个未验独立场景。
- 范围：按原完成条件逐项指到当前源码、实际操作/环境/身份和原始材料，核对旧入口、文档与迁移限制；对缺项写具体不足及最小补验，不删范围或重复业务写。两角色试用步骤与反馈模板不能预填真人通过。
- 结果：原F01–F13、74原路径与4个补充阶段可追溯；5B独立CONFLICT事实已存在，不沿用早期extract误判。两文档修正后documentation/Premium通过，主任务只读源码/WIP/JAR/78材料/139链接/5份测试manifest均匹配，33敏感路径不存在、24端口无监听。没有新环境、业务调用或重复测试/build。见[实施结果](../../output/tasks/business-method-api/BMAPI-5D-实施结果.md)和[真人步骤](../../output/tasks/business-method-api/BMAPI-5D-两角色试用步骤.md)。
- 剩余与后续：G01同内容重扫、G02冲突停留用户保护、G03独立Java UNKNOWN、G04方法变化/移除、G05真人、G06项目角色首页；下一唯一批次5E补四个技术场景与入口。真人安排仍待答，环境不为未安排试用留凭据；原CLOSED不恢复，不提交推送部署、不动已有库/真实授权/客户系统。

### BMAPI-5E：剩余独立场景与项目角色入口

- 用户任务：U1/U2方法原结果查询及声明维护、U3重扫/冲突、U4固定引用保护、F02/F05/F08/F11；见[任务书](../../output/tasks/business-method-api/BMAPI-5E-剩余独立场景与项目角色入口任务.md)。
- 当前状态：[主任务限定复核](../../output/tasks/business-method-api/BMAPI-5E-主任务复核.md)签收E1–E4及来源400透传；E5全域受限/允许有真实角色代表证据，部分授权缺口已由[R1限定复核](../../output/tasks/business-method-api/BMAPI-5E-R1-主任务复核.md)闭合。5E技术范围已收口，整体/真人仍未验收。
- 预期行为：相同内容无重复资产/绑定/投影或派发；冲突停留可解释且新Console/发布拒绝；Java写后UNKNOWN只查原ID；方法变化/移除保护原具体版本/引用，经显式处理发布后使用新输入。PROJECT默认首页解释受限范围并有可达任务入口，保持真实授权和正常会话，不持续重刷已拒请求。
- 范围与验证：同一新MySQL/五服务/SDK/有限角色共用一次前置，各例分别采正常HTTP/浏览器/原RunTrace/receiver；最小受限入口支撑与实际阻塞修正，按真实改动跑目标测试/build/架构。无变集合不重跑，不重新搭已签收市场/Agent/Embed/A2A全矩阵。
- 已复核证据：同内容资产11/双绑定11、15/投影0均不增；required冲突时accepted/工作副本/版本/pin/receiver不变，409与修后400定位来源。Java WRITE invocation 75bdec90-1a62-408c-adfb-526edac0e3dc原ID重开/刷新，ledger/receiver仍1；Java READ同definitionId3契约value→key，新显式版本/修订后read1→2，移除注解后拒绝且仍2。有限角色有共享入口与390px键盘证据，既有GLOBAL正常总览作为单独反例。
- 验证与边界：执行者dashboard80/auth168/Control11项通过，前端build/Control package成功；三项旧全站UI守卫失败原样保留，不扩大本批。主任务源码/WIP/JAR/链接/材料hash核对与13路径/9PID/11端口清理复核通过；原环境CLOSED。完整证据见[实施结果](../../output/tasks/business-method-api/BMAPI-5E-实施结果.md)、[索引](../../output/tasks/business-method-api/BMAPI-5E/证据索引.md)。不把Run/Trace FAILED概括成UNKNOWN或业务未执行。
- 停止点：真人G05不预填、不重复问、不留秘密等候。保留原F01–F13、只读Studio、Tool投影和共享WIP，不扩平台权限、不碰已有库/真实授权/客户系统或部署；R1只补部分授权与材料层次。

### BMAPI-5E-R1：首页部分授权补修

- 用户任务：U1–U4正常入口、F11/F13；见[任务书](../../output/tasks/business-method-api/BMAPI-5E-R1-首页部分授权补修任务.md)。这是原E5保留有权总览行为的窄补修，不新增产品决定。
- 当前状态：[主任务限定复核通过](../../output/tasks/business-method-api/BMAPI-5E-R1-主任务复核.md)。已先复现单域403/health403隐藏其他授权域，再修现有Dashboard按域展示/刷新；只在四必要运营域全403时退到授权任务入口。
- 预期行为：拒绝域不自动重刷，允许域数据与刷新仍可见；全部无运营权限保留准确范围说明/共享菜单任务入口；非403故障、401、身份竞态和空数据授权状态分清。
- 范围与验证：必要前端目标回归/build与当前产品浏览器分支证据，HTTP夹具不称真实权限集成；已有PROJECT/GLOBAL真实代表事实引用5E，不重跑Java写/READ/API/发布或开放协议全矩阵。E3材料修正只厘清收据/RunTrace层次，不改运行时模型。
- 验证与边界：已读执行者9文件93/93、build/Premium通过记录，当前SHA相符。主任务独立核对六份前后字节、3241源码/481WIP/6JAR、31链接、13浏览器观察/56命令，目视七张核心图；169请求全是明确夹具GET，四次观察器错误保留。R1私有路径/进程与旧5E十三路径、合并12端口无残留，源码仅六个授权差异、两份E3说明窄修，没有业务重发。
- 停止点：本批结束，转5F；真人与最终收口未完成，不标100%。既有三项全站UI守卫失败保留，不称已通过，不恢复CLOSED或真实系统。

### BMAPI-5F：最终守卫与受影响集合收口

- 用户任务：U1–U4当前合法使用与直接消费者，F11/F13；见[任务书](../../output/tasks/business-method-api/BMAPI-5F-最终守卫与受影响集合收口任务.md)。
- 当前状态：[主任务限定复核通过](../../output/tasks/business-method-api/BMAPI-5F-主任务复核.md)，仅四授权文件；主题/组件已通过，页头保留MCP可选eyebrow及账号治理overview的旧规则失配，转仅脚本5F-R1。A2A当前路由owner、真实Supervisor结构、HEAD检索页及已有空态/焦点保护均有正反例，不称三项主守卫全部通过。
- 预期行为与范围：保留既有功能/产品边界，验证器精确检查当前owner和原语义；正例通过且真正删除焦点、错误空态、缺失稳定Workflow工具/配置版本或header的反例仍拒绝。不得删除coverage、无条件通过或强迫产品恢复旧页/文案。不扩平台整理或权限。
- 验证与下一步：已核对theme 8、components 375、headers 33个变异及安全/no-op材料；前两项exit 0，headers exit 1两失败明确。build/Premium/架构/文档/diff记录通过；十五项主任务字节/日志/链接核对通过，四图覆盖桌面与960px键盘及弹层，只是受控夹具；旧零态、原有标题裁切、暗色及真人不冒充覆盖。原F13继续待5F-R1与整体复核，G05不预填；本批资源已清理、旧环境保持CLOSED。

### BMAPI-5F-R1：页头规则精确对齐

- 用户任务：U1–U4直接消费者与F13；见[任务书](../../output/tasks/business-method-api/BMAPI-5F-R1-页头规则精确对齐任务.md)。
- 当前状态：[主任务限定复核通过](../../output/tasks/business-method-api/BMAPI-5F-R1-主任务复核.md)，仅页头守卫改变；MCP与账号页面物理字节与初始/不可变HEAD相同，现有shared primitive和页面职责支持当前结构。
- 预期行为与范围：MCP可选eyebrow、必须有overview/platform的可达共享页头及非空用途描述；账号治理overview保留enriched/刷新。其他断言与33个反例/4安全变体/no-op保留，新增错误类型/描述/可达性反例，不能直接删coverage制造绿灯。
- 验证与下一步：三守卫exit 0、页头47反例/6安全/当前正例/no-op成立，strict/文档/diff原件通过，21项主任务核对通过；引用5F无产品漂移的build/架构/浏览器证据，没有新环境或业务重跑。原F01–F13映射/来源链成立，F11后改页面原生缩放与真人仍未验，转5G。

### BMAPI-5G：当前首页与Agent原生缩放补验

- 用户任务：U1–U4正常入口与Workflow-as-Tool直接消费者，原F11/F13；见[任务书](../../output/tasks/business-method-api/BMAPI-5G-当前首页与Agent原生缩放补验任务.md)。
- 当前状态：[主任务限定复核](../../output/tasks/business-method-api/BMAPI-5G-主任务复核.md)已完成；[当前交付](../../output/tasks/business-method-api/BMAPI-5G-实施结果.md)为部分通过，首次控制失败报告副本与[阻塞复核](../../output/tasks/business-method-api/BMAPI-5G-控制入口阻塞主任务复核.md)保留。用户“可以的”批准具体路线后已执行并清理；共享侧栏键盘失败转同批R1，不把部分通过写成本批或整体完成。
- 实际行为与范围：新有限GET夹具的当前首页部分/全部403、Agent v7两项完整字段与概览状态有原生200%和正常焦点证据，四次原生请求/回读2、CSSzoom1，结束回读1；实际是独立Playwright Chromium136，不冒称用户日常Chrome或真实平台授权/后端/客户业务。三项内容区任务链接可达，不能替代失败的共享菜单。
- 验证与停止：35项主任务只读核对、49原CLI结果、524当前引用SHA及54链接成立，目视五图；590旧材料/6JAR/产品源码不变。唯一共享进度差异按主任务归属和精确SHA记录，执行者严格失败与双方观察器首次失败不覆盖。143.173秒/63.351秒拒绝GET均不增，允许域继续读取，只证明窗口内行为。当前精确私有路径/进程/5297/5298无残留。32次正常Tab与方向键停在scrollbar，三菜单tabindex -1，基线第8节批准最小共享支撑；不恢复CLOSED、不重发业务写、不留环境等真人，G05和整体未100%。

### BMAPI-5G-R1：共享侧栏键盘入口修正

- 用户任务：U1–U4资产正常入口、原F11/F13；见[任务书](../../output/tasks/business-method-api/BMAPI-5G-R1-侧栏键盘入口修正任务.md)。[主任务限定复核通过](../../output/tasks/business-method-api/BMAPI-5G-R1-主任务复核.md)，执行回合 `01a1043c-091d-7151-bece-366cb200ac14` 已完成，游标`:10`；本批结束，无新实施批次下发。
- 预期行为与范围：正常Tab进入纵向菜单、键盘移动/展开/折叠/单次激活原资产路由，焦点在自有滚动区内可见且可退出；保留权限过滤、菜单顺序、项目/退出及共享WIP。只允许AppSidebar菜单接线/样式、必要单一键盘composable和有意义的目标测试/入口，不扩权限、路由、SQL、后台或全平台页面。
- 实现与证据：真实Element Plus菜单单一Tab入口、原节点键盘/单次激活、自有滚动区和主题焦点；四个源码变化，不改权限/路由/SQL/后端。执行者9项目标、177 auth、93 Dashboard及类型/build、三守卫有效反例/严格检查原件通过，集合不加总。真实展开/折叠三资产路由、子菜单/退出及有限过滤、当前首页/Agent字段有32份raw匹配结果和13个实际原生2/CSS1证据；结束恢复1。暗色只作兼容探针、GET夹具不冒充授权集成/真人。
- 复核与清理：38项源码/字节/raw核对、七图目视、四项当前精确路径/进程/5307/5308清理通过；1143旧材料/6JAR/共享WIP保持。首次观察器三处误判与枚举顺序比较已按实际原件修正；报告误指失败文件的路径在主复核勘误，原报告与失败不覆盖。

### 原 F01–F13 当前技术集合复核

- 已完成：[主任务技术证据集合](../../output/tasks/business-method-api/BMAPI-最终技术证据主任务复核.md)逐项保持原完成条件及实际限制；[当前11项只读核对](../../output/tasks/business-method-api/BMAPI-5G-R1/root-review-20261004T0127Z/current-technical-evidence.json)通过。74原映射文件字节一致，5D以来19个前序变化与4个R1变化全部复核，5F-R1→5G→R1无来源断链，当前3243产品文件与最终交付一致。主任务没有重复业务/产品测试/build/浏览器。
- 仍未完成：原G05开发者D1–D4/实施人员I1–I6真人反馈、F11用户理解/可发现性和F13一次最终整体签收。模板仍空，安排问题已问未答；不重复问、不以技术自动化填真人，不制造百分比。没有已知技术待办时不新增任务/扩大范围；收到真实反馈后自主整理必要修正、复核并完成原清单签收。全部条件满足前续办保持启用。

### BMAPI-UAT-START：供用户本人查看的前后端启动

- 用户任务：用户确认技术完成状态后明确要求启动前后端供本人查看；关联U1–U4真实入口与G05/F11人工验收准备，启动本身不是反馈通过。唯一当前操作批次，见[任务书](../../output/tasks/business-method-api/BMAPI-UAT-START-前后端启动任务.md)，由既有执行窗口执行。
- 范围与预期：核对已配置MySQL/Redis/Milvus、当前JAR freshness/JDK17，真实Model/Knowledge/Capability/Runtime/Control与Vite、逐服务health和正常代理登录/PID/日志/WIP核验。现有库只读前态缺8表6列后，用户明确要求直接执行远程升级、不新建本地库；本批授权变更已取代此前不升级限制。主任务已复核20260915资产类型、20260916方法Console、20260920 API基础、20260923清单接纳→Console、20260924 Workflow pin、20261001市场binding共七份增量，顺序与原任务末尾一致。
- 实际执行：原远程39.106.238.224:33106/reach_ai，178表完整备份采用DPAPI CurrentUser与private ACL保护、明文未落盘，解密回读验证后逐份执行七份原SQL，全部exit0、SHA与任务一致。最终186表、目标类型117/索引32/中文10项无失败；既有授权绑定与八表原字段摘要在升级后/登录交付后保持，只新增capability:invoke定义。DDL前只核对当前账号可见连接与In_use，不宣称有全局PROCESS可见性。临时凭据文件已删除，加密备份保留，未创建本地库。
- 启动与复核：[实施结果](../../output/tasks/business-method-api/BMAPI-UAT-START-实施结果.md)、[主任务复核](../../output/tasks/business-method-api/BMAPI-UAT-START-主任务复核.md)。五服务/Vite统一原远程依赖，Control进程级bootstrap-admin=false；最后health-05包含短超时与单独重试后的实际五服务UP、前端200，正常已有账号me/项目33业务方法/API目录HTTP200且真实total0。Knowledge早期Redis5秒超时/DOWN与旧集合告警保留，后续恢复UP，不承诺知识后台已修复或长期稳定。主任务只读核对3243源码/486WIP/19保护文件/6JAR与18进程身份，15488前态材料按前后清单和执行者原audit复核；没有重复产品测试/构建/浏览器/数据库请求。
- 留存与边界：Vite5200 PID13876，Model18601/15092、Knowledge18602/300（/ai）、Control18603/27068、Runtime18604/32904、Capability18605/20784保持运行；验证浏览器已关闭。结束查看后只按本批Stop-OwnedStack.ps1及创建时间/命令SHA停止自有进程，不停远程依赖、不删备份。首备份参数错误、前端引号失败、遗漏projectId与GET登录误探针、freshness旧描述和主任务DateTime观察器误判均保留并勘误。G05/整体仍false；续办实际PAUSED保持，未恢复计划、减少原验收项或填充假资产。

## 5. 后续批次记录模板

完成一批后保留紧凑结果，将“当前执行位置”移到唯一下一步。长日志放到对应输出目录，只在这里链接。

```text
批次 / 日期：
关联用户任务与基线决定：
改动前后的具体行为：
本批文件 / 模块 / 表 / 路由边界：
必要支撑项及它阻塞的具体场景：
验证场景和期望结果：
实际修改与现有 WIP 的关系：
代码 / 静态 / 模块测试 / 浏览器 / 真实调用证据：
未验证部分与原因：
旧入口接替与退场状态：
下一步及停止点：
```

## 6. 设计决策与范围变化

| 编号 | 问题 | 状态 / 结论 | 证据与影响 |
| --- | --- | --- | --- |
| D1–D6 | 基线中的产品方向 | 2026-09-15 用户确认 | 本专项会话；不再重复询问用户角色与整体改造路线 |
| Q1 | Controller 的双重声明 | 方法声明与 HTTP 映射形成各自调用契约，保留实现关联 | 完整分流在 BMAPI-3；2A 不改变现有路由与 skip |
| Q2 | 身份、版本与调用投影关联 | 明确类型进入来源 metadata，投影列仅作派生；保留已有方法稳定引用 | 2A 实现类型贯通；API 有界编码与冲突规则在 3 具体化 |
| Q3 | 来源与平台字段所有权 | 来源拥有契约，平台拥有接纳与独立补充 | 不提供类型、风险和来源契约的旁路覆盖 |
| Q4 | 试调用执行路径 | 独立 Console 入口、项目凭据身份、调用权限与 ACL ALLOW、持久执行 / 结果查询 | 2C 规格已确定，按 A 后端 / B 页面推进；真实业务用户桥接不在本批构造 |
| Q5 | 扫描 / 外部 API / 投影收口 | 统一进入 API 资产，发现/配置/验证分别表达 | 3 包含外部 Operation 到执行的缺口，不扩建市场 |
| Q6 | 开发数据与已有引用处理 | 首批增量字段、旧行未分类、保留稳定引用 | 不执行升级或盲加唯一索引；数据处置待限定范围证据 |

新增或改变决定时，记录原决定、证据、具体方案、影响和确认来源。常规实现选择记录理由即可，不新增逐批审批流程。

## 7. 既有 WIP 与后续事项

- 当前工作树包含本轮开始前的前端、Control / Capability 及文档 WIP。本轮不恢复、覆盖或回滚这些实现。
- 历史 A2d0：按[旧交接记录](../../output/analysis/project-review-20260913/实施进度.md)保持暂停；历史测试未经本轮重验，不作为专项通过证据。
- 全平台范围一致性、通用权限改造、Agent / Workflow 全页面整理等不作为本专项前置；出现真实阻塞时，按基线第 8 节记录最小依赖。
- 当前没有新增的范围外实施事项。后续发现时在这里说明价值与延期理由，避免混入当前批次。
