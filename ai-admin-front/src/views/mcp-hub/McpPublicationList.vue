<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Plus, QuestionFilled, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import McpListToolbar from './McpListToolbar.vue'
import { LiquidGlassButton, LiquidGlassDropdown, LiquidGlassEmptyState, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable } from '@/components/common/liquid-glass'
import {
  archiveMcpPublication,
  createMcpPublication,
  listMcpPublications,
  resumeMcpPublication,
  suspendMcpPublication,
} from '@/api/mcp'
import type { McpPublication, McpPublicationState } from '@/types/mcp'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE } from '@/auth/platformAccess'
const { can } = usePlatformCan()
defineEmits<{ help: [] }>()
const canManage = computed(() => can(PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE))

const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{1,127}$/

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const rows = ref<McpPublication[]>([])
const total = ref(0)
const query = reactive({ search: '', state: '', limit: 20, offset: 0 })
const hasFilters = computed(() => Boolean(query.search.trim() || query.state))

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
  loadError.value = ''
  try {
    const { data } = await listMcpPublications({
      search: query.search || undefined,
      state: query.state || undefined,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = extractErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  query.offset = 0
  reload()
}

function resetFilters() {
  query.search = ''
  query.state = ''
  applyFilters()
}

function changePage(page: number, size: number) {
  query.limit = size
  query.offset = (page - 1) * size
  reload()
}

// ── 新建发布 ──
const createVisible = ref(false)
const createForm = reactive({ name: '', description: '' })
const nameValid = computed(() => NAME_PATTERN.test(createForm.name.trim()))

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
    const { data } = await createMcpPublication({
      name: createForm.name.trim(),
      description: createForm.description.trim() || null,
    })
    ElMessage.success('服务已创建，请选择开放的工具')
    createVisible.value = false
    await router.push(`/mcp-hub/publications/${data.id}`)
  } catch (error) {
    ElMessage.error(`创建失败：${extractErrorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

// ── 状态操作 ──
async function runStateAction(kind: 'suspend' | 'resume' | 'archive', row: McpPublication) {
  const prompts = {
    suspend: { title: '暂停发布', message: `确认暂停「${row.name}」？暂停后不再接收新调用，进行中的调用按超时自然收敛。` },
    resume: { title: '恢复发布', message: `确认恢复「${row.name}」？恢复后按当前修订继续对外服务。` },
    archive: { title: '归档发布', message: `确认归档「${row.name}」？归档后外部客户端无法再调用该发布。` },
  } as const
  try {
    await ElMessageBox.confirm(prompts[kind].message, prompts[kind].title, { type: 'warning' })
  } catch {
    return
  }
  try {
    if (kind === 'suspend') await suspendMcpPublication(row.id)
    else if (kind === 'resume') await resumeMcpPublication(row.id)
    else await archiveMcpPublication(row.id)
    ElMessage.success({ suspend: '已暂停', resume: '已恢复', archive: '已归档' }[kind])
    await reload()
  } catch (error) {
    ElMessage.error(`操作失败：${extractErrorMessage(error)}`)
  }
}

function openDetail(row: McpPublication, connect = false) {
  router.push(connect === true
    ? { path: `/mcp-hub/publications/${row.id}`, query: { tab: 'connect' } }
    : `/mcp-hub/publications/${row.id}`)
}

onMounted(reload)
</script>

<template>
  <section class="publication-page mcp-list-page">
    <McpListToolbar>
      <LiquidGlassButton source="wallpaper" link :icon="QuestionFilled" aria-label="MCP 开放使用说明" title="使用说明" @click="$emit('help')" />
      <LiquidGlassButton v-if="canManage" source="wallpaper" type="primary" :icon="Plus" @click="openCreate">新建 MCP 服务</LiquidGlassButton>
    </McpListToolbar>

    <LiquidGlassTable
      class="workbench-list-surface"
      :current-page="Math.floor(query.offset / query.limit) + 1"
      :page-size="query.limit"
      :total="total"
      :page-sizes="[20, 50, 100]"
      pagination-label="MCP 服务分页"
      :loading="loading"
      :empty="!loading && rows.length === 0"
      @change="changePage"
    >
      <template #empty>
        <LiquidGlassEmptyState v-if="loadError" compact title="服务加载失败" :description="loadError">
          <LiquidGlassButton @click="reload">重试</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else-if="hasFilters" compact title="没有匹配的服务">
          <LiquidGlassButton link type="primary" @click="resetFilters">清除筛选</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else compact title="还没有 MCP 服务" :description="canManage ? '选择业务方法或 Workflow，发布后配置接入。' : '管理员发布的服务会显示在这里。'">
          <LiquidGlassButton v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建 MCP 服务</LiquidGlassButton>
        </LiquidGlassEmptyState>
      </template>
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" :show-reset="false" @query="applyFilters">
          <LiquidGlassInput
            v-model="query.search"
            :prefix-icon="Search"
            clearable
            placeholder="搜索服务名称或说明"
            aria-label="搜索 MCP 服务"
            @clear="applyFilters"
          />
          <LiquidGlassSelect v-model="query.state" class="state-filter" clearable placeholder="全部状态" dropdown-title="服务状态" aria-label="服务状态" @change="applyFilters">
            <el-option v-for="option in stateOptions" :key="option.value" :label="option.label" :value="option.value" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>
      <el-table :data="rows" stripe empty-text=" " @row-click="openDetail">
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
        <el-table-column prop="updatedAt" label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <LiquidGlassButton link type="primary" @click.stop="openDetail(row)">{{ canManage ? (row.state === 'DRAFT' ? '继续配置' : '管理') : '查看' }}</LiquidGlassButton>
              <LiquidGlassButton v-if="row.state === 'PUBLISHED'" link type="primary" @click.stop="openDetail(row, true)">接入</LiquidGlassButton>
              <LiquidGlassDropdown v-if="canManage && row.state !== 'ARCHIVED'" trigger="click" title="服务管理">
                <LiquidGlassButton link :icon="MoreFilled" :aria-label="`${row.name} 的更多操作`" @click.stop />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="row.state === 'PUBLISHED'" @click="runStateAction('suspend', row)">暂停服务</el-dropdown-item>
                    <el-dropdown-item v-if="row.state === 'SUSPENDED'" @click="runStateAction('resume', row)">恢复服务</el-dropdown-item>
                    <el-dropdown-item @click="runStateAction('archive', row)">归档服务</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </LiquidGlassDropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog
      v-model="createVisible"
      title="新建 MCP 服务"
      description="创建后选择要开放的工具，发布后才可调用。"
      width="min(560px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item required>
          <template #label>
            服务名称
            <span class="form-hint">2-128 个字符，以字母开头，仅含字母、数字、下划线或连字符</span>
          </template>
          <el-input v-model="createForm.name" placeholder="例如 finance-tools" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="例如：查询客户、库存与订单"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="createVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="submitting" @click="submitCreate">创建并配置</LiquidGlassButton>
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

.row-actions { display: flex; align-items: center; gap: 10px; }

/* 状态是短值，筛选条里收窄，把宽度留给搜索框。 */
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter) {
  flex: 0 1 160px;
  max-width: 180px;
}

.cell-stack strong,
.cell-stack small {
  display: block;
}

.cell-stack strong {
  color: var(--text-primary);
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

@media (max-width: 760px) {
  .section-intro {
    align-items: stretch;
    flex-direction: column;
  }

  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter) {
    max-width: none;
  }
}
</style>
