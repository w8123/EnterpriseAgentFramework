<template>
  <section class="role-workbench">
    <aside class="role-directory">
      <div class="role-directory__header">
        <div>
          <strong>角色目录</strong>
          <span>{{ roles.length }} 个权限包</span>
        </div>
        <el-button type="primary" :icon="Plus" circle aria-label="新建自定义角色" @click="startCreate" />
      </div>
      <el-input v-model="roleKeyword" clearable :prefix-icon="Search" placeholder="搜索角色" />
      <div v-loading="loading" class="role-list">
        <button
          v-for="role in filteredRoles"
          :key="role.id"
          type="button"
          class="role-item"
          :class="{ 'is-active': !creating && selectedRoleId === role.id }"
          @click="selectRole(role)"
        >
          <span class="role-item__icon" :class="role.roleKind.toLowerCase()">
            <el-icon><Lock v-if="role.roleKind === 'SYSTEM'" /><UserFilled v-else /></el-icon>
          </span>
          <span class="role-item__body">
            <strong>{{ role.roleName }}</strong>
            <small>{{ role.roleCode }}</small>
          </span>
          <el-tag :type="role.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="plain">
            {{ role.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </button>
        <el-empty v-if="!filteredRoles.length" description="没有匹配角色" :image-size="72" />
      </div>
    </aside>

    <div class="role-detail">
      <template v-if="selectedRole || creating">
        <header class="role-detail__header">
          <div>
            <div class="title-line">
              <h2>{{ creating ? '新建自定义角色' : selectedRole?.roleName }}</h2>
              <el-tag v-if="selectedRole" :type="selectedRole.roleKind === 'SYSTEM' ? 'warning' : 'primary'" effect="plain">
                {{ selectedRole.roleKind === 'SYSTEM' ? '系统角色' : '自定义角色' }}
              </el-tag>
            </div>
            <p v-if="isReadOnly">系统角色由平台基线维护，权限不可在这里修改，避免升级后语义漂移。</p>
            <p v-else>角色是可复用的权限包；账号授权时再限定全部项目或指定项目。</p>
          </div>
          <div v-if="selectedRole" class="impact-summary">
            <div><strong>{{ selectedRole.userCount }}</strong><span>关联账号</span></div>
            <div><strong>{{ selectedRole.grantCount }}</strong><span>授权记录</span></div>
            <div><strong>{{ selectedRole.permissionIds.length }}</strong><span>权限项</span></div>
          </div>
        </header>

        <el-alert
          v-if="selectedRole?.status === 'INACTIVE'"
          type="warning"
          title="该角色已停用，不会出现在新的账号授权中；已有绑定仍保留以便审计。"
          :closable="false"
          show-icon
        />

        <el-form label-position="top" class="role-form">
          <div class="role-form__grid">
            <el-form-item label="角色编码" required>
              <el-input
                v-model="roleForm.roleCode"
                :disabled="!creating"
                maxlength="64"
                placeholder="例如 KNOWLEDGE_EDITOR"
                @input="roleForm.roleCode = roleForm.roleCode.toUpperCase()"
              />
            </el-form-item>
            <el-form-item label="角色名称" required>
              <el-input v-model="roleForm.roleName" :disabled="isReadOnly" maxlength="128" />
            </el-form-item>
            <el-form-item class="description-field" label="角色说明">
              <el-input
                v-model="roleForm.description"
                type="textarea"
                :rows="2"
                :disabled="isReadOnly"
                maxlength="512"
                show-word-limit
                placeholder="说明适用岗位、责任边界和授权条件"
              />
            </el-form-item>
            <el-form-item label="状态">
              <el-radio-group v-model="roleForm.status" :disabled="isReadOnly">
                <el-radio-button value="ACTIVE">启用</el-radio-button>
                <el-radio-button value="INACTIVE">停用</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </div>
        </el-form>

        <div class="permission-heading">
          <div>
            <strong>权限清单</strong>
            <span>已选择 {{ roleForm.permissionIds.length }} 项</span>
          </div>
          <el-input v-model="permissionKeyword" clearable :prefix-icon="Search" placeholder="搜索权限编码或名称" />
        </div>

        <el-checkbox-group v-model="roleForm.permissionIds" class="permission-groups">
          <section v-for="group in filteredPermissionGroups" :key="group.resourceType" class="permission-group">
            <header>
              <strong>{{ resourceLabel(group.resourceType) }}</strong>
              <span>{{ selectedCount(group.permissions) }} / {{ group.permissions.length }}</span>
            </header>
            <div class="permission-grid">
              <label
                v-for="permission in group.permissions"
                :key="permission.id"
                class="permission-card"
                :class="{ 'is-reserved': permission.reservedForSystemRole }"
              >
                <el-checkbox
                  :value="permission.id"
                  :disabled="isReadOnly || permission.reservedForSystemRole"
                />
                <span>
                  <strong>{{ permission.permissionName }}</strong>
                  <code>{{ permission.permissionCode }}</code>
                  <small>{{ permission.description || '未提供说明' }}</small>
                </span>
                <el-tooltip v-if="permission.reservedForSystemRole" content="仅允许系统角色持有" placement="top">
                  <el-icon class="reserved-lock"><Lock /></el-icon>
                </el-tooltip>
              </label>
            </div>
          </section>
        </el-checkbox-group>

        <footer v-if="!isReadOnly" class="role-actions">
          <span>角色保存后即可分配给账号；权限变更会影响所有关联账号。</span>
          <div>
            <el-button v-if="creating" @click="cancelCreate">取消</el-button>
            <el-button type="primary" :loading="saving" @click="saveRole">
              {{ creating ? '创建角色' : '保存角色' }}
            </el-button>
          </div>
        </footer>
      </template>

      <el-empty v-else description="请选择角色查看权限" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, Plus, Search, UserFilled } from '@element-plus/icons-vue'
import {
  createPlatformRole,
  updatePlatformRole,
  type PlatformPermissionView,
  type PlatformRoleManagementView,
} from '@/api/platformAuth'
import { groupPlatformPermissions } from '@/auth/platformAccountManagement'

const props = defineProps<{
  roles: PlatformRoleManagementView[]
  permissions: PlatformPermissionView[]
  loading: boolean
}>()

const emit = defineEmits<{ changed: [] }>()
const roleKeyword = ref('')
const permissionKeyword = ref('')
const selectedRoleId = ref<number | null>(null)
const creating = ref(false)
const saving = ref(false)

const roleForm = reactive({
  roleCode: '',
  roleName: '',
  description: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
  permissionIds: [] as number[],
})

const selectedRole = computed(() => (
  props.roles.find((role) => role.id === selectedRoleId.value) ?? null
))
const isReadOnly = computed(() => !creating.value && selectedRole.value?.roleKind === 'SYSTEM')
const filteredRoles = computed(() => {
  const term = roleKeyword.value.trim().toLowerCase()
  return props.roles.filter((role) => !term || [role.roleCode, role.roleName, role.description]
    .some((value) => value?.toLowerCase().includes(term)))
})
const filteredPermissionGroups = computed(() => {
  const term = permissionKeyword.value.trim().toLowerCase()
  const permissions = props.permissions.filter((permission) => !term || [
    permission.permissionCode,
    permission.permissionName,
    permission.description,
    permission.resourceType,
  ].some((value) => value?.toLowerCase().includes(term)))
  return groupPlatformPermissions(permissions)
})

watch(
  () => props.roles,
  (roles) => {
    if (creating.value) return
    const current = roles.find((role) => role.id === selectedRoleId.value)
    selectRole(current ?? roles[0] ?? null)
  },
  { immediate: true },
)

function applyRole(role: PlatformRoleManagementView) {
  Object.assign(roleForm, {
    roleCode: role.roleCode,
    roleName: role.roleName,
    description: role.description || '',
    status: role.status as 'ACTIVE' | 'INACTIVE',
    permissionIds: [...role.permissionIds],
  })
}

function selectRole(role: PlatformRoleManagementView | null) {
  creating.value = false
  selectedRoleId.value = role?.id ?? null
  permissionKeyword.value = ''
  if (role) applyRole(role)
}

function startCreate() {
  creating.value = true
  selectedRoleId.value = null
  permissionKeyword.value = ''
  Object.assign(roleForm, {
    roleCode: '', roleName: '', description: '', status: 'ACTIVE', permissionIds: [],
  })
}

function cancelCreate() {
  selectRole(props.roles[0] ?? null)
}

async function saveRole() {
  if (!roleForm.roleCode.trim()) return ElMessage.warning('请输入角色编码')
  if (!roleForm.roleName.trim()) return ElMessage.warning('请输入角色名称')
  if (roleForm.status === 'ACTIVE' && !roleForm.permissionIds.length) {
    return ElMessage.warning('启用角色至少需要一项权限')
  }

  let acknowledgeAssignedUsers = false
  if (
    !creating.value
    && selectedRole.value
    && selectedRole.value.status === 'ACTIVE'
    && roleForm.status === 'INACTIVE'
    && selectedRole.value.userCount > 0
  ) {
    try {
      await ElMessageBox.confirm(
        `该角色关联 ${selectedRole.value.userCount} 个账号。停用后它将停止参与新授权，是否确认？`,
        '确认停用角色',
        { type: 'warning', confirmButtonText: '确认停用' },
      )
    } catch {
      return
    }
    acknowledgeAssignedUsers = true
  }

  saving.value = true
  try {
    if (creating.value) {
      const { data } = await createPlatformRole({
        roleCode: roleForm.roleCode,
        roleName: roleForm.roleName,
        description: roleForm.description || undefined,
        status: roleForm.status,
        permissionIds: roleForm.permissionIds,
      })
      selectedRoleId.value = data.id
      creating.value = false
      ElMessage.success('自定义角色已创建')
    } else if (selectedRole.value) {
      await updatePlatformRole(selectedRole.value.id, {
        roleName: roleForm.roleName,
        description: roleForm.description || undefined,
        status: roleForm.status,
        permissionIds: roleForm.permissionIds,
        acknowledgeAssignedUsers,
      })
      ElMessage.success('角色权限已保存')
    }
    emit('changed')
  } finally {
    saving.value = false
  }
}

function selectedCount(permissions: PlatformPermissionView[]) {
  const selected = new Set(roleForm.permissionIds)
  return permissions.filter((permission) => selected.has(permission.id)).length
}

const RESOURCE_LABELS: Record<string, string> = {
  PLATFORM: '平台治理',
  PROJECT: '项目',
  AGENT: 'Agent',
  WORKFLOW: 'Workflow',
  CAPABILITY: '能力',
  KNOWLEDGE: '知识库',
  MODEL: '模型',
  RUNOPS: '运行与审计',
  EMBED: '嵌入与接入',
}

function resourceLabel(resourceType: string) {
  return RESOURCE_LABELS[resourceType] || resourceType
}
</script>

<style scoped lang="scss">
.role-workbench {
  display: grid;
  min-height: 660px;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
}

.role-directory,
.role-detail {
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
}

.role-directory {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.role-directory__header,
.role-detail__header,
.title-line,
.permission-heading,
.permission-group > header,
.role-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.role-directory__header > div,
.permission-heading > div {
  display: grid;
  gap: 2px;
}

.role-directory__header span,
.permission-heading span,
.permission-group > header span,
.role-actions > span {
  color: var(--text-secondary);
  font-size: 12px;
}

.role-list {
  display: grid;
  align-content: start;
  gap: 6px;
}

.role-item {
  display: grid;
  width: 100%;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  color: var(--text-primary);
  background: transparent;
  text-align: left;
  cursor: pointer;

  &:hover,
  &.is-active {
    border-color: rgb(var(--brand-primary-rgb) / 0.26);
    background: rgb(var(--brand-selected-rgb) / 0.44);
  }
}

.role-item__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 9px;
  color: var(--brand-active);
  background: rgb(var(--brand-selected-rgb) / 0.68);

  &.system {
    color: var(--el-color-warning-dark-2);
    background: var(--el-color-warning-light-9);
  }
}

.role-item__body {
  display: grid;
  min-width: 0;
  gap: 2px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--text-secondary);
  }
}

