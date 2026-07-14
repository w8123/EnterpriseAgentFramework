<template>
  <div class="embed-session-audit-page project-workbench-page">
    <div class="page-head">
      <div>
        <h1>嵌入式会话审计</h1>
        <p>查看当前项目下业务页面中的嵌入式对话会话、对话事件和页面动作。</p>
      </div>
      <div class="page-actions">
        <el-button @click="goPageActions">返回前端页面管理</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>会话历史</template>
      <el-table :data="sessions" row-key="sessionId" @row-click="selectSession">
        <el-table-column prop="sessionId" label="会话 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="agentId" label="智能体" width="150" show-overflow-tooltip />
        <el-table-column prop="externalUserId" label="用户" width="150" show-overflow-tooltip />
        <el-table-column prop="pageInstanceId" label="页面实例" min-width="180" show-overflow-tooltip />
        <el-table-column prop="origin" label="来源域" min-width="180" show-overflow-tooltip />
        <el-table-column prop="route" label="路由" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="selectSession(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <AppDrawer
      v-model="drawerVisible"
      title="会话详情"
      size="72%"
      append-to-body
      destroy-on-close
    >
      <div v-if="selectedSession" class="session-detail" v-loading="detailLoading">
        <div class="session-summary">
          <div>
            <span>会话 ID</span>
            <strong>{{ selectedSession.sessionId }}</strong>
          </div>
          <div>
            <span>智能体</span>
            <strong>{{ selectedSession.agentId || '-' }}</strong>
          </div>
          <div>
            <span>用户</span>
            <strong>{{ selectedSession.externalUserId || '-' }}</strong>
          </div>
          <div>
            <span>页面实例</span>
            <strong>{{ selectedSession.pageInstanceId || '-' }}</strong>
          </div>
          <div>
            <span>来源域</span>
            <strong>{{ selectedSession.origin || '-' }}</strong>
          </div>
          <div>
            <span>路由</span>
            <strong>{{ selectedSession.route || '-' }}</strong>
          </div>
          <div>
            <span>状态</span>
            <strong>{{ selectedSession.status || '-' }}</strong>
          </div>
          <div>
            <span>创建时间</span>
            <strong>{{ selectedSession.createdAt || '-' }}</strong>
          </div>
        </div>

        <el-tabs v-model="activeDetailTab" class="detail-tabs">
          <el-tab-pane label="对话事件" name="chat">
            <el-table :data="chatEvents" row-key="id" size="small">
              <el-table-column prop="eventType" label="事件" width="150" />
              <el-table-column prop="role" label="角色" width="90" />
              <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
              <el-table-column prop="traceId" label="追踪 ID" min-width="160" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="180" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="页面动作" name="actions">
            <el-table :data="pageActions" row-key="id" size="small">
              <el-table-column prop="requestId" label="请求 ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="actionKey" label="动作" min-width="180" show-overflow-tooltip />
              <el-table-column prop="status" label="状态" width="120" />
              <el-table-column prop="targetPageInstanceId" label="页面实例" min-width="180" show-overflow-tooltip />
              <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
              <el-table-column prop="requestedAt" label="请求时间" width="180" />
              <el-table-column prop="completedAt" label="完成时间" width="180" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </AppDrawer>
  </div>
</template>

<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listEmbedChatEvents,
  listEmbedSessions,
  listPageActionEvents,
  type EmbedChatEventView,
  type EmbedSessionView,
  type PageActionEventView,
} from '@/api/embedOps'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detailLoading = ref(false)
const drawerVisible = ref(false)
const activeDetailTab = ref('chat')
const sessions = ref<EmbedSessionView[]>([])
const pageActions = ref<PageActionEventView[]>([])
const chatEvents = ref<EmbedChatEventView[]>([])
const selectedSession = ref<EmbedSessionView | null>(null)
const selectedSessionId = ref('')
const currentProjectCode = computed(() => String(route.params.projectCode || route.query.projectCode || ''))

async function load() {
  loading.value = true
  try {
    const params = currentProjectCode.value ? { appId: currentProjectCode.value } : {}
    const { data } = await listEmbedSessions(params)
    sessions.value = data || []
    if (selectedSessionId.value) {
      await loadSessionDetail(selectedSessionId.value)
    }
  } finally {
    loading.value = false
  }
}

async function selectSession(row: EmbedSessionView) {
  selectedSession.value = row
  selectedSessionId.value = row.sessionId
  drawerVisible.value = true
  activeDetailTab.value = 'chat'
  await loadSessionDetail(row.sessionId)
}

async function loadSessionDetail(sessionId: string) {
  detailLoading.value = true
  try {
    const [actions, events] = await Promise.all([
      listPageActionEvents({ sessionId }),
      listEmbedChatEvents(sessionId),
    ])
    pageActions.value = actions.data || []
    chatEvents.value = events.data || []
  } finally {
    detailLoading.value = false
  }
}

function goPageActions() {
  router.push({
    name: 'EmbedOpsMonitor',
    params: { projectCode: currentProjectCode.value },
  })
}

onMounted(load)
</script>

<style scoped>
.embed-session-audit-page {
  display: flex;
  flex-direction: column;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-head h1 {
  margin: 0;
  font-size: 22px;
}

.page-head p {
  margin: 6px 0 0;
  color: var(--text-secondary);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.session-detail {
  display: grid;
  gap: 16px;
}

.session-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--bg-muted);
}

.session-summary div {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.session-summary span {
  color: var(--text-secondary);
  font-size: 12px;
}

.session-summary strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-tabs {
  min-width: 0;
}

@media (max-width: 980px) {
  .session-summary {
    grid-template-columns: 1fr;
  }
}
</style>
