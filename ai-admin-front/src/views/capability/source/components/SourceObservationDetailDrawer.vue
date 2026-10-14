<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import SourceOwnerLink from './SourceOwnerLink.vue'
import { scanSensitiveTypeLabel } from '@/utils/scanSensitiveLabels'
import type { ProjectToolInfo } from '@/types/scanProject'
import type { SemanticDoc, SemanticDocIndex } from '@/types/semanticDoc'
import { useSelectedSemanticDocument } from '../composables/useSelectedSemanticDocument'
import type { ToolParameter } from '@/types/tool'

interface ParameterRow extends ToolParameter { _key: string; children?: ParameterRow[] }
const props = withDefaults(defineProps<{
  row: ProjectToolInfo | null
  projectId: number
  doc?: SemanticDocIndex
  semanticLoading?: boolean
  semanticError?: string
  loading: boolean
  error: string
  rescanLoading: boolean
  canWrite?: boolean
  canReadOwner?: boolean
  generating?: boolean
  parameterRows: (parameters: ToolParameter[] | null | undefined) => ParameterRow[]
  renderMd: (content: string | null | undefined) => string
  toolParameterCount: (row: ProjectToolInfo) => number
  toolLinkLabel: (row: ProjectToolInfo) => string
  toolLinkTagType: (row: ProjectToolInfo) => 'success' | 'warning' | 'danger' | 'info'
}>(), { canWrite: true, canReadOwner: false, generating: false, semanticLoading: false, semanticError: '' })
const visible = defineModel<boolean>({ required: true })
const { document: fullDoc, loading: bodyLoading, error: bodyError, refresh: refreshBody } = useSelectedSemanticDocument(
  () => props.projectId, () => visible.value ? props.doc : null, () => props.semanticLoading)
const emit = defineEmits<{
  retry: []
  openDiff: [row: ProjectToolInfo]
  rescanFromSource: [row: ProjectToolInfo]
  regenerateTool: [row: ProjectToolInfo]
  openEditDoc: [doc: SemanticDoc]
}>()
</script>

<template>
  <AppDrawer v-model="visible" title="来源详情" size="min(800px, 96vw)" append-to-body destroy-on-close>
    <div v-if="row" class="source-observation-detail" v-loading="loading" element-loading-text="正在读取来源详情…" :aria-busy="loading">
      <header class="source-observation-detail__identity">
        <h3>{{ row.title || row.name }}</h3>
        <el-tag size="small" :type="row.removedFromSource ? 'danger' : 'info'">{{ row.removedFromSource ? '来源已移除' : '已发现' }}</el-tag>
        <p>{{ row.description || '尚未提供来源说明' }}</p>
        <SourceOwnerLink :project-id="projectId" :observation-id="row.scanToolId" :can-read="canReadOwner" />
      </header>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon>
        <template #default><el-button @click="emit('retry')">重新加载详情</el-button></template>
      </el-alert>
      <template v-else-if="!loading">
        <section class="source-detail-section">
          <h4>来源信息</h4>
          <dl class="source-detail-facts">
            <div><dt>接口标识</dt><dd><code>{{ row.name }}</code></dd></div>
            <div><dt>HTTP 端点</dt><dd><code>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '-' }}</code></dd></div>
            <div><dt>Base URL</dt><dd>{{ row.baseUrl || '-' }}</dd></div>
            <div><dt>来源定位</dt><dd>{{ row.sourceLocation || '-' }}</dd></div>
            <div><dt>请求体类型</dt><dd>{{ row.requestBodyType || '-' }}</dd></div>
            <div><dt>响应类型</dt><dd>{{ row.responseType || '-' }}</dd></div>
          </dl>
          <div class="source-detail-link">
            <span>历史投影关联</span>
            <el-tag :type="toolLinkTagType(row)" size="small">{{ toolLinkLabel(row) }}</el-tag>
            <el-tag v-if="row.sdkCapabilityReviewPending" size="small" type="warning">SDK 评审</el-tag>
            <el-button v-if="(row.toolSyncDiffFields?.length || 0) > 0" link type="primary" @click="emit('openDiff', row)">查看差异</el-button>
          </div>
          <p v-if="row.toolLinkMessage" class="source-detail-muted">{{ row.toolLinkMessage }}</p>
        </section>
        <section class="source-detail-section">
          <h4>参数定义 <span>{{ toolParameterCount(row) }} 项</span></h4>
          <el-table v-if="row.parameters?.length" :data="parameterRows(row.parameters)" size="small" row-key="_key"
            :tree-props="{ children: 'children' }" default-expand-all aria-label="来源参数定义">
            <el-table-column prop="name" label="参数名" min-width="160" />
            <el-table-column prop="type" label="类型" min-width="130" />
            <el-table-column label="位置" width="80"><template #default="{ row: param }">{{ param.location || '-' }}</template></el-table-column>
            <el-table-column prop="description" label="描述" min-width="160" />
            <el-table-column label="必填" width="60"><template #default="{ row: param }">{{ param.required ? '是' : '否' }}</template></el-table-column>
          </el-table>
          <p v-else class="source-detail-muted">没有声明参数</p>
        </section>
        <section class="source-detail-section">
          <h4>敏感数据</h4>
          <div v-if="row.sensitiveData?.types?.length" class="source-detail-tags">
            <el-tag v-for="type in row.sensitiveData.types" :key="type" size="small" type="warning">{{ scanSensitiveTypeLabel(type) }}</el-tag>
          </div>
          <p class="source-detail-muted">{{ row.sensitiveData?.summary || (row.sensitiveData?.scannedAt ? '本次扫描暂无敏感数据标记' : '尚未扫描或暂无结果') }}</p>
          <p v-if="row.sensitiveData?.scannedAt" class="source-detail-muted">扫描时间：{{ row.sensitiveData.scannedAt }}</p>
          <p v-if="row.sensitiveData?.modelName" class="source-detail-muted">扫描模型：{{ row.sensitiveData.modelName }}</p>
        </section>
        <section class="source-detail-section">
          <div class="source-detail-section__heading">
            <h4>AI 语义文档</h4>
            <div class="source-detail-actions">
              <el-button size="small" :disabled="!canWrite || generating || semanticLoading || !!semanticError" :loading="generating" @click="canWrite && !generating && !semanticLoading && !semanticError && emit('regenerateTool', row)">{{ doc ? '重新生成 AI' : '生成 AI 文档' }}</el-button>
              <el-button size="small" :disabled="!fullDoc || !canWrite || generating || semanticLoading || bodyLoading || !!semanticError || !!bodyError" @click="canWrite && !generating && !semanticLoading && !bodyLoading && !semanticError && !bodyError && fullDoc && emit('openEditDoc', fullDoc)">编辑 AI 文档</el-button>
            </div>
          </div>
          <p v-if="semanticLoading || bodyLoading" class="source-detail-muted" role="status">正在读取业务说明…</p>
          <p v-else-if="semanticError" class="source-detail-muted" role="alert">{{ semanticError }}</p>
          <el-alert v-else-if="bodyError" :title="bodyError" type="error" :closable="false"><template #default><el-button @click="refreshBody">重新读取正文</el-button></template></el-alert>
          <div v-else-if="fullDoc?.contentMd" class="source-detail-markdown" v-html="renderMd(fullDoc.contentMd)" />
          <p v-else class="source-detail-muted">还没有补充 AI 语义文档</p>
        </section>
      </template>
    </div>
    <template #footer>
      <div class="source-detail-footer">
        <el-tooltip v-if="row" content="仅刷新这条来源记录；API 接纳状态在所属目录查看，SDK 来源通过项目同步更新。" placement="top">
          <el-button :disabled="!canWrite || loading || !!error || row.removedFromSource || row.sourceLocation?.toLowerCase().startsWith('sdk:')"
            :loading="rescanLoading" @click="canWrite && emit('rescanFromSource', row)">扫描更新</el-button>
        </el-tooltip>
        <el-button @click="visible = false">关闭</el-button>
      </div>
    </template>
  </AppDrawer>
