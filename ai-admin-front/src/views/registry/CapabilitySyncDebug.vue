<template>
  <WorkbenchPage class="capability-change-page" layout="list">
    <PageHeader v-if="!isDiagnosticsRoute" variant="standard" title="能力变化"
      description="查看当前需要确认的变化，追溯每一次处理。" />

    <PageHeader
      v-else
      variant="standard"
      domain="project"
      eyebrow="SDK Snapshot Diagnostics"
      title="同步诊断"
      description="验证 SDK 上报格式与字段差异，诊断数据独立保存。"
    >
      <template #tags>
        <el-tag type="warning" effect="light">开发者诊断</el-tag>
        <el-tag v-if="selectedProjectCode" effect="light">{{ selectedProjectCode }}</el-tag>
      </template>
      <template #actions>
        <el-button :icon="Back" @click="router.push('/capability/review')">返回能力变化</el-button>
      </template>
    </PageHeader>

    <section :class="{ 'capability-change-workspace glass-surface-panel': !isDiagnosticsRoute }">
      <section class="scope-selector" aria-label="选择项目">
        <label for="capability-change-project" class="scope-label"><el-icon aria-hidden="true"><FolderOpened /></el-icon>项目</label>
        <el-select :model-value="projectStore.currentProjectId" filterable clearable :loading="projectStore.loading"
          id="capability-change-project" placeholder="选择项目" aria-label="选择项目" @change="selectProject">
          <el-option v-for="project in projectStore.projects" :key="project.id" :value="project.id" :label="projectStore.projectLabel(project)" />
        </el-select>
        <el-button v-if="!projectStore.loading && !projectStore.projects.length" link type="primary" @click="projectStore.fetchProjects()">重新加载项目</el-button>
        <el-button v-if="!isDiagnosticsRoute" link :icon="Operation" @click="router.push('/capability/sync-snapshot')">开发者诊断</el-button>
      </section>
      <CapabilityReviewPanel v-if="!isDiagnosticsRoute" :project-code="selectedProjectCode" />
    </section>

    <template v-if="isDiagnosticsRoute">
      <el-alert
        title="诊断不会改变能力目录或真实来源状态"
        description="在这里验证请求，不会产生用户待办。能力接纳由业务系统的可信 SDK 同步触发，系统按策略自动处理。"
        type="warning"
        :closable="false"
        show-icon
      />

      <section class="sync-debug-grid">
        <article class="debug-panel glass-surface-panel">
          <header class="debug-panel__header">
            <div>
              <span>请求</span>
              <h2>CapabilitySyncRequest JSON</h2>
            </div>
            <el-button link type="primary" @click="fillExample">恢复安全示例</el-button>
          </header>

          <el-alert
            v-if="!selectedProjectCode"
            title="请先选择项目"
            type="warning"
            show-icon
            :closable="false"
            class="debug-inline-alert"
          />

          <el-input
            v-model="jsonText"
            type="textarea"
            :rows="24"
            resize="none"
            class="debug-json-input"
            aria-label="CapabilitySyncRequest JSON"
          />

          <footer class="debug-actions">
            <el-button :loading="loading" :disabled="!selectedProjectCode" @click="run()">
              验证请求
            </el-button>

          </footer>
        </article>

        <article class="debug-panel glass-surface-panel">
          <header class="debug-panel__header">
            <div>
              <span>响应</span>
              <h2>同步结果</h2>
            </div>
            <StatusTag v-if="result" :label="result.applied ? '已改变目录' : '仅生成快照'" :tone="result.applied ? 'warning' : 'info'" />
          </header>

          <div v-if="!result" class="debug-empty">
            <el-empty description="执行后在这里查看快照结果" :image-size="92" />
          </div>

          <template v-else>
            <MetricStrip :items="syncMetricItems" density="compact" aria-label="同步结果指标" />
            <dl class="sync-facts">
              <div><dt>Sync ID</dt><dd>{{ result.syncId }}</dd></div>
              <div><dt>项目编码</dt><dd>{{ result.projectCode }}</dd></div>
            </dl>

            <el-table :data="result.items" row-key="qualifiedName" max-height="460" class="sync-result-table">
              <el-table-column label="能力" min-width="210">
                <template #default="{ row }">
                  <div class="result-capability-cell">
                    <strong>{{ row.name }}</strong>
                    <small>{{ row.qualifiedName }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="变化" width="110">
                <template #default="{ row }">
                  <StatusTag :label="syncChangeLabel(row.changeType)" :tone="syncChangeTone(row.changeType)" />
                </template>
              </el-table-column>
              <el-table-column label="字段差异" min-width="170" show-overflow-tooltip>
                <template #default="{ row }">{{ formatFieldDiffs(row.fieldDiffs) }}</template>
              </el-table-column>
              <el-table-column label="影响证据" min-width="190">
                <template #default>本地目录可判断，跨服务引用未汇总</template>
              </el-table-column>
            </el-table>
          </template>
        </article>
      </section>
    </template>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, FolderOpened, Operation } from '@element-plus/icons-vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem, StatusTone } from '@/components/common/glassWorkbench'
