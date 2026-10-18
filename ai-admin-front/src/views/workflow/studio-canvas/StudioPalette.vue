<template>
  <aside class="palette">
    <div class="palette-rail">
      <el-tooltip content="添加节点" placement="right">
        <button class="rail-primary" type="button" :disabled="readOnly" @click="emit('open-search')">
          <el-icon><Plus /></el-icon>
        </button>
      </el-tooltip>
      <button
        v-for="group in groups"
        :key="group.title"
        class="rail-item"
        :class="{ active: expanded && activeGroup === group.title }"
        type="button"
        @click="emit('open-group', group.title)"
      >
        <span class="rail-icon">
          <el-icon><component :is="group.icon" /></el-icon>
        </span>
        <em>{{ group.title.slice(0, 2) }}</em>
      </button>
    </div>
    <div class="palette-content">
      <div class="panel-title-row">
        <div>
          <div class="palette-title">节点库</div>
          <div class="palette-subtitle">搜索或拖入节点，保存后写入图规范。</div>
        </div>
        <el-button text :icon="ArrowLeft" @click="expanded = false" />
      </div>
      <el-input
        v-model="keyword"
        class="node-search"
        :prefix-icon="Search"
        clearable
        placeholder="搜索节点、用途或类型"
        size="small"
      />
      <div v-for="group in filteredGroups" :key="group.title" class="palette-group">
        <div class="palette-group-title">{{ group.title }}</div>
        <div
          v-for="item in group.items"
          :key="item.kind"
          class="palette-item"
          :class="{ disabled: readOnly }"
          :style="{ borderLeftColor: kindColor(item.kind).border }"
          :draggable="!readOnly"
          @dragstart="emit('drag-start', $event, item.kind)"
          @dblclick="emit('add', item.kind)"
        >
          <div class="palette-item-head">
            <div class="palette-item-icon" :style="{ background: kindColor(item.kind).bg, color: kindColor(item.kind).border }">
              <el-icon><component :is="item.icon" /></el-icon>
            </div>
            <div>
              <div class="palette-item-title">{{ item.label }}</div>
              <div class="palette-item-meta">{{ item.meta }}</div>
            </div>
          </div>
          <div class="palette-item-desc">{{ item.hint }}</div>
        </div>
      </div>
      <el-divider>快捷操作</el-divider>
      <div class="palette-tips">
        拖到画布或双击添加节点<br />
        Ctrl+F 搜索节点，Ctrl+Z / Ctrl+Y 撤销重做<br />
        Ctrl+S 保存，Ctrl+O 自动整理，Delete 删除选中项
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ArrowLeft, Plus, Search } from '@element-plus/icons-vue'
import type { CanvasNodeKind } from '@/types/studio'
import type { WorkflowStudioPaletteGroup } from '../composables/useWorkflowStudioPalette'

/** Left node library: a rail of groups that expands into a searchable list of draggable nodes. */
defineProps<{
  readOnly: boolean
  groups: WorkflowStudioPaletteGroup[]
  filteredGroups: WorkflowStudioPaletteGroup[]
  activeGroup: string
  kindColor: (kind: CanvasNodeKind) => { bg: string; border: string }
}>()

const emit = defineEmits<{
  'open-search': []
  'open-group': [title: string]
  'drag-start': [event: DragEvent, kind: CanvasNodeKind]
  add: [kind: CanvasNodeKind]
}>()

const expanded = defineModel<boolean>('expanded', { required: true })
const keyword = defineModel<string>('keyword', { required: true })
</script>

<style scoped lang="scss">
.palette {
  display: flex;
  min-width: 0;
  overflow: hidden;
  border-right: 1px solid #e5edf7;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.palette-rail {
  display: flex;
  flex: 0 0 72px;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 14px 10px;
  border-right: 1px solid #edf2f7;
  background: rgba(255, 255, 255, 0.82);
}

.rail-primary,
.rail-item {
  border: 0;
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease, background 0.16s ease;
}

.rail-primary {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4776ff, #6846f6);
  color: #fff;
  box-shadow: 0 12px 24px rgba(75, 97, 255, 0.28);
}

.rail-item {
  display: grid;
  place-items: center;
  width: 48px;
  min-height: 54px;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
}

.rail-item .rail-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #eef4ff;
  color: #3157ff;
  font-size: 16px;
}

.rail-item em {
  margin-top: 3px;
  font-size: 11px;
  font-style: normal;
  line-height: 1;
}

.rail-item:hover,
.rail-item.active {
  background: #f0f5ff;
  color: #3157ff;
  box-shadow: inset 3px 0 0 #3157ff;
}

.palette-content {
  display: none;
  flex: 1;
  min-width: 0;
  padding: 16px 14px;
  overflow-y: auto;
}

.panel-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 14px;
}

.palette-title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 4px;
  color: #111827;
}

.palette-subtitle {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 12px;
}

.node-search {
  margin-bottom: 12px;
}

.palette-group {
  margin-bottom: 14px;
}

.palette-group-title {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}

.palette-item {
  border: 1px solid #e6edf7;
  border-left-width: 3px;
  border-radius: 8px;
  padding: 10px 11px;
  margin-bottom: 8px;
  cursor: grab;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.045);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.palette-item:hover {
  transform: translateY(-1px);
  border-color: #cbd5e1;
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.08);
}

.palette-item:active {
  cursor: grabbing;
}

.palette-item-head {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 9px;
  align-items: center;
  min-width: 0;
}

.palette-item-desc {
  display: -webkit-box;
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  line-height: 1.45;
  margin-top: 6px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.palette-item-title {
  color: #1f2937;
  font-weight: 700;
  font-size: 13px;
}

.palette-item-meta {
  color: #94a3b8;
  font-size: 11px;
  margin-top: 2px;
}

.palette-tips {
  color: #64748b;
  font-size: 12px;
  line-height: 1.8;
}

.palette-item-icon,
.palette-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--node-border);
  border-radius: 8px;
  background: var(--node-bg);
  color: var(--node-border);
}

.palette-item-main {
  min-width: 0;
}

.palette-item-title,
.palette-item-meta,
.palette-item-hint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
