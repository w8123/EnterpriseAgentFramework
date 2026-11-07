# Node.js / Express 业务系统接入

`@reachai/node-sdk` 1.0.0 面向 Node.js 20 或以上、Express 4.18 至 5.x。项目注册、能力清单、业务调用与 Embed 令牌沿用 [SDK 接入协议](../docs/contracts/sdk-protocol.md)。业务 HTTP API 仍使用平台 OpenAPI 导入，不伪报 Spring MVC 来源。首版未提供其他 Node.js 框架的适配器。

从平台下载并核验 npm 包 SHA-256，在业务工程执行 `npm install <已校验tgz路径> express`。TypeScript 项目还须安装与宿主 Express 主版本一致的 `@types/express`：Express 4 使用 `@types/express@4`，Express 5 使用 `@types/express@5`。应用调用 `createReachRouter` 前必须声明完整能力；服务开始监听后调用 `start()` 注册并心跳，退出时调用 `stop()` 下线。示例中的两个函数必须由宿主实现，不可使用浏览器提交的用户或角色充当可信身份。

```ts
import express from 'express'
import { ReachAI, EncryptedFileCredentialStore, createReachRouter, currentInvocation } from '@reachai/node-sdk'

const app = express()
// 在此之前配置业务系统自己的 Cookie、登录会话和 CSRF 验证。
const reach = new ReachAI({
  registryUrl: process.env.REACHAI_REGISTRY_URL!,
  projectCode: process.env.REACHAI_PROJECT_CODE!,
  baseUrl: process.env.REACHAI_BASE_URL!,
  enrollmentToken: process.env.REACHAI_ENROLLMENT_TOKEN,
  allowedOrigins: [process.env.REACHAI_BUSINESS_ORIGIN!],
}, {
  credentialStore: new EncryptedFileCredentialStore(process.env.REACHAI_CREDENTIAL_FILE!, process.env.REACHAI_CREDENTIAL_KEY!),
  principalResolver: async (request, agentId) => {
    // 从服务端已验证的业务登录态取得用户、租户、角色和业务会话。
    throw new Error('请实现真实业务登录态解析')
  },
  securityBridge: async (identity, next) => {
    // 核验员工与租户，恢复业务身份；业务方法仍需验证数据权限。
    throw new Error('请实现真实业务身份与权限校验')
  },
})
reach.capability({
  name: 'order.query', title: '查询订单', sideEffect: 'READ', businessIdentityRequired: true,
  inputSchema: { type: 'object', properties: { orderId: { type: 'string' } }, required: ['orderId'] },
  outputSchema: { type: 'object', properties: { id: { type: 'string' }, status: { type: 'string' } }, required: ['id', 'status'] },
  handler: async ({ orderId }) => {
    const identity = currentInvocation()
    // 根据 identity 和业务库中的订单所有权进行授权。
    throw new Error('请调用已授权的业务服务')
  },
})
app.use(createReachRouter(reach))
const server = app.listen(3000, async () => { await reach.start() })
process.on('SIGTERM', () => { void reach.stop().finally(() => server.close()) })
```

`REACHAI_CREDENTIAL_KEY` 为独立秘密管理服务提供的 32 字节随机密钥的标准 Base64 编码；凭据文件与密钥分别保管，目录仅允许服务账号访问（Windows 由宿主配置 ACL）。注册发送前会持久化 pending 标记，响应丢失或保存失败不得自动重用一次性 token；核对平台并按受控流程恢复。多 worker 首次只由单个实例注册，其余实例使用同一受保护凭据。

输入和输出须提供明确的 JSON Schema；支持标量、可空值、数组和显式字段对象，不支持 `$ref`、递归、任意字典或其他联合类型。未知输入字段会被拒绝；标准 `format` 由 `ajv-formats` 校验，未知格式不能注册。`currentInvocation()` 在异步请求间隔离，已验证身份不能替代业务行权限或事务校验。能力没有声明时不得同步空清单；确认完整清单为空后可调用 `markEmptyInventoryComplete()`。同步回调默认防重放仅覆盖单进程，多进程部署须注入共享、原子、故障时拒绝的 `nonceStore`。业务退出必须调用 `await reach.revokeBusinessSession(sessionId)`，失败由宿主可靠处理。

平台对相同清单可能复用已有快照；同步响应的 `syncId` 可能是先前快照的编号，不能作为本次请求编号的回执。SDK 检查快照编号、项目和接收数量，接纳状态仍须向平台核对。

业务索引写入使用 `indexUpsert`、`indexBatch`、`indexDelete`。回源能力必须声明 `businessIndexResolver: true` 和业务身份，输入为唯一必需的 `request` 对象，包含三个必需字符串 `resourceType`、`resourceId`、`expectedSourceVersion`。宿主查询真实行并验证用户可见性后，以 `BusinessIndexAccessScope.authorizeRow` 创建作用域，再由 `BusinessIndexResolution.createAuthorized` 返回；SDK 会核对已验证用户、租户、项目和请求对象。宿主负责真实数据权限判断。

开发时在此目录执行 `npm ci`、`npm test`、`npm run build`。共享签名向量和隔离测试不能代替真实业务系统与目标部署环境验收。
