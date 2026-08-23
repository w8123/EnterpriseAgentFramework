<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      variant="entity"
      domain="knowledge"
      :title="kbInfo?.name || '知识库详情'"
      show-back
      @back="router.push('/knowledge')"
    >
      <template #tags>
        <el-tag effect="plain" size="small">{{ kbCode }}</el-tag>
        <StatusTag
          :label="kbInfo?.scope || 'WORKSPACE'"
          :tone="scopeTagType(kbInfo?.scope)"
        />
      </template>
      <template #meta>
        <span v-if="kbInfo?.projectCode">项目 {{ kbInfo.projectCode }}</span>
      </template>
      <template #actions>
        <el-button @click="router.push({ path: '/retrieval', query: { kb: kbCode } })">
          <el-icon><Search /></el-icon>
          召回测试
        </el-button>
        <el-button type="primary" @click="router.push('/knowledge/import')">
          <el-icon><Upload /></el-icon>
          文件入库
        </el-button>
      </template>
    </PageHeader>

    <MetricStrip :items="knowledgeMetrics" aria-label="知识库运营指标" />

    <el-tabs v-model="activeTab" class="ops-tabs">
      <el-tab-pane label="运营概览" name="overview">
        <div class="overview-grid">
          <WorkbenchPanel title="命中热度" density="compact">
            <DataTableShell
              density="compact"
              :empty="(dashboard?.hotChunks || []).length === 0"
            >
              <el-table :data="dashboard?.hotChunks || []" size="small" height="320" empty-text=" ">
                <el-table-column prop="chunkIndex" label="#" width="64" />
                <el-table-column label="段落" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.title || truncate(row.content, 80) }}</template>
                </el-table-column>
                <el-table-column prop="hitCount" label="命中" width="80" align="center" />
              </el-table>
            </DataTableShell>
          </WorkbenchPanel>
          <WorkbenchPanel title="最近命中" density="compact">
            <DataTableShell
              density="compact"
              :empty="(dashboard?.recentHits || []).length === 0"
            >
              <el-table :data="dashboard?.recentHits || []" size="small" height="320" empty-text=" ">
                <el-table-column prop="queryText" label="问题" min-width="220" show-overflow-tooltip />
                <el-table-column label="分数" width="88">
                  <template #default="{ row }">{{ formatScore(row.score) }}</template>
                </el-table-column>
                <el-table-column prop="fileName" label="文档" min-width="160" show-overflow-tooltip />
              </el-table>
            </DataTableShell>
          </WorkbenchPanel>
        </div>

        <div class="overview-grid bottom-grid">
          <WorkbenchPanel title="低置信命中" density="compact">
            <DataTableShell
              density="compact"
              :empty="(dashboard?.lowConfidenceHits || []).length === 0"
            >
              <el-table :data="dashboard?.lowConfidenceHits || []" size="small" height="260" empty-text=" ">
                <el-table-column prop="queryText" label="问题" min-width="220" show-overflow-tooltip />
                <el-table-column label="分数" width="88">
                  <template #default="{ row }">{{ formatScore(row.score) }}</template>
                </el-table-column>
                <el-table-column prop="fileName" label="文档" min-width="160" show-overflow-tooltip />
              </el-table>
            </DataTableShell>
          </WorkbenchPanel>
          <WorkbenchPanel title="零命中段落" density="compact">
            <DataTableShell
              density="compact"
              :empty="(dashboard?.zeroHitChunks || []).length === 0"
            >
              <el-table :data="dashboard?.zeroHitChunks || []" size="small" height="260" empty-text=" ">
                <el-table-column prop="chunkIndex" label="#" width="64" />
                <el-table-column label="内容" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.title || truncate(row.content, 100) }}</template>
                </el-table-column>
              </el-table>
            </DataTableShell>
          </WorkbenchPanel>
        </div>
      </el-tab-pane>

      <el-tab-pane label="文件" name="files">
        <DocumentImportJobCard
          :job="reparseJob"
          title="重新解析任务"
          :busy="reparseActionLoading"
          :client-error="reparseClientError"
          dismissible
          @refresh="resumeReparseJob"
          @retry="handleRetryReparse"
          @cancel="handleCancelReparse"
          @dismiss="dismissReparseJob"
        />
        <div class="toolbar">
          <span class="selection-hint">已选 {{ selectedFiles.length }} 个文件</span>
          <el-button type="primary" size="small" :disabled="selectedFiles.length === 0" @click="openBatchTagDialog('FILE')">
            <el-icon><Plus /></el-icon>
            批量打标签
          </el-button>
        </div>
        <DataTableShell
          density="compact"
          :empty="fileList.length === 0"
          :loading="filesLoading"
        >
          <el-table :data="fileList" stripe empty-text=" " @selection-change="handleFileSelectionChange">
            <el-table-column type="selection" width="48" />
            <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
            <el-table-column label="解析器" width="170">
              <template #default="{ row }">
                <div class="provider-cell">
                  <el-tag size="small" effect="plain">{{ documentProviderLabel(row.parseProvider) }}</el-tag>
                  <span>{{ row.parseProviderVersion || '—' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="段落" width="100" align="center" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <StatusTag :label="statusText(row.status)" :tone="statusTagType(row.status)" />
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="180" />
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="router.push(`/knowledge/${kbCode}/file/${row.fileId}`)">
                  段落运营
                </el-button>
                <el-button
                  type="warning"
                  link
                  size="small"
                  :loading="reparsingId === row.fileId || isReparsingFile(row.fileId)"
                  :disabled="reparseInProgress && !isReparsingFile(row.fileId)"
                  @click="handleReparse(row)"
                >
                  重解析
                </el-button>
                <el-popconfirm title="确定删除该文件及关联段落和向量？" @confirm="handleDelete(row)">
                  <template #reference><el-button type="danger" link size="small">删除</el-button></template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="段落运营" name="chunks">
        <div class="toolbar">
          <el-input v-model="chunkKeyword" clearable placeholder="搜索段落" class="search-input" @keyup.enter="fetchChunks" />
          <el-select v-model="selectedTagFilter" clearable placeholder="按标签筛选" class="tag-filter" @change="fetchChunks">
            <el-option
              v-for="tag in tagStats"
              :key="`${tag.tagKey}:${tag.tagValue}`"
              :label="`${tag.tagGroup || '默认'} / ${tag.tagValue}`"
              :value="`${tag.tagKey}\u0001${tag.tagValue}`"
            >
              <span class="tag-option">
                <span class="tag-dot" :style="{ backgroundColor: tag.color || '#409EFF' }"></span>
                {{ tag.tagGroup || '默认' }} / {{ tag.tagKey }}={{ tag.tagValue }}
              </span>
            </el-option>
          </el-select>
          <el-segmented v-model="chunkEnabled" :options="chunkFilterOptions" @change="fetchChunks" />
          <el-button @click="fetchChunks" :loading="chunksLoading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="primary" :disabled="selectedChunks.length === 0" @click="openBatchTagDialog('CHUNK')">
            <el-icon><Plus /></el-icon>
            批量打标签
          </el-button>
        </div>
        <DataTableShell
          density="compact"
          :empty="chunks.length === 0"
          :loading="chunksLoading"
        >
          <el-table :data="chunks" stripe empty-text=" " @selection-change="handleChunkSelectionChange">
            <el-table-column type="selection" width="48" />
            <el-table-column prop="chunkIndex" label="#" width="70" align="center" />
            <el-table-column label="内容" min-width="420">
              <template #default="{ row }">
                <div class="chunk-title">{{ row.title || `段落 ${row.chunkIndex}` }}</div>
                <div v-if="row.elementType || row.sectionPath || chunkSourceLabel(row)" class="chunk-source">
                  <el-tag v-if="row.elementType" size="small" effect="plain">
                    {{ documentElementLabel(row.elementType) }}
                  </el-tag>
                  <span v-if="row.sectionPath">{{ row.sectionPath }}</span>
                  <span v-if="chunkSourceLabel(row)">{{ chunkSourceLabel(row) }}</span>
                </div>
                <div class="chunk-content">{{ truncate(row.content, 180) }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="hitCount" label="命中" width="90" align="center" />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <StatusTag
                  :label="row.enabled === 0 ? '停用' : '启用'"
                  :tone="row.enabled === 0 ? 'info' : 'success'"
                />
              </template>
            </el-table-column>
            <el-table-column prop="fileId" label="文件 ID" width="180" show-overflow-tooltip />
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="命中分析" name="hits">
        <div class="toolbar">
          <el-switch v-model="lowConfidenceOnly" active-text="只看低置信" @change="fetchHits" />
          <el-button @click="fetchHits" :loading="hitsLoading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
        <DataTableShell
          density="compact"
          :empty="hitLogs.length === 0"
          :loading="hitsLoading"
        >
          <el-table :data="hitLogs" stripe empty-text=" ">
            <el-table-column prop="queryText" label="用户问题" min-width="260" show-overflow-tooltip />
            <el-table-column prop="searchMode" label="模式" width="90" />
            <el-table-column label="分数" width="90">
              <template #default="{ row }">{{ formatScore(row.score) }}</template>
            </el-table-column>
            <el-table-column label="直接返回" width="100" align="center">
              <template #default="{ row }">
                <StatusTag
                  :label="row.directReturn ? '是' : '否'"
                  :tone="row.directReturn ? 'success' : 'info'"
                />
              </template>
            </el-table-column>
            <el-table-column prop="fileName" label="命中文档" min-width="180" show-overflow-tooltip />
            <el-table-column prop="createTime" label="时间" width="180" />
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="检索策略" name="policy">
        <el-form :model="configForm" label-width="130px" class="policy-form">
          <el-row :gutter="16">
            <el-col :span="8"><el-form-item label="切分策略"><el-select v-model="configForm.splitType" style="width:100%"><el-option label="固定长度" value="FIXED" /><el-option label="段落切分" value="PARAGRAPH" /><el-option label="语义切分" value="SEMANTIC" /></el-select></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="Chunk 大小"><el-input-number v-model="configForm.chunkSize" :min="100" :max="4000" :step="100" style="width:100%" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="重叠大小"><el-input-number v-model="configForm.chunkOverlap" :min="0" :max="1000" :step="10" style="width:100%" /></el-form-item></el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="8"><el-form-item label="检索模式"><el-segmented v-model="configForm.searchMode" :options="searchModeOptions" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="TopK"><el-input-number v-model="configForm.topK" :min="1" :max="50" style="width:100%" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="相似度阈值"><el-slider v-model="configForm.similarityThreshold" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="8"><el-form-item label="直接返回"><el-switch v-model="configForm.directReturnEnabled" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="直接返回阈值"><el-slider v-model="configForm.directReturnThreshold" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="Reranker"><el-switch v-model="configForm.rerankEnabled" /></el-form-item></el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="12"><el-form-item label="向量权重"><el-slider v-model="configForm.vectorWeight" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="关键词权重"><el-slider v-model="configForm.keywordWeight" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
          </el-row>
          <el-button type="primary" :loading="configSaving" @click="handleSaveConfig">保存策略</el-button>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tags">
        <div class="toolbar"><span></span><el-button type="primary" size="small" @click="openTagDialog"><el-icon><Plus /></el-icon>新增标签</el-button></div>
        <div class="tag-panel-stack">
          <WorkbenchPanel title="标签库" density="compact">
            <DataTableShell density="compact" :empty="tagStats.length === 0">
              <el-table :data="tagStats" stripe empty-text=" ">
                <el-table-column label="标签" min-width="220">
                  <template #default="{ row }">
                    <el-tag effect="light" :color="row.color || '#409EFF'" class="colored-tag">{{ row.tagValue }}</el-tag>
                    <span class="tag-key">{{ row.tagKey }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="tagGroup" label="分组" width="130" />
                <el-table-column prop="totalCount" label="绑定" width="80" align="center" />
                <el-table-column prop="fileCount" label="文件" width="80" align="center" />
                <el-table-column prop="chunkCount" label="段落" width="80" align="center" />
                <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
              </el-table>
            </DataTableShell>
          </WorkbenchPanel>
          <DataTableShell density="compact" :empty="tags.length === 0">
            <el-table :data="tags" stripe empty-text=" ">
              <el-table-column prop="tagKey" label="Key" width="180" />
              <el-table-column prop="tagValue" label="Value" min-width="220" />
              <el-table-column prop="targetType" label="对象" width="140" />
              <el-table-column prop="targetId" label="对象 ID" min-width="180" show-overflow-tooltip />
              <el-table-column label="操作" width="100"><template #default="{ row }"><el-popconfirm title="确定删除该标签？" @confirm="handleDeleteTag(row.id)"><template #reference><el-button type="danger" link size="small">删除</el-button></template></el-popconfirm></template></el-table-column>
            </el-table>
          </DataTableShell>
        </div>
      </el-tab-pane>

      <el-tab-pane label="问题映射" name="questions">
        <div class="toolbar"><span></span><el-button type="primary" size="small" @click="questionDialogVisible = true"><el-icon><Plus /></el-icon>新增问题</el-button></div>
        <DataTableShell density="compact" :empty="questions.length === 0">
          <el-table :data="questions" stripe empty-text=" ">
            <el-table-column prop="question" label="问题" min-width="320" show-overflow-tooltip />
            <el-table-column prop="chunkId" label="段落 ID" width="120" />
            <el-table-column prop="source" label="来源" width="120" />
            <el-table-column prop="hitCount" label="命中" width="90" align="center" />
            <el-table-column label="操作" width="100"><template #default="{ row }"><el-popconfirm title="确定删除该问题映射？" @confirm="handleDeleteQuestion(row.id)"><template #reference><el-button type="danger" link size="small">删除</el-button></template></el-popconfirm></template></el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>
    </el-tabs>

    <AppDialog
      v-model="tagDialogVisible"
      title="新增标签"
      width="460px"
    >
      <el-form :model="tagForm" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="分组"><el-input v-model="tagForm.tagGroup" placeholder="如 部门 / 业务线" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="颜色"><el-color-picker v-model="tagForm.color" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="Key"><el-input v-model="tagForm.tagKey" /></el-form-item>
        <el-form-item label="Value"><el-input v-model="tagForm.tagValue" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="tagForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="父级ID"><el-input-number v-model="tagForm.parentId" :min="1" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="排序"><el-input-number v-model="tagForm.sortOrder" :min="0" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="对象"><el-select v-model="tagForm.targetType" style="width:100%"><el-option label="知识库" value="KNOWLEDGE" /><el-option label="文件" value="FILE" /><el-option label="段落" value="CHUNK" /></el-select></el-form-item>
        <el-form-item label="对象 ID"><el-input v-model="tagForm.targetId" placeholder="知识库标签可留空" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="tagDialogVisible = false">取消</el-button><el-button type="primary" :loading="tagSaving" @click="handleCreateTag">保存</el-button></template>
    </AppDialog>

    <AppDialog
      v-model="batchTagDialogVisible"
      title="Batch Tags"
      width="520px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="batch-tag-alert"
        :title="`Bind one tag to ${selectedBatchCount} ${batchTagTargetType === 'FILE' ? 'files' : 'chunks'}`"
      />
      <el-form :model="batchTagForm" label-width="90px">
        <el-form-item label="Preset">
          <el-select v-model="batchSelectedTag" clearable filterable placeholder="Select existing tag" style="width:100%" @change="applyBatchTagPreset">
            <el-option
              v-for="tag in tagStats"
              :key="`${tag.tagKey}:${tag.tagValue}`"
              :label="`${tag.tagGroup || '默认'} / ${tag.tagKey}=${tag.tagValue}`"
              :value="`${tag.tagKey}\u0001${tag.tagValue}`"
            />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="Group"><el-input v-model="batchTagForm.tagGroup" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="Color"><el-color-picker v-model="batchTagForm.color" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="Key"><el-input v-model="batchTagForm.tagKey" /></el-form-item>
        <el-form-item label="Value"><el-input v-model="batchTagForm.tagValue" /></el-form-item>
        <el-form-item label="Desc"><el-input v-model="batchTagForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="Sort"><el-input-number v-model="batchTagForm.sortOrder" :min="0" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchTagDialogVisible = false">Cancel</el-button>
        <el-button type="primary" :loading="batchTagSaving" @click="handleBatchCreateTags">Apply</el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="questionDialogVisible"
      title="新增问题映射"
      width="560px"
    >
      <el-form :model="questionForm" label-width="90px">
        <el-form-item label="问题"><el-input v-model="questionForm.question" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="段落 ID"><el-input-number v-model="questionForm.chunkId" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="来源"><el-select v-model="questionForm.source" style="width:100%"><el-option label="人工" value="MANUAL" /><el-option label="反馈" value="FEEDBACK" /><el-option label="导入" value="IMPORT" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="questionDialogVisible = false">取消</el-button><el-button type="primary" :loading="questionSaving" @click="handleCreateQuestion">保存</el-button></template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import DocumentImportJobCard from '@/components/DocumentImportJobCard.vue'
import type { MetricStripItem, StatusTone } from '@/components/common/glassWorkbench'
import {
  batchCreateKnowledgeTags,
  createKnowledgeQuestion,
  createKnowledgeTag,
  deleteFile,
  deleteKnowledgeQuestion,
  deleteKnowledgeTag,
  getKbChunks,
  getKbFiles,
  getKnowledgeHitLogs,
  getKnowledgeList,
  getKnowledgeOpsDashboard,
  getKnowledgeQuestions,
  getKnowledgeStats,
  getKnowledgeTagStats,
  getKnowledgeTags,
  reparseFile,
  updateKbConfig,
} from '@/api/knowledge'
import {
  cancelDocumentImportJob,
  getDocumentImportJob,
  retryDocumentImportJob,
} from '@/api/import'
import type { ChunkDetail, FileInfo, KbConfig, KnowledgeBase, KnowledgeHitLog, KnowledgeOpsDashboard, KnowledgeQuestion, KnowledgeStats, KnowledgeTag, KnowledgeTagStats } from '@/types/knowledge'
import type { DocumentImportJob } from '@/types/import'
import {
  documentElementLabel,
  documentProviderLabel,
  formatDocumentSourceLocator,
  isAbortError,
  isDocumentJobTerminal,
  pollDocumentImportJob,
} from '@/utils/documentImport'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string

const activeTab = ref('overview')
const kbInfo = ref<KnowledgeBase | null>(null)
const dashboard = ref<KnowledgeOpsDashboard | null>(null)
const fileList = ref<FileInfo[]>([])
const chunks = ref<ChunkDetail[]>([])
const hitLogs = ref<KnowledgeHitLog[]>([])
const tags = ref<KnowledgeTag[]>([])
const tagStats = ref<KnowledgeTagStats[]>([])
const questions = ref<KnowledgeQuestion[]>([])

const filesLoading = ref(false)
const chunksLoading = ref(false)
const hitsLoading = ref(false)
const configSaving = ref(false)
const tagSaving = ref(false)
const batchTagSaving = ref(false)
const questionSaving = ref(false)
const reparsingId = ref<string | null>(null)
const reparseJob = ref<DocumentImportJob | null>(null)
const reparseActionLoading = ref(false)
const reparseClientError = ref('')
const reparsePollingController = ref<AbortController | null>(null)
const tagDialogVisible = ref(false)
const batchTagDialogVisible = ref(false)
const questionDialogVisible = ref(false)
const selectedFiles = ref<FileInfo[]>([])
const selectedChunks = ref<ChunkDetail[]>([])
const batchTagTargetType = ref<'FILE' | 'CHUNK'>('FILE')
const batchSelectedTag = ref('')
const chunkKeyword = ref('')
const chunkEnabled = ref<'all' | 'enabled' | 'disabled'>('all')
const selectedTagFilter = ref('')
const lowConfidenceOnly = ref(false)

const emptyStats: KnowledgeStats = { knowledgeBaseCode: kbCode, fileCount: 0, chunkCount: 0, activeChunkCount: 0, questionCount: 0, tagCount: 0, hitCount: 0 }
const stats = computed(() => dashboard.value?.stats || emptyStats)
const knowledgeMetrics = computed<MetricStripItem[]>(() => [
  { key: 'files', label: '文件', value: stats.value.fileCount, iconKey: 'workflow-sources', tone: 'brand' },
  { key: 'chunks', label: '段落', value: stats.value.chunkCount, iconKey: 'api-discovery', tone: 'info' },
  { key: 'active', label: '启用段落', value: stats.value.activeChunkCount, iconKey: 'agent-ready', tone: 'success' },
  { key: 'questions', label: '问题', value: stats.value.questionCount, iconKey: 'ai-semantic', tone: 'warning' },
  { key: 'hits', label: '命中', value: stats.value.hitCount, iconKey: 'scan', tone: 'brand' },
])
const selectedBatchCount = computed(() => batchTagTargetType.value === 'FILE' ? selectedFiles.value.length : selectedChunks.value.length)
const reparseInProgress = computed(() => !!reparseJob.value && !isDocumentJobTerminal(reparseJob.value.status))

const configForm = reactive<KbConfig>({ splitType: 'FIXED', chunkSize: 500, chunkOverlap: 50, searchMode: 'hybrid', topK: 5, similarityThreshold: 0.5, directReturnEnabled: true, directReturnThreshold: 0.9, rerankEnabled: true, vectorWeight: 0.7, keywordWeight: 0.3 })
const tagForm = reactive({
  targetType: 'KNOWLEDGE',
  targetId: '',
  tagKey: '',
  tagValue: '',
  tagGroup: '默认',
  color: '#409EFF',
  description: '',
  parentId: undefined as number | undefined,
  sortOrder: 0,
})
const batchTagForm = reactive({
  tagKey: '',
  tagValue: '',
  tagGroup: '默认',
  color: '#409EFF',
  description: '',
  parentId: undefined as number | undefined,
  sortOrder: 0,
})
const questionForm = reactive({ question: '', chunkId: undefined as number | undefined, source: 'MANUAL' })

const searchModeOptions = [{ label: '混合', value: 'hybrid' }, { label: '向量', value: 'vector' }, { label: '关键词', value: 'keyword' }]
const chunkFilterOptions = [{ label: '全部', value: 'all' }, { label: '启用', value: 'enabled' }, { label: '停用', value: 'disabled' }]

function truncate(text = '', maxLen: number) {
  return text.length > maxLen ? `${text.slice(0, maxLen)}...` : text
}

function formatScore(score?: number) {
  return typeof score === 'number' ? score.toFixed(3) : '-'
}

function statusText(status: number) {
  return ({ 0: '处理中', 1: '完成', 2: '失败' } as Record<number, string>)[status] || '未知'
}

function statusTagType(status: number): StatusTone {
  return ({ 0: 'warning', 1: 'success', 2: 'danger' } as Record<number, StatusTone>)[status] || 'info'
}

function scopeTagType(scope?: string): StatusTone {
  if (scope === 'SHARED') return 'success'
  if (scope === 'PROJECT') return 'warning'
  return 'info'
}

function applyKbConfig(found: KnowledgeBase) {
  Object.assign(configForm, {
    splitType: found.splitType || 'FIXED',
    chunkSize: found.chunkSize || 500,
    chunkOverlap: found.chunkOverlap || 50,
    searchMode: found.searchMode || 'hybrid',
    topK: found.topK || 5,
    similarityThreshold: found.similarityThreshold ?? 0.5,
    directReturnEnabled: found.directReturnEnabled ?? true,
    directReturnThreshold: found.directReturnThreshold ?? 0.9,
    rerankEnabled: found.rerankEnabled ?? true,
    vectorWeight: found.vectorWeight ?? 0.7,
    keywordWeight: found.keywordWeight ?? 0.3,
  })
}

async function fetchKbInfo() {
  const { data } = await getKnowledgeList()
  const found = Array.isArray(data) ? data.find((kb) => kb.code === kbCode) : null
  if (found) {
    kbInfo.value = found
    applyKbConfig(found)
  }
}

async function fetchDashboard() {
  const { data } = await getKnowledgeOpsDashboard(kbCode)
  dashboard.value = data as unknown as KnowledgeOpsDashboard
}

async function fetchStatsOnly() {
  const { data } = await getKnowledgeStats(kbCode)
  dashboard.value = { ...(dashboard.value || {}), stats: data as unknown as KnowledgeStats } as KnowledgeOpsDashboard
}

async function fetchFiles() {
  filesLoading.value = true
  try {
    const { data } = await getKbFiles(kbCode)
    fileList.value = Array.isArray(data) ? data : []
  } finally {
    filesLoading.value = false
  }
}

async function fetchChunks() {
  chunksLoading.value = true
  try {
    const enabled = chunkEnabled.value === 'enabled' ? 1 : chunkEnabled.value === 'disabled' ? 0 : undefined
    const [tagKey, tagValue] = selectedTagFilter.value ? selectedTagFilter.value.split('\u0001') : []
    const { data } = await getKbChunks(kbCode, {
      keyword: chunkKeyword.value || undefined,
      enabled,
      tagKey,
      tagValue,
      limit: 200,
    })
    chunks.value = Array.isArray(data) ? data : []
  } finally {
    chunksLoading.value = false
  }
}

async function fetchHits() {
  hitsLoading.value = true
  try {
    const { data } = await getKnowledgeHitLogs(kbCode, { limit: 100, lowConfidenceOnly: lowConfidenceOnly.value })
    hitLogs.value = Array.isArray(data) ? data : []
  } finally {
    hitsLoading.value = false
  }
}

async function fetchTags() {
  const [{ data: tagData }, { data: statsData }] = await Promise.all([
    getKnowledgeTags(kbCode),
    getKnowledgeTagStats(kbCode),
  ])
  tags.value = Array.isArray(tagData) ? tagData : []
  tagStats.value = Array.isArray(statsData) ? statsData : []
}

async function fetchQuestions() {
  const { data } = await getKnowledgeQuestions(kbCode)
  questions.value = Array.isArray(data) ? data : []
}

async function refreshAll() {
  await Promise.all([fetchKbInfo(), fetchDashboard(), fetchFiles(), fetchTags(), fetchQuestions()])
}

function openTagDialog() {
  tagForm.targetType = 'KNOWLEDGE'
  tagForm.targetId = ''
  tagForm.tagKey = ''
  tagForm.tagValue = ''
  tagForm.tagGroup = '默认'
  tagForm.color = '#409EFF'
  tagForm.description = ''
  tagForm.parentId = undefined
  tagForm.sortOrder = 0
  tagDialogVisible.value = true
}

function handleFileSelectionChange(rows: FileInfo[]) {
  selectedFiles.value = rows
}

function handleChunkSelectionChange(rows: ChunkDetail[]) {
  selectedChunks.value = rows
}

function openBatchTagDialog(targetType: 'FILE' | 'CHUNK') {
  batchTagTargetType.value = targetType
  batchSelectedTag.value = ''
  batchTagForm.tagKey = ''
  batchTagForm.tagValue = ''
  batchTagForm.tagGroup = '默认'
  batchTagForm.color = '#409EFF'
  batchTagForm.description = ''
  batchTagForm.parentId = undefined
  batchTagForm.sortOrder = 0
  batchTagDialogVisible.value = true
}

function applyBatchTagPreset(value: string) {
  if (!value) return
  const [tagKey, tagValue] = value.split('\u0001')
  const preset = tagStats.value.find((tag) => tag.tagKey === tagKey && tag.tagValue === tagValue)
  batchTagForm.tagKey = tagKey || ''
  batchTagForm.tagValue = tagValue || ''
  batchTagForm.tagGroup = preset?.tagGroup || '默认'
  batchTagForm.color = preset?.color || '#409EFF'
  batchTagForm.description = preset?.description || ''
  batchTagForm.parentId = preset?.parentId
  batchTagForm.sortOrder = preset?.sortOrder || 0
}

async function handleSaveConfig() {
  configSaving.value = true
  try {
    await updateKbConfig(kbCode, configForm)
    ElMessage.success('检索策略已保存')
    await fetchKbInfo()
  } finally {
    configSaving.value = false
  }
}

async function handleDelete(row: FileInfo) {
  await deleteFile(row.fileId)
  ElMessage.success('文件已删除')
  await Promise.all([fetchFiles(), fetchDashboard()])
}

async function handleReparse(row: FileInfo) {
  if (reparseInProgress.value) {
    ElMessage.warning('请先等待当前重新解析任务完成或取消该任务')
    return
  }
  reparsingId.value = row.fileId
  reparseClientError.value = ''
  try {
    const { data } = await reparseFile(row.fileId)
    const job = data as unknown as DocumentImportJob
    setReparseJob(job)
    ElMessage.success('已提交重新解析任务，可在文件页持续查看进度')
    await trackReparseJob(job)
  } finally {
    reparsingId.value = null
  }
}

async function handleRetryReparse() {
  const job = reparseJob.value
  if (!job) return
  reparseActionLoading.value = true
  reparseClientError.value = ''
  stopReparsePolling()
  try {
    const { data } = await retryDocumentImportJob(job.jobId)
    const retried = data as unknown as DocumentImportJob
    setReparseJob(retried)
    reparseActionLoading.value = false
    await trackReparseJob(retried)
  } catch (error) {
    if (!isAbortError(error)) reparseClientError.value = errorMessage(error, '重新解析重试失败')
  } finally {
    reparseActionLoading.value = false
  }
}

async function handleCancelReparse() {
  const job = reparseJob.value
  if (!job) return
  try {
    await ElMessageBox.confirm(
      `确认取消“${job.fileName}”的重新解析任务？当前线上文件不会被删除。`,
      '取消重新解析',
      { confirmButtonText: '确认取消', cancelButtonText: '继续处理', type: 'warning' },
    )
  } catch {
    return
  }
  reparseActionLoading.value = true
  stopReparsePolling()
  try {
    await cancelDocumentImportJob(job.jobId)
    reparseJob.value = { ...job, status: 'CANCELLED', stage: 'CANCELLED' }
    clearStoredReparseJob()
    ElMessage.success('重新解析任务已取消')
  } catch (error) {
    reparseClientError.value = errorMessage(error, '无法取消重新解析任务')
  } finally {
    reparseActionLoading.value = false
  }
}

function resumeReparseJob() {
  if (!reparseJob.value) return
  reparseClientError.value = ''
  void trackReparseJob(reparseJob.value)
}

function dismissReparseJob() {
  stopReparsePolling()
  reparseJob.value = null
  reparseClientError.value = ''
  clearStoredReparseJob()
}

async function trackReparseJob(job: DocumentImportJob) {
  stopReparsePolling()
  const controller = new AbortController()
  reparsePollingController.value = controller
  try {
    const result = await pollDocumentImportJob({
      jobId: job.jobId,
      fetchJob: fetchImportJob,
      signal: controller.signal,
      stopWhen: (current) => current.status === 'COMPLETED',
      onUpdate: setReparseJob,
    })
    setReparseJob(result)
    if (result.status === 'COMPLETED') {
      clearStoredReparseJob()
      ElMessage.success('文件重新解析并替换完成')
      await Promise.all([fetchFiles(), fetchDashboard()])
    } else if (result.status === 'FAILED') {
      ElMessage.error(result.errorMessage || `重新解析失败（${result.errorCode || 'FAILED'}）`)
    }
  } catch (error) {
    if (!isAbortError(error)) reparseClientError.value = errorMessage(error, '无法刷新重新解析任务')
  } finally {
    if (reparsePollingController.value === controller) reparsePollingController.value = null
  }
}

async function fetchImportJob(jobId: string, includePreview: boolean): Promise<DocumentImportJob> {
  const { data } = await getDocumentImportJob(jobId, includePreview)
  return data as unknown as DocumentImportJob
}

function setReparseJob(job: DocumentImportJob) {
  reparseJob.value = job
  if (job.status === 'COMPLETED' || job.status === 'CANCELLED') clearStoredReparseJob()
  else storeReparseJob(job.jobId)
}

function isReparsingFile(fileId: string): boolean {
  return reparseInProgress.value && reparseJob.value?.replaceFileId === fileId
}

function stopReparsePolling() {
  reparsePollingController.value?.abort()
  reparsePollingController.value = null
}

function reparseStorageKey() {
  return `reachai:knowledge:reparse-job:${kbCode}`
}

function storeReparseJob(jobId: string) {
  try {
    window.sessionStorage.setItem(reparseStorageKey(), jobId)
  } catch {
    // Server-side durable state remains authoritative.
  }
}

function clearStoredReparseJob() {
  try {
    window.sessionStorage.removeItem(reparseStorageKey())
  } catch {
    // Ignore unavailable browser storage.
  }
}

async function restoreReparseJob() {
  let jobId = ''
  try {
    jobId = window.sessionStorage.getItem(reparseStorageKey()) || ''
  } catch {
    return
  }
  if (!jobId) return
  try {
    const job = await fetchImportJob(jobId, false)
    setReparseJob(job)
    if (!isDocumentJobTerminal(job.status)) await trackReparseJob(job)
  } catch (error) {
    reparseClientError.value = errorMessage(error, '无法恢复重新解析任务')
  }
}

function chunkSourceLabel(row: ChunkDetail): string {
  return formatDocumentSourceLocator(row.sourceLocatorJson)
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

async function handleCreateTag() {
  if (!tagForm.tagKey.trim() || !tagForm.tagValue.trim()) return ElMessage.warning('请填写标签 Key 和 Value')
  tagSaving.value = true
  try {
    await createKnowledgeTag(kbCode, {
      ...tagForm,
      targetId: tagForm.targetId || undefined,
      parentId: tagForm.parentId || undefined,
    })
    ElMessage.success('标签已创建')
    tagDialogVisible.value = false
    tagForm.tagKey = ''
    tagForm.tagValue = ''
    tagForm.targetId = ''
    tagForm.description = ''
    tagForm.parentId = undefined
    tagForm.sortOrder = 0
    await Promise.all([fetchTags(), fetchStatsOnly()])
  } finally {
    tagSaving.value = false
  }
}

async function handleBatchCreateTags() {
  if (!batchTagForm.tagKey.trim() || !batchTagForm.tagValue.trim()) return ElMessage.warning('Please input tag key and value')
  const targetIds = batchTagTargetType.value === 'FILE'
    ? selectedFiles.value.map((file) => file.fileId)
    : selectedChunks.value.map((chunk) => String(chunk.id))
  if (targetIds.length === 0) return ElMessage.warning('Please select targets first')
  batchTagSaving.value = true
  try {
    const { data } = await batchCreateKnowledgeTags(kbCode, {
      targetType: batchTagTargetType.value,
      targetIds,
      tagKey: batchTagForm.tagKey,
      tagValue: batchTagForm.tagValue,
      tagGroup: batchTagForm.tagGroup,
      color: batchTagForm.color,
      description: batchTagForm.description,
      parentId: batchTagForm.parentId,
      sortOrder: batchTagForm.sortOrder,
    })
    const createdCount = Array.isArray(data) ? data.length : 0
    ElMessage.success(`Tagged ${createdCount} new target(s)`)
    batchTagDialogVisible.value = false
    await Promise.all([fetchTags(), fetchStatsOnly()])
    if (batchTagTargetType.value === 'CHUNK') await fetchChunks()
  } finally {
    batchTagSaving.value = false
  }
}

async function handleDeleteTag(id: number) {
  await deleteKnowledgeTag(kbCode, id)
  ElMessage.success('标签已删除')
  await Promise.all([fetchTags(), fetchStatsOnly()])
}

async function handleCreateQuestion() {
  if (!questionForm.question.trim()) return ElMessage.warning('请填写问题')
  questionSaving.value = true
  try {
    await createKnowledgeQuestion(kbCode, { ...questionForm })
    ElMessage.success('问题映射已创建')
    questionDialogVisible.value = false
    questionForm.question = ''
    questionForm.chunkId = undefined
    await Promise.all([fetchQuestions(), fetchStatsOnly()])
  } finally {
    questionSaving.value = false
  }
}

async function handleDeleteQuestion(id: number) {
  await deleteKnowledgeQuestion(kbCode, id)
  ElMessage.success('问题映射已删除')
  await Promise.all([fetchQuestions(), fetchStatsOnly()])
}

watch(activeTab, (tab) => {
  if (tab === 'chunks' && chunks.value.length === 0) fetchChunks()
  if (tab === 'hits' && hitLogs.value.length === 0) fetchHits()
})

onMounted(refreshAll)
onMounted(restoreReparseJob)
onBeforeUnmount(stopReparsePolling)
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 14px;
}

.bottom-grid {
  margin-top: 14px;
}

.toolbar {
  justify-content: space-between;
  margin-bottom: 10px;
}

.search-input {
  max-width: 360px;
}

.tag-filter {
  width: 240px;
}

.tag-panel-stack {
  display: grid;
  gap: var(--section-gap);
}

.selection-hint {
  color: var(--text-secondary);
  font-size: 13px;
}

.batch-tag-alert {
  margin-bottom: 14px;
}

.tag-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tag-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.colored-tag {
  border: 0;
  color: var(--text-inverse);
}

.tag-key {
  margin-left: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.chunk-title {
  margin-bottom: 4px;
  font-weight: 600;
}

.chunk-content {
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.provider-cell {
  display: flex;
  align-items: center;
  gap: 8px;

  span {
    overflow: hidden;
    font-size: 12px;
    color: var(--text-muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.chunk-source {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 5px;
  font-size: 12px;
  color: var(--text-muted);
}

.policy-form {
  max-width: 1180px;
}

@media (max-width: 960px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
