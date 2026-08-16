<template>
  <WorkbenchPage class="memory-erasure-page">
    <section class="page-hero glass-surface-shell">
      <div>
        <span class="eyebrow">ENTERPRISE MEMORY ERASURE</span>
        <h1>跨域记忆擦除</h1>
        <p>以一个可审计请求协调个人长期记忆、检索投影和 Runtime 会话，并收集业务源、RunOps 与备份证明。</p>
      </div>
      <el-button :loading="refreshing" :disabled="!currentRequest" @click="refreshCurrent(true)">刷新状态</el-button>
    </section>

    <el-alert
      v-if="!canManage"
      class="page-alert"
      type="error"
      :closable="false"
      show-icon
      title="当前账号没有 context:memory:erasure:manage 权限"
      description="只有获得独立全局权限的平台管理员才能创建、重试或提交擦除证据；后端会再次强制校验。"
    />
    <el-alert
      v-else
      class="page-alert"
      type="warning"
      :closable="false"
      show-icon
      title="这是跨域永久操作，不等同于“我的记忆”自助清空"
      description="原始 Runtime 用户标识只通过请求体提交，自动域完成后即从编排记录擦除。Legal Hold 不会被绕过；业务源与备份必须由各自 owner 出具证据。"
    />

    <section class="workspace-grid">
      <el-card shadow="never" class="glass-surface-shell">
        <template #header><strong>创建擦除请求</strong></template>
        <el-form label-position="top" :disabled="!canManage || creating">
          <div class="form-grid">
            <el-form-item label="租户 ID" required>
              <el-input v-model="form.tenantId" maxlength="96" placeholder="default" />
            </el-form-item>
            <el-form-item label="Runtime 用户 ID" required>
              <el-input
                v-model="form.runtimeUserId"
                type="password"
                maxlength="128"
                show-password
                autocomplete="off"
                placeholder="只在请求体和自动执行窗口内使用"
              />
            </el-form-item>
            <el-form-item label="原因码" required>
              <el-select v-model="form.reasonCode" style="width: 100%">
                <el-option label="隐私请求 (PRIVACY_REQUEST)" value="PRIVACY_REQUEST" />
                <el-option label="账号注销 (ACCOUNT_CLOSURE)" value="ACCOUNT_CLOSURE" />
                <el-option label="合同擦除 (CONTRACTUAL_ERASURE)" value="CONTRACTUAL_ERASURE" />
                <el-option label="安全响应 (SECURITY_RESPONSE)" value="SECURITY_RESPONSE" />
              </el-select>
            </el-form-item>
            <el-form-item label="审批 / 工单引用" required>
              <el-input v-model="form.referenceId" maxlength="128" placeholder="例如 CHG-2026-0042" />
            </el-form-item>
          </div>
          <el-form-item label="客户端幂等键">
            <el-input v-model="form.clientRequestId" readonly />
          </el-form-item>
          <el-button type="danger" :loading="creating" @click="createRequest">创建永久擦除请求</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="glass-surface-shell">
        <template #header><strong>查询已有请求</strong></template>
        <p class="card-description">使用变更单中保存的请求 ID 恢复操作视图。查询不会返回原始 Runtime 用户标识。</p>
        <el-input v-model="lookupRequestId" maxlength="64" placeholder="requestId" @keyup.enter="lookupRequest">
          <template #append>
            <el-button :loading="lookingUp" :disabled="!canManage" @click="lookupRequest">查询</el-button>
          </template>
        </el-input>
        <div v-if="currentRequest" class="current-summary">
          <span>当前请求</span>
          <code>{{ currentRequest.requestId }}</code>
          <el-tag :type="requestStatusType(currentRequest.status)">{{ requestStatusLabel(currentRequest.status) }}</el-tag>
        </div>
      </el-card>
    </section>

    <section v-if="currentRequest" class="result-card glass-surface-shell">
      <div class="result-header">
        <div>
          <span class="eyebrow">ERASURE REQUEST</span>
          <h2>{{ currentRequest.requestId }}</h2>
          <p>tenant {{ currentRequest.tenantId }} · owner HMAC {{ compactHash(currentRequest.runtimeUserHash) }}</p>
        </div>
        <div class="result-actions">
          <el-tag size="large" :type="requestStatusType(currentRequest.status)">
            {{ requestStatusLabel(currentRequest.status) }}
          </el-tag>
          <el-button
            v-if="canRetry(currentRequest.status)"
            type="warning"
            plain
            :loading="retrying"
            @click="retryRequest"
          >重新进入自动处理</el-button>
        </div>
      </div>

      <el-descriptions :column="3" border class="request-metadata">
        <el-descriptions-item label="原因码">{{ currentRequest.reasonCode }}</el-descriptions-item>
        <el-descriptions-item label="证据引用">{{ currentRequest.referenceId }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ currentRequest.attemptCount }}</el-descriptions-item>
        <el-descriptions-item label="自动域完成">{{ formatTime(currentRequest.automatedCompletedAt) }}</el-descriptions-item>
        <el-descriptions-item label="全部完成">{{ formatTime(currentRequest.completedAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近失败码">{{ currentRequest.lastFailureCode || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-table :data="currentRequest.domains" stripe class="domain-table">
        <el-table-column label="数据域" min-width="230">
          <template #default="{ row }">
            <strong>{{ domainLabel(row.domainCode) }}</strong>
            <small>{{ row.domainCode }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="ownerService" label="责任方" min-width="190" />
        <el-table-column label="方式" width="130">
          <template #default="{ row }">{{ row.executionMode === 'AUTOMATED' ? '自动核验' : '人工证据' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170">
          <template #default="{ row }">
            <el-tag :type="domainStatusType(row.status)">{{ domainStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果 / 失败码" min-width="200">
          <template #default="{ row }">
            <span>{{ row.resultCode || row.lastFailureCode || '-' }}</span>
            <small v-if="row.affectedCount != null">影响计数 {{ row.affectedCount }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="evidenceReference" label="证据引用" min-width="210" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canAttest(row)"
              link
              type="primary"
              @click="openEvidence(row)"
            >提交证据</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="currentRequest.status === 'ACTION_REQUIRED'"
        type="warning"
        :closable="false"
        show-icon
        title="自动域已完成，仍需四个 owner 的证据"
        description="只有全部人工域完成后请求才会进入 COMPLETED；RETAINED_LEGAL 会得到 COMPLETED_WITH_RETENTION，不会伪装为已物理删除。"
      />
    </section>

    <AppDialog v-model="evidenceVisible" title="提交责任域证据" width="540px">
      <el-alert type="info" :closable="false" show-icon :title="domainLabel(evidenceForm.domainCode)" />
      <el-form label-position="top" class="evidence-form">
        <el-form-item label="结果" required>
          <el-select v-model="evidenceForm.resultCode" style="width: 100%">
            <el-option label="已擦除 (ERASED)" value="ERASED" />
            <el-option label="不适用 (NOT_APPLICABLE)" value="NOT_APPLICABLE" />
            <el-option label="依法保留 (RETAINED_LEGAL)" value="RETAINED_LEGAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="不可变证据引用" required>
          <el-input v-model="evidenceForm.evidenceReference" maxlength="128" placeholder="例如 evidence://CHG-42/backup" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evidenceVisible = false">取消</el-button>
        <el-button type="primary" :loading="attesting" @click="submitEvidence">提交证据</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import {
  attestMemoryErasureDomain,
  createMemoryErasureRequest,
  getMemoryErasureRequest,
  retryMemoryErasureRequest,
} from '@/api/context'
import { platformSessionUser } from '@/auth/platformSession'
import type {
  MemoryErasureDomain,
  MemoryErasureDomainStatus,
  MemoryErasureEvidenceRequest,
  MemoryErasureRequest,
  MemoryErasureRequestStatus,
} from '@/types/context'

const MANAGE_PERMISSION = 'context:memory:erasure:manage'
const currentRequest = ref<MemoryErasureRequest | null>(null)
const lookupRequestId = ref('')
const creating = ref(false)
const lookingUp = ref(false)
const refreshing = ref(false)
const retrying = ref(false)
const attesting = ref(false)
const evidenceVisible = ref(false)

const form = reactive({
  tenantId: 'default',
  runtimeUserId: '',
  reasonCode: 'PRIVACY_REQUEST',
  referenceId: '',
  clientRequestId: crypto.randomUUID(),
})
const evidenceForm = reactive<{
  domainCode: string
  resultCode: MemoryErasureEvidenceRequest['resultCode']
  evidenceReference: string
}>({ domainCode: '', resultCode: 'ERASED', evidenceReference: '' })

const canManage = computed(() => {
  const permissions = platformSessionUser.value?.permissions || []
  return permissions.includes('*') || permissions.includes(MANAGE_PERMISSION)
})

async function createRequest() {
  if (!form.tenantId.trim() || !form.runtimeUserId.trim() || !form.referenceId.trim()) {
    ElMessage.warning('请填写租户、Runtime 用户和审批 / 工单引用')
    return
  }
  try {
    await ElMessageBox.prompt(
      '请求会协调九个数据域，并且不会绕过 Legal Hold。请输入“跨域永久擦除”确认。',
      '创建企业级擦除请求',
      {
        confirmButtonText: '创建请求',
        cancelButtonText: '取消',
        type: 'warning',
        inputPlaceholder: '跨域永久擦除',
        inputValidator: value => value === '跨域永久擦除' || '请输入“跨域永久擦除”',
      },
    )
  } catch {
    return
  }
  creating.value = true
  try {
    const { data } = await createMemoryErasureRequest({
      confirmation: 'ERASE_ALL_AGENT_MEMORY_DOMAINS',
      clientRequestId: form.clientRequestId,
      tenantId: form.tenantId.trim(),
      runtimeUserId: form.runtimeUserId.trim(),
      reasonCode: form.reasonCode,
      referenceId: form.referenceId.trim(),
    })
    currentRequest.value = data
    lookupRequestId.value = data.requestId
    form.runtimeUserId = ''
    ElMessage.success(data.created ? '擦除请求已创建' : '已返回同一幂等请求')
  } finally {
    creating.value = false
  }
}

async function lookupRequest() {
  if (!lookupRequestId.value.trim()) return ElMessage.warning('请输入 requestId')
  lookingUp.value = true
  try {
    const { data } = await getMemoryErasureRequest(lookupRequestId.value.trim())
    currentRequest.value = data
  } finally {
    lookingUp.value = false
  }
}

async function refreshCurrent(showMessage = false) {
  if (!currentRequest.value) return
  refreshing.value = true
  try {
    const { data } = await getMemoryErasureRequest(currentRequest.value.requestId)
    currentRequest.value = data
    if (showMessage) ElMessage.success('状态已刷新')
  } catch {
    if (showMessage) ElMessage.error('状态刷新失败')
  } finally {
    refreshing.value = false
  }
}

async function retryRequest() {
  if (!currentRequest.value) return
  try {
    await ElMessageBox.confirm('仅会重试尚未完成的自动域；Legal Hold 必须先按流程释放。是否继续？', '重试擦除请求', {
      confirmButtonText: '进入重试', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return
  }
  retrying.value = true
  try {
    currentRequest.value = (await retryMemoryErasureRequest(currentRequest.value.requestId)).data
    ElMessage.success('已进入重试队列')
  } finally {
    retrying.value = false
  }
}

function openEvidence(domain: MemoryErasureDomain) {
  evidenceForm.domainCode = domain.domainCode
  evidenceForm.resultCode = 'ERASED'
  evidenceForm.evidenceReference = ''
  evidenceVisible.value = true
}

async function submitEvidence() {
  if (!currentRequest.value || !evidenceForm.evidenceReference.trim()) {
    ElMessage.warning('请填写不可变证据引用')
    return
  }
  attesting.value = true
  try {
    currentRequest.value = (await attestMemoryErasureDomain(
      currentRequest.value.requestId,
      evidenceForm.domainCode,
      {
        resultCode: evidenceForm.resultCode,
        evidenceReference: evidenceForm.evidenceReference.trim(),
      },
    )).data
    evidenceVisible.value = false
    ElMessage.success('责任域证据已记录')
  } finally {
    attesting.value = false
  }
}

function canRetry(status: MemoryErasureRequestStatus) {
  return canManage.value && ['RETRY', 'FAILED', 'BLOCKED_LEGAL_HOLD'].includes(status)
}

function canAttest(domain: MemoryErasureDomain) {
  return canManage.value
    && Boolean(currentRequest.value?.automatedCompletedAt)
    && domain.executionMode === 'MANUAL_EVIDENCE'
    && domain.status !== 'COMPLETED'
}

const requestLabels: Record<MemoryErasureRequestStatus, string> = {
  REQUESTED: '等待处理', RUNNING: '自动处理中', RETRY: '等待重试',
  BLOCKED_LEGAL_HOLD: '被 Legal Hold 阻止', ACTION_REQUIRED: '待人工证据',
  FAILED: '处理失败', COMPLETED: '全部完成', COMPLETED_WITH_RETENTION: '完成但依法保留',
}
const domainLabels: Record<string, string> = {
  CONTROL_PERSONAL_MEMORY: '个人长期记忆',
  CONTROL_CANDIDATE_OUTBOX: '候选与投递证明',
  KNOWLEDGE_PERSONAL_PROJECTION: '个人记忆检索投影',
  RUNTIME_SESSION_STATE: 'Runtime 会话状态',
  RUNTIME_CONVERSATION_LEDGER: 'Runtime 会话账本',
  RUNOPS_TRACE_INTERACTION: 'RunOps / Trace / Interaction',
  BUSINESS_INDEX: '业务索引',
  BUSINESS_SOURCE_SYSTEM: '业务权威源系统',
  BACKUP_EXPIRY_AND_RESTORE: '备份到期与恢复追平',
}
const domainStatusLabels: Record<MemoryErasureDomainStatus, string> = {
  PENDING: '等待执行', WAITING_DEPENDENCY: '等待依赖', WAITING_EVIDENCE: '等待证据',
  BLOCKED_LEGAL_HOLD: 'Legal Hold', FAILED: '失败', COMPLETED: '完成',
}

function requestStatusLabel(status: MemoryErasureRequestStatus) { return requestLabels[status] || status }
function domainStatusLabel(status: MemoryErasureDomainStatus) { return domainStatusLabels[status] || status }
function domainLabel(code: string) { return domainLabels[code] || code }
function requestStatusType(status: MemoryErasureRequestStatus) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'COMPLETED_WITH_RETENTION' || status === 'ACTION_REQUIRED' || status === 'RETRY') return 'warning'
  if (status === 'FAILED' || status === 'BLOCKED_LEGAL_HOLD') return 'danger'
  return 'info'
}
function domainStatusType(status: MemoryErasureDomainStatus) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'BLOCKED_LEGAL_HOLD') return 'danger'
  if (status === 'WAITING_EVIDENCE' || status === 'WAITING_DEPENDENCY') return 'warning'
  return 'info'
}
function compactHash(value: string) { return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-8)}` : value }
function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}
</script>

<style scoped lang="scss">
.memory-erasure-page { color: var(--text-primary); }
.glass-surface-shell { border: 1px solid var(--border-divider); border-radius: var(--radius-lg); background: var(--bg-primary); }
.page-hero, .result-header { display: flex; justify-content: space-between; align-items: center; gap: 24px; }
.page-hero { padding: 28px 30px; }
.eyebrow { color: var(--brand-primary); font-size: 11px; font-weight: 800; letter-spacing: .14em; }
h1 { margin: 7px 0 8px; font-size: 28px; }
h2 { margin: 6px 0; font-size: 20px; overflow-wrap: anywhere; }
.page-hero p, .result-header p, .card-description { margin: 0; color: var(--text-secondary); }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(320px, .65fr); gap: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.card-description { margin-bottom: 16px; font-size: 13px; line-height: 1.7; }
.current-summary { display: grid; gap: 9px; margin-top: 22px; padding: 14px; border-radius: var(--radius-md); background: var(--bg-secondary); }
.current-summary code { overflow-wrap: anywhere; color: var(--text-primary); }
.result-card { padding: 24px; }
.result-actions { display: flex; align-items: center; gap: 10px; }
.request-metadata { margin: 18px 0; }
.domain-table { margin-bottom: 16px; }
.domain-table strong, .domain-table small { display: block; }
.domain-table small { margin-top: 4px; color: var(--text-muted); font-size: 11px; }
.evidence-form { margin-top: 16px; }
@media (max-width: 1000px) {
  .workspace-grid, .form-grid { grid-template-columns: 1fr; }
  .page-hero, .result-header { align-items: stretch; flex-direction: column; }
  .result-actions { align-items: flex-start; flex-wrap: wrap; }
}
</style>
