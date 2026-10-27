<template>
  <LiquidGlassPage class="knowledge-list-page">
    <PageHeader
      variant="overview"
      presentation="plain"
      action-presentation="inline"
      domain="knowledge"
      title="知识库管理"
      description="管理知识文件和检索配置，为 Agent 与 Workflow 提供参考资料。"
    >
      <template #actions>
        <LiquidGlassViewToggle v-model="viewMode" source="wallpaper" />
        <LiquidGlassButton v-if="canWriteKnowledge" source="wallpaper" type="primary" :icon="Plus" @click="openCreateDialog">新建知识库</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassTable
      class="knowledge-list-surface workbench-list-surface"
      :body-layout="viewMode === 'card' ? 'cards' : 'table'"
      pagination-label="知识库分页"
      density="compact"
      :loading="knowledgeStore.loading"
      :empty="filteredKnowledgeList.length === 0"
      :empty-description="emptyDescription"
      :current-page="currentPage"
      :page-size="pageSize"
      :total="filteredKnowledgeList.length"
      :page-sizes="[10, 20, 50]"
      @update:current-page="handlePageChange"
      @update:page-size="handlePageSizeChange"
    >
      <template #toolbar>
        <LiquidGlassFilterBar
          density="compact"
          :loading="knowledgeStore.loading"
          query-label="搜索"
          @query="handleSearch"
          @reset="resetFilters"
        >
          <LiquidGlassInput
            v-model="keyword"
            clearable
            :prefix-icon="Search"
            placeholder="搜索知识库名称、编码或描述"
            aria-label="关键词"
          />
          <LiquidGlassSelect v-model="statusFilter" clearable placeholder="知识库状态" aria-label="知识库状态">
            <el-option label="已启用" value="enabled" />
            <el-option label="已禁用" value="disabled" />
          </LiquidGlassSelect>
          <LiquidGlassSelect v-model="embeddingFilter" clearable placeholder="向量模型配置" aria-label="向量模型配置">
            <el-option label="已配置" value="configured" />
            <el-option label="未配置" value="unconfigured" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>

      <!-- 卡片视图：极简高级液态玻璃（Liquid Glass）架构 -->
      <div v-if="viewMode === 'card'" class="knowledge-card-grid">
        <article
          v-for="kb in pagedKnowledgeList"
          :key="kb.code"
          class="kb-liquid-card glass-surface-frost"
          @click="router.push(`/knowledge/${kb.code}`)"
        >
          <!-- 液态极光微光晕 -->
          <div class="kb-liquid-card__ambient">
            <span class="ambient-orb ambient-orb--violet" />
            <span class="ambient-orb ambient-orb--cyan" />
          </div>

          <!-- 顶沿微光棱镜高光条 -->
          <div class="kb-liquid-card__prism-edge" />

          <!-- 卡片头部：水晶图标 + 标题/编码 + 状态微标 -->
          <div class="kb-liquid-card__header">
            <div class="kb-liquid-card__avatar">
              <el-icon :size="20" class="avatar-icon"><Collection /></el-icon>
            </div>
            <div class="kb-liquid-card__identity">
              <h3 class="kb-liquid-card__title" :title="kb.name">{{ kb.name }}</h3>
              <div
                class="kb-code-sub"
                title="点击复制知识库编码"
                @click.stop="copyKbCode(kb.code, $event)"
              >
                <span>{{ kb.code }}</span>
                <el-icon class="copy-icon"><CopyDocument /></el-icon>
              </div>
            </div>
            <div class="kb-liquid-card__status">
              <span
                class="kb-status-pill"
                :class="{ 'is-active': kb.status === 1, 'is-disabled': kb.status !== 1 }"
              >
                <span class="kb-status-pill__dot" />
                <span>{{ kb.status === 1 ? '已启用' : '已禁用' }}</span>
              </span>
            </div>
          </div>

          <!-- 知识库描述：自然纯净展示 -->
          <p class="kb-liquid-card__description" :class="{ 'is-empty': !kb.description }">
            {{ kb.description || '暂无描述信息，点击进入详情可补充业务说明。' }}
          </p>

          <!-- 核心资产：单行通透内联微晶指标（Inline Glass Stats） -->
          <div class="kb-liquid-card__stats-row">
            <span class="stat-chip">
              <el-icon><Document /></el-icon>
              <strong>{{ kb.fileCount ?? 0 }}</strong>
              <small>文件</small>
            </span>
            <span class="stat-chip">
              <el-icon><Files /></el-icon>
              <strong>{{ kb.chunkCount ?? 0 }}</strong>
              <small>段落</small>
            </span>
            <span
              class="stat-chip stat-chip--agent"
              :class="{ 'is-connected': agentSummaryBadge(kb.code).connected }"
              :title="usageSummary(usageFor(usages, kb.code))"
              @click.stop="canAttachAgent && !agentSummaryBadge(kb.code).connected && openAttachDialog(kb)"
            >
              <el-icon><Connection /></el-icon>
              <span>{{ agentSummaryBadge(kb.code).label }}</span>
            </span>
          </div>

          <!-- 底部工具栏：时间 + 检索测试 + 更多操作 -->
          <div class="kb-liquid-card__footer" @click.stop>
            <span class="meta-time" :title="`创建时间：${kb.createTime || '-'}`">
              {{ formatRelativeTime(kb.updateTime || kb.createTime) }}
            </span>

            <div class="action-buttons">
              <button
                type="button"
                class="liquid-action-btn"
                @click="router.push({ path: `/knowledge/${kb.code}`, query: { tab: 'retrieval' } })"
              >
                <el-icon><Search /></el-icon>
                <span>检索测试</span>
              </button>

              <el-dropdown trigger="click" @command="handleDropdownCommand($event, kb)">
                <button type="button" class="liquid-action-btn liquid-action-btn--more" aria-label="更多操作">
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu class="kb-card-dropdown">
                    <el-dropdown-item command="detail">
                      <el-icon><Right /></el-icon>
                      <span>查看详情</span>
                    </el-dropdown-item>
                    <el-dropdown-item command="files">
                      <el-icon><Document /></el-icon>
                      <span>文件管理</span>
                    </el-dropdown-item>
                    <el-dropdown-item v-if="canAttachAgent" command="attach">
                      <el-icon><Connection /></el-icon>
                      <span>接入 Agent</span>
                    </el-dropdown-item>
                    <el-dropdown-item command="settings">
                      <el-icon><Setting /></el-icon>
                      <span>知识库设置</span>
                    </el-dropdown-item>
                    <el-dropdown-item v-if="canWriteKnowledge" command="delete" divided class="is-danger">
                      <el-icon><Delete /></el-icon>
                      <span>删除知识库</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
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
        <el-table-column prop="fileCount" label="文件" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.fileCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="段落" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.chunkCount ?? 0 }}</el-tag>
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
        <el-table-column prop="createTime" label="创建时间" width="180" :formatter="formatTableDateTime" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
              @click="router.push(`/knowledge/${row.code}`)">
              详情
            </el-button>
            <el-popconfirm
              v-if="canWriteKnowledge"
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
          <el-button v-else-if="canWriteKnowledge" type="primary" :icon="Plus" @click="openCreateDialog">新建知识库</el-button>
        </div>
      </template>
    </LiquidGlassTable>

    <!-- 新建弹窗 -->
    <GlassDialog
      v-model="dialogVisible"
      title="新建知识库"
      eyebrow="知识资产"
      description="创建可用于文件入库、向量检索与智能问答的知识空间。"
      status="新建模式"
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
            <el-form-item label="向量模型" prop="embeddingModelInstanceId">
              <el-select
                v-model="form.embeddingModelInstanceId"
                filterable
                placeholder="请选择向量模型实例"
                :loading="embeddingInstancesLoading"
                @visible-change="handleEmbeddingSelectVisible"
              >
                <el-option
                  v-for="item in embeddingInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="EMBEDDING"
                    :option-count="embeddingInstances.length"
                    :loading="embeddingInstancesLoading"
                    :load-error="embeddingInstancesLoadError"
                    @retry="fetchEmbeddingInstances"
                  />
                </template>
              </el-select>
            </el-form-item>
            <el-form-item label="回答模型" prop="llmModelInstanceId">
              <el-select
                v-model="form.llmModelInstanceId"
                filterable
                placeholder="请选择回答生成模型实例"
                :loading="llmInstancesLoading"
                @visible-change="handleLlmSelectVisible"
              >
                <el-option
                  v-for="item in llmInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="LLM"
                    :option-count="llmInstances.length"
                    :loading="llmInstancesLoading"
                    :load-error="llmInstancesLoadError"
                    @retry="fetchLlmInstances"
                  />
                </template>
              </el-select>
            </el-form-item>
            <el-form-item class="is-wide" label="重排模型（可选）">
              <el-select
                v-model="form.rerankModelInstanceId"
                clearable
                filterable
                placeholder="需要提升结果排序质量时，可选择重排模型"
                :loading="rerankInstancesLoading"
                @visible-change="handleRerankSelectVisible"
              >
                <el-option
                  v-for="item in rerankInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="RERANKER"
                    :option-count="rerankInstances.length"
                    :loading="rerankInstancesLoading"
                    :load-error="rerankInstancesLoadError"
                    @retry="fetchRerankInstances"
                  />
                </template>
              </el-select>
            </el-form-item>
          </div>
        </section>
      </el-form>

      <template #hint>
        <el-icon><InfoFilled /></el-icon>
        向量模型与回答模型为必选项，创建后仍可继续调整；更换向量模型需要重新解析已有文件。
      </template>
      <template #footer>
        <LiquidGlassButton @click="dialogVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="submitLoading" @click="handleSubmit">
          创建
        </LiquidGlassButton>
      </template>
    </GlassDialog>

    <!-- 快捷接入 Agent 弹窗 -->
    <KnowledgeAttachAgentDialog
      v-model="attachDialogVisible"
      :kb-code="targetAttachKb?.code || ''"
      :kb-name="targetAttachKb?.name || ''"
      @attached="handleAgentAttached"
    />
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { formatTableDateTime } from '@/utils/dateTime'
import GlassDialog from '@/components/common/GlassDialog.vue'
import GlassSectionHeader from '@/components/common/GlassSectionHeader.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import KnowledgeAttachAgentDialog from './knowledge/KnowledgeAttachAgentDialog.vue'
import { computed, ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePlatformCan } from '@/auth/usePlatformCan'
import {
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_KNOWLEDGE_WRITE,
} from '@/auth/platformAccess'
import { canLoadModelCatalog } from '@/auth/modelCatalogAccess'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus,
  Collection,
  Coin,
  Search,
  EditPen,
  InfoFilled,
  CopyDocument,
  Document,
  Files,
  Connection,
  MoreFilled,
  Right,
  Setting,
  Delete,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { LiquidGlassButton, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable, LiquidGlassViewToggle, LiquidGlassPage } from '@/components/common/liquid-glass'
