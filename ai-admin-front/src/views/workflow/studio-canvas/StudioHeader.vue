<template>
  <header class="studio-header">
    <div class="header-left">
      <el-tooltip content="返回 Workflow 列表" placement="bottom">
        <button class="studio-back-btn" type="button" @click="emit('back')">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回</span>
        </button>
      </el-tooltip>
      <div class="studio-title-wrap">
        <div class="studio-title-row">
          <span class="studio-eyebrow">Workflow 编排</span>
          <el-tooltip :content="displayName" placement="bottom" :show-after="400">
            <h1>{{ displayName }}</h1>
          </el-tooltip>
        </div>
        <div class="studio-meta-row">
          <el-tag size="small" effect="plain">{{ visibilityLabel }}</el-tag>
          <el-tag
            size="small"
            :type="validationTagType"
            effect="plain"
          >
            {{ validationLabel }}
          </el-tag>
          <el-tooltip
            v-if="keySlug"
            :content="keySlug"
            placement="bottom"
            :show-after="400"
          >
            <el-tag size="small" type="info" class="studio-slug-tag">{{ keySlug }}</el-tag>
          </el-tooltip>
          <el-tag size="small" type="success">{{ projectCode || '全局' }}</el-tag>
          <el-tag v-if="viewOnly" size="small" type="warning" effect="plain" class="studio-view-only-tag">只读查看</el-tag>
          <span v-if="saveBadgeText !== visibilityLabel" class="save-state" :class="saveStateClass" role="status">
            <i aria-hidden="true"></i>
            <span>{{ saveBadgeText }}</span>
          </span>
        </div>
      </div>
    </div>
    <div class="header-right">
      <div class="header-action-group" role="group" aria-label="检查与调试">
        <el-button
          v-if="!hideRun"
          class="studio-run-button"
          :icon="VideoPlay"
          :loading="debugLoading"
          :title="readOnlyTrial
            ? '草稿调用了业务方法或 API：真实调用业务系统，只允许只读操作，先保存草稿再运行'
            : '在沙箱里运行当前草稿，不调用业务系统'"
          @click="emit('run')"
        >
          调试
          <span class="run-mode" :class="{ 'is-trial': readOnlyTrial }">{{ readOnlyTrial ? '只读试运行' : '沙箱' }}</span>
        </el-button>
        <el-button class="studio-validate-button" :icon="CircleCheck" :loading="validating" @click="emit('validate')">校验</el-button>
      </div>
      <span v-if="!viewOnly" class="header-action-divider" aria-hidden="true"></span>
      <div v-if="!viewOnly" class="header-action-group" role="group" aria-label="保存与发布">
        <el-button class="studio-save-button" :icon="DocumentChecked" :loading="saving" :disabled="readOnly" @click="emit('save')">保存</el-button>
        <el-button
          v-if="showPageAssistantSync"
          :loading="pageAssistantSyncing"
          :disabled="readOnly"
          @click="emit('sync-page-assistant')"
        >
          同步页面副驾驶
        </el-button>
        <el-button class="studio-publish-button" type="primary" :icon="Promotion" :loading="releaseChecking" :disabled="readOnly" @click="emit('publish')">发布</el-button>
      </div>
      <el-dropdown trigger="click" popper-class="studio-actions-menu" @command="(command: string | number | object) => emit('command', command)">
        <el-button class="studio-more-button" :icon="MoreFilled" aria-label="更多操作" title="更多操作" />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="eval" :icon="Finished">到运行中心评测</el-dropdown-item>
            <el-dropdown-item command="json" :icon="DocumentChecked">Workflow 源码</el-dropdown-item>
            <el-dropdown-item command="fit" :icon="Aim">聚焦画布</el-dropdown-item>
            <el-dropdown-item command="layout" :icon="Rank">自动整理</el-dropdown-item>
            <el-dropdown-item command="versions" :icon="Tickets">版本记录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import {
  Aim,
  ArrowLeft,
  CircleCheck,
  DocumentChecked,
  Finished,
  MoreFilled,
  Promotion,
  Rank,
  Tickets,
  VideoPlay,
} from '@element-plus/icons-vue'

defineProps<{
  displayName: string
  visibilityLabel: string
  validationLabel: string
  validationTagType: 'danger' | 'warning' | 'success' | 'info'
  keySlug: string
  projectCode: string
  saveStateClass: Record<string, boolean>
  saveBadgeText: string
  readOnly: boolean
  /** The caller's role may only look at this Workflow: save, publish and AI edit are hidden, not disabled. */
  viewOnly?: boolean
  /** The role lacks workflow:debug, so 调试 is hidden. */
  hideRun?: boolean
  debugLoading: boolean
  /** The draft calls business methods or APIs, so "调试" runs a read-only trial instead of a sandbox. */
  readOnlyTrial: boolean
  validating: boolean
  saving: boolean
  releaseChecking: boolean
  showPageAssistantSync: boolean
  pageAssistantSyncing: boolean
}>()

const emit = defineEmits<{
  back: []
  run: []
  validate: []
  command: [command: string | number | object]
  save: []
  'sync-page-assistant': []
  publish: []
}>()
</script>

