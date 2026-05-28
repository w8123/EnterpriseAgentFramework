<template>
  <div class="business-user-page">
    <div class="page-header">
      <div>
        <h2>业务用户目录</h2>
        <p>统一查看平台内业务用户、外部身份映射和角色绑定，支持人工校正跨系统账号关系。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        clearable
        placeholder="搜索统一用户、姓名、邮箱、手机号"
        :prefix-icon="Search"
        @keyup.enter="search"
      />
      <el-input v-model="filters.tenantId" clearable placeholder="租户，如 default" @keyup.enter="search" />
      <el-select v-model="filters.status" clearable placeholder="状态" style="width: 140px">
        <el-option label="ACTIVE" value="ACTIVE" />
        <el-option label="DISABLED" value="DISABLED" />
        <el-option label="DELETED" value="DELETED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
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
          <el-tag size="small" :type="statusTagType(row.status)">{{ row.status }}</el-tag>
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
              {{ item }}
            </el-tag>
            <span v-if="!row.roleCodes?.length" class="muted">无</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="lastSeenAt" label="最近出现" min-width="170" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openUserDialog(row)">校正资料</el-button>
          <el-button link @click="openIdentityDrawer(row)">身份映射</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.size"
      :total="pagination.total"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="reload"
      @size-change="reload"
    />

    <el-dialog v-model="userDialogOpen" title="校正业务用户资料" width="520px">
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
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
            <el-option label="DELETED" value="DELETED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="identityDrawerOpen" size="560px" title="外部身份映射">
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
                  {{ role.roleCode }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="primary" @click="editIdentity(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="identity-form">
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
                <el-option label="ACTIVE" value="ACTIVE" />
                <el-option label="DISABLED" value="DISABLED" />
                <el-option label="DELETED" value="DELETED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button :loading="saving" type="primary" @click="saveIdentity">保存映射</el-button>
              <el-button @click="resetIdentityForm">清空</el-button>
            </el-form-item>
          </el-form>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
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

const filters = reactive({
  keyword: '',
  tenantId: 'default',
  status: '',
})
const pagination = reactive({ current: 1, size: 20, total: 0 })
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

function statusTagType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DISABLED') return 'warning'
  if (status === 'DELETED') return 'danger'
  return 'info'
}

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
  } finally {
    loading.value = false
  }
}

function search() {
  pagination.current = 1
  reload()
}

function openUserDialog(row: BusinessUserView) {
  editingUser.value = { ...row }
  userDialogOpen.value = true
}

async function saveUser() {
  if (!editingUser.value) return
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
  if (!selectedUser.value) return
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
.business-user-page {
  padding: 16px 20px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;

  h2 {
    margin: 0 0 4px;
    font-size: 20px;
  }

  p {
    margin: 0;
    color: #667085;
    font-size: 13px;
  }
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 180px 140px auto;
  gap: 10px;
  margin-bottom: 12px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.muted {
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #344054;
}

.el-pagination {
  justify-content: flex-end;
  margin-top: 12px;
}

.drawer-user {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;

  span {
    color: #667085;
    font-size: 12px;
  }
}

.identity-form {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #eaecf0;

  h3 {
    margin: 0 0 12px;
    font-size: 15px;
  }
}

@media (max-width: 920px) {
  .toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
