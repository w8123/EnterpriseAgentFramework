<template>
  <div class="ai-capabilities" :aria-busy="loading">
    <LiquidGlassEmptyState v-if="loading" compact class="ai-capabilities__state" title="正在加载能力…" />
    <LiquidGlassNotice v-else-if="error" type="error" :title="error" />
    <LiquidGlassEmptyState v-else-if="!groups.length" compact class="ai-capabilities__state"
      :title="emptyTitle" :description="emptyDescription" />
    <template v-else>
      <div class="ai-capabilities__toolbar">
        <header class="ai-capabilities__scope">
          <h2>{{ singleGroup?.title ?? '可用能力' }} <span class="ai-capabilities__count">{{ total }} 项能力</span></h2>
          <p v-if="singleGroup?.subtitle">{{ singleGroup.subtitle }}</p>
        </header>
        <LiquidGlassInput
          v-if="total"
          ref="searchInput"
          v-model="keyword"
          class="ai-capabilities__search"
          source="backdrop"
          :prefix-icon="Search"
          placeholder="搜索能力说明或工具名"
          aria-label="搜索能力说明或工具名"
        >
          <template #suffix>
            <LiquidGlassButton v-if="keyword" link :icon="Close" class="ai-capabilities__clear"
              aria-label="清除搜索" title="清除搜索" @click="clearSearch" />
          </template>
        </LiquidGlassInput>
      </div>

      <div v-if="availableModes.length > 1" class="ai-capabilities__filters" role="group" aria-label="按执行方式筛选">
        <button type="button" :aria-pressed="selectedMode === 'all'" @click="selectedMode = 'all'">
          <el-icon aria-hidden="true"><Grid /></el-icon>
          <span>全部</span><span class="ai-capabilities__count">{{ total }}</span>
        </button>
        <button v-for="mode in availableModes" :key="mode.key" type="button"
          :class="`is-${mode.key}`" :aria-pressed="selectedMode === mode.key" @click="selectedMode = mode.key">
          <el-icon aria-hidden="true"><component :is="mode.icon" /></el-icon>
          <span>{{ mode.label }}</span><span class="ai-capabilities__count">{{ modeCounts[mode.key] }}</span>
        </button>
      </div>

      <LiquidGlassEmptyState v-if="!visibleGroups.length" compact class="ai-capabilities__state"
        title="没有符合条件的能力" :description="availableModes.length > 1 ? '换个关键词，或切换执行方式。' : '换个关键词试试。'" />
      <section v-for="group in visibleGroups" :key="group.key" class="ai-capabilities__group" :data-capability-group="group.key">
        <header v-if="!singleGroup" class="ai-capabilities__scope">
          <h3>{{ group.title }}</h3>
          <p v-if="group.subtitle">{{ group.subtitle }}</p>
        </header>
        <p v-if="!group.sections.length" class="ai-capabilities__muted">这个系统暂时没有开放给 AI 的能力。</p>
        <section v-for="section in group.sections" :key="section.key" class="ai-capabilities__mode" :class="`is-${section.key}`">
          <header class="ai-capabilities__mode-heading">
            <span class="ai-capabilities__mode-icon" aria-hidden="true"><el-icon><component :is="section.icon" /></el-icon></span>
            <div>
              <component :is="singleGroup ? 'h3' : 'h4'">
                {{ section.label }} <span class="ai-capabilities__count">{{ section.items.length }} 项</span>
              </component>
              <p>{{ section.hint }}</p>
            </div>
          </header>
          <ul class="ai-capabilities__entries" role="list">
            <li v-for="item in section.items.slice(0, previewSize)" :key="item.name">
              <p v-if="item.description?.trim()">{{ item.description }}</p>
              <code>{{ item.name }}</code>
            </li>
          </ul>
          <details v-if="section.items.length > previewSize" :key="term" class="ai-capabilities__more">
            <summary><el-icon aria-hidden="true"><ArrowRight /></el-icon> 查看其余 {{ section.items.length - previewSize }} 项</summary>
            <ul class="ai-capabilities__entries" role="list">
              <li v-for="item in section.items.slice(previewSize)" :key="item.name">
                <p v-if="item.description?.trim()">{{ item.description }}</p>
                <code>{{ item.name }}</code>
              </li>
            </ul>
          </details>
        </section>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, Close, EditPen, Grid, Lock, Search, VideoPlay, View } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassInput, LiquidGlassNotice } from '@/components/common/liquid-glass'
