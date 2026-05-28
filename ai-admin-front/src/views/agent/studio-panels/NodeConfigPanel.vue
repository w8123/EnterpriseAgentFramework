<template>
  <el-tabs v-model="activeContractTab" class="node-contract-tabs">
    <el-tab-pane label="输入" name="input">
      <div class="node-specific-panel contract-panel">
        <el-divider>输入合同</el-divider>
        <el-alert
          title="必填输入需要绑定到 params、sys、nodeOutput 或业务别名；系统会保留 lastOutput 作为便捷默认值。"
          type="info"
          :closable="false"
        />
        <div class="field-table-head">
          <strong>输入字段</strong>
          <el-button size="small" type="primary" plain @click="addInputPort">添加输入</el-button>
        </div>
        <div v-for="(port, index) in editableInputs" :key="port.id || index" class="contract-row">
          <el-input v-model="port.id" placeholder="目标字段，如 query" @change="syncInputs" />
          <el-select v-model="port.type" placeholder="类型" @change="syncInputs">
            <el-option label="string" value="string" />
            <el-option label="number" value="number" />
            <el-option label="boolean" value="boolean" />
            <el-option label="object" value="object" />
            <el-option label="array" value="array" />
            <el-option label="any" value="any" />
          </el-select>
          <el-switch v-model="port.required" inline-prompt active-text="必填" inactive-text="可选" @change="syncInputs" />
          <el-select
            :model-value="inputMapping[port.id || port.name || ''] || port.source || ''"
            filterable
            allow-create
            default-first-option
            :filter-method="filterVariableOptions"
            placeholder="选择变量来源"
            popper-class="studio-variable-select"
            @change="(value: string) => bindInput(port.id || port.name || '', value)"
          >
            <el-option-group
              v-for="group in groupedVariableOptions"
              :key="group.group"
              :label="group.group"
            >
              <el-option
                v-for="item in group.options"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              >
                <div class="variable-option">
                  <strong>{{ item.label }}</strong>
                  <span>{{ item.value }}</span>
                  <em v-if="item.description">{{ item.description }}</em>
                </div>
              </el-option>
            </el-option-group>
          </el-select>
          <el-button text type="danger" @click="removeInputPort(index)">删除</el-button>
        </div>
        <div v-if="!editableInputs.length" class="panel-empty-row">
          当前节点没有声明输入。可以添加输入字段，也可以在专属配置中继续使用表达式。
        </div>
      </div>
    </el-tab-pane>

    <el-tab-pane label="配置" name="config">
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
        :tool-options="toolOptions"
        :composition-options="compositionOptions"
        @credential-created="$emit('credentialCreated', $event)"
        @create-call-node="$emit('createCallNode', $event)"
      />
      <div v-else class="node-specific-panel">
        <el-divider>节点配置</el-divider>
        <el-alert title="该节点没有专属配置项" type="info" :closable="false" />
      </div>
    </el-tab-pane>

    <el-tab-pane label="输出" name="output">
      <div class="node-specific-panel contract-panel">
        <el-divider>输出合同</el-divider>
        <el-form-item label="输出别名">
          <el-input v-model="data.outputAlias" placeholder="如 intent_result / business_result" @change="syncOutputAlias" />
        </el-form-item>
        <div class="field-table-head">
          <strong>输出变量</strong>
          <el-button size="small" type="primary" plain @click="addOutputPort">添加输出</el-button>
        </div>
        <div v-for="(port, index) in editableOutputs" :key="port.id || index" class="contract-row output-row">
          <el-input v-model="port.id" placeholder="输出字段，如 answer" @change="syncOutputs" />
          <el-select v-model="port.type" placeholder="类型" @change="syncOutputs">
            <el-option label="string" value="string" />
            <el-option label="number" value="number" />
            <el-option label="boolean" value="boolean" />
            <el-option label="object" value="object" />
            <el-option label="array" value="array" />
            <el-option label="any" value="any" />
          </el-select>
          <el-input v-model="port.source" placeholder="来源说明，可选" @change="syncOutputs" />
          <el-button text type="danger" @click="removeOutputPort(index)">删除</el-button>
        </div>
        <div v-if="!editableOutputs.length" class="panel-empty-row">
          当前节点没有声明输出。建议至少保留一个业务别名，方便下游节点引用。
        </div>
      </div>
    </el-tab-pane>

    <el-tab-pane label="高级" name="advanced">
      <div class="node-specific-panel contract-panel">
        <el-divider>变量合同 JSON</el-divider>
        <el-alert title="这里展示保存到 GraphSpec 的变量合同，适合 SDK 或高级调试对齐。" type="warning" :closable="false" />
        <pre class="contract-json">{{ contractJson }}</pre>
      </div>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CanvasNodeData, InteractionCallNodeRequest, StudioPort, StudioVariableOption } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ToolInfo } from '@/types/tool'
