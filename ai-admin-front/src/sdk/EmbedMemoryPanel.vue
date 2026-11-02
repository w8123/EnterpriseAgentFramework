<template>
  <section class="eaf-memory" role="dialog" :aria-label="t.title">
    <header class="eaf-memory__head">
      <strong>{{ t.title }}</strong>
      <button type="button" class="eaf-memory__close" :aria-label="t.close" @click="emit('close')">×</button>
    </header>
    <p class="eaf-memory__hint">{{ t.hint }}</p>

    <div v-if="layerOptions.length > 1" class="eaf-memory__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="scopeFilter === ''"
        :class="{ active: scopeFilter === '' }"
        @click="selectScope('')"
      >{{ t.all }} ({{ memories.total }})</button>
      <button
        v-for="layer in layerOptions"
        :key="layer.scope"
        type="button"
        role="tab"
        :aria-selected="scopeFilter === layer.scope"
        :class="{ active: scopeFilter === layer.scope }"
        @click="selectScope(layer.scope)"
      >{{ layer.name }}</button>
    </div>

    <div v-if="error" class="eaf-memory__error" role="alert">{{ error }}</div>

    <div class="eaf-memory__body">
      <p v-if="!loading && !memories.items.length" class="eaf-memory__empty">{{ t.emptyMemories }}</p>
      <article v-for="item in memories.items" :key="item.id" class="eaf-memory__item">
        <div class="eaf-memory__text">
          <span class="eaf-memory__tag">{{ placeLabel(item) }}</span>
          <p>{{ item.content }}</p>
          <small>{{ formatTime(item.updatedAt) }}<template v-if="item.sourceAgentId"> · {{ item.sourceAgentId }}</template></small>
        </div>
        <button type="button" class="eaf-memory__link danger" :disabled="busyId === item.id" @click="forget(item.id)">
          {{ t.delete }}
        </button>
      </article>
    </div>

    <footer v-if="memories.total" class="eaf-memory__foot">
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
  content: string
  layer?: 'PERSONAL' | 'PROJECT'
  scope?: string
  layerName?: string | null
  groupName?: string | null
  sourceAgentId?: string | null
  updatedAt?: string | null
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
  hint: '助手会参考这些信息来回答你，只有你自己能看到。删除后不再作为长期记忆使用；当前对话仍可能保留已发送的内容。',
  all: '全部',
  personal: '个人记忆',
  emptyMemories: '还没有记住任何信息。对助手说“请记住……”即可添加。',
  delete: '删除',
  eraseAll: '清空全部记忆',
  eraseConfirm: '清空后无法恢复，确定吗？',
  eraseYes: '确定清空',
  cancel: '取消',
  failed: '操作失败，请稍后重试',
}
const en: typeof zh = {
  title: 'My memories',
  close: 'Close',
  hint: 'The assistant uses these to answer you. Only you can see them. Deleted items are no longer used as long-term memory; messages already sent may remain in the current conversation.',
  all: 'All',
  personal: 'Personal',
  emptyMemories: 'Nothing remembered yet. Say "remember ..." to add something.',
  delete: 'Delete',
  eraseAll: 'Erase all memories',
  eraseConfirm: 'This cannot be undone. Continue?',
  eraseYes: 'Erase',
  cancel: 'Cancel',
  failed: 'Something went wrong. Try again later.',
}
const t = computed(() => (props.locale === 'en-US' ? en : zh))

const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
const confirmingErase = ref(false)
const erasing = ref(false)
const scopeFilter = ref('')
const knownLayers = ref<Array<{ scope: string; name: string }>>([])
const memories = reactive<Page<MemoryItem>>({ items: [], total: 0 })
const layerOptions = computed(() => knownLayers.value)

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
  if (scopeFilter.value) query.set('scope', scopeFilter.value)
  const page = await call<Page<MemoryItem>>(`?${query}`)
  memories.items = page.items
  memories.total = page.total
  // Layers are learnt from what the unfiltered list shows, so a filtered view keeps every tab.
  if (!scopeFilter.value) {
    const layers = new Map<string, string>()
    page.items.forEach((item) => {
      if (item.scope) layers.set(item.scope, item.layer === 'PERSONAL' ? t.value.personal : (item.layerName || item.scope))
    })
    knownLayers.value = [...layers].map(([scope, name]) => ({ scope, name }))
      .sort((a, b) => (a.scope === 'personal' ? -1 : b.scope === 'personal' ? 1 : a.name.localeCompare(b.name)))
  }
}

async function reload() {
  loading.value = true
  await guarded(loadMemories)
  loading.value = false
}

async function selectScope(scope: string) {
  scopeFilter.value = scope
  await reload()
}

async function forget(id: number) {
  busyId.value = id
  await guarded(async () => {
    await call(`/${id}`, { method: 'DELETE' })
    await loadMemories()
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
    scopeFilter.value = ''
    await loadMemories()
  })
  erasing.value = false
}

function placeLabel(item: MemoryItem) {
  const layer = item.layer === 'PROJECT' ? (item.layerName || '') : t.value.personal
  return item.groupName ? `${layer} · ${item.groupName}` : layer
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
