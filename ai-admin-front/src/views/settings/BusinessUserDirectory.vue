<template>
  <LiquidGlassPage class="business-user-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="业务用户目录"
      description="查看业务用户、外部身份映射与角色绑定。"
    >
      <template v-if="loaded" #tags>
        <el-tag type="info" effect="light">共 {{ pagination.total }} 位业务用户</el-tag>
      </template>
      <template #actions>
        <el-tooltip content="刷新业务用户" placement="top">
          <LiquidGlassButton source="wallpaper" circle :icon="Refresh" :loading="loading" aria-label="刷新业务用户" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <LiquidGlassTable
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.size"
      class="business-user-list workbench-list-surface"
      :loading="loading"
      :empty="loaded && users.length === 0"
      empty-description="暂无符合条件的业务用户"
      :total="pagination.total"
      :page-sizes="[20, 50, 100]"
      pagination-label="业务用户分页"
      @change="reload"
    >
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" query-label="查询" @query="search" @reset="resetFilters">
          <LiquidGlassInput
            v-model="filters.keyword"
            clearable
            placeholder="搜索统一用户、姓名、邮箱、手机号"
            aria-label="搜索业务用户"
            :prefix-icon="Search"
          />
          <LiquidGlassInput
            v-model="filters.tenantId"
            class="business-user-filter__tenant"
            clearable
            placeholder="租户，如 default"
            aria-label="租户"
          />
          <LiquidGlassSelect v-model="filters.status" class="business-user-filter__status" clearable placeholder="状态" dropdown-title="用户状态" aria-label="用户状态">
            <el-option
              v-for="item in BUSINESS_USER_STATUS_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>
      <el-table :data="users" empty-text="" stripe>
      <el-table-column prop="globalUserId" label="统一用户 ID" min-width="150">
        <template #default="{ row }">
          <code>{{ row.globalUserId }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="displayName" label="姓名" min-width="130" />
      <el-table-column label="联系方式" min-width="190">
        <template #default="{ row }">
          <div class="muted">{{ row.email || '-' }}</div>
          <div class="muted">{{ row.mobile || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="tenantId" label="租户" width="110" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <CommonStatusTag :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="外部身份" min-width="220">
        <template #default="{ row }">
          <div class="tag-list">
            <el-tag v-for="item in row.externalIdentities" :key="item" size="small" type="info">
              {{ item }}
            </el-tag>
            <span v-if="!row.externalIdentities?.length" class="muted">无</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色绑定" min-width="220">
        <template #default="{ row }">
          <div class="tag-list">
            <el-tag v-for="item in row.roleCodes" :key="item" size="small">
              {{ formatPlatformRoleLabel(item) }}
            </el-tag>
            <span v-if="!row.roleCodes?.length" class="muted">无</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="lastSeenAt" label="最近出现" min-width="170" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canManageBusinessUsers" link type="primary" @click="openUserDialog(row)">校正资料</el-button>
          <el-button link @click="openIdentityDrawer(row)">
            {{ canManageBusinessUsers ? '身份映射' : '查看映射' }}
          </el-button>
        </template>
      </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="userDialogOpen" title="校正业务用户资料" width="520px">
      <el-form v-if="editingUser" :model="editingUser" label-width="110px">
        <el-form-item label="统一用户 ID">
          <el-input v-model="editingUser.globalUserId" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="editingUser.displayName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editingUser.email" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editingUser.mobile" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editingUser.status">
            <el-option
              v-for="item in BUSINESS_USER_STATUS_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="userDialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="saving" @click="saveUser">保存</LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDrawer v-model="identityDrawerOpen" size="560px" title="外部身份映射">
      <template v-if="selectedUser">
        <div class="drawer-user">
          <strong>{{ selectedUser.displayName || selectedUser.globalUserId }}</strong>
          <span>{{ selectedUser.tenantId }} / {{ selectedUser.globalUserId }}</span>
        </div>

        <el-table :data="identities" v-loading="identityLoading" size="small" stripe>
          <el-table-column label="外部账号" min-width="170">
            <template #default="{ row }">
              <code>{{ row.appId }}:{{ row.externalUserId }}</code>
              <div class="muted">{{ row.externalUserName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="部门" min-width="130">
            <template #default="{ row }">
              <div>{{ row.deptName || '-' }}</div>
              <div class="muted">{{ row.deptId || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="160">
            <template #default="{ row }">
              <div class="tag-list">
                <el-tag v-for="role in row.roles" :key="role.id" size="small">
                  {{ formatPlatformRoleLabel(role.roleCode, role.roleName) }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column v-if="canManageBusinessUsers" label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="primary" @click="editIdentity(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="canManageBusinessUsers" class="identity-form">
          <h3>{{ identityForm.id ? '编辑映射' : '新增映射' }}</h3>
          <el-form :model="identityForm" label-width="100px">
            <el-form-item label="应用 ID" required>
              <el-input v-model="identityForm.appId" placeholder="bzsdk / portal" />
            </el-form-item>
            <el-form-item label="外部用户 ID" required>
              <el-input v-model="identityForm.externalUserId" />
            </el-form-item>
            <el-form-item label="外部姓名">
              <el-input v-model="identityForm.externalUserName" />
            </el-form-item>
            <el-form-item label="部门">
              <el-input v-model="identityForm.deptName" placeholder="部门名称" />
            </el-form-item>
            <el-form-item label="角色">
              <el-input v-model="identityForm.rolesRaw" placeholder="多个角色用逗号分隔，如 admin,operator" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="identityForm.status">
                <el-option
                  v-for="item in BUSINESS_USER_STATUS_SELECT_OPTIONS"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button :loading="saving" type="primary" @click="saveIdentity">保存映射</el-button>
              <el-button @click="resetIdentityForm">清空</el-button>
            </el-form-item>
          </el-form>
        </div>
      </template>
    </AppDrawer>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  hasPlatformPermission,
  PLATFORM_PERMISSION_BUSINESS_USER_MANAGE,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import CommonStatusTag from '@/components/CommonStatusTag.vue'
import { BUSINESS_USER_STATUS_SELECT_OPTIONS, formatPlatformRoleLabel } from '@/utils/uiLabels'
import {
  listBusinessUserIdentities,
  listBusinessUsers,
  saveBusinessUserIdentity,
  updateBusinessUser,
} from '@/api/platformIdentity'
import type {
  BusinessUserView,
  ExternalIdentityCommand,
  ExternalIdentityView,
} from '@/api/platformIdentity'

const loading = ref(false)
const saving = ref(false)
const identityLoading = ref(false)
const users = ref<BusinessUserView[]>([])
const identities = ref<ExternalIdentityView[]>([])
const selectedUser = ref<BusinessUserView | null>(null)
const editingUser = ref<(BusinessUserView & { email?: string; mobile?: string }) | null>(null)
const userDialogOpen = ref(false)
const identityDrawerOpen = ref(false)
const canManageBusinessUsers = computed(() => hasPlatformPermission(
  platformSessionUser.value?.permissions,
  PLATFORM_PERMISSION_BUSINESS_USER_MANAGE,
))

const filters = reactive({
  keyword: '',
  tenantId: 'default',
  status: '',
})
const pagination = reactive({ current: 1, size: 20, total: 0 })
const loaded = ref(false)
const identityForm = reactive({
  id: undefined as number | undefined,
  appId: '',
  externalUserId: '',
  externalUserName: '',
  deptId: '',
  deptName: '',
  status: 'ACTIVE',
  rolesRaw: '',
})

async function reload() {
  loading.value = true
  try {
    const { data } = await listBusinessUsers({
      current: pagination.current,
      size: pagination.size,
      keyword: filters.keyword || undefined,
      tenantId: filters.tenantId || undefined,
      status: filters.status || undefined,
    })
    users.value = data?.records ?? []
    pagination.total = data?.total ?? 0
    loaded.value = true
  } finally {
    loading.value = false
  }
}

function search() {
  pagination.current = 1
  reload()
}

function resetFilters() {
  Object.assign(filters, { keyword: '', tenantId: 'default', status: '' })
  search()
}

function openUserDialog(row: BusinessUserView) {
  if (!canManageBusinessUsers.value) return
  editingUser.value = { ...row }
  userDialogOpen.value = true
}

async function saveUser() {
  if (!canManageBusinessUsers.value || !editingUser.value) return
  saving.value = true
  try {
    await updateBusinessUser(editingUser.value.id, {
      globalUserId: editingUser.value.globalUserId,
      displayName: editingUser.value.displayName,
      email: editingUser.value.email,
      mobile: editingUser.value.mobile,
      status: editingUser.value.status,
    })
    ElMessage.success('已保存业务用户资料')
    userDialogOpen.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

async function openIdentityDrawer(row: BusinessUserView) {
  selectedUser.value = row
  identityDrawerOpen.value = true
  resetIdentityForm()
  await reloadIdentities()
}

async function reloadIdentities() {
  if (!selectedUser.value) return
  identityLoading.value = true
  try {
    const { data } = await listBusinessUserIdentities(selectedUser.value.id)
    identities.value = data ?? []
  } finally {
    identityLoading.value = false
  }
}

function editIdentity(row: ExternalIdentityView) {
  if (!canManageBusinessUsers.value) return
  identityForm.id = row.id
  identityForm.appId = row.appId
  identityForm.externalUserId = row.externalUserId
  identityForm.externalUserName = row.externalUserName
  identityForm.deptId = row.deptId || ''
  identityForm.deptName = row.deptName || ''
  identityForm.status = row.status || 'ACTIVE'
  identityForm.rolesRaw = row.roles.map((role) => role.roleCode).join(',')
}

function resetIdentityForm() {
  identityForm.id = undefined
  identityForm.appId = ''
  identityForm.externalUserId = ''
  identityForm.externalUserName = ''
  identityForm.deptId = ''
  identityForm.deptName = ''
  identityForm.status = 'ACTIVE'
  identityForm.rolesRaw = ''
}

async function saveIdentity() {
  if (!canManageBusinessUsers.value || !selectedUser.value) return
  if (!identityForm.appId || !identityForm.externalUserId) {
    ElMessage.warning('请填写应用 ID 和外部用户 ID')
    return
  }
  const body: ExternalIdentityCommand = {
    id: identityForm.id,
    appId: identityForm.appId,
    externalUserId: identityForm.externalUserId,
    externalUserName: identityForm.externalUserName,
    deptId: identityForm.deptId,
    deptName: identityForm.deptName,
    status: identityForm.status,
    roles: identityForm.rolesRaw
      .split(/[,，\s]+/)
      .map((role) => role.trim())
      .filter(Boolean),
  }
  saving.value = true
  try {
    await saveBusinessUserIdentity(selectedUser.value.id, body)
    ElMessage.success('已保存外部身份映射')
    resetIdentityForm()
    await reloadIdentities()
    await reload()
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
// 租户和状态是短值，筛选条里收窄，把宽度留给关键词。
.business-user-list :deep(.filter-bar__fields > .liquid-glass-control.business-user-filter__tenant),
.business-user-list :deep(.filter-bar__fields > .liquid-glass-control.business-user-filter__status) {
  flex: 1 1 150px;
  max-width: 190px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.muted {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--text-secondary);
}

.drawer-user {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;

  span {
    color: var(--text-muted);
    font-size: 12px;
  }
}

.identity-form {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--border-divider);

  h3 {
    margin: 0 0 12px;
    font-size: 15px;
  }
}

</style>
