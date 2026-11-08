# Java 接入：Spring Boot Starter

> 适合：Java 开发者。读完你会在 Spring Boot 业务系统里引入 Starter，用注解声明一个业务方法，并在 ReachAI 里看到它。

## 做完你会得到什么

业务系统启动后自动注册到 ReachAI，你用 `@ReachCapability` 标出来的方法会出现在「业务能力」里，AI 可以在员工允许的范围内调用它。

## 开始前你需要

- 已经在 ReachAI 建好项目，拿到项目编码和接入令牌，见 [接入第一个业务系统](connect-project.md)。
- Spring Boot 2.7 或 3.x，Java 8 及以上。Starter 同时带有 Boot 2 和 Boot 3 的自动配置。
  - 目前真实项目验证过的是 Spring Boot 2.7（芋道 ERP）；Boot 3 还没有在真实项目上验证过。
  - Spring Boot 2.7 以下的版本不支持。

## 第 1 步：引入依赖

在业务系统的启动模块引入 Starter：

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

写注解的业务模块（比如 `order-module`）另外引入 Capability SDK：

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-capability-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> 实际版本和下载地址以项目接入工作台给出的为准。Starter 还没有发布到 Maven 中央仓库，工作台会提供下载和安装到本地仓库的命令。

## 第 2 步：写配置

在 `application.yaml` 里加：

```yaml
reachai:
  registry:
    url: http://reachai.example.com          # ReachAI 地址
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN}   # 第一次的接入令牌
    credential-store-path: /opt/order/data/reachai-credentials.properties  # 长期凭据保存位置
  project:
    code: order-service                        # 项目编码，和平台上一致
    name: 订单系统
    base-url: http://192.168.1.20:8080         # 业务系统后端地址，ReachAI 回调用
  capability:
    scan-mode: ANNOTATED_ONLY                  # 只扫描带 @ReachCapability 的方法
    scan-packages:
      - com.company.order                      # 扫描范围，越小越好
```

令牌不要写死在配置文件里提交到 Git，用环境变量传进去。

第一次启动注册成功后，Starter 把长期凭据写进 `credential-store-path` 指定的文件，之后就不再需要令牌。凭据文件放在应用包外面（容器部署时放在持久卷里），只让运行业务系统的账号能读。

## 第 3 步：声明业务方法

在 Spring Bean 的方法上加 `@ReachCapability`：

```java
@Service
public class OrderQueryService {

    @ReachCapability(
        name = "order.query",
        title = "查询订单",
        description = "按订单号查询当前用户可见的订单，返回状态、金额和下单时间",
        sideEffect = ReachSideEffectLevel.READ,
        businessIdentityRequired = true)
    public OrderView query(@ReachParam(name = "orderId", required = true, description = "订单号") String orderId) {
        return loadAuthorizedOrder(orderId);
    }
}
```

几个要点：

| 属性 | 怎么填 |
| --- | --- |
| `name` | 全局唯一。同一个系统里重名，SDK 会拒绝同步 |
| `title` / `description` | 写给 AI 看的。说清楚能查什么、怎么过滤、返回什么，AI 才知道什么时候该用它 |
| `sideEffect` | `READ` 只读；`WRITE` 会改数据（嵌入助手里要用户确认，员工 MCP 里要审批）；`IRREVERSIBLE` 做了收不回（默认不让 AI 调） |
| `businessIdentityRequired` | 方法里要用到「当前登录用户」就设成 `true`。平台会拒绝没有员工身份的调用 |

参数和返回值要用明确的类型（DTO、基本类型、List），SDK 据此生成输入输出结构。返回值是统一包装（比如 `CommonResult<T>`）时，AI 看到的字段路径会带上包装层，例如 `data.list`。

> 先开放几个只读查询，跑通了再开放写操作。

## 第 4 步：接身份桥

声明了 `businessIdentityRequired = true` 的方法，需要你实现 `ReachAiSecurityContextBridge`，把平台传来的员工身份还原成业务系统的登录用户。这一步决定了 AI 能看到哪些数据，单独一篇讲：[业务身份桥](business-identity.md)。

同时要在业务系统的登录拦截里放行 `/reachai/**`（Starter 自己验签）。

## 第 5 步：启动并核对

启动业务系统，日志里会看到注册和同步成功的信息。然后回 ReachAI：

1. 项目接入工作台里「后端 SDK」「业务服务校验」两项变绿。
2. 左侧「业务能力」里能看到 `order.query`，见 [核对业务能力](capabilities.md)。

## 背后发生了什么

- 启动时 Starter 扫描 `scan-packages` 下带注解的方法，生成每个方法的输入输出结构，签名后同步给平台。平台记下来源版本，方法签名变了会提示「来源变化」，要你确认后才生效。
- 平台调用时，请求带着签名和一个短期调用令牌（里面有员工身份）到 `/reachai/**`。Starter 验签、验令牌，交给你的身份桥建立登录上下文，再执行方法。方法里原有的数据权限和事务照常生效。

## 遇到问题

**同步后业务能力里看不到方法。** 检查 `scan-packages` 是否包含方法所在包，方法所在类是否是 Spring Bean。

**更新了本地 SNAPSHOT 的 Starter，打包后没生效。** Maven 可能复用了旧的可执行包。用 `mvn -pl <启动模块> -am '-Dmaven.jar.forceCreation=true' package` 强制重新打包。

**调用返回 401 / 403。** 先看 [业务身份桥](business-identity.md) 是否接好，再看 [开发者常见问题](troubleshooting.md)。

参考：[Starter 说明](../../reachai-spring-boot2-starter/README.md)、[Capability SDK 说明](../../reachai-capability-sdk/README.md)、[SDK 接入协议](../contracts/sdk-protocol.md)。

## 下一步

- [业务身份桥](business-identity.md)
- [核对业务能力](capabilities.md)
