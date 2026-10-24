<script setup lang="ts">
import { computed } from 'vue'
import LiquidGlassLayer from '@/components/common/LiquidGlassLayer.vue'
import { Connection, Grid, Lock, Share } from '@element-plus/icons-vue'
import type {
  DashboardWidgetDefinition,
  DashboardWidgetConfig,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import { listWidgetDefinitions, resolveWidgetTitle } from '../widgetRegistry'
import { availableDashboardShortcuts, SHORTCUT_WIDGET_KEY, shortcutPresetConfig, shortcutPresets } from '../dashboardShortcuts'

/**
 * Widget 目录抽屉：区分可添加、已添加（仍可再次添加）与不可用。
 * 不可用项诚实展示禁用原因，不允许以近似数据冒充。
 */
const props = defineProps<{
  open: boolean
  items: DashboardWidgetInstance[]
  permissions?: readonly string[]
}>()

const emit = defineEmits<{
  close: []
  add: [widgetKey: string, config?: DashboardWidgetConfig]
}>()

interface CatalogEntry {
  definition: DashboardWidgetDefinition
  addedCount: number
  addable: boolean
  config?: DashboardWidgetConfig
  catalogKey: string
}

const categoryLabels: Record<string, string> = {
  NAVIGATION: '快捷入口',
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
  listWidgetDefinitions().flatMap((definition): CatalogEntry[] => {
    const addedCount = props.items.filter((item) => item.widgetKey === definition.key).length
    if (definition.key === SHORTCUT_WIDGET_KEY) {
      const available = new Set(availableDashboardShortcuts(props.permissions ?? []).map(item => item.id))
      return [...shortcutPresets.map(preset => ({
        definition: { ...definition, title: preset.title, description: preset.description },
        catalogKey: `${definition.key}.${preset.key}`,
        config: shortcutPresetConfig(preset.key, props.permissions),
        addedCount: props.items.filter(item => item.widgetKey === definition.key && item.config.shortcutPreset === preset.key).length,
        addable: preset.ids.some(id => available.has(id)),
      })), { definition: { ...definition, title: '自定义快捷入口' }, catalogKey: definition.key,
        config: shortcutPresetConfig('custom'),
        addedCount: props.items.filter(item => item.widgetKey === definition.key && (!item.config.shortcutPreset || item.config.shortcutPreset === 'custom')).length,
        addable: available.size > 0 }]
    }
    return [{
      definition,
      catalogKey: definition.key,
      addedCount,
      addable: definition.availability.state === 'AVAILABLE',
    }]
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

function instanceTitlesOf(widgetKey: string, preset?: DashboardWidgetConfig['shortcutPreset']) {
  return props.items
    .filter((item) => item.widgetKey === widgetKey && (!preset || item.config.shortcutPreset === preset || (preset === 'custom' && !item.config.shortcutPreset)))
    .map((item) => resolveWidgetTitle(item))
}

function presetIcon(preset?: DashboardWidgetConfig['shortcutPreset']) {
  return preset === 'developer' ? Connection : preset === 'operator' ? Share : preset === 'admin' ? Lock : Grid
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
        class="dash-catalog app-frosted-overlay liquid-glass-host"
        role="dialog"
        aria-modal="true"
        aria-label="看板组件目录"
        data-testid="widget-catalog"
      >
        <LiquidGlassLayer host-selector=".app-frosted-overlay" />
        <header class="dash-catalog__header">
          <div>
            <h3>看板组件目录</h3>
            <p>组合常用入口和数据组件，打造自己的工作台。布局保存在此浏览器。</p>
          </div>
          <button type="button" class="dash-catalog__close" aria-label="关闭组件目录" @click="emit('close')">✕</button>
        </header>
        <div class="dash-catalog__body">
          <section v-for="[label, entries] in grouped" :key="label" class="dash-catalog__group">
            <h4>{{ label }}</h4>
            <ul>
              <li
                v-for="entry in entries"
                :key="entry.catalogKey"
                :class="{ 'is-disabled': !entry.addable, 'is-shortcut': entry.definition.category === 'NAVIGATION' }"
                :data-catalog-widget="entry.definition.key"
                :data-catalog-preset="entry.config?.shortcutPreset"
              >
                <div class="dash-catalog__meta">
                  <strong><component v-if="entry.definition.category === 'NAVIGATION'" :is="presetIcon(entry.config?.shortcutPreset)" class="dash-catalog__preset-icon" aria-hidden="true" />{{ entry.definition.title }}</strong>
                  <p v-if="!entry.addable" class="dash-catalog__reason">
                    不可用：{{ entry.definition.availability.reason ?? '当前账号没有这组入口的访问权限' }}
                  </p>
                  <p v-else>{{ entry.definition.description }}</p>
                  <small v-if="entry.addable && entry.addedCount > 0">
                    已添加 {{ entry.addedCount }} 个实例（{{ instanceTitlesOf(entry.definition.key, entry.config?.shortcutPreset).join('、') }}），可再次添加
                  </small>
                  <small v-else-if="entry.addable">未添加</small>
                </div>
                <button
                  v-if="entry.addable"
                  type="button"
                  :aria-label="`添加组件 ${entry.definition.title}`"
                  @click="emit('add', entry.definition.key, entry.config)"
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
  padding: var(--layout-sidebar-inset-block);
  background: var(--ops-overlay-backdrop);
}

.dash-catalog {
  width: min(420px, 92vw);
  height: 100%;
  display: flex;
  flex-direction: column;
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
  background: var(--surface-glass-inset);
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

.dash-catalog__preset-icon { width: 16px; height: 16px; flex: 0 0 16px; color: var(--ops-blue); }
.dash-catalog__group li.is-shortcut {
  padding: 13px 12px;
  border-radius: 10px;
  background: var(--surface-glass-inset);

  strong { display: flex; align-items: center; gap: 7px; font-size: 13px; }
  p { margin-top: 7px; font-size: 11px; }
  small { font-size: 11px; }
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
