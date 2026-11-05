<template>
  <main class="employee-portal">
    <div class="employee-portal__inner">
      <header class="employee-portal__head">
        <div class="employee-portal__brand">
          <div class="brand-mark">R</div>
          <div>
            <h1>AI 工具接入</h1>
            <p v-if="me">{{ me.userName || me.account }} · {{ me.systemName }}</p>
          </div>
        </div>
        <div v-if="me" class="employee-portal__head-actions">
          <a class="employee-portal__workbench-link" href="/me?connect=business">在工作台中使用</a>
          <LiquidGlassButton source="wallpaper" :loading="signingOut" @click="signOut">退出</LiquidGlassButton>
        </div>
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
          把 Claude Code、Codex 等 AI 工具接进来，让它以你的身份使用已开放的业务能力；业务写操作按管理员设置审批。连接还允许 AI 工具读取、保存和删除你的个人记忆；连接时会发送你的相关偏好，记忆操作不经过业务写审批。
        </p>

        <AiAccessStatusBar :items="statusItems" />

        <nav class="employee-portal__tabs" role="tablist" aria-label="AI 工具接入">
          <button
            v-for="item in tabs"
            :key="item.key"
            type="button"
            role="tab"
            class="employee-portal__tab"
            :class="{ active: tab === item.key }"
            :aria-selected="tab === item.key"
            :data-portal-tab="item.key"
            @click="tab = item.key"
          >{{ item.label }}<span v-if="item.key === 'approvals' && pendingApprovals" class="employee-portal__count">{{ pendingApprovals }}</span></button>
        </nav>

        <div v-show="tab === 'setup'">
          <AiAccessSetup
            v-model:tool-kind="toolKind"
            scope-title="勾选要让它操作的系统"
            scope-hint="可以同时接入多个系统，权限和你在各系统里一样。"
            :prompt="installPrompt"
            :creating="creating"
            :can-create="picked.length > 0"
            :disabled-reason="systems.length ? '请至少勾选一个系统' : ''"
            :error="createError"
            :try-phrases="tryPhrases"
            @create="createCredential"
            @done="installPrompt = null"
          >
            <template #scope>
              <p v-if="!systemsLoading && !systems.length" class="employee-portal__muted">暂时没有可以接入的系统，请联系管理员开通。</p>
              <label v-for="system in systems" :key="system.projectCode" class="employee-portal__check">
                <input v-model="picked" type="checkbox" :value="system.projectCode" :disabled="!!installPrompt">
                <span>{{ system.name }}</span>
                <small>{{ system.toolCount }} 项能力<template v-if="system.current"> · 当前系统</template></small>
              </label>
            </template>
          </AiAccessSetup>
        </div>

        <LiquidGlassCard v-show="tab === 'capabilities'" class="employee-portal__card" data-testid="portal-capabilities">
          <AiCapabilityList
            :groups="capabilityGroups"
            :loading="capabilitiesLoading"
            :error="capabilitiesError"
            empty-description="管理员还没有给你开通能让 AI 操作的系统。"
          />
          <section v-if="skills.length" class="employee-portal__skills">
            <h3>同事整理好的做法（Skill）</h3>
            <p class="employee-portal__muted">下载后解压，放进 ~/.claude/skills/（Claude Code）或 ~/.codex/skills/（Codex），重启 AI 工具后直接说要办的事即可。</p>
            <article v-for="skill in skills" :key="skill.skillId" class="employee-portal__skill" data-testid="skill-item">
              <div>
                <strong>{{ skill.displayName || skill.name }}</strong>
                <span>{{ skill.description }}</span>
                <small>{{ skill.systemName || skill.projectCode }} · v{{ skill.version }}</small>
              </div>
              <LiquidGlassButton link :loading="downloadingId === skill.skillId" @click="downloadSkill(skill)">下载</LiquidGlassButton>
            </article>
          </section>
        </LiquidGlassCard>

        <LiquidGlassCard v-show="tab === 'credentials'" class="employee-portal__card">
          <AiCredentialTable
            :rows="credentialRows"
            :loading="connectionsLoading"
            :error="connectionsError"
            :busy-id="revokingId"
            @revoke="revoke"
          />
        </LiquidGlassCard>

        <LiquidGlassCard v-show="tab === 'records'" class="employee-portal__card">
          <AiCallRecords
            :records="callRecords"
            :loading="loadingCalls"
            :error="callsError"
            show-system
            @refresh="loadCalls"
          />
        </LiquidGlassCard>

        <LiquidGlassCard v-show="tab === 'approvals'" class="employee-portal__card" data-testid="portal-approvals">
          <p v-if="approvalsError" class="employee-portal__error" role="alert">{{ approvalsError }}</p>
          <LiquidGlassEmptyState
            v-else-if="!approvals.length"
            title="还没有提交过审批"
            description="AI 替你办写操作（如新建客户）时，会先提交审批，进度显示在这里。"
          />
          <ul v-else class="employee-portal__approvals">
            <li v-for="item in approvals" :key="item.approvalId" data-testid="approval-item">
              <div>
                <strong>#{{ item.approvalId }} {{ item.tool }}</strong>
                <small>{{ item.businessSystem }}<template v-if="item.comment"> · 审批备注：{{ item.comment }}</template></small>
              </div>
              <span class="employee-portal__approval-state" :class="`is-${approvalTone(item.approvalStatus)}`">
                {{ approvalStateLabel(item.approvalStatus) }}
              </span>
            </li>
          </ul>
        </LiquidGlassCard>

        <LiquidGlassCard v-if="tab === 'assistant'" class="employee-portal__card" data-testid="portal-assistant">
          <EmployeeAssistant :get-token="getToken" />
        </LiquidGlassCard>

        <LiquidGlassCard v-if="tab === 'memories'" class="employee-portal__card" data-testid="portal-memories">
          <MemoryWorkspace :api="memoryApi" system-scoped />
          <MemoryIdentityBinding :api="memoryIdentityApi" @linked="sharedMemoryPanel?.loadSystems()" />
          <SharedMemoryPanel ref="sharedMemoryPanel" :api="sharedMemoryApi" :identity-api="memoryIdentityApi" />
        </LiquidGlassCard>
      </template>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { EMPLOYEE_PORTAL_TOKEN_KEY } from '@/api/employeePortal'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState } from '@/components/common/liquid-glass'
