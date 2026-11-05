<template>
  <section ref="panel" class="eaf-aitool" :class="{ 'eaf-aitool--page': page }" :role="page ? 'region' : 'dialog'" :aria-label="t.title" tabindex="-1" @keydown.esc.stop="!page && emit('close')">
    <header class="eaf-aitool__head">
      <div>
        <span class="eaf-aitool__eyebrow">{{ t.eyebrow }}</span>
        <h3>{{ t.title }}</h3>
      </div>
      <button v-if="!page" type="button" class="eaf-aitool__close" :aria-label="t.close" @click="emit('close')">×</button>
    </header>

    <div class="eaf-aitool__body">
      <div v-if="error" class="eaf-aitool__error" role="alert">
        <span>{{ error }}</span>
        <button v-if="!systems.length && !created" type="button" class="eaf-aitool__link" :disabled="loading" @click="reload">{{ t.retry }}</button>
      </div>
      <template v-if="created">
        <div class="eaf-aitool__success-mark" aria-hidden="true">✓</div>
        <p class="eaf-aitool__done" role="status">{{ t.ready }}</p>
        <p class="eaf-aitool__step">{{ created.setup.hint }}</p>
        <pre class="eaf-aitool__code" data-testid="setup-content">{{ created.setup.content }}</pre>
        <div class="eaf-aitool__row">
          <button type="button" class="eaf-aitool__primary" @click="copySetup">
            {{ copied ? t.copied : t.copy }}
          </button>
          <button type="button" class="eaf-aitool__link" @click="finish">{{ t.done }}</button>
        </div>
        <p v-if="copyFailed" class="eaf-aitool__step" role="status">{{ t.copyFailed }}</p>
        <p class="eaf-aitool__warn">{{ t.secretOnce }}</p>
      </template>

      <template v-else>
        <p class="eaf-aitool__intro">{{ t.intro }}</p>
        <fieldset class="eaf-aitool__group" :disabled="loading || creating">
          <legend class="eaf-aitool__label"><span class="eaf-aitool__number">1</span>{{ t.systems }}</legend>
          <p v-if="loading" class="eaf-aitool__empty" role="status">{{ t.loading }}</p>
          <p v-else-if="!systems.length && !error" class="eaf-aitool__empty">{{ t.noSystems }}</p>
          <label v-for="system in systems" :key="system.projectCode" class="eaf-aitool__check" :class="{ 'is-selected': picked.includes(system.projectCode) }">
            <input v-model="picked" type="checkbox" :value="system.projectCode">
            <span class="eaf-aitool__system-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 7h3m2 0h3M8 11h3m2 0h3M10 21v-5h4v5"/></svg></span>
            <span class="eaf-aitool__text"><strong>{{ system.name }}</strong><small>{{ t.toolCount(system.toolCount) }}</small></span>
            <small v-if="system.current" class="eaf-aitool__tag">{{ t.current }}</small>
          </label>
        </fieldset>

        <fieldset class="eaf-aitool__group" :disabled="loading || creating">
          <legend class="eaf-aitool__label"><span class="eaf-aitool__number">2</span>{{ t.tool }}</legend>
          <div class="eaf-aitool__tools">
            <label v-for="tool in toolKinds" :key="tool.value" class="eaf-aitool__radio" :class="{ 'is-selected': toolKind === tool.value }">
              <input v-model="toolKind" type="radio" :name="toolGroupName" :value="tool.value">
              <img v-if="connectionToolIcon[tool.value]" :src="connectionToolIcon[tool.value]" alt="" width="28" height="28">
              <svg v-else width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="3" y="5" width="18" height="15" rx="4"/><path d="M12 2v3M7 20v2m10-2v2M7 11h2m6 0h2m-8 4h6"/></svg>
              <span>{{ tool.label }}</span>
            </label>
          </div>
        </fieldset>

        <aside class="eaf-aitool__permission">
          <strong>{{ t.permissions }}</strong>
          <p class="eaf-aitool__hint">{{ t.hint }}</p>
        </aside>

        <section v-if="connections.length" class="eaf-aitool__section">
          <h4 class="eaf-aitool__label">{{ t.mine }}<span class="eaf-aitool__count">{{ connections.length }}</span></h4>
          <article v-for="item in connections" :key="item.id" class="eaf-aitool__item">
            <span class="eaf-aitool__connection-icon" aria-hidden="true">
              <img v-if="connectionToolIcon[item.toolKind]" :src="connectionToolIcon[item.toolKind]" alt="" width="22" height="22">
              <svg v-else width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="5" width="18" height="15" rx="4"/><path d="M8 11h2m4 0h2m-7 4h6"/></svg>
            </span>
            <div class="eaf-aitool__text">
              <strong>{{ toolLabel(item.toolKind) }}</strong>
              <span>{{ item.systems.map((system) => system.name).join('、') }}</span>
              <small>{{ stateLabel(item) }}</small>
            </div>
            <button
              v-if="item.state === 'ACTIVE'"
              type="button"
              class="eaf-aitool__link danger"
              :disabled="busyId === item.id"
              @click="disconnect(item.id)"
            >{{ t.disconnect }}</button>
          </article>
        </section>

        <template v-if="!page">
          <button
            type="button"
            class="eaf-aitool__link eaf-aitool__portal"
            data-testid="open-portal"
            :disabled="openingPortal"
            @click="openPortal"
          >{{ t.openPortal }}</button>
          <p v-if="portalUrl" class="eaf-aitool__step">
            {{ t.portalBlocked }}<a :href="portalUrl" target="_blank" rel="noopener">{{ t.openPortal }}</a>
          </p>
        </template>

        <section v-if="approvals.length" class="eaf-aitool__section">
          <h4 class="eaf-aitool__label">{{ t.approvals }}</h4>
          <article v-for="item in approvals" :key="item.approvalId" class="eaf-aitool__item" data-testid="approval-item">
            <div class="eaf-aitool__text">
              <strong>#{{ item.approvalId }} {{ item.tool }}</strong>
              <span>{{ item.businessSystem }} · {{ approvalStateLabel(item.approvalStatus) }}</span>
              <small v-if="item.comment">{{ t.comment }}{{ item.comment }}</small>
            </div>
          </article>
        </section>

        <section v-if="skills.length" class="eaf-aitool__section">
          <h4 class="eaf-aitool__label">{{ t.skills }}</h4>
          <p class="eaf-aitool__step">{{ t.skillHint }}</p>
          <article v-for="item in skills" :key="item.skillId" class="eaf-aitool__item" data-testid="skill-item">
            <div class="eaf-aitool__text">
              <strong>{{ item.displayName || item.name }}</strong>
              <span>{{ item.description }}</span>
              <small>{{ item.systemName || item.projectCode }} · v{{ item.version }}</small>
            </div>
            <button
              type="button"
              class="eaf-aitool__link"
              :disabled="downloadingId === item.skillId"
              @click="downloadSkill(item)"
            >{{ downloadingId === item.skillId ? t.downloading : t.download }}</button>
          </article>
        </section>
      </template>
    </div>
    <footer v-if="!created" class="eaf-aitool__footer">
      <span>{{ picked.length ? t.selected(picked.length) : t.pickSystem }}</span>
      <button type="button" class="eaf-aitool__primary" :disabled="loading || creating || !picked.length" :aria-busy="creating" @click="create">
        <span :class="{ 'is-hidden': creating }" :aria-hidden="creating">{{ t.create }}<span aria-hidden="true"> →</span></span>
        <span :class="{ 'is-hidden': !creating }" :aria-hidden="!creating">{{ t.creating }}</span>
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from 'vue'
import { connectionToolIcon } from './aiToolIcons'

