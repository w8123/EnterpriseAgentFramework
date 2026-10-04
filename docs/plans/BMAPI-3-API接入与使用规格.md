# BMAPI-3 API 接入与使用规格

更新时间：2026-10-03。状态：BMAPI-3A 各批、3B1 OpenAPI GET、3B2 Controller GET、3B3 双来源/冲突、3C-A 作者流程、3C-B 已发布执行、3C-C API 草稿只读真实试运行、3B4 POST JSON Console、4A GET 变化/引用/显式重发、3D 市场只读 GET 受控绑定与 3C-D/R1 写 API 已发布 Workflow 已按各自隔离证据通过范围限定的主任务复核。后续[5A 旧入口退场](../../output/tasks/business-method-api/BMAPI-5A-主任务复核.md)、[5B 独立 MySQL/五服务与原生 200% 代表操作](../../output/tasks/business-method-api/BMAPI-5B-主任务复核.md)、[5C 授权入口](../../output/tasks/business-method-api/BMAPI-5C-主任务复核.md)及[R1 完成提示/可见焦点](../../output/tasks/business-method-api/BMAPI-5C-R1-主任务复核.md)亦已限定复核；不把这些代表证据扩写为全平台、复杂认证/契约、客户环境或真人验收。实际执行位置与剩余项只见[专项进度](业务方法与API重构实施进度.md)，不在本规格另设进度表。

关联[实施基线](./业务方法与API重构实施基线.md) U3、[设计决策](./业务方法与API重构设计决策.md) Q1/Q2/Q5、[3-0 核对报告](../../output/tasks/business-method-api/BMAPI-3-0-API使用路径契约核对.md)和[主任务复核](../../output/tasks/business-method-api/BMAPI-3-0-主任务复核.md)。

## 1. 本阶段用户目标

开发者通过 Controller 或 OpenAPI 发现一个 HTTP API 后，在 API 管理中看到稳定操作身份、完整请求/响应契约、来源和当前事实状态；同一操作来自多个来源时能看到等价关系或具体冲突。实施人员只能选择已接纳、当前可用且项目/环境匹配的 API，在 Workflow 发布时固定同一 owner contract，并通过受控 HTTP 执行、Trace 和引用反查完成验证。

发现、目录收录、项目接入意图、配置完成、契约接纳、连通性验证、调用成功和授权可用是不同事实。任何页面状态不得用其中一个冒充另一个。

## 2. 对象与所有权

- HTTP API 是 Capability Service 内的一等资产，拥有稳定操作身份、accepted contract 和来源关系。
- Controller、OpenAPI、Starter MVC 和 API 市场 Operation 是来源；来源绑定保存观察事实和等价/冲突/移除状态，不成为另一份可自由编辑资产。市场来源目前仅限定签收 3D 无认证只读 GET 范围。
- `capability_tool_definition` 是已接纳 API 的运行投影。用户不维护第二份通用 Tool，也不以 Tool name 作为 API 身份。
- Runtime 拥有 Workflow、凭据、执行、Trace 和发布引用；只通过 Capability internal API / read model 获取 API 执行投影，不直接读取 Capability 表。
- API 市场拥有外部目录和项目接入意图。3D 服务端受控 binding 已接入同一 API owner；raw `marketRef` 仍仅能作 provenance，不是授权、版本 pin 或调用成功证据，旧 raw HTTP_REQUEST 引用在最终发布/执行时拒绝。

## 3. HTTP 操作身份与契约

### 3.1 服务范围

服务范围至少由当前 Capability 项目和环境显式确定。项目 ID 负责内部隔离；面向引用的内部服务稳定范围键由不可随展示名改变的 `projectCode + environment` 形成。base URL 和凭据属于环境配置，不进入逻辑操作身份。3D 外部来源使用 owner 确认的稳定市场 entryKey 服务命名空间，隔离不同外部服务及内部服务的同路径；目录版本不作为稳定身份，作为选定来源修订固定。该扩展已按 3D 隔离代表证据复核，保持既有内部三来源的身份/hash/ref 字节语义；不代表全部外部来源或真实部署完成。

### 3.2 操作身份

操作身份由以下规范化字段共同确定：

1. 服务范围；
2. 大写 HTTP method；
3. 完整 route template（context path + endpoint path）；
4. mapping conditions 的规范化摘要，包括 consumes、produces、headers 和 params 条件。

