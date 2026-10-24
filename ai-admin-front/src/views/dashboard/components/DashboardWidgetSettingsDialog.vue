<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import LiquidGlassLayer from '@/components/common/LiquidGlassLayer.vue'
import type { DashboardWidgetConfig, DashboardWidgetInstance } from '@/types/operationsDashboard'
import { getWidgetDefinition } from '../widgetRegistry'
import { SHORTCUT_WIDGET_KEY } from '../dashboardShortcuts'
import DashboardShortcutEditor from './DashboardShortcutEditor.vue'

/**
 * Widget 设置面板：修改实例标题；支持配置字段（topN / 显示数量）。
 * 修改只保留在对话框草稿内，点击“应用”才提交并形成一条撤销历史；
 * “取消”不改变 widget 配置与渲染。
 */
const props = defineProps<{
  instance: DashboardWidgetInstance | null
  permissions?: readonly string[]
  editing?: boolean
  saveError?: string | null
}>()

const emit = defineEmits<{
  close: []
  apply: [patch: DashboardWidgetConfig]
}>()

const titleDraft = ref('')
const numberDrafts = ref<Record<string, number>>({})
const shortcutDraft = ref<DashboardWidgetConfig>({})
const dialogRef = ref<HTMLElement | null>(null)
const isShortcuts = computed(() => props.instance?.widgetKey === SHORTCUT_WIDGET_KEY)
let previousFocus: HTMLElement | null = null

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return }
  if (event.key !== 'Tab') return
  const elements = [...(dialogRef.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input, select:not(:disabled)') ?? [])]
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

watch(() => Boolean(props.instance), async open => {
  if (open) {
    previousFocus = document.activeElement as HTMLElement | null
    document.addEventListener('keydown', handleKeydown)
    await nextTick()
    dialogRef.value?.querySelector<HTMLInputElement>('input')?.focus()
  } else {
    document.removeEventListener('keydown', handleKeydown)
    previousFocus?.focus()
  }
}, { immediate: true })
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

watch(
  () => props.instance,
  (instance) => {
    if (!instance) return
    titleDraft.value = instance.config.title ?? ''
    shortcutDraft.value = JSON.parse(JSON.stringify(instance.config)) as DashboardWidgetConfig
    const drafts: Record<string, number> = {}
    for (const field of getWidgetDefinition(instance.widgetKey)?.configFields ?? []) {
      const value = instance.config[field.key]
      drafts[field.key] = typeof value === 'number' ? value : (getWidgetDefinition(instance.widgetKey)?.defaultConfig[field.key] as number) ?? field.min
    }
    numberDrafts.value = drafts
  },
  { immediate: true },
)

function apply() {
  if (!props.instance) return
  const patch: DashboardWidgetConfig = { title: titleDraft.value }
  if (isShortcuts.value) {
    patch.shortcutIds = shortcutDraft.value.shortcutIds
    patch.shortcutPreset = shortcutDraft.value.shortcutPreset
  }
  for (const [key, value] of Object.entries(numberDrafts.value)) {
    patch[key as 'topN' | 'displayCount'] = value
  }
  emit('apply', patch)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="instance" class="dash-settings__overlay" @pointerdown.self="emit('close')">
      <aside
        ref="dialogRef"
        class="dash-settings app-frosted-overlay liquid-glass-host"
        :class="{ 'dash-settings--shortcuts': isShortcuts }"
        role="dialog"
        aria-modal="true"
        :aria-label="isShortcuts ? '自定义快捷入口' : '组件设置'"
        data-testid="widget-settings"
      >
        <LiquidGlassLayer host-selector=".app-frosted-overlay" />
        <header class="dash-settings__header">
          <div><span v-if="isShortcuts" class="dash-settings__eyebrow">我的工作台</span><h3>{{ isShortcuts ? '自定义快捷入口' : '组件设置' }}</h3></div>
          <button type="button" class="dash-settings__close" aria-label="关闭设置" @click="emit('close')">✕</button>
        </header>
        <div class="dash-settings__body">
          <label class="dash-settings__field">
            <span>{{ isShortcuts ? '组件名称' : '实例标题' }}</span>
            <input
              v-model="titleDraft"
              type="text"
              data-testid="widget-settings-title"
              maxlength="40"
              placeholder="留空使用组件默认标题"
            >
          </label>
          <DashboardShortcutEditor v-if="isShortcuts" v-model="shortcutDraft" :permissions="permissions ?? []" />
          <label
            v-for="field in getWidgetDefinition(instance.widgetKey)?.configFields ?? []"
            :key="field.key"
            class="dash-settings__field"
          >
            <span>{{ field.label }}（{{ field.min }}–{{ field.max }}）</span>
            <input
              v-model.number="numberDrafts[field.key]"
              type="number"
              :min="field.min"
              :max="field.max"
              step="1"
              :data-testid="`widget-settings-${field.key}`"
            >
          </label>
          <p class="dash-settings__hint">{{ editing ? '设置先应用到布局草稿，保存布局后生效。' : '仅为当前账号保存在此浏览器，其他人的工作台不受影响。' }}</p>
          <p v-if="saveError" class="dash-settings__error" role="alert">{{ saveError }}</p>
        </div>
        <footer class="dash-settings__footer">
          <button type="button" @click="emit('close')">取消</button>
          <button type="button" class="is-primary" @click="apply">{{ editing ? '应用' : '保存' }}</button>
        </footer>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.dash-settings__overlay {
  position: fixed;
  z-index: 41;
  inset: 0;
  display: grid;
  place-items: center;
  background: var(--ops-overlay-backdrop);
}

.dash-settings {
  width: min(360px, 92vw);
  max-height: calc(100dvh - 48px);
  display: flex;
  flex-direction: column;
}

.dash-settings--shortcuts { width: min(580px, calc(100vw - 32px)); }
.dash-settings__eyebrow { display: block; margin-bottom: 3px; color: var(--ops-text-secondary); font-size: 11px; }
.dash-settings__error { margin: 0; color: var(--ops-danger); font-size: 12px; }

.dash-settings__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--ops-divider);

  h3 {
    margin: 0;
    color: var(--ops-text-strong);
    font-size: 14px;
  }
}

.dash-settings__close {
  padding: 3px 8px;
  color: var(--ops-text-secondary);
  background: transparent;
  border: 1px solid var(--ops-border);
  border-radius: 4px;
  cursor: pointer;
}

.dash-settings__body {
  display: grid;
  gap: 12px;
  padding: 14px;
  min-height: 0;
  overflow-y: auto;
}

.dash-settings--shortcuts .dash-settings__header,
.dash-settings--shortcuts .dash-settings__footer { padding: 16px 20px; }
.dash-settings--shortcuts .dash-settings__body { padding: 18px 20px; gap: 20px; }

.dash-settings__field {
  display: grid;
  gap: 5px;

  span {
    color: var(--ops-text-secondary);
    font-size: 11px;
  }

  input {
    padding: 7px 9px;
    color: var(--ops-text-strong);
    background: var(--ops-input-background);
    border: 1px solid var(--ops-border);
    border-radius: 5px;
    font-size: 12px;
  }

  input:focus-visible {
    border-color: var(--ops-cyan);
    outline: none;
  }
}

.dash-settings__hint {
  margin: 0;
  color: var(--ops-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

.dash-settings__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid var(--ops-divider);

  button {
    padding: 6px 16px;
    color: var(--ops-text-secondary);
    background: var(--ops-surface-control-strong);
    border: 1px solid var(--ops-border);
    border-radius: 5px;
    cursor: pointer;
  }

  button.is-primary {
    color: var(--ops-primary-action-text);
    background: var(--ops-primary-action-background);
    border-color: var(--ops-primary-action-border);
  }

  button:hover,
  button:focus-visible {
    outline: none;
    filter: brightness(1.12);
  }
}
</style>
