<template>
  <section class="role-workbench">
    <!-- 左侧：角色目录 -->
    <LiquidGlassCard as="div" source="wallpaper" class="role-directory" role="navigation" aria-label="角色目录">
      <header class="role-directory__header">
        <div class="header-title">
          <strong>角色列表</strong>
          <span class="role-count-badge">{{ filteredRoles.length }}</span>
        </div>
        <el-button
          type="primary"
          size="small"
          :icon="Plus"
          class="create-role-btn"
          aria-label="新建自定义角色"
          @click="startCreate"
        >
          新建角色
        </el-button>
      </header>

      <div class="role-search-wrap">
        <el-input
          v-model="roleKeyword"
          clearable
          size="default"
          :prefix-icon="Search"
          placeholder="搜索角色名称或编码..."
        />
      </div>

      <div v-loading="loading" class="role-list">
        <button
          v-for="role in filteredRoles"
          :key="role.id"
          type="button"
          class="role-item"
          :class="{
            'is-active': !creating && selectedRoleId === role.id,
            'is-system': role.roleKind === 'SYSTEM',
          }"
          @click="selectRole(role)"
        >
          <span class="role-item__icon" :class="role.roleKind.toLowerCase()">
            <el-icon><Lock v-if="role.roleKind === 'SYSTEM'" /><UserFilled v-else /></el-icon>
          </span>

          <span class="role-item__body">
            <span class="role-item__title-row">
              <strong class="role-item__name">{{ role.roleName }}</strong>
            </span>
            <small class="role-item__code">{{ role.roleCode }}</small>
          </span>

          <span class="role-item__tags">
            <el-tag
              :type="role.roleKind === 'SYSTEM' ? 'warning' : 'primary'"
              size="small"
              effect="plain"
              class="type-tag"
            >
              {{ role.roleKind === 'SYSTEM' ? '内置' : '自定义' }}
            </el-tag>
            <span v-if="role.status === 'INACTIVE'" class="inactive-dot" title="已停用" />
          </span>
        </button>

        <el-empty v-if="!filteredRoles.length" description="未找到匹配角色" :image-size="64" />
      </div>
    </LiquidGlassCard>

    <!-- 右侧：角色详情与权限矩阵 -->
    <LiquidGlassCard as="div" source="wallpaper" class="role-detail">
      <template v-if="selectedRole || creating">
        <!-- 头部标题区 -->
        <header class="role-detail__header">
          <div class="header-main">
            <div class="title-row">
              <h2 class="role-title">{{ creating ? '新建自定义角色' : selectedRole?.roleName }}</h2>
              <el-tag
                v-if="selectedRole"
                :type="selectedRole.roleKind === 'SYSTEM' ? 'warning' : 'primary'"
                effect="plain"
                class="role-kind-tag"
              >
                {{ selectedRole.roleKind === 'SYSTEM' ? '系统预置角色' : '自定义角色' }}
              </el-tag>
              <el-tag
                v-if="selectedRole"
                :type="selectedRole.status === 'ACTIVE' ? 'success' : 'info'"
                size="small"
                effect="light"
              >
                {{ selectedRole.status === 'ACTIVE' ? '启用中' : '已停用' }}
              </el-tag>
            </div>
            <p class="role-subtitle">
              <template v-if="creating">定义专属角色并分配平台操作权限，创建后可在“账号管理”中进行分配。</template>
              <template v-else-if="isReadOnly">系统预置角色，内置平台核心管理权限，由平台基线统一维护不可修改。</template>
              <template v-else>自定义可复用权限包，可在此灵活调整权限，并为不同业务人员灵活授权。</template>
            </p>
          </div>

          <!-- 核心统计胶囊 -->
          <div v-if="selectedRole" class="role-stats-capsules">
            <div class="stat-capsule" title="当前绑定此角色的平台账号数量">
              <span class="capsule-label">关联账号</span>
              <strong class="capsule-value">{{ selectedRole.userCount }}</strong>
            </div>
            <div class="stat-capsule" title="针对该角色的授权记录总数">
              <span class="capsule-label">授权记录</span>
              <strong class="capsule-value">{{ selectedRole.grantCount }}</strong>
            </div>
            <div class="stat-capsule highlight" title="该角色当前持有的权限项数量">
              <span class="capsule-label">包含权限</span>
              <strong class="capsule-value">{{ selectedRole.permissionIds.length }}</strong>
            </div>
          </div>
        </header>

        <!-- 停用告警 -->
        <LiquidGlassNotice
          v-if="selectedRole?.status === 'INACTIVE'"
          type="warning"
          title="该角色已停用：新授权将无法分配此角色，已有绑定账号保留以备安全审计。"
          source="backdrop"
          class="notice-bar"
        />

        <!-- 基本信息区：系统角色采用紧凑属性看板，自定义角色采用编辑表单 -->
        <section v-if="isReadOnly && selectedRole" class="role-meta-summary" aria-label="角色基本信息">
          <div class="meta-item">
            <span class="meta-label">角色编码</span>
            <div class="meta-value-code">
              <code>{{ selectedRole.roleCode }}</code>
              <el-tooltip content="复制角色编码" placement="top">
                <el-button link :icon="CopyDocument" class="copy-btn" @click="copyCode(selectedRole.roleCode)" />
              </el-tooltip>
            </div>
          </div>
          <div class="meta-item flex-grow">
            <span class="meta-label">职责说明</span>
            <span class="meta-value-text">{{ selectedRole.description || '平台预置角色，具备全部治理与运维权限。' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">维护机制</span>
            <span class="meta-badge-text">基线自动对齐 · 版本受控</span>
          </div>
        </section>

        <!-- 编辑模式表单 -->
        <el-form v-else label-position="top" class="role-form">
          <div class="role-form__grid">
            <el-form-item label="角色编码" required>
              <el-input
                v-model="roleForm.roleCode"
                :disabled="!creating"
                maxlength="64"
                placeholder="例如 DATA_ANALYST"
                @input="roleForm.roleCode = roleForm.roleCode.toUpperCase()"
              />
            </el-form-item>
            <el-form-item label="角色名称" required>
              <el-input v-model="roleForm.roleName" maxlength="128" placeholder="例如 数据分析师" />
            </el-form-item>
            <el-form-item class="description-field" label="角色说明">
              <el-input
                v-model="roleForm.description"
                type="textarea"
                :rows="2"
                maxlength="512"
                show-word-limit
                placeholder="说明角色职责边界与适用人员场景..."
              />
            </el-form-item>
            <el-form-item label="角色状态">
              <el-radio-group v-model="roleForm.status">
                <el-radio-button value="ACTIVE">正常启用</el-radio-button>
                <el-radio-button value="INACTIVE">停用该角色</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </div>
        </el-form>

        <!-- 权限配置与展示区 -->
        <div class="permission-section">
          <div class="permission-heading">
            <div class="permission-title-wrap">
              <strong>权限配置清单</strong>
              <span class="selected-summary">
                已分配 <em>{{ roleForm.permissionIds.length }}</em> / {{ permissions.length }} 项权限
              </span>
            </div>

            <!-- 工具条：状态过滤、快捷全选与搜索 -->
            <div class="permission-toolbar">
              <el-radio-group v-model="permissionFilterScope" size="small" class="scope-switch">
                <el-radio-button value="ALL">全部 ({{ permissions.length }})</el-radio-button>
                <el-radio-button value="SELECTED">已包含 ({{ roleForm.permissionIds.length }})</el-radio-button>
                <el-radio-button value="UNSELECTED">未包含 ({{ unselectedCount }})</el-radio-button>
              </el-radio-group>

              <div v-if="!isReadOnly" class="quick-actions">
                <el-button link type="primary" size="small" @click="selectAllCurrentFiltered">全选筛选</el-button>
                <el-button link type="info" size="small" @click="clearAllSelected">清空已选</el-button>
              </div>

              <el-input
                v-model="permissionKeyword"
                clearable
                size="small"
                :prefix-icon="Search"
                placeholder="搜索权限名称或编码..."
                class="permission-search-input"
              />
            </div>
          </div>

          <!-- 权限分类分组列表 -->
          <div class="permission-groups">
            <section
              v-for="group in filteredPermissionGroups"
              :key="group.resourceType"
              class="permission-group"
            >
              <header class="group-header">
                <div class="group-title">
                  <span class="group-name">{{ resourceLabel(group.resourceType) }}</span>
                  <code class="group-code">{{ group.resourceType }}</code>
                </div>
                <div class="group-meta">
                  <span
                    class="group-badge"
                    :class="{ 'is-all': selectedCount(group.permissions) === group.permissions.length }"
                  >
                    {{ selectedCount(group.permissions) }} / {{ group.permissions.length }}
                  </span>
                  <el-button
                    v-if="!isReadOnly"
                    link
                    size="small"
                    class="group-toggle-btn"
                    @click="toggleGroupSelection(group.permissions)"
                  >
                    {{ selectedCount(group.permissions) === group.permissions.length ? '取消本组' : '全选本组' }}
                  </el-button>
                </div>
              </header>

              <div class="permission-grid">
                <div
                  v-for="permission in group.permissions"
                  :key="permission.id"
                  class="permission-card"
                  :class="{
                    'is-selected': isPermissionChecked(permission.id),
                    'is-reserved': permission.reservedForSystemRole,
                    'is-readonly': isReadOnly,
                  }"
                  @click="handleCardClick(permission)"
                >
                  <!-- 勾选状态指示 -->
                  <div class="permission-check">
                    <template v-if="isReadOnly">
                      <el-icon v-if="isPermissionChecked(permission.id)" class="checked-icon"><Check /></el-icon>
                      <span v-else class="unchecked-placeholder" />
                    </template>
                    <el-checkbox
                      v-else
                      :model-value="isPermissionChecked(permission.id)"
                      :disabled="permission.reservedForSystemRole"
                      @click.stop
                      @change="togglePermission(permission.id)"
                    />
                  </div>

                  <!-- 权限主要信息 -->
                  <div class="permission-body">
                    <div class="permission-top-line">
                      <strong class="permission-name">{{ permission.permissionName }}</strong>
                      <el-tooltip v-if="permission.reservedForSystemRole" content="仅限系统预置角色持有" placement="top">
                        <el-icon class="reserved-lock"><Lock /></el-icon>
                      </el-tooltip>
                    </div>
                    <code class="permission-code">{{ permission.permissionCode }}</code>
                    <p class="permission-desc">{{ permission.description || '暂无权限描述' }}</p>
                  </div>
                </div>
              </div>
            </section>

            <el-empty
              v-if="!filteredPermissionGroups.length"
              description="没有符合当前筛选条件的权限项"
              :image-size="60"
            />
          </div>
        </div>

        <!-- 底部保存/取消栏（仅可编辑模式） -->
        <footer v-if="!isReadOnly" class="role-actions">
          <div class="role-actions__hint">
            <span>保存后将立即更新角色配置，已有绑定账号将自动继承更新后的权限。</span>
          </div>
          <div class="role-actions__buttons">
            <el-button v-if="creating" @click="cancelCreate">取消</el-button>
            <el-button type="primary" :loading="saving" @click="saveRole">
              {{ creating ? '创建角色' : '保存角色变更' }}
            </el-button>
          </div>
        </footer>
      </template>

      <el-empty v-else description="请在左侧选择角色查看其详细权限" />
    </LiquidGlassCard>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, CopyDocument, Lock, Plus, Search, UserFilled } from '@element-plus/icons-vue'
