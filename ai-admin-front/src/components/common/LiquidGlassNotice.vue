<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, InfoFilled, WarningFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import LiquidGlassCard from './LiquidGlassCard.vue'
import type { LiquidGlassSource } from './liquidGlassContext'

// 页面级提示条：替代直接落在壁纸上的 el-alert，复用卡片磨砂面和壁纸自适应配色。
const props = withDefaults(defineProps<{
  type?: 'info' | 'success' | 'warning' | 'error'
  title?: string
  description?: string
  source?: LiquidGlassSource
}>(), { type: 'info', title: '', description: '' })

const icon = computed(() => ({ info: InfoFilled, success: CircleCheck, warning: WarningFilled, error: CircleCloseFilled })[props.type])
const role = computed(() => (props.type === 'error' || props.type === 'warning' ? 'alert' : 'status'))
</script>

<template>
  <LiquidGlassCard as="div" class="liquid-notice" :class="`liquid-notice--${props.type}`" :source="props.source" :role="role">
    <el-icon class="liquid-notice__icon" aria-hidden="true"><component :is="icon" /></el-icon>
    <div class="liquid-notice__body">
      <strong v-if="props.title || $slots.title" class="liquid-notice__title"><slot name="title">{{ props.title }}</slot></strong>
      <p v-if="props.description || $slots.default" class="liquid-notice__text"><slot>{{ props.description }}</slot></p>
    </div>
    <div v-if="$slots.actions" class="liquid-notice__actions"><slot name="actions" /></div>
  </LiquidGlassCard>
</template>

<style scoped lang="scss">
.liquid-notice {
  --notice-tone: var(--status-info);
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--radius-lg);
}
.liquid-notice--success { --notice-tone: var(--status-success); }
.liquid-notice--warning { --notice-tone: var(--status-warning); }
.liquid-notice--error { --notice-tone: var(--status-danger); }
.liquid-notice__icon { flex: 0 0 auto; margin-top: 2px; color: var(--notice-tone); font-size: 16px; }
.liquid-notice__body { display: grid; flex: 1; min-width: 0; gap: 2px; }
.liquid-notice__title { color: var(--text-primary); font-size: 13px; font-weight: 650; line-height: 1.5; }
.liquid-notice__text { margin: 0; color: var(--text-secondary); font-size: 12.5px; line-height: 1.6; overflow-wrap: anywhere; }
.liquid-notice__actions { display: flex; flex: 0 0 auto; align-items: center; gap: var(--layout-control-gap); }
@media (max-width: 640px) {
  .liquid-notice { flex-wrap: wrap; }
  .liquid-notice__actions { width: 100%; justify-content: flex-end; }
}
@media (forced-colors: active) {
  .liquid-notice__icon { color: CanvasText; }
}
</style>
