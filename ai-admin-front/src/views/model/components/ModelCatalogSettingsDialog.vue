<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshRight } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  getModelCatalogStatus,
  getModelInstances,
  triggerModelCatalogSync,
  updateModelCatalogSettings,
} from '@/api/model'
import type {
  ModelCatalogManualSyncResponse,
  ModelCatalogSettingsRequest,
  ModelCatalogStatus,
  ModelInstance,
} from '@/types/model'
import { normalizeListPayload, readModelApiPayload } from '../modelCenterUi'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [status: ModelCatalogStatus]
}>()

const loading = ref(false)
const saving = ref(false)
const syncing = ref(false)
const error = ref('')
const status = ref<ModelCatalogStatus | null>(null)
const autoSyncEnabled = ref(false)
const savedAutoSyncEnabled = ref(false)
const analyzerModelInstanceId = ref<string | undefined>('')
const savedAnalyzerModelInstanceId = ref('')
const analyzerCandidates = ref<ModelInstance[]>([])

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const analyzerChanged = computed(
  () => (analyzerModelInstanceId.value || '') !== savedAnalyzerModelInstanceId.value,
)
const settingChanged = computed(
  () => autoSyncEnabled.value !== savedAutoSyncEnabled.value || analyzerChanged.value,
)
const progressText = computed(() => {
  if (!status.value) return '尚未读取目录状态'
  return `今日 ${status.value.completedToday}/${status.value.sourceCount} 个来源已完成`
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) void loadStatus()
  },
)

async function loadStatus() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await getModelCatalogStatus()
    const nextStatus = readModelApiPayload<ModelCatalogStatus>(data)
    status.value = nextStatus
    const enabled = nextStatus.autoSyncEnabled ?? nextStatus.enabled
    autoSyncEnabled.value = enabled
    savedAutoSyncEnabled.value = enabled
    analyzerModelInstanceId.value = nextStatus.analyzerModelInstanceId || ''
    savedAnalyzerModelInstanceId.value = analyzerModelInstanceId.value
    await loadAnalyzerCandidates()
  } catch (err) {
    status.value = null
    error.value = err instanceof Error ? err.message : '读取模型目录设置失败'
  } finally {
    loading.value = false
  }
}

async function loadAnalyzerCandidates() {
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    analyzerCandidates.value = normalizeListPayload<ModelInstance>(data).filter(
      (instance) => instance.status === 'ACTIVE' || instance.id === analyzerModelInstanceId.value,
    )
  } catch {
    analyzerCandidates.value = []
  }
}

async function saveSettings() {
  saving.value = true
  const autoSyncChanged = autoSyncEnabled.value !== savedAutoSyncEnabled.value
  const request: ModelCatalogSettingsRequest = {}
  if (autoSyncChanged) request.autoSyncEnabled = autoSyncEnabled.value
  if (analyzerChanged.value) request.analyzerModelInstanceId = analyzerModelInstanceId.value || ''
  try {
    const { data } = await updateModelCatalogSettings(request)
    const nextStatus = readModelApiPayload<ModelCatalogStatus>(data)
    status.value = nextStatus
    savedAutoSyncEnabled.value = nextStatus.autoSyncEnabled ?? nextStatus.enabled
    autoSyncEnabled.value = savedAutoSyncEnabled.value
    savedAnalyzerModelInstanceId.value = nextStatus.analyzerModelInstanceId || ''
    analyzerModelInstanceId.value = savedAnalyzerModelInstanceId.value
    emit('updated', nextStatus)
    if (autoSyncChanged) {
      ElMessage.success(autoSyncEnabled.value ? '已开启模型目录自动同步' : '已关闭模型目录自动同步')
    } else {
      ElMessage.success('已保存目录分析模型')
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存目录设置失败')
  } finally {
    saving.value = false
  }
}

async function synchronizeNow() {
  syncing.value = true
  try {
    const { data } = await triggerModelCatalogSync()
    const result = readModelApiPayload<ModelCatalogManualSyncResponse>(data)
    if (result.accepted) {
      ElMessage.success(result.message)
    } else {
      ElMessage.info(result.message)
    }
    await loadStatus()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '提交手动同步失败')
  } finally {
    syncing.value = false
  }
}
</script>