import {
  createPlatformRole,
  updatePlatformRole,
  type PlatformPermissionView,
  type PlatformRoleManagementView,
} from '@/api/platformAuth'
import { groupPlatformPermissions } from '@/auth/platformAccountManagement'
import { LiquidGlassCard, LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  roles: PlatformRoleManagementView[]
  permissions: PlatformPermissionView[]
  loading: boolean
}>()

const emit = defineEmits<{ changed: [] }>()
const roleKeyword = ref('')
const permissionKeyword = ref('')
const permissionFilterScope = ref<'ALL' | 'SELECTED' | 'UNSELECTED'>('ALL')
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

const unselectedCount = computed(() => (
  Math.max(0, props.permissions.length - roleForm.permissionIds.length)
))

const filteredPermissionGroups = computed(() => {
  const term = permissionKeyword.value.trim().toLowerCase()
  const selectedSet = new Set(roleForm.permissionIds)

  const permissions = props.permissions.filter((permission) => {
    // 范围过滤
    if (permissionFilterScope.value === 'SELECTED' && !selectedSet.has(permission.id)) {
      return false
    }
    if (permissionFilterScope.value === 'UNSELECTED' && selectedSet.has(permission.id)) {
      return false
    }

    // 关键词过滤
    if (term) {
      const match = [
        permission.permissionCode,
        permission.permissionName,
        permission.description,
        permission.resourceType,
        resourceLabel(permission.resourceType || ''),
      ].some((value) => value?.toLowerCase().includes(term))
      if (!match) return false
    }

    return true
  })

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

function isPermissionChecked(permissionId: number): boolean {
  return roleForm.permissionIds.includes(permissionId)
}

function togglePermission(permissionId: number) {
  if (isReadOnly.value) return
  const index = roleForm.permissionIds.indexOf(permissionId)
  if (index >= 0) {
    roleForm.permissionIds.splice(index, 1)
  } else {
    roleForm.permissionIds.push(permissionId)
  }
}

function handleCardClick(permission: PlatformPermissionView) {
  if (isReadOnly.value || permission.reservedForSystemRole) return
  togglePermission(permission.id)
}

function toggleGroupSelection(groupPermissions: PlatformPermissionView[]) {
  if (isReadOnly.value) return
  const availablePermissions = groupPermissions.filter((p) => !p.reservedForSystemRole)
  const allSelected = availablePermissions.every((p) => roleForm.permissionIds.includes(p.id))

  if (allSelected) {
    // 全部取消
    const removeIds = new Set(availablePermissions.map((p) => p.id))
    roleForm.permissionIds = roleForm.permissionIds.filter((id) => !removeIds.has(id))
  } else {
    // 全部勾选
    const currentSet = new Set(roleForm.permissionIds)
    availablePermissions.forEach((p) => currentSet.add(p.id))
    roleForm.permissionIds = [...currentSet]
  }
}

function selectAllCurrentFiltered() {
  if (isReadOnly.value) return
  const currentSet = new Set(roleForm.permissionIds)
  filteredPermissionGroups.value.forEach((group) => {
    group.permissions.forEach((permission) => {
      if (!permission.reservedForSystemRole) {
        currentSet.add(permission.id)
      }
    })
  })
  roleForm.permissionIds = [...currentSet]
}

function clearAllSelected() {
  if (isReadOnly.value) return
  roleForm.permissionIds = []
}

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
  permissionFilterScope.value = 'ALL'
  if (role) applyRole(role)
}

function startCreate() {
  creating.value = true
  selectedRoleId.value = null
  permissionKeyword.value = ''
  permissionFilterScope.value = 'ALL'
  Object.assign(roleForm, {
    roleCode: '',
    roleName: '',
    description: '',
    status: 'ACTIVE',
    permissionIds: [],
  })
}

function cancelCreate() {
  selectRole(props.roles[0] ?? null)
}

function copyCode(text: string) {
  if (!text) return
  if (navigator?.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      ElMessage.success('角色编码已复制')
    }).catch(() => {
      ElMessage.info(text)
    })
  } else {
    ElMessage.info(text)
  }
}

