<template>
  <div
    class="ai-edit-bar"
    :class="{ 'debug-drawer-open': debugOpen, 'is-generating': loading }"
    @mousedown.stop
    @click.stop
  >
    <div class="ai-edit-input-row">
      <el-input
        v-model="instruction"
        :disabled="readOnly || loading"
        clearable
        class="ai-edit-main-input"
        type="textarea"
        resize="none"
        :autosize="{ minRows: 2, maxRows: 4 }"
        placeholder="描述你想创建、修改或修复的 Workflow，AI 会结合当前画布理解意图"
        @keydown.enter.exact.prevent="emit('run')"
      />
      <div class="ai-edit-toolbar">
        <div class="ai-edit-toolbar-left">
          <el-tooltip content="AI 会参考当前画布、选中的节点、检查结果和可用的业务能力生成修改方案" placement="top">
            <el-button text circle :icon="MagicStick" disabled />
          </el-tooltip>
        </div>
        <div class="ai-edit-toolbar-right">
          <el-tooltip content="最小化 AI 编排" placement="top">
            <el-button
              class="ai-edit-minimize"
              circle
              :icon="Minus"
              aria-label="最小化 AI 编排"
              @click="emit('minimize')"
            />
          </el-tooltip>
          <el-popover trigger="click" placement="top-end" width="360" popper-class="ai-edit-model-popover">
            <template #reference>
              <el-button
                class="ai-edit-model-trigger"
                :class="{ active: !!selectedModelLabel }"
                :disabled="loading"
                circle
                aria-label="选择 AI 编排模型"
              >
                <el-icon><LlmModelIcon /></el-icon>
              </el-button>
            </template>
            <div class="ai-edit-model-panel">
              <div class="ai-edit-model-title">选择 AI 编排模型</div>
              <el-select
                v-model="modelInstanceId"
                filterable
                clearable
                placeholder="选择用于创建和修改的模型"
                :disabled="loading"
                :loading="modelOptionsLoading"
                @visible-change="(visible: boolean) => visible && emit('load-models')"
              >
                <el-option
                  v-for="item in modelOptions"
                  :key="item.id"
                  :label="modelOptionLabel(item)"
                  :value="item.id"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="LLM"
                    :option-count="modelOptions.length"
                    :loading="modelOptionsLoading"
                    :load-error="modelOptionsLoadError"
                    @retry="emit('load-models')"
                  />
                </template>
              </el-select>
              <div class="ai-edit-model-hint">{{ selectedModelLabel || '未选择模型时会使用 Workflow 默认模型' }}</div>
            </div>
          </el-popover>
          <el-tooltip content="生成编排方案" placement="top">
            <el-button
              class="ai-edit-send"
              type="primary"
              circle
              :icon="SendIcon"
              :loading="loading"
              :disabled="!instruction.trim()"
              aria-label="生成编排方案"
              @click="emit('run')"
            />
          </el-tooltip>
        </div>
      </div>
    </div>
    <div
      v-if="preview"
      class="ai-edit-preview"
      :class="{ 'is-failed': previewFailed }"
    >
      <div class="ai-edit-preview-head">
        <strong>
          {{ previewFailed ? 'AI 编排生成失败' : (preview.summary || 'AI 编排预览') }}
        </strong>
        <span>{{ preview.operations.length }} 项变更</span>
      </div>
      <div v-if="previewFailed" class="ai-edit-alert error">
        <span>{{ preview.summary || 'AI 没能生成可用的修改方案，可以换个说法再试一次。' }}</span>
        <span v-if="preview.authoringId">错误编号：{{ preview.authoringId }}</span>
        <span v-if="preview.failureCode">失败码：{{ preview.failureCode }}</span>
        <span>这个方案没有通过校验，不能应用到草稿。</span>
        <span v-for="item in preview.validationErrors || []" :key="item">{{ item }}</span>
      </div>
      <div v-else-if="preview.validationErrors?.length" class="ai-edit-alert error">
        <span v-for="item in preview.validationErrors" :key="item">{{ item }}</span>
      </div>
      <div v-if="preview.warnings?.length" class="ai-edit-alert warning">
        <span v-for="item in preview.warnings" :key="item">{{ item }}</span>
      </div>
      <div class="ai-edit-operation-grid">
        <div
          v-for="group in operationGroups"
          :key="group.type"
          class="ai-edit-operation-group"
        >
          <strong>{{ group.label }}</strong>
          <span v-for="item in group.items" :key="operationKey(item)">
            {{ operationTarget(item) }}{{ item.reason ? `：${item.reason}` : '' }}
          </span>
        </div>
      </div>
      <div class="ai-edit-actions">
        <el-button text @click="emit('clear')">取消</el-button>
        <el-button
          v-if="previewFailed"
          type="primary"
          :loading="loading"
          :disabled="loading"
          @click="emit('regenerate')"
        >
          重新生成
        </el-button>
        <el-button
          v-else
          type="primary"
          :disabled="previewFailed || !!preview.validationErrors?.length"
          @click="emit('apply')"
        >
          应用到草稿
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { MagicStick, Minus } from '@element-plus/icons-vue'
import LlmModelIcon from '@/components/icons/LlmModelIcon.vue'
import SendIcon from '@/components/icons/SendIcon.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import type { ModelInstance } from '@/types/model'
import type { WorkflowProposalEditOperation, WorkflowProposalEditResult } from '@/types/workflow'
import { operationTarget, proposalOperationKey as operationKey } from '../composables/useWorkflowStudioProposalActions'

