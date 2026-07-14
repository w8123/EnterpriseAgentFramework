<template>
  <AppDrawer
    :model-value="modelValue"
    title="页面记忆工作台"
    size="92%"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @opened="loadWorkbench"
  >
    <div v-if="page" class="page-memory-workbench">
      <header class="memory-head">
        <div class="memory-title">
          <span class="memory-eyebrow">PAGE MEMORY</span>
          <h2>{{ page.name || page.pageKey }}</h2>
          <div class="memory-meta">
            <el-tag effect="plain">{{ page.pageKey }}</el-tag>
            <el-tag v-if="page.routePattern" effect="plain">{{ page.routePattern }}</el-tag>
            <el-tag type="success" effect="plain">{{ page.status }}</el-tag>
          </div>
        </div>
        <div class="memory-stats">
          <span>
            <b>{{ manualItems.length }}</b>
            <small>手工记忆</small>
          </span>
          <span>
            <b>{{ aiScanCandidates.length }}</b>
            <small>AI 扫描</small>
          </span>
          <span>
            <b>{{ attachmentCandidates.length }}</b>
            <small>附件提取</small>
          </span>
        </div>
      </header>

      <el-tabs v-model="activeTab" class="memory-tabs">
        <el-tab-pane label="手工填写" name="manual">
          <div class="tab-toolbar">
            <span>已确认页面记忆</span>
            <div>
              <el-button size="small" :icon="Refresh" :loading="loading" @click="loadManualItems">刷新</el-button>
              <el-button size="small" type="primary" :icon="Plus" @click="openManualCreate">新增页面记忆</el-button>
            </div>
          </div>
          <el-table v-loading="manualLoading" :data="manualItems" row-key="id" size="small">
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title || row.itemType }}</template>
            </el-table-column>
            <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
            <el-table-column prop="sourceRef" label="来源" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceRef || '-' }}</template>
            </el-table-column>
            <el-table-column prop="trustLevel" label="信任" width="90" />
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="itemStatusTagType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="170" />
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :icon="Edit" @click="openManualEdit(row)">编辑</el-button>
                <el-button link type="success" :icon="CircleCheck" @click="verifyManualPageMemory(row)">确认</el-button>
                <el-dropdown trigger="click" @command="(cmd: string) => handleManualCommand(cmd, row)">
                  <el-button link type="primary">更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="revoke">废弃</el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!manualLoading && !manualItems.length" description="暂无手工页面记忆" :image-size="88" />
        </el-tab-pane>

        <el-tab-pane label="AI Coding 工具扫描" name="ai-scan">
          <div class="tab-toolbar">
            <span>AI Coding 工具扫描候选</span>
            <div>
              <el-select v-model="candidateStatusFilter" size="small" style="width: 130px" @change="loadCandidates">
                <el-option v-for="status in candidateStatusOptions" :key="status" :label="status" :value="status" />
              </el-select>
              <el-button size="small" :icon="Refresh" :loading="candidateLoading" @click="loadCandidates">刷新</el-button>
            </div>
          </div>
          <el-table v-loading="candidateLoading" :data="aiScanCandidates" row-key="id" size="small">
            <el-table-column prop="title" label="候选标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title || row.candidateType }}</template>
            </el-table-column>
            <el-table-column prop="content" label="候选内容" min-width="320" show-overflow-tooltip />
            <el-table-column prop="sourceType" label="来源" width="110" />
            <el-table-column prop="sourceRef" label="证据位置" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceRef || row.traceId || '-' }}</template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="candidateStatusTagType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170" />
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'PENDING'" @click="approvePageMemoryCandidate(row)">采纳</el-button>
                <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="rejectPageMemoryCandidate(row)">忽略</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!candidateLoading && !aiScanCandidates.length" description="暂无 AI Coding 扫描候选" :image-size="88" />
        </el-tab-pane>

        <el-tab-pane label="从附件中提取" name="attachment">
          <div class="tab-toolbar">
            <span>附件提取候选</span>
            <div>
              <el-button size="small" :icon="Refresh" :loading="candidateLoading" @click="loadCandidates">刷新</el-button>
              <el-button size="small" type="primary" :icon="UploadFilled" @click="openAttachmentCandidateCreate">登记附件提取结果</el-button>
            </div>
          </div>
          <el-table v-loading="candidateLoading" :data="attachmentCandidates" row-key="id" size="small">
            <el-table-column prop="title" label="候选标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title || row.candidateType }}</template>
            </el-table-column>
            <el-table-column prop="content" label="提取内容" min-width="320" show-overflow-tooltip />
            <el-table-column prop="sourceRef" label="附件/片段" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceRef || '-' }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="提取理由" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.reason || '-' }}</template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="candidateStatusTagType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170" />
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'PENDING'" @click="approvePageMemoryCandidate(row)">采纳</el-button>
                <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="rejectPageMemoryCandidate(row)">忽略</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!candidateLoading && !attachmentCandidates.length" description="暂无附件提取候选" :image-size="88" />
        </el-tab-pane>
      </el-tabs>
    </div>
    <el-empty v-else description="请选择页面" :image-size="88" />

    <AppDialog v-model="manualDialogVisible" title="页面记忆" width="720px" destroy-on-close>
      <el-form :model="manualForm" label-width="96px">
        <el-form-item label="标题">
          <el-input v-model="manualForm.title" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="manualForm.content" type="textarea" :rows="7" maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="manualForm.summary" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="来源引用">
          <el-input v-model="manualForm.sourceRef" maxlength="256" />
        </el-form-item>
        <el-form-item label="信任等级">
          <el-select v-model="manualForm.trustLevel" style="width: 180px">
            <el-option v-for="level in trustLevelOptions" :key="level" :label="level" :value="level" />
          </el-select>
        </el-form-item>
        <el-form-item label="置信度">
          <el-input-number v-model="manualForm.confidence" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveManualPageMemory">保存</el-button>
      </template>
    </AppDialog>

    <AppDialog v-model="attachmentDialogVisible" title="附件提取候选" width="760px" destroy-on-close>
      <el-form :model="attachmentForm" label-width="110px">
        <el-form-item label="候选标题">
          <el-input v-model="attachmentForm.title" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="附件/片段">
          <el-input v-model="attachmentForm.sourceRef" maxlength="256" placeholder="PRD.md#页面说明 / 截图文件名 / 文档片段编号" />
        </el-form-item>
        <el-form-item label="提取内容" required>
          <el-input v-model="attachmentForm.content" type="textarea" :rows="7" maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="提取理由">
          <el-input v-model="attachmentForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="置信度">
          <el-input-number v-model="attachmentForm.confidence" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attachmentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAttachmentCandidate">保存候选</el-button>
      </template>
    </AppDialog>
  </AppDrawer>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, reactive, ref, watch } from 'vue'
