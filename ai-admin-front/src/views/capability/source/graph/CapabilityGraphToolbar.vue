<script setup lang="ts">
import { ref } from 'vue'
import { ArrowDown, QuestionFilled, Refresh, Share } from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'

const props = withDefaults(defineProps<{
  loading?: boolean
  analysisBusy?: boolean
  canWrite?: boolean
  focusedLabel?: string
  linkMode?: boolean
  showCandidates?: boolean
  showDtoPanel?: boolean
  linkSourceLabel?: string
}>(), { loading: false, analysisBusy: false, canWrite: false, linkMode: false,
  showCandidates: false, showDtoPanel: false })
const emit = defineEmits<{
  refresh: []
  'focus-subject': []
  'analysis-command': [command: string]
  'update:linkMode': [value: boolean]
  'update:showCandidates': [value: boolean]
  'update:showDtoPanel': [value: boolean]
}>()
const helpVisible = ref(false)
function analyze(command: string) {
  if (props.canWrite && !props.loading && !props.analysisBusy) emit('analysis-command', command)
}
</script>

<template>
  <div class="graph-workhead">
    <header class="graph-workhead__main">
      <div class="graph-workhead__heading">
        <div v-if="$slots.navigation" class="graph-workhead__back"><slot name="navigation" /></div>
        <div class="graph-workhead__identity">
          <span class="graph-workhead__symbol" aria-hidden="true"><el-icon><Share /></el-icon></span>
          <div>
            <h2>关联分析</h2>
            <p>契约关联，非实际调用依赖</p>
          </div>
        </div>
      </div>
      <div class="graph-workhead__actions" role="group" aria-label="图谱分析操作">
        <el-button :icon="Refresh" :loading="loading" @click="!loading && emit('refresh')">刷新</el-button>
        <el-dropdown v-if="canWrite" trigger="click" @command="analyze">
          <el-button type="primary" :loading="analysisBusy" :disabled="loading">
            分析与更新<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="inferModels" :disabled="loading || analysisBusy">推断共享数据结构</el-dropdown-item>
            <el-dropdown-item command="inferRequestResponse" :disabled="loading || analysisBusy">推断请求与响应关联</el-dropdown-item>
            <el-dropdown-item command="rebuild" divided :disabled="loading || analysisBusy">按当前来源更新关系</el-dropdown-item>
            <el-dropdown-item command="regenerate" :disabled="loading || analysisBusy">清空并重新生成</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div>
    </header>

    <div class="graph-workhead__controls">
      <div class="graph-workhead__options">
        <div class="graph-option-group" role="group" aria-label="图谱显示选项">
          <span class="graph-option-group__label">显示</span>
          <div class="graph-view-option">
            <el-switch :model-value="showCandidates" active-text="候选边" aria-label="候选边" size="small"
              @update:model-value="emit('update:showCandidates', Boolean($event))" />
          </div>
          <div class="graph-view-option">
            <el-switch :model-value="showDtoPanel" active-text="数据模型" aria-label="数据模型" size="small"
              @update:model-value="emit('update:showDtoPanel', Boolean($event))" />
          </div>
        </div>
        <div v-if="canWrite" class="graph-option-group graph-option-group--edit" role="group" aria-label="图谱编辑模式">
          <div class="graph-view-option" :class="{ 'is-active': linkMode }">
            <el-switch :model-value="linkMode" active-text="连线模式" aria-label="连线模式" size="small"
              @update:model-value="emit('update:linkMode', Boolean($event))" />
          </div>
        </div>
      </div>
      <el-button text :icon="QuestionFilled" @click="helpVisible = true">操作帮助</el-button>
    </div>

    <div v-if="focusedLabel || (canWrite && linkMode)" class="graph-workhead__context" role="status">
      <template v-if="canWrite && linkMode">
        <span class="graph-workhead__status-dot" aria-hidden="true" />
        <span v-if="linkSourceLabel">已选源节点：{{ linkSourceLabel }}，点击目标节点完成连线</span>
        <span v-else>请点击一个接口或参数作为源节点</span>
      </template>
      <template v-else>
        <span class="graph-workhead__subject" :title="focusedLabel">当前能力：{{ focusedLabel }}</span>
      </template>
      <el-button v-if="focusedLabel" text type="primary" @click="emit('focus-subject')">当前能力关联</el-button>
    </div>

    <AppDrawer v-model="helpVisible" title="图谱操作帮助" size="min(420px, 100vw)" append-to-body>
      <div class="graph-help">
        <section><h3>理解关联</h3><p>这里展示人工标注与模型推断的请求、响应和共享 DTO/VO 数据结构关联。Workflow、Agent 等真实引用，请在对应能力的“使用位置”查看。</p></section>
        <section><h3>查看详情</h3><p>选择业务操作、参数或连线查看详情。开启“数据模型”后，可查看 DTO/VO 及字段引用；开启“候选边”可查看待确认的推断关系。</p></section>
        <section v-if="canWrite"><h3>补充关系</h3><p>开启“连线模式”，依次选择源节点和目标节点补充引用关系。“分析与更新”提供模型推断和来源更新操作，清空重新生成会先请求确认。</p></section>
        <section><h3>移动与浏览</h3><p>卡片可拖动，或聚焦后按 <kbd>Alt</kbd> + 方向键调整位置。使用画布内滚动条浏览完整关系。</p></section>
      </div>
    </AppDrawer>
  </div>
