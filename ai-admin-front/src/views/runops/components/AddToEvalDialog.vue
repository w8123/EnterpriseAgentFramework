<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { createEvalOpsDataset, createEvalOpsDatasetVersionFromTrace, listEvalOpsDatasets } from '@/api/agentEval'
import { getRunCenterConversation } from '@/api/runops'
import type { EvalOpsDatasetSummary } from '@/types/agentEval'

/**
 * 把一次运行沉淀为评测用例。运行记录不保存用户原话，所以问题来自对话记录；
 * 找不到时由用户填写。
 */
const props = defineProps<{
  modelValue: boolean
  traceId: string
  agentId: string
  question?: string | null
  sessionId?: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const datasets = ref<EvalOpsDatasetSummary[]>([])
const loading = ref(false)
const saving = ref(false)
/** 选择框里「新建数据集」的取值；新建时这次运行就是数据集的第一个用例。 */
const NEW_DATASET = -1
const form = reactive({ datasetId: undefined as number | undefined, newDatasetName: '', message: '', expectedJson: '' })
const creatingDataset = computed(() => form.datasetId === NEW_DATASET)

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

async function findQuestion(): Promise<string> {
  if (props.question) return props.question
  if (!props.sessionId) return ''
  try {
    const { data } = await getRunCenterConversation(props.sessionId)
    return data.turns.find(turn => turn.run?.traceId === props.traceId)?.question ?? ''
  } catch {
    return ''
  }
}

watch(() => props.modelValue, async open => {
  if (!open) return
  form.datasetId = undefined
  form.newDatasetName = '回归用例'
  form.message = ''
  form.expectedJson = JSON.stringify({ success: true }, null, 2)
  loading.value = true
  try {
    const [{ data }, question] = await Promise.all([listEvalOpsDatasets({ targetId: props.agentId }), findQuestion()])
    datasets.value = Array.isArray(data) ? data.filter(item => item.status !== 'ARCHIVED') : []
    form.datasetId = datasets.value[0]?.id ?? NEW_DATASET
    form.message = question
  } catch {
    datasets.value = []
    form.datasetId = NEW_DATASET
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!form.datasetId) return ElMessage.warning('请选择数据集')
  if (creatingDataset.value && !form.newDatasetName.trim()) return ElMessage.warning('请填写数据集名称')
  if (!form.message.trim()) return ElMessage.warning('请填写用例问题')
  let expected: Record<string, unknown>
  try {
    expected = JSON.parse(form.expectedJson) as Record<string, unknown>
  } catch {
    return ElMessage.error('期望结果不是有效的 JSON')
  }
  saving.value = true
  try {
    if (creatingDataset.value) {
      await createEvalOpsDataset({
        targetType: 'AGENT',
        targetId: props.agentId,
        name: form.newDatasetName.trim(),
        source: 'RUNOPS',
        items: [{
          itemKey: `trace-${props.traceId.replace(/[^A-Za-z0-9_-]/g, '').slice(-48)}`,
          message: form.message.trim(),
          input: { message: form.message.trim() },
          expected,
          tags: ['runops', 'regression'],
          sourceTraceId: props.traceId,
        }],
      })
      ElMessage.success('已新建数据集，这次运行是第一个用例')
    } else {
      await createEvalOpsDatasetVersionFromTrace(form.datasetId, {
        traceId: props.traceId,
        message: form.message.trim(),
        expected,
        changeNote: `从运行 ${props.traceId} 加入`,
      })
      ElMessage.success('已加入评测集，数据集生成了新版本')
    }
    visible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? `加入失败：${error.message}` : '加入失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppDialog v-model="visible" title="加入评测集" width="560px">
    <div v-loading="loading">
      <el-form label-position="top">
        <el-form-item label="数据集">
          <el-select v-model="form.datasetId" class="full-width">
            <el-option v-for="item in datasets" :key="item.id" :label="`${item.name}（${item.versionCount} 个版本）`" :value="item.id" />
            <el-option label="＋ 新建数据集" :value="NEW_DATASET" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="creatingDataset" label="新数据集名称">
          <el-input v-model="form.newDatasetName" maxlength="128" placeholder="例如：ERP 助手回归" />
        </el-form-item>
        <el-form-item label="用例问题">
          <el-input v-model="form.message" type="textarea" :rows="3" placeholder="用户当时问的问题" />
        </el-form-item>
        <el-form-item label="期望结果（JSON）">
          <el-input v-model="form.expectedJson" type="textarea" :rows="5" class="mono" />
          <p class="hint">例如 {"success": true, "calledTools": ["product-stock-query"]}。保存后会生成数据集新版本，旧实验不受影响。</p>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ creatingDataset ? '新建并加入' : '加入' }}</el-button>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.full-width {
  width: 100%;
}

.mono :deep(textarea) {
  font-family: var(--font-mono, monospace);
}

.hint {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