import { useKnowledgeStore } from '@/store/knowledge'
import { createKnowledge, deleteKnowledge, getKnowledgeUsage } from '@/api/knowledge'
import { getModelInstances } from '@/api/model'
import type { KnowledgeBase, KnowledgeBaseForm, KnowledgeUsage } from '@/types/knowledge'
import { usageFor, usageSummary, agentNames } from './knowledge/knowledgeDetailModel'
import { formatRelativeTime } from '@/utils/relativeTime'
import type { ModelInstance } from '@/types/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'

const router = useRouter()
const { can } = usePlatformCan()
const canWriteKnowledge = computed(() => can(PLATFORM_PERMISSION_KNOWLEDGE_WRITE))
const canAttachAgent = computed(() => can(PLATFORM_PERMISSION_AGENT_WRITE))
const knowledgeStore = useKnowledgeStore()
const usages = ref<KnowledgeUsage[]>([])
const attachDialogVisible = ref(false)
const targetAttachKb = ref<KnowledgeBase | null>(null)

function agentSummaryBadge(code: string): { connected: boolean; label: string } {
  const usage = usageFor(usages.value, code)
  const count = usage.workflows?.length ?? 0
  if (count > 0) {
    const agents = agentNames(usage.workflows)
    if (agents.length > 0) {
      return { connected: true, label: `${agents.length} 个 Agent` }
    }
    return { connected: true, label: `${count} 个 Workflow` }
  }
  return { connected: false, label: '+ 接入 Agent' }
}

