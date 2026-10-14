<script setup lang="ts">
import { ArrowDown, Connection, Download, MagicStick, MoreFilled, Refresh, Search, Tools } from '@element-plus/icons-vue'
import type { ApiGovernanceAction, ApiGovernanceAdvice } from '@/views/capability/source/composables/useSourceSummary'
import type { BusinessCapabilityWorkspace } from '../../businessCapabilityRoutes'

const props = withDefaults(defineProps<{
  stageAdvice: ApiGovernanceAdvice
  primaryActionLoading: boolean
  reconcileLoading: boolean
  loading: boolean
  workspace: BusinessCapabilityWorkspace
  sourceCount?: number
  sensitiveScanStarting?: boolean
  sensitiveTaskPolling?: boolean
  sensitiveStatusError?: string
  exportLoading?: boolean
  canWrite?: boolean
}>(), { sourceCount: 0, sensitiveScanStarting: false, sensitiveTaskPolling: false, sensitiveStatusError: '', exportLoading: false, canWrite: false })

const emit = defineEmits<{
  primaryAction: [action: ApiGovernanceAction]
  openModelGeneratePanel: []
  reconcile: []
  openImportDialog: []
  startSensitiveDataScan: []
  exportExcel: []
}>()

type MoreCommand = 'modelSettings' | 'reconcile' | 'scanSensitive' | 'export' | 'scanRules'

function onMoreCommand(command: MoreCommand) {
  if (command === 'modelSettings') {
    emit('openModelGeneratePanel')
  } else if (command === 'reconcile') {
    if (!props.reconcileLoading) emit('reconcile')
  } else if (command === 'scanSensitive') {
    if (props.canWrite && props.sourceCount && !props.loading && !props.sensitiveScanStarting && !props.sensitiveTaskPolling && !props.sensitiveStatusError) emit('startSensitiveDataScan')
  } else if (command === 'export') {
    if (props.sourceCount && !props.loading && !props.exportLoading) emit('exportExcel')
  } else if (command === 'scanRules') {
    emit('primaryAction', 'scanRules')
  }
}
</script>

<template>
  <section v-if="workspace === 'sources'" class="source-workbench-actions source-workbench-actions--inline" aria-label="接入与变更操作">
    <div class="source-workbench-actions__primary">
      <el-button
        type="primary"
        :icon="Connection"
        @click="emit('openImportDialog')"
      >
        同步与发现
      </el-button>
    </div>
    <div class="source-workbench-actions__secondary">
      <el-button :icon="Refresh" :loading="loading" aria-label="刷新来源" @click="emit('primaryAction', 'refresh')">刷新</el-button>
      <el-dropdown trigger="click" @command="onMoreCommand">
        <el-button plain :icon="MoreFilled">
          更多操作
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
              <el-dropdown-item v-if="canWrite" command="scanSensitive" :icon="Search" :disabled="!sourceCount || loading || sensitiveScanStarting || sensitiveTaskPolling || !!sensitiveStatusError">扫描敏感数据</el-dropdown-item>
              <el-dropdown-item command="export" :icon="Download" :disabled="!sourceCount || loading || exportLoading">导出 EXCEL</el-dropdown-item>
              <el-dropdown-item v-if="stageAdvice.secondaryAction === 'scanRules'" command="scanRules" :icon="Tools">配置扫描规则</el-dropdown-item>
            <el-dropdown-item command="modelSettings" :icon="MagicStick">批量补充说明</el-dropdown-item>
            <el-dropdown-item command="reconcile" :icon="Connection" divided :disabled="reconcileLoading">
              核对来源关联
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </section>
</template>

<style scoped lang="scss">
.source-workbench-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--density-compact-section-gap);
}
.source-workbench-actions__primary,
.source-workbench-actions__secondary {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.source-workbench-actions__secondary { margin-left: auto; }
.source-workbench-actions :deep(.el-button + .el-button) { margin-left: 0; }
.source-workbench-actions--inline { gap: 8px; flex-wrap: nowrap; }
.source-workbench-actions--inline :deep(.el-button) { height: var(--project-list-control-height, var(--density-comfortable-control-height)); border-radius: var(--radius-md); }
.source-workbench-actions--inline :deep(.el-button--default) { background: var(--surface-glass-control); box-shadow: var(--inner-highlight); }
.source-workbench-actions--inline :deep(.el-button--primary) { background: var(--brand-primary-gradient); box-shadow: var(--shadow-active); border-color: transparent; }
@media (max-width: 640px) {
  .source-workbench-actions__primary { width: 100%; flex-wrap: nowrap; }
  .source-workbench-actions__primary :deep(.el-button) { flex: 1; min-width: 0; padding-inline: 10px; font-size: 12px; }
  .source-workbench-actions__secondary { margin-left: 0; }
  .source-workbench-actions--inline { width: 100%; flex-wrap: wrap; }
  .source-workbench-actions--inline .source-workbench-actions__primary { flex: 1 0 100%; width: 100%; }
  .source-workbench-actions--inline .source-workbench-actions__secondary { width: 100%; flex-wrap: nowrap; }
  .source-workbench-actions--inline .source-workbench-actions__secondary > .el-button,
  .source-workbench-actions--inline .source-workbench-actions__secondary :deep(.el-dropdown) { flex: 1; min-width: 0; }
  .source-workbench-actions--inline .source-workbench-actions__secondary :deep(.el-dropdown > .el-button) { width: 100%; }
}
</style>
