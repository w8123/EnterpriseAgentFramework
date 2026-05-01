<template>
  <div class="page-container">
    <div class="page-header">
      <h2>检索测试</h2>
    </div>

    <!-- 搜索面板 -->
    <el-card shadow="never" class="section-card">
      <el-form :model="searchForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="16">
            <el-form-item label="查询内容">
              <el-input
                v-model="searchForm.query"
                type="textarea"
                :rows="3"
                placeholder="请输入要检索的内容..."
                @keydown.ctrl.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="知识库">
              <el-select
                v-model="searchForm.knowledgeBaseCodes"
                multiple
                clearable
                placeholder="全部知识库"
                style="width: 100%"
              >
                <el-option
                  v-for="kb in knowledgeList"
                  :key="kb.code"
                  :label="kb.name"
                  :value="kb.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="TopK">
              <el-input-number v-model="searchForm.topK" :min="1" :max="50" style="width: 100%" />
            </el-form-item>
            <el-form-item label="分数阈值">
              <el-slider v-model="searchForm.scoreThreshold" :min="0" :max="1" :step="0.05"
                show-input :show-input-controls="false" input-size="small" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :loading="searching" @click="handleSearch"
            :disabled="!searchForm.query.trim()">
            <el-icon><Search /></el-icon>
            开始检索
          </el-button>
          <el-button @click="resetForm">重置</el-button>
          <span v-if="result" class="search-meta">
            共 {{ result.totalResults }} 条结果，耗时 {{ result.costMs }}ms
          </span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 检索结果 -->
    <el-card v-if="result && result.items.length > 0" shadow="never" class="section-card">
      <template #header>
        <span>检索结果</span>
      </template>
      <div class="result-list">
        <div v-for="(item, idx) in result.items" :key="item.chunkId" class="result-item">
          <div class="result-header">
            <div class="result-rank">#{{ idx + 1 }}</div>
            <div class="result-meta">
              <el-tag size="small" effect="plain">{{ item.knowledgeBaseCode }}</el-tag>
              <span class="file-name">{{ item.fileName }}</span>
              <span class="chunk-index" v-if="item.chunkIndex !== null">Chunk #{{ item.chunkIndex }}</span>
            </div>
            <div class="result-score">
              <el-tag :type="scoreTagType(item.score)" size="small">
                相似度: {{ (item.score * 100).toFixed(1) }}%
              </el-tag>
            </div>
          </div>
          <div class="result-content">
            <span v-if="expandedIds.has(idx)">{{ item.content }}</span>
            <span v-else>{{ truncate(item.content, 300) }}</span>
            <el-button
              v-if="item.content && item.content.length > 300"
              type="primary"
              link
              size="small"
              @click="toggleExpand(idx)"
            >
              {{ expandedIds.has(idx) ? '收起' : '展开全文' }}
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 空状态 -->
    <el-card v-if="result && result.items.length === 0" shadow="never" class="section-card">
      <el-empty description="未找到相关内容，请尝试调整查询或降低分数阈值" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { retrievalTest, getKnowledgeList } from '@/api/knowledge'
import type { KnowledgeBase, RetrievalTestResponse } from '@/types/knowledge'

const knowledgeList = ref<KnowledgeBase[]>([])
const searching = ref(false)
const result = ref<RetrievalTestResponse | null>(null)
const expandedIds = ref<Set<number>>(new Set())

const searchForm = reactive({
  query: '',
  knowledgeBaseCodes: [] as string[],
  topK: 5,
  scoreThreshold: 0.3,
})

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

function toggleExpand(idx: number) {
  if (expandedIds.value.has(idx)) {
    expandedIds.value.delete(idx)
  } else {
    expandedIds.value.add(idx)
  }
  expandedIds.value = new Set(expandedIds.value)
}

function scoreTagType(score: number): string {
  if (score >= 0.8) return 'success'
  if (score >= 0.6) return ''
  if (score >= 0.4) return 'warning'
  return 'danger'
}

async function handleSearch() {
  if (!searchForm.query.trim()) {
    ElMessage.warning('请输入查询内容')
    return
  }
  searching.value = true
  expandedIds.value = new Set()
  try {
    const { data } = await retrievalTest({
      query: searchForm.query,
      knowledgeBaseCodes: searchForm.knowledgeBaseCodes.length > 0
        ? searchForm.knowledgeBaseCodes : undefined,
      topK: searchForm.topK,
      scoreThreshold: searchForm.scoreThreshold,
    })
    result.value = data.data
  } finally {
    searching.value = false
  }
}

function resetForm() {
  searchForm.query = ''
  searchForm.knowledgeBaseCodes = []
  searchForm.topK = 5
  searchForm.scoreThreshold = 0.3
  result.value = null
  expandedIds.value = new Set()
}

async function fetchKnowledgeList() {
  try {
    const { data } = await getKnowledgeList()
    knowledgeList.value = data.data || []
  } catch {
    // 错误已在拦截器中处理
  }
}

onMounted(() => {
  fetchKnowledgeList()
})
</script>

<style scoped lang="scss">
.search-meta {
  margin-left: 16px;
  font-size: 13px;
  color: #64748b;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  padding: 16px;
  background: var(--bg-tertiary);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  transition: border-color 0.2s;

  &:hover {
    border-color: #409eff;
  }
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.result-rank {
  font-size: 14px;
  font-weight: 700;
  color: #409eff;
  min-width: 28px;
}

.result-meta {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-name {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
}

.chunk-index {
  font-size: 12px;
  color: #64748b;
}

.result-score {
  flex-shrink: 0;
}

.result-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
  word-break: break-all;
  white-space: pre-wrap;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .search-meta,
  .chunk-index {
    color: #94a3b8;
  }

  .result-item {
    border: 1px solid #ebeef5;
  }
}
</style>
