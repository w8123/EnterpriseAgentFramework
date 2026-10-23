<template>
  <div class="node-config-workspace">
    <div class="node-primary-config">
      <component
        :is="panel"
        v-if="panel"
        :data="data"
        :node-id="nodeId"
        :canvas-nodes="canvasNodes"
        :model-options="modelOptions"
        :model-options-loading="modelOptionsLoading"
        :model-options-load-error="modelOptionsLoadError"
        :knowledge-options="knowledgeOptions"
        :variable-options="variableOptions"
        :credential-options="credentialOptions"
        :project-id="projectId"
        :project-code="projectCode"
        :request-scope-key="requestScopeKey"
        :options="toolLikeOptions"
        :tool-options="toolOptions"
        :tool-options-loading="toolOptionsLoading"
        :tool-options-load-error="toolOptionsLoadError"
        @retry-methods="$emit('retryMethods')"
        :node-type-options="nodeTypeOptions"
        @credential-created="$emit('credentialCreated', $event)"
        @create-call-node="$emit('createCallNode', $event)"
        @validation-change="$emit('validationChange', $event)"
        @reload-model-options="$emit('reloadModelOptions')"
      />
      <div v-else class="node-specific-panel">
        <LiquidGlassNotice
          :title="data.kind === 'start' ? '流程从这里开始，后续节点可引用本次输入。' : data.kind === 'end' ? '流程在这里结束，返回当前已生成的回答。' : '该节点没有专属配置项'"
          type="info"
          source="backdrop"
        />
      </div>
    </div>
    <div class="node-contract-sections">
      <ConfigSection title="输入变量" description="声明字段与变量来源" :count="editableInputs.length" collapsible>
        <div class="node-specific-panel contract-panel">
          <LiquidGlassNotice
            title="为输入字段选择变量来源，必填字段需要绑定有效的值。"
            type="info"
            source="backdrop"
          />
          <div class="field-table-head">
            <strong>输入字段</strong>
            <el-button size="small" type="primary" plain @click="addInputPort">添加输入</el-button>
          </div>
          <div v-for="(port, index) in editableInputs" :key="port.id || index" class="contract-row input-contract-row">
            <el-input v-model="port.id" placeholder="目标字段，如 query" @change="syncInputs" />
            <el-select v-model="port.type" placeholder="类型" @change="syncInputs">
              <el-option label="string" value="string" />
              <el-option label="number" value="number" />
              <el-option label="boolean" value="boolean" />
              <el-option label="object" value="object" />
              <el-option label="array" value="array" />
              <el-option label="any" value="any" />
            </el-select>
            <el-switch
              v-model="port.required"
              inline-prompt
              active-text="必填"
              inactive-text="可选"
              @change="syncInputs"
            />
            <el-select
              class="contract-source"
              :model-value="inputMapping[port.id || port.name || ''] || port.source || ''"
              filterable
              allow-create
              default-first-option
              :filter-method="filterVariableOptions"
              placeholder="选择变量来源"
              popper-class="studio-variable-select"
              @change="(value: string) => bindInput(port.id || port.name || '', value)"
            >
              <el-option-group v-for="group in groupedVariableOptions" :key="group.group" :label="group.group">
                <el-option v-for="item in group.options" :key="item.value" :label="item.label" :value="item.value">
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
      </ConfigSection>
      <ConfigSection
        title="输出变量"
        :description="effectiveOutputAlias ? `下游引用 · ${effectiveOutputAlias}` : '设置下游可引用的名称与字段'"
        :count="editableOutputs.length"
        collapsible
      >
        <div class="node-specific-panel contract-panel">
          <el-form-item v-if="!hasDedicatedOutputAlias" label="输出别名">
            <el-input
              v-model="data.outputAlias"
              placeholder="如 intent_result / business_result"
              @change="syncOutputAlias"
            />
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
      </ConfigSection>
      <ConfigSection title="高级" description="查看变量契约 JSON" collapsible>
        <pre class="contract-json">{{ contractJson }}</pre>
      </ConfigSection>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ConfigSection from './ConfigSection.vue'
import type { CanvasNodeData, InteractionCallNodeRequest, StudioPort, StudioVariableOption } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import type { KnowledgeBase } from '@/types/knowledge'
import type { BusinessMethodInfo } from '@/types/businessMethod'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { WorkflowGraphNodeTypeDescriptor } from '@/types/agent'
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
import VariableAssignConfigPanel from './VariableAssignConfigPanel.vue'
import TemplateConfigPanel from './TemplateConfigPanel.vue'
import ApprovalConfigPanel from './ApprovalConfigPanel.vue'
import LoopConfigPanel from './LoopConfigPanel.vue'
import KnowledgeWriteConfigPanel from './KnowledgeWriteConfigPanel.vue'
import DocumentExtractConfigPanel from './DocumentExtractConfigPanel.vue'
import McpConfigPanel from './McpConfigPanel.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
  nodeId?: string
  canvasNodes?: import('@/types/studio').CanvasNode[]
  modelOptions: ModelInstance[]
  modelOptionsLoading: boolean
  modelOptionsLoadError: boolean
  knowledgeOptions: KnowledgeBase[]
  toolOptions: BusinessMethodInfo[]
  toolOptionsLoading?: boolean
  toolOptionsLoadError?: boolean
  variableOptions: Array<string | StudioVariableOption>
  credentialOptions: WorkflowCredential[]
  projectId?: number | null
  projectCode?: string | null
  requestScopeKey?: string | null
  nodeTypeOptions?: WorkflowGraphNodeTypeDescriptor[]
}>()

defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
  createCallNode: [request: InteractionCallNodeRequest]
  retryMethods: []
  validationChange: [payload: { nodeId?: string; valid: boolean; message?: string }]
  reloadModelOptions: []
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
  variable: VariableAssignConfigPanel,
  template: TemplateConfigPanel,
  approval: ApprovalConfigPanel,
  loop: LoopConfigPanel,
  knowledgeWrite: KnowledgeWriteConfigPanel,
  documentExtract: DocumentExtractConfigPanel,
  mcp: McpConfigPanel,
  tool: ToolConfigPanel,
}

const panel = computed(() => registry[props.data.kind as keyof typeof registry])
const hasDedicatedOutputAlias = computed(() => ['userInput', 'interaction', 'pageAction', 'loop'].includes(props.data.kind))
const effectiveOutputAlias = computed(() => props.data.kind === 'loop'
  ? props.data.loopConfig?.outputAlias || props.data.outputAlias || ''
  : props.data.outputAlias || '')
const toolLikeOptions = computed(() => props.toolOptions)
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
  if (props.data.kind === 'tool') {
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
  outputAlias: effectiveOutputAlias.value,
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
  const stringMapping = Object.fromEntries(
    Object.entries(inputMapping.value).map(([key, value]) => [
      key,
      typeof value === 'string' ? value : JSON.stringify(value),
    ]),
  )
  props.data.inputMapping = stringMapping
  if (props.data.kind === 'tool') {
    props.data.toolConfig ||= { inputMapping: {} }
    props.data.toolConfig.inputMapping = { ...inputMapping.value }
  }
  if (props.data.kind === 'mcp') {
    props.data.mcpConfig ||= { toolName: '', inputMapping: {} }
    props.data.mcpConfig.inputMapping = stringMapping
  }
}

function addOutputPort() {
  const alias = effectiveOutputAlias.value || 'output'
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

<style lang="scss" src="./studio-panels.scss"></style>