function openAttachDialog(kb: KnowledgeBase) {
  targetAttachKb.value = kb
  attachDialogVisible.value = true
}

async function handleAgentAttached() {
  attachDialogVisible.value = false
  if (targetAttachKb.value) {
    await fetchUsages([targetAttachKb.value.code])
  }
}

async function copyKbCode(code: string, e?: Event) {
  e?.stopPropagation()
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code)
    } else {
      const input = document.createElement('input')
      input.value = code
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success({ message: `已复制编码：${code}`, duration: 1800 })
  } catch {
    ElMessage.error('复制失败')
  }
}

function handleDropdownCommand(command: string, kb: KnowledgeBase) {
  switch (command) {
    case 'detail':
      router.push(`/knowledge/${kb.code}`)
      break
    case 'files':
      router.push({ path: `/knowledge/${kb.code}`, query: { tab: 'files' } })
      break
    case 'attach':
      openAttachDialog(kb)
      break
    case 'settings':
      router.push({ path: `/knowledge/${kb.code}`, query: { tab: 'settings' } })
      break
    case 'delete':
      void confirmDelete(kb)
      break
  }
}

async function confirmDelete(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `确定删除知识库「${kb.name}」？所有关联文件、切片及向量数据将被清除，操作不可撤销。`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      },
    )
    await handleDelete(kb.code)
  } catch {
    // 取消删除
  }
}

