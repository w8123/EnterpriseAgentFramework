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
    description = "查询当前业务用户可见的订单", sideEffect = ReachSideEffectLevel.READ)
public OrderView query(@ReachParam(name = "orderId", required = true) String orderId) {
    return loadAuthorizedOrder(orderId);
}
```

业务方法与 HTTP API 是两种契约，不能按是否走 HTTP 或 `source=sdk` 猜测类型。同一 Java 实现同时声明两种入口时保留各自来源和身份；响应 schema 保留实际业务包装，例如 `{success,data:{count}}` 的字段路径是 `data.count`。

## 业务身份

Starter 验证调用签名与令牌，再通过 `ReachAiSecurityContextBridge` 还原业务用户、租户和角色。业务系统保留最终数据权限与事务校验；不能把平台账号、浏览器提交的用户 ID 或缺失身份自动映射成业务管理员。

Registry 心跳、下线和同步回调按项目签名；来源可发现不代表已接纳或可执行。来源变化、试调用和发布保护见[Workflow](workflow.md)。

## 嵌入业务页面

1. 业务后端实现 `ReachAiEmbedPrincipalResolver`，从当前登录态解析用户、租户和角色；Starter 提供 `POST /reachai/embed/token`。
2. 在 Agent 的嵌入配置中设置允许的项目、来源域名和令牌策略，核对 readiness。
3. 前端按平台生成的接入代码使用 `createEafChat()`；令牌由后端 broker 提供，不能在前端签发。
4. 用 `createEafPageBridge()` 注册当前页面允许的读取、筛选、导航和表单动作。动作契约与页面上下文传给平台，业务数据写入仍走业务方法或 API。

Embed Token、会话、项目、用户与 Origin 必须一致。页面注册使用 Embed Token；请求体中的 principal 不构成可信身份。跨页面动作遵循 `NAVIGATE → TARGET_READY → PAGE_ACTION`，只在目标页面就绪后执行，并保留取消、超时与一次确认语义。

SDK 对话主题隔离于宿主页面。参数与导出类型以 [eafChat.ts](../ai-admin-front/src/sdk/eafChat.ts)、[eafPageBridge.ts](../ai-admin-front/src/sdk/eafPageBridge.ts)及生成的类型声明为准。

## 员工工具与样例

管理员在 MCP 服务中开通员工使用；员工从业务系统面板生成个人连接，再复制生成的配置到自己的 AI 工具。身份、写审批与审计规则见[开放协议](protocols.md)。

- [芋道 ERP 接入](guides/芋道ERP接入ReachAI实施步骤.md)：后端身份桥、页面补丁与验收。
- [员工使用自己的 AI 工具](guides/用Claude%20Code操作芋道.md)：生成连接、写审批、Skill 下载与断开。

验收覆盖签名拒绝、非白名单来源、跨项目/用户隔离、只读调用、写确认、契约变化与运行回查。当前环境的检查条件见[运维](operations.md)。
