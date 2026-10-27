# Spring Boot Starter

Java 8 兼容的业务系统接入实现，提供项目/实例注册、心跳、能力发现与同步回调、调用验签、业务身份桥和嵌入令牌 broker。包含 Boot 2 `spring.factories` 与 Boot 3 `AutoConfiguration.imports` 元数据；与具体业务应用的兼容性通过目标构建验证。

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

声明注解的业务模块另依赖 [Capability SDK](../reachai-capability-sdk/README.md)。实际版本和安装参数取平台 onboarding manifest。

## 配置与注册

配置 `reachai.registry.url`、首次 Enrollment Token、项目 code / base-url 与能力扫描范围，示例见[系统接入](../docs/integration.md)。成功注册后将项目凭据原子写入 credential store，不落盘 Enrollment Token；凭据文件位于应用包外并限制权限。

本地凭据保存失败时恢复目录权限，让现有注册流程重试；响应完全丢失或进程在保存前退出时，由有权限的操作人核对项目凭据恢复，不重复复制未知身份或把凭据写入源码。静态凭据与 enrollment 的精确行为以当前配置类型和 Starter 实现为准。

## 业务扩展

- `ReachAiSecurityContextBridge`：将已验证调用身份还原为业务系统的用户、租户与角色。
- `ReachAiEmbedPrincipalResolver`：从业务登录态解析身份，配合 `POST /reachai/embed/token`。
- SDK `bizindex` resolver：按当前身份回源读取业务对象，不把索引文本当作授权。

只放行由 Starter 验签保护的接入路径，不移除业务系统的最终数据权限。主流程与验收见[接入](../docs/integration.md)，身份规则见[安全](../docs/security.md)。