import MemoryWorkspace from '@/components/memory/MemoryWorkspace.vue'
import SharedMemoryPanel from '@/components/memory/SharedMemoryPanel.vue'
import MemoryIdentityBinding from '@/components/memory/MemoryIdentityBinding.vue'
import { portalMemoryApi, portalSharedMemoryApi, portalMemoryIdentityApi } from '@/api/memory'
import EmployeeAssistant from './portal/EmployeeAssistant.vue'
import AiAccessStatusBar from '@/components/ai-access/AiAccessStatusBar.vue'
import AiAccessSetup from '@/components/ai-access/AiAccessSetup.vue'
import AiCapabilityList from '@/components/ai-access/AiCapabilityList.vue'
import AiCredentialTable from '@/components/ai-access/AiCredentialTable.vue'
import AiCallRecords from '@/components/ai-access/AiCallRecords.vue'
import {
  aiToolLabel, buildInstallPrompt, isToday,
  type AiCallRecord, type AiCapabilityGroup, type AiCredentialRow, type AiToolKind,
} from '@/components/ai-access/aiAccess'

interface Me { userName?: string; account: string; projectCode: string; systemName: string }
interface CallView {
  id: number
  connectionId?: number | null
  toolKind?: string | null
  toolName?: string | null
  businessSystem?: string | null
  success: boolean
  latencyMs?: number | null
  errorCategory?: string | null
  createdAt?: string | null
}
interface SystemOption { projectCode: string; name: string; toolCount: number; current: boolean }
interface Connection {
  id: number
  toolKind: string
  keyPrefix?: string | null
  state: string
  systems: { projectCode: string; name: string }[]
  createdAt?: string | null
  lastUsedAt?: string | null
  expiresAt?: string | null
}
interface Created {
  connection: Connection
  setup: { endpointUrl: string; kind: string; content: string; hint: string }
}
interface SystemCapabilities {
  projectCode: string
  name: string
  current: boolean
  tools: { name: string; description?: string | null; readOnly: boolean; approvalRequired: boolean }[]
}
interface Approval { approvalId: number; approvalStatus: string; tool: string; businessSystem: string; comment?: string }
interface EmployeeSkill {
  skillId: number
  name: string
  displayName?: string | null
  description?: string | null
  projectCode: string
  systemName?: string | null
  version: string
}

