<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="router.push(`/knowledge/${kbCode}`)">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div>
          <h2>文件段落</h2>
          <div class="subline">
            <el-tag effect="plain" size="small">{{ fileId }}</el-tag>
            <span>共 {{ chunkList.length }} 个段落</span>
          </div>
        </div>
      </div>
    </div>

    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header-row">
          <span>段落列表</span>
          <el-button size="small" @click="fetchChunks" :loading="loading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="chunkList" stripe style="width: 100%">
        <el-table-column prop="chunkIndex" label="序号" width="80" align="center" />
        <el-table-column label="内容" min-width="420">
          <template #default="{ row }">
            <div class="chunk-content-cell">
              <span v-if="expandedIds.has(row.id)">{{ row.content }}</span>
              <span v-else>{{ truncate(row.content, 180) }}</span>
              <el-button
                v-if="row.content && row.content.length > 180"
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
        <el-table-column prop="length" label="长度" width="90" align="center" />
        <el-table-column prop="hitCount" label="命中" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.hitCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 0 ? 'info' : 'success'" size="small">
              {{ row.enabled === 0 ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="向量 ID" width="220" show-overflow-tooltip>
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
  return text.length > maxLen ? `${text.slice(0, maxLen)}...` : text
}

function toggleExpand(id: number) {
  if (expandedIds.value.has(id)) {
    expandedIds.value.delete(id)
  } else {
    expandedIds.value.add(id)
  }
  expandedIds.value = new Set(expandedIds.value)
}

async function fetchChunks() {
  loading.value = true
  try {
    const { data } = await getFileChunks(fileId)
    chunkList.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

onMounted(fetchChunks)
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

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chunk-content-cell {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
  word-break: break-word;
  white-space: pre-wrap;
}

.vector-id-text {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  color: #64748b;
}

:global([data-theme="light"]) {
  .vector-id-text {
    color: #94a3b8;
  }
}
</style>
