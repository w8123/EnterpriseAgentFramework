<template>
  <div class="external-connection-tab">
    <WorkbenchPanel class="glass-workspace-adaptive" title="外部连接" density="compact">
      <el-alert v-if="!kb?.external" title="连接配置读取失败，请重新填写地址、密钥并选择远端库。" type="warning" :closable="false" show-icon />
      <el-form label-position="top" :disabled="!canWriteKnowledge || saving">
        <ExternalKnowledgeConnectionFields
          v-model="draft"
          :kb-code="kbCode"
          :api-key-configured="Boolean(kb?.external?.apiKeyConfigured)"
          :provider-locked="Boolean(kb?.externalProvider)"
        />
      </el-form>
      <div class="external-connection-tab__note">
        内容在外部系统里增删改，这里不需要同步；ReachAI 每次检索都实时调用。命中记录在「命中分析」里能看到。
      </div>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
      <div v-if="canWriteKnowledge" class="external-connection-tab__footer">
        <LiquidGlassButton source="wallpaper" type="primary" :loading="saving" @click="save">保存连接</LiquidGlassButton>
      </div>
    </WorkbenchPanel>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { updateExternalKnowledge } from '@/api/knowledge'
import type { ExternalKnowledgeConnectionForm, KnowledgeBase } from '@/types/knowledge'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_KNOWLEDGE_WRITE } from '@/auth/platformAccess'
import ExternalKnowledgeConnectionFields from './ExternalKnowledgeConnectionFields.vue'

const props = defineProps<{ kbCode: string; kb: KnowledgeBase | null }>()
const emit = defineEmits<{ saved: [] }>()
const { can } = usePlatformCan()
const canWriteKnowledge = computed(() => can(PLATFORM_PERMISSION_KNOWLEDGE_WRITE))

const draft = ref<ExternalKnowledgeConnectionForm>({ provider: '', baseUrl: '', clientId: '', apiKey: '', datasets: [] })
const saving = ref(false)
const saveError = ref('')

watch(() => props.kb, (kb) => {
  draft.value = {
    provider: kb?.external?.provider || kb?.externalProvider || '',
    baseUrl: kb?.external?.baseUrl || '',
    clientId: kb?.external?.clientId || '',
    apiKey: '',
    datasets: [...(kb?.external?.datasets || [])],
  }
}, { immediate: true })

async function save() {
  saveError.value = ''
  if (draft.value.datasets.length === 0) {
    saveError.value = '请至少勾选一个远端知识库'
    return
  }
  saving.value = true
  try {
    await updateExternalKnowledge(props.kbCode, { ...draft.value, kbCode: props.kbCode })
    ElMessage.success('连接已保存')
    emit('saved')
  } catch (error: unknown) {
    saveError.value = error instanceof Error && error.message ? error.message : '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped lang="scss">
.external-connection-tab {
  display: grid;
  gap: 14px;
  max-width: 1180px;
}

.external-connection-tab__note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}

.external-connection-tab__footer {
  margin-top: 12px;
}
</style>
