<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="router.push(`/knowledge/${kbCode}`)">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div>
          <h2>文档段落运营</h2>
          <div class="subline">
            <el-tag effect="plain" size="small">{{ fileId }}</el-tag>
            <span>{{ chunkList.length }} 个段落</span>
            <span>{{ enabledCount }} 个启用</span>
          </div>
        </div>
      </div>
      <el-button size="small" @click="fetchChunks" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索段落内容或标题" class="search-input">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-segmented v-model="enabledFilter" :options="filterOptions" />
    </div>

    <el-table v-loading="loading" :data="filteredChunks" stripe style="width: 100%">
      <el-table-column prop="chunkIndex" label="#" width="72" align="center" />
      <el-table-column label="段落" min-width="460">
        <template #default="{ row }">
          <div class="chunk-title">{{ row.title || `段落 ${row.chunkIndex}` }}</div>
          <div class="chunk-content">
            {{ expandedIds.has(row.id) ? row.content : truncate(row.content, 220) }}
            <el-button v-if="row.content?.length > 220" type="primary" link size="small" @click="toggleExpand(row.id)">
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
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled !== 0"
            :loading="togglingId === row.id"
            @change="(val: boolean) => handleToggle(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="向量" width="160" align="center">
        <template #default="{ row }">
          <el-button type="primary" link size="small" :loading="reembeddingId === row.id" @click="handleReembed(row)">
            重建向量
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" title="编辑段落" width="720px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" placeholder="可选，用于运营识别" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editForm.content" type="textarea" :rows="10" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editEnabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh, Search } from '@element-plus/icons-vue'
import { getFileChunks, reembedChunk, toggleChunk, updateChunk } from '@/api/knowledge'
import type { ChunkDetail } from '@/types/knowledge'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string
const fileId = route.params.fileId as string

const chunkList = ref<ChunkDetail[]>([])
const loading = ref(false)
const saving = ref(false)
const togglingId = ref<number | null>(null)
const reembeddingId = ref<number | null>(null)
const expandedIds = ref<Set<number>>(new Set())
const keyword = ref('')
const enabledFilter = ref<'all' | 'enabled' | 'disabled'>('all')
const editVisible = ref(false)
const editingId = ref<number | null>(null)
const editEnabled = ref(true)
const editForm = reactive({ title: '', content: '' })

const filterOptions = [
  { label: '全部', value: 'all' },
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' },
]

const enabledCount = computed(() => chunkList.value.filter((item) => item.enabled !== 0).length)

const filteredChunks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return chunkList.value.filter((item) => {
    if (enabledFilter.value === 'enabled' && item.enabled === 0) return false
    if (enabledFilter.value === 'disabled' && item.enabled !== 0) return false
    if (!kw) return true
    return `${item.title || ''} ${item.content || ''}`.toLowerCase().includes(kw)
  })
})

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? `${text.slice(0, maxLen)}...` : text
}

function toggleExpand(id: number) {
  expandedIds.value.has(id) ? expandedIds.value.delete(id) : expandedIds.value.add(id)
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

function openEdit(row: ChunkDetail) {
  editingId.value = row.id
  editForm.title = row.title || ''
  editForm.content = row.content || ''
  editEnabled.value = row.enabled !== 0
  editVisible.value = true
}

async function handleSave() {
  if (!editingId.value || !editForm.content.trim()) {
    ElMessage.warning('段落内容不能为空')
    return
  }
  saving.value = true
  try {
    await updateChunk(editingId.value, {
      title: editForm.title,
      content: editForm.content,
      enabled: editEnabled.value ? 1 : 0,
    })
    ElMessage.success('段落已保存')
    editVisible.value = false
    await fetchChunks()
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: ChunkDetail, val: boolean) {
  togglingId.value = row.id
  try {
    await toggleChunk(row.id, val ? 1 : 0)
    row.enabled = val ? 1 : 0
  } finally {
    togglingId.value = null
  }
}

async function handleReembed(row: ChunkDetail) {
  reembeddingId.value = row.id
  try {
    await reembedChunk(row.id)
    ElMessage.success('向量已重建')
  } finally {
    reembeddingId.value = null
  }
}

onMounted(fetchChunks)
</script>

<style scoped lang="scss">
.header-left,
.subline,
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-left h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.subline {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.toolbar {
  justify-content: space-between;
  margin-bottom: 14px;
}

.search-input {
  max-width: 360px;
}

.chunk-title {
  margin-bottom: 6px;
  font-weight: 600;
  color: var(--text-primary);
}

.chunk-content {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
