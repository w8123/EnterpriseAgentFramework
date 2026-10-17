<template>
  <WorkbenchPage class="runtime-user-mapping-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="Personal Memory Identity"
      title="运行时用户映射"
      description="把平台账号映射到指定租户下的 Runtime 用户，Agent 运行时据此召回该用户的个人记忆。"
    >
      <template #actions>
        <el-tooltip content="刷新映射" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新映射" @click="loadMappings" />
        </el-tooltip>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增映射</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="default 租户无需映射"
      description="default 租户默认使用平台用户 ID 作为 Runtime 用户；其他租户必须存在唯一的 ACTIVE 映射，否则个人记忆会被拒绝访问。"
    />

    <div class="mapping-filters">
      <el-input v-model="filters.tenantId" placeholder="租户 ID" style="width: 160px" @keyup.enter="loadMappings" />
      <el-input-number
        v-model="filters.platformUserId"
        :min="1"
        controls-position="right"
        placeholder="平台用户 ID"
        style="width: 160px"
      />
      <el-input
        v-model="filters.runtimeUserId"
        clearable
        placeholder="Runtime 用户 ID"
        style="width: 220px"
        @keyup.enter="loadMappings"
      />
      <el-select v-model="filters.status" style="width: 120px">
        <el-option label="ACTIVE" value="ACTIVE" />
        <el-option label="DELETED" value="DELETED" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="loadMappings">查询</el-button>
    </div>

    <el-table :data="mappings" v-loading="loading" stripe>
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
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" type="danger" :disabled="row.status !== 'ACTIVE'" @click="handleDelete(row)">
            撤销
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && mappings.length === 0" description="暂无映射" />

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
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMapping">创建映射</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import {
  createContextRuntimeUserMapping,
  deleteContextRuntimeUserMapping,
  listContextRuntimeUserMappings,
} from '@/api/context'
import type { ContextRuntimeUserMapping } from '@/types/context'

const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const mappings = ref<ContextRuntimeUserMapping[]>([])

const filters = reactive({
  tenantId: 'default',
  platformUserId: undefined as number | undefined,
  runtimeUserId: '',
  status: 'ACTIVE',
})

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
    const { data } = await listContextRuntimeUserMappings({
      tenantId: filters.tenantId.trim(),
      platformUserId: filters.platformUserId || undefined,
      runtimeUserId: filters.runtimeUserId.trim() || undefined,
      status: filters.status || undefined,
      limit: 200,
    })
    mappings.value = data ?? []
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
    await createContextRuntimeUserMapping({
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

async function handleDelete(row: ContextRuntimeUserMapping) {
  try {
    await ElMessageBox.confirm(
      `撤销后平台用户 ${row.platformUserId} 在租户 ${row.tenantId} 下将无法访问 Runtime 用户 ${row.runtimeUserId} 的个人记忆。`,
      '撤销映射',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await deleteContextRuntimeUserMapping(row.id)
  ElMessage.success('映射已撤销')
  await loadMappings()
}

onMounted(loadMappings)
</script>

<style scoped>
.mapping-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
</style>
