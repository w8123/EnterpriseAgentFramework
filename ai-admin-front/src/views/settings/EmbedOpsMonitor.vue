<template>
  <div class="embed-ops-page">
    <div class="page-head">
      <div>
        <h1>嵌入式对话审计</h1>
        <p>按业务系统、Agent、用户、Session 查看嵌入式会话和页面动作闭环。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-form class="filters" inline>
      <el-form-item label="App">
        <el-input v-model="filters.appId" clearable placeholder="projectCode / appId" />
      </el-form-item>
      <el-form-item label="Agent">
        <el-input v-model="filters.agentId" clearable placeholder="agentId" />
      </el-form-item>
      <el-form-item label="用户">
        <el-input v-model="filters.externalUserId" clearable placeholder="externalUserId" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-card shadow="never">
      <template #header>Embed app policy</template>
      <el-form class="renderer-form" inline>
        <el-form-item label="Project">
          <el-input v-model="credentialProjectCode" clearable placeholder="projectCode" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="credentialLoading" @click="loadCredentialPolicies">Load policies</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="credentialPolicies" row-key="id" size="small">
        <el-table-column prop="projectCode" label="Project" width="150" />
        <el-table-column prop="appKey" label="App Key" min-width="160" show-overflow-tooltip />
        <el-table-column prop="allowedOriginsJson" label="Allowed Origins" min-width="220" show-overflow-tooltip />
        <el-table-column prop="allowedAgentIdsJson" label="Allowed Agents" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tokenTtlSeconds" label="TTL" width="90" />
        <el-table-column prop="status" label="Status" width="100" />
        <el-table-column label="Ops" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="editCredentialPolicy(row)">Edit</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form v-if="editingCredentialId" class="policy-editor" label-width="120px">
        <el-form-item label="Allowed Origins">
          <el-input v-model="credentialOriginsText" type="textarea" :rows="2" placeholder="https://app.example.com, https://*.corp.example.com" />
        </el-form-item>
        <el-form-item label="Allowed Agents">
          <el-input v-model="credentialAgentsText" placeholder="agent-a,agent-b" />
        </el-form-item>
        <el-form-item label="Token TTL">
          <el-input-number v-model="credentialTtlSeconds" :min="60" :max="3600" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="credentialSaving" @click="saveCredentialPolicy">Save policy</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>Embed renderer registry</template>
      <el-form class="renderer-form" inline>
        <el-form-item label="App">
          <el-input v-model="rendererForm.appId" clearable placeholder="appId" />
        </el-form-item>
        <el-form-item label="Renderer">
          <el-input v-model="rendererForm.rendererKey" clearable placeholder="bzsdk.teamProfile" />
        </el-form-item>
        <el-form-item label="Version">
          <el-input v-model="rendererForm.version" clearable placeholder="1.0.0" />
        </el-form-item>
        <el-form-item label="Agents">
          <el-input v-model="rendererAgentsText" clearable placeholder="agent-a,agent-b" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="rendererSaving" @click="saveRenderer">Save renderer</el-button>
          <el-button :loading="rendererLoading" @click="loadRenderers">Refresh</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="renderers" row-key="id" size="small">
        <el-table-column prop="appId" label="App" width="140" />
        <el-table-column prop="rendererKey" label="Renderer" min-width="180" show-overflow-tooltip />
        <el-table-column prop="version" label="Version" width="120" />
        <el-table-column prop="allowedAgentIdsJson" label="Allowed Agents" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="Status" width="100" />
        <el-table-column label="Ops" width="180">
          <template #default="{ row }">
            <el-button link type="primary" @click="editRenderer(row)">Edit</el-button>
            <el-button link type="danger" @click="disableRenderer(row)">Disable</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-table :data="sessions" row-key="sessionId" @row-click="selectSession">
      <el-table-column prop="sessionId" label="Session" min-width="220" show-overflow-tooltip />
      <el-table-column prop="appId" label="App" width="130" />
      <el-table-column prop="agentId" label="Agent" width="150" show-overflow-tooltip />
      <el-table-column prop="externalUserId" label="用户" width="150" show-overflow-tooltip />
      <el-table-column prop="pageInstanceId" label="页面实例" min-width="180" show-overflow-tooltip />
      <el-table-column prop="origin" label="Origin" min-width="180" show-overflow-tooltip />
      <el-table-column prop="route" label="Route" min-width="160" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
    </el-table>

    <div class="detail-grid">
      <el-card shadow="never">
        <template #header>页面动作</template>
        <el-table :data="pageActions" row-key="id" size="small">
          <el-table-column prop="requestId" label="Request" min-width="160" show-overflow-tooltip />
          <el-table-column prop="actionKey" label="Action" min-width="160" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column prop="targetPageInstanceId" label="页面实例" min-width="160" show-overflow-tooltip />
          <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>Chat 事件</template>
        <el-table :data="chatEvents" row-key="id" size="small">
          <el-table-column prop="eventType" label="事件" width="150" />
          <el-table-column prop="role" label="角色" width="90" />
          <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
          <el-table-column prop="traceId" label="Trace" min-width="140" show-overflow-tooltip />
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createEmbedRenderer,
  disableEmbedRenderer as disableEmbedRendererApi,
  listEmbedCredentialPolicies,
  listEmbedChatEvents,
  listEmbedRenderers,
  listEmbedSessions,
  listPageActionEvents,
  updateEmbedCredentialPolicy,
  updateEmbedRenderer,
  type EmbedChatEventView,
  type EmbedCredentialPolicyView,
  type EmbedRendererPayload,
  type EmbedRendererView,
  type EmbedSessionView,
  type PageActionEventView,
} from '@/api/embedOps'

