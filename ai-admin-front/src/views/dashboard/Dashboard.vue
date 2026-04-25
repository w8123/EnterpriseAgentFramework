<template>
  <div class="page-container">
    <div class="page-header">
      <h2>概览</h2>
      <el-button type="primary" @click="refresh" :loading="loading">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon agent-icon"><el-icon :size="28"><Cpu /></el-icon></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.agentCount }}</div>
            <div class="stat-label">Agent 数量</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon kb-icon"><el-icon :size="28"><Collection /></el-icon></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.knowledgeBaseCount }}</div>
            <div class="stat-label">知识库数量</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon tool-icon"><el-icon :size="28"><SetUp /></el-icon></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.toolCount }}</div>
            <div class="stat-label">Tool 数量</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon model-icon"><el-icon :size="28"><Coin /></el-icon></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.providerCount }}</div>
            <div class="stat-label">模型 Provider</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 服务状态 -->
    <el-card shadow="never" class="section-card">
      <template #header>服务状态</template>
      <el-table :data="serviceStatus" v-loading="statusLoading">
        <el-table-column prop="name" label="服务" width="200" />
        <el-table-column prop="url" label="地址" width="250" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.healthy ? 'success' : 'danger'" size="small">
              {{ row.healthy ? '正常' : '异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="详情" />
      </el-table>
    </el-card>

    <!-- Agent 列表快览 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-with-action">
              <span>最近 Agent</span>
              <el-button text type="primary" @click="$router.push('/agent')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="recentAgents" size="small">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="intentType" label="意图类型" width="130">
              <template #default="{ row }">
                <el-tag size="small">{{ row.intentType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="enabled" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-with-action">
              <span>最近知识库</span>
              <el-button text type="primary" @click="$router.push('/knowledge')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="recentKnowledge" size="small">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="code" label="编码" width="140" />
            <el-table-column prop="fileCount" label="文件数" width="80" align="center" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Refresh, Cpu, Collection, SetUp, Coin } from '@element-plus/icons-vue'
import type { AgentDefinition } from '@/types/agent'
import type { KnowledgeBase } from '@/types/knowledge'
import { getAgentList } from '@/api/agent'
import { getKnowledgeList } from '@/api/knowledge'
import { getProviders } from '@/api/model'
import { getTools } from '@/api/tool'

const loading = ref(false)
const statusLoading = ref(false)

const stats = reactive({
  agentCount: 0,
  knowledgeBaseCount: 0,
  toolCount: 0,
  providerCount: 0,
})

const recentAgents = ref<AgentDefinition[]>([])
const recentKnowledge = ref<KnowledgeBase[]>([])

interface ServiceHealthRow {
  name: string
  url: string
  healthy: boolean
  message: string
}

const serviceStatus = ref<ServiceHealthRow[]>([])

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
  statusLoading.value = true
  const services = [
    { name: 'ai-agent-service', url: 'http://localhost:8603', healthPath: '/actuator/health' },
    { name: 'ai-skills-service', url: 'http://localhost:8602', healthPath: '/ai/actuator/health' },
    { name: 'ai-model-service', url: 'http://localhost:8601', healthPath: '/actuator/health' },
  ]

  const checks: ServiceHealthRow[] = []
  for (const svc of services) {
    try {
      const resp = await fetch(svc.healthPath, { signal: AbortSignal.timeout(5000) })
      checks.push({
        name: svc.name,
        url: svc.url,
        healthy: resp.ok,
        message: resp.ok ? 'UP' : `HTTP ${resp.status}`,
      })
    } catch {
      checks.push({
        name: svc.name,
        url: svc.url,
        healthy: false,
        message: '无法连接',
      })
    }
  }

  serviceStatus.value = checks
  statusLoading.value = false
}

function refresh() {
  fetchStats()
  checkHealth()
}

onMounted(refresh)
</script>

<style scoped lang="scss">
.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  :deep(.el-card__body) {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 20px;
  }
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;

  &.agent-icon { background: linear-gradient(135deg, #409eff, #337ecc); }
  &.kb-icon { background: linear-gradient(135deg, #67c23a, #529b2e); }
  &.tool-icon { background: linear-gradient(135deg, #e6a23c, #cf9236); }
  &.model-icon { background: linear-gradient(135deg, #909399, #73767a); }
}

.stat-info {
  .stat-value {
    font-size: 28px;
    font-weight: 700;
    color: #1d2129;
    line-height: 1;
  }

  .stat-label {
    font-size: 13px;
    color: #909399;
    margin-top: 6px;
  }
}

.section-card {
  margin-bottom: 16px;
}

.card-header-with-action {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
