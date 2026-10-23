<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'remote'"
    id="agent-config-remote"
    class="a2a-bindings-panel"
    title="Agent 协作"
    density="compact"
  >
    <template #actions>
      <el-button type="primary" :icon="Plus" :disabled="isNew" @click="openA2aBindingDialog">
        添加 Agent
      </el-button>
    </template>
    <LiquidGlassNotice
      v-if="isNew"
      type="info"
      title="请先保存当前 Agent，再添加协作 Agent。"
      source="backdrop"
    />
    <div v-else-if="!remoteAgentBindings.length" class="capability-empty-state">
      <span class="capability-empty-state__icon is-collaboration"><el-icon><Connection /></el-icon></span>
      <strong>暂未添加 Agent</strong>
    </div>
    <div v-else class="config-asset-list">
      <article v-for="(row, index) in remoteAgentBindings" :key="row.remoteAgentRevisionId" class="config-asset-card is-collaboration">
        <header class="config-asset-card__header">
          <span class="config-asset-card__icon is-collaboration"><el-icon><Connection /></el-icon></span>
          <div class="config-asset-card__copy">
            <div class="config-asset-card__title"><strong>{{ row.remoteAgentKey || `Agent #${row.remoteAgentId}` }}</strong></div>
            <p>{{ row.description || '用于完成已授权的协作任务' }}</p>
            <div class="config-asset-card__tags">
              <span class="asset-pill is-info">外部 A2A</span>
              <span class="asset-pill" :class="riskToneClass(row.riskLevel)">{{ riskLabel(row.riskLevel) }}</span>
              <span class="asset-pill">最长等待 {{ Math.round((row.timeoutMs || 0) / 1000) }} 秒</span>
            </div>
          </div>
          <div class="config-asset-card__actions">
            <el-switch v-model="row.enabled" size="small" :aria-label="`启用 ${row.remoteAgentKey || '协作 Agent'}`" />
            <el-dropdown trigger="click">
              <el-button link :icon="MoreFilled" aria-label="更多操作" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item :disabled="index === 0" @click="moveA2aBinding(index, -1)">上移</el-dropdown-item>
                  <el-dropdown-item :disabled="index === remoteAgentBindings.length - 1" @click="moveA2aBinding(index, 1)">下移</el-dropdown-item>
                  <el-dropdown-item divided @click="removeA2aBinding(index)">移除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>
        <details class="config-asset-card__settings">
          <summary>高级</summary>
          <div class="tool-override-grid config-card-settings-grid">
            <el-form-item label="调用标识"><el-input v-model="row.toolName" placeholder="通常无需修改" /></el-form-item>
            <el-form-item label="操作类型"><el-select v-model="row.riskLevel"><el-option label="只查询" value="READ" /><el-option label="会修改数据" value="WRITE" /><el-option label="可能无法撤销" value="IRREVERSIBLE" /></el-select></el-form-item>
            <el-form-item label="所需权限" class="wide"><el-input v-model="row.permissionKey" placeholder="通常无需修改" /></el-form-item>
            <el-form-item label="可委派任务" class="wide"><div class="config-asset-task-list"><el-tag v-for="skillId in row.allowedSkillIds" :key="skillId" size="small" :title="skillId">{{ a2aSkillLabel(row, skillId) }}</el-tag></div></el-form-item>
          </div>
        </details>
      </article>
    </div>
  </WorkbenchPanel>

  <AppDialog
    v-model="a2aBindingDialogVisible"
    title="添加协作 Agent"
    width="min(720px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div class="a2a-dialog-shell" v-loading="a2aBindingLoading">
      <header class="a2a-dialog-intro">
        <span class="a2a-dialog-intro__icon"><el-icon><Connection /></el-icon></span>
        <div class="a2a-dialog-intro__copy">
          <small>AGENT COLLABORATION</small>
          <strong>把任务交给可信的外部 Agent</strong>
          <p>仅显示已经建立信任并通过评审的远程 Agent。</p>
        </div>
        <span class="a2a-dialog-intro__protocol">A2A</span>
      </header>

      <section v-if="!localAgentPrincipals.length" class="a2a-dialog-empty">
        <span class="a2a-dialog-empty__icon"><el-icon><Connection /></el-icon></span>
        <small class="a2a-dialog-empty__eyebrow">还差 1 项准备</small>
        <h3>为当前 Agent 创建协作身份</h3>
        <p>协作身份用于识别任务由谁发起，并套用对应的信任与权限策略。完成后再回来选择远程 Agent。</p>
        <ol class="a2a-setup-path" aria-label="添加协作 Agent 的步骤">
          <li class="is-current"><span>1</span><div><strong>创建协作身份</strong><small>当前待完成</small></div></li>
          <li><span>2</span><div><strong>选择远程 Agent</strong><small>从可信目录选择</small></div></li>
          <li><span>3</span><div><strong>确认委派范围</strong><small>添加到当前 Agent</small></div></li>
        </ol>
      </section>

      <section v-else-if="!trustedRemoteAgents.length" class="a2a-dialog-empty">
        <span class="a2a-dialog-empty__icon is-ready"><el-icon><Check /></el-icon></span>
        <small class="a2a-dialog-empty__eyebrow is-ready">协作身份已就绪</small>
        <h3>还没有可选择的可信 Agent</h3>
        <p>请先在 A2A 互联中心发现远程 Agent，完成评审并建立信任，再回到这里添加。</p>
        <ol class="a2a-setup-path" aria-label="添加协作 Agent 的步骤">
          <li class="is-done"><span>1</span><div><strong>创建协作身份</strong><small>已经完成</small></div></li>
          <li class="is-current"><span>2</span><div><strong>建立远程信任</strong><small>当前待完成</small></div></li>
          <li><span>3</span><div><strong>确认委派范围</strong><small>添加到当前 Agent</small></div></li>
        </ol>
      </section>

      <el-form v-else class="a2a-dialog-form" label-position="top">
        <div class="a2a-dialog-choice-grid">
          <section class="a2a-dialog-choice-card">
            <div class="a2a-dialog-choice-card__heading">
              <span>1</span>
              <div><strong>选择发起身份</strong><small>代表当前 Agent 发起协作</small></div>
            </div>
            <el-select v-model="a2aPrincipalSelection" filterable placeholder="请选择协作身份">
              <el-option v-for="principal in localAgentPrincipals" :key="principal.id" :label="`${principal.displayName} · ${principal.principalKey}`" :value="principal.id" />
            </el-select>
          </section>

          <section class="a2a-dialog-choice-card">
            <div class="a2a-dialog-choice-card__heading">
              <span>2</span>
              <div><strong>选择协作对象</strong><small>只列出已建立信任的 Agent</small></div>
            </div>
            <el-select v-model="a2aRemoteAgentSelection" filterable placeholder="请选择远程 Agent" @change="handleA2aRemoteAgentChanged">
              <el-option v-for="remote in trustedRemoteAgents" :key="remote.id" :label="`${remote.displayName} · ${remote.remoteAgentKey}`" :value="remote.id" />
            </el-select>
          </section>
        </div>

        <article v-if="selectedA2aRemoteAgent" class="a2a-dialog-agent-preview">
          <span class="a2a-dialog-agent-preview__avatar">{{ selectedA2aRemoteAgent.displayName.trim().slice(0, 1) || 'A' }}</span>
          <div class="a2a-dialog-agent-preview__copy">
            <strong>{{ selectedA2aRemoteAgent.displayName }}</strong>
            <span>{{ selectedA2aRemoteAgent.remoteAgentKey }} · {{ selectedA2aRemoteAgent.tenantScope || '全局范围' }}</span>
            <p>{{ selectedA2aRemoteRevision?.description || selectedA2aRemoteAgent.lastHealthSummary || '已通过信任评审，可按授权范围接收委派任务。' }}</p>
          </div>
          <div class="a2a-dialog-agent-preview__tags">
            <el-tag type="success" effect="plain" size="small">已信任</el-tag>
            <el-tag :type="selectedA2aRemoteAgent.callable ? 'success' : 'warning'" effect="plain" size="small">
              {{ selectedA2aRemoteAgent.callable ? '可调用' : '待就绪' }}
            </el-tag>
          </div>
        </article>

        <section class="a2a-dialog-task-section">
          <div class="a2a-dialog-section-heading">
            <div><strong>允许委派的任务</strong><small>只开放当前 Agent 真正需要使用的任务</small></div>
            <span>{{ a2aBindingForm.allowedSkillIds.length }} 项</span>
          </div>
          <el-select
            v-model="a2aBindingForm.allowedSkillIds"
            multiple
            filterable
            collapse-tags
            :max-collapse-tags="2"
            :disabled="!selectedA2aRemoteRevision"
            :placeholder="selectedA2aRemoteAgent ? '请选择允许委派的任务' : '请先选择远程 Agent'"
          >
            <el-option v-for="skill in selectedA2aRemoteRevision?.protocolSkills || []" :key="skill.id" :label="`${skill.name} · ${skill.id}`" :value="skill.id" />
          </el-select>
        </section>

        <details class="a2a-dialog-settings">
          <summary><span><strong>调用设置</strong><small>系统已自动生成，通常无需修改</small></span></summary>
          <div class="a2a-dialog-settings__grid">
            <el-form-item label="调用标识"><el-input v-model="a2aBindingForm.toolName" placeholder="通常无需修改" /></el-form-item>
            <el-form-item label="所需权限"><el-input v-model="a2aBindingForm.permissionKey" placeholder="通常无需修改" /></el-form-item>
            <el-form-item label="操作类型"><el-select v-model="a2aBindingForm.riskLevel"><el-option label="只查询" value="READ" /><el-option label="会修改数据" value="WRITE" /><el-option label="可能无法撤销" value="IRREVERSIBLE" /></el-select></el-form-item>
            <el-form-item label="最长等待（秒）"><el-input-number v-model="a2aTimeoutSeconds" :min="1" :max="600" :step="1" /></el-form-item>
          </div>
        </details>
      </el-form>
    </div>
    <template #footer>
      <div class="a2a-dialog-footer">
        <span v-if="!localAgentPrincipals.length">完成设置后，再回来添加协作 Agent</span>
        <span v-else-if="!trustedRemoteAgents.length">建立信任后，再回来选择协作对象</span>
        <span v-else>添加后会进入当前配置草稿，保存后才会生效</span>
        <div class="a2a-dialog-footer__actions">
          <LiquidGlassButton @click="a2aBindingDialogVisible = false">取消</LiquidGlassButton>
          <LiquidGlassButton v-if="!localAgentPrincipals.length" type="primary" @click="openA2aTrustWorkspace">打开信任与策略</LiquidGlassButton>
          <LiquidGlassButton v-else-if="!trustedRemoteAgents.length" type="primary" @click="openA2aRemoteAgentCatalog">打开远程 Agent 目录</LiquidGlassButton>
          <LiquidGlassButton v-else type="primary" :disabled="!a2aBindingReady" @click="addA2aBinding">添加到当前 Agent</LiquidGlassButton>
        </div>
      </div>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { Check, Connection, MoreFilled, Plus } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

