<template>
  <section class="eaf-memory" role="dialog" :aria-label="t.title">
    <header class="eaf-memory__head">
      <strong>{{ t.title }}</strong>
      <button type="button" class="eaf-memory__close" :aria-label="t.close" @click="emit('close')">×</button>
    </header>
    <p class="eaf-memory__hint">{{ t.hint }}</p>

    <div class="eaf-memory__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="tab === 'memories'"
        :class="{ active: tab === 'memories' }"
        @click="tab = 'memories'"
      >{{ t.memories }} ({{ memories.total }})</button>
      <button
        type="button"
        role="tab"
        :aria-selected="tab === 'candidates'"
        :class="{ active: tab === 'candidates' }"
        @click="tab = 'candidates'"
      >{{ t.candidates }} ({{ candidates.total }})</button>
    </div>

    <div v-if="error" class="eaf-memory__error" role="alert">{{ error }}</div>

    <div class="eaf-memory__body">
      <template v-if="tab === 'memories'">
        <select
          v-if="agentOptions.length > 1"
          v-model="agentFilter"
          class="eaf-memory__filter"
          :aria-label="t.source"
          @change="loadMemories"
        >
          <option value="">{{ t.allSources }}</option>
          <option v-for="agent in agentOptions" :key="agent" :value="agent">{{ agent }}</option>
        </select>
        <p v-if="!loading && !memories.items.length" class="eaf-memory__empty">{{ t.emptyMemories }}</p>
        <article v-for="item in memories.items" :key="item.id" class="eaf-memory__item">
          <div class="eaf-memory__text">
            <span class="eaf-memory__tag">{{ typeLabel(item.type) }}</span>
            <p>{{ item.content }}</p>
            <small>{{ formatTime(item.updatedAt) }}<template v-if="item.sourceAgentId"> · {{ item.sourceAgentId }}</template></small>
          </div>
          <button type="button" class="eaf-memory__link danger" :disabled="busyId === item.id" @click="forget(item.id)">
            {{ t.delete }}
          </button>
        </article>
      </template>

      <template v-else>
        <p v-if="!loading && !candidates.items.length" class="eaf-memory__empty">{{ t.emptyCandidates }}</p>
        <article v-for="item in candidates.items" :key="item.id" class="eaf-memory__item candidate">
          <div class="eaf-memory__text">
            <span class="eaf-memory__tag">{{ typeLabel(item.type) }}</span>
            <p>{{ item.content }}</p>
            <small v-if="item.conflictMemoryId">{{ t.replaces }}</small>
          </div>
          <div class="eaf-memory__actions">
            <button type="button" class="eaf-memory__link primary" :disabled="busyId === item.id" @click="review(item.id, 'approve')">
              {{ t.approve }}
            </button>
            <button type="button" class="eaf-memory__link" :disabled="busyId === item.id" @click="review(item.id, 'reject')">
              {{ t.reject }}
            </button>
          </div>
        </article>
      </template>
    </div>

    <footer v-if="memories.total || candidates.total" class="eaf-memory__foot">
      <button
        v-if="!confirmingErase"
        type="button"
        class="eaf-memory__link danger"
        @click="confirmingErase = true"
      >{{ t.eraseAll }}</button>
      <template v-else>
        <span>{{ t.eraseConfirm }}</span>
        <button type="button" class="eaf-memory__link danger" :disabled="erasing" @click="eraseAll">{{ t.eraseYes }}</button>
        <button type="button" class="eaf-memory__link" @click="confirmingErase = false">{{ t.cancel }}</button>
      </template>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

interface MemoryItem {
  id: number
  type: string
  content: string
  sourceAgentId?: string | null
  updatedAt?: string | null
}

interface CandidateItem {
  id: number
  type: string
  content: string
  conflictMemoryId?: number | null
}

interface Page<T> { items: T[]; total: number }