async function saveRole() {
  if (!roleForm.roleCode.trim()) return ElMessage.warning('请输入角色编码')
  if (!roleForm.roleName.trim()) return ElMessage.warning('请输入角色名称')
  if (roleForm.status === 'ACTIVE' && !roleForm.permissionIds.length) {
    return ElMessage.warning('启用角色至少需要包含一项权限')
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
        `该角色当前关联 ${selectedRole.value.userCount} 个账号。停用后它将停止参与新授权，是否确认？`,
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
      ElMessage.success('自定义角色已成功创建')
    } else if (selectedRole.value) {
      await updatePlatformRole(selectedRole.value.id, {
        roleName: roleForm.roleName,
        description: roleForm.description || undefined,
        status: roleForm.status,
        permissionIds: roleForm.permissionIds,
        acknowledgeAssignedUsers,
      })
      ElMessage.success('角色权限配置已保存')
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
  PLATFORM: '平台治理与底座',
  PROJECT: '项目与环境',
  AGENT: 'Agent 智能体',
  WORKFLOW: 'Workflow 工作流',
  CAPABILITY: '业务能力资产',
  KNOWLEDGE: '知识库与文档',
  MODEL: '模型网关与模板',
  RUNOPS: '运行监控与审计',
  EMBED: '嵌入与第三方接入',
}

function resourceLabel(resourceType: string) {
  return RESOURCE_LABELS[resourceType] || resourceType
}
</script>

<style scoped lang="scss">
.role-workbench {
  display: grid;
  min-height: 680px;
  grid-template-columns: 310px minmax(0, 1fr);
  gap: 16px;
}

// 左侧角色目录
.role-directory {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

.role-directory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  .header-title {
    display: flex;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--text-primary);
      font-size: 15px;
      font-weight: 600;
    }

    .role-count-badge {
      display: inline-grid;
      min-width: 20px;
      height: 18px;
      place-items: center;
      padding: 0 6px;
      border-radius: 9px;
      background: var(--surface-glass-inset);
      color: var(--text-secondary);
      font-size: 11px;
      font-weight: 500;
    }
  }

  .create-role-btn {
    font-size: 12px;
    padding: 6px 12px;
  }
}

