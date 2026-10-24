<template>
  <div class="embed-session-audit-page project-workbench-page">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="project"
      title="嵌入式会话审计"
      description="查看当前项目下业务页面中的嵌入式对话会话、对话事件和页面动作。"
    >
      <template v-if="loaded" #tags>
        <el-tag type="info" effect="light">共 {{ sessions.length }} 个会话</el-tag>
        <el-tag v-if="currentProjectCode" type="info" effect="light">{{ currentProjectCode }}</el-tag>
      </template>
      <template #actions>
        <LiquidGlassButton source="wallpaper" :icon="Back" @click="goPageActions">返回前端页面管理</LiquidGlassButton>
        <el-tooltip content="刷新会话" placement="top">
          <LiquidGlassButton source="wallpaper" circle :icon="Refresh" :loading="loading" aria-label="刷新会话" @click="load" />
        </el-tooltip>
      </template>
    </PageHeader>

    <LiquidGlassTable
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="session-table workbench-list-surface"
      :loading="loading"
      :empty="loaded && sessions.length === 0"
      empty-description="当前项目还没有嵌入式会话"
      :total="sessions.length"
      :page-sizes="[20, 50, 100]"
      pagination-label="会话分页"
    >
      <el-table :data="pagedSessions" row-key="sessionId" @row-click="selectSession">
        <el-table-column prop="sessionId" label="会话 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="agentId" label="智能体" width="150" show-overflow-tooltip />
        <el-table-column prop="externalUserId" label="用户" width="150" show-overflow-tooltip />
        <el-table-column prop="pageInstanceId" label="页面实例" min-width="180" show-overflow-tooltip />
        <el-table-column prop="origin" label="来源域" min-width="180" show-overflow-tooltip />
        <el-table-column prop="route" label="路由" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="180" :formatter="formatTableDateTime" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="selectSession(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDrawer
      v-model="drawerVisible"
      title="会话详情"
      description="查看会话上下文、对话内容与页面动作。展开事件可阅读完整内容。"
      size="min(1120px, 94vw)"
      append-to-body
      destroy-on-close
    >
      <div v-if="selectedSession" class="session-detail" v-loading="detailLoading">
        <dl class="session-summary app-overlay-section">
          <div>
            <dt>会话 ID</dt>
            <dd class="session-identifier">{{ selectedSession.sessionId }}</dd>
          </div>
          <div>
            <dt>智能体</dt>
            <dd>{{ selectedSession.agentId || '-' }}</dd>
          </div>
          <div>
            <dt>用户</dt>
            <dd class="session-identifier">{{ selectedSession.externalUserId || '-' }}</dd>
          </div>
          <div>
            <dt>页面实例</dt>
            <dd class="session-identifier">{{ selectedSession.pageInstanceId || '-' }}</dd>
          </div>
          <div>
            <dt>来源域</dt>
            <dd>{{ selectedSession.origin || '-' }}</dd>
          </div>
          <div>
            <dt>路由</dt>
            <dd>{{ selectedSession.route || '-' }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd><el-tag :type="selectedSession.status === 'ACTIVE' ? 'success' : 'info'" effect="light">{{ selectedSession.status || '-' }}</el-tag></dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(selectedSession.createdAt) }}</dd>
          </div>
        </dl>

        <LiquidGlassTabs v-model="activeDetailTab" class="detail-tabs">
          <el-tab-pane label="对话事件" name="chat">
            <LiquidGlassTable source="backdrop" :show-pagination="false" class="detail-event-table">
              <el-table :data="chatEvents" row-key="id" size="small">
                <el-table-column type="expand" width="44">
                  <template #default="{ row }">
                    <div class="session-event-content">
                      <span class="session-event-label">完整内容</span>
                      <pre>{{ row.content || '此事件没有文本内容' }}</pre>
                      <template v-if="row.traceId">
                        <span class="session-event-label">追踪 ID</span>
                        <code>{{ row.traceId }}</code>
                      </template>
                      <template v-if="row.payloadJson">
                        <span class="session-event-label">事件数据</span>
                        <pre>{{ row.payloadJson }}</pre>
                      </template>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="eventType" label="事件" width="110" />
                <el-table-column prop="role" label="角色" width="90" />
                <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
                <el-table-column prop="traceId" label="追踪 ID" min-width="160" show-overflow-tooltip />
                <el-table-column prop="createdAt" label="创建时间" width="180" :formatter="formatTableDateTime" />
              </el-table>
            </LiquidGlassTable>
          </el-tab-pane>
          <el-tab-pane label="页面动作" name="actions">
            <LiquidGlassTable source="backdrop" :show-pagination="false" class="detail-event-table">
              <el-table :data="pageActions" row-key="id" size="small">
                <el-table-column type="expand" width="44">
                  <template #default="{ row }">
                    <div class="session-event-content">
                      <span class="session-event-label">请求 ID</span>
                      <code>{{ row.requestId }}</code>
                      <template v-if="row.argsJson">
                        <span class="session-event-label">请求参数</span>
                        <pre>{{ row.argsJson }}</pre>
                      </template>
                      <template v-if="row.resultJson">
                        <span class="session-event-label">执行结果</span>
                        <pre>{{ row.resultJson }}</pre>
                      </template>
                      <template v-if="row.errorMessage">
                        <span class="session-event-label">错误详情</span>
                        <pre>{{ row.errorMessage }}</pre>
                      </template>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="requestId" label="请求 ID" min-width="180" show-overflow-tooltip />
                <el-table-column prop="actionKey" label="动作" min-width="180" show-overflow-tooltip />
                <el-table-column prop="status" label="状态" width="120" />
                <el-table-column prop="targetPageInstanceId" label="页面实例" min-width="180" show-overflow-tooltip />
                <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
                <el-table-column prop="requestedAt" label="请求时间" width="180" :formatter="formatTableDateTime" />
                <el-table-column prop="completedAt" label="完成时间" width="180" :formatter="formatTableDateTime" />
              </el-table>
            </LiquidGlassTable>
          </el-tab-pane>
        </LiquidGlassTabs>
      </div>
    </AppDrawer>
  </div>
