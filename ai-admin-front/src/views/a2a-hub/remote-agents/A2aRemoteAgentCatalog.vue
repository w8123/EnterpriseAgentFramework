<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh, Search } from '@element-plus/icons-vue'
import AgentCardPreview from '@/components/a2a-hub/AgentCardPreview.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
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

function resetDiscoverForm() {
  Object.assign(discoverForm, {
    remoteAgentKey: '',
    displayName: '',
    tenantScope: '',
    agentCardUrl: '',
  })
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
    ElMessage.warning('请填写远程 Agent 标识、显示名称和 Agent Card HTTPS URL')
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
    '重新发现远程 Agent',
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
    '批准远程 Agent 修订',
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
      '拒绝后该远程 Agent 会保持隔离。请填写不包含凭据或敏感正文的审核原因。',
      '拒绝远程 Agent 修订',
      { inputPlaceholder: '例如：提供方身份无法确认', confirmButtonText: '拒绝修订' },
    )
    submitting.value = true
    const { data } = await rejectA2aRemoteAgentRevision(agent.id, revision.id, value)
    detail.value = data
    selectedRevisionId.value = revision.id
    ElMessage.warning('修订已拒绝，远程 Agent 保持隔离')
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
  await ElMessageBox.confirm(`${action}远程 Agent 目录项？`, `${action}远程 Agent`, {
    type: 'warning',
  })
  const { data } = enabled
    ? await enableA2aRemoteAgent(agent.id)
    : await disableA2aRemoteAgent(agent.id)
  detail.value = data
  ElMessage.success(`已${action}`)
  await reload()
}

onMounted(reload)
</script>

<template>
  <section class="remote-catalog">
    <div class="section-intro">
      <div>
        <h2>远程 Agent 目录</h2>
        <p>由 Control Service 安全发现并固定外部 Agent Card；远端变化必须形成新修订并重新审核。</p>
      </div>
      <div class="section-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="resetDiscoverForm(); discoverVisible = true">
          发现远程 Agent
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="安全发现、不可变修订、认证方案、出站凭据与 Task 生命周期已经接通；调用仍必须经过 Agent 固定版本绑定。"
      description="入站 Key 与出站秘密物理分离；只允许审核确认的 Header API Key 或 Bearer，并对 message:send、tasks:get、tasks:cancel 分别授权。"
    />

    <WorkbenchPanel title="目录与治理状态" description="默认仅展示安全摘要；Agent Card 正文只在审核抽屉中查看。">
      <div class="filter-bar">
        <el-input
          v-model="query.search"
          clearable
          :prefix-icon="Search"
          placeholder="搜索标识或名称"
          @keyup.enter="applyFilters"
        />
        <el-select v-model="query.status" clearable placeholder="信任状态" @change="applyFilters">
          <el-option v-for="value in ['VERIFYING', 'REVIEW_REQUIRED', 'TRUSTED', 'DISABLED', 'QUARANTINED']" :key="value" :label="a2aLabel(value)" :value="value" />
        </el-select>
        <el-select v-model="query.health" clearable placeholder="健康状态" @change="applyFilters">
          <el-option v-for="value in ['UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNREACHABLE']" :key="value" :label="a2aLabel(value)" :value="value" />
        </el-select>
        <el-button type="primary" plain @click="applyFilters">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id" @row-click="openDetail">
        <el-table-column label="远程 Agent" min-width="220">
          <template #default="{ row }">
            <div class="identity-cell">
              <strong>{{ row.displayName }}</strong>
              <span>{{ row.remoteAgentKey }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="tenantScope" label="Tenant Scope" min-width="130">
          <template #default="{ row }">{{ row.tenantScope || '全局' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="a2aTone(row.status)" effect="plain">{{ a2aLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="健康" width="110">
          <template #default="{ row }"><el-tag :type="a2aTone(row.healthStatus)" effect="plain">{{ a2aLabel(row.healthStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="可调用" min-width="180">
          <template #default="{ row }">
            <span class="callability" :class="{ ready: row.callable }">{{ row.callable ? '可调用' : callabilityLabel(row.callabilityCode) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近发现" width="180">
          <template #default="{ row }">{{ a2aFormatDate(row.lastDiscoveredAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">审核</el-button></template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <span>共 {{ total }} 个远程 Agent</span>
        <el-pagination
          :current-page="Math.floor(query.offset / query.limit) + 1"
          :page-size="query.limit"
          :total="total"
          layout="prev, pager, next"
          @current-change="(page: number) => { query.offset = (page - 1) * query.limit; reload() }"
        />
      </div>
    </WorkbenchPanel>

    <AppDialog
      v-model="discoverVisible"
      title="发现远程 Agent"
      description="只接受 HTTPS Agent Card URL。服务端不会跟随重定向，并会拒绝私网、回环、链路本地、元数据和文档保留地址。"
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
        <div class="security-boundary">
          <el-icon><Connection /></el-icon>
          <span>URL 由 Control Service 拉取；浏览器不会直连远端，也不会携带平台登录态或入站凭据。</span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="discoverVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDiscovery">验证并创建目录项</el-button>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="detailVisible"
      title="远程 Agent 审核"
      description="审阅远端声明、网络验证证据和修订历史，再显式固定接口与 Trust Profile。"
      size="min(92vw, 1180px)"
    >
      <div v-loading="detailLoading" class="detail-shell">
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
          </div>

          <el-alert
            v-if="pendingRevision"
            type="warning"
            :closable="false"
            show-icon
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
              <el-empty v-if="detail.revisions.length === 0" :image-size="48" description="尚无可审核修订" />
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
            <el-empty v-else description="选择一个修订查看审核材料" />
          </div>
        </template>
      </div>
    </AppDrawer>
  </section>
</template>

<style scoped lang="scss">
.remote-catalog { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.section-intro, .section-actions, .detail-header, .title-line, .revision-title, .tag-cluster, .approval-actions { display: flex; align-items: center; gap: 10px; }
.section-intro, .detail-header, .revision-title { justify-content: space-between; align-items: flex-start; }
.section-intro h2, .section-intro p, .detail-header h3, .detail-header p, .revision-title h4, .revision-title p { margin: 0; }
.section-intro h2 { font-size: 18px; }
.section-intro p, .detail-header p, .revision-title p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.filter-bar { display: grid; grid-template-columns: minmax(220px, 1fr) 170px 150px auto; gap: 10px; margin-bottom: 14px; }
.identity-cell strong, .identity-cell span { display: block; }
.identity-cell strong { color: var(--text-primary); }
.identity-cell span { margin-top: 3px; color: var(--text-muted); font: 11px ui-monospace, SFMono-Regular, Consolas, monospace; }
.callability { color: var(--status-warning); font-size: 12px; }
.callability.ready { color: var(--status-success); }
.pagination-row { display: flex; justify-content: space-between; align-items: center; margin-top: 14px; color: var(--text-muted); font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.security-boundary { display: flex; gap: 10px; align-items: flex-start; padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--text-secondary); background: var(--surface-glass-control); line-height: 1.6; font-size: 12px; }
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
.evidence-grid pre { max-height: 220px; margin: 0; overflow: auto; color: var(--text-secondary); font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; }
.hash-line { display: grid; gap: 5px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border-divider); color: var(--text-muted); font-size: 11px; }
@media (max-width: 980px) { .summary-grid, .claim-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .evidence-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .section-intro, .detail-header { flex-direction: column; } .filter-bar, .form-grid, .review-layout { grid-template-columns: 1fr; } .revision-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
