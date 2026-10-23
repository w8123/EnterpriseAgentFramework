<template>
  <WorkbenchPage class="knowledge-detail-page" density="comfortable">
    <PageHeader
      variant="entity"
      presentation="plain"
      action-presentation="inline"
      domain="knowledge"
      :title="kbInfo?.name || '知识库详情'"
      show-back
      @back="router.push('/knowledge')"
    >
      <template #tags>
        <el-tag effect="plain" size="small">{{ kbCode }}</el-tag>
        <StatusTag :label="accessModeLabel(kbInfo?.accessMode)" :tone="kbInfo?.accessMode === 'FILE_GRANTS' ? 'warning' : 'success'" />
      </template>
      <template #meta>
        <div class="knowledge-header-meta">
          <MetricStrip presentation="inline" source="wallpaper" :items="knowledgeMetrics" aria-label="知识库指标" />
          <span class="knowledge-header-meta__text">
            <template v-if="kbInfo?.projectCode">项目 {{ kbInfo.projectCode }} · </template>回答模型 {{ modelName(kbInfo?.llmModelInstanceId) }}
          </span>
        </div>
      </template>
      <template #actions>
        <LiquidGlassButton source="wallpaper" type="primary" :icon="Connection" @click="attachDialogVisible = true">接入 Agent</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassCard source="wallpaper" class="knowledge-usage" aria-labelledby="knowledge-usage-title">
      <h2 id="knowledge-usage-title" class="knowledge-usage__title">谁在用</h2>
      <div v-if="usage.workflows.length === 0 && !usage.hiddenWorkflowCount" class="usage-empty">
        <span>还没有 Agent 使用这个知识库。接入后，Agent 遇到相关问题会检索本库并按资料回答。</span>
        <el-button type="primary" link @click="attachDialogVisible = true">接入 Agent</el-button>
      </div>
      <div v-else class="usage-list">
        <div v-for="workflow in usage.workflows" :key="workflow.id" class="usage-row">
          <el-button type="primary" link @click="router.push(`/workflows/${workflow.id}/studio`)">{{ workflow.name }}</el-button>
          <StatusTag :label="workflow.status === 'ACTIVE' ? '已发布' : '草稿'" :tone="workflow.status === 'ACTIVE' ? 'success' : 'info'" />
          <span v-if="workflow.agents.length === 0" class="usage-muted">未接入 Agent</span>
          <template v-else>
            <span class="usage-muted">Agent</span>
            <el-button
              v-for="agent in workflow.agents"
              :key="agent.id"
              link
              @click="router.push(`/agent/${agent.id}/edit`)"
            >
              {{ agent.name }}
            </el-button>
          </template>
        </div>
        <div v-if="usage.hiddenWorkflowCount" class="usage-muted">另有 {{ usage.hiddenWorkflowCount }} 个 Workflow 在你没有权限的项目里。</div>
      </div>
    </LiquidGlassCard>

    <LiquidGlassTabs v-model="activeTab" source="wallpaper" class="knowledge-tabs">
      <el-tab-pane label="文件" name="files">
        <KnowledgeFilesTab :kb-code="kbCode" :kb="kbInfo" @changed="refreshSummary" />
      </el-tab-pane>
      <el-tab-pane label="检索测试" name="retrieval">
        <KnowledgeRetrievalTab :kb-code="kbCode" :kb="kbInfo" @recorded="hitsTabRef?.refresh()" />
      </el-tab-pane>
      <el-tab-pane label="命中分析" name="hits">
        <KnowledgeHitsTab ref="hitsTabRef" :kb-code="kbCode" />
      </el-tab-pane>
      <el-tab-pane label="设置" name="settings">
        <KnowledgeSettingsTab :kb-code="kbCode" :kb="kbInfo" :model-name="modelName" @saved="fetchKbInfo" />
      </el-tab-pane>
    </LiquidGlassTabs>

    <KnowledgeAttachAgentDialog
      v-model="attachDialogVisible"
      :kb-code="kbCode"
      :kb-name="kbInfo?.name || kbCode"
      @attached="fetchUsage"
    />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Connection } from '@element-plus/icons-vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassTabs } from '@/components/common/liquid-glass'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { getKnowledgeList, getKnowledgeStats, getKnowledgeUsage } from '@/api/knowledge'
import type { KnowledgeBase, KnowledgeStats, KnowledgeUsage } from '@/types/knowledge'
import KnowledgeFilesTab from './knowledge/KnowledgeFilesTab.vue'
import KnowledgeRetrievalTab from './knowledge/KnowledgeRetrievalTab.vue'
import KnowledgeHitsTab from './knowledge/KnowledgeHitsTab.vue'
import KnowledgeSettingsTab from './knowledge/KnowledgeSettingsTab.vue'
import KnowledgeAttachAgentDialog from './knowledge/KnowledgeAttachAgentDialog.vue'
import { accessModeLabel, resolveKnowledgeDetailTab, usageFor, type KnowledgeDetailTab } from './knowledge/knowledgeDetailModel'
import { useModelNames } from './knowledge/useModelNames'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string

const activeTab = ref<KnowledgeDetailTab>(resolveKnowledgeDetailTab(route.query.tab))
const kbInfo = ref<KnowledgeBase | null>(null)
const stats = ref<KnowledgeStats | null>(null)
const usages = ref<KnowledgeUsage[]>([])
const attachDialogVisible = ref(false)
const hitsTabRef = ref<InstanceType<typeof KnowledgeHitsTab> | null>(null)
const { loadModelNames, modelName } = useModelNames()

const usage = computed(() => usageFor(usages.value, kbCode))
const knowledgeMetrics = computed<MetricStripItem[]>(() => [
  { key: 'files', label: '文件', value: stats.value?.fileCount ?? 0 },
  { key: 'chunks', label: '段落', value: stats.value?.chunkCount ?? 0 },
  { key: 'active', label: '启用段落', value: stats.value?.activeChunkCount ?? 0 },
  { key: 'hits', label: '命中', value: stats.value?.hitCount ?? 0, tone: 'brand' },
])

async function fetchKbInfo() {
  const { data } = await getKnowledgeList()
  kbInfo.value = Array.isArray(data) ? data.find((kb) => kb.code === kbCode) || null : null
}

async function fetchStats() {
  const { data } = await getKnowledgeStats(kbCode)
  stats.value = data as unknown as KnowledgeStats
}

async function fetchUsage() {
  try {
    const { data } = await getKnowledgeUsage([kbCode])
    usages.value = Array.isArray(data) ? data : []
  } catch {
    usages.value = []
  }
}

function refreshSummary() {
  void fetchStats()
}

watch(activeTab, (tab) => {
  void router.replace({ query: { ...route.query, tab } })
})

onMounted(() => {
  void Promise.all([fetchKbInfo(), fetchStats(), fetchUsage(), loadModelNames()])
})
</script>

<style scoped lang="scss">
.knowledge-header-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 18px;
  min-width: 0;
}

.knowledge-header-meta__text {
  color: var(--text-on-wallpaper-secondary);
  font-size: 12.5px;
}

.knowledge-usage {
  display: grid;
  gap: 8px;
  padding-block: 12px;
}

.knowledge-usage__title {
  margin: 0;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 650;
}

.usage-empty,
.usage-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.usage-list {
  display: grid;
  gap: 6px;
}

.usage-muted {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
