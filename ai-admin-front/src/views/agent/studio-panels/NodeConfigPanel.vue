<template>
  <component
    :is="panel"
    v-if="panel"
    :data="data"
    :model-options="modelOptions"
    :knowledge-options="knowledgeOptions"
    :variable-options="variableOptions"
    :credential-options="credentialOptions"
    :param-source-hints="paramSourceHints"
    :project-id="projectId"
    :project-code="projectCode"
    :options="toolLikeOptions"
    @credential-created="$emit('credentialCreated', $event)"
  />
  <div v-else class="node-specific-panel">
    <el-divider>节点配置</el-divider>
    <el-alert title="该节点没有专属配置项" type="info" :closable="false" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ToolInfo } from '@/types/tool'
import type { CapabilityInfo } from '@/types/capability'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import LlmConfigPanel from './LlmConfigPanel.vue'
import KnowledgeConfigPanel from './KnowledgeConfigPanel.vue'
import HttpConfigPanel from './HttpConfigPanel.vue'
import ParameterConfigPanel from './ParameterConfigPanel.vue'
import ConditionConfigPanel from './ConditionConfigPanel.vue'
import ToolConfigPanel from './ToolConfigPanel.vue'
import AnswerConfigPanel from './AnswerConfigPanel.vue'
import CodeConfigPanel from './CodeConfigPanel.vue'
import IntentClassifierConfigPanel from './IntentClassifierConfigPanel.vue'
import VariableAggregateConfigPanel from './VariableAggregateConfigPanel.vue'
import ApprovalConfigPanel from './ApprovalConfigPanel.vue'
import LoopConfigPanel from './LoopConfigPanel.vue'
import KnowledgeWriteConfigPanel from './KnowledgeWriteConfigPanel.vue'
import DocumentExtractConfigPanel from './DocumentExtractConfigPanel.vue'
import McpConfigPanel from './McpConfigPanel.vue'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
  knowledgeOptions: KnowledgeBase[]
  toolOptions: ToolInfo[]
  capabilityOptions: CapabilityInfo[]
  variableOptions: string[]
  credentialOptions: WorkflowCredential[]
  paramSourceHints: ApiGraphParamSourceHint[]
  projectId?: number | null
  projectCode?: string | null
}>()

defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
}>()

const registry = {
  llm: LlmConfigPanel,
  knowledge: KnowledgeConfigPanel,
  http: HttpConfigPanel,
  parameter: ParameterConfigPanel,
  condition: ConditionConfigPanel,
  answer: AnswerConfigPanel,
  code: CodeConfigPanel,
  classifier: IntentClassifierConfigPanel,
  aggregate: VariableAggregateConfigPanel,
  approval: ApprovalConfigPanel,
  loop: LoopConfigPanel,
  knowledgeWrite: KnowledgeWriteConfigPanel,
  documentExtract: DocumentExtractConfigPanel,
  mcp: McpConfigPanel,
  tool: ToolConfigPanel,
  skill: ToolConfigPanel,
}

const panel = computed(() => registry[props.data.kind as keyof typeof registry])
const toolLikeOptions = computed(() => props.data.kind === 'skill' ? props.capabilityOptions : props.toolOptions)
</script>

