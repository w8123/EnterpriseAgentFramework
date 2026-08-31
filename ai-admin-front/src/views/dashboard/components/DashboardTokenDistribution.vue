<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardDomainStatus, DashboardTokenDistributionItem } from '@/types/operationsDashboard'
import { formatCompactNumber } from '../dashboardModel'
import DashboardPanelState from './DashboardPanelState.vue'

const props = defineProps<{
  status: DashboardDomainStatus
  items: DashboardTokenDistributionItem[]
}>()

defineEmits<{
  retry: []
}>()

const total = computed(() => props.items.reduce((sum, item) => sum + item.tokens, 0))
const donutStyle = computed(() => {
  if (!total.value) return {}
  let cursor = 0
  const stops = props.items.map((item) => {
    const start = cursor
    cursor += item.ratio
    return `${item.color} ${start}% ${cursor}%`
  })
  return { background: `conic-gradient(${stops.join(', ')})` }
})
</script>

<template>
  <div class="token-distribution widget-body">
    <header class="token-distribution__header">
      <p>仅统计样本中大于 0 的已记录 Token</p>
    </header>
    <div v-if="status === 'loading'" class="token-distribution__loading" aria-busy="true" aria-label="Token 分布加载中">
      <span />
      <i v-for="i in 4" :key="i" />
    </div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="Token 数据暂不可用"
      detail="运行数据源失败，未将错误降级为 0。"
      tone="error"
      @retry="$emit('retry')"
    />
    <DashboardPanelState
      v-else-if="!items.length"
      title="暂无可确认的 Token 记录"
      detail="当前 token_cost=0 无法区分真实零消耗与未采集，因此这里不显示 0 分布。"
      tone="disabled"
    />
    <div v-else class="token-distribution__body">
      <div class="token-distribution__donut" :style="donutStyle">
        <span>
          <strong>{{ formatCompactNumber(total) }}</strong>
          <small>已记录</small>
        </span>
      </div>
      <ul>
        <li v-for="item in items" :key="item.key">
          <i :style="{ background: item.color }" />
          <span>{{ item.name }}</span>
          <strong>{{ formatCompactNumber(item.tokens) }}</strong>
          <small>{{ item.ratio.toFixed(1) }}%</small>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.token-distribution__header {
  min-height: 36px;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 12px;
  padding: 8px 11px;
  background: var(--ops-panel-header-background);
  border-bottom: 1px solid var(--ops-divider);
}

.token-distribution__header p {
  margin: 0;
  color: var(--ops-text-muted);
  font-size: 9px;
}

.token-distribution__body {
  flex: 1;
  min-height: 180px;
  display: grid;
  grid-template-columns: minmax(96px, 132px) minmax(0, 1fr);
  gap: 18px;
  align-items: center;
  padding: 12px 15px 16px;
}

.token-distribution__donut {
  position: relative;
  width: 100%;
  max-width: 126px;
  aspect-ratio: 1;
  margin: 0 auto;
  border-radius: 50%;
  box-shadow: 0 0 26px color-mix(in srgb, var(--ops-blue) 12%, transparent);
}

.token-distribution__donut::after {
  content: '';
  position: absolute;
  inset: 24px;
  background: var(--ops-panel);
  border: 1px solid var(--ops-divider);
  border-radius: 50%;
}

.token-distribution__donut span {
  position: absolute;
  z-index: 1;
  inset: 0;
  display: grid;
  place-content: center;
  text-align: center;
}

.token-distribution__donut strong {
  color: var(--ops-text-strong);
  font: 700 18px/1.1 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.token-distribution__donut small {
  margin-top: 3px;
  color: var(--ops-text-muted);
  font-size: 9px;
}

.token-distribution ul {
  min-width: 0;
  display: grid;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.token-distribution li {
  min-width: 0;
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr) auto auto;
  gap: 7px;
  align-items: center;
  color: var(--ops-text-secondary);
  font-size: 10px;
}

.token-distribution li i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  box-shadow: 0 0 8px currentColor;
}

.token-distribution li span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.token-distribution li strong {
  color: var(--ops-text-strong);
  font: 600 10px/1 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.token-distribution li small {
  min-width: 40px;
  color: var(--ops-text-muted);
  text-align: right;
}

.token-distribution__loading {
  flex: 1;
  min-height: 180px;
  display: grid;
  grid-template-columns: minmax(96px, 126px) minmax(0, 1fr);
  gap: 18px;
  align-items: center;
  padding: 14px;
}

.token-distribution__loading > span {
  width: 100%;
  max-width: 118px;
  aspect-ratio: 1;
  margin: 0 auto;
  border: 15px solid color-mix(in srgb, var(--ops-blue) 10%, transparent);
  border-top-color: color-mix(in srgb, var(--ops-blue) 28%, transparent);
  border-radius: 50%;
}

.token-distribution__loading i {
  grid-column: 2;
  height: 10px;
  margin-top: -50px;
  background: var(--ops-track);
  border-radius: 3px;
}

@media (max-width: 580px) {
  .token-distribution__body { grid-template-columns: 1fr; }
  .token-distribution__donut { margin: 0 auto; }
}
</style>