import { CircleCheck, Edit, Plus, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveContextMemoryCandidate,
  createContextItem,
  createContextMemoryCandidate,
  createContextNamespace,
  deleteContextItem,
  listContextItems,
  listContextMemoryCandidates,
  rejectContextMemoryCandidate,
  revokeContextItem,
  updateContextItem,
  verifyContextItem,
} from '@/api/context'
import type { PageRegistryView } from '@/api/embedOps'
import type {
  ContextItem,
  ContextMemoryCandidate,
  ContextMemoryCandidateCreateRequest,
  ContextMemoryCandidateStatus,
  ContextNamespace,
  ContextScope,
  ContextTrustLevel,
} from '@/types/context'

const props = defineProps<{
  modelValue: boolean
  page: PageRegistryView | null
  projectCode: string
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
}>()

const tenantId = 'default'
const loading = ref(false)
const manualLoading = ref(false)
const candidateLoading = ref(false)
const saving = ref(false)
const activeTab = ref<'manual' | 'ai-scan' | 'attachment'>('manual')
const pageMemoryNamespace = ref<ContextNamespace | null>(null)
const manualItems = ref<ContextItem[]>([])
const aiScanCandidates = ref<ContextMemoryCandidate[]>([])
const attachmentCandidates = ref<ContextMemoryCandidate[]>([])
const candidateStatusFilter = ref<ContextMemoryCandidateStatus>('PENDING')
const manualDialogVisible = ref(false)
const attachmentDialogVisible = ref(false)
const editingManualId = ref<number | null>(null)

