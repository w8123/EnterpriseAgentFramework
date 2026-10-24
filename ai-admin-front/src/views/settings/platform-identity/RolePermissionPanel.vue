<template>
  <section class="role-workbench">
    <!-- 左侧：角色目录 -->
    <LiquidGlassCard
      as="div"
      source="wallpaper"
      class="role-directory"
      role="navigation"
      aria-label="角色目录"
    >
      <header class="role-directory__header">
        <div class="header-title">
          <strong>角色列表</strong>
          <span class="role-count-badge">{{ filteredRoles.length }}</span>
        </div>
        <LiquidGlassButton
          type="primary"
          size="small"
          :icon="Plus"
          class="create-role-btn"
          aria-label="新建自定义角色"
          @click="startCreate"
        >
          新建角色
        </LiquidGlassButton>
      </header>

      <div class="role-search-wrap">
        <LiquidGlassInput
          v-model="roleKeyword"
          clearable
          size="default"
          :prefix-icon="Search"
          placeholder="搜索角色名称或编码"
          aria-label="搜索角色"
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
            'glass-surface-selection': !creating && selectedRoleId === role.id,
            'is-system': role.roleKind === 'SYSTEM',
          }"
          :aria-pressed="!creating && selectedRoleId === role.id"
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
      <p class="role-directory__hint">系统角色只读，自定义角色可编辑。</p>
    </LiquidGlassCard>

    <!-- 右侧：角色详情与权限清单 -->
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
                {{ selectedRole.roleKind === 'SYSTEM' ? '系统预置 · 只读' : '自定义角色' }}
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
              <template v-if="creating"
                >定义专属角色并分配平台操作权限，创建后可在“账号管理”中进行分配。</template
              >
              <template v-else-if="isReadOnly">{{
                selectedRole?.description || '系统预置角色，权限由平台基线统一维护。'
              }}</template>
              <template v-else>调整角色信息与权限，保存后对已关联账号生效。</template>
            </p>
          </div>

          <!-- 关联信息使用轻量统计，权限计数统一放在清单标题旁 -->
          <div v-if="selectedRole" class="role-stats-capsules">
            <div class="stat-capsule" title="当前绑定此角色的平台账号数量">
              <span class="capsule-label">关联账号</span>
              <strong class="capsule-value">{{ selectedRole.userCount }}</strong>
            </div>
            <div class="stat-capsule" title="针对该角色的授权记录总数">
              <span class="capsule-label">授权记录</span>
              <strong class="capsule-value">{{ selectedRole.grantCount }}</strong>
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

        <!-- 系统角色使用行内元数据，自定义角色保留编辑表单 -->
        <section v-if="isReadOnly && selectedRole" class="role-meta-summary" aria-label="角色基本信息">
          <div class="meta-item">
            <span class="meta-label">角色编码</span>
            <div class="meta-value-code">
              <code>{{ selectedRole.roleCode }}</code>
              <el-tooltip content="复制角色编码" placement="top">
                <LiquidGlassButton
                  circle
                  size="small"
                  :icon="CopyDocument"
                  aria-label="复制角色编码"
                  @click="copyCode(selectedRole.roleCode)"
                />
              </el-tooltip>
            </div>
          </div>
          <div class="meta-item">
            <span class="meta-label">维护机制</span>
            <span class="meta-badge-text">平台基线统一维护</span>
          </div>
        </section>

        <!-- 编辑模式表单 -->
        <el-form v-else label-position="top" class="role-form">
          <div class="role-form__grid">
            <el-form-item label="角色编码" required>
              <LiquidGlassInput
                v-model="roleForm.roleCode"
                :disabled="!creating"
                maxlength="64"
                placeholder="例如 DATA_ANALYST"
                @input="roleForm.roleCode = roleForm.roleCode.toUpperCase()"
              />
            </el-form-item>
            <el-form-item label="角色名称" required>
              <LiquidGlassInput v-model="roleForm.roleName" maxlength="128" placeholder="例如 数据分析师" />
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
        <section class="permission-section" aria-label="角色权限">
          <div class="permission-heading">
            <div class="permission-title-wrap">
              <h3>{{ isReadOnly ? '权限清单' : '权限配置' }}</h3>
              <span class="selected-summary">
                已包含 <em>{{ roleForm.permissionIds.length }}</em> / {{ permissions.length }} 项
              </span>
            </div>

            <button
              v-if="filteredPermissionGroups.length"
              type="button"
              class="text-action"
              @click="toggleAllGroups"
            >
              {{ allGroupsCollapsed ? '全部展开' : '全部收起' }}
            </button>
          </div>

          <div class="permission-toolbar">
            <LiquidGlassNav class="scope-switch" aria-label="权限包含状态">
              <button
                v-for="scope in permissionScopes"
                :key="scope.value"
                type="button"
                :aria-pressed="permissionFilterScope === scope.value"
                @click="permissionFilterScope = scope.value"
              >
                {{ scope.label }} <span>{{ scope.count }}</span>
              </button>
            </LiquidGlassNav>

            <LiquidGlassInput
              v-model="permissionKeyword"
              clearable
              :prefix-icon="Search"
              placeholder="搜索权限名称或编码"
              aria-label="搜索权限"
              class="permission-search-input"
            />
          </div>

          <div v-if="!isReadOnly" class="quick-actions">
            <LiquidGlassButton size="small" @click="selectAllCurrentFiltered">全选筛选结果</LiquidGlassButton>
            <LiquidGlassButton size="small" @click="clearAllSelected">清空已选</LiquidGlassButton>
            <span>仅可勾选允许分配给自定义角色的权限</span>
          </div>

          <!-- 权限分类分组列表 -->
          <div class="permission-groups">
            <section
              v-for="group in filteredPermissionGroups"
              :key="group.resourceType"
              class="permission-group"
            >
              <header class="group-header">
                <button
                  type="button"
                  class="group-disclosure"
                  :aria-expanded="!collapsedGroups.has(group.resourceType)"
                  :aria-controls="`${permissionListId}-${group.resourceType}`"
                  @click="toggleGroup(group.resourceType)"
                >
                  <el-icon
                    class="group-chevron"
                    :class="{ 'is-collapsed': collapsedGroups.has(group.resourceType) }"
                    aria-hidden="true"
                    ><ArrowDown
                  /></el-icon>
                  <span class="group-name">{{ resourceLabel(group.resourceType) }}</span>
                  <code v-if="resourceLabel(group.resourceType) !== group.resourceType" class="group-code">{{
                    group.resourceType
                  }}</code>
                  <span class="group-badge">
                    已含 {{ selectedCount(group.permissions) }} / {{ group.permissions.length }}
                  </span>
                </button>
                <button
                  v-if="
                    !isReadOnly && group.permissions.some((permission) => !permission.reservedForSystemRole)
                  "
                  type="button"
                  class="text-action group-toggle-btn"
                  @click="toggleGroupSelection(group.permissions)"
                >
                  {{ isGroupSelected(group.permissions) ? '取消本组' : '全选本组' }}
                </button>
              </header>

              <div
                :id="`${permissionListId}-${group.resourceType}`"
                v-show="!collapsedGroups.has(group.resourceType)"
                class="permission-rows"
              >
                <div
                  v-for="permission in group.permissions"
                  :key="permission.id"
                  class="permission-row"
                  :class="{
                    'is-selected': isPermissionChecked(permission.id),
                    'is-readonly': isReadOnly,
                  }"
                >
                  <div class="permission-identity">
                    <div class="permission-label">
                      <template v-if="isReadOnly">
                        <el-icon
                          class="permission-indicator"
                          :class="{ 'is-included': isPermissionChecked(permission.id) }"
                          aria-hidden="true"
                        >
                          <Check v-if="isPermissionChecked(permission.id)" /><Minus v-else />
                        </el-icon>
                        <span class="permission-name">{{ permission.permissionName }}</span>
                      </template>
                      <el-checkbox
                        v-else
                        :model-value="isPermissionChecked(permission.id)"
                        :disabled="permission.reservedForSystemRole"
                        :aria-label="permission.permissionName"
                        @change="togglePermission(permission.id)"
                        ><span class="permission-name">{{ permission.permissionName }}</span></el-checkbox
                      >
                    </div>
                    <p v-if="permission.description" class="permission-desc">{{ permission.description }}</p>
                  </div>
                  <code class="permission-code">{{ permission.permissionCode }}</code>
                  <span
                    class="permission-status"
                    :class="{ 'is-included': isPermissionChecked(permission.id) }"
                  >
                    <template v-if="!isReadOnly && permission.reservedForSystemRole"
                      ><el-icon aria-hidden="true"><Lock /></el-icon>仅限系统</template
                    >
                    <template v-else>{{ isPermissionChecked(permission.id) ? '已包含' : '未包含' }}</template>
                  </span>
                </div>
              </div>
            </section>

            <el-empty
              v-if="!filteredPermissionGroups.length"
              description="没有符合当前筛选条件的权限项"
              :image-size="60"
            />
          </div>
        </section>

        <!-- 底部保存/取消栏（仅可编辑模式） -->
        <footer v-if="!isReadOnly" class="role-actions">
          <div class="role-actions__hint">
            <span>保存后将立即更新角色配置，已有绑定账号将自动继承更新后的权限。</span>
          </div>
          <div class="role-actions__buttons">
            <LiquidGlassButton v-if="creating" @click="cancelCreate">取消</LiquidGlassButton>
            <LiquidGlassButton type="primary" :loading="saving" @click="saveRole">
              {{ creating ? '创建角色' : '保存角色变更' }}
            </LiquidGlassButton>
          </div>
        </footer>
      </template>

      <el-empty v-else description="请在左侧选择角色查看其详细权限" />
    </LiquidGlassCard>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, useId, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Check,
  CopyDocument,
  Lock,
  Minus,
  Plus,
  Search,
  UserFilled,
} from '@element-plus/icons-vue'
import {
  createPlatformRole,
  updatePlatformRole,
  type PlatformPermissionView,
  type PlatformRoleManagementView,
} from '@/api/platformAuth'
import { groupPlatformPermissions } from '@/auth/platformAccountManagement'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassInput,
  LiquidGlassNav,
  LiquidGlassNotice,
} from '@/components/common/liquid-glass'

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
const createdRole = ref<PlatformRoleManagementView | null>(null)
const creating = ref(false)
const saving = ref(false)
const permissionListId = useId()
const collapsedGroups = ref(new Set<string>())