const {
  isNew,
  activeConfigSection,
  remoteAgentBindings,
  a2aBindingDialogVisible,
  a2aBindingLoading,
  trustedRemoteAgents,
  localAgentPrincipals,
  a2aBindingForm,
  a2aPrincipalSelection,
  a2aRemoteAgentSelection,
  a2aTimeoutSeconds,
  selectedA2aRemoteAgent,
  selectedA2aRemoteRevision,
  a2aBindingReady,
  riskLabel,
  riskToneClass,
  openA2aTrustWorkspace,
  openA2aRemoteAgentCatalog,
  openA2aBindingDialog,
  handleA2aRemoteAgentChanged,
  addA2aBinding,
  removeA2aBinding,
  moveA2aBinding,
  a2aSkillLabel,
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

.config-asset-card__tags,
.config-asset-task-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.config-asset-card__tags {
  margin-top: 8px;
}

.config-asset-task-list {
  min-height: 32px;
}

.config-asset-card.is-collaboration .config-asset-card__header {
  min-height: 92px;
}

.workbench-layout:not(.has-section-nav) .capability-empty-state {
  min-height: 140px;
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

.tool-override-grid {
  display: grid;
  gap: 12px;
  padding: 8px 20px;
}

.skill-binding-identity .el-tag {
  justify-self: start;
}

.a2a-dialog-shell {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.a2a-dialog-intro {
  display: grid;
  min-width: 0;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider));
  border-radius: var(--radius-md);
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--brand-primary) 11%, transparent), transparent 72%),
    var(--surface-solid-control);
}

