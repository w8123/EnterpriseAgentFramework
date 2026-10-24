<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowDown, ArrowUp, Close, Plus, Rank } from '@element-plus/icons-vue'
import type { DashboardWidgetConfig } from '@/types/operationsDashboard'
import {
  availableDashboardShortcuts,
  dashboardShortcuts,
  resolveShortcutIds,
  shortcutPresetConfig,
  shortcutPresets,
  type ShortcutPresetKey,
} from '../dashboardShortcuts'

const props = defineProps<{ modelValue: DashboardWidgetConfig; permissions: readonly string[] }>()
const emit = defineEmits<{ 'update:modelValue': [config: DashboardWidgetConfig] }>()
const selectedIds = computed(() => resolveShortcutIds(props.modelValue))
const available = computed(() => availableDashboardShortcuts(props.permissions))
const allowedIds = computed(() => new Set(available.value.map(item => item.id)))
const selected = computed(() => selectedIds.value.flatMap(id => dashboardShortcuts.find(item => item.id === id) ?? []))
const remaining = computed(() => available.value.filter(item => !selectedIds.value.includes(item.id)))
const dragId = ref<string | null>(null)

function choosePreset(key: ShortcutPresetKey) {
  emit('update:modelValue', { ...props.modelValue, ...shortcutPresetConfig(key, props.permissions) })
}

function update(ids: string[]) {
  emit('update:modelValue', { ...props.modelValue, shortcutPreset: 'custom', shortcutIds: ids })
}

function add(event: Event) {
  const select = event.target as HTMLSelectElement
  if (remaining.value.some(item => item.id === select.value)) update([...selectedIds.value, select.value])
  select.value = ''
}

function move(id: string, delta: number) {
  const ids = [...selectedIds.value]
  const from = ids.indexOf(id)
  const to = from + delta
  if (from < 0 || to < 0 || to >= ids.length) return
  ids.splice(to, 0, ...ids.splice(from, 1))
  update(ids)
}

function drop(target: string) {
  if (!dragId.value || dragId.value === target) return
  const from = selectedIds.value.indexOf(dragId.value)
  const to = selectedIds.value.indexOf(target)
  if (from >= 0 && to >= 0) move(dragId.value, to - from)
  dragId.value = null
}
</script>

<template>
  <div class="shortcut-editor" data-testid="shortcut-editor">
    <div class="shortcut-editor__section-head"><span>从预设开始</span><small>选择后可继续调整</small></div>
    <div class="shortcut-editor__presets" aria-label="快捷入口预设">
      <button
        v-for="preset in shortcutPresets"
        :key="preset.key"
        type="button"
        :aria-pressed="modelValue.shortcutPreset === preset.key"
        :data-shortcut-preset="preset.key"
        @click="choosePreset(preset.key)"
      >
        <strong>{{ preset.label }}</strong>
        <span>{{ preset.description }}</span>
      </button>
    </div>

    <div class="shortcut-editor__section-head">
      <label for="shortcut-add">显示入口 <small>{{ selected.length }}</small></label>
      <span class="shortcut-editor__order-hint"><Rank aria-hidden="true" />拖动或用箭头排序</span>
    </div>
    <ol class="shortcut-editor__list" aria-label="已选快捷入口">
      <li
        v-for="(shortcut, index) in selected"
        :key="shortcut.id"
        :class="{ 'is-dragging': dragId === shortcut.id, 'is-unavailable': !allowedIds.has(shortcut.id) }"
        :data-selected-shortcut="shortcut.id"
        draggable="true"
        @dragstart="dragId = shortcut.id; if ($event.dataTransfer) $event.dataTransfer.effectAllowed = 'move'"
        @dragend="dragId = null"
        @dragover.prevent
        @drop.prevent="drop(shortcut.id)"
      >
        <Rank class="shortcut-editor__grip" aria-hidden="true" />
        <span class="shortcut-editor__icon"><component :is="shortcut.icon" aria-hidden="true" /></span>
        <span class="shortcut-editor__name">{{ shortcut.label }}<small v-if="!allowedIds.has(shortcut.id)">当前无访问权限</small></span>
        <div class="shortcut-editor__row-actions">
          <button type="button" :disabled="index === 0" :aria-label="`上移${shortcut.label}`" @click="move(shortcut.id, -1)"><ArrowUp /></button>
          <button type="button" :disabled="index === selected.length - 1" :aria-label="`下移${shortcut.label}`" @click="move(shortcut.id, 1)"><ArrowDown /></button>
          <button type="button" class="is-remove" :aria-label="`移除${shortcut.label}`" @click="update(selectedIds.filter(id => id !== shortcut.id))"><Close /></button>
        </div>
      </li>
    </ol>
    <p v-if="!selected.length" class="shortcut-editor__empty">选择一套预设，或添加你常用的功能。</p>
    <div class="shortcut-editor__add">
      <Plus aria-hidden="true" />
      <select id="shortcut-add" aria-label="添加快捷入口" :disabled="!remaining.length" @change="add">
        <option value="">{{ remaining.length ? '添加快捷入口' : '已添加全部可用入口' }}</option>
        <option v-for="shortcut in remaining" :key="shortcut.id" :value="shortcut.id">{{ shortcut.label }}</option>
      </select>
    </div>
  </div>