</template>

<style scoped lang="scss">
.source-observation-detail { min-width: 0; min-height: 160px; color: var(--text-primary); }
.source-observation-detail__identity { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-bottom: 20px; }
.source-observation-detail__identity h3 { margin: 0; font-size: 18px; line-height: 1.5; overflow-wrap: anywhere; }
.source-observation-detail__identity p { width: 100%; margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.source-detail-section { padding-block: 16px; border-top: 1px solid var(--border-divider); }
.source-detail-section h4 { display: flex; align-items: baseline; gap: 8px; margin: 0 0 12px; font-size: 14px; }
.source-detail-section h4 span { color: var(--text-muted); font-size: 12px; font-weight: 400; }
.source-detail-facts { display: grid; gap: 10px; margin: 0 0 16px; font-size: 13px; }
.source-detail-facts > div { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
.source-detail-facts dt, .source-detail-muted { color: var(--text-secondary); }
.source-detail-facts dd { margin: 0; overflow-wrap: anywhere; }
.source-detail-link, .source-detail-tags, .source-detail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; }
.source-detail-muted { margin: 8px 0 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.source-detail-section__heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.source-detail-section__heading h4 { margin: 0; }
.source-detail-actions :deep(.el-button + .el-button) { margin-left: 0; }
.source-detail-footer { display: flex; justify-content: flex-end; gap: 8px; }
.source-detail-footer :deep(.el-button + .el-button) { margin-left: 0; }
.source-detail-markdown { min-width: 0; font-size: 13px; line-height: 1.75; overflow-wrap: anywhere; }
.source-detail-markdown :deep(h1), .source-detail-markdown :deep(h2), .source-detail-markdown :deep(h3) { font-size: 15px; line-height: 1.5; margin: 16px 0 8px; }
.source-detail-markdown :deep(pre) { padding: 12px; border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); background: var(--surface-solid-control); overflow: auto; }
.source-detail-markdown :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
.source-detail-markdown :deep(th), .source-detail-markdown :deep(td) { padding: 6px 10px; border: 1px solid var(--border-divider); }
.source-detail-markdown :deep(a) { color: var(--text-link); }
.source-detail-markdown :deep(a:focus-visible) { outline: 2px solid var(--border-focus); outline-offset: 2px; }
@media (max-width: 640px) { .source-detail-facts > div { grid-template-columns: 80px minmax(0, 1fr); gap: 8px; } }
</style>
