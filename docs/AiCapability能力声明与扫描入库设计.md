# AiCapability 能力声明与扫描入库设计

> 目标：让可改造的 Java 业务系统通过结构化注解显式声明 Agent 能力，扫描后自动进入 `scan_project_tool` / `tool_definition`，并成为 Agent Studio 可拖拽、可映射、可治理的节点。

## 一、设计目标

纯 Controller / OpenAPI 扫描能让平台“看到接口”，但很难稳定获得业务域、副作用、参数来源、权限建议等 Agent 编排所需信息。本设计在保持零改造扫描链路可用的前提下，为可改造业务系统提供一组轻量注解：

- `@AiCapability`：声明一个 Controller 方法或业务方法是 Agent 可用能力。
- `@AiParam`：声明入参或 DTO 字段的业务语义、字典、来源提示。
- `@AiOutput`：声明出参或 DTO 字段是否可作为后续节点参数来源。

第一阶段只做“扫描入库 + 元数据展示 + Studio 节点消费”，不做 Java 方法直调，也不把平台扩展成通用工作流引擎。

## 二、注解契约

### 2.1 `@AiCapability`

用于方法级能力声明，建议与 Spring MVC 映射注解一起使用。

```java
@AiCapability(
    name = "queryCustomerCredit",
    title = "查询客户授信额度",
    description = "根据客户ID查询当前可用授信额度",
    domain = "finance",
    module = "customer",
    tags = {"客户", "授信", "额度"},
    sideEffect = SideEffectLevel.READ_ONLY,
    requiredRoles = {"finance_user"},
    timeoutMs = 3000,
    retryLimit = 1
)
@PostMapping("/customer/credit/query")
public CreditVO queryCredit(@RequestBody CreditQueryRequest request) {
    return creditService.query(request);
}
```

字段含义：

| 字段 | 说明 | 入库建议 |
| --- | --- | --- |
| `name` | Agent 能力名；为空时回退方法名规范化 | `name` |
| `title` | 业务展示名 | `capability_metadata_json.title` |
| `description` | 面向 Agent 的业务描述；优先级高于 JavaDoc / Swagger | `description` |
| `domain` | 业务领域，如 finance / hr / crm | `capability_metadata_json.domain`，后续可反写领域挂接 |
| `module` | 业务模块 | `capability_metadata_json.module` |
| `tags` | 业务标签 | `capability_metadata_json.tags` |
| `sideEffect` | 副作用等级 | promote 到 `tool_definition.side_effect` |
| `agentVisible` | 是否默认对 Agent 可见 | `agent_visible` |
| `requiredRoles` | 权限建议 | `capability_metadata_json.requiredRoles`，后续可生成 ACL 草稿 |
| `timeoutMs` / `retryLimit` | 可靠性建议 | `capability_metadata_json`，后续给 Tool/Skill 执行器消费 |

### 2.2 `@AiParam`

用于 Controller 参数、DTO 字段、record component。

```java
public class CreditQueryRequest {
    @AiParam(
        description = "客户ID",
        sourceHint = "通常来自 queryCustomer.response.data.customerId",
        required = true,
        dictType = "customer"
    )
    private Long customerId;
}
```

字段含义：

| 字段 | 说明 | 入库建议 |
| --- | --- | --- |
| `description` | 参数业务说明 | 参数 `description` |
| `required` | 是否必填；为空时沿用 `@RequestParam(required)` / 校验注解推断 | 参数 `required` |
| `example` | 示例值 | 参数 `metadata.example` |
| `sourceHint` | 参数来源提示 | 参数 `metadata.sourceHint`，也可进入接口图谱候选解释 |
| `dictType` | 字典类型，如 dept / user / customer | 参数 `metadata.dictType` |
| `sensitive` | 是否敏感 | 参数 `metadata.sensitive` |

### 2.3 `@AiOutput`

用于 DTO 字段或 record component，帮助接口图谱判断哪些出参可作为后续入参来源。

```java
public class CustomerVO {
    @AiOutput(
        description = "客户ID，可作为合同创建、授信查询等接口的 customerId",
        businessKey = "customer.id",
        canBeSourceFor = {"customerId", "contract.customerId"}
    )
    private Long customerId;
}
```

字段含义：

