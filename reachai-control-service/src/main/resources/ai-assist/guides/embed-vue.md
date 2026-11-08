# Vue 3 页面接入嵌入对话

> 验证情况：已在芋道 ERP（Vue 3 + Element Plus）真实验证。

## 适用范围

Vue 3（Vite 或 Webpack 构建）。Vue 2 请用「script 标签接入」那篇。

## 准备

1. 后端已接好 ReachAI SDK，并提供**嵌入换票接口**：Java Starter 默认 `POST /reachai/embed/token`（经网关时常见为 `/api/reachai/embed-token`），C# / Python / Node / Go SDK 默认 `POST /reachai/embed/token`。这个接口必须处在业务系统正常登录保护之下，浏览器只拿短期 Embed Token，**不持有项目密钥**。
2. 在 ReachAI 里有一个已发布的智能体，并在它的「嵌入配置」里把业务系统前端的来源（协议 + 域名 + 端口，例如 `https://erp.example.com`）加入允许来源。记下智能体的 Agent ID（keySlug）。
3. 取得浏览器 SDK `@reachai/embed-chat`：读取项目接入清单（onboarding manifest）的 `sdkArtifacts`，用 reachai-onboarding Skill 里的 `scripts/install-embed-chat.mjs` 安装到业务仓库的 `vendor/reachai/`，它会校验 SHA-256。不要猜 npm 源，也不要从 ReachAI 的其他路径下载。

ReachAI 地址：`{{reachaiUrl}}`（如果和你连接 MCP 用的地址不同，以 MCP 地址为准）。项目编码：`{{projectCode}}`。

## 步骤

1. 在登录后的主框架组件（如 `layout/index.vue`）里挂载一次，不要放到每个页面里：

```vue
<template>
  <div id="reachai-chat" />
</template>

<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import { createEafChat } from '@reachai/embed-chat'
import '@reachai/embed-chat/style.css'

let chat
async function reachAiToken(context) {
  const response = await fetch('/reachai/embed/token', {   // 换成你的换票接口
    method: 'POST',
    credentials: 'include',
    signal: context?.signal,
    headers: { 'Content-Type': 'application/json' },        // 业务系统要求的 CSRF 头也加在这里
    body: JSON.stringify({
      pageKey: context?.pageKey,
      pageInstanceId: context?.pageInstanceId,              // 原样转发，不要自己生成
      route: context?.route,
      origin: context?.origin,
    }),
  })
  if (!response.ok) throw Object.assign(new Error('获取对话令牌失败'), { status: response.status })
  const payload = await response.json()
  const data = payload.data ?? payload                      // 业务系统有统一包装时在这里解包
  return { token: data.token, expiresIn: data.expiresIn }
}

onMounted(async () => {
  chat = await createEafChat({
    mount: '#reachai-chat',
    agentId: '替换为 Agent ID',
    apiBase: '{{reachaiUrl}}',
    position: 'bottom-right',
    tokenProvider: reachAiToken,
  })
})

onBeforeUnmount(() => chat?.destroy())
</script>
```

2. 退出登录的逻辑里先调用 `chat.destroy()` 再跳转。
3. 需要让 AI 操作当前页面时，用 `createEafPageBridge()` 注册页面动作，把 `bridge` 和 `page: { pageKey }` 传给 `createEafChat`；页面动作的约定见业务页面工作台。

## 自检

- [ ] 登录后每个页面只出现一个对话按钮；登录页、退出页、OAuth 回调页不出现。
- [ ] 打开对话能收到回复；浏览器 Network 里换票接口返回 200，没有 401 / 403。
- [ ] 退出登录后对话被销毁，再用另一个账号登录，看不到上一个人的对话。
- [ ] 来源不在白名单的页面打开时被拒绝（403）。
- [ ] 让 AI 调用 `reachai_doctor`（projectCode="{{projectCode}}"），「嵌入对话接入策略」一项为 PASS。

## 常见问题

- **403**：来源不在智能体允许来源里，或这个账号没有该智能体 / 页面的权限。
- **401**：换票接口没带上业务登录态（检查 `credentials: 'include'`、Cookie 域、CSRF 头）。
- **没有样式或布局错乱**：没有引入同一版本的 `style.css`，或打包时丢了它引用的图片资源。
- **pageInstanceId 不一致**：不要自己生成，SDK、换票接口、会话和 Page Bridge 必须用同一个。