const trustLevelOptions: ContextTrustLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'VERIFIED']
const candidateStatusOptions: ContextMemoryCandidateStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED']
const aiScanSourceTypes = new Set(['CODE', 'SQL', 'API', 'TRACE', 'PAGE', 'WORKFLOW', 'SYSTEM', 'AGENT_OUTPUT'])

const currentProjectCode = computed(() => props.page?.projectCode || props.projectCode)
const pageKey = computed(() => props.page?.pageKey || '')

const manualForm = reactive({
  title: '',
  content: '',
  summary: '',
  sourceRef: '',
  trustLevel: 'VERIFIED' as ContextTrustLevel,
  confidence: 0.9,
})

const attachmentForm = reactive({
  title: '',
  sourceRef: '',
  content: '',
  reason: '',
  confidence: 0.65,
})

watch(
  () => [props.modelValue, props.page?.pageKey],
  ([visible]) => {
    if (visible) {
      void loadWorkbench()
    }
  },
)

async function loadWorkbench() {
  if (!props.modelValue || !props.page || !currentProjectCode.value) return
  loading.value = true
  try {
    await ensurePageNamespace()
    await Promise.all([loadManualItems(), loadCandidates()])
  } finally {
    loading.value = false
  }
}

async function ensurePageNamespace() {
  if (pageMemoryNamespace.value && pageMemoryNamespace.value.ownerId === pageKey.value) {
    return pageMemoryNamespace.value
  }
  if (!props.page || !currentProjectCode.value) {
    throw new Error('page is required')
  }
  const { data } = await createContextNamespace({
    namespaceType: 'PAGE',
    tenantId,
    projectCode: currentProjectCode.value,
    ownerType: 'PAGE',
    ownerId: pageKey.value,
    displayName: `页面记忆: ${props.page.name || pageKey.value}`,
    description: props.page.routePattern || pageKey.value,
    createdBy: 'page-memory-workbench',
  })
  pageMemoryNamespace.value = data
  return data
}

function buildScope(): ContextScope {
  return {
    tenantId,
    projectCode: currentProjectCode.value,
    memoryLane: 'PROJECT_DEV',
  }
}

async function loadManualItems() {
  if (!props.page) return
  const namespace = await ensurePageNamespace()
  manualLoading.value = true
  try {
    const { data } = await listContextItems({
      ...buildScope(),
      namespaceId: namespace.id,
      itemType: 'PAGE_CONTEXT',
      limit: 100,
    })
    manualItems.value = data || []
  } finally {
    manualLoading.value = false
  }
}

async function loadCandidates() {
  if (!props.page) return
  const namespace = await ensurePageNamespace()
  candidateLoading.value = true
  try {
    const { data } = await listContextMemoryCandidates({
      ...buildScope(),
      namespaceId: namespace.id,
      candidateType: 'PAGE_CONTEXT',
      pageInstanceId: pageKey.value,
      status: candidateStatusFilter.value,
      includeExpired: true,
      limit: 200,
    })
    const rows = data || []
    attachmentCandidates.value = rows.filter(isAttachmentCandidate)
    aiScanCandidates.value = rows.filter(isAiScanCandidate)
  } finally {
    candidateLoading.value = false
  }
}

function isAttachmentCandidate(row: ContextMemoryCandidate) {
  return row.origin === 'ATTACHMENT_EXTRACT' || row.sourceType === 'DOC'
}

function isAiScanCandidate(row: ContextMemoryCandidate) {
  return !isAttachmentCandidate(row) && aiScanSourceTypes.has(row.sourceType)
}

function openManualCreate() {
  editingManualId.value = null
  Object.assign(manualForm, {
    title: '',
    content: '',
    summary: '',
    sourceRef: props.page?.routePattern || pageKey.value,
    trustLevel: 'VERIFIED',
    confidence: 0.9,
  })
  manualDialogVisible.value = true
}

