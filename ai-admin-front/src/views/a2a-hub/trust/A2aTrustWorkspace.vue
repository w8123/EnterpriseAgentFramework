<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { LiquidGlassButton, LiquidGlassDropdown, LiquidGlassInput, LiquidGlassNotice, LiquidGlassTable, LiquidGlassTabs } from '@/components/common/liquid-glass'
import A2aTrustLevelBadge from '@/components/a2a-hub/A2aTrustLevelBadge.vue'
import A2aListToolbar from '../A2aListToolbar.vue'
import { usePlatformCan } from '@/auth/usePlatformCan'
import {
  changeA2aPrincipalStatus,
  changeA2aTrustProfileStatus,
  createA2aApiKey,
  createA2aPrincipal,
  createA2aTrustProfile,
  listA2aCredentials,
  listA2aPrincipals,
  listA2aTrustProfiles,
  revokeA2aCredential,
  rotateA2aCredential,
  rotateA2aOutboundSecret,
  storeA2aOutboundSecret,
  updateA2aTrustProfile,
} from '@/api/a2aHub'
import type {
  A2aApiKeyIssue,
  A2aCredential,
  A2aDirection,
  A2aPrincipal,
  A2aPrincipalCreateRequest,
  A2aTrustProfile,
  A2aTrustProfileUpsertRequest,
} from '@/types/a2aHub'
import { a2aFormatDate, a2aLabel, a2aTone } from '@/utils/a2aHub'
import { listAgents } from '@/api/agent'
import type { Agent } from '@/types/workflow'

const { can } = usePlatformCan()
const activeTab = ref('profiles')
const loading = ref(false)
const loadError = ref(false)
const search = ref('')
let listRequest = 0
const profiles = ref<A2aTrustProfile[]>([])
const principals = ref<A2aPrincipal[]>([])
const credentials = ref<A2aCredential[]>([])
const workspaceTabs = [
  { name: 'profiles', label: '信任策略' },
  { name: 'principals', label: '调用身份' },
  { name: 'credentials', label: '凭据' },
]
const searchPlaceholder = computed(() => activeTab.value === 'profiles' ? '搜索策略名称或标识'
  : activeTab.value === 'principals' ? '搜索身份名称或标识' : '搜索凭据名称或标识')
const runtimeAgents = ref<Agent[]>([])
const runtimeAgentsLoading = ref(false)
const runtimeAgentsFailed = ref(false)
let runtimeAgentRequest = 0
const principalDetailVisible = ref(false)
const selectedPrincipal = ref<A2aPrincipal | null>(null)
const credentialDetailVisible = ref(false)
const selectedCredential = ref<A2aCredential | null>(null)

const profileDialogVisible = ref(false)
const profileSubmitting = ref(false)
const editingProfileId = ref<number | null>(null)
const canEditProfile = computed(() => can('a2a-hub:trust:manage'))
const profileForm = reactive<A2aTrustProfileUpsertRequest>(emptyProfile())

const credentialDialogVisible = ref(false)
const credentialSubmitting = ref(false)
const credentialDirection = ref<A2aDirection>('INBOUND')
const credentialForm = reactive({
  credentialKey: '',
  name: '',
  credentialType: 'BEARER' as 'API_KEY' | 'BEARER',
  secret: '',
  expiresAt: defaultExpiry(),
})
const secretVisible = ref(false)
const secretIssue = ref<A2aApiKeyIssue | null>(null)

const principalDialogVisible = ref(false)
const principalSubmitting = ref(false)
const principalForm = reactive<A2aPrincipalCreateRequest>(emptyPrincipal())
const selectedTrust = computed(() => profiles.value.find((item) => item.id === principalForm.trustProfileId))
const localAgentPrincipal = computed(() => principalForm.principalType === 'LOCAL_AGENT')
const principalTrustProfiles = computed(() => profiles.value.filter((item) => {
  if (item.status !== 'ACTIVE') return false
  const directions = localAgentPrincipal.value
    ? ['OUTBOUND', 'BIDIRECTIONAL']
    : ['INBOUND', 'BIDIRECTIONAL']
  return directions.includes(item.direction)
}))
const activeCredentials = computed(() => credentials.value.filter((item) =>
  item.status === 'ACTIVE' && item.direction === 'INBOUND',
))

