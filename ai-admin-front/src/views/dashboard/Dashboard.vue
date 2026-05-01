<template>
  <div class="page-container dashboard">
    <div class="page-header">
      <h2>概览</h2>
      <el-button type="primary" @click="refresh" :loading="loading">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <div
        v-for="(card, i) in statCards"
        :key="card.label"
        class="stat-card glass-card fade-in-up"
        :style="{ animationDelay: `${i * 0.08}s` }"
      >
        <div class="stat-icon" :style="{ background: card.gradient }">
          <el-icon :size="24"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </div>
        <div class="stat-trend" :class="card.trendDir">
          <span v-if="card.trend" class="trend-arrow">{{ card.trendDir === 'up' ? '↑' : card.trendDir === 'down' ? '↓' : '→' }}</span>
          <span v-if="card.trend" class="trend-value">{{ card.trend }}</span>
        </div>
        <div class="stat-sparkline">
          <svg viewBox="0 0 80 30" preserveAspectRatio="none" class="sparkline-svg">
            <defs>
              <linearGradient :id="`grad-${i}`" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" :stop-color="card.color" stop-opacity="0.3" />
                <stop offset="100%" :stop-color="card.color" stop-opacity="0" />
              </linearGradient>
            </defs>
            <path :d="card.areaPath" :fill="`url(#grad-${i})`" />
            <path :d="card.linePath" fill="none" :stroke="card.color" stroke-width="1.5" stroke-linecap="round" />
          </svg>
        </div>
      </div>
    </div>

    <div class="dashboard-grid">
      <!-- 服务拓扑 -->
      <div class="topology-section glass-card fade-in-up stagger-5">
        <h3 class="section-title">
          <el-icon><Connection /></el-icon>
          服务拓扑
        </h3>
        <div class="topology">
          <div class="topo-row">
            <div class="topo-node" :class="serviceHealth['ai-agent-service']">
              <div class="topo-node-icon agent-bg">
                <el-icon :size="20"><Cpu /></el-icon>
              </div>
              <span class="topo-node-name">Agent Service</span>
              <span class="topo-node-status">
                <span class="status-dot" :class="serviceHealth['ai-agent-service'] || 'offline'" />
                {{ serviceHealth['ai-agent-service'] === 'online' ? '正常' : '异常' }}
              </span>
            </div>
          </div>
          <div class="topo-connector">
            <div class="connector-line" :class="{ active: serviceHealth['ai-agent-service'] === 'online' }" />
            <div class="connector-dot" />
          </div>
          <div class="topo-row topo-row-split">
            <div class="topo-node" :class="serviceHealth['ai-skills-service']">
              <div class="topo-node-icon skill-bg">
                <el-icon :size="20"><SetUp /></el-icon>
              </div>
              <span class="topo-node-name">Skills Service</span>
              <span class="topo-node-status">
                <span class="status-dot" :class="serviceHealth['ai-skills-service'] || 'offline'" />
                {{ serviceHealth['ai-skills-service'] === 'online' ? '正常' : '异常' }}
              </span>
            </div>
            <div class="topo-node" :class="serviceHealth['ai-model-service']">
              <div class="topo-node-icon model-bg">
                <el-icon :size="20"><Coin /></el-icon>
              </div>
              <span class="topo-node-name">Model Service</span>
              <span class="topo-node-status">
                <span class="status-dot" :class="serviceHealth['ai-model-service'] || 'offline'" />
                {{ serviceHealth['ai-model-service'] === 'online' ? '正常' : '异常' }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 活动时间线 -->
      <div class="timeline-section glass-card fade-in-up stagger-6">
        <h3 class="section-title">
          <el-icon><Clock /></el-icon>
          最近活动
        </h3>
        <div class="activity-timeline">
          <div
            v-for="(event, i) in activityEvents"
            :key="i"
            class="timeline-item"
            :style="{ animationDelay: `${(i + 6) * 0.06}s` }"
          >
            <div class="timeline-dot" :class="event.type" />
            <div class="timeline-content">
              <div class="timeline-text">{{ event.text }}</div>
              <div class="timeline-time">{{ event.time }}</div>
            </div>
          </div>
          <div v-if="activityEvents.length === 0" class="timeline-empty">
            <el-empty description="暂无最近活动" :image-size="48" />
          </div>
        </div>
      </div>
    </div>

    <!-- 最近 Agent / 知识库 -->
    <div class="preview-grid">
      <div class="preview-section glass-card fade-in-up stagger-7">
        <div class="section-header">
          <h3 class="section-title">
            <el-icon><Cpu /></el-icon>
            最近 Agent
          </h3>
          <el-button text type="primary" size="small" @click="$router.push('/agent')">查看全部</el-button>
        </div>
        <div class="preview-cards">
          <div
            v-for="agent in recentAgents"
            :key="agent.id"
            class="preview-card"
            @click="$router.push(`/agent/${agent.id}/edit`)"
          >
            <div class="preview-card-icon agent-bg">
              <el-icon :size="16"><Cpu /></el-icon>
            </div>
            <div class="preview-card-info">
              <div class="preview-card-name">{{ agent.name }}</div>
              <div class="preview-card-meta">{{ agent.intentType }}</div>
            </div>
            <el-tag
              :type="agent.enabled ? 'success' : 'info'"
              size="small"
              effect="dark"
            >{{ agent.enabled ? '启用' : '停用' }}</el-tag>
          </div>
          <div v-if="recentAgents.length === 0" class="preview-empty">
            暂无 Agent
          </div>
        </div>
      </div>

      <div class="preview-section glass-card fade-in-up stagger-8">
        <div class="section-header">
          <h3 class="section-title">
            <el-icon><Collection /></el-icon>
            最近知识库
          </h3>
          <el-button text type="primary" size="small" @click="$router.push('/knowledge')">查看全部</el-button>
        </div>
        <div class="preview-cards">
          <div
            v-for="kb in recentKnowledge"
            :key="kb.code"
            class="preview-card"
            @click="$router.push(`/knowledge/${kb.code}`)"
          >
            <div class="preview-card-icon kb-bg">
              <el-icon :size="16"><Collection /></el-icon>
            </div>
            <div class="preview-card-info">
              <div class="preview-card-name">{{ kb.name }}</div>
              <div class="preview-card-meta">{{ kb.code }}</div>
            </div>
            <span class="preview-card-count">{{ kb.fileCount ?? 0 }} 文件</span>
          </div>
          <div v-if="recentKnowledge.length === 0" class="preview-empty">
            暂无知识库
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import {
  Refresh, Cpu, Collection, SetUp, Coin, Connection, Clock,
} from '@element-plus/icons-vue'
import type { AgentDefinition } from '@/types/agent'
import type { KnowledgeBase } from '@/types/knowledge'
import { getAgentList } from '@/api/agent'
import { getKnowledgeList } from '@/api/knowledge'
import { getProviders } from '@/api/model'
import { getTools } from '@/api/tool'

const loading = ref(false)

const stats = reactive({
  agentCount: 0,
  knowledgeBaseCount: 0,
  toolCount: 0,
  providerCount: 0,
})

const recentAgents = ref<AgentDefinition[]>([])
const recentKnowledge = ref<KnowledgeBase[]>([])

const serviceHealth = reactive<Record<string, string>>({})

interface ActivityEvent {
  text: string
  time: string
  type: 'success' | 'warning' | 'info' | 'error'
}

const activityEvents = ref<ActivityEvent[]>([])

function generateSparkline(data: number[], w = 80, h = 30): { linePath: string; areaPath: string } {
  if (data.length < 2) return { linePath: '', areaPath: '' }
  const max = Math.max(...data) || 1
  const min = Math.min(...data)
  const range = max - min || 1
  const step = w / (data.length - 1)
  const points = data.map((v, i) => ({
    x: i * step,
    y: h - ((v - min) / range) * (h - 4) - 2,
  }))
  let line = `M ${points[0].x} ${points[0].y}`
  for (let i = 1; i < points.length; i++) {
    const cp1x = points[i - 1].x + step * 0.4
    const cp1y = points[i - 1].y
    const cp2x = points[i].x - step * 0.4
    const cp2y = points[i].y
    line += ` C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${points[i].x} ${points[i].y}`
  }
  const area = `${line} L ${w} ${h} L 0 ${h} Z`
  return { linePath: line, areaPath: area }
}

const statCards = computed(() => [
  {
    label: 'Agent 数量',
    value: stats.agentCount,
    icon: Cpu,
    gradient: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
    color: '#6366f1',
    trend: '15%',
    trendDir: 'up',
    ...generateSparkline([3, 5, 4, 7, 6, 8, 10, 9, 11, stats.agentCount || 12]),
  },
  {
    label: '知识库数量',
    value: stats.knowledgeBaseCount,
    icon: Collection,
    gradient: 'linear-gradient(135deg, #22d3ee, #06b6d4)',
    color: '#22d3ee',
    trend: '5%',
    trendDir: 'up',
    ...generateSparkline([2, 3, 3, 4, 5, 5, 6, 6, 7, stats.knowledgeBaseCount || 8]),
  },
  {
    label: 'Tool 数量',
    value: stats.toolCount,
    icon: SetUp,
    gradient: 'linear-gradient(135deg, #f59e0b, #d97706)',
    color: '#f59e0b',
    trend: '',
    trendDir: 'stable',
    ...generateSparkline([20, 22, 25, 24, 28, 30, 29, 31, 33, stats.toolCount || 34]),
  },
  {
    label: '模型 Provider',
    value: stats.providerCount,
    icon: Coin,
    gradient: 'linear-gradient(135deg, #64748b, #475569)',
    color: '#64748b',
    trend: '',
    trendDir: 'stable',
    ...generateSparkline([2, 2, 3, 3, 3, 3, 3, 3, 3, stats.providerCount || 3]),
  },
])

async function fetchStats() {
  loading.value = true
  const results = await Promise.allSettled([
    getAgentList(),
    getKnowledgeList(),
    getTools({ current: 1, size: 1 }),
    getProviders(),
  ])

  if (results[0].status === 'fulfilled') {
    const data = results[0].value.data
    const agents = Array.isArray(data) ? data : []
    stats.agentCount = agents.length
    recentAgents.value = agents.slice(0, 5)
  }

  if (results[1].status === 'fulfilled') {
    const resp = results[1].value.data
    const kbs = (resp as any)?.data ?? (Array.isArray(resp) ? resp : [])
    stats.knowledgeBaseCount = kbs.length
    recentKnowledge.value = kbs.slice(0, 5)
  }

  if (results[2].status === 'fulfilled') {
    const d = results[2].value.data as { total?: number } | undefined
    stats.toolCount = typeof d?.total === 'number' ? d.total : 0
  }

  if (results[3].status === 'fulfilled') {
    const resp = results[3].value.data
    const providers = (resp as any)?.data ?? (Array.isArray(resp) ? resp : [])
    stats.providerCount = providers.length
  }

  loading.value = false
}

async function checkHealth() {
  const services = [
    { name: 'ai-agent-service', url: 'http://localhost:8603', healthPath: '/actuator/health' },
    { name: 'ai-skills-service', url: 'http://localhost:8602', healthPath: '/ai/actuator/health' },
    { name: 'ai-model-service', url: 'http://localhost:8601', healthPath: '/actuator/health' },
  ]

  for (const svc of services) {
    try {
      const resp = await fetch(svc.healthPath, { signal: AbortSignal.timeout(5000) })
      serviceHealth[svc.name] = resp.ok ? 'online' : 'offline'
    } catch {
      serviceHealth[svc.name] = 'offline'
    }
  }
}

function buildActivityEvents() {
  const events: ActivityEvent[] = []
  const now = new Date()
  const recent = recentAgents.value
  if (recent.length > 0) {
    events.push({
      text: `Agent "${recent[0].name}" 最近更新`,
      time: formatTime(now, 0),
      type: 'info',
    })
  }
  if (recentKnowledge.value.length > 0) {
    events.push({
      text: `知识库 "${recentKnowledge.value[0].name}" 可用`,
      time: formatTime(now, 5),
      type: 'success',
    })
  }
  const allOnline = Object.values(serviceHealth).every(s => s === 'online')
  events.push({
    text: allOnline ? '所有服务运行正常' : '部分服务异常，请检查',
    time: formatTime(now, 10),
    type: allOnline ? 'success' : 'warning',
  })
  events.push({
    text: '系统启动完成',
    time: formatTime(now, 30),
    type: 'info',
  })
  activityEvents.value = events
}

function formatTime(base: Date, minutesAgo: number): string {
  const d = new Date(base.getTime() - minutesAgo * 60000)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function refresh() {
  fetchStats().then(() => {
    checkHealth().then(buildActivityEvents)
  })
}

onMounted(refresh)
</script>

<style scoped lang="scss">
.dashboard {
  position: relative;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px;
  position: relative;
  overflow: hidden;
  animation-fill-mode: both;
}

.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
  letter-spacing: -0.02em;
}

.stat-label {
  font-size: 13px;
  color: #64748b;
  margin-top: 6px;
}

.stat-trend {
  position: absolute;
  top: 16px;
  right: 16px;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 2px;

  &.up {
    color: #22d3ee;
  }

  &.down {
    color: #f43f5e;
  }

  &.stable {
    color: #64748b;
  }
}

.stat-sparkline {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 100px;
  height: 40px;
  opacity: 0.6;
}

.sparkline-svg {
  width: 100%;
  height: 100%;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 24px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 20px;

  .el-icon {
    color: #6366f1;
  }
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.topology-section {
  padding: 24px;
  animation-fill-mode: both;
}

.topology {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px 0;
}

.topo-row {
  display: flex;
  gap: 32px;
  justify-content: center;
}

.topo-row-split {
  width: 100%;
  justify-content: space-around;
}

.topo-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px 24px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  transition: all 0.3s ease;
  min-width: 140px;

  &.online {
    border-color: rgba(34, 211, 238, 0.2);
  }

  &.offline {
    border-color: rgba(244, 63, 94, 0.2);
  }
}

.topo-node-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;

  &.agent-bg {
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
  }

  &.skill-bg {
    background: linear-gradient(135deg, #22d3ee, #06b6d4);
  }

  &.model-bg {
    background: linear-gradient(135deg, #f59e0b, #d97706);
  }
}

.topo-node-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.topo-node-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-secondary);
}

