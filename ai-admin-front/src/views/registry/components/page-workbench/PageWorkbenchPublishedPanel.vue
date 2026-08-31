<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { PublishedPageWorkflow } from '@/types/pageWorkbench'
import {
  pageWorkbenchHumanText,
  pageWorkbenchRiskLabel,
} from '@/utils/pageWorkbenchPresentation'

defineProps<{
  workflows: PublishedPageWorkflow[]
  unavailable?: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  refresh: []
  accept: [workflow: PublishedPageWorkflow]
  run: [workflow: PublishedPageWorkflow]
}>()

function formatDateTime(value?: string) {
  if (!value) return '暂无调用'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="page-workbench-section published-section" v-loading="loading">
    <div class="page-workbench-section__header">
      <div>
        <h2>在线能力</h2>
        <p>持续查看已经上线的页面助手、关联智能体、真实调用表现和最近运行。</p>
      </div>
      <el-button :icon="Refresh" @click="emit('refresh')">刷新</el-button>
    </div>

    <el-alert
      v-if="unavailable"
      class="published-unavailable"
      type="warning"
      :closable="false"
      title="运行服务的发布数据当前不可用"
      description="已发布列表不会使用缓存或模拟指标。请确认运行服务可访问后重试。"
      show-icon
    />

    <div v-if="workflows.length" class="published-list">
      <article v-for="workflow in workflows" :key="`${workflow.workflowId}:${workflow.pageKey}`">
        <header>
          <div>
            <small>{{ workflow.pageKey }}</small>
            <h3>
              {{
                pageWorkbenchHumanText(
                  workflow.workflowName,
                  `页面工作流 · ${workflow.pageKey}`,
                )
              }}
            </h3>
            <p>
              {{
                pageWorkbenchHumanText(
                  workflow.workflowDescription,
                  '当前工作流尚未提供中文说明。',
                )
              }}
            </p>
          </div>
          <el-tag type="success" effect="plain">运行中</el-tag>
        </header>

        <dl class="published-metrics">
          <div>
            <dt>工作流版本</dt>
            <dd>{{ workflow.workflowVersion }}</dd>
          </div>
          <div>
            <dt>关联智能体</dt>
            <dd>
              {{
                pageWorkbenchHumanText(
                  workflow.agentName,
                  `智能体 · ${workflow.agentKeySlug}`,
                )
              }}
            </dd>
          </div>
          <div>
            <dt>近 30 天调用</dt>
            <dd>{{ workflow.recentCallCount }}</dd>
          </div>
          <div>
            <dt>成功率</dt>
            <dd>{{ workflow.successRate == null ? '暂无数据' : `${workflow.successRate}%` }}</dd>
          </div>
        </dl>

        <div class="published-detail">
          <span>
            <strong>发布时间</strong>
            {{ formatDateTime(workflow.publishedAt) }}
          </span>
          <span>
            <strong>最近调用</strong>
            {{ formatDateTime(workflow.latestCallAt) }}
          </span>
          <span>
            <strong>调用名称</strong>
            {{ workflow.toolName }}
          </span>
        </div>

        <footer>
          <span>
            风险：{{ pageWorkbenchRiskLabel(workflow.riskLevel) }}
            <template v-if="workflow.permissionKey"> · 权限：{{ workflow.permissionKey }}</template>
          </span>
          <div class="published-actions">
            <el-button
              v-if="workflow.latestTraceId"
              plain
              @click="emit('run', workflow)"
            >
              查看最近运行
            </el-button>
            <el-button type="primary" plain @click="emit('accept', workflow)">
              发起浏览器验收
            </el-button>
          </div>
        </footer>
      </article>
    </div>

    <el-empty
      v-else-if="!unavailable"
      :image-size="108"
      description="还没有真实发布并接入智能体的业务页面工作流"
    >
      <p class="published-empty-hint">完成实施、浏览器验收和发布后，运行数据会出现在这里。</p>
    </el-empty>
  </section>
</template>
