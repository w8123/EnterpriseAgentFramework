<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
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
  <AppDialog v-model="visible" title="页面助手 AI 快速接入" width="1100px" destroy-on-close>
    <div class="ai-prompt-dialog ai-prompt-dialog--onboarding">
      <el-alert
        class="ai-prompt-dialog__intro"
        type="info"
        show-icon
        :closable="false"
        title="复制给 Cursor / Codex / Claude Code"
        description="该提示词只面向当前业务前端页面动作接入；项目级 SDK、网关和 embed token 接入仍走项目 AI 快速接入。"
      />

      <div class="ai-prompt-dialog__workbench">
        <section class="ai-prompt-main" aria-label="AI 接入提示词">
          <header class="ai-prompt-main__head">
            <div class="ai-prompt-main__titles">
              <h3 class="ai-prompt-section-title">AI 接入提示词</h3>
              <p class="ai-prompt-section-hint">选择工具后复制到对应 AI Coding 客户端执行</p>
            </div>
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
          </header>
          <el-input
            class="ai-prompt-editor"
            :model-value="pageAssistantOnboardingPrompt"
            type="textarea"
            resize="none"
            readonly
          />
        </section>

        <aside class="ai-prompt-aside" aria-label="接入状态">
          <section class="ai-access-status">
            <header class="ai-access-status__head">
              <div class="ai-access-status__titles">
                <h3 class="ai-prompt-section-title">接入状态</h3>
                <code class="ai-access-session-id" :title="pageAssistantSession?.sessionId || '准备中'">
                  {{ pageAssistantSession?.sessionId || '准备中' }}
                </code>
              </div>
              <div class="ai-access-session-actions">
                <el-button size="small" :loading="pageAssistantManifestLoading" @click="emit('refreshSession')">
                  刷新进度
                </el-button>
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
            </header>

            <div class="ai-access-meta-grid">
              <div class="ai-access-meta-cell">
                <small>App Key</small>
                <strong>{{ pageAssistantManifest?.project.registryAppKey || project?.registryAppKey || '未配置' }}</strong>
              </div>
              <div class="ai-access-meta-cell">
                <small>AI Coding</small>
                <strong>{{ aiCodingAccessState }}</strong>
              </div>
              <div class="ai-access-meta-cell">
                <small>目标页面</small>
                <strong>{{ selectedPage?.name || selectedPageKey || '待确认' }}</strong>
              </div>
              <div class="ai-access-meta-cell">
                <small>接入进度</small>
                <strong>{{ pageAssistantProgressText }}</strong>
              </div>
            </div>

            <div v-if="pageAssistantSessionSteps.length" class="ai-access-step-list">
              <div v-for="step in pageAssistantSessionSteps" :key="step.stepKey" class="ai-access-step">
                <el-tag size="small" :type="stepStatusTagType(step.status)" effect="plain">{{ step.status }}</el-tag>
                <div class="ai-access-step__body">
                  <span class="ai-access-step__title">{{ step.title }}</span>
                  <small class="ai-access-step__key">{{ step.message || step.stepKey }}</small>
                </div>
              </div>
            </div>
            <el-alert
              v-else
              class="ai-access-status__empty"
              type="warning"
              show-icon
              :closable="false"
              title="尚未获取到页面助手进度"
              description="可以先复制提示词；Cursor 完成接入后可按提示词中的 page-assistant session URL 回传进度。"
            />
          </section>

          <section class="ai-helper-commands" aria-label="辅助命令">
            <h3 class="ai-prompt-section-title">辅助命令</h3>
            <div class="ai-helper-command-list">
              <div class="ai-helper-command">
                <div class="ai-helper-command__head">
                  <div class="ai-helper-command__meta">
                    <strong>Angular scaffold</strong>
                    <small>在业务前端仓库生成官方 Page Action bridge 模板</small>
                  </div>
                  <el-button size="small" :icon="DocumentCopy" @click="emit('copyScaffoldCommand')">复制</el-button>
                </div>
                <code class="ai-helper-command__code" :title="pageAssistantScaffoldCommand">
                  {{ pageAssistantScaffoldCommand }}
                </code>
              </div>
              <div class="ai-helper-command">
                <div class="ai-helper-command__head">
                  <div class="ai-helper-command__meta">
                    <strong>本地 verify</strong>
                    <small>使用本机 PowerShell 验证静态证据；需要时加 -ReportToPlatform 回传</small>
                  </div>
                  <el-button size="small" :icon="DocumentCopy" @click="emit('copyVerifyCommand')">复制</el-button>
                </div>
                <code class="ai-helper-command__code" :title="pageAssistantVerifyCommand">
                  {{ pageAssistantVerifyCommand }}
                </code>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </div>
  </AppDialog>
</template>

<style scoped lang="scss">
@use '../../styles/PageAssistantPromptDialog.scss';
</style>
