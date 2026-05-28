<template>
  <div class="node-specific-panel">
    <el-divider>{{ data.kind === 'skill' ? '能力调用' : '工具调用' }}</el-divider>
    <el-form-item :label="data.kind === 'skill' ? '引用能力' : '引用工具'">
      <el-select v-model="config.ref" filterable placeholder="选择引用" style="width: 100%" @change="handleRefChange">
        <el-option v-for="item in options" :key="item.name" :label="assetLabel(item)" :value="item.name" />
      </el-select>
    </el-form-item>
    <el-form-item label="凭据引用">
      <CredentialSelect
        v-model="config.credentialRef"
        :credentials="credentialOptions"
        :project-id="projectId"
        :project-code="projectCode"
        @created="$emit('credentialCreated', $event)"
      />
    </el-form-item>
    <el-form-item v-if="isRequestTool" label="最大请求时间">
      <el-input-number
        v-model="maxRequestSeconds"
        :min="1"
        :max="1800"
        :step="10"
        controls-position="right"
      />
      <span class="timeout-unit">秒</span>
    </el-form-item>
    <el-form-item label="参数映射">
      <el-input :model-value="formatMap(config.inputMapping)" type="textarea" :rows="6" placeholder="customerId = params.customerId" @update:model-value="config.inputMapping = parseMap($event)" />
    </el-form-item>
    <el-form-item label="映射备注">
      <el-input v-model="config.mappingNote" type="textarea" :rows="2" />
    </el-form-item>
    <div v-if="paramSourceHints.length" class="param-hints">
      <div class="field-table-head">
        <strong>参数来源提示</strong>
      </div>
      <div v-for="hint in paramSourceHints" :key="`${hint.targetPath}-${hint.sourceApi}-${hint.sourcePath}`" class="param-hint-row">
        <div>
          <strong>{{ hint.targetPath }}</strong>
          <span>{{ hint.sourceApi }}.{{ hint.sourcePath }}</span>
        </div>
        <el-tag v-if="hint.confidence !== null" size="small">{{ Math.round((hint.confidence || 0) * 100) }}%</el-tag>
        <el-button size="small" text type="primary" @click="applyHint(hint)">应用</el-button>
      </div>
    </div>
    <div v-if="selectedTool?.parameters?.length" class="tool-params">
      <div class="field-table-head">
        <strong>工具参数</strong>
        <el-button size="small" text type="primary" @click="fillMissingMappings">补齐映射</el-button>
      </div>
      <div v-for="param in selectedTool.parameters" :key="param.name" class="tool-param-row">
        <strong>{{ param.name }}</strong>
        <span>{{ param.type }}</span>
        <em>{{ param.required ? '必填' : '可选' }}</em>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, ToolNodeConfig } from '@/types/studio'
import type { ToolInfo } from '@/types/tool'
import type { CompositionInfo } from '@/types/composition'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import { formatMap, parseMap } from './panelUtils'
import CredentialSelect from './CredentialSelect.vue'

const props = defineProps<{
  data: CanvasNodeData
  options: (ToolInfo | CompositionInfo)[]
  credentialOptions: WorkflowCredential[]
  paramSourceHints: ApiGraphParamSourceHint[]
  projectId?: number | null
  projectCode?: string | null
}>()
defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
}>()

const config = computed<ToolNodeConfig>(() => {
  props.data.toolConfig ||= {
    ref: '',
    qualifiedName: null,
    projectCode: null,
    visibility: null,
    credentialRef: '',
    maxRequestTimeMs: 180000,
    inputMapping: {},
  }
  return props.data.toolConfig
})
const selectedTool = computed(() => props.options.find((item) => item.name === config.value.ref) as ToolInfo | undefined)
const isRequestTool = computed(() => {
  if (props.data.kind !== 'tool') return false
  const tool = selectedTool.value
  return !tool || tool.source !== 'code' || !!tool.httpMethod || !!tool.endpointPath
})
const maxRequestSeconds = computed({
  get: () => Math.round((config.value.maxRequestTimeMs || 180000) / 1000),
  set: (value: number) => {
    config.value.maxRequestTimeMs = Math.max(1000, Math.min(1800000, Math.round(value || 180) * 1000))
  },
})

function handleRefChange() {
  const selected = selectedTool.value
  config.value.qualifiedName = selected?.qualifiedName || null
  config.value.projectCode = selected?.projectCode || null
  config.value.visibility = selected?.visibility || null
  config.value.maxRequestTimeMs ||= 180000
  props.data.description = selected?.description || props.data.description || ''
}

function fillMissingMappings() {
  const mapping = { ...(config.value.inputMapping || {}) }
  for (const param of selectedTool.value?.parameters || []) {
    if (!mapping[param.name]) mapping[param.name] = param.name
  }
  config.value.inputMapping = mapping
}

function applyHint(hint: ApiGraphParamSourceHint) {
  const mapping = { ...(config.value.inputMapping || {}) }
  mapping[hint.targetPath] = `${hint.sourceApi}.${hint.sourcePath}`
  config.value.inputMapping = mapping
  if (!config.value.mappingNote) {
    config.value.mappingNote = '参数来源由接口图谱关系生成。'
  }
}

function assetLabel(item: ToolInfo | CompositionInfo) {
  const project = item.projectCode ? ` / ${item.projectCode}` : ''
  const visibility = item.visibility ? ` / ${item.visibility}` : ''
  return `${item.name}${project}${visibility}`
}
</script>

<style scoped>
.timeout-unit {
  margin-left: 8px;
  color: var(--text-secondary);
}
</style>
