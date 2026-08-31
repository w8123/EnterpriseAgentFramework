<template>
  <section class="identity-panel">
    <div class="panel-toolbar">
      <div class="panel-toolbar__filters">
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索用户名、姓名、邮箱或手机号" />
        <el-select v-model="statusFilter" class="compact-filter" aria-label="账号状态">
          <el-option label="全部状态" value="ALL" />
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
        <el-select v-model="sourceFilter" class="compact-filter" aria-label="账号来源">
          <el-option label="全部来源" value="ALL" />
          <el-option label="本地账号" value="LOCAL" />
          <el-option label="外部身份" value="EXTERNAL" />
        </el-select>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建账号</el-button>
    </div>

    <div class="table-shell">
      <el-table :data="pagedAccounts" v-loading="loading" row-key="id">
        <el-table-column label="账号" min-width="205">
          <template #default="{ row }">
            <div class="identity-cell">
              <span class="identity-avatar">{{ initials(row) }}</span>
              <div>
                <strong>{{ row.displayName || row.username }}</strong>
                <span>{{ row.username }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="身份来源" width="116">
          <template #default="{ row }">
            <span class="source-label">
              <el-icon><Key v-if="row.sourceProvider === 'LOCAL'" /><Connection v-else /></el-icon>
              {{ row.sourceProvider === 'LOCAL' ? '本地密码' : row.sourceProvider }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="角色与范围" min-width="280">
          <template #default="{ row }">
            <div v-if="grantSummaries(row).length" class="tag-list">
              <el-tag
                v-for="grant in grantSummaries(row)"
                :key="`${grant.roleId}-${grant.scopeType}`"
                effect="plain"
                size="small"
              >
                {{ grant.roleName || grant.roleCode }} · {{ grant.scopeType === 'GLOBAL' ? '全局' : grant.scopeSummary }}
              </el-tag>
            </div>
            <span v-else class="muted">未授权</span>
          </template>
        </el-table-column>
        <el-table-column label="活跃会话" width="92" align="center">
          <template #default="{ row }">
            <span class="session-count" :class="{ active: row.activeSessionCount > 0 }">
              {{ row.activeSessionCount || 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="165">
          <template #default="{ row }">{{ formatTime(row.lastLoginAt, '从未登录') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="104" fixed="right" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openGrants(row)">授权</el-button>
            <el-dropdown trigger="click" @command="handleAccountCommand($event, row)">
              <el-button link :icon="MoreFilled" aria-label="更多账号操作" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit" :icon="Edit">编辑资料</el-dropdown-item>
                  <el-dropdown-item
                    v-if="row.sourceProvider === 'LOCAL'"
                    command="password"
                    :icon="Key"
                  >重置密码</el-dropdown-item>
                  <el-dropdown-item
                    command="revoke"
                    :icon="SwitchButton"
                    :disabled="row.activeSessionCount === 0"
                    divided
                  >撤销全部会话</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的平台账号" />
        </template>
      </el-table>
    </div>

    <div v-if="filteredAccounts.length > pageSize" class="pagination-row">
      <span>共 {{ filteredAccounts.length }} 个账号</span>
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        layout="prev, pager, next"
        :total="filteredAccounts.length"
      />
    </div>

    <AppDialog
      v-model="accountDialogOpen"
      :title="accountDialogMode === 'create' ? '新建平台账号' : '编辑账号资料'"
      :description="accountDialogMode === 'create' ? '创建用于管理端和设计工作台的人员账号；初始权限遵循最小授权原则。' : '用户名和身份来源不可修改；停用账号会立即撤销其全部登录会话。'"
      width="720px"
      destroy-on-close
    >
      <el-form label-position="top" class="account-form">
        <div class="form-grid">
          <el-form-item label="用户名" required>
            <el-input
              v-model="accountForm.username"
              :disabled="accountDialogMode === 'edit'"
              maxlength="96"
              placeholder="例如 zhang.san"
            />
          </el-form-item>
          <el-form-item label="姓名 / 显示名" required>
            <el-input v-model="accountForm.displayName" maxlength="128" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="accountForm.email" maxlength="128" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="accountForm.mobile" maxlength="64" />
          </el-form-item>
          <el-form-item v-if="accountDialogMode === 'create'" label="初始密码" required>
            <el-input v-model="accountForm.password" type="password" show-password autocomplete="new-password" />
            <div class="field-help" :class="{ 'has-error': accountForm.password && passwordIssues.length }">
              12–128 位，至少包含大写、小写、数字、符号中的三类
            </div>
          </el-form-item>
          <el-form-item label="账号状态">
            <el-radio-group v-model="accountForm.status">
              <el-radio-button value="ACTIVE">启用</el-radio-button>
              <el-radio-button
                value="INACTIVE"
                :disabled="accountDialogMode === 'edit' && editingAccountId === currentUserId"
              >停用</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>

        <template v-if="accountDialogMode === 'create'">
          <el-divider content-position="left">初始角色授权（可选）</el-divider>
          <PlatformGrantEditor v-model="accountGrants" :roles="roles" />
        </template>
      </el-form>
      <template #footer>
        <el-button @click="accountDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAccount">
          {{ accountDialogMode === 'create' ? '创建账号' : '保存资料' }}
        </el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="grantDialogOpen"
      title="角色与数据范围"
      :description="grantAccount ? `为 ${grantAccount.displayName || grantAccount.username} 配置权限包及其生效范围。项目权限优先使用指定项目，避免无意授予全局访问。` : ''"
      width="920px"
      destroy-on-close
    >
      <el-alert
        v-if="grantAccount?.status !== 'ACTIVE'"
        type="warning"
        :closable="false"
        title="停用账号不能调整授权；请先启用账号。"
        show-icon
      />
      <PlatformGrantEditor v-else v-model="editingGrants" :roles="roles" />
      <template #footer>
        <el-button @click="grantDialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="grantAccount?.status !== 'ACTIVE'"
          @click="saveGrants"
        >保存授权</el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="passwordDialogOpen"
      title="重置本地密码"
      :description="passwordAccount ? `将为 ${passwordAccount.displayName || passwordAccount.username} 设置新密码，并立即撤销其全部登录会话。` : ''"
      width="500px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="新密码" required>
          <el-input v-model="newPassword" type="password" show-password autocomplete="new-password" />
          <div class="field-help" :class="{ 'has-error': newPassword && resetPasswordIssues.length }">
            12–128 位，至少包含大写、小写、数字、符号中的三类
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogOpen = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submitPasswordReset">重置并下线</el-button>
      </template>
    </AppDialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Connection,
  Edit,
  Key,
  MoreFilled,
  Plus,
  Search,
  SwitchButton,
} from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  createPlatformAccount,
  resetPlatformAccountPassword,
  revokePlatformAccountSessions,
  savePlatformUserRoleGrants,
  updatePlatformAccount,
  type PlatformAccountUserView,
  type PlatformRoleManagementView,
} from '@/api/platformAuth'
import { platformSessionUser } from '@/auth/platformSession'
import { passwordPolicyIssues } from '@/auth/platformAccountManagement'
import { useProjectStore } from '@/store/project'
import {
  editorRowsToCommands,
  grantsToEditorRows,
  groupGrantsForDisplay,
  type PlatformGrantEditorRow,
} from '@/utils/platformUserGrants'
import PlatformGrantEditor from './PlatformGrantEditor.vue'

const props = defineProps<{
  accounts: PlatformAccountUserView[]
  roles: PlatformRoleManagementView[]
  loading: boolean
}>()

const emit = defineEmits<{ changed: [] }>()
const projectStore = useProjectStore()
const keyword = ref('')
const statusFilter = ref('ALL')
const sourceFilter = ref('ALL')
const currentPage = ref(1)
const pageSize = 20
const saving = ref(false)
const accountDialogOpen = ref(false)
const accountDialogMode = ref<'create' | 'edit'>('create')
const editingAccountId = ref<number | null>(null)
const accountGrants = ref<PlatformGrantEditorRow[]>([])
const grantDialogOpen = ref(false)
const grantAccount = ref<PlatformAccountUserView | null>(null)
const editingGrants = ref<PlatformGrantEditorRow[]>([])
const passwordDialogOpen = ref(false)
const passwordAccount = ref<PlatformAccountUserView | null>(null)
const newPassword = ref('')

interface AccountFormState {
  username: string
  displayName: string
  email: string
  mobile: string
  password: string
  status: 'ACTIVE' | 'INACTIVE'
}

const accountForm = reactive<AccountFormState>({
  username: '',
  displayName: '',
  email: '',
  mobile: '',
  password: '',
  status: 'ACTIVE',
})

const currentUserId = computed(() => platformSessionUser.value?.userId ?? null)
const passwordIssues = computed(() => passwordPolicyIssues(accountForm.password))
const resetPasswordIssues = computed(() => passwordPolicyIssues(newPassword.value))

const filteredAccounts = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return props.accounts.filter((account) => {
    if (statusFilter.value !== 'ALL' && account.status !== statusFilter.value) return false
    if (sourceFilter.value === 'LOCAL' && account.sourceProvider !== 'LOCAL') return false
    if (sourceFilter.value === 'EXTERNAL' && account.sourceProvider === 'LOCAL') return false
    if (!term) return true
    return [account.username, account.displayName, account.email, account.mobile]
      .some((value) => value?.toLowerCase().includes(term))
  })
})

