<template>
  <div
    class="workflow-inspector"
    :class="`is-${panel}`"
    role="region"
    :aria-label="panel === 'validation' ? '草稿检查' : '流程变量'"
    @mousedown.stop
    @click.stop
  >
    <section v-if="panel === 'validation'" class="inspector-section">
      <div class="inspector-head">
        <div class="inspector-title">
          <span class="inspector-title-icon is-validation">
            <el-icon><CircleCheck /></el-icon>
          </span>
          <div>
            <strong>草稿检查</strong>
            <small>画布结构与运行时发布门禁</small>
          </div>
        </div>
        <div class="inspector-head-actions">
          <el-tag size="small" round :type="validationTagType">
            {{ validationLabel }}
          </el-tag>
          <button class="inspector-close" type="button" aria-label="关闭草稿检查" @click="emit('close')">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>
      <div class="inspector-body">
        <div v-if="graphLintItems.length" class="inspector-list">
          <button
            v-for="item in graphLintItems.slice(0, 5)"
            :key="item.level + '-' + (item.nodeId || item.edgeId || item.message)"
            class="inspector-item"
            :class="item.level"
            type="button"
            @click="emit('focus-lint', item)"
          >
            <span>{{ item.level === 'error' ? '阻断' : '提醒' }}</span>
            <em>{{ item.message }}</em>
            <el-icon class="inspector-item-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
        <div v-if="runtimeValidationItems.length" class="inspector-list runtime-validation-list">
          <button
            v-for="item in runtimeValidationItems.slice(0, 5)"
            :key="item.level + '-' + item.code + '-' + (item.target || item.message)"
            class="inspector-item"
            :class="item.level"
            type="button"
            @click="emit('focus-runtime', item.target)"
          >
            <span>{{ item.level === 'error' ? '发布阻断' : '运行提醒' }}</span>
            <em>{{ item.message }}</em>
            <el-icon class="inspector-item-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
        <div v-if="validationRequestError" class="inspector-empty-state is-error">
          <span class="inspector-empty-icon"><Close /></span>
          <div>
            <strong>发布校验暂不可用</strong>
            <span>{{ validationRequestError }}</span>
          </div>
        </div>
        <div v-else-if="!graphLintItems.length && !runtimeValidationItems.length" class="inspector-empty-state">
          <span class="inspector-empty-icon"><CircleCheck /></span>
          <div>
            <strong>{{ validationValid ? '草稿检查通过' : '画布结构检查通过' }}</strong>
            <span>{{ validationValid
              ? '画布与运行时配置均已满足发布要求。'
              : '运行“校验”可继续确认模型、资源与运行时配置。' }}</span>
          </div>
        </div>
      </div>
    </section>
    <section v-else class="inspector-section">
      <div class="inspector-head">
        <div class="inspector-title">
          <span class="inspector-title-icon is-variable">
            <el-icon><Coin /></el-icon>
          </span>
          <div>
            <strong>流程变量</strong>
            <small>可在节点表达式和输入映射中引用</small>
          </div>
        </div>
        <div class="inspector-head-actions">
          <span class="inspector-count">{{ graphVariables.length }} 个</span>
          <button class="inspector-close" type="button" aria-label="关闭变量面板" @click="emit('close')">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>
      <div class="inspector-body">
        <div v-if="graphVariables.length" class="variable-chips">
          <button
            v-for="item in graphVariables.slice(0, 12)"
            :key="item.name"
            class="variable-chip"
            type="button"
            :title="`${item.name} · ${item.source}`"
            @click="emit('select-node', item.nodeId)"
          >
            <span class="variable-chip-icon"><Coin /></span>
            <span class="variable-chip-content">
              <code>{{ item.name }}</code>
              <small>来源：{{ item.source }}</small>
            </span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <div v-else class="inspector-empty-state">
          <span class="inspector-empty-icon"><Coin /></span>
          <div>
            <strong>暂无流程变量</strong>
            <span>配置节点输出别名后，可在后续节点中直接引用。</span>
          </div>
        </div>
        <div v-if="graphVariables.length > 12" class="inspector-more-hint">
          当前展示前 12 个变量，共 {{ graphVariables.length }} 个
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ArrowRight, CircleCheck, Close, Coin } from '@element-plus/icons-vue'
import type { GraphLintItem, WorkflowStudioGraphVariable } from '../composables/useWorkflowStudioGraphAnalysis'

