<template>
  <WorkbenchPage class="ai-tool-access" layout="list">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="agent"
      title="AI 工具接入"
      :description="workspaceDescription"
    />

    <LiquidGlassTabs v-model="tab" source="wallpaper">
      <el-tab-pane label="接入与体验" name="setup">
        <template #label><el-icon aria-hidden="true"><Connection /></el-icon>接入与体验</template>
        <AiAccessSetup
          v-model:tool-kind="toolKind"
          v-model:client-id="clientId"
          :tools="PLATFORM_AI_TOOL_OPTIONS"
          scope-title="凭据设置"
          :scope-summary="`有效期 ${form.ttlDays} 天`"
          scope-hint="按你当前的账号权限授权，权限减少时自动收紧。"
          :prompt="installPrompt"
          :creating="creating"
          :can-create="!!form.label.trim()"
          disabled-reason="请在「凭据设置」中填写凭据名称。"
          :error="createError"
          :try-phrases="tryPhrases"
          @create="createConnection"
          @done="created = null"
        >
          <template #scope>
            <div class="scope-form">
              <label>
                <span>凭据名称</span>
                <LiquidGlassInput v-model="form.label" class="credential-name-input" source="backdrop" maxlength="128" :placeholder="`例如：我的 ${selectedClient.label}`" aria-label="凭据名称" :aria-invalid="!form.label.trim()" :disabled="creating" />
              </label>
              <label>
                <span>有效期</span>
                <LiquidGlassSelect v-model="form.ttlDays" aria-label="凭据有效期" dropdown-title="凭据有效期" :disabled="creating">
                  <el-option label="1 天" :value="1" />
                  <el-option label="7 天" :value="7" />
                  <el-option label="30 天" :value="30" />
                </LiquidGlassSelect>
              </label>
            </div>
          </template>
          <template #footer>
            <AiAccessStatusBar :items="statusItems" class="ai-tool-access__setup-status" />
          </template>
        </AiAccessSetup>
      </el-tab-pane>

      <el-tab-pane label="提示词库" name="prompts">
        <template #label><el-icon aria-hidden="true"><Document /></el-icon>提示词库</template>
        <LiquidGlassCard source="wallpaper">
          <AiPromptLibrary connection="platform" />
        </LiquidGlassCard>
      </el-tab-pane>

      <el-tab-pane label="我能办的事" name="capabilities">
        <template #label><el-icon aria-hidden="true"><Grid /></el-icon>我能办的事</template>
        <LiquidGlassCard source="wallpaper">
          <AiCapabilityList
            :key="ownerId ?? 'signed-out'"
            :groups="capabilityGroups"
            :loading="capabilitiesLoading"
            :error="capabilitiesError"
            empty-description="你的账号还没有可以交给 AI 的平台权限。"
          />
        </LiquidGlassCard>
      </el-tab-pane>

      <el-tab-pane label="访问凭据" name="credentials">
        <template #label><el-icon aria-hidden="true"><Key /></el-icon>访问凭据</template>
        <LiquidGlassCard source="wallpaper">
          <AiCredentialTable
            :rows="credentialRows"
            :loading="loading"
            :error="error"
            :busy-id="revokingId"
            @revoke="disconnect"
          />
        </LiquidGlassCard>
      </el-tab-pane>

      <el-tab-pane label="任务记录" name="records">
        <template #label><el-icon aria-hidden="true"><Clock /></el-icon>任务记录</template>
        <LiquidGlassCard source="wallpaper">
          <AiCallRecords :records="callRecords" :loading="callsLoading" :error="callsError" @refresh="loadCalls" />
        </LiquidGlassCard>
      </el-tab-pane>
    </LiquidGlassTabs>
    <AiAccessStatusBar v-if="tab !== 'setup'" :items="statusItems" />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { Clock, Connection, Document, Grid, Key } from '@element-plus/icons-vue'
