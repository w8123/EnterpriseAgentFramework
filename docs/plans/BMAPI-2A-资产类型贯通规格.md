# BMAPI-2A：资产类型贯通规格

更新时间：2026-09-15。状态：主任务已定规格，待执行任务实现与验证。

关联：[实施基线](./业务方法与API重构实施基线.md) U1/U2、[设计决策](./业务方法与API重构设计决策.md) Q1–Q3/Q6。本文只覆盖方法和现有 MVC 描述符的类型贯通，不等于业务方法产品已交付。

## 1. 预期行为

- Java 显式方法描述符明确为 `BUSINESS_METHOD`；现有 MVC endpoint 描述符明确为 `HTTP_API`。
- SDK 注册将类型保存在 `metadata.assetType`，Capability 统一验证并通过现有快照、diff、接纳写入扫描行和调用投影。
- 两张投影表有明确 `asset_type`，为下一批业务方法目录筛选提供可靠依据。
- 未携带类型的旧输入仍可沿原链路处理，但投影为 `UNCLASSIFIED`；其 metadata/hash 不因补默认字段而静默变化。
- 现有方法调用名称、默认 SDK 入口、HTTP API 地址、参数语义、Workflow/MCP pin 与来源保护保持原有执行约束。

## 2. 允许的代码范围

- `reachai-capability-sdk`：明确类型契约与 descriptor/scanner。
- `reachai-spring-boot2-starter`：MVC descriptor 类型赋值及注册 metadata 传递。
- `reachai-capability-service`：统一类型解析/校验，来源应用、更新、回滚和必要读取投影的一致性；相关实体与测试。
- `sql/initV2.sql`、`sql/upgrade-20260915-capability-asset-type.sql`、`sql/README.md`；必要契约说明及服务表所有权说明。
- 本批结果写 `output/tasks/business-method-api/BMAPI-2A-实施结果.md`，完整测试输出放同目录的批次子目录。

不修改主任务维护的基线、设计决策和进度；不修改前端、Control、Runtime 主路径、API 市场、通用权限和 A2d0 WIP。若存在必须跨出范围的实际编译/契约依赖，先回报具体文件、错误及最小方案。

## 3. 数据与类型规则

1. 使用明确的 SDK 类型（枚举或等价的受控类型）；服务端集中解析，不在各 Mapper、接口或页面散布字符串推断。
2. `metadata.assetType` 接受 `BUSINESS_METHOD`、`HTTP_API`；缺失兼容为未分类。非法非空值必须明确拒绝，不退回方法、API 或自动忽略。
3. 兼容缺失时，不向原 metadata 插入 `UNCLASSIFIED`。类型一旦被明确声明就是契约内容，既有 diff/hash/来源漂移保护必须覆盖其变化。
4. `capability_scan_project_tool` 与 `capability_tool_definition` 新增 `asset_type VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED'`，取值含上述三类；索引支持按类型和项目读取。初始化与 upgrade 沿用项目幂等写法。
5. 该列是源契约的派生投影，只由领域写入过程维护。检查 SDK APPLY、重复/不变同步、IGNORE、下线与 rollback：候选或被忽略变化不能提前污染已接纳投影；回滚不能保留错误的新类型。
6. 旧手工/内置/扫描记录默认未分类，不依据路径、GET/POST、`source=sdk` 或 className 存在就回填类型；扫描 API 的完整类型化在 BMAPI-3 处理。
7. 现有通用目录请求不得获得任意改变 SDK 类型的写权限。若已有完整对象更新会意外覆盖新字段，应在 owning service 最小修正并给出反例测试。
8. 本批不变更稳定名称、公共路由与 qualified name 唯一约束，不执行升级 SQL，不为填新字段重算已有发布哈希。

## 4. 本批明确保留的未完成部分

- MVC 对显式注解方法的 skip 行为、本来丢失的参数 location、多 method 的身份冲突，留待 BMAPI-3 一起按 API 契约修复；不得顺手扩大本批。
- API 目录筛选与业务方法页面、单项试调用、Workflow 选择体验，分别由后续明确子批次完成。
- 不用本批测试宣称真实 SDK 同步、数据库升级或用户使用流程已验收。

## 5. 执行任务必须提供的验证

- SDK：显式方法声明得到方法类型，现有参数与返回声明未回退；JDK8 契约保持可编译。
- Starter：MVC 得到 API 类型；显式方法与 API 注册 metadata 明确不同；现有方法名称和调用地址保持一致。
- Capability：两类分别通过 SOURCE 接纳写入一致投影；非法值拒绝；未声明旧输入 metadata/hash 保持原语义；类型变化触发既有差异与漂移规则。
- 状态反例：候选/IGNORE 不改 accepted 投影，重复同步幂等，更新及 rollback 后类型与已接纳契约一致；SDK 类型不能由通用目录写入改掉。
- 数据：新库与 upgrade 的列、默认值、索引一致，包含旧行未分类说明；若未实际运行数据库验证，明确标为未验证。
- 运行相关 Maven 模块目标测试和必要模块编译；使用 JDK17 构建服务，SDK 保持 JDK8 目标。运行 `node scripts/verify-architecture.mjs` 与本批 `git diff --check`，包括主任务本轮新增规格和进度文档的空白/路径检查。
- 对比开始时的工作树，确认没有覆盖既有 WIP。不要重新跑无关前端测试、全量模型调用或发布/部署。

测试失败时由执行任务定位并在范围内修复；遇到已有无关失败需给出同一错误签名与归因，不通过改断言或跳过验证制造成功。

## 6. 回报和停止点

结果包含改动文件、关键行为、测试命令/数量/结果、证据路径、未验证部分与范围外依赖。向主任务主动回报一次，随后结束回合；主任务复核前不进入 BMAPI-2B。实际运行测试和复测均由执行任务负责。