function openManualEdit(row: ContextItem) {
  editingManualId.value = row.id
  Object.assign(manualForm, {
    title: row.title || '',
    content: row.content || '',
    summary: row.summary || '',
    sourceRef: row.sourceRef || props.page?.routePattern || pageKey.value,
    trustLevel: row.trustLevel || 'MEDIUM',
    confidence: row.confidence ?? 0.7,
  })
  manualDialogVisible.value = true
}

async function saveManualPageMemory() {
  if (!manualForm.content.trim()) {
    ElMessage.warning('页面记忆内容不能为空')
    return
  }
  const namespace = await ensurePageNamespace()
  saving.value = true
  try {
    if (editingManualId.value) {
      await updateContextItem(editingManualId.value, {
        title: manualForm.title.trim(),
        content: manualForm.content.trim(),
        summary: manualForm.summary.trim(),
        sourceType: 'MANUAL',
        sourceRef: manualForm.sourceRef.trim(),
        trustLevel: manualForm.trustLevel,
        confidence: manualForm.confidence,
        metadataJson: buildPageMetadata('MANUAL'),
        updatedBy: 'page-memory-workbench',
      }, buildScope())
    } else {
      await createContextItem({
        namespaceId: namespace.id,
        itemType: 'PAGE_CONTEXT',
        memoryLane: 'PROJECT_DEV',
        title: manualForm.title.trim() || `${props.page?.name || pageKey.value} 页面记忆`,
        content: manualForm.content.trim(),
        summary: manualForm.summary.trim(),
        sourceType: 'MANUAL',
        sourceRef: manualForm.sourceRef.trim() || props.page?.routePattern || pageKey.value,
        confidence: manualForm.confidence,
        trustLevel: manualForm.trustLevel,
        visibility: 'PROJECT',
        tenantId,
        projectCode: currentProjectCode.value,
        pageInstanceId: pageKey.value,
        createdBy: 'page-memory-workbench',
        metadataJson: buildPageMetadata('MANUAL'),
        bindings: [{
          bindType: 'PAGE',
          bindId: pageKey.value,
          bindKey: props.page?.routePattern || props.page?.name || pageKey.value,
          tenantId,
          projectCode: currentProjectCode.value,
        }],
        evidence: [{
          evidenceType: 'USER_CONFIRMATION',
          evidenceRef: manualForm.sourceRef.trim() || pageKey.value,
          evidenceExcerpt: manualForm.content.trim().slice(0, 500),
          metadataJson: buildPageMetadata('MANUAL_EVIDENCE'),
        }],
      })
    }
    ElMessage.success('页面记忆已保存')
    manualDialogVisible.value = false
    await loadManualItems()
  } finally {
    saving.value = false
  }
}

async function verifyManualPageMemory(row: ContextItem) {
  await verifyContextItem(row.id, { ...buildScope(), trustLevel: 'VERIFIED', confidence: 0.95 })
  ElMessage.success('页面记忆已确认')
  await loadManualItems()
}

async function handleManualCommand(command: string, row: ContextItem) {
  if (command === 'revoke') {
    await ElMessageBox.confirm(`确认废弃页面记忆「${row.title || row.itemType}」？`, '废弃页面记忆', { type: 'warning' })
    await revokeContextItem(row.id, buildScope())
    ElMessage.success('页面记忆已废弃')
    await loadManualItems()
  }
  if (command === 'delete') {
    await ElMessageBox.confirm(`确认删除页面记忆「${row.title || row.itemType}」？`, '删除页面记忆', { type: 'warning' })
    await deleteContextItem(row.id, buildScope())
    ElMessage.success('页面记忆已删除')
    await loadManualItems()
  }
}

function openAttachmentCandidateCreate() {
  Object.assign(attachmentForm, {
    title: '',
    sourceRef: '',
    content: '',
    reason: '',
    confidence: 0.65,
  })
  attachmentDialogVisible.value = true
}

