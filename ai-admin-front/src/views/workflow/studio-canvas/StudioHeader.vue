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
        </div>
      </div>
    </div>
    <div class="header-right">
      <span class="save-state" :class="saveStateClass">
        <i></i>
        <span>{{ saveBadgeText }}</span>
      </span>
      <el-button
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
      <el-button :icon="CircleCheck" :loading="validating" @click="emit('validate')">校验</el-button>
      <el-dropdown trigger="click" @command="(command: string | number | object) => emit('command', command)">
        <el-button :icon="MoreFilled">更多</el-button>
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
      <el-button :loading="saving" :disabled="readOnly" @click="emit('save')">保存</el-button>
      <el-button
        v-if="showPageAssistantSync"
        :loading="pageAssistantSyncing"
        :disabled="readOnly"
        @click="emit('sync-page-assistant')"
      >
        同步页面副驾驶
      </el-button>
      <el-button type="primary" :loading="releaseChecking" :disabled="readOnly" @click="emit('publish')">发布</el-button>
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
  gap: 18px;
  padding: 12px 20px;
  border-bottom: 1px solid #e6ebf3;
  background: rgba(255, 255, 255, 0.96);
}

.header-left {
  min-width: 0;
  flex: 1 1 auto;
  gap: 12px;
}

.header-right {
  flex: 0 0 auto;
  gap: 10px;
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
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.studio-title-row h1 {
  overflow: hidden;
  min-width: 0;
  flex: 1 1 auto;
  max-width: min(520px, calc(100vw - 760px));
  margin: 0;
  font-size: 17px;
  font-weight: 800;
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
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.studio-slug-tag {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
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
  gap: 8px;
  min-width: 82px;
  min-height: 40px;
  padding: 0 14px 0 10px;
  border: 1px solid #cdd9ec;
  border-radius: 10px;
  background: linear-gradient(180deg, #ffffff 0%, #f6f9ff 100%);
  color: #1e3a8a;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
  box-shadow: 0 10px 24px rgba(30, 58, 138, 0.12);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
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
  border-radius: 999px;
  background: #e9f0ff;
  color: #3157ff;
  font-size: 16px;
}

.studio-back-btn:hover {
  transform: translateY(-1px);
  border-color: #9db5ff;
  box-shadow: 0 14px 30px rgba(49, 87, 255, 0.18);
}

.studio-back-btn:active {
  transform: translateY(0);
}

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: rgba(248, 250, 252, 0.9);
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.save-state i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.12);
}

.save-state.is-pending i {
  background: #f97316;
  box-shadow: 0 0 0 3px rgba(249, 115, 22, 0.16);
}

.save-state.is-pending {
  border-color: #fed7aa;
  background: #fff7ed;
  color: #c2410c;
}

.save-state.is-saving i {
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}

.save-state.is-saving {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.save-state.is-unpublished i {
  background: #d97706;
  box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.14);
}

.save-state.is-unpublished {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}

.save-state.is-saved i {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.14);
}

.save-state.is-saved {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #15803d;
}

@media (max-width: 1120px) {
  .studio-header {
    height: auto;
    min-height: 66px;
    align-items: flex-start;
    flex-direction: column;
    padding: 12px 16px;
  }

  .header-right {
    width: 100%;
    flex-wrap: wrap;
  }
}

@media (max-width: 760px) {
  .studio-title-row h1 {
    max-width: calc(100vw - 120px);
  }
}

.run-mode {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 18px;
}

.run-mode.is-trial {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}
</style>
