<template>
  <WorkbenchPage class="personal-memory-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="agent"
      title="我的记忆"
      description="管理当前账号的个人记忆，确认对话中识别出的候选信息。"
    >
      <template #meta>
        <MetricStrip presentation="inline" source="wallpaper" :items="memoryMetrics" aria-label="个人记忆概览" />
      </template>
      <template #actions>
        <LiquidGlassButton source="wallpaper" :icon="Download" @click="downloadExport">导出 JSON</LiquidGlassButton>
        <LiquidGlassButton source="wallpaper" type="danger" plain :loading="erasingAll" @click="eraseAll">清空全部</LiquidGlassButton>
        <LiquidGlassButton source="wallpaper" type="primary" :icon="Plus" @click="openCreate">添加记忆</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassNotice
      source="wallpaper"
      type="info"
      title="记忆用于改善回答，不会授予权限，也不会覆盖安全规则、当前请求或业务数据。密码、Token 和密钥禁止存储。"
    />

    <LiquidGlassNav source="wallpaper" class="memory-tabs" aria-label="个人记忆视图">
      <button
        v-for="tab in memoryTabs"
        :key="tab.name"
        type="button"
        :aria-pressed="activeTab === tab.name"
        @click="selectTab(tab.name)"
      >
        {{ tab.label }}<span v-if="tab.count !== undefined" class="tab-count">{{ tab.count }}</span>
      </button>
    </LiquidGlassNav>

    <LiquidGlassTable
      v-if="activeTab === 'memories'"
      body-layout="cards"
      presentation="collection"
      :show-pagination="false"
      :loading="loadingMemories"
      :empty="!loadingMemories && !memoryPage.items.length"
    >
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loadingMemories" @query="loadMemories" @reset="resetFilters">
          <LiquidGlassInput v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索标题或内容" aria-label="搜索记忆" />
          <LiquidGlassSelect v-model="typeFilter" class="short-filter" clearable placeholder="全部类型" dropdown-title="记忆类型" aria-label="记忆类型" @change="loadMemories">
            <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </LiquidGlassSelect>
          <LiquidGlassSelect v-model="agentFilter" class="agent-filter" clearable filterable placeholder="全部来源 Agent" dropdown-title="来源 Agent" aria-label="来源 Agent" @change="loadMemories">
            <el-option v-for="agent in agentOptions" :key="agent" :label="agent" :value="agent" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>
      <div class="memory-list">
        <article v-for="item in memoryPage.items" :key="item.id" class="memory-row">
          <div class="memory-main">
            <div class="memory-heading">
              <el-tag size="small" effect="plain">{{ typeLabel(item.type) }}</el-tag>
              <strong>{{ item.title || '未命名记忆' }}</strong>
            </div>
            <p>{{ item.content }}</p>
            <small>
              {{ sourceLabel(item.sourceType) }}<template v-if="item.sourceAgentId"> · 来自 {{ item.sourceAgentId }}</template>
              · 更新于 {{ formatTime(item.updatedAt) }}
            </small>
          </div>
          <div class="row-actions">
            <el-button link type="primary" @click="openEdit(item)">编辑</el-button>
            <el-button link type="danger" @click="forget(item)">删除</el-button>
          </div>
        </article>
      </div>
      <template #empty>
        <LiquidGlassEmptyState
          :title="keyword || typeFilter || agentFilter ? '没有匹配的记忆' : '还没有已确认的个人记忆'"
          :description="keyword || typeFilter || agentFilter ? '换个关键词或清空筛选再试。' : '在对话里说“记住…”，或点「添加记忆」手动写一条。'"
        />
      </template>
    </LiquidGlassTable>

    <LiquidGlassTable
      v-else-if="activeTab === 'candidates'"
      body-layout="cards"
      presentation="collection"
      :show-pagination="false"
      :loading="loadingCandidates"
      :empty="!loadingCandidates && !candidatePage.items.length"
    >
      <div class="memory-list">
        <article v-for="item in candidatePage.items" :key="item.id" class="memory-row candidate-row">
          <div class="memory-main">
            <div class="memory-heading">
              <el-tag size="small" effect="plain">{{ typeLabel(item.type) }}</el-tag>
              <strong>{{ item.title || '对话中识别到的信息' }}</strong>
              <el-tag v-if="item.conflictMemoryId" size="small" type="warning">可能替换已有记忆</el-tag>
              <el-tag v-if="(item.occurrenceCount || 1) > 1" size="small" type="info">
                出现 {{ item.occurrenceCount }} 次
              </el-tag>
            </div>
            <p>{{ item.content }}</p>
            <small>
              {{ item.reason || '对话中识别' }}<template v-if="item.sourceAgentId"> · 来自 {{ item.sourceAgentId }}</template>
              · {{ formatTime(item.lastSeenAt || item.createdAt) }}
            </small>
          </div>
          <div class="row-actions">
            <LiquidGlassButton type="primary" size="small" @click="approve(item)">确认记住</LiquidGlassButton>
            <LiquidGlassButton size="small" @click="reject(item)">忽略</LiquidGlassButton>
          </div>
        </article>
      </div>
      <template #empty>
        <LiquidGlassEmptyState title="没有待确认候选" description="Agent 在对话中识别到值得记住的信息时，会先放在这里等你确认。" />
      </template>
    </LiquidGlassTable>

    <LiquidGlassTable v-else :show-pagination="false" :loading="loadingAudit">
      <el-table :data="auditEvents" empty-text="暂无审计记录">
        <el-table-column label="事件" width="160">
          <template #default="scope">{{ eventLabel(scope.row.eventType) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="200" />
        <el-table-column prop="agentId" label="Agent" min-width="140" />
        <el-table-column prop="decision" label="结果" width="110" />
        <el-table-column label="时间" width="190">
          <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="editorVisible" :title="editingId ? '编辑记忆' : '添加记忆'" width="560px">
      <el-form label-position="top">
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="256" /></el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="5" maxlength="16000" show-word-limit />
        </el-form-item>
        <el-form-item v-if="!editingId" label="稳定语义键（可选）">
          <el-input v-model="form.semanticKey" placeholder="例如 response-language，用于更新同一条偏好" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="editorVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNav,
  LiquidGlassNotice,
  LiquidGlassSelect,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import {
  approveMyMemoryCandidate,
  eraseAllMyMemories,
  exportMyMemories,
  forgetMyMemory,
  listMyMemories,
  listMyMemoryAudit,
  listMyMemoryCandidates,
  rejectMyMemoryCandidate,
  rememberMyMemory,
  updateMyMemory,
} from '@/api/memory'
import type {
  PersonalMemory,
  PersonalMemoryAudit,
  PersonalMemoryCandidate,
  PersonalMemoryCandidatePage,
  PersonalMemoryPage,
  PersonalMemorySource,
  PersonalMemoryType,
} from '@/types/memory'

