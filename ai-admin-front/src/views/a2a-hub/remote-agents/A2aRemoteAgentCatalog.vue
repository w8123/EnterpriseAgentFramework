<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import AgentCardPreview from '@/components/a2a-hub/AgentCardPreview.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassInput, LiquidGlassNotice, LiquidGlassSelect, LiquidGlassTable } from '@/components/common/liquid-glass'
import A2aListToolbar from '../A2aListToolbar.vue'
import {
  approveA2aRemoteAgentRevision,
  disableA2aRemoteAgent,
  discoverA2aRemoteAgent,
  enableA2aRemoteAgent,
  getA2aRemoteAgent,
  listA2aCredentials,
  listA2aRemoteAgents,
  listA2aTrustProfiles,
  rediscoverA2aRemoteAgent,
  rejectA2aRemoteAgentRevision,
} from '@/api/a2aHub'
import type {
  A2aRemoteAgent,
  A2aRemoteAgentDetail,
  A2aRemoteAgentDiscoverRequest,
  A2aRemoteAgentRevision,
  A2aCredential,
  A2aTrustProfile,
} from '@/types/a2aHub'
import { a2aFormatDate, a2aLabel, a2aTone } from '@/utils/a2aHub'
import { usePlatformCan } from '@/auth/usePlatformCan'

const route = useRoute()
const router = useRouter()
const { can } = usePlatformCan()
const loading = ref(false)
const submitting = ref(false)
const rows = ref<A2aRemoteAgent[]>([])
const total = ref(0)
const query = reactive({ search: '', status: '', health: '', limit: 20, offset: 0 })

