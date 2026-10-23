<template>
  <div class="knowledge-files-tab">
    <DocumentImportJobCard
      :job="job"
      :title="jobTitle"
      :busy="jobActionLoading"
      :client-error="jobClientError"
      dismissible
      @refresh="resumeJob"
      @retry="handleRetryJob"
      @cancel="handleCancelJob"
      @dismiss="dismissJob"
    />

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
            :auto-upload="false"
            :show-file-list="false"
            :accept="acceptTypes"
            :disabled="uploading || jobInProgress"
            :on-change="handleUploadChange"
          >
            <LiquidGlassButton type="primary" :icon="Upload" :loading="uploading" :disabled="jobInProgress">上传文件</LiquidGlassButton>
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
              type="warning"
              link
              size="small"
              :loading="reparsingId === row.fileId || isReparsingFile(row.fileId)"
              :disabled="jobInProgress && !isReparsingFile(row.fileId)"
              @click="handleReparse(row)"
            >
              重新解析
            </el-button>
            <el-popconfirm title="确定删除该文件及其段落和向量？" @confirm="handleDelete(row)">
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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

const props = defineProps<{
  kbCode: string
  kb: KnowledgeBase | null
}>()

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
const pollingController = ref<AbortController | null>(null)

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
      autoCommit: true,
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
  jobClientError.value = ''
  clearStoredJob()
}

async function trackJob(current: DocumentImportJob) {
  stopPolling()
  const controller = new AbortController()
  pollingController.value = controller
  try {
    const result = await pollDocumentImportJob({
      jobId: current.jobId,
      fetchJob,
      signal: controller.signal,
      stopWhen: (candidate) => candidate.status === 'COMPLETED',
      onUpdate: setJob,
    })
    setJob(result)
    if (result.status === 'COMPLETED') {
      ElMessage.success(jobKind.value === 'reparse' ? '文件已重新解析并替换' : '文件已入库，可以检索了')
      await fetchFiles()
      emit('changed')
    } else if (result.status === 'FAILED') {
      ElMessage.error(result.errorMessage || `处理失败（${result.errorCode || 'FAILED'}）`)
    }
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = errorMessage(error, '无法刷新任务进度')
  } finally {
    if (pollingController.value === controller) pollingController.value = null
  }
}

async function fetchJob(jobId: string, includePreview: boolean): Promise<DocumentImportJob> {
  const { data } = await getDocumentImportJob(jobId, includePreview)
  return data as unknown as DocumentImportJob
}

function setJob(current: DocumentImportJob) {
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
    window.sessionStorage.setItem(storageKey(), `${jobKind.value}:${jobId}`)
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
  let stored = ''
  try {
    stored = window.sessionStorage.getItem(storageKey()) || ''
  } catch {
    return
  }
  const [kind, jobId] = stored.split(':')
  if (!jobId) return
  try {
    jobKind.value = kind === 'reparse' ? 'reparse' : 'upload'
    const current = await fetchJob(jobId, false)
    setJob(current)
    if (!isDocumentJobTerminal(current.status)) await trackJob(current)
  } catch (error) {
    jobClientError.value = errorMessage(error, '无法恢复任务进度')
  }
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

defineExpose({ fetchFiles })

onMounted(fetchFiles)
onMounted(restoreJob)
onBeforeUnmount(stopPolling)
</script>

<style scoped lang="scss">
.knowledge-files-tab {
  display: grid;
  gap: var(--layout-section-gap);
}

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