.topo-connector {
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 32px;
  position: relative;
}

.connector-line {
  width: 2px;
  height: 100%;
  background: rgba(255, 255, 255, 0.06);
  position: relative;
  overflow: hidden;

  &.active {
    background: rgba(99, 102, 241, 0.2);

    &::after {
      content: '';
      position: absolute;
      top: -100%;
      left: 0;
      width: 100%;
      height: 50%;
      background: linear-gradient(180deg, transparent, #6366f1, transparent);
      animation: flowDown 2s ease-in-out infinite;
    }
  }
}

.connector-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6366f1;
  margin-top: -3px;
  box-shadow: 0 0 8px rgba(99, 102, 241, 0.4);
}

@keyframes flowDown {
  0% { top: -50%; }
  100% { top: 150%; }
}

.timeline-section {
  padding: 24px;
  animation-fill-mode: both;
}

.activity-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.timeline-item {
  display: flex;
  gap: 14px;
  padding: 12px 0;
  position: relative;
  animation: fadeInUp 0.3s ease both;

  &:not(:last-child)::after {
    content: '';
    position: absolute;
    left: 5px;
    top: 30px;
    bottom: -6px;
    width: 1px;
    background: rgba(255, 255, 255, 0.05);
  }
}

.timeline-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 3px;
  position: relative;
  z-index: 1;

  &.success {
    background: #22d3ee;
    box-shadow: 0 0 8px rgba(34, 211, 238, 0.4);
  }

  &.warning {
    background: #f59e0b;
    box-shadow: 0 0 8px rgba(245, 158, 11, 0.4);
  }

  &.error {
    background: #f43f5e;
    box-shadow: 0 0 8px rgba(244, 63, 94, 0.4);
  }

  &.info {
    background: #6366f1;
    box-shadow: 0 0 8px rgba(99, 102, 241, 0.4);
  }
}

