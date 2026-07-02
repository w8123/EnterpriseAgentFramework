<script setup lang="ts">
import { DocumentCopy, MagicStick } from '@element-plus/icons-vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ModelInstance } from '@/types/model'
import type { AiAccessStep } from '@/types/scanProject'
import type { WorkflowDraftGenerationResult } from '@/types/workflow'
import type {
  AssistantGoal,
  AssistantGoalOption,
  DraftSource,
  WorkflowAiCodingDraftEvidence,
} from '@/views/registry/pageAssistantWizardViewModel'
import { modelOptionLabel, stepStatusTagType } from '@/views/registry/pageAssistantWizardUtils'

const assistantGoal = defineModel<AssistantGoal>('assistantGoal', { required: true })
const agentName = defineModel<string>('agentName', { required: true })
const modelInstanceId = defineModel<string>('modelInstanceId', { required: true })
const requirement = defineModel<string>('requirement', { required: true })

defineProps<{
  selectedPage: PageRegistryView | null
  selectedPageKey: string
  selectedActions: PageActionRegistryView[]
  selectedApiAssets: ApiAssetItem[]
  apiAssets: ApiAssetItem[]
  modelOptions: ModelInstance[]
  assistantGoalOptions: AssistantGoalOption[]
  isAiCodingWorkflowSelected: boolean
  createdWorkflowId: string
  workflowAiCodingDraftStep: AiAccessStep | null
  workflowAiCodingDraftEvidence: WorkflowAiCodingDraftEvidence
  workflowAiCodingValidationSummary: string
  workflowAiCodingPageAssistantValidationSummary: string
  workflowAiCodingRuntimeVerificationSummary: string
  workflowAiCodingResetting: boolean
  draftPreview: WorkflowDraftGenerationResult | null
  draftSource: DraftSource
  generating: boolean
}>()

