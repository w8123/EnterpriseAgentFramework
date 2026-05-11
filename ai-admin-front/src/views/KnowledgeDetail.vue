<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="router.push('/knowledge')">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div>
          <h2>{{ kbInfo?.name || '知识库详情' }}</h2>
          <div class="subline">
            <el-tag effect="plain" size="small">{{ kbCode }}</el-tag>
            <el-tag size="small" :type="scopeTagType(kbInfo?.scope)">{{ kbInfo?.scope || 'WORKSPACE' }}</el-tag>
            <span v-if="kbInfo?.projectCode">项目 {{ kbInfo.projectCode }}</span>
          </div>
        </div>
      </div>
      <el-button type="primary" @click="router.push('/knowledge/import')">
        <el-icon><Upload /></el-icon>
        文件入库
      </el-button>
    </div>

    <div class="stats-grid">
      <div class="stat-tile">
        <span>文件</span>
        <strong>{{ stats?.fileCount ?? fileList.length }}</strong>
      </div>
      <div class="stat-tile">
        <span>段落</span>
        <strong>{{ stats?.chunkCount ?? kbInfo?.chunkCount ?? 0 }}</strong>
      </div>
      <div class="stat-tile">
        <span>问题</span>
        <strong>{{ stats?.questionCount ?? questions.length }}</strong>
      </div>
      <div class="stat-tile">
        <span>标签</span>
        <strong>{{ stats?.tagCount ?? tags.length }}</strong>
      </div>
      <div class="stat-tile">
        <span>命中</span>
        <strong>{{ stats?.hitCount ?? kbInfo?.hitCount ?? 0 }}</strong>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="ops-tabs">
      <el-tab-pane label="文件" name="files">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-row">
              <span>文件列表</span>
              <el-button size="small" @click="fetchFiles" :loading="filesLoading">
                <el-icon><Refresh /></el-icon>
                刷新
              </el-button>
            </div>
          </template>

          <el-table v-loading="filesLoading" :data="fileList" stripe style="width: 100%">
            <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
            <el-table-column prop="fileType" label="类型" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.fileType || '-' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="120" align="right">
              <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="段落" width="100" align="center" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="180" />
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="router.push(`/knowledge/${kbCode}/file/${row.fileId}`)">
                  段落
                </el-button>
                <el-button type="warning" link size="small" :loading="reparsingId === row.fileId" @click="handleReparse(row)">
                  重解析
                </el-button>
                <el-popconfirm title="确定删除该文件及关联段落和向量？" @confirm="handleDelete(row)">
                  <template #reference>
                    <el-button type="danger" link size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="检索策略" name="policy">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-row">
              <span>切分与检索策略</span>
              <el-button type="primary" size="small" :loading="configSaving" @click="handleSaveConfig">
                保存配置
              </el-button>
            </div>
          </template>

          <el-form :model="configForm" label-width="130px" class="policy-form">
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="切分策略">
                  <el-select v-model="configForm.splitType" style="width: 100%">
                    <el-option label="固定长度" value="FIXED" />
                    <el-option label="段落切分" value="PARAGRAPH" />
                    <el-option label="语义切分" value="SEMANTIC" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="Chunk 大小">
                  <el-input-number v-model="configForm.chunkSize" :min="100" :max="4000" :step="100" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="重叠大小">
                  <el-input-number v-model="configForm.chunkOverlap" :min="0" :max="1000" :step="10" style="width: 100%" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="检索模式">
                  <el-segmented
                    v-model="configForm.searchMode"
                    :options="[
                      { label: '混合', value: 'hybrid' },
                      { label: '向量', value: 'vector' },
                      { label: '关键词', value: 'keyword' },
                    ]"
                    style="width: 100%"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="TopK">
                  <el-input-number v-model="configForm.topK" :min="1" :max="50" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="相似度阈值">
                  <el-slider v-model="configForm.similarityThreshold" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="直接返回">
                  <el-switch v-model="configForm.directReturnEnabled" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="直接返回阈值">
                  <el-slider v-model="configForm.directReturnThreshold" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="Rerank">
                  <el-switch v-model="configForm.rerankEnabled" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="向量权重">
                  <el-slider v-model="configForm.vectorWeight" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="关键词权重">
                  <el-slider v-model="configForm.keywordWeight" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tags">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-row">
              <span>标签</span>
              <el-button type="primary" size="small" @click="tagDialogVisible = true">
                <el-icon><Plus /></el-icon>
                新增标签
              </el-button>
            </div>
          </template>
          <el-table :data="tags" stripe>
            <el-table-column prop="tagKey" label="Key" width="180" />
            <el-table-column prop="tagValue" label="Value" min-width="220">
              <template #default="{ row }">
                <el-tag effect="plain">{{ row.tagValue }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetType" label="对象" width="140" />
            <el-table-column prop="targetId" label="对象 ID" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-popconfirm title="确定删除该标签？" @confirm="handleDeleteTag(row.id)">
                  <template #reference>
                    <el-button type="danger" link size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="问题映射" name="questions">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header-row">
              <span>问题映射</span>
              <el-button type="primary" size="small" @click="questionDialogVisible = true">
                <el-icon><Plus /></el-icon>
                新增问题
              </el-button>
            </div>
          </template>
          <el-table :data="questions" stripe>
            <el-table-column prop="question" label="问题" min-width="320" show-overflow-tooltip />
            <el-table-column prop="chunkId" label="段落 ID" width="120" />
            <el-table-column prop="source" label="来源" width="120" />
            <el-table-column prop="hitCount" label="命中" width="90" align="center" />
            <el-table-column prop="updateTime" label="更新时间" width="180" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-popconfirm title="确定删除该问题映射？" @confirm="handleDeleteQuestion(row.id)">
                  <template #reference>
                    <el-button type="danger" link size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="tagDialogVisible" title="新增标签" width="460px">
      <el-form :model="tagForm" label-width="90px">
        <el-form-item label="Key">
          <el-input v-model="tagForm.tagKey" placeholder="如 department" />
        </el-form-item>
        <el-form-item label="Value">
          <el-input v-model="tagForm.tagValue" placeholder="如 finance" />
        </el-form-item>
        <el-form-item label="对象">
          <el-select v-model="tagForm.targetType" style="width: 100%">
            <el-option label="知识库" value="KNOWLEDGE" />
            <el-option label="文件" value="FILE" />
            <el-option label="段落" value="CHUNK" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象 ID">
          <el-input v-model="tagForm.targetId" placeholder="知识库标签可留空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="handleCreateTag">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="questionDialogVisible" title="新增问题映射" width="560px">
      <el-form :model="questionForm" label-width="90px">
        <el-form-item label="问题">
          <el-input v-model="questionForm.question" type="textarea" :rows="3" placeholder="输入常见问法或用户原始问题" />
        </el-form-item>
        <el-form-item label="段落 ID">
          <el-input-number v-model="questionForm.chunkId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="questionForm.source" style="width: 100%">
            <el-option label="人工" value="MANUAL" />
            <el-option label="反馈" value="FEEDBACK" />
            <el-option label="导入" value="IMPORT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="questionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="questionSaving" @click="handleCreateQuestion">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import {
  createKnowledgeQuestion,
  createKnowledgeTag,
  deleteFile,
  deleteKnowledgeQuestion,
  deleteKnowledgeTag,
  getKbFiles,
  getKnowledgeList,
  getKnowledgeQuestions,
  getKnowledgeStats,
  getKnowledgeTags,
  reparseFile,
  updateKbConfig,
} from '@/api/knowledge'
import type {
  FileInfo,
  KbConfig,
  KnowledgeBase,
  KnowledgeQuestion,
  KnowledgeStats,
  KnowledgeTag,
} from '@/types/knowledge'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string

const activeTab = ref('files')
const kbInfo = ref<KnowledgeBase | null>(null)
const stats = ref<KnowledgeStats | null>(null)
const fileList = ref<FileInfo[]>([])
const tags = ref<KnowledgeTag[]>([])
const questions = ref<KnowledgeQuestion[]>([])

const filesLoading = ref(false)
const configSaving = ref(false)
const tagSaving = ref(false)
const questionSaving = ref(false)
const reparsingId = ref<string | null>(null)
const tagDialogVisible = ref(false)
const questionDialogVisible = ref(false)

const configForm = reactive<KbConfig>({
  splitType: 'FIXED',
  chunkSize: 500,
  chunkOverlap: 50,
  searchMode: 'hybrid',
  topK: 5,
  similarityThreshold: 0.5,
  directReturnEnabled: true,
  directReturnThreshold: 0.9,
  rerankEnabled: true,
  vectorWeight: 0.7,
  keywordWeight: 0.3,
})

const tagForm = reactive({
  targetType: 'KNOWLEDGE',
  targetId: '',
  tagKey: '',
  tagValue: '',
})

const questionForm = reactive({
  question: '',
  chunkId: undefined as number | undefined,
  source: 'MANUAL',
})

function formatFileSize(bytes: number | null): string {
  if (!bytes) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let i = 0
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(1)} ${units[i]}`
}

function statusText(status: number): string {
  const map: Record<number, string> = { 0: '处理中', 1: '完成', 2: '失败' }
  return map[status] ?? '未知'
}

function statusTagType(status: number): string {
  const map: Record<number, string> = { 0: 'warning', 1: 'success', 2: 'danger' }
  return map[status] ?? 'info'
}

function scopeTagType(scope?: string): string {
  if (scope === 'SHARED') return 'success'
  if (scope === 'PROJECT') return 'warning'
  return 'info'
}

function applyKbConfig(found: KnowledgeBase) {
  configForm.splitType = found.splitType || 'FIXED'
  configForm.chunkSize = found.chunkSize || 500
  configForm.chunkOverlap = found.chunkOverlap || 50
  configForm.searchMode = found.searchMode || 'hybrid'
  configForm.topK = found.topK || 5
  configForm.similarityThreshold = found.similarityThreshold ?? 0.5
  configForm.directReturnEnabled = found.directReturnEnabled ?? true
  configForm.directReturnThreshold = found.directReturnThreshold ?? 0.9
  configForm.rerankEnabled = found.rerankEnabled ?? true
  configForm.vectorWeight = found.vectorWeight ?? 0.7
  configForm.keywordWeight = found.keywordWeight ?? 0.3
}

async function fetchKbInfo() {
  const { data } = await getKnowledgeList()
  const list = Array.isArray(data) ? data : []
  const found = list.find((kb: KnowledgeBase) => kb.code === kbCode)
  if (found) {
    kbInfo.value = found
    applyKbConfig(found)
  }
}

async function fetchStats() {
  const { data } = await getKnowledgeStats(kbCode)
  stats.value = data as unknown as KnowledgeStats
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

async function fetchTags() {
  const { data } = await getKnowledgeTags(kbCode)
  tags.value = Array.isArray(data) ? data : []
}

async function fetchQuestions() {
  const { data } = await getKnowledgeQuestions(kbCode)
  questions.value = Array.isArray(data) ? data : []
}

async function refreshAll() {
  await Promise.all([fetchKbInfo(), fetchStats(), fetchFiles(), fetchTags(), fetchQuestions()])
}

async function handleSaveConfig() {
  configSaving.value = true
  try {
    await updateKbConfig(kbCode, configForm)
    ElMessage.success('配置已保存')
    await fetchKbInfo()
  } finally {
    configSaving.value = false
  }
}

async function handleDelete(row: FileInfo) {
  await deleteFile(row.fileId)
  ElMessage.success('删除成功')
  await Promise.all([fetchFiles(), fetchStats()])
}

async function handleReparse(row: FileInfo) {
  reparsingId.value = row.fileId
  try {
    await reparseFile(row.fileId)
    ElMessage.success('重新解析已完成')
    await Promise.all([fetchFiles(), fetchStats()])
  } finally {
    reparsingId.value = null
  }
}

async function handleCreateTag() {
  if (!tagForm.tagKey.trim() || !tagForm.tagValue.trim()) {
    ElMessage.warning('请填写标签 Key 和 Value')
    return
  }
  tagSaving.value = true
  try {
    await createKnowledgeTag(kbCode, {
      targetType: tagForm.targetType,
      targetId: tagForm.targetId || undefined,
      tagKey: tagForm.tagKey,
      tagValue: tagForm.tagValue,
    })
    ElMessage.success('标签已创建')
    tagDialogVisible.value = false
    tagForm.tagKey = ''
    tagForm.tagValue = ''
    tagForm.targetId = ''
    await Promise.all([fetchTags(), fetchStats()])
  } finally {
    tagSaving.value = false
  }
}

async function handleDeleteTag(id: number) {
  await deleteKnowledgeTag(kbCode, id)
  ElMessage.success('标签已删除')
  await Promise.all([fetchTags(), fetchStats()])
}

async function handleCreateQuestion() {
  if (!questionForm.question.trim()) {
    ElMessage.warning('请填写问题')
    return
  }
  questionSaving.value = true
  try {
    await createKnowledgeQuestion(kbCode, {
      question: questionForm.question,
      chunkId: questionForm.chunkId,
      source: questionForm.source,
    })
    ElMessage.success('问题映射已创建')
    questionDialogVisible.value = false
    questionForm.question = ''
    questionForm.chunkId = undefined
    await Promise.all([fetchQuestions(), fetchStats()])
  } finally {
    questionSaving.value = false
  }
}

async function handleDeleteQuestion(id: number) {
  await deleteKnowledgeQuestion(kbCode, id)
  ElMessage.success('问题映射已删除')
  await Promise.all([fetchQuestions(), fetchStats()])
}

onMounted(refreshAll)
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;

  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    color: var(--text-primary);
  }
}

.subline {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-tile {
  min-height: 72px;
  padding: 14px 16px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: var(--bg-secondary);
  display: flex;
  flex-direction: column;
  gap: 8px;

  span {
    color: var(--text-secondary);
    font-size: 13px;
  }

  strong {
    color: var(--text-primary);
    font-size: 24px;
    line-height: 1;
  }
}

.ops-tabs {
  margin-top: 4px;
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.policy-form {
  max-width: 1180px;
}

@media (max-width: 960px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
