<template>
  <div class="platform-user-page">
    <div class="page-header">
      <div>
        <h2>平台用户与角色</h2>
        <p>维护 Agent Studio 管理端账号的角色授权，支持 GLOBAL 和 PROJECT 作用域。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="username" label="用户名" min-width="150" />
      <el-table-column prop="displayName" label="显示名" min-width="160" />
      <el-table-column prop="sourceProvider" label="来源" width="130">
        <template #default="{ row }">
          <el-tag size="small">{{ row.sourceProvider }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最近登录" width="180" />
      <el-table-column label="角色授权" min-width="280">
        <template #default="{ row }">
          <div class="grant-list">
            <el-tag
              v-for="grant in grantsByUser[row.id] || []"
              :key="`${grant.roleId}-${grant.scopeType}-${grant.scopeValue}`"
              size="small"
              effect="plain"
            >
              {{ grant.roleCode || grant.roleId }} · {{ grant.scopeType }}:{{ grant.scopeValue }}
            </el-tag>
            <span v-if="!(grantsByUser[row.id] || []).length" class="empty-text">未分配</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openGrants(row)">分配角色</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" title="角色授权" width="760px">
      <div v-if="currentUser" class="dialog-user">
        <strong>{{ currentUser.displayName || currentUser.username }}</strong>
        <span>{{ currentUser.username }} · {{ currentUser.sourceProvider }}</span>
      </div>

      <div class="grant-editor">
        <div v-for="(grant, index) in editingGrants" :key="index" class="grant-row">
          <el-select v-model="grant.roleId" placeholder="选择角色" filterable>
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="`${role.roleCode} · ${role.roleName}`"
              :value="role.id"
            />
          </el-select>
          <el-select v-model="grant.scopeType" class="scope-type">
            <el-option label="GLOBAL" value="GLOBAL" />
            <el-option label="PROJECT" value="PROJECT" />
          </el-select>
          <el-input
            v-model="grant.scopeValue"
            :placeholder="grant.scopeType === 'PROJECT' ? 'projectId / projectCode' : '*'"
          />
          <el-button :icon="Delete" circle @click="removeGrant(index)" />
        </div>
      </div>

      <el-button :icon="Plus" @click="addGrant">新增授权</el-button>

      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGrants">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import {
  listPlatformRoles,
  listPlatformUserRoleGrants,
  listPlatformUsers,
  savePlatformUserRoleGrants,
} from '@/api/platformAuth'
import type {
  PlatformRoleView,
  PlatformUserRoleGrant,
  PlatformUserRoleGrantCommand,
  PlatformUserView,
} from '@/api/platformAuth'

const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const users = ref<PlatformUserView[]>([])
const roles = ref<PlatformRoleView[]>([])
const currentUser = ref<PlatformUserView | null>(null)
const grantsByUser = reactive<Record<number, PlatformUserRoleGrant[]>>({})
const editingGrants = ref<PlatformUserRoleGrantCommand[]>([])

async function reload() {
  loading.value = true
  try {
    const [{ data: userData }, { data: roleData }] = await Promise.all([
      listPlatformUsers(),
      listPlatformRoles(),
    ])
    users.value = userData ?? []
    roles.value = (roleData ?? []).filter((role) => role.status === 'ACTIVE')
    await Promise.all(users.value.map((user) => loadUserGrants(user.id)))
  } finally {
    loading.value = false
  }
}

async function loadUserGrants(userId: number) {
  const { data } = await listPlatformUserRoleGrants(userId)
  grantsByUser[userId] = data ?? []
}

async function openGrants(user: PlatformUserView) {
  currentUser.value = user
  await loadUserGrants(user.id)
  editingGrants.value = (grantsByUser[user.id] || []).map((grant) => ({
    roleId: grant.roleId,
    scopeType: grant.scopeType || 'GLOBAL',
    scopeValue: grant.scopeValue || '*',
  }))
  dialogOpen.value = true
}

function addGrant() {
  editingGrants.value.push({
    roleId: roles.value[0]?.id ?? 0,
    scopeType: 'GLOBAL',
    scopeValue: '*',
  })
}

function removeGrant(index: number) {
  editingGrants.value.splice(index, 1)
}

async function saveGrants() {
  if (!currentUser.value) return
  const invalid = editingGrants.value.some((grant) => !grant.roleId || !grant.scopeType || !grant.scopeValue)
  if (invalid) {
    ElMessage.warning('请完整填写角色、作用域类型和作用域值')
    return
  }
  saving.value = true
  try {
    await savePlatformUserRoleGrants(currentUser.value.id, editingGrants.value)
    await loadUserGrants(currentUser.value.id)
    ElMessage.success('角色授权已保存')
    dialogOpen.value = false
  } finally {
    saving.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.platform-user-page {
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

.grant-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.empty-text,
.dialog-user span {
  color: #667085;
  font-size: 13px;
}

.dialog-user {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.grant-editor {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.grant-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 130px minmax(160px, 1fr) 40px;
  gap: 8px;
  align-items: center;
}

@media (max-width: 760px) {
  .grant-row {
    grid-template-columns: 1fr;
  }
}
</style>
