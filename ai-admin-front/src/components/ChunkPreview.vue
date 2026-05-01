<template>
  <div class="chunk-preview">
    <!-- 统计摘要 -->
    <div v-if="chunks.length > 0" class="preview-summary">
      <el-tag effect="plain" type="info">
        共 <strong>{{ totalChunks }}</strong> 个 Chunk
      </el-tag>
      <el-tag effect="plain" type="info" style="margin-left: 8px">
        平均长度 <strong>{{ averageLength }}</strong> 字符
      </el-tag>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="preview-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 空状态 -->
    <el-empty
      v-else-if="chunks.length === 0"
      description="上传文件后自动预览切分结果"
      :image-size="120"
    />

    <!-- Chunk 列表 -->
    <div v-else class="chunk-list">
      <div
        v-for="chunk in chunks"
        :key="chunk.index"
        class="chunk-item"
      >
        <div class="chunk-header">
          <span class="chunk-index">
            <el-tag size="small" type="primary" effect="plain">
              # {{ chunk.index }}
            </el-tag>
          </span>
          <span class="chunk-length">{{ chunk.length }} 字符</span>
        </div>
        <div class="chunk-content">{{ chunk.content }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChunkItem } from '@/types/import'

const props = defineProps<{
  chunks: ChunkItem[]
  totalChunks: number
  loading: boolean
}>()

const averageLength = computed(() => {
  if (props.chunks.length === 0) return 0
  const total = props.chunks.reduce((sum, c) => sum + c.length, 0)
  return Math.round(total / props.chunks.length)
})
</script>

<style scoped lang="scss">
.chunk-preview {
  width: 100%;
}

.preview-summary {
  margin-bottom: 16px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.preview-loading {
  padding: 20px 0;
}

.chunk-list {
  max-height: 520px;
  overflow-y: auto;
  padding-right: 4px;
}

.chunk-item {
  padding: 12px 16px;
  margin-bottom: 8px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-glass);
  border-radius: 6px;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:hover {
    border-color: rgba(99, 102, 241, 0.4);
    box-shadow: 0 2px 8px rgba(99, 102, 241, 0.12);
  }
}

.chunk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.chunk-length {
  font-size: 12px;
  color: var(--text-muted);
}

.chunk-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
  word-break: break-all;
  white-space: pre-wrap;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .chunk-item:hover {
    border-color: #409eff;
    box-shadow: 0 2px 8px rgba(64, 158, 255, 0.08);
  }
}
</style>
