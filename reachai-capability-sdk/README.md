# Capability SDK

Java 8 兼容的业务能力声明契约，不依赖 Spring 运行时。业务模块用它声明方法、参数、返回与业务索引 resolver；启动模块使用 [Starter](../reachai-spring-boot2-starter/README.md)。

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-capability-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

部署使用平台 onboarding manifest 给出的版本、安装命令和摘要，不猜测制品仓库地址。

| 契约 | 用途 |
| --- | --- |
| `@ReachCapability` | 明确开放的 Java 方法、说明、副作用与调用要求 |
| `@ReachParam` / `@ReachOutput` | 输入与返回字段的可消费契约 |
| `ReachSideEffectLevel` | 声明真实业务操作影响 |
| `bizindex` | 带版本业务引用、回源解析与权限约束 |

注解不授予业务权限，最终校验和事务仍由业务系统负责。声明、注册、试调用和页面接入的完整顺序见[系统接入](../docs/integration.md)，方法/API 的差异与发布保护见[Workflow](../docs/workflow.md)。