defineProps<{
  readOnly: boolean
  loading: boolean
  debugOpen: boolean
  preview: WorkflowProposalEditResult | null
  previewFailed: boolean
  operationGroups: Array<{ type: string; label: string; items: WorkflowProposalEditOperation[] }>
  modelOptions: ModelInstance[]
  modelOptionsLoading: boolean
  modelOptionsLoadError: boolean
  selectedModelLabel: string
}>()

const emit = defineEmits<{
  run: []
  apply: []
  clear: []
  regenerate: []
  minimize: []
  'load-models': []
}>()

const instruction = defineModel<string>('instruction', { required: true })
const modelInstanceId = defineModel<string>('modelInstanceId', { required: true })

function modelOptionLabel(item: ModelInstance) {
  return `${item.name || item.id} / ${item.provider || '-'} / ${item.modelName || '-'}`
}
</script>

<style scoped lang="scss">
.ai-edit-preview {
  display: grid;
  gap: 10px;
  min-width: 0;
  margin-bottom: 12px;
}

.ai-edit-preview.is-failed {
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--el-color-danger) 35%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--el-color-danger) 8%, transparent);
}

.ai-edit-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.ai-edit-preview-head strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-edit-preview-head span {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ai-edit-operation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 8px;
}

.ai-edit-operation-group {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.ai-edit-operation-group strong {
  color: var(--el-text-color-primary);
  font-size: 12px;
}

.ai-edit-operation-group span {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-edit-bar {
  position: absolute;
  right: 22px;
  bottom: 72px;
  left: 22px;
  z-index: 10;
  display: grid;
  gap: 10px;
  max-width: 860px;
  margin: 0 auto;
  padding: 18px 20px 16px;
  border: 1px solid transparent;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.74), rgba(241, 245, 249, 0.44)) padding-box,
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(129, 140, 248, 0.28)) border-box;
  box-shadow:
    0 24px 70px rgba(15, 23, 42, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.68);
  backdrop-filter: blur(22px) saturate(1.18);
  isolation: isolate;
  overflow: hidden;
}

.ai-edit-bar::before,
.ai-edit-bar::after {
  content: '';
  position: absolute;
  pointer-events: none;
}

.ai-edit-bar::before {
  inset: -2px;
  z-index: 0;
  border-radius: inherit;
  background: conic-gradient(from 140deg, #7c3aed, #06b6d4, #22c55e, #f472b6, #7c3aed);
  opacity: 0;
  filter: blur(1px);
}

.ai-edit-bar::after {
  inset: 1px;
  z-index: 1;
  border-radius: 27px;
  background:
    radial-gradient(circle at 12% 0%, rgba(129, 140, 248, 0.12), transparent 30%),
    radial-gradient(circle at 88% 12%, rgba(34, 211, 238, 0.12), transparent 30%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.68), rgba(248, 250, 252, 0.5));
  backdrop-filter: blur(22px) saturate(1.18);
}

.ai-edit-bar > * {
  position: relative;
  z-index: 2;
}

.ai-edit-bar.is-generating::before {
  opacity: 0.78;
  animation: debugAuraSpin 5.8s linear infinite, debugPulseGlow 2.4s ease-in-out infinite;
}

.ai-edit-bar.debug-drawer-open {
  right: calc(min(960px, 58vw) + 22px);
  max-width: none;
  margin-left: 0;
}

.ai-edit-input-row {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.ai-edit-main-input :deep(.el-textarea__inner) {
  min-height: 56px !important;
  padding: 4px 4px 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: #0f172a;
  font-size: 17px;
  line-height: 1.55;
}

.ai-edit-main-input :deep(.el-textarea__inner::placeholder) {
  color: rgba(51, 65, 85, 0.58);
}

.ai-edit-toolbar,
.ai-edit-toolbar-left,
.ai-edit-toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-edit-toolbar {
  justify-content: space-between;
}

.ai-edit-minimize,
.ai-edit-model-trigger,
.ai-edit-send {
  width: 38px;
  height: 38px;
  border: 0;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}

.ai-edit-minimize,
.ai-edit-model-trigger {
  background: rgba(248, 250, 252, 0.86);
  color: #1f2937;
}

.ai-edit-model-trigger.active {
  color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.15), 0 12px 24px rgba(37, 99, 235, 0.12);
}

.ai-edit-model-panel {
  display: grid;
  gap: 10px;
}

.ai-edit-model-panel .el-select {
  width: 100%;
}

.ai-edit-model-title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 800;
}

.ai-edit-model-hint {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-edit-send:not(.is-disabled) {
  background: #2563eb;
  color: #ffffff;
  box-shadow: 0 16px 32px rgba(37, 99, 235, 0.24);
}

@keyframes debugAuraSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes debugPulseGlow {
  0%,
  100% {
    opacity: 0.58;
  }

  50% {
    opacity: 0.9;
  }
}
</style>
