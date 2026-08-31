<script setup lang="ts">
import type {
  DashboardDomainStatus,
  DashboardProjectSummaryItem,
} from '@/types/operationsDashboard'
import DashboardPanelState from '../DashboardPanelState.vue'
import DashboardProjectCard from '../DashboardProjectCard.vue'

defineProps<{
  status: DashboardDomainStatus
  projects: DashboardProjectSummaryItem[]
}>()

defineEmits<{
  open: [project: DashboardProjectSummaryItem]
  viewAll: []
  retry: []
}>()
</script>

<template>
  <div class="widget-body">
    <div v-if="status === 'loading'" class="ops-projects-loading" aria-busy="true" aria-label="业务系统加载中">
      <span v-for="i in 3" :key="i" />
    </div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="业务系统数据暂不可用"
      detail="项目目录或 Agent 数据加载失败；没有把不可用状态显示为 0 个项目。"
      tone="error"
      @retry="$emit('retry')"
    />
    <DashboardPanelState
      v-else-if="!projects.length"
      title="尚未接入业务系统"
      detail="完成 Starter 注册或扫描项目接入后，这里会显示真实的项目运营入口。"
    />
    <div v-else class="ops-project-cards">
      <DashboardProjectCard
        v-for="(project, index) in projects"
        :key="project.key"
        :project="project"
        :index="index"
        @open="$emit('open', project)"
      />
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
