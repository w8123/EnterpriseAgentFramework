<template>
  <div
    class="project-selector"
    :class="{ compact, 'is-dark': theme === 'dark', 'has-project': hasCurrentProject }"
    :title="scopeTitle"
  >
    <div class="scope-badge">
      <span class="scope-dot" />
      <span class="scope-label">{{ hasCurrentProject ? '当前项目' : '项目范围' }}</span>
    </div>
    <el-select
      class="project-select"
      :model-value="projectStore.currentProjectId"
      :loading="projectStore.loading"
      clearable
      filterable
      placeholder="全部项目"
      size="small"
      :style="{ width: compact ? '240px' : '320px' }"
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
import { computed, onMounted } from 'vue'
import { useProjectStore } from '@/store/project'
import { useTheme } from '@/composables/useTheme'
import { formatProjectKindLabel } from '@/utils/projectLabels'

const projectStore = useProjectStore()
const { theme } = useTheme()

defineProps<{
  compact?: boolean
}>()

const hasCurrentProject = computed(() => projectStore.currentProjectId !== null)
const scopeTitle = computed(() => {
  if (!projectStore.currentProject) return '当前查看全部项目'
  return `当前仅查看项目：${projectStore.projectLabel(projectStore.currentProject)}`
})

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
  gap: 10px;
  min-height: 40px;
  padding: 4px 6px 4px 10px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.07), rgba(255, 255, 255, 0.025)),
    rgba(15, 23, 42, 0.18);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;

  &.has-project {
    border-color: rgba(34, 211, 238, 0.46);
    background:
      linear-gradient(135deg, rgba(34, 211, 238, 0.14), rgba(99, 102, 241, 0.12)),
      rgba(15, 23, 42, 0.22);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.10),
      0 0 0 3px rgba(34, 211, 238, 0.08);
  }

  &.compact {
    min-height: 36px;
    padding: 2px 5px 2px 9px;
    background: #fff;
    border-color: #e4e7ee;

    .scope-label {
      color: #475467;
    }

    :deep(.el-select__wrapper) {
      min-height: 32px;
      border-radius: 8px;
      background: #f8f9fc;
      box-shadow: 0 0 0 1px #e4e7ee inset;
    }
  }
}

.scope-badge {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
}

.scope-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.14);
}

.scope-label {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.project-selector.has-project {
  .scope-dot {
    background: #22d3ee;
    box-shadow:
      0 0 0 3px rgba(34, 211, 238, 0.18),
      0 0 12px rgba(34, 211, 238, 0.62);
  }

  .scope-label {
    color: #67e8f9;
  }

  :deep(.el-select__wrapper) {
    background: rgba(15, 23, 42, 0.42);
    box-shadow: 0 0 0 1px rgba(34, 211, 238, 0.32) inset;
  }

  :deep(.el-select__selected-item) {
    color: #f8fafc;
    font-weight: 700;
  }
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
  .scope-label {
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

.project-selector.compact.has-project {
  background:
    linear-gradient(135deg, rgba(236, 254, 255, 0.95), rgba(238, 242, 255, 0.94)),
    #fff;
  border-color: rgba(6, 182, 212, 0.34);
  box-shadow: 0 0 0 3px rgba(6, 182, 212, 0.08);

  .scope-label {
    color: #0e7490;
  }

  :deep(.el-select__wrapper) {
    background: #fff;
    box-shadow: 0 0 0 1px rgba(6, 182, 212, 0.26) inset;
  }

  :deep(.el-select__selected-item) {
    color: #0f172a;
  }
}

.project-selector.compact.is-dark.has-project {
  background:
    linear-gradient(135deg, rgba(34, 211, 238, 0.14), rgba(99, 102, 241, 0.12)),
    rgba(15, 23, 42, 0.22);
  border-color: rgba(34, 211, 238, 0.46);

  .scope-label {
    color: #67e8f9;
  }
}
</style>
