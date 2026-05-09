<template>
  <div class="project-selector" :class="{ compact, 'is-dark': theme === 'dark' }">
    <span class="selector-label">项目</span>
    <el-select
      :model-value="projectStore.currentProjectId"
      :loading="projectStore.loading"
      clearable
      filterable
      placeholder="全部项目"
      size="small"
      :style="{ width: compact ? '220px' : '260px' }"
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
import { useTheme } from '@/composables/useTheme'
import { formatProjectKindLabel } from '@/utils/projectLabels'

const projectStore = useProjectStore()
const { theme } = useTheme()

defineProps<{
  compact?: boolean
}>()

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

  &.compact {
    .selector-label {
      color: #475467;
      font-weight: 700;
    }

    :deep(.el-select__wrapper) {
      min-height: 32px;
      border-radius: 8px;
      background: #f8f9fc;
      box-shadow: 0 0 0 1px #e4e7ee inset;
    }
  }
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

.project-selector.compact.is-dark {
  .selector-label {
    color: #94a3b8;
  }

  :deep(.el-select__wrapper) {
    background: rgba(255, 255, 255, 0.04);
    box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
  }

  :deep(.el-select__selected-item),
  :deep(.el-select__placeholder) {
    color: #cbd5e1;
  }

  :deep(.el-select__caret) {
    color: #94a3b8;
  }
}
</style>
