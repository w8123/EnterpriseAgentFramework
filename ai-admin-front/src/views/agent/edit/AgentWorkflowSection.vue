<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'workflows'"
    id="agent-config-workflows"
    class="capabilities-panel"
    title="可调用 Workflow"
    density="compact"
  >
    <template #actions>
      <el-button type="primary" :icon="Plus" @click="openWorkflowPicker">
        添加 Workflow
      </el-button>
    </template>
    <div v-if="!generalWorkflowEntries.length" class="capability-empty-state">
      <span class="capability-empty-state__icon"><el-icon><Document /></el-icon></span>
      <strong>暂未添加 Workflow</strong>
    </div>
    <div v-else class="config-asset-list">
      <article v-for="({ row }, position) in generalWorkflowEntries" :key="row.workflowId" class="config-asset-card">
        <header class="config-asset-card__header">
          <span class="config-asset-card__drag" aria-hidden="true"><el-icon><Rank /></el-icon></span>
          <span class="config-asset-card__icon is-workflow"><el-icon><Document /></el-icon></span>
          <div class="config-asset-card__copy">
            <div class="config-asset-card__title">
              <strong>{{ row.workflowName || workflowById(row.workflowId)?.name || row.workflowId }}</strong>
              <span class="asset-pill" :class="riskToneClass(row.riskLevel)">{{ riskLabel(row.riskLevel) }}</span>
            </div>
            <p>{{ workflowSummary(row) }}</p>
          </div>
          <div class="config-asset-card__actions">
            <span>{{ row.enabled !== false ? '已启用' : '已停用' }}</span>
            <el-switch v-model="row.enabled" size="small" :aria-label="`启用 ${row.workflowName || row.workflowId}`" />
            <el-dropdown trigger="click">
              <el-button link :icon="MoreFilled" aria-label="更多操作" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item :disabled="position === 0" @click="moveGeneralWorkflowTool(position, -1)">上移</el-dropdown-item>
                  <el-dropdown-item :disabled="position === generalWorkflowEntries.length - 1" @click="moveGeneralWorkflowTool(position, 1)">下移</el-dropdown-item>
                  <el-dropdown-item divided @click="removeWorkflowToolById(row.workflowId)">移除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <div class="config-asset-card__meta">
          <div>
            <small>版本</small>
            <strong v-if="newerWorkflowVersion(row)" class="is-stale" title="发布 Agent 后才会用到新版本">
              当前 {{ formatWorkflowVersionLabel(row.workflowVersion) }} · 最新 {{ formatWorkflowVersionLabel(newerWorkflowVersion(row)?.latestWorkflowVersion) }}
            </strong>
            <strong v-else>{{ row.workflowVersion || '已发布' }}</strong>
          </div>
          <div><small>操作范围</small><strong>{{ riskLabel(row.riskLevel) }}</strong></div>
          <div><small>调用权限</small><strong :class="{ 'is-ready': row.permissionKey }">{{ row.permissionKey ? '已配置' : '待配置' }}</strong></div>
        </div>

        <details class="config-asset-card__settings">
          <summary>高级</summary>
          <div class="tool-override-grid config-card-settings-grid">
            <el-form-item label="调用标识">
              <el-input v-model="row.toolName" placeholder="通常无需修改" />
            </el-form-item>
            <el-form-item label="操作类型">
              <el-select v-model="row.riskLevel" @change="syncReadOnly(row)">
                <el-option label="只查询" value="READ" />
                <el-option label="会修改数据" value="WRITE" />
                <el-option label="会操作页面" value="PAGE_ACTION" />
                <el-option label="可能无法撤销" value="IRREVERSIBLE" />
              </el-select>
            </el-form-item>
            <el-form-item label="所需权限" class="wide">
              <el-input v-model="row.permissionKey" placeholder="通常无需修改" />
            </el-form-item>
            <el-form-item label="自定义说明" class="wide">
              <el-input v-model="row.descriptionOverride" type="textarea" :rows="2" placeholder="留空沿用 Workflow 描述" />
            </el-form-item>
            <el-form-item label="自定义输入格式（Schema）">
              <el-input v-model="row.inputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空使用默认设置" />
            </el-form-item>
            <el-form-item label="自定义输出格式（Schema）">
              <el-input v-model="row.outputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空使用默认设置" />
            </el-form-item>
          </div>
        </details>
      </article>
    </div>
  </WorkbenchPanel>

  <AppDialog
    v-model="workflowPickerVisible"
    title="添加可调用 Workflow"
    width="900px"
    append-to-body
    destroy-on-close
  >
    <div class="workflow-picker-intro">
      <div>
        <strong>选择已发布的 Workflow</strong>
        <p>添加后，请确认操作类型和所需权限；调用标识通常不用修改。</p>
      </div>
      <span>{{ workflowPickerTotal }} 个可用</span>
    </div>
    <div class="workflow-picker-searchbar">
      <el-input
        v-model="workflowPickerKeyword"
        clearable
        :prefix-icon="Search"
        placeholder="搜索名称、唯一标识、用途、项目或类型"
        @input="scheduleWorkflowPickerSearch"
      />
      <div class="workflow-picker-search-summary">
        <span>{{ workflowPickerTotal }} 个结果</span>
        <i />
        <strong>已选 {{ workflowPickerSelection.length }}</strong>
      </div>
    </div>
    <div class="workflow-picker-results" v-loading="workflowPickerLoading">
      <div v-if="workflowPickerRows.length" class="workflow-picker-list">
        <div
          v-for="workflow in workflowPickerRows"
          :key="workflow.id"
          class="workflow-picker-item"
          :class="{ 'is-selected': workflowPickerSelection.includes(workflow.id) }"
          role="checkbox"
          tabindex="0"
          :aria-checked="workflowPickerSelection.includes(workflow.id)"
          @click="toggleWorkflowPickerRow(workflow.id)"
          @keydown.enter.prevent="toggleWorkflowPickerRow(workflow.id)"
          @keydown.space.prevent="toggleWorkflowPickerRow(workflow.id)"
        >
          <el-checkbox
            class="workflow-picker-checkbox"
            :model-value="workflowPickerSelection.includes(workflow.id)"
            @click.stop
            @change="toggleWorkflowPickerRow(workflow.id, Boolean($event))"
          />
          <div
            class="workflow-avatar"
            :class="workflowAvatarClass(workflow.workflowKind, workflow.definitionAuthority)"
          >
            {{ workflowInitial(workflow.name) }}
          </div>
          <div class="workflow-picker-copy">
            <div class="workflow-picker-name-line">
              <strong>{{ workflow.name }}</strong>
              <el-tag :type="workflowKindTagType(workflow.workflowKind)" effect="light" size="small">
                {{ formatWorkflowKindLabel(workflow.workflowKind) }}
              </el-tag>
            </div>
            <span>{{ workflow.keySlug }}</span>
            <p :title="workflow.description || '暂无用途说明'">
              {{ workflow.description || '暂无用途说明' }}
            </p>
          </div>
          <div class="workflow-picker-project" :title="workflow.projectCode || '平台级 Workflow'">
            <span>所属项目</span>
            <strong>{{ workflow.projectCode || '平台级' }}</strong>
          </div>
          <div v-if="workflowPickerSelection.includes(workflow.id)" class="workflow-picker-selected-mark">
            <el-icon><Check /></el-icon>
          </div>
        </div>
      </div>
      <el-empty
        v-else-if="!workflowPickerLoading"
        class="workflow-picker-no-results"
        description="没有匹配的已发布 Workflow"
        :image-size="72"
      />
    </div>
    <div v-if="workflowPickerTotal" class="workflow-picker-pagination">
      <el-pagination
        v-model:current-page="workflowPickerPage"
        background
        layout="total, prev, pager, next"
        :page-size="workflowPickerPageSize"
        :total="workflowPickerTotal"
        @current-change="loadWorkflowPickerPage"
      />
    </div>
    <template #footer>
      <LiquidGlassButton @click="workflowPickerVisible = false">取消</LiquidGlassButton>
      <LiquidGlassButton type="primary" @click="applyWorkflowPickerSelection">
        添加所选 Workflow（{{ workflowPickerSelection.length }}）
      </LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import { Check, Document, MoreFilled, Plus, Rank, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  workflowPickerVisible,
  workflowPickerKeyword,
  workflowPickerSelection,
  workflowPickerPage,
  workflowPickerPageSize,
  workflowPickerRows,
  workflowPickerTotal,
  workflowPickerLoading,
  isNew,
  activeConfigSection,
  generalWorkflowEntries,
  newerWorkflowVersion,
  riskLabel,
  riskToneClass,
  workflowSummary,
  workflowById,
  openWorkflowPicker,
  scheduleWorkflowPickerSearch,
  toggleWorkflowPickerRow,
  applyWorkflowPickerSelection,
  workflowInitial,
  workflowAvatarClass,
  workflowKindTagType,
  loadWorkflowPickerPage,
  syncReadOnly,
  moveGeneralWorkflowTool,
  removeWorkflowToolById,
  formatWorkflowVersionLabel,
  formatWorkflowKindLabel,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.wide {
  grid-column: span 2;
}

.capability-empty-state {
  display: grid;
  min-height: 280px;
  place-items: center;
  align-content: center;
  gap: 12px;
  color: var(--text-primary);
}

.capability-empty-state__icon,
.config-asset-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
  font-size: 20px;
}