import { diffRegistryCapabilities } from '@/api/registry'
import { useProjectStore } from '@/store/project'
import CapabilityReviewPanel from '@/views/registry/components/CapabilityReviewPanel.vue'
import type { CapabilitySyncRequest, CapabilitySyncResponse } from '@/types/registry'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const loading = ref(false)
const result = ref<CapabilitySyncResponse | null>(null)
const jsonText = ref('')
const isDiagnosticsRoute = computed(() => route.name === 'CapabilitySyncSnapshot')
const selectedProjectCode = computed(() => projectStore.currentProjectCode || '')
function selectProject(value: number | undefined) {
  projectStore.selectCurrentProject(value || null)
}

const syncMetricItems = computed<MetricStripItem[]>(() => [
  { key: 'received', label: '收到能力', value: result.value?.received || 0, tone: 'brand', iconKey: 'api' },
  { key: 'added', label: '新增', value: result.value?.added || 0, tone: 'success', iconKey: 'plus' },
  { key: 'changed', label: '字段变化', value: result.value?.changed || 0, tone: 'warning', iconKey: 'diff' },
  { key: 'applied', label: '已应用', value: result.value?.applied || 0, tone: result.value?.applied ? 'warning' : 'info', iconKey: 'check' },
])

onMounted(async () => {
  fillExample()
  if (!projectStore.projects.length) await projectStore.fetchProjects()
  const requested = projectStore.projects.find(project => project.projectCode === route.query.projectCode)
  if (requested) projectStore.selectCurrentProject(requested.id)

})

watch(selectedProjectCode, (code) => {
  result.value = null
  if (route.query.projectCode !== code) void router.replace({ query: { ...route.query, projectCode: code || undefined, changePage: undefined } })
  fillExample()
})

watch(() => route.query.projectCode, (code) => {
  if (typeof code !== 'string' || code === selectedProjectCode.value || projectStore.loading) return
  const project = projectStore.projects.find(project => project.projectCode === code)
  projectStore.selectCurrentProject(project?.id || null)
})

watch(isDiagnosticsRoute, () => {
  result.value = null
  fillExample()
})

