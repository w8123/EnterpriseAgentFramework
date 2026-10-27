<template>
  <article class="market-card">
    <header>
      <div class="identity">
        <span class="source-mark">{{ sourceMark }}</span>
        <div>
          <h3>{{ item.name }}</h3>
          <code>{{ item.source }}/{{ item.slug }}</code>
        </div>
      </div>
      <StatusTag :label="trustLabel(item.trustLevel)" :tone="trustTone(item.trustLevel)" />
    </header>

    <p class="description">{{ description }}</p>

    <div class="signals">
      <span class="install-signal"><strong>{{ formatInstalls(item.installs) }}</strong><small>安装信号</small></span>
      <span class="source-type">{{ sourceType }}</span>
      <StatusTag v-if="item.duplicate" label="疑似副本" tone="warning" />
    </div>

    <footer>
      <a v-if="item.marketUrl" :href="item.marketUrl" target="_blank" rel="noopener noreferrer">查看上游</a>
      <LiquidGlassButton
        type="primary"
        size="small"
        :disabled="!item.importable || !canImport"
        @click="$emit('inspect', item)"
      >
        {{ canImport ? (item.importable ? '检查并导入' : '暂不支持导入') : '只读浏览' }}
      </LiquidGlassButton>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { LiquidGlassButton } from '@/components/common/liquid-glass'
import type { SkillMarketItem } from '@/types/skillMarket'
import { formatInstalls, trustLabel, trustTone } from '../skillMarketPresentation'

const props = defineProps<{
  item: SkillMarketItem
  canImport: boolean
}>()

defineEmits<{
  inspect: [item: SkillMarketItem]
}>()

const sourceMark = computed(() => (props.item.source || '?').slice(0, 2).toUpperCase())
const description = computed(() => props.item.description?.trim()
  || '标准 Agent Skill；导入前读取真实 SKILL.md 与文件树。')
const sourceType = computed(() => {
  if (props.item.sourceType === 'github') return 'GitHub 公共仓库'
  if (props.item.sourceType === 'well-known') return 'Well-known 来源'
  return props.item.sourceType || '公共来源'
})
</script>

<style scoped lang="scss">
.market-card {
  position: relative;
  display: grid;
  grid-template-rows: auto minmax(44px, 1fr) auto auto;
  min-width: 0;
  min-height: 206px;
  gap: 13px;
  padding: 18px;
  overflow: hidden;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-frost);
  box-shadow: var(--shadow-glass-frost);
  -webkit-backdrop-filter: blur(var(--glass-blur-frost));
  backdrop-filter: blur(var(--glass-blur-frost));
  transition: border-color .18s ease, transform .18s ease, box-shadow .18s ease;
}

.market-card::before {
  position: absolute;
  top: -72px;
  right: -54px;
  width: 150px;
  height: 150px;
  border-radius: 999px;
  background: radial-gradient(circle, rgb(var(--brand-primary-rgb) / 0.12), transparent 68%);
  content: '';
  pointer-events: none;
}

.market-card > * {
  position: relative;
  min-width: 0;
}

.market-card:hover {
  transform: translateY(-2px);
  border-color: rgb(var(--brand-primary-rgb) / 0.34);
  box-shadow: var(--shadow-glass-frost);
}

.market-card header,
.market-card footer,
.identity,
.signals {
  display: flex;
  align-items: center;
}

.market-card header {
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.identity {
  flex: 1 1 auto;
  min-width: 0;
  gap: 11px;
}

.identity > div {
  min-width: 0;
}

.source-mark {
  display: grid;
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid var(--border-glass-edge);
  border-radius: 12px;
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  font-size: 12px;
  font-weight: 800;
}

h3 {
  margin: 0 0 5px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 15px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

code {
  display: block;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 10px;
  line-height: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.description {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.7;
  letter-spacing: 0.012em;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.signals {
  min-height: 28px;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.install-signal,
.source-type {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 5px 8px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  background: var(--surface-glass-inset);
}

.install-signal strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 16px;
}

.install-signal small {
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.market-card footer {
  align-self: end;
  justify-content: space-between;
  min-height: 34px;
  gap: 12px;
  padding-top: 11px;
  border-top: 1px solid var(--border-divider);
}

.market-card footer a {
  color: var(--brand-on-surface);
  font-size: 11px;
  font-weight: 600;
  text-decoration: none;
}

.market-card footer a:hover {
  color: var(--brand-primary);
}
</style>
