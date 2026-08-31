<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Refresh } from '@element-plus/icons-vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import AgentCardPreview from '@/components/a2a-hub/AgentCardPreview.vue'
import { getA2aPublication, listA2aPublications } from '@/api/a2aHub'
import type { A2aPublication, A2aPublicationDetail, A2aPublicationRevision } from '@/types/a2aHub'

const loading = ref(false)
const publications = ref<A2aPublication[]>([])
const selectedId = ref<number | null>(null)
const detail = ref<A2aPublicationDetail | null>(null)
const selectedRevision = computed<A2aPublicationRevision | null>(() => {
  const current = detail.value?.publication.currentRevisionId
  return detail.value?.revisions.find((item) => item.id === current)
    ?? detail.value?.revisions[0]
    ?? null
})

const cardUrl = computed(() => selectedRevision.value ? `${selectedRevision.value.publicOrigin}/.well-known/agent-card.json` : '')
const sendUrl = computed(() => selectedRevision.value ? `${selectedRevision.value.publicOrigin}/a2a/v1/message:send` : '')
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

const readiness = computed(() => [
  { item: 'A2A 1.0 Agent Card', status: selectedRevision.value ? 'AVAILABLE' : 'NO_PUBLICATION', tone: selectedRevision.value ? 'success' : 'warning' },
  { item: 'HTTP+JSON message:send / tasks:get / tasks:list / tasks:cancel', status: 'IMPLEMENTED', tone: 'success' },
  { item: 'Streaming / Subscribe / Push / Extended Card', status: 'NOT_SUPPORTED', tone: 'info' },
  { item: '官方 TCK MUST', status: selectedRevision.value?.conformanceStatus ?? 'NOT_RUN', tone: selectedRevision.value?.conformanceStatus === 'PASSED' ? 'success' : 'warning' },
  { item: '真实双系统 E2E', status: 'NOT_RUN', tone: 'warning' },
])

async function reload() {
  loading.value = true
  try {
    const { data } = await listA2aPublications({ status: 'PUBLISHED', limit: 100, offset: 0 })
    publications.value = data?.items ?? []
    if (!selectedId.value && publications.value.length) selectedId.value = publications.value[0].id
    if (selectedId.value) await loadDetail(selectedId.value)
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: number) {
  selectedId.value = id
  const { data } = await getA2aPublication(id)
  detail.value = data
}

async function copy(value: string) {
  await navigator.clipboard.writeText(value)
  ElMessage.success('已复制；示例不会包含真实凭据')
}

onMounted(reload)
</script>

<template>
  <section class="developer-console" v-loading="loading">
    <div class="section-intro">
      <div><h2>开发与诊断</h2><p>从已发布的不可变修订生成接入信息；诊断结果区分“已实现、未支持、未执行”。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <WorkbenchPanel level="control" density="compact">
      <div class="publication-picker"><label>诊断目标</label><el-select :model-value="selectedId" filterable placeholder="选择已发布 Publication" @change="loadDetail"><el-option v-for="item in publications" :key="item.id" :label="`${item.publicationKey} · ${item.publicHost}`" :value="item.id" /></el-select></div>
    </WorkbenchPanel>

    <el-empty v-if="!publications.length" :image-size="64" description="还没有已发布的 Publication；请先完成草稿、预检和发布。" />

    <template v-else>
      <div class="developer-grid">
        <WorkbenchPanel title="标准入口" description="生产合规证据必须来自 Host-based 标准路由。">
          <div class="endpoint-list">
            <div><span>Agent Card</span><code>{{ cardUrl }}</code><el-button link :icon="CopyDocument" @click="copy(cardUrl)">复制</el-button></div>
            <div><span>Send Message</span><code>{{ sendUrl }}</code><el-button link :icon="CopyDocument" @click="copy(sendUrl)">复制</el-button></div>
            <div><span>Get Task</span><code>{{ selectedRevision?.publicOrigin }}/a2a/v1/tasks/{taskId}</code></div>
            <div><span>Version Header</span><code>A2A-Version: 1.0</code></div>
            <div><span>Media Type</span><code>application/a2a+json</code></div>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel title="发布门禁" description="没有验证证据的能力不会显示为通过。">
          <div class="readiness-list"><div v-for="item in readiness" :key="item.item"><span>{{ item.item }}</span><el-tag :type="item.tone as any" effect="plain">{{ item.status }}</el-tag></div></div>
        </WorkbenchPanel>
      </div>

      <WorkbenchPanel title="脱敏 curl 示例" description="使用环境变量注入 API Key；Message ID 必须由客户端稳定生成以获得幂等语义。">
        <div class="code-heading"><span>Inbound SendMessage</span><el-button link :icon="CopyDocument" @click="copy(curlSample)">复制示例</el-button></div>
        <pre class="code-block">{{ curlSample }}</pre>
      </WorkbenchPanel>

      <WorkbenchPanel title="只读 Agent Card 快照" description="该 JSON 是发布修订的不可变快照，不是浏览器临时拼装结果。">
        <AgentCardPreview :revision="selectedRevision" />
      </WorkbenchPanel>

      <el-alert type="warning" :closable="false" show-icon title="官方 TCK、真实外部调用和双系统 E2E 尚未执行时，本页面不会把“代码存在”表述为“企业可用”。" />
    </template>
  </section>
</template>

<style scoped lang="scss">
.developer-console { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.section-intro, .publication-picker, .code-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.section-intro { align-items: flex-start; }
.section-intro h2, .section-intro p { margin: 0; }
.section-intro h2 { font-size: 18px; }
.section-intro p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.publication-picker { justify-content: flex-start; }
.publication-picker label { color: var(--text-secondary); font-size: 13px; font-weight: 650; }
.publication-picker .el-select { width: min(520px, 100%); }
.developer-grid { display: grid; grid-template-columns: 1.15fr 0.85fr; gap: var(--layout-page-gap); }
.endpoint-list, .readiness-list { display: grid; gap: 7px; }
.endpoint-list > div { display: grid; grid-template-columns: 100px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--border-divider); }
.endpoint-list span, .readiness-list span { color: var(--text-secondary); font-size: 12px; }
.endpoint-list code { overflow: hidden; color: var(--text-primary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.readiness-list > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border-divider); }
.code-heading { margin-bottom: 8px; color: var(--text-secondary); font-size: 12px; }
.code-block { max-height: 420px; margin: 0; overflow: auto; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--text-secondary); background: var(--surface-glass-control); font: 12px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 900px) { .developer-grid { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .section-intro { flex-direction: column; } .publication-picker { align-items: stretch; flex-direction: column; } .publication-picker .el-select { width: 100%; } .endpoint-list > div { grid-template-columns: 1fr; } }
</style>