展示名、operationId、Controller 方法名、Tool name、source location 和 base URL 均不能单独作为身份。实现使用完整 SHA-256 identity hash 建立唯一键；稳定 `qualifiedName` 使用服务范围与完整 hash，不使用可能碰撞的截断 hash。

### 3.3 规范化契约

契约至少包含身份字段、参数位置（path/query/header/cookie/body）、请求 schema/content type、响应 schema/status/content type、认证需求和副作用声明。列表与对象字段采用确定性排序，hash 不受 JSON 属性顺序或来源展示文案影响。

秘密值、base URL、Runtime credential reference、Cookie/Header 实际值和验证响应体不得进入身份、contract hash、目录表或来源绑定。认证方案和必需 header 名可以进入契约，凭据值不可以。

## 4. 多来源关联规则

- 相同服务范围和操作身份映射到同一候选 API 资产。
- 第一条来源只形成 `DISCOVERED` 候选，不自动变成已接纳、已授权或可执行。
- 后续来源的规范化 contract hash 与已有活动来源一致时标记为 `EQUIVALENT`；不一致时资产进入 `CONFLICT`，保留每个来源事实，禁止最后写入者覆盖。
- 来源重复上报必须幂等；同一来源 key 的新 revision 只更新该绑定，再重算资产聚合状态。
- 来源移除只将该绑定标为 `REMOVED`。仍有等价活动来源时资产不消失；全部来源移除时为 `SOURCE_MISSING`。已发布引用不得自动改指向另一个来源。
- 3A-2 复用现有 registry sync、snapshot 和 sync log 承载发现证据；API 接纳、忽略、回滚与历史审计在 3B 复用既有 review/apply 生命周期并按 API 资产语义扩展，不另建一套完整评审系统。

## 5. 分批实施边界

### BMAPI-3A-1：身份、契约和来源关系基础

新增 Capability-owned HTTP API asset/source binding 持久化、确定性规范化和聚合状态。只提供服务内领域能力和持久化测试；不改现有扫描/SDK 写链，不增加公开路由，不生成 Tool 投影，不修改 Runtime 或前端。

### BMAPI-3A-2A：Starter MVC 来源接入

通过现有签名 registry sync 同时传输业务方法和独立的 `httpApis` 清单，让 Starter MVC 候选进入 3A-1 asset/source binding。补齐多 method/path、参数位置、mapping/content requirement 和双重声明；复用同一 snapshot/sync log，发现仍不自动接纳或生成 Tool 投影。

### BMAPI-3A-2B1：Controller 文件扫描来源接入

让现有 Controller 文件扫描在同一次扫描中产出独立 HTTP API operation inventory，由 Capability Service 以 `CONTROLLER_SCAN` 来源适配到同一规范化候选；验证重扫、移除、来源证据及与 Starter MVC 同 scope 的等价或冲突。不按扫描 Tool name 合并，不把扫描行或 `promote-to-tool` 当作接纳。

### BMAPI-3A-2B2：OpenAPI 文件扫描来源接入

让 OpenAPI 文件扫描产出独立 HTTP API operation inventory，补齐 path/query/header/cookie/body、响应、content、security requirement 等可证明的契约事实；与 Starter/Controller 在同 scope、identity 和 canonical contract 一致时自动等价，否则保留冲突或明确的不可比较原因。旧扫描项和人工 `promote-to-tool` 的退场条件在本批形成具体去留清单，接纳与执行仍留给 3B/3C。

### BMAPI-3B：API 管理与受控验证

交付 API 目录、详情、来源/冲突/接纳状态和项目环境配置；受控验证复用 Runtime credential、egress、Trace 和脱敏边界。扫描项目明文 API key 不作为新凭据方案，读取 DTO 不返回秘密。

首个交付以一条普通 Controller 或 OpenAPI 操作为单位，贯通目录查找、请求/响应理解、来源状态、必要配置与接纳、受控 HTTP 试调用和可行动的失败提示。用实际页面操作和隔离 HTTP 调用证明这条路径，再复用同一流程补齐另一种代表来源；不把所有解析形式完备作为页面交付的前置条件。未知/不完整扫描不得被展示为完整发现或可信来源移除，已知来源移除缺口继续作为明确验收项。此安排调整交付顺序，不降低契约、授权、凭据、调用保护与来源正确性的要求。

