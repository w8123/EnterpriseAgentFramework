<script setup lang="ts">
import { computed, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import claudeCodeIcon from '@/assets/ai-coding-providers/claude-code.svg'
import codexIcon from '@/assets/ai-coding-providers/codex.svg'
import cursorIcon from '@/assets/ai-coding-providers/cursor.svg'
import openCodeIcon from '@/assets/ai-coding-providers/opencode.svg'
import traeIcon from '@/assets/ai-coding-providers/trae.svg'
import type {
  AiCodingTask,
  AiCodingExecutionStatus,
} from '@/types/aiCodingTask'
import {
  AI_CODING_EXECUTION_STATUS_OPTIONS,
  aiCodingConnectionStatusLabel,
  aiCodingExecutionStatusTagType,
  aiCodingExecutionStatusLabel,
  aiCodingProviderLabel,
} from '@/utils/aiCodingPresentation'
import {
  pageWorkbenchTaskSummary,
  pageWorkbenchTaskTitle,
} from '@/utils/pageWorkbenchPresentation'

type PageTaskKind =
  | 'PROJECT_ONBOARDING'
  | 'PAGE_MAP_SCAN'
  | 'PAGE_READONLY_ANALYSIS'
  | 'WORKFLOW_ENGINEERING'
  | 'CODE_IMPLEMENTATION'
  | 'BROWSER_ACCEPTANCE'
  | 'PRE_RELEASE_CHECK'

const props = defineProps<{
  tasks: AiCodingTask[]
  loading?: boolean
  unavailable?: string
  variant?: 'activity' | 'advanced'
}>()

const emit = defineEmits<{
  detail: [task: AiCodingTask]
  refresh: []
}>()

const typeFilter = ref<'ALL' | PageTaskKind>('ALL')
const statusFilter = ref<'ALL' | AiCodingExecutionStatus>('ALL')
const keyword = ref('')
const activityMode = computed(() => props.variant === 'activity')

const filteredTasks = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return props.tasks.filter((task) => {
    const typeMatched = typeFilter.value === 'ALL' || task.taskKind === typeFilter.value
    const statusMatched =
      statusFilter.value === 'ALL' || task.executionStatus === statusFilter.value
    const keywordMatched =
      !normalizedKeyword ||
      [task.title, task.objective, pageKey(task), task.taskId]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalizedKeyword))
    return typeMatched && statusMatched && keywordMatched
  })
})

const typeOptions: Array<{ value: 'ALL' | PageTaskKind; label: string }> = [
  { value: 'ALL', label: '全部类型' },
  { value: 'PROJECT_ONBOARDING', label: '项目接入' },
  { value: 'PAGE_MAP_SCAN', label: '页面地图扫描' },
  { value: 'PAGE_READONLY_ANALYSIS', label: '单页面只读分析' },
  { value: 'WORKFLOW_ENGINEERING', label: '页面工作流建设' },
  { value: 'CODE_IMPLEMENTATION', label: '代码实施' },
  { value: 'BROWSER_ACCEPTANCE', label: '浏览器验收' },
  { value: 'PRE_RELEASE_CHECK', label: '发布前检查' },
]

const statusOptions: Array<{
  value: 'ALL' | AiCodingExecutionStatus
  label: string
}> = [
  { value: 'ALL', label: '全部状态' },
  ...AI_CODING_EXECUTION_STATUS_OPTIONS,
]

const providerIcons: Readonly<Record<string, string>> = {
  CODEX: codexIcon,
  CURSOR: cursorIcon,
  TRAE: traeIcon,
  CLAUDE_CODE: claudeCodeIcon,
  OPENCODE: openCodeIcon,
  OPEN_CODE: openCodeIcon,
}

function normalizedProvider(provider?: string) {
  return String(provider || '')
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, '_')
}

function providerIcon(provider?: string) {
  return providerIcons[normalizedProvider(provider)]
}

function providerLabel(provider?: string) {
  const normalized = normalizedProvider(provider)
  if (normalized === 'OPENCODE' || normalized === 'OPEN_CODE') return 'OpenCode'
  return aiCodingProviderLabel(provider)
}

