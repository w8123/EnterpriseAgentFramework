<template>
  <div class="knowledge-files-tab">
    <DocumentImportJobCard
      :job="job"
      :title="jobTitle"
      :busy="jobActionLoading || commitInProgress"
      :client-error="jobClientError"
      :client-error-title="commitOutcomeUnknown ? '入库结果待确认' : '任务操作未完成'"
      :show-actions="!commitOutcomeUnknown"
      dismissible
      @refresh="resumeJob"
      @retry="handleRetryJob"
      @cancel="handleCancelJob"
      @dismiss="dismissJob"
    />

    <section v-if="job && (commitOutcomeUnknown || (job.status === 'PARSED' && !job.autoCommit))" class="import-review" aria-label="文档入库确认">
      <h3>确认文件入库</h3>
      <p v-if="commitInProgress">正在等待文件入库完成，请勿重复提交。</p>
      <p v-else-if="commitOutcomeUnknown">入库结果尚未确认，请先检查任务状态。</p>
      <p v-else>解析已完成。确认后，这些内容将写入知识库并可供检索。</p>
      <template v-if="job.preview && !commitInProgress && !commitOutcomeUnknown">
        <p>共 {{ job.preview.totalChunks }} 个段落<span v-if="job.preview.totalChunks > previewLimit">，当前展示前 {{ previewLimit }} 个</span>。</p>
        <ol class="import-preview">
          <li v-for="chunk in job.preview.chunks.slice(0, previewLimit)" :key="chunk.index">
            <strong v-if="chunk.sectionPath">{{ chunk.sectionPath }}</strong>
            <p>{{ chunk.content }}</p>
          </li>
        </ol>
        <el-button v-if="job.preview.chunks.length > previewLimit" @click="previewLimit += 20">显示更多段落</el-button>
      </template>
      <p v-else-if="!commitInProgress && !commitOutcomeUnknown">暂时无法显示解析内容，请重新读取预览后再确认。</p>
      <div v-if="!commitInProgress" class="import-review-actions">
        <el-button v-if="!job.preview && !commitOutcomeUnknown" :loading="jobActionLoading" @click="loadPreview">重新读取预览</el-button>
        <el-button v-if="commitOutcomeUnknown" :loading="jobActionLoading" @click="checkCommitStatus">检查任务状态</el-button>
        <el-button v-if="canWriteKnowledge && job.status === 'PARSED'" type="primary" :loading="jobActionLoading"
          :disabled="!job.preview || commitOutcomeUnknown" @click="handleCommitJob">确认入库</el-button>
      </div>
    </section>

    <div v-if="recoveryError && !job" role="alert" class="import-review">
      <p>{{ jobClientError }}</p>
      <el-button :loading="restoringJob" @click="restoreJob">重新恢复任务</el-button>
    </div>

    <LiquidGlassTable
      :empty="fileList.length === 0"
      :loading="filesLoading"
      :show-pagination="false"
      empty-description="还没有文件。上传 TXT、Markdown、Word、PDF 等文件后，平台会解析、切分并建立索引。"
    >
      <template #toolbar>
        <div class="toolbar">
          <div class="toolbar-hint">
            Markdown 和 Word 先按标题切成段落，单节超过 {{ kb?.chunkSize || 500 }} 字再按长度切；切分方式在「设置」里调整，改完后重新解析文件即可生效。
          </div>
          <el-upload
            v-if="canWriteKnowledge"
            :auto-upload="false"
            :show-file-list="false"
            :accept="acceptTypes"
            :disabled="uploading || jobInProgress || restoringJob || recoveryError"
            :on-change="handleUploadChange"
          >
            <LiquidGlassButton type="primary" :icon="Upload" :loading="uploading" :disabled="jobInProgress || restoringJob || recoveryError">上传文件</LiquidGlassButton>
          </el-upload>
        </div>
      </template>
      <el-table :data="fileList" stripe empty-text=" ">
        <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column label="解析器" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ documentProviderLabel(row.parseProvider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="段落" width="90" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <StatusTag :label="fileStatusText(row.status)" :tone="fileStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="上传时间" width="180" :formatter="formatTableDateTime" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="router.push(`/knowledge/${kbCode}/file/${row.fileId}`)">
              查看段落
            </el-button>
            <el-button
              v-if="canWriteKnowledge"
              type="warning"
              link
              size="small"
              :loading="reparsingId === row.fileId || isReparsingFile(row.fileId)"
              :disabled="jobInProgress && !isReparsingFile(row.fileId)"
              @click="handleReparse(row)"
            >
              重新解析
            </el-button>
            <el-popconfirm v-if="canWriteKnowledge" title="确定删除该文件及其段落和向量？" @confirm="handleDelete(row)">
              <template #reference><el-button type="danger" link size="small">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>
  </div>
</template>

<script setup lang="ts">
import { formatTableDateTime } from '@/utils/dateTime'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { UploadFile } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassTable } from '@/components/common/liquid-glass'
import StatusTag from '@/components/common/StatusTag.vue'
import DocumentImportJobCard from '@/components/DocumentImportJobCard.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import { deleteFile, getKbFiles, reparseFile } from '@/api/knowledge'
import {
  cancelDocumentImportJob,
  commitDocumentImportJob,
  getDocumentImportJob,
  retryDocumentImportJob,
  submitDocumentImportJob,
} from '@/api/import'
import type { FileInfo, KnowledgeBase } from '@/types/knowledge'
import type { DocumentImportJob } from '@/types/import'
import { isSupportedFile } from '@/utils'
import {
  documentProviderLabel,
  isAbortError,
  isDocumentJobTerminal,
  pollDocumentImportJob,
  validateDocumentFileSize,
} from '@/utils/documentImport'
import { chunkStrategyForSplitType } from './knowledgeDetailModel'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_KNOWLEDGE_WRITE } from '@/auth/platformAccess'
import { platformSessionId } from '@/auth/platformSession'
const { can } = usePlatformCan()
const canWriteKnowledge = computed(() => can(PLATFORM_PERMISSION_KNOWLEDGE_WRITE))