async function saveAttachmentCandidate() {
  if (!attachmentForm.content.trim()) {
    ElMessage.warning('提取内容不能为空')
    return
  }
  const namespace = await ensurePageNamespace()
  const payload: ContextMemoryCandidateCreateRequest = {
    tenantId,
    projectCode: currentProjectCode.value,
    namespaceId: namespace.id,
    memoryLane: 'PROJECT_DEV',
    candidateType: 'PAGE_CONTEXT',
    title: attachmentForm.title.trim() || `${props.page?.name || pageKey.value} 附件提取`,
    content: attachmentForm.content.trim(),
    reason: attachmentForm.reason.trim(),
    sourceType: 'DOC',
    sourceRef: attachmentForm.sourceRef.trim() || pageKey.value,
    pageInstanceId: pageKey.value,
    origin: 'ATTACHMENT_EXTRACT',
    confidence: attachmentForm.confidence,
    trustLevel: 'LOW',
    visibility: 'PROJECT',
    metadataJson: buildPageMetadata('ATTACHMENT_EXTRACT'),
  }
  saving.value = true
  try {
    await createContextMemoryCandidate(payload)
    ElMessage.success('附件提取候选已保存')
    attachmentDialogVisible.value = false
    activeTab.value = 'attachment'
    candidateStatusFilter.value = 'PENDING'
    await loadCandidates()
  } finally {
    saving.value = false
  }
}

async function approvePageMemoryCandidate(row: ContextMemoryCandidate) {
  await ElMessageBox.confirm(`确认采纳候选「${row.title || row.candidateType}」？`, '采纳页面记忆候选', { type: 'warning' })
  await approveContextMemoryCandidate(row.id, {
    ...buildScope(),
    reviewReason: 'approved from page memory workbench',
    trustLevel: 'MEDIUM',
    confidence: row.confidence ?? undefined,
  })
  ElMessage.success('候选已采纳为页面记忆')
  await Promise.all([loadManualItems(), loadCandidates()])
}

async function rejectPageMemoryCandidate(row: ContextMemoryCandidate) {
  await ElMessageBox.confirm(`确认忽略候选「${row.title || row.candidateType}」？`, '忽略页面记忆候选', { type: 'warning' })
  await rejectContextMemoryCandidate(row.id, {
    ...buildScope(),
    reviewReason: 'rejected from page memory workbench',
  })
  ElMessage.success('候选已忽略')
  await loadCandidates()
}

function buildPageMetadata(sourceChannel: string) {
  return JSON.stringify({
    sourceChannel,
    targetType: 'PAGE',
    pageKey: pageKey.value,
    pageName: props.page?.name || '',
    routePattern: props.page?.routePattern || '',
    projectCode: currentProjectCode.value,
  })
}

function itemStatusTagType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'STALE') return 'warning'
  if (status === 'REVOKED') return 'info'
  return 'danger'
}

function candidateStatusTagType(status: string) {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}
</script>

<style scoped>
.page-memory-workbench {
  display: grid;
  gap: 14px;
}

.memory-head {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background: color-mix(in srgb, var(--bg-tertiary) 44%, transparent);
}

.memory-title {
  min-width: 0;
  display: grid;
  gap: 8px;
}

.memory-eyebrow {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.memory-title h2 {
  margin: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 21px;
  font-weight: 800;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.memory-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(96px, 1fr));
  gap: 10px;
}

.memory-stats span {
  display: grid;
  place-items: center;
  gap: 4px;
  min-width: 96px;
  padding: 10px 12px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background: var(--bg-card);
}

.memory-stats b {
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 800;
}

.memory-stats small {
  color: var(--text-secondary);
  font-size: 12px;
}

.memory-tabs {
  min-width: 0;
}

.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background: var(--bg-card);
}

.tab-toolbar > span {
  color: var(--text-primary);
  font-weight: 700;
}

.tab-toolbar > div {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

@media (max-width: 860px) {
  .memory-head {
    display: grid;
  }

  .memory-stats {
    grid-template-columns: 1fr;
  }

  .tab-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