const pagedAccounts = computed(() => {
  const offset = (currentPage.value - 1) * pageSize
  return filteredAccounts.value.slice(offset, offset + pageSize)
})

watch([keyword, statusFilter, sourceFilter], () => { currentPage.value = 1 })

function resetAccountForm() {
  Object.assign(accountForm, {
    username: '', displayName: '', email: '', mobile: '', password: '', status: 'ACTIVE',
  })
  accountGrants.value = []
  editingAccountId.value = null
}

function openCreate() {
  resetAccountForm()
  accountDialogMode.value = 'create'
  accountDialogOpen.value = true
}

function openEdit(account: PlatformAccountUserView) {
  accountDialogMode.value = 'edit'
  editingAccountId.value = account.id
  Object.assign(accountForm, {
    username: account.username,
    displayName: account.displayName || account.username,
    email: account.email || '',
    mobile: account.mobile || '',
    password: '',
    status: account.status as 'ACTIVE' | 'INACTIVE',
  })
  accountDialogOpen.value = true
}

function openGrants(account: PlatformAccountUserView) {
  grantAccount.value = account
  editingGrants.value = grantsToEditorRows(account.grants || [], projectStore.projects)
  grantDialogOpen.value = true
}

function openPasswordReset(account: PlatformAccountUserView) {
  passwordAccount.value = account
  newPassword.value = ''
  passwordDialogOpen.value = true
}

