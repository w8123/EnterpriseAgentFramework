<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import {
  archiveMcpPublication,
  createMcpPublication,
  listMcpPublications,
  publishMcpPublication,
  resumeMcpPublication,
  suspendMcpPublication,
} from '@/api/mcp'
import type { McpPublication, McpPublicationState } from '@/types/mcp'

const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{1,127}$/

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const rows = ref<McpPublication[]>([])
const total = ref(0)
const query = reactive({ search: '', state: '', limit: 20, offset: 0 })

const stateOptions: Array<{ value: McpPublicationState; label: string }> = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'SUSPENDED', label: '已暂停' },
  { value: 'ARCHIVED', label: '已归档' },
]

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

function stateLabel(state: string): string {
  return stateLabels[state] ?? state
}

function stateTone(state: string) {
  return stateTones[state] ?? 'info'
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
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
    const { data } = await listMcpPublications({
      search: query.search || undefined,
      state: query.state || undefined,
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

// ── 新建发布 ──
const createVisible = ref(false)
const createForm = reactive({ name: '', description: '' })
const nameValid = computed(() => NAME_PATTERN.test(createForm.name))

function openCreate() {
  createForm.name = ''
  createForm.description = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!nameValid.value) {
    ElMessage.warning('名称需 2-128 个字符，以字母开头，仅含字母、数字、下划线或连字符')
    return
  }
  submitting.value = true
  try {
    await createMcpPublication({
      name: createForm.name.trim(),
      description: createForm.description.trim() || null,
    })
    ElMessage.success('发布已创建，请在详情页添加条目并完成预检')
    createVisible.value = false
    await reload()
  } finally {
    submitting.value = false
  }
}

// ── 状态操作 ──
async function publishFlow(row: McpPublication) {
  await ElMessageBox.confirm(
    `确认发布「${row.name}」？发布后条目解析结果冻结为不可变修订，外部客户端立即按新修订调用。`,
    '发布确认',
    { type: 'warning', confirmButtonText: '确认发布' },
  )
  try {
    await publishMcpPublication(row.id)
    ElMessage.success('发布成功')
    await reload()
  } catch (error) {
    const message = extractErrorMessage(error)
    if (/irreversible|不可逆/i.test(message)) {
      await ElMessageBox.confirm(
        `该发布包含不可逆（IRREVERSIBLE）工具，默认拒绝发布：${message}。必须显式确认放行后才能继续。`,
        '不可逆工具二次确认',
        { type: 'error', confirmButtonText: '确认放行并发布' },
      )
      await publishMcpPublication(row.id, { irreversibleAcknowledged: true })
      ElMessage.success('发布成功')
      await reload()
    }
  }
}

async function runStateAction(kind: 'suspend' | 'resume' | 'archive', row: McpPublication) {
  const prompts = {
    suspend: { title: '暂停发布', message: `确认暂停「${row.name}」？暂停后不再接收新调用，进行中的调用按超时自然收敛。` },
    resume: { title: '恢复发布', message: `确认恢复「${row.name}」？恢复后按当前修订继续对外服务。` },
    archive: { title: '归档发布', message: `确认归档「${row.name}」？归档后外部客户端无法再调用该发布。` },
  } as const
  await ElMessageBox.confirm(prompts[kind].message, prompts[kind].title, { type: 'warning' })
  const response = kind === 'suspend'
    ? await suspendMcpPublication(row.id)
    : kind === 'resume'
      ? await resumeMcpPublication(row.id)
      : await archiveMcpPublication(row.id)
  rows.value = rows.value.map((item) => (item.id === response.data.publication.id ? response.data.publication : item))
  ElMessage.success({ suspend: '已暂停', resume: '已恢复', archive: '已归档' }[kind])
  await reload()
}

function openDetail(row: McpPublication) {
  router.push(`/mcp-hub/publications/${row.id}`)
}

onMounted(reload)
</script>

<template>
  <section class="publication-page">
    <div class="section-intro">
      <div>
        <h2>对外发布</h2>
        <p>把能力目录条目与已发布 Workflow 组成版本化 MCP 服务；修订不可变，可暂停、恢复与回滚。</p>
      </div>
      <div class="section-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建发布</el-button>
      </div>
    </div>

    <WorkbenchPanel level="control" density="compact">
      <div class="filter-row">
        <el-input
          v-model="query.search"
          :prefix-icon="Search"
          clearable
          placeholder="搜索发布名称或说明"
          @keyup.enter="applyFilters"
          @clear="applyFilters"
        />
        <el-select v-model="query.state" clearable placeholder="全部状态" @change="applyFilters">
          <el-option v-for="option in stateOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button type="primary" @click="applyFilters">查询</el-button>
      </div>
    </WorkbenchPanel>

    <el-card class="workbench-list-surface" shadow="never">
      <el-table :data="rows" v-loading="loading" stripe @row-click="openDetail">
        <el-table-column prop="name" label="名称" min-width="200">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.name }}</strong>
              <small>{{ row.description || '暂无说明' }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTone(row.state)" size="small">{{ stateLabel(row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentRevisionId" label="当前修订" width="100">
          <template #default="{ row }">{{ row.currentRevisionId ? `#${row.currentRevisionId}` : '-' }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button v-if="row.state === 'DRAFT'" link type="success" @click.stop="publishFlow(row)">发布</el-button>
            <el-button v-if="row.state === 'PUBLISHED'" link type="warning" @click.stop="runStateAction('suspend', row)">暂停</el-button>
            <el-button v-if="row.state === 'SUSPENDED'" link type="success" @click.stop="runStateAction('resume', row)">恢复</el-button>
            <el-button v-if="row.state !== 'ARCHIVED'" link type="danger" @click.stop="runStateAction('archive', row)">归档</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        :current-page="Math.floor(query.offset / query.limit) + 1"
        :page-size="query.limit"
        :total="total"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        @current-change="(page: number) => { query.offset = (page - 1) * query.limit; reload() }"
        @size-change="(size: number) => { query.limit = size; query.offset = 0; reload() }"
      />
    </el-card>

    <AppDialog
      v-model="createVisible"
      title="新建对外发布"
      description="草稿不对外服务；添加条目并通过预检后发布才生效。"
      width="min(560px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item required>
          <template #label>
            名称
            <span class="form-hint">2-128 个字符，以字母开头，仅含字母、数字、下划线或连字符</span>
          </template>
          <el-input v-model="createForm.name" placeholder="例如 finance-tools" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="描述该发布对外提供什么能力，供管理员与接入方理解"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.publication-page {
  display: flex;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.section-intro,
.section-actions,
.filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.section-intro {
  align-items: flex-start;
}

.section-intro h2,
.section-intro p {
  margin: 0;
}

.section-intro h2 {
  color: var(--text-primary);
  font-size: 18px;
}

.section-intro p {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 13px;
}

.filter-row > .el-input {
  flex: 1;
  max-width: 420px;
}

.filter-row > .el-select {
  width: 150px;
}

.cell-stack strong,
.cell-stack small {
  display: block;
}

.cell-stack small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-hint {
  margin-left: 6px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 400;
}

.workbench-list-surface :deep(.el-pagination) {
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 760px) {
  .section-intro,
  .filter-row {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-row > .el-input,
  .filter-row > .el-select {
    width: 100%;
    max-width: none;
  }
}
</style>