function fillExample() {
  const code = selectedProjectCode.value || 'demo-project'
  jsonText.value = JSON.stringify(
    {
      source: 'manual-debug',
      apply: false,
      capabilities: [
        {
          name: 'queryOrder',
          title: '查询订单',
          description: '按订单号查询订单详情',
          httpMethod: 'GET',
          endpointPath: '/api/orders/{orderNo}',
          sideEffect: 'READ_ONLY',
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

async function run() {
  if (!selectedProjectCode.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  let payload: CapabilitySyncRequest
  try {
    const parsed = JSON.parse(jsonText.value)
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.capabilities)) throw new Error('invalid payload')
    payload = parsed as CapabilitySyncRequest
  } catch {
    ElMessage.error('请求必须是包含 capabilities 数组的 JSON 对象')
    return
  }

  payload = { ...payload, apply: false }

  loading.value = true
  try {
    const project = selectedProjectCode.value
    const { data } = await diffRegistryCapabilities(project, payload)
    if (selectedProjectCode.value !== project) return
    result.value = data
    ElMessage.success('诊断完成，目录未改变')
  } catch {
    ElMessage.error('诊断未完成，请检查请求内容和项目权限后重试')
  } finally {
    loading.value = false
  }
}

function formatFieldDiffs(diffs: CapabilitySyncResponse['items'][number]['fieldDiffs']) {
  if (!diffs?.length) return '无字段变化'
  return diffs.map(item => item.field).join('、')
}

function syncChangeLabel(type: CapabilitySyncResponse['items'][number]['changeType']) {
  return { ADDED: '新增', CHANGED: '字段变更', DELETED: '停止上报', UNCHANGED: '无变化' }[type]
}

function syncChangeTone(type: CapabilitySyncResponse['items'][number]['changeType']): StatusTone {
  if (type === 'ADDED') return 'success'
  if (type === 'CHANGED') return 'warning'
  if (type === 'DELETED') return 'danger'
  return 'neutral'
}
</script>

<style scoped lang="scss">
.capability-change-workspace { min-width: 0; border-radius: var(--radius-lg); }
.scope-selector { display: flex; min-width: 0; align-items: center; gap: 12px; flex-wrap: wrap; }
.capability-change-workspace > .scope-selector { padding: 16px 24px; border-bottom: 1px solid var(--border-divider); }
.scope-label { display: inline-flex; align-items: center; gap: 8px; color: var(--text-secondary); font-size: 13px; flex-shrink: 0; }
.scope-label .el-icon { color: var(--text-muted); font-size: 17px; }
.scope-selector .el-select { width: min(360px, 100%); min-width: 0; }
.scope-selector > .el-button:last-child { margin-left: auto; color: var(--text-secondary); font-size: 12px; }
.scope-selector > .el-button:last-child:hover { color: var(--brand-primary); }
@media (max-width: 900px) {
  .capability-change-workspace > .scope-selector { padding: 16px; }
  .scope-selector .el-select { flex: 1; width: auto; min-width: 180px; }
}

.sync-debug-grid {
  display: grid;
  grid-template-columns: minmax(390px, 0.9fr) minmax(0, 1.1fr);
  gap: var(--section-gap);
  min-width: 0;
}

.debug-panel {
  min-width: 0;
  overflow: hidden;
  border-radius: var(--radius-lg);
}

.debug-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px var(--panel-padding);
  border-bottom: 1px solid var(--border-divider);
}

.debug-panel__header span { color: var(--brand-primary); font-size: 10px; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase; }
.debug-panel__header h2 { margin: 3px 0 0; color: var(--text-primary); font-size: 15px; }
.debug-inline-alert { margin: 12px var(--panel-padding) 0; }
.debug-json-input { display: block; padding: var(--panel-padding); }
.debug-json-input :deep(textarea) { font: 12px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; }

.debug-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  padding: 0 var(--panel-padding) var(--panel-padding);
}

.debug-empty { display: grid; min-height: 520px; place-items: center; }
.debug-panel :deep(.metric-strip) { margin: var(--panel-padding); }

.sync-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 0 var(--panel-padding) var(--panel-padding);
}

.sync-facts > div { min-width: 0; padding: 9px 10px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.sync-facts dt,
.sync-facts dd { margin: 0; }
.sync-facts dt { color: var(--text-muted); font-size: 10px; }
.sync-facts dd { overflow: hidden; margin-top: 4px; color: var(--text-primary); font: 11px/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }

.sync-result-table { width: calc(100% - (var(--panel-padding) * 2)); margin: 0 var(--panel-padding) var(--panel-padding); }
.result-capability-cell { display: grid; min-width: 0; gap: 3px; }
.result-capability-cell strong,
.result-capability-cell small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-capability-cell strong { color: var(--text-primary); font-size: 12px; }
.result-capability-cell small { color: var(--text-muted); font: 10px/1.4 ui-monospace, SFMono-Regular, Consolas, monospace; }

@media (max-width: 1080px) {
  .sync-debug-grid { grid-template-columns: 1fr; }
  .debug-empty { min-height: 300px; }
}

@media (max-width: 640px) {
  .project-scope { grid-template-columns: auto minmax(0, 1fr); }
  .project-scope > .el-button { grid-column: 1 / -1; justify-self: stretch; }
  .sync-facts { grid-template-columns: 1fr; }
}
</style>
