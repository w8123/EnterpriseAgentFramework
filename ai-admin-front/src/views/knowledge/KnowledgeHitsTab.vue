<template>
  <div class="knowledge-hits-tab">
    <div class="toolbar">
      <el-segmented v-model="sourceFilter" :options="sourceOptions" @change="fetchHits" />
      <el-switch v-model="lowConfidenceOnly" active-text="只看低置信" @change="fetchHits" />
      <span class="toolbar-hint">只记录问题和命中分数，不记录回答。</span>
      <el-button :loading="hitsLoading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>
    <DataTableShell
      density="compact"
      :empty="hitLogs.length === 0"
      :loading="hitsLoading"
      :empty-description="emptyDescription"
    >
      <el-table :data="hitLogs" stripe empty-text=" " max-height="420">
        <el-table-column prop="queryText" label="用户问题" min-width="260" show-overflow-tooltip />
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <StatusTag :label="hitSourceLabel(row.source)" :tone="row.source === 'RUNTIME' ? 'success' : 'info'" />
          </template>
        </el-table-column>
        <el-table-column label="分数" width="90">
          <template #default="{ row }">{{ formatScore(row.score) }}</template>
        </el-table-column>
        <el-table-column prop="fileName" label="命中文件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="180" />
        <el-table-column label="运行" width="90" align="center">
          <template #default="{ row }">
            <router-link v-if="row.traceId" :to="`/runops/${encodeURIComponent(row.traceId)}`">查看</router-link>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </DataTableShell>

    <div class="chunk-grid">
      <WorkbenchPanel title="命中最多的段落" density="compact">
        <DataTableShell density="compact" :empty="(dashboard?.hotChunks || []).length === 0">
          <el-table :data="dashboard?.hotChunks || []" size="small" max-height="300" empty-text=" ">
            <el-table-column label="段落" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sectionPath || row.title || truncate(row.content, 80) }}</template>
            </el-table-column>
            <el-table-column prop="hitCount" label="命中" width="80" align="center" />
          </el-table>
        </DataTableShell>
      </WorkbenchPanel>
      <WorkbenchPanel title="从未命中的段落" density="compact">
        <DataTableShell density="compact" :empty="(dashboard?.zeroHitChunks || []).length === 0">
          <el-table :data="dashboard?.zeroHitChunks || []" size="small" max-height="300" empty-text=" ">
            <el-table-column label="段落" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sectionPath || row.title || truncate(row.content, 100) }}</template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </WorkbenchPanel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { getKnowledgeHitLogs, getKnowledgeOpsDashboard } from '@/api/knowledge'
import type { KnowledgeHitLog, KnowledgeOpsDashboard } from '@/types/knowledge'
import { hitSourceLabel, type HitSourceFilter } from './knowledgeDetailModel'

const props = defineProps<{
  kbCode: string
}>()

const sourceOptions = [
  { label: '真实对话', value: 'RUNTIME' },
  { label: '测试与评测', value: 'CONSOLE' },
  { label: '全部', value: 'ALL' },
]

const sourceFilter = ref<HitSourceFilter>('RUNTIME')
const lowConfidenceOnly = ref(false)
const hitLogs = ref<KnowledgeHitLog[]>([])
const hitsLoading = ref(false)
const dashboard = ref<KnowledgeOpsDashboard | null>(null)

const emptyDescription = computed(() => sourceFilter.value === 'RUNTIME'
  ? '还没有真实对话命中。把知识库接入 Agent 后，用户的提问会出现在这里。'
  : '还没有命中记录。')

function truncate(text = '', maxLen: number) {
  return text.length > maxLen ? `${text.slice(0, maxLen)}...` : text
}

function formatScore(score?: number) {
  return typeof score === 'number' ? score.toFixed(3) : '-'
}

async function fetchHits() {
  hitsLoading.value = true
  try {
    const { data } = await getKnowledgeHitLogs(props.kbCode, {
      limit: 100,
      lowConfidenceOnly: lowConfidenceOnly.value,
      source: sourceFilter.value === 'ALL' ? undefined : sourceFilter.value,
    })
    hitLogs.value = Array.isArray(data) ? data : []
  } finally {
    hitsLoading.value = false
  }
}

async function fetchDashboard() {
  const { data } = await getKnowledgeOpsDashboard(props.kbCode)
  dashboard.value = data as unknown as KnowledgeOpsDashboard
}

async function refresh() {
  await Promise.all([fetchHits(), fetchDashboard()])
}

defineExpose({ refresh })

onMounted(refresh)
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
}

.muted {
  color: var(--text-muted);
}

.toolbar-hint {
  flex: 1;
  font-size: 12px;
  color: var(--text-muted);
}

.chunk-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
  margin-top: 14px;
}

@media (max-width: 960px) {
  .chunk-grid {
    grid-template-columns: 1fr;
  }
}
</style>
