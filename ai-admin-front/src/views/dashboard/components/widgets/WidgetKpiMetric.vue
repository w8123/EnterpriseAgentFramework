<script setup lang="ts">
import type { DashboardMetricCardModel } from '@/types/operationsDashboard'
import DashboardMetricCard from '../DashboardMetricCard.vue'

defineProps<{
  metric: DashboardMetricCardModel
}>()
</script>

<template>
  <DashboardMetricCard :metric="metric" class="widget-metric-fill" />
</template>

<style scoped lang="scss">
.widget-metric-fill {
  height: 100%;
}
</style>
