<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>能力变更评审 / 同步调试台</h2>
        <p>SDK 上报先生成 snapshot 与字段级 diff，可在后端评审 API 中逐条 apply / ignore。</p>
      </div>
      <ProjectSelector />
    </div>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>CapabilitySyncRequest JSON</span>
              <el-button link type="primary" @click="fillExample">填充示例</el-button>
            </div>
          </template>
          <el-alert
            v-if="!selectedProjectCode"
            title="请先在顶部选择项目，或从项目详情页进入调试。"
            type="warning"
            show-icon
            :closable="false"
            class="mb-12"
          />
          <el-input v-model="jsonText" type="textarea" :rows="24" resize="vertical" />
          <div class="action-row">
            <el-button :loading="loading" :disabled="!selectedProjectCode" @click="run('diff')">Diff</el-button>
            <el-button :loading="loading" :disabled="!selectedProjectCode" @click="run('apply')">Apply</el-button>
            <el-button type="primary" :loading="loading" :disabled="!selectedProjectCode" @click="run('sync')">Sync</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>同步结果</template>
          <el-empty v-if="!result" description="暂无结果" />
          <template v-else>
            <el-row :gutter="12" class="stats">
              <el-col :span="5"><el-statistic title="Received" :value="result.received" /></el-col>
              <el-col :span="5"><el-statistic title="Added" :value="result.added" /></el-col>
              <el-col :span="5"><el-statistic title="Changed" :value="result.changed" /></el-col>
              <el-col :span="5"><el-statistic title="Unchanged" :value="result.unchanged" /></el-col>
              <el-col :span="4"><el-statistic title="Applied" :value="result.applied" /></el-col>
            </el-row>
            <el-descriptions :column="2" border class="mb-12">
              <el-descriptions-item label="Sync ID">{{ result.syncId }}</el-descriptions-item>
              <el-descriptions-item label="项目编码">{{ result.projectCode }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="result.items" row-key="qualifiedName" max-height="520">
              <el-table-column prop="qualifiedName" label="Qualified Name" min-width="220" show-overflow-tooltip />
              <el-table-column prop="storageName" label="Storage Name" min-width="180" show-overflow-tooltip />
              <el-table-column prop="changeType" label="变化" width="130">
                <template #default="{ row }">
                  <el-tag :type="changeType(row.changeType)">{{ row.changeType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="字段差异" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ formatFieldDiffs(row.fieldDiffs) }}
                </template>
              </el-table-column>
              <el-table-column label="影响分析" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ formatImpact(row.impact) }}
                </template>
              </el-table-column>
              <el-table-column prop="existingToolId" label="Existing Tool ID" width="160" />
            </el-table>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ProjectSelector from '@/components/ProjectSelector.vue'
import { applyRegistryCapabilities, diffRegistryCapabilities, syncRegistryCapabilities } from '@/api/registry'
import { useProjectStore } from '@/store/project'
import type { CapabilitySyncRequest, CapabilitySyncResponse } from '@/types/registry'

const projectStore = useProjectStore()
const loading = ref(false)
const result = ref<CapabilitySyncResponse | null>(null)
const jsonText = ref('')

const selectedProjectCode = computed(() => projectStore.currentProjectCode || '')

fillExample()

function fillExample() {
  const code = selectedProjectCode.value || 'demo-project'
  jsonText.value = JSON.stringify(
    {
      source: 'manual-debug',
      capabilities: [
        {
          name: 'queryOrder',
          title: '查询订单',
          description: '按订单号查询订单详情',
          httpMethod: 'GET',
          endpointPath: '/api/orders/{orderNo}',
          visibility: 'PROJECT',
          enabled: true,
          agentVisible: true,
          parameters: [
            {
              name: 'orderNo',
              type: 'string',
              description: '订单号',
              required: true,
              location: 'path',
            },
          ],
          metadata: { projectCode: code },
        },
      ],
    },
    null,
    2,
  )
}

async function run(mode: 'diff' | 'apply' | 'sync') {
  if (!selectedProjectCode.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  let payload: CapabilitySyncRequest
  try {
    payload = JSON.parse(jsonText.value)
  } catch {
    ElMessage.error('JSON 格式不正确')
    return
  }
  loading.value = true
  try {
    const api =
      mode === 'diff'
        ? diffRegistryCapabilities
        : mode === 'apply'
          ? applyRegistryCapabilities
          : syncRegistryCapabilities
    const { data } = await api(selectedProjectCode.value, payload)
    result.value = data
    ElMessage.success(`${mode.toUpperCase()} 完成`)
  } finally {
    loading.value = false
  }
}

function changeType(type: string) {
  if (type === 'ADDED') return 'success'
  if (type === 'CHANGED') return 'warning'
  if (type === 'DELETED') return 'danger'
  return 'info'
}

function formatFieldDiffs(diffs: CapabilitySyncResponse['items'][number]['fieldDiffs']) {
  if (!diffs?.length) return '-'
  return diffs.map((item) => item.field).join(', ')
}

function formatImpact(impact: CapabilitySyncResponse['items'][number]['impact']) {
  if (!impact) return '-'
  return Object.entries(impact)
    .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.length : value}`)
    .join(' | ')
}
</script>

<style scoped lang="scss">
.page-container {
  padding: 24px;
}

.page-header,
.card-header,
.action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header {
  margin-bottom: 16px;

  h2 {
    margin: 0 0 6px;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
  }
}

.action-row {
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.stats,
.mb-12 {
  margin-bottom: 12px;
}
</style>