.role-search-wrap {
  width: 100%;
}

.role-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
}

.role-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-md, 10px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease-in-out;
  position: relative;

  &:hover {
    border-color: rgb(var(--brand-primary-rgb) / 0.18);
    background: rgb(var(--brand-selected-rgb) / 0.28);
  }

  &.is-active {
    border-color: rgb(var(--brand-primary-rgb) / 0.36);
    background: rgb(var(--brand-selected-rgb) / 0.46);

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 10px;
      bottom: 10px;
      width: 3px;
      border-radius: 0 2px 2px 0;
      background: var(--brand-active);
    }
  }
}

.role-item__icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 8px;
  font-size: 14px;
  color: var(--brand-active);
  background: rgb(var(--brand-selected-rgb) / 0.65);

  &.system {
    color: var(--el-color-warning);
    background: var(--el-color-warning-light-9);
  }
}

.role-item__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  gap: 2px;
}

.role-item__title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.role-item__name {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-item__code {
  color: var(--text-secondary);
  font-size: 11px;
  font-family: var(--font-family-mono, monospace);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-item__tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;

  .type-tag {
    font-size: 11px;
    padding: 0 6px;
    height: 20px;
    line-height: 18px;
  }

  .inactive-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--text-secondary);
  }
}

// 右侧详情面板
.role-detail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 20px;
  padding: 22px;
}

