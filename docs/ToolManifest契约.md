# Tool Manifest 契约

`Tool Manifest` 是 `ai-skill-scanner` 与模板生成器之间的统一输入输出格式，用来冻结 scanner-first 主线的最小契约。

## 适用范围

- 第一批仅覆盖 HTTP 接口型 Tool。
- 扫描优先级固定为：`Swagger/OpenAPI -> Controller 注解 -> Service/JavaDoc`。
- 当前契约优先服务代码生成和运行时接入，不包含权限、审计、跨语言协议等治理字段。

## 顶层结构

```yaml
project:
  name: legacy-crm
  baseUrl: http://localhost:9001
  contextPath: /api
tools:
  - name: query_customer
    description: 查询客户信息
    method: GET
    path: /customer/search
    endpoint: GET /api/customer/search
    parameters:
      - name: keyword
        type: string
        description: 搜索关键词
        required: true
        location: QUERY
    requestBodyType:
    responseType: JSON数组，包含客户列表
    source:
      scanner: openapi
      location: openapi.yaml#/paths/~1customer~1search/get
```

## 字段说明

### `project`

- `name`: 被扫描项目标识，同时作为生成模块和配置前缀的基础名称。
- `baseUrl`: 运行时调用目标服务的基础地址。
- `contextPath`: 服务公共前缀，例如 `/api`、`/ai`。

### `tools[]`

- `name`: 生成后 `AiTool.name()` 的唯一标识，使用 snake_case。
- `description`: Tool 对业务能力的简洁描述。
- `method`: HTTP 方法，当前支持 `GET`、`POST`、`PUT`、`DELETE`。
- `path`: 不含 `contextPath` 的接口路径。
- `endpoint`: 人类可读的完整端点摘要，格式固定为 `<METHOD> <contextPath><path>`。
- `parameters`: 生成运行时 `ToolParameter` 与 HTTP 请求组装所需的参数列表。
- `requestBodyType`: 原始请求体类型；若无请求体则为空。
- `responseType`: 原始响应类型或可读说明。
- `source`: 记录扫描来源，便于追踪和人工修正。

### `parameters[]`

- `name`: Tool 入参名称。
- `type`: 生成后暴露给 Agent 的参数类型，首批仅用 `string`、`integer`、`number`、`boolean`、`json`。
- `description`: 参数说明。
- `required`: 是否必填。
- `location`: HTTP 参数来源，目前固定为 `PATH`、`QUERY`、`BODY`。

## MVP 映射规则

- `@RequestBody` 或 OpenAPI `requestBody` 在 MVP 阶段统一映射为单个 `BODY` 参数。
- 该参数默认命名为 `body_json`，类型为 `json`。
- 复杂 DTO 字段展开、嵌套对象拆分、Schema 级约束不在当前阶段实现。
- 生成器会把 `BODY` 参数透传为 JSON 请求体，把 `PATH` 与 `QUERY` 参数分别组装到 URL。

## 生成约束

- 生成产物必须兼容现有 `ai-skill-sdk` 的 `AiTool` 和 `ToolParameter`。
- 生成产物遵循当前 `ai-skill-services` 的 jar 加载模式。
- 当前生成客户端对齐现有仓库实现，默认使用 `RestClient` 发起 HTTP 调用，而不是引入新的独立运行时。