interface SystemOption { projectCode: string; name: string; toolCount: number; current: boolean }
interface Connection {
  id: number
  toolKind: string
  state: string
  systems: { projectCode: string; name: string }[]
  lastUsedAt?: string | null
  expiresAt?: string | null
}
interface Approval {
  approvalId: number
  approvalStatus: string
  tool: string
  businessSystem: string
  comment?: string
}
interface EmployeeSkill {
  skillId: number
  name: string
  displayName?: string | null
  description?: string | null
  projectCode: string
  systemName?: string | null
  version: string
}
interface Created {
  connection: Connection
  setup: { endpointUrl: string; kind: string; content: string; hint: string }
}

const props = withDefaults(defineProps<{
  apiBase: string
  getToken: () => Promise<string>
  locale?: string
  /** Shown as a section of the employee personal page instead of over the chat. */
  page?: boolean
}>(), { locale: 'zh-CN', page: false })

const emit = defineEmits<{ (event: 'close'): void }>()

const zh = {
  eyebrow: '连接你的工作助手',
  title: '在我的 AI 工具中使用',
  intro: '把业务能力带到熟悉的 AI 工具，继续用你的身份办事。',
  permissions: '连接的访问范围',
  loading: '正在加载可接入的系统…',
  retry: '重试',
  selected: (count: number) => `已选择 ${count} 个系统`,
  pickSystem: '请先选择要接入的系统',
  close: '关闭',
  hint: '选择业务系统后，AI 工具以你的身份使用已开放的能力，业务写操作按管理员设置审批。连接还允许 AI 工具读取、保存和删除你的个人记忆；连接时会发送你的相关偏好，记忆操作不经过业务写审批。',
  systems: '要接入的系统',
  noSystems: '暂时没有可以接入的系统，请联系管理员开通。',
  toolCount: (count: number) => `${count} 项能力`,
  current: '当前系统',
  tool: '你用的 AI 工具',
  create: '生成连接',
  creating: '正在生成…',
  ready: '已生成，按下面一步完成接入：',
  copy: '复制',
  copied: '已复制',
  copyFailed: '无法访问剪贴板，请选中上面的内容手动复制。',
  done: '完成',
  secretOnce: '这段内容只显示一次，里面包含你的个人连接密钥，请不要发给别人。',
  mine: '我的访问凭据',
  disconnect: '停用',
  active: '使用中',
  revoked: '已停用',
  expired: '已过期',
  lastUsed: '最近使用',
  neverUsed: '还未使用',
  failed: '操作失败，请稍后重试',
  approvals: '我的审批',
  comment: '审批备注：',
  skills: '可用的做法（Skill）',
  skillHint: '同事整理好的办事做法。下载后解压，把文件夹放进 ~/.claude/skills/（Claude Code）或 ~/.codex/skills/（Codex），重启 AI 工具后直接说要办的事即可。',
  download: '下载',
  downloading: '下载中…',
  openPortal: '在浏览器里管理',
  portalBlocked: '浏览器拦截了新窗口，请点这里打开：',
  approvalStates: {
    PENDING: '等待审批', EXECUTING: '已通过，执行中', SUCCEEDED: '已通过并执行', FAILED: '已通过但执行失败',
    REJECTED: '已驳回', EXPIRED: '已过期',
  } as Record<string, string>,
  tools: { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: '其他 AI 工具' } as Record<string, string>,
}
const en: typeof zh = {
  eyebrow: 'Connect your work assistant',
  title: 'Use in my AI tool',
  intro: 'Bring business capabilities to your familiar AI tool, using your own identity.',
  permissions: 'What this connection can access',
  loading: 'Loading available systems…',
  retry: 'Retry',
  selected: (count: number) => `${count} system${count === 1 ? '' : 's'} selected`,
  pickSystem: 'Choose a system to connect',
  close: 'Close',
  hint: 'Choose business systems for your AI tool to access as you. Business writes follow the administrator’s approval settings. The connection can also read, save and delete your personal memories; relevant preferences are sent when it connects, and memory changes do not use business write approval.',
  systems: 'Systems to connect',
  noSystems: 'No system is available yet. Ask your administrator to enable it.',
  toolCount: (count: number) => `${count} capabilities`,
  current: 'this system',
  tool: 'Your AI tool',
  create: 'Create connection',
  creating: 'Creating…',
  ready: 'Created. One step left:',
  copy: 'Copy',
  copied: 'Copied',
  copyFailed: 'Clipboard access is unavailable. Select the content above and copy it manually.',
  done: 'Done',
  secretOnce: 'Shown only once. It contains your personal connection key; do not share it.',
  mine: 'My connections',
  disconnect: 'Disconnect',
  active: 'Active',
  revoked: 'Disconnected',
  expired: 'Expired',
  lastUsed: 'Last used',
  neverUsed: 'Not used yet',
  failed: 'Something went wrong. Try again later.',
  approvals: 'My approvals',
  comment: 'Note: ',
  skills: 'Ready-made skills',
  skillHint: 'Ways of working your colleagues saved. Unzip the download into ~/.claude/skills/ (Claude Code) or ~/.codex/skills/ (Codex), restart the tool, then just ask for the task.',
  download: 'Download',
  downloading: 'Downloading…',
  openPortal: 'Manage in browser',
  portalBlocked: 'The new window was blocked. Open it here: ',
  approvalStates: {
    PENDING: 'Waiting for approval', EXECUTING: 'Approved, running', SUCCEEDED: 'Approved and done',
    FAILED: 'Approved but failed', REJECTED: 'Rejected', EXPIRED: 'Expired',
  },
  tools: { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: 'Other AI tool' },
}
const t = computed(() => (props.locale === 'en-US' ? en : zh))
const toolKinds = computed(() => ['CLAUDE_CODE', 'CODEX', 'OTHER']
  .map((value) => ({ value, label: t.value.tools[value] })))
