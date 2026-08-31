<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshRight } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  getModelCatalogStatus,
  triggerModelCatalogSync,
  updateModelCatalogSettings,
} from '@/api/model'
import type { ModelCatalogManualSyncResponse, ModelCatalogStatus } from '@/types/model'
import { readModelApiPayload } from '../modelCenterUi'

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

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const settingChanged = computed(() => autoSyncEnabled.value !== savedAutoSyncEnabled.value)
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
  } catch (err) {
    status.value = null
    error.value = err instanceof Error ? err.message : '读取模型目录设置失败'
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  saving.value = true
  try {
    const { data } = await updateModelCatalogSettings({
      autoSyncEnabled: autoSyncEnabled.value,
    })
    const nextStatus = readModelApiPayload<ModelCatalogStatus>(data)
    status.value = nextStatus
    savedAutoSyncEnabled.value = nextStatus.autoSyncEnabled ?? nextStatus.enabled
    autoSyncEnabled.value = savedAutoSyncEnabled.value
    emit('updated', nextStatus)
    ElMessage.success(autoSyncEnabled.value ? '已开启模型目录自动同步' : '已关闭模型目录自动同步')
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
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

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

      <el-alert
        v-if="status && !status.analyzerConfigured"
        type="warning"
        :closable="false"
        show-icon
        title="尚未配置目录分析模型，暂不能手动同步"
        description="请先设置 MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID，并重启模型服务。"
      />

      <p v-if="status" class="catalog-settings__message">{{ status.message }}</p>
    </div>

    <template #footer>
      <div class="catalog-settings__footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="loading || Boolean(error) || !settingChanged"
          @click="saveSettings"
        >
          保存设置
        </el-button>
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
