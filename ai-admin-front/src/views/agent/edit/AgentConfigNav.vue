<template>
  <nav v-if="!isNew" class="config-section-nav" aria-label="配置分区">
    <strong class="config-section-nav__title">配置</strong>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'basic' }" @click="showConfigSection('basic')">
      <span class="config-section-nav__icon"><el-icon><Cpu /></el-icon></span>
      <span class="config-section-nav__label">基本信息</span>
      <span class="config-section-nav__count is-ready"><el-icon><Check /></el-icon></span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'decision' }" @click="showConfigSection('decision')">
      <span class="config-section-nav__icon"><el-icon><ChatDotRound /></el-icon></span>
      <span class="config-section-nav__label">对话与决策</span>
      <span class="config-section-nav__count is-ready"><el-icon><Check /></el-icon></span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'workflows' }" @click="showConfigSection('workflows')">
      <span class="config-section-nav__icon"><el-icon><Document /></el-icon></span>
      <span class="config-section-nav__label">可调用 Workflow</span>
      <span class="config-section-nav__count">{{ generalWorkflowEntries.length }}</span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'knowledge' }" @click="showConfigSection('knowledge')">
      <span class="config-section-nav__icon"><el-icon><Reading /></el-icon></span>
      <span class="config-section-nav__label">知识库</span>
      <span class="config-section-nav__count">{{ knowledgeTools.length }}</span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'remote' }" @click="showConfigSection('remote')">
      <span class="config-section-nav__icon"><el-icon><Connection /></el-icon></span>
      <span class="config-section-nav__label">Agent 协作</span>
      <span class="config-section-nav__count">{{ remoteAgentBindings.length }}</span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'skills' }" @click="showConfigSection('skills')">
      <span class="config-section-nav__icon"><el-icon><Guide /></el-icon></span>
      <span class="config-section-nav__label">Skill</span>
      <span class="config-section-nav__count">{{ skillBindings.length }}</span>
    </button>
    <button type="button" :class="{ 'is-active': activeConfigSection === 'memory' }" @click="showConfigSection('memory')">
      <span class="config-section-nav__icon"><el-icon><Collection /></el-icon></span>
      <span class="config-section-nav__label">记忆</span>
      <span class="config-section-nav__count" :class="{ 'is-ready': memoryPolicy.personalMemoryRead || memoryPolicy.personalMemoryWrite !== 'OFF' }">{{ memorySummaryLabel }}</span>
    </button>
    <div v-if="draftConfig" class="config-section-nav__footer">
      <span><strong>草稿 v{{ draftConfig.versionNo }}</strong><small>待发布</small></span>
      <button type="button" @click="showVersionSection">查看版本</button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { ChatDotRound, Check, Connection, Cpu, Document, Collection, Guide, Reading } from '@element-plus/icons-vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  isNew,
  activeConfigSection,
  generalWorkflowEntries,
  knowledgeTools,
  remoteAgentBindings,
  skillBindings,
  memoryPolicy,
  memorySummaryLabel,
  draftConfig,
  showConfigSection,
  showVersionSection,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.config-section-nav {
  position: sticky;
  top: 0;
  z-index: 2;
  display: grid;
  gap: 5px;
  align-self: start;
  padding: 8px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.config-section-nav__title {
  padding: 7px 10px 11px;
  border-bottom: 1px solid var(--border-divider);
  color: var(--text-primary);
  font-size: 13px;
  line-height: 20px;
}

.config-section-nav > button {
  display: grid;
  min-width: 0;
  min-height: 50px;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 5px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  text-align: left;
  transition: color var(--motion-duration-fast) ease, background var(--motion-duration-fast) ease;
}

.config-section-nav > button:hover {
  color: var(--brand-active);
  background: var(--surface-glass-inset);
}

.config-section-nav > button.is-active {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-weight: 700;
}

.config-section-nav__icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--border-divider);
  border-radius: 9px;
  color: var(--text-secondary);
  background: var(--surface-solid-control);
  transition: color var(--motion-duration-fast) ease, background var(--motion-duration-fast) ease;
}

.config-section-nav > button.is-active .config-section-nav__icon {
  border-color: transparent;
  color: var(--text-inverse);
  background: var(--brand-primary);
  box-shadow: var(--shadow-active);
}

.config-section-nav__label {
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-section-nav__count {
  display: grid;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  place-items: center;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--surface-glass-inset);
  font-size: 11px;
  font-weight: 700;
}

.config-section-nav__count.is-ready {
  color: var(--status-success);
}

.config-section-nav__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  background: var(--surface-glass-inset);
}

.config-section-nav__footer span,
.config-section-nav__footer strong,
.config-section-nav__footer small {
  display: block;
}

.config-section-nav__footer strong {
  color: var(--text-primary);
  font-size: 12px;
}

.config-section-nav__footer small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 10px;
}

.config-section-nav__footer button {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
}

@media (max-width: 960px) {
  .config-section-nav {
      position: static;
      display: flex;
      overflow-x: auto;
    }

  .config-section-nav > button {
      flex: 0 0 auto;
      min-width: 150px;
      white-space: nowrap;
    }

  .config-section-nav__title,
    .config-section-nav__footer {
      display: none;
    }
}
</style>