</template>

<script setup lang="ts">
import { formatDateTime, formatTableDateTime } from '@/utils/dateTime'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { LiquidGlassButton, LiquidGlassTable, LiquidGlassTabs } from '@/components/common/liquid-glass'
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
const loaded = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pagedSessions = computed(() => {
  const offset = (currentPage.value - 1) * pageSize.value
  return sessions.value.slice(offset, offset + pageSize.value)
})
const currentProjectCode = computed(() => String(route.params.projectCode || route.query.projectCode || ''))

async function load() {
  loading.value = true
  try {
    const params = currentProjectCode.value ? { appId: currentProjectCode.value } : {}
    const { data } = await listEmbedSessions(params)
    sessions.value = data || []
    loaded.value = true
    const lastPage = Math.max(1, Math.ceil(sessions.value.length / pageSize.value))
    if (currentPage.value > lastPage) currentPage.value = lastPage
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

.session-table {
  min-height: 0;
}

.session-table :deep(.el-table__row) {
  cursor: pointer;
}

.session-detail {
  display: grid;
  gap: 16px;
  container-type: inline-size;
}

.session-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
  margin: 0;
}

.session-summary div {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.session-summary dt {
  color: var(--text-secondary);
  font-size: 12px;
}

.session-summary dd {
  min-width: 0;
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.session-identifier { font-family: var(--font-family-mono, monospace); }

.detail-event-table { flex: none; width: 100%; }

.session-event-content {
  position: sticky;
  inset-inline-start: 0;
  display: grid;
  gap: 8px;
  box-sizing: border-box;
  max-inline-size: 100cqi;
  padding: 16px 24px;
  color: var(--text-primary);
  background: var(--surface-glass-inset);
}

.session-event-label { font-size: 12px; font-weight: 600; color: var(--text-secondary); }

.session-event-content pre,
.session-event-content code {
  margin: 0 0 8px;
  font: inherit;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.session-event-content code { font-family: var(--font-family-mono, monospace); }

.detail-tabs {
  min-width: 0;
}

@media (max-width: 600px) {
  .session-summary {
    grid-template-columns: 1fr;
  }
}
</style>
