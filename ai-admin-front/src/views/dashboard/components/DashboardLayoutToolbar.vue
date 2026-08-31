<script setup lang="ts">
/**
 * 看板工具栏：常态提供“编辑布局”；编辑态提供
 * 添加组件、撤销、重做、整理布局、恢复默认、取消、保存。
 */
defineProps<{
  editing: boolean
  canUndo: boolean
  canRedo: boolean
  saveError?: string | null
  layoutSourceLabel: string
  savedRevision: number
  narrow?: boolean
}>()

defineEmits<{
  beginEdit: []
  cancel: []
  save: []
  openCatalog: []
  undo: []
  redo: []
  tidy: []
  resetDefault: []
}>()
</script>

<template>
  <div class="dash-toolbar" :data-editing="editing ? 'true' : 'false'">
    <template v-if="!editing">
      <span class="dash-toolbar__source">
        {{ layoutSourceLabel }} · revision {{ savedRevision }}
      </span>
      <button
        v-if="!narrow"
        type="button"
        class="dash-toolbar__edit"
        data-testid="edit-layout"
        aria-label="编辑布局"
        @click="$emit('beginEdit')"
      >✎ 编辑布局</button>
      <span v-else class="dash-toolbar__narrow-hint" data-testid="narrow-edit-disabled">
        当前内容区小于 1000px，已禁用布局编辑；放大窗口后可添加、移动和缩放模块。
      </span>
    </template>
    <template v-else>
      <span
        v-if="editing && narrow"
        class="dash-toolbar__paused"
        role="status"
        data-testid="edit-paused"
      >布局编辑已暂停 · 内容区小于 1000px；草稿已保留，放大窗口后继续编辑</span>
      <span v-else class="dash-toolbar__mode">布局编辑中 · 仅修改草稿，未影响已保存布局</span>
      <span
        v-if="saveError"
        class="dash-toolbar__error"
        role="alert"
        data-testid="save-error"
      >{{ saveError }}</span>
      <span class="dash-toolbar__actions">
        <button
          type="button"
          data-testid="open-catalog"
          :disabled="narrow"
          @click="$emit('openCatalog')"
        >＋ 添加组件</button>
        <button
          type="button"
          data-testid="undo"
          :disabled="!canUndo || narrow"
          @click="$emit('undo')"
        >↶ 撤销</button>
        <button
          type="button"
          data-testid="redo"
          :disabled="!canRedo || narrow"
          @click="$emit('redo')"
        >↷ 重做</button>
        <button type="button" data-testid="tidy" :disabled="narrow" @click="$emit('tidy')">◫ 整理布局</button>
        <button type="button" data-testid="reset-default" :disabled="narrow" @click="$emit('resetDefault')">⟲ 恢复默认</button>
        <button type="button" class="is-ghost" data-testid="cancel-edit" @click="$emit('cancel')">取消</button>
        <button type="button" class="is-primary" data-testid="save-layout" :disabled="narrow" @click="$emit('save')">保存布局</button>
      </span>
    </template>
  </div>
</template>

<style scoped lang="scss">
.dash-toolbar {
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 4px 10px;
  background: var(--ops-topbar-background);
  border: 1px solid var(--ops-border);
  border-radius: 7px;
}

.dash-toolbar__source {
  margin-right: auto;
  color: var(--ops-text-muted);
  font-size: 10px;
}

.dash-toolbar__mode {
  margin-right: auto;
  color: var(--ops-warning);
  font-size: 10px;
}

.dash-toolbar__paused {
  margin-right: auto;
  color: var(--ops-warning);
  font-size: 10px;
}

.dash-toolbar__error {
  color: var(--ops-danger);
  font-size: 10px;
}

.dash-toolbar__narrow-hint {
  margin-right: auto;
  color: var(--ops-warning);
  font-size: 10px;
}

.dash-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dash-toolbar__actions button,
.dash-toolbar__edit {
  padding: 5px 11px;
  color: var(--ops-text-secondary);
  background: var(--ops-surface-control-strong);
  border: 1px solid var(--ops-border);
  border-radius: 5px;
  font-size: 11px;
  cursor: pointer;
}

.dash-toolbar__actions button:hover:not(:disabled),
.dash-toolbar__actions button:focus-visible:not(:disabled),
.dash-toolbar__edit:hover,
.dash-toolbar__edit:focus-visible {
  color: var(--ops-cyan);
  border-color: var(--ops-border-strong);
  outline: none;
}

.dash-toolbar__actions button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.dash-toolbar__actions button.is-primary {
  color: var(--ops-primary-action-text);
  background: var(--ops-primary-action-background);
  border-color: var(--ops-primary-action-border);
}

.dash-toolbar__actions button.is-ghost {
  color: var(--ops-warning);
}

@media (max-width: 900px) {
  .dash-toolbar {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}
</style>
