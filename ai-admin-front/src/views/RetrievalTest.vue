<template>
  <div class="page-container">
    <div class="page-header">
      <h2>召回测试实验室</h2>
    </div>

    <el-card shadow="never" class="section-card">
      <el-form :model="searchForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="15">
            <el-form-item label="查询内容">
              <el-input v-model="searchForm.query" type="textarea" :rows="4" placeholder="输入一个真实用户问题，观察向量、关键词、混合排序与 reranker 结果" @keydown.ctrl.enter="handleSearch" />
            </el-form-item>
          </el-col>
          <el-col :span="9">
            <el-form-item label="知识库">
              <el-select v-model="searchForm.knowledgeBaseCodes" multiple clearable placeholder="全部知识库" style="width: 100%">
                <el-option v-for="kb in knowledgeList" :key="kb.code" :label="kb.name" :value="kb.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="检索模式">
              <el-segmented v-model="searchForm.searchMode" :options="searchModeOptions" />
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12"><el-form-item label="TopK"><el-input-number v-model="searchForm.topK" :min="1" :max="50" style="width: 100%" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="记录命中"><el-switch v-model="searchForm.recordHit" /></el-form-item></el-col>
            </el-row>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8"><el-form-item label="分数阈值"><el-slider v-model="searchForm.scoreThreshold" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="向量权重"><el-slider v-model="searchForm.vectorWeight" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="关键词权重"><el-slider v-model="searchForm.keywordWeight" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8"><el-form-item label="Reranker"><el-switch v-model="searchForm.rerankEnabled" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="直接返回"><el-switch v-model="searchForm.directReturnEnabled" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="直返阈值"><el-slider v-model="searchForm.directReturnThreshold" :min="0" :max="1" :step="0.05" show-input /></el-form-item></el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :loading="searching" :disabled="!searchForm.query.trim()" @click="handleSearch">
            <el-icon><Search /></el-icon>
            开始测试
          </el-button>
          <el-button @click="resetForm">重置</el-button>
          <span v-if="result" class="search-meta">共 {{ result.totalResults }} 条结果，耗时 {{ result.costMs }}ms</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="result?.directReturn" type="success" show-icon class="direct-alert" title="已触发直接返回">
      <template #default>{{ truncate(result.directReturnContent || '', 220) }}</template>
    </el-alert>

    <el-card v-if="result && result.items.length > 0" shadow="never" class="section-card">
      <template #header>召回结果解释</template>
      <div class="result-list">
        <div v-for="(item, idx) in result.items" :key="`${item.chunkId}-${idx}`" class="result-item">
          <div class="result-header">
            <strong>#{{ idx + 1 }}</strong>
            <el-tag size="small" effect="plain">{{ item.knowledgeBaseCode }}</el-tag>
            <span class="file-name">{{ item.fileName || item.fileId }}</span>
            <el-tag v-if="item.directReturn" type="success" size="small">直接返回</el-tag>
            <el-tag :type="scoreTagType(item.score)" size="small">综合 {{ formatPercent(item.score) }}</el-tag>
          </div>
          <div class="score-row">
            <span>向量 {{ formatScore(item.vectorScore) }}</span>
            <span>关键词 {{ formatScore(item.keywordScore) }}</span>
            <span>Rerank {{ formatScore(item.rerankScore) }}</span>
            <span>{{ item.reason }}</span>
          </div>
          <div class="result-content">
            {{ expandedIds.has(idx) ? item.content : truncate(item.content, 320) }}
            <el-button v-if="item.content?.length > 320" type="primary" link size="small" @click="toggleExpand(idx)">
              {{ expandedIds.has(idx) ? '收起' : '展开全文' }}
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <el-card v-if="result && result.items.length === 0" shadow="never" class="section-card">
      <el-empty description="未找到相关内容。若开启了记录命中，这次查询会进入低置信/未命中分析。" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getKnowledgeList, retrievalTest } from '@/api/knowledge'
import type { KnowledgeBase, RetrievalTestResponse } from '@/types/knowledge'

const route = useRoute()
const knowledgeList = ref<KnowledgeBase[]>([])
const searching = ref(false)
const result = ref<RetrievalTestResponse | null>(null)
const expandedIds = ref<Set<number>>(new Set())

const searchModeOptions = [{ label: '混合', value: 'hybrid' }, { label: '向量', value: 'vector' }, { label: '关键词', value: 'keyword' }]

const searchForm = reactive({
  query: '',
  knowledgeBaseCodes: [] as string[],
  topK: 5,
  scoreThreshold: 0.3,
  searchMode: 'hybrid',
  rerankEnabled: true,
  directReturnEnabled: true,
  directReturnThreshold: 0.9,
  vectorWeight: 0.7,
  keywordWeight: 0.3,
  recordHit: true,
})

function truncate(text: string, maxLen: number) {
  if (!text) return ''
  return text.length > maxLen ? `${text.slice(0, maxLen)}...` : text
}

function formatScore(score?: number) {
  return typeof score === 'number' ? score.toFixed(3) : '-'
}

function formatPercent(score?: number) {
  return typeof score === 'number' ? `${(score * 100).toFixed(1)}%` : '-'
}

function scoreTagType(score: number) {
  if (score >= 0.8) return 'success'
  if (score >= 0.5) return 'warning'
  return 'danger'
}

function toggleExpand(idx: number) {
  expandedIds.value.has(idx) ? expandedIds.value.delete(idx) : expandedIds.value.add(idx)
  expandedIds.value = new Set(expandedIds.value)
}

async function handleSearch() {
  if (!searchForm.query.trim()) return ElMessage.warning('请输入查询内容')
  searching.value = true
  expandedIds.value = new Set()
  try {
    const { data } = await retrievalTest({
      ...searchForm,
      knowledgeBaseCodes: searchForm.knowledgeBaseCodes.length ? searchForm.knowledgeBaseCodes : undefined,
    })
    result.value = data as unknown as RetrievalTestResponse
  } finally {
    searching.value = false
  }
}

function resetForm() {
  Object.assign(searchForm, { query: '', knowledgeBaseCodes: [], topK: 5, scoreThreshold: 0.3, searchMode: 'hybrid', rerankEnabled: true, directReturnEnabled: true, directReturnThreshold: 0.9, vectorWeight: 0.7, keywordWeight: 0.3, recordHit: true })
  result.value = null
  expandedIds.value = new Set()
}

async function fetchKnowledgeList() {
  const { data } = await getKnowledgeList()
  knowledgeList.value = Array.isArray(data) ? data : []
  const kb = route.query.kb as string | undefined
  if (kb) searchForm.knowledgeBaseCodes = [kb]
}

onMounted(fetchKnowledgeList)
</script>

<style scoped lang="scss">
.search-meta {
  margin-left: 16px;
  font-size: 13px;
  color: var(--text-secondary);
}

.direct-alert {
  margin-bottom: 14px;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  padding: 16px;
  border-radius: 8px;
  background: var(--bg-tertiary);
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.result-header,
.score-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.result-header {
  margin-bottom: 8px;
}

.score-row {
  margin-bottom: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}

.file-name {
  color: var(--text-secondary);
  font-size: 13px;
}

.result-content {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
}
</style>