import type { CompositionInfo } from '@/types/composition'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import LlmConfigPanel from './LlmConfigPanel.vue'
import UserInputConfigPanel from './UserInputConfigPanel.vue'
import InteractionConfigPanel from './InteractionConfigPanel.vue'
import PageActionConfigPanel from './PageActionConfigPanel.vue'
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
  compositionOptions: CompositionInfo[]
  variableOptions: Array<string | StudioVariableOption>
  credentialOptions: WorkflowCredential[]
  paramSourceHints: ApiGraphParamSourceHint[]
  projectId?: number | null
  projectCode?: string | null
}>()

defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
  createCallNode: [request: InteractionCallNodeRequest]
}>()

const registry = {
  userInput: UserInputConfigPanel,
  interaction: InteractionConfigPanel,
  pageAction: PageActionConfigPanel,
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
const toolLikeOptions = computed(() => props.data.kind === 'skill' ? props.compositionOptions : props.toolOptions)
const activeContractTab = ref('config')
const editableInputs = ref<StudioPort[]>([])
const editableOutputs = ref<StudioPort[]>([])

const variableKeyword = ref('')
const fallbackVariableOptions: StudioVariableOption[] = [
  { value: 'params', label: '用户输入 · 全部参数', group: '用户输入', description: '用户输入节点写入的 params 对象' },
  { value: 'params.question', label: '用户输入 · 用户问题', group: '用户输入', description: '用户输入节点的 question 字段' },
  { value: 'sys.userId', label: '系统变量 · 当前用户 ID', group: '系统变量', description: '运行上下文中的用户标识' },
  { value: 'sys.tenantId', label: '系统变量 · 租户 ID', group: '系统变量', description: '运行上下文中的租户标识' },
  { value: 'sys.roles', label: '系统变量 · 用户角色', group: '系统变量', description: '当前用户角色列表' },
  { value: 'input', label: '运行态 · 原始输入消息', group: '运行态变量', description: '本次运行的原始消息' },
  { value: 'answer', label: '运行态 · 最终回答', group: '运行态变量', description: '当前已生成的 answer' },
  { value: 'lastOutput', label: '运行态 · 上一步输出', group: '运行态变量', description: '便捷变量，适合快速串联原型' },
  { value: 'lastRoute', label: '运行态 · 命中分支', group: '运行态变量', description: '条件或意图分类节点最近一次路由' },
]

const normalizedVariableOptions = computed<StudioVariableOption[]>(() => {
  const map = new Map<string, StudioVariableOption>()
  for (const item of fallbackVariableOptions) map.set(item.value, item)
  for (const item of props.variableOptions) {
    const option = normalizeVariableOption(item)
    if (option.value) map.set(option.value, option)
  }
  return Array.from(map.values())
})

const groupedVariableOptions = computed(() => {
  const keyword = variableKeyword.value.trim().toLowerCase()
  const filtered = normalizedVariableOptions.value.filter((item) => {
    if (!keyword) return true
    return [item.label, item.value, item.group, item.description, item.source]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
  const groups = ['用户输入', '系统变量', '节点输出', '运行态变量', '高级表达式']
  return groups
    .map((group) => ({
      group,
      options: filtered.filter((item) => item.group === group),
    }))
    .filter((group) => group.options.length)
})

function filterVariableOptions(keyword: string) {
  variableKeyword.value = keyword
}

function normalizeVariableOption(item: string | StudioVariableOption): StudioVariableOption {
  if (typeof item !== 'string') {
    return item
  }
  if (item.startsWith('sys.')) {
    return {
      value: item,
      label: systemVariableLabel(item),
      group: '系统变量',
      description: item,
    }
  }
  if (item.startsWith('nodeOutput.')) {
    return {
      value: item,
      label: `节点输出 · ${item.replace('nodeOutput.', '')}`,
      group: '节点输出',
      description: item,
    }
  }
  if (item.startsWith('var.')) {
    return {
      value: item,
      label: `业务别名 · ${item.replace('var.', '')}`,
      group: '节点输出',
      description: item,
    }
  }
  if (['input', 'answer', 'lastOutput', 'lastRoute', 'lastSuccess', 'lastError'].includes(item)) {
    return {
      value: item,
      label: runtimeVariableLabel(item),
      group: '运行态变量',
      description: item,
    }
  }
  if (item.startsWith('params')) {
    return {
      value: item,
      label: item === 'params' ? '用户输入 · 全部参数' : `用户输入 · ${item.replace('params.', '')}`,
      group: '用户输入',
      description: item,
    }
  }
  return {
    value: item,
    label: `表达式 · ${item}`,
    group: '高级表达式',
    description: item,
  }
}

function systemVariableLabel(value: string) {
  const labels: Record<string, string> = {
    'sys.userId': '系统变量 · 当前用户 ID',
    'sys.tenantId': '系统变量 · 租户 ID',
    'sys.roles': '系统变量 · 用户角色',
  }
  return labels[value] || `系统变量 · ${value.replace('sys.', '')}`
}

function runtimeVariableLabel(value: string) {
  const labels: Record<string, string> = {
    input: '运行态 · 原始输入消息',
    answer: '运行态 · 最终回答',
    lastOutput: '运行态 · 上一步输出',
    lastRoute: '运行态 · 命中分支',
    lastSuccess: '运行态 · 上一步是否成功',
    lastError: '运行态 · 上一步错误',
  }
  return labels[value] || `运行态 · ${value}`
}

const inputMapping = computed(() => {
  if (props.data.kind === 'tool' || props.data.kind === 'skill') {
    props.data.toolConfig ||= { inputMapping: {} }
    props.data.toolConfig.inputMapping ||= {}
    return props.data.toolConfig.inputMapping
  }
  if (props.data.kind === 'mcp') {
    props.data.mcpConfig ||= { toolName: '', inputMapping: {} }
    props.data.mcpConfig.inputMapping ||= {}
    return props.data.mcpConfig.inputMapping
  }
  props.data.inputMapping ||= {}
  return props.data.inputMapping
})

const contractJson = computed(() => JSON.stringify({
  inputSchema: props.data.inputSchema || {},
  outputSchema: props.data.outputSchema || {},
  inputMapping: inputMapping.value,
  outputAlias: props.data.outputAlias || '',
  inputs: props.data.inputs || [],
  outputs: props.data.outputs || [],
}, null, 2))

watch(
  () => props.data,
  () => {
    editableInputs.value = (props.data.inputs || []).map((item) => ({ ...item }))
    editableOutputs.value = (props.data.outputs || []).map((item) => ({ ...item }))
  },
  { immediate: true, deep: false },
)

function addInputPort() {
  editableInputs.value.push({ id: `input${editableInputs.value.length + 1}`, name: '', type: 'string', required: true })
  syncInputs()
}

function removeInputPort(index: number) {
  const [removed] = editableInputs.value.splice(index, 1)
  const key = removed?.id || removed?.name || ''
  if (key) delete inputMapping.value[key]
  syncInputs()
}

function syncInputs() {
  props.data.inputs = editableInputs.value.map((item) => ({ ...item, name: item.name || item.id }))
  props.data.inputSchema = { fields: props.data.inputs }
}

function bindInput(target: string, source: string) {
  if (!target) return
  inputMapping.value[target] = source
  props.data.inputMapping = { ...inputMapping.value }
  if (props.data.kind === 'tool' || props.data.kind === 'skill') {
    props.data.toolConfig ||= { inputMapping: {} }
    props.data.toolConfig.inputMapping = { ...inputMapping.value }
  }
  if (props.data.kind === 'mcp') {
    props.data.mcpConfig ||= { toolName: '', inputMapping: {} }
    props.data.mcpConfig.inputMapping = { ...inputMapping.value }
  }
}

function addOutputPort() {
  const alias = props.data.outputAlias || 'output'
  editableOutputs.value.push({ id: alias, name: alias, type: 'any', required: false })
  syncOutputs()
}

function removeOutputPort(index: number) {
  editableOutputs.value.splice(index, 1)
  syncOutputs()
}

function syncOutputs() {
  props.data.outputs = editableOutputs.value.map((item) => ({ ...item, name: item.name || item.id }))
  props.data.outputSchema = { fields: props.data.outputs }
}

function syncOutputAlias() {
  if (props.data.kind === 'userInput') {
    props.data.userInputConfig ||= { fields: [], outputAlias: 'params' }
    props.data.userInputConfig.outputAlias = props.data.outputAlias || 'params'
  }
  if (props.data.kind === 'interaction') {
    props.data.interactionConfig ||= {
      interactionType: 'COLLECT_INPUT',
      binding: { sourceKind: 'NONE' },
      title: props.data.label,
      component: 'FORM',
      fields: [],
      outputAlias: 'interaction_output',
    }
    props.data.interactionConfig.binding ||= { sourceKind: 'NONE' }
    props.data.interactionConfig.outputAlias = props.data.outputAlias || 'interaction_output'
  }
}
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

  .user-input-field-row {
    grid-template-columns: minmax(120px, 1fr) auto auto minmax(120px, 1fr) minmax(96px, 0.8fr) auto;
  }

  .panel-empty-row {
    padding: 14px;
    border: 1px dashed #cbd5e1;
    border-radius: 12px;
    background: #f8fafc;
    color: #64748b;
    font-size: 13px;
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
    .user-input-field-row,
    .condition-group-head,
    .param-hint-row,
    .tool-param-row {
      grid-template-columns: 1fr;
    }
  }
}

.node-contract-tabs {
  .el-tabs__header {
    margin-bottom: 14px;
  }
}

.contract-panel {
  .contract-row {
    display: grid;
    grid-template-columns: minmax(120px, 1fr) 110px auto minmax(180px, 1.4fr) auto;
    gap: 8px;
    align-items: center;
    padding: 10px;
    border: 1px solid #e8eef7;
    border-radius: 12px;
    background: #ffffff;
  }

  .output-row {
    grid-template-columns: minmax(140px, 1fr) 110px minmax(160px, 1fr) auto;
  }

  .contract-json {
    max-height: 360px;
    margin: 0;
    padding: 12px;
    overflow: auto;
    border-radius: 10px;
    background: #0f172a;
    color: #e2e8f0;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }

  @media (max-width: 760px) {
    .contract-row,
    .output-row {
      grid-template-columns: 1fr;
    }
  }
}

:global(.studio-variable-select) {
  .el-select-group__title {
    color: #64748b;
    font-size: 12px;
    font-weight: 800;
  }

  .el-select-dropdown__item {
    height: auto;
    min-height: 56px;
    padding: 7px 14px;
    line-height: 1.35;
  }

  .variable-option {
    display: grid;
    gap: 2px;
    min-width: 0;

    strong,
    span,
    em {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      color: #0f172a;
      font-size: 13px;
      font-weight: 850;
    }

    span {
      color: #4f46e5;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12px;
      font-weight: 700;
    }

    em {
      color: #94a3b8;
      font-size: 11px;
      font-style: normal;
    }
  }
}
</style>
