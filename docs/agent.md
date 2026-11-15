# Agent 与标准 Skill

## Agent 配置

Agent 是稳定身份；模型、提示词、策略、记忆及可调用 Workflow 属于配置版本。正常业务入口使用已发布配置；管理端在 `agent:debug` 和项目授权通过后，可显式调试草稿，运行标记为 DEBUG。

AgentScope Supervisor 负责理解、规划、从版本化 Workflow-as-Tool 白名单选择零个或多个流程，并在上限内重规划。新建 Agent 默认 STANDARD；研发模式不绕过风险、身份或来源保护。规划步数、调用次数与超时来自配置，不由客户端字段提升。

只读调用可按策略并行，写操作串行并进入确认；页面动作需要明确的页面意图。Agent 不直接维护第二套业务方法/API 契约。绑定、发布和执行都核对精确目标版本及当前可用性。

### 使用项目全部已接入能力

配置里的「可使用本项目全部已接入能力」（`toolCatalogMode=PROJECT_CAPABILITIES`）让助手不必先为每个能力搭 Workflow：业务系统在「MCP 互联中心」向员工开放的能力，助手按需求查找后直接调用。新建 Agent 默认打开；关闭后（`ALLOW_LIST`）只用绑定的 Workflow。

| 项 | 规则 |
| --- | --- |
| 能力来源 | 与员工用自己的 AI 工具连接时是同一份发布内容，Agent 不另存契约；标为不可撤销的能力不开放 |
| 身份 | 只对业务系统内对话中已验证的员工生效，以该员工在业务系统里的账号和角色调用；控制台调试、自动化、A2A 没有这组工具 |
| 权限 | 沿用调用权限规则；项目关闭「员工使用」或账号未同步时，助手查不到也调不了 |
| 写入 | 项目开着「写操作需要审批」时，写入只提交审批，通过后以员工身份执行一次；助手如实告知「已提交审批」 |
| 用量 | 每轮最多 6 次查找、12 次调用；写入失败后本轮不重试 |
| 记录 | 每次调用进调用日志和审计导出，工具显示为「业务系统内的助手」；运行记录只留能力名和参数名，不留参数值 |

绑定的 Workflow 仍然优先：有匹配的流程就走流程，没有才由模型查找能力并决定调用顺序。运行记录的 `decisionMode` 区分两者：`WORKFLOW` 是固定流程，`CAPABILITY` 是模型推理。

### 直达流程与推理沉淀

未知的请求第一次走推理，重复出现的请求改走固定流程，减少模型轮次、耗时和 token。

| 项 | 规则 |
| --- | --- |
| 直达回答 | Workflow 回复节点打开「直达回答」（`directAnswer`）后，流程回复原样作为最终回答，不再经过计划和改写两轮模型调用 |
| 适用流程 | 只对已验证的只读流程生效：节点只有只读业务方法和纯计算、模型节点，风险下限为 READ |
| 调用方式 | 请求明确匹配一个直达流程时，模型可不写计划直接调用，运行时记录单步计划；一轮只走一个直达流程，其他情况走原有计划路径 |
| 沉淀建议 | Agent 配置页列出近 30 天助手至少两次靠推理、且只调用一个只读能力完成的请求 |
| 生成草稿 | 「生成流程草稿」创建 `用户问题 → 业务能力 → 按结果回答 → 回复` 的 Workflow 草稿，可在 Studio 里修改，不对员工生效 |
| 确认启用 | 由有 Agent 和 Workflow 发布权限的人点「确认启用」，才会发布流程并发布带该流程的助手新版本 |
| 第二次跳过选工具 | 模型选中直达流程并原样交付回答后，在可信身份、同一会话且没有持久会话记忆或其他上下文时，完全相同的问题可跳过模型选工具。只匹配当前仍可执行的直达流程，发布契约和绑定契约均须明确只接收 `question` 且禁止额外字段；有其他业务输入、上下文依赖或条件无法确认时仍交给模型。记忆只在 Runtime 内存中，服务重启后重新积累 |

接口：`GET /api/agents/{agentId}/learned-flows`、`POST /api/agents/{agentId}/learned-flows/draft`、`POST /api/agents/{agentId}/learned-flows/enable`。运行记录用 `directFlow`、`directFlowDelivered` 标出直达流程。用量页「费用」里的「推理与流程」按靠推理完成、走固定流程、普通对话三类统计次数、平均耗时和 Token（`GET /api/mcp/usage/routes`）。

