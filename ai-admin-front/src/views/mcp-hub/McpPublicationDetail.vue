<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, DocumentCopy, Plus, Refresh } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import {
  addMcpPublicationItem,
  archiveMcpPublication,
  createMcpClient,
  getMcpPublication,
  precheckMcpPublication,
  publishMcpPublication,
  removeMcpPublicationItem,
  resolveMcpPublicationPreview,
  resumeMcpPublication,
  revokeMcpClient,
  rollbackMcpPublication,
  rotateMcpClient,
  suspendMcpPublication,
  updateMcpClient,
  updateMcpPublication,
} from '@/api/mcp'
import { listAllTools } from '@/api/tool'
import { listToolAclRoles } from '@/api/toolAcl'
import { listWorkflows } from '@/api/workflow'
import type { ToolInfo } from '@/types/tool'
import type { WorkflowWorkingCopy } from '@/types/workflow'
import type {
  McpClientView,
  McpItemKind,
  McpPrecheckReport,
  McpPublicationDetail,
  McpPublicationRevisionView,
  McpResolvePreviewRow,
  McpRiskLevel,
  McpRiskSummary,
} from '@/types/mcp'

const route = useRoute()
const router = useRouter()
const publicationId = computed(() => Number(route.params.id))

const loading = ref(false)
const detail = ref<McpPublicationDetail | null>(null)
const activeTab = ref('items')

const stateLabels: Record<string, string> = {
  DRAFT: '草稿',
  VALIDATING: '校验中',
  READY: '预检通过',
  PUBLISHED: '已发布',
  SUSPENDED: '已暂停',
  ARCHIVED: '已归档',
}

const stateTones: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  VALIDATING: 'warning',
  READY: 'success',
  PUBLISHED: 'success',
  SUSPENDED: 'warning',
  ARCHIVED: 'danger',
}

const clientStateLabels: Record<string, string> = {
  ACTIVE: '有效',
  ROTATED: '已轮换',
  REVOKED: '已吊销',
  EXPIRED: '已过期',
}

const riskLabels: Record<string, string> = {
  READ: '只读',
  WRITE: '写入',
  PAGE_ACTION: '页面操作',
  IRREVERSIBLE: '不可逆',
  UNKNOWN: '未知风险',
}

const riskTones: Record<string, 'info' | 'warning' | 'danger'> = {
  READ: 'info',
  WRITE: 'warning',
  PAGE_ACTION: 'warning',
  IRREVERSIBLE: 'danger',
  UNKNOWN: 'info',
}

function stateLabel(state?: string): string {
  return state ? (stateLabels[state] ?? state) : '-'
}

function stateTone(state?: string) {
  return state ? (stateTones[state] ?? 'info') : 'info'
}

function riskLabel(level?: string | null): string {
  return level ? (riskLabels[level] ?? level) : '未评估'
}