const roleForm = reactive({
  roleCode: '',
  roleName: '',
  description: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
  permissionIds: [] as number[],
})

const selectedRole = computed(
  () =>
    props.roles.find((role) => role.id === selectedRoleId.value) ??
    (createdRole.value?.id === selectedRoleId.value ? createdRole.value : null),
)

const isReadOnly = computed(() => !creating.value && selectedRole.value?.roleKind === 'SYSTEM')

const filteredRoles = computed(() => {
  const term = roleKeyword.value.trim().toLowerCase()
  return props.roles.filter(
    (role) =>
      !term ||
      [role.roleCode, role.roleName, role.description].some((value) => value?.toLowerCase().includes(term)),
  )
})

const unselectedCount = computed(() => Math.max(0, props.permissions.length - roleForm.permissionIds.length))

const permissionScopes = computed(() => [
  { value: 'ALL' as const, label: '全部', count: props.permissions.length },
  { value: 'SELECTED' as const, label: '已包含', count: roleForm.permissionIds.length },
  { value: 'UNSELECTED' as const, label: '未包含', count: unselectedCount.value },
])

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
        resourceLabel(permissionDomain(permission.resourceType || '')),
      ].some((value) => value?.toLowerCase().includes(term))
      if (!match) return false
    }

    return true
  })

  // A2A / MCP 的细资源类型仍用于搜索，阅读清单按所属业务域集中展示。
  const groups = new Map<string, { resourceType: string; permissions: PlatformPermissionView[] }>()
  for (const group of groupPlatformPermissions(permissions)) {
    const resourceType = permissionDomain(group.resourceType)
    const current = groups.get(resourceType)
    if (current) current.permissions.push(...group.permissions)
    else groups.set(resourceType, { resourceType, permissions: [...group.permissions] })
  }
  return [...groups.values()]
})

