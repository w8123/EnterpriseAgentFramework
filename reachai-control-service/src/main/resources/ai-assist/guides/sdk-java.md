# Java（Spring Boot 2.7 / 3）后端接入

> 验证情况：Spring Boot 2.7 已在芋道 ERP 真实验证；Spring Boot 3 按 Starter 元数据支持，尚未在真实项目验证。

## 适用范围

Spring Boot 2.7.x 或 3.x，Java 8+（Boot 3 需要 Java 17+）。2.7 以下（如 2.1.x）不支持。

## 准备

1. 调用 `reachai_onboarding_resources` 获取所有语言的 `sdkArtifacts` 和 `skillPackageUrl`，选择当前语言制品。注册地址使用 AI 所连接的 ReachAI 平台根地址 `{{reachaiUrl}}`，从业务服务确认该地址可达。首次接入先确认项目编码「{{projectCode}}」，用 `reachai_onboarding_enrollment_request` 申请并由本人在「AI 操作审核」批准，再用 `reachai_onboarding_enrollment_execute` 获取一次性令牌。注册成功后项目出现在工作台。
2. 所有密钥（Enrollment Token、AppKey / AppSecret、凭据加密密钥）只放环境变量或密钥管理器，不写进代码、配置文件、日志和浏览器。
3. ReachAI 的制品地址不是 Maven / NuGet / npm / pip / Go 模块仓库，按清单里的 `downloadUrl` 下载并校验 `integritySha256`，不要猜仓库地址。

## 步骤

1. 按清单 `sdkArtifacts` 安装 `reachai-capability-sdk` 和 `reachai-spring-boot2-starter`（reachai-onboarding Skill 的安装脚本会校验 JAR 和 POM 的摘要后 `mvn install:install-file`）。
2. 在可运行的 Spring Boot 模块加 Starter；在声明 `@ReachCapability` 的每个模块加 `reachai-capability-sdk`：

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>${reachai.sdk.version}</version>
</dependency>
```

3. `application.yml` 写非敏感配置，密钥用环境变量（完整模板见 reachai-onboarding Skill 的 `templates/application-reachai.yml`）：

```yaml
reachai:
  registry:
    url: ${REACHAI_REGISTRY_URL}           # 连接 ReachAI 的平台根地址
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN:}
    credential-store-path: ${REACHAI_REGISTRY_CREDENTIAL_STORE_PATH}
  project:
    code: {{projectCode}}
    name: 业务系统名称
    base-url: ${REACHAI_PROJECT_BASE_URL}   # ReachAI 服务器能访问到的业务系统地址
    environment: ${REACHAI_PROJECT_ENV:dev}
  capability:
    scan-mode: ANNOTATED_ONLY              # 只登记标了 @ReachCapability 的方法
    scan-packages:
      - com.example.business               # 换成业务包名
```

首次注册前通过环境变量提供 `REACHAI_REGISTRY_ENROLLMENT_TOKEN`，并将 `REACHAI_REGISTRY_CREDENTIAL_STORE_PATH` 设为应用包外的受保护持久路径。注册后的凭据会写入该路径供重启使用。已有项目也可按既有受保护配置提供 AppKey 和 AppSecret。

4. 在 Service 方法上用 `@ReachCapability` 写清中文描述、读写类型、是否需要业务身份，参数用 `@ReachParam`。只开放你确定要给 AI 用的方法。
5. 实现 `ReachAiEmbedPrincipalResolver`（从当前登录态返回用户、租户、角色）和 `ReachAiSecurityContextBridge`（调用业务方法前恢复业务身份）。不要信任浏览器传来的用户 ID。
6. 退出登录时调用 `ReachAiEmbedTokenClient.revokeBusinessSession(...)`，并处理失败重试。
7. 详细注解和配置项见 reachai-onboarding Skill 的 `references/java-sdk-access.md`、`java-sdk-api-reference.md`。

## 自检

- [ ] 业务系统启动日志里能看到注册成功，ReachAI 项目详情里实例在线。
- [ ] 在 ReachAI 里触发一次 SDK 同步（项目接入工作台，或让 AI 用 `reachai_ai_coding_task_verify` 做 SDK_SYNC），业务方法出现在能力目录里。
- [ ] 用一个只读业务方法试调用成功，结果和业务系统页面上一致。
- [ ] 换一个权限更低的账号，看不到 / 调不了没有权限的数据。
- [ ] 让 AI 调用 `reachai_doctor`（projectCode="{{projectCode}}"），「业务系统 SDK 实例」为 PASS。

## 常见问题

- **注册失败 401 / 403**：注册凭据不对或已用过；一次性 Enrollment Token 只能用一次，注册结果不确定时不要自动重用。
- **ReachAI 调不到业务系统**：`BaseUrl` 必须是 ReachAI 服务器能访问到的地址，经网关时转发 `/reachai/**` 并保留签名请求头。
- **能力没出现**：首次启动只注册和心跳，不会自动同步；要在 ReachAI 里明确触发 SDK 同步。
- **普通 HTTP 接口**：不是 SDK 能力的接口用 HTTP API 导入（OpenAPI / curl），不要伪装成 SDK 能力。
