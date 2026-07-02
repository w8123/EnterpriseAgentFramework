<script setup lang="ts">
import { Finished, MagicStick, Warning } from '@element-plus/icons-vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'

defineProps<{
  isAiCodingWorkflowSelected: boolean
  createdWorkflowId: string
  selectedPageKey: string
  selectedPage: PageRegistryView | null
  selectedActions: PageActionRegistryView[]
  draftPreviewPresent: boolean
  draftIssueCount: number
  draftIssues: string[]
  draftNodeCount: number
  draftEdgeCount: number
  selectedModelLabel: string
  workflowName: string
  creatingWorkflow: boolean
}>()

const emit = defineEmits<{
  focusDraftStep: []
  goBindStep: []
  confirmCreateWorkflow: []
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 5</span>
        <h2>确认草稿</h2>
      </div>
    </div>

    <div v-if="isAiCodingWorkflowSelected" class="studio-ready">
      <div class="studio-ready-hero">
        <div class="studio-ready-icon">
          <el-icon><Finished /></el-icon>
        </div>
        <div class="studio-ready-copy">
          <span>AI Coding 链路</span>
          <strong>已选择 AI Coding Workflow，可直接挂载智能体</strong>
          <p>该 Workflow 已由外部 AI 工具创建，无需在此步再次创建。</p>
        </div>
        <div class="studio-ready-state">
          <em>Skipped</em>
        </div>
      </div>

      <div class="studio-ready-metrics">
        <div>
          <span>workflowId</span>
          <strong>{{ createdWorkflowId }}</strong>
        </div>
        <div>
          <span>pageKey</span>
          <strong>{{ selectedPageKey || '未选择' }}</strong>
        </div>
        <div>
          <span>routePattern</span>
          <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
        </div>
        <div>
          <span>已选动作</span>
          <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
        </div>
      </div>

      <div class="studio-ready-actions">
        <button type="button" class="secondary" @click="emit('focusDraftStep')">
          返回选择 Workflow
        </button>
        <button type="button" class="primary" @click="emit('goBindStep')">
          <el-icon><Finished /></el-icon>
          去挂载智能体
        </button>
      </div>
    </div>

    <div v-else-if="draftPreviewPresent" class="studio-ready">
      <div class="studio-ready-hero" :class="{ warning: draftIssueCount }">
        <div class="studio-ready-icon">
          <el-icon>
            <Warning v-if="draftIssueCount" />
            <Finished v-else />
          </el-icon>
        </div>
        <div class="studio-ready-copy">
          <span>{{ draftIssueCount ? '需要复核' : '草稿已就绪' }}</span>
          <strong>{{ draftIssueCount ? '草稿仍有校验问题' : '确认后将创建 PAGE_ASSISTANT Workflow' }}</strong>
          <p>
            {{ draftIssueCount
              ? '请返回配置或重新生成，修复后再创建 Workflow。'
              : '创建 Workflow 后将继续挂载到页面副驾驶 Agent，再进入 Workflow Studio。' }}
          </p>
        </div>
        <div class="studio-ready-state">
          <em>{{ draftIssueCount ? `${draftIssueCount} 个问题` : 'Preview' }}</em>
        </div>
      </div>

      <div class="studio-ready-metrics">
        <div>
          <span>Workflow 名称</span>
          <strong>{{ workflowName }}</strong>
        </div>
        <div>
          <span>pageKey</span>
          <strong>{{ selectedPageKey || '未选择' }}</strong>
        </div>
        <div>
          <span>routePattern</span>
          <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
        </div>
        <div>
          <span>已选动作</span>
          <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
        </div>
        <div>
          <span>流程结构</span>
          <strong>{{ draftNodeCount }} 节点 / {{ draftEdgeCount }} 连线</strong>
        </div>
        <div>
          <span>模型</span>
          <strong>{{ selectedModelLabel }}</strong>
        </div>
      </div>

      <div v-if="draftIssueCount" class="studio-ready-issues">
        <span v-for="item in draftIssues" :key="item">{{ item }}</span>
      </div>

      <div class="studio-ready-actions">
        <button type="button" class="secondary" @click="emit('focusDraftStep')">
          返回配置
        </button>
        <button
          type="button"
          class="primary"
          :disabled="Boolean(draftIssueCount) || creatingWorkflow"
          @click="emit('confirmCreateWorkflow')"
        >
          <el-icon><Finished /></el-icon>
          {{ creatingWorkflow ? '创建中...' : '确认创建 Workflow' }}
        </button>
      </div>
    </div>
    <div v-else class="studio-ready-empty">
      <div class="studio-ready-icon warning">
        <el-icon><MagicStick /></el-icon>
      </div>
      <strong>还没有生成草稿</strong>
      <span>先完成配置并生成 GraphSpec 预览，再确认创建 Workflow。</span>
      <button type="button" @click="emit('focusDraftStep')">去生成草稿</button>
    </div>
  </div>
</template>