首批细化为 [3B1 API 目录与首条受控试调用](../../output/tasks/business-method-api/BMAPI-3B1-API目录与首条受控试调用任务.md)：从带项目凭据的 OpenAPI GET 查询交付完整页面操作，复用 Runtime PROJECT credential、受控 HTTP client 与 Console 记录，不要求先创建 Workflow/MCP/Tool。首批调用形式之外的 API 明确显示限制；Controller 完整代表流程、写方法与其余必要契约形式仍属于 3B 后续覆盖，不能把首条通过计为整个 API 阶段完成。

第二条用户流程见 [3B2 Controller 来源 API 代表流程](../../output/tasks/business-method-api/BMAPI-3B2-Controller来源API代表流程任务.md)：同一 Controller 源码从扫描到 API 目录、接纳、显式连接选择、隔离 HTTP 和 Run/Trace，不再追加仅有来源适配的前置批次。3B1 原生 200% 缩放未验证项在整体验收中继续跟踪，不能由窄屏 CSS 证据替代。

3B2 已按[主任务复核](../../output/tasks/business-method-api/BMAPI-3B2-主任务复核.md)通过第二条隔离功能流程；这不关闭多来源关系、写方法、复杂契约或真实环境验收。为保持用户操作交付节奏，先进入 3C 的 API Workflow 使用路径，再继续收齐 3B 余项；这只是实施排序，不改变 U3 的验收范围。

3B3 已按[主任务复核](../../output/tasks/business-method-api/BMAPI-3B3-主任务复核.md)签收一个 GET 的双来源与冲突用户流程。[3B4 写 API 受控试调用任务](../../output/tasks/business-method-api/BMAPI-3B4-写API受控试调用代表流程任务.md)已交付一个正常扫描 POST + WRITE + JSON 扁平请求体 API 的详情输入、明确确认、一次隔离写入与未知结果查询；复用现有 Console ledger、项目凭据、egress 和 Run/Trace。Console 支持扩展与 Studio 只读/当前 Workflow 支持分别判定，写请求不自动重发或跟随重定向。本批不是写 API Workflow 或全部 HTTP/schema 形式开放。

3B4 首版的实际写入/未知恢复等证据保留，R1/R2 已按[最终主任务复核](../../output/tasks/business-method-api/BMAPI-3B4-主任务复核.md)闭合并签收：JSON body 缺省与显式 null 分开，当前不支持 nullable 契约时明确拒绝 null；来源 password-format/writeOnly 声明贯通既有编辑器遮蔽及 prepare 固定敏感输入值的安全结果。真实公开 HTTP、聚焦浏览器与必要回归分别有新证据，不把首版历史结果当作修正后的完整复跑。签收限隔离 Console 代表流程。

### BMAPI-3C：Workflow 使用路径

API picker 只读取已接纳、READY、范围匹配的 API；GraphSpec 保存稳定 API ref，Runtime 发布固定 owner contract 和技术投影，执行后形成 Trace 与草稿/发布引用反查。不得 name-only fallback。

3C-A 的[作者流程任务](../../output/tasks/business-method-api/BMAPI-3C-A-API进入Workflow作者流程任务.md)已按[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-A-主任务复核.md)交付 Studio 选择、映射、保存/重开、可达的下游字段节点及同图 Runtime 草稿回读；[3C-B 已发布受控执行](../../output/tasks/business-method-api/BMAPI-3C-B-主任务复核.md)已按隔离范围通过复核。两者都不能代替未发布草稿的真实调试。

Studio 草稿真实调试采用用户于 2026-09-24 确认的[只读真实试运行方案](../../output/tasks/business-method-api/BMAPI-3C-Studio真实调试身份方案.md)：项目测试身份、调用权限、审计和专用可信内部入口必须同时成立；普通 `DEBUG_UNTRUSTED` 不升级为可信调用。[3C-C 主任务复核](../../output/tasks/business-method-api/BMAPI-3C-C-主任务复核.md)只签收保存草稿的 GET API→变量代表图；复用同一信任机制的[2D-C 业务方法主任务复核](../../output/tasks/business-method-api/BMAPI-2D-C-主任务复核.md)只签收一个只读标量方法代表图。[3B3 多来源主任务复核](../../output/tasks/business-method-api/BMAPI-3B3-主任务复核.md)已按一个 GET 操作的双来源、冲突解释和隔离用户流程限定签收，不代表写 API 或全部变化影响已验收。