<style scoped lang="scss">
.studio-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 76px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-shell);
  box-shadow: var(--glass-rim-double);
  -webkit-backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
  color: var(--text-primary);
}

.header-left {
  min-width: 0;
  flex: 1 1 auto;
  gap: 16px;
}

.header-right {
  flex: 0 0 auto;
  gap: 12px;
}

.header-action-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.header-right .el-button {
  height: 36px;
  margin: 0;
  padding: 0 13px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
  transition: background var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.header-right .el-button :deep(.el-icon) {
  font-size: 16px;
}

.header-action-divider {
  width: 1px;
  height: 22px;
  background: var(--border-divider);
}

.header-right .studio-run-button {
  border-color: color-mix(in srgb, var(--brand-primary) 24%, var(--border-subtle));
  background: color-mix(in srgb, var(--brand-primary) 5%, var(--surface-solid-overlay));
  color: var(--text-primary);
}

.header-right .studio-run-button:not(.is-disabled):hover {
  border-color: var(--border-focus);
  background: color-mix(in srgb, var(--brand-primary) 10%, var(--surface-solid-overlay));
}

.header-right .studio-run-button :deep(.el-icon) {
  color: var(--brand-primary);
}

.header-right .studio-validate-button,
.header-right .studio-more-button {
  border-color: transparent;
  background: transparent;
  color: var(--text-secondary);
  box-shadow: none;
}

.header-right .studio-validate-button:not(.is-disabled):hover,
.header-right .studio-more-button:hover {
  background: var(--el-fill-color-light);
  color: var(--text-primary);
}

.header-right .studio-save-button {
  border-color: var(--border-readable);
  background: var(--surface-solid-control);
  color: var(--text-secondary);
}

.header-right .studio-save-button:not(.is-disabled):hover {
  border-color: var(--border-focus);
  color: var(--brand-primary);
}

.header-right .studio-publish-button {
  padding: 0 17px;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 3px 8px rgb(var(--brand-primary-rgb) / 0.2), inset 0 1px 0 rgb(255 255 255 / 0.16);
}

.header-right .studio-publish-button:not(.is-disabled):hover {
  box-shadow: 0 4px 12px rgb(var(--brand-primary-rgb) / 0.32);
}

.header-right .studio-publish-button.is-disabled {
  background: var(--el-button-disabled-bg-color);
  box-shadow: none;
}

.header-right .studio-more-button {
  width: 32px;
  padding: 0;
}

.header-right .el-button:focus-visible,
.studio-back-btn:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 3px;
}

.studio-title-wrap {
  min-width: 0;
  flex: 1 1 auto;
}

.studio-title-row {
  gap: 10px;
  min-width: 0;
}

.studio-eyebrow {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
}

.studio-title-row h1 {
  overflow: hidden;
  min-width: 0;
  flex: 1 1 auto;
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-title-row :deep(.el-tooltip__trigger) {
  display: block;
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
}

.studio-meta-row {
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.studio-meta-row :deep(.el-tag) {
  height: 20px;
  padding: 0 6px;
  border: 0;
  border-radius: 4px;
  font-size: 11px;
}

.studio-slug-tag {
  max-width: 210px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
}

.header-left,
.header-right,
.studio-title-row,
.studio-meta-row {
  display: flex;
  align-items: center;
}

.studio-back-btn {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 10px 0 6px;
  border: 1px solid var(--border-subtle);
  border-radius: 9px;
  background: var(--surface-solid-control);
  color: var(--text-secondary);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.studio-back-btn span {
  white-space: nowrap;
}

.studio-back-btn .el-icon {
  flex: 0 0 24px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  color: var(--text-muted);
  font-size: 16px;
}

.studio-back-btn:hover {
  border-color: var(--border-focus);
  background: var(--el-fill-color-light);
  color: var(--brand-primary);
}

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 20px;
  margin-left: 4px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}

.save-state i {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: currentColor;
}

.save-state.is-pending {
  color: var(--status-warning);
}

.save-state.is-saving {
  color: var(--status-info);
}

.save-state.is-unpublished {
  color: var(--status-warning);
}

.save-state.is-saved {
  color: var(--status-success);
}

@media (max-width: 1240px) {
  .studio-header {
    height: auto;
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px;
  }

  .header-right {
    width: 100%;
    flex-wrap: wrap;
  }

  .header-left {
    width: 100%;
  }
}

@media (max-width: 760px) {
  .studio-header {
    gap: 10px;
    padding: 10px 12px;
  }

  .header-left {
    gap: 10px;
  }

  .studio-eyebrow,
  .studio-slug-tag {
    display: none;
  }

  .header-right {
    gap: 6px;
  }

  .header-action-group {
    gap: 4px;
  }

  .header-right .el-button {
    padding: 0 10px;
  }

  .header-right .studio-more-button {
    padding: 0;
  }

  .run-mode {
    display: none;
  }
}

.run-mode {
  margin-left: 8px;
  padding-left: 8px;
  border-left: 1px solid var(--border-divider);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 500;
  line-height: 14px;
}

.run-mode.is-trial {
  color: var(--status-warning);
}

@media (prefers-reduced-motion: reduce) {
  .header-right .el-button,
  .studio-back-btn {
    transition: none;
  }
}
</style>