| 字段 | 说明 | 入库建议 |
| --- | --- | --- |
| `description` | 输出字段业务说明 | 参数 `description` |
| `businessKey` | 业务对象键，如 `customer.id` | 参数 `metadata.businessKey` |
| `canBeSourceFor` | 建议可供哪些入参使用 | 参数 `metadata.canBeSourceFor` |
| `sensitive` | 是否敏感 | 参数 `metadata.sensitive` |

## 三、扫描与合并规则

Controller 扫描时按以下优先级合并描述：

1. `@AiCapability.description`
2. `@Operation.description / summary`
3. `@ApiOperation`
4. JavaDoc
5. 方法名

能力名按以下优先级：

1. `@AiCapability.name`
2. 规范化方法名
3. 泛化方法名时回退规范化路径

参数描述按以下优先级：

1. `@AiParam.description` / `@AiOutput.description`
2. JavaDoc `@param`
3. `@Schema.description`
4. `@Parameter.description`
5. 字段名

副作用等级按以下优先级：

1. `@AiCapability.sideEffect`
2. 扫描期 `SideEffectInferrer` 根据 HTTP method + path 保守推断
3. 默认 `WRITE`

## 四、入库模型

第一阶段用一个 JSON 扩展字段承载能力声明，避免过早拆表：

```text
scan_project_tool.capability_metadata_json
tool_definition.capability_metadata_json
```

示例：

```json
{
  "declared": true,
  "name": "queryCustomerCredit",
  "title": "查询客户授信额度",
  "domain": "finance",
  "module": "customer",
  "tags": ["客户", "授信"],
  "sideEffect": "READ_ONLY",
  "agentVisible": true,
  "requiredRoles": ["finance_user"],
  "timeoutMs": 3000,
  "retryLimit": 1,
  "source": "AiCapability"
}
```

参数级元数据直接挂在参数 JSON 中：

```json
{
  "name": "customerId",
  "type": "integer",
  "description": "客户ID",
  "required": true,
  "location": "BODY",
  "metadata": {
    "sourceHint": "通常来自 queryCustomer.response.data.customerId",
    "dictType": "customer",
    "sensitive": false
  }
}
```

## 五、与 Studio 和接口图谱的关系

### 5.1 SDK 运行时扫描与平台「扫描设置」

业务系统引入 `ai-spring-boot-starter` 后，无 `@AiCapability` 的 Controller 方法会按 **`scan_project.scan_settings`** 中的 **接口说明来源顺序 / 参数说明来源顺序**（与「扫描详情 → 扫描设置」同一配置）尝试 **Swagger `@ApiOperation`、OpenAPI `@Operation`、`@Schema`、`@Parameter`** 等**运行时可见注解**；**不在业务 YAML 中配置该顺序**。管理端可在 **注册中心 → 项目详情** 中编辑并保存同一套 `PATCH /api/scan-projects/{id}/scan-settings`。SDK 通过 **`GET /api/registry/projects/{projectCode}/capability-description-settings`**（与注册相同的签名校验）拉取子集；服务端会剔除 **Javadoc** 类源（SDK 无法从字节码读取 JavaDoc）。

Agent Studio 使用能力声明做三件事：

1. Tool 节点属性面板展示领域、标签、副作用、权限建议。
2. 节点参数面板展示 `sourceHint`，辅助运营做变量映射。
3. 发布前检查 `sideEffect`、`requiredRoles` 与 Agent 当前配置是否冲突。

接口图谱使用能力声明做两件事：

1. `@AiOutput.businessKey / canBeSourceFor` 提升候选边置信度。
2. `@AiParam.sourceHint` 作为参数来源提示的人工种子。

## 六、阶段边界

本阶段做：

- SDK 注解定义。
- Controller 扫描读取注解。
- 扫描结果携带能力元数据。
- 扫描入库和 promote 到全局 Tool 时保留元数据。
- 前端 Tool / Studio 展示元数据。

本阶段不做：

- Java 方法直调。
- 编译期注解处理器。
- 自动生成 Tool ACL 规则。
- 通用工作流 DSL。
- 复杂表达式引擎。

后续如果真实业务验证通过，再把 `domain / tags / requiredRoles` 等高频字段拆为独立列或独立治理表。
