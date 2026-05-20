<template>
  <div class="agent-workbench page-container">
    <header class="workbench-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" text @click="router.push('/agent')">返回</el-button>
        <div>
          <div class="eyebrow">智能体配置</div>
          <h2>{{ isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}` }}</h2>
        </div>
        <el-tag size="small" effect="plain">{{ currentModeLabel }}</el-tag>
        <el-tag size="small" :type="form.enabled ? 'success' : 'info'" effect="plain">
          {{ form.enabled ? '启用' : '停用' }}
        </el-tag>
      </div>
      <div class="header-actions">
        <el-button v-if="isWorkflowRuntime && !isNew" :icon="Share" @click="openStudio">打开画布</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </header>

    <section class="runtime-strip">
      <button
        v-for="runtime in visibleRuntimes"
        :key="runtime.runtimeType"
        class="runtime-card"
        :class="{ active: form.runtimeType === runtime.runtimeType, unavailable: !runtime.available }"
        :disabled="!runtime.available"
        @click="selectRuntime(runtime)"
      >
        <span class="runtime-icon">
          <el-icon><component :is="runtimeIcon(runtime)" /></el-icon>
        </span>
        <span class="runtime-main">
          <strong>{{ runtime.displayName || runtime.runtimeType }}</strong>
          <em>{{ runtimeModeLabel(runtime) }}</em>
        </span>
        <el-tag size="small" :type="runtime.available ? (form.runtimeType === runtime.runtimeType ? 'primary' : 'info') : 'info'" effect="plain">
          {{ runtime.available ? (form.runtimeType === runtime.runtimeType ? '当前' : runtime.primaryAction || '选择') : '不可用' }}
        </el-tag>
      </button>
    </section>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" v-loading="pageLoading">
      <main class="workbench-layout">
        <section class="main-column">
          <section class="panel">
            <div class="panel-head">
              <div>
                <h3>身份与路由</h3>
                <p>只保留智能体身份、项目归属和入口路由。</p>
              </div>
              <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
            </div>
            <div class="form-grid two">
              <el-form-item label="名称" prop="name">
                <el-input v-model="form.name" placeholder="如：合同审核助手" />
              </el-form-item>
              <el-form-item label="意图类型" prop="intentType">
                <el-select v-model="form.intentType" filterable allow-create placeholder="选择或输入意图">
                  <el-option v-for="item in INTENT_TYPES" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="可见性">
                <el-select v-model="form.visibility">
                  <el-option label="私有" value="PRIVATE" />
                  <el-option label="项目" value="PROJECT" />
                  <el-option label="共享" value="SHARED" />
                  <el-option label="公开" value="PUBLIC" />
                </el-select>
              </el-form-item>
              <el-form-item label="所属项目">
                <el-select
                  v-model="form.projectId"
                  clearable
                  filterable
                  placeholder="平台级 / 全局"
                  @change="handleProjectChange"
                >
                  <el-option
                    v-for="project in scanProjects"
                    :key="project.id"
                    :label="projectOptionLabel(project)"
                    :value="project.id"
                  />
                </el-select>
              </el-form-item>
            </div>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话描述智能体的业务边界" />
            </el-form-item>
          </section>

          <section class="panel">
            <div class="panel-head">
              <div>
                <h3>运行入口</h3>
                <p>{{ runtimeEntryCopy }}</p>
              </div>
              <el-segmented v-model="form.runtimePlacement" :options="runtimePlacementOptions" />
            </div>
            <div class="form-grid three">
              <el-form-item label="模型厂商">
                <el-select v-model="selectedLlmProvider" filterable placeholder="选择厂商" @change="handleLlmProviderChange">
                  <el-option v-for="provider in llmProviderOptions" :key="provider" :label="provider" :value="provider" />
                </el-select>
              </el-form-item>
              <el-form-item label="默认模型" prop="modelInstanceId">
                <el-select
                  v-model="form.modelInstanceId"
                  filterable
                  placeholder="选择模型实例"
                  :disabled="!selectedLlmProvider"
                  @change="syncSelectedLlmProvider"
                >
                  <el-option
                    v-for="item in filteredLlmModelInstances"
                    :key="item.id"
                    :label="`${item.name} (${item.modelName})`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item v-if="isAgentScopeRuntime" label="触发方式">
                <el-select v-model="form.triggerMode">
                  <el-option v-for="item in TRIGGER_MODES" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.runtimePlacement !== 'CENTRAL'" label="纳管实例" class="wide">
                <el-select v-model="selectedRuntimeInstanceId" clearable filterable placeholder="选择 Embedded Runtime 实例">
                  <el-option
                    v-for="instance in availableRuntimeInstances"
                    :key="instance.instanceId"
                    :label="runtimeInstanceLabel(instance)"
                    :value="instance.instanceId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item v-if="isAgentScopeRuntime" label="输出 Schema">
                <el-input v-model="form.outputSchemaType" placeholder="如 ReviewResult" />
              </el-form-item>
              <el-form-item v-if="isAgentScopeRuntime" label="副作用工具">
                <el-switch v-model="form.allowIrreversible" active-text="允许" inactive-text="禁止" />
              </el-form-item>
            </div>
          </section>

          <section v-if="form.runtimeType === 'AGENTSCOPE'" class="panel runtime-panel autonomous">
            <div class="panel-head">
              <div>
                <h3>AgentScope 对话能力</h3>
                <p>模型自主决策，可用资源以白名单注入。</p>
              </div>
              <el-tag effect="plain">AUTONOMOUS</el-tag>
            </div>
            <div class="form-grid three">
              <el-form-item label="最大步数">
                <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
              </el-form-item>
              <el-form-item label="Agent 类型">
                <el-radio-group v-model="form.type">
                  <el-radio-button value="single">单 Agent</el-radio-button>
                  <el-radio-button value="pipeline">Pipeline</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="多 Agent 模型">
                <el-switch v-model="form.useMultiAgentModel" />
              </el-form-item>
            </div>
            <el-form-item label="System Prompt">
              <el-input
                v-model="form.systemPrompt"
                type="textarea"
                :rows="8"
                placeholder="定义角色、边界、工具使用策略和回答风格"
              />
            </el-form-item>
            <div class="form-grid two">
              <el-form-item label="可用工具">
                <el-select v-model="form.tools" multiple filterable collapse-tags collapse-tags-tooltip placeholder="选择 Tool">
                  <el-option
                    v-for="tool in availableTools"
                    :key="tool.name"
                    :label="capabilityLabel(tool)"
                    :value="tool.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="可用能力">
                <el-select v-model="form.skills" multiple filterable collapse-tags collapse-tags-tooltip placeholder="选择 Capability">
                  <el-option
                    v-for="item in availableCapabilities"
                    :key="item.name"
                    :label="capabilityLabel(item)"
                    :value="item.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="默认知识库">
                <el-input v-model="form.knowledgeBaseGroupId" placeholder="可选：对话型检索默认资源" />
              </el-form-item>
              <el-form-item label="Prompt 模板">
                <el-input v-model="form.promptTemplateId" placeholder="可选：覆盖默认 System Prompt" />
              </el-form-item>
            </div>
            <el-form-item v-if="form.type === 'pipeline'" label="子 Agent ID">
              <div class="tag-editor">
                <el-tag
                  v-for="(id, index) in form.pipelineAgentIds"
                  :key="`${id}-${index}`"
                  closable
                  @close="form.pipelineAgentIds.splice(index, 1)"
                >
                  {{ id }}
                </el-tag>
                <el-input
                  v-model="newPipelineId"
                  size="small"
                  placeholder="Agent ID"
                  @keyup.enter="addPipelineId"
                  @blur="addPipelineId"
                />
              </div>
            </el-form-item>
          </section>

          <section v-else-if="form.runtimeType !== 'LANGGRAPH4J'" class="panel runtime-panel future">
            <div class="panel-head">
              <div>
                <h3>{{ selectedRuntime?.displayName || form.runtimeType }} 配置</h3>
                <p>该运行时已在平台契约中占位，专属配置面板后续接入。</p>
              </div>
              <el-tag type="info" effect="plain">{{ currentModeLabel }}</el-tag>
            </div>
            <el-empty :image-size="72" description="专属工作台尚未接入" />
          </section>
        </section>

        <aside class="side-column">
          <section class="summary-panel">
            <h3>当前运行时</h3>
            <p class="summary-copy">{{ runtimeSummaryCopy }}</p>
            <dl>
              <div>
                <dt>形态</dt>
                <dd>{{ currentModeLabel }}</dd>
              </div>
              <div>
                <dt>资源配置</dt>
                <dd>{{ resourcePolicyLabel }}</dd>
              </div>
              <div>
                <dt>编排方式</dt>
                <dd>{{ isWorkflowRuntime ? '流程画布' : '表单配置' }}</dd>
              </div>
            </dl>
            <el-button v-if="isWorkflowRuntime && !isNew" class="summary-action" type="primary" plain :icon="Share" @click="openStudio">
              进入流程画布
            </el-button>
          </section>
        </aside>
      </main>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft, Cpu, Connection, DataLine, EditPen, Share } from '@element-plus/icons-vue'
import { INTENT_TYPES, TRIGGER_MODES } from '@/types/agent'
import type { AgentForm, AgentGraphSpec, AgentMode, AgentRuntimeCapability, AgentRuntimeType } from '@/types/agent'
import { createAgent, getAgent, getAgentRuntimes, updateAgent, validateAgentRuntime } from '@/api/agent'
import { listRegistryProjectInstances } from '@/api/registry'
import { getTools } from '@/api/tool'
import { listCapabilities } from '@/api/capability'
import { getScanProjects } from '@/api/scanProject'
import { getModelInstances } from '@/api/model'
import type { ToolInfo } from '@/types/tool'
import type { CapabilityInfo } from '@/types/capability'
import type { ScanProject } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import type { ModelInstance } from '@/types/model'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const selectedLlmProvider = ref('')
const runtimeOptions = ref<AgentRuntimeCapability[]>([])
const scanProjects = ref<ScanProject[]>([])
const runtimeInstances = ref<ProjectInstance[]>([])
const llmModelInstances = ref<ModelInstance[]>([])
const toolOptions = ref<ToolInfo[]>([])
const capabilityOptions = ref<CapabilityInfo[]>([])
const newPipelineId = ref('')

const form = reactive<AgentForm>({
  keySlug: '',
  name: '',
  description: '',
  agentMode: 'AUTONOMOUS',
  projectId: null,
  projectCode: null,
  visibility: 'PRIVATE',
  intentType: 'GENERAL_CHAT',
  systemPrompt: '',
  tools: [],
  toolRefs: [],
  skills: [],
  skillRefs: [],
  modelInstanceId: '',
  runtimeType: 'AGENTSCOPE',
  runtimePlacement: 'CENTRAL',
  runtimeConfig: {},
  defaultResourceConfig: {},
  graphSpec: null,
  maxSteps: 5,
  enabled: true,
  type: 'single',
  pipelineAgentIds: [],
  knowledgeBaseGroupId: '',
  promptTemplateId: '',
  outputSchemaType: '',
  triggerMode: 'all',
  useMultiAgentModel: false,
  extra: {},
  allowIrreversible: false,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
  intentType: [{ required: true, message: '请选择或输入意图类型', trigger: 'change' }],
  modelInstanceId: [{ required: true, message: '请选择默认模型', trigger: 'change' }],
}

const runtimePlacementOptions = [
  { label: '中台运行', value: 'CENTRAL' },
  { label: '本地运行', value: 'EMBEDDED' },
  { label: '混合运行', value: 'HYBRID' },
]

const visibleRuntimes = computed(() => {
  if (runtimeOptions.value.length) return runtimeOptions.value
  return [{
    runtimeType: 'AGENTSCOPE',
    displayName: 'AgentScope',
    agentMode: 'AUTONOMOUS',
    configurationSurface: 'FORM',
    primaryAction: '配置对话能力',
    resourcePolicy: 'AGENT_DEFAULTS',
    available: true,
    supportsStreaming: true,
    supportsTools: true,
    supportsHandoff: false,
    supportsGraph: false,
    supportsHumanInterrupt: true,
    supportsArtifacts: false,
    supportsCodeWorkspace: false,
    supportsCloudExecution: false,
  } satisfies AgentRuntimeCapability]
})

const selectedRuntime = computed(() =>
  visibleRuntimes.value.find((item) => item.runtimeType === form.runtimeType),
)
const isWorkflowRuntime = computed(() => modeForRuntime(form.runtimeType) === 'WORKFLOW')
const isAgentScopeRuntime = computed(() => form.runtimeType === 'AGENTSCOPE')
const currentModeLabel = computed(() => modeLabel(form.agentMode || modeForRuntime(form.runtimeType)))
const runtimeEntryCopy = computed(() =>
  isWorkflowRuntime.value
    ? '流程型智能体以 GraphSpec 为执行契约，默认模型只作为节点兜底。'
    : '对话型智能体以 Prompt、模型和可用资源白名单为核心。',
)
const resourcePolicyLabel = computed(() =>
  selectedRuntime.value?.resourcePolicy === 'NODE_LEVEL'
    ? '节点级资源'
    : 'Agent 默认资源',
)
const runtimeSummaryCopy = computed(() =>
  isWorkflowRuntime.value
    ? '模型、知识库、工具等资源在流程节点内配置；这里仅维护入口和默认兜底。'
    : '对话型智能体在这里配置 Prompt、默认模型和可调用资源。',
)

const llmProviderOptions = computed(() =>
  Array.from(new Set(llmModelInstances.value.map((item) => item.provider).filter(Boolean))).sort(),
)
const filteredLlmModelInstances = computed(() =>
  llmModelInstances.value.filter((item) => item.provider === selectedLlmProvider.value),
)
const availableRuntimeInstances = computed(() =>
  runtimeInstances.value.filter((item) => item.status === 'ONLINE'),
)
const availableTools = computed(() =>
  toolOptions.value.filter((tool) => tool.enabled && tool.agentVisible),
)
const availableCapabilities = computed(() =>
  capabilityOptions.value.filter((item) => item.enabled && item.agentVisible && !item.draft),
)
const selectedRuntimeInstanceId = computed({
  get: () => embeddedRuntimeConfig().instanceId || '',
  set: (instanceId: string) => {
    const instance = availableRuntimeInstances.value.find((item) => item.instanceId === instanceId)
    setEmbeddedRuntimeTarget(instance)
  },
})

function modeForRuntime(runtimeType?: AgentRuntimeType): AgentMode {
  if (runtimeType === 'LANGGRAPH4J') return 'WORKFLOW'
  if (runtimeType === 'CURSOR_CODE_AGENT') return 'CODE'
  if (runtimeType === 'OPENAI_AGENTS') return 'EXTERNAL'
  return 'AUTONOMOUS'
}

function modeLabel(mode?: AgentMode) {
  if (mode === 'WORKFLOW') return '流程工作流'
  if (mode === 'CODE') return '代码工程'
  if (mode === 'EXTERNAL') return '外部托管'
  return '自主对话'
}

function runtimeModeLabel(runtime: AgentRuntimeCapability) {
  return modeLabel(runtime.agentMode || modeForRuntime(runtime.runtimeType))
}

function runtimeIcon(runtime: AgentRuntimeCapability) {
  const mode = runtime.agentMode || modeForRuntime(runtime.runtimeType)
  if (mode === 'WORKFLOW') return Connection
  if (mode === 'CODE') return EditPen
  if (mode === 'EXTERNAL') return DataLine
  return Cpu
}

function selectRuntime(runtime: AgentRuntimeCapability) {
  if (!runtime.available) return
  form.runtimeType = runtime.runtimeType
  form.agentMode = runtime.agentMode || modeForRuntime(runtime.runtimeType)
  if (form.runtimeType === 'LANGGRAPH4J') {
    form.type = 'single'
    form.tools = []
    form.toolRefs = []
    form.skills = []
    form.skillRefs = []
    form.pipelineAgentIds = []
    form.useMultiAgentModel = false
    ensureWorkflowGraphSpec()
  } else if (form.runtimeType === 'AGENTSCOPE') {
    form.graphSpec = null
  }
}

function projectOptionLabel(project?: ScanProject | null) {
  if (!project) return ''
  const code = project.projectCode ? ` / ${project.projectCode}` : ''
  const env = project.environment ? ` · ${project.environment}` : ''
  return `${project.name}${code}${env}`
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

function capabilityLabel(item: ToolInfo | CapabilityInfo) {
  const project = item.projectCode ? ` · ${item.projectCode}` : ''
  const visibility = item.visibility ? ` · ${item.visibility}` : ''
  return `${item.name}${project}${visibility}`
}

function runtimeInstanceLabel(instance: ProjectInstance) {
  const status = instance.status === 'DISABLED' ? '已禁用' : instance.status
  const host = instance.host || instance.baseUrl || instance.instanceId
  return `${host} · ${status} · SDK ${instance.sdkVersion || '-'}`
}

function embeddedRuntimeConfig() {
  return (form.runtimeConfig?.embeddedRuntime || {}) as { projectCode?: string; instanceId?: string }
}

function setEmbeddedRuntimeTarget(instance?: ProjectInstance) {
  if (!instance) {
    const { embeddedRuntime: _embeddedRuntime, ...rest } = form.runtimeConfig || {}
    form.runtimeConfig = rest
    return
  }
  form.runtimeConfig = {
    ...(form.runtimeConfig || {}),
    embeddedRuntime: {
      projectCode: instance.projectCode || form.projectCode,
      instanceId: instance.instanceId,
    },
  }
}

function addPipelineId() {
  const id = newPipelineId.value.trim()
  if (id && !form.pipelineAgentIds.includes(id)) {
    form.pipelineAgentIds.push(id)
  }
  newPipelineId.value = ''
}

function handleLlmProviderChange() {
  form.modelInstanceId = ''
}

function syncSelectedLlmProvider() {
  const selected = llmModelInstances.value.find((item) => item.id === form.modelInstanceId)
  selectedLlmProvider.value = selected?.provider || selectedLlmProvider.value
  if (form.runtimeType === 'LANGGRAPH4J') {
    ensureWorkflowGraphSpec()
  }
}

async function handleProjectChange(projectId: number | null | undefined) {
  form.projectCode = projectCodeById(projectId)
  form.tools = []
  form.skills = []
  await Promise.all([loadToolOptions(), loadCapabilityOptions(), loadRuntimeInstances()])
}

function defaultWorkflowGraphSpec(): AgentGraphSpec {
  return {
    code: form.keySlug || form.name || 'agent_graph',
    name: form.name || 'Agent Graph',
    mode: 'WORKFLOW',
    runtimeHint: 'LANGGRAPH4J',
    nodes: [
      {
        id: 'llm',
        type: 'LLM',
        name: 'LLM',
        config: {
          modelInstanceId: form.modelInstanceId,
          systemPrompt: form.systemPrompt,
          userPrompt: '{{ input }}',
          outputFormat: 'text',
        },
      },
    ],
    edges: [
      { from: 'START', to: 'llm', condition: 'always' },
      { from: 'llm', to: 'END', condition: 'always' },
    ],
    entry: 'llm',
    finish: ['llm'],
  }
}

function ensureWorkflowGraphSpec() {
  if (!form.graphSpec?.nodes?.length) {
    form.graphSpec = defaultWorkflowGraphSpec()
    return form.graphSpec
  }
  form.graphSpec = {
    ...form.graphSpec,
    code: form.graphSpec.code || form.keySlug || form.name || 'agent_graph',
    name: form.graphSpec.name || form.name || 'Agent Graph',
    mode: 'WORKFLOW',
    runtimeHint: 'LANGGRAPH4J',
    entry: form.graphSpec.entry || form.graphSpec.nodes[0]?.id,
    finish: form.graphSpec.finish?.length ? form.graphSpec.finish : [form.graphSpec.nodes[0]?.id].filter(Boolean) as string[],
  }
  return form.graphSpec
}

function syncForSave() {
  form.agentMode = modeForRuntime(form.runtimeType)
  const selectedInstance = availableRuntimeInstances.value.find((item) => item.instanceId === selectedRuntimeInstanceId.value)
  if (form.runtimePlacement !== 'CENTRAL' && selectedInstance) {
    setEmbeddedRuntimeTarget(selectedInstance)
  }
  form.defaultResourceConfig = {
    modelInstanceId: form.modelInstanceId,
    systemPrompt: form.systemPrompt,
    knowledgeBaseGroupId: form.knowledgeBaseGroupId,
    promptTemplateId: form.promptTemplateId,
    outputSchemaType: form.outputSchemaType,
  }
  if (form.runtimeType === 'AGENTSCOPE') {
    form.graphSpec = null
    form.runtimeConfig = {
      ...(form.runtimeConfig || {}),
      agentScope: {
        maxSteps: form.maxSteps,
        type: form.type,
        useMultiAgentModel: form.useMultiAgentModel,
        tools: form.tools,
        toolRefs: form.toolRefs || [],
        skills: form.skills,
        skillRefs: form.skillRefs || [],
        pipelineAgentIds: form.pipelineAgentIds,
      },
    }
    return
  }
  if (form.runtimeType === 'LANGGRAPH4J') {
    ensureWorkflowGraphSpec()
    form.tools = []
    form.toolRefs = []
    form.skills = []
    form.skillRefs = []
    form.type = 'single'
    form.pipelineAgentIds = []
    form.useMultiAgentModel = false
  }
}

async function validateBeforeSave() {
  syncForSave()
  if (
    form.runtimePlacement !== 'CENTRAL'
    && !availableRuntimeInstances.value.some((item) => item.instanceId === selectedRuntimeInstanceId.value)
  ) {
    ElMessage.error('请选择 Embedded Runtime 纳管实例')
    return false
  }
  try {
    const { data } = await validateAgentRuntime(form)
    if (!data.valid) {
      ElMessage.error(data.message || '运行时校验失败')
      return false
    }
    return true
  } catch (error) {
    const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
    ElMessage.error(response?.data?.message || response?.data?.error || '运行时校验请求失败')
    return false
  }
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const runtimeValid = await validateBeforeSave()
    if (!runtimeValid) return
    if (isNew) {
      const { data } = await createAgent(form)
      ElMessage.success('创建成功')
      if (data.runtimeType === 'LANGGRAPH4J') {
        router.push(`/agent/${data.id}/studio`)
      } else {
        router.push('/agent')
      }
      return
    }
    await updateAgent(agentId, form)
    ElMessage.success('保存成功')
    router.push('/agent')
  } catch (error) {
    const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
    ElMessage.error(response?.data?.message || response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

function openStudio() {
  if (isNew) return
  router.push(`/agent/${agentId}/studio`)
}

async function loadAgent() {
  if (isNew) return
  pageLoading.value = true
  try {
    const { data } = await getAgent(agentId)
    Object.assign(form, {
      keySlug: data.keySlug ?? '',
      name: data.name,
      description: data.description || '',
      agentMode: data.agentMode || modeForRuntime(data.runtimeType),
      projectId: data.projectId ?? null,
      projectCode: data.projectCode ?? projectCodeById(data.projectId) ?? null,
      visibility: data.visibility || 'PRIVATE',
      intentType: data.intentType || 'GENERAL_CHAT',
      systemPrompt: data.systemPrompt || '',
      tools: data.tools || [],
      toolRefs: data.toolRefs || [],
      skills: data.skills || [],
      skillRefs: data.skillRefs || [],
      modelInstanceId: data.modelInstanceId || '',
      runtimeType: data.runtimeType || 'AGENTSCOPE',
      runtimePlacement: data.runtimePlacement || 'CENTRAL',
      runtimeConfig: data.runtimeConfig || {},
      defaultResourceConfig: data.defaultResourceConfig || {},
      graphSpec: data.graphSpec || null,
      maxSteps: data.maxSteps || 5,
      enabled: data.enabled ?? true,
      type: data.type || 'single',
      pipelineAgentIds: data.pipelineAgentIds || [],
      knowledgeBaseGroupId: data.knowledgeBaseGroupId || '',
      promptTemplateId: data.promptTemplateId || '',
      outputSchemaType: data.outputSchemaType || '',
      triggerMode: data.triggerMode || 'all',
      useMultiAgentModel: data.useMultiAgentModel ?? false,
      extra: data.extra || {},
      canvasJson: data.canvasJson || '',
      allowIrreversible: data.allowIrreversible ?? false,
    })
    if (form.runtimeType === 'LANGGRAPH4J') {
      ensureWorkflowGraphSpec()
    }
    syncSelectedLlmProvider()
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
  }
}

async function loadRuntimeOptions() {
  try {
    const { data } = await getAgentRuntimes()
    runtimeOptions.value = Array.isArray(data) ? data : []
    const available = runtimeOptions.value.find((runtime) => runtime.runtimeType === form.runtimeType && runtime.available)
      || runtimeOptions.value.find((runtime) => runtime.available)
    if (available && !runtimeOptions.value.some((runtime) => runtime.runtimeType === form.runtimeType)) {
      selectRuntime(available)
    }
  } catch {
    runtimeOptions.value = []
  }
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
    if (isNew) {
      const queryProjectId = Number(route.query.projectId)
      form.projectId = Number.isFinite(queryProjectId) && queryProjectId > 0
        ? queryProjectId
        : projectStore.currentProjectId ?? null
      form.projectCode = projectCodeById(form.projectId)
    }
  } catch {
    scanProjects.value = []
  }
}

async function loadRuntimeInstances() {
  if (!form.projectCode) {
    runtimeInstances.value = []
    return
  }
  try {
    const { data } = await listRegistryProjectInstances(form.projectCode)
    runtimeInstances.value = Array.isArray(data) ? data : []
  } catch {
    runtimeInstances.value = []
  }
}

async function loadModelInstances() {
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    const list = data?.data ?? (Array.isArray(data) ? data : [])
    llmModelInstances.value = list.filter((item: ModelInstance) => item.status === 'ACTIVE')
    if (form.modelInstanceId) {
      syncSelectedLlmProvider()
    }
  } catch {
    llmModelInstances.value = []
  }
}

async function loadToolOptions() {
  try {
    const { data } = await getTools({
      current: 1,
      size: 2000,
      ...(form.projectId != null ? { projectId: form.projectId } : {}),
    })
    toolOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    toolOptions.value = []
  }
}

async function loadCapabilityOptions() {
  try {
    const { data } = await listCapabilities({
      current: 1,
      size: 2000,
      ...(form.projectId != null ? { projectId: form.projectId } : {}),
    })
    capabilityOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    capabilityOptions.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadRuntimeOptions(), loadScanProjects(), loadModelInstances()])
  await loadAgent()
  await Promise.all([loadToolOptions(), loadCapabilityOptions(), loadRuntimeInstances()])
})
</script>

<style scoped lang="scss">
.agent-workbench {
  min-height: calc(100vh - 64px);
  padding: 20px 24px 28px;
  background: #f6f8fc;
}

.workbench-header,
.runtime-strip,
.panel,
.summary-panel {
  border: 1px solid #e5eaf4;
  background: #fff;
  box-shadow: 0 8px 22px rgba(31, 45, 61, 0.04);
}

.workbench-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 82px;
  padding: 18px 22px;
  border-radius: 8px;
}

.header-left,
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.eyebrow {
  color: #7d8ca5;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h2,
h3 {
  margin: 0;
  color: #172033;
}

h2 {
  font-size: 22px;
  line-height: 30px;
}

h3 {
  font-size: 16px;
  line-height: 24px;
}

p {
  margin: 4px 0 0;
  color: #68758c;
  font-size: 13px;
  line-height: 20px;
}

.runtime-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-top: 14px;
  padding: 12px;
  border-radius: 8px;
}

.runtime-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 68px;
  padding: 12px;
  border: 1px solid #e4e9f3;
  border-radius: 6px;
  background: #fbfcff;
  color: #1f2d44;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, box-shadow 0.2s;
}

.runtime-card.active {
  border-color: #6658f6;
  background: #f4f3ff;
  box-shadow: inset 0 0 0 1px rgba(102, 88, 246, 0.18);
}

.runtime-card.unavailable {
  cursor: not-allowed;
  opacity: 0.55;
}

.runtime-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: #eef1ff;
  color: #6658f6;
  flex: 0 0 auto;
}

.runtime-main {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.runtime-main strong,
.runtime-main em {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-main strong {
  font-size: 14px;
}

.runtime-main em {
  color: #7b879b;
  font-size: 12px;
  font-style: normal;
}

.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 272px;
  gap: 14px;
  margin-top: 14px;
}

.main-column,
.side-column {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.panel,
.summary-panel {
  border-radius: 10px;
}

.panel {
  padding: 18px 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.form-grid {
  display: grid;
  gap: 14px 22px;
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.wide {
  grid-column: span 2;
}

:deep(.el-select),
:deep(.el-input),
:deep(.el-textarea),
:deep(.el-input-number) {
  width: 100%;
}

.runtime-panel {
  border-left: 3px solid #6658f6;
}

.tag-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

.tag-editor .el-input {
  width: 220px;
}

.summary-panel {
  padding: 16px;
}

.summary-panel h3 {
  margin-bottom: 6px;
}

.summary-copy {
  margin-bottom: 12px;
}

.summary-panel dl {
  margin: 0;
  padding: 0;
}

.summary-panel dl div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 0;
  border-top: 1px solid #edf1f7;
}

.summary-panel dl div:first-child {
  border-top: 0;
}

.summary-panel dt {
  color: #7a879b;
}

.summary-panel dd {
  margin: 0;
  color: #27364f;
  font-weight: 700;
  text-align: right;
}

.summary-action {
  width: 100%;
  margin-top: 12px;
}

@media (max-width: 1280px) {
  .workbench-layout {
    grid-template-columns: 1fr;
  }

  .side-column {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .agent-workbench {
    padding: 16px;
  }

  .workbench-header,
  .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .form-grid.two,
  .form-grid.three,
  .side-column {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }
}
</style>