async function fetchUsages(codes: string[]) {
  if (codes.length === 0) return
  try {
    const { data } = await getKnowledgeUsage(codes)
    usages.value = Array.isArray(data) ? data : []
  } catch {
    usages.value = []
  }
}
const viewMode = ref<'table' | 'card'>('table')
const keyword = ref('')
const statusFilter = ref<'' | 'enabled' | 'disabled'>('')
const embeddingFilter = ref<'' | 'configured' | 'unconfigured'>('')
const appliedKeyword = ref('')
const appliedStatusFilter = ref<'' | 'enabled' | 'disabled'>('')
const appliedEmbeddingFilter = ref<'' | 'configured' | 'unconfigured'>('')
const currentPage = ref(1)
const pageSize = ref(10)
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
const submitLoading = ref(false)
const formRef = ref<FormInstance>()
const embeddingInstances = ref<ModelInstance[]>([])
const rerankInstances = ref<ModelInstance[]>([])
const llmInstances = ref<ModelInstance[]>([])
const embeddingInstancesLoading = ref(false)
const rerankInstancesLoading = ref(false)
const llmInstancesLoading = ref(false)
const embeddingInstancesLoadError = ref(false)
const rerankInstancesLoadError = ref(false)
const llmInstancesLoadError = ref(false)

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
  embeddingModelInstanceId: [{ required: true, message: '请选择向量模型', trigger: 'change' }],
  llmModelInstanceId: [{ required: true, message: '请选择回答模型', trigger: 'change' }],
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
  resetForm()
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
  embeddingInstancesLoading.value = true
  embeddingInstancesLoadError.value = false
  try {
    const { data } = await getModelInstances({ modelType: 'EMBEDDING' })
    embeddingInstances.value = normalizeActiveModelInstances(data, 'EMBEDDING')
  } catch {
    embeddingInstances.value = []
    embeddingInstancesLoadError.value = true
  } finally {
    embeddingInstancesLoading.value = false
  }
}

async function fetchRerankInstances() {
  rerankInstancesLoading.value = true
  rerankInstancesLoadError.value = false
  try {
    const { data } = await getModelInstances({ modelType: 'RERANKER' })
    rerankInstances.value = normalizeActiveModelInstances(data, 'RERANKER')
  } catch {
    rerankInstances.value = []
    rerankInstancesLoadError.value = true
  } finally {
    rerankInstancesLoading.value = false
  }
}

async function fetchLlmInstances() {
  llmInstancesLoading.value = true
  llmInstancesLoadError.value = false
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    llmInstances.value = normalizeActiveModelInstances(data, 'LLM')
  } catch {
    llmInstances.value = []
    llmInstancesLoadError.value = true
  } finally {
    llmInstancesLoading.value = false
  }
}

function handleEmbeddingSelectVisible(visible: boolean) {
  if (visible) void fetchEmbeddingInstances()
}

function handleRerankSelectVisible(visible: boolean) {
  if (visible) void fetchRerankInstances()
}

function handleLlmSelectVisible(visible: boolean) {
  if (visible) void fetchLlmInstances()
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    await createKnowledge(form)
    ElMessage.success('创建成功')
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

watch(
  () => knowledgeStore.knowledgeList.map((item) => item.code).join(','),
  (codes) => { void fetchUsages(codes ? codes.split(',') : []) },
)

onMounted(() => {
  knowledgeStore.fetchList()
  if (!canLoadModelCatalog()) return
  fetchEmbeddingInstances()
  fetchRerankInstances()
  fetchLlmInstances()
})
</script>

<style scoped lang="scss">
// 卡片网格：精简紧凑，单卡片小巧精致，多卡片优雅排布
.knowledge-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 310px), 340px));
  gap: 16px;
  padding: 2px 2px 16px;
}

