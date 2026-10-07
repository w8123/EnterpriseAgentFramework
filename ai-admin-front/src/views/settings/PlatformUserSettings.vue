<template>
  <WorkbenchPage class="platform-identity-page" layout="list">
    <PageHeader
      variant="overview"
      domain="platform"
      eyebrow="Identity & Access Management"
      title="账号与权限"
      description="管理平台人员账号、角色、项目范围与安全审计；业务终端用户保持独立身份域。"
    >
      <template #tags>
        <el-tag type="success" effect="plain">RBAC</el-tag>
        <el-tag effect="plain">全局 / 项目范围</el-tag>
      </template>
      <template #actions>
        <span v-if="lastRefreshedAt" class="refresh-time">更新于 {{ lastRefreshedAt }}</span>
        <el-tooltip content="刷新账号、角色和审计数据" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <section class="metric-grid" aria-label="身份治理概览">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card">
        <span class="metric-card__icon" :class="metric.tone">
          <el-icon><component :is="metric.icon" /></el-icon>
        </span>
        <div>
          <strong>{{ metric.value }}</strong>
          <span>{{ metric.label }}</span>
        </div>
        <small>{{ metric.caption }}</small>
      </article>
    </section>

    <section class="boundary-note">
      <div><strong>账号</strong><span>谁可以进入平台</span></div>
      <el-icon><ArrowRight /></el-icon>
      <div><strong>角色</strong><span>可以执行哪些操作</span></div>
      <el-icon><ArrowRight /></el-icon>
      <div><strong>作用域</strong><span>权限在哪些项目生效</span></div>
      <el-icon><ArrowRight /></el-icon>
      <div><strong>审计</strong><span>谁在何时改变了什么</span></div>
    </section>

    <section class="identity-workbench workbench-list-surface">
      <el-tabs v-model="activeTab" class="identity-tabs">
        <el-tab-pane name="accounts">
          <template #label>
            <span class="tab-label"><el-icon><User /></el-icon>账号管理<em>{{ accounts.length }}</em></span>
          </template>
          <AccountDirectoryPanel
            :accounts="accounts"
            :roles="roles"
            :loading="loading"
            @changed="reload"
          />
        </el-tab-pane>

        <el-tab-pane name="roles">
          <template #label>
            <span class="tab-label"><el-icon><Lock /></el-icon>角色与权限<em>{{ roles.length }}</em></span>
          </template>
          <RolePermissionPanel
            :roles="roles"
            :permissions="permissions"
            :loading="loading"
            @changed="reload"
          />
        </el-tab-pane>

        <el-tab-pane name="audit">
          <template #label>
            <span class="tab-label"><el-icon><DocumentChecked /></el-icon>授权审计<em>{{ auditEvents.length }}</em></span>
          </template>
          <AuthorizationAuditPanel :events="auditEvents" :loading="loading" />
        </el-tab-pane>
      </el-tabs>
    </section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Connection,
  DocumentChecked,
  Key,
  Lock,
  Refresh,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import {
  listPlatformAccounts,
  listPlatformAuthAuditEvents,
  listPlatformManagedRoles,
  listPlatformPermissions,
  type PlatformAccountUserView,
  type PlatformAuthAuditEventView,
  type PlatformPermissionView,
  type PlatformRoleManagementView,
} from '@/api/platformAuth'
import { useProjectStore } from '@/store/project'
import AccountDirectoryPanel from './platform-identity/AccountDirectoryPanel.vue'
import AuthorizationAuditPanel from './platform-identity/AuthorizationAuditPanel.vue'
import RolePermissionPanel from './platform-identity/RolePermissionPanel.vue'

const projectStore = useProjectStore()
const activeTab = ref('accounts')
const loading = ref(false)
const accounts = ref<PlatformAccountUserView[]>([])
const roles = ref<PlatformRoleManagementView[]>([])
const permissions = ref<PlatformPermissionView[]>([])
const auditEvents = ref<PlatformAuthAuditEventView[]>([])
const lastRefreshedAt = ref('')