import type { AiCapabilityGroup, AiCapabilityMode } from './aiAccess'

const props = withDefaults(defineProps<{
  groups: AiCapabilityGroup[]
  loading?: boolean
  error?: string
  emptyTitle?: string
  emptyDescription?: string
}>(), { loading: false, error: '', emptyTitle: '暂时没有可以让 AI 办的事', emptyDescription: '请联系管理员开通。' })

const modes = [
  { key: 'read', label: '直接执行', hint: 'AI 可以直接查询。', icon: View },
  { key: 'trial', label: '只读试运行', hint: '会真实执行调用，可能消耗额度。调用身份见能力说明。', icon: VideoPlay },
  { key: 'approval', label: '需要审核', hint: '写操作先提交审核，通过后才执行。', icon: Lock },
  { key: 'write', label: '直接修改', hint: '会保存修改，影响范围见能力说明。', icon: EditPen },
] as const

const previewSize = 6
const keyword = ref('')
const selectedMode = ref<AiCapabilityMode | 'all'>('all')
const searchInput = ref<InstanceType<typeof LiquidGlassInput>>()
const singleGroup = computed(() => props.groups.length === 1 ? props.groups[0] : undefined)
const allItems = computed(() => props.groups.flatMap(group => group.items))
const total = computed(() => allItems.value.length)
const modeCounts = computed(() => {
  const counts = { read: 0, trial: 0, approval: 0, write: 0 }
  for (const item of allItems.value) counts[item.mode] += 1
  return counts
})
const availableModes = computed(() => modes.filter(mode => modeCounts.value[mode.key] > 0))
const term = computed(() => keyword.value.trim().toLowerCase())

const visibleGroups = computed(() => props.groups.map(group => ({
  key: group.key,
  title: group.title,
  subtitle: group.subtitle,
  sections: modes
    .filter(mode => selectedMode.value === 'all' || selectedMode.value === mode.key)
    .map(mode => ({
      ...mode,
      items: group.items.filter(item => item.mode === mode.key && (
        !term.value || item.name.toLowerCase().includes(term.value) || item.description?.toLowerCase().includes(term.value)
      )),
    }))
    .filter(mode => mode.items.length),
})).filter(group => group.sections.length || (!term.value && selectedMode.value === 'all')))

watch(availableModes, (available) => {
  if (available.length < 2 || !available.some(mode => mode.key === selectedMode.value)) selectedMode.value = 'all'
})

function clearSearch() {
  keyword.value = ''
  searchInput.value?.focus()
}
</script>

<style scoped lang="scss">
.ai-capabilities {
  container: ai-capabilities / inline-size;
  display: grid;
  min-width: 0;
  gap: var(--layout-section-gap);
  padding: clamp(2px, 0.7vw, 10px);
}

.ai-capabilities__toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: var(--layout-section-gap) 24px;
}

.ai-capabilities__scope {
  min-width: 0;

  h2, h3 {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--layout-control-gap);
    margin: 0;
    color: var(--text-primary);
    font-size: 18px;
    font-weight: 650;
    overflow-wrap: anywhere;
  }
  h3 { font-size: 16px; }
  p { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
}