function grantSummaries(account: PlatformAccountUserView) {
  return groupGrantsForDisplay(account.grants || [], projectStore.projects)
}

function validateGrants(rows: PlatformGrantEditorRow[]): boolean {
  if (rows.some((row) => !row.roleId)) {
    ElMessage.warning('每条授权都需要选择角色')
    return false
  }
  if (rows.some((row) => row.scopeType === 'PROJECT' && !row.projectIds.length)) {
    ElMessage.warning('指定项目的授权至少需要选择一个项目')
    return false
  }
  const roleIds = rows.map((row) => row.roleId)
  if (new Set(roleIds).size !== roleIds.length) {
    ElMessage.warning('同一角色请只添加一次；多个项目可在同一行多选')
    return false
  }
  return true
}

async function submitAccount() {
  if (!accountForm.displayName.trim()) return ElMessage.warning('请输入姓名或显示名')
  if (accountDialogMode.value === 'create') {
    if (!accountForm.username.trim()) return ElMessage.warning('请输入用户名')
    if (passwordIssues.value.length) return ElMessage.warning(passwordIssues.value[0])
    if (!validateGrants(accountGrants.value)) return
  }

  saving.value = true
  try {
    if (accountDialogMode.value === 'create') {
      await createPlatformAccount({
        username: accountForm.username,
        displayName: accountForm.displayName,
        email: accountForm.email || undefined,
        mobile: accountForm.mobile || undefined,
        password: accountForm.password,
        status: accountForm.status,
        grants: editorRowsToCommands(accountGrants.value, projectStore.projects),
      })
      ElMessage.success('平台账号已创建')
    } else if (editingAccountId.value != null) {
      await updatePlatformAccount(editingAccountId.value, {
        displayName: accountForm.displayName,
        email: accountForm.email || undefined,
        mobile: accountForm.mobile || undefined,
        status: accountForm.status,
      })
      ElMessage.success('账号资料已保存')
    }
    accountDialogOpen.value = false
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function saveGrants() {
  if (!grantAccount.value || !validateGrants(editingGrants.value)) return
  saving.value = true
  try {
    await savePlatformUserRoleGrants(
      grantAccount.value.id,
      editorRowsToCommands(editingGrants.value, projectStore.projects),
    )
    ElMessage.success('角色授权已更新')
    grantDialogOpen.value = false
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function submitPasswordReset() {
  if (!passwordAccount.value) return
  if (resetPasswordIssues.value.length) return ElMessage.warning(resetPasswordIssues.value[0])
  saving.value = true
  try {
    const { data } = await resetPlatformAccountPassword(passwordAccount.value.id, newPassword.value)
    ElMessage.success(`密码已重置，已撤销 ${data.revokedSessions} 个会话`)
    passwordDialogOpen.value = false
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function revokeSessions(account: PlatformAccountUserView) {
  try {
    await ElMessageBox.confirm(
      `将立即撤销 ${account.displayName || account.username} 的 ${account.activeSessionCount} 个登录会话，是否继续？`,
      '撤销登录会话',
      { type: 'warning', confirmButtonText: '确认撤销' },
    )
  } catch {
    return
  }
  const { data } = await revokePlatformAccountSessions(account.id)
  ElMessage.success(`已撤销 ${data.revokedSessions} 个会话`)
  emit('changed')
}

function handleAccountCommand(command: string, account: PlatformAccountUserView) {
  if (command === 'edit') openEdit(account)
  if (command === 'password') openPasswordReset(account)
  if (command === 'revoke') void revokeSessions(account)
}

function initials(account: PlatformAccountUserView) {
  return (account.displayName || account.username).trim().slice(0, 2).toUpperCase()
}

function formatTime(value?: string, fallback = '-') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped lang="scss">
.identity-panel {
  display: grid;
  gap: 16px;
}

.panel-toolbar,
.panel-toolbar__filters,
.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-toolbar__filters {
  flex: 1;
  justify-content: flex-start;

  > .el-input {
    width: min(420px, 100%);
  }
}

.compact-filter {
  width: 132px;
}

.table-shell {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
}

.identity-cell {
  display: flex;
  align-items: center;
  gap: 11px;

  > div {
    display: grid;
    min-width: 0;
    gap: 2px;
  }

  strong,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--text-secondary);
    font-size: 12px;
  }
}

.identity-avatar {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.22);
  border-radius: 10px;
  color: var(--brand-active);
  background: rgb(var(--brand-selected-rgb) / 0.52);
  font-size: 12px;
  font-weight: 800;
}

.source-label,
.tag-list {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tag-list {
  flex-wrap: wrap;
}

.muted,
.pagination-row,
.field-help {
  color: var(--text-secondary);
  font-size: 12px;
}

.session-count {
  display: inline-grid;
  min-width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 8px;
  background: var(--surface-glass-control);

  &.active {
    color: var(--brand-active);
    background: rgb(var(--brand-selected-rgb) / 0.62);
    font-weight: 700;
  }
}

.pagination-row {
  justify-content: flex-end;
}

.account-form {
  min-height: 260px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.field-help {
  margin-top: 5px;
  line-height: 1.45;
}

.field-help.has-error {
  color: var(--el-color-danger);
}

@media (max-width: 820px) {
  .panel-toolbar,
  .panel-toolbar__filters {
    align-items: stretch;
    flex-direction: column;
  }

  .panel-toolbar__filters > .el-input,
  .compact-filter,
  .panel-toolbar > .el-button {
    width: 100%;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