const props = withDefaults(defineProps<{
  apiBase: string
  getToken: () => Promise<string>
  locale?: string
}>(), { locale: 'zh-CN' })

const emit = defineEmits<{ (event: 'close'): void }>()

const zh = {
  title: '我的记忆',
  close: '关闭',
  hint: '助手会参考这些信息来回答你，只有你自己能看到。删除后立即不再使用。',
  memories: '已记住',
  candidates: '待确认',
  source: '来源',
  allSources: '全部来源',
  emptyMemories: '还没有记住任何信息。对助手说“请记住……”即可添加。',
  emptyCandidates: '没有待确认的信息。',
  delete: '删除',
  approve: '记住',
  reject: '忽略',
  replaces: '确认后会替换一条已有记忆',
  eraseAll: '清空全部记忆',
  eraseConfirm: '清空后无法恢复，确定吗？',
  eraseYes: '确定清空',
  cancel: '取消',
  failed: '操作失败，请稍后重试',
  types: { FACT: '事实', PREFERENCE: '偏好', RULE: '规则', NOTE: '备注' } as Record<string, string>,
}
const en: typeof zh = {
  title: 'My memories',
  close: 'Close',
  hint: 'The assistant uses these to answer you. Only you can see them. Deleted items stop being used at once.',
  memories: 'Remembered',
  candidates: 'To confirm',
  source: 'Source',
  allSources: 'All sources',
  emptyMemories: 'Nothing remembered yet. Say "remember ..." to add something.',
  emptyCandidates: 'Nothing to confirm.',
  delete: 'Delete',
  approve: 'Remember',
  reject: 'Ignore',
  replaces: 'Confirming replaces an existing memory',
  eraseAll: 'Erase all memories',
  eraseConfirm: 'This cannot be undone. Continue?',
  eraseYes: 'Erase',
  cancel: 'Cancel',
  failed: 'Something went wrong. Try again later.',
  types: { FACT: 'Fact', PREFERENCE: 'Preference', RULE: 'Rule', NOTE: 'Note' },
}
const t = computed(() => (props.locale === 'en-US' ? en : zh))