const discoverVisible = ref(false)
const discoverForm = reactive<A2aRemoteAgentDiscoverRequest>({
  remoteAgentKey: '',
  displayName: '',
  tenantScope: '',
  agentCardUrl: '',
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<A2aRemoteAgentDetail | null>(null)
const trustProfiles = ref<A2aTrustProfile[]>([])
const credentials = ref<A2aCredential[]>([])
const selectedRevisionId = ref<number | null>(null)
const reviewForm = reactive<{
  preferredInterfaceKey: string
  preferredSecuritySchemeKey: string
  trustProfileId?: number
  credentialId?: number
}>({
  preferredInterfaceKey: '',
  preferredSecuritySchemeKey: '',
  trustProfileId: undefined,
  credentialId: undefined,
})

const selectedRevision = computed<A2aRemoteAgentRevision | null>(() =>
  detail.value?.revisions.find((item) => item.id === selectedRevisionId.value) ?? null,
)
const pendingRevision = computed(() =>
  detail.value?.revisions.find((item) => item.reviewStatus === 'PENDING') ?? null,
)
const activeOutboundTrustProfiles = computed(() => trustProfiles.value.filter((item) =>
  item.status === 'ACTIVE' && ['OUTBOUND', 'BIDIRECTIONAL'].includes(item.direction),
))
const selectedAuthenticationOption = computed(() => pendingRevision.value?.authenticationOptions.find(
  (item) => item.securitySchemeKey === reviewForm.preferredSecuritySchemeKey,
) ?? null)
const compatibleOutboundCredentials = computed(() => credentials.value.filter((item) =>
  item.direction === 'OUTBOUND'
    && ['ACTIVE', 'GRACE'].includes(item.status)
    && item.credentialType === selectedAuthenticationOption.value?.credentialType,
))
const formattedNetworkEvidence = computed(() => {
  const raw = selectedRevision.value?.networkEvidenceJson
  if (!raw) return '-'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

function callabilityLabel(code: string) {
  const labels: Record<string, string> = {
    REMOTE_AGENT_NOT_TRUSTED: '需先完成修订审核',
    APPROVED_REVISION_NOT_SELECTED: '尚未固定可调用修订',
    REMOTE_AGENT_UNREACHABLE: '最近健康检查不可达',
  }
  return labels[code] ?? code
}

function remoteStatusLabel(agent: A2aRemoteAgent): string {
  if (agent.status !== 'TRUSTED') return a2aLabel(agent.status)
  if (agent.healthStatus === 'UNREACHABLE') return '不可达'
  if (!agent.callable) {
    const label = callabilityLabel(agent.callabilityCode)
    return label === agent.callabilityCode ? '暂不可调用' : label
  }
  return agent.healthStatus === 'DEGRADED' ? '健康降级' : '可调用'
}

async function reload() {
  loading.value = true
  try {
    const { data } = await listA2aRemoteAgents({
      search: query.search || undefined,
      status: query.status || undefined,
      health: query.health || undefined,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  query.offset = 0
  reload()
}

function changePage(page: number, size: number) {
  query.limit = size
  query.offset = (page - 1) * size
  reload()
}

function resetDiscoverForm() {
  Object.assign(discoverForm, {
    remoteAgentKey: '',
    displayName: '',
    tenantScope: '',
    agentCardUrl: '',
  })
}

function openDiscover() {
  if (!can('a2a-hub:remote-agent:manage')) return
  resetDiscoverForm()
  discoverVisible.value = true
}

async function openDetail(row: A2aRemoteAgent | number) {
  const id = typeof row === 'number' ? row : row.id
  detailVisible.value = true
  detailLoading.value = true
  try {
    const [detailResult, trustResult, credentialResult] = await Promise.all([
      getA2aRemoteAgent(id),
      listA2aTrustProfiles({ status: 'ACTIVE', limit: 100 }),
      listA2aCredentials({ limit: 100 }),
    ])
    detail.value = detailResult.data
    trustProfiles.value = trustResult.data?.items ?? []
    credentials.value = credentialResult.data?.items ?? []
    selectedRevisionId.value = detail.value.revisions[0]?.id ?? null
    prepareReview(detail.value.revisions.find((item) => item.reviewStatus === 'PENDING'))
  } finally {
    detailLoading.value = false
  }
}

function prepareReview(revision?: A2aRemoteAgentRevision) {
  reviewForm.preferredInterfaceKey = revision?.supportedInterfaces.find((item) =>
    item.protocolBinding.toUpperCase() === 'HTTP+JSON' && item.protocolVersion === '1.0',
  )?.interfaceKey ?? ''
  reviewForm.preferredSecuritySchemeKey = revision?.authenticationRequired
    ? revision.authenticationOptions.find((item) => item.selectable)?.securitySchemeKey ?? ''
    : ''
  reviewForm.trustProfileId = undefined
  reviewForm.credentialId = undefined
}

async function submitDiscovery() {
  if (!discoverForm.remoteAgentKey.trim() || !discoverForm.displayName.trim()
    || !discoverForm.agentCardUrl.trim()) {
    ElMessage.warning('请填写外部 Agent 标识、显示名称和 Agent Card HTTPS URL')
    return
  }
  submitting.value = true
  try {
    const { data } = await discoverA2aRemoteAgent({
      remoteAgentKey: discoverForm.remoteAgentKey.trim(),
      displayName: discoverForm.displayName.trim(),
      tenantScope: discoverForm.tenantScope?.trim(),
      agentCardUrl: discoverForm.agentCardUrl.trim(),
    })
    discoverVisible.value = false
    ElMessage[data.outcome === 'QUARANTINED' ? 'warning' : 'success'](
      data.outcome === 'QUARANTINED'
        ? `发现记录已隔离：${data.reasonCode ?? '验证失败'}`
        : data.outcome === 'UNCHANGED' ? 'Agent Card 未发生变化' : '发现完成，请审核新修订',
    )
    await reload()
    await openDetail(data.detail.remoteAgent.id)
  } finally {
    submitting.value = false
  }
}

async function rediscover() {
  const agent = detail.value?.remoteAgent
  if (!agent) return
  await ElMessageBox.confirm(
    '系统将从服务端重新执行 DNS/IP/TLS 校验并拉取 Agent Card。远端内容变化不会自动获得信任。',
    '重新发现外部 Agent',
    { type: 'warning', confirmButtonText: '开始验证' },
  )
  submitting.value = true
  try {
    const { data } = await rediscoverA2aRemoteAgent(agent.id)
    detail.value = data.detail
    selectedRevisionId.value = data.detail.revisions[0]?.id ?? null
    prepareReview(data.detail.revisions.find((item) => item.reviewStatus === 'PENDING'))
    ElMessage[data.outcome === 'QUARANTINED' ? 'warning' : 'success'](
      data.outcome === 'QUARANTINED'
        ? `验证失败并已隔离：${data.reasonCode ?? '未知原因'}`
        : data.outcome === 'UNCHANGED' ? '验证通过，Agent Card 未变化' : '发现新修订，等待审核',
    )
    await reload()
  } finally {
    submitting.value = false
  }
}

async function approveRevision() {
  const agent = detail.value?.remoteAgent
  const revision = pendingRevision.value
  if (!agent || !revision || !reviewForm.preferredInterfaceKey || !reviewForm.trustProfileId) {
    ElMessage.warning('请选择 A2A 1.0 HTTP+JSON 接口和出站 Trust Profile')
    return
  }
  if (revision.authenticationRequired && !reviewForm.preferredSecuritySchemeKey) {
    ElMessage.warning('该 Agent Card 要求认证，但没有选择可支持的认证方案')
    return
  }
  if (reviewForm.preferredSecuritySchemeKey && !reviewForm.credentialId) {
    ElMessage.warning('请选择与认证方案类型匹配的有效出站凭据')
    return
  }
  await ElMessageBox.confirm(
    '批准后会固定不可变修订、HTTP+JSON 接口、认证方案、出站凭据与 Trust Profile。远端调用仍由独立调用门禁控制。',
    '批准外部 Agent 修订',
    { type: 'warning', confirmButtonText: '批准并固定' },
  )
  submitting.value = true
  try {
    const { data } = await approveA2aRemoteAgentRevision(agent.id, revision.id, {
      preferredInterfaceKey: reviewForm.preferredInterfaceKey,
      preferredSecuritySchemeKey: reviewForm.preferredSecuritySchemeKey || undefined,
      trustProfileId: reviewForm.trustProfileId,
      credentialId: reviewForm.credentialId,
    })
    detail.value = data
    selectedRevisionId.value = revision.id
    ElMessage.success('修订已批准并固定；发布到本地 Agent 配置后即可受控调用')
    await reload()
  } finally {
    submitting.value = false
  }
}

async function rejectRevision() {
  const agent = detail.value?.remoteAgent
  const revision = pendingRevision.value
  if (!agent || !revision) return
  try {
    const { value } = await ElMessageBox.prompt(
      '拒绝后该外部 Agent 会保持隔离。请填写不包含凭据或敏感正文的审核原因。',
      '拒绝外部 Agent 修订',
      { inputPlaceholder: '例如：提供方身份无法确认', confirmButtonText: '拒绝修订' },
    )
    submitting.value = true
    const { data } = await rejectA2aRemoteAgentRevision(agent.id, revision.id, value)
    detail.value = data
    selectedRevisionId.value = revision.id
    ElMessage.warning('修订已拒绝，外部 Agent 保持隔离')
    await reload()
  } catch {
    // Dialog cancellation is not an error.
  } finally {
    submitting.value = false
  }
}

async function changeEnabled(enabled: boolean) {
  const agent = detail.value?.remoteAgent
  if (!agent) return
  const action = enabled ? '启用' : '停用'
  await ElMessageBox.confirm(`${action}外部 Agent 目录项？`, `${action}外部 Agent`, {
    type: 'warning',
  })
  const { data } = enabled
    ? await enableA2aRemoteAgent(agent.id)
    : await disableA2aRemoteAgent(agent.id)
  detail.value = data
  ElMessage.success(`已${action}`)
  await reload()
}

watch(() => route.query.action, async (action) => {
  if (action !== 'create') return
  const nextQuery = { ...route.query }
  delete nextQuery.action
  await router.replace({ query: nextQuery })
  openDiscover()
}, { immediate: true })

onMounted(reload)
</script>

<template>
<section class="remote-catalog" aria-label="外部 Agent">
    <LiquidGlassTable
      class="workbench-list-surface"
      :current-page="Math.floor(query.offset / query.limit) + 1"
      :page-size="query.limit"
      :total="total"
      :show-pagination="total > query.limit"
      :page-sizes="[20, 50, 100]"
      pagination-label="外部 Agent 分页"
      :loading="loading"
      :empty="!loading && rows.length === 0"
      empty-description="暂无外部 Agent"
      @change="changePage"
    >
      <template #toolbar>
        <A2aListToolbar :loading="loading" @query="applyFilters" @refresh="reload">
          <LiquidGlassInput v-model="query.search" clearable :prefix-icon="Search" placeholder="搜索名称或标识" aria-label="搜索外部 Agent" @clear="applyFilters" />
          <LiquidGlassSelect v-model="query.status" class="short-filter" clearable placeholder="信任状态" dropdown-title="信任状态" aria-label="信任状态" @change="applyFilters">
            <el-option v-for="value in ['VERIFYING', 'REVIEW_REQUIRED', 'TRUSTED', 'DISABLED', 'QUARANTINED']" :key="value" :label="a2aLabel(value)" :value="value" />
          </LiquidGlassSelect>
          <LiquidGlassSelect v-model="query.health" class="short-filter" clearable placeholder="健康状态" dropdown-title="健康状态" aria-label="健康状态" @change="applyFilters">
            <el-option v-for="value in ['UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNREACHABLE']" :key="value" :label="a2aLabel(value)" :value="value" />
          </LiquidGlassSelect>
          <template #actions>
            <LiquidGlassButton v-if="can('a2a-hub:remote-agent:manage')" type="primary" :icon="Plus" @click="openDiscover">接入外部 Agent</LiquidGlassButton>
          </template>
        </A2aListToolbar>
      </template>
      <el-table :data="rows" row-key="id" empty-text=" " @row-click="openDetail">
        <el-table-column label="外部 Agent" min-width="260" show-overflow-tooltip>
          <template #default="{ row }"><div class="identity-cell"><strong>{{ row.displayName }}</strong><span>{{ row.remoteAgentKey }}</span></div></template>
        </el-table-column>
        <el-table-column label="状态" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status !== 'TRUSTED' ? a2aTone(row.status) : row.callable && !['DEGRADED', 'UNREACHABLE'].includes(row.healthStatus) ? 'success' : 'warning'"
              :title="'信任：' + a2aLabel(row.status) + '；健康：' + a2aLabel(row.healthStatus)">
              {{ remoteStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近检查" width="180"><template #default="{ row }">{{ a2aFormatDate(row.lastHealthCheckedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">{{ row.status === 'REVIEW_REQUIRED' ? '审核' : '查看' }}</el-button></template></el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog
      v-model="discoverVisible"
      title="接入外部 Agent"
      description="填写服务方提供的 HTTPS Agent Card 地址。审核通过后，在本地 Agent 的协作配置中绑定使用。"
      width="640px"
      destroy-on-close
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="稳定标识" required>
            <el-input v-model="discoverForm.remoteAgentKey" placeholder="remote-reviewer" />
          </el-form-item>
          <el-form-item label="显示名称" required>
            <el-input v-model="discoverForm.displayName" placeholder="远程审核 Agent" />
          </el-form-item>
        </div>
        <el-form-item label="Tenant Scope">
          <el-input v-model="discoverForm.tenantScope" placeholder="留空表示全局目录" />
        </el-form-item>
        <el-form-item label="Agent Card URL" required>
          <el-input v-model="discoverForm.agentCardUrl" placeholder="https://agent.example.com/.well-known/agent-card.json" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="discoverVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="submitting" @click="submitDiscovery">验证并创建目录项</LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="detailVisible"
      title="外部 Agent 审核"
      description="审阅远端声明、网络验证证据和修订历史，再显式固定接口与 Trust Profile。"
      size="min(92vw, 1180px)"
    >
      <div v-loading="detailLoading" class="detail-shell glass-loading-surface">
        <template v-if="detail">
          <div class="detail-header">
            <div>
              <div class="title-line">
                <h3>{{ detail.remoteAgent.displayName }}</h3>
                <el-tag :type="a2aTone(detail.remoteAgent.status)" effect="plain">{{ a2aLabel(detail.remoteAgent.status) }}</el-tag>
                <el-tag :type="a2aTone(detail.remoteAgent.healthStatus)" effect="plain">{{ a2aLabel(detail.remoteAgent.healthStatus) }}</el-tag>
              </div>
              <p>{{ detail.remoteAgent.remoteAgentKey }} · {{ detail.remoteAgent.tenantScope || '全局目录' }}</p>
            </div>
            <div class="section-actions">
              <el-button :loading="submitting" :disabled="detail.remoteAgent.status === 'VERIFYING'" :icon="Refresh" @click="rediscover">重新发现</el-button>
              <el-button v-if="detail.remoteAgent.status === 'TRUSTED'" type="danger" plain @click="changeEnabled(false)">停用</el-button>
              <el-button v-if="detail.remoteAgent.status === 'DISABLED'" type="primary" plain @click="changeEnabled(true)">启用</el-button>
            </div>
          </div>

          <div class="summary-grid">
            <div><span>Agent Card URL</span><strong class="break-all">{{ detail.remoteAgent.agentCardUrl }}</strong></div>
            <div><span>当前固定修订</span><strong>{{ detail.remoteAgent.currentRevisionId ?? '未固定' }}</strong></div>
            <div><span>固定认证方案</span><strong>{{ detail.remoteAgent.preferredSecuritySchemeKey || '匿名 / 未固定' }}</strong></div>
            <div><span>调用门禁</span><strong>{{ callabilityLabel(detail.remoteAgent.callabilityCode) }}</strong></div>
            <div><span>最后结果</span><strong>{{ detail.remoteAgent.lastHealthSummary || '-' }}</strong></div>
            <div><span>最近发现</span><strong>{{ a2aFormatDate(detail.remoteAgent.lastDiscoveredAt) }}</strong></div>
            <div><span>最近检查</span><strong>{{ a2aFormatDate(detail.remoteAgent.lastHealthCheckedAt) }}</strong></div>
          </div>

          <LiquidGlassNotice
            v-if="pendingRevision"
            type="warning"
            title="检测到待审核 Agent Card 修订"
            description="审核建立目录信任并固定接口、认证与凭据；还需在本地 Agent 的发布配置中显式绑定后才可调用。"
          />

          <div class="review-layout">
            <aside class="revision-list">
              <button
                v-for="revision in detail.revisions"
                :key="revision.id"
                type="button"
                :class="{ active: revision.id === selectedRevisionId }"
                @click="selectedRevisionId = revision.id; prepareReview(revision.reviewStatus === 'PENDING' ? revision : undefined)"
              >
                <span><strong>修订 {{ revision.revisionNo }}</strong><el-tag size="small" :type="a2aTone(revision.reviewStatus)" effect="plain">{{ a2aLabel(revision.reviewStatus) }}</el-tag></span>
                <small>{{ revision.agentVersion }} · {{ a2aFormatDate(revision.discoveredAt) }}</small>
                <code>{{ revision.agentCardSha256.slice(0, 16) }}…</code>
              </button>
              <LiquidGlassEmptyState v-if="detail.revisions.length === 0" title="尚无可审核修订" />
            </aside>

            <div v-if="selectedRevision" class="revision-detail">
              <div class="revision-title">
                <div><h4>{{ selectedRevision.name }}</h4><p>{{ selectedRevision.description }}</p></div>
                <div class="tag-cluster">
                  <el-tag effect="plain">A2A {{ selectedRevision.supportedInterfaces[0]?.protocolVersion }}</el-tag>
                  <el-tag :type="selectedRevision.authenticationRequired ? 'warning' : 'info'" effect="plain">{{ selectedRevision.authenticationRequired ? '需要认证' : '未声明认证' }}</el-tag>
                  <el-tag :type="selectedRevision.signatureStatus === 'VERIFIED' ? 'success' : 'warning'" effect="plain">签名 {{ selectedRevision.signatureStatus }}</el-tag>
                </div>
              </div>

              <div class="interfaces">
                <h5>认证兼容性</h5>
                <div v-if="selectedRevision.authenticationOptions.length === 0" class="interface-row">
                  <span><strong>{{ selectedRevision.anonymousAccessAllowed ? '无需远端认证' : '没有可选认证声明' }}</strong><small>{{ selectedRevision.authenticationSupportCode }}</small></span>
                </div>
                <div v-for="option in selectedRevision.authenticationOptions" :key="option.securitySchemeKey" class="interface-row">
                  <span>
                    <strong>{{ option.securitySchemeKey }} · {{ option.schemeType }}</strong>
                    <small>{{ option.selectable ? `${option.placement} ${option.headerName || ''}` : option.supportCode }}</small>
                  </span>
                  <el-tag :type="option.selectable ? 'success' : 'danger'" size="small" effect="plain">{{ option.selectable ? '首版支持' : '阻断' }}</el-tag>
                </div>
              </div>

              <div class="claim-grid">
                <div><span>提供方</span><strong>{{ selectedRevision.providerOrganization || '-' }}</strong></div>
                <div><span>输入媒体</span><strong>{{ selectedRevision.defaultInputModes.join(', ') }}</strong></div>
                <div><span>输出媒体</span><strong>{{ selectedRevision.defaultOutputModes.join(', ') }}</strong></div>
                <div><span>协议 AgentSkill</span><strong>{{ selectedRevision.protocolSkills.length }}</strong></div>
              </div>

              <div class="interfaces">
                <h5>已验证接口</h5>
                <div v-for="item in selectedRevision.supportedInterfaces" :key="item.interfaceKey" class="interface-row">
                  <span><strong>{{ item.protocolBinding }} · {{ item.protocolVersion }}</strong><small>{{ item.url }}</small></span>
                  <code>{{ item.interfaceKey }}</code>
                </div>
              </div>

              <div v-if="selectedRevision.reviewStatus === 'PENDING'" class="approval-box">
                <h5>人工审核决策</h5>
                <div class="form-grid">
                  <el-form-item label="固定接口">
                    <el-select v-model="reviewForm.preferredInterfaceKey" placeholder="选择受支持接口">
                      <el-option
                        v-for="item in selectedRevision.supportedInterfaces.filter((candidate) => candidate.protocolBinding.toUpperCase() === 'HTTP+JSON' && candidate.protocolVersion === '1.0')"
                        :key="item.interfaceKey"
                        :label="`${item.protocolBinding} ${item.protocolVersion} · ${item.url}`"
                        :value="item.interfaceKey"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="出站 Trust Profile">
                    <el-select v-model="reviewForm.trustProfileId" placeholder="选择 ACTIVE 出站策略">
                      <el-option v-for="profile in activeOutboundTrustProfiles" :key="profile.id" :label="`${profile.name} · ${profile.environment}`" :value="profile.id" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="远端认证方案">
                    <el-select v-model="reviewForm.preferredSecuritySchemeKey" :disabled="selectedRevision.authenticationOptions.every((item) => !item.selectable)" placeholder="选择 Agent Card 声明的方案" @change="reviewForm.credentialId = undefined">
                      <el-option v-if="selectedRevision.anonymousAccessAllowed" label="无需认证（Agent Card 明确允许）" value="" />
                      <el-option v-for="option in selectedRevision.authenticationOptions.filter((item) => item.selectable)" :key="option.securitySchemeKey" :label="`${option.securitySchemeKey} · ${option.schemeType}`" :value="option.securitySchemeKey" />
                    </el-select>
                  </el-form-item>
                  <el-form-item v-if="reviewForm.preferredSecuritySchemeKey" label="出站凭据">
                    <el-select v-model="reviewForm.credentialId" placeholder="选择类型匹配的加密凭据">
                      <el-option v-for="credential in compatibleOutboundCredentials" :key="credential.id" :label="`${credential.name} · ${credential.credentialType} · v${credential.versionNo}`" :value="credential.id" />
                    </el-select>
                  </el-form-item>
                </div>
                <p>原始秘密不会进入 Agent Card、审核记录或响应。当前仅支持安全 Header API Key 与 Bearer；OAuth2、OIDC、mTLS、Cookie/Query Key 和组合认证会被明确阻断。</p>
                <div class="approval-actions">
                  <el-button type="danger" plain :loading="submitting" @click="rejectRevision">拒绝并隔离</el-button>
                  <el-button type="primary" :loading="submitting" @click="approveRevision">批准并固定修订</el-button>
                </div>
              </div>

              <div class="evidence-grid">
                <WorkbenchPanel title="网络验证证据" description="只保留无 host、IP 和证书正文的安全摘要。">
                  <pre>{{ formattedNetworkEvidence }}</pre>
                  <div class="hash-line"><span>TLS 证书 SHA-256</span><code>{{ selectedRevision.tlsIdentitySha256 || '-' }}</code></div>
                </WorkbenchPanel>
                <WorkbenchPanel title="Agent Card 快照" description="规范化 JSON 与内容哈希共同定义不可变修订。">
                  <AgentCardPreview :revision="selectedRevision" />
                </WorkbenchPanel>
              </div>
            </div>
            <LiquidGlassEmptyState v-else title="选择一个修订查看审核材料" />
          </div>
        </template>
      </div>
    </AppDrawer>
  </section>
</template>

<style scoped lang="scss">
.remote-catalog { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.section-actions, .detail-header, .title-line, .revision-title, .tag-cluster, .approval-actions { display: flex; align-items: center; gap: 10px; }
.detail-header, .revision-title { justify-content: space-between; align-items: flex-start; }
.section-actions { flex-wrap: wrap; }
.detail-header h3, .detail-header p, .revision-title h4, .revision-title p { margin: 0; }
.detail-header p, .revision-title p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
/* 状态是短值，筛选条里收窄，把宽度留给搜索框。 */
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.short-filter) { flex: 0 1 160px; max-width: 180px; }
.identity-cell strong, .identity-cell span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.identity-cell strong { color: var(--text-primary); }
.identity-cell span { margin-top: 3px; color: var(--text-muted); font: 11px ui-monospace, SFMono-Regular, Consolas, monospace; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.detail-shell { min-height: 240px; }
.summary-grid, .claim-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 16px 0; }
.summary-grid > div, .claim-grid > div { min-width: 0; padding: 10px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.summary-grid span, .summary-grid strong, .claim-grid span, .claim-grid strong { display: block; }
.summary-grid span, .claim-grid span { color: var(--text-muted); font-size: 11px; }
.summary-grid strong, .claim-grid strong { margin-top: 5px; color: var(--text-primary); font-size: 12px; line-height: 1.5; }
.break-all { word-break: break-all; }
.review-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 16px; margin-top: 16px; }
.revision-list { display: flex; flex-direction: column; gap: 8px; }
.revision-list button { width: 100%; padding: 10px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); text-align: left; color: var(--text-secondary); background: var(--surface-glass-control); cursor: pointer; }
.revision-list button.active { border-color: var(--brand-active); background: var(--surface-glass-selected); }
.revision-list button > span { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.revision-list small, .revision-list code { display: block; margin-top: 5px; color: var(--text-muted); font-size: 10px; }
.revision-detail { min-width: 0; }
.tag-cluster { flex-wrap: wrap; justify-content: flex-end; }
.interfaces { margin-top: 14px; }
.interfaces h5, .approval-box h5 { margin: 0 0 10px; color: var(--text-primary); font-size: 13px; }
.interface-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 10px; border-top: 1px solid var(--border-divider); }
.interface-row strong, .interface-row small { display: block; }
.interface-row small { margin-top: 4px; color: var(--text-muted); word-break: break-all; }
.interface-row code, .hash-line code { color: var(--text-muted); font-size: 10px; word-break: break-all; }
.approval-box { margin-top: 16px; padding: 14px; border: 1px solid var(--status-warning); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.approval-box p { margin: 0; color: var(--text-muted); font-size: 12px; line-height: 1.6; }
.approval-actions { justify-content: flex-end; margin-top: 12px; }
.evidence-grid { display: grid; grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr); gap: 14px; margin-top: 16px; }
.evidence-grid pre { max-height: 220px; margin: 0; overflow: auto; padding: 10px; border-radius: var(--radius-md); color: var(--code-text); background: var(--code-surface); font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; }
.hash-line { display: grid; gap: 5px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border-divider); color: var(--text-muted); font-size: 11px; }
@media (max-width: 980px) { .summary-grid, .claim-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .evidence-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .detail-header { flex-direction: column; } .form-grid, .review-layout { grid-template-columns: 1fr; } .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.short-filter) { max-width: none; } .revision-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
