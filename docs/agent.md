# Agent 与标准 Skill

## Agent 配置

Agent 是稳定身份；模型、提示词、策略、记忆及可调用 Workflow 属于配置版本。正常业务入口使用已发布配置；管理端在 `agent:debug` 和项目授权通过后，可显式调试草稿，运行标记为 DEBUG。

AgentScope Supervisor 负责理解、规划、从版本化 Workflow-as-Tool 白名单选择零个或多个流程，并在上限内重规划。新建 Agent 默认 STANDARD；研发模式不绕过风险、身份或来源保护。规划步数、调用次数与超时来自配置，不由客户端字段提升。

只读调用可按策略并行，写操作串行并进入确认；页面动作需要明确的页面意图。Agent 不直接维护第二套业务方法/API 契约。绑定、发布和执行都核对精确目标版本及当前可用性。

## 记忆与知识

| 能力 | 归属与用法 |
| --- | --- |
| 会话记忆 | Runtime 保存同一会话上下文，按会话与身份隔离 |
| 个人长期记忆 | Control 维护 canonical 数据，Knowledge 提供可重建检索投影 |
| 知识库 | 通过已发布知识问答 Workflow 使用，检索授权与证据策略见[数据与模型](data.md) |
| 业务索引 | 只用于发现，消费内容前按当前身份回源鉴权 |

Agent 的记忆策略决定是否召回、提取及何时写入。用户可在嵌入式“我的记忆”查看、删除或处理候选；所有者取自已验证身份，不能从请求体接受。长期记忆、短期会话、大结果工件不能互相混用。

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

## 内置包与来源

内置包随 Control 发布，使用 `BuiltinAgentSkillSource` 和版本摘要清单维护同版本同字节。修改说明、参考文档、脚本或内嵌资源必须升版本并更新 [builtin-agent-skill-releases.json](../reachai-control-service/src/test/resources/builtin-agent-skill-releases.json)，不能覆盖已有版本或绑定。

外部 Skill 市场引入固定来源与不可变版本，仍走相同的范围、校验和审核链。标准 Skill 与业务能力、MCP Tool 的职责不合并。

运行记录沉淀由 [AgentSkillRunCaptureService](../reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillRunCaptureService.java)确定性生成草稿；只描述工具、字段与调用顺序，不带真实业务参数值。保存进入 REVIEW_PENDING，再经原审核发布流程。员工按项目权限下载已发布包，不自动发布或推送到其 AI 工具。
