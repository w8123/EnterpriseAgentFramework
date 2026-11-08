<template>
  <div class="node-specific-panel">
    <ConfigSection title="调用的工作流" description="只能选同一项目里已发布的工作流，并固定一个版本">
      <el-form-item label="工作流">
        <el-select
          :model-value="config.workflowId || undefined"
          filterable
          clearable
          :loading="loadingWorkflows"
          placeholder="选择要调用的工作流"
          style="width: 100%"
          @update:model-value="selectWorkflow"
        >
          <el-option v-for="item in workflowOptions" :key="item.id" :label="item.name || item.keySlug" :value="item.id" />
        </el-select>
        <div v-if="loadError" class="hint is-error">{{ loadError }}</div>
      </el-form-item>
      <el-form-item label="版本">
        <el-select
          :model-value="config.versionId || undefined"
          :disabled="!config.workflowId"
          :loading="loadingVersions"
          placeholder="选择发布版本"
          style="width: 100%"
          @update:model-value="selectVersion"
        >
          <el-option
            v-for="item in versionOptions"
            :key="item.id"
            :label="`${item.version}${item.status === 'ACTIVE' ? '（当前生效）' : ''}`"
            :value="item.id"
          />
        </el-select>
        <div v-if="config.workflowId && !loadingVersions && !versionOptions.length" class="hint">
          这个工作流还没有发布版本，先去发布它。
        </div>
      </el-form-item>
      <el-form-item label="传入参数">
        <el-input
          v-model="inputsText"
          type="textarea"
          :rows="4"
          placeholder="每行一个：子流程参数名 = 变量路径或模板&#10;customerName = params.name&#10;message = lastOutput"
          @change="commitInputs"
        />
        <div class="hint">
          子流程里用 params.参数名 读取。它的回复在 nodeOutput.节点ID.answer，结构化结果在 .output。
          子流程不能暂停等人确认，也不能再调用子流程。
        </div>
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { CanvasNodeData, SubWorkflowNodeConfig } from '@/types/studio'
import type { WorkflowVersion, WorkflowWorkingCopy } from '@/types/workflow'
import { listWorkflowVersions, listWorkflows } from '@/api/workflow'
import { formatMap, parseMap } from './panelUtils'

const props = defineProps<{
  data: CanvasNodeData
  projectId?: number | null
  projectCode?: string | null
}>()

const route = useRoute()
const currentWorkflowId = computed(() => String(route?.params?.workflowId || route?.params?.id || ''))

const config = computed<SubWorkflowNodeConfig>(() => props.data.subWorkflowConfig || { workflowId: '', inputs: {} })

function write(patch: Partial<SubWorkflowNodeConfig>) {
  props.data.subWorkflowConfig = { ...config.value, ...patch }
}

const workflows = ref<WorkflowWorkingCopy[]>([])
const versions = ref<WorkflowVersion[]>([])
const loadingWorkflows = ref(false)
const loadingVersions = ref(false)
const loadError = ref('')

const workflowOptions = computed(() => workflows.value.filter((item) => item.id !== currentWorkflowId.value))
const versionOptions = computed(() => versions.value.filter((item) => item.status === 'ACTIVE' || item.status === 'RETIRED'))

async function loadWorkflows() {
  loadingWorkflows.value = true
  loadError.value = ''
  try {
    const params = props.projectId ? { projectId: props.projectId } : props.projectCode ? { projectCode: props.projectCode } : {}
    const result = await listWorkflows(params)
    workflows.value = Array.isArray(result) ? result : []
  } catch {
    loadError.value = '工作流列表加载失败'
  } finally {
    loadingWorkflows.value = false
  }
}

async function loadVersions(workflowId: string) {
  versions.value = []
  if (!workflowId) return
  loadingVersions.value = true
  try {
    const result = await listWorkflowVersions(workflowId)
    versions.value = Array.isArray(result) ? result : []
  } catch {
    versions.value = []
  } finally {
    loadingVersions.value = false
  }
}

async function selectWorkflow(value?: string) {
  const workflowId = value || ''
  const name = workflows.value.find((item) => item.id === workflowId)?.name
  write({ workflowId, workflowName: name || undefined, versionId: undefined, versionLabel: undefined })
  await loadVersions(workflowId)
  const active = versionOptions.value.find((item) => item.status === 'ACTIVE')
  if (active) selectVersion(active.id)
}

function selectVersion(value?: number) {
  const version = versions.value.find((item) => item.id === value)
  write({ versionId: value || undefined, versionLabel: version?.version || undefined })
}

const inputsText = ref('')
watch(() => [props.data, config.value.inputs] as const, () => {
  inputsText.value = formatMap(config.value.inputs)
}, { immediate: true })

function commitInputs() {
  const next = parseMap(inputsText.value)
  if (JSON.stringify(next) !== JSON.stringify(config.value.inputs || {})) write({ inputs: next })
}

onMounted(() => {
  void loadWorkflows()
  if (config.value.workflowId) void loadVersions(config.value.workflowId)
})
</script>