const API = '/api/employee-portal'
const TOKEN_KEY = EMPLOYEE_PORTAL_TOKEN_KEY

// 员工的「AI 工具接入」：照宝塔 MCP 面板分成接入与体验、我能办的事、访问凭据、任务记录，外加我的审批、我的助手（全屏对话）和我的记忆。
const tabs = [
  { key: 'setup', label: '接入与体验' },
  { key: 'capabilities', label: '我能办的事' },
  { key: 'credentials', label: '访问凭据' },
  { key: 'records', label: '任务记录' },
  { key: 'approvals', label: '我的审批' },
  { key: 'assistant', label: '我的助手' },
  { key: 'memories', label: '我的记忆' },
] as const
type PortalTab = typeof tabs[number]['key']
const approvalStates: Record<string, string> = {
  PENDING: '等待审批', EXECUTING: '已通过，执行中', SUCCEEDED: '已通过并执行', FAILED: '已通过但执行失败',
  REJECTED: '已驳回', EXPIRED: '已过期',
}

const tab = ref<PortalTab>('setup')
const state = ref<'loading' | 'signedIn' | 'signedOut'>('loading')
const signedOutReason = ref('')
const me = ref<Me | null>(null)
const calls = ref<CallView[]>([])
const loadingCalls = ref(false)
const callsError = ref('')
const signingOut = ref(false)
const systems = ref<SystemOption[]>([])
const systemsLoading = ref(false)
const picked = ref<string[]>([])
const toolKind = ref<AiToolKind>('CLAUDE_CODE')
const creating = ref(false)
const createError = ref('')
const installPrompt = ref<string | null>(null)
const connections = ref<Connection[]>([])
const connectionsLoading = ref(false)
const connectionsError = ref('')
const revokingId = ref<number | null>(null)
const capabilities = ref<SystemCapabilities[]>([])
const capabilitiesLoading = ref(false)
const capabilitiesError = ref('')
const approvals = ref<Approval[]>([])
const approvalsError = ref('')
const skills = ref<EmployeeSkill[]>([])
const downloadingId = ref<number | null>(null)
let token = ''

class SessionExpired extends Error {}

const toolDescriptions = computed(() => {
  const descriptions = new Map<string, string>()
  for (const system of capabilities.value) {
    for (const tool of system.tools) {
      if (tool.description) descriptions.set(tool.name, tool.description)
    }
  }
  return descriptions
})

const capabilityGroups = computed<AiCapabilityGroup[]>(() => capabilities.value.map(system => ({
  key: system.projectCode,
  title: system.name,
  subtitle: system.current ? '当前系统' : undefined,
  items: system.tools.map(tool => ({
    name: tool.name,
    description: tool.description,
    mode: tool.readOnly ? 'read' : tool.approvalRequired ? 'approval' : 'write',
  })),
})))

const credentialRows = computed<AiCredentialRow[]>(() => connections.value.map(connection => ({
  id: connection.id,
  name: `${aiToolLabel(connection.toolKind)} 凭据 #${connection.id}`,
  toolKind: connection.toolKind,
  keyHint: connection.keyPrefix ? `${connection.keyPrefix}******` : '******',
  scope: connection.systems.map(system => system.name).join('、') || '-',
  status: connection.state,
  createdAt: connection.createdAt,
  expiresAt: connection.expiresAt,
  lastUsedAt: connection.lastUsedAt,
})))

const credentialNames = computed(() => new Map(credentialRows.value.map(row => [row.id, row.name])))

const callRecords = computed<AiCallRecord[]>(() => calls.value.map(call => ({
  id: call.id,
  createdAt: call.createdAt,
  credential: (call.connectionId != null && credentialNames.value.get(call.connectionId)) || aiToolLabel(call.toolKind),
  action: (call.toolName && toolDescriptions.value.get(call.toolName)) || call.toolName || '-',
  system: call.businessSystem,
  success: call.success,
  latencyMs: call.latencyMs,
  error: call.errorCategory,
})))

