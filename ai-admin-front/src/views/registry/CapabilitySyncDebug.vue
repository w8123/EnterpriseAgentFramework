<template>
  <WorkbenchPage class="capability-sync-debug-page">
    <PageHeader
      variant="standard"
      domain="project"
      eyebrow="Capability Sync Review"
      title="能力变更评审 / 同步调试台"
      description="集中查看项目能力快照、处理字段差异，并在需要时使用原始请求调试 SDK 同步链路。"
    />

    <CapabilityReviewPanel
      v-if="selectedProjectCode"
      ref="reviewPanelRef"
      :project-code="selectedProjectCode"
      class="capability-review-section"
    />

    <section class="sync-debug-section">
      <div class="sync-debug-heading">
        <div>
          <h2>SDK 同步调试</h2>
          <p>直接构造 CapabilitySyncRequest；Diff 仅生成差异，Apply / Sync 可能更新当前项目的能力目录。</p>
        </div>
        <el-tag type="warning" effect="plain">调试工具</el-tag>
      </div>

      <section class="capability-sync-grid">
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
                title="请先在左上角的“当前项目”中选择项目。"
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
                  <el-table-column prop="qualifiedName" label="全限定名" min-width="220" show-overflow-tooltip />
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
                  <el-table-column prop="existingToolId" label="现有执行定义 ID" width="160" />
                </el-table>
              </template>
            </el-card>
          </el-col>
        </el-row>
      </section>
    </section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { applyRegistryCapabilities, diffRegistryCapabilities, syncRegistryCapabilities } from '@/api/registry'
import { useProjectStore } from '@/store/project'
import CapabilityReviewPanel from '@/views/registry/components/CapabilityReviewPanel.vue'
import type { CapabilitySyncRequest, CapabilitySyncResponse } from '@/types/registry'

const projectStore = useProjectStore()
const loading = ref(false)
const result = ref<CapabilitySyncResponse | null>(null)
const jsonText = ref('')
const reviewPanelRef = ref<InstanceType<typeof CapabilityReviewPanel>>()

const selectedProjectCode = computed(() => projectStore.currentProjectCode || '')

fillExample()

function fillExample() {
  const code = selectedProjectCode.value || 'demo-project'
  jsonText.value = JSON.stringify(
    {
      source: 'manual-debug',
      apply: true,
      capabilities: [
        {
          name: 'queryOrder',
          title: '查询订单',
          description: '按订单号查询订单详情',
          httpMethod: 'GET',
          endpointPath: '/api/orders/{orderNo}',
          enabled: true,
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

watch(selectedProjectCode, () => {
  result.value = null
  fillExample()
})

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
    await reviewPanelRef.value?.loadSnapshots()
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
.capability-review-section,
.sync-debug-section {
  margin-top: 16px;
}

.sync-debug-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.sync-debug-heading h2,
.sync-debug-heading p {
  margin: 0;
}

.sync-debug-heading h2 {
  color: var(--el-text-color-primary);
  font-size: 16px;
  line-height: 24px;
}

.sync-debug-heading p {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.card-header,
.action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
