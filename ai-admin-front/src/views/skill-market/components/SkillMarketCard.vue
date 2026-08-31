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
      <el-button
        type="primary"
        size="small"
        :disabled="!item.importable || !canImport"
        @click="$emit('inspect', item)"
      >
        {{ canImport ? (item.importable ? '检查并导入' : '暂不支持导入') : '只读浏览' }}
      </el-button>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from '@/components/common/StatusTag.vue'
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
  grid-template-rows: auto minmax(42px, 1fr) auto auto;
  min-width: 0;
  min-height: 206px;
  gap: 13px;
  padding: 16px;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 15px;
  background:
    linear-gradient(145deg,
      color-mix(in srgb, var(--el-bg-color) 94%, transparent),
      color-mix(in srgb, var(--brand-selected-bg) 34%, var(--el-bg-color)));
  box-shadow:
    0 16px 34px -26px rgb(var(--brand-primary-rgb) / 0.5),
    inset 0 1px 0 rgba(255, 255, 255, 0.76);
  backdrop-filter: blur(18px) saturate(132%);
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
  box-shadow:
    0 20px 38px -24px rgb(var(--brand-primary-rgb) / 0.38),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
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
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 12px;
  color: var(--brand-active);
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgb(var(--brand-selected-rgb) / 0.7));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82);
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
  line-height: 1.6;
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
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  border-radius: 8px;
  background: color-mix(in srgb, var(--brand-selected-bg) 24%, transparent);
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
  border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
}

.market-card footer a {
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 600;
  text-decoration: none;
}

.market-card footer a:hover {
  color: var(--brand-primary);
}
</style>
