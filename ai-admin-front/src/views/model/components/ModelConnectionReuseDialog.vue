<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassNotice } from '@/components/common/liquid-glass'
import { createModelWithSavedConnection, testModelWithSavedConnection } from '@/api/model'
import type { ModelInstance, ModelInstanceTestResult, ModelType } from '@/types/model'
import { readModelApiPayload } from '../modelCenterUi'
import ModelTestResultPanel from './ModelTestResultPanel.vue'

const props = defineProps<{ source: ModelInstance | null }>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ created: [id: string] }>()
const name = ref('')
const modelName = ref('')
const modelType = ref<ModelType>('LLM')
const result = ref<ModelInstanceTestResult | null>(null)
const testing = ref(false)
const saving = ref(false)
let generation = 0

watch(() => [open.value, props.source?.id], () => {
  generation++
  result.value = null
  testing.value = false
  saving.value = false
  name.value = ''
  modelName.value = ''
  modelType.value = 'LLM'
})
watch(() => [modelName.value, modelType.value], () => { result.value = null })

async function test() {
  if (testing.value || saving.value) return
  const sourceId = props.source?.id
  const target = modelName.value.trim()
  if (!sourceId || !target) { ElMessage.warning('请填写供应商模型名'); return }
  const attempt = ++generation
  testing.value = true
  result.value = null
  try {
    const { data } = await testModelWithSavedConnection(sourceId, { modelType: modelType.value, modelName: target })
    if (attempt !== generation || !open.value || props.source?.id !== sourceId) return
    result.value = readModelApiPayload<ModelInstanceTestResult>(data)
  } catch (error) {
    if (attempt === generation && open.value) ElMessage.error(error instanceof Error ? error.message : '测试失败')
  } finally { if (attempt === generation) testing.value = false }
}

async function create() {
  if (testing.value || saving.value) return
  const sourceId = props.source?.id
  const title = name.value.trim()
  const target = modelName.value.trim()
  if (!sourceId || !title || !target) { ElMessage.warning('请填写实例名称和供应商模型名'); return }
  if (!result.value?.success) { ElMessage.warning('请先测试当前模型'); return }
  const attempt = ++generation
  saving.value = true
  try {
    const { data } = await createModelWithSavedConnection(sourceId, { name: title, modelType: modelType.value, modelName: target })
    if (attempt !== generation || !open.value || props.source?.id !== sourceId) return
    const created = readModelApiPayload<ModelInstance>(data)
    open.value = false
    ElMessage.success('模型已添加，请测试已保存配置')
    emit('created', created.id)
  } catch (error) {
    if (attempt === generation && open.value) ElMessage.error(error instanceof Error ? error.message : '添加失败')
  } finally { if (attempt === generation) saving.value = false }
}
</script>

<template>
  <AppDialog v-model="open" title="使用此连接添加模型" width="600px"
    description="使用当前供应商的服务地址和凭据，在相同项目范围内添加另一个模型。"
    :close-on-click-modal="!testing && !saving" :close-on-press-escape="!testing && !saving" :show-close="!testing && !saving">
    <LiquidGlassNotice type="warning" title="新模型保存的是独立凭据副本；今后更换密钥时，需要分别更新。" />
    <el-form label-position="top" novalidate @submit.prevent="test">
      <el-form-item label="实例名称" required>
        <el-input v-model="name" maxlength="128" :disabled="testing || saving" placeholder="例如：通义对话" />
      </el-form-item>
      <el-form-item label="模型类型" required>
        <el-select v-model="modelType" :disabled="testing || saving" style="width: 100%">
          <el-option label="大语言模型" value="LLM" />
          <el-option label="向量模型" value="EMBEDDING" />
          <el-option label="重排模型" value="RERANKER" />
        </el-select>
      </el-form-item>
      <el-form-item label="供应商模型名" required>
        <el-input v-model="modelName" maxlength="256" :disabled="testing || saving" placeholder="例如：qwen-plus" />
      </el-form-item>
    </el-form>
    <ModelTestResultPanel :result="result" />
    <template #footer>
      <LiquidGlassButton :disabled="testing || saving" @click="open = false">取消</LiquidGlassButton>
      <LiquidGlassButton :loading="testing" :disabled="saving" @click="test">测试模型</LiquidGlassButton>
      <LiquidGlassButton type="primary" :loading="saving" :disabled="testing || !result?.success" @click="create">添加模型</LiquidGlassButton>
    </template>
  </AppDialog>
</template>