来源变化、具体引用影响和显式重新发布的完整操作，已按 U4 的[BMAPI-4A 主任务复核](../../output/tasks/business-method-api/BMAPI-4A-主任务复核.md)限定签收一个 GET 流程。sourceSetRevision、契约 hash 和引用完整性分别判断；接纳不自动改旧 pin、调用授权或开放发布。真实未发布草稿的来源拒绝以独立补充证据证明，不将已发布状态前置门禁混称来源保护；4A 不代表 U4 全部完成。

2026-10-02 用户要求持续推进，[3C-D 写 API 已发布 Workflow](../../output/tasks/business-method-api/BMAPI-3C-D-写API已发布Workflow代表流程任务.md)已按[主任务复核](../../output/tasks/business-method-api/BMAPI-3C-D-主任务复核.md)限定签收：正常扫描 POST + WRITE JSON 扁平对象从 Studio 选择/映射、保存/重开、显式发布进入实际写入、下游结果及运行/引用回查；[R1](../../output/tasks/business-method-api/BMAPI-3C-D-R1-实施结果.md)修正合法必填空对象、390px 裁切和真实无摘要/回查说明，静态收口通过。复用既有 pin、可信入口、项目凭据、风险/Guard 与不自动重试语义，Studio 继续只读；Console 写成功不代替 Workflow 验收，原生 200%、夹具流式错误/警告及整体环境/真实用户验收继续单列。

### BMAPI-3D：API 市场受控绑定

把 Operation 和项目接入意图转换为可验证的 external API binding；凭据、版本、Operation 权限和引用成立后才允许发布执行。修正市场 `READY` 与 raw `marketRef` 的误导语义，并保留目录与 Runtime 所有权边界。

按[3D 受控绑定代表流程任务](../../output/tasks/business-method-api/BMAPI-3D-API市场受控绑定代表流程任务.md)和[主任务复核](../../output/tasks/business-method-api/BMAPI-3D-主任务复核.md)，已限定签收一个已发布无认证只读 GET：市场选择项目/环境/固定版本/Operation → 同一 API 候选与接纳 → 显式项目连接/目标 ACL → Runtime 实际 Console 验证 → 正常 Studio 保存/只读试运行/显式发布 → 实际已发布 MCP Workflow 调用。当前 proof 绑定来源/契约/连接/凭据修订；发现、接入或市场 READY 不代替验证。新目录版本不自动替换项目选择，显式改选及失效恢复不复活旧验证/pin；来源下线旧发布零追加派发。旧 raw marketRef 不能作为可信执行证明，普通无市场引用 HTTP 节点保持其既有职责。证据限单 JVM/隔离 H2/签名 HTTP/loopback 与正常浏览器；复杂认证/schema、写 API Workflow、MySQL、独立部署和真实业务/用户验收继续单列。

## 6. 3A-1 验收结果

1. 相同服务范围、method、route 和 conditions 得到相同 identity；不同项目/环境、method、route 或 conditions 不会合并。
2. JSON 属性顺序、来源展示名和 source location 不改变 contract hash；参数位置、schema、content/auth requirement 变化会改变 hash。
3. 两个来源同 identity + 同 contract 形成一个资产和两个等价绑定；contract 不同形成可解释冲突且不覆盖原观察。
4. 重复观察幂等；更新单个来源 revision、移除一个来源和移除全部来源后状态正确。
5. 新表进入 `sql/initV2.sql`、当次 upgrade SQL 和表所有权；MySQL 5.7/8 写法沿用当前幂等约定。
6. 没有公开 API、页面、Tool 投影或 Runtime 行为变化；现有 BUSINESS_METHOD 来源状态与投影回归通过。

2026-09-21 主任务按上述门槛签收：R1–R7 组合 17/17、canonicalizer 4/4、H2/MyBatis 10/10、BUSINESS_METHOD 回归 35/35、架构与表所有权守卫 13/13 均通过。证据是本地源码和隔离 H2；真实 MySQL upgrade、生产并发与外部 HTTP 不在该结论内。

## 7. 停止条件

3A-2B1 只接 Controller 文件扫描和 Capability-owned 来源观察，不修改 OpenAPI 扫描、公开管理 API、前端、Control、Runtime、Workflow、权限、真实数据库或 HTTP 执行。若发现必须改变“API 一等资产、Tool 仅为投影、发现不等于接纳”的已确认产品方向，整理影响后再讨论；普通 DTO、表字段和类拆分由执行者自主处理。
