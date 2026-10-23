<template>
  <LiquidGlassPage class="runtime-user-mapping-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="governance"
      title="运行时用户映射"
      description="关联平台账号与运行时用户，定位个人记忆的所属身份。"
    >
      <template #actions>
        <el-tooltip content="刷新映射" placement="top">
          <LiquidGlassButton source="wallpaper" circle :icon="Refresh" :loading="loading" aria-label="刷新映射" @click="loadMappings" />
        </el-tooltip>
        <LiquidGlassButton source="wallpaper" type="primary" :icon="Plus" @click="openCreate">新增映射</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassNotice source="wallpaper" title="default 租户无需映射">
      default 租户默认使用平台用户 ID 作为 Runtime 用户；其他租户必须存在唯一的 ACTIVE 映射，否则个人记忆会被拒绝访问。
    </LiquidGlassNotice>

    <LiquidGlassTable
      class="workbench-list-surface"
      :loading="loading"
      :empty="loaded && mappings.length === 0"
      empty-description="暂无映射"
      :show-pagination="false"
    >
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" query-label="查询" @query="loadMappings" @reset="resetFilters">
          <LiquidGlassInput v-model="filters.tenantId" class="mapping-filter is-short" placeholder="租户 ID" aria-label="租户 ID" />
          <LiquidGlassInput v-model="platformUserIdText" class="mapping-filter is-short" inputmode="numeric" placeholder="平台用户 ID" aria-label="平台用户 ID" clearable />
          <LiquidGlassInput v-model="filters.runtimeUserId" :prefix-icon="Search" clearable placeholder="Runtime 用户 ID" aria-label="Runtime 用户 ID" />
          <LiquidGlassSelect v-model="filters.status" class="mapping-filter is-short" dropdown-title="映射状态" aria-label="映射状态">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DELETED" value="DELETED" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>
      <el-table :data="mappings" stripe>
        <el-table-column prop="tenantId" label="租户" width="120" />
        <el-table-column prop="platformUserId" label="平台用户" width="110" />
        <el-table-column prop="runtimeUserId" label="Runtime 用户" min-width="180" show-overflow-tooltip />
        <el-table-column prop="globalUserId" label="globalUserId" min-width="150" show-overflow-tooltip />
        <el-table-column prop="externalUserId" label="externalUserId" min-width="150" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建者" width="100" />
        <el-table-column prop="updatedAt" label="更新时间" width="170" :formatter="formatTableDateTime" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="danger" :disabled="row.status !== 'ACTIVE'" @click="handleDelete(row)">
              撤销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="dialogOpen" title="新增运行时用户映射" width="520px" destroy-on-close>
      <el-form :model="form" label-width="130px">
        <el-form-item label="租户 ID" required>
          <el-input v-model="form.tenantId" maxlength="96" />
        </el-form-item>
        <el-form-item label="平台用户 ID" required>
          <el-input-number v-model="form.platformUserId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="Runtime 用户 ID">
          <el-input v-model="form.runtimeUserId" placeholder="留空时使用 globalUserId / externalUserId" />
        </el-form-item>
        <el-form-item label="globalUserId">
          <el-input v-model="form.globalUserId" />
        </el-form-item>
        <el-form-item label="externalUserId">
          <el-input v-model="form.externalUserId" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="dialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="saveMapping">创建映射</LiquidGlassButton>
      </template>
    </AppDialog>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { formatTableDateTime } from '@/utils/dateTime'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import {
  createRuntimeUserMapping,
  deleteRuntimeUserMapping,
  listRuntimeUserMappings,
} from '@/api/runtimeUserMapping'
import type { RuntimeUserMapping } from '@/types/runtimeUserMapping'

const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const mappings = ref<RuntimeUserMapping[]>([])
const loaded = ref(false)

const filters = reactive({
  tenantId: 'default',
  platformUserId: undefined as number | undefined,
  runtimeUserId: '',
  status: 'ACTIVE',
})

// 平台用户 ID 在筛选条里用单行输入，只接受正整数。
const platformUserIdText = computed({
  get: () => (filters.platformUserId ? String(filters.platformUserId) : ''),
  set: (value: string) => {
    const parsed = Number.parseInt(value.replace(/\D/g, ''), 10)
    filters.platformUserId = Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  },
})

function resetFilters() {
  Object.assign(filters, { tenantId: 'default', platformUserId: undefined, runtimeUserId: '', status: 'ACTIVE' })
  loadMappings()
}

const form = reactive({
  tenantId: 'default',
  platformUserId: undefined as number | undefined,
  runtimeUserId: '',
  globalUserId: '',
  externalUserId: '',
})

async function loadMappings() {
  if (!filters.tenantId.trim()) {
    ElMessage.warning('请填写租户 ID')
    return
  }
  loading.value = true
  try {
    const { data } = await listRuntimeUserMappings({
      tenantId: filters.tenantId.trim(),
      platformUserId: filters.platformUserId || undefined,
      runtimeUserId: filters.runtimeUserId.trim() || undefined,
      status: filters.status || undefined,
      limit: 200,
    })
    mappings.value = data ?? []
    loaded.value = true
  } catch {
    mappings.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    tenantId: filters.tenantId.trim() || 'default',
    platformUserId: undefined,
    runtimeUserId: '',
    globalUserId: '',
    externalUserId: '',
  })
  dialogOpen.value = true
}

async function saveMapping() {
  if (!form.tenantId.trim() || !form.platformUserId) {
    ElMessage.warning('请填写租户 ID 和平台用户 ID')
    return
  }
  if (!form.runtimeUserId.trim() && !form.globalUserId.trim() && !form.externalUserId.trim()) {
    ElMessage.warning('请至少填写 Runtime 用户 ID、globalUserId 或 externalUserId 之一')
    return
  }
  saving.value = true
  try {
    await createRuntimeUserMapping({
      tenantId: form.tenantId.trim(),
      platformUserId: form.platformUserId,
      runtimeUserId: form.runtimeUserId.trim() || undefined,
      globalUserId: form.globalUserId.trim() || undefined,
      externalUserId: form.externalUserId.trim() || undefined,
    })
    ElMessage.success('映射已创建')
    dialogOpen.value = false
    await loadMappings()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: RuntimeUserMapping) {
  try {
    await ElMessageBox.confirm(
      `撤销后平台用户 ${row.platformUserId} 在租户 ${row.tenantId} 下将无法访问 Runtime 用户 ${row.runtimeUserId} 的个人记忆。`,
      '撤销映射',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await deleteRuntimeUserMapping(row.id)
  ElMessage.success('映射已撤销')
  await loadMappings()
}

onMounted(loadMappings)
</script>

<style scoped>
/* 租户、平台用户和状态是短值，筛选条里收窄，把宽度留给 Runtime 用户 ID。 */
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.mapping-filter.is-short) {
  flex: 1 1 130px;
  max-width: 170px;
}
</style>