const loading = ref(false)
const credentialLoading = ref(false)
const credentialSaving = ref(false)
const rendererLoading = ref(false)
const rendererSaving = ref(false)
const sessions = ref<EmbedSessionView[]>([])
const pageActions = ref<PageActionEventView[]>([])
const chatEvents = ref<EmbedChatEventView[]>([])
const credentialPolicies = ref<EmbedCredentialPolicyView[]>([])
const renderers = ref<EmbedRendererView[]>([])
const selectedSessionId = ref('')
const credentialProjectCode = ref('')
const editingCredentialId = ref<number | null>(null)
const credentialOriginsText = ref('')
const credentialAgentsText = ref('')
const credentialTtlSeconds = ref(600)
const editingRendererId = ref<number | null>(null)
const rendererAgentsText = ref('')
const filters = reactive({
  appId: '',
  agentId: '',
  externalUserId: '',
})
const rendererForm = reactive({
  appId: '',
  rendererKey: '',
  name: '',
  version: '1.0.0',
  status: 'ACTIVE',
})

async function load() {
  loading.value = true
  try {
    const params = Object.fromEntries(Object.entries(filters).filter(([, value]) => value))
    const [sessionResult] = await Promise.all([
      listEmbedSessions(params),
      loadCredentialPolicies(),
      loadRenderers(),
    ])
    const { data } = sessionResult
    sessions.value = data || []
    if (selectedSessionId.value) {
      await loadSessionDetail(selectedSessionId.value)
    }
  } finally {
    loading.value = false
  }
}

async function loadCredentialPolicies() {
  credentialLoading.value = true
  try {
    const params = credentialProjectCode.value ? { projectCode: credentialProjectCode.value } : {}
    const { data } = await listEmbedCredentialPolicies(params)
    credentialPolicies.value = data || []
  } finally {
    credentialLoading.value = false
  }
}

function editCredentialPolicy(row: EmbedCredentialPolicyView) {
  editingCredentialId.value = row.id
  credentialProjectCode.value = row.projectCode
  credentialTtlSeconds.value = row.tokenTtlSeconds || 600
  credentialOriginsText.value = parseJsonArray(row.allowedOriginsJson).join(',')
  credentialAgentsText.value = parseJsonArray(row.allowedAgentIdsJson).join(',')
}

async function saveCredentialPolicy() {
  if (!editingCredentialId.value) return
  credentialSaving.value = true
  try {
    await updateEmbedCredentialPolicy(editingCredentialId.value, {
      allowedOrigins: splitCsv(credentialOriginsText.value),
      allowedAgentIds: splitCsv(credentialAgentsText.value),
      tokenTtlSeconds: credentialTtlSeconds.value,
      status: 'ACTIVE',
    })
    await loadCredentialPolicies()
  } finally {
    credentialSaving.value = false
  }
}

async function loadRenderers() {
  rendererLoading.value = true
  try {
    const params = rendererForm.appId ? { appId: rendererForm.appId } : {}
    const { data } = await listEmbedRenderers(params)
    renderers.value = data || []
  } finally {
    rendererLoading.value = false
  }
}

async function saveRenderer() {
  rendererSaving.value = true
  try {
    const payload: EmbedRendererPayload = {
      appId: rendererForm.appId.trim(),
      rendererKey: rendererForm.rendererKey.trim(),
      name: rendererForm.name.trim() || rendererForm.rendererKey.trim(),
      version: rendererForm.version.trim() || '1.0.0',
      inputSchema: {},
      allowedAgentIds: rendererAgentsText.value.split(',').map((item) => item.trim()).filter(Boolean),
      status: rendererForm.status,
    }
    if (editingRendererId.value) {
      await updateEmbedRenderer(editingRendererId.value, payload)
    } else {
      await createEmbedRenderer(payload)
    }
    editingRendererId.value = null
    await loadRenderers()
  } finally {
    rendererSaving.value = false
  }
}

function editRenderer(row: EmbedRendererView) {
  editingRendererId.value = row.id
  rendererForm.appId = row.appId
  rendererForm.rendererKey = row.rendererKey
  rendererForm.name = row.name || row.rendererKey
  rendererForm.version = row.version || '1.0.0'
  rendererForm.status = row.status || 'ACTIVE'
  try {
    const agents = JSON.parse(row.allowedAgentIdsJson || '[]')
    rendererAgentsText.value = Array.isArray(agents) ? agents.join(',') : ''
  } catch {
    rendererAgentsText.value = ''
  }
}

async function disableRenderer(row: EmbedRendererView) {
  await disableEmbedRendererApi(row.id)
  await loadRenderers()
}

function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function parseJsonArray(value?: string): string[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

async function selectSession(row: EmbedSessionView) {
  selectedSessionId.value = row.sessionId
  await loadSessionDetail(row.sessionId)
}

async function loadSessionDetail(sessionId: string) {
  const [actions, events] = await Promise.all([
    listPageActionEvents({ sessionId }),
    listEmbedChatEvents(sessionId),
  ])
  pageActions.value = actions.data || []
  chatEvents.value = events.data || []
}

onMounted(load)
</script>

<style scoped>
.embed-ops-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-head h1 {
  margin: 0;
  font-size: 22px;
}

.page-head p {
  margin: 6px 0 0;
  color: var(--text-secondary);
}

.filters {
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 980px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
