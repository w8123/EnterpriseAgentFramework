<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useProjectStore } from '@/store/project'
import type { ToolAclProjectScope } from '@/types/toolAcl'

const props = defineProps<{ modelValue: ToolAclProjectScope; disabled?: boolean }>()
const emit = defineEmits<{ (event: 'update:modelValue', scope: ToolAclProjectScope): void }>()
const projectStore = useProjectStore()
const platformScope = 'platform'
const selection = computed(() => {
  if (props.modelValue.projectId != null) return props.modelValue.projectId
  if (props.modelValue.projectCode?.trim()) return `code:${props.modelValue.projectCode}`
  if (props.modelValue.projectId === null && props.modelValue.projectCode === null) return platformScope
  return undefined
})
const options = computed(() => {
  const projects: { value: number | string; label: string }[] = projectStore.projects.map(project => ({
    value: project.id,
    label: projectStore.projectLabel(project),
  }))
  const selected = selection.value
  if (selected !== undefined && selected !== platformScope && !projects.some(project => project.value === selected)) {
    projects.unshift({ value: selected, label: `现有项目范围 / ${props.modelValue.projectCode || `#${props.modelValue.projectId}`}` })
  }
  return projects
})

function selectProject(value: number | string) {
  if (value === platformScope) {
    emit('update:modelValue', { projectId: null, projectCode: null })
    return
  }
  const project = projectStore.projects.find(project => project.id === value)
  if (project) emit('update:modelValue', { projectId: project.id, projectCode: project.projectCode?.trim() || null })
}

onMounted(() => {
  if (projectStore.status === 'idle') void projectStore.fetchProjects()
})
</script>

<template>
  <el-form-item label="项目范围" required>
    <div class="acl-project-scope-field">
      <el-select :model-value="selection" :loading="projectStore.loading" :disabled="disabled" filterable
        placeholder="选择具体项目" aria-label="调用权限项目范围" @change="selectProject">
        <el-option v-for="project in options" :key="project.value" :label="project.label" :value="project.value" />
        <el-option label="平台范围（适用于所有项目）" :value="platformScope" />
      </el-select>
      <p class="scope-hint">{{ selection === platformScope ? '此规则适用于所有项目，请核对授权范围。' : '项目规则仅匹配所选项目；编辑时保留原有范围。' }}</p>
      <p v-if="projectStore.status === 'error'" class="scope-error" role="alert">
        {{ projectStore.errorMessage }}
        <el-button link type="primary" :disabled="disabled" @click="projectStore.fetchProjects()">重试项目列表</el-button>
      </p>
    </div>
  </el-form-item>
</template>

<style scoped>
.acl-project-scope-field { width: 100%; }
.acl-project-scope-field :deep(.el-select) { width: 100%; }
.scope-hint, .scope-error { margin: 6px 0 0; color: var(--text-secondary); font-size: var(--font-size-sm); line-height: 1.5; }
.scope-error { color: var(--el-color-danger); }
</style>
