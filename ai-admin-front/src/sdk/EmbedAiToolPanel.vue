<template>
  <section class="eaf-aitool" role="dialog" :aria-label="t.title">
    <header class="eaf-aitool__head">
      <strong>{{ t.title }}</strong>
      <button type="button" class="eaf-aitool__close" :aria-label="t.close" @click="emit('close')">×</button>
    </header>
    <p class="eaf-aitool__hint">{{ t.hint }}</p>

    <div v-if="error" class="eaf-aitool__error" role="alert">{{ error }}</div>

    <div class="eaf-aitool__body">
      <template v-if="created">
        <p class="eaf-aitool__done">{{ t.ready }}</p>
        <p class="eaf-aitool__step">{{ created.setup.hint }}</p>
        <pre class="eaf-aitool__code" data-testid="setup-content">{{ created.setup.content }}</pre>
        <div class="eaf-aitool__row">
          <button type="button" class="eaf-aitool__primary" @click="copySetup">
            {{ copied ? t.copied : t.copy }}
          </button>
          <button type="button" class="eaf-aitool__link" @click="finish">{{ t.done }}</button>
        </div>
        <p class="eaf-aitool__warn">{{ t.secretOnce }}</p>
      </template>

      <template v-else>
        <h4 class="eaf-aitool__label">{{ t.systems }}</h4>
        <p v-if="!loading && !systems.length" class="eaf-aitool__empty">{{ t.noSystems }}</p>
        <label v-for="system in systems" :key="system.projectCode" class="eaf-aitool__check">
          <input v-model="picked" type="checkbox" :value="system.projectCode">
          <span>{{ system.name }}</span>
          <small>{{ t.toolCount(system.toolCount) }}<template v-if="system.current"> · {{ t.current }}</template></small>
        </label>

        <h4 class="eaf-aitool__label">{{ t.tool }}</h4>
        <div class="eaf-aitool__tools" role="radiogroup">
          <label v-for="tool in toolKinds" :key="tool.value" class="eaf-aitool__radio">
            <input v-model="toolKind" type="radio" name="eaf-aitool-kind" :value="tool.value">
            <span>{{ tool.label }}</span>
          </label>
        </div>

        <button
          type="button"
          class="eaf-aitool__primary"
          :disabled="creating || !picked.length"
          @click="create"
        >{{ creating ? t.creating : t.create }}</button>

        <template v-if="connections.length">
          <h4 class="eaf-aitool__label">{{ t.mine }}</h4>
          <article v-for="item in connections" :key="item.id" class="eaf-aitool__item">
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
        </template>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

interface SystemOption { projectCode: string; name: string; toolCount: number; current: boolean }
interface Connection {
  id: number
  toolKind: string
  state: string
  systems: { projectCode: string; name: string }[]
  lastUsedAt?: string | null
  expiresAt?: string | null
}
interface Created {
  connection: Connection
  setup: { endpointUrl: string; kind: string; content: string; hint: string }
}

const props = withDefaults(defineProps<{
  apiBase: string
  getToken: () => Promise<string>
  locale?: string
}>(), { locale: 'zh-CN' })

const emit = defineEmits<{ (event: 'close'): void }>()

const zh = {
  title: '在我的 AI 工具中使用',
  close: '关闭',
  hint: '勾选要接入的系统，复制一行到你自己的 Claude Code、Codex 等 AI 工具，就能让它以你的身份查询和办理业务。权限和你在系统里一样。',
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
  done: '完成',
  secretOnce: '这段内容只显示一次，里面包含你的个人连接密钥，请不要发给别人。',
  mine: '我的连接',
  disconnect: '断开',
  active: '使用中',
  revoked: '已断开',
  expired: '已过期',
  lastUsed: '最近使用',
  neverUsed: '还未使用',
  failed: '操作失败，请稍后重试',
  tools: { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: '其他 AI 工具' } as Record<string, string>,
}
const en: typeof zh = {
  title: 'Use in my AI tool',
  close: 'Close',
  hint: 'Tick the systems to connect and paste one line into your own Claude Code, Codex or other AI tool. It then works as you, with the same permissions you have in the system.',
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
  tools: { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: 'Other AI tool' },
}
const t = computed(() => (props.locale === 'en-US' ? en : zh))
const toolKinds = computed(() => ['CLAUDE_CODE', 'CODEX', 'OTHER']
  .map((value) => ({ value, label: t.value.tools[value] })))

const loading = ref(false)
const error = ref('')
const systems = ref<SystemOption[]>([])
const connections = ref<Connection[]>([])
const picked = ref<string[]>([])
const toolKind = ref('CLAUDE_CODE')
const creating = ref(false)
const created = ref<Created | null>(null)
const copied = ref(false)
const busyId = ref<number | null>(null)

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
    const [options, mine] = await Promise.all([
      call<SystemOption[]>('/options'),
      call<Connection[]>(''),
    ])
    systems.value = options
    connections.value = mine
    if (!picked.value.length) {
      picked.value = options.filter((system) => system.current).map((system) => system.projectCode)
    }
  })
  loading.value = false
}

