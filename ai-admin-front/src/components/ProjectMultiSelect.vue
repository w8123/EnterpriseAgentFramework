<template>
  <el-select
    :model-value="modelValue"
    multiple
    collapse-tags
    collapse-tags-tooltip
    filterable
    clearable
    :loading="projectStore.loading"
    :placeholder="placeholder"
    class="project-multi-select"
    @update:model-value="emit('update:modelValue', $event)"
    @visible-change="handleVisibleChange"
  >
    <el-option
      v-for="project in projectStore.projects"
      :key="project.id"
      :label="projectStore.projectLabel(project)"
      :value="project.id"
    >
      <div class="project-option">
        <span>{{ project.name }}</span>
        <span v-if="project.projectCode" class="project-code">{{ project.projectCode }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
import { useProjectStore } from '@/store/project'

withDefaults(
  defineProps<{
    modelValue: number[]
    placeholder?: string
  }>(),
  {
    placeholder: '选择项目（可多选）',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: number[]]
}>()

const projectStore = useProjectStore()

function handleVisibleChange(visible: boolean) {
  if (visible && !projectStore.projects.length) {
    projectStore.fetchProjects()
  }
}
</script>

<style scoped lang="scss">
.project-multi-select {
  width: 100%;
}

.project-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.project-code {
  color: var(--text-disabled);
  font-size: 12px;
}
</style>