const props = withDefaults(defineProps<{
  kbCode: string
  kb: KnowledgeBase | null
  active?: boolean
}>(), { active: true })

const emit = defineEmits<{
  changed: []
}>()

const router = useRouter()
const acceptTypes = '.txt,.md,.markdown,.csv,.doc,.docx,.pdf,.pptx,.xlsx,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.webp'

const fileList = ref<FileInfo[]>([])
const filesLoading = ref(false)
const uploading = ref(false)
const reparsingId = ref<string | null>(null)
const job = ref<DocumentImportJob | null>(null)
const jobKind = ref<'upload' | 'reparse'>('upload')
const jobActionLoading = ref(false)
const jobClientError = ref('')
const commitOutcomeUnknown = ref(false)
const commitInProgress = ref(false)
const restoringJob = ref(true)
const recoveryError = ref(false)
const previewLimit = ref(20)
const pollingController = ref<AbortController | null>(null)
let reviewGeneration = 0
let disposed = false
watch([() => props.active, platformSessionId], () => { reviewGeneration += 1 }, { flush: 'sync' })

const jobInProgress = computed(() => !!job.value && !isDocumentJobTerminal(job.value.status))
const jobTitle = computed(() => jobKind.value === 'reparse' ? '重新解析' : '上传文件')

function fileStatusText(status: number) {
  return ({ 0: '处理中', 1: '可检索', 2: '失败' } as Record<number, string>)[status] || '未知'
}

function fileStatusTone(status: number): StatusTone {
  return ({ 0: 'warning', 1: 'success', 2: 'danger' } as Record<number, StatusTone>)[status] || 'info'
}

async function fetchFiles() {
  filesLoading.value = true
  try {
    const { data } = await getKbFiles(props.kbCode)
    fileList.value = Array.isArray(data) ? data : []
  } finally {
    filesLoading.value = false
  }
}

