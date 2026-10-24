<template>
  <div class="knowledge-settings-tab">
    <WorkbenchPanel class="glass-workspace-adaptive" title="基础信息" density="compact">
      <el-form :model="basicForm" label-width="120px" :disabled="!kb || saving">
        <el-form-item label="名称" required :error="basicErrors.name">
          <el-input v-model="basicForm.name" placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input :model-value="kb?.code || kbCode" disabled />
        </el-form-item>
        <el-form-item label="说明" prop="description">
          <el-input v-model="basicForm.description" type="textarea" :rows="3" placeholder="简要说明知识范围或用途（可选）" />
        </el-form-item>
        <el-form-item v-for="field in modelFields" :key="field.key" :label="field.label" :required="field.type !== 'RERANKER'" :error="basicErrors[field.key]">
          <el-select
            v-model="basicForm[field.key]"
            filterable
            :clearable="field.type === 'RERANKER'"
            :placeholder="`请选择${field.label}`"
            :loading="modelLoading[field.type]"
            @visible-change="(visible: boolean) => visible && fetchModels(field.type)"
          >
            <el-option
              v-if="basicForm[field.key] && !models[field.type].some(item => item.id === basicForm[field.key])"
              :value="basicForm[field.key]!"
              :label="modelName(basicForm[field.key])"
              disabled
            />
            <el-option v-for="item in models[field.type]" :key="item.id" :label="`${item.name} / ${item.modelName}`" :value="item.id" />
            <template #empty>
              <ModelSelectEmptyState
                :model-type="field.type"
                :option-count="models[field.type].length"
                :loading="modelLoading[field.type]"
                :load-error="modelLoadError[field.type]"
                @retry="fetchModels(field.type)"
              />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item>
          <div class="field-hint">更换向量模型后，需要重新解析已有文件。</div>
        </el-form-item>
      </el-form>
    </WorkbenchPanel>

    <el-form :model="form" label-width="120px" class="knowledge-settings-tab" :disabled="!kb || saving">
      <WorkbenchPanel class="glass-workspace-adaptive" title="谁能检索" density="compact">
        <el-form-item label="检索范围">
          <el-radio-group v-model="form.accessMode">
            <el-radio value="ALL_CALLERS">所有调用方</el-radio>
            <el-radio value="FILE_GRANTS">仅授权文件</el-radio>
          </el-radio-group>
          <div class="field-hint">{{ accessModeHint(form.accessMode) }}</div>
        </el-form-item>
      </WorkbenchPanel>

      <WorkbenchPanel class="glass-workspace-adaptive" title="切分" density="compact">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="切分方式">
              <el-select v-model="form.splitType" style="width: 100%">
                <el-option label="固定长度" value="FIXED" />
                <el-option label="按段落" value="PARAGRAPH" />
                <el-option label="按语义" value="SEMANTIC" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="段落长度（字）">
              <el-input-number v-model="form.chunkSize" :min="100" :max="4000" :step="100" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="前后重叠（字）">
              <el-input-number v-model="form.chunkOverlap" :min="0" :max="1000" :step="10" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="panel-hint">Markdown 和 Word 总是先按标题切；改动只对之后上传或重新解析的文件生效。</div>
      </WorkbenchPanel>

      <WorkbenchPanel class="glass-workspace-adaptive" title="检索" density="compact">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="检索方式">
              <el-segmented v-model="form.searchMode" :options="searchModeOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="返回条数">
              <el-input-number v-model="form.topK" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="重排序">
              <el-switch v-model="form.rerankEnabled" :disabled="!basicForm.rerankModelInstanceId" />
              <div v-if="!basicForm.rerankModelInstanceId" class="field-hint">先给知识库配置重排模型才能开启。</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="相似度下限">
              <el-slider v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" show-input />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="语义权重">
              <el-slider v-model="form.vectorWeight" :min="0" :max="1" :step="0.05" show-input />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="关键词权重">
              <el-slider v-model="form.keywordWeight" :min="0" :max="1" :step="0.05" show-input />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="panel-hint">Agent 对话、Workflow 知识节点（没有单独设置时）和「检索测试」都使用这里的设置。低于相似度下限的段落不会返回。</div>
      </WorkbenchPanel>

      <div v-if="canWriteKnowledge" class="form-footer">
        <LiquidGlassButton source="wallpaper" type="primary" :loading="saving" @click="handleSave">保存设置</LiquidGlassButton>
      </div>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { updateKbConfig, updateKnowledge } from '@/api/knowledge'
import { getModelInstances } from '@/api/model'
import { canLoadModelCatalog } from '@/auth/modelCatalogAccess'
import type { KbConfig, KnowledgeBase, KnowledgeBaseForm } from '@/types/knowledge'
import type { ModelInstance, ModelType } from '@/types/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { accessModeHint } from './knowledgeDetailModel'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_KNOWLEDGE_WRITE } from '@/auth/platformAccess'
const { can } = usePlatformCan()
const canWriteKnowledge = computed(() => can(PLATFORM_PERMISSION_KNOWLEDGE_WRITE))

const props = defineProps<{
  kbCode: string
  kb: KnowledgeBase | null
  modelName: (id?: string | null) => string
}>()

const emit = defineEmits<{
  saved: []
}>()

