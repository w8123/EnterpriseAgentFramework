<template>
  <WorkbenchPage class="knowledge-import-page">
    <PageHeader
      variant="workbench"
      domain="knowledge"
      eyebrow="Knowledge Ingestion"
      title="文件入库"
      description="上传后由 Java Fast 或 Docling 解析；任务状态、失败重试和来源锚点全程可见。"
    />

    <section class="knowledge-import-grid"><el-row :gutter="20">
      <!-- 左侧：操作区域 -->
      <el-col :span="12">
        <!-- 选择知识库 -->
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-title">
              <el-icon><Collection /></el-icon>
              选择知识库
            </div>
          </template>
          <KnowledgeSelector v-model="importStore.knowledgeBaseCode" @change="onKnowledgeChange" />
        </el-card>

        <!-- 文件上传 -->
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-title">
              <el-icon><UploadFilled /></el-icon>
              文件上传
            </div>
          </template>
          <FileUploader ref="fileUploaderRef" @change="onFileChange" />
        </el-card>

        <DocumentImportJobCard
          :job="activeJob"
          :busy="jobActionLoading"
          :client-error="jobClientError"
          dismissible
          @refresh="resumeActiveJob"
          @retry="handleRetryJob"
          @cancel="handleCancelJob"
          @dismiss="handleDismissJob"
        />

        <!-- 切分策略配置 -->
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-title">
              <el-icon><Scissor /></el-icon>
              切分策略
            </div>
          </template>
          <ChunkStrategyForm v-model:config="importStore.chunkConfig" @change="onStrategyChange" />
        </el-card>

        <!-- 高级参数 -->
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-title">
              <el-icon><Setting /></el-icon>
              高级参数
            </div>
          </template>
          <AdvancedSettings v-model:params="importStore.extraParams" />
        </el-card>

        <!-- 入库操作 -->
        <ImportActions
          :loading="importStore.importLoading"
          :can-import="canImport"
          @import="handleImport"
          @reset="handleReset"
        />
      </el-col>

      <!-- 右侧：预览区域 -->
      <el-col :span="12">
        <el-card shadow="never" class="section-card preview-card">
          <template #header>
            <div class="card-title">
              <el-icon><View /></el-icon>
              Chunk 预览
              <el-tag v-if="importStore.totalChunks > 0" type="success" size="small" style="margin-left: 8px">
                {{ importStore.totalChunks }} 个
              </el-tag>
            </div>
          </template>
          <ChunkPreview
            :chunks="importStore.chunkPreview"
            :total-chunks="importStore.totalChunks"
            :loading="importStore.previewLoading"
          />
        </el-card>
      </el-col>
    </el-row></section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Collection,
  UploadFilled,
  Scissor,
  Setting,
  View,
} from '@element-plus/icons-vue'
import { useImportStore } from '@/store/import'
import {
  cancelDocumentImportJob,
  commitDocumentImportJob,
  getDocumentImportJob,
  retryDocumentImportJob,
  submitDocumentImportJob,
} from '@/api/import'
import type { DocumentImportJob } from '@/types/import'
import {
  canCancelDocumentJob,
  DOCUMENT_JOB_STORAGE_KEY,
  isAbortError,
  pollDocumentImportJob,
} from '@/utils/documentImport'

import KnowledgeSelector from '@/components/KnowledgeSelector.vue'
import FileUploader from '@/components/FileUploader.vue'
import ChunkStrategyForm from '@/components/ChunkStrategyForm.vue'
import AdvancedSettings from '@/components/AdvancedSettings.vue'
import ChunkPreview from '@/components/ChunkPreview.vue'
import ImportActions from '@/components/ImportActions.vue'
import DocumentImportJobCard from '@/components/DocumentImportJobCard.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'

const importStore = useImportStore()
const fileUploaderRef = ref<InstanceType<typeof FileUploader>>()
const activeJob = ref<DocumentImportJob | null>(null)
const previewGeneration = ref(0)
const pollingController = ref<AbortController | null>(null)
const jobActionLoading = ref(false)
const jobClientError = ref('')

/** 入库前置条件：同一持久任务已完成预览且尚未提交。 */
const canImport = computed(() => {
  return (
    activeJob.value?.status === 'PARSED' &&
    !activeJob.value.autoCommit &&
    importStore.chunkPreview.length > 0 &&
    !importStore.previewLoading &&
    !importStore.importLoading
  )
})

function onKnowledgeChange(code: string) {
  if (activeJob.value && activeJob.value.knowledgeBaseCode !== code && !importStore.file) {
    abandonCurrentJob()
    importStore.chunkPreview = []
    importStore.totalChunks = 0
    importStore.fileStatus = 'idle'
  }
  if (importStore.file) {
    void triggerPreview()
  }
}

function onFileChange(file: File | null) {
  importStore.setFile(file)
  if (file) {
    void triggerPreview()
  } else {
    cancelCurrentPreview()
  }
}

function onStrategyChange() {
  if (importStore.file) {
    void triggerPreview()
  }
}