const pendingApprovals = computed(() => approvals.value.filter(item => item.approvalStatus === 'PENDING').length)

const statusItems = computed(() => [
  { label: 'AI 接入服务', value: '运行正常', tone: 'ok' as const, dot: true },
  { label: '使用中的凭据', value: connections.value.filter(item => item.state === 'ACTIVE').length },
  { label: '今日调用', value: `${calls.value.filter(call => isToday(call.createdAt)).length} 次` },
  { label: '等待审批', value: pendingApprovals.value, tone: pendingApprovals.value ? 'warn' as const : undefined },
])

const tryPhrases = computed(() => {
  const system = systems.value.find(item => picked.value.includes(item.projectCode))?.name ?? me.value?.systemName ?? '业务系统'
  return [`帮我在${system}里查一下最近的库存`, `在${system}里新建一个客户，名称叫测试客户`, '我提交的审批现在到哪一步了？']
})

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

const memoryApi = portalMemoryApi(getToken, API)
const sharedMemoryApi = portalSharedMemoryApi(getToken, API)
const memoryIdentityApi = portalMemoryIdentityApi(getToken, API)
const sharedMemoryPanel = ref<InstanceType<typeof SharedMemoryPanel> | null>(null)

function signedOut(reason = '') {
  token = ''
  store('')
  me.value = null
  signedOutReason.value = reason
  state.value = 'signedOut'
}

/** Runs a load; a 401 signs the page out, anything else becomes the section's error. */
async function guarded(action: () => Promise<void>, onError: (message: string) => void, fallback: string) {
  try {
    await action()
  } catch (failure) {
    if (failure instanceof SessionExpired) signedOut(failure.message)
    else onError(failure instanceof Error ? failure.message : fallback)
  }
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
    void Promise.all([loadSystems(), loadConnections(), loadCalls(), loadCapabilities(), loadApprovals(), loadSkills()])
  } catch (failure) {
    signedOut(failure instanceof Error ? failure.message : '')
  }
}

async function loadSystems() {
  systemsLoading.value = true
  await guarded(async () => {
    systems.value = await request<SystemOption[]>('/ai-tool-connections/options')
    if (!picked.value.length) {
      const current = systems.value.find(system => system.current) ?? systems.value[0]
      if (current) picked.value = [current.projectCode]
    }
  }, () => { systems.value = [] }, '')
  systemsLoading.value = false
}

async function loadConnections() {
  connectionsLoading.value = true
  connectionsError.value = ''
  await guarded(async () => {
    connections.value = await request<Connection[]>('/ai-tool-connections')
  }, (message) => { connectionsError.value = message }, '凭据加载失败')
  connectionsLoading.value = false
}

async function loadCalls() {
  loadingCalls.value = true
  callsError.value = ''
  await guarded(async () => {
    calls.value = await request<CallView[]>('/calls')
  }, (message) => { callsError.value = message }, '调用记录加载失败')
  loadingCalls.value = false
}

async function loadCapabilities() {
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  await guarded(async () => {
    capabilities.value = await request<SystemCapabilities[]>('/capabilities')
  }, (message) => { capabilitiesError.value = message }, '加载失败')
  capabilitiesLoading.value = false
}

async function loadApprovals() {
  approvalsError.value = ''
  await guarded(async () => {
    approvals.value = await request<Approval[]>('/ai-tool-connections/approvals')
  }, (message) => { approvalsError.value = message }, '审批加载失败')
}

async function loadSkills() {
  await guarded(async () => {
    skills.value = await request<EmployeeSkill[]>('/ai-tool-connections/skills')
  }, () => { skills.value = [] }, '')
}

