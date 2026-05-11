<template>
  <div class="page-container">
    <div class="page-header">
      <h2>能力挖掘</h2>
      <div class="header-actions">
        <el-button @click="loadAll" :loading="loading">刷新</el-button>
        <el-button @click="generateDemo" :loading="demoGenerating">生成 Demo Trace</el-button>
        <el-button type="primary" @click="generate" :loading="generating">生成草稿</el-button>
      </div>
    </div>

    <el-card shadow="never" v-if="precheck">
      <el-row :gutter="12">
        <el-col :span="6"><el-statistic title="日志量" :value="precheck.logCount" /></el-col>
        <el-col :span="6"><el-statistic title="Trace 数" :value="precheck.traceCount" /></el-col>
        <el-col :span="6"><el-statistic title="多步 Trace" :value="precheck.multiStepTraceCount" /></el-col>
        <el-col :span="6">
          <el-tag :type="precheck.readyForMining ? 'success' : 'warning'">{{ precheck.readyForMining ? '可启动' : '建议补数' }}</el-tag>
        </el-col>
      </el-row>
      <div class="tips">
        <b>建议喂数场景：</b>{{ precheck.recommendedScenarios.join('；') }}
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <el-table :data="drafts" v-loading="loading" stripe>
        <el-table-column prop="name" label="草稿名" min-width="220" />
        <el-table-column prop="status" label="状态" width="140" />
        <el-table-column prop="confidenceScore" label="置信度" width="100" />
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="preview(row)">预览</el-button>
            <el-button link type="success" @click="publish(row)">一键上架</el-button>
            <el-button link @click="setStatus(row, 'DISCARDED')">丢弃</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="previewVisible" :title="`草稿预览 - ${currentDraft?.name || ''}`" size="45%">
      <div v-if="currentDraft">
        <pre class="spec">{{ currentDraft.specJson }}</pre>
        <el-divider />
        <el-button size="small" @click="loadTraceByDraft(currentDraft)">加载来源 Trace</el-button>
        <TraceTimeline :nodes="traceNodes" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import TraceTimeline from '@/components/TraceTimeline.vue'
import { getTraceDetail } from '@/api/trace'
import type { TraceNode } from '@/types/trace'
import {
  generateCapabilityDrafts,
  generateDemoTraces,
  getCapabilityMiningPrecheck,
  listCapabilityDrafts,
  publishCapabilityDraft,
  updateCapabilityDraftStatus,
  type CapabilityDraft,
  type CapabilityMiningPrecheck,
} from '@/api/capabilityMining'

const loading = ref(false)
const generating = ref(false)
const demoGenerating = ref(false)
const precheck = ref<CapabilityMiningPrecheck | null>(null)
const drafts = ref<CapabilityDraft[]>([])
const previewVisible = ref(false)
const currentDraft = ref<CapabilityDraft | null>(null)
const traceNodes = ref<TraceNode[]>([])

async function loadAll() {
  loading.value = true
  try {
    const [pre, ds] = await Promise.all([getCapabilityMiningPrecheck(7), listCapabilityDrafts()])
    precheck.value = pre.data
    drafts.value = ds.data || []
  } catch {
    ElMessage.error('加载能力挖掘数据失败')
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    await generateCapabilityDrafts({ days: 7, minSupport: 3, limit: 20 })
    ElMessage.success('草稿生成完成')
    await loadAll()
  } catch {
    ElMessage.error('生成失败')
  } finally {
    generating.value = false
  }
}

async function generateDemo() {
  demoGenerating.value = true
  try {
    const { data } = await generateDemoTraces({
      scenario: 'order_after_sale',
      traceCount: 120,
      successRate: 0.92,
      noiseRate: 0.08,
    })
    ElMessage.success(`已生成 ${data.traceCount} 条 Demo Trace，写入 ${data.insertedLogCount} 条日志`)
    await loadAll()
  } catch {
    ElMessage.error('生成 Demo Trace 失败')
  } finally {
    demoGenerating.value = false
  }
}

function preview(draft: CapabilityDraft) {
  currentDraft.value = draft
  previewVisible.value = true
  traceNodes.value = []
}

async function loadTraceByDraft(draft: CapabilityDraft) {
  const traceId = (draft.sourceTraceIds || '').split(',')[0]
  if (!traceId) return
  try {
    const { data } = await getTraceDetail(traceId)
    traceNodes.value = data.nodes || []
  } catch {
    traceNodes.value = []
  }
}

async function publish(draft: CapabilityDraft) {
  try {
    await publishCapabilityDraft(draft.id)
    ElMessage.success('上架成功')
    await loadAll()
  } catch {
    ElMessage.error('上架失败')
  }
}

async function setStatus(draft: CapabilityDraft, status: string) {
  try {
    await updateCapabilityDraftStatus(draft.id, { status, reviewNote: 'manual-review' })
    await loadAll()
  } catch {
    ElMessage.error('更新状态失败')
  }
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.header-actions { display: flex; gap: 8px; }
.tips { margin-top: 12px; font-size: 13px; color: var(--text-secondary); }
.spec {
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--bg-tertiary);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 4px;
  padding: 8px;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .spec {
    border: 1px solid #ebeef5;
  }
}
</style>