const toolGroupName = `eaf-aitool-${useId()}`
const panel = ref<HTMLElement>()

const loading = ref(false)
const error = ref('')
const systems = ref<SystemOption[]>([])
const connections = ref<Connection[]>([])
const approvals = ref<Approval[]>([])
const skills = ref<EmployeeSkill[]>([])
const downloadingId = ref<number | null>(null)
const picked = ref<string[]>([])
const toolKind = ref('CLAUDE_CODE')
const creating = ref(false)
const created = ref<Created | null>(null)
const copied = ref(false)
const copyFailed = ref(false)
const busyId = ref<number | null>(null)
const openingPortal = ref(false)
const portalUrl = ref('')

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await props.getToken()
  const response = await fetch(`${props.apiBase}/ai-tool-connections${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      Authorization: `Bearer ${token}`,
    },
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
    throw new Error(payload.message || t.value.failed)
  }
  return payload.data as T
}

async function guarded(action: () => Promise<void>) {
  error.value = ''
  try {
    await action()
  } catch (failure) {
    error.value = failure instanceof Error && failure.message ? failure.message : t.value.failed
  }
}

async function reload() {
  loading.value = true
  await guarded(async () => {
    const [options, mine, submitted, available] = await Promise.all([
      call<SystemOption[]>('/options'),
      call<Connection[]>(''),
      // Optional: an older ReachAI without write approval or skills simply shows no list.
      call<Approval[]>('/approvals').catch(() => [] as Approval[]),
      call<EmployeeSkill[]>('/skills').catch(() => [] as EmployeeSkill[]),
    ])
    systems.value = options
    connections.value = mine
    approvals.value = submitted ?? []
    skills.value = available ?? []
    if (!picked.value.length) {
      picked.value = options.filter((system) => system.current).map((system) => system.projectCode)
    }
  })
  loading.value = false
}

async function create() {
  if (loading.value || creating.value || !picked.value.length) return
  creating.value = true
  await guarded(async () => {
    created.value = await call<Created>('', {
      method: 'POST',
      body: JSON.stringify({ toolKind: toolKind.value, systems: picked.value }),
    })
    copied.value = false
    copyFailed.value = false
  })
  creating.value = false
  if (created.value) await focusPanel()
}

async function copySetup() {
  if (!created.value) return
  try {
    await navigator.clipboard.writeText(created.value.setup.content)
    copied.value = true
    copyFailed.value = false
  } catch {
    // Clipboard can be blocked inside an iframe; the text stays selectable in the box.
    copied.value = false
    copyFailed.value = true
  }
}

async function finish() {
  created.value = null
  await reload()
  await focusPanel()
}

async function disconnect(id: number) {
  const trigger = document.activeElement
  busyId.value = id
  await guarded(async () => {
    await call(`/${id}`, { method: 'DELETE' })
    connections.value = await call<Connection[]>('')
  })
  busyId.value = null
  if (trigger && !trigger.isConnected) await focusPanel()
}

async function downloadSkill(item: EmployeeSkill) {
  downloadingId.value = item.skillId
  await guarded(async () => {
    const token = await props.getToken()
    const response = await fetch(`${props.apiBase}/ai-tool-connections/skills/${item.skillId}/package`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(payload.message || t.value.failed)
    }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = `${item.name}-${item.version}.zip`
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
  downloadingId.value = null
}

async function openPortal() {
  // Open the tab inside the click so pop-up blockers allow it, then point it at the one-time link.
  const tab = window.open('', '_blank')
  openingPortal.value = true
  portalUrl.value = ''
  await guarded(async () => {
    const { url } = await call<{ url: string }>('/portal-ticket', { method: 'POST' })
    if (tab) {
      tab.opener = null
      tab.location.href = url
    } else {
      portalUrl.value = url
    }
  })
  if (error.value && tab) tab.close()
  openingPortal.value = false
}

function toolLabel(kind: string) {
  return t.value.tools[kind] || kind
}

function approvalStateLabel(state: string) {
  return t.value.approvalStates[state] || state
}

function stateLabel(item: Connection) {
  if (item.state === 'REVOKED') return t.value.revoked
  if (item.state === 'EXPIRED') return t.value.expired
  const used = item.lastUsedAt ? new Date(item.lastUsedAt) : null
  return used && !Number.isNaN(used.getTime())
    ? `${t.value.active} · ${t.value.lastUsed} ${used.toLocaleDateString(props.locale)}`
    : `${t.value.active} · ${t.value.neverUsed}`
}

async function focusPanel() {
  if (props.page) return
  await nextTick()
  panel.value?.focus({ preventScroll: true })
}

onMounted(() => {
  void focusPanel()
  void reload()
})

defineExpose({ reload })
</script>

<style scoped>
.eaf-aitool {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  color: var(--reachai-chat-text);
  background: var(--reachai-chat-surface);
  font: 13px/1.6 var(--reachai-chat-font-family);
  text-align: left;
  color-scheme: var(--reachai-chat-color-scheme, light);
  outline: none;
  container-type: inline-size;
}

.eaf-aitool :is(button, input) { font-family: inherit; font-size: inherit; line-height: inherit; }
.eaf-aitool :is(button, label, input, a) { -webkit-tap-highlight-color: transparent; }
.eaf-aitool button { cursor: pointer; }
.eaf-aitool button:disabled { opacity: 0.5; cursor: not-allowed; }
.eaf-aitool :is(button, input, a):focus-visible {
  outline: 2px solid var(--reachai-chat-primary);
  outline-offset: 3px;
}
.eaf-aitool__head {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--reachai-chat-border);
  background: var(--reachai-chat-glass-card);
}
.eaf-aitool__eyebrow {
  display: block;
  margin-bottom: 3px;
  color: var(--reachai-chat-text-secondary);
  font-size: 11px;
  font-weight: 500;
}
.eaf-aitool__head h3 { margin: 0; color: inherit; font: 650 17px/1.4 var(--reachai-chat-font-family); }
.eaf-aitool__close {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--reachai-chat-border);
  border-radius: 9px;
  color: var(--reachai-chat-text-secondary);
  background: var(--reachai-chat-glass-control);
  font-size: 22px;
  line-height: 1;
}
.eaf-aitool__close:hover { color: var(--reachai-chat-primary); background: var(--reachai-chat-surface-muted); }
.eaf-aitool__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 16px 18px 20px 20px;
  gap: 18px;
}
.eaf-aitool__intro, .eaf-aitool__hint, .eaf-aitool__step, .eaf-aitool__warn, .eaf-aitool__empty {
  margin: 0;
  color: var(--reachai-chat-text-secondary);
  overflow-wrap: anywhere;
}
.eaf-aitool__intro { font-size: 12px; }
.eaf-aitool__group { display: flex; flex-direction: column; gap: 8px; min-width: 0; margin: 0; padding: 0; border: 0; }
.eaf-aitool__label {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 7px;
  margin: 0 0 10px;
  padding: 0;
  color: var(--reachai-chat-text);
  font-size: 13px;
  font-weight: 650;
}
.eaf-aitool__number {
  display: inline-grid;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  place-items: center;
  border: 1px solid rgb(var(--reachai-chat-primary-rgb) / 0.2);
  border-radius: 7px;
  color: var(--reachai-chat-primary);
  background: rgb(var(--reachai-chat-primary-rgb) / 0.06);
  font-size: 11px;
}
.eaf-aitool__check, .eaf-aitool__radio {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--reachai-chat-border);
  border-radius: var(--reachai-chat-radius);
  background: var(--reachai-chat-glass-control);
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease;
}
.eaf-aitool__check { min-height: 64px; padding: 10px 12px; }
.eaf-aitool__check input, .eaf-aitool__radio input { width: 14px; height: 14px; flex-shrink: 0; margin: 0; accent-color: var(--reachai-chat-primary); }
.eaf-aitool__system-icon { display: grid; width: 32px; height: 36px; flex: 0 0 32px; place-items: center; color: var(--reachai-chat-primary); }
.eaf-aitool__system-icon svg { width: 25px; height: 25px; }
.eaf-aitool__check .eaf-aitool__text { flex: 1; }
.eaf-aitool__check .eaf-aitool__text strong { font-weight: 600; }
.eaf-aitool__tag, .eaf-aitool__count {
  padding: 2px 6px;
  border-radius: 5px;
  color: var(--reachai-chat-text-secondary);
  background: var(--reachai-chat-surface-muted);
  font-size: 10px;
  white-space: nowrap;
}
.eaf-aitool__tools { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.eaf-aitool__radio { flex-direction: column; justify-content: center; min-height: 91px; padding: 20px 4px 10px; gap: 8px; text-align: center; }
.eaf-aitool__radio input { position: absolute; right: 7px; top: 7px; width: 12px; height: 12px; }
.eaf-aitool__radio img { width: 28px; height: 28px; object-fit: contain; }
.eaf-aitool__radio span { font-size: 11px; font-weight: 600; overflow-wrap: anywhere; }
.eaf-aitool__check:hover, .eaf-aitool__radio:hover { border-color: var(--reachai-chat-primary); }
.eaf-aitool .is-selected { border-color: rgb(var(--reachai-chat-primary-rgb) / 0.62); background: rgb(var(--reachai-chat-primary-rgb) / 0.055); }
.eaf-aitool__group:disabled label { cursor: default; }
.eaf-aitool__permission { padding: 12px; border: 1px solid var(--reachai-chat-border); border-radius: 10px; background: var(--reachai-chat-surface-muted); }
.eaf-aitool__permission strong { font-size: 11px; font-weight: 600; color: var(--reachai-chat-text-secondary); }
.eaf-aitool__hint { margin-top: 5px; font-size: 11px; line-height: 1.75; }
.eaf-aitool__section { display: flex; flex-direction: column; gap: 8px; padding-top: 18px; border-top: 1px solid var(--reachai-chat-border); }
.eaf-aitool__section .eaf-aitool__label { margin-bottom: 2px; }
.eaf-aitool__item { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 11px 0; border-bottom: 1px solid var(--reachai-chat-border); }
.eaf-aitool__item:last-child { border-bottom: 0; }
.eaf-aitool__connection-icon { display: grid; flex: 0 0 36px; width: 36px; height: 36px; place-items: center; border: 1px solid var(--reachai-chat-border); border-radius: 10px; background: var(--reachai-chat-primary-contrast); color: var(--reachai-chat-primary); }
.eaf-aitool__text { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; overflow-wrap: anywhere; }
.eaf-aitool__text strong { font-size: 13px; font-weight: 600; }
.eaf-aitool__text span { font-size: 12px; color: var(--reachai-chat-text-secondary); }
.eaf-aitool__text small { color: var(--reachai-chat-text-muted); font-size: 11px; }
.eaf-aitool__link { flex-shrink: 0; border: 0; border-radius: 6px; padding: 5px 7px; background: transparent; color: var(--reachai-chat-primary); }
.eaf-aitool__link:hover { background: rgb(var(--reachai-chat-primary-rgb) / 0.08); }
.eaf-aitool__link.danger { color: var(--reachai-chat-danger); }
.eaf-aitool__portal { align-self: flex-start; font-size: 12px; }
.eaf-aitool__footer { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--reachai-chat-border); background: var(--reachai-chat-surface); }
.eaf-aitool__footer > span { color: var(--reachai-chat-text-secondary); font-size: 11px; }
.eaf-aitool__footer .eaf-aitool__primary { display: inline-grid; }
.eaf-aitool__footer .eaf-aitool__primary > span { grid-area: 1 / 1; }
.eaf-aitool__footer .is-hidden { visibility: hidden; }
.eaf-aitool__primary { flex-shrink: 0; padding: 9px 16px; border: 1px solid transparent; border-radius: 10px; background: var(--reachai-chat-primary); color: var(--reachai-chat-primary-contrast); font-weight: 600; }
.eaf-aitool__primary:hover:not(:disabled) { background: var(--reachai-chat-primary-deep); }
.eaf-aitool button:active:not(:disabled) { transform: translateY(1px); }
.eaf-aitool__error { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; border: 1px solid currentColor; border-radius: 10px; color: var(--reachai-chat-danger); font-size: 12px; overflow-wrap: anywhere; }
.eaf-aitool__success-mark { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 12px; color: var(--reachai-chat-success); background: color-mix(in srgb, var(--reachai-chat-success) 8%, transparent); font-size: 22px; }
.eaf-aitool__done { margin: 0; font-size: 15px; font-weight: 650; }
.eaf-aitool__step, .eaf-aitool__warn { font-size: 12px; }
.eaf-aitool__warn { padding: 10px 12px; border-left: 2px solid var(--reachai-chat-warning); background: var(--reachai-chat-surface-muted); }
.eaf-aitool__code { margin: 0; padding: 14px; border: 1px solid var(--reachai-chat-border); border-radius: 10px; color: var(--reachai-chat-text); background: var(--reachai-chat-surface-muted); font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; user-select: all; }
.eaf-aitool__row { display: flex; align-items: center; gap: 12px; }
.eaf-aitool--page { position: static; inset: auto; z-index: auto; border-radius: 16px; overflow: hidden; }
.eaf-aitool--page .eaf-aitool__body { flex: auto; overflow: visible; }

@container (max-width: 320px) {
  .eaf-aitool__head, .eaf-aitool__footer { padding-inline: 14px; }
  .eaf-aitool__body { padding-inline: 14px; }
  .eaf-aitool__check { gap: 7px; padding-inline: 9px; }
  .eaf-aitool__system-icon { display: none; }
  .eaf-aitool__head h3 { font-size: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .eaf-aitool * { transition: none; }
}
@media (forced-colors: active) {
  .eaf-aitool .is-selected { outline: 2px solid Highlight; }
  .eaf-aitool__number, .eaf-aitool__primary { border-color: ButtonText; }
}
</style>
