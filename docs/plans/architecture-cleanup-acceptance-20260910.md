# 架构整理验收对照（2026-09-10）

2026-09-12 本轮收口：用户明确要求尽快完成并提交 Git，采用主体整理、必要回归和开发环境可用的验收标准。下文尚未验证的扩展项转为后续清单，不再阻塞本轮提交；历史结果和未通过记录仍保留。最新多选发布契约修复已部署，Runtime 1665 通过、1 跳过，候选实际发布通过，后续模型执行超时，未证明真实多选续跑。以 [本轮交付说明](architecture-cleanup-closeout-20260912.md) 为当前状态入口。

2026-09-12 续跑计数补充：已修复部分完成消息把已有计划／Workflow／Token 统计清零，以及下一个 Workflow 重新获得调用额度的问题。Runtime 全量 1663 项中 1662 通过、1 跳过，新包已部署。独立候选环境的真实 Agent、两次输入、换版和 Runtime 重启复验通过：旧发布版本保持，计划／Workflow／调用计数各为 1，8 条 Span 闭合，三次重放无额外执行，冲突返回 409，49 张原表保护核对通过。第一轮模型等待超时和清理证据保留，第二轮未调整模型、期限或断言。非零 Token 保留有持久化测试，本轮真实 Token 记录为 0；额外模型轮次累计用量仍需核对。见 [本批修复与验证边界](../../output/tasks/architecture-audit-20260905/workflow-resume-metrics-notes.md)。

2026-09-12 RunOps 补充：已修复暂停调用审计导致成功续跑被误报为“异常完成”，保留原审计并用唯一交互／版本／父 Span 推导当前状态。新包部署后，同一条真实记录的完成提示、调用标签、准确跳转和键盘进入调用视图通过浏览器复验。Runtime 全量 1655 项中 1654 通过、1 跳过，前端 15 项通过，六服务健康。多选数组校验修复已一并部署，但真实多选／Embed 验收仍待完成；另发现续跑 Workflow 计数为 0 和放大布局待核对。见 [本批修复与边界](../../output/tasks/architecture-audit-20260905/runops-resumed-tool-notes.md)。

2026-09-12 交互追踪补充：独立候选环境的真实 Agent／两次输入／固定版本／Runtime 重启验收发现 Run 已完成但 Workflow Span 仍等待，已将追踪更新纳入交互完成事务并部署。91 项相关测试和 Runtime 全量通过；新包复验中 8 条 Span 闭合、重复提交无额外执行、冲突返回 409，49 张原表保护核对通过。RunOps 已验证完成态公开查询，浏览器、等待界面、其他变体及跨主机仍待验收，正常发布门槛保持 PRESENT_OUTPUT。见 [交互追踪修复](../../output/tasks/architecture-audit-20260905/workflow-interaction-trace-recovery-notes.md)。

2026-09-12 交互续跑补充：已复现请求 context 覆盖通过摘要校验的检查点状态，并移除这处覆盖。88 项相关测试与 Runtime 全量测试通过，新包已恢复运行、六服务健康；公开阻塞交互门槛未改，完整 Agent／Embed／RunOps 恢复继续待验收。见 [检查点状态完整性修复](../../output/tasks/architecture-audit-20260905/workflow-resume-context-notes.md)。

2026-09-12 最新索引验收：完整 Knowledge 进程在真实向量写入完成、尚未确认和发布时退出；租约自然到期后旧向量被回收，公开重试复用保存的解析结果发布新一代向量，经过旧执行的 300 秒再次回收仍保持完整。16 张原表指纹和原索引业务字段、本地文件均已核对，独立正常服务恢复及六服务健康通过。工作租约在演练中设为 120 秒，正常配置恢复为 1200 秒，边界见 [索引中断验收](../../output/tasks/architecture-audit-20260905/knowledge-index-crash-notes.md)。

2026-09-12 最新验收：第三轮完整 Knowledge 分片上传中断通过。8 MiB 上传完成第一片 5 MiB，第二片只送达 64 KiB 后终止上传进程；未接纳导入任务，另一进程在默认 1200 秒期限后回收源分片，相邻上传、分片和完整对象不变。17 张原表与本地文件保持不变，测试集合、桶和专用临时目录已清理。正常开发服务在本次续接时恢复，六服务健康及启动器退出后存活已验证；这不代表演练脚本自动恢复了正常服务。见 [完整分片中断验收](../../output/tasks/architecture-audit-20260905/knowledge-s3-partial-notes.md)。

