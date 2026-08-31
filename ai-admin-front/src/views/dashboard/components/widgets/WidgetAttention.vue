<script setup lang="ts">
import type {
  DashboardAttentionSignal,
  DashboardDomainStatus,
} from '@/types/operationsDashboard'
import type { RouteLocationRaw } from 'vue-router'
import DashboardPanelState from '../DashboardPanelState.vue'

defineProps<{
  status: DashboardDomainStatus
  items: DashboardAttentionSignal[]
  hasSignals: boolean
  sampleCount: number
}>()

defineEmits<{
  open: [target: RouteLocationRaw]
  viewAll: []
  retry: []
}>()
</script>

<template>
  <div class="widget-body">
    <div v-if="status === 'loading'" class="ops-ranking-state" aria-busy="true">风险信号汇总中…</div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="风险信号暂不可用"
      detail="运行接口失败，不能据此宣布没有风险。"
      tone="error"
      @retry="$emit('retry')"
    />
    <div v-else-if="hasSignals" class="ops-attention">
      <button
        v-for="item in items"
        :key="item.key"
        type="button"
        :class="`is-${item.tone}`"
        @click="$emit('open', item.to)"
      >
        <span class="ops-attention__value">{{ item.value }}</span>
        <span class="ops-attention__copy"><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span>
      </button>
    </div>
    <div v-else class="ops-attention-clear">
      <strong>本次样本未发现异常信号</strong>
      <span>结论仅覆盖当前返回的 {{ sampleCount }} 条运行记录，不代表生产全量。</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
</style>