.capability-empty-state__icon.is-collaboration,
.config-asset-card__icon.is-collaboration {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.capability-empty-state__icon.is-skill {
  color: var(--status-info);
}

.capability-empty-state strong {
  font-size: 14px;
  line-height: 22px;
}

.config-asset-list {
  display: grid;
  gap: 12px;
}

.config-asset-card {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight);
}

.config-asset-card__header {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}

.config-asset-card__drag {
  display: grid;
  width: 20px;
  flex: 0 0 20px;
  place-items: center;
  color: var(--text-muted);
  font-size: 16px;
}

.config-asset-card__copy {
  min-width: 0;
  flex: 1;
}

.config-asset-card__title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.config-asset-card__title strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__copy > p {
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 19px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 11px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.asset-pill {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--surface-glass-inset);
  font-size: 11px;
  font-weight: 650;
  line-height: 18px;
  white-space: nowrap;
}

.asset-pill.is-read {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.asset-pill.is-info {
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
}

.asset-pill.is-warning {
  color: var(--status-warning);
  background: color-mix(in srgb, var(--status-warning) 10%, var(--surface-solid-control));
}

.asset-pill.is-danger {
  color: var(--status-danger);
  background: color-mix(in srgb, var(--status-danger) 10%, var(--surface-solid-control));
}

.config-asset-card__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--border-divider);
  background: var(--surface-glass-inset);
}

