<template>
  <WorkbenchPage class="ai-tool-access" layout="list">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="agent"
      title="AI 工具接入"
      description="连接你习惯的 AI 工具，以你的平台身份查询和配置 ReachAI。"
    />

    <AiAccessStatusBar :items="statusItems" />

    <LiquidGlassTabs v-model="tab" source="wallpaper">
      <el-tab-pane label="接入与体验" name="setup" class="ai-tool-access__setup-pane">
        <template #label><el-icon aria-hidden="true"><Connection /></el-icon>接入与体验</template>
        <AiAccessSetup
          v-model:tool-kind="toolKind"
          v-model:client-id="clientId"
          :tools="PLATFORM_AI_TOOL_OPTIONS"
          scope-title="设置访问凭据"
          scope-hint="按你当前的账号权限授权，权限减少时自动收紧。"
          :prompt="installPrompt"
          :creating="creating"
          :can-create="!!form.label.trim()"
          disabled-reason="请先填写凭据名称"
          :error="createError"
          :try-phrases="tryPhrases"
          @create="createConnection"
          @done="created = null"
        >
          <template #scope>
            <div class="scope-form">
              <label>
                <span>凭据名称</span>
                <LiquidGlassInput v-model="form.label" class="credential-name-input" source="backdrop" maxlength="128" :placeholder="`例如：我的 ${selectedClient.label}`" aria-label="凭据名称" :disabled="!!created || creating" />
              </label>
              <label>
                <span>有效期</span>
                <LiquidGlassSelect v-model="form.ttlDays" aria-label="凭据有效期" dropdown-title="凭据有效期" :disabled="!!created || creating">
                  <el-option label="1 天" :value="1" />
                  <el-option label="7 天" :value="7" />
                  <el-option label="30 天" :value="30" />
                </LiquidGlassSelect>
              </label>
            </div>
          </template>
        </AiAccessSetup>
      </el-tab-pane>

      <el-tab-pane label="我能办的事" name="capabilities">
        <template #label><el-icon aria-hidden="true"><Grid /></el-icon>我能办的事</template>
        <LiquidGlassCard source="wallpaper">
          <AiCapabilityList
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
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Connection, Grid, Key } from '@element-plus/icons-vue'
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
import {
  buildInstallPrompt, isToday, PLATFORM_AI_TOOL_OPTIONS,
  type AiCallRecord, type AiCapabilityGroup, type AiClientId, type AiCredentialRow, type AiToolKind,
} from '@/components/ai-access/aiAccess'
import { platformAiSetup } from './platformAiSetup'

// 管理端的「AI 工具接入」：和员工门户同一套界面，授权范围是 ReachAI 平台本身。
const tab = ref<'setup' | 'capabilities' | 'credentials' | 'records'>('setup')
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
  subtitle: '按你当前账号的权限计算；新建凭据时按这些权限授权。',
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
  { label: 'AI 接入服务', value: loading.value ? '检查中' : error.value ? '无法连接' : '连接可用', tone: error.value ? 'danger' as const : loading.value ? undefined : 'ok' as const, dot: true },
  { label: '使用中的凭据', value: connections.value.filter(item => item.status === 'ACTIVE').length },
  { label: '今日调用', value: `${calls.value.filter(call => isToday(call.createdAt)).length} 次` },
  { label: '待我审核', value: pendingApprovals.value, tone: pendingApprovals.value ? 'warn' as const : undefined },
])

const tryPhrases = ['列出我能管理的项目', '查看我的项目接入了哪些业务方法', '帮我新建一个查询库存的 Workflow 草稿']

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
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  await forOwner(listPlatformAiCapabilities, (data) => { capabilities.value = data },
    () => { capabilitiesError.value = '加载失败，请刷新重试。' })
  capabilitiesLoading.value = false
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
.ai-tool-access { height: 100%; max-height: 100%; min-height: 0; overflow: hidden; }
.ai-tool-access > :deep(.app-page-header), .ai-tool-access > :deep(.ai-status-bar) { flex-shrink: 0; }
.ai-tool-access > :deep(.liquid-glass-tabs) { display: flex; flex: 1; min-height: 0; flex-direction: column; }
.ai-tool-access :deep(.el-tabs__header) { flex-shrink: 0; margin-bottom: 16px; }
.ai-tool-access :deep(.el-tabs__item) { gap: 7px; }
.ai-tool-access :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: auto; }
.ai-tool-access :deep(.el-tab-pane) { min-height: 100%; }
.ai-tool-access :deep(.ai-tool-access__setup-pane) { height: 100%; min-height: 0; }
.ai-tool-access :deep(.ai-setup) { height: 100%; min-height: 0; grid-template-rows: minmax(0, 1fr); align-items: stretch; }
.ai-tool-access :deep(.ai-setup__aside) { height: 100%; min-height: 0; }
.ai-tool-access :deep(.ai-setup__guide) { display: flex; flex: 1; min-height: 0; flex-direction: column; overflow: auto; }
.ai-tool-access :deep(.ai-setup__guide > *) { flex-shrink: 0; }
.ai-tool-access :deep(.ai-setup__examples) { margin-top: auto; padding-top: 20px; }
.ai-tool-access :deep(.ai-setup__workspace) { display: flex; min-height: 0; flex-direction: column; overflow: auto; }
.ai-tool-access :deep(.ai-setup__scope) { margin-top: 24px; padding-top: 24px; }
.ai-tool-access :deep(.liquid-glass-card) { box-sizing: border-box; }

@media (max-width: 960px) {
  .ai-tool-access :deep(.ai-tool-access__setup-pane) { height: auto; }
  .ai-tool-access :deep(.ai-setup) { height: auto; grid-template-rows: auto; align-items: start; }
  .ai-tool-access :deep(.ai-setup__aside) { display: flex; height: auto; }
  .ai-tool-access :deep(.ai-setup__guide), .ai-tool-access :deep(.ai-setup__workspace) { overflow: visible; }
  .ai-tool-access :deep(.ai-setup__scope) { margin-top: 24px; }
}

.scope-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 16px;

  label { display: flex; min-width: 0; flex-direction: column; gap: 8px; }
  span { color: var(--text-secondary); font-size: 12px; }
}

.scope-form :deep(.credential-name-input .liquid-glass-control__content) {
  --control-ink: var(--text-primary);
  --control-ink-muted: var(--text-muted);
  background: var(--surface-solid-opaque);
}

@media (max-width: 640px) {
  .scope-form { grid-template-columns: 1fr; }
}
</style>
