<script setup lang="ts">
/**
 * 统一 Widget 外壳：常态显示标题；编辑态由标题栏直接拖动，保留设置/删除操作，
 * 并在左、右、下三侧提供缩放热区。
 * 删除只能通过显式按钮或 Delete 键，不允许“拖出画布即删除”。
 */
withDefaults(
  defineProps<{
    instanceId: string
    title: string
    subtitle?: string
    editing?: boolean
    selected?: boolean
    /** 裸模式（KPI 卡片）：常态不渲染标题栏，卡片自身标签即标题；编辑态仍显示可拖动标题栏。 */
    bare?: boolean
  }>(),
  {
    subtitle: '',
    editing: false,
    selected: false,
    bare: false,
  },
)

const emit = defineEmits<{
  select: []
  move: [dx: number, dy: number]
  resize: [dw: number, dh: number]
  remove: []
  settings: []
}>()

function onHandleKeydown(event: KeyboardEvent) {
  // 设置 / 删除按钮位于可拖动标题栏中，按键事件冒泡时不能触发布局操作。
  if (event.target !== event.currentTarget) return
  const arrows: Record<string, [number, number]> = {
    ArrowLeft: [-1, 0],
    ArrowRight: [1, 0],
    ArrowUp: [0, -1],
    ArrowDown: [0, 1],
  }
  const delta = arrows[event.key]
  if (!delta) {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault()
      emit('remove')
    }
    return
  }
  event.preventDefault()
  // 方向键移动一格；Shift + 方向键调整尺寸。
  if (event.shiftKey) emit('resize', delta[0], delta[1])
  else emit('move', delta[0], delta[1])
}
</script>

<template>
  <section
    class="dash-frame"
    :class="{
      'ops-panel': !bare || editing,
      'is-editing': editing,
      'is-selected': editing && selected,
      'dash-frame--bare': bare && !editing,
    }"
    :data-grid-item="instanceId"
    @pointerdown="emit('select')"
  >
    <header
      v-if="editing || !bare"
      class="dash-frame__bar"
      :class="{ 'is-draggable': editing }"
      :data-grid-drag="editing ? instanceId : undefined"
      :tabindex="editing ? 0 : undefined"
      :aria-label="editing ? `拖动标题栏移动模块 ${title}；方向键移动，Shift 加方向键调整尺寸，Delete 删除` : undefined"
      @keydown="onHandleKeydown"
    >
      <div class="dash-frame__heading">
        <h3 class="dash-frame__title">{{ title }}</h3>
        <p v-if="subtitle" class="dash-frame__subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="!editing" class="dash-frame__actions">
        <slot name="actions" />
      </div>
      <span v-else class="dash-frame__tools">
        <button type="button" :aria-label="`设置 ${title}`" title="设置" @click="emit('settings')">⚙</button>
        <button type="button" class="is-danger" :aria-label="`删除 ${title}`" title="删除" @click="emit('remove')">✕</button>
      </span>
    </header>
    <div class="dash-frame__body">
      <slot />
    </div>
    <template v-if="editing">
      <span class="dash-frame__resize-edge is-left" :data-grid-resize="instanceId" data-grid-resize-edge="left" aria-hidden="true" />
      <span class="dash-frame__resize-edge is-right" :data-grid-resize="instanceId" data-grid-resize-edge="right" aria-hidden="true" />
      <span class="dash-frame__resize-edge is-bottom" :data-grid-resize="instanceId" data-grid-resize-edge="bottom" aria-hidden="true" />
    </template>
  </section>
</template>

<style scoped lang="scss">
.dash-frame {
  position: relative;
  min-width: 0;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.dash-frame--bare {
  display: block;
}

.dash-frame__bar {
  min-height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  background: var(--ops-panel-header-background);
  border-bottom: 1px solid var(--ops-divider);
}

.dash-frame__bar.is-draggable {
  cursor: grab;
  touch-action: none;
}

.dash-frame__bar.is-draggable:active {
  cursor: grabbing;
}

.dash-frame__bar.is-draggable:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--ops-cyan) 62%, transparent);
  outline-offset: -2px;
}

.dash-frame__heading {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.dash-frame__title {
  margin: 0;
  overflow: hidden;
  color: var(--ops-text-strong);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash-frame__subtitle {
  margin: 0;
  overflow: hidden;
  color: var(--ops-text-muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash-frame__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dash-frame__tools {
  display: flex;
  gap: 4px;
}

.dash-frame__tools button {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--ops-text-secondary);
  background: var(--ops-input-background);
  border: 1px solid var(--ops-border);
  border-radius: 4px;
  cursor: pointer;
}

.dash-frame__tools button:hover,
.dash-frame__tools button:focus-visible {
  color: var(--ops-cyan);
  border-color: var(--ops-border-strong);
  outline: none;
}

.dash-frame__tools button.is-danger:hover,
.dash-frame__tools button.is-danger:focus-visible {
  color: var(--ops-danger);
  border-color: var(--ops-danger-border);
}

.dash-frame__body {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.dash-frame--bare .dash-frame__body {
  overflow: visible;
}

.dash-frame__resize-edge {
  position: absolute;
  z-index: 4;
  touch-action: none;
}

.dash-frame__resize-edge::after {
  content: '';
  position: absolute;
  background: var(--ops-cyan);
  border-radius: 99px;
  box-shadow: 0 0 10px color-mix(in srgb, var(--ops-cyan) 46%, transparent);
  opacity: 0;
  transition: opacity 120ms ease;
}

.dash-frame__resize-edge:hover::after {
  opacity: .82;
}

.dash-frame__resize-edge.is-left,
.dash-frame__resize-edge.is-right {
  top: 5px;
  bottom: 8px;
  width: 8px;
  cursor: ew-resize;
}

.dash-frame__resize-edge.is-left { left: 0; }
.dash-frame__resize-edge.is-right { right: 0; }

.dash-frame__resize-edge.is-left::after,
.dash-frame__resize-edge.is-right::after {
  top: 12px;
  bottom: 12px;
  width: 2px;
}

.dash-frame__resize-edge.is-left::after { left: 1px; }
.dash-frame__resize-edge.is-right::after { right: 1px; }

.dash-frame__resize-edge.is-bottom {
  right: 8px;
  bottom: 0;
  left: 8px;
  height: 8px;
  cursor: ns-resize;
}

.dash-frame__resize-edge.is-bottom::after {
  right: 12px;
  bottom: 1px;
  left: 12px;
  height: 2px;
}

.dash-frame.is-selected .dash-frame__resize-edge::after {
  opacity: .18;
}

.dash-frame.is-selected .dash-frame__resize-edge:hover::after {
  opacity: .9;
}

.dash-frame.is-editing {
  height: 100%;
  min-height: 0;
  max-height: 100%;
  overflow: hidden;
  border-style: dashed;
}

.dash-frame.is-selected {
  border-color: var(--ops-cyan);
  border-style: solid;
  box-shadow: 0 0 0 1px var(--ops-cyan), 0 0 18px color-mix(in srgb, var(--ops-cyan) 18%, transparent);
}
</style>
