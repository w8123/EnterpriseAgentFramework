<template>
  <main class="employee-portal">
    <div class="employee-portal__inner">
      <header class="employee-portal__head">
        <div class="employee-portal__brand">
          <div class="brand-mark">R</div>
          <div>
            <h1>我的 AI 工具</h1>
            <p v-if="me">{{ me.userName || me.account }} · {{ me.systemName }}</p>
          </div>
        </div>
        <LiquidGlassButton v-if="me" source="wallpaper" :loading="signingOut" @click="signOut">退出</LiquidGlassButton>
      </header>

      <LiquidGlassCard v-if="state === 'loading'" class="employee-portal__card">
        <p class="employee-portal__muted">正在打开…</p>
      </LiquidGlassCard>

      <LiquidGlassCard v-else-if="state === 'signedOut'" class="employee-portal__card" data-testid="portal-signed-out">
        <LiquidGlassEmptyState
          title="请从业务系统打开这个页面"
          :description="signedOutReason || '在业务系统的 AI 对话面板里点「在我的 AI 工具中使用」，再点「在浏览器里管理」，不用输入密码就能进来。'"
        />
      </LiquidGlassCard>

      <template v-else>
        <p class="employee-portal__intro">
          在这里管理你接入 Claude Code、Codex 等 AI 工具的连接，看看它们最近替你办了哪些事。你的 AI 工具只能做你在业务系统里本来就有权限做的事。
        </p>

        <LiquidGlassCard class="employee-portal__card employee-portal__card--panel">
          <EmbedAiToolPanel ref="panel" page api-base="/api/employee-portal" :get-token="getToken" />
        </LiquidGlassCard>

        <LiquidGlassCard class="employee-portal__card">
          <div class="employee-portal__section-head">
            <h2>最近的调用</h2>
            <LiquidGlassButton :loading="loadingCalls" @click="loadCalls">刷新</LiquidGlassButton>
          </div>
          <p v-if="callsError" class="employee-portal__error" role="alert">{{ callsError }}</p>
          <p v-else-if="!loadingCalls && !calls.length" class="employee-portal__muted">
            还没有调用记录。接入后，你的 AI 工具每次查询或办理业务都会记在这里。
          </p>
          <ul v-else class="employee-portal__calls">
            <li v-for="call in calls" :key="call.id" data-testid="portal-call">
              <span class="employee-portal__status" :class="call.success ? 'is-ok' : 'is-failed'">
                {{ call.success ? '成功' : '未完成' }}
              </span>
              <div class="employee-portal__call-text">
                <strong>{{ call.toolName || '-' }}</strong>
                <small>{{ call.businessSystem }} · {{ toolLabel(call.toolKind) }}<template v-if="call.latencyMs != null"> · {{ call.latencyMs }} ms</template></small>
              </div>
              <time>{{ formatDateTime(call.createdAt) }}</time>
            </li>
          </ul>
        </LiquidGlassCard>
      </template>
    </div>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState } from '@/components/common/liquid-glass'
import EmbedAiToolPanel from '@/sdk/EmbedAiToolPanel.vue'

interface Me { userName?: string; account: string; projectCode: string; systemName: string }
interface CallView {
  id: number
  toolKind?: string | null
  toolName?: string | null
  businessSystem?: string | null
  success: boolean
  latencyMs?: number | null
  createdAt?: string | null
}

const API = '/api/employee-portal'
const TOKEN_KEY = 'reachai.employee-portal.token'
const toolLabels: Record<string, string> = { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: '其他 AI 工具' }

const state = ref<'loading' | 'signedIn' | 'signedOut'>('loading')
const signedOutReason = ref('')
const me = ref<Me | null>(null)
const calls = ref<CallView[]>([])
const loadingCalls = ref(false)
const callsError = ref('')
const signingOut = ref(false)
let token = ''

class SessionExpired extends Error {}

