<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Key, Plus, Refresh, Search, User } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import A2aTrustLevelBadge from '@/components/a2a-hub/A2aTrustLevelBadge.vue'
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

const activeTab = ref('profiles')
const loading = ref(false)
const search = ref('')
const profiles = ref<A2aTrustProfile[]>([])
const principals = ref<A2aPrincipal[]>([])
const credentials = ref<A2aCredential[]>([])
const runtimeAgents = ref<Agent[]>([])

const profileDialogVisible = ref(false)
const profileSubmitting = ref(false)
const editingProfileId = ref<number | null>(null)
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
  loading.value = true
  try {
    const query = { search: search.value || undefined, limit: 100, offset: 0 }
    const [profileResult, principalResult, credentialResult, runtimeAgentResult] = await Promise.all([
      listA2aTrustProfiles(query),
      listA2aPrincipals(query),
      listA2aCredentials(query),
      listAgents(),
    ])
    profiles.value = profileResult.data?.items ?? []
    principals.value = principalResult.data?.items ?? []
    credentials.value = credentialResult.data?.items ?? []
    runtimeAgents.value = runtimeAgentResult.data ?? []
  } finally {
    loading.value = false
  }
}

function openProfile(profile?: A2aTrustProfile) {
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
    ElMessage.success(editingProfileId.value ? 'Trust Profile 已更新' : 'Trust Profile 已创建')
    profileDialogVisible.value = false
    await reload()
  } finally {
    profileSubmitting.value = false
  }
}

async function transitionProfile(profile: A2aTrustProfile, status: string) {
  await ElMessageBox.confirm(
    status === 'ARCHIVED' ? '归档后不可恢复，且现有发布可能失去可用策略。' : `确认将策略切换为“${a2aLabel(status)}”？`,
    'Trust Profile 状态变更',
    { type: 'warning' },
  )
  await changeA2aTrustProfileStatus(profile.id, status)
  await reload()
}

function openCredential(direction: A2aDirection) {
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
  if (credential.direction === 'OUTBOUND') {
    const { value } = await ElMessageBox.prompt(
      '请输入新的远端 API Key 或 Bearer Token。系统只接收一次并立即加密，不会回显。',
      '轮换出站凭据',
      {
        type: 'warning',
        inputType: 'password',
        inputValidator: (input: string) => input.length >= 16 || '秘密至少需要 16 个字符',
      },
    )
    await rotateA2aOutboundSecret(credential.id, {
      secret: value,
      expiresAt: credential.expiresAt ?? undefined,
    })
    ElMessage.success('出站凭据已轮换，现有远程 Agent 绑定已原子迁移')
  } else {
    await ElMessageBox.confirm('轮换会签发新 API Key、重绑定 Principal，并立即吊销当前版本。', '轮换入站凭据', { type: 'warning' })
    const { data } = await rotateA2aCredential(credential.id)
    showIssue(data)
  }
  await reload()
}

async function revokeCredential(credential: A2aCredential) {
  await ElMessageBox.confirm('吊销后使用该凭据的远程主体将无法认证。', '吊销凭据', { type: 'warning' })
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
  Object.assign(principalForm, emptyPrincipal())
  principalDialogVisible.value = true
}

function principalTrustChanged() {
  principalForm.credentialId = undefined
  principalForm.scopes = selectedTrust.value?.allowedScopes.filter((item) => item !== '*') ?? []
}

function principalTypeChanged() {
  principalForm.trustProfileId = 0
  principalForm.credentialId = undefined
  principalForm.runtimeAgentId = undefined
  principalForm.scopes = []
}

async function savePrincipal() {
  const trust = selectedTrust.value
  if (!principalForm.principalKey.trim() || !principalForm.displayName.trim() || !trust) {
    ElMessage.warning('请完整填写主体标识、名称和 Trust Profile')
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
    ElMessage.success('Principal 已创建')
    principalDialogVisible.value = false
    await reload()
  } finally {
    principalSubmitting.value = false
  }
}

async function transitionPrincipal(principal: A2aPrincipal, status: string) {
  await ElMessageBox.confirm(`确认将主体切换为“${a2aLabel(status)}”？`, 'Principal 状态变更', { type: 'warning' })
  await changeA2aPrincipalStatus(principal.id, status)
  await reload()
}