<template>
  <AppDialog
    v-model="visible"
    title="模型目录设置"
    description="控制官方模型目录的自动核验，也可以按需提交当天的手动同步。"
    width="620px"
    destroy-on-close
  >
    <div v-loading="loading" class="catalog-settings">
      <LiquidGlassNotice
        v-if="error"
        type="error"
        :title="error"
        source="backdrop"
      />

      <section class="catalog-settings__card catalog-settings__card--analyzer">
        <div>
          <strong>目录分析模型</strong>
          <p>同步时用它读懂各厂商官方模型页面，提取新模型和下线信息；提取结果还要通过原文校验才会更新模板。</p>
        </div>
        <el-select
          v-model="analyzerModelInstanceId"
          class="catalog-settings__analyzer"
          clearable
          filterable
          placeholder="选择一个已启用的大语言模型"
          :disabled="loading || Boolean(error)"
        >
          <el-option
            v-for="instance in analyzerCandidates"
            :key="instance.id"
            :label="`${instance.name}（${instance.modelName}）`"
            :value="instance.id"
          />
        </el-select>
      </section>

      <section class="catalog-settings__card">
        <div>
          <strong>每日自动同步</strong>
          <p>默认关闭。开启后，服务按业务日期为每个官方来源创建一次同步槽位。</p>
        </div>
        <el-switch
          v-model="autoSyncEnabled"
          aria-label="每日自动同步"
          :disabled="loading || Boolean(error)"
        />
      </section>

      <section class="catalog-settings__card catalog-settings__card--manual">
        <div>
          <strong>手动同步</strong>
          <p>立即处理今天尚未成功的来源；已成功或无变化的来源不会重复执行。</p>
          <span>{{ progressText }}</span>
        </div>
        <el-button
          class="catalog-settings__sync"
          type="primary"
          plain
          :icon="RefreshRight"
          :loading="syncing"
          :disabled="loading || !status?.analyzerConfigured"
          @click="synchronizeNow"
        >
          立即同步
        </el-button>
      </section>

      <LiquidGlassNotice
        v-if="status && !status.analyzerConfigured"
        type="warning"
        title="尚未配置目录分析模型，暂不能同步"
        description="先在模型中心接入一个大语言模型，再在上方选择并保存。"
        source="backdrop"
      />

      <p v-if="status" class="catalog-settings__message">{{ status.message }}</p>
    </div>

    <template #footer>
      <div class="catalog-settings__footer">
        <LiquidGlassButton @click="visible = false">关闭</LiquidGlassButton>
        <LiquidGlassButton
          type="primary"
          :loading="saving"
          :disabled="loading || Boolean(error) || !settingChanged"
          @click="saveSettings"
        >
          保存设置
        </LiquidGlassButton>
      </div>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.catalog-settings {
  display: grid;
  gap: 14px;
}

.catalog-settings__card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px;
  border: 1px solid var(--border-glass);
  border-radius: 14px;
  background: var(--surface-glass-control);
}

.catalog-settings__card strong {
  color: var(--text-primary);
  font-size: 14px;
}

.catalog-settings__card p {
  margin: 5px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.catalog-settings__card span {
  display: block;
  margin-top: 7px;
  color: var(--text-muted);
  font-size: 12px;
}

.catalog-settings__card--analyzer {
  align-items: flex-start;
}

.catalog-settings__analyzer {
  flex: 0 0 240px;
}

.catalog-settings__card--manual {
  align-items: flex-start;
}

.catalog-settings__sync {
  flex: 0 0 auto;
}

.catalog-settings__message {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.catalog-settings__footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 640px) {
  .catalog-settings__card {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>
