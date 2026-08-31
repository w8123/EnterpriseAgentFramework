<script setup lang="ts">
import type {
  DashboardDomainState,
  DashboardServiceHealthSignal,
} from '@/types/operationsDashboard'

defineProps<{
  health: DashboardDomainState<DashboardServiceHealthSignal[]>
}>()
</script>

<template>
  <div class="ops-health-signals widget-body" aria-label="服务数据源状态">
    <span v-if="health.status === 'loading'" class="ops-health-signal">服务状态加载中</span>
    <span v-else-if="health.status === 'error'" class="ops-health-signal is-offline"><i />健康聚合不可用</span>
    <span
      v-for="service in health.data"
      v-else
      :key="service.key"
      class="ops-health-signal"
      :class="`is-${service.status}`"
    ><i />{{ service.name }}</span>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  flex-wrap: wrap;
  align-content: flex-start;
  padding: 10px 12px;
  gap: 6px;
}
</style>