/** Upload once, then wait for the same durable parse result used by commit. */
async function triggerPreview() {
  if (!importStore.file || !importStore.knowledgeBaseCode) return

  const generation = ++previewGeneration.value
  stopPolling()
  const previousJob = activeJob.value
  activeJob.value = null
  jobClientError.value = ''
  clearStoredJob()
  if (previousJob && canCancelDocumentJob(previousJob.status)) {
    try {
      await cancelDocumentImportJob(previousJob.jobId)
    } catch {
      // The previous job may already have completed; it is no longer the active preview.
    }
  }
  if (generation !== previewGeneration.value) return

  importStore.previewLoading = true
  importStore.fileStatus = 'previewing'
  importStore.chunkPreview = []
  importStore.totalChunks = 0

  try {
    const { data } = await submitDocumentImportJob({
      file: importStore.file,
      knowledgeBaseCode: importStore.knowledgeBaseCode,
      chunkStrategy: importStore.chunkConfig.chunkStrategy,
      chunkSize: importStore.chunkConfig.chunkSize,
      chunkOverlap: importStore.chunkConfig.chunkOverlap,
      extraParams: {
        tags: importStore.extraParams.tags,
        deptId: importStore.extraParams.deptId,
        overwrite: importStore.extraParams.overwrite,
      },
    })
    const job = data as unknown as DocumentImportJob
    if (generation !== previewGeneration.value) {
      await cancelDocumentImportJob(job.jobId).catch(() => undefined)
      return
    }
    setActiveJob(job)
    const result = await trackJob(job, generation)
    if (!result?.preview) return
    importStore.setPreviewResult(result.preview.chunks, result.preview.totalChunks)
    importStore.fileStatus = 'uploaded'
  } catch (error) {
    if (isAbortError(error) || generation !== previewGeneration.value) return
    importStore.fileStatus = 'error'
    importStore.chunkPreview = []
    importStore.totalChunks = 0
    const message = error instanceof Error ? error.message : '文档解析失败'
    jobClientError.value = message
  } finally {
    if (generation === previewGeneration.value) {
      importStore.previewLoading = false
    }
  }
}

