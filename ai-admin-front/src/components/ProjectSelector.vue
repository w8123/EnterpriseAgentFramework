<template>
  <div class="project-selector">
    <span class="selector-label">项目</span>
    <el-select
      :model-value="projectStore.currentProjectId"
      :loading="projectStore.loading"
      clearable
      filterable
      placeholder="全部项目"
      size="small"
      style="width: 260px"
      @update:model-value="handleChange"
      @visible-change="handleVisibleChange"
    >
      <el-option :value="null" label="全部项目" />
      <el-option
        v-for="project in projectStore.projects"
        :key="project.id"
        :label="projectStore.projectLabel(project)"
        :value="project.id"
      >
        <div class="project-option">
          <span>{{ project.name }}</span>
          <el-tag size="small" type="info">{{ formatProjectKindLabel(project.projectKind || 'SCAN') }}</el-tag>
          <span class="project-code">{{ project.projectCode || `ID ${project.id}` }}</span>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useProjectStore } from '@/store/project'
import { formatProjectKindLabel } from '@/utils/projectLabels'

const projectStore = useProjectStore()

onMounted(() => {
  if (!projectStore.projects.length) {
    projectStore.fetchProjects()
  }
})

function handleChange(value: number | null | undefined) {
  projectStore.setCurrentProject(value ?? null)
}

function handleVisibleChange(visible: boolean) {
  if (visible) {
    projectStore.fetchProjects()
  }
}
</script>

<style scoped lang="scss">
.project-selector {
  display: flex;
  align-items: center;
  gap: 8px;
}

.selector-label {
  color: var(--text-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.project-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.project-code {
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