const searchModeOptions = [{ label: '混合', value: 'hybrid' }, { label: '语义', value: 'vector' }, { label: '关键词', value: 'keyword' }]
const saving = ref(false)
const basicValidationAttempted = ref(false)
const basicForm = reactive<KnowledgeBaseForm>({
  name: '', code: '', description: '', embeddingModelInstanceId: '', llmModelInstanceId: '', rerankModelInstanceId: '',
})
const basicErrors = computed(() => ({
  name: basicValidationAttempted.value && !basicForm.name.trim() ? '请输入知识库名称' : '',
  embeddingModelInstanceId: basicValidationAttempted.value && !basicForm.embeddingModelInstanceId ? '请选择向量模型' : '',
  llmModelInstanceId: basicValidationAttempted.value && !basicForm.llmModelInstanceId ? '请选择回答模型' : '',
  rerankModelInstanceId: '',
}))
const modelFields = [
  { key: 'embeddingModelInstanceId', label: '向量模型', type: 'EMBEDDING' },
  { key: 'llmModelInstanceId', label: '回答模型', type: 'LLM' },
  { key: 'rerankModelInstanceId', label: '重排模型', type: 'RERANKER' },
] as const
const models = reactive<Record<ModelType, ModelInstance[]>>({ EMBEDDING: [], LLM: [], RERANKER: [] })
const modelLoading = reactive<Record<ModelType, boolean>>({ EMBEDDING: false, LLM: false, RERANKER: false })
const modelLoadError = reactive<Record<ModelType, boolean>>({ EMBEDDING: false, LLM: false, RERANKER: false })

async function fetchModels(type: ModelType) {
  if (!canLoadModelCatalog()) return
  modelLoading[type] = true
  modelLoadError[type] = false
  try {
    const { data } = await getModelInstances({ modelType: type })
    models[type] = normalizeActiveModelInstances(data, type)
  } catch {
    models[type] = []
    modelLoadError[type] = true
  } finally {
    modelLoading[type] = false
  }
}

watch(() => JSON.stringify(props.kb && [props.kb.code, props.kb.name, props.kb.description, props.kb.embeddingModelInstanceId, props.kb.llmModelInstanceId, props.kb.rerankModelInstanceId]), () => {
  const kb = props.kb
  if (!kb) return
  Object.assign(basicForm, {
    name: kb.name, code: kb.code, description: kb.description || '',
    embeddingModelInstanceId: kb.embeddingModelInstanceId || '',
    llmModelInstanceId: kb.llmModelInstanceId || '',
    rerankModelInstanceId: kb.rerankModelInstanceId || '',
  })
}, { immediate: true })

onMounted(() => { modelFields.forEach(field => { void fetchModels(field.type) }) })
const form = reactive<KbConfig>({
  accessMode: 'ALL_CALLERS',
  splitType: 'FIXED',
  chunkSize: 500,
  chunkOverlap: 50,
  searchMode: 'hybrid',
  topK: 5,
  similarityThreshold: 0.5,
  rerankEnabled: false,
  vectorWeight: 0.7,
  keywordWeight: 0.3,
})

watch(() => JSON.stringify(props.kb && [props.kb.code, props.kb.accessMode, props.kb.splitType, props.kb.chunkSize, props.kb.chunkOverlap, props.kb.searchMode, props.kb.topK, props.kb.similarityThreshold, props.kb.rerankEnabled, props.kb.vectorWeight, props.kb.keywordWeight]), () => {
  const kb = props.kb
  if (!kb) return
  Object.assign(form, {
    accessMode: kb.accessMode || 'ALL_CALLERS',
    splitType: kb.splitType || 'FIXED',
    chunkSize: kb.chunkSize || 500,
    chunkOverlap: kb.chunkOverlap ?? 50,
    searchMode: kb.searchMode || 'hybrid',
    topK: kb.topK || 5,
    similarityThreshold: kb.similarityThreshold ?? 0.5,
    rerankEnabled: kb.rerankEnabled ?? false,
    vectorWeight: kb.vectorWeight ?? 0.7,
    keywordWeight: kb.keywordWeight ?? 0.3,
  })
}, { immediate: true })

async function handleSave() {
  if (!props.kb || saving.value) return
  basicForm.name = basicForm.name.trim()
  basicValidationAttempted.value = true
  if (Object.values(basicErrors.value).some(Boolean)) return
  const basicPayload = { ...basicForm, code: props.kbCode, rerankModelInstanceId: basicForm.rerankModelInstanceId || '' }
  const configPayload = { ...form, rerankEnabled: Boolean(basicPayload.rerankModelInstanceId) && form.rerankEnabled }
  saving.value = true
  let basicSaved = false
  try {
    await updateKnowledge(basicPayload)
    basicSaved = true
    await updateKbConfig(props.kbCode, configPayload)
    ElMessage.success('设置已保存')
    emit('saved')
  } catch {
    if (basicSaved) ElMessage.error('基本信息已保存，其他设置保存失败，请重试。')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped lang="scss">
.knowledge-settings-tab {
  display: grid;
  gap: 14px;
  max-width: 1180px;
}

.field-hint,
.panel-hint {
  width: 100%;
  font-size: 12px;
  color: var(--text-muted);
}

.form-footer {
  display: flex;
  justify-content: flex-start;
}
</style>