</template>

<style scoped lang="scss">
.shortcut-editor { display: grid; gap: 10px; }
.shortcut-editor svg { width: 16px; height: 16px; flex-shrink: 0; }
.shortcut-editor__section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  color: var(--ops-text-secondary);
  font-size: 12px;

  small { color: var(--ops-text-muted); font-size: 11px; font-weight: 400; }
  label small { margin-left: 5px; font-variant-numeric: tabular-nums; }
}
.shortcut-editor__presets {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;

  button {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 12px;
    color: var(--ops-text);
    text-align: left;
    background: var(--surface-glass-inset);
    border: 1px solid var(--ops-border);
    border-radius: 9px;
    cursor: pointer;
  }

  button[aria-pressed='true'] { border-color: var(--ops-blue); background: var(--ops-surface-hover); }
  button:hover { border-color: var(--ops-border-strong); }
  strong { font-size: 12px; font-weight: 600; }
  span { color: var(--ops-text-secondary); font-size: 11px; line-height: 1.6; }
}
.shortcut-editor__order-hint { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: var(--ops-text-muted); }
.shortcut-editor__list { display: grid; gap: 5px; padding: 0; margin: 0; list-style: none; }
.shortcut-editor__list li {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid var(--ops-divider);
  border-radius: 8px;
  background: var(--surface-glass-inset);

  &.is-dragging { opacity: .45; }
  &.is-unavailable .shortcut-editor__name { color: var(--ops-text-muted); }
}
.shortcut-editor__grip { color: var(--ops-text-muted); cursor: grab; }
.shortcut-editor__icon { display: grid; place-items: center; width: 28px; height: 28px; color: var(--ops-blue); background: var(--ops-surface-hover); border-radius: 7px; }
.shortcut-editor__name { flex: 1; min-width: 0; color: var(--ops-text-strong); font-size: 12px; }
.shortcut-editor__name small { display: block; color: var(--ops-text-muted); font-size: 11px; }
.shortcut-editor__row-actions {
  display: flex;
  gap: 3px;

  button { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; color: var(--ops-text-secondary); background: transparent; border: 0; border-radius: 5px; cursor: pointer; }
  button:hover { color: var(--ops-blue); background: var(--ops-surface-hover); }
  button.is-remove:hover { color: var(--ops-danger); }
  button:disabled { opacity: .25; cursor: default; }
}
.shortcut-editor__add {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border: 1px dashed var(--ops-border-strong);
  border-radius: 8px;
  color: var(--ops-blue);

  select { flex: 1; min-width: 0; padding: 10px 0; border: 0; color: var(--ops-text-secondary); background: transparent; font-size: 12px; cursor: pointer; }
  option { color: var(--ops-text); background: var(--ops-panel); }
}
.shortcut-editor__empty { margin: 4px 0; padding: 12px; text-align: center; color: var(--ops-text-secondary); font-size: 12px; }
@media (max-width: 520px) {
  .shortcut-editor__presets { grid-template-columns: minmax(0, 1fr); }
  .shortcut-editor__presets button { gap: 2px; padding: 9px 12px; }
  .shortcut-editor__grip, .shortcut-editor__order-hint { display: none; }
  .shortcut-editor__list li { gap: 6px; }
  .shortcut-editor__row-actions button { width: 30px; height: 36px; }
}
</style>
