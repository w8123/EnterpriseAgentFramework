<template>
  <div class="page-container">
    <!-- 顶部：返回 + 标题 -->
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="router.push(`/knowledge/${kbCode}`)">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <h2>文件详情</h2>
        <el-tag effect="plain" size="small" style="margin-left: 8px">{{ fileId }}</el-tag>
      </div>
    </div>

    <!-- Chunk 列表 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <span>Chunk 列表（共 {{ chunkList.length }} 个）</span>
          <el-button size="small" @click="fetchChunks" :loading="loading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="chunkList" stripe style="width: 100%">
        <el-table-column prop="chunkIndex" label="序号" width="80" align="center" />
        <el-table-column label="内容" min-width="400">
          <template #default="{ row }">
            <div class="chunk-content-cell">
              <span v-if="expandedIds.has(row.id)">{{ row.content }}</span>
              <span v-else>{{ truncate(row.content, 150) }}</span>
              <el-button
                v-if="row.content && row.content.length > 150"
                type="primary"
                link
                size="small"
                @click="toggleExpand(row.id)"
              >
                {{ expandedIds.has(row.id) ? '收起' : '展开' }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="length" label="长度" width="100" align="center" />
        <el-table-column label="向量 ID" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="vector-id-text">{{ row.vectorId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { getFileChunks } from '@/api/knowledge'
import type { ChunkDetail } from '@/types/knowledge'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string
const fileId = route.params.fileId as string

const chunkList = ref<ChunkDetail[]>([])
const loading = ref(false)
const expandedIds = ref<Set<number>>(new Set())

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

function toggleExpand(id: number) {
  if (expandedIds.value.has(id)) {
    expandedIds.value.delete(id)
  } else {
    expandedIds.value.add(id)
  }
  // 触发响应式更新
  expandedIds.value = new Set(expandedIds.value)
}

async function fetchChunks() {
  loading.value = true
  try {
    const { data } = await getFileChunks(fileId)
    chunkList.value = data.data || []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchChunks()
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

.chunk-content-cell {
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
  word-break: break-all;
  white-space: pre-wrap;
}

.vector-id-text {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  color: #909399;
}
</style>
