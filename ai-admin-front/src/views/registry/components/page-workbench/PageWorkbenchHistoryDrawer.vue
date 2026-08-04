<script setup lang="ts">
import { computed } from 'vue'
import {
  CircleCheck,
  Clock,
  DocumentChecked,
  Monitor,
  VideoPlay,
} from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import type { AiCodingTask } from '@/types/aiCodingTask'
import type { ProjectPage, PublishedPageWorkflow } from '@/types/pageWorkbench'
import {
  aiCodingExecutionStatusLabel,
  aiCodingExecutionStatusTagType,
  aiCodingProviderLabel,
} from '@/utils/aiCodingPresentation'
import {
  pageWorkbenchPageName,
  pageWorkbenchTaskSummary,
  pageWorkbenchTaskTitle,
} from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
  tasks: AiCodingTask[]
  workflow?: PublishedPageWorkflow | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  task: [task: AiCodingTask]
  run: [workflow: PublishedPageWorkflow]
}>()

const timeline = computed(() => [...props.tasks].sort(
  (left, right) => new Date(left.updatedAt).getTime() - new Date(right.updatedAt).getTime(),
))

const openQuestionCount = computed(() => props.tasks.reduce(
  (total, task) => total + (task.openQuestions?.length || 0),
  0,
))

const implementationTask = computed(() => [...props.tasks].reverse().find(
  (task) => task.taskKind === 'CODE_IMPLEMENTATION' && task.executionStatus === 'COMPLETED',
))

const acceptanceTask = computed(() => [...props.tasks].reverse().find(
  (task) => task.taskKind === 'BROWSER_ACCEPTANCE' && task.executionStatus === 'COMPLETED',
))

function taskKindLabel(kind: string) {
  return ({
    PAGE_MAP_SCAN: '页面信息更新',
    PAGE_READONLY_ANALYSIS: 'AI 只读分析',
    CODE_IMPLEMENTATION: 'AI Coding 接入活动',
    WORKFLOW_ENGINEERING: '页面助手建设',
    PRE_RELEASE_CHECK: '上线前检查',
    BROWSER_ACCEPTANCE: '真实浏览器验收',
  }[kind] || kind)
}

function formatDateTime(value?: string) {
  if (!value) return '尚未更新'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <AppDrawer
    :model-value="modelValue"
    title="接入历程"
    size="min(720px, 100vw)"
    class="page-workbench-drawer journey-history-drawer"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <header class="workbench-modal-heading">
        <span>PAGE ONBOARDING HISTORY</span>
        <h2>接入历程</h2>
        <p>{{ page ? pageWorkbenchPageName(page) : '当前页面' }} · 从页面发现到真实验收的完整过程。</p>
      </header>
    </template>

    <section class="journey-history-body">
      <article class="journey-history-summary">
        <el-icon><Clock /></el-icon>
        <div><strong>当前页面的接入活动</strong><p>发现、分析、实施、发布和验收记录统一归集。</p></div>
        <span><b>{{ tasks.length }} 个活动</b><b v-if="openQuestionCount">{{ openQuestionCount }} 个待回答问题</b><b v-else>没有遗漏的待办</b></span>
      </article>

      <div v-if="timeline.length" class="journey-history-timeline">
        <button v-for="task in timeline" :key="task.taskId" type="button" @click="emit('task', task)">
          <i><el-icon><CircleCheck v-if="task.executionStatus === 'COMPLETED'" /><Clock v-else /></el-icon></i>
          <span>
            <span><strong>{{ taskKindLabel(task.taskKind) }}</strong><time>{{ formatDateTime(task.updatedAt) }}</time></span>
            <small>{{ aiCodingProviderLabel(task.executorProvider) }} · {{ pageWorkbenchTaskTitle(task.title, taskKindLabel(task.taskKind)) }}</small>
            <p>{{ pageWorkbenchTaskSummary(task.lastMessage, task.objective, aiCodingExecutionStatusLabel(task.executionStatus)) }}</p>
          </span>
          <el-tag :type="aiCodingExecutionStatusTagType(task.executionStatus)" effect="plain" size="small">
            {{ aiCodingExecutionStatusLabel(task.executionStatus) }}
          </el-tag>
        </button>
      </div>
      <el-empty v-else :image-size="72" description="当前页面还没有接入活动" />

      <section class="journey-materials">
        <header><div><h3>最新验收材料</h3><p>只展示真实活动或运行数据中已经存在的材料线索。</p></div></header>
        <div>
          <button type="button" :disabled="!implementationTask" @click="implementationTask && emit('task', implementationTask)">
            <el-icon><DocumentChecked /></el-icon><span><strong>实施与自检报告</strong><small>{{ implementationTask ? '来自已完成代码实施活动' : '尚未形成' }}</small></span>
          </button>
          <button type="button" :disabled="!acceptanceTask" @click="acceptanceTask && emit('task', acceptanceTask)">
            <el-icon><Monitor /></el-icon><span><strong>浏览器验收证据</strong><small>{{ acceptanceTask ? '来自已完成人工验收活动' : '尚未通过验收' }}</small></span>
          </button>
          <button type="button" :disabled="!workflow?.latestTraceId" @click="workflow?.latestTraceId && emit('run', workflow)">
            <el-icon><VideoPlay /></el-icon><span><strong>运行 Trace</strong><small>{{ workflow?.latestTraceId || '尚无真实运行' }}</small></span>
          </button>
        </div>
      </section>
    </section>
  </AppDrawer>
</template>
