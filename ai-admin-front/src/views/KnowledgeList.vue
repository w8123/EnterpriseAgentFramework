<template>
  <WorkbenchPage class="knowledge-list-page" layout="list">
    <PageHeader
      variant="overview"
      domain="knowledge"
      eyebrow="Knowledge Hub"
      title="知识库管理"
      description="统一管理知识源、向量化模型、文件资产与检索状态。"
    >
      <template #summary>
        <HeaderMiniStat label="知识库总数" :value="knowledgeStore.knowledgeList.length" hint="统一知识源" />
        <HeaderMiniStat label="文件总数" :value="totalFileCount" hint="已纳入检索" />
      </template>
      <template #actions>
        <ViewToggle v-model="viewMode" />
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建知识库</el-button>
      </template>
    </PageHeader>

    <DataTableShell
      class="project-list-table-shell knowledge-list-surface workbench-list-surface"
      :class="{ 'project-list-card-mode': viewMode === 'card' }"
      density="compact"
      :loading="knowledgeStore.loading"
      :empty="filteredKnowledgeList.length === 0"
      :empty-description="emptyDescription"
      :current-page="currentPage"
      :page-size="pageSize"
      :total="filteredKnowledgeList.length"
      :page-sizes="[10, 20, 50]"
      pagination-layout="total, prev, pager, next, sizes"
      @update:current-page="handlePageChange"
      @update:page-size="handlePageSizeChange"
    >
      <template #toolbar>
        <FilterBar
          class="project-list-filter-bar"
          density="compact"
          :loading="knowledgeStore.loading"
          query-label="搜索"
          @query="handleSearch"
          @reset="resetFilters"
        >
          <el-form-item label="关键词">
            <el-input
              v-model="keyword"
              clearable
              :prefix-icon="Search"
              placeholder="搜索知识库名称、编码或描述"
              @keyup.enter="handleSearch"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="statusFilter" clearable placeholder="知识库状态">
              <el-option label="已启用" value="enabled" />
              <el-option label="已禁用" value="disabled" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型配置">
            <el-select v-model="embeddingFilter" clearable placeholder="Embedding 配置">
              <el-option label="已配置" value="configured" />
              <el-option label="未配置" value="unconfigured" />
            </el-select>
          </el-form-item>
        </FilterBar>
      </template>

      <!-- 卡片视图保留，并与列表共享筛选、分页和空状态。 -->
      <div v-if="viewMode === 'card'" class="project-list-card-grid">
        <article
          v-for="kb in pagedKnowledgeList"
          :key="kb.code"
          class="project-list-card"
          @click="router.push(`/knowledge/${kb.code}`)"
        >
          <div class="project-list-card__header">
            <div class="project-list-card__icon">
              <el-icon :size="20"><Collection /></el-icon>
            </div>
            <div class="project-list-card__identity">
              <h3>{{ kb.name }}</h3>
              <code>{{ kb.code }}</code>
            </div>
            <el-tag :type="kb.status === 1 ? 'success' : 'danger'" size="small" effect="light">
              {{ kb.status === 1 ? '已启用' : '已禁用' }}
            </el-tag>
          </div>

          <p class="project-list-card__description">{{ kb.description || '暂无描述' }}</p>

          <div class="project-list-card__metrics">
            <span>
              <strong>{{ kb.fileCount ?? 0 }}</strong>
              <small>文件</small>
            </span>
            <span>
              <strong>{{ kb.chunkCount ?? 0 }}</strong>
              <small>知识片段</small>
            </span>
          </div>

          <div class="project-list-card__meta">
            <el-icon><Coin /></el-icon>
            <span>Embedding</span>
            <strong>{{ kb.embeddingModelInstanceId || '未配置模型实例' }}</strong>
          </div>

          <div class="project-list-card__footer">
            <el-button link type="primary" size="small" @click.stop="router.push(`/knowledge/${kb.code}`)">详情</el-button>
            <el-button link type="primary" size="small" @click.stop="openEditDialog(kb)">编辑</el-button>
            <el-popconfirm
              title="确定删除此知识库？所有关联数据将被清除。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(kb.code)"
            >
              <template #reference>
                <el-button link type="danger" size="small" @click.stop>删除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </article>
      </div>

      <el-table
        v-else
        :data="pagedKnowledgeList"
        row-key="code"
        style="width: 100%"
      >
        <el-table-column prop="name" label="名称" min-width="160">
          <template #default="{ row }">
            <el-button type="primary" link @click="router.push(`/knowledge/${row.code}`)">
              {{ row.name }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" min-width="140">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.code }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="embeddingModelInstanceId" label="Embedding 实例" min-width="160">
          <template #default="{ row }">
            {{ row.embeddingModelInstanceId || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="fileCount" label="文件数" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.fileCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
              @click="router.push(`/knowledge/${row.code}`)">
              详情
            </el-button>
            <el-button type="primary" link size="small" @click="openEditDialog(row)">
              编辑
            </el-button>
            <el-popconfirm
              title="确定删除此知识库？所有关联数据将被清除。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(row.code)"
            >
              <template #reference>
                <el-button type="danger" link size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <template #empty>
        <div class="project-list-empty-state">
          <img src="/智能化.svg" alt="" />
          <div class="project-list-empty-copy">
            <h3>{{ emptyTitle }}</h3>
            <p>{{ emptyDescription }}</p>
          </div>
          <el-button v-if="hasActiveFilters" @click="resetFilters">重置筛选</el-button>
          <el-button v-else type="primary" :icon="Plus" @click="openCreateDialog">新建知识库</el-button>
        </div>
      </template>
    </DataTableShell>

    <!-- 新建 / 编辑弹窗 -->
    <GlassDialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑知识库' : '新建知识库'"
      eyebrow="知识资产"
      :description="isEdit ? '调整知识库基础信息与检索模型配置。' : '创建可用于文件入库、向量检索与智能问答的知识空间。'"
      :status="isEdit ? '编辑模式' : '新建模式'"
      class="knowledge-editor-dialog"
      width="760px"
      destroy-on-close
    >
      <template #icon>
        <el-icon><Collection /></el-icon>
      </template>

      <el-form
        ref="formRef"
        class="glass-dialog-form knowledge-editor-form"
        :model="form"
        :rules="formRules"
        label-position="top"
      >
        <section class="glass-dialog-section glass-surface-control">
          <GlassSectionHeader
            title="基础信息"
            description="定义知识库的名称、唯一编码与用途说明。"
            meta="3 项"
          >
            <template #icon><el-icon><EditPen /></el-icon></template>
          </GlassSectionHeader>

          <div class="glass-dialog-grid">
            <el-form-item label="知识库名称" prop="name">
              <el-input v-model="form.name" placeholder="例如：企业制度知识库" />
            </el-form-item>
            <el-form-item label="知识库编码" prop="code">
              <el-input
                v-model="form.code"
                placeholder="例如：kb_policy"
                :disabled="isEdit"
              />
            </el-form-item>
            <el-form-item class="is-wide" label="知识库描述" prop="description">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="3"
                resize="none"
                placeholder="简要说明知识范围、使用对象或典型检索场景（可选）"
              />
            </el-form-item>
          </div>
        </section>

        <section class="glass-dialog-section glass-surface-control">
          <GlassSectionHeader
            title="模型配置"
            description="选择向量化、重排与回答生成所使用的模型实例。"
            meta="3 项"
          >
            <template #icon><el-icon><Coin /></el-icon></template>
          </GlassSectionHeader>

          <div class="glass-dialog-grid">
            <el-form-item label="Embedding 实例" prop="embeddingModelInstanceId">
              <el-select
                v-model="form.embeddingModelInstanceId"
                filterable
                placeholder="请选择向量模型实例"
              >
                <el-option
                  v-for="item in embeddingInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="LLM 实例" prop="llmModelInstanceId">
              <el-select
                v-model="form.llmModelInstanceId"
                filterable
                placeholder="请选择回答生成模型实例"
              >
                <el-option
                  v-for="item in llmInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item class="is-wide" label="Rerank 实例（可选）">
              <el-select
                v-model="form.rerankModelInstanceId"
                clearable
                filterable
                placeholder="需要提升结果排序质量时，可选择 Reranker 模型实例"
              >
                <el-option
                  v-for="item in rerankInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </div>
        </section>
      </el-form>

      <template #hint>
        <el-icon><InfoFilled /></el-icon>
        Embedding 与 LLM 为必选项，创建后仍可继续调整。
      </template>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </GlassDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import GlassDialog from '@/components/common/GlassDialog.vue'
import GlassSectionHeader from '@/components/common/GlassSectionHeader.vue'
import { computed, ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Plus, Collection, Coin, Search, EditPen, InfoFilled } from '@element-plus/icons-vue'
import ViewToggle from '@/components/ViewToggle.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import HeaderMiniStat from '@/components/common/HeaderMiniStat.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import { useKnowledgeStore } from '@/store/knowledge'
import { createKnowledge, updateKnowledge, deleteKnowledge } from '@/api/knowledge'
import { getModelInstances } from '@/api/model'
import type { KnowledgeBase, KnowledgeBaseForm } from '@/types/knowledge'
import type { ModelInstance } from '@/types/model'

const router = useRouter()
const knowledgeStore = useKnowledgeStore()
const viewMode = ref<'table' | 'card'>('table')
const keyword = ref('')
const statusFilter = ref<'' | 'enabled' | 'disabled'>('')
const embeddingFilter = ref<'' | 'configured' | 'unconfigured'>('')
const appliedKeyword = ref('')
const appliedStatusFilter = ref<'' | 'enabled' | 'disabled'>('')
const appliedEmbeddingFilter = ref<'' | 'configured' | 'unconfigured'>('')
const currentPage = ref(1)
const pageSize = ref(10)
const totalFileCount = computed(() =>
  knowledgeStore.knowledgeList.reduce((total, item) => total + (item.fileCount ?? 0), 0),
)
const hasActiveFilters = computed(() =>
  Boolean(appliedKeyword.value || appliedStatusFilter.value || appliedEmbeddingFilter.value),
)
const filteredKnowledgeList = computed(() => {
  const normalizedKeyword = appliedKeyword.value.trim().toLocaleLowerCase()
  return knowledgeStore.knowledgeList.filter((item) => {
    const matchesKeyword = !normalizedKeyword || [item.name, item.code, item.description]
      .filter(Boolean)
      .some((value) => String(value).toLocaleLowerCase().includes(normalizedKeyword))
    const matchesStatus = !appliedStatusFilter.value
      || (appliedStatusFilter.value === 'enabled' ? item.status === 1 : item.status !== 1)
    const hasEmbedding = Boolean(item.embeddingModelInstanceId)
    const matchesEmbedding = !appliedEmbeddingFilter.value
      || (appliedEmbeddingFilter.value === 'configured' ? hasEmbedding : !hasEmbedding)
    return matchesKeyword && matchesStatus && matchesEmbedding
  })
})
const pagedKnowledgeList = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredKnowledgeList.value.slice(start, start + pageSize.value)
})
const emptyTitle = computed(() => hasActiveFilters.value ? '没有匹配的知识库' : '还没有知识库')
const emptyDescription = computed(() => hasActiveFilters.value
  ? '换个关键词或筛选条件，再试一次。'
  : '创建知识库，统一管理文件资产、向量模型与检索配置。',
)

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const formRef = ref<FormInstance>()
const embeddingInstances = ref<ModelInstance[]>([])
const rerankInstances = ref<ModelInstance[]>([])
const llmInstances = ref<ModelInstance[]>([])

const form = reactive<KnowledgeBaseForm>({
  name: '',
  code: '',
  description: '',
  embeddingModelInstanceId: '',
  rerankModelInstanceId: '',
  llmModelInstanceId: '',
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入知识库编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '编码只能包含字母、数字和下划线，且以字母开头', trigger: 'blur' },
  ],
  embeddingModelInstanceId: [{ required: true, message: '请选择 Embedding 模型实例', trigger: 'change' }],
  llmModelInstanceId: [{ required: true, message: '请选择 LLM 模型实例', trigger: 'change' }],
}

