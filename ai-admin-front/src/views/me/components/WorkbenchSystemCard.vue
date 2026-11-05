<template>
  <article class="workbench-system" :class="{ 'is-selected': selected }">
    <a v-if="system.url" class="workbench-system__entry" :href="system.url" target="_blank" rel="noopener noreferrer" :aria-label="`打开${system.name}（新标签页）`">
      <span class="workbench-system__icon" aria-hidden="true">{{ systemMonogram(system.name) }}</span>
      <strong :title="system.name">{{ system.name }}</strong>
      <span class="workbench-system__open">打开系统 <TopRight aria-hidden="true" /></span>
    </a>
    <div v-else class="workbench-system__entry">
      <span class="workbench-system__icon" aria-hidden="true">{{ systemMonogram(system.name) }}</span>
      <strong :title="system.name">{{ system.name }}</strong>
      <span class="workbench-system__open">入口未配置</span>
    </div>
    <div class="workbench-system__actions">
      <button class="workbench-system__ask" type="button" :disabled="disabled" :aria-pressed="selected" @click="$emit('select', system.code)">
        <span class="workbench-system__dot" :class="{ 'is-connected': system.connection && !system.connection.expired }" aria-hidden="true" />
        {{ system.connection?.expired ? '重新连接' : selected ? '当前系统' : system.connection ? 'AI 办事' : '连接 AI' }}
      </button>
      <button class="workbench-system__pin" type="button" :aria-label="`${pinned ? '取消置顶' : '置顶'}${system.name}`" :aria-pressed="pinned" :title="pinned ? '取消置顶' : '置顶'" @click="$emit('pin', system.code)"><StarFilled v-if="pinned" aria-hidden="true" /><Star v-else aria-hidden="true" /></button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { Star, StarFilled, TopRight } from '@element-plus/icons-vue'
import { systemMonogram } from '../myWorkbench'
import type { WorkbenchSystem } from '../useMyWorkbench'
defineProps<{ system: WorkbenchSystem; selected: boolean; pinned: boolean; disabled?: boolean }>()
defineEmits<{ select: [code: string]; pin: [code: string] }>()
</script>

<style scoped lang="scss">
.workbench-system { min-width: 0; border: 1px solid var(--border-divider); border-radius: var(--radius-lg); background: var(--surface-solid-control); overflow: hidden; }
.workbench-system.is-selected { border-color: var(--border-focus); }
.workbench-system__entry { display: flex; flex-direction: column; align-items: center; gap: 9px; padding: 18px 10px 13px; color: var(--text-primary); text-decoration: none; }
a.workbench-system__entry { cursor: pointer; }
a.workbench-system__entry:hover { background: var(--surface-hover); color: var(--brand-on-surface); }
a.workbench-system__entry:active { background: var(--surface-pressed); }
.workbench-system__entry:focus-visible, button:focus-visible { outline: 2px solid var(--border-focus); outline-offset: -3px; }
.workbench-system__icon { display: grid; place-items: center; width: 44px; height: 44px; border-radius: var(--radius-lg); background: var(--brand-soft-bg); color: var(--brand-on-surface); font-size: 18px; font-weight: 700; }
.workbench-system strong { max-width: 100%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 13px; font-weight: 600; }
.workbench-system__open { display: flex; align-items: center; gap: 3px; color: var(--text-muted); font-size: 11px; }
svg { width: 13px; height: 13px; }
.workbench-system__actions { display: flex; border-top: 1px solid var(--border-divider); }
button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 34px; border: 0; background: transparent; color: var(--text-secondary); font: inherit; font-size: 11px; cursor: pointer; }
button:hover { color: var(--brand-on-surface); background: var(--surface-hover); }
button:active { background: var(--surface-pressed); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.workbench-system__ask { flex: 1; min-width: 0; }
.workbench-system__pin { flex: 0 0 32px; border-left: 1px solid var(--border-divider); }
.workbench-system__pin[aria-pressed='true'] { color: var(--brand-on-surface); }
.workbench-system__dot { width: 5px; height: 5px; border-radius: 50%; background: var(--text-muted); }
.workbench-system__dot.is-connected { background: var(--status-success); }
</style>
