# 开放协议

## MCP 开放

MCP 开放将已接纳的业务方法与已发布 Workflow 组成平台提供的 MCP 服务，供员工工具或授权客户端调用。“开放”不代表匿名公开，也不表示平台注册并调用外部 MCP 服务。

管理端提供 MCP 服务、员工连接、写操作审批和调用记录。Publication 发布产生不可变修订，工具列表是发布快照与调用方范围的交集；凭证轮换、吊销或暂停服务应立即影响新调用。

| 调用方 | 使用方式 |
| --- | --- |
| 应用 / 第三方客户端 | 使用绑定单个服务的客户端凭证，受工具 scope、期限和发布状态约束 |
| 企业员工 | 在业务系统面板选择系统、生成个人连接，复制平台生成的配置到自己的 AI 工具 |

公共协议采用 JSON-RPC 与 Streamable HTTP，实际版本协商、路径和开启状态以 [MCP 实现](../reachai-control-service/src/main/java/com/enterprise/ai/control/mcp/)及[公共路由](contracts/public-routes.md)为准。业务方法从 owner 派生协议定义；API 通过固定 API owner 的已发布 Workflow 暴露，不复制为通用 Tool 资产。

## 员工身份和写审批

员工连接绑定平台业务用户和允许系统；调用时重新确认用户状态、外部身份、业务角色与服务范围。管理员不能用配置中的显示名称替代身份，个人连接不开放不可逆能力。

1. 管理员在服务的“接入与使用 → 员工使用”中开通对应系统，并设置写审批策略。
2. 员工从业务系统 AI 面板生成连接，也可通过一次性入口打开“我的 AI 工具”管理页。
3. 需要审批的非只读调用冻结员工、连接、工具、发布修订与参数，返回审批单；重复提交不重复建单。
4. 审批人通过后重新校验全部执行条件，再以员工身份派发一次；并发通过不能重复执行，失效或漂移记为失败。
5. 员工通过 `reachai_approval_status` 或面板查询结果。断开、停用、过期或权限变化立即阻止后续调用。

审批不修改冻结参数；过期、驳回和执行失败分别记录。调用记录与 Run/Trace 关联员工、工具、系统和执行结果。个人连接密钥只在创建时显示，服务端保存摘要。

运行经验可按[Agent Skill 流程](agent.md)生成和审核，员工仅下载当前系统允许的已发布包。操作样例见[员工接入指南](guides/用Claude%20Code操作芋道.md)。

## 平台 AI 连接

平台用户可在管理端个人中心创建和断开自己的 AI 连接，供 Codex、Claude Code 等工具通过 `/mcp/platform` 操作其获授权的平台资源。连接属于真实平台账号，不与员工业务连接、项目 AI Coding Key 或浏览器会话互换。密钥仅在创建时显示，服务端只保存摘要；建议将密钥保存在 AI 工具运行环境中，配置只引用环境变量。

连接最多使用创建时账号拥有的角色和权限范围。每次调用都与账号当前授权取交集；停用账号、断开连接、连接过期或撤销账号全部会话会阻止后续调用。账号新增权限后须重新创建连接。项目范围与具体资源仍由原管理操作的服务端校验；平台工具调用只审计连接、账号、操作和结果状态，不记录参数或结果正文。写入前先记录带关联标识的执行意图，审计不可用时不派发写入。

