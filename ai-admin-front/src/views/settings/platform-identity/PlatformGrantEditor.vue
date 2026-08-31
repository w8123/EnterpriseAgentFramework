<template>
  <div class="grant-editor">
    <div v-if="!modelValue.length" class="grant-empty">
      <el-icon><Lock /></el-icon>
      <div>
        <strong>尚未授权</strong>
        <span>账号可以登录，但不会获得任何平台资源访问权限。</span>
      </div>
    </div>

    <div v-for="(grant, index) in modelValue" :key="`${grant.roleId}-${index}`" class="grant-row">
      <el-select
        :model-value="grant.roleId || undefined"
        filterable
        placeholder="选择角色"
        @update:model-value="updateGrant(index, { roleId: Number($event) })"
      >
        <el-option
          v-for="role in activeRoles"
          :key="role.id"
          :label="`${role.roleName} (${role.roleCode})`"
          :value="role.id"
        >
          <div class="role-option">
            <span>{{ role.roleName }}</span>
            <small>{{ role.roleCode }} · {{ role.roleKind === 'SYSTEM' ? '系统' : '自定义' }}</small>
          </div>
        </el-option>
      </el-select>

      <el-segmented
        :model-value="grant.scopeType"
        :options="scopeOptions"
        @update:model-value="updateScope(index, String($event) as 'GLOBAL' | 'PROJECT')"
      />

      <ProjectMultiSelect
        v-if="grant.scopeType === 'PROJECT'"
        :model-value="grant.projectIds"
        placeholder="选择授权项目"
        @update:model-value="updateGrant(index, { projectIds: $event })"
      />
      <el-input v-else model-value="全部项目" disabled />

      <el-button :icon="Delete" circle plain aria-label="删除授权" @click="removeGrant(index)" />
    </div>

    <el-button :icon="Plus" plain @click="addGrant">添加角色授权</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Delete, Lock, Plus } from '@element-plus/icons-vue'
import ProjectMultiSelect from '@/components/ProjectMultiSelect.vue'
import type { PlatformRoleManagementView } from '@/api/platformAuth'
import type { PlatformGrantEditorRow } from '@/utils/platformUserGrants'

const props = defineProps<{
  modelValue: PlatformGrantEditorRow[]
  roles: PlatformRoleManagementView[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PlatformGrantEditorRow[]]
}>()

const scopeOptions = [
  { label: '全部项目', value: 'GLOBAL' },
  { label: '指定项目', value: 'PROJECT' },
]

const activeRoles = computed(() => props.roles.filter((role) => role.status === 'ACTIVE'))

function replaceRows(rows: PlatformGrantEditorRow[]) {
  emit('update:modelValue', rows)
}

function updateGrant(index: number, patch: Partial<PlatformGrantEditorRow>) {
  replaceRows(props.modelValue.map((row, rowIndex) => (
    rowIndex === index ? { ...row, ...patch } : row
  )))
}

function updateScope(index: number, scopeType: 'GLOBAL' | 'PROJECT') {
  updateGrant(index, { scopeType, projectIds: [] })
}

function removeGrant(index: number) {
  replaceRows(props.modelValue.filter((_, rowIndex) => rowIndex !== index))
}

function addGrant() {
  replaceRows([
    ...props.modelValue,
    { roleId: 0, scopeType: 'PROJECT', projectIds: [] },
  ])
}
</script>

<style scoped lang="scss">
.grant-editor {
  display: grid;
  gap: 12px;
}

.grant-row {
  display: grid;
  grid-template-columns: minmax(170px, 0.9fr) 170px minmax(180px, 1.1fr) 36px;
  gap: 10px;
  align-items: center;
}

.grant-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border: 1px dashed var(--border-divider);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
  background: var(--surface-glass-control);

  .el-icon {
    flex: 0 0 auto;
    font-size: 22px;
  }

  div {
    display: grid;
    gap: 3px;
  }

  strong {
    color: var(--text-primary);
  }

  span {
    font-size: 13px;
  }
}

.role-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  small {
    color: var(--text-secondary);
  }
}

@media (max-width: 980px) {
  .grant-row {
    grid-template-columns: 1fr;
  }
}
</style>