const emit = defineEmits<{
  openWorkflowAiCodingPrompt: []
  focusBindStep: []
  openAiCodingStudio: []
  resetAiCodingDraft: []
  useAiCodingDraft: []
  useDefaultRequirement: []
  apiSelectionChange: [assets: ApiAssetItem[]]
  switchToPlatformGeneration: []
  generateDraft: []
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 4</span>
        <h2>生成 / 选择 Workflow 草稿</h2>
      </div>
      <div class="panel-actions">
        <el-tag effect="plain">{{ selectedApiAssets.length }} 个 API 资产</el-tag>
      </div>
    </div>

    <div class="draft-summary-strip" aria-label="生成草稿摘要">
      <span>页面：{{ selectedPage?.name || selectedPageKey || '未选择' }}</span>
      <span>动作：{{ selectedActions.length }} 个已选</span>
      <span>模型：{{ modelInstanceId ? '已选择' : '未选择' }}</span>
      <span>API：{{ selectedApiAssets.length }} 个</span>
    </div>

    <section class="draft-entry-section ai-coding-entry">
      <div class="draft-entry-head">
        <div>
          <h3>AI Coding 生成</h3>
          <small>由外部 AI 工具创建 Workflow 并回传结果</small>
        </div>
        <el-button :icon="DocumentCopy" @click="emit('openWorkflowAiCodingPrompt')">使用 AI Coding 生成</el-button>
      </div>

      <div v-if="isAiCodingWorkflowSelected" class="draft-source-banner ai-coding">
        <strong>已选用 AI Coding Workflow</strong>
        <span>workflowId：{{ createdWorkflowId }}</span>
        <el-button size="small" type="primary" @click="emit('focusBindStep')">去挂载智能体</el-button>
      </div>

      <div v-else-if="workflowAiCodingDraftStep" class="workflow-ai-coding-result-card">
        <div class="workflow-ai-coding-result-head">
          <el-tag :type="stepStatusTagType(workflowAiCodingDraftStep.status)" effect="plain">
            AI Coding {{ workflowAiCodingDraftStep.status }}
          </el-tag>
          <strong>{{ workflowAiCodingDraftStep.message || 'Workflow AI Coding 草稿已回传' }}</strong>
        </div>
        <div class="studio-ready-metrics compact">
          <div>
            <span>workflowId</span>
            <strong>{{ workflowAiCodingDraftEvidence.workflowId || '—' }}</strong>
          </div>
          <div>
            <span>keySlug</span>
            <strong>{{ workflowAiCodingDraftEvidence.keySlug || '—' }}</strong>
          </div>
          <div>
            <span>validate</span>
            <strong>{{ workflowAiCodingValidationSummary || '—' }}</strong>
          </div>
          <div>
            <span>page-assistant validate</span>
            <strong>{{ workflowAiCodingPageAssistantValidationSummary || '—' }}</strong>
          </div>
          <div>
            <span>browser runtime</span>
            <strong>{{ workflowAiCodingRuntimeVerificationSummary || '—' }}</strong>
          </div>
        </div>
        <div class="workflow-ai-coding-result-actions">
          <el-button size="small" @click="emit('openAiCodingStudio')">打开 Studio</el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :loading="workflowAiCodingResetting"
            @click="emit('resetAiCodingDraft')"
          >
            删除并重新生成
          </el-button>
          <el-button size="small" type="primary" @click="emit('useAiCodingDraft')">使用该 Workflow 继续</el-button>
        </div>
      </div>
    </section>

    <section class="draft-entry-section platform-entry">
      <div class="draft-entry-head">
        <div>
          <h3>平台内生成</h3>
          <small>配置模型与要求，在平台内生成 GraphSpec 草稿</small>
        </div>
        <el-tag v-if="draftPreview && draftSource === 'PLATFORM_GENERATED'" effect="plain" type="success">已有平台草稿</el-tag>
      </div>

      <el-form label-position="top" class="draft-form draft-console">
        <section class="draft-config-card">
          <div class="draft-section-head">
            <h3>助手基础配置</h3>
            <small>确认助手类型、名称和模型</small>
          </div>

          <div class="goal-card-grid" role="radiogroup" aria-label="助手目标">
            <button
              v-for="goal in assistantGoalOptions"
              :key="goal.value"
              class="goal-card"
              :class="{ selected: assistantGoal === goal.value }"
              type="button"
              role="radio"
              :aria-checked="assistantGoal === goal.value"
              @click="assistantGoal = goal.value"
            >
              <span class="goal-card-icon" :class="`tone-${goal.tone}`" aria-hidden="true">
                <el-icon>
                  <component :is="goal.icon" />
                </el-icon>
              </span>
              <strong>{{ goal.title }}</strong>
              <small>{{ goal.desc }}</small>
            </button>
          </div>

          <el-form-item label="Workflow 名称">
            <el-input v-model="agentName" placeholder="例如：班组档案页面助手" />
          </el-form-item>
          <el-form-item label="模型实例">
            <el-select v-model="modelInstanceId" placeholder="选择 LLM 模型实例" filterable>
              <el-option
                v-for="model in modelOptions"
                :key="model.id"
                :label="modelOptionLabel(model)"
                :value="model.id"
              />
            </el-select>
          </el-form-item>
        </section>

        <section class="draft-config-card prompt-card">
          <div class="draft-section-head prompt-head">
            <div>
              <h3>生成要求</h3>
              <small>描述生成草稿的执行意图和约束</small>
            </div>
            <button type="button" @click="emit('useDefaultRequirement')">使用默认要求</button>
          </div>
          <el-input v-model="requirement" class="prompt-editor" type="textarea" :rows="8" resize="none" />
        </section>

        <section class="draft-config-card api-resource-card">
          <div class="draft-section-head">
            <h3>API 资产（可选）</h3>
            <small>可绑定后端 API 作为工具资源提供给模型</small>
          </div>
          <div class="api-assets">
            <el-table
              v-if="apiAssets.length"
              :data="apiAssets"
              row-key="apiId"
              size="small"
              @selection-change="emit('apiSelectionChange', $event)"
            >
              <el-table-column type="selection" width="42" />
              <el-table-column prop="name" label="API" min-width="180" show-overflow-tooltip />
              <el-table-column prop="httpMethod" label="方法" width="80" />
              <el-table-column prop="globalToolName" label="Tool" min-width="160" show-overflow-tooltip />
            </el-table>
            <div v-else class="api-empty-card">
              <span class="api-empty-icon">API</span>
              <div>
                <strong>暂无可选 API 资产</strong>
                <small>本次将仅基于已选择的页面动作生成 GraphSpec 草稿。</small>
              </div>
            </div>
          </div>
        </section>
      </el-form>

      <div class="draft-generate-row">
        <template v-if="isAiCodingWorkflowSelected">
          <el-button link type="primary" @click="emit('switchToPlatformGeneration')">改用平台生成</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :icon="MagicStick" :loading="generating" @click="emit('generateDraft')">
            生成 Workflow 草稿
          </el-button>
        </template>
      </div>
    </section>
  </div>
</template>
