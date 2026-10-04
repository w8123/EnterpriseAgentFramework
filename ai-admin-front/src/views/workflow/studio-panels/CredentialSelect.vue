<template>
  <div class="credential-select">
    <el-select :model-value="modelValue" clearable filterable placeholder="选择凭据"
      style="width: 100%" @update:model-value="emit('update:modelValue', $event || '')">
      <el-option v-for="item in credentials" :key="item.credentialRef"
        :label="`${item.name} / ${item.type}`" :value="item.credentialRef" />
    </el-select>
    <el-button :icon="Plus" @click="dialogVisible = true">新建</el-button>
    <CredentialCreateDialog v-model="dialogVisible" :project-id="projectId" :project-code="projectCode"
      @created="created" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import CredentialCreateDialog from '@/components/credential/CredentialCreateDialog.vue'
import type { WorkflowCredential } from '@/types/workflowCredential'

defineProps<{ modelValue?: string; credentials: WorkflowCredential[];
  projectId?: number | null; projectCode?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; created: [credential: WorkflowCredential] }>()
const dialogVisible = ref(false)
function created(credential: WorkflowCredential) {
  emit('created', credential)
  emit('update:modelValue', credential.credentialRef)
}
</script>

<style scoped lang="scss">
.credential-select { display: grid; grid-template-columns: 1fr auto; gap: 8px; width: 100%; }
</style>
