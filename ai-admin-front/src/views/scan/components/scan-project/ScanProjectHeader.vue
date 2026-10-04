<script setup lang="ts">
import { ArrowDown, Connection, MagicStick, MoreFilled, Plus, Refresh, Tools } from '@element-plus/icons-vue'
import type { ScanProject } from '@/types/scanProject'
import type { ApiGovernanceAction, ApiGovernanceAdvice } from '@/views/scan/composables/useScanProjectSummary'

defineProps<{
  project: ScanProject | null
  stageAdvice: ApiGovernanceAdvice
  primaryActionLoading: boolean
  reconcileLoading: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  primaryAction: [action: ApiGovernanceAction]
  openModelGeneratePanel: []
  openOpsPanel: []
  reconcile: []
  openImportDialog: []
}>()

type MoreCommand = 'modelSettings' | 'reconcile' | 'ops'

function onMoreCommand(command: MoreCommand) {
  if (command === 'modelSettings') {
    emit('openModelGeneratePanel')
  } else if (command === 'reconcile') {
    emit('reconcile')
  } else {
    emit('openOpsPanel')
  }
}
</script>

<template>
  <section class="project-hero api-catalog-hero">
    <div class="hero-copy">
      <span class="hero-accent" aria-hidden="true" />
      <div class="project-title-block">
        <div class="title-row">
          <h1>API 管理</h1>
          <el-tag v-if="project?.name" class="project-context-tag" effect="plain">
            项目：{{ project.name }}
          </el-tag>
        </div>
        <p class="hero-description">
          发现业务系统来源并保留同步与语义证据；业务方法/API 的接纳、连接和调用在对应项目目录完成。
        </p>
      </div>
    </div>
    <div class="hero-actions asset-actions">
      <el-button plain :icon="Plus" @click="emit('openImportDialog')">
        添加接口
      </el-button>
      <el-button
        v-if="stageAdvice.primaryAction !== 'importApi'"
        class="primary-action"
        type="primary"
        :icon="MagicStick"
        :loading="primaryActionLoading"
        @click="emit('primaryAction', stageAdvice.primaryAction)"
      >
        {{ stageAdvice.primaryLabel }}
      </el-button>
      <el-tooltip
        v-if="stageAdvice.secondaryAction === 'refresh'"
        :content="stageAdvice.secondaryLabel || '刷新同步状态'"
        placement="bottom"
      >
        <el-button
          plain
          :icon="Refresh"
          aria-label="刷新同步状态"
          circle
          :loading="loading"
          @click="emit('primaryAction', 'refresh')"
        />
      </el-tooltip>
      <el-button
        v-else-if="stageAdvice.secondaryLabel && stageAdvice.secondaryAction"
        plain
        @click="emit('primaryAction', stageAdvice.secondaryAction)"
      >
        {{ stageAdvice.secondaryLabel }}
      </el-button>
      <el-dropdown trigger="click" @command="onMoreCommand">
        <el-button plain :icon="MoreFilled">
          更多操作
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="modelSettings" :icon="MagicStick">AI 语义生成</el-dropdown-item>
            <el-dropdown-item command="reconcile" :icon="Connection" :disabled="reconcileLoading">
              只读核对来源关联
            </el-dropdown-item>
            <el-dropdown-item command="ops" :icon="Tools">维护动作</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </section>
</template>
