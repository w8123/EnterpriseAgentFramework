<template>
  <div class="page-container">
    <div class="page-header">
      <h2>文件入库</h2>
    </div>

    <el-row :gutter="20">
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
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Collection,
  UploadFilled,
  Scissor,
  Setting,
  View,
} from '@element-plus/icons-vue'
import { useImportStore } from '@/store/import'
import { previewChunks, importFile } from '@/api/import'

import KnowledgeSelector from '@/components/KnowledgeSelector.vue'
import FileUploader from '@/components/FileUploader.vue'
import ChunkStrategyForm from '@/components/ChunkStrategyForm.vue'
import AdvancedSettings from '@/components/AdvancedSettings.vue'
import ChunkPreview from '@/components/ChunkPreview.vue'
import ImportActions from '@/components/ImportActions.vue'

const importStore = useImportStore()
const fileUploaderRef = ref<InstanceType<typeof FileUploader>>()

/** 入库前置条件：已选知识库 + 已上传文件 + 已完成预览 */
const canImport = computed(() => {
  return (
    !!importStore.knowledgeBaseCode &&
    !!importStore.file &&
    importStore.chunkPreview.length > 0 &&
    !importStore.previewLoading &&
    !importStore.importLoading
  )
})

function onKnowledgeChange(_code: string) {
  // 切换知识库不影响已上传文件和预览
}

function onFileChange(file: File | null) {
  importStore.setFile(file)
  if (file) {
    triggerPreview()
  }
}

function onStrategyChange() {
  if (importStore.file) {
    triggerPreview()
  }
}

/** 触发 Chunk 预览 */
async function triggerPreview() {
  if (!importStore.file) return

  importStore.previewLoading = true
  importStore.chunkPreview = []
  importStore.totalChunks = 0

  try {
    const { data } = await previewChunks({
      file: importStore.file,
      chunkStrategy: importStore.chunkConfig.chunkStrategy,
      chunkSize: importStore.chunkConfig.chunkSize,
      chunkOverlap: importStore.chunkConfig.chunkOverlap,
    })
    const result = data.data
    importStore.setPreviewResult(result.chunks, result.totalChunks)
    importStore.fileStatus = 'uploaded'
  } catch {
    importStore.fileStatus = 'error'
    importStore.chunkPreview = []
    importStore.totalChunks = 0
  } finally {
    importStore.previewLoading = false
  }
}

/** 执行入库 */
async function handleImport() {
  if (!importStore.knowledgeBaseCode) {
    ElMessage.warning('请先选择知识库')
    return
  }
  if (!importStore.file) {
    ElMessage.warning('请先上传文件')
    return
  }
  if (importStore.chunkPreview.length === 0) {
    ElMessage.warning('请等待预览完成后再入库')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认将文件 "${importStore.fileName}" 的 ${importStore.totalChunks} 个 Chunk 入库到 "${importStore.knowledgeBaseCode}"？`,
      '确认入库',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  importStore.importLoading = true
  importStore.fileStatus = 'importing'

  try {
    const { data } = await importFile({
      file: importStore.file,
      knowledgeBaseCode: importStore.knowledgeBaseCode,
      chunkStrategy: importStore.chunkConfig.chunkStrategy,
      chunkSize: importStore.chunkConfig.chunkSize,
      chunkOverlap: importStore.chunkConfig.chunkOverlap,
      extraParams: {
        enableOcr: importStore.extraParams.enableOcr,
        tags: importStore.extraParams.tags,
        deptId: importStore.extraParams.deptId,
        overwrite: importStore.extraParams.overwrite,
      },
    })

    const result = data.data
    ElMessage.success(
      `入库成功！共 ${result.chunkCount} 个 Chunk，${result.vectorCount} 个向量`,
    )
    importStore.fileStatus = 'done'
  } catch {
    importStore.fileStatus = 'error'
  } finally {
    importStore.importLoading = false
  }
}

/** 重置所有状态 */
function handleReset() {
  importStore.reset()
  fileUploaderRef.value?.reset()
}
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