type MemoryTab = 'memories' | 'candidates' | 'audit'
const activeTab = ref<MemoryTab>('memories')
const keyword = ref('')
const typeFilter = ref<PersonalMemoryType | ''>('')
const agentFilter = ref('')
const knownAgents = ref<string[]>([])
const agentOptions = computed(() => knownAgents.value)
const loadingMemories = ref(false)
const loadingCandidates = ref(false)
const loadingAudit = ref(false)
const editorVisible = ref(false)
const saving = ref(false)
const erasingAll = ref(false)
const editingId = ref<number | null>(null)
const memoryPage = reactive<PersonalMemoryPage>({ items: [], total: 0, limit: 100, offset: 0 })
const candidatePage = reactive<PersonalMemoryCandidatePage>({ items: [], total: 0, limit: 100, offset: 0 })
const auditEvents = ref<PersonalMemoryAudit[]>([])
const memoryTabs = computed<Array<{ name: MemoryTab; label: string; count?: number }>>(() => [
  { name: 'memories', label: '已确认', count: memoryPage.total },
  { name: 'candidates', label: '待确认', count: candidatePage.total },
  { name: 'audit', label: '审计记录' },
])
const memoryMetrics = computed<MetricStripItem[]>(() => [
  { key: 'memories', label: '已确认记忆', value: memoryPage.total },
  { key: 'candidates', label: '待确认候选', value: candidatePage.total, tone: candidatePage.total ? 'warning' : undefined },
  { key: 'agents', label: '来源 Agent', value: agentOptions.value.length },
])
const form = reactive<{ type: PersonalMemoryType; title: string; content: string; semanticKey: string }>({
  type: 'NOTE', title: '', content: '', semanticKey: '',
})
const typeOptions: Array<{ value: PersonalMemoryType; label: string }> = [
  { value: 'FACT', label: '个人事实' },
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'RULE', label: '长期规则' },
  { value: 'NOTE', label: '备注' },
]

