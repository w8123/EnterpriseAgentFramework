<template>
  <div class="page-container">
    <div class="page-header">
      <h2>知识库管理</h2>
      <div class="header-actions">
        <ViewToggle v-model="viewMode" />
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>
          新建知识库
        </el-button>
      </div>
    </div>

    <!-- 卡片视图 -->
    <div v-if="viewMode === 'card'" class="card-grid">
      <div
        v-for="kb in knowledgeStore.knowledgeList"
        :key="kb.code"
        class="kb-card glass-card"
        @click="router.push(`/knowledge/${kb.code}`)"
      >
        <div class="kb-card-header">
          <div class="kb-card-icon">
            <el-icon :size="20"><Collection /></el-icon>
          </div>
          <div class="kb-card-title-area">
            <h4 class="kb-card-name">{{ kb.name }}</h4>
            <code class="kb-card-code">{{ kb.code }}</code>
          </div>
          <el-tag
            :type="kb.status === 1 ? 'success' : 'danger'"
            size="small"
            effect="dark"
          >{{ kb.status === 1 ? '启用' : '禁用' }}</el-tag>
        </div>
        <div class="kb-card-desc">{{ kb.description || '暂无描述' }}</div>
        <div class="kb-card-meta">
          <span class="kb-meta-item">
            <el-icon><Document /></el-icon>
            {{ kb.fileCount ?? 0 }} 文件
          </span>
          <span class="kb-meta-item">
            <el-icon><Coin /></el-icon>
            {{ kb.embeddingModel || '未配置模型' }}
          </span>
        </div>
        <div class="kb-card-footer">
          <el-button link type="primary" size="small" @click.stop="router.push(`/knowledge/${kb.code}`)">详情</el-button>
          <el-button link type="primary" size="small" @click.stop="openEditDialog(kb)">编辑</el-button>
          <el-popconfirm
            title="确定删除此知识库？所有关联数据将被清除。"
            @confirm="handleDelete(kb.code)"
          >
            <template #reference>
              <el-button link type="danger" size="small" @click.stop>删除</el-button>
            </template>
          </el-popconfirm>
        </div>
      </div>
    </div>

    <!-- 知识库列表 -->
    <el-card v-else shadow="never" class="section-card">
      <el-table
        v-loading="knowledgeStore.loading"
        :data="knowledgeStore.knowledgeList"
        stripe
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
        <el-table-column prop="embeddingModel" label="Embedding 模型" min-width="160">
          <template #default="{ row }">
            {{ row.embeddingModel || '-' }}
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
    </el-card>

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑知识库' : '新建知识库'"
      width="520px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="110px"
        label-position="left"
      >
        <el-form-item label="知识库名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="知识库编码" prop="code">
          <el-input
            v-model="form.code"
            placeholder="唯一标识，如 kb_contract"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item label="Embedding 模型" prop="embeddingModel">
          <el-input v-model="form.embeddingModel" placeholder="如 text-embedding-ada-002" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入描述（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Plus, Collection, Document, Coin } from '@element-plus/icons-vue'
import ViewToggle from '@/components/ViewToggle.vue'
import { useKnowledgeStore } from '@/store/knowledge'
import { createKnowledge, updateKnowledge, deleteKnowledge } from '@/api/knowledge'
import type { KnowledgeBase, KnowledgeBaseForm } from '@/types/knowledge'

const router = useRouter()
const knowledgeStore = useKnowledgeStore()
const viewMode = ref<'table' | 'card'>('table')

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<KnowledgeBaseForm>({
  name: '',
  code: '',
  description: '',
  embeddingModel: '',
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入知识库编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '编码只能包含字母、数字和下划线，且以字母开头', trigger: 'blur' },
  ],
}

function resetForm() {
  form.name = ''
  form.code = ''
  form.description = ''
  form.embeddingModel = ''
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
  form.embeddingModel = row.embeddingModel || ''
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()

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

onMounted(() => {
  knowledgeStore.fetchList()
})
</script>

<style scoped lang="scss">
.kb-card {
  cursor: pointer;
}

.kb-card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.kb-card-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #22d3ee, #06b6d4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.kb-card-title-area {
  flex: 1;
  min-width: 0;
}

.kb-card-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-card-code {
  font-size: 11px;
  color: #a5b4fc;
  background: rgba(99, 102, 241, 0.1);
  padding: 1px 6px;
  border-radius: 4px;
}

.kb-card-desc {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.kb-card-meta {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}

.kb-meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #64748b;

  .el-icon {
    font-size: 14px;
  }
}

.kb-card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .kb-card-code {
    color: #6366f1;
    background: rgba(99, 102, 241, 0.08);
  }

  .kb-meta-item {
    color: #94a3b8;
  }

  .kb-card-footer {
    border-top: 1px solid #ebeef5;
  }
}
</style>