/** 执行入库 */
async function handleImport() {
  const currentJob = activeJob.value
  if (!currentJob || currentJob.status !== 'PARSED' || importStore.chunkPreview.length === 0) {
    ElMessage.warning('请等待预览完成后再入库')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认将文件 "${currentJob.fileName}" 的 ${importStore.totalChunks} 个 Chunk 入库到 "${currentJob.knowledgeBaseCode}"？`,
      '确认入库',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  importStore.importLoading = true
  importStore.fileStatus = 'importing'

  try {
    const generation = ++previewGeneration.value
    stopPolling()
    const { data } = await commitDocumentImportJob(currentJob.jobId)
    const committed = data as unknown as DocumentImportJob
    setActiveJob(committed)
    const result = await trackJob(committed, generation)
    if (!result || result.status !== 'COMPLETED') return
    ElMessage.success(
      `入库成功！文件已由 ${result.providerType === 'DOCLING' ? 'Docling' : 'Java Fast'} 解析并完成索引。`,
    )
    importStore.fileStatus = 'done'
    clearStoredJob()
  } catch (error) {
    if (isAbortError(error)) return
    importStore.fileStatus = 'error'
    const message = error instanceof Error ? error.message : '文档入库失败'
    jobClientError.value = message
  } finally {
    importStore.importLoading = false
  }
}

/** 重置所有状态 */
function handleReset() {
  abandonCurrentJob()
  importStore.reset()
  fileUploaderRef.value?.reset()
}

function cancelCurrentPreview() {
  abandonCurrentJob()
}

async function handleRetryJob() {
  const job = activeJob.value
  if (!job) return
  jobActionLoading.value = true
  jobClientError.value = ''
  const generation = ++previewGeneration.value
  stopPolling()
  try {
    const { data } = await retryDocumentImportJob(job.jobId)
    const retried = data as unknown as DocumentImportJob
    setActiveJob(retried)
    jobActionLoading.value = false
    const result = await trackJob(retried, generation)
    if (result?.status === 'PARSED' && result.preview) {
      importStore.setPreviewResult(result.preview.chunks, result.preview.totalChunks)
      importStore.fileStatus = 'uploaded'
    } else if (result?.status === 'COMPLETED') {
      importStore.fileStatus = 'done'
      clearStoredJob()
      ElMessage.success('文档重试入库成功')
    }
  } catch (error) {
    if (!isAbortError(error)) jobClientError.value = errorMessage(error, '任务重试失败')
  } finally {
    jobActionLoading.value = false
  }
}

async function handleCancelJob() {
  const job = activeJob.value
  if (!job) return
  try {
    await ElMessageBox.confirm(
      `确认取消文档任务“${job.fileName}”？尚未入库的原件和解析工件将被清理。`,
      '取消文档任务',
      { confirmButtonText: '确认取消', cancelButtonText: '继续处理', type: 'warning' },
    )
  } catch {
    return
  }
  jobActionLoading.value = true
  previewGeneration.value += 1
  stopPolling()
  try {
    await cancelDocumentImportJob(job.jobId)
    activeJob.value = { ...job, status: 'CANCELLED', stage: 'CANCELLED' }
    importStore.fileStatus = 'idle'
    importStore.chunkPreview = []
    importStore.totalChunks = 0
    clearStoredJob()
    ElMessage.success('文档任务已取消')
  } catch (error) {
    jobClientError.value = errorMessage(error, '无法取消任务')
  } finally {
    jobActionLoading.value = false
  }
}

function handleDismissJob() {
  stopPolling()
  activeJob.value = null
  jobClientError.value = ''
  importStore.chunkPreview = []
  importStore.totalChunks = 0
  importStore.fileStatus = importStore.file ? 'uploaded' : 'idle'
  clearStoredJob()
}

function resumeActiveJob() {
  const job = activeJob.value
  if (!job) return
  jobClientError.value = ''
  const generation = ++previewGeneration.value
  void trackJob(job, generation).then((result) => {
    if (result?.status === 'PARSED' && result.preview) {
      importStore.setPreviewResult(result.preview.chunks, result.preview.totalChunks)
      importStore.fileStatus = 'uploaded'
    }
  })
}

async function trackJob(job: DocumentImportJob, generation: number): Promise<DocumentImportJob | null> {
  stopPolling()
  const controller = new AbortController()
  pollingController.value = controller
  try {
    const result = await pollDocumentImportJob({
      jobId: job.jobId,
      includePreview: !job.autoCommit,
      fetchJob,
      signal: controller.signal,
      stopWhen: (current) => current.autoCommit
        ? current.status === 'COMPLETED'
        : current.status === 'PARSED' && !!current.preview,
      onUpdate: (current) => {
        if (generation === previewGeneration.value) setActiveJob(current)
      },
    })
    if (generation !== previewGeneration.value) return null
    setActiveJob(result)
    if (result.status === 'FAILED') {
      importStore.fileStatus = 'error'
      ElMessage.error(result.errorMessage || `文档处理失败（${result.errorCode || 'FAILED'}）`)
    }
    if (result.status === 'CANCELLED') importStore.fileStatus = 'idle'
    return result
  } catch (error) {
    if (isAbortError(error) || generation !== previewGeneration.value) return null
    jobClientError.value = errorMessage(error, '无法刷新文档任务')
    return null
  } finally {
    if (pollingController.value === controller) pollingController.value = null
  }
}

async function fetchJob(jobId: string, includePreview: boolean): Promise<DocumentImportJob> {
  const { data } = await getDocumentImportJob(jobId, includePreview)
  return data as unknown as DocumentImportJob
}

function setActiveJob(job: DocumentImportJob) {
  activeJob.value = job
  if (job.status === 'COMPLETED' || job.status === 'CANCELLED') clearStoredJob()
  else storeJob(job.jobId)
}

function abandonCurrentJob() {
  const job = activeJob.value
  previewGeneration.value += 1
  stopPolling()
  activeJob.value = null
  jobClientError.value = ''
  clearStoredJob()
  if (job && canCancelDocumentJob(job.status)) {
    void cancelDocumentImportJob(job.jobId).catch(() => undefined)
  }
}

function stopPolling() {
  pollingController.value?.abort()
  pollingController.value = null
}

function storeJob(jobId: string) {
  try {
    window.sessionStorage.setItem(DOCUMENT_JOB_STORAGE_KEY, jobId)
  } catch {
    // Durable server state still works when browser storage is unavailable.
  }
}

function clearStoredJob() {
  try {
    window.sessionStorage.removeItem(DOCUMENT_JOB_STORAGE_KEY)
  } catch {
    // Ignore unavailable browser storage.
  }
}

async function restoreActiveJob() {
  let jobId = ''
  try {
    jobId = window.sessionStorage.getItem(DOCUMENT_JOB_STORAGE_KEY) || ''
  } catch {
    return
  }
  if (!jobId) return
  try {
    let job = await fetchJob(jobId, false)
    if (!job.autoCommit && job.status === 'PARSED') job = await fetchJob(jobId, true)
    setActiveJob(job)
    importStore.knowledgeBaseCode = job.knowledgeBaseCode
    if (job.status === 'PARSED' && job.preview) {
      importStore.setPreviewResult(job.preview.chunks, job.preview.totalChunks)
      importStore.fileStatus = 'uploaded'
      return
    }
    if (job.status === 'FAILED' || job.status === 'CANCELLED' || job.status === 'COMPLETED') return
    const generation = ++previewGeneration.value
    const result = await trackJob(job, generation)
    if (result?.status === 'PARSED' && result.preview) {
      importStore.setPreviewResult(result.preview.chunks, result.preview.totalChunks)
      importStore.fileStatus = 'uploaded'
    }
  } catch (error) {
    jobClientError.value = errorMessage(error, '无法恢复文档任务')
  }
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

onMounted(restoreActiveJob)
onBeforeUnmount(stopPolling)
</script>

<style scoped lang="scss">

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
  color: var(--text-primary);
}

.preview-card {
  position: sticky;
  top: 24px;

  :deep(.el-card__body) {
    padding: 16px 20px;
  }
}
</style>