const allGroupsCollapsed = computed(
  () =>
    filteredPermissionGroups.value.length > 0 &&
    filteredPermissionGroups.value.every((group) => collapsedGroups.value.has(group.resourceType)),
)

watch([permissionKeyword, permissionFilterScope], () => collapsedGroups.value.clear())

function toggleGroup(resourceType: string) {
  if (collapsedGroups.value.has(resourceType)) collapsedGroups.value.delete(resourceType)
  else collapsedGroups.value.add(resourceType)
}

function toggleAllGroups() {
  collapsedGroups.value = allGroupsCollapsed.value
    ? new Set()
    : new Set(filteredPermissionGroups.value.map((group) => group.resourceType))
}

watch(
  () => props.roles,
  (roles) => {
    if (creating.value) return
    const current = roles.find((role) => role.id === selectedRoleId.value)
    // 创建响应先于目录刷新到达时，保留详情，避免短暂切换为空状态。
    if (!current && createdRole.value?.id === selectedRoleId.value) return
    selectRole(current ?? roles[0] ?? null)
  },
  { immediate: true },
)

function isPermissionChecked(permissionId: number): boolean {
  return roleForm.permissionIds.includes(permissionId)
}

function togglePermission(permissionId: number) {
  if (
    isReadOnly.value ||
    props.permissions.find((permission) => permission.id === permissionId)?.reservedForSystemRole
  )
    return
  const index = roleForm.permissionIds.indexOf(permissionId)
  if (index >= 0) {
    roleForm.permissionIds.splice(index, 1)
  } else {
    roleForm.permissionIds.push(permissionId)
  }
}