.config-asset-card__meta > div {
  min-width: 0;
  padding: 10px 16px;
}

.config-asset-card__meta > div + div {
  border-left: 1px solid var(--border-divider);
}

.config-asset-card__meta small,
.config-asset-card__meta strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__meta small {
  color: var(--text-muted);
  font-size: 10px;
}

.config-asset-card__meta strong {
  margin-top: 3px;
  color: var(--text-primary);
  font-size: 12px;
}

.config-asset-card__meta strong.is-stale {
  color: var(--el-color-warning);
}

.config-asset-card__meta strong.is-ready {
  color: var(--status-success);
}

.config-asset-card__settings {
  border-top: 1px solid var(--border-divider);
}

.config-asset-card__settings summary {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  list-style: none;
}

.config-asset-card__settings summary::-webkit-details-marker {
  display: none;
}

.config-asset-card__settings summary::after {
  content: '展开';
  margin-left: auto;
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 650;
}

.config-asset-card__settings[open] summary::after {
  content: '收起';
}

.config-asset-card__settings[open] summary {
  border-bottom: 1px solid var(--border-divider);
}

.config-card-settings-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 16px;
}

.config-card-settings-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.config-asset-card.is-collaboration .config-asset-card__header {
  min-height: 92px;
}

.workbench-layout:not(.has-section-nav) .capability-empty-state {
  min-height: 140px;
}