<style lang="scss">
.node-specific-panel:not(.llm-panel) {
  display: grid;
  gap: 14px;

  > .el-divider {
    justify-content: flex-start;
    height: auto;
    margin: 2px 0 0;
    border-top: 0;

    .el-divider__text {
      position: static;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 0;
      background: transparent;
      color: #0f172a;
      font-size: 15px;
      font-weight: 850;
      transform: none;

      &::before {
        content: '';
        width: 9px;
        height: 9px;
        border-radius: 3px;
        background: linear-gradient(135deg, #635bff, #0ea5e9);
        box-shadow: 0 0 0 4px rgba(99, 91, 255, 0.1);
      }
    }
  }

  > .el-form-item,
  > .node-config-alert,
  > .param-hints,
  > .tool-params,
  > .condition-group,
  > .field-table-head + .field-row,
  > .field-table-head + .aggregate-row,
  > .field-table-head + .classifier-row {
    margin-bottom: 0;
  }

  > .el-form-item,
  .condition-group,
  .param-hints,
  .tool-params {
    padding: 14px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background:
      linear-gradient(180deg, rgba(248, 250, 252, 0.86), rgba(255, 255, 255, 0.98)),
      #ffffff;
    box-shadow: 0 12px 28px rgba(15, 23, 42, 0.04);
  }

  > .el-form-item {
    align-items: flex-start;
  }

  .el-form-item__label {
    color: #475569;
    font-size: 13px;
    font-weight: 800;
    line-height: 42px;
  }

  .el-input__wrapper,
  .el-select__wrapper {
    min-height: 42px;
    border-radius: 11px;
    box-shadow: 0 0 0 1px #dbe3ef inset;
  }

  .el-input__inner,
  .el-select__placeholder,
  .el-select__selected-item {
    color: #334155;
    font-size: 14px;
    font-weight: 600;
  }

  .el-textarea__inner {
    min-height: 128px;
    padding: 13px 15px;
    border-radius: 13px;
    color: #334155;
    font-size: 14px;
    line-height: 1.7;
    box-shadow: 0 0 0 1px #dbe3ef inset;
  }

  .el-input-number {
    width: 150px;
  }

  .el-input-number .el-input__wrapper {
    min-height: 42px;
  }

  .el-segmented {
    --el-segmented-item-selected-bg-color: #635bff;
    --el-segmented-item-selected-color: #ffffff;
    border-radius: 9px;
    background: #eef2ff;
  }

  .el-switch.is-checked .el-switch__core {
    background-color: #635bff;
    border-color: #635bff;
  }

  .el-button--primary.is-plain {
    border-color: #c7d2fe;
    background: #eef2ff;
    color: #4f46e5;
  }

  .el-button--danger.is-text {
    color: #e11d48;
  }

  .field-table-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin: 2px 0 0;
    padding: 13px 14px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #f8fbff;

    strong {
      color: #0f172a;
      font-size: 14px;
      font-weight: 850;
    }
  }

  .field-row,
  .condition-row,
  .condition-group-head {
    display: grid;
    grid-template-columns: minmax(92px, 1fr) auto auto minmax(100px, 0.8fr) minmax(120px, 1.1fr) auto;
    gap: 8px;
    align-items: center;
    margin-bottom: 0;
    padding: 10px;
    border: 1px solid #e8eef7;
    border-radius: 12px;
    background: #ffffff;
  }

  .schema-row {
    grid-template-columns: minmax(120px, 1fr) auto auto minmax(110px, 0.8fr) auto;
  }

  .condition-group {
    display: grid;
    gap: 8px;
  }

  .condition-group-head {
    grid-template-columns: 1fr auto auto;
    padding: 0 0 10px;
    border: 0;
    border-bottom: 1px solid #e8eef7;
    border-radius: 0;
    background: transparent;
  }

  .condition-row {
    grid-template-columns: minmax(110px, 1fr) auto minmax(100px, 1fr) auto;
  }

  .classifier-row,
  .aggregate-row {
    display: grid;
    grid-template-columns: minmax(90px, 0.8fr) minmax(120px, 1fr) minmax(160px, 1.4fr) auto;
    gap: 8px;
    align-items: center;
    margin-bottom: 0;
    padding: 10px;
    border: 1px solid #e8eef7;
    border-radius: 12px;
    background: #ffffff;
  }

  .aggregate-row {
    grid-template-columns: minmax(110px, 0.8fr) minmax(180px, 1.6fr) auto;
  }

  .tool-params,
  .param-hints {
    display: grid;
    gap: 8px;
    margin: 0;

    .field-table-head {
      padding: 0 0 8px;
      border: 0;
      border-bottom: 1px solid #e8eef7;
      border-radius: 0;
      background: transparent;
    }
  }

  .param-hint-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 8px;
    align-items: center;
    padding: 10px;
    border: 1px solid #e8eef7;
    border-radius: 10px;
    background: #ffffff;
    font-size: 12px;
    line-height: 1.5;

    div {
      min-width: 0;
    }

    strong,
    span {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    span {
      color: #64748b;
    }
  }

  .tool-param-row {
    display: grid;
    grid-template-columns: minmax(120px, 1fr) 80px 80px;
    gap: 8px;
    align-items: center;
    padding: 10px;
    border: 1px solid #e8eef7;
    border-radius: 10px;
    background: #ffffff;
    font-size: 12px;

    span,
    em {
      color: #64748b;
      font-style: normal;
    }
  }

  @media (max-width: 760px) {
    .field-row,
    .condition-row,
    .classifier-row,
    .aggregate-row,
    .condition-group-head,
    .param-hint-row,
    .tool-param-row {
      grid-template-columns: 1fr;
    }
  }
}
</style>