async function create() {
  creating.value = true
  await guarded(async () => {
    created.value = await call<Created>('', {
      method: 'POST',
      body: JSON.stringify({ toolKind: toolKind.value, systems: picked.value }),
    })
    copied.value = false
  })
  creating.value = false
}

async function copySetup() {
  if (!created.value) return
  try {
    await navigator.clipboard.writeText(created.value.setup.content)
    copied.value = true
  } catch {
    // Clipboard can be blocked inside an iframe; the text stays selectable in the box.
    copied.value = false
  }
}

async function finish() {
  created.value = null
  await reload()
}

async function disconnect(id: number) {
  busyId.value = id
  await guarded(async () => {
    await call(`/${id}`, { method: 'DELETE' })
    connections.value = await call<Connection[]>('')
  })
  busyId.value = null
}

function toolLabel(kind: string) {
  return t.value.tools[kind] || kind
}

function stateLabel(item: Connection) {
  if (item.state === 'REVOKED') return t.value.revoked
  if (item.state === 'EXPIRED') return t.value.expired
  const used = item.lastUsedAt ? new Date(item.lastUsedAt) : null
  return used && !Number.isNaN(used.getTime())
    ? `${t.value.active} · ${t.value.lastUsed} ${used.toLocaleDateString(props.locale)}`
    : `${t.value.active} · ${t.value.neverUsed}`
}

onMounted(reload)

defineExpose({ reload })
</script>

<style scoped>
.eaf-aitool {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  min-height: 0;
  color: var(--reachai-chat-text, #1f2a2a);
  background: var(--reachai-chat-surface, #fff);
  font-size: 13px;
}

/* SDK 只依赖自身主题变量，与「我的记忆」面板同一套磨砂材质。 */
@supports (backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)) {
  .eaf-aitool {
    background: color-mix(in srgb, var(--reachai-chat-surface, #fff) 82%, transparent);
    -webkit-backdrop-filter: blur(32px) saturate(1.15);
    backdrop-filter: blur(32px) saturate(1.15);
  }
}

:global([data-reduce-transparency='true']) .eaf-aitool {
  background: var(--reachai-chat-surface, #fff);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

@media (prefers-reduced-transparency: reduce) {
  .eaf-aitool {
    background: var(--reachai-chat-surface, #fff);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

@media (forced-colors: active) {
  .eaf-aitool {
    color: CanvasText;
    background: Canvas;
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

.eaf-aitool__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 4px;
  font-size: 14px;
}

.eaf-aitool__close {
  border: none;
  background: transparent;
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.eaf-aitool__hint,
.eaf-aitool__step,
.eaf-aitool__warn {
  margin: 0 14px 8px;
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 12px;
}

.eaf-aitool__step,
.eaf-aitool__warn {
  margin: 0;
}

.eaf-aitool__error {
  margin: 0 14px 8px;
  color: var(--reachai-chat-danger, #b42318);
  font-size: 12px;
}

.eaf-aitool__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.eaf-aitool__label {
  margin: 8px 0 0;
  font-size: 12px;
  font-weight: 600;
}

.eaf-aitool__empty {
  margin: 4px 0;
  color: var(--reachai-chat-text-muted, #52605f);
}

.eaf-aitool__check,
.eaf-aitool__radio {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.eaf-aitool__check {
  padding: 8px 10px;
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: 8px;
}

.eaf-aitool__check small {
  margin-left: auto;
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 11px;
}

.eaf-aitool__tools {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.eaf-aitool__primary {
  align-self: flex-start;
  margin-top: 6px;
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  color: #fff;
  background: var(--reachai-chat-primary, #6366f1);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.eaf-aitool__primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.eaf-aitool__done {
  margin: 4px 0 0;
  font-weight: 600;
}

.eaf-aitool__code {
  margin: 0;
  padding: 10px;
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: 8px;
  background: rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.06);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  user-select: all;
}

.eaf-aitool__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.eaf-aitool__row .eaf-aitool__primary {
  margin-top: 0;
}

.eaf-aitool__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: 8px;
}

.eaf-aitool__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.eaf-aitool__text span {
  overflow-wrap: anywhere;
}

.eaf-aitool__text small {
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 11px;
}

.eaf-aitool__link {
  flex-shrink: 0;
  border: none;
  padding: 2px 4px;
  background: transparent;
  color: var(--reachai-chat-text-muted, #52605f);
  font: inherit;
  cursor: pointer;
}

.eaf-aitool__link.danger { color: var(--reachai-chat-danger, #b42318); }
.eaf-aitool__link:disabled { opacity: 0.5; cursor: wait; }
</style>
