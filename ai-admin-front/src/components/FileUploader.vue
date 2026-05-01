<template>
  <div class="file-uploader">
    <el-upload
      ref="uploadRef"
      drag
      :auto-upload="false"
      :limit="1"
      :accept="acceptTypes"
      :on-change="handleFileChange"
      :on-remove="handleFileRemove"
      :on-exceed="handleExceed"
      :file-list="fileList"
      class="upload-dragger"
    >
      <el-icon class="el-icon--upload" :size="48"><UploadFilled /></el-icon>
      <div class="el-upload__text">
        拖拽文件到此处，或 <em>点击上传</em>
      </div>
      <template #tip>
        <div class="el-upload__tip">
          支持 doc / docx / pdf 格式，单文件上传
        </div>
      </template>
    </el-upload>

    <div v-if="currentFile" class="file-info">
      <el-tag type="success" effect="plain">
        <el-icon><Document /></el-icon>
        {{ currentFile.name }}
        <span class="file-size">（{{ formatFileSize(currentFile.size) }}）</span>
      </el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { UploadFile, UploadInstance, UploadRawFile } from 'element-plus'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document } from '@element-plus/icons-vue'
import { isSupportedFile, formatFileSize } from '@/utils'

const emit = defineEmits<{
  change: [file: File | null]
}>()

const uploadRef = ref<UploadInstance>()
const currentFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])

const acceptTypes = '.doc,.docx,.pdf'

function handleFileChange(uploadFile: UploadFile) {
  const rawFile = uploadFile.raw
  if (!rawFile) return

  if (!isSupportedFile(rawFile.name)) {
    ElMessage.warning('不支持的文件格式，请上传 doc / docx / pdf 文件')
    fileList.value = []
    return
  }

  currentFile.value = rawFile
  emit('change', rawFile)
}

function handleFileRemove() {
  currentFile.value = null
  fileList.value = []
  emit('change', null)
}

function handleExceed() {
  ElMessage.warning('仅支持单文件上传，请先移除已选文件')
}

/** 供外部重置状态 */
function reset() {
  uploadRef.value?.clearFiles()
  currentFile.value = null
  fileList.value = []
}

defineExpose({ reset })
</script>

<style scoped lang="scss">
.file-uploader {
  width: 100%;
}

.upload-dragger {
  width: 100%;

  :deep(.el-upload) {
    width: 100%;
  }

  :deep(.el-upload-dragger) {
    width: 100%;
    padding: 32px 0;
  }
}

.file-info {
  margin-top: 12px;

  .file-size {
    color: #909399;
    font-size: 12px;
  }
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .file-info .file-size {
    color: #94a3b8;
  }
}
</style>
