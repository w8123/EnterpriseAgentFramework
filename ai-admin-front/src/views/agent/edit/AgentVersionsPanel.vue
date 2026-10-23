<template>
  <section class="agent-version-section">
    <WorkbenchPanel
      title="配置版本"
      description="查看当前线上版本和历史快照；历史版本可以复制为新的可编辑草稿。"
      density="compact"
    >
      <template #actions>
        <StatusTag v-if="activeConfig" :label="`线上 v${activeConfig.versionNo}`" tone="success" />
        <StatusTag v-if="draftConfig" :label="`草稿 v${draftConfig.versionNo}`" tone="warning" />
      </template>
      <el-table :data="configVersions" class="config-version-table agent-version-table">
        <el-table-column label="版本" width="90"><template #default="{ row }"><strong>v{{ row.versionNo }}</strong></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="versionStatusType(row.status)">{{ configVersionStatusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="Workflow" width="100"><template #default="{ row }">{{ row.tools?.length || 0 }} 个</template></el-table-column>
        <el-table-column label="Skill" width="100"><template #default="{ row }">{{ row.skills?.length || 0 }} 个</template></el-table-column>
        <el-table-column label="更新时间" min-width="168"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <el-button link @click="viewVersionInConfig(row)">查看</el-button>
            <el-button v-if="row.status !== 'DRAFT' && canWriteAgent" link type="primary" @click="handleCopyVersion(row)">复制为草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
    </WorkbenchPanel>
  </section>
</template>

<script setup lang="ts">
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  isNew,
  activeSection,
  configVersions,
  canWriteAgent,
  activeConfig,
  draftConfig,
  viewVersionInConfig,
  handleCopyVersion,
  versionStatusType,
  configVersionStatusLabel,
  formatDateTime,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.agent-overview-scroll,
.agent-version-section {
  min-height: 0;
  flex: 1 1 auto;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 1px 2px 2px 1px;
  scrollbar-gutter: stable;
}

.agent-version-section :deep(.workbench-panel) {
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.agent-version-table :deep(.el-table__cell) {
  padding-top: 11px;
  padding-bottom: 11px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.workflow-tool-table,
.config-version-table {
  margin-top: 12px;
}

.skill-binding-identity .el-tag {
  justify-self: start;
}

@media (max-width: 960px) {
  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}
</style>