当前工具覆盖平台身份、授权项目、业务方法与 HTTP API、业务索引、Agent、Workflow 和运行记录的读取，以及 Agent 身份创建、配置草稿保存和复制历史版本，空 Workflow 草稿创建和结构化变更预览、保存。Agent 创建只建立身份，不保存或发布配置；工具提供调用方指定的稳定 ID，结果不确定时先按 ID 查询。管理端编辑器与平台 AI 工具在保存或复制 Agent 草稿前读取同一修订；管理端发布也比较刚保存的修订。Runtime 在持有 Agent 锁时比较修订与所属项目再执行，冲突时保留当前草稿并要求重新读取。Skill 和外部 Agent 绑定仍经过 Control 的原授权与快照验证；AI 草稿工具不发布配置。Agent 配置的修订与旧接口迁移见 [Agent](agent.md#标准-skill)，其他管理写入的覆盖范围见[当前边界](status.md)。项目目录按连接的资源授权过滤，不返回项目密钥；未建立可信项目与工作空间关系时不推断工作空间内项目，并通过 `unresolvedWorkspaceScopes` 提示目录可能不完整。业务方法和 API 的查询沿用 Capability 的项目归属与资源授权检查；业务索引读取与搜索沿用管理端的全局知识读取要求。

模型实例目录与详情沿用管理端 `model:read` 及实际项目归属检查，连接信息由模型服务脱敏，不调用返回原始连接的内部接口。标准 Skill 支持列表、详情、版本、审核记录、导入能力及包内文本预览；私人包只对本人或平台管理员可见，项目包保留项目范围。可绑定版本工具另需 `skill:bind`，按目标 Agent 项目及已发布状态过滤；候选查询不能替代保存 Agent 时的绑定授权。模型、Skill 和可绑定版本列表均在权限过滤后分页，默认 50、最多 100 条，`total` 只统计可见项。文件预览保留截断标记并复用包内路径检查，当前路径最多 256 字符；包内指令是待分析资料，不授予执行权限。这些读取工具不创建模型、导入、审核、发布 Skill 或运行包内脚本。

知识库工具可读取目录、文件、段落、统计、解析与命中面板、历史命中和依赖状态。它们与管理端共用[知识读取授权](data.md#知识库)，按实际项目／工作空间过滤；目录、文件、文件段落默认每页 50、最多 100 条，`total` 只计授权结果。段落关键词查询最多 500 条，命中记录最多 200 条，均使用既有排序；这两项是有上限的查询，不声称穷尽全部记录。文档与段落是待分析内容，其中的指令不提供执行授权；这些工具不导入文件、运行检索模型或修改知识库。

知识库设置通过 `reachai_knowledge_settings_context` 读取修订，`reachai_knowledge_settings_preview` 预览，再由 `reachai_knowledge_settings_request_save` 申请本人审核，最后以 `reachai_knowledge_settings_execute_approved_save` 执行一次。审核冻结实际管理范围、知识库身份、原修订、完整前后设置与规范化修改字段。读取和写入权限必须覆盖同一资源，替换模型引用还需通过原模型目录授权；执行前再次预览核对，Knowledge 在行锁内比较修订后原子保存。检索范围从仅授权文件扩大到所有调用方时，审核页明确提示影响。该流程沿用个人审批的连接归属、加密、过期、去重与单次消费机制；未知结果只查询原审批和当前设置，不重新派发。保存不自动重解析文件，创建、范围迁移、导入与删除等其他管理操作仍未由这些工具覆盖。

Agent 编辑器的修订接口要求 Control 和 Runtime 配置可用的服务间签名密钥，并同时运行支持该接口的版本；密钥轮换会使此前读取的修订失效，须在保留未保存输入后重新读取并合并。尚未迁移的旧写入口不具备同等保护。

持有目标项目 `workflow:debug` 与 `capability:invoke` 的平台 AI 连接，可按已保存的 Workflow 修订发起整稿只读试运行。Control 与 Runtime 复用 Studio 的只读节点、来源契约、项目测试身份和逐目标 ACL 检查；模型或请求字段不提供身份、凭据或允许目标。`trialId` 是调用方提供的小写 UUID，服务端按连接和账号派生运行编号，并在节点执行前利用 `runtime_run.trace_id` 的唯一键认领。同一连接的同一编号最多派发一次；重试或连接中断后使用状态工具查询，不换编号盲目执行。状态读取只返回本连接的运行状态与编号，不替代 RunOps 的完整记录权限；它是持久化记录，不证明进程仍然存活。未查到运行记录可能表示原请求仍在准备，不能据此认定未执行。该模式要求支持去重命令的 Runtime；旧版本须在执行前拒绝，部署时需验证服务版本和运行记录唯一索引，并保留认领记录以维持去重。

具备调用权限和 ACL 的连接可试调用只读业务方法与 API。业务方法使用项目测试身份，HTTP API 使用配置的连接身份，均不冒充调用人的业务用户身份。业务方法和 HTTP API 写入可由 AI 申请个人审核：请求绑定原调用编号、项目、目标、副作用、完整输入及已接纳契约和连接修订；API 还绑定来源修订与所用凭据修订。连接所属用户在平台 AI 连接页面核对并批准或拒绝。输入以独立配置的密钥加密保存，审核有效期为 30 分钟；同一连接、操作类型和调用编号不会生成不同参数的审批。批准只允许原连接消费一次，派发前重新检查账号权限、ACL、来源与冻结修订。拒绝、过期和消费后清除加密输入；中断或结果不确定时按原 invocationId 查询，不盲目重发。部署配置与验收要求见[运维](operations.md)。

草稿变更必须携带读取到的修订，默认保留画布布局；保存通过 Runtime 的来源归属检查、GraphSpec mutation 与发布校验，不自动发布。创建响应不确定时先按唯一标识查找。其他管理写入与完整人机任务覆盖仍需继续接入与验证，见[当前边界](status.md)。

Agent 发布通过个人审核执行：连接须在同一目标项目同时具有 Agent 读取与发布权限，先读取草稿修订和配置版本，再申请审核。服务端冻结完整配置、可见范围与角色、所属项目、当前线上配置编号和本次将固定的 Workflow 发布版本，最多保存 512 KiB 的加密审核内容；人从“AI 操作审核”查看后批准一次发布。执行重新核对权限和来源绑定，并在 Agent 行锁内检查草稿修订、使用范围与线上配置，按审核中的 Workflow 版本固定白名单；版本发生变化则拒绝，不能静默改用最新版本。原连接只消费一次审批，拒绝、过期或消费后清除加密内容。创建响应丢失时按原 `invocationId` 查询本连接审批元数据；发布响应未确认时查询原审批及 Agent 配置版本，不新建请求重试。该入口要求支持受审核发布的 Runtime，旧服务缺少接口时不回退到普通发布。

Workflow 发布使用 `reachai_workflow_request_publish` 申请本人审核，再由原连接调用 `reachai_workflow_execute_approved_publish` 消费一次审批。同一项目的读取与发布权限均须有效。服务端冻结完整发布定义、固定后的 GraphSpec、发布参数和线上版本，在 Runtime 行锁内重新核对摘要；内容或来源变化须重新申请。审核内容加密保存，批准后派发前消费，响应未知不重新派发。可按原 `invocationId` 找回审批，再用 `reachai_workflow_versions` 核对原发布版本。审核页展示的是冻结内容，不能用页面临时修改替换批准的快照。

把已发布的页面助手同步到项目页面副驾驶时，先用 `reachai_workflow_page_assistant_context` 读取目标 Agent、修订、线上配置和是否已接入，再用 `reachai_workflow_page_assistant_request_sync` 申请本人审核：目标已存在时提交其 `baseRevision`，尚未创建时提交 `createTarget=true`，两者不能同时出现。审批冻结目标、修订和页面助手线上版本；原连接调用 `reachai_workflow_page_assistant_execute_approved_sync` 执行一次，执行前重新读取并比对，变化即拒绝。同步与 Workflow Studio 共用签名入口，需要同一项目的 Workflow 读写权限，生成新的线上配置并保留人工草稿。结果未知时查询原审批并重新读取 context，不新建申请重试。

只读治理工具复用控制台同一读取链路和权限：`reachai_workflows_search` 与 `reachai_workflow_agent_references` 需要 Workflow 读取权限；`reachai_workflow_validate_release` 只做发布前校验、不发布，需要 Workflow 写权限；`reachai_agent_statistics` 需要 Agent 读取权限；`reachai_eval_readiness`、`reachai_eval_datasets`、`reachai_eval_dataset_get`、`reachai_eval_experiments`、`reachai_eval_experiment_get` 需要 Agent 评测权限。评测数据集与实验的写入仍待接入。

Workflow 回滚到历史版本使用 `reachai_workflow_request_rollback` 申请本人审核，再由原连接调用 `reachai_workflow_execute_approved_rollback` 执行一次。需要同一项目的 Workflow 读取与发布权限，并提交读取到的工作副本修订。审批冻结目标版本号、发布时间、发布者、GraphSpec 摘要与当前线上版本；目标已是线上版本时拒绝。执行前重新读取并比对，变化即拒绝；结果未知时查询原审批与版本列表，不新建申请重试。

文档导入任务与知识库页面共用同一入口、资源授权和审计。`reachai_knowledge_import_job_get` 读取任务状态，可带切片预览；`reachai_knowledge_usage` 查看哪些 Workflow 引用了知识库。失败任务可用 `reachai_knowledge_import_job_retry` 直接重试，但须提交刚读到的状态（FAILED 或 RETRY_WAIT），状态已变化时返回 `KNOWLEDGE_IMPORT_JOB_STATE_CHANGED`，结果未知时重新读取任务而不是再次重试。正式入库和取消都走本人审核：`reachai_knowledge_import_job_request_commit` 冻结任务、文件和切片预览摘要，`reachai_knowledge_import_job_request_cancel` 冻结任务和文件（取消会丢弃原件）；原连接分别调用对应的 `execute_approved_*` 执行一次，执行前重新读取比对，变化即拒绝。上传新文件尚未开放给 AI。

Skill 版本治理与 Skill 页面共用同一控制器、行锁状态机和审计。`reachai_skill_version_request_review`（需要 Skill 评审权限）申请评审通过或驳回，`reachai_skill_version_request_lifecycle`（需要 Skill 发布权限）申请发布、设为默认、弃用或撤销；审批冻结包摘要、风险报告、版本状态和当前默认版本，原连接调用 `reachai_skill_version_execute_approved_change` 执行一次，执行前重新读取比对，变化时返回 `SKILL_VERSION_STATE_CHANGED`。Skill 市场可用 `reachai_skill_market_sources`、`_search`、`_imports` 只读查看，用 `reachai_skill_market_probe` 探查来源；`reachai_skill_market_import` 需要导入权限，钉住探查得到的提交和包摘要直接导入，导入结果为待评审版本，评审和发布仍走上面的本人审核；结果未知时查看导入记录，不直接再次导入。上传 Skill 包与从运行记录沉淀 Skill 尚未开放给 AI。

业务系统（扫描项目）只读工具与业务系统页面走同一控制台代理，并先用同一路径权限规则判断：`reachai_business_system_get` 读取配置与接入状态（按控制台规则去除已保存密钥），`reachai_business_system_operation_blockers`、`_tools`、`_tool_get`、`_sources`、`_sensitive_status` 分别读取操作阻塞项、扫描接口、来源与敏感数据扫描进度。工具调用权限规则可用 `reachai_tool_acl_list`、`_roles`、`_explain` 只读查看和判定；`reachai_tool_acl_request_change` 申请本人审核一次新建、修改、启停或删除，审批冻结现有规则（含更新时间）和要写入的完整规则，原连接调用 `reachai_tool_acl_execute_approved_change` 执行一次，规则在此期间被改动时返回 `TOOL_ACL_CHANGED`；批量授权尚未开放给 AI。重新扫描、对齐工具、SDK 接入检查、SDK 扫描、单个接口按来源重扫与敏感数据扫描可由有平台写权限的 AI 直接调用（`reachai_business_system_rescan`、`_reconcile_tools`、`_sdk_access_check`、`_sdk_sync_scan`、`_tool_rescan_from_source`、`_sensitive_scan`），沿用同一控制台代理和路径权限；被引用阻塞、扫描结果过期或已有扫描在运行时只返回 `BUSINESS_SYSTEM_OPERATION_BLOCKED`，结果未确认时返回 UNKNOWN，要求重新读取而不是再次执行。业务系统的新建、基本信息修改、扫描设置修改与删除经 `reachai_business_system_request_change` 申请本人审核（动作 `BUSINESS_SYSTEM_CHANGE`），冻结当前配置与要写入的内容，修改时未传的字段保持原值；注册凭据替换经 `reachai_business_system_request_credential` 申请，审批只保存 AppKey 与 AppSecret 的加盐 SHA-256 指纹，`reachai_business_system_execute_approved_change` 执行时须由同一连接再次提供同一个 AppSecret，指纹不符即作废。业务系统在审核期间被改动时返回 `BUSINESS_SYSTEM_CHANGED`；OpenAPI 文档上传尚未开放给 AI。

知识切片：`reachai_knowledge_chunk_request_change` 申请本人审核一次切片改写（内容与标题）或启停（动作 `KNOWLEDGE_CHUNK_CHANGE`），审批冻结切片所属文件、知识库范围、原文与新内容；`reachai_knowledge_chunk_execute_approved_change` 执行前重新读取，切片被改动时返回 `KNOWLEDGE_CHUNK_CHANGED`。`reachai_knowledge_chunk_reembed` 直接重新向量化。三者都先读取知识服务签名的资源范围，要求调用者对该知识库有写权限；知识服务的切片接口本身不按资源校验，因此控制台入口也要求带 `fileId`，由 Control 按同一规则核对后再转发。

文件传输：文件超出 MCP 请求体上限，平台 AI 先用本连接令牌向 `POST /mcp/platform/uploads?fileName=…` 发送原始文件（最大 20MB），得到 `uploadId`。上传只属于该连接，一小时后过期，每个连接最多 5 个待用上传，保存在 Control 本机临时目录（重启即失效，不跨实例共享）。`reachai_knowledge_import_job_submit` 把上传提交为知识库导入任务（与页面上传同一范围校验和审计，任务停在待入库），`reachai_skill_package_discover` 只读检查 Skill ZIP，`reachai_skill_package_import` 导入为待评审版本。写入工具在调用前就删除上传，结果未确认时返回 UNKNOWN，须先核对目标再重新上传，不能用同一上传重放。运行记录沉淀 Skill：`reachai_skill_capture_preview` 与运行中心使用同一处理器生成草稿（只读），`reachai_skill_capture_create` 把（可修改的）SKILL.md 保存为待评审版本，结果未确认时返回 UNKNOWN。

模型实例：`reachai_model_templates_list`、`reachai_model_template_get` 只读查看提供商模板；`reachai_model_test` 对已保存实例做一次连接测试，需要实例所属项目的模型管理权限（无项目即平台级），只记录测试结果，结果未确认时返回 UNKNOWN。新建、修改和归档经 `reachai_model_request_change` 申请本人审核（动作 `MODEL_INSTANCE_CHANGE`）：审批冻结脱敏后的当前实例和要写入的字段，修改时未传的字段保持原值且不能换项目；`connection` 中名称含 key、secret、token、password 的新值只保存加盐 SHA-256 指纹，读回的 `******` 表示沿用原密钥。`reachai_model_execute_approved_change` 执行时须在 `secrets` 中按同样字段名再次提供这些新密钥，指纹不符或多给都拒绝并作废审批；实例在此期间被改动时返回 `MODEL_INSTANCE_CHANGED`，返回结果再次脱敏。按模板新建尚未开放给 AI（可先读模板再用 CREATE）。提供商账号是平台级的，地址和密钥对引用它的所有项目的模型生效：`reachai_model_provider_accounts_list`、`_get` 按模型读取权限只读查看（脱敏）；`reachai_model_provider_account_models`（用已保存密钥列出提供商模型）、账号修改、模型目录设置与同步都要求平台级模型管理权限，项目级管理员看不到这些工具。`reachai_model_provider_discover_draft` 不保存，用给定地址和密钥试取模型列表。账号新建、修改、删除和批量接入模型（最多 20 个）经 `reachai_model_provider_account_request_change` 申请本人审核（动作 `MODEL_PROVIDER_ACCOUNT_CHANGE`），密钥同样只存指纹、执行时再次提供；仍有模型引用的账号在申请时就返回 `MODEL_PROVIDER_ACCOUNT_IN_USE`，账号在审核期间被改动（包括引用数变化）时返回 `MODEL_PROVIDER_ACCOUNT_CHANGED`。`reachai_model_catalog_status` 只读，`reachai_model_catalog_settings_update` 直接写入完整新值，`reachai_model_catalog_sync` 结果未确认时返回 UNKNOWN，须先查看状态。

MCP 发布：平台 AI 通过同一组控制台控制器、MCP Hub 权限和审计管理 MCP 发布。`reachai_mcp_publications_list`、`_get`、`_revisions`、`_resolve_preview`、`_precheck`、`_clients`（只显示密钥前缀）和 `_employee_access` 只读。新建、改名、添加和移除条目只改草稿，外部客户端看到的工具要等发布后才变化，所以直接执行；名称重复返回 `MCP_PUBLICATION_NAME_CONFLICT`。发布、暂停、恢复、归档、回滚到已有修订、修改员工接入、修改或吊销客户端经 `reachai_mcp_publication_request_change` 申请本人审核（动作 `MCP_PUBLICATION_CHANGE`）：审批冻结发布状态、条目（含更新时间）、修订，以及发布时的检查结果、客户端（不含最近使用时间）或员工接入设置；带不可逆工具的发布须 `irreversibleAcknowledged` 且持有不可逆发布权限，改客户端角色另需角色管理权限。`reachai_mcp_publication_execute_approved_change` 执行前重新读取比对，变化时返回 `MCP_PUBLICATION_CHANGED`，领域拒绝返回对应的稳定代码（如 `MCP_PUBLICATION_TRANSITION_INVALID`），意外失败返回 UNKNOWN。新建客户端和轮换密钥会生成新的 API Key，只在控制台向本人显示，不开放给 AI。

自动化：`reachai_automations_list`、`reachai_automation_readiness`、`_get`、`_occurrences` 经同一控制器和项目授权只读。自动化会以创建者身份无人值守地运行目标，所以新建、修改、恢复和归档经 `reachai_automation_request_change` 申请本人审核（动作 `AUTOMATION_CHANGE`）：审批冻结自动化配置、修订和当前版本（不含随调度变化的触发时间），执行时再读取比对，并按审核时的修订提交，Runtime 发现修订已变时返回 `AUTOMATION_REVISION_CONFLICT`。暂停（须提交当前修订）、立即运行、重试和取消某次运行与页面按钮一样直接执行；立即运行和重试的结果未确认时返回 UNKNOWN，须先查看运行记录，不直接再次执行。

运行中心与评测：`reachai_run_diagnostics`、`reachai_run_compare` 经同一 Runtime 控制器按项目授权只读。评测在 Runtime 的只读评测上下文中执行，可执行副作用都被拦截，所以 `reachai_eval_dataset_create`、`_dataset_version_create`、`_dataset_version_from_trace`、`_experiment_create` 与控制台一样直接执行（结果未确认时返回 UNKNOWN，须先核对列表），`_experiment_cancel` 可安全重复，`_experiment_items` 只读。运行重放会以调用者身份用新输入再次执行原运行的已发布 Agent 版本，工具可能产生真实副作用，因此经 `reachai_run_request_replay` 申请本人审核（动作 `RUN_REPLAY`），冻结原运行的 Agent、版本、项目和状态以及重放输入；`reachai_run_execute_approved_replay` 执行前重新读取，变化时返回 `RUN_REPLAY_SOURCE_CHANGED`，结果未确认时返回 UNKNOWN。调试会话和从运行生成 Workflow 候选尚未开放给 AI。

账号与角色：`reachai_accounts_list`、`reachai_roles_list`、`reachai_permissions_list`、`reachai_account_audit_events` 经同一账号控制器只读，只对持有平台级 `platform:admin` 的连接可见。修改账号资料或状态、强制下线、替换角色授权、新建或修改自定义角色经 `reachai_account_request_change` 申请本人审核（动作 `ACCOUNT_CHANGE`），冻结目标账号（不含最近登录时间和活跃会话数）或角色（不含授权人数），授权和权限附带角色名与权限编码供核对；目标是连接所属本人、或要改的角色由本人持有时直接拒绝，返回 `PLATFORM_ACCOUNT_SELF_CHANGE_FORBIDDEN`。`reachai_account_execute_approved_change` 执行前重新读取，变化时返回 `PLATFORM_ACCOUNT_CHANGED`，结果未确认时返回 UNKNOWN。新建账号和重置密码只能由人在控制台操作，因为两者都会让 AI 持有可登录的密码。

业务索引：`reachai_business_index_create`（新建空索引）与 `reachai_business_index_rebuild` 和控制台一样直接执行，要求全局 `knowledge:write`，结果未确认时返回 UNKNOWN。修改与删除会改变检索它的 Agent 看到的内容或移除全部记录，经 `reachai_business_index_request_change` 申请本人审核（动作 `BUSINESS_INDEX_CHANGE`），冻结索引定义（不含记录数等随推送变化的统计）；`reachai_business_index_execute_approved_change` 执行前重新读取，变化时返回 `BUSINESS_INDEX_CHANGED`。记录的写入与删除仍由业务系统经项目 Key 推送，不开放给平台 AI。调用权限批量授权经 `reachai_tool_acl_request_batch_grant` 申请本人审核（动作 `TOOL_ACL_CHANGE`，操作 `BATCH_GRANT`，最多 100 个目标），冻结会被覆盖的同角色同范围规则，执行前重新读取，变化时返回 `TOOL_ACL_CHANGED`，并核对写入条数。

A2A Hub 只读：`reachai_a2a_overview` 与发布、远程 Agent、任务、外部主体、信任配置、凭据的列表和详情工具经同一 A2A 管理控制器读取，要求 `a2a-hub:read`；凭据只返回元数据和指纹。`reachai_a2a_task_payload` 读取任务消息或产物载荷，另需 `a2a-hub:payload:read`，与控制台一样记入审计。A2A 的写操作仍按产品范围冻结，未开放给平台 AI。

运营视图：`reachai_mcp_overview`、`reachai_mcp_usage`、`reachai_mcp_call_logs`（不含正文）、`reachai_mcp_call_log_get`（正文只给 `mcp-hub:payload:read`）、`reachai_mcp_employee_connections`、`reachai_mcp_employee_write_approvals` 与 `_write_approval_get` 经同一 MCP Hub 控制器只读；`reachai_run_conversations` 与 `reachai_run_conversation_get` 经运行中心对话控制器按项目校验 `runops:read`。`reachai_mcp_employee_connection_revoke` 与控制台的「断开」一样直接执行并记审计（只会收回访问）。员工写操作的批准与拒绝是人的把关，不开放给 AI。

模型草稿与 Agent 启停：`reachai_model_test_draft` 用 AI 提供的连接配置测试未保存的模型草稿或发现供应商模型，不保存内容，草稿不能带已保存实例的 `id`（否则会沿用其密钥发往草稿地址），按草稿的 `projectCode`（未给时按平台级）要求模型管理权限；控制台的 `test-draft` 与 `discover-models` 带 `id` 时，也要求该实例所在项目的模型管理权限。Agent 启用、停用与删除经 `reachai_agent_request_lifecycle_change` 申请本人审核（动作 `AGENT_LIFECYCLE_CHANGE`），冻结 Agent 的名称、项目、可见范围、角色、启用状态与线上配置版本，执行前重新读取，变化时返回 `AGENT_LIFECYCLE_CHANGED`；Agent 配置修改仍走草稿与发布。

HTTP API 连接：连接决定调用发往哪个地址、携带哪个已保存凭据，改地址可能把凭据交给别的主机，因此保存 API 自己的连接、保存项目默认连接与移除覆盖都经 `reachai_http_api_connection_request_change` 申请本人审核（动作 `HTTP_API_CONNECTION_CHANGE`），冻结有效连接、覆盖与项目默认的地址、认证方式、凭据引用和修订（不含健康状态与预览）；`reachai_http_api_connection_execute_approved_change` 执行前重新读取，变化时返回 `HTTP_API_CONNECTION_CHANGED`，并按审核时的修订提交，由 Runtime 拒绝过期修订。凭据本身只在控制台录入。

身份映射：`reachai_runtime_user_mappings`（全局 `context:runtime-user:mapping:manage`）、`reachai_business_users` 与 `reachai_business_user_identities`（全局 `identity:business-user:read`）经身份页同一控制器只读。新建或删除运行时用户映射、保存业务用户在某个业务系统里的账号身份，都经 `reachai_identity_mapping_request_change` 申请本人审核（动作 `IDENTITY_MAPPING_CHANGE`）：新建冻结该平台账号现有生效映射，删除只针对生效中的映射，保存身份冻结该业务用户现有账号身份（不含最近出现时间）；执行前重新读取并比对，被改动时返回 `IDENTITY_MAPPING_CHANGED`，结果未知时返回 UNKNOWN 而不重发。AI 不能给连接所属本人的平台账号建映射（`IDENTITY_MAPPING_SELF_CHANGE_FORBIDDEN`），以免由 AI 决定本人以哪个业务身份办事。业务用户资料修改与工作流凭据的录入、修改、删除尚未开放。

知识库新建、删除与检索测试：`reachai_knowledge_base_create` 只能建在调用者有知识写权限的范围内（不填时为默认工作空间的 WORKSPACE 范围），结果未知时返回 UNKNOWN 并提示先按编码查找；`reachai_knowledge_retrieval_test` 要求每个知识库的读取权限，最多 20 个，默认不记入命中统计。删除知识库（连同全部文件）或其中一个文件经 `reachai_knowledge_request_delete` 申请本人审核（动作 `KNOWLEDGE_DELETE`），冻结知识库的编码、名称与范围以及文件清单（不含解析进度和切片数），执行前重新读取比对，变化时返回 `KNOWLEDGE_DELETE_TARGET_CHANGED`，结果未知时返回 UNKNOWN 而不重发。控制台转发这些知识服务端点前也按所指的知识库或文件校验知识权限（知识服务本身不按资源授权），控制台不使用的知识服务写端点一律拒绝。

只读补充：`reachai_tools_list`（能力目录）、`reachai_trace_get`（原始轨迹）、`reachai_runtime_human_approvals`（运行中等待人工确认的步骤，确认仍在调试页由人操作）、`reachai_http_api_references` 与 `reachai_capability_references`（引用影响面）、`reachai_capability_changes`（能力变更列表）经控制台同一控制器授权。能力变更的采纳、忽略与回滚经 `reachai_capability_change_request_review` 申请本人审核（动作 `CAPABILITY_CHANGE_REVIEW`），冻结变更内容、字段差异与引用影响面；执行前重新读取，变更状态或影响面变化时返回 `CAPABILITY_CHANGE_STATE_CHANGED`，结果未知时返回 UNKNOWN 而不重发。

「我的」页面：`reachai_my_memory_*` 读写连接所属本人的个人记忆（与「我的记忆」页面同一归属，经运行时用户映射解析），AI 保存的记忆标记为 `AI_TOOL` 来源，以 invocationId 作为幂等键；全部清除只由人在页面上操作。`reachai_my_ai_connections`、`reachai_my_ai_connection_revoke` 与 `reachai_my_ai_calls` 查看和吊销本人的平台 AI 连接、查看调用记录；新建连接只由人操作（令牌只展示给人一次），AI 也不能批准或拒绝自己的申请。AI 可用的工具清单即 MCP 的 `tools/list`，与「我的 AI 工具」页面列出的能力同源。

嵌入式对话：`reachai_embed_*` 只读工具查看会话（不含对话内容）、页面动作记录、登记页面与动作目录、就绪检查、接入策略和调试结果；嵌入控制器不按项目授权，因此 AI 需要平台级 `embed:read`。修改接入策略（未传字段沿用当前值，因为策略整体替换）与删除登记页面（连同会话和对话记录）经 `reachai_embed_request_change` 申请本人审核（动作 `EMBED_CHANGE`，需要平台级 `embed:manage`），变化时返回 `EMBED_TARGET_CHANGED`，结果未知时返回 UNKNOWN 而不重发。对话内容读取与页面动作调试尚未开放。

API 市场：`reachai_api_market_entries`、`_entry`、`_overview`（kind=STATS/CATEGORIES/SOURCES）、`_integrations`、`_integration_get` 只读，权限与控制台相同。`reachai_api_market_select` 直接执行，只把条目登记为项目里“配置中”的接入，让接入就绪仍需人补齐并验证凭证；`reachai_api_market_integration_disable` 只能把接入标为 BROKEN 或 DISABLED。两者结果丢失时返回 UNKNOWN，要求先读回再决定，平台不会重发。

业务能力与 API 关系图：读取工具（`reachai_business_capability_*` 读、`reachai_api_graph_read`）走控制台同一个按项目授权的目录入口。分组新建/重命名、分类整体替换（每个能力带读取到的修订号）、语义文档改写（带 `expectedUpdatedAt`）、语义生成（固定 `force=false`，只填补空文档）、关系推断与同步、候选确认/拒绝（确认人记为当前账号）、手工关系和布局保存直接执行，结果丢失返回 UNKNOWN 且不重发。`reachai_api_graph_request_change` 的 REGENERATE 会清空全部关系、节点和布局，DELETE_EDGE 会永久删除一条关系，两者都先申请本人审核（`API_GRAPH_CHANGE`），冻结关系图规模、各状态数量、人工关系清单和全部关系的摘要，或被删关系本身；执行前重新读取，变化则拒绝。

页面工作台与注册实例：`reachai_page_workbench_read` 读取接入总览、页面、就绪度、页面地图、分析发现和已交付 Workflow；`reachai_registry_instances` 列出 SDK 注册实例。适配层先按控制台同一条项目路由规则校验项目权限再调用。手工登记页面、标记分析发现（审核人记为当前账号）、修改实例状态和清理离线实例直接执行，结果丢失返回 UNKNOWN 且不重发。把页面 Workflow 工程任务交付上线依赖 AI 编码控制台任务，尚未开放。

Workflow 凭据：`reachai_workflow_credentials` 只返回打码预览。`reachai_workflow_credential_create` 直接执行，新凭据在被连接或 Workflow 引用前不生效，引用标识由库表唯一约束保护。修改（名称、类型、范围、项目、更换密钥）和停用经 `reachai_workflow_credential_request_change` 申请本人审核（`WORKFLOW_CREDENTIAL_CHANGE`），审核单冻结当前凭据元数据与打码预览，新密钥只保存加盐指纹；执行时同一连接须再次提供同一密钥，凭据期间变化则拒绝。修改和停用沿用控制台规则，需要全局凭据管理权限。`reachai_workflow_runtime_validate` 只校验不保存。Studio 调试运行对 AI 以只读试运行代替，单节点调试与调试会话尚未开放。

调试会话与其他：`reachai_workflow_debug_session_get` 读取 Studio 调试会话，`reachai_workflow_debug_session_cancel` 直接取消。新建调试会话和回答其中的交互仍由人操作：调试会话运行未发布的图且不受只读限制，交互步骤就是图里的人工确认；AI 用只读试运行测试草稿。`reachai_http_api_accept` 按读取到的来源修订直接接纳观察到的 HTTP API；`reachai_knowledge_import_file_reparse` 重新解析未提交导入的一个文件，写入知识库仍需提交审核。

平台状态：`reachai_platform_services_health` 对已登录账号开放，`reachai_platform_system_check` 与 `reachai_platform_auth_providers` 仅管理员可用，适配层先按控制台路由规则校验。新增或修改登录方式会改变谁能登录平台，仍由管理员在控制台操作。

AI 编码控制台：控制台任务接口原先只按路由规则校验“某处有权限”，现按每个任务所属项目校验读写权限，列表只返回可读项目的任务，凭据策略按路径项目校验；创建人、操作人、回答人、发放人和验收人一律取当前账号，不再读取请求体。`reachai_ai_coding_tasks`、`reachai_ai_coding_task_get` 读取任务、托管执行、产物列表和验收就绪；新建任务、取消任务、回答问题直接执行，结果丢失返回 UNKNOWN。发放接入包（给外部编码智能体的任务凭据）、启动托管执行、处理托管执行中的审批、验收结果和修改凭据策略仍由人操作：它们发放凭据、启动代码执行或签收 AI 的工作。

合规删除：`reachai_memory_erasure_get` 读取删除请求，`reachai_memory_erasure_retry` 直接重新处理未完成的数据域，两者都要全局合规删除权限。新建删除请求会永久删除一个运行时用户在所有数据域的记忆，走 `MEMORY_ERASURE_REQUEST` 本人审核，冻结租户、运行时用户、原因、参考编号和请求键；结果未知时用同一请求键重新申请，服务端返回已有请求而不会重复删除。各数据域的人工删除证据是合规签署，仍由合规管理员在控制台提交。

Agent 与页面 Workflow 发布：`reachai_agent_knowledge_qa_prepare` 只创建知识问答 Workflow、不挂 Agent，直接执行。把 Agent 线上配置更新到最新 Workflow 版本，或创建知识问答 Workflow 并挂到 Agent 发布，都走 `AGENT_WORKFLOW_PUBLISH` 本人审核，冻结 Agent 线上配置版本和各 Workflow 的当前与最新版本，期间任何变化都拒绝执行。页面 Workflow 交付走 `PAGE_WORKFLOW_DELIVERY` 本人审核，冻结交付请求和所用的已应用工程结果；控制台交付接口原先只要求项目写权限，现与 Workflow、Agent 发布页一致，要求项目的 Workflow 发布权限，挂 Agent 时还要 Agent 发布权限，发布人记为登录账号。

运行沉淀为 Workflow 候选：`reachai_run_workflow_candidate_eligibility` 检查一次运行能否候选化，`reachai_run_workflow_candidate_create` 直接创建或复用候选编码任务，产出只是草稿 Workflow。控制台这两个接口原先只按路由规则校验运行中心读权限，可读取任意项目运行的候选依据并在任意项目建任务，创建人也由请求体指定；现按运行所属项目校验运行中心读权限，创建还要该项目的平台或 Workflow 写权限，创建人记为登录账号。

未开放的接口：`scripts/report-platform-ai-coverage.mjs` 的 `UNOPENED` 为每个没有平台 AI 工具的前端请求记录类别和原因：A2A 冻结、只展示一次给人的密钥或令牌、人的批准与签署、仅限人操作、人的登录会话、已由其他工具覆盖、尚未开放。报告列出未分类和过期的条目，测试要求两者为空。

## A2A

A2A 连接已发布的本地 Agent 与受信任远端 Agent，Control 管理 Publication、Revision、Principal、Trust、Credential、Task / Event / Artifact，Runtime 执行固定的 Agent 配置与真实取消。

管理端保留一个“A2A 互联中心”入口，主要导航为“总览 / 对外发布 / 外部 Agent / 协作任务”；“信任与策略”和“开发与诊断”从“管理工具”进入。

“信任与策略”按信任策略、调用身份和凭据分组，列表保留名称、状态与主要操作，授权范围、指纹等信息在详情中查看，停用、隔离、归档和凭据轮换等操作从“更多”进入。“开发与诊断”优先展示当前已发布修订的接入地址，调用示例、协议支持与验证、Agent Card 快照默认折叠；不使用历史或草稿修订替代缺失的当前修订。

| 主线 | 方向与操作边界 |
| --- | --- |
| 对外发布 | 外部 Agent → 本平台 Agent。选择已有 Agent 的已发布配置版本，配置开放契约和访问权限，检查并确认发布；这是已有 Agent 的开放渠道，不创建另一份 Agent |
| 外部 Agent | 本平台 Agent → 外部 Agent。通过 Agent Card 接入、验证并审核固定版本，再由本地 Agent 的协作配置选择绑定与允许委派的任务；接入目录不等于自动允许所有 Agent 调用 |

总览只保留两条主线的简短数量、操作入口、实际待处理事项和最近任务，不常驻展示运行统计、流程示意或使用说明。列表合并筛选与操作，单页数据不显示分页；任务标识缩略显示，完整标识、调用身份与运行信息从详情查看。使用说明从管理工具按需打开。

协作任务统一展示对外服务（`INBOUND`）和外部协作（`OUTBOUND`），方向与状态参数用于筛选，主导航可恢复全部方向；身份、凭据、信任、任务追踪和诊断共用。互联状态与最近任务独立处理失败，失败不显示为零资产或无风险。发布和接入按钮分别受既有管理权限控制，任务详情深链及协议/API 技术标识保持原契约。

Agent Card 使用 Host-based `/.well-known/agent-card.json`，协议使用 `/a2a/v1/**`、`A2A-Version: 1.0` 和 JSON。入站身份来自协议认证；metadata 不授予租户、用户、角色或 scope。出站委派固定远端修订与信任策略，不能把远端响应或凭证带入无约束的公共上下文。

正文按策略加密，传输审计只保留安全摘要；Task 与 outbox 持久化，避免完成或取消事件丢失。新方向与生产开放受[当前边界](status.md)及[运维门禁](operations.md)约束。

## API 市场

API 市场属于 Capability，维护外部目录、固定版本、Operation 和项目接入意图。项目内使用仍进入正式 HTTP API owner、Runtime 生效连接、受控验证与 Workflow 发布链。

收录、已接入、配置完成和验证成功是不同状态。Operation 或来源变化不能静默恢复旧引用，市场内容重新出现也不自动恢复已失效的选择。浏览器从市场选 API，不直接取得任意 URL 调用权。