async function handleUploadChange(uploadFile: UploadFile) {
  if (restoringJob.value || recoveryError.value) return
  const file = uploadFile.raw
  if (!file) return
  if (!isSupportedFile(file.name)) {
    ElMessage.warning('不支持的文件格式，请上传文本、Office、PDF 或图片/扫描件')
    return
  }
  const sizeError = validateDocumentFileSize(file)
  if (sizeError) {
    ElMessage.warning(sizeError)
    return
  }
  uploading.value = true
  jobClientError.value = ''
  try {
    const { data } = await submitDocumentImportJob({
      file,
      knowledgeBaseCode: props.kbCode,
      chunkStrategy: chunkStrategyForSplitType(props.kb?.splitType),
      chunkSize: props.kb?.chunkSize || 500,
      chunkOverlap: props.kb?.chunkOverlap ?? 50,
      autoCommit: false,
    })
    jobKind.value = 'upload'
    const submitted = data as unknown as DocumentImportJob
    setJob(submitted)
    uploading.value = false
    await trackJob(submitted)
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = errorMessage(error, '上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleReparse(row: FileInfo) {
  if (jobInProgress.value) {
    ElMessage.warning('请先等待当前任务完成或取消该任务')
    return
  }
  reparsingId.value = row.fileId
  jobClientError.value = ''
  try {
    const { data } = await reparseFile(row.fileId)
    jobKind.value = 'reparse'
    const submitted = data as unknown as DocumentImportJob
    setJob(submitted)
    await trackJob(submitted)
  } finally {
    reparsingId.value = null
  }
}

async function handleRetryJob() {
  const current = job.value
  if (!current) return
  jobActionLoading.value = true
  jobClientError.value = ''
  stopPolling()
  try {
    const { data } = await retryDocumentImportJob(current.jobId)
    const retried = data as unknown as DocumentImportJob
    setJob(retried)
    jobActionLoading.value = false
    await trackJob(retried)
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = errorMessage(error, '重试失败')
  } finally {
    jobActionLoading.value = false
  }
}

async function loadPreview() {
  const current = job.value
  if (!current || current.status !== 'PARSED') return
  jobActionLoading.value = true
  jobClientError.value = ''
  try {
    const refreshed = await fetchJob(current.jobId, true)
    if (disposed || job.value?.jobId !== current.jobId) return
    setJob(refreshed)
    if (refreshed.status === 'PARSED' && !refreshed.preview) {
      jobClientError.value = '无法读取解析预览，请稍后重试。'
    }
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = errorMessage(error, '无法读取解析预览')
  } finally {
    jobActionLoading.value = false
  }
}

async function handleCommitJob() {
  const current = job.value
  if (!canWriteKnowledge.value || !current?.preview || current.knowledgeBaseCode !== props.kbCode || current.status !== 'PARSED' || commitOutcomeUnknown.value || jobActionLoading.value) return
  jobActionLoading.value = true
  jobClientError.value = ''
  const generation = reviewGeneration
  let commitSent = false
  try {
    const refreshed = await fetchJob(current.jobId, true)
    if (disposed || !props.active || generation !== reviewGeneration || !canWriteKnowledge.value
      || job.value?.jobId !== current.jobId || props.kbCode !== current.knowledgeBaseCode) return
    if (refreshed.status !== 'PARSED' || refreshed.fileId !== current.fileId
      || refreshed.knowledgeBaseCode !== current.knowledgeBaseCode
      || JSON.stringify(refreshed.preview) !== JSON.stringify(current.preview)) {
      setJob(refreshed)
      jobClientError.value = '文件状态或解析结果已变化，请核对后重新确认。'
      return
    }
    commitInProgress.value = true
    // Persist uncertainty before sending: a refresh or lost response must not invite a duplicate commit.
    commitOutcomeUnknown.value = true
    storeJob(current.jobId)
    commitSent = true
    const { data } = await commitDocumentImportJob(current.jobId)
    const submitted = data as unknown as DocumentImportJob
    if (job.value?.jobId !== current.jobId) return
    setJob(submitted)
    jobActionLoading.value = false
    await trackJob(submitted, true)
  } catch (error) {
    if (!isAbortError(error)) {
      if (commitSent) {
        const status = (error as { response?: { status?: number } })?.response?.status
        if (status === 401 || status === 403) {
          commitOutcomeUnknown.value = false
          storeJob(current.jobId)
          jobClientError.value = '当前账号没有入库权限或登录已失效，请检查账号权限后重试。'
        } else {
          commitOutcomeUnknown.value = true
          jobClientError.value = '入库结果未确认，请检查任务状态，不要重复提交。'
        }
      } else {
        jobClientError.value = errorMessage(error, '无法核对解析结果，请稍后重试。')
      }
    }
  } finally {
    jobActionLoading.value = false
    commitInProgress.value = false
  }
}

async function checkCommitStatus() {
  const current = job.value
  if (!current) return
  jobActionLoading.value = true
  try {
    const refreshed = await fetchJob(current.jobId, false)
    if (job.value?.jobId !== current.jobId) return
    if (refreshed.status !== 'PARSED') {
      if (isDocumentJobTerminal(refreshed.status)) commitOutcomeUnknown.value = false
      jobClientError.value = ''
      setJob(refreshed)
      commitInProgress.value = !isDocumentJobTerminal(refreshed.status)
      await trackJob(refreshed, true)
    } else {
      setJob(refreshed)
      jobClientError.value = '入库结果仍未确认，请稍后再次检查任务状态。'
    }
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = '无法检查入库状态，请稍后重试。'
  } finally {
    jobActionLoading.value = false
    commitInProgress.value = false
  }
}

async function handleCancelJob() {
  const current = job.value
  if (!current) return
  try {
    await ElMessageBox.confirm(
      `确认取消“${current.fileName}”的${jobTitle.value}任务？已在线的文件不会被删除。`,
      '取消任务',
      { confirmButtonText: '确认取消', cancelButtonText: '继续处理', type: 'warning' },
    )
  } catch {
    return
  }
  jobActionLoading.value = true
  stopPolling()
  try {
    await cancelDocumentImportJob(current.jobId)
    job.value = { ...current, status: 'CANCELLED', stage: 'CANCELLED' }
    clearStoredJob()
    ElMessage.success('任务已取消')
  } catch (error) {
    jobClientError.value = errorMessage(error, '无法取消任务')
  } finally {
    jobActionLoading.value = false
  }
}

function resumeJob() {
  if (!job.value) return
  jobClientError.value = ''
  void trackJob(job.value)
}

function dismissJob() {
  stopPolling()
  job.value = null
  commitOutcomeUnknown.value = false
  jobClientError.value = ''
  clearStoredJob()
}

async function trackJob(current: DocumentImportJob, waitForCommit = false) {
  if (disposed) return
  stopPolling()
  const controller = new AbortController()
  pollingController.value = controller
  try {
    const result = await pollDocumentImportJob({
      jobId: current.jobId,
      fetchJob,
      signal: controller.signal,
      stopWhen: (candidate) => candidate.status === 'COMPLETED' || (!waitForCommit && candidate.status === 'PARSED' && !candidate.autoCommit),
      onUpdate: setJob,
    })
    setJob(result)
    if (result.status === 'PARSED' && !result.autoCommit && !waitForCommit) {
      await loadPreview()
    } else if (result.status === 'COMPLETED') {
      commitOutcomeUnknown.value = false
      ElMessage.success(jobKind.value === 'reparse' ? '文件已重新解析并替换' : '文件已入库，可以检索了')
      await fetchFiles()
      emit('changed')
    } else if (result.status === 'FAILED') {
      commitOutcomeUnknown.value = false
      setJob(result)
      ElMessage.error(result.errorMessage || `处理失败（${result.errorCode || 'FAILED'}）`)
    } else if (result.status === 'CANCELLED') {
      commitOutcomeUnknown.value = false
    }
  } catch (error) {
    if (!isAbortError(error)) {
      if (waitForCommit) {
        commitOutcomeUnknown.value = true
        jobClientError.value = '入库结果未确认，请检查任务状态，不要重复提交。'
      } else {
        jobClientError.value = errorMessage(error, '无法刷新任务进度')
      }
    }
  } finally {
    if (pollingController.value === controller) pollingController.value = null
  }
}

async function fetchJob(jobId: string, includePreview: boolean): Promise<DocumentImportJob> {
  const { data } = await getDocumentImportJob(jobId, includePreview)
  return data as unknown as DocumentImportJob
}

function setJob(current: DocumentImportJob) {
  if (job.value?.jobId !== current.jobId) previewLimit.value = 20
  job.value = current
  if (current.status === 'COMPLETED' || current.status === 'CANCELLED') clearStoredJob()
  else storeJob(current.jobId)
}

function isReparsingFile(fileId: string): boolean {
  return jobInProgress.value && job.value?.replaceFileId === fileId
}

async function handleDelete(row: FileInfo) {
  await deleteFile(row.fileId)
  ElMessage.success('文件已删除')
  await fetchFiles()
  emit('changed')
}

function stopPolling() {
  pollingController.value?.abort()
  pollingController.value = null
}

function storageKey() {
  return `reachai:knowledge:file-job:${props.kbCode}`
}

function storeJob(jobId: string) {
  try {
    window.sessionStorage.setItem(storageKey(), `${jobKind.value}:${jobId}${commitOutcomeUnknown.value ? ':unknown' : ''}`)
  } catch {
    // Server-side job state stays authoritative.
  }
}

function clearStoredJob() {
  try {
    window.sessionStorage.removeItem(storageKey())
  } catch {
    // Ignore unavailable browser storage.
  }
}

async function restoreJob() {
  restoringJob.value = true
  recoveryError.value = false
  let stored = ''
  try {
    stored = window.sessionStorage.getItem(storageKey()) || ''
  } catch {
    restoringJob.value = false
    return
  }
  const [kind, jobId, commitState] = stored.split(':')
  if (!jobId) {
    restoringJob.value = false
    return
  }
  try {
    jobKind.value = kind === 'reparse' ? 'reparse' : 'upload'
    commitOutcomeUnknown.value = commitState === 'unknown'
    const current = await fetchJob(jobId, false)
    if (disposed) return
    if (current.knowledgeBaseCode !== props.kbCode) {
      clearStoredJob()
      jobClientError.value = '任务不属于当前知识库，请重新打开对应知识库。'
      return
    }
    if (isDocumentJobTerminal(current.status)) commitOutcomeUnknown.value = false
    setJob(current)
    if (commitOutcomeUnknown.value && current.status === 'PARSED') {
      jobClientError.value = '入库结果未确认，请检查任务状态，不要重复提交。'
    } else if (!isDocumentJobTerminal(current.status)) {
      await trackJob(current, commitOutcomeUnknown.value)
    }
  } catch (error) {
    recoveryError.value = true
    jobClientError.value = errorMessage(error, '无法恢复任务进度')
  } finally {
    restoringJob.value = false
  }
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

defineExpose({ fetchFiles })

onMounted(fetchFiles)
onMounted(restoreJob)
onBeforeUnmount(() => { disposed = true; reviewGeneration += 1; stopPolling() })
</script>

<style scoped lang="scss">
.knowledge-files-tab {
  display: grid;
  gap: var(--layout-section-gap);
}

.import-review {
  padding: var(--layout-section-gap);
  border: 1px solid var(--border-glass);
  border-radius: var(--el-border-radius-base);
  background: var(--bg-tertiary);
}

.import-review h3 { margin: 0 0 8px; }
.import-review p { color: var(--text-secondary); line-height: 1.6; }
.import-preview { max-height: 320px; overflow: auto; padding-left: 24px; }
.import-preview p { white-space: pre-wrap; overflow-wrap: anywhere; }
.import-review-actions { display: flex; flex-wrap: wrap; gap: 8px; }

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

@media (max-width: 640px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
