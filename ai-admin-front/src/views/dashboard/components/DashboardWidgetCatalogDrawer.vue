<script setup lang="ts">
import { computed } from 'vue'
import type {
  DashboardWidgetDefinition,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import { listWidgetDefinitions, resolveWidgetTitle } from '../widgetRegistry'

/**
 * Widget 目录抽屉：区分可添加、已添加（仍可再次添加）与不可用。
 * 不可用项诚实展示禁用原因，不允许以近似数据冒充。
 */
const props = defineProps<{
  open: boolean
  items: DashboardWidgetInstance[]
}>()

const emit = defineEmits<{
  close: []
  add: [widgetKey: string]
}>()

interface CatalogEntry {
  definition: DashboardWidgetDefinition
  addedCount: number
  addable: boolean
}

const categoryLabels: Record<string, string> = {
  KPI: '指标',
  RANKING: '排行',
  TREND: '趋势',
  QUALITY: '质量',
  PROJECT: '业务系统',
  DISTRIBUTION: '分布',
  ACTIVITY: '动态',
  SERVICE: '服务状态',
  INSIGHT: '洞察',
  COST: '成本',
  TELEMETRY: '遥测',
}

const catalog = computed<CatalogEntry[]>(() =>
  listWidgetDefinitions().map((definition) => {
    const addedCount = props.items.filter((item) => item.widgetKey === definition.key).length
    return {
      definition,
      addedCount,
      addable: definition.availability.state === 'AVAILABLE',
    }
  }),
)

const grouped = computed(() => {
  const groups = new Map<string, CatalogEntry[]>()
  for (const entry of catalog.value) {
    const label = categoryLabels[entry.definition.category] ?? entry.definition.category
    groups.set(label, [...(groups.get(label) ?? []), entry])
  }
  return [...groups.entries()]
})

function instanceTitlesOf(widgetKey: string) {
  return props.items
    .filter((item) => item.widgetKey === widgetKey)
    .map((item) => resolveWidgetTitle(item))
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="dash-catalog__overlay"
      @pointerdown.self="emit('close')"
    >
      <aside
        class="dash-catalog"
        role="dialog"
        aria-modal="true"
        aria-label="看板组件目录"
        data-testid="widget-catalog"
      >
        <header class="dash-catalog__header">
          <div>
            <h3>看板组件目录</h3>
            <p>同一组件可添加多个实例；保存仅写入当前浏览器布局。</p>
          </div>
          <button type="button" class="dash-catalog__close" aria-label="关闭组件目录" @click="emit('close')">✕</button>
        </header>
        <div class="dash-catalog__body">
          <section v-for="[label, entries] in grouped" :key="label" class="dash-catalog__group">
            <h4>{{ label }}</h4>
            <ul>
              <li
                v-for="entry in entries"
                :key="entry.definition.key"
                :class="{ 'is-disabled': !entry.addable }"
                :data-catalog-widget="entry.definition.key"
              >
                <div class="dash-catalog__meta">
                  <strong>{{ entry.definition.title }}</strong>
                  <p v-if="!entry.addable" class="dash-catalog__reason">
                    不可用：{{ entry.definition.availability.reason }}
                  </p>
                  <p v-else>{{ entry.definition.description }}</p>
                  <small v-if="entry.addable && entry.addedCount > 0">
                    已添加 {{ entry.addedCount }} 个实例（{{ instanceTitlesOf(entry.definition.key).join('、') }}），可再次添加
                  </small>
                  <small v-else-if="entry.addable">未添加</small>
                </div>
                <button
                  v-if="entry.addable"
                  type="button"
                  :aria-label="`添加组件 ${entry.definition.title}`"
                  @click="emit('add', entry.definition.key)"
                >添加</button>
                <span v-else class="dash-catalog__badge">不可用</span>
              </li>
            </ul>
          </section>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.dash-catalog__overlay {
  position: fixed;
  z-index: 40;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: var(--ops-overlay-backdrop);
}

.dash-catalog {
  width: min(420px, 92vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--ops-overlay-surface);
  border-left: 1px solid var(--ops-border-strong);
  box-shadow: var(--ops-overlay-shadow);
}

.dash-catalog__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--ops-divider);

  h3 {
    margin: 0;
    color: var(--ops-text-strong);
    font-size: 15px;
  }

  p {
    margin: 4px 0 0;
    color: var(--ops-text-muted);
    font-size: 10px;
  }
}

.dash-catalog__close {
  padding: 4px 8px;
  color: var(--ops-text-secondary);
  background: transparent;
  border: 1px solid var(--ops-border);
  border-radius: 4px;
  cursor: pointer;
}

.dash-catalog__body {
  flex: 1;
  overflow: auto;
  padding: 10px 14px 18px;
}

.dash-catalog__group h4 {
  margin: 14px 2px 6px;
  color: var(--ops-cyan);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.dash-catalog__group ul {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.dash-catalog__group li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 10px;
  background: var(--ops-surface-soft);
  border: 1px solid var(--ops-border);
  border-radius: 7px;
}

.dash-catalog__group li.is-disabled {
  opacity: 0.62;
  border-style: dashed;
}

.dash-catalog__meta strong {
  color: var(--ops-text-strong);
  font-size: 12px;
}

.dash-catalog__meta p {
  margin: 3px 0 0;
  color: var(--ops-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

.dash-catalog__meta p.dash-catalog__reason {
  color: var(--ops-warning);
}

.dash-catalog__meta small {
  display: block;
  margin-top: 4px;
  color: var(--ops-text-secondary);
  font-size: 9px;
}

.dash-catalog__group button {
  padding: 5px 12px;
  color: var(--ops-cyan);
  background: var(--ops-active-background);
  border: 1px solid color-mix(in srgb, var(--ops-blue) 30%, transparent);
  border-radius: 5px;
  cursor: pointer;
}

.dash-catalog__group button:hover,
.dash-catalog__group button:focus-visible {
  background: color-mix(in srgb, var(--ops-blue) 18%, var(--ops-panel));
  outline: none;
}

.dash-catalog__badge {
  padding: 5px 10px;
  color: var(--ops-text-muted);
  background: var(--ops-rank-surface);
  border: 1px dashed var(--ops-rank-border);
  border-radius: 5px;
  font-size: 10px;
}
</style>