function emptyProfile(): A2aTrustProfileUpsertRequest {
  return {
    profileKey: '',
    name: '',
    description: '',
    direction: 'INBOUND',
    environment: 'DEVELOPMENT',
    trustLevel: 'KNOWN',
    authenticationMethods: ['API_KEY'],
    allowedScopes: ['a2a:message:send', 'a2a:task:read', 'a2a:task:cancel'],
    authorizationPolicy: {
      publicationKeys: ['*'],
      remoteAgentKeys: ['*'],
      protocolSkillIds: ['*'],
      operations: ['message:send', 'tasks:get', 'tasks:list', 'tasks:cancel'],
      tenantScopes: ['*'],
    },
    dataPolicy: {
      allowedDataClassifications: ['INTERNAL'],
      allowTextParts: true,
      allowFileParts: false,
      allowUrlParts: true,
      payloadRetentionDays: 30,
    },
    delegatedIdentityPolicy: 'DENY',
    personalMemoryPolicy: 'DISABLED',
    rateLimitPerMinute: 60,
    maxConcurrentTasks: 10,
    maxRequestBytes: 1_048_576,
    maxArtifactBytes: 10_485_760,
    taskTimeoutMs: 120_000,
    allowAnonymous: false,
  }
}

function emptyPrincipal(): A2aPrincipalCreateRequest {
  return {
    principalKey: '',
    principalType: 'REMOTE_AGENT',
    runtimeAgentId: undefined,
    displayName: '',
    tenantScope: '',
    trustProfileId: 0,
    credentialId: undefined,
    scopes: [],
    attributes: {},
  }
}

function defaultExpiry() {
  const value = new Date()
  value.setDate(value.getDate() + 90)
  const local = new Date(value.getTime() - value.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 19)
}

