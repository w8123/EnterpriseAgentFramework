# 在业务页面嵌入对话

将已发布的 Agent 放进现有业务页面，员工继续使用原系统的登录身份。对话接入与页面动作可以分别完成，先跑通可信对话，再按页面需要注册动作。

## 前提

完成[系统接入](../integration.md)，准备已发布 Agent、业务系统地址与允许嵌入的来源域名。业务后端需从当前登录态解析真实用户、租户和角色。

## 1. 配置后端身份

实现 `ReachAiEmbedPrincipalResolver`，由业务后端根据登录态返回身份。Starter 的 `POST /reachai/embed/token` 提供短期 Embed Token；浏览器只向这个 broker 请求令牌，不持有项目 appSecret。

调用业务方法时还需要 `ReachAiSecurityContextBridge` 将经过验证的身份还原到业务系统。两者分别负责签发对话身份和执行时的身份衔接，不能用浏览器传入的用户 ID 代替。

## 2. 核对 Agent 嵌入配置

在 Agent 的嵌入配置中选择项目、设置允许来源和令牌策略，再检查 readiness。来源协议、域名和端口要与真实页面一致。

携带 `Origin` 的 Embed Bearer 请求须与令牌中的来源一致；无 `Origin` 的服务端调用仍需通过令牌鉴权。来源头和 CORS 不能代替身份验证，也不能阻止非浏览器客户端伪造来源头。

以平台为这个 Agent 生成的接入包和代码为准；SDK 的参数和类型来自实际构建的声明文件。

本地开发时，管理端开发服务也提供 `/sdk/embed-chat/` 下的浏览器资源。`./dev.sh up` 在资源缺失时构建 SDK；修改 SDK 源码后，在 `ai-admin-front` 重新运行 `npm run build:sdk`。正式构建则将 SDK 一并复制到站点制品。

## 3. 挂载对话

下面展示 SDK 调用结构。先用平台提供的包完成 `createEafChat` 导入，并准备容器元素；令牌响应的业务包装结构按你的 broker 适配。SDK 样式是独立文件，使用打包工具时还需引入 `@reachai/embed-chat/style.css`；直接加载浏览器资源时，须同时引入同一版本的 `style.css` 和 JavaScript，并保留样式引用的相对资源。否则组件可能出现无样式或布局错乱。

```javascript
const chat = await createEafChat({
  agentId: '替换为已发布的 Agent ID',
  mount: '#reachai-chat',
  apiBase: 'https://reachai.example.com',
  tokenProvider: async (context) => {
    const response = await fetch('/reachai/embed/token', {
      method: 'POST',
      credentials: 'include',
      signal: context?.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        agentId: '替换为已发布的 Agent ID',
        pageKey: context?.pageKey,
        pageInstanceId: context?.pageInstanceId,
        route: context?.route,
        origin: context?.origin,
      }),
    })
    if (!response.ok) throw Object.assign(new Error('获取对话令牌失败'), { status: response.status })
    // Starter 返回 { token, expiresIn, sessionHint }；有业务包装时需解包。
    return response.json()
  },
})

// 在宿主组件卸载或用户退出登录时调用。
function disposeChat() {
  chat.destroy()
}
```

保留 broker 的 HTTP 状态，SDK 才能区分登录失效、页面或助手未获授权和服务暂不可用。Starter 对未登录返回 401，对接入拒绝返回 403，对上游故障返回 502；出现 403 时应检查账号权限、允许来源和 Agent 配置。

原样转发 SDK 提供的页面身份，不自己生成备用 `pageInstanceId`。宿主后端要求的 CSRF 或其他认证头也应按原业务系统加入请求。`tokenProvider` 的实际返回类型见 [eafChat.ts](../../ai-admin-front/src/sdk/eafChat.ts)。

对话中可选择“清除上下文”，宿主也可调用 `await chat.clearContext()`。清除成功后当前对话内容从界面移除；长期记忆和审计记录保留。进行中的消息或待处理交互结束后才能清除，失败时界面保留原对话。该能力需要同时升级 Control、Runtime 和 SDK；网络中断时结果可能不确定，应先检查当前状态再决定是否重试。

宿主退出登录后应立即销毁对话并停止续签。接入方可以让服务端把每个业务登录会话的高熵、不复用的不可逆标识作为 `ReachAiEmbedPrincipal` 的第六个参数 `businessSessionId`，并在会话失效时通过 `ReachAiEmbedTokenClient.revokeBusinessSession(businessSessionId)` 发起项目签名通知。成功后，该项目和业务会话绑定的已签发及后续换发令牌都会被拒绝；其他业务会话不受影响。该标识必须由业务服务端从可信登录态取得，不能使用浏览器字段，也不能直接使用访问令牌或刷新令牌。宿主必须可靠处理通知失败和并发退出，不能把请求发出当作撤销成功。

绑定登录会话的 Embed Token 只能续用同一业务登录会话创建的对话。旧对话没有可信登录会话归属，升级不会自动关联；启用绑定后应创建新对话，不能用新令牌接管旧对话。未绑定令牌和未绑定旧对话仍按原有用户、项目等约束工作，接入方应逐步退出旧方案。

第七个参数 `businessSessionExpiresAt` 可传入业务后端验证过的访问令牌到期时间（Unix 秒）；ReachAI 会取该时间和项目令牌有效期的较早值，拒绝已过期的登录态。业务后端与 ReachAI 应保持时钟同步。新版 Starter 要求 Control 显式确认会话绑定及到期限制：启用这些参数时，旧版 Control 的响应会被拒绝，升级时应先更新 Control 和 Capability，再更新业务后端。

撤销只影响之后的令牌校验，不回滚已执行操作，也不保证中止已经通过校验的在途请求；撤销记录目前不自动清理。未绑定 `businessSessionId` 的旧令牌、未接入服务端退出通知的宿主仍按原有效期失效。部署时应按风险设置较短的令牌有效期，不要把令牌写入日志或持久化存储。

销毁会取消尚未执行的页面动作确认与认领，并中止 SDK 的相关请求；宿主处理器已经开始的操作无法由 SDK 撤回，业务系统应按自身规则处理结果不确定的操作。

## 4. 按需要增加页面动作

用 `createEafPageBridge()` 显式注册当前页面允许的读取、筛选、导航或表单动作，再将 bridge 与页面信息传给对话。业务写入仍经过后端业务方法或 API。

当平台发起跨页面导航并触发 bridge 的 `onNavigate` 时，等目标页面挂载并注册动作，再使用 SDK 的 `rebindPage()` 完成绑定。普通的用户主动路由跳转不能直接调用 `rebindPage()`；如需切换页面动作，当前应销毁旧实例并为目标页面创建新实例。页面动作契约见 [eafPageBridge.ts](../../ai-admin-front/src/sdk/eafPageBridge.ts)。

新版 SDK 的跨页面重绑定依赖 Control 的 `rebind-v2` 幂等协议；升级时先升级 Control，再更新业务页面 SDK。旧版 Control 不支持该协议时，跨页面重绑定会失败并保留错误，不会自动降级到不具备相同重试保障的接口。

## 5. 验证接入

测试正常登录、令牌过期、退出再登录、不同用户与跨项目访问、错误 Origin、页面切换与组件重复挂载。确认旧实例已销毁、身份未串用，写操作遵循当前确认策略。

完整身份约束见[系统接入](../integration.md#嵌入业务页面)。带实际后端和前端补丁的例子见[芋道 ERP 接入](芋道ERP接入ReachAI实施步骤.md)。