## 记忆与知识

| 能力 | 归属与用法 |
| --- | --- |
| 会话记忆 | Runtime 保存同一会话上下文，按会话与身份隔离 |
| 个人长期记忆 | Control 维护 canonical 数据，Knowledge 提供可重建检索投影 |
| 知识库 | 通过已发布知识问答 Workflow 使用，检索授权与证据策略见[数据与模型](data.md) |
| 业务索引 | 只用于发现，消费内容前按当前身份回源鉴权 |

Agent 的记忆策略决定是否召回、提取及何时写入。用户说“记住……”时直接生效；个人记忆分「个人记忆 / 项目记忆」两层，每层下用户自建分组，在“我的记忆”里新增、编辑、删除，不设候选和审计；所有者取自已验证身份，不能从请求体接受。新版源码中嵌入用户的个人记忆默认按已验证的业务系统身份隔离；旧记录保留原身份域，不自动合并。跨系统可信绑定与目标环境验收条件见[当前边界](status.md)。长期记忆、短期会话、大结果工件不能互相混用。

上下文工程只压缩会话或卸载超大 Tool 结果；完整结果作为 Runtime 加密短期工件保存。内部规划和 reasoning 不作为公共内容输出，配置是否启用以服务设置为准。

## 标准 Skill

Skill 是包含 `SKILL.md` 的标准目录，平台用 ZIP 传输。单包导入一个 Skill；仓库包先发现候选，再选择一个子树，统一经过校验、审核和发布。不能从 publisher 字符串或下载地址推断可信。

| 范围 | 访问约束 |
| --- | --- |
| PRIVATE | 所有者与平台管理员 |
| PROJECT | 对应项目权限，只绑定同项目 Agent |
| SHARED / PUBLIC | 相应全局读取、导入与绑定权限 |

Control 在保存和发布 Agent 配置时重新证明可见性、作用域、版本与摘要；Runtime 使用受证明的精确绑定。升级显式选择 PUBLISHED 版本，不自动跟随 latest；项目级 Skill 不得借移动 Agent 绕过隔离。

运行前复核状态、ZIP / 文件树摘要和 Host 能力。必需 Skill 失败则阻断，可选 Skill 按策略跳过并记录原因，不回退到未校验缓存。MODEL_SELECTED 按需加载，ALWAYS 固定主说明，EXPLICIT 不在普通对话中自动激活。

脚本策略与内容加载分开。普通绑定和 AI Coding 默认不开放脚本执行；执行脚本还需 Host、沙箱和明确策略，不能把 Skill 的文字指令当作权限。

Agent 配置的旧版 `/config-versions/draft`、`/{configVersionId}/publish` 和 `/{configVersionId}/copy-to-draft` 写入口在 Control 与 Runtime 均返回 `409 AGENT_DRAFT_REVISION_REQUIRED`，不再修改配置。控制台客户端先读取 `/api/agents/{agentId}/config-versions/draft-context`，再使用 `draft-if-unchanged`、`publish-if-unchanged` 或 `copy-to-draft-if-unchanged` 并原样提交修订；不能由旧接口代取最新修订后自动执行。项目级外部客户端遵循 [Agent AI Coding](ai-coding.md#agent-api)。

## 内置包与来源

内置包随 Control 发布，使用 `BuiltinAgentSkillSource` 和版本摘要清单维护同版本同字节。修改说明、参考文档、脚本或内嵌资源必须升版本并更新 [builtin-agent-skill-releases.json](../reachai-control-service/src/test/resources/builtin-agent-skill-releases.json)，不能覆盖已有版本或绑定。

外部 Skill 市场引入固定来源与不可变版本，仍走相同的范围、校验和审核链。标准 Skill 与业务能力、MCP Tool 的职责不合并。

运行记录沉淀由 [AgentSkillRunCaptureService](../reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillRunCaptureService.java)确定性生成草稿；只描述工具、字段与调用顺序，不带真实业务参数值。保存进入 REVIEW_PENDING，再经原审核发布流程。员工按项目权限下载已发布包，不自动发布或推送到其 AI 工具。