function readStored(): string {
  try {
    return sessionStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

function store(value: string) {
  try {
    if (value) sessionStorage.setItem(TOKEN_KEY, value)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // The page still works for this tab without storage.
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  const payload = await response.json().catch(() => ({}))
  if (response.status === 401) throw new SessionExpired(payload.message || '登录已过期')
  if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
    throw new Error(payload.message || '请求失败，请稍后重试')
  }
  return payload.data as T
}

function getToken() {
  return Promise.resolve(token)
}

function signedOut(reason = '') {
  token = ''
  store('')
  me.value = null
  signedOutReason.value = reason
  state.value = 'signedOut'
}

/** Takes the one-time ticket out of the address bar so it is never bookmarked or shared. */
function takeTicket(): string {
  const match = window.location.hash.match(/ticket=([0-9a-f]+)/i)
  if (match) {
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
  }
  return match ? match[1] : ''
}

async function signIn() {
  const ticket = takeTicket()
  try {
    if (ticket) {
      const session = await request<{ token: string; me: Me }>('/session', {
        method: 'POST',
        body: JSON.stringify({ ticket }),
      })
      token = session.token
      store(token)
      me.value = session.me
    } else {
      token = readStored()
      if (!token) {
        signedOut()
        return
      }
      me.value = await request<Me>('/me')
    }
    state.value = 'signedIn'
    void loadCalls()
  } catch (failure) {
    signedOut(failure instanceof Error ? failure.message : '')
  }
}

async function loadCalls() {
  loadingCalls.value = true
  callsError.value = ''
  try {
    calls.value = await request<CallView[]>('/calls')
  } catch (failure) {
    if (failure instanceof SessionExpired) {
      signedOut(failure.message)
    } else {
      callsError.value = failure instanceof Error ? failure.message : '调用记录加载失败'
    }
  } finally {
    loadingCalls.value = false
  }
}

async function signOut() {
  signingOut.value = true
  try {
    await request('/session', { method: 'DELETE' })
  } catch {
    // Leaving the page signed out locally is enough.
  } finally {
    signingOut.value = false
    signedOut('已退出。需要时从业务系统的 AI 对话面板重新打开。')
  }
}

function toolLabel(kind?: string | null) {
  return (kind && toolLabels[kind]) || 'AI 工具'
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

onMounted(signIn)
</script>

<style scoped lang="scss">
.employee-portal {
  min-height: 100vh;
  padding: 32px 16px 48px;
  background: var(--surface-page-background);
}

.employee-portal__inner {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: min(760px, 100%);
  margin: 0 auto;
}

.employee-portal__head,
.employee-portal__brand,
.employee-portal__section-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.employee-portal__head,
.employee-portal__section-head {
  justify-content: space-between;
}

.brand-mark {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  color: var(--text-on-brand);
  font-weight: 800;
  background: var(--brand-primary-gradient);
  box-shadow: var(--shadow-active);
}

.employee-portal__brand h1 {
  margin: 0;
  color: var(--text-on-wallpaper, var(--text-primary));
  font-size: 22px;
}

.employee-portal__brand p {
  margin: 2px 0 0;
  color: var(--text-on-wallpaper-secondary, var(--text-secondary));
  font-size: 13px;
}

.employee-portal__intro {
  margin: 0;
  color: var(--text-on-wallpaper-secondary, var(--text-secondary));
  font-size: 13px;
  line-height: 1.7;
}

.employee-portal__card {
  padding: 18px 20px;
}

.employee-portal__card--panel {
  padding: 0;
}

.employee-portal__section-head h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
}

.employee-portal__muted,
.employee-portal__error {
  margin: 12px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.employee-portal__error {
  color: var(--el-color-danger);
}

.employee-portal__calls {
  display: flex;
  flex-direction: column;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.employee-portal__calls li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border-subtle, rgb(127 127 127 / 0.18));
}

.employee-portal__call-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 2px;

  strong {
    overflow: hidden;
    color: var(--text-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}

.employee-portal__calls time {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.employee-portal__status {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;

  &.is-ok {
    color: var(--el-color-success);
    background: color-mix(in srgb, var(--el-color-success) 12%, transparent);
  }

  &.is-failed {
    color: var(--el-color-warning);
    background: color-mix(in srgb, var(--el-color-warning) 12%, transparent);
  }
}

@media (max-width: 560px) {
  .employee-portal__calls time {
    display: none;
  }
}
</style>
