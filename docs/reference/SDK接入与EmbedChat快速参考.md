# SDK 接入与 Embed Chat 快速参考

业务前端使用 `@reachai/embed-chat` 的 `createEafChat()` 挂载一个全局
对话入口；业务后端从当前登录态换取短期 Embed Token。SDK 的完整可发布
README 随 npm tarball 分发，AI Coding 接入则随 `reachai-onboarding` Skill
分发，入口为
`references/embed-chat-quick-reference.md`。

Java 侧的精确注解成员、`ReachAiEmbedTokenClient`、
`ReachAiEmbedTokenRequest` 与 `ReachAiEmbedPrincipal` 构造签名在 Skill 的
`references/java-sdk-api-reference.md` 中。仓库内入口为
[Capability SDK README](../../reachai-capability-sdk/README.md) 和
[Spring Boot Starter README](../../reachai-spring-boot2-starter/README.md)。

最小调用需要 `mount`、已 provision 的 `agentId` 与 `tokenProvider`。后者
会收到 `pageKey`、`pageInstanceId`、`route`、`origin`，必须原样转发给业务
Token Broker。不要在浏览器保存 appSecret、AI Coding Key 或业务登录 Token。

认证边界如下：

- `/api/reachai/embed-token`：正常业务登录态，业务后端调用 Starter 的
  `ReachAiEmbedTokenClient`。
- `/api/reachai/embed/**`：转发 `Authorization: Bearer <embedToken>` 到
  ReachAI；不能交给业务 OAuth2/JWT Resource Server 解析。

Spring Cloud Gateway、Nginx 与 Kong 的推荐说明在 Skill 的
`references/gateway-examples.md`，可直接复用的完整文件在
`examples/gateway/`。项目接入时应优先读取 task context 中的
`requiredResources`，而不是从 tarball 的 `index.d.ts` 逆向接口。

真实 E2E 需要一个已有的业务测试登录会话。ReachAI 只观察当前任务之后的
Embed Session、用户消息和助手回复，不会伪造业务 OAuth2 身份。

若业务系统能提供专用测试账号的非交互授权，可把完整 Authorization 值或
Cookie 放入本机环境变量，再由 `reachai-doctor --mode e2e` 走真实 Token
Broker、Embed 代理、Session 和 Message 链路。该检查只证明授权对话协议；它不会
猜测业务能力参数、调用任意业务 API 或伪造浏览器页面回调。能力调用和页面动作必须
在“页面接入工作台”基于精确 Workflow Trace 与真实业务页面分别验收。密钥值不得出现
在命令行、日志或 Artifact 中；页面入口的可见性与交互体验仍由最终人工验收确认。
