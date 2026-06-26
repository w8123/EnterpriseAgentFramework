<template>
  <div class="platform-user-page">
    <div class="page-header">
      <div>
        <h2>平台用户与角色</h2>
        <p>维护管理端与 Workflow Studio 账号的角色授权，支持全局与项目两种作用域。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-table :data="users" v-loading="loading" stripe class="platform-user-table">
      <el-table-column prop="username" label="用户名" min-width="150" />
      <el-table-column prop="displayName" label="显示名" min-width="160">
        <template #default="{ row }">
          {{ formatPlatformUserDisplayName(row.displayName, row.username) }}
        </template>
      </el-table-column>
      <el-table-column prop="sourceProvider" label="来源" width="130">
        <template #default="{ row }">
          <el-tag size="small">{{ formatSourceProviderLabel(row.sourceProvider) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <CommonStatusTag :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最近登录" width="180" />
      <el-table-column label="角色授权" min-width="300">
        <template #default="{ row }">
          <div class="grant-list">
            <el-tag
              v-for="grant in displayGrantsForUser(row.id)"
              :key="`${grant.roleId}-${grant.scopeType}-${grant.scopeSummary}`"
              size="small"
              effect="plain"
            >
              {{ formatPlatformRoleLabel(grant.roleCode, grant.roleName) }} ·
              {{ formatScopeTypeLabel(grant.scopeType) }}:{{ grant.scopeSummary }}
            </el-tag>
            <span v-if="!displayGrantsForUser(row.id).length" class="empty-text">未分配</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openGrants(row)">分配角色</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" title="角色授权" width="820px">
      <div v-if="currentUser" class="dialog-user">
        <strong>{{ formatPlatformUserDisplayName(currentUser.displayName, currentUser.username) }}</strong>
        <span>{{ currentUser.username }} · {{ formatSourceProviderLabel(currentUser.sourceProvider) }}</span>
      </div>

      <div class="grant-editor">
        <div v-for="(grant, index) in editingGrants" :key="index" class="grant-row">
          <el-select v-model="grant.roleId" placeholder="选择角色" filterable>
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="formatPlatformRoleOptionLabel(role.roleCode, role.roleName)"
              :value="role.id"
            />
          </el-select>
          <el-select
            v-model="grant.scopeType"
            class="scope-type"
            @change="handleScopeTypeChange(grant)"
          >
            <el-option
              v-for="item in SCOPE_TYPE_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <div class="scope-value">
            <ProjectMultiSelect
              v-if="grant.scopeType === 'PROJECT'"
              v-model="grant.projectIds"
            />
            <el-input v-else model-value="全部项目" disabled />
          </div>
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
import CommonStatusTag from '@/components/CommonStatusTag.vue'
import ProjectMultiSelect from '@/components/ProjectMultiSelect.vue'
import {
  SCOPE_TYPE_SELECT_OPTIONS,
  formatPlatformRoleLabel,
  formatPlatformRoleOptionLabel,
  formatPlatformUserDisplayName,
  formatScopeTypeLabel,
  formatSourceProviderLabel,
} from '@/utils/uiLabels'
import {
  editorRowsToCommands,
  grantsToEditorRows,
  groupGrantsForDisplay,
  type PlatformGrantEditorRow,
} from '@/utils/platformUserGrants'
import {
  listPlatformRoles,
  listPlatformUserRoleGrants,
  listPlatformUsers,
  savePlatformUserRoleGrants,
} from '@/api/platformAuth'
import type { PlatformRoleView, PlatformUserRoleGrant, PlatformUserView } from '@/api/platformAuth'
import { useProjectStore } from '@/store/project'

const projectStore = useProjectStore()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const users = ref<PlatformUserView[]>([])
const roles = ref<PlatformRoleView[]>([])
const currentUser = ref<PlatformUserView | null>(null)
const grantsByUser = reactive<Record<number, PlatformUserRoleGrant[]>>({})
const editingGrants = ref<PlatformGrantEditorRow[]>([])

function displayGrantsForUser(userId: number) {
  return groupGrantsForDisplay(grantsByUser[userId] || [], projectStore.projects)
}

async function reload() {
  loading.value = true
  try {
    const [{ data: userData }, { data: roleData }] = await Promise.all([
      listPlatformUsers(),
      listPlatformRoles(),
      projectStore.fetchProjects(),
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
  await Promise.all([loadUserGrants(user.id), projectStore.fetchProjects()])
  editingGrants.value = grantsToEditorRows(grantsByUser[user.id] || [], projectStore.projects)
  if (!editingGrants.value.length) {
    addGrant()
  }
  dialogOpen.value = true
}

function addGrant() {
  editingGrants.value.push({
    roleId: roles.value[0]?.id ?? 0,
    scopeType: 'GLOBAL',
    projectIds: [],
  })
}

function handleScopeTypeChange(grant: PlatformGrantEditorRow) {
  if (grant.scopeType === 'PROJECT') {
    grant.projectIds = []
    return
  }
  grant.projectIds = []
}

function removeGrant(index: number) {
  editingGrants.value.splice(index, 1)
}

async function saveGrants() {
  if (!currentUser.value) return
  const invalidRole = editingGrants.value.some((grant) => !grant.roleId)
  const invalidProject = editingGrants.value.some(
    (grant) => grant.scopeType === 'PROJECT' && grant.projectIds.length === 0,
  )
  if (invalidRole) {
    ElMessage.warning('请选择角色')
    return
  }
  if (invalidProject) {
    ElMessage.warning('项目作用域请至少选择一个项目')
    return
  }
  saving.value = true
  try {
    const payload = editorRowsToCommands(editingGrants.value, projectStore.projects)
    await savePlatformUserRoleGrants(currentUser.value.id, payload)
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
  min-height: calc(100vh - 56px);
  padding: 28px 32px 40px;
  background: var(--brand-page-bg);
  background-size: 28px 28px, 28px 28px, auto, auto, auto, auto;
  color: var(--text-primary);
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  padding: 20px 22px;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.58);
  border-radius: 8px;
  background: var(--brand-glass-bg);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 18px 44px rgb(var(--brand-primary-rgb) / 0.12);
  backdrop-filter: blur(20px) saturate(1.08);

  h2 {
    margin: 0 0 8px;
    color: var(--text-primary);
    font-size: 24px;
    line-height: 1.25;
    font-weight: 800;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 14px;
    line-height: 1.6;
  }

  :deep(.el-button) {
    border-radius: 8px;
    border-color: rgb(var(--brand-selected-rgb) / 0.72);
    background: rgba(255, 255, 255, 0.72);
    color: var(--text-primary);

    &:hover {
      border-color: rgb(var(--brand-primary-rgb) / 0.32);
      background: rgb(var(--brand-selected-rgb) / 0.42);
      color: var(--brand-primary);
    }
  }
}

.platform-user-table {
  overflow: hidden;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.56);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.74);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.76),
    0 18px 42px rgb(var(--brand-primary-rgb) / 0.1);
  backdrop-filter: blur(18px);

  :deep(th.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.44);
    color: var(--brand-active);
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    color: var(--text-primary);
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.2);
  }
}

.grant-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.empty-text,
.dialog-user span {
  color: var(--text-secondary);
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
  grid-template-columns: minmax(200px, 1fr) 120px minmax(240px, 1.4fr) 40px;
  gap: 8px;
  align-items: center;
}

.scope-value {
  min-width: 0;
}

:global([data-theme="dark"]) {
  .platform-user-page {
    color: #e5e7eb;
  }

  .page-header,
  .platform-user-table {
    border-color: rgb(var(--brand-primary-rgb) / 0.28);
    background: linear-gradient(145deg, rgba(15, 23, 42, 0.82), rgb(var(--brand-primary-rgb) / 0.18));
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.08),
      0 18px 44px rgba(0, 0, 0, 0.24);
  }

  .platform-user-table {
    :deep(th.el-table__cell) {
      background: rgb(var(--brand-primary-rgb) / 0.24);
      color: var(--brand-selected-bg);
    }

    :deep(td.el-table__cell) {
      background: rgba(15, 23, 42, 0.56);
      color: #e2e8f0;
    }
  }
}

@media (max-width: 760px) {
  .grant-row {
    grid-template-columns: 1fr;
  }
}
</style>