// 头部
.role-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 4px;

  .header-main {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }

  .title-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .role-title {
    margin: 0;
    color: var(--text-primary);
    font-size: 20px;
    font-weight: 700;
    line-height: 1.25;
  }

  .role-subtitle {
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.5;
  }
}

// 统计胶囊
.role-stats-capsules {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.stat-capsule {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 20px;
  border: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-inset);

  .capsule-label {
    color: var(--text-secondary);
    font-size: 11px;
  }

  .capsule-value {
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  &.highlight {
    border-color: rgb(var(--brand-primary-rgb) / 0.28);
    background: rgb(var(--brand-selected-rgb) / 0.35);

    .capsule-value {
      color: var(--brand-active);
    }
  }
}

.notice-bar {
  margin-top: -6px;
}

// 只读属性看板（替代丑陋的 disabled 表单）
.role-meta-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px 24px;
  padding: 12px 16px;
  border-radius: var(--radius-lg, 12px);
  border: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-inset);

  .meta-item {
    display: flex;
    flex-direction: column;
    gap: 4px;

    &.flex-grow {
      flex: 1;
      min-width: 220px;
    }
  }

  .meta-label {
    color: var(--text-secondary);
    font-size: 11px;
    font-weight: 500;
  }

  .meta-value-code {
    display: inline-flex;
    align-items: center;
    gap: 6px;

    code {
      font-size: 12px;
      font-weight: 600;
      color: var(--brand-active);
      background: rgb(var(--brand-selected-rgb) / 0.45);
      padding: 2px 8px;
      border-radius: 4px;
    }

    .copy-btn {
      color: var(--text-secondary);
      &:hover {
        color: var(--brand-active);
      }
    }
  }

  .meta-value-text {
    color: var(--text-primary);
    font-size: 13px;
    line-height: 1.4;
  }

  .meta-badge-text {
    color: var(--text-secondary);
    font-size: 12px;
  }
}

