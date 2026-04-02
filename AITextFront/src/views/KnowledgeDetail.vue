<template>
  <div class="page-container">
    <!-- 顶部：返回 + 标题 -->
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="router.push('/knowledge')">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <h2>{{ kbInfo?.name || '知识库详情' }}</h2>
        <el-tag effect="plain" size="small" style="margin-left: 8px">{{ kbCode }}</el-tag>
      </div>
    </div>

    <!-- Chunk 策略配置 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <span>Chunk 策略配置</span>
          <el-button type="primary" size="small" :loading="configSaving" @click="handleSaveConfig">
            保存配置
          </el-button>
        </div>
      </template>
      <el-form :model="configForm" label-width="120px" inline>
        <el-form-item label="切分策略">
          <el-select v-model="configForm.splitType" style="width: 180px">
            <el-option label="固定长度 (FIXED)" value="FIXED" />
            <el-option label="段落切分 (PARAGRAPH)" value="PARAGRAPH" />
            <el-option label="语义切分 (SEMANTIC)" value="SEMANTIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="Chunk 大小">
          <el-input-number v-model="configForm.chunkSize" :min="100" :max="4000" :step="100" />
        </el-form-item>
        <el-form-item label="重叠大小">
          <el-input-number v-model="configForm.chunkOverlap" :min="0" :max="1000" :step="10" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 文件列表 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <span>文件列表（共 {{ fileList.length }} 个）</span>
          <el-button size="small" @click="fetchFiles" :loading="filesLoading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table v-loading="filesLoading" :data="fileList" stripe style="width: 100%">
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="fileType" label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.fileType || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件大小" width="120" align="right">
          <template #default="{ row }">
            {{ formatFileSize(row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="Chunk 数" width="100" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
              @click="router.push(`/knowledge/${kbCode}/file/${row.fileId}`)">
              查看详情
            </el-button>
            <el-button type="warning" link size="small"
              :loading="reparsingId === row.fileId"
              @click="handleReparse(row)">
              重新解析
            </el-button>
            <el-popconfirm
              title="确定删除此文件？关联的chunk和向量数据将同时清除。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button type="danger" link size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { getKbFiles, updateKbConfig, deleteFile, reparseFile, getKnowledgeList } from '@/api/knowledge'
import type { FileInfo, KnowledgeBase, KbConfig } from '@/types/knowledge'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string

const kbInfo = ref<KnowledgeBase | null>(null)
const fileList = ref<FileInfo[]>([])
const filesLoading = ref(false)
const configSaving = ref(false)
const reparsingId = ref<string | null>(null)

const configForm = reactive<KbConfig>({
  splitType: 'FIXED',
  chunkSize: 500,
  chunkOverlap: 50,
})

function formatFileSize(bytes: number | null): string {
  if (!bytes || bytes === 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(1)} ${units[i]}`
}

function statusText(status: number): string {
  const map: Record<number, string> = { 0: '解析中', 1: '完成', 2: '失败' }
  return map[status] ?? '未知'
}

function statusTagType(status: number): string {
  const map: Record<number, string> = { 0: 'warning', 1: 'success', 2: 'danger' }
  return map[status] ?? 'info'
}

async function fetchKbInfo() {
  try {
    const { data } = await getKnowledgeList()
    const list = data.data || []
    const found = list.find((kb: KnowledgeBase) => kb.code === kbCode)
    if (found) {
      kbInfo.value = found
      configForm.splitType = found.splitType || 'FIXED'
      configForm.chunkSize = found.chunkSize || 500
      configForm.chunkOverlap = found.chunkOverlap || 50
    }
  } catch {
    // 错误已在拦截器中处理
  }
}

async function fetchFiles() {
  filesLoading.value = true
  try {
    const { data } = await getKbFiles(kbCode)
    fileList.value = data.data || []
  } finally {
    filesLoading.value = false
  }
}

async function handleSaveConfig() {
  configSaving.value = true
  try {
    await updateKbConfig(kbCode, configForm)
    ElMessage.success('配置已保存')
  } finally {
    configSaving.value = false
  }
}

async function handleDelete(row: FileInfo) {
  try {
    await deleteFile(row.fileId)
    ElMessage.success('删除成功')
    await fetchFiles()
  } catch {
    // 错误已在拦截器中处理
  }
}

async function handleReparse(row: FileInfo) {
  reparsingId.value = row.fileId
  try {
    await reparseFile(row.fileId)
    ElMessage.success('重新解析已完成')
    await fetchFiles()
  } catch {
    // 错误已在拦截器中处理
  } finally {
    reparsingId.value = null
  }
}

onMounted(() => {
  fetchKbInfo()
  fetchFiles()
})
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;

  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    color: #1d2129;
  }
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