async function createCredential() {
  if (creating.value || !picked.value.length) return
  creating.value = true
  createError.value = ''
  await guarded(async () => {
    const created = await request<Created>('/ai-tool-connections', {
      method: 'POST',
      body: JSON.stringify({ toolKind: toolKind.value, systems: picked.value }),
    })
    installPrompt.value = buildInstallPrompt({
      toolKind: toolKind.value,
      target: created.connection.systems.map(system => system.name).join('、'),
      configHint: created.setup.hint,
      configContent: created.setup.content,
    })
    await loadConnections()
  }, (message) => { createError.value = message }, '生成失败，请稍后重试')
  creating.value = false
}

async function revoke(row: AiCredentialRow) {
  if (revokingId.value !== null) return
  if (!window.confirm(`停用「${row.name}」后，这个 AI 工具就不能再替你办事了。确定停用吗？`)) return
  revokingId.value = row.id
  await guarded(async () => {
    await request(`/ai-tool-connections/${row.id}`, { method: 'DELETE' })
    await loadConnections()
  }, (message) => { connectionsError.value = message }, '停用失败，请重试')
  revokingId.value = null
}

async function downloadSkill(skill: EmployeeSkill) {
  if (downloadingId.value !== null) return
  downloadingId.value = skill.skillId
  try {
    const response = await fetch(`${API}/ai-tool-connections/skills/${skill.skillId}/package`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (response.status === 401) throw new SessionExpired('登录已过期')
    if (!response.ok) throw new Error('下载失败')
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = `${skill.name}-${skill.version}.zip`
    link.click()
    URL.revokeObjectURL(url)
  } catch (failure) {
    if (failure instanceof SessionExpired) signedOut(failure.message)
  } finally {
    downloadingId.value = null
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

function approvalStateLabel(status: string) {
  return approvalStates[status] ?? status
}

function approvalTone(status: string) {
  return status === 'SUCCEEDED' ? 'ok' : status === 'PENDING' || status === 'EXECUTING' ? 'warn'
    : status === 'FAILED' || status === 'REJECTED' ? 'danger' : 'muted'
}

onMounted(signIn)
</script>

<style scoped lang="scss">
.employee-portal__head-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.employee-portal__workbench-link { padding: 8px 0; color: var(--brand-on-surface); font-size: 13px; text-decoration: none; cursor: pointer; }
.employee-portal__workbench-link:hover { text-decoration: underline; }
.employee-portal__workbench-link:active { color: var(--text-primary); }
.employee-portal__workbench-link:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.employee-portal {
  min-height: 100vh;
  padding: 32px 16px 48px;
  background: var(--surface-page-background);
}

.employee-portal__inner {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: min(1040px, 100%);
  margin: 0 auto;
}

.employee-portal__head,
.employee-portal__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.employee-portal__head {
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

.employee-portal__tabs {
  display: flex;
  gap: 4px;
  align-self: flex-start;
  max-width: 100%;
  padding: 4px;
  overflow-x: auto;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--glass-control-radius);
  background: var(--surface-glass-navigation);
}

.employee-portal__tab {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
  height: var(--control-height, 32px);
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--glass-control-radius);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;

  &.active {
    border-color: var(--border-glass-edge);
    background: var(--surface-glass-inset);
    color: var(--brand-on-surface, var(--brand-primary));
    font-weight: 650;
  }
}

.employee-portal__count {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  color: var(--text-on-brand);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  background: var(--status-warning);
}

.employee-portal__check {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-primary);
  font-size: 14px;

  small { color: var(--text-secondary); font-size: 12px; }
}

/* SDK 面板默认盖在对话框上；在个人页里作为普通区块展示。 */
.employee-portal__muted,
.employee-portal__error {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.employee-portal__error {
  color: var(--el-color-danger);
}

.employee-portal__skills {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;

  h3 { margin: 0; color: var(--text-primary); font-size: 15px; }
}

.employee-portal__skill,
.employee-portal__approvals li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border-divider);

  div { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
  strong { color: var(--text-primary); font-size: 13px; }
  span, small { color: var(--text-secondary); font-size: 12px; }
}

.employee-portal__approvals {
  margin: 0;
  padding: 0;
  list-style: none;
}

.employee-portal__approval-state {
  flex-shrink: 0;
  font-size: 12px;

  &.is-ok { color: var(--status-success) !important; }
  &.is-warn { color: var(--status-warning) !important; }
  &.is-danger { color: var(--status-danger) !important; }
}
</style>
