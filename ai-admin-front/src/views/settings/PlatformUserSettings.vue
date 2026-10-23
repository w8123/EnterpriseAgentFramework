<template>
  <LiquidGlassPage class="platform-identity-page">
    <PageHeader
      variant="overview"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="账号与权限"
      description="管理平台账号、角色、项目授权与安全审计。"
    >
      <template #tags>
        <el-tag type="success" effect="light">RBAC</el-tag>
        <el-tag type="info" effect="light">全局 / 项目范围</el-tag>
      </template>
      <template #meta>
        <MetricStrip presentation="inline" source="wallpaper" :items="metrics" aria-label="身份治理概览" />
      </template>
      <template #actions>
        <span v-if="lastRefreshedAt" class="refresh-time">更新于 {{ lastRefreshedAt }}</span>
        <el-tooltip content="刷新账号、角色和审计数据" placement="top">
          <LiquidGlassButton source="wallpaper" circle :icon="Refresh" :loading="loading" aria-label="刷新" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <section class="identity-workbench workbench-list-surface" aria-label="账号与权限工作区">
      <ol class="boundary-flow" aria-label="授权链路">
        <li v-for="step in boundarySteps" :key="step.title"><strong>{{ step.title }}</strong><span>{{ step.text }}</span></li>
      </ol>
      <LiquidGlassTabs v-model="activeTab" source="wallpaper" class="identity-tabs">
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
      </LiquidGlassTabs>
    </section>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentChecked, Lock, Refresh, User } from '@element-plus/icons-vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { LiquidGlassButton, LiquidGlassPage, LiquidGlassTabs } from '@/components/common/liquid-glass'
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

const boundarySteps = [
  { title: '账号', text: '谁可以进入平台' },
  { title: '角色', text: '可以执行哪些操作' },
  { title: '作用域', text: '权限在哪些项目生效' },
  { title: '审计', text: '谁在何时改变了什么' },
]

const metrics = computed<MetricStripItem[]>(() => {
  const activeAccounts = accounts.value.filter((account) => account.status === 'ACTIVE').length
  const customRoles = roles.value.filter((role) => role.roleKind === 'CUSTOM').length
  const activeSessions = accounts.value.reduce((sum, account) => sum + account.activeSessionCount, 0)
  const globalAdmins = accounts.value.filter((account) => account.grants.some((grant) => (
    grant.roleCode === 'PLATFORM_ADMIN' && grant.scopeType === 'GLOBAL'
  ))).length
  return [
    { key: 'accounts', label: '启用账号', value: activeAccounts, hint: `共 ${accounts.value.length} 个平台人员账号` },
    { key: 'roles', label: '自定义角色', value: customRoles, hint: `另有 ${roles.value.length - customRoles} 个系统角色` },
    { key: 'sessions', label: '活跃会话', value: activeSessions, hint: '可按账号立即撤销' },
    { key: 'admins', label: '全局管理员', value: globalAdmins, hint: '末位管理员受安全保护' },
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
  color: var(--text-primary);
}

.refresh-time {
  color: var(--text-on-wallpaper-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.identity-workbench {
  position: relative;
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
}

// 授权链路是一行说明文字，直接落在壁纸上，不再单独占一块面板。
.boundary-flow {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  align-items: center;
  height: calc(var(--control-height) + 10px);
  margin: 0;
  padding: 0;
  gap: 6px;
  list-style: none;
  color: var(--text-on-wallpaper-secondary);
  font-size: 12px;
  pointer-events: none;

  li {
    display: inline-flex;
    align-items: baseline;
    gap: 4px;
  }

  li + li::before {
    content: '→';
    margin-right: 6px;
    opacity: 0.6;
  }

  strong {
    color: var(--text-on-wallpaper);
    font-weight: 600;
  }
}

.identity-tabs {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
}

.identity-tabs :deep(.el-tabs__header) {
  flex: 0 0 auto;
  margin-bottom: var(--layout-section-gap);
}

.identity-tabs :deep(.el-tabs__content) {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  overflow-y: auto;
  padding-bottom: var(--layout-list-page-end);
}

.identity-tabs :deep(.el-tab-pane) {
  display: flex;
  flex: 1 0 auto;
  flex-direction: column;
}

.identity-tabs :deep(.el-tab-pane > *) {
  flex: 1 0 auto;
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
    background: color-mix(in srgb, currentColor 14%, transparent);
    font-size: 11px;
    font-style: normal;
  }
}

@media (max-width: 1180px) {
  .boundary-flow {
    display: none;
  }
}

@media (max-width: 760px) {
  // 窄屏由整页滚动，工作区不再自带一层滚动，避免表格被压成只剩表头。
  .identity-workbench,
  .identity-tabs,
  .identity-tabs :deep(.el-tabs__content) {
    flex: 0 0 auto;
    overflow: visible;
  }

  .identity-tabs :deep(.el-tab-pane > *) {
    min-height: 420px;
  }
}

@media (max-width: 720px) {
  .identity-tabs :deep(.el-tabs__item) {
    padding: 0 10px !important;
  }

  .tab-label > .el-icon {
    display: none;
  }
}
</style>