function isGroupSelected(groupPermissions: PlatformPermissionView[]) {
  const available = groupPermissions.filter((permission) => !permission.reservedForSystemRole)
  return available.length > 0 && available.every((permission) => isPermissionChecked(permission.id))
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
  createdRole.value = null
  creating.value = false
  selectedRoleId.value = role?.id ?? null
  permissionKeyword.value = ''
  permissionFilterScope.value = 'ALL'
  collapsedGroups.value.clear()
  if (role) applyRole(role)
}

function startCreate() {
  createdRole.value = null
  creating.value = true
  selectedRoleId.value = null
  permissionKeyword.value = ''
  permissionFilterScope.value = 'ALL'
  collapsedGroups.value.clear()
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
    navigator.clipboard
      .writeText(text)
      .then(() => {
        ElMessage.success('角色编码已复制')
      })
      .catch(() => {
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
    !creating.value &&
    selectedRole.value &&
    selectedRole.value.status === 'ACTIVE' &&
    roleForm.status === 'INACTIVE' &&
    selectedRole.value.userCount > 0
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
      createdRole.value = data
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
  A2A: 'A2A 互联',
  MCP: 'MCP 发布与调用',
  A2A_CONFORMANCE: 'A2A 诊断与一致性',
  A2A_CREDENTIAL: 'A2A 凭证',
  A2A_HUB: 'A2A 互联',
  A2A_PAYLOAD: 'A2A 留存载荷',
  A2A_PUBLICATION: 'A2A 本地发布',
  A2A_REMOTE_AGENT: 'A2A 远程 Agent',
  A2A_TASK: 'A2A 任务',
  A2A_TRUST: 'A2A 主体与信任',
  AGENT_SKILL: 'Agent Skill 包',
  AUTOMATION: '自动化',
  BUSINESS_USER: '业务用户',
  MCP_CREDENTIAL: 'MCP 凭证',
  MCP_CREDENTIAL_ROLE: 'MCP 凭证角色',
  MCP_EMPLOYEE_CONNECTION: 'MCP 员工连接',
  MCP_HUB: 'MCP 发布与调用',
  MCP_PAYLOAD: 'MCP 留存载荷',
  MCP_PUBLICATION: 'MCP 对外发布',
  MEMORY_ERASURE: '跨域数据擦除',
  RUNTIME_SESSION: '会话保留',
  RUNTIME_USER_MAPPING: '运行时用户映射',
  TOOL_ACL: '调用权限',
  WORKFLOW_CREDENTIAL: 'Workflow 执行凭证',
  OTHER: '其他权限',
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

function permissionDomain(resourceType: string) {
  if (resourceType.startsWith('A2A_')) return 'A2A'
  if (resourceType.startsWith('MCP_')) return 'MCP'
  return resourceType
}
</script>

<style scoped lang="scss">
.role-workbench {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns: 256px minmax(0, 1fr);
  gap: var(--layout-section-gap);
}

.role-directory,
.role-detail {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  padding: var(--layout-panel-padding);
}

.role-directory {
  gap: 12px;
  overflow: hidden;
}
.role-directory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.header-title {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.header-title strong {
  font-size: 14px;
  font-weight: 650;
}
.role-count-badge {
  color: var(--text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.create-role-btn {
  flex-shrink: 0;
}
.role-search-wrap {
  width: 100%;
}
.role-list {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}
.role-directory__hint {
  margin: 0;
  padding-top: 12px;
  border-top: 1px solid var(--border-divider);
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.6;
}

.role-item {
  position: relative;
  display: flex;
  width: 100%;
  flex-shrink: 0;
  align-items: center;
  gap: 9px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-primary);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--motion-duration-fast) ease,
    border-color var(--motion-duration-fast) ease;
}
.role-item:hover {
  background: var(--surface-hover);
}
.role-item.is-active {
  border-color: var(--border-focus);
  background: var(--surface-glass-selected);
}
.role-item.is-active::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--brand-on-surface);
  content: '';
}
.role-item__icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  place-items: center;
  border-radius: var(--radius-sm);
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  font-size: 14px;
}
.role-item__icon.system {
  color: var(--status-warning);
}
.role-item__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}
.role-item__title-row {
  display: flex;
  min-width: 0;
}
.role-item__name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-item__code {
  overflow: hidden;
  color: var(--text-secondary);
  font-family: var(--font-family-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-item__tags {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
}
.type-tag {
  height: 20px;
  padding: 0 4px;
  font-size: 10px;
}
.inactive-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
}

.role-detail {
  gap: 12px;
  overflow-y: auto;
}
.role-detail__header {
  display: flex;
  flex-shrink: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px 20px;
  flex-wrap: wrap;
}
.header-main {
  display: flex;
  min-width: 0;
  flex: 1 1 360px;
  flex-direction: column;
  gap: 6px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.role-title {
  margin: 0;
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 650;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
.role-subtitle {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.role-stats-capsules {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-top: 5px;
}
.stat-capsule {
  display: flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}
.capsule-label {
  color: var(--text-secondary);
  font-size: 11px;
}
.capsule-value {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.role-meta-summary {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px 24px;
  flex-wrap: wrap;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-divider);
}
.meta-item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.meta-label {
  color: var(--text-secondary);
  font-size: 11px;
  white-space: nowrap;
}
.meta-value-code {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}
.meta-value-code code {
  padding: 0;
  color: var(--text-secondary);
  background: transparent;
  font-family: var(--font-family-mono);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.meta-badge-text {
  color: var(--text-secondary);
  font-size: 11px;
}
.notice-bar {
  flex-shrink: 0;
}

.role-form {
  flex-shrink: 0;
}
.role-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.role-form :deep(.el-form-item) {
  margin-bottom: 10px;
}
.role-form :deep(.el-form-item__label) {
  margin-bottom: 5px;
  font-size: 12px;
}
.role-form :deep(.liquid-glass-input) {
  width: 100%;
}

.permission-section {
  display: flex;
  min-height: 160px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}
.permission-heading {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.permission-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.permission-title-wrap h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}
.selected-summary {
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.selected-summary em {
  color: var(--brand-on-surface);
  font-style: normal;
  font-weight: 650;
}
.permission-toolbar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  flex-wrap: wrap;
}
.scope-switch {
  flex-shrink: 0;
}
.scope-switch span {
  color: var(--text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.permission-search-input {
  width: 260px;
  min-width: 200px;
  flex: 0 1 260px;
}
.quick-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.quick-actions > span {
  color: var(--text-secondary);
  font-size: 11px;
}

.permission-groups {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.permission-group + .permission-group {
  margin-top: 6px;
}
.group-header {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-glass-inset);
}
.group-disclosure {
  display: flex;
  min-width: 0;
  min-height: 34px;
  flex: 1;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  border: 0;
  color: var(--text-primary);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.group-chevron {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 11px;
  transition: transform var(--motion-duration-fast) ease;
}
.group-chevron.is-collapsed {
  transform: rotate(-90deg);
}
.group-name {
  font-size: 12px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.group-disclosure .group-code {
  overflow: hidden;
  max-width: 35%;
  padding: 0;
  color: var(--text-muted);
  background: transparent;
  font-family: var(--font-family-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-badge {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.text-action {
  flex-shrink: 0;
  padding: 4px 0;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.text-action:hover {
  color: var(--brand-on-surface);
}
.group-toggle-btn {
  font-size: 11px;
}

.permission-row {
  display: grid;
  min-height: 40px;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr) 66px;
  align-items: center;
  gap: 8px 16px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-divider);
}
.permission-row:last-child {
  border-bottom: 0;
}
.permission-row:not(.is-readonly):hover {
  background: var(--surface-hover);
}
.permission-identity {
  min-width: 0;
}
.permission-label {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.permission-indicator {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 14px;
}
.permission-indicator.is-included {
  color: var(--status-success);
}
.permission-name {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.permission-label :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  min-height: 24px;
  margin: 0;
  white-space: normal;
}
.permission-label :deep(.el-checkbox__label) {
  min-width: 0;
  padding-left: 8px;
  white-space: normal;
}
.permission-label :deep(.el-checkbox.is-disabled .permission-name) {
  color: var(--text-muted);
}
.permission-row .permission-code {
  min-width: 0;
  padding: 0;
  color: var(--text-secondary);
  background: transparent;
  font-family: var(--font-family-mono);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.permission-status {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  color: var(--text-muted);
  font-size: 11px;
  white-space: nowrap;
}
.permission-status.is-included {
  color: var(--status-success);
}
.permission-desc {
  margin: 3px 0 0 22px;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.role-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-divider);
}
.role-actions__hint {
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.6;
}
.role-actions__buttons {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
}
.role-item:focus-visible,
.group-disclosure:focus-visible,
.text-action:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

@media (max-width: 1080px), (max-height: 740px) {
  .role-workbench {
    align-items: start;
  }
  .role-detail {
    overflow: visible;
  }
  .permission-groups {
    overflow: visible;
  }
}

@media (max-width: 1080px) {
  .role-workbench {
    grid-template-columns: 1fr;
  }
  .role-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-height: 220px;
  }
  .role-directory__hint {
    padding-top: 8px;
  }
}

@media (max-width: 760px) {
  .role-workbench {
    gap: 12px;
  }
  .role-list {
    grid-template-columns: 1fr;
    max-height: 200px;
  }
  .role-form__grid {
    grid-template-columns: 1fr;
  }
  .role-detail__header,
  .role-actions {
    flex-direction: column;
    align-items: stretch;
  }
  .header-main {
    flex-basis: auto;
  }
  .role-title {
    font-size: 18px;
  }
  .role-stats-capsules {
    padding: 0;
  }
  .permission-toolbar {
    align-items: stretch;
  }
  .permission-search-input {
    width: 100%;
    min-width: 0;
    flex: 1 1 100%;
  }
  .permission-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 3px 8px;
  }
  .permission-identity {
    grid-column: 1;
    grid-row: 1;
  }
  .permission-row .permission-code {
    grid-column: 1;
    grid-row: 2;
    padding-left: 22px;
    font-size: 10px;
  }
  .permission-status {
    grid-column: 2;
    grid-row: 1 / 3;
  }
  .group-disclosure .group-code {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .role-item,
  .group-chevron {
    transition: none;
  }
}

@media (forced-colors: active) {
  .role-item.is-active {
    outline: 1px solid Highlight;
  }
  .permission-indicator.is-included,
  .permission-status.is-included {
    color: CanvasText;
  }
}
</style>
