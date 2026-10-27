<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { DocumentChecked, Monitor, Promotion, User } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { LiquidGlassNav } from '@/components/common/liquid-glass'
import { listMcpWriteApprovals } from '@/api/mcp'

const route = useRoute()
const isPublicationDetail = computed(() => route.name === 'McpHubPublicationDetail')
const helpOpen = ref(false)
const pendingCount = ref<number | null>(null)
const items = [
  { path: '/mcp-hub/publications', label: 'MCP 服务', icon: Promotion },
  { path: '/mcp-hub/employee-connections', label: '员工连接', icon: User },
  { path: '/mcp-hub/write-approvals', label: '写操作审批', icon: DocumentChecked },
  { path: '/mcp-hub/call-logs', label: '调用记录', icon: Monitor },
]

const activePath = computed(() => {
  const path = route.path
  return items.find((item) => path.startsWith(item.path))?.path ?? '/mcp-hub/publications'
})

watch(() => route.path, async (path, _previous, onCleanup) => {
  if (path === '/mcp-hub/write-approvals') return
  let current = true
  onCleanup(() => { current = false })
  try {
    const { data } = await listMcpWriteApprovals({ state: 'PENDING', limit: 1, offset: 0 })
    if (current) pendingCount.value = data.pendingCount
  } catch {
    if (current) pendingCount.value = null
  }
}, { immediate: true })
</script>

<template>
  <WorkbenchPage class="mcp-hub-layout" layout="list" :data-workspace="isPublicationDetail ? 'detail' : 'list'">
    <PageHeader
      variant="overview"
      height-preset="compact"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="MCP 开放"
    >
      <template #actions>
        <div class="hub-navigation">
          <LiquidGlassNav source="wallpaper" class="hub-nav" aria-label="MCP 开放工作区">
            <router-link
              v-for="item in items"
              :key="item.path"
              :to="item.path"
              :class="{ 'is-active': activePath === item.path }"
              :aria-current="activePath === item.path ? 'page' : undefined"
            >
              <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
              {{ item.label }}
              <span v-if="item.path === '/mcp-hub/write-approvals' && pendingCount" class="pending-count" :aria-label="`${pendingCount} 项待审批`">{{ pendingCount }}</span>
            </router-link>
          </LiquidGlassNav>
        </div>
      </template>
    </PageHeader>

    <router-view @pending-count="pendingCount = $event" @help="helpOpen = true" />

    <AppDialog v-model="helpOpen" title="MCP 开放使用说明" width="min(600px, 94vw)">
      <div class="hub-help">
        <p>将业务方法和已发布 Workflow 组成 MCP 服务，供员工自己的 AI 工具和经授权的第三方智能体调用。</p>
        <h3>创建并发布服务</h3>
        <p>新建 MCP 服务，选择业务方法或已发布 Workflow，检查通过后发布。</p>
        <h3>选择接入方式</h3>
        <p>员工使用：在服务详情开通「员工使用」，员工从业务系统的 AI 对话面板连接自己的 AI 工具。</p>
        <p>客户端接入：在服务详情创建客户端凭证，按接入配置连接。API Key 只在创建或轮换时显示一次。</p>
        <h3>管理使用情况</h3>
        <p>在「员工连接」查看或断开连接；在「写操作审批」处理请求；在「调用记录」查看结果和执行详情。</p>
      </div>
    </AppDialog>
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.mcp-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.mcp-hub-layout[data-workspace='list'] {
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
}

.mcp-hub-layout[data-workspace='list'] > :deep(.mcp-list-page) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 760px) {
  .mcp-hub-layout[data-workspace='list'] { overflow-y: auto; }
  .mcp-hub-layout[data-workspace='list'] > :deep(.mcp-list-page) { flex: 1 0 auto; }
  .mcp-hub-layout[data-workspace='list'] :deep(.workbench-list-surface) {
    flex: 1 0 280px;
    min-height: 280px;
  }
}

.hub-navigation { display: flex; flex-wrap: wrap; align-items: center; gap: var(--layout-control-gap); }
.hub-nav { margin-top: 0; }
.pending-count { color: var(--status-warning); font-size: 12px; font-weight: 700; }
.hub-help { color: var(--text-secondary); line-height: 1.75; }
.hub-help h3 { margin: 16px 0 4px; color: var(--text-primary); font-size: 14px; }
.hub-help h3:first-child { margin-top: 0; }
.hub-help p { margin: 4px 0; }
</style>