function resetForm() {
  form.name = ''
  form.code = ''
  form.description = ''
  form.embeddingModelInstanceId = ''
  form.rerankModelInstanceId = ''
  form.llmModelInstanceId = ''
}

function openCreateDialog() {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row: KnowledgeBase) {
  isEdit.value = true
  form.name = row.name
  form.code = row.code
  form.description = row.description || ''
  form.embeddingModelInstanceId = row.embeddingModelInstanceId || ''
  form.rerankModelInstanceId = row.rerankModelInstanceId || ''
  form.llmModelInstanceId = row.llmModelInstanceId || ''
  dialogVisible.value = true
}

function handleSearch() {
  appliedKeyword.value = keyword.value.trim()
  appliedStatusFilter.value = statusFilter.value
  appliedEmbeddingFilter.value = embeddingFilter.value
  currentPage.value = 1
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  embeddingFilter.value = ''
  appliedKeyword.value = ''
  appliedStatusFilter.value = ''
  appliedEmbeddingFilter.value = ''
  currentPage.value = 1
}

function handlePageChange(page: number) {
  currentPage.value = page
}

function handlePageSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
}

async function fetchEmbeddingInstances() {
  const { data } = await getModelInstances({ modelType: 'EMBEDDING' })
  embeddingInstances.value = data?.data ?? (Array.isArray(data) ? data : [])
}