2026-09-12 分片中断演练补充：两轮未通过的全应用尝试均已清理，正常服务恢复。独立真实 MinIO 用例暴露回收清单中空 StorageClass 导致 SDK 解析失败，已补回归并修复；39 项定向测试、407 项 Knowledge 全量测试及实际后端精确分片清理通过。新包已部署，完整分片中断在第三轮通过，见最新记录；此前 S3 全应用证据保留为修复前版本的验收，不能直接算作新解析逻辑的完整恢复验证。见 [清单响应修复](../../output/tasks/architecture-audit-20260905/knowledge-multipart-metadata-notes.md)。

2026-09-12 最新补充：S3 已完成远端 PUT、Java 尚未收到确认时的实际进程中断通过验收。另一完整进程接管后发布新对象，旧对象在未修改的 1200 秒期限后被回收，新结果仍可读；未确认记录按设计保留为 RECLAIMING。测试资源已清理，17 张表原有记录和本地文件不变，正常 Knowledge 在独立启动器退出后仍健康，见 [写入中断与回收](../../output/tasks/architecture-audit-20260905/knowledge-s3-write-notes.md)。这不代表部分正文／multipart 传输或索引执行中断均已通过。

同日能力目录补齐 [15 项 MySQL 来源、并发及回滚复验](../../output/tasks/architecture-audit-20260905/registry-catalog-mysql-notes.md) 和 [3 项真实插入失败原子性复验](../../output/tasks/architecture-audit-20260905/registry-catalog-fault-mysql-notes.md)。成功及失败批次共 200 张临时表全部移除，10 张原表逐组行数和指纹一致，328 个编译类与 Capability 运行包一致。初始 JSON 夹具错误和触发器权限拒绝均保留；替代故障只作用于独立临时表，没有调整全局权限。普通测试数量、生产代码和升级 SQL 未变。

2026-09-12 S3 双进程验收补充：真实 Java 文本解析读取被中断时，另一个已运行的完整 Knowledge 进程在租约自然到期后以第二次尝试完成同一任务。公开预览、取消后的版本化对象回收、相邻对象保留均通过；17 张表原有记录及本地文件未变，Milvus 测试集合和临时桶已清理，正常服务独立恢复后六服务健康。第三轮接管曾遇到 SocketException，第三次尝试成功但未满足验收断言；第四轮关闭验收代理的空闲连接复用并记录全部传输事件，仍保留两次尝试断言。连接中断的具体来源未确证，失败证据保留，见 [共享 S3 接管记录](../../output/tasks/architecture-audit-20260905/knowledge-s3-cluster-notes.md)。本批没有生产代码或 SQL 变更。

2026-09-12 实测补充：Workflow 内部阻塞交互的公开发布被现有门槛拒绝，尚未开始恢复验收；失败夹具已清理，见 [发布门槛核对](../../output/tasks/architecture-audit-20260905/workflow-interaction-release-gate-notes.md)。Knowledge 首次上传暴露的验签后 multipart `Stream closed` 已经真实 Tomcat／MVC 复现、修复并部署；完整测试 402 项通过。第二轮真实上传、文件锁造成的删除失败及完整 Knowledge 重启后的持久化回收通过，17 张表原有记录和原存储文件未变，Milvus 测试集合已删除，六服务健康，见 [本地文件回收恢复验收](../../output/tasks/architecture-audit-20260905/knowledge-storage-recovery-notes.md)。本批仍不代表 S3、解析／索引执行中崩溃或多进程恢复已经通过。

2026-09-11 Studio 首次执行／提交续跑中断均已验收：实际 Runtime 在距期限约 43 秒时终止，两笔只读调用在进程退出后完成；恢复后会话 EXPIRED／UNKNOWN，Run 与 Trace 原子收尾，四次回放零新增调用，47 张表原有记录不变。Runtime 1619 通过、1 跳过；新包与默认配置、六服务健康已核对。见 [执行中断验收](../../output/tasks/architecture-audit-20260905/studio-active-crash-notes.md)。

状态：进行中。更新时间：2026-09-12。本地两个实际 Runtime 的 Workflow 和真实 AgentScope Automation 故障接管已通过；人工确认续跑中进程退出也已通过；Studio 暂停后草稿快照重启与提交回放、执行中断已通过；Knowledge 本地删除失败后重启回收、S3 解析中断接管、远端写入与分片中断回收、索引执行中断和重试已通过。候选 Workflow 两次输入跨重启及续跑计数已通过；真实多选和其他交互变体、Embed、等待态与缩放浏览器检查、跨主机恢复和其余部署验收仍待完成。

2026-09-11 已补齐 Supervisor 确认续跑期限：原三项缺口已复现，最终 Runtime 1602 项中 1601 通过、1 跳过；超时保存 UNKNOWN 并原子收尾交互、事件、Run／Trace，拒绝重复授权及迟到成功。第 22 份开发库增量两遍验证通过，新包和六服务健康已核对。该批之后的真实确认续跑中断验收现已通过；期限修复见 [本批记录](../../output/tasks/architecture-audit-20260905/supervisor-resume-deadline-notes.md)。

