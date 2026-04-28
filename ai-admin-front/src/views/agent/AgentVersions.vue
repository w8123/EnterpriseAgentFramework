<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button @click="router.push('/agent')" :icon="ArrowLeft" text>返回</el-button>
        <h2>版本管理 — {{ agent?.name ?? agentId }}</h2>
        <el-tag v-if="agent?.keySlug" size="small" type="info">{{ agent.keySlug }}</el-tag>
      </div>
      <el-button type="primary" @click="loadVersions" :loading="loading">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="versions" v-loading="loading" stripe>
        <el-table-column prop="version" label="版本" width="140">
          <template #default="{ row }">
            <span class="version-cell">{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rolloutPercent" label="灰度比例" width="130">
          <template #default="{ row }">
            <el-progress
              :percentage="row.rolloutPercent"
              :stroke-width="10"
              :status="row.status === 'ACTIVE' ? 'success' : undefined"
            />
          </template>
        </el-table-column>
        <el-table-column prop="publishedBy" label="发布者" width="140" />
        <el-table-column prop="publishedAt" label="发布时间" width="180" />
        <el-table-column prop="note" label="发布说明" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="showSnapshot(row)"
            >查看快照</el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="showDiff(row)"
            >Diff</el-button>
            <el-popconfirm
              :title="`确认回滚到 ${row.version}？其它 ACTIVE 版本会被置为 RETIRED`"
              @confirm="handleRollback(row)"
              :disabled="row.status === 'ACTIVE' && row.rolloutPercent === 100"
            >
              <template #reference>
                <el-button
                  link
                  type="warning"
                  size="small"
                  :disabled="row.status === 'ACTIVE' && row.rolloutPercent === 100"
                >回滚</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="snapshotOpen" :title="snapshotTitle" width="720px">
      <pre class="snapshot-pre">{{ prettySnapshot }}</pre>
    </el-dialog>

    <el-dialog v-model="diffOpen" :title="diffTitle" width="820px">
      <el-table :data="snapshotDiffRows" border size="small">
        <el-table-column prop="field" label="字段" width="160" />
        <el-table-column prop="current" label="当前版本" min-width="300">
          <template #default="{ row }">
            <pre class="diff-pre">{{ row.current }}</pre>
          </template>
        </el-table-column>
        <el-table-column prop="previous" label="对比版本" min-width="300">
          <template #default="{ row }">
            <pre class="diff-pre">{{ row.previous }}</pre>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!snapshotDiffRows.length" description="核心字段无差异或没有可对比版本" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import type { AgentDefinition, AgentVersion } from '@/types/agent'
import { getAgent, listAgentVersions, rollbackAgentVersion } from '@/api/agent'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string

const loading = ref(false)
const versions = ref<AgentVersion[]>([])
const agent = ref<AgentDefinition | null>(null)

const snapshotOpen = ref(false)
const snapshotRow = ref<AgentVersion | null>(null)
const diffOpen = ref(false)
const diffRow = ref<AgentVersion | null>(null)
const snapshotTitle = computed(() => snapshotRow.value
  ? `快照 — ${snapshotRow.value.version}`
  : '快照')
const prettySnapshot = computed(() => {
  if (!snapshotRow.value) return ''
  try {
    return JSON.stringify(JSON.parse(snapshotRow.value.snapshotJson), null, 2)
  } catch {
    return snapshotRow.value.snapshotJson
  }
})
const diffTitle = computed(() => diffRow.value ? `版本 Diff — ${diffRow.value.version}` : '版本 Diff')
const snapshotDiffRows = computed(() => {
  if (!diffRow.value) return []
  const previous = findPreviousVersion(diffRow.value)
  if (!previous) return []
  const currentJson = parseSnapshot(diffRow.value.snapshotJson)
  const previousJson = parseSnapshot(previous.snapshotJson)
  const fields = ['name', 'intentType', 'systemPrompt', 'tools', 'skills', 'maxSteps', 'allowIrreversible', 'canvasJson']
  return fields
    .map((field) => ({
      field,
      current: stringifyValue(currentJson?.[field]),
      previous: stringifyValue(previousJson?.[field]),
    }))
    .filter((row) => row.current !== row.previous)
})

function statusTagType(status: string): '' | 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'RETIRED': return 'info'
    case 'DRAFT': return 'warning'
    default: return ''
  }
}

async function loadAgent() {
  try {
    const { data } = await getAgent(agentId)
    agent.value = data
  } catch {
    ElMessage.error('加载 Agent 失败')
  }
}

async function loadVersions() {
  loading.value = true
  try {
    const { data } = await listAgentVersions(agentId)
    versions.value = Array.isArray(data) ? data : []
  } catch {
    versions.value = []
    ElMessage.error('加载版本列表失败')
  } finally {
    loading.value = false
  }
}

async function handleRollback(row: AgentVersion) {
  try {
    await rollbackAgentVersion(agentId, row.id, 'admin')
    ElMessage.success(`已回滚到 ${row.version}`)
    await loadVersions()
  } catch (err) {
    ElMessage.error('回滚失败：' + (err as Error).message)
  }
}

function showSnapshot(row: AgentVersion) {
  snapshotRow.value = row
  snapshotOpen.value = true
}

function showDiff(row: AgentVersion) {
  diffRow.value = row
  diffOpen.value = true
}

function findPreviousVersion(row: AgentVersion) {
  const idx = versions.value.findIndex((v) => v.id === row.id)
  if (idx < 0) return null
  return versions.value[idx + 1] ?? null
}

function parseSnapshot(raw: string): Record<string, unknown> | null {
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

function stringifyValue(value: unknown) {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

onMounted(() => {
  loadAgent()
  loadVersions()
})
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;

  h2 {
    margin: 0;
    font-size: 18px;
  }
}

.version-cell {
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-weight: 500;
}

.snapshot-pre {
  background: #f8fafc;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  max-height: 480px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.diff-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
}
</style>
