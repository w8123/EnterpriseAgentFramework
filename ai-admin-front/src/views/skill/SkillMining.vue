<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Skill Mining</h2>
      <div class="header-actions">
        <el-button @click="loadAll" :loading="loading">刷新</el-button>
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
  generateSkillDrafts,
  getSkillMiningPrecheck,
  listSkillDrafts,
  publishSkillDraft,
  updateSkillDraftStatus,
  type SkillDraft,
  type SkillMiningPrecheck,
} from '@/api/skillMining'

const loading = ref(false)
const generating = ref(false)
const precheck = ref<SkillMiningPrecheck | null>(null)
const drafts = ref<SkillDraft[]>([])
const previewVisible = ref(false)
const currentDraft = ref<SkillDraft | null>(null)
const traceNodes = ref<TraceNode[]>([])

async function loadAll() {
  loading.value = true
  try {
    const [pre, ds] = await Promise.all([getSkillMiningPrecheck(7), listSkillDrafts()])
    precheck.value = pre.data
    drafts.value = ds.data || []
  } catch {
    ElMessage.error('加载 SkillMining 数据失败')
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    await generateSkillDrafts({ days: 7, minSupport: 3, limit: 20 })
    ElMessage.success('草稿生成完成')
    await loadAll()
  } catch {
    ElMessage.error('生成失败')
  } finally {
    generating.value = false
  }
}

function preview(draft: SkillDraft) {
  currentDraft.value = draft
  previewVisible.value = true
  traceNodes.value = []
}

async function loadTraceByDraft(draft: SkillDraft) {
  const traceId = (draft.sourceTraceIds || '').split(',')[0]
  if (!traceId) return
  try {
    const { data } = await getTraceDetail(traceId)
    traceNodes.value = data.nodes || []
  } catch {
    traceNodes.value = []
  }
}

async function publish(draft: SkillDraft) {
  try {
    await publishSkillDraft(draft.id)
    ElMessage.success('上架成功')
    await loadAll()
  } catch {
    ElMessage.error('上架失败')
  }
}

async function setStatus(draft: SkillDraft, status: string) {
  try {
    await updateSkillDraftStatus(draft.id, { status, reviewNote: 'manual-review' })
    await loadAll()
  } catch {
    ElMessage.error('更新状态失败')
  }
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.header-actions { display: flex; gap: 8px; }
.tips { margin-top: 12px; font-size: 13px; color: #606266; }
.spec {
  white-space: pre-wrap;
  word-break: break-all;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
}
</style>
