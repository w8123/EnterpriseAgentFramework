<template>
  <div v-if="visible" class="eaf-shortcuts" role="region" :aria-label="t.region">
    <div v-for="tip in tips" :key="`tip-${tip.id}`" class="eaf-shortcuts__tip" role="note">
      <strong>{{ t.tip }}</strong>{{ tip.title }}：{{ tip.advice }}
    </div>
    <div v-if="shortcuts.length" class="eaf-shortcuts__row">
      <span class="eaf-shortcuts__label">{{ t.label }}</span>
      <button
        v-for="item in shortcuts"
        :key="item.id"
        type="button"
        class="eaf-shortcuts__chip"
        :class="{ 'is-here': item.forThisPage }"
        :disabled="disabled"
        :title="item.prompt"
        @click="pick(item)"
      >
        {{ item.title }}
      </button>
      <button type="button" class="eaf-shortcuts__close" :aria-label="t.close" :title="t.close" @click="hidden = true">×</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

interface Shortcut {
  id: number
  kind: string
  title: string
  prompt: string
  forThisPage: boolean
}

interface Tip {
  id: number
  title: string
  advice: string
}

const props = withDefaults(defineProps<{
  apiBase: string
  getToken: () => Promise<string>
  route: string
  locale?: string
  disabled?: boolean
}>(), { locale: 'zh-CN', disabled: false })

const emit = defineEmits<{ pick: [prompt: string] }>()

const shortcuts = ref<Shortcut[]>([])
const tips = ref<Tip[]>([])
const hidden = ref(false)
let sequence = 0

const t = computed(() => props.locale === 'en-US'
  ? { region: 'Suggested shortcuts', label: 'Shortcuts', tip: 'Heads-up · ', close: 'Hide shortcuts' }
  : { region: '推荐的快捷操作', label: '快捷操作', tip: '容易出错 · ', close: '收起快捷操作' })
const visible = computed(() => !hidden.value && (shortcuts.value.length > 0 || tips.value.length > 0))

async function load() {
  const current = ++sequence
  try {
    const token = await props.getToken()
    const response = await fetch(
      `${props.apiBase}/activity/shortcuts?route=${encodeURIComponent(props.route || '/')}`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    const payload = await response.json().catch(() => ({}))
    if (current !== sequence) return
    if (!response.ok || !payload?.data) {
      shortcuts.value = []
      tips.value = []
      return
    }
    shortcuts.value = Array.isArray(payload.data.shortcuts) ? payload.data.shortcuts : []
    tips.value = Array.isArray(payload.data.tips) ? payload.data.tips : []
  } catch {
    // Shortcuts are a convenience: never surface an error in the chat.
    if (current === sequence) {
      shortcuts.value = []
      tips.value = []
    }
  }
}

function pick(item: Shortcut) {
  emit('pick', item.prompt)
  void props.getToken()
    .then((token) => fetch(`${props.apiBase}/activity/shortcuts/${item.id}/used`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    }))
    .catch(() => undefined)
}

watch(() => props.route, () => {
  hidden.value = false
  void load()
}, { immediate: true })

defineExpose({ reload: load })
</script>

<style scoped>
.eaf-shortcuts {
  display: grid;
  gap: 6px;
  padding: 6px 10px 0;
}
.eaf-shortcuts__tip {
  font-size: 12px;
  line-height: 1.5;
  padding: 6px 10px;
  border-radius: 10px;
  color: var(--eaf-chat-text, inherit);
  background: color-mix(in srgb, #f59e0b 12%, transparent);
}
.eaf-shortcuts__tip strong {
  font-weight: 600;
  color: #b45309;
}
.eaf-shortcuts__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.eaf-shortcuts__label {
  font-size: 12px;
  opacity: 0.7;
}
.eaf-shortcuts__chip {
  font: inherit;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, currentColor 18%, transparent);
  background: transparent;
  color: inherit;
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eaf-shortcuts__chip.is-here {
  border-color: color-mix(in srgb, var(--eaf-chat-accent, #3b82f6) 55%, transparent);
}
.eaf-shortcuts__chip:hover:not(:disabled) {
  background: color-mix(in srgb, var(--eaf-chat-accent, #3b82f6) 10%, transparent);
}
.eaf-shortcuts__chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.eaf-shortcuts__close {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
</style>
