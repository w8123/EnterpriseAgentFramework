<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'decision'"
    id="agent-config-decision"
    class="agent-core-panel supervisor-panel"
    title="对话与决策"
    description="选择 Agent 使用的模型，并说明它应如何理解需求和作出判断。"
    density="compact"
  >
    <template #actions>
      <el-button plain :icon="Setting" @click="advancedSettingsVisible = true">高级设置</el-button>
    </template>

    <div class="supervisor-config-stack">
      <div class="supervisor-inline-row">
        <span class="supervisor-inline-label">默认模型</span>
        <el-select
          v-model="supervisor.modelInstanceId"
          clearable
          filterable
          class="supervisor-inline-control"
          placeholder="发布前必选"
          :loading="llmModelInstancesLoading"
          @visible-change="handleModelSelectVisible"
        >
          <el-option
            v-for="item in llmModelInstances"
            :key="item.id"
            :label="`${item.name} (${item.modelName})`"
            :value="item.id"
          />
          <template #empty>
            <ModelSelectEmptyState
              model-type="LLM"
              :option-count="llmModelInstances.length"
              :loading="llmModelInstancesLoading"
              :load-error="llmModelInstancesLoadError"
              @retry="loadModelInstances"
            />
          </template>
        </el-select>
      </div>

      <div class="supervisor-inline-row">
        <span class="supervisor-inline-label">工作要求</span>
        <button
          type="button"
          class="prompt-preview"
          :class="{ 'is-empty': !systemPromptPreviewHasContent }"
          @click="openSystemPromptEditor"
        >
          <span class="prompt-preview__text">{{ systemPromptPreview }}</span>
          <el-icon class="prompt-preview__icon"><EditPen /></el-icon>
        </button>
      </div>
    </div>

    <p class="supervisor-panel-note">
      超时、重新规划和调用限制可在「高级设置」中调整。
    </p>
  </WorkbenchPanel>

  <AppDialog
    v-model="systemPromptDialogVisible"
    title="编辑 Agent 的工作要求"
    description="说明它的职责、判断规则、可调用范围和回答边界。"
    width="760px"
    append-to-body
    destroy-on-close
  >
    <el-input
      v-model="systemPromptDraft"
      type="textarea"
      :rows="18"
      class="system-prompt-editor"
      placeholder="例如：你是企业业务助手。先理解用户要解决的问题，再选择合适的 Workflow；没有可用流程时，直接说明限制。"
    />
    <template #footer>
      <LiquidGlassButton @click="systemPromptDialogVisible = false">取消</LiquidGlassButton>
      <LiquidGlassButton type="primary" @click="applySystemPromptDraft">保存工作要求</LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { EditPen, Setting } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  advancedSettingsVisible,
  systemPromptDialogVisible,
  systemPromptDraft,
  isNew,
  activeConfigSection,
  llmModelInstances,
  llmModelInstancesLoading,
  llmModelInstancesLoadError,
  supervisor,
  systemPromptPreviewHasContent,
  systemPromptPreview,
  openSystemPromptEditor,
  applySystemPromptDraft,
  loadModelInstances,
  handleModelSelectVisible,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.supervisor-panel-note {
  margin: 14px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.supervisor-config-stack {
  display: grid;
  gap: 14px;
  padding-top: 2px;
}

.supervisor-inline-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.supervisor-inline-label {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
  line-height: 20px;
  white-space: nowrap;
}

.supervisor-inline-control {
  width: 100%;
  min-width: 0;
}

.prompt-preview {
  display: grid;
  width: 100%;
  min-width: 0;
  min-height: 36px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--border-divider);
  border-radius: 8px;
  background: var(--surface-solid-control);
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.prompt-preview:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: color-mix(in srgb, var(--brand-selected-bg) 36%, var(--surface-solid-control));
}

.prompt-preview:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.32);
  outline-offset: 2px;
}

.prompt-preview.is-empty {
  color: var(--text-muted);
}

.prompt-preview__text {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prompt-preview__icon {
  color: var(--brand-active);
  font-size: 15px;
}

.system-prompt-editor :deep(.el-textarea__inner) {
  min-height: 360px;
  line-height: 1.65;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

@media (max-width: 960px) {
  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}
</style>
