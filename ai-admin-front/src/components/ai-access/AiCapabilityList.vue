<template>
  <div class="ai-capabilities">
    <p v-if="loading && !groups.length" class="ai-capabilities__muted" role="status">正在加载…</p>
    <p v-else-if="error" class="ai-capabilities__error" role="alert">{{ error }}</p>
    <LiquidGlassEmptyState v-else-if="!groups.length" :title="emptyTitle" :description="emptyDescription" />
    <section v-for="group in groups" :key="group.key" class="ai-capabilities__group" :data-capability-group="group.key">
      <header>
        <h3>{{ group.title }}</h3>
        <p v-if="group.subtitle">{{ group.subtitle }}</p>
      </header>
      <div v-for="mode in modes" :key="mode.key" v-show="itemsOf(group, mode.key).length" class="ai-capabilities__mode">
        <h4>
          <span class="ai-capabilities__badge" :class="`is-${mode.key}`">{{ mode.label }}</span>
          <small>{{ mode.hint }}</small>
        </h4>
        <ul>
          <li v-for="item in itemsOf(group, mode.key)" :key="item.name" :title="item.name">
            {{ item.description || item.name }}
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { LiquidGlassEmptyState } from '@/components/common/liquid-glass'
import type { AiCapabilityGroup, AiCapabilityMode } from './aiAccess'

withDefaults(defineProps<{
  groups: AiCapabilityGroup[]
  loading?: boolean
  error?: string
  emptyTitle?: string
  emptyDescription?: string
}>(), { loading: false, error: '', emptyTitle: '暂时没有可以让 AI 办的事', emptyDescription: '请联系管理员开通。' })

const modes: { key: AiCapabilityMode; label: string; hint: string }[] = [
  { key: 'read', label: '直接执行', hint: '只读，AI 可以直接替你查' },
  { key: 'trial', label: '只读试运行', hint: '会真实执行调用，可能消耗额度；调用身份见具体说明' },
  { key: 'approval', label: '需要审核', hint: '写操作，先提交审核，通过后才执行' },
  { key: 'write', label: '直接修改', hint: '会保存修改，具体影响范围见能力说明' },
]

function itemsOf(group: AiCapabilityGroup, mode: AiCapabilityMode) {
  return group.items.filter(item => item.mode === mode)
}
</script>

<style scoped lang="scss">
.ai-capabilities { display: flex; flex-direction: column; gap: 12px; }
.ai-capabilities__muted { margin: 0; color: var(--text-secondary); font-size: 13px; }
.ai-capabilities__error { margin: 0; color: var(--status-danger); font-size: 13px; }

.ai-capabilities__group {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 18px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-md);
  background: var(--surface-glass-inset);

  header h3 { margin: 0; color: var(--text-primary); font-size: 15px; }
  header p { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
}

.ai-capabilities__mode {
  h4 { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0 0 6px; font-size: 13px; font-weight: 500; }
  small { color: var(--text-secondary); font-size: 12px; }
  ul { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 6px 16px; margin: 0; padding-left: 18px; }
  li { color: var(--text-primary); font-size: 13px; line-height: 1.6; }
}

.ai-capabilities__badge {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;

  &.is-read { color: var(--status-success); background: var(--status-success-soft); }
  &.is-trial { color: var(--brand-on-surface); background: var(--surface-glass-navigation); }
  &.is-approval { color: var(--status-warning); background: var(--status-warning-soft); }
  &.is-write { color: var(--brand-on-surface); background: var(--surface-glass-navigation); }
}
</style>