.a2a-dialog-intro__icon,
.a2a-dialog-empty__icon {
  display: grid;
  place-items: center;
  border-radius: 13px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
}

.a2a-dialog-intro__icon {
  width: 44px;
  height: 44px;
  font-size: 21px;
}

.a2a-dialog-intro__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-intro__copy small {
  color: var(--text-link);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.11em;
}

.a2a-dialog-intro__copy strong {
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.a2a-dialog-intro__copy p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
}

.a2a-dialog-intro__protocol {
  padding: 4px 8px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider));
  border-radius: 999px;
  color: var(--text-link);
  background: var(--surface-glass-control);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.a2a-dialog-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 24px 20px 20px;
  border: 1px dashed color-mix(in srgb, var(--brand-primary) 20%, var(--border-divider));
  border-radius: var(--radius-lg);
  background: var(--surface-glass-inset);
  text-align: center;
}

.a2a-dialog-empty__icon {
  width: 48px;
  height: 48px;
  margin-bottom: 2px;
  font-size: 22px;
}

.a2a-dialog-empty__icon.is-ready {
  color: var(--status-success);
  background: var(--status-success-soft);
}

.a2a-dialog-empty__eyebrow {
  color: var(--text-link);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.a2a-dialog-empty__eyebrow.is-ready {
  color: var(--status-success);
}

.a2a-dialog-empty h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 18px;
  line-height: 26px;
}