2026-09-11 用户确认续跑中实际 Runtime 进程退出已通过：SDK 调用已进入、Workflow 层超时而 Supervisor 仍在续跑时中断执行者；恢复后保存 UNKNOWN，两次确认只回放收据，SDK 始终一次调用。租约自然到期后 44 张业务表恢复，46 张表原始记录未变，准确保留本次 2 条运行和 3 条轨迹审计，六服务健康。原清理脚本对审计留存的误判及独立只读复核均保留，见 [真实中断验收](../../output/tasks/architecture-audit-20260905/user-confirmation-crash-notes.md)。

范围沿用 [原始 R1–R8 体检与实施清单](architecture-cleanup-audit-20260905.md)。本表区分已经验证的代码规则与尚未完成的部署验收；测试总数、文件行数和健康检查均不能单独证明整个整理完成。

| 原始要求 | 当前实现与证据 | 尚需保留的边界 |
| --- | --- | --- |
| R1：能力来源与目录写入统一治理 | 扫描编辑／推送同时检查来源和已关联投影；[来源守卫](../../reachai-capability-service/src/main/java/com/enterprise/ai/capability/internal/CapabilitySourceContractGuard.java) 使用稳定来源身份及契约哈希。独立 SDK 主机已验证漂移、评审与旧发布拒绝，见 [SDK 证据](../../output/tasks/architecture-audit-20260905/sdk-trace-propagation-proof.json)。 | 独立样例不是客户部署；不能扩大为所有客户系统验收。 |
| R2：主要入口使用固定发布执行上下文 | MCP、Automation 和 Supervisor 使用 Workflow owner 的发布快照；真实 Agent、MCP 双协议、Automation MANUAL／ONCE 已有执行证据，见 [Automation 记录](../../output/tasks/architecture-audit-20260905/automation-utc-trace-notes.md)。另已补齐独立 SDK 首次失败后自动重试，以及两个相隔 30 秒的 CRON 槽位执行，发布新版后仍使用固定版本，见 [周期与重试验收](../../output/tasks/architecture-audit-20260905/automation-cron-retry-notes.md)。定时 Agent 的真实 AgentScope → 固定 Workflow → SDK 调用也已通过，见 [Agent 定时验收](../../output/tasks/architecture-audit-20260905/automation-agent-live-notes.md)。 | 本地双 JVM 的 Workflow 故障接管已通过；真实 AgentScope 定时任务的执行中进程故障已另行通过本地双 JVM 验收；人工确认续跑中崩溃已单独通过本地验收，跨主机生产环境仍需独立证明；无人值守人工确认终止已有独立验收。 |
| R3：发布身份可信、原始发布审计保留 | [Control 发布网关](../../reachai-control-service/src/main/java/com/enterprise/ai/control/runtime/RuntimeWorkflowReleaseGateway.java) 白名单组装命令并签署平台主体；[Runtime 入口](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/api/RuntimeWorkflowVersionPublicController.java) 只接受已验证的 Control 平台身份。回滚追加独立事件，不改原始发布人。 | 不把请求体中的 publishedBy／operator 当作可信身份。 |
| R4：发布、保存和回滚修订语义一致 | [版本服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowVersionService.java) 共用修订检查和行锁；[MySQL／H2 五项复验](../../output/tasks/architecture-audit-20260905/workflow-release-mysql-notes.md) 通过，覆盖并发发布、回滚保留草稿及失败原子性。 | 发布校验和能力固定依赖受控；事务失败点刻意注入，不等同外部依赖故障恢复。 |
| R5：检查器覆盖真实边界 | 当前 13 项 [架构检查](../../output/tasks/architecture-audit-20260905/architecture-implementation-final.log) 通过，包含长环、平铺持久化类型及路由检查。 | 源码检查不证明动态反射或所有运行时交互。 |
| R6：模块内数据访问和契约归属清晰 | Control 24 个、Runtime 28 个模块的循环组、循环边及持久化例外均为 0，见 [边界记录](../../output/tasks/architecture-audit-20260905/final-module-boundaries.json) 与 [规则](../architecture/internal-module-boundaries.md)。 | 这里的零值只针对上述两个服务的已声明源码模块，不是所有分布式调用零耦合。 |
| R7：有完整性语义的批量引用读取 | Workflow 自有反查索引、Agent 自有绑定证据和组合层保持分工。[MySQL 查询计划](../../output/tasks/architecture-audit-20260905/workflow-reference-mysql-notes.md) 与 [并发快照](../../output/tasks/architecture-audit-20260905/workflow-reference-snapshot-notes.md) 已通过。 | 覆盖探测仍可能扫描草稿／版本；EXPLAIN 估计不是生产吞吐量或实际扫描行数。 |
| R8：按独立职责拆分复杂实现 | Supervisor 计划、委派、最终回答和续跑契约；Studio 工作副本与共享弹层；AI Coding 查询／命令；Registry 接纳／凭证；Knowledge 生命周期／查询／编辑均已有分批验证。最新 [续跑契约](../../output/tasks/architecture-audit-20260905/supervisor-continuation-contract-notes.md) 修复六个公开入口反例。 | 仍需完成完整应用中的故障恢复证据；不能把受控依赖测试等同真实 AgentScope 全链路恢复。 |

