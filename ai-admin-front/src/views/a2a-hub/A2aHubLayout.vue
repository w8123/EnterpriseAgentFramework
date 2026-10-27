<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Connection, List, Promotion, Setting } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassDropdown, LiquidGlassNav } from '@/components/common/liquid-glass'

const route = useRoute()
const router = useRouter()
const helpVisible = ref(false)
const items = [
  { path: '/a2a-hub/overview', label: '总览', hint: '健康与待办' },
  { path: '/a2a-hub/publications', label: '对外发布', hint: '让外部 Agent 调用本平台 Agent', icon: Promotion },
  { path: '/a2a-hub/remote-agents', label: '外部 Agent', hint: '接入可协作的外部 Agent', icon: Connection },
  { path: '/a2a-hub/tasks', label: '协作任务', hint: '查看双向任务与执行记录', icon: List },
]
const managementItems = [
  { path: '/a2a-hub/trust', label: '信任与策略', hint: '身份与凭据' },
  { path: '/a2a-hub/developer', label: '开发与诊断', hint: '接入与合规' },
]

const activePath = computed(() => [...items, ...managementItems].find((item) => route.path.startsWith(item.path))?.path)
const activeManagement = computed(() => managementItems.find((item) => item.path === activePath.value))

function openManagement(command: string) {
  if (command === 'help') helpVisible.value = true
  else router.push(command)
}
</script>

<template>
  <WorkbenchPage class="a2a-hub-layout" layout="list">
    <PageHeader
      variant="overview"
      height-preset="compact"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="A2A 互联中心"
    >
      <template #actions>
        <div class="hub-navigation">
          <LiquidGlassNav source="wallpaper" class="hub-nav" aria-label="A2A 互联中心工作区">
            <router-link
              v-for="item in items"
              :key="item.path"
              :to="item.path"
              :class="{ 'is-active': activePath === item.path }"
              :aria-current="activePath === item.path ? 'page' : undefined"
              :title="item.hint"
            >
              <el-icon v-if="item.icon" aria-hidden="true"><component :is="item.icon" /></el-icon>
              {{ item.label }}
            </router-link>
          </LiquidGlassNav>
          <LiquidGlassDropdown source="wallpaper" trigger="click" @command="openManagement">
            <LiquidGlassButton source="wallpaper" :type="activeManagement ? 'primary' : 'default'" :icon="Setting" aria-label="治理与诊断">
              {{ activeManagement?.label ?? '管理工具' }}
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </LiquidGlassButton>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in managementItems" :key="item.path" :command="item.path" :aria-current="activePath === item.path ? 'page' : undefined">
                  {{ item.label }}
                </el-dropdown-item>
                <el-dropdown-item divided command="help">使用说明</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </LiquidGlassDropdown>
        </div>
      </template>
    </PageHeader>

    <!-- 子页根节点挂 v-loading；合并玻璃加载层，避免整页刷新时盖一层白雾。 -->
    <router-view v-slot="{ Component }">
      <component :is="Component" class="glass-loading-surface" />
    </router-view>

    <AppDialog v-model="helpVisible" title="A2A 使用说明" width="min(600px, 94vw)">
      <div class="hub-help">
        <h3>对外发布</h3>
        <p>选择本平台 Agent 的已发布配置，设置对外服务与访问权限，检查通过后确认发布。</p>
        <h3>外部 Agent</h3>
        <p>通过 HTTPS Agent Card 地址接入外部 Agent，完成验证与审核后，在本地 Agent 的协作配置中绑定已审核版本，并选择允许委派的任务。外部服务变化后需要重新审核。</p>
        <h3>协作任务</h3>
        <p>对外服务表示外部调用本平台，外部协作表示本平台调用外部。按方向和状态筛选任务，打开详情查看完整标识、调用身份、运行记录与产物。</p>
        <h3>信任与策略</h3>
        <p>信任策略控制调用范围、数据权限和任务限额；调用身份（Principal）由认证结果确认，请求 metadata 中的用户、租户、角色和 scope 不会授予权限。外部 Agent 默认禁用 Personal Memory。入站 API Key 仅在签发或轮换后显示一次，出站秘密保存后不可读取。</p>
        <h3>开发与诊断</h3>
        <p>选择已发布的服务，复制标准接入地址。调用示例、协议支持与验证记录、Agent Card 快照可展开查看；已实现的接口不代表已通过官方 TCK 或双系统端到端验证。</p>
      </div>
    </AppDialog>
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.a2a-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.hub-navigation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--layout-control-gap);
  min-width: 0;
}

.hub-nav { margin-top: 0; }
.hub-help { color: var(--text-secondary); line-height: 1.7; }
.hub-help h3 { margin: 16px 0 4px; color: var(--text-primary); font-size: 14px; }
.hub-help h3:first-child { margin-top: 0; }
.hub-help p { margin: 0; }
</style>