const tab = ref<'memories' | 'candidates'>('memories')
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
const confirmingErase = ref(false)
const erasing = ref(false)
const agentFilter = ref('')
const knownAgents = ref<string[]>([])
const memories = reactive<Page<MemoryItem>>({ items: [], total: 0 })
const candidates = reactive<Page<CandidateItem>>({ items: [], total: 0 })
const agentOptions = computed(() => knownAgents.value)

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await props.getToken()
  const response = await fetch(`${props.apiBase}/memories${path}`, {
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

async function loadMemories() {
  const query = new URLSearchParams({ limit: '100' })
  if (agentFilter.value) query.set('agentId', agentFilter.value)
  const page = await call<Page<MemoryItem>>(`?${query}`)
  memories.items = page.items
  memories.total = page.total
  const merged = new Set(knownAgents.value)
  page.items.forEach((item) => { if (item.sourceAgentId) merged.add(item.sourceAgentId) })
  knownAgents.value = [...merged].sort()
}

async function loadCandidates() {
  const page = await call<Page<CandidateItem>>('/candidates?limit=100')
  candidates.items = page.items
  candidates.total = page.total
}

async function reload() {
  loading.value = true
  await guarded(async () => { await Promise.all([loadMemories(), loadCandidates()]) })
  loading.value = false
}

async function forget(id: number) {
  busyId.value = id
  await guarded(async () => {
    await call(`/${id}`, { method: 'DELETE' })
    await loadMemories()
  })
  busyId.value = null
}

async function review(id: number, decision: 'approve' | 'reject') {
  busyId.value = id
  await guarded(async () => {
    await call(`/candidates/${id}/${decision}`, { method: 'POST' })
    await Promise.all([loadCandidates(), decision === 'approve' ? loadMemories() : Promise.resolve()])
  })
  busyId.value = null
}

async function eraseAll() {
  erasing.value = true
  await guarded(async () => {
    await call('/erase-all', {
      method: 'POST',
      body: JSON.stringify({ confirmation: 'ERASE_ALL_PERSONAL_MEMORIES' }),
    })
    confirmingErase.value = false
    await Promise.all([loadMemories(), loadCandidates()])
  })
  erasing.value = false
}

function typeLabel(type: string) {
  return t.value.types[type] || type
}

function formatTime(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString(props.locale)
}

onMounted(reload)

defineExpose({ reload })
</script>

<style scoped>
.eaf-memory {
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

/* SDK 只依赖自身主题变量，避免嵌入业务系统时耦合管理端样式。 */
@supports (backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)) {
  .eaf-memory {
    background: color-mix(in srgb, var(--reachai-chat-surface, #fff) 82%, transparent);
    -webkit-backdrop-filter: blur(32px) saturate(1.15);
    backdrop-filter: blur(32px) saturate(1.15);
  }
}

:global([data-reduce-transparency='true']) .eaf-memory {
  background: var(--reachai-chat-surface, #fff);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

@media (prefers-reduced-transparency: reduce) {
  .eaf-memory {
    background: var(--reachai-chat-surface, #fff);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

@media (forced-colors: active) {
  .eaf-memory {
    color: CanvasText;
    background: Canvas;
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

.eaf-memory__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 4px;
  font-size: 14px;
}

.eaf-memory__close {
  border: none;
  background: transparent;
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.eaf-memory__hint {
  margin: 0 14px 8px;
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 12px;
}

.eaf-memory__tabs {
  display: flex;
  gap: 4px;
  padding: 0 14px;
  border-bottom: 1px solid var(--reachai-chat-border, #d7e0de);
}

.eaf-memory__tabs button {
  padding: 6px 8px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--reachai-chat-text-muted, #52605f);
  font: inherit;
  cursor: pointer;
}

.eaf-memory__tabs button.active {
  border-bottom-color: var(--reachai-chat-primary, #6366f1);
  color: var(--reachai-chat-primary, #6366f1);
  font-weight: 600;
}

.eaf-memory__error {
  margin: 8px 14px 0;
  color: var(--reachai-chat-danger, #b42318);
  font-size: 12px;
}

.eaf-memory__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.eaf-memory__filter {
  align-self: flex-start;
  padding: 4px 6px;
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font: inherit;
}

.eaf-memory__empty {
  margin: 16px 0;
  color: var(--reachai-chat-text-muted, #52605f);
  text-align: center;
}

.eaf-memory__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: 8px;
}

.eaf-memory__item.candidate {
  border-left: 3px solid var(--reachai-chat-primary, #6366f1);
}

.eaf-memory__text {
  min-width: 0;
}

.eaf-memory__text p {
  margin: 4px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.eaf-memory__text small {
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 11px;
}

.eaf-memory__tag {
  display: inline-block;
  padding: 0 6px;
  border-radius: 4px;
  background: rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.1);
  color: var(--reachai-chat-primary, #6366f1);
  font-size: 11px;
}

.eaf-memory__actions {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.eaf-memory__link {
  flex-shrink: 0;
  border: none;
  padding: 2px 4px;
  background: transparent;
  color: var(--reachai-chat-text-muted, #52605f);
  font: inherit;
  cursor: pointer;
}

.eaf-memory__link.primary { color: var(--reachai-chat-primary, #6366f1); font-weight: 600; }
.eaf-memory__link.danger { color: var(--reachai-chat-danger, #b42318); }
.eaf-memory__link:disabled { opacity: 0.5; cursor: wait; }

.eaf-memory__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 14px 12px;
  border-top: 1px solid var(--reachai-chat-border, #d7e0de);
  color: var(--reachai-chat-text-muted, #52605f);
  font-size: 12px;
}
</style>
