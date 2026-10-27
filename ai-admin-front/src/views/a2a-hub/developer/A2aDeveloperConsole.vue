<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, type TagProps } from 'element-plus'
import { ArrowRight, CopyDocument, Refresh } from '@element-plus/icons-vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassNotice, LiquidGlassSelect } from '@/components/common/liquid-glass'
import AgentCardPreview from '@/components/a2a-hub/AgentCardPreview.vue'
import { getA2aPublication, listA2aPublications } from '@/api/a2aHub'
import type { A2aPublication, A2aPublicationDetail } from '@/types/a2aHub'

const loading = ref(false)
const detailLoading = ref(false)
const loadError = ref('')
const publications = ref<A2aPublication[]>([])
const selectedId = ref<number | null>(null)
const detail = ref<A2aPublicationDetail | null>(null)
let listRequest = 0
let detailRequest = 0

const selectedRevision = computed(() => {
  if (detail.value?.publication.id !== selectedId.value || detail.value?.publication.status !== 'PUBLISHED') return null
  return detail.value.revisions.find((item) => item.id === detail.value?.publication.currentRevisionId) ?? null
})
const publicOrigin = computed(() => selectedRevision.value?.publicOrigin.replace(/\/+$/, '') ?? '')
const cardUrl = computed(() => publicOrigin.value ? `${publicOrigin.value}/.well-known/agent-card.json` : '')
const sendUrl = computed(() => publicOrigin.value ? `${publicOrigin.value}/a2a/v1/message:send` : '')
const endpoints = computed(() => [
  { label: 'Agent Card', url: cardUrl.value },
  { label: '发送消息', url: sendUrl.value },
  { label: '查询任务', url: `${publicOrigin.value}/a2a/v1/tasks/{taskId}` },
])
const curlSample = computed(() => !sendUrl.value ? '' : `curl --request POST '${sendUrl.value}' \\
  --header 'A2A-Version: 1.0' \\
  --header 'Content-Type: application/a2a+json' \\
  --header "Authorization: Bearer \$A2A_API_KEY" \\
  --data '{
    "message": {
      "messageId": "msg-client-generated-001",
      "role": "ROLE_USER",
      "parts": [{ "text": "请处理这个任务" }]
    },
    "configuration": { "returnImmediately": true }
  }'`)

const readiness = computed<Array<{ item: string; status: string; tone: TagProps['type'] }>>(() => {
  const conformance = selectedRevision.value?.conformanceStatus
  const conformanceLabels: Record<string, string> = { PASSED: '已通过', FAILED: '未通过', NOT_RUN: '未执行', QUEUED: '排队中', RUNNING: '执行中', ERROR: '执行出错' }
  return [
    { item: 'A2A 1.0 Agent Card', status: '已生成', tone: 'info' },
    { item: 'HTTP+JSON 消息与任务接口', status: '已实现', tone: 'info' },
    { item: 'Streaming / Subscribe / Push / Extended Card', status: '暂不支持', tone: 'info' },
    { item: '官方 TCK MUST', status: conformance ? conformanceLabels[conformance] ?? conformance : '未提供', tone: conformance === 'PASSED' ? 'success' : conformance === 'FAILED' || conformance === 'ERROR' ? 'danger' : 'info' },
    { item: '双系统端到端验证', status: '未查询', tone: 'info' },
  ]
})

async function reload() {
  const request = ++listRequest
  ++detailRequest
  loading.value = true
  detailLoading.value = false
  detail.value = null
  loadError.value = ''
  try {
    const { data } = await listA2aPublications({ status: 'PUBLISHED', limit: 100, offset: 0 })
    if (request !== listRequest) return
    publications.value = data?.items ?? []
    selectedId.value = publications.value.find((item) => item.id === selectedId.value)?.id
      ?? publications.value[0]?.id ?? null
    if (selectedId.value !== null) await loadDetail(selectedId.value)
  } catch {
    if (request === listRequest) loadError.value = '发布记录加载失败，请重试。'
  } finally {
    if (request === listRequest) loading.value = false
  }
}