async function loadMemories() {
  loadingMemories.value = true
  try {
    const { data } = await listMyMemories({
      keyword: keyword.value || undefined,
      type: typeFilter.value || undefined,
      agentId: agentFilter.value || undefined,
      limit: 100,
    })
    Object.assign(memoryPage, data)
    rememberAgents(data.items.map(item => item.sourceAgentId))
  } finally { loadingMemories.value = false }
}

async function loadCandidates() {
  loadingCandidates.value = true
  try {
    const { data } = await listMyMemoryCandidates({ status: 'PENDING', limit: 100 })
    Object.assign(candidatePage, data)
  } finally { loadingCandidates.value = false }
}

async function loadAudit() {
  loadingAudit.value = true
  try { auditEvents.value = (await listMyMemoryAudit()).data }
  finally { loadingAudit.value = false }
}

function selectTab(name: MemoryTab) {
  activeTab.value = name
  if (name === 'candidates') loadCandidates()
  if (name === 'audit') loadAudit()
}

function resetFilters() {
  keyword.value = ''
  typeFilter.value = ''
  agentFilter.value = ''
  loadMemories()
}

function resetForm() {
  editingId.value = null
  form.type = 'NOTE'; form.title = ''; form.content = ''; form.semanticKey = ''
}

function openCreate() { resetForm(); editorVisible.value = true }
function openEdit(item: PersonalMemory) {
  editingId.value = item.id
  form.type = item.type
  form.title = item.title || ''
  form.content = item.content
  form.semanticKey = ''
  editorVisible.value = true
}

async function save() {
  if (!form.content.trim()) return ElMessage.warning('请输入记忆内容')
  saving.value = true
  try {
    if (editingId.value) {
      await updateMyMemory(editingId.value, { type: form.type, title: form.title, content: form.content })
    } else {
      await rememberMyMemory({
        type: form.type,
        title: form.title,
        content: form.content,
        semanticKey: form.semanticKey || undefined,
        clientRequestId: crypto.randomUUID(),
      })
    }
    ElMessage.success('记忆已保存')
    editorVisible.value = false
    await loadMemories()
  } finally { saving.value = false }
}

async function forget(item: PersonalMemory) {
  await ElMessageBox.confirm('删除后所有 Agent 立即不再使用这条记忆，且无法恢复。是否继续？', '删除个人记忆', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  })
  await forgetMyMemory(item.id)
  ElMessage.success('已删除')
  await loadMemories()
}

async function eraseAll() {
  let typed = ''
  try {
    const result = await ElMessageBox.prompt(
      '这会永久清空当前账号的全部长期记忆和待确认候选，并异步删除检索投影。Runtime 会话、业务源系统、RunOps 和备份不在本操作范围内。请输入“全部删除”继续。',
      '清空全部个人记忆',
      {
        confirmButtonText: '永久清空',
        cancelButtonText: '取消',
        type: 'warning',
        inputPlaceholder: '全部删除',
        inputValidator: value => value === '全部删除' || '请输入“全部删除”',
      },
    )
    typed = result.value
  } catch {
    return
  }
  if (typed !== '全部删除') return
  erasingAll.value = true
  try {
    const { data } = await eraseAllMyMemories({ confirmation: 'ERASE_ALL_PERSONAL_MEMORIES' })
    ElMessage.success(`已清空 ${data.memoriesErased} 条记忆和 ${data.candidatesErased} 条候选`)
    await Promise.all([loadMemories(), loadCandidates(), loadAudit()])
  } finally {
    erasingAll.value = false
  }
}