.role-detail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 18px;
  padding: 20px;
}

.role-detail__header {
  align-items: flex-start;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 6px;
    color: var(--text-secondary);
    font-size: 13px;
  }
}

.title-line {
  justify-content: flex-start;
}

.impact-summary {
  display: flex;
  gap: 18px;

  div {
    display: grid;
    justify-items: end;
    gap: 1px;
  }

  strong {
    font-size: 20px;
  }

  span {
    color: var(--text-secondary);
    font-size: 11px;
  }
}

.role-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.description-field {
  grid-column: 1 / -1;
}

.permission-heading {
  padding-top: 4px;

  .el-input {
    width: min(360px, 50%);
  }
}

.permission-groups {
  display: grid;
  gap: 14px;
}

.permission-group {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
}

.permission-group > header {
  padding: 10px 14px;
  background: var(--surface-glass-control);
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  background: var(--border-divider);
}

.permission-card {
  position: relative;
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: flex-start;
  gap: 6px;
  min-height: 84px;
  padding: 12px;
  background: var(--surface-glass-panel);
  cursor: pointer;

  > span {
    display: grid;
    min-width: 0;
    gap: 3px;
  }

  code,
  small {
    overflow: hidden;
    color: var(--text-secondary);
    font-size: 11px;
    text-overflow: ellipsis;
  }

  code {
    color: var(--brand-active);
  }

  &.is-reserved {
    cursor: not-allowed;
  }
}

.reserved-lock {
  color: var(--el-color-warning);
}

.role-actions {
  position: sticky;
  bottom: 0;
  margin: auto -20px -20px;
  padding: 14px 20px;
  border-top: 1px solid var(--border-divider);
  background: var(--surface-glass-panel);
}

@media (max-width: 1050px) {
  .role-workbench {
    grid-template-columns: 1fr;
  }

  .role-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .role-list,
  .role-form__grid,
  .permission-grid {
    grid-template-columns: 1fr;
  }

  .role-detail__header,
  .permission-heading,
  .role-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .permission-heading .el-input {
    width: 100%;
  }

  .impact-summary {
    justify-content: space-between;

    div {
      justify-items: start;
    }
  }
}
</style>
