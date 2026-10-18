<template>
  <div class="knowledge-retrieval-tab">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="visibility-alert"
      :title="endUserVisibilityHint(kb?.accessMode)"
    />
    <el-form :model="form" label-position="top" @submit.prevent>
      <el-form-item label="用户会怎么问">
        <el-input
          v-model="form.query"
          type="textarea"
          :rows="3"
          placeholder="输入一个真实用户问题，例如：电子产品签收后多少天可以无理由退货？（Ctrl + Enter 检索）"
          @keydown.ctrl.enter="handleSearch"
        />
      </el-form-item>
      <div class="form-actions">
        <el-button type="primary" :loading="searching" :disabled="!form.query.trim()" @click="handleSearch">
          <el-icon><Search /></el-icon>
          检索
        </el-button>
        <el-switch v-model="form.recordHit" active-text="记入命中分析" />
        <span class="settings-hint">
          按本库设置检索：{{ searchModeLabel(kb?.searchMode) }}，返回 {{ kb?.topK || 5 }} 条，相似度不低于 {{ kb?.similarityThreshold ?? 0.5 }}
        </span>
        <span v-if="result" class="search-meta">找到 {{ result.totalResults }} 条，耗时 {{ result.costMs }} ms</span>
      </div>
    </el-form>

    <div v-if="result && result.items.length > 0" class="result-list">
      <div v-for="(item, idx) in result.items" :key="`${item.chunkId}-${idx}`" class="result-item">
        <div class="result-header">
          <strong>#{{ idx + 1 }}</strong>
          <span class="file-name">{{ item.fileName || item.fileId }}</span>
          <StatusTag :label="`相似度 ${formatPercent(item.score)}`" :tone="scoreTone(item.score)" />
        </div>
        <div class="score-row">
          <span>向量 {{ formatScore(item.vectorScore) }}</span>
          <span>关键词 {{ formatScore(item.keywordScore) }}</span>
          <span>重排 {{ formatScore(item.rerankScore) }}</span>
          <span v-if="item.reason">{{ item.reason }}</span>
        </div>
        <div class="result-content">
          {{ expanded.has(idx) ? item.content : truncate(item.content, 320) }}
          <el-button v-if="item.content?.length > 320" type="primary" link size="small" @click="toggleExpand(idx)">
            {{ expanded.has(idx) ? '收起' : '展开全文' }}
          </el-button>
        </div>
      </div>
    </div>
    <el-empty
      v-else-if="result"
      description="没有找到相关段落。终端用户问这个问题时，知识问答会回复「资料里没有」。"
    />
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import { retrievalTest } from '@/api/knowledge'
import type { KnowledgeBase, RetrievalTestResponse } from '@/types/knowledge'
import { endUserVisibilityHint } from './knowledgeDetailModel'

const props = defineProps<{
  kbCode: string
  kb: KnowledgeBase | null
}>()

const emit = defineEmits<{
  recorded: []
}>()

const form = reactive({ query: '', recordHit: false })
const searching = ref(false)
const result = ref<RetrievalTestResponse | null>(null)
const expanded = ref<Set<number>>(new Set())

function searchModeLabel(mode?: string) {
  return ({ vector: '向量检索', keyword: '关键词检索', hybrid: '混合检索' } as Record<string, string>)[mode || 'hybrid'] || '混合检索'
}

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

function scoreTone(score: number): StatusTone {
  if (score >= 0.8) return 'success'
  if (score >= 0.5) return 'warning'
  return 'danger'
}

function toggleExpand(idx: number) {
  const next = new Set(expanded.value)
  if (next.has(idx)) next.delete(idx)
  else next.add(idx)
  expanded.value = next
}

async function handleSearch() {
  if (!form.query.trim()) return ElMessage.warning('请输入问题')
  searching.value = true
  expanded.value = new Set()
  try {
    const { data } = await retrievalTest({
      query: form.query.trim(),
      knowledgeBaseCodes: [props.kbCode],
      recordHit: form.recordHit,
    })
    result.value = data as unknown as RetrievalTestResponse
    if (form.recordHit) emit('recorded')
  } finally {
    searching.value = false
  }
}
</script>

<style scoped lang="scss">
.visibility-alert {
  margin-bottom: 12px;
}

.form-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 16px;
}

.settings-hint,
.search-meta {
  font-size: 12px;
  color: var(--text-muted);
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-subtle);
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