async function fetchRerankInstances() {
  const { data } = await getModelInstances({ modelType: 'RERANKER' })
  rerankInstances.value = data?.data ?? (Array.isArray(data) ? data : [])
}

async function fetchLlmInstances() {
  const { data } = await getModelInstances({ modelType: 'LLM' })
  llmInstances.value = data?.data ?? (Array.isArray(data) ? data : [])
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateKnowledge(form)
      ElMessage.success('更新成功')
    } else {
      await createKnowledge(form)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await knowledgeStore.fetchList()
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(code: string) {
  try {
    await deleteKnowledge(code)
    ElMessage.success('删除成功')
    await knowledgeStore.fetchList()
  } catch {
    // 错误已在拦截器中处理
  }
}

watch(viewMode, () => {
  currentPage.value = 1
})

watch(
  () => filteredKnowledgeList.value.length,
  (total) => {
    const lastPage = Math.max(1, Math.ceil(total / pageSize.value))
    if (currentPage.value > lastPage) currentPage.value = lastPage
  },
)

onMounted(() => {
  knowledgeStore.fetchList()
  fetchEmbeddingInstances()
  fetchRerankInstances()
  fetchLlmInstances()
})
</script>

<style scoped lang="scss">
.knowledge-list-surface {
  margin-bottom: 0;
}
</style>