.workflow-picker-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.workflow-picker-intro strong {
  display: block;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.workflow-picker-intro p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.workflow-picker-intro > span {
  flex: 0 0 auto;
  padding: 5px 10px;
  border-radius: 999px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 12px;
  font-weight: 700;
}

.workflow-picker-searchbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight);
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

.workflow-picker-searchbar :deep(.el-input__wrapper) {
  min-height: 40px;
  border-radius: 9px;
  box-shadow: var(--inner-highlight);
}

.workflow-picker-search-summary {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.workflow-picker-search-summary i {
  width: 1px;
  height: 14px;
  background: var(--border-divider);
}

.workflow-picker-search-summary strong {
  color: var(--brand-active);
}

.workflow-picker-results {
  position: relative;
  min-height: 112px;
}

.workflow-picker-list {
  display: grid;
  gap: 8px;
  max-height: 466px;
  margin-top: 14px;
  padding: 2px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.workflow-picker-item {
  position: relative;
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr) minmax(120px, 170px) 28px;
  align-items: center;
  gap: 13px;
  min-height: 82px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-solid-control);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.workflow-picker-item:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: rgb(var(--brand-primary-rgb) / 0.025);
  box-shadow: var(--shadow-panel);
  transform: translateY(-1px);
}

.workflow-picker-item:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.32);
  outline-offset: 2px;
}

.workflow-picker-item.is-selected {
  border-color: rgb(var(--brand-primary-rgb) / 0.46);
  background: var(--brand-selected-bg);
  box-shadow: var(--shadow-active);
}

.workflow-picker-checkbox {
  flex: 0 0 auto;
}

.workflow-picker-copy {
  min-width: 0;
}

.workflow-picker-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.workflow-picker-name-line > strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > span {
  display: block;
  overflow: hidden;
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project {
  min-width: 0;
  padding-left: 14px;
  border-left: 1px solid var(--border-divider);
}

.workflow-picker-project span,
.workflow-picker-project strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project span {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.workflow-picker-project strong {
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.workflow-picker-selected-mark {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  color: var(--text-inverse);
  background: var(--brand-primary);
  box-shadow: var(--shadow-active);
}

.workflow-picker-no-results {
  min-height: 220px;
}

.workflow-picker-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}

.workflow-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--text-inverse);
  font-weight: 800;
}

.workflow-avatar.chat {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
}

.workflow-avatar.sdk-graph {
  background: linear-gradient(
    135deg,
    var(--status-success),
    color-mix(in srgb, var(--status-success) 72%, var(--status-info))
  );
}

.workflow-avatar.page-action {
  background: linear-gradient(
    135deg,
    var(--status-warning),
    color-mix(in srgb, var(--status-warning) 76%, var(--status-danger))
  );
}

.tool-override-grid {
  display: grid;
  gap: 12px;
  padding: 8px 20px;
}

.skill-binding-identity .el-tag {
  justify-self: start;
}

@media (max-width: 960px) {
  .wide {
      grid-column: auto;
    }

  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}

@media (max-width: 720px) {
  .config-asset-card__header {
      align-items: flex-start;
      flex-wrap: wrap;
    }

  .config-asset-card__actions {
      width: 100%;
      justify-content: flex-end;
      padding-left: 76px;
    }

  .config-asset-card__meta,
    .config-card-settings-grid {
      grid-template-columns: minmax(0, 1fr);
    }

  .config-asset-card__meta > div + div {
      border-top: 1px solid var(--border-divider);
      border-left: 0;
    }

  .config-card-settings-grid .wide {
      grid-column: auto;
    }
}
</style>
