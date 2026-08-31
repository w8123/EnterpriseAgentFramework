<script setup lang="ts">
import { ref, watch } from 'vue'
import type { DashboardWidgetInstance } from '@/types/operationsDashboard'
import { getWidgetDefinition } from '../widgetRegistry'

/**
 * Widget 设置面板：修改实例标题；支持配置字段（topN / 显示数量）。
 * 修改只保留在对话框草稿内，点击“应用”才提交并形成一条撤销历史；
 * “取消”不改变 widget 配置与渲染。
 */
const props = defineProps<{
  instance: DashboardWidgetInstance | null
}>()

const emit = defineEmits<{
  close: []
  apply: [patch: { title?: string; topN?: number; displayCount?: number }]
}>()

const titleDraft = ref('')
const numberDrafts = ref<Record<string, number>>({})

watch(
  () => props.instance,
  (instance) => {
    if (!instance) return
    titleDraft.value = instance.config.title ?? ''
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
  const patch: { title?: string; topN?: number; displayCount?: number } = { title: titleDraft.value }
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
        class="dash-settings"
        role="dialog"
        aria-modal="true"
        aria-label="组件设置"
        data-testid="widget-settings"
      >
        <header class="dash-settings__header">
          <h3>组件设置</h3>
          <button type="button" class="dash-settings__close" aria-label="关闭设置" @click="emit('close')">✕</button>
        </header>
        <div class="dash-settings__body">
          <label class="dash-settings__field">
            <span>实例标题</span>
            <input
              v-model="titleDraft"
              type="text"
              data-testid="widget-settings-title"
              maxlength="40"
              placeholder="留空使用组件默认标题"
            >
          </label>
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
          <p class="dash-settings__hint">设置仅保存在布局草稿中，随“保存布局”写入当前浏览器布局。</p>
        </div>
        <footer class="dash-settings__footer">
          <button type="button" class="is-primary" @click="apply(); emit('close')">应用</button>
          <button type="button" @click="emit('close')">取消</button>
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
  background: var(--ops-overlay-surface);
  border: 1px solid var(--ops-border-strong);
  border-radius: 10px;
  box-shadow: var(--ops-overlay-shadow);
}

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
}

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