onMounted(reload)
</script>

<template>
  <section class="trust-workspace">
    <div class="section-intro">
      <div><h2>信任与策略</h2><p>Principal 来自认证结果；Trust Profile 决定谁能调用谁、处理什么数据，以及任务边界。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon title="请求 metadata 中的 userId、tenant、role 和 scope 永远不会提升为安全主体。远程 Agent 默认禁用 Personal Memory。" />

    <WorkbenchPanel level="control" density="compact">
      <div class="workspace-toolbar">
        <el-tabs v-model="activeTab" class="workspace-tabs">
          <el-tab-pane :label="`Trust Profile (${profiles.length})`" name="profiles" />
          <el-tab-pane :label="`Principal (${principals.length})`" name="principals" />
          <el-tab-pane :label="`凭据 (${credentials.length})`" name="credentials" />
        </el-tabs>
        <div class="toolbar-actions">
          <el-input v-model="search" :prefix-icon="Search" clearable placeholder="搜索当前治理资产" @keyup.enter="reload" />
          <el-button v-if="activeTab === 'profiles'" type="primary" :icon="Plus" @click="openProfile()">新建策略</el-button>
          <el-button v-else-if="activeTab === 'principals'" type="primary" :icon="User" @click="openPrincipal">新建主体</el-button>
          <template v-else>
            <el-button :icon="Key" @click="openCredential('INBOUND')">签发入站 Key</el-button>
            <el-button type="primary" :icon="Key" @click="openCredential('OUTBOUND')">保存出站凭据</el-button>
          </template>
        </div>
      </div>
    </WorkbenchPanel>

    <el-card v-if="activeTab === 'profiles'" class="workbench-list-surface" shadow="never">
      <el-table :data="profiles" v-loading="loading" stripe>
        <el-table-column label="策略" min-width="180"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.name }}</strong><small>{{ row.profileKey }} · v{{ row.version }}</small></div></template></el-table-column>
        <el-table-column label="范围" width="150"><template #default="{ row }"><div class="cell-stack"><strong>{{ a2aLabel(row.direction) }} · {{ a2aLabel(row.environment) }}</strong><small>{{ row.authorizationPolicy.publicationKeys.join(', ') || '无 Publication 权限' }}</small></div></template></el-table-column>
        <el-table-column label="信任" width="95"><template #default="{ row }"><A2aTrustLevelBadge :level="row.trustLevel" /></template></el-table-column>
        <el-table-column label="认证" min-width="150"><template #default="{ row }"><el-tag v-for="method in row.authenticationMethods" :key="method" size="small" class="inline-tag">{{ a2aLabel(method) }}</el-tag></template></el-table-column>
        <el-table-column label="容量 / 数据" min-width="175"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.rateLimitPerMinute }}/min · {{ row.maxConcurrentTasks }} 并发</strong><small>{{ row.dataPolicy.allowedDataClassifications.join(', ') }} · 保留 {{ row.dataPolicy.payloadRetentionDays }} 天</small></div></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openProfile(row)">编辑</el-button><el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="transitionProfile(row, 'DISABLED')">停用</el-button><el-button v-if="row.status === 'DISABLED'" link type="success" @click="transitionProfile(row, 'ACTIVE')">启用</el-button><el-button v-if="row.status !== 'ARCHIVED'" link type="danger" @click="transitionProfile(row, 'ARCHIVED')">归档</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-else-if="activeTab === 'principals'" class="workbench-list-surface" shadow="never">
      <el-table :data="principals" v-loading="loading" stripe>
        <el-table-column label="Principal" min-width="190"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.displayName }}</strong><small>{{ row.principalKey }} · {{ row.principalType }}</small></div></template></el-table-column>
        <el-table-column prop="tenantScope" label="租户" width="130"><template #default="{ row }">{{ row.tenantScope || '全局' }}</template></el-table-column>
        <el-table-column label="Trust / Credential" min-width="150"><template #default="{ row }"><div class="cell-stack"><strong>Trust #{{ row.trustProfileId }}</strong><small>{{ row.credentialId ? `Credential #${row.credentialId}` : '匿名开发主体' }}</small></div></template></el-table-column>
        <el-table-column label="Scopes" min-width="180"><template #default="{ row }">{{ row.scopes.join(', ') || '-' }}</template></el-table-column>
        <el-table-column label="最近认证" width="170"><template #default="{ row }">{{ a2aFormatDate(row.lastAuthenticatedAt) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="170" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="transitionPrincipal(row, 'QUARANTINED')">隔离</el-button><el-button v-if="['DISABLED','QUARANTINED'].includes(row.status)" link type="success" @click="transitionPrincipal(row, 'ACTIVE')">恢复</el-button><el-button v-if="row.status !== 'ARCHIVED'" link type="danger" @click="transitionPrincipal(row, 'ARCHIVED')">归档</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-else class="workbench-list-surface" shadow="never">
      <el-table :data="credentials" v-loading="loading" stripe>
        <el-table-column label="凭据" min-width="180"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.name }}</strong><small>{{ row.credentialKey }} · v{{ row.versionNo }}</small></div></template></el-table-column>
        <el-table-column label="方向" width="90"><template #default="{ row }"><el-tag size="small" effect="plain">{{ a2aLabel(row.direction) }}</el-tag></template></el-table-column>
        <el-table-column prop="credentialType" label="类型" width="110"><template #default="{ row }">{{ a2aLabel(row.credentialType) }}</template></el-table-column>
        <el-table-column prop="fingerprint" label="指纹" min-width="190"><template #default="{ row }"><code>{{ row.fingerprint }}</code></template></el-table-column>
        <el-table-column label="有效期" width="170"><template #default="{ row }">{{ a2aFormatDate(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="最近使用" width="170"><template #default="{ row }">{{ a2aFormatDate(row.lastUsedAt) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button v-if="['ACTIVE','GRACE'].includes(row.status)" link type="primary" @click="rotateCredential(row)">轮换</el-button><el-button v-if="['ACTIVE','GRACE'].includes(row.status)" link type="danger" @click="revokeCredential(row)">吊销</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <AppDialog v-model="profileDialogVisible" :title="editingProfileId ? '编辑 Trust Profile' : '新建 Trust Profile'" width="min(920px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="policy-form">
        <div class="form-grid"><el-form-item label="策略标识"><el-input v-model="profileForm.profileKey" :disabled="!!editingProfileId" placeholder="例如 partner-agent-dev" /></el-form-item><el-form-item label="名称"><el-input v-model="profileForm.name" /></el-form-item></div>
        <el-form-item label="说明"><el-input v-model="profileForm.description" type="textarea" :rows="2" /></el-form-item>
        <div class="form-grid form-grid--4"><el-form-item label="方向"><el-select v-model="profileForm.direction"><el-option v-for="value in ['INBOUND','OUTBOUND','BIDIRECTIONAL']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="环境"><el-select v-model="profileForm.environment"><el-option v-for="value in ['DEVELOPMENT','TEST','STAGING','PRODUCTION']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="信任等级"><el-select v-model="profileForm.trustLevel"><el-option v-for="value in ['UNTRUSTED','KNOWN','TRUSTED','PRIVILEGED']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="认证方式"><el-select v-model="profileForm.authenticationMethods" multiple @change="normalizeAuthContract"><el-option v-for="value in ['API_KEY','BEARER','HMAC','OAUTH2_CLIENT_CREDENTIALS','MTLS','ANONYMOUS']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item></div>
        <el-divider content-position="left">最小授权</el-divider>
        <div class="form-grid"><el-form-item label="Publication allowlist"><el-select v-model="profileForm.authorizationPolicy.publicationKeys" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Remote Agent allowlist"><el-select v-model="profileForm.authorizationPolicy.remoteAgentKeys" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="协议 AgentSkill allowlist"><el-select v-model="profileForm.authorizationPolicy.protocolSkillIds" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Operations"><el-select v-model="profileForm.authorizationPolicy.operations" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="租户 allowlist"><el-select v-model="profileForm.authorizationPolicy.tenantScopes" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="Principal Scopes"><el-select v-model="profileForm.allowedScopes" multiple filterable allow-create default-first-option /></el-form-item></div>
        <el-divider content-position="left">数据与容量边界</el-divider>
        <div class="form-grid form-grid--4"><el-form-item label="数据等级"><el-select v-model="profileForm.dataPolicy.allowedDataClassifications" multiple filterable allow-create default-first-option /></el-form-item><el-form-item label="正文保留（天）"><el-input-number v-model="profileForm.dataPolicy.payloadRetentionDays" :min="0" :max="3650" /></el-form-item><el-form-item label="每分钟请求"><el-input-number v-model="profileForm.rateLimitPerMinute" :min="1" /></el-form-item><el-form-item label="并发 Task"><el-input-number v-model="profileForm.maxConcurrentTasks" :min="1" /></el-form-item><el-form-item label="请求上限（bytes）"><el-input-number v-model="profileForm.maxRequestBytes" :min="1" /></el-form-item><el-form-item label="Artifact 上限（bytes）"><el-input-number v-model="profileForm.maxArtifactBytes" :min="1" /></el-form-item><el-form-item label="Task 超时（ms）"><el-input-number v-model="profileForm.taskTimeoutMs" :min="1000" /></el-form-item></div>
        <div class="switch-row"><el-checkbox v-model="profileForm.dataPolicy.allowTextParts">允许 Text Part</el-checkbox><el-checkbox v-model="profileForm.dataPolicy.allowFileParts">允许 File Part</el-checkbox><el-checkbox v-model="profileForm.dataPolicy.allowUrlParts">允许 URL Part</el-checkbox></div>
        <div class="form-grid"><el-form-item label="委派用户身份"><el-select v-model="profileForm.delegatedIdentityPolicy"><el-option label="禁止" value="DENY" /><el-option label="仅接受可验证声明" value="ATTESTED_ONLY" /></el-select></el-form-item><el-form-item label="Personal Memory"><el-select v-model="profileForm.personalMemoryPolicy"><el-option label="禁用（默认）" value="DISABLED" /><el-option label="仅验证用户" value="ATTESTED_USER_ONLY" /></el-select></el-form-item></div>
        <el-alert v-if="profileForm.authenticationMethods.includes('ANONYMOUS')" type="error" :closable="false" show-icon title="匿名仅限 DEVELOPMENT，且不能与其他认证方式混用；页面已自动收紧这些字段。" />
      </el-form>
      <template #footer><el-button @click="profileDialogVisible = false">取消</el-button><el-button type="primary" :loading="profileSubmitting" @click="saveProfile">保存策略</el-button></template>
    </AppDialog>

    <AppDialog
      v-model="credentialDialogVisible"
      :title="credentialDirection === 'OUTBOUND' ? '保存出站凭据' : '签发入站 API Key'"
      :description="credentialDirection === 'OUTBOUND' ? '远端秘密只提交一次，并使用独立 AES-GCM 密钥加密；任何 API 都不会回显。' : '平台仅保存 BCrypt 哈希与指纹；原始 Key 只在下一步显示一次。'"
      width="560px"
      :close-on-click-modal="false"
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
      <el-alert v-if="credentialDirection === 'OUTBOUND'" type="warning" :closable="false" show-icon title="不要把远端秘密复制到日志、工单、聊天或 Git。保存后只能轮换，不能读取。" />
      <template #footer><el-button @click="credentialDialogVisible = false">取消</el-button><el-button type="primary" :loading="credentialSubmitting" @click="createCredential">{{ credentialDirection === 'OUTBOUND' ? '加密保存' : '签发' }}</el-button></template>
    </AppDialog>

    <AppDialog :model-value="secretVisible" title="立即保存 API Key" description="离开此窗口后平台无法恢复原始 Key；请保存到 Vault/KMS 等企业秘密管理器。" width="680px" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" @update:model-value="(value: boolean) => { if (!value) closeSecret() }">
      <el-alert type="warning" :closable="false" show-icon title="不要把 API Key 粘贴到聊天、日志、工单或 Git。" />
      <div v-if="secretIssue" class="secret-box"><code>{{ secretIssue.apiKeyOnce }}</code><el-button type="primary" @click="copySecret">复制</el-button></div>
      <template #footer><el-button type="primary" @click="closeSecret">我已安全保存，关闭</el-button></template>
    </AppDialog>

    <AppDialog v-model="principalDialogVisible" title="创建认证 Principal" description="Principal 是凭据认证后的稳定身份，不读取请求 metadata 生成。" width="650px" :close-on-click-modal="false">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="主体标识"><el-input v-model="principalForm.principalKey" placeholder="例如 partner-alpha.agent" /></el-form-item><el-form-item label="显示名称"><el-input v-model="principalForm.displayName" /></el-form-item></div><div class="form-grid"><el-form-item label="主体类型"><el-select v-model="principalForm.principalType" @change="principalTypeChanged"><el-option label="本地 Agent（用于出站委派）" value="LOCAL_AGENT" /><el-option label="远程 Agent" value="REMOTE_AGENT" /><el-option label="远程客户端" value="REMOTE_CLIENT" /></el-select></el-form-item><el-form-item label="租户范围"><el-input v-model="principalForm.tenantScope" placeholder="留空表示全局" /></el-form-item></div><el-form-item v-if="localAgentPrincipal" label="本地 Runtime Agent"><el-select v-model="principalForm.runtimeAgentId" filterable><el-option v-for="agent in runtimeAgents" :key="agent.id" :label="`${agent.name} · ${agent.keySlug}`" :value="agent.id" /></el-select></el-form-item><el-form-item label="Trust Profile"><el-select v-model="principalForm.trustProfileId" filterable @change="principalTrustChanged"><el-option v-for="profile in principalTrustProfiles" :key="profile.id" :label="`${profile.name} · ${a2aLabel(profile.direction)} · ${a2aLabel(profile.environment)}`" :value="profile.id" /></el-select></el-form-item><el-form-item v-if="!localAgentPrincipal && selectedTrust && !selectedTrust.allowAnonymous" label="API Key 凭据"><el-select v-model="principalForm.credentialId" filterable><el-option v-for="credential in activeCredentials" :key="credential.id" :label="`${credential.name} · v${credential.versionNo} · ${credential.fingerprint.slice(0, 12)}…`" :value="credential.id" /></el-select></el-form-item><el-alert v-if="localAgentPrincipal" type="info" :closable="false" show-icon title="本地 Agent Principal 由 Runtime 通过服务间身份校验，不绑定入站凭据；只允许选择出站或双向 Trust Profile。" /><el-form-item label="Scopes"><el-select v-model="principalForm.scopes" multiple filterable allow-create default-first-option /></el-form-item></el-form>
      <template #footer><el-button @click="principalDialogVisible = false">取消</el-button><el-button type="primary" :loading="principalSubmitting" @click="savePrincipal">创建主体</el-button></template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.trust-workspace { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.section-intro, .workspace-toolbar, .toolbar-actions, .switch-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.section-intro { align-items: flex-start; }
