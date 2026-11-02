# 系统接入

目标是让平台发现并受控调用业务系统明确开放的操作，同时沿用原系统的身份、权限和事务。SDK 接入、页面嵌入和员工工具连接分别验证，不用注册成功代替真实业务调用成功。

## Java 系统

1. 在业务启动模块引入 [Starter](../reachai-spring-boot2-starter/README.md)，声明注解的业务模块引入 [Capability SDK](../reachai-capability-sdk/README.md)。使用平台 onboarding manifest 给出的版本与制品。
2. 在平台创建项目，取得一次性 Enrollment Token；配置项目、注册地址和扫描范围。
3. 在 Spring Bean 上声明允许调用的业务方法，并为参数、返回类型和副作用提供明确契约。
4. 启动业务实例并同步来源，在平台核对身份、来源、输入输出和变化接纳结果。
5. 配置业务身份桥与实际权限，完成受控试调用，再进入 Workflow。

```yaml
reachai:
  registry:
    url: https://reachai.example.com
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN}
  project:
    code: order-service
    name: 订单系统
    base-url: https://orders.example.com
  capability:
    scan-mode: ANNOTATED_ONLY
    scan-packages:
      - com.company.order
```

注册完成后 Starter 将项目凭据原子写入受保护的 credential store，不持久化 Enrollment Token。凭据文件应位于应用包之外，浏览器不持有 appSecret。已配置静态凭据的系统按 Starter 当前配置契约使用，不混用不同项目身份。

```java
@ReachCapability(name = "order.query", title = "查询订单",
    description = "查询当前业务用户可见的订单", sideEffect = ReachSideEffectLevel.READ,
    businessIdentityRequired = true)
public OrderView query(@ReachParam(name = "orderId", required = true) String orderId) {
    return loadAuthorizedOrder(orderId);
}
```

业务方法与 HTTP API 是两种契约，不能按是否走 HTTP 或 `source=sdk` 猜测类型。同一 Java 实现同时声明两种入口时保留各自来源和身份；响应 schema 保留实际业务包装，例如 `{success,data:{count}}` 的字段路径是 `data.count`。

同一业务进程中的 `@ReachCapability` 名称必须唯一，包括省略 `name` 时使用的方法名。发现重名时 SDK 会拒绝同步与调用，修正声明后再重新同步，避免将一个名称绑定到错误的实现。

## 没有 SDK 的系统

改不了代码、或不是 Java 的系统，在「项目管理 → 新建项目 → OpenAPI 导入」上传 Swagger / OpenAPI 导出的 `.json`、`.yaml` 或 `.yml` 文档（最大 5MB）。文档内容保存在平台数据库，之后在项目来源的「同步与发现」里重新导入即可刷新接口；更换文档在项目详情「编辑」里重新上传。

平台不读取服务器上的源码或文件目录，也不再提供 Controller 源码扫描。Java / Spring 系统用 Starter 发现 Spring MVC 接口，可以拿到完整的参数和响应结构。旧库升级见 [upgrade-20261028-remove-source-code-scan.sql](../sql/upgrade-20261028-remove-source-code-scan.sql)。

## 业务身份

Starter 验证调用签名与令牌，再通过 `ReachAiSecurityContextBridge` 还原业务用户、租户和角色。业务系统保留最终数据权限与事务校验；不能把平台账号、浏览器提交的用户 ID 或缺失身份自动映射成业务管理员。

业务方法即使不要求特定角色，只要依赖当前业务用户，也应声明 `businessIdentityRequired = true`。平台会拒绝无签名用户身份的调用以及控制台项目身份试调用；声明不能替代业务系统对用户、租户和数据权限的验证。新增或修改声明会改变能力契约，需重新同步并评审。

Registry 心跳、下线和同步回调按项目签名；来源可发现不代表已接纳或可执行。来源变化、试调用和发布保护见[Workflow](workflow.md)。

## 嵌入业务页面

1. 业务后端实现 `ReachAiEmbedPrincipalResolver`，从当前登录态解析用户、租户和角色；Starter 提供 `POST /reachai/embed/token`。
2. 在 Agent 的嵌入配置中设置允许的项目、来源域名和令牌策略，核对 readiness。
3. 前端按平台生成的接入代码使用 `createEafChat()`；令牌由后端 broker 提供，不能在前端签发。
4. 用 `createEafPageBridge()` 注册当前页面允许的读取、筛选、导航和表单动作。动作契约与页面上下文传给平台，业务数据写入仍走业务方法或 API。

Embed Token、会话、项目、用户与 Origin 必须一致。Starter 的令牌交换以项目凭据签名完整请求正文并防重放；Control、Capability 和 Starter 需要一起升级，旧版仅签名项目标识的令牌交换请求会被拒绝。业务身份中的 `attributes.tenantId` 由业务后端从已验证登录态提供，未提供时使用 `default`；浏览器请求体不能指定可信身份。页面注册使用 Embed Token。跨页面动作遵循 `NAVIGATE → TARGET_READY → PAGE_ACTION`，只在目标页面就绪后执行；动作要求确认时，跳转和目标动作分别需要用户确认，取消或超时不会继续执行后续阶段。

Starter 的平台注册地址应直接指向目标服务，不能依靠 HTTP 重定向；带项目签名的请求遇到重定向时会失败，避免将请求凭据发送到其他地址。Starter 接收的单次响应上限为 8 MiB；HTTP 错误只保留状态码，不记录上游错误正文。

SDK 对话主题隔离于宿主页面。参数与导出类型以 [eafChat.ts](../ai-admin-front/src/sdk/eafChat.ts)、[eafPageBridge.ts](../ai-admin-front/src/sdk/eafPageBridge.ts)及生成的类型声明为准。

## 员工工具与样例

管理员在 MCP 服务中开通员工使用；员工从业务系统面板生成个人连接，再复制生成的配置到自己的 AI 工具。身份、写审批与审计规则见[开放协议](protocols.md)。

- [芋道 ERP 接入](guides/芋道ERP接入ReachAI实施步骤.md)：后端身份桥、页面补丁与验收。
- [员工使用自己的 AI 工具](guides/用Claude%20Code操作芋道.md)：生成连接、写审批、Skill 下载与断开。

验收覆盖签名拒绝、非白名单来源、跨项目/用户隔离、只读调用、写确认、契约变化与运行回查。当前环境的检查条件见[运维](operations.md)。