export interface StudioRuntimeValidationItem {
  level: 'error' | 'warning'
  code?: string
  target?: string | null
  message: string
}

/** Floating panel above the canvas status bar: draft checks or the variables later nodes can reference. */
defineProps<{
  panel: 'validation' | 'variables'
  validationLabel: string
  validationTagType: 'danger' | 'warning' | 'success' | 'info'
  validationValid: boolean
  validationRequestError: string
  graphLintItems: GraphLintItem[]
  runtimeValidationItems: StudioRuntimeValidationItem[]
  graphVariables: WorkflowStudioGraphVariable[]
}>()

const emit = defineEmits<{
  close: []
  'focus-lint': [item: GraphLintItem]
  'focus-runtime': [target?: string | null]
  'select-node': [nodeId: string]
}>()
</script>

<style scoped lang="scss">
.workflow-inspector {
  position: absolute;
  left: 18px;
  right: auto;
  bottom: 72px;
  z-index: 6;
  width: min(760px, calc(100% - 36px));
  pointer-events: none;
}

.inspector-section {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  background: var(--el-bg-color-overlay, #fff);
  box-shadow: 0 20px 52px rgba(15, 23, 42, 0.14);
  pointer-events: auto;
  backdrop-filter: blur(16px);
}

.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 66px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: linear-gradient(135deg, var(--el-fill-color-blank) 0%, var(--el-color-primary-light-9) 100%);
}

.inspector-title,
.inspector-head-actions {
  display: flex;
  align-items: center;
}

.inspector-title {
  min-width: 0;
  gap: 10px;
}

.inspector-title > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.inspector-title strong {
  color: var(--el-text-color-primary);
  font-size: 15px;
  line-height: 1.2;
}

.inspector-title small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspector-title-icon {
  display: grid;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  font-size: 17px;
}

.inspector-title-icon.is-validation {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.inspector-title-icon.is-variable {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.inspector-head-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.inspector-count {
  padding: 4px 9px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
}

.inspector-close {
  display: grid;
  width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}

.inspector-close:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.inspector-body {
  max-height: min(300px, calc(100vh - 230px));
  padding: 12px 14px 14px;
  overflow-y: auto;
}

.inspector-list {
  display: grid;
  gap: 8px;
}

.runtime-validation-list {
  margin-top: 8px;
}

.inspector-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 16px;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
  transition: transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.inspector-item:hover {
  transform: translateY(-1px);
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}

.inspector-item > span {
  min-width: 48px;
  padding: 3px 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
  white-space: nowrap;
}

.inspector-item em {
  min-width: 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-style: normal;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.inspector-item-arrow {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.inspector-empty-state {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 64px;
  padding: 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}

.inspector-empty-state.is-error {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}

.inspector-empty-icon {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: var(--el-color-success-light-8);
  color: var(--el-color-success);
  font-size: 16px;
}

.inspector-empty-state.is-error .inspector-empty-icon {
  background: var(--el-color-danger-light-8);
  color: var(--el-color-danger);
}

.inspector-empty-state > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.inspector-empty-state strong {
  color: var(--el-text-color-primary);
  font-size: 13px;
}

.inspector-empty-state > div > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.variable-chips {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
}

.variable-chip {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 9px;
  min-width: 0;
  min-height: 58px;
  padding: 9px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
  transition: transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.variable-chip:hover {
  transform: translateY(-1px);
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}

.variable-chip-icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 9px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 14px;
}

.variable-chip-content {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.variable-chip code {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.variable-chip small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.variable-chip > .el-icon {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.inspector-more-hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-align: right;
}

.inspector-close:focus-visible,
.inspector-item:focus-visible,
.variable-chip:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.workflow-inspector.is-variables {
  width: min(860px, calc(100% - 36px));
}

.inspector-item.error {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}

.inspector-item.error > span {
  background: var(--el-color-danger-light-8);
  color: var(--el-color-danger);
}

.inspector-item.warning {
  border-color: var(--el-color-warning-light-7);
  background: var(--el-color-warning-light-9);
}

.inspector-item.warning > span {
  background: var(--el-color-warning-light-8);
  color: var(--el-color-warning-dark-2);
}
</style>