</template>

<style scoped lang="scss">
.graph-workhead { flex: 0 0 auto; min-width: 0; }
.graph-workhead__main { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap;
  gap: 12px 20px; padding: 16px 20px; }
.graph-workhead__heading, .graph-workhead__identity, .graph-workhead__actions { display: flex; align-items: center; gap: 12px; min-width: 0; }
.graph-workhead__heading { flex-wrap: wrap; }
.graph-workhead__back { border-right: 1px solid var(--border-divider); padding-right: 16px; }
.graph-workhead__back :deep(.el-button) { padding-inline: 0; color: var(--text-secondary); }
.graph-workhead__back :deep(.el-button:hover) { color: var(--brand-active); }
.graph-workhead__symbol { display: grid; place-items: center; flex: 0 0 36px; width: 36px; height: 36px;
  border: 1px solid var(--border-subtle); border-radius: var(--radius-md);
  background: var(--surface-glass-selected); box-shadow: var(--inner-highlight); color: var(--brand-active); font-size: 18px; }
.graph-workhead h2 { margin: 0; color: var(--text-primary); font-size: 17px; font-weight: 600; line-height: 1.4; }
.graph-workhead__identity p { margin: 3px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
.graph-workhead__actions { gap: 8px; flex-wrap: wrap; }
.graph-workhead :deep(.el-button) { margin-left: 0; }
.graph-workhead__controls { display: flex; min-width: 0; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 8px 16px; padding: 8px 20px; border-top: 1px solid var(--border-divider);
  border-bottom: 1px solid var(--border-divider); background: var(--surface-glass-control); }
.graph-workhead__options { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 12px 20px; }
.graph-option-group { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 16px; }
.graph-option-group__label { color: var(--text-muted); font-size: 11px; }
.graph-option-group--edit { border-left: 1px solid var(--border-divider); padding-left: 20px; }
.graph-view-option { display: inline-flex; align-items: center; gap: 8px; min-height: 32px; color: var(--text-secondary); font-size: 12px; cursor: pointer; }
.graph-view-option.is-active { color: var(--brand-active); }
.graph-view-option :deep(.el-switch__label) { color: var(--text-secondary); }
.graph-view-option :deep(.el-switch__label.is-active) { color: var(--brand-active); }
.graph-view-option :deep(.el-switch__label span) { font-size: 12px; }
.graph-workhead__context { display: flex; align-items: center; min-width: 0; gap: 8px; padding: 8px 20px;
  border-bottom: 1px solid var(--border-divider); color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.graph-workhead__subject { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.graph-workhead__context > .el-button { flex-shrink: 0; margin-left: auto; }
.graph-workhead__status-dot { width: 6px; height: 6px; flex-shrink: 0; border-radius: 50%; background: var(--brand-primary); }
.graph-help section + section { margin-top: 24px; }
.graph-help h3 { margin: 0 0 8px; color: var(--text-primary); font-size: 14px; font-weight: 600; }
.graph-help p { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; }
.graph-help kbd { padding: 2px 6px; border: 1px solid var(--border-readable); border-radius: var(--radius-sm); background: var(--surface-solid-control); font-family: inherit; }
@media (max-width: 640px) {
  .graph-workhead__main { padding: 14px 12px; gap: 12px; }
  .graph-workhead__heading { width: 100%; gap: 10px; }
  .graph-workhead__back { padding-right: 10px; }
  .graph-workhead__back :deep(.el-button) { font-size: 12px; }
  .graph-workhead__symbol { display: none; }
  .graph-workhead__actions { width: 100%; }
  .graph-workhead__actions > .el-button, .graph-workhead__actions > .el-dropdown { flex: 1; }
  .graph-workhead__actions .el-dropdown :deep(.el-button) { width: 100%; }
  .graph-workhead__controls { display: grid; grid-template-columns: minmax(0, 1fr) auto; padding: 8px 12px; gap: 4px 8px; }
  .graph-workhead__options { display: contents; }
  .graph-option-group { gap: 12px; }
  .graph-option-group:not(.graph-option-group--edit) { grid-column: 1 / -1; }
  .graph-option-group--edit { padding-left: 0; border-left: 0; }
  .graph-workhead__controls > .el-button { grid-column: 2; justify-self: end; }
  .graph-workhead__context { padding: 8px 12px; flex-wrap: wrap; }
}
</style>
