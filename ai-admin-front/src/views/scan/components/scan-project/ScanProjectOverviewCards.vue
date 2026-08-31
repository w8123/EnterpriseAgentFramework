<script setup lang="ts">
import { computed } from 'vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import type { ApiGovernanceStage } from '@/views/scan/composables/useScanProjectSummary'

const fallbackGovernanceStages: ApiGovernanceStage[] = [
  { key: 'discover', label: '发现 API', value: '-', desc: '先完成扫描或 SDK 同步', status: 'active' },
  { key: 'semantic', label: '补全 AI 语义', value: '-', desc: '等待接口目录生成', status: 'todo' },
  { key: 'tool', label: '纳入能力目录', value: '-', desc: '等待项目接口', status: 'todo' },
  { key: 'agent', label: '用于 Agent', value: '-', desc: '纳管后开放给 Agent', status: 'todo' },
]

const props = defineProps<{
  stages: ApiGovernanceStage[]
}>()

const governanceEntries = computed(() => (props.stages.length ? props.stages : fallbackGovernanceStages)
  .filter((stage) => stage.key !== 'risk'))

const stageIconKeyMap: Record<string, string> = {
  discover: 'api-discovery',
  semantic: 'ai-semantic',
  tool: 'tool-publish',
  agent: 'agent-ready',
}

const stageStatusTextMap: Record<ApiGovernanceStage['status'], string> = {
  done: '已完成',
  active: '当前',
  todo: '待处理',
  warning: '需关注',
  danger: '风险',
}

const stageToneMap: Record<ApiGovernanceStage['status'], string> = {
  done: 'success',
  active: 'brand',
  todo: 'muted',
  warning: 'warning',
  danger: 'danger',
}

function stageIconKey(key: string) {
  return stageIconKeyMap[key] || 'scan'
}

function stageStatusText(status: ApiGovernanceStage['status']) {
  return stageStatusTextMap[status] || '待处理'
}

function stageTone(status: ApiGovernanceStage['status']) {
  return stageToneMap[status] || 'muted'
}
</script>

<template>
  <section class="governance-focus-panel">
    <div class="governance-stage-grid" aria-label="项目接口治理状态">
      <article
        v-for="entry in governanceEntries"
        :key="entry.key"
        class="governance-stage-card"
        :class="`is-${entry.status}`"
      >
        <MetricIconBg
          class="governance-stage-icon"
          :icon-key="stageIconKey(entry.key)"
          :tone="stageTone(entry.status)"
        />
        <div class="governance-stage-copy">
          <span class="governance-stage-label">{{ entry.label }}</span>
          <strong>{{ entry.value }}</strong>
          <small>{{ entry.desc }}</small>
        </div>
        <span class="governance-stage-status">{{ stageStatusText(entry.status) }}</span>
      </article>
    </div>
  </section>
</template>
