<template>
  <div class="page-container">
    <div class="page-header">
      <h2>知识库管理</h2>
      <el-button type="primary" @click="openCreateDialog">
        <el-icon><Plus /></el-icon>
        新建知识库
      </el-button>
    </div>

    <!-- 知识库列表 -->
    <el-card shadow="never" class="section-card">
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
import { Plus } from '@element-plus/icons-vue'
import { useKnowledgeStore } from '@/store/knowledge'
import { createKnowledge, updateKnowledge, deleteKnowledge } from '@/api/knowledge'
import type { KnowledgeBase, KnowledgeBaseForm } from '@/types/knowledge'

const router = useRouter()
const knowledgeStore = useKnowledgeStore()

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
