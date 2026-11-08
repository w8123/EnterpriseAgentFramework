<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { getModelInstancePrice, saveModelInstancePrice, type ModelInstancePrice } from '@/api/model'
import { readModelApiPayload } from '../modelCenterUi'

const props = defineProps<{ modelId: string; canManage: boolean }>()

const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const loadError = ref('')
const price = ref<ModelInstancePrice | null>(null)
const draft = ref<{ input: number | null; output: number | null; currency: 'CNY' | 'USD' }>({
  input: null,
  output: null,
  currency: 'CNY',
})

function symbol(currency: string | undefined) {
  return currency === 'USD' ? '$' : '¥'
}

function text(value: number | null | undefined, currency: string | undefined) {
  return value == null ? '未填' : `${symbol(currency)}${value} / 百万 Token`
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getModelInstancePrice(props.modelId)
    price.value = readModelApiPayload<ModelInstancePrice>(data)
  } catch (error) {
    price.value = null
    loadError.value = error instanceof Error ? error.message : '价格加载失败'
  } finally {
    loading.value = false
  }
}

function startEdit() {
  draft.value = {
    input: price.value?.inputPricePerMillion ?? null,
    output: price.value?.outputPricePerMillion ?? null,
    currency: price.value?.currency ?? 'CNY',
  }
  editing.value = true
}

async function save() {
  saving.value = true
  try {
    const { data } = await saveModelInstancePrice(props.modelId, {
      inputPricePerMillion: draft.value.input,
      outputPricePerMillion: draft.value.output,
      currency: draft.value.currency,
    })
    price.value = readModelApiPayload<ModelInstancePrice>(data)
    editing.value = false
    ElMessage.success('价格已保存，「AI 使用情况」的费用会按新价格计算')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

watch(() => props.modelId, load, { immediate: true })
</script>

<template>
  <WorkbenchPanel
    class="glass-workspace-adaptive model-detail__panel"
    title="价格"
    description="填写供应商的每百万 Token 价格，「AI 使用情况」用它估算费用；不影响调用。"
    density="comfortable"
  >
    <div v-if="loading" v-loading="true" class="price-loading" aria-label="正在加载价格" />
    <p v-else-if="loadError" class="price-error">{{ loadError }}</p>
    <div v-else-if="!editing" class="info-grid">
      <div class="info-row">
        <span>输入</span><em>{{ text(price?.inputPricePerMillion, price?.currency) }}</em>
      </div>
      <div class="info-row">
        <span>输出</span><em>{{ text(price?.outputPricePerMillion, price?.currency) }}</em>
      </div>
    </div>
    <el-form v-else label-position="top" class="price-form" @submit.prevent="save">
      <el-form-item label="每百万输入 Token">
        <el-input-number v-model="draft.input" :min="0" :max="100000" :precision="4" :step="0.5" controls-position="right" placeholder="未填" />
      </el-form-item>
      <el-form-item label="每百万输出 Token">
        <el-input-number v-model="draft.output" :min="0" :max="100000" :precision="4" :step="0.5" controls-position="right" placeholder="未填" />
      </el-form-item>
      <el-form-item label="币种">
        <el-radio-group v-model="draft.currency">
          <el-radio-button value="CNY">人民币</el-radio-button>
          <el-radio-button value="USD">美元</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <div v-if="canManage && !loading && !loadError" class="panel-actions">
      <template v-if="editing">
        <el-button type="primary" :loading="saving" @click="save">保存价格</el-button>
        <el-button :disabled="saving" @click="editing = false">取消</el-button>
      </template>
      <el-button v-else @click="startEdit">{{ price?.inputPricePerMillion == null && price?.outputPricePerMillion == null ? '填写价格' : '修改价格' }}</el-button>
    </div>
  </WorkbenchPanel>
</template>

<style scoped lang="scss">
.price-loading {
  min-height: 64px;
}
.price-error {
  margin: 0;
  color: var(--status-danger);
  font-size: 13px;
}
.price-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0 12px;
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.info-row {
  display: grid;
  gap: 4px;
}
.info-row span {
  color: var(--text-secondary);
  font-size: 12px;
}
.info-row em {
  color: var(--text-primary);
  font-style: normal;
}
.panel-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