async function approve(item: PersonalMemoryCandidate) {
  await approveMyMemoryCandidate(item.id)
  ElMessage.success(item.conflictMemoryId ? '已确认并更新相关记忆' : '已加入个人记忆')
  await Promise.all([loadCandidates(), loadMemories()])
}

async function reject(item: PersonalMemoryCandidate) {
  await rejectMyMemoryCandidate(item.id)
  ElMessage.success('已忽略')
  await loadCandidates()
}

async function downloadExport() {
  const response = await exportMyMemories()
  const url = URL.createObjectURL(response.data as Blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = 'reachai-personal-memory.json'; anchor.click()
  URL.revokeObjectURL(url)
}

function rememberAgents(ids: Array<string | null | undefined>) {
  const merged = new Set(knownAgents.value)
  ids.forEach(id => { if (id) merged.add(id) })
  knownAgents.value = [...merged].sort()
}

const sourceLabels: Record<PersonalMemorySource, string> = {
  MANUAL: '手动添加',
  CONVERSATION: '对话中记住',
  CANDIDATE: '确认候选',
}

function sourceLabel(source: PersonalMemorySource) {
  return sourceLabels[source] || source
}

const eventLabels: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  DELETE: '删除',
  ERASE_ALL: '清空全部',
  EXPIRE: '到期清理',
  SEARCH: 'Agent 召回',
  CANDIDATE_CREATE: '识别候选',
  CANDIDATE_APPROVE: '确认候选',
  CANDIDATE_REJECT: '忽略候选',
}

function eventLabel(type: string) {
  return eventLabels[type] || type
}

function typeLabel(type: PersonalMemoryType) {
  return typeOptions.find(item => item.value === type)?.label || type
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

onMounted(() => Promise.all([loadMemories(), loadCandidates()]))
</script>

<style scoped lang="scss">
.memory-tabs { align-self: flex-start; }
.tab-count { min-width: 20px; padding: 0 6px; border-radius: var(--radius-pill); background: var(--surface-glass-inset); font-size: 11px; font-variant-numeric: tabular-nums; text-align: center; }
/* 类型是短值、来源 Agent 中等长度，筛选条里收窄，把宽度留给搜索框。 */
:deep(.filter-bar__fields > .liquid-glass-control.short-filter) { flex: 0 1 150px; max-width: 170px; }
:deep(.filter-bar__fields > .liquid-glass-control.agent-filter) { flex: 0 1 200px; max-width: 220px; }
.memory-list { display: grid; gap: 10px; }
.memory-row { display: flex; justify-content: space-between; gap: var(--layout-panel-padding); padding: 12px var(--layout-panel-padding); border: 1px solid var(--border-glass-edge); border-radius: var(--radius-md); background: var(--surface-glass-frost); box-shadow: var(--glass-rim-double); }
.candidate-row { border-left: 3px solid var(--brand-primary); }
.memory-main { min-width: 0; }
.memory-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.memory-heading strong { color: var(--text-primary); }
.memory-main p { margin: 10px 0 8px; color: var(--text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.memory-main small { color: var(--text-muted); }
.row-actions { display: flex; align-items: center; flex-shrink: 0; gap: 8px; }
@media (max-width: 900px) {
  .memory-row { flex-direction: column; align-items: stretch; }
  :deep(.filter-bar__fields > .liquid-glass-control:is(.short-filter, .agent-filter)) { max-width: none; }
}
</style>
