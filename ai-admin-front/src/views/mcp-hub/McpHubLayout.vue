<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { DataAnalysis, Monitor, Promotion } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { LiquidGlassNav } from '@/components/common/liquid-glass'

const route = useRoute()
const items = [
  { path: '/mcp-hub/overview', label: '总览', icon: DataAnalysis },
  { path: '/mcp-hub/publications', label: '对外发布', icon: Promotion },
  { path: '/mcp-hub/call-logs', label: '调用流水', icon: Monitor },
]

const activePath = computed(() => {
  const path = route.path
  return items.find((item) => path.startsWith(item.path))?.path ?? '/mcp-hub/overview'
})
</script>

<template>
  <WorkbenchPage class="mcp-hub-layout" layout="list">
    <PageHeader
      variant="overview"
      height-preset="compact"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="MCP 互联中心"
      description="将能力和已发布 Workflow 安全发布为 MCP Tool。"
    >
      <template #meta>
        <LiquidGlassNav source="wallpaper" class="hub-nav" aria-label="MCP 互联中心工作区">
          <router-link
            v-for="item in items"
            :key="item.path"
            :to="item.path"
            :class="{ 'is-active': activePath === item.path }"
            :aria-current="activePath === item.path ? 'page' : undefined"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            {{ item.label }}
          </router-link>
        </LiquidGlassNav>
      </template>
    </PageHeader>

    <router-view />
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.mcp-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.hub-nav {
  margin-top: 6px;
}
</style>