.a2a-dialog-empty > p {
  max-width: 54ch;
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 21px;
}

.a2a-setup-path {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  text-align: left;
}

.a2a-setup-path li {
  display: grid;
  min-width: 0;
  grid-template-columns: 26px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  background: var(--surface-solid-control);
}

.a2a-setup-path li > span,
.a2a-dialog-choice-card__heading > span {
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text-muted);
  background: var(--surface-glass-inset);
  font-size: 11px;
  font-weight: 750;
}

.a2a-setup-path li > span {
  width: 26px;
  height: 26px;
}

.a2a-setup-path li div,
.a2a-dialog-choice-card__heading div {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.a2a-setup-path strong,
.a2a-dialog-choice-card__heading strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-setup-path small,
.a2a-dialog-choice-card__heading small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-setup-path li.is-current {
  border-color: color-mix(in srgb, var(--brand-primary) 28%, var(--border-divider));
  background: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-solid-control));
}

.a2a-setup-path li.is-current > span {
  color: var(--text-inverse);
  background: var(--brand-primary);
}

.a2a-setup-path li.is-done > span {
  color: var(--text-inverse);
  background: var(--status-success);
}

.a2a-dialog-form,
.a2a-dialog-task-section {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.a2a-dialog-choice-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.a2a-dialog-choice-card {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-choice-card__heading {
  display: grid;
  min-width: 0;
  grid-template-columns: 28px minmax(0, 1fr);
  align-items: center;
  gap: 9px;
}

.a2a-dialog-choice-card__heading > span {
  width: 28px;
  height: 28px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
}

.a2a-dialog-choice-card :deep(.el-select),
.a2a-dialog-task-section :deep(.el-select),
.a2a-dialog-settings :deep(.el-select),
.a2a-dialog-settings :deep(.el-input-number) {
  width: 100%;
}

.a2a-dialog-agent-preview {
  display: grid;
  min-width: 0;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--status-success) 22%, var(--border-divider));
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--status-success-soft) 58%, var(--surface-solid-control));
}

.a2a-dialog-agent-preview__avatar {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 12px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
  font-size: 17px;
  font-weight: 800;
}

.a2a-dialog-agent-preview__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-agent-preview__copy strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-dialog-agent-preview__copy > span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-dialog-agent-preview__copy p {
  display: -webkit-box;
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.a2a-dialog-agent-preview__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 5px;
}

.a2a-dialog-task-section {
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-section-heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.a2a-dialog-section-heading > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-section-heading strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 19px;
}

.a2a-dialog-section-heading small {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.a2a-dialog-section-heading > span {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 999px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
  font-size: 10px;
  font-weight: 750;
}

.a2a-dialog-settings {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-settings summary {
  display: flex;
  min-height: 54px;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  color: var(--text-primary);
  cursor: pointer;
  list-style: none;
}

.a2a-dialog-settings summary::-webkit-details-marker {
  display: none;
}

.a2a-dialog-settings summary > span {
  display: grid;
  gap: 1px;
}

.a2a-dialog-settings summary strong {
  font-size: 13px;
  line-height: 19px;
}

.a2a-dialog-settings summary small {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.a2a-dialog-settings summary::after {
  content: '展开';
  margin-left: auto;
  color: var(--text-link);
  font-size: 11px;
  font-weight: 700;
}

.a2a-dialog-settings[open] summary {
  border-bottom: 1px solid var(--border-divider);
}

.a2a-dialog-settings[open] summary::after {
  content: '收起';
}

.a2a-dialog-settings__grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
}

.a2a-dialog-settings__grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.a2a-dialog-footer {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.a2a-dialog-footer > span {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
  text-align: left;
}

.a2a-dialog-footer__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
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
  .a2a-dialog-intro {
      grid-template-columns: 40px minmax(0, 1fr);
    }

  .a2a-dialog-intro__icon {
      width: 40px;
      height: 40px;
    }

  .a2a-dialog-intro__protocol {
      grid-column: 2;
      justify-self: start;
    }

  .a2a-setup-path,
    .a2a-dialog-choice-grid,
    .a2a-dialog-settings__grid {
      grid-template-columns: minmax(0, 1fr);
    }

  .a2a-dialog-agent-preview {
      grid-template-columns: 40px minmax(0, 1fr);
    }

  .a2a-dialog-agent-preview__avatar {
      width: 40px;
      height: 40px;
    }

  .a2a-dialog-agent-preview__tags {
      grid-column: 2;
      justify-content: flex-start;
    }

  .a2a-dialog-footer {
      align-items: stretch;
      flex-direction: column;
    }

  .a2a-dialog-footer__actions {
      justify-content: flex-end;
    }

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

  .config-card-settings-grid .wide {
      grid-column: auto;
    }
}
</style>
