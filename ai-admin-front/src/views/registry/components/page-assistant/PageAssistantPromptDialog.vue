<script setup lang="ts">
import { DocumentCopy } from '@element-plus/icons-vue'
import type { PageRegistryView } from '@/api/embedOps'
import type { AiAccessSession, AiAccessStep, PageAssistantOnboardingManifest, ScanProject } from '@/types/scanProject'
import { stepStatusTagType } from '@/views/registry/pageAssistantWizardUtils'

type AiPromptTool = 'Cursor' | 'Codex' | 'Claude Code'

const visible = defineModel<boolean>('visible', { required: true })
const aiPromptTool = defineModel<AiPromptTool>('aiPromptTool', { required: true })

defineProps<{
  pageAssistantSession: AiAccessSession | null
  pageAssistantManifest: PageAssistantOnboardingManifest | null
  project: ScanProject | null
  selectedPage: PageRegistryView | null
  selectedPageKey: string
  aiCodingAccessState: string
  pageAssistantProgressText: string
  pageAssistantSessionSteps: AiAccessStep[]
  pageAssistantScaffoldCommand: string
  pageAssistantVerifyCommand: string
  pageAssistantOnboardingPrompt: string
  pageAssistantManifestLoading: boolean
  pageAssistantCheckRunning: boolean
  aiPromptCopied: boolean
}>()

const emit = defineEmits<{
  refreshSession: []
  runSelfCheck: []
  copyScaffoldCommand: []
  copyVerifyCommand: []
  copyPrompt: []
}>()
</script>

<template>
  <el-dialog v-model="visible" title="页面助手 AI 快速接入" width="860px" destroy-on-close>
    <div class="ai-prompt-dialog">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="复制给 Cursor / Codex / Claude Code"
        description="该提示词只面向当前业务前端页面动作接入；项目级 SDK、网关和 embed token 接入仍走项目 AI 快速接入。"
      />
      <section class="ai-access-session-panel">
        <div class="ai-access-session-head">
          <div>
            <span>页面助手进度会话</span>
            <strong>{{ pageAssistantSession?.sessionId || '准备中' }}</strong>
          </div>
          <div class="ai-access-session-actions">
            <el-button size="small" :loading="pageAssistantManifestLoading" @click="emit('refreshSession')">刷新进度</el-button>
            <el-button
              size="small"
              type="primary"
              :loading="pageAssistantCheckRunning"
              :disabled="!pageAssistantSession"
              @click="emit('runSelfCheck')"
            >
              运行自检
            </el-button>
          </div>
        </div>
        <div class="ai-access-meta-grid">
          <span>
            <small>App Key</small>
            <strong>{{ pageAssistantManifest?.project.registryAppKey || project?.registryAppKey || '未配置' }}</strong>
          </span>
          <span>
            <small>AI Coding</small>
            <strong>{{ aiCodingAccessState }}</strong>
          </span>
          <span>
            <small>目标页面</small>
            <strong>{{ selectedPage?.name || selectedPageKey || '待确认' }}</strong>
          </span>
          <span>
            <small>接入进度</small>
            <strong>{{ pageAssistantProgressText }}</strong>
          </span>
        </div>
        <div v-if="pageAssistantSessionSteps.length" class="ai-access-step-list">
          <div v-for="step in pageAssistantSessionSteps" :key="step.stepKey" class="ai-access-step">
            <el-tag size="small" :type="stepStatusTagType(step.status)" effect="plain">{{ step.status }}</el-tag>
            <span>{{ step.title }}</span>
            <small>{{ step.message || step.stepKey }}</small>
          </div>
        </div>
        <el-alert
          v-else
          type="warning"
          show-icon
          :closable="false"
          title="尚未获取到页面助手进度"
          description="可以先复制提示词；Cursor 完成接入后可按提示词中的 page-assistant session URL 回传进度。"
        />
      </section>
      <section class="ai-helper-command-list">
        <div class="ai-helper-command">
          <span>
            <strong>Angular scaffold</strong>
            <small>在业务前端仓库生成官方 Page Action bridge 模板</small>
          </span>
          <code>{{ pageAssistantScaffoldCommand }}</code>
          <el-button size="small" :icon="DocumentCopy" @click="emit('copyScaffoldCommand')">复制</el-button>
        </div>
        <div class="ai-helper-command">
          <span>
            <strong>本地 verify</strong>
            <small>使用本机 PowerShell 验证静态证据；需要时加 -ReportToPlatform 回传</small>
          </span>
          <code>{{ pageAssistantVerifyCommand }}</code>
          <el-button size="small" :icon="DocumentCopy" @click="emit('copyVerifyCommand')">复制</el-button>
        </div>
      </section>
      <div class="ai-prompt-toolbar">
        <el-radio-group v-model="aiPromptTool" size="small">
          <el-radio-button label="Cursor" />
          <el-radio-button label="Codex" />
          <el-radio-button label="Claude Code" />
        </el-radio-group>
        <el-button type="primary" :icon="DocumentCopy" @click="emit('copyPrompt')">
          {{ aiPromptCopied ? '已复制' : '复制提示词' }}
        </el-button>
      </div>
      <el-input
        class="ai-prompt-editor"
        :model-value="pageAssistantOnboardingPrompt"
        type="textarea"
        :rows="24"
        resize="none"
        readonly
      />
    </div>
  </el-dialog>
</template>
