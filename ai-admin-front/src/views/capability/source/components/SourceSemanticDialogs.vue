<script setup lang="ts">
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { computed } from 'vue'
import AppDialog from '@/components/common/AppDialog.vue'


const props = withDefaults(defineProps<{
  docEditSaving: boolean
  canWrite?: boolean
}>(), { canWrite: true })

const docEditVisible = defineModel<boolean>('docEditVisible', { required: true })
const docEditContent = defineModel<string>('docEditContent', { required: true })
const docEditOpen = computed({ get: () => docEditVisible.value,
  set: (visible: boolean) => { if (!props.docEditSaving) docEditVisible.value = visible } })
const docEditDraft = computed({ get: () => docEditContent.value,
  set: (content: string) => { if (!props.docEditSaving && props.canWrite) docEditContent.value = content } })

const emit = defineEmits<{
  submitDocEdit: []
}>()
</script>

<template>
  <AppDialog v-model="docEditOpen" title="编辑业务说明" description="保存后保留人工编辑；强制重新生成仍会覆盖内容。" width="720px"
    :show-close="!docEditSaving" :close-on-click-modal="!docEditSaving" :close-on-press-escape="!docEditSaving">
    <p v-if="!canWrite" role="status">当前账号仅可阅读业务说明，无法保存修改。</p>
    <el-input v-model="docEditDraft" :disabled="docEditSaving || !canWrite" type="textarea" :rows="18" resize="none" aria-label="业务说明 Markdown 内容" placeholder="Markdown 内容" />
    <template #footer>
      <LiquidGlassButton :disabled="docEditSaving" @click="docEditOpen = false">取消</LiquidGlassButton>
      <LiquidGlassButton type="primary" :loading="docEditSaving" :disabled="!canWrite" @click="!docEditSaving && canWrite && emit('submitDocEdit')">保存</LiquidGlassButton>
    </template>
  </AppDialog>

</template>