async function loadDetail(id: number) {
  const request = ++detailRequest
  selectedId.value = id
  detail.value = null
  detailLoading.value = true
  loadError.value = ''
  try {
    const { data } = await getA2aPublication(id)
    if (request === detailRequest) detail.value = data
  } catch {
    if (request === detailRequest) loadError.value = '接入信息加载失败，请重试。'
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

async function copy(value: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

onMounted(reload)
</script>

<template>
  <section class="developer-console">
    <div class="developer-toolbar">
      <div v-if="publications.length" class="developer-target">
        <label for="a2a-developer-target">对外服务</label>
        <LiquidGlassSelect id="a2a-developer-target" source="wallpaper" class="picker-select" :model-value="selectedId" :disabled="loading" filterable placeholder="选择对外服务" dropdown-title="对外服务" @change="loadDetail">
          <el-option v-for="item in publications" :key="item.id" :label="`${item.publicationKey} · ${item.publicHost}`" :value="item.id" />
        </LiquidGlassSelect>
      </div>
      <LiquidGlassButton circle source="wallpaper" :icon="Refresh" :loading="loading || detailLoading" aria-label="刷新" title="刷新" class="refresh-button" @click="reload" />
    </div>

    <LiquidGlassNotice v-if="loadError" source="wallpaper" type="error" :title="loadError" />
    <div v-else-if="loading || detailLoading" v-loading="true" class="developer-pending" role="status" aria-label="正在加载接入信息" />
    <LiquidGlassEmptyState v-else-if="!publications.length" source="wallpaper" title="还没有已发布的服务" description="在「对外发布」完成发布后，即可查看接入信息。" />
    <LiquidGlassEmptyState v-else-if="!selectedRevision" source="wallpaper" title="暂无可用的已发布修订" description="请到「对外发布」检查当前服务的发布状态。" />

    <template v-else>
      <WorkbenchPanel class="glass-workspace-adaptive" title="接入信息">
        <template #actions><el-tag type="info" size="small" effect="plain">修订 #{{ selectedRevision.revisionNo }}</el-tag></template>
        <div class="endpoint-list">
          <div v-for="endpoint in endpoints" :key="endpoint.label">
            <span>{{ endpoint.label }}</span>
            <code :title="endpoint.url">{{ endpoint.url }}</code>
            <el-button class="developer-copy" link type="primary" :icon="CopyDocument" :aria-label="`复制${endpoint.label}地址`" @click="copy(endpoint.url)">复制</el-button>
          </div>
        </div>
        <div class="protocol-meta"><code>A2A-Version: 1.0</code><code>Content-Type: application/a2a+json</code></div>
      </WorkbenchPanel>

      <WorkbenchPanel class="glass-workspace-adaptive">
        <div class="developer-disclosures">
          <details>
            <summary><span>curl 调用示例</span><el-icon aria-hidden="true"><ArrowRight /></el-icon></summary>
            <div class="disclosure-content">
              <div class="code-heading"><p>通过 A2A_API_KEY 环境变量提供凭据，并为请求生成稳定的 messageId。</p><el-button class="developer-copy" link type="primary" :icon="CopyDocument" @click="copy(curlSample)">复制示例</el-button></div>
              <pre class="code-block">{{ curlSample }}</pre>
            </div>
          </details>
          <details>
            <summary><span>协议支持与验证</span><el-icon aria-hidden="true"><ArrowRight /></el-icon></summary>
            <div class="disclosure-content readiness-list">
              <div v-for="item in readiness" :key="item.item"><span>{{ item.item }}</span><el-tag :type="item.tone" size="small" effect="plain">{{ item.status }}</el-tag></div>
            </div>
          </details>
          <details>
            <summary><span>Agent Card 快照</span><el-icon aria-hidden="true"><ArrowRight /></el-icon></summary>
            <div class="disclosure-content"><AgentCardPreview :revision="selectedRevision" /></div>
          </details>
        </div>
      </WorkbenchPanel>
    </template>
  </section>
</template>

<style scoped lang="scss">
.developer-console { display: flex; flex-direction: column; gap: var(--layout-page-gap); min-width: 0; }
.developer-toolbar { display: flex; align-items: center; gap: var(--layout-control-gap); min-width: 0; }
.developer-target { display: flex; flex: 0 1 640px; align-items: center; gap: var(--layout-control-gap); min-width: 0; }
.developer-toolbar label { flex-shrink: 0; color: var(--text-on-wallpaper); font-size: 13px; font-weight: 650; }
.picker-select { flex: 1; min-width: 0; }
.refresh-button { margin-left: auto; flex-shrink: 0; }
.developer-pending { min-height: 180px; }
.developer-console :deep(.developer-copy) { --el-button-hover-text-color: var(--text-link); color: var(--text-link); }
.endpoint-list { display: grid; }
.endpoint-list > div { display: grid; grid-template-columns: 100px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border-divider); }
.endpoint-list span { color: var(--text-secondary); font-size: 13px; }
.endpoint-list code { overflow: hidden; color: var(--text-primary); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.protocol-meta { display: flex; flex-wrap: wrap; gap: 8px 24px; margin-top: 14px; color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
.developer-disclosures details + details { border-top: 1px solid var(--border-divider); }
.developer-disclosures summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 0; color: var(--text-primary); font-size: 14px; font-weight: 650; cursor: pointer; list-style: none; }
.developer-disclosures summary::-webkit-details-marker { display: none; }
.developer-disclosures summary:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; border-radius: var(--radius-sm); }
.developer-disclosures details[open] > summary .el-icon { transform: rotate(90deg); }
.disclosure-content { padding: 0 0 16px; }
.readiness-list > div { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; color: var(--text-secondary); font-size: 13px; }
.readiness-list .el-tag { flex-shrink: 0; }
.code-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 10px; color: var(--text-secondary); font-size: 12px; }
.code-heading p { flex: 1 1 280px; margin: 0; line-height: 1.6; }
.code-block { max-height: 420px; margin: 0; overflow: auto; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--code-text); background: var(--code-surface); font: 12px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 620px) {
  .developer-toolbar { flex-wrap: wrap; }
  .developer-target { flex: 1 1 220px; }
  .endpoint-list > div { grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
  .endpoint-list span { grid-column: 1 / -1; }
}
</style>
