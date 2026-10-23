<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'basic'"
    id="agent-config-basic"
    class="agent-core-panel"
    title="基本信息"
    description="说明这个 Agent 是谁、归属哪个项目，以及谁可以使用。"
    density="compact"
  >
    <template #actions>
      <el-switch
        v-model="form.enabled"
        inline-prompt
        active-text="启"
        inactive-text="停"
      />
    </template>

    <div class="form-grid two agent-identity-grid">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="如：合同审核助手" />
      </el-form-item>
      <el-form-item label="唯一标识" prop="keySlug">
        <el-input v-model="form.keySlug" placeholder="如 contract-review（保存后不可修改）" :disabled="!isNew" />
      </el-form-item>
      <el-form-item label="所属项目">
        <el-select
          v-model="form.projectId"
          clearable
          filterable
          placeholder="平台级 / 全局"
          @change="handleProjectChange"
        >
          <el-option
            v-for="project in writableScanProjects"
            :key="project.id"
            :label="projectOptionLabel(project)"
            :value="project.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="允许使用的角色">
        <el-select
          v-model="form.allowedRoles"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          collapse-tags-tooltip
          placeholder="留空不限制"
        />
      </el-form-item>
      <el-form-item label="描述" class="wide">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="一句话说明职责与适用场景"
        />
      </el-form-item>
    </div>
  </WorkbenchPanel>
</template>

<script setup lang="ts">
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  isNew,
  activeConfigSection,
  writableScanProjects,
  form,
  projectOptionLabel,
  handleProjectChange,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.agent-workbench__main,
.form-grid {
  min-width: 0;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.agent-identity-grid {
  gap: 12px;
}

.agent-identity-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.wide {
  grid-column: span 2;
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

@media (max-width: 960px) {
  .form-grid.two {
      grid-template-columns: 1fr;
    }

  .wide {
      grid-column: auto;
    }
}

@media (max-width: 720px) {
  .config-card-settings-grid .wide {
      grid-column: auto;
    }
}
</style>