import {
  createPlatformAiConnection,
  listPlatformAiCalls,
  listPlatformAiCapabilities,
  listPlatformAiConnections,
  revokePlatformAiConnection,
  type CreatedPlatformAiConnection,
  type PlatformAiCall,
  type PlatformAiCapability,
  type PlatformAiConnection,
} from '@/api/platformAiConnections'
import { listPlatformAiApprovals } from '@/api/platformAiApprovals'
import { platformSessionUser } from '@/auth/platformSession'
import { useSidebarWorkspace } from '@/composables/useSidebarWorkspace'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import {
  LiquidGlassCard, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import AiAccessStatusBar from '@/components/ai-access/AiAccessStatusBar.vue'
import AiAccessSetup from '@/components/ai-access/AiAccessSetup.vue'
import AiCapabilityList from '@/components/ai-access/AiCapabilityList.vue'
import AiCredentialTable from '@/components/ai-access/AiCredentialTable.vue'
import AiCallRecords from '@/components/ai-access/AiCallRecords.vue'
import AiPromptLibrary from '@/components/ai-access/AiPromptLibrary.vue'
import {
  buildInstallPrompt, isToday, PLATFORM_AI_TOOL_OPTIONS,
  type AiCallRecord, type AiCapabilityGroup, type AiClientId, type AiCredentialRow, type AiToolKind,
} from '@/components/ai-access/aiAccess'
import { platformAiSetup } from './platformAiSetup'

// 管理端的「AI 工具接入」：和员工门户同一套界面，授权范围是 ReachAI 平台本身。
const route = useRoute()
const { currentWorkspace } = useSidebarWorkspace()
const workspaceDescription = computed(() => ({
  developer: '用你习惯的 AI 工具，协助接入业务系统、改造项目和开发智能体。',
  manager: '用你习惯的 AI 工具，协助了解使用情况、运营智能体和处理管理工作。',
  admin: '用你习惯的 AI 工具，协助检查和管理 ReachAI 平台。',
  my: '用你习惯的 AI 工具，连接 ReachAI，让 AI 协助你完成工作。',
  all: '用你习惯的 AI 工具，连接 ReachAI，让 AI 协助你完成工作。',
})[currentWorkspace.value.key])
const tab = ref<'setup' | 'prompts' | 'capabilities' | 'credentials' | 'records'>(route?.query?.tab === 'prompts' ? 'prompts' : 'setup')
const connections = ref<PlatformAiConnection[]>([])
const loading = ref(false)
const error = ref('')
const createError = ref('')
const creating = ref(false)
const revokingId = ref<number | null>(null)
const created = ref<CreatedPlatformAiConnection | null>(null)
const toolKind = ref<AiToolKind>('CLAUDE_CODE')
const clientId = ref<AiClientId>('CLAUDE_CODE')
const selectedClient = computed(() => PLATFORM_AI_TOOL_OPTIONS.find(tool => tool.value === clientId.value)!)
const createdClientId = ref<AiClientId>('CLAUDE_CODE')
const form = reactive<{ label: string; ttlDays: number }>({ label: '', ttlDays: 7 })

watch(clientId, (_value, previous) => {
  const previousLabel = PLATFORM_AI_TOOL_OPTIONS.find(tool => tool.value === previous)?.label
  if (!form.label.trim() || form.label === `我的 ${previousLabel}`) form.label = `我的 ${selectedClient.value.label}`
  createError.value = ''
}, { immediate: true })
const capabilities = ref<PlatformAiCapability[]>([])
const capabilitiesLoading = ref(false)
const capabilitiesError = ref('')
const calls = ref<PlatformAiCall[]>([])
const callsLoading = ref(false)
const callsError = ref('')
const pendingApprovals = ref(0)
const ownerId = computed(() => platformSessionUser.value?.userId ?? null)
let requestGeneration = 0
let ownerGeneration = 0

/** Shown once: built from the one-time token, kept only in memory while this tab stays open. */
const installPrompt = computed(() => {
  const value = created.value
  if (!value) return null
  const client = PLATFORM_AI_TOOL_OPTIONS.find(tool => tool.value === createdClientId.value)!
  const setup = platformAiSetup(value.connection.toolKind, window.location.origin, value.connection.id, client.label)
  return buildInstallPrompt({
    toolKind: value.connection.toolKind,
    toolLabel: client.label,
    manual: client.manual,
    target: 'ReachAI 平台',
    configHint: setup.title,
    configContent: setup.content,
    secretVariable: setup.variable,
    secret: value.token,
  })
})

const toolDescriptions = computed(() => new Map(capabilities.value.map(item => [item.name, item.description])))

const capabilityGroups = computed<AiCapabilityGroup[]>(() => capabilities.value.length ? [{
  key: 'platform',
  title: 'ReachAI 平台',
  subtitle: '按当前账号的权限展示。新建凭据将按这些权限授权。',
  items: capabilities.value.map(item => ({
    name: item.name,
    description: item.description,
    mode: item.executionMode === 'READ_ONLY_TRIAL' ? 'trial'
      : item.approvalRequired ? 'approval' : item.readOnly ? 'read' : 'write',
  })),
}] : [])

const credentialRows = computed<AiCredentialRow[]>(() => connections.value.map(connection => ({
  id: connection.id,
  name: connection.label,
  toolKind: connection.toolKind,
  keyHint: `${connection.tokenHint}******`,
  scope: 'ReachAI 平台',
  status: connection.status,
  createdAt: connection.createdAt,
  expiresAt: connection.expiresAt,
  lastUsedAt: connection.lastUsedAt,
})))

const callRecords = computed<AiCallRecord[]>(() => calls.value.map(call => ({
  id: call.id,
  createdAt: call.createdAt,
  credential: call.connectionLabel || (call.connectionId ? `凭据 #${call.connectionId}` : '-'),
  action: (call.toolName && toolDescriptions.value.get(call.toolName)) || call.toolName || '-',
  success: call.success,
  latencyMs: call.latencyMs,
  error: call.errorCode,
})))

const statusItems = computed(() => [
  {
    label: '使用中的凭据',
    value: loading.value ? '加载中' : error.value ? '加载失败' : connections.value.filter(item => item.status === 'ACTIVE').length,
    tone: error.value ? 'danger' as const : undefined,
  },
  { label: '今日调用', value: `${calls.value.filter(call => isToday(call.createdAt)).length} 次` },
  { label: '待我审核', value: pendingApprovals.value, tone: pendingApprovals.value ? 'warn' as const : undefined },
])

const exampleOptions = {
  developer: [
    { tool: 'reachai_project_onboarding_start', phrase: '帮我接入 ReachAI' },
    { tool: 'reachai_doctor', phrase: '查看我的项目接入情况' },
    { tool: 'reachai_integration_guide', phrase: '协助我接入业务系统' },
    { tool: 'reachai_workflow_create_draft', phrase: '帮我创建一个 Workflow 草稿' },
  ],
  manager: [
    { tool: 'reachai_mcp_usage', phrase: '查看 AI 使用情况' },
    { tool: 'reachai_mcp_employee_write_approvals', phrase: '查看员工 AI 写操作审批' },
    { tool: 'reachai_agents_list', phrase: '查看智能体列表' },
  ],
  admin: [
    { tool: 'reachai_doctor', phrase: '帮我检查一个项目的接入配置' },
    { tool: 'reachai_models_list', phrase: '查看可用的模型资源' },
    { tool: 'reachai_projects_list', phrase: '查看我能访问的项目' },
  ],
  my: [
    { tool: 'reachai_projects_list', phrase: '查看我能访问的项目' },
    { tool: 'reachai_agents_list', phrase: '查看我能访问的智能体' },
    { tool: 'reachai_platform_approvals_list', phrase: '查看这个连接的审批记录' },
  ],
} as const
const tryPhrases = computed(() => {
  const names = new Set(capabilities.value.map(capability => capability.name))
  const key = currentWorkspace.value.key === 'all' ? 'my' : currentWorkspace.value.key
  return exampleOptions[key].filter(example => names.has(example.tool)).map(example => example.phrase)
})

watch(ownerId, (owner) => {
  requestGeneration += 1
  ownerGeneration += 1
  connections.value = []
  created.value = null
  creating.value = false
  loading.value = false
  revokingId.value = null
  error.value = ''
  createError.value = ''
  capabilities.value = []
  capabilitiesLoading.value = false
  capabilitiesError.value = ''
  calls.value = []
  pendingApprovals.value = 0
  if (owner !== null) {
    void loadConnections()
    void loadCapabilities()
    void loadCalls()
    void loadPendingApprovals()
  }
}, { immediate: true })

async function loadConnections() {
  const owner = ownerId.value
  if (owner === null) return
  const generation = ++requestGeneration
  loading.value = true
  error.value = ''
  try {
    const { data } = await listPlatformAiConnections()
    if (generation === requestGeneration && owner === ownerId.value) connections.value = data
  } catch {
    if (generation === requestGeneration && owner === ownerId.value) error.value = '凭据列表加载失败，请刷新重试。'
  } finally {
    if (generation === requestGeneration && owner === ownerId.value) loading.value = false
  }
}

/** Loads a list for the current owner and drops the answer if the signed-in user changed meanwhile. */
async function forOwner<T>(load: () => Promise<{ data: T }>, apply: (data: T) => void, fail: () => void) {
  const generation = ownerGeneration
  try {
    const { data } = await load()
    if (generation === ownerGeneration) apply(data)
  } catch {
    if (generation === ownerGeneration) fail()
  }
}

async function loadCapabilities() {
  const generation = ownerGeneration
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  await forOwner(listPlatformAiCapabilities, (data) => { capabilities.value = data },
    () => { capabilitiesError.value = '能力列表加载失败，请刷新重试。' })
  if (generation === ownerGeneration) capabilitiesLoading.value = false
}

async function loadCalls() {
  callsLoading.value = true
  callsError.value = ''
  await forOwner(listPlatformAiCalls, (data) => { calls.value = data },
    () => { callsError.value = '任务记录加载失败，请刷新重试。' })
  callsLoading.value = false
}

async function loadPendingApprovals() {
  await forOwner(listPlatformAiApprovals, (data) => {
    pendingApprovals.value = data.filter(item => item.status === 'PENDING').length
  }, () => { pendingApprovals.value = 0 })
}

async function createConnection() {
  if (creating.value) return
  const label = form.label.trim()
  if (!label) {
    createError.value = '请输入凭据名称。'
    return
  }
  const owner = ownerId.value
  if (owner === null) return
  const generation = ownerGeneration
  const selectedClientId = clientId.value
  creating.value = true
  createError.value = ''
  try {
    const { data } = await createPlatformAiConnection({ label, toolKind: toolKind.value, ttlDays: form.ttlDays })
    if (owner !== ownerId.value || generation !== ownerGeneration) return
    createdClientId.value = selectedClientId
    created.value = data
    await loadConnections()
  } catch {
    if (owner === ownerId.value && generation === ownerGeneration) createError.value = '凭据创建失败，请检查网络后重试。'
  } finally {
    if (owner === ownerId.value && generation === ownerGeneration) creating.value = false
  }
}

async function disconnect(row: AiCredentialRow) {
  if (revokingId.value !== null) return
  const owner = ownerId.value
  if (owner === null) return
  try {
    await ElMessageBox.confirm(`停用“${row.name}”后，该 AI 工具将不能继续使用这条凭据。`, '停用凭据', {
      confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  if (owner !== ownerId.value) return
  revokingId.value = row.id
  try {
    await revokePlatformAiConnection(row.id)
    if (owner !== ownerId.value) return
    ElMessage.success('凭据已停用')
    await loadConnections()
  } catch {
    if (owner === ownerId.value) ElMessage.error('停用凭据失败，请重试。')
  } finally {
    if (owner === ownerId.value) revokingId.value = null
  }
}
</script>

<style scoped lang="scss">
.ai-tool-access :deep(.el-tabs__header) { margin-bottom: 16px; }
.ai-tool-access :deep(.el-tabs__item) { gap: 7px; }
.ai-tool-access__setup-status {
  gap: 4px 16px;

  :deep(.ai-status-bar__item) { flex: 0 0 auto; gap: 5px; padding: 0; border: 0; }
  :deep(.ai-status-bar__label) { color: var(--text-secondary); font-size: 12px; }
  :deep(.ai-status-bar__value) { font-size: 12px; }
}

.scope-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
  gap: 16px;

  label { display: flex; min-width: 0; flex-direction: column; gap: 8px; }
  span { color: var(--text-secondary); font-size: 12px; }
}

.scope-form :deep(.credential-name-input .liquid-glass-control__content) {
  --control-ink: var(--text-primary);
  --control-ink-muted: var(--text-muted);
  background: var(--surface-solid-opaque);
}

</style>
