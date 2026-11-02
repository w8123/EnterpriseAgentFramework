<script setup lang="ts">
import { Check } from '@element-plus/icons-vue'
import AppDialog from './AppDialog.vue'
import type { SidebarWorkspace, SidebarWorkspaceKey } from './sidebarWorkspaces'

const props = defineProps<{
  modelValue: boolean
  workspaces: readonly SidebarWorkspace[]
  current?: SidebarWorkspaceKey
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  select: [key: SidebarWorkspaceKey]
}>()

function choose(key: SidebarWorkspaceKey) {
  emit('update:modelValue', false)
  if (key !== props.current) emit('select', key)
}
</script>

<template>
  <AppDialog
    :model-value="props.modelValue"
    title="切换角色"
    description="切换后菜单只显示该角色的工作内容，账号权限不变。"
    width="min(440px, calc(100vw - 32px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="role-switch-list" role="radiogroup" aria-label="切换角色">
      <button
        v-for="workspace in props.workspaces"
        :key="workspace.key"
        class="role-switch-option"
        :class="{ active: workspace.key === props.current }"
        type="button"
        role="radio"
        :aria-checked="workspace.key === props.current"
        :data-workspace="workspace.key"
        @click="choose(workspace.key)"
      >
        <span class="role-switch-text">
          <strong>{{ workspace.label }}</strong>
          <small>{{ workspace.description }}</small>
        </span>
        <el-icon v-if="workspace.key === props.current" class="role-switch-check"><Check /></el-icon>
      </button>
    </div>
  </AppDialog>
</template>

<style scoped lang="scss">
.role-switch-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.role-switch-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;

  &:hover,
  &.active {
    border-color: color-mix(in srgb, var(--brand-primary) 40%, transparent);
    background: color-mix(in srgb, var(--brand-primary) 8%, transparent);
  }
}

.role-switch-text {
  display: flex;
  flex-direction: column;
  gap: 2px;

  strong {
    font-size: 14px;
    font-weight: 700;
  }

  small {
    color: var(--text-secondary);
    font-size: 12px;
  }
}

.role-switch-check {
  color: var(--brand-primary);
  font-size: 16px;
}
</style>