function riskTone(level?: string | null) {
  return level ? (riskTones[level] ?? 'info') : 'info'
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

function formatJson(value: unknown): string {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function parseRiskSummary(json?: string | null): McpRiskSummary {
  if (!json) return { read: 0, write: 0, page_action: 0, irreversible: 0, unknown: 0 }
  try {
    const parsed = JSON.parse(json) as Partial<McpRiskSummary>
    return {
      read: Number(parsed.read) || 0,
      write: Number(parsed.write) || 0,
      page_action: Number(parsed.page_action) || 0,
      irreversible: Number(parsed.irreversible) || 0,
      unknown: Number(parsed.unknown) || 0,
    }
  } catch {
    return { read: 0, write: 0, page_action: 0, irreversible: 0, unknown: 0 }
  }
}

function extractErrorMessage(error: unknown): string {
  const anyError = error as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return anyError?.response?.data?.message
    || anyError?.response?.data?.detail
    || anyError?.message
    || '请求失败'
}

async function reload() {
  loading.value = true
  try {
    const { data } = await getMcpPublication(publicationId.value)
    detail.value = data
  } finally {
    loading.value = false
  }
}

// ── 发布信息编辑 ──
const editVisible = ref(false)
const editSubmitting = ref(false)
const editForm = reactive({ name: '', description: '' })

function openEdit() {
  editForm.name = detail.value?.publication.name ?? ''
  editForm.description = detail.value?.publication.description ?? ''
  editVisible.value = true
}

async function submitEdit() {
  editSubmitting.value = true
  try {
    await updateMcpPublication(publicationId.value, {
      name: editForm.name.trim(),
      description: editForm.description.trim() || null,
    })
    ElMessage.success('发布信息已更新')
    editVisible.value = false
    await reload()
  } finally {
    editSubmitting.value = false
  }
}

// ── 状态操作 ──
async function publishFlow() {
  const publication = detail.value?.publication
  if (!publication) return
  await ElMessageBox.confirm(
    `确认发布「${publication.name}」？条目解析结果将冻结为不可变修订，外部客户端立即按新修订调用。`,
    '发布确认',
    { type: 'warning', confirmButtonText: '确认发布' },
  )
  try {
    detail.value = (await publishMcpPublication(publicationId.value)).data
    ElMessage.success('发布成功')
  } catch (error) {
    const message = extractErrorMessage(error)
    if (/irreversible|不可逆/i.test(message)) {
      await ElMessageBox.confirm(
        `该发布包含不可逆（IRREVERSIBLE）工具，默认拒绝发布：${message}。必须显式确认放行后才能继续。`,
        '不可逆工具二次确认',
        { type: 'error', confirmButtonText: '确认放行并发布' },
      )
      detail.value = (await publishMcpPublication(publicationId.value, { irreversibleAcknowledged: true })).data
      ElMessage.success('发布成功')
    } else {
      ElMessage.error(`发布失败：${message}`)
    }
  }
}

async function runStateAction(kind: 'suspend' | 'resume' | 'archive') {
  const publication = detail.value?.publication
  if (!publication) return
  const prompts = {
    suspend: { title: '暂停发布', message: `确认暂停「${publication.name}」？暂停后不再接收新调用，进行中的调用按超时自然收敛。` },
    resume: { title: '恢复发布', message: `确认恢复「${publication.name}」？恢复后按当前修订继续对外服务。` },
    archive: { title: '归档发布', message: `确认归档「${publication.name}」？归档后外部客户端无法再调用该发布。` },
  } as const
  await ElMessageBox.confirm(prompts[kind].message, prompts[kind].title, { type: 'warning' })
  const response = kind === 'suspend'
    ? await suspendMcpPublication(publicationId.value)
    : kind === 'resume'
      ? await resumeMcpPublication(publicationId.value)
      : await archiveMcpPublication(publicationId.value)
  detail.value = response.data
  ElMessage.success({ suspend: '已暂停', resume: '已恢复', archive: '已归档' }[kind])
}

// ── Tab 1：条目 ──
const tools = ref<ToolInfo[]>([])
const workflows = ref<WorkflowWorkingCopy[]>([])
const assetsLoading = ref(false)

const itemDialogOpen = ref(false)
const itemSubmitting = ref(false)
const itemForm = reactive({
  sourceKind: 'CAPABILITY' as McpItemKind,
  sourceRef: '',
  alias: '',
  descriptionOverride: '',
  riskLevelOverride: '' as '' | Exclude<McpRiskLevel, 'UNKNOWN'>,
})

async function loadAssets() {
  assetsLoading.value = true
  try {
    const [toolResult, workflowResult] = await Promise.all([listAllTools(), listWorkflows()])
    tools.value = toolResult
    workflows.value = workflowResult.data ?? []
  } finally {
    assetsLoading.value = false
  }
}

function openItemDialog() {
  itemForm.sourceKind = 'CAPABILITY'
  itemForm.sourceRef = ''
  itemForm.alias = ''
  itemForm.descriptionOverride = ''
  itemForm.riskLevelOverride = ''
  itemDialogOpen.value = true
  if (!tools.value.length && !workflows.value.length) {
    loadAssets()
  }
}

async function submitItem() {
  if (!itemForm.sourceRef) {
    ElMessage.warning('请选择要发布的资产')
    return
  }
  if (itemForm.sourceKind === 'WORKFLOW' && !itemForm.riskLevelOverride) {
    ElMessage.warning('Workflow 对外发布前必须明确风险等级')
    return
  }
  itemSubmitting.value = true
  try {
    await addMcpPublicationItem(publicationId.value, {
      sourceKind: itemForm.sourceKind,
      sourceRef: itemForm.sourceRef,
      alias: itemForm.alias.trim() || null,
      descriptionOverride: itemForm.descriptionOverride.trim() || null,
      riskLevelOverride: itemForm.riskLevelOverride || null,
    })
    ElMessage.success('条目已添加')
    itemDialogOpen.value = false
    previewRows.value = null
    await reload()
  } finally {
    itemSubmitting.value = false
  }
}

async function removeItem(item: { id: number; sourceRef: string }) {
  await ElMessageBox.confirm(`确认移除条目「${item.sourceRef}」？下次发布将不再包含该工具。`, '移除条目', { type: 'warning' })
  await removeMcpPublicationItem(publicationId.value, item.id)
  ElMessage.success('条目已移除')
  previewRows.value = null
  await reload()
}

const previewRows = ref<McpResolvePreviewRow[] | null>(null)
const previewLoading = ref(false)

async function runResolvePreview() {
  previewLoading.value = true
  try {
    const { data } = await resolveMcpPublicationPreview(publicationId.value)
    previewRows.value = data ?? []
  } finally {
    previewLoading.value = false
  }
}

const precheckReport = ref<McpPrecheckReport | null>(null)
const precheckLoading = ref(false)

async function runPrecheck() {
  precheckLoading.value = true
  try {
    const { data } = await precheckMcpPublication(publicationId.value)
    precheckReport.value = data
    if (data?.ok) ElMessage.success('预检通过，可以发布')
  } finally {
    precheckLoading.value = false
  }
}

// ── Tab 2：修订 ──
function revisionRowClass({ row }: { row: McpPublicationRevisionView }): string {
  return row.id === detail.value?.publication.currentRevisionId ? 'is-current' : ''
}

async function rollbackRevision(revision: McpPublicationRevisionView) {
  await ElMessageBox.confirm(
    `确认回滚到修订 r${revision.revisionNo}？回滚后 tools/list 与 tools/call 立即按该修订执行。`,
    '回滚确认',
    { type: 'warning' },
  )
  detail.value = (await rollbackMcpPublication(publicationId.value, revision.id)).data
  ElMessage.success(`已回滚到 r${revision.revisionNo}`)
}

// ── Tab 3：凭证 ──
const clientDialogOpen = ref(false)
const clientSubmitting = ref(false)
const editingClient = ref<McpClientView | null>(null)
const clientForm = reactive({
  name: '',
  projectId: null as number | null,
  projectCode: '',
  environment: '',
  tenantId: '',
  roles: [] as string[],
  toolScope: [] as string[],
  enabled: true,
  expiresAt: null as string | null,
})
const availableToolNames = computed(() => detail.value?.availableToolNames ?? [])
const aclRoleOptions = ref<string[]>([])

async function ensureAclRoles() {
  if (aclRoleOptions.value.length) return
  try {
    const { data } = await listToolAclRoles()
    aclRoleOptions.value = data ?? []
  } catch {
    aclRoleOptions.value = []
  }
}

function openClientCreate() {
  editingClient.value = null
  clientForm.name = ''
  clientForm.projectId = null
  clientForm.projectCode = ''
  clientForm.environment = ''
  clientForm.tenantId = ''
  clientForm.roles = []
  clientForm.toolScope = []
  clientForm.enabled = true
  clientForm.expiresAt = null
  clientDialogOpen.value = true
  ensureAclRoles()
}

function openClientEdit(client: McpClientView) {
  editingClient.value = client
  clientForm.name = client.name
  clientForm.projectId = client.projectId
  clientForm.projectCode = client.projectCode
  clientForm.environment = client.environment
  clientForm.tenantId = client.tenantId
  clientForm.roles = [...client.roles]
  clientForm.toolScope = [...client.toolScope]
  clientForm.enabled = client.enabled
  clientForm.expiresAt = client.expiresAt
  clientDialogOpen.value = true
  ensureAclRoles()
}

const issuedKey = ref<{ plaintextApiKey: string; note: string } | null>(null)
const keyDialogOpen = ref(false)
watch(keyDialogOpen, (open) => {
  if (!open) issuedKey.value = null
})

async function submitClient() {
  if (!editingClient.value && !clientForm.name.trim()) {
    ElMessage.warning('请填写凭证名称')
    return
  }
  if (!editingClient.value
    && (!clientForm.projectCode.trim() || !clientForm.environment.trim() || !clientForm.tenantId.trim())) {
    ElMessage.warning('请完整填写项目编码、环境与租户')
    return
  }
  if (!clientForm.roles.length) {
    ElMessage.warning('请至少选择一个已由 Tool ACL 管理的角色')
    return
  }
  clientSubmitting.value = true
  try {
    if (editingClient.value) {
      await updateMcpClient(publicationId.value, editingClient.value.id, {
        roles: clientForm.roles,
        toolScope: clientForm.toolScope,
        enabled: clientForm.enabled,
        expiresAt: clientForm.expiresAt,
      })
      ElMessage.success('凭证已更新')
    } else {
      const { data } = await createMcpClient(publicationId.value, {
        name: clientForm.name.trim(),
        projectId: clientForm.projectId,
        projectCode: clientForm.projectCode.trim(),
        environment: clientForm.environment.trim(),
        tenantId: clientForm.tenantId.trim(),
        roles: clientForm.roles,
        toolScope: clientForm.toolScope,
        expiresAt: clientForm.expiresAt,
      })
      issuedKey.value = { plaintextApiKey: data.plaintextApiKey, note: data.note }
      keyDialogOpen.value = true
    }
    clientDialogOpen.value = false
    await reload()
  } finally {
    clientSubmitting.value = false
  }
}

async function rotateClient(client: McpClientView) {
  await ElMessageBox.confirm(
    `确认轮换「${client.name}」的凭证？旧凭证立即置为已轮换，新 Key 仅显示一次。`,
    '轮换凭证',
    { type: 'warning' },
  )
  const { data } = await rotateMcpClient(publicationId.value, client.id)
  issuedKey.value = { plaintextApiKey: data.plaintextApiKey, note: data.note }
  keyDialogOpen.value = true
  await reload()
}

async function revokeClient(client: McpClientView) {
  await ElMessageBox.confirm(
    `确认吊销「${client.name}」？吊销后该凭证立即失效且不可恢复，只能重新创建。`,
    '吊销凭证',
    { type: 'error', confirmButtonText: '确认吊销' },
  )
  await revokeMcpClient(publicationId.value, client.id)
  ElMessage.success('凭证已吊销')
  await reload()
}

function copyText(text: string) {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

// ── Tab 4：接入指引 ──
const jsonrpcEndpoint = `${window.location.origin}/mcp`
const manifestEndpoint = `${window.location.origin}/mcp/manifest`
const publicationName = computed(() => detail.value?.publication.name ?? 'reachai')
const sampleToolName = computed(() => {
  const fromPreview = previewRows.value?.find((row) => row.resolvable && row.tool)?.tool?.name
  return fromPreview ?? availableToolNames.value[0] ?? '<TOOL_NAME>'
})

const cursorSnippet = computed(() => `{
  "mcpServers": {
    "${publicationName.value}": {
      "url": "${jsonrpcEndpoint}",
      "headers": { "Authorization": "Bearer <API_KEY>" }
    }
  }
}`)

const claudeCliSnippet = computed(() => `claude mcp add \\
  --transport http ${publicationName.value} \\
  "${jsonrpcEndpoint}" \\
  --header "Authorization: Bearer <API_KEY>"`)

const claudeDesktopSnippet = computed(() => `{
  "mcpServers": {
    "${publicationName.value}": {
      "command": "mcp-proxy",
      "args": [
        "--header", "Authorization=Bearer <API_KEY>",
        "${jsonrpcEndpoint}"
      ]
    }
  }
}`)

const curlSnippet = computed(() => `# 2026-07-28：无 initialize，每个请求携带版本、方法和 request-scoped _meta
# 1) server/discover
curl -X POST "${jsonrpcEndpoint}" \\
  -H "Authorization: Bearer <API_KEY>" \\
  -H "Content-Type: application/json" \\
  -H "Accept: application/json" \\
  -H "MCP-Protocol-Version: 2026-07-28" \\
  -H "Mcp-Method: server/discover" \\
  -d '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'

# 2) tools/list
curl -X POST "${jsonrpcEndpoint}" \\
  -H "Authorization: Bearer <API_KEY>" \\
  -H "Content-Type: application/json" \\
  -H "Accept: application/json" \\
  -H "MCP-Protocol-Version: 2026-07-28" \\
  -H "Mcp-Method: tools/list" \\
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'

# 3) tools/call
curl -X POST "${jsonrpcEndpoint}" \\
  -H "Authorization: Bearer <API_KEY>" \\
  -H "Content-Type: application/json" \\
  -H "Accept: application/json" \\
  -H "MCP-Protocol-Version: 2026-07-28" \\
  -H "Mcp-Method: tools/call" \\
  -H "Mcp-Name: ${sampleToolName.value}" \\
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"${sampleToolName.value}","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'

# 2025-11-25 legacy：先 initialize，再发送 initialized 通知
curl -X POST "${jsonrpcEndpoint}" \\
  -H "Authorization: Bearer <API_KEY>" \\
  -H "Content-Type: application/json" \\
  -H "Accept: application/json, text/event-stream" \\
  -d '{"jsonrpc":"2.0","id":10,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl-demo","version":"1.0.0"}}}'

curl -X POST "${jsonrpcEndpoint}" \\
  -H "Authorization: Bearer <API_KEY>" \\
  -H "Content-Type: application/json" \\
  -H "Accept: application/json, text/event-stream" \\
  -H "MCP-Protocol-Version: 2025-11-25" \\
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'`)

onMounted(reload)
</script>

<template>
  <section class="publication-detail" v-loading="loading">
    <div v-if="detail" class="detail-header">
      <el-button link :icon="ArrowLeft" @click="router.push('/mcp-hub/publications')">返回列表</el-button>
      <div class="detail-title">
        <h2>{{ detail.publication.name }}</h2>
        <div class="detail-meta">
          <el-tag :type="stateTone(detail.publication.state)">{{ stateLabel(detail.publication.state) }}</el-tag>
          <span>当前修订：{{ detail.publication.currentRevisionId ? `#${detail.publication.currentRevisionId}` : '未发布' }}</span>
          <span>条目 {{ detail.items.length }} 个 · 凭证 {{ detail.clients.length }} 个</span>
          <span>更新于 {{ formatDateTime(detail.publication.updatedAt) }}</span>
        </div>
      </div>
      <div class="detail-actions">
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
        <el-button @click="openEdit">编辑信息</el-button>
        <el-button
          v-if="detail.publication.state === 'DRAFT' || detail.publication.state === 'PUBLISHED'"
          type="primary"
          @click="publishFlow"
        >
          {{ detail.publication.state === 'PUBLISHED' ? '发布新修订' : '发布' }}
        </el-button>
        <el-button v-if="detail.publication.state === 'PUBLISHED'" type="warning" @click="runStateAction('suspend')">暂停</el-button>
        <el-button v-if="detail.publication.state === 'SUSPENDED'" type="success" @click="runStateAction('resume')">恢复</el-button>
        <el-button v-if="detail.publication.state !== 'ARCHIVED'" type="danger" plain @click="runStateAction('archive')">归档</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <!-- Tab 1 条目 -->
      <el-tab-pane label="条目" name="items">
        <div v-if="detail" class="tab-body">
          <div class="tab-toolbar">
            <p class="tab-hint">从能力目录与已发布 Workflow 中挑选条目；发布前先做解析预览与预检，不可逆工具需显式放行。</p>
            <div class="tab-actions">
              <el-button :icon="Plus" type="primary" @click="openItemDialog">添加条目</el-button>
              <el-button :loading="previewLoading" @click="runResolvePreview">解析预览</el-button>
              <el-button :loading="precheckLoading" @click="runPrecheck">预检</el-button>
            </div>
          </div>

          <el-table :data="detail.items" size="small" stripe>
            <el-table-column label="来源" width="110">
              <template #default="{ row }">
                <el-tag :type="row.sourceKind === 'CAPABILITY' ? 'primary' : 'success'" size="small">
                  {{ row.sourceKind === 'CAPABILITY' ? '能力' : 'Workflow' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sourceRef" label="sourceRef" min-width="180" show-overflow-tooltip />
            <el-table-column prop="alias" label="对外别名" min-width="140">
              <template #default="{ row }">{{ row.alias || '-' }}</template>
            </el-table-column>
            <el-table-column prop="descriptionOverride" label="说明覆盖" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.descriptionOverride || '-' }}</template>
            </el-table-column>
            <el-table-column label="风险覆盖" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.riskLevelOverride" :type="riskTone(row.riskLevelOverride)" size="small">
                  {{ riskLabel(row.riskLevelOverride) }}
                </el-tag>
                <span v-else>继承资产</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="removeItem(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="previewRows" class="preview-block">
            <h3>解析预览（tools/list 投影）</h3>
            <el-table :data="previewRows" size="small" stripe>
              <el-table-column label="解析" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.resolvable ? 'success' : 'danger'" size="small">
                    {{ row.resolvable ? '成功' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="工具 / 问题" min-width="180">
                <template #default="{ row }">
                  <template v-if="row.resolvable && row.tool">
                    <div class="cell-stack">
                      <strong>{{ row.tool.name }}</strong>
                      <small>{{ row.tool.description || '暂无描述' }}</small>
                    </div>
                  </template>
                  <span v-else class="problem-text">{{ row.problem || '解析失败' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="风险级" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.resolvable && row.tool" :type="riskTone(row.tool.riskLevel)" size="small">
                    {{ riskLabel(row.tool.riskLevel) }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="inputSchema" min-width="280">
                <template #default="{ row }">
                  <el-collapse v-if="row.resolvable && row.tool">
                    <el-collapse-item :title="`查看 ${row.tool.name} 的 inputSchema`">
                      <pre class="schema-json">{{ formatJson(row.tool.inputSchema) }}</pre>
                    </el-collapse-item>
                  </el-collapse>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div v-if="precheckReport" class="preview-block">
            <h3>预检报告</h3>
            <el-alert
              :type="precheckReport.ok ? 'success' : 'error'"
              :closable="false"
              show-icon
              :title="precheckReport.ok ? '预检通过，可以发布' : '预检未通过，请先修正条目'"
              :description="`工具 ${precheckReport.toolCount} 个 · 不可逆 ${precheckReport.irreversibleToolCount} 个 · 风险摘要：只读 ${precheckReport.riskSummary.read} / 写入 ${precheckReport.riskSummary.write} / 页面操作 ${precheckReport.riskSummary.page_action} / 不可逆 ${precheckReport.riskSummary.irreversible} / 未知 ${precheckReport.riskSummary.unknown}`"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- Tab 2 修订 -->
      <el-tab-pane label="修订" name="revisions">
        <div v-if="detail" class="tab-body">
          <p class="tab-hint">修订是发布内容的不可变快照；tools/list 与 tools/call 始终按当前生效修订执行，回滚即切换当前修订指针。</p>
          <el-table :data="detail.revisions" size="small" stripe :row-class-name="revisionRowClass">
            <el-table-column label="修订" width="100">
              <template #default="{ row }">
                <span class="revision-no">r{{ row.revisionNo }}</span>
                <el-tag v-if="row.id === detail.publication.currentRevisionId" type="success" size="small" class="current-tag">当前</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="toolCount" label="工具数" width="90" />
            <el-table-column label="风险摘要" min-width="260">
              <template #default="{ row }">
                <span class="risk-summary">
                  只读 {{ parseRiskSummary(row.riskSummaryJson).read }}
                  · 写入 {{ parseRiskSummary(row.riskSummaryJson).write }}
                  · 页面操作 {{ parseRiskSummary(row.riskSummaryJson).page_action }}
                  · 不可逆 {{ parseRiskSummary(row.riskSummaryJson).irreversible }}
                  · 未知 {{ parseRiskSummary(row.riskSummaryJson).unknown }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="publishedAt" label="发布时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.id !== detail.publication.currentRevisionId
                    && (detail.publication.state === 'PUBLISHED' || detail.publication.state === 'SUSPENDED')"
                  link
                  type="warning"
                  size="small"
                  @click="rollbackRevision(row)"
                >
                  回滚到此
                </el-button>
                <span v-else class="current-text">生效中</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- Tab 3 凭证 -->
      <el-tab-pane label="凭证" name="clients">
        <div v-if="detail" class="tab-body">
          <div class="tab-toolbar">
            <p class="tab-hint">凭证归属本发布；API Key 仅创建/轮换时明文显示一次。工具范围只能引用本发布内的工具名，留空表示整包。</p>
            <div class="tab-actions">
              <el-button
                type="primary"
                :icon="Plus"
                :disabled="detail.publication.state !== 'PUBLISHED'"
                @click="openClientCreate"
              >
                创建凭证
              </el-button>
            </div>
          </div>
          <el-table :data="detail.clients" size="small" stripe>
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="项目 / 环境 / 租户" min-width="210" show-overflow-tooltip>
              <template #default="{ row }">{{ row.projectCode }} / {{ row.environment }} / {{ row.tenantId }}</template>
            </el-table-column>
            <el-table-column label="API Key" width="130">
              <template #default="{ row }"><code>{{ row.apiKeyPrefix }}…</code></template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag
                  :type="row.state === 'ACTIVE' ? 'success' : row.state === 'EXPIRED' ? 'warning' : 'info'"
                  size="small"
                >
                  {{ clientStateLabels[row.state] ?? row.state }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="70">
              <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="角色" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.roles.length ? row.roles.join(', ') : '-' }}</template>
            </el-table-column>
            <el-table-column label="工具范围" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.toolScope.length ? row.toolScope.join(', ') : '整包' }}</template>
            </el-table-column>
            <el-table-column prop="expiresAt" label="过期时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
            </el-table-column>
            <el-table-column prop="lastUsedAt" label="最近调用" width="170">
              <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button :disabled="row.state !== 'ACTIVE'" link type="primary" size="small" @click="openClientEdit(row)">编辑</el-button>
                <el-button :disabled="row.state !== 'ACTIVE'" link type="warning" size="small" @click="rotateClient(row)">轮换</el-button>
                <el-button :disabled="row.state !== 'ACTIVE'" link type="danger" size="small" @click="revokeClient(row)">吊销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- Tab 4 接入指引 -->
      <el-tab-pane label="接入指引" name="onboarding">
        <div class="tab-body">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="API Key 在「凭证」页签创建或轮换后一次性显示，请立即保存；下方示例中的 <API_KEY> 替换为该 Key。"
          />
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="MCP 端点">
              <code class="inline-code">{{ jsonrpcEndpoint }}</code>
              <el-button link size="small" :icon="DocumentCopy" @click="copyText(jsonrpcEndpoint)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Manifest">
              <code class="inline-code">{{ manifestEndpoint }}</code>
              <el-button link size="small" :icon="DocumentCopy" @click="copyText(manifestEndpoint)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="协议">MCP（JSON-RPC 2.0 over HTTP）</el-descriptions-item>
            <el-descriptions-item label="鉴权"><code class="inline-code">Authorization: Bearer &lt;API_KEY&gt;</code></el-descriptions-item>
          </el-descriptions>

          <el-collapse class="onboarding-collapse">
            <el-collapse-item title="Cursor 接入（~/.cursor/mcp.json）" name="cursor">
              <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorSnippet" @copy="copyText" />
            </el-collapse-item>
            <el-collapse-item title="Claude 接入（claude mcp add / Desktop 配置）" name="claude">
              <CodeSnippetBlock title="claude mcp add" :code="claudeCliSnippet" @copy="copyText" />
              <el-alert
                class="snippet-alert"
                type="warning"
                :closable="false"
                show-icon
                title="Claude Desktop 目前仅支持 stdio MCP，可用 mcp-proxy 把 HTTP 端点桥接为 stdio。"
              />
              <CodeSnippetBlock title="claude_desktop_config.json" :code="claudeDesktopSnippet" @copy="copyText" />
            </el-collapse-item>
            <el-collapse-item title="curl 自检（modern discover / tools 与 legacy initialize）" name="curl">
              <CodeSnippetBlock title="curl" :code="curlSnippet" @copy="copyText" />
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 添加条目对话框 -->
    <AppDialog
      v-model="itemDialogOpen"
      title="添加发布条目"
      description="条目引用能力目录条目或已发布 Workflow；发布时解析冻结为不可变修订。"
      width="min(600px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-position="top" v-loading="assetsLoading">
        <el-form-item label="来源类型">
          <el-radio-group v-model="itemForm.sourceKind" @change="itemForm.sourceRef = ''">
            <el-radio-button value="CAPABILITY">能力（Capability Tool）</el-radio-button>
            <el-radio-button value="WORKFLOW">Workflow</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item required label="资产">
          <el-select
            v-if="itemForm.sourceKind === 'CAPABILITY'"
            v-model="itemForm.sourceRef"
            filterable
            placeholder="搜索并选择能力 Tool（sourceRef 为 Tool name）"
          >
            <el-option
              v-for="tool in tools"
              :key="tool.name"
              :label="`${tool.name}（${tool.title || tool.name}）`"
              :value="tool.name"
            />
          </el-select>
          <el-select
            v-else
            v-model="itemForm.sourceRef"
            filterable
            placeholder="搜索并选择 Workflow（sourceRef 为 Workflow id）"
          >
            <el-option
              v-for="workflow in workflows"
              :key="workflow.id"
              :label="`${workflow.name}（${workflow.keySlug}）`"
              :value="workflow.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对外别名（可选）">
          <el-input v-model="itemForm.alias" placeholder="留空使用解析出的默认工具名" />
        </el-form-item>
        <el-form-item label="说明覆盖（可选）">
          <el-input v-model="itemForm.descriptionOverride" type="textarea" :rows="2" placeholder="留空使用资产自身描述" />
        </el-form-item>
        <el-form-item :required="itemForm.sourceKind === 'WORKFLOW'" label="风险等级">
          <el-select v-model="itemForm.riskLevelOverride" clearable placeholder="Capability 可继承；Workflow 必须明确选择">
            <el-option label="只读（READ）" value="READ" />
            <el-option label="写入（WRITE）" value="WRITE" />
            <el-option label="页面操作（PAGE_ACTION）" value="PAGE_ACTION" />
            <el-option label="不可逆（IRREVERSIBLE）" value="IRREVERSIBLE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="itemSubmitting" @click="submitItem">添加</el-button>
      </template>
    </AppDialog>

    <!-- 创建 / 编辑凭证对话框 -->
    <AppDialog
      v-model="clientDialogOpen"
      :title="editingClient ? `编辑凭证：${editingClient.name}` : '创建凭证'"
      description="创建成功后明文 API Key 仅显示一次；工具范围留空表示整包，填写则只能选本发布内的工具名。"
      width="min(620px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item v-if="!editingClient" required label="名称">
          <el-input v-model="clientForm.name" placeholder="例如 Cursor（个人开发）/ Dify 生产" />
        </el-form-item>
        <template v-if="!editingClient">
          <el-form-item label="项目 ID（可选）">
            <el-input-number v-model="clientForm.projectId" :min="1" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item required label="项目编码">
            <el-input v-model="clientForm.projectCode" placeholder="必须与 Tool ACL / Runtime 项目范围一致" />
          </el-form-item>
          <el-form-item required label="环境">
            <el-input v-model="clientForm.environment" placeholder="例如 dev / test / prod" />
          </el-form-item>
          <el-form-item required label="租户">
            <el-input v-model="clientForm.tenantId" placeholder="签入 Runtime 的可信 tenantId" />
          </el-form-item>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          :title="`作用域不可变：${clientForm.projectCode} / ${clientForm.environment} / ${clientForm.tenantId}`"
        />
        <el-form-item required label="Tool ACL 角色">
          <el-select v-model="clientForm.roles" multiple filterable placeholder="只能选择调用权限中已管理的角色">
            <el-option v-for="role in aclRoleOptions" :key="role" :label="role" :value="role" />
          </el-select>
        </el-form-item>
        <el-form-item label="工具范围">
          <el-select
            v-model="clientForm.toolScope"
            multiple
            filterable
            placeholder="留空表示整包；选项来自当前发布可服务的工具名"
          >
            <el-option v-for="name in availableToolNames" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingClient" label="启用">
          <el-switch v-model="clientForm.enabled" />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="clientForm.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示永不过期"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="clientDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="clientSubmitting" @click="submitClient">
          {{ editingClient ? '保存' : '创建' }}
        </el-button>
      </template>
    </AppDialog>

    <!-- 一次性明文 API Key 对话框 -->
    <AppDialog
      v-model="keyDialogOpen"
      title="API Key 已生成"
      width="min(640px, 94vw)"
      :close-on-click-modal="false"
      :show-close="false"
    >
      <el-alert type="warning" :closable="false" show-icon title="请立即复制并妥善保存，关闭后将不再显示。" />
      <div class="apikey-box">
        <code>{{ issuedKey?.plaintextApiKey }}</code>
        <el-button type="primary" :icon="DocumentCopy" @click="copyText(issuedKey?.plaintextApiKey ?? '')">复制</el-button>
      </div>
      <p v-if="issuedKey?.note" class="apikey-note">{{ issuedKey.note }}</p>
      <template #footer>
        <el-button type="primary" @click="keyDialogOpen = false">已保存</el-button>
      </template>
    </AppDialog>

    <!-- 编辑发布信息对话框 -->
    <AppDialog v-model="editVisible" title="编辑发布信息" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item required label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="editForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="submitEdit">保存</el-button>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.publication-detail {
  display: flex;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.detail-header {
  display: flex;
  align-items: flex-start;
  gap: var(--section-gap);
}

.detail-title {
  flex: 1;
  min-width: 0;
}

.detail-title h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 18px;
  overflow-wrap: anywhere;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 12px;
}

.detail-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.detail-tabs {
  min-width: 0;
}

.tab-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tab-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.tab-hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.tab-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 6px;
}

.cell-stack strong,
.cell-stack small {
  display: block;
}

.cell-stack small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 12px;
}

.problem-text {
  color: var(--status-danger, var(--el-color-danger));
  font-size: 12px;
}

.preview-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--border-divider);
}

.preview-block h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 14px;
}

.schema-json {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
  color: var(--text-primary);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
}

.revision-no {
  margin-right: 6px;
  color: var(--text-primary);
  font-weight: 600;
}

.current-tag {
  margin-left: 4px;
}

.current-text {
  color: var(--text-muted);
  font-size: 12px;
}

.risk-summary {
  color: var(--text-secondary);
  font-size: 12px;
}

:deep(.el-table .is-current) {
  background: var(--surface-glass-selected);
}

.inline-code {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.onboarding-collapse {
  min-width: 0;
}

.snippet-alert {
  margin: 10px 0;
}

.apikey-box {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 14px 0;
  padding: 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
}

.apikey-box code {
  flex: 1;
  overflow-wrap: anywhere;
  word-break: break-all;
  user-select: all;
}

.apikey-note {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 900px) {
  .detail-header,
  .tab-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-actions,
  .tab-actions {
    justify-content: flex-start;
  }
}
</style>
