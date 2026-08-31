<template>
  <WorkbenchPage class="biz-index-list-page" layout="list">
    <PageHeader
      variant="standard"
      domain="knowledge"
      eyebrow="Business Index"
      title="业务索引管理"
      description="管理业务数据的语义索引、文本模板、向量模型与附件切分配置。"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">注册索引</el-button>
      </template>
    </PageHeader>

    <!-- 索引列表 -->
    <el-card shadow="never" class="section-card workbench-list-surface">
      <el-table
        v-loading="bizIndexStore.loading"
        :data="bizIndexStore.bizIndexList"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="indexName" label="索引名称" min-width="140">
          <template #default="{ row }">
            <el-button type="primary" link @click="router.push(`/biz-index/${row.indexCode}`)">
              {{ row.indexName }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="indexCode" label="索引编码" min-width="140">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.indexCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sourceSystem" label="来源系统" min-width="120" />
        <el-table-column prop="recordCount" label="记录数" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.recordCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="attachmentChunkCount" label="附件Chunk" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info">{{ row.attachmentChunkCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
              @click="router.push(`/biz-index/${row.indexCode}`)">
              详情
            </el-button>
            <el-button type="primary" link size="small" @click="openEditDialog(row)">
              编辑
            </el-button>
            <el-popconfirm
              title="确定删除此索引？所有关联数据将被清除。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(row.indexCode)"
            >
              <template #reference>
                <el-button type="danger" link size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 注册 / 编辑弹窗 -->
    <AppDialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑索引' : '注册索引'"
      width="680px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="120px"
        label-position="left"
      >
        <el-form-item label="索引编码" prop="indexCode">
          <el-input
            v-model="form.indexCode"
            placeholder="唯一标识，如 biz_material"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item label="索引名称" prop="indexName">
          <el-input v-model="form.indexName" placeholder="如：物资语义索引" />
        </el-form-item>
        <el-form-item label="来源系统" prop="sourceSystem">
          <el-input v-model="form.sourceSystem" placeholder="如：material_system" />
        </el-form-item>
        <el-form-item label="文本模板" prop="textTemplate">
          <el-input
            v-model="form.textTemplate"
            type="textarea"
            :rows="3"
            placeholder="如：物资名称：{name}，规格型号：{spec}，用途：{useScene}"
          />
          <div class="form-tip">
            使用 {fieldName} 作为占位符，支持 {fieldName|默认值} 语法
          </div>
        </el-form-item>
        <el-form-item label="字段定义" prop="fieldSchema">
          <el-input
            v-model="form.fieldSchema"
            type="textarea"
            :rows="5"
            placeholder='JSON 格式，如：{"fields":[{"name":"name","label":"物资名称","type":"string","required":true,"indexed":true}]}'
          />
        </el-form-item>
        <el-form-item label="Embedding 实例" prop="embeddingModelInstanceId">
          <el-select
            v-model="form.embeddingModelInstanceId"
            placeholder="请选择 Embedding 模型实例"
            style="width: 100%"
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
        <el-form-item label="附件切分策略">
          <el-select v-model="form.splitType" style="width: 200px">
            <el-option label="固定长度" value="FIXED" />
            <el-option label="段落切分" value="PARAGRAPH" />
            <el-option label="语义切分" value="SEMANTIC" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="切分大小">
              <el-input-number v-model="form.chunkSize" :min="100" :max="2000" :step="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="切分重叠">
              <el-input-number v-model="form.chunkOverlap" :min="0" :max="500" :step="10" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="可选备注"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          {{ isEdit ? '保存' : '注册' }}
        </el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { useBizIndexStore } from '@/store/bizIndex'
import { createBizIndex, updateBizIndex, deleteBizIndex } from '@/api/bizIndex'
import { getModelInstances } from '@/api/model'
import type { BizIndex, BizIndexForm } from '@/types/bizIndex'
import type { ModelInstance } from '@/types/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'

const router = useRouter()
const bizIndexStore = useBizIndexStore()

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const formRef = ref<FormInstance>()
const embeddingInstances = ref<ModelInstance[]>([])
const embeddingInstancesLoading = ref(false)
const embeddingInstancesLoadError = ref(false)

const form = reactive<BizIndexForm>({
  indexCode: '',
  indexName: '',
  sourceSystem: '',
  textTemplate: '',
  fieldSchema: '',
  embeddingModelInstanceId: '',
  chunkSize: 500,
  chunkOverlap: 50,
  splitType: 'FIXED',
  remark: '',
})

const formRules: FormRules = {
  indexCode: [
    { required: true, message: '请输入索引编码', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,62}$/,
      message: '编码只能包含字母、数字和下划线，以字母开头',
      trigger: 'blur',
    },
  ],
  indexName: [{ required: true, message: '请输入索引名称', trigger: 'blur' }],
  sourceSystem: [{ required: true, message: '请输入来源系统标识', trigger: 'blur' }],
  textTemplate: [{ required: true, message: '请输入文本模板', trigger: 'blur' }],
  fieldSchema: [{ required: true, message: '请输入字段定义', trigger: 'blur' }],
}

function resetForm() {
  form.indexCode = ''
  form.indexName = ''
  form.sourceSystem = ''
  form.textTemplate = ''
  form.fieldSchema = ''
  form.embeddingModelInstanceId = ''
  form.chunkSize = 500
  form.chunkOverlap = 50
  form.splitType = 'FIXED'
  form.remark = ''
}

function openCreateDialog() {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row: BizIndex) {
  isEdit.value = true
  form.indexCode = row.indexCode
  form.indexName = row.indexName
  form.sourceSystem = row.sourceSystem
  form.textTemplate = row.textTemplate
  form.fieldSchema = row.fieldSchema
  form.embeddingModelInstanceId = row.embeddingModelInstanceId || ''
  form.chunkSize = row.chunkSize
  form.chunkOverlap = row.chunkOverlap
  form.splitType = row.splitType
  form.remark = row.remark || ''
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()

  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateBizIndex(form.indexCode, form)
      ElMessage.success('更新成功')
    } else {
      await createBizIndex(form)
      ElMessage.success('注册成功')
    }
    dialogVisible.value = false
    await bizIndexStore.fetchList()
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(indexCode: string) {
  try {
    await deleteBizIndex(indexCode)
    ElMessage.success('删除成功')
    await bizIndexStore.fetchList()
  } catch {
    // 错误已在拦截器中处理
  }
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

function handleEmbeddingSelectVisible(visible: boolean) {
  if (visible) void fetchEmbeddingInstances()
}

onMounted(() => {
  bizIndexStore.fetchList()
  fetchEmbeddingInstances()
})
</script>

<style scoped lang="scss">
.biz-index-list-page > .section-card {
  margin-bottom: 0;
}

.form-tip {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
  line-height: 1.4;
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .form-tip {
    color: #94a3b8;
  }
}
</style>