// 极简液态玻璃卡片
.kb-liquid-card {
  position: relative;
  display: flex;
  min-width: 0;
  max-width: 340px;
  flex-direction: column;
  padding: 16px 18px 14px;
  overflow: hidden;
  border-radius: 18px;
  cursor: pointer;

  transition:
    transform 220ms cubic-bezier(0.16, 1, 0.3, 1),
    border-color 220ms ease,
    box-shadow 220ms ease;

  &:hover {
    transform: translateY(-3px) scale(1.004);
    border-color: var(--border-divider);
    .ambient-orb--violet {
      transform: translate(5px, -3px) scale(1.15);
      opacity: 0.85;
    }

    .ambient-orb--cyan {
      transform: translate(-3px, 5px) scale(1.2);
      opacity: 0.75;
    }

    .kb-liquid-card__prism-edge {
      opacity: 1;
      transform: scaleX(1);
    }

    .kb-liquid-card__avatar {
      transform: scale(1.05) rotate(-2deg);
      box-shadow:
        inset 0 1.5px 3px rgba(255, 255, 255, 1),
        0 5px 14px -2px rgb(var(--brand-primary-rgb) / 0.35);
    }

    .kb-liquid-card__title {
      color: var(--brand-on-surface);
    }
  }

  // 内部微光极光
  &__ambient {
    position: absolute;
    inset: 0;
    pointer-events: none;
    overflow: hidden;
    border-radius: inherit;
    z-index: 0;

    .ambient-orb {
      position: absolute;
      border-radius: 50%;
      filter: blur(32px);
      transition: transform 500ms cubic-bezier(0.16, 1, 0.3, 1), opacity 500ms ease;

      &--violet {
        top: -15px;
        right: -10px;
        width: 140px;
        height: 140px;
        background: radial-gradient(circle, rgb(var(--brand-primary-rgb) / 0.32) 0%, rgb(var(--brand-primary-rgb) / 0.14) 50%, transparent 70%);
        opacity: 0.65;
      }

      &--cyan {
        bottom: -20px;
        left: -15px;
        width: 130px;
        height: 130px;
        background: radial-gradient(circle, color-mix(in srgb, var(--status-info) 26%, transparent) 0%, color-mix(in srgb, var(--status-info) 10%, transparent) 50%, transparent 70%);
        opacity: 0.55;
      }
    }
  }

  // 顶沿水光棱镜高光条
  &__prism-edge {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 2.5px;
    background: linear-gradient(
      90deg,
      transparent 0%,
      var(--surface-solid-panel) 20%,
      var(--brand-primary) 50%,
      var(--status-info) 80%,
      transparent 100%
    );
    opacity: 0.7;
    transform: scaleX(0.8);
    transform-origin: center;
    transition: all 360ms ease;
    z-index: 1;
  }

  // 头部区域
  &__header {
    position: relative;
    z-index: 1;
    display: flex;
    align-items: flex-start;
    gap: 11px;
    margin-bottom: 9px;
  }

  // 水晶圆角图标徽章
  &__avatar {
    position: relative;
    display: grid;
    width: 36px;
    height: 36px;
    flex-shrink: 0;
    place-items: center;
    border-radius: 11px;
    background: linear-gradient(
      140deg,
      var(--surface-solid-panel) 0%,
      rgb(var(--brand-primary-rgb) / 0.8) 50%,
      rgb(var(--brand-primary-rgb) / 0.6) 100%
    );
    border: 1px solid var(--border-glass-edge);
    box-shadow:
      inset 0 1.5px 2px rgba(255, 255, 255, 1),
      inset 0 -1px 1px rgb(var(--brand-primary-rgb) / 0.15),
      0 4px 10px -2px rgb(var(--brand-primary-rgb) / 0.22);
    transition: all 220ms cubic-bezier(0.16, 1, 0.3, 1);

    .avatar-icon {
      color: var(--brand-on-surface);
      font-size: 18px;
      filter: drop-shadow(0 1px 2px rgb(var(--brand-primary-rgb) / 0.3));
    }
  }

  &__identity {
    min-width: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__title {
    margin: 0;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 14.5px;
    font-weight: 700;
    line-height: 20px;
    letter-spacing: -0.01em;
    text-overflow: ellipsis;
    white-space: nowrap;
    transition: color 160ms ease;
  }

  // 极简编码小字
  .kb-code-sub {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    align-self: flex-start;
    max-width: 100%;
    color: var(--text-muted);
    font-family: var(--font-family-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: 11px;
    line-height: 15px;
    cursor: copy;
    transition: all 140ms ease;

    &:hover {
      color: var(--brand-primary);

      .copy-icon {
        opacity: 1;
        transform: scale(1.1);
      }
    }

    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .copy-icon {
      font-size: 10.5px;
      opacity: 0.6;
      transition: all 140ms ease;
    }
  }

  // 状态呼吸胶囊
  .kb-status-pill {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 7px;
    border-radius: 999px;
    font-size: 10.5px;
    font-weight: 600;
    white-space: nowrap;
    border: 1px solid transparent;

    &.is-active {
      background: linear-gradient(135deg, color-mix(in srgb, var(--status-success) 12%, transparent) 0%, color-mix(in srgb, var(--status-success) 6%, transparent) 100%);
      color: var(--status-success);
      border-color: color-mix(in srgb, var(--status-success) 25%, transparent);
      box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.8);

      .kb-status-pill__dot {
        background: var(--status-success);
        box-shadow: 0 0 6px var(--status-success), 0 0 2px #fff;
        animation: pulse-dot 2s infinite;
      }
    }

    &.is-disabled {
      background: var(--surface-hover);
      color: var(--text-muted);
      border-color: var(--border-subtle);

      .kb-status-pill__dot {
        background: var(--text-muted);
      }
    }

    &__dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
    }
  }

  // 描述文本：纯净通透
  &__description {
    position: relative;
    z-index: 1;
    display: -webkit-box;
    min-height: 32px;
    margin: 0 0 12px;
    overflow: hidden;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 16px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;

    &.is-empty {
      color: var(--text-muted);
      font-style: italic;
    }
  }

  // 单行极简通透指标栏 (Inline Stats)
  &__stats-row {
    position: relative;
    z-index: 1;
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 12px;
    flex-wrap: wrap;

    .stat-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 8px;
      border-radius: 999px;
      background: var(--surface-glass-control);
      border: 1px solid var(--border-glass-edge);
      box-shadow:
        inset 0 1px 1px rgba(255, 255, 255, 0.95),
        0 1px 3px rgba(15, 23, 42, 0.02);
      color: var(--text-secondary);
      font-size: 11px;
      line-height: 15px;
      white-space: nowrap;

      .el-icon {
        font-size: 12px;
        color: var(--text-muted);
      }

      strong {
        color: var(--text-primary);
        font-weight: 700;
        font-family: var(--font-family-mono, ui-monospace, SFMono-Regular, monospace);
      }

      small {
        color: var(--text-muted);
        font-size: 10.5px;
      }

      &--agent {
        margin-left: auto;
        color: var(--text-muted);
        transition: all 160ms ease;

        &.is-connected {
          background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.1) 0%, rgb(var(--brand-primary-rgb) / 0.06) 100%);
          border-color: rgb(var(--brand-primary-rgb) / 0.28);
          color: var(--brand-primary);

          .el-icon {
            color: var(--brand-primary);
          }
        }

        &:not(.is-connected) {
          cursor: pointer;
          color: var(--brand-primary);
          border-style: dashed;

          &:hover {
            background: rgb(var(--brand-primary-rgb) / 0.12);
            border-color: var(--brand-primary);
          }
        }
      }
    }
  }

  // 底部工具栏
  &__footer {
    position: relative;
    z-index: 1;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: auto;
    padding-top: 10px;
    border-top: 1px solid var(--border-glass-edge);

    .meta-time {
      color: var(--text-muted);
      font-size: 11px;
      white-space: nowrap;
    }

    .action-buttons {
      display: flex;
      align-items: center;
      gap: 6px;
    }
  }
}

// 极简液态水滴晶片按钮
.liquid-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  background: linear-gradient(
    145deg,
    var(--surface-glass-control) 0%,
    var(--surface-glass-control) 100%
  );
  border: 1px solid var(--border-glass-edge);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 1),
    0 1px 4px rgba(30, 58, 110, 0.04);
  color: var(--text-secondary);
  backdrop-filter: blur(8px);
  transition: all 160ms ease;

  &:hover {
    color: var(--brand-primary);
    background: linear-gradient(
      145deg,
      var(--surface-solid-panel) 0%,
      rgb(var(--brand-primary-rgb) / 0.8) 100%
    );
    border-color: rgb(var(--brand-primary-rgb) / 0.4);
    box-shadow:
      inset 0 1px 1px rgba(255, 255, 255, 1),
      0 3px 8px rgb(var(--brand-primary-rgb) / 0.2);
    transform: translateY(-1px);
  }

  &--more {
    width: 26px;
    padding: 0;
  }
}

@keyframes pulse-dot {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.15);
  }
}
</style>