## 剩余验收工作

确认生成后的额外模型请求和默认匿名状态读取已修复并通过真实重启复验，见 [确认暂停与状态检查验收](../../output/tasks/architecture-audit-20260905/user-confirmation-pause-notes.md)。已修正性能测试模拟等待提前返回的问题，原 5% 门槛及完整回归通过，当前额外开销 1.14%，见 [等待条件核对](../../output/tasks/architecture-audit-20260905/kernel-latency-deadline-notes.md)。历史偶发波动的完整归因仍未建立，不据此扩展到生产规模性能。

1. 真实 AgentScope 定时任务的执行中进程故障已通过本地双 JVM 验收：在第一笔 SDK 只读查询进入后终止实际执行者，另一 PID 在租约到期后按原 Agent／Workflow 发布版本完成第二次尝试；旧 Run／Supervisor 轨迹正确结束，44 表指纹恢复、正常开关和六服务健康均已核对，见 [AgentScope 故障接管验收](../../output/tasks/architecture-audit-20260905/automation-agent-crash-notes.md)。这是至少一次重试，不是恢复丢失的内存模型流；人工确认正在续跑时的进程退出已另行通过本地实际验收，见本表最新记录。Automation 人工确认终止已通过真实 AgentScope 复验：取消确认会话、关闭等待追踪、零业务调用、不可重试，并修复了取消时间误用 UTC 的问题；两轮各 44 张表清理比对和正常服务恢复通过，见 [交互终止验收](../../output/tasks/architecture-audit-20260905/automation-interaction-live-notes.md)。本地两个 Runtime 的 Workflow 故障接管已通过：第一次签名 SDK 调用进入后终止所属进程，真实租约到期后由另一 PID 完成第二次尝试，旧 Attempt／Run／Trace 正确结束。新 JAR 已部署、原开关恢复、37 张表指纹一致、六服务健康，见 [双进程验收](../../output/tasks/architecture-audit-20260905/automation-cluster-live-notes.md)。此前 MANUAL／ONCE、周期、临时失败重试和真实定时 Agent 成功路径的证据继续保留。
2. 用户待确认会话跨实际 Runtime 重启已通过：真实 AgentScope 续跑保持旧 Agent／Workflow 版本，独立 SDK 仅调用一次，重复确认回放、44 表清理与六服务健康均有证据，见 [用户确认恢复验收](../../output/tasks/architecture-audit-20260905/user-confirmation-live-notes.md)。用户确认续跑中进程退出已另行通过；Studio 工作副本暂停后重启、快照隔离、两阶段提交回放与实际 Run／Trace 已通过，见 [草稿恢复验收](../../output/tasks/architecture-audit-20260905/studio-draft-recovery-notes.md)。Studio 首次执行和提交续跑中的进程中断已通过，见 [执行中断验收](../../output/tasks/architecture-audit-20260905/studio-active-crash-notes.md)。仍需完成 Workflow 内部交互恢复。现有隔离 Control→Runtime HTTP／MySQL 恢复证据保留，但范围不同。
3. Knowledge 本地删除失败跨重启、共享 S3 解析中断接管、远端对象写入完成但本地未确认的进程中断和 1200 秒自然到期回收均已通过。索引执行中断、保存解析重试及旧执行再次回收已通过本地完整进程验收；仍需完成跨主机恢复、部署 S3 IAM／网络／持久卷，以及尚未覆盖领域的 MySQL 并发与查询计划。能力目录的 15 项来源／并发／回滚和 3 项插入失败原子性已补齐；Knowledge 内容／目录写入、引用索引和 Workflow 草稿、发布／回滚测试继续单列，不外推为整个数据库的全部行为。

客户部署环境和生产规模性能仍属于明确未验证的边界，没有现成证据时不能报通过。当前整体状态保持整理与验收进行中；“约 90%”是工程估算，没有用测试数量换算百分比。
