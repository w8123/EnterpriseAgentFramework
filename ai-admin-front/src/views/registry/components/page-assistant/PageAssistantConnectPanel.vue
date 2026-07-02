<script setup lang="ts">
import { Close, DocumentCopy } from '@element-plus/icons-vue'
import type { PageRegistryView } from '@/api/embedOps'
import type { PageAssistantSessionSummary } from '@/types/scanProject'
import { pageAccessStateLabel, pageAccessStateTagType } from '@/views/registry/pageAssistantWizardUtils'

export interface WizardStatItem {
  key: string
  icon: string
  label: string
  value: string
}

export interface PageAccessGroup {
  key: string
  title: string
  items: PageAssistantSessionSummary[]
}

const props = defineProps<{
  stats: WizardStatItem[]
  pageRegistry: PageRegistryView[]
  pageAssistantAccessCount: number
  pageAssistantAccessGroups: PageAccessGroup[]
  pageAssistantSessions: PageAssistantSessionSummary[]
  pageAssistantSessionsLoading: boolean
  pageAssistantCheckRunning: boolean
  sdkHelperVisible: boolean
  sdkTemplateCopied: boolean
  highlightedSdkTemplate: string
  resolvePageAccessTitle: (session: PageAssistantSessionSummary) => string
}>()

const emit = defineEmits<{
  openAiPrompt: []
  copySdkTemplate: []
  'update:sdkHelperVisible': [value: boolean]
  refreshSessions: []
  selectAccess: [session: PageAssistantSessionSummary]
  runCardCheck: [session: PageAssistantSessionSummary]
  createAssistant: [session: PageAssistantSessionSummary]
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 1</span>
      </div>
      <div class="panel-actions">
        <el-button type="primary" :icon="DocumentCopy" @click="emit('openAiPrompt')">AI 快速接入</el-button>
        <el-popover
          :visible="sdkHelperVisible"
          placement="left-start"
          :width="640"
          trigger="click"
          popper-class="sdk-template-popover"
          @update:visible="emit('update:sdkHelperVisible', $event)"
        >
          <template #reference>
            <el-button class="access-template-button" :icon="DocumentCopy">手动接入</el-button>
          </template>
          <div class="template-box template-modal inline-template">
            <div class="template-modal-head">
              <div class="template-title-block">
                <span class="template-icon">
                  <el-icon><DocumentCopy /></el-icon>
                </span>
                <div>
                  <h3>手动接入</h3>
                  <p>用于展示最小页面动作声明示例，帮助业务系统完成 SDK 接入</p>
                  <div class="template-badges">
                    <span>推荐模板</span>
                    <span>最小示例</span>
                  </div>
                </div>
              </div>
              <div class="template-actions">
                <el-button class="copy-template-button" :icon="DocumentCopy" @click="emit('copySdkTemplate')">
                  {{ sdkTemplateCopied ? '已复制' : '复制代码' }}
                </el-button>
                <button
                  class="template-close-button"
                  type="button"
                  aria-label="关闭手动接入"
                  @click="emit('update:sdkHelperVisible', false)"
                >
                  <el-icon><Close /></el-icon>
                </button>
              </div>
            </div>
            <div class="template-code-shell">
              <div class="template-code-toolbar">
                <span>JavaScript</span>
                <span>SDK 示例</span>
                <span>已适配页面动作目录</span>
              </div>
              <pre><code v-html="highlightedSdkTemplate" /></pre>
            </div>
          </div>
        </el-popover>
      </div>
    </div>

    <div class="health-grid">
      <div v-for="item in stats" :key="item.label" class="health-card" :class="`stat-${item.key}`">
        <span class="stat-icon">{{ item.icon }}</span>
        <span class="stat-label">{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </div>

    <section class="page-access-board">
      <div class="page-access-board-head">
        <div>
          <h3>页面接入进度</h3>
          <small>{{ pageAssistantAccessCount ? `${pageAssistantAccessCount} 个页面接入任务` : '复制提示词后，Cursor 回传进度会出现在这里' }}</small>
        </div>
        <el-button size="small" :loading="pageAssistantSessionsLoading" @click="emit('refreshSessions')">刷新进度</el-button>
      </div>
      <div v-if="pageAssistantAccessCount" class="page-access-progress">
        <div class="page-access-status-row" aria-label="页面接入状态统计">
          <span v-for="group in pageAssistantAccessGroups" :key="group.key" class="page-access-status-pill">
            <strong>{{ group.title }}</strong>
            <small>{{ group.items.length }}</small>
          </span>
        </div>
        <div class="page-access-card-list">
          <article v-for="session in pageAssistantSessions" :key="session.sessionId" class="page-access-card">
            <div class="page-access-card-head">
              <span>
                <strong>{{ resolvePageAccessTitle(session) }}</strong>
                <small>{{ session.targetRoute || '等待目标路由' }}</small>
              </span>
              <el-tag size="small" :type="pageAccessStateTagType(session.completionState)" effect="plain">
                {{ pageAccessStateLabel(session.completionState) }}
              </el-tag>
            </div>
            <div class="page-access-card-meta">
              <span>{{ session.toolName || 'AI Coding' }}</span>
              <span>{{ session.completedSteps }}/{{ session.totalSteps }} 步</span>
              <span>{{ session.actionCount }} 动作</span>
            </div>
            <p>{{ session.lastMessage || session.sessionId }}</p>
            <div class="page-access-card-actions">
              <el-button size="small" text @click="emit('selectAccess', session)">详情</el-button>
              <el-button size="small" text :loading="pageAssistantCheckRunning" @click="emit('runCardCheck', session)">自检</el-button>
              <el-button size="small" text :disabled="!session.targetPageKey" @click="emit('createAssistant', session)">创建助手</el-button>
            </div>
          </article>
        </div>
      </div>
      <el-empty v-else description="暂无页面接入任务" :image-size="64" />
    </section>

    <div class="step-footer-note">
      <el-alert
        v-if="!pageRegistry.length"
        type="warning"
        show-icon
        :closable="false"
        title="当前项目还没有页面上报"
        description="可以查看手动接入完成 SDK 声明，或先手工声明一个页面动作草案。"
      />
      <el-alert
        v-else
        type="success"
        show-icon
        :closable="false"
        title="页面动作目录已就绪"
        description="下一步选择要服务的业务页面。"
      />
    </div>
  </div>
</template>
