<template>
  <el-select
    v-model="modelValue"
    placeholder="请选择知识库"
    filterable
    :loading="knowledgeStore.loading"
    style="width: 100%"
    @change="handleChange"
  >
    <el-option
      v-for="kb in knowledgeStore.knowledgeList"
      :key="kb.code"
      :label="`${kb.name} (${kb.code})`"
      :value="kb.code"
    >
      <div class="kb-option">
        <span class="kb-name">{{ kb.name }}</span>
        <span class="kb-code">{{ kb.code }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useKnowledgeStore } from '@/store/knowledge'

const knowledgeStore = useKnowledgeStore()

const modelValue = defineModel<string>({ required: true })
const emit = defineEmits<{ change: [code: string] }>()

function handleChange(val: string) {
  emit('change', val)
}

onMounted(() => {
  if (knowledgeStore.knowledgeList.length === 0) {
    knowledgeStore.fetchList()
  }
})
</script>

<style scoped>
.kb-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-name {
  font-size: 14px;
}

.kb-code {
  font-size: 12px;
  color: #909399;
}
</style>
