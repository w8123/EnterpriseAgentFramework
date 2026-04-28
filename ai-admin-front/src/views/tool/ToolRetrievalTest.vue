<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Tool 检索测试</h2>
      <div class="header-actions">
        <el-button :loading="rebuildStarting" type="warning" @click="handleRebuild">
          重建向量索引
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="section-card">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="用户问题">
          <el-input
            v-model="form.query"
            placeholder="例如：帮我查询最近一周的工单数量"
            style="width: 420px"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="form.topK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="仅启用">
          <el-switch v-model="form.enabledOnly" />
        </el-form-item>
        <el-form-item label="仅 Agent 可见">
          <el-switch v-model="form.agentVisibleOnly" />
        </el-form-item>
        <el-form-item label="相似度下限">
          <el-input-number
            v-model="form.minScore"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            placeholder="默认用服务端配置"
            controls-position="right"
            style="width: 160px"
          />
          <span class="form-hint">0=不过滤；留空用服务端 min-score</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searching" @click="handleSearch">检索</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>召回结果（{{ candidates.length }}）</template>
      <el-empty v-if="!searching && candidates.length === 0" description="无召回结果" />
      <el-table v-else :data="candidates" stripe>
        <el-table-column label="#" type="index" width="60" />
        <el-table-column prop="toolName" label="Tool 名" min-width="220" />
        <el-table-column label="分数" width="100">
          <template #default="{ row }">
            <el-tag :type="scoreTag(row.score)">{{ row.score.toFixed(4) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="projectId" label="项目 ID" width="100" />
        <el-table-column prop="moduleId" label="模块 ID" width="100" />
        <el-table-column label="入库文本" min-width="320">
          <template #default="{ row }">
            <span class="text-ellipsis" :title="row.text">{{ row.text }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="task" shadow="never" class="section-card">
      <template #header>
        <span>重建任务</span>
        <el-tag :type="stageTag(task.stage)" style="margin-left: 10px">{{ task.stage }}</el-tag>
      </template>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="总数">{{ task.totalSteps }}</el-descriptions-item>
        <el-descriptions-item label="已完成">{{ task.completedSteps }}</el-descriptions-item>
        <el-descriptions-item label="成功">{{ task.successCount }}</el-descriptions-item>
        <el-descriptions-item label="跳过">{{ task.skippedCount }}</el-descriptions-item>
        <el-descriptions-item label="失败">{{ task.failedCount }}</el-descriptions-item>
        <el-descriptions-item label="当前">{{ task.currentStep || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始">{{ task.startedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="结束">{{ task.finishedAt || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-progress
        v-if="task.stage === 'QUEUED' || task.stage === 'RUNNING'"
        :percentage="taskPercent"
        :text-inside="true"
        :stroke-width="18"
        style="margin-top: 12px"
      />
      <el-alert
        v-if="task.stage === 'FAILED'"
        style="margin-top: 12px"
        type="error"
        :title="`重建失败：${task.errorMessage || '未知错误'}`"
        :closable="false"
        show-icon
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getToolRetrievalRebuildStatus,
  searchToolRetrieval,
  startToolRetrievalRebuild,
} from '@/api/toolRetrieval'
import type { ToolCandidate, ToolRebuildTask, ToolRetrievalSearchRequest } from '@/types/toolRetrieval'

const form = reactive({
  query: '',
  topK: 10,
  enabledOnly: false,
  agentVisibleOnly: false,
  /** undefined：不传，走后端默认 min-score */
  minScore: undefined as number | undefined,
})

const searching = ref(false)
const candidates = ref<ToolCandidate[]>([])

const rebuildStarting = ref(false)
const task = ref<ToolRebuildTask | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

const taskPercent = computed(() => {
  if (!task.value || !task.value.totalSteps) return 0
  return Math.round((task.value.completedSteps / task.value.totalSteps) * 100)
})

async function handleSearch() {
  if (!form.query.trim()) {
    ElMessage.warning('请输入用户问题')
    return
  }
  searching.value = true
  try {
    const payload: ToolRetrievalSearchRequest = {
      query: form.query.trim(),
      topK: form.topK,
      enabledOnly: form.enabledOnly,
      agentVisibleOnly: form.agentVisibleOnly,
    }
    if (form.minScore !== undefined && form.minScore !== null) {
      payload.minScore = form.minScore
    }
    const { data } = await searchToolRetrieval(payload)
    candidates.value = data?.candidates || []
    if (!candidates.value.length && data?.message) {
      ElMessage.info(data.message)
    }
  } catch (err) {
    ElMessage.error((err as Error).message || '检索失败')
  } finally {
    searching.value = false
  }
}

async function handleRebuild() {
  rebuildStarting.value = true
  try {
    const { data } = await startToolRetrievalRebuild()
    ElMessage.success('已提交重建任务')
    startPolling(data.taskId)
  } catch (err) {
    ElMessage.error((err as Error).message || '启动重建失败')
  } finally {
    rebuildStarting.value = false
  }
}

function startPolling(taskId: string) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const { data } = await getToolRetrievalRebuildStatus(taskId)
      if (!data) return
      task.value = data
      if (data.stage === 'DONE' || data.stage === 'FAILED') {
        stopPolling()
      }
    } catch {
      /* silent */
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function loadLatest() {
  try {
    const { data } = await getToolRetrievalRebuildStatus()
    if (data) {
      task.value = data
      if (data.stage === 'RUNNING' || data.stage === 'QUEUED') {
        startPolling(data.taskId)
      }
    }
  } catch {
    /* silent */
  }
}

function stageTag(stage: string): 'success' | 'warning' | 'info' | 'danger' | '' {
  switch (stage) {
    case 'DONE':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'warning'
    default:
      return 'info'
  }
}

function scoreTag(score: number): 'success' | 'warning' | 'info' | 'danger' | '' {
  if (score >= 0.7) return 'success'
  if (score >= 0.5) return 'warning'
  return 'info'
}

onMounted(loadLatest)
onUnmounted(stopPolling)
</script>

<style scoped>
.section-card {
  margin-top: 12px;
}
.search-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}
.form-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.text-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