function providerClass(provider?: string) {
  const normalized = normalizedProvider(provider)
  const className = normalized === 'OPEN_CODE' ? 'OPENCODE' : normalized
  return `is-${className.toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'unknown'}`
}

function typeLabel(type: string) {
  return typeOptions.find((option) => option.value === type)?.label || type
}

function statusLabel(status: AiCodingExecutionStatus) {
  return aiCodingExecutionStatusLabel(status)
}

function pageKey(task: AiCodingTask) {
  return task.targets.find(
    (target) => target.targetRole === 'PRIMARY' && target.targetType === 'PAGE',
  )?.targetKey
}

function formatDateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="page-workbench-section task-section">
    <div class="page-workbench-section__header">
      <div>
        <h2>{{ activityMode ? '活动记录' : 'AI 任务记录' }}</h2>
        <p>
          {{
            activityMode
              ? '查看页面扫描、AI 实施、上线和真实验收活动；问题与结果都可以从这里恢复。'
              : '查看项目扫描、页面分析、工作流建设、代码实施、浏览器验收和发布前检查的执行记录。'
          }}
        </p>
      </div>
      <div class="section-header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="emit('refresh')">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="unavailable"
      type="error"
      show-icon
      :closable="false"
      :title="unavailable"
    >
      点击右上角“刷新”重新读取任务状态。任务状态恢复前不会创建新任务。
    </el-alert>

    <div v-if="!unavailable && tasks.length" class="task-toolbar">
      <el-select v-model="typeFilter">
        <el-option
          v-for="option in typeOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-select v-model="statusFilter">
        <el-option
          v-for="option in statusOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="搜索任务">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>

    <div
      v-if="!unavailable && filteredTasks.length"
      class="task-list"
      v-loading="loading"
    >
      <button
        v-for="task in filteredTasks"
        :key="task.taskId"
        class="task-row"
        type="button"
        @click="emit('detail', task)"
      >
        <span
          class="task-row__provider"
          :class="providerClass(task.executorProvider)"
          role="img"
          :aria-label="`${providerLabel(task.executorProvider)} 图标`"
          :title="providerLabel(task.executorProvider)"
        >
          <img
            v-if="providerIcon(task.executorProvider)"
            :src="providerIcon(task.executorProvider)"
            alt=""
            aria-hidden="true"
          />
          <small v-else class="task-row__provider-fallback">
            {{ providerLabel(task.executorProvider).slice(0, 2) }}
          </small>
        </span>
        <span class="task-row__main">
          <span>
            <strong>{{ pageWorkbenchTaskTitle(task.title, typeLabel(task.taskKind)) }}</strong>
            <el-tag
              :type="aiCodingExecutionStatusTagType(task.executionStatus)"
              effect="plain"
              size="small"
            >
              {{ statusLabel(task.executionStatus) }}
            </el-tag>
          </span>
          <small>
            {{ typeLabel(task.taskKind) }}
            <template v-if="pageKey(task)"> · {{ pageKey(task) }}</template>
            · {{ task.accessMode === 'READ_ONLY' ? '只读' : '可写' }}
            ·
            {{ aiCodingConnectionStatusLabel(task.connection.status) }}
          </small>
          <p>
            {{
              pageWorkbenchTaskSummary(
                task.lastMessage,
                task.objective,
                statusLabel(task.executionStatus),
              )
            }}
          </p>
        </span>
        <span class="task-row__meta">
          <strong v-if="task.openQuestions.length">{{ task.openQuestions.length }} 个待回答问题</strong>
          <small>{{ formatDateTime(task.updatedAt) }}</small>
        </span>
      </button>
    </div>

    <el-empty
      v-else-if="!unavailable"
      :image-size="108"
      :description="
        tasks.length
          ? `当前筛选条件下没有${activityMode ? '活动' : '记录'}`
          : activityMode
            ? '还没有页面接入活动'
            : '还没有 AI 任务记录'
      "
    />
  </section>
</template>
