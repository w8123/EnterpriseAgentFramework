<script setup lang="ts">
import type {
  DashboardDomainStatus,
  DashboardProjectSummaryItem,
} from '@/types/operationsDashboard'
import DashboardPanelState from '../DashboardPanelState.vue'

defineProps<{
  status: DashboardDomainStatus
  projects: DashboardProjectSummaryItem[]
  maxRuns: number
}>()

defineEmits<{
  open: [project: DashboardProjectSummaryItem]
  viewAll: []
  retry: []
}>()
</script>

<template>
  <div class="widget-body">
    <div v-if="status === 'loading'" class="ops-ranking-state" aria-busy="true">项目排行加载中…</div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="项目排行暂不可用"
      detail="项目目录或运行样本加载失败。"
      tone="error"
      @retry="$emit('retry')"
    />
    <DashboardPanelState
      v-else-if="!projects.length"
      title="暂无可归属的项目运行样本"
      detail="接入业务系统并产生带 projectCode 的运行后显示排行。"
    />
    <div v-else class="ops-project-ranking">
      <button
        v-for="(project, index) in projects"
        :key="project.key"
        type="button"
        class="ops-project-rank"
        @click="$emit('open', project)"
      >
        <span class="ops-project-rank__index">{{ index + 1 }}</span>
        <span class="ops-project-rank__name">{{ project.name }}</span>
        <span class="ops-project-rank__bar"><i :style="{ width: `${(project.runs / maxRuns) * 100}%` }" /></span>
        <span class="ops-project-rank__value">{{ project.runs }}</span>
      </button>
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