const metrics = computed(() => {
  const activeAccounts = accounts.value.filter((account) => account.status === 'ACTIVE').length
  const customRoles = roles.value.filter((role) => role.roleKind === 'CUSTOM').length
  const activeSessions = accounts.value.reduce((sum, account) => sum + account.activeSessionCount, 0)
  const globalAdmins = accounts.value.filter((account) => account.grants.some((grant) => (
    grant.roleCode === 'PLATFORM_ADMIN' && grant.scopeType === 'GLOBAL'
  ))).length
  return [
    { label: '启用账号', value: activeAccounts, caption: `共 ${accounts.value.length} 个平台人员账号`, icon: markRaw(UserFilled), tone: 'blue' },
    { label: '自定义角色', value: customRoles, caption: `另有 ${roles.value.length - customRoles} 个系统角色`, icon: markRaw(Lock), tone: 'violet' },
    { label: '活跃会话', value: activeSessions, caption: '可按账号立即撤销', icon: markRaw(Connection), tone: 'green' },
    { label: '全局管理员', value: globalAdmins, caption: '末位管理员受安全保护', icon: markRaw(Key), tone: 'amber' },
  ]
})

async function reload() {
  loading.value = true
  try {
    const [accountResponse, roleResponse, permissionResponse, auditResponse] = await Promise.all([
      listPlatformAccounts(),
      listPlatformManagedRoles(),
      listPlatformPermissions(),
      listPlatformAuthAuditEvents({ limit: 200 }),
      projectStore.fetchProjects(),
    ])
    accounts.value = accountResponse.data ?? []
    roles.value = roleResponse.data ?? []
    permissions.value = permissionResponse.data ?? []
    auditEvents.value = auditResponse.data ?? []
    lastRefreshedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    ElMessage.error('账号与权限数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.platform-identity-page {
  min-height: 100%;
  color: var(--text-primary);
}

.refresh-time {
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 15px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);

  > div {
    display: grid;
    gap: 1px;
  }

  strong {
    font-size: 23px;
    line-height: 1;
  }

  span,
  small {
    color: var(--text-secondary);
    font-size: 12px;
  }

  small {
    grid-column: 1 / -1;
  }
}

.metric-card__icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 12px;
  font-size: 18px;

  &.blue { color: #2563eb; background: rgba(37, 99, 235, 0.1); }
  &.violet { color: #7c3aed; background: rgba(124, 58, 237, 0.1); }
  &.green { color: #059669; background: rgba(5, 150, 105, 0.1); }
  &.amber { color: #d97706; background: rgba(217, 119, 6, 0.1); }
}

.boundary-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 12px 18px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.2);
  border-radius: var(--radius-lg);
  background: rgb(var(--brand-selected-rgb) / 0.26);

  > div {
    display: grid;
    gap: 1px;
    text-align: center;
  }

  span {
    color: var(--text-secondary);
    font-size: 11px;
  }

  > .el-icon {
    color: var(--text-secondary);
  }
}

.identity-workbench {
  min-width: 0;
  padding: 0 18px 18px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-xl);
  background: var(--surface-glass-panel);
  box-shadow: 0 18px 44px rgb(var(--brand-primary-rgb) / 0.08);
}

.identity-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.identity-tabs :deep(.el-tabs__item) {
  height: 54px;
  padding: 0 22px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;

  em {
    display: inline-grid;
    min-width: 20px;
    height: 20px;
    place-items: center;
    padding: 0 5px;
    border-radius: 10px;
    background: var(--surface-glass-control);
    font-size: 11px;
    font-style: normal;
  }
}

@media (max-width: 1050px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .boundary-note {
    align-items: stretch;
    flex-direction: column;

    > .el-icon {
      align-self: center;
      transform: rotate(90deg);
    }
  }

  .identity-workbench {
    padding-inline: 10px;
  }

  .identity-tabs :deep(.el-tabs__item) {
    padding: 0 8px;
  }
}
</style>
