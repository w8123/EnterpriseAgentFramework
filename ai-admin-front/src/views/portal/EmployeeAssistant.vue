<template>
  <div class="eaf-chat employee-assistant" data-testid="employee-assistant">
    <EmbedChatHost
      api-base="/api/embed"
      :token-provider="provideToken"
      :bridge="bridge"
      :memory-panel="false"
      :ai-tool-panel="false"
      placeholder="说说要办的事，例如：帮我查一下最近的库存"
    />
  </div>
</template>

<script setup lang="ts">
import EmbedChatHost from '@/sdk/EmbedChatHost.vue'
import { createEafPageBridge } from '@/sdk/eafPageBridge'
import type { EafChatError, EafChatTokenProviderContext, EafChatTokenResult } from '@/sdk/eafChat'
import '@/sdk/style.css'

/**
 * 员工个人页 · 我的助手：全屏和业务系统里的同一个 AI 助手对话。
 * 对话令牌由个人页登录换取，身份和 Agent 与业务系统里的对话面板相同；这里没有业务页面，所以不登记页面动作。
 */
const props = defineProps<{
  getToken: () => Promise<string>
}>()

const bridge = createEafPageBridge({ route: '/my/ai-tools' })

interface AssistantToken { token: string; expiresIn: number; expiresAt: string; agentId: string }

function failure(message: string, httpStatus: number, retryable: boolean): Error & EafChatError {
  const error = new Error(message) as Error & EafChatError
  error.name = 'EafChatTokenError'
  error.code = 'PORTAL_ASSISTANT_TOKEN'
  error.phase = 'token'
  error.retryable = retryable
  error.httpStatus = httpStatus
  return error
}

async function provideToken(context?: EafChatTokenProviderContext): Promise<EafChatTokenResult> {
  const portalToken = await props.getToken()
  const response = await fetch('/api/employee-portal/assistant/token', {
    method: 'POST',
    signal: context?.signal,
    headers: {
      'Content-Type': 'application/json',
      ...(portalToken ? { Authorization: `Bearer ${portalToken}` } : {}),
    },
    body: JSON.stringify({ pageInstanceId: context?.pageInstanceId || bridge.pageInstanceId }),
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || !payload.data?.token) {
    const status = response.ok ? 500 : response.status
    // 401 / 409 只能回到业务系统重新打开，重试没有用。
    throw failure(payload.message || '暂时无法连接 AI 助手，请稍后重试', status, status >= 500)
  }
  const data = payload.data as AssistantToken
  return { token: data.token, expiresIn: data.expiresIn }
}
</script>

<style scoped>
.employee-assistant {
  height: min(72vh, 760px);
  min-height: 420px;
  border-radius: 16px;
  overflow: hidden;
}
</style>