async function reload() {
  const request = ++listRequest
  loading.value = true
  loadError.value = false
  try {
    const activeQuery = { search: search.value.trim() || undefined, limit: 100, offset: 0 }
    const relatedQuery = { limit: 100, offset: 0 }
    const [profileResult, principalResult, credentialResult] = await Promise.all([
      listA2aTrustProfiles(activeTab.value === 'profiles' ? activeQuery : relatedQuery),
      listA2aPrincipals(activeTab.value === 'principals' ? activeQuery : relatedQuery),
      listA2aCredentials(activeTab.value === 'credentials' ? activeQuery : relatedQuery),
    ])
    if (request !== listRequest) return
    profiles.value = profileResult.data?.items ?? []
    principals.value = principalResult.data?.items ?? []
    credentials.value = credentialResult.data?.items ?? []
  } catch {
    if (request === listRequest) loadError.value = true
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function openProfile(profile?: A2aTrustProfile) {
  if (!profile && !can('a2a-hub:trust:manage')) return
  editingProfileId.value = profile?.id ?? null
  Object.assign(profileForm, profile
    ? {
        profileKey: profile.profileKey,
        name: profile.name,
        description: profile.description,
        direction: profile.direction,
        environment: profile.environment,
        trustLevel: profile.trustLevel,
        authenticationMethods: [...profile.authenticationMethods],
        allowedScopes: [...profile.allowedScopes],
        authorizationPolicy: {
          publicationKeys: [...profile.authorizationPolicy.publicationKeys],
          remoteAgentKeys: [...profile.authorizationPolicy.remoteAgentKeys],
          protocolSkillIds: [...profile.authorizationPolicy.protocolSkillIds],
          operations: [...profile.authorizationPolicy.operations],
          tenantScopes: [...profile.authorizationPolicy.tenantScopes],
        },
        dataPolicy: { ...profile.dataPolicy, allowedDataClassifications: [...profile.dataPolicy.allowedDataClassifications] },
        delegatedIdentityPolicy: profile.delegatedIdentityPolicy,
        personalMemoryPolicy: profile.personalMemoryPolicy,
        rateLimitPerMinute: profile.rateLimitPerMinute,
        maxConcurrentTasks: profile.maxConcurrentTasks,
        maxRequestBytes: profile.maxRequestBytes,
        maxArtifactBytes: profile.maxArtifactBytes,
        taskTimeoutMs: profile.taskTimeoutMs,
        allowAnonymous: profile.allowAnonymous,
      }
    : emptyProfile())
  profileDialogVisible.value = true
}

function normalizeAuthContract() {
  const anonymous = profileForm.authenticationMethods.includes('ANONYMOUS')
  if (anonymous) {
    profileForm.authenticationMethods = ['ANONYMOUS']
    profileForm.allowAnonymous = true
    profileForm.environment = 'DEVELOPMENT'
    if (['TRUSTED', 'PRIVILEGED'].includes(profileForm.trustLevel)) profileForm.trustLevel = 'KNOWN'
  } else {
    profileForm.allowAnonymous = false
  }
}

async function saveProfile() {
  if (!canEditProfile.value) return
  normalizeAuthContract()
  if (!profileForm.profileKey.trim() || !profileForm.name.trim()) {
    ElMessage.warning('请填写策略标识和名称')
    return
  }
  if (!profileForm.authenticationMethods.length) {
    ElMessage.warning('至少选择一种认证方式')
    return
  }
  profileSubmitting.value = true
  try {
    if (editingProfileId.value) await updateA2aTrustProfile(editingProfileId.value, profileForm)
    else await createA2aTrustProfile(profileForm)
    ElMessage.success(editingProfileId.value ? '信任策略已更新' : '信任策略已创建')
    profileDialogVisible.value = false
    await reload()
  } finally {
    profileSubmitting.value = false
  }
}

async function transitionProfile(profile: A2aTrustProfile, status: string) {
  if (!can('a2a-hub:trust:manage')) return
  const confirmed = await ElMessageBox.confirm(
    status === 'ARCHIVED' ? '归档后不可恢复，且现有发布可能失去可用策略。' : `确认将策略切换为“${a2aLabel(status)}”？`,
    '变更策略状态',
    { type: 'warning' },
  ).then(() => true, () => false)
  if (!confirmed) return
  await changeA2aTrustProfileStatus(profile.id, status)
  await reload()
}

function openCredential(direction: A2aDirection) {
  if (!can('a2a-hub:credential:manage')) return
  credentialDirection.value = direction
  Object.assign(credentialForm, {
    credentialKey: '',
    name: '',
    credentialType: 'BEARER',
    secret: '',
    expiresAt: defaultExpiry(),
  })
  credentialDialogVisible.value = true
}

function showIssue(issue: A2aApiKeyIssue) {
  secretIssue.value = issue
  secretVisible.value = true
}

async function createCredential() {
  if (!can('a2a-hub:credential:manage')) return
  if (!credentialForm.credentialKey.trim() || !credentialForm.name.trim()) {
    ElMessage.warning('请填写凭据标识和名称')
    return
  }
  if (credentialDirection.value === 'OUTBOUND' && credentialForm.secret.length < 16) {
    ElMessage.warning('出站凭据秘密至少需要 16 个字符')
    return
  }
  credentialSubmitting.value = true
  try {
    if (credentialDirection.value === 'OUTBOUND') {
      await storeA2aOutboundSecret({
        credentialKey: credentialForm.credentialKey.trim(),
        name: credentialForm.name.trim(),
        credentialType: credentialForm.credentialType,
        secret: credentialForm.secret,
        expiresAt: credentialForm.expiresAt,
      })
      credentialForm.secret = ''
      ElMessage.success('出站凭据已加密保存；原始秘密不会回显')
    } else {
      const { data } = await createA2aApiKey({
        credentialKey: credentialForm.credentialKey.trim(),
        name: credentialForm.name.trim(),
        expiresAt: credentialForm.expiresAt,
      })
      showIssue(data)
    }
    credentialDialogVisible.value = false
    await reload()
  } finally {
    credentialSubmitting.value = false
  }
}

async function rotateCredential(credential: A2aCredential) {
  if (!can('a2a-hub:credential:manage')) return
  if (credential.direction === 'OUTBOUND') {
    const result = await ElMessageBox.prompt(
      '请输入新的远端 API Key 或 Bearer Token。系统只接收一次并立即加密，不会回显。',
      '轮换出站凭据',
      {
        type: 'warning',
        inputType: 'password',
        inputValidator: (input: string) => input.length >= 16 || '秘密至少需要 16 个字符',
      },
    ).catch(() => null)
    if (!result) return
    await rotateA2aOutboundSecret(credential.id, {
      secret: result.value,
      expiresAt: credential.expiresAt ?? undefined,
    })
    ElMessage.success('出站凭据已轮换，现有外部 Agent 绑定已原子迁移')
  } else {
    const confirmed = await ElMessageBox.confirm('轮换会签发新 API Key、重绑定调用身份，并立即吊销当前版本。', '轮换入站凭据', { type: 'warning' }).then(() => true, () => false)
    if (!confirmed) return
    const { data } = await rotateA2aCredential(credential.id)
    showIssue(data)
  }
  await reload()
}

async function revokeCredential(credential: A2aCredential) {
  if (!can('a2a-hub:credential:manage')) return
  const confirmed = await ElMessageBox.confirm('吊销后使用该凭据的远程主体将无法认证。', '吊销凭据', { type: 'warning' }).then(() => true, () => false)
  if (!confirmed) return
  await revokeA2aCredential(credential.id)
  ElMessage.success('凭据已吊销')
  await reload()
}

async function copySecret() {
  if (!secretIssue.value) return
  await navigator.clipboard.writeText(secretIssue.value.apiKeyOnce)
  ElMessage.success('API Key 已复制；请立即保存到企业秘密管理器')
}

function closeSecret() {
  secretVisible.value = false
  secretIssue.value = null
}

function openPrincipal() {
  if (!can('a2a-hub:trust:manage')) return
  Object.assign(principalForm, emptyPrincipal())
  principalDialogVisible.value = true
}

function principalTrustChanged() {
  principalForm.credentialId = undefined
  principalForm.scopes = selectedTrust.value?.allowedScopes.filter((item) => item !== '*') ?? []
}

async function loadRuntimeAgents() {
  const request = ++runtimeAgentRequest
  runtimeAgentsLoading.value = true
  runtimeAgentsFailed.value = false
  try {
    const { data } = await listAgents()
    if (request === runtimeAgentRequest) runtimeAgents.value = data ?? []
  } catch {
    if (request === runtimeAgentRequest) {
      runtimeAgents.value = []
      runtimeAgentsFailed.value = true
    }
  } finally {
    if (request === runtimeAgentRequest) runtimeAgentsLoading.value = false
  }
}

function principalTypeChanged() {
  principalForm.trustProfileId = 0
  principalForm.credentialId = undefined
  principalForm.runtimeAgentId = undefined
  principalForm.scopes = []
  if (localAgentPrincipal.value) {
    void loadRuntimeAgents()
  } else {
    ++runtimeAgentRequest
    runtimeAgentsLoading.value = false
    runtimeAgentsFailed.value = false
  }
}

async function savePrincipal() {
  if (!can('a2a-hub:trust:manage')) return
  const trust = selectedTrust.value
  if (!principalForm.principalKey.trim() || !principalForm.displayName.trim() || !trust) {
    ElMessage.warning('请完整填写身份标识、名称和信任策略')
    return
  }
  if (localAgentPrincipal.value && !principalForm.runtimeAgentId) {
    ElMessage.warning('请选择该 Principal 代表的本地 Runtime Agent')
    return
  }
  if (!localAgentPrincipal.value && !trust.allowAnonymous && !principalForm.credentialId) {
    ElMessage.warning('API Key Trust Profile 必须绑定一个有效凭据')
    return
  }
  principalSubmitting.value = true
  try {
    await createA2aPrincipal({
      ...principalForm,
      runtimeAgentId: localAgentPrincipal.value ? principalForm.runtimeAgentId : undefined,
      credentialId: localAgentPrincipal.value || trust.allowAnonymous
        ? undefined
        : principalForm.credentialId,
    })
    ElMessage.success('调用身份已创建')
    principalDialogVisible.value = false
    await reload()
  } finally {
    principalSubmitting.value = false
  }
}

async function transitionPrincipal(principal: A2aPrincipal, status: string) {
  if (!can('a2a-hub:trust:manage')) return
  const confirmed = await ElMessageBox.confirm(`确认将调用身份切换为“${a2aLabel(status)}”？`, '变更身份状态', { type: 'warning' }).then(() => true, () => false)
  if (!confirmed) return
  await changeA2aPrincipalStatus(principal.id, status)
  await reload()
}

function profileName(id: number) {
  return profiles.value.find((item) => item.id === id)?.name ?? `策略 #${id}`
}

function credentialName(principal: A2aPrincipal) {
  if (principal.principalType === 'LOCAL_AGENT') return 'Runtime 身份校验'
  return principal.credentialId
    ? credentials.value.find((item) => item.id === principal.credentialId)?.name ?? `凭据 #${principal.credentialId}`
    : '未绑定凭据'
}

function principalTypeLabel(value: string) {
  return ({ LOCAL_AGENT: '本地 Agent', REMOTE_AGENT: '外部 Agent', REMOTE_CLIENT: '远程客户端' } as Record<string, string>)[value] ?? value
}

function credentialCommand(command: string, credential: A2aCredential) {
  if (command === 'rotate') void rotateCredential(credential)
  else if (command === 'revoke') void revokeCredential(credential)
}

watch(activeTab, () => {
  search.value = ''
  void reload()
}, { flush: 'sync' })
onMounted(reload)
</script>

<template>
  <section class="trust-workspace">
    <LiquidGlassTabs v-model="activeTab" source="wallpaper" presentation="underline" aria-label="治理资产类型">
      <el-tab-pane v-for="tab in workspaceTabs" :key="tab.name" :name="tab.name" :label="tab.label" />
    </LiquidGlassTabs>

    <LiquidGlassTable class="workbench-list-surface" :loading="loading" :show-pagination="false">
      <template #toolbar>
        <A2aListToolbar :loading="loading" @query="reload" @refresh="reload">
          <LiquidGlassInput v-model="search" :prefix-icon="Search" clearable :placeholder="searchPlaceholder" aria-label="搜索治理资产" @clear="reload" />
          <template #actions>
            <LiquidGlassButton v-if="activeTab === 'profiles' && can('a2a-hub:trust:manage')" type="primary" :icon="Plus" @click="openProfile()">新建策略</LiquidGlassButton>
            <LiquidGlassButton v-else-if="activeTab === 'principals' && can('a2a-hub:trust:manage')" type="primary" :icon="Plus" @click="openPrincipal">新建身份</LiquidGlassButton>
            <LiquidGlassDropdown v-else-if="activeTab === 'credentials' && can('a2a-hub:credential:manage')" trigger="click" @command="openCredential">
              <LiquidGlassButton type="primary" :icon="Plus">新建凭据<el-icon class="el-icon--right"><ArrowDown /></el-icon></LiquidGlassButton>
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item command="INBOUND">签发入站 API Key</el-dropdown-item>
                <el-dropdown-item command="OUTBOUND">保存出站凭据</el-dropdown-item>
              </el-dropdown-menu></template>
            </LiquidGlassDropdown>
          </template>
        </A2aListToolbar>
      </template>
      <LiquidGlassNotice v-if="loadError" type="error" title="治理资产加载失败，请刷新重试。" />
      <el-table v-else-if="activeTab === 'profiles'" :data="profiles" stripe empty-text="还没有信任策略">
        <el-table-column label="策略" min-width="240" show-overflow-tooltip><template #default="{ row }"><div class="cell-stack"><strong>{{ row.name }}</strong><small>{{ row.profileKey }}</small></div></template></el-table-column>
        <el-table-column label="范围" width="150"><template #default="{ row }">{{ a2aLabel(row.direction) }} · {{ a2aLabel(row.environment) }}</template></el-table-column>
        <el-table-column label="信任等级" width="110"><template #default="{ row }"><A2aTrustLevelBadge :level="row.trustLevel" /></template></el-table-column>
        <el-table-column label="认证方式" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.authenticationMethods.map(a2aLabel).join(' / ') }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><div class="row-actions">
          <el-button link type="primary" @click="openProfile(row)">{{ can('a2a-hub:trust:manage') ? '编辑' : '查看' }}</el-button>
          <LiquidGlassDropdown v-if="can('a2a-hub:trust:manage') && row.status !== 'ARCHIVED'" trigger="click" @command="(status: string) => transitionProfile(row, status)">
            <LiquidGlassButton circle :icon="MoreFilled" :aria-label="`${row.name}的更多操作`" title="更多" />
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item v-if="row.status === 'ACTIVE'" command="DISABLED">停用</el-dropdown-item>
              <el-dropdown-item v-if="row.status === 'DISABLED'" command="ACTIVE">启用</el-dropdown-item>
              <el-dropdown-item divided command="ARCHIVED">归档</el-dropdown-item>
            </el-dropdown-menu></template>
          </LiquidGlassDropdown>
        </div></template></el-table-column>
      </el-table>
      <el-table v-else-if="activeTab === 'principals'" :data="principals" stripe empty-text="还没有调用身份">
        <el-table-column label="调用身份" min-width="220" show-overflow-tooltip><template #default="{ row }"><div class="cell-stack"><strong>{{ row.displayName }}</strong><small>{{ row.principalKey }}</small></div></template></el-table-column>
        <el-table-column label="类型" width="120"><template #default="{ row }">{{ principalTypeLabel(row.principalType) }}</template></el-table-column>
        <el-table-column prop="tenantScope" label="租户" width="130"><template #default="{ row }">{{ row.tenantScope || '全局' }}</template></el-table-column>
        <el-table-column label="信任策略" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ profileName(row.trustProfileId) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><div class="row-actions">
          <el-button link type="primary" @click="selectedPrincipal = row; principalDetailVisible = true">查看</el-button>
          <LiquidGlassDropdown v-if="can('a2a-hub:trust:manage') && row.status !== 'ARCHIVED'" trigger="click" @command="(status: string) => transitionPrincipal(row, status)">
            <LiquidGlassButton circle :icon="MoreFilled" :aria-label="`${row.displayName}的更多操作`" title="更多" />
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item v-if="row.status === 'ACTIVE'" command="QUARANTINED">隔离</el-dropdown-item>
              <el-dropdown-item v-if="['DISABLED', 'QUARANTINED'].includes(row.status)" command="ACTIVE">恢复</el-dropdown-item>
              <el-dropdown-item divided command="ARCHIVED">归档</el-dropdown-item>
            </el-dropdown-menu></template>
          </LiquidGlassDropdown>
        </div></template></el-table-column>
      </el-table>
      <el-table v-else :data="credentials" stripe empty-text="还没有凭据">
        <el-table-column label="凭据" min-width="220" show-overflow-tooltip><template #default="{ row }"><div class="cell-stack"><strong>{{ row.name }}</strong><small>{{ row.credentialKey }}</small></div></template></el-table-column>
        <el-table-column label="方向" width="90"><template #default="{ row }"><el-tag size="small" effect="plain">{{ a2aLabel(row.direction) }}</el-tag></template></el-table-column>
        <el-table-column prop="credentialType" label="类型" width="110"><template #default="{ row }">{{ a2aLabel(row.credentialType) }}</template></el-table-column>
        <el-table-column label="有效期" width="170"><template #default="{ row }">{{ a2aFormatDate(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><div class="row-actions">
          <el-button link type="primary" @click="selectedCredential = row; credentialDetailVisible = true">查看</el-button>
          <LiquidGlassDropdown v-if="can('a2a-hub:credential:manage') && ['ACTIVE', 'GRACE'].includes(row.status)" trigger="click" @command="(command: string) => credentialCommand(command, row)">
            <LiquidGlassButton circle :icon="MoreFilled" :aria-label="`${row.name}的更多操作`" title="更多" />
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="rotate">轮换</el-dropdown-item>
              <el-dropdown-item divided command="revoke">吊销</el-dropdown-item>
            </el-dropdown-menu></template>
          </LiquidGlassDropdown>
        </div></template></el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDrawer v-model="principalDetailVisible" title="调用身份" size="min(600px, 100vw)">
      <el-descriptions v-if="selectedPrincipal" :column="1" border class="asset-detail">
        <el-descriptions-item label="名称">{{ selectedPrincipal.displayName }}</el-descriptions-item>
        <el-descriptions-item label="标识">{{ selectedPrincipal.principalKey }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ principalTypeLabel(selectedPrincipal.principalType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ a2aLabel(selectedPrincipal.status) }}</el-descriptions-item>
        <el-descriptions-item label="租户">{{ selectedPrincipal.tenantScope || '全局' }}</el-descriptions-item>
        <el-descriptions-item label="认证主体">{{ selectedPrincipal.authenticatedSubject || '—' }}</el-descriptions-item>
        <el-descriptions-item label="信任策略">{{ profileName(selectedPrincipal.trustProfileId) }}</el-descriptions-item>
        <el-descriptions-item label="认证凭据">{{ credentialName(selectedPrincipal) }}</el-descriptions-item>
        <el-descriptions-item label="授权范围">{{ selectedPrincipal.scopes.join(', ') || '—' }}</el-descriptions-item>
        <el-descriptions-item label="最近认证">{{ a2aFormatDate(selectedPrincipal.lastAuthenticatedAt) }}</el-descriptions-item>
        <el-descriptions-item v-for="(value, key) in selectedPrincipal.attributes" :key="key" :label="String(key)">{{ value }}</el-descriptions-item>
      </el-descriptions>
    </AppDrawer>

    <AppDrawer v-model="credentialDetailVisible" title="凭据详情" size="min(600px, 100vw)">
      <el-descriptions v-if="selectedCredential" :column="1" border class="asset-detail">
        <el-descriptions-item label="名称">{{ selectedCredential.name }}</el-descriptions-item>
        <el-descriptions-item label="标识">{{ selectedCredential.credentialKey }}</el-descriptions-item>
        <el-descriptions-item label="方向">{{ a2aLabel(selectedCredential.direction) }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ a2aLabel(selectedCredential.credentialType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ a2aLabel(selectedCredential.status) }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ selectedCredential.versionNo }}</el-descriptions-item>
        <el-descriptions-item label="指纹"><code>{{ selectedCredential.fingerprint }}</code></el-descriptions-item>
        <el-descriptions-item label="有效期">{{ a2aFormatDate(selectedCredential.expiresAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近使用">{{ a2aFormatDate(selectedCredential.lastUsedAt) }}</el-descriptions-item>
      </el-descriptions>
    </AppDrawer>

    <AppDialog v-model="profileDialogVisible" :title="!canEditProfile ? '查看信任策略' : editingProfileId ? '编辑信任策略' : '新建信任策略'" width="min(920px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="policy-form" :disabled="!canEditProfile">
        <div class="form-grid"><el-form-item label="策略标识"><el-input v-model="profileForm.profileKey" :disabled="!!editingProfileId" placeholder="例如 partner-agent-dev" /></el-form-item><el-form-item label="名称"><el-input v-model="profileForm.name" /></el-form-item></div>
        <el-form-item label="说明"><el-input v-model="profileForm.description" type="textarea" :rows="2" /></el-form-item>
        <div class="form-grid form-grid--4"><el-form-item label="方向"><el-select v-model="profileForm.direction"><el-option v-for="value in ['INBOUND','OUTBOUND','BIDIRECTIONAL']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="环境"><el-select v-model="profileForm.environment"><el-option v-for="value in ['DEVELOPMENT','TEST','STAGING','PRODUCTION']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="信任等级"><el-select v-model="profileForm.trustLevel"><el-option v-for="value in ['UNTRUSTED','KNOWN','TRUSTED','PRIVILEGED']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="认证方式"><el-select v-model="profileForm.authenticationMethods" multiple @change="normalizeAuthContract"><el-option v-for="value in ['API_KEY','BEARER','HMAC','OAUTH2_CLIENT_CREDENTIALS','MTLS','ANONYMOUS']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item></div>
        <el-divider content-position="left">最小授权</el-divider>
        <div class="form-grid"><el-form-item label="Publication allowlist"><el-select v-model="profileForm.authorizationPolicy.publicationKeys" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Remote Agent allowlist"><el-select v-model="profileForm.authorizationPolicy.remoteAgentKeys" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="协议 AgentSkill allowlist"><el-select v-model="profileForm.authorizationPolicy.protocolSkillIds" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Operations"><el-select v-model="profileForm.authorizationPolicy.operations" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="租户 allowlist"><el-select v-model="profileForm.authorizationPolicy.tenantScopes" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Principal Scopes"><el-select v-model="profileForm.allowedScopes" multiple filterable allow-create default-first-option /></el-form-item></div>
        <el-divider content-position="left">数据与容量边界</el-divider>
        <div class="form-grid form-grid--4"><el-form-item label="数据等级"><el-select v-model="profileForm.dataPolicy.allowedDataClassifications" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="正文保留（天）"><el-input-number v-model="profileForm.dataPolicy.payloadRetentionDays" :min="0" :max="3650" /></el-form-item><el-form-item label="每分钟请求"><el-input-number v-model="profileForm.rateLimitPerMinute" :min="1" /></el-form-item><el-form-item label="并发 Task"><el-input-number v-model="profileForm.maxConcurrentTasks" :min="1" /></el-form-item><el-form-item label="请求上限（bytes）"><el-input-number v-model="profileForm.maxRequestBytes" :min="1" /></el-form-item><el-form-item label="Artifact 上限（bytes）"><el-input-number v-model="profileForm.maxArtifactBytes" :min="1" /></el-form-item><el-form-item label="Task 超时（ms）"><el-input-number v-model="profileForm.taskTimeoutMs" :min="1000" /></el-form-item></div>
        <div class="switch-row"><el-checkbox v-model="profileForm.dataPolicy.allowTextParts">允许 Text Part</el-checkbox><el-checkbox v-model="profileForm.dataPolicy.allowFileParts">允许 File Part</el-checkbox><el-checkbox v-model="profileForm.dataPolicy.allowUrlParts">允许 URL Part</el-checkbox></div>
        <div class="form-grid"><el-form-item label="委派用户身份"><el-select v-model="profileForm.delegatedIdentityPolicy"><el-option label="禁止" value="DENY" /><el-option label="仅接受可验证声明" value="ATTESTED_ONLY" /></el-select></el-form-item><el-form-item label="Personal Memory"><el-select v-model="profileForm.personalMemoryPolicy"><el-option label="禁用（默认）" value="DISABLED" /><el-option label="仅验证用户" value="ATTESTED_USER_ONLY" /></el-select></el-form-item></div>
        <LiquidGlassNotice v-if="profileForm.authenticationMethods.includes('ANONYMOUS')" type="error" title="匿名仅限 DEVELOPMENT，且不能与其他认证方式混用；页面已自动收紧这些字段。" />
      </el-form>
      <template #footer><LiquidGlassButton @click="profileDialogVisible = false">{{ canEditProfile ? '取消' : '关闭' }}</LiquidGlassButton><LiquidGlassButton v-if="canEditProfile" type="primary" :loading="profileSubmitting" @click="saveProfile">保存策略</LiquidGlassButton></template>
    </AppDialog>

    <AppDialog
      v-model="credentialDialogVisible"
      :title="credentialDirection === 'OUTBOUND' ? '保存出站凭据' : '签发入站 API Key'"
      :description="credentialDirection === 'OUTBOUND' ? '远端秘密只提交一次，并使用独立 AES-GCM 密钥加密；任何 API 都不会回显。' : '平台仅保存 BCrypt 哈希与指纹；原始 Key 只在下一步显示一次。'"
      width="560px"
      :close-on-click-modal="false"
      @closed="credentialForm.secret = ''"
    >
      <el-form label-position="top">
        <el-form-item label="凭据标识"><el-input v-model="credentialForm.credentialKey" placeholder="例如 partner-alpha" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="credentialForm.name" /></el-form-item>
        <el-form-item v-if="credentialDirection === 'OUTBOUND'" label="认证类型">
          <el-select v-model="credentialForm.credentialType"><el-option label="Bearer Token" value="BEARER" /><el-option label="Header API Key" value="API_KEY" /></el-select>
        </el-form-item>
        <el-form-item v-if="credentialDirection === 'OUTBOUND'" label="远端秘密">
          <el-input v-model="credentialForm.secret" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="过期时间"><el-date-picker v-model="credentialForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
      </el-form>
      <LiquidGlassNotice v-if="credentialDirection === 'OUTBOUND'" type="warning" title="不要把远端秘密复制到日志、工单、聊天或 Git。保存后只能轮换，不能读取。" />
      <template #footer><LiquidGlassButton @click="credentialDialogVisible = false">取消</LiquidGlassButton><LiquidGlassButton type="primary" :loading="credentialSubmitting" @click="createCredential">{{ credentialDirection === 'OUTBOUND' ? '加密保存' : '签发' }}</LiquidGlassButton></template>
    </AppDialog>

    <AppDialog :model-value="secretVisible" title="立即保存 API Key" description="离开此窗口后平台无法恢复原始 Key；请保存到 Vault/KMS 等企业秘密管理器。" width="680px" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" @update:model-value="(value: boolean) => { if (!value) closeSecret() }">
      <LiquidGlassNotice type="warning" title="不要把 API Key 粘贴到聊天、日志、工单或 Git。" />
      <div v-if="secretIssue" class="secret-box"><code>{{ secretIssue.apiKeyOnce }}</code><el-button type="primary" @click="copySecret">复制</el-button></div>
      <template #footer><LiquidGlassButton type="primary" @click="closeSecret">我已安全保存，关闭</LiquidGlassButton></template>
    </AppDialog>

    <AppDialog v-model="principalDialogVisible" title="新建调用身份" width="650px" :close-on-click-modal="false">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="身份标识"><el-input v-model="principalForm.principalKey" placeholder="例如 partner-alpha.agent" /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="principalForm.displayName" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="身份类型"><el-select v-model="principalForm.principalType" @change="principalTypeChanged"><el-option label="本地 Agent（用于出站委派）" value="LOCAL_AGENT" /><el-option label="外部 Agent" value="REMOTE_AGENT" /><el-option label="远程客户端" value="REMOTE_CLIENT" /></el-select></el-form-item>
          <el-form-item label="租户范围"><el-input v-model="principalForm.tenantScope" placeholder="留空表示全局" /></el-form-item>
        </div>
        <el-form-item v-if="localAgentPrincipal" label="本地 Agent"><el-select v-model="principalForm.runtimeAgentId" :loading="runtimeAgentsLoading" filterable><el-option v-for="agent in runtimeAgents" :key="agent.id" :label="`${agent.name} · ${agent.keySlug}`" :value="agent.id" /></el-select><el-button v-if="runtimeAgentsFailed" link type="primary" @click="loadRuntimeAgents">加载失败，重试</el-button></el-form-item>
        <el-form-item label="信任策略"><el-select v-model="principalForm.trustProfileId" filterable @change="principalTrustChanged"><el-option v-for="profile in principalTrustProfiles" :key="profile.id" :label="`${profile.name} · ${a2aLabel(profile.direction)} · ${a2aLabel(profile.environment)}`" :value="profile.id" /></el-select></el-form-item>
        <el-form-item v-if="!localAgentPrincipal && selectedTrust && !selectedTrust.allowAnonymous" label="API Key 凭据"><el-select v-model="principalForm.credentialId" filterable><el-option v-for="credential in activeCredentials" :key="credential.id" :label="`${credential.name} · v${credential.versionNo} · ${credential.fingerprint.slice(0, 12)}…`" :value="credential.id" /></el-select></el-form-item>
        <LiquidGlassNotice v-if="localAgentPrincipal" type="info" title="本地 Agent 通过服务间身份校验，不绑定入站凭据；请选择出站或双向信任策略。" />
        <el-form-item label="授权范围（Scopes）"><el-select v-model="principalForm.scopes" multiple filterable allow-create default-first-option /></el-form-item>
      </el-form>
      <template #footer><LiquidGlassButton @click="principalDialogVisible = false">取消</LiquidGlassButton><LiquidGlassButton type="primary" :loading="principalSubmitting" @click="savePrincipal">创建身份</LiquidGlassButton></template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.trust-workspace { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.row-actions { display: flex; align-items: center; gap: 8px; }
.switch-row { display: flex; align-items: center; gap: 10px; }
.asset-detail { overflow-wrap: anywhere; }
.cell-stack strong, .cell-stack small { display: block; }
.cell-stack strong { color: var(--text-primary); }
.cell-stack small { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.policy-form :deep(.el-select), .policy-form :deep(.el-input-number), .policy-form :deep(.el-date-editor), .el-dialog :deep(.el-select), .el-dialog :deep(.el-date-editor) { width: 100%; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.form-grid--4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.switch-row { justify-content: flex-start; flex-wrap: wrap; margin-bottom: 16px; }
.secret-box { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: center; margin-top: 16px; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.secret-box code { overflow-wrap: anywhere; color: var(--text-primary); font-size: 13px; }

@media (max-width: 900px) {
  .form-grid--4 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 600px) {
  .form-grid, .form-grid--4 { grid-template-columns: 1fr; }
}
</style>