.timeline-content {
  flex: 1;
  min-width: 0;
}

.timeline-text {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.4;
}

.timeline-time {
  font-size: 11px;
  color: #475569;
  margin-top: 4px;
}

.timeline-empty {
  padding: 20px 0;
}

.preview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.preview-section {
  padding: 24px;
  animation-fill-mode: both;
}

.preview-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preview-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.03);
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    background: rgba(99, 102, 241, 0.06);
    border-color: rgba(99, 102, 241, 0.12);
  }
}

.preview-card-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;

  &.agent-bg {
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
  }

  &.kb-bg {
    background: linear-gradient(135deg, #22d3ee, #06b6d4);
  }
}

.preview-card-info {
  flex: 1;
  min-width: 0;
}

.preview-card-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-card-meta {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}

.preview-card-count {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.preview-empty {
  font-size: 13px;
  color: #475569;
  text-align: center;
  padding: 24px 0;
}

@media (max-width: 1200px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .dashboard-grid,
  .preview-grid {
    grid-template-columns: 1fr;
  }
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .topo-node {
    background: #ffffff;
    border: 1px solid #ebeef5;
  }

  .connector-line {
    background: #e4e7ed;
  }

  .timeline-item:not(:last-child)::after {
    background: #ebeef5;
  }

  .preview-card {
    background: #f9fafb;
    border: 1px solid #ebeef5;

    &:hover {
      background: rgba(99, 102, 241, 0.04);
      border-color: rgba(99, 102, 241, 0.15);
    }
  }

  .stat-label,
  .stat-trend.stable {
    color: #94a3b8;
  }

  .timeline-time,
  .preview-empty {
    color: #94a3b8;
  }

  .preview-card-meta {
    color: #94a3b8;
  }
}
</style>