.ai-capabilities__count {
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  background: var(--status-neutral-soft);
  font-size: 12px;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ai-capabilities__search {
  flex: 1 1 252px;
  min-width: 0;
  max-width: 320px;
}

.ai-capabilities__clear {
  min-width: 26px;
  min-height: 26px;
}

.ai-capabilities__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--layout-control-gap);

  button {
    display: inline-flex;
    align-items: center;
    min-height: 38px;
    gap: 7px;
    padding: 4px 11px;
    border: 1px solid var(--border-readable);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    background: transparent;
    font: inherit;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;

    .el-icon { color: var(--mode-ink, var(--text-secondary)); font-size: 15px; }
    &:hover { background: var(--surface-hover); }
    &[aria-pressed='true'] {
      border-color: var(--border-focus);
      color: var(--brand-on-surface);
      background: var(--surface-reading-current);
    }
  }
}

.ai-capabilities__group {
  display: grid;
  gap: var(--layout-section-gap);
  min-width: 0;
}

.ai-capabilities__group + .ai-capabilities__group {
  padding-top: var(--layout-section-gap);
  border-top: 1px solid var(--border-readable);
}

.ai-capabilities__mode {
  min-width: 0;
  border: 1px solid var(--border-readable);
  border-radius: var(--radius-lg);
}

.ai-capabilities__mode-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  background: var(--surface-reading-stripe);

  h3, h4 {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin: 0;
    color: var(--text-primary);
    font-size: 14px;
    font-weight: 650;
  }
  p { margin: 2px 0 0; color: var(--text-secondary); font-size: 13px; line-height: 1.5; }
}

.ai-capabilities__mode-icon {
  display: grid;
  flex: 0 0 34px;
  height: 34px;
  place-items: center;
  border-radius: var(--radius-md);
  color: var(--mode-ink);
  background: var(--mode-wash);
  font-size: 17px;
}

.ai-capabilities__entries {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  column-gap: 28px;
  margin: 0;
  padding: 0 14px;
  list-style: none;

  li {
    min-width: 0;
    padding: 9px 0;
    border-top: 1px solid var(--border-divider);
    overflow-wrap: anywhere;
  }
  p { max-width: 86ch; margin: 0 0 3px; color: var(--text-primary); font-size: 14px; line-height: 1.6; }
  code { color: var(--text-muted); font: 12px/1.5 var(--font-family-mono); }
  code:only-child { color: var(--text-primary); }
}

.ai-capabilities__more {
  border-top: 1px solid var(--border-divider);

  summary {
    display: flex;
    align-items: center;
    gap: 6px;
    min-height: 36px;
    padding: 0 14px;
    border-radius: 0 0 var(--radius-lg) var(--radius-lg);
    color: var(--brand-on-surface);
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;

    &::-webkit-details-marker { display: none; }
    &:hover { background: var(--surface-hover); }
  }
  &[open] summary { border-radius: 0; }
  &[open] .el-icon { transform: rotate(90deg); }
}

.ai-capabilities__filters button:focus-visible,
.ai-capabilities__more summary:focus-visible,
.ai-capabilities__clear:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.ai-capabilities__state { min-height: 176px; }
.ai-capabilities__muted { margin: 0; color: var(--text-secondary); font-size: 13px; }

.is-read { --mode-ink: var(--status-success); --mode-wash: var(--status-success-soft); }
.is-trial { --mode-ink: var(--status-info); --mode-wash: var(--status-info-soft); }
.is-approval { --mode-ink: var(--status-warning); --mode-wash: var(--status-warning-soft); }
.is-write { --mode-ink: var(--brand-on-surface); --mode-wash: var(--surface-reading-current); }

@container ai-capabilities (min-width: 720px) {
  .ai-capabilities__entries { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@container ai-capabilities (max-width: 420px) {
  .ai-capabilities__search { max-width: none; }
  .ai-capabilities__filters {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 116px), 1fr));

    button { justify-content: space-between; gap: 4px; padding-inline: 8px; }
    button .el-icon { display: none; }
  }
}

@media (forced-colors: active) {
  .ai-capabilities__filters button[aria-pressed='true'] { border-color: Highlight; }
}
</style>