.section-intro h2, .section-intro p { margin: 0; }
.section-intro h2 { font-size: 18px; }
.section-intro p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.workspace-tabs { min-width: 0; flex: 1; }
.workspace-tabs :deep(.el-tabs__header) { margin: 0; }
.workspace-tabs :deep(.el-tabs__content) { display: none; }
.toolbar-actions .el-input { width: 250px; }
.cell-stack strong, .cell-stack small { display: block; }
.cell-stack small { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.inline-tag { margin: 2px 4px 2px 0; }
.policy-form :deep(.el-select), .policy-form :deep(.el-input-number), .policy-form :deep(.el-date-editor), .el-dialog :deep(.el-select), .el-dialog :deep(.el-date-editor) { width: 100%; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.form-grid--4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.switch-row { justify-content: flex-start; flex-wrap: wrap; margin-bottom: 16px; }
.secret-box { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: center; margin-top: 16px; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.secret-box code { overflow-wrap: anywhere; color: var(--text-primary); font-size: 13px; }

@media (max-width: 900px) {
  .workspace-toolbar { align-items: stretch; flex-direction: column; }
  .toolbar-actions { width: 100%; flex-wrap: wrap; justify-content: flex-start; }
  .toolbar-actions .el-input { width: 100%; }
  .form-grid--4 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 600px) {
  .form-grid, .form-grid--4 { grid-template-columns: 1fr; }
}
</style>
