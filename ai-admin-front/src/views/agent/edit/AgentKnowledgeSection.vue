<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'knowledge'"
    id="agent-config-knowledge"
    class="knowledge-panel"
    title="知识库"
    density="compact"
  >
    <template #actions>
      <el-button type="primary" :icon="Plus" :disabled="isNew" @click="openKnowledgePicker">
        添加知识库
      </el-button>
    </template>
    <LiquidGlassNotice
      v-if="isNew"
      type="info"
      title="请先保存当前 Agent，再添加知识库。"
      source="backdrop"
    />
    <div v-else-if="!knowledgeTools.length" class="knowledge-empty-state">
      <span class="knowledge-card__icon"><el-icon><Reading /></el-icon></span>
      <strong>暂未添加知识库</strong>
      <p>添加后，用户问到制度、流程等资料时，Agent 会查询知识库并附上出处。</p>
    </div>
    <div v-else class="knowledge-list">
      <article v-for="row in knowledgeTools" :key="row.workflowId" class="knowledge-card">
        <span class="knowledge-card__icon"><el-icon><Reading /></el-icon></span>
        <div class="knowledge-card__copy">
          <strong>{{ knowledgeToolLabel(row) }}</strong>
          <p>通过知识问答 Workflow 查询{{ row.workflowVersion ? ` · ${row.workflowVersion}` : '' }}</p>
        </div>
        <div class="knowledge-card__actions">
          <span>{{ row.enabled !== false ? '已启用' : '已停用' }}</span>
          <el-switch v-model="row.enabled" size="small" :aria-label="`启用 ${knowledgeToolLabel(row)}`" />
          <el-button link type="danger" @click="removeWorkflowToolById(row.workflowId)">移除</el-button>
        </div>
      </article>
      <p class="knowledge-hint">修改只保存在草稿里；保存草稿后可以先调试，再发布。</p>
    </div>
  </WorkbenchPanel>

  <AppDialog
    v-model="knowledgePickerVisible"
    title="添加知识库"
    width="min(640px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
  >
    <div class="knowledge-picker" v-loading="knowledgeOptionsLoading">
      <p class="knowledge-picker__intro">选择后会准备好对应的知识问答 Workflow，并加入当前草稿。</p>
      <div v-if="!knowledgeOptionsLoading && !knowledgeOptions.length" class="knowledge-empty-state is-compact">
        <strong>没有可用的知识库</strong>
      </div>
      <div v-for="kb in knowledgeOptions" :key="kb.code" class="knowledge-picker__row">
        <div class="knowledge-card__copy">
          <strong>{{ kb.name || kb.code }}</strong>
          <p>{{ kb.description || kb.code }}</p>
        </div>
        <el-button v-if="knowledgeOptionAttached(kb)" disabled>已添加</el-button>
        <el-button
          v-else
          type="primary"
          plain
          :loading="knowledgeAddingCode === kb.code"
          :disabled="Boolean(knowledgeAddingCode) && knowledgeAddingCode !== kb.code"
          @click="addKnowledgeBase(kb)"
        >
          添加
        </el-button>
      </div>
    </div>
    <template #footer>
      <LiquidGlassButton @click="knowledgePickerVisible = false">完成</LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { Plus, Reading } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

const {
  isNew,
  activeConfigSection,
  knowledgeTools,
  knowledgeToolLabel,
  knowledgePickerVisible,
  knowledgeOptions,
  knowledgeOptionsLoading,
  knowledgeAddingCode,
  knowledgeOptionAttached,
  openKnowledgePicker,
  addKnowledgeBase,
  removeWorkflowToolById,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.knowledge-empty-state {
  display: grid;
  min-height: 220px;
  place-items: center;
  align-content: center;
  gap: 10px;
  color: var(--text-primary);
  text-align: center;
}

.knowledge-empty-state.is-compact {
  min-height: 120px;
}

.knowledge-empty-state p,
.knowledge-hint,
.knowledge-picker__intro {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 19px;
}

.knowledge-list,
.knowledge-picker {
  display: grid;
  gap: 12px;
}

.knowledge-card,
.knowledge-picker__row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-control);
}

.knowledge-card__icon {
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

.knowledge-card__copy {
  min-width: 0;
  flex: 1;
}

.knowledge-card__copy strong {
  display: block;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-card__copy p {
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 19px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-card__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