// 编辑表单
.role-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.description-field {
  grid-column: 1 / -1;
}

// 权限清单区域
.permission-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  flex: 1;
}

.permission-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 2px;
}

.permission-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 10px;

  strong {
    color: var(--text-primary);
    font-size: 15px;
    font-weight: 600;
  }

  .selected-summary {
    color: var(--text-secondary);
    font-size: 12px;

    em {
      color: var(--brand-active);
      font-weight: 600;
      font-style: normal;
    }
  }
}

.permission-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;

  .scope-switch :deep(.el-radio-button__inner) {
    font-size: 12px;
    padding: 5px 11px;
  }

  .quick-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .permission-search-input {
    width: 210px;
  }
}

// 权限分组
.permission-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.permission-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-lg, 12px);
  border: 1px solid var(--border-glass-edge);
  background: color-mix(in srgb, var(--surface-glass-inset) 60%, transparent);
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  .group-title {
    display: flex;
    align-items: center;
    gap: 8px;

    .group-name {
      color: var(--text-primary);
      font-size: 13px;
      font-weight: 600;
    }

    .group-code {
      color: var(--text-secondary);
      font-size: 11px;
      opacity: 0.8;
    }
  }

  .group-meta {
    display: flex;
    align-items: center;
    gap: 10px;

    .group-badge {
      display: inline-flex;
      align-items: center;
      padding: 1px 8px;
      border-radius: 10px;
      font-size: 11px;
      color: var(--text-secondary);
      background: var(--surface-glass-inset);

      &.is-all {
        color: var(--el-color-success);
        background: var(--el-color-success-light-9);
        font-weight: 600;
      }
    }

    .group-toggle-btn {
      font-size: 12px;
      padding: 0;
    }
  }
}

// 权限卡片网格
.permission-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.permission-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-inset);
  cursor: pointer;
  transition: all 0.16s ease;

  &:hover:not(.is-readonly) {
    border-color: rgb(var(--brand-primary-rgb) / 0.35);
    background: rgb(var(--brand-selected-rgb) / 0.3);
  }

  &.is-selected {
    border-color: rgb(var(--brand-primary-rgb) / 0.38);
    background: rgb(var(--brand-selected-rgb) / 0.4);

    .permission-name {
      color: var(--brand-active);
    }
  }

  &.is-readonly {
    cursor: default;

    &:not(.is-selected) {
      opacity: 0.55;
    }
  }

  &.is-reserved {
    cursor: not-allowed;
  }
}

.permission-check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-top: 2px;
  flex-shrink: 0;

  .checked-icon {
    color: var(--brand-active);
    font-size: 16px;
    font-weight: bold;
  }

  .unchecked-placeholder {
    width: 14px;
    height: 14px;
    border-radius: 3px;
    border: 1px dashed var(--border-subtle);
  }
}

.permission-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  gap: 2px;

  .permission-top-line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
  }

  .permission-name {
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 600;
    line-height: 1.35;
  }

  .reserved-lock {
    color: var(--el-color-warning);
    font-size: 13px;
    flex-shrink: 0;
  }

  .permission-code {
    color: var(--text-secondary);
    font-size: 11px;
    font-family: var(--font-family-mono, monospace);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .permission-desc {
    margin: 3px 0 0;
    color: var(--text-secondary);
    font-size: 11px;
    line-height: 1.4;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
}

// 底部操作栏
.role-actions {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: auto -22px -22px;
  padding: 14px 22px;
  border-top: 1px solid var(--border-divider);
  border-radius: 0 0 var(--radius-xl) var(--radius-xl);
  background: var(--surface-reading-sticky);
  z-index: 5;

  .role-actions__hint {
    color: var(--text-secondary);
    font-size: 12px;
  }

  .role-actions__buttons {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

@media (max-width: 1080px) {
  .role-workbench {
    grid-template-columns: 1fr;
  }

  .role-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .role-list,
  .role-form__grid,
  .permission-grid {
    grid-template-columns: 1fr;
  }

  .role-detail__header,
  .permission-heading,
  .role-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .role-stats-capsules {
    justify-content: flex-start;
  }

  .permission-toolbar {
    flex-direction: column;
    align-items: stretch;

    .permission-search-input {
      width: 100%;
    }
  }
}
</style>
