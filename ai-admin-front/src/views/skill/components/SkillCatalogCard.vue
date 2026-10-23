<script setup lang="ts">
import { ArrowRight, Document, CircleCheck, Warning } from '@element-plus/icons-vue'
import { LiquidGlassCard } from '@/components/common/liquid-glass'
import StatusTag from '@/components/common/StatusTag.vue'
import type { AgentSkillSummary } from '@/types/skill'
import { skillScopeLabel, skillPublisherTrustLabel, skillStatusLabel, skillStatusTone } from '@/utils/skillLabels'

defineProps<{ skill: AgentSkillSummary }>()
const emit = defineEmits<{ open: [skill: AgentSkillSummary] }>()
</script>

<template>
  <LiquidGlassCard as="article" source="wallpaper" interactive class="skill-card" @click="emit('open', skill)">
    <header class="skill-card__header">
      <el-icon class="skill-card__mark" aria-hidden="true"><Document /></el-icon>
      <div class="skill-card__identity">
        <h3><button type="button" class="skill-card__open" :aria-label="`查看 ${skill.displayName || skill.name} 详情`" @click.stop="emit('open', skill)">
          <span>{{ skill.displayName || skill.name }}</span><el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </button></h3>
        <span class="skill-card__package" :title="`${skill.publisher}/${skill.name}`">{{ skill.publisher }}/{{ skill.name }}</span>
      </div>
    </header>

    <p class="skill-card__description">{{ skill.description || '尚未填写这份岗位工作手册的用途说明。' }}</p>

    <div class="skill-card__context">
      <span class="skill-card__scope" :title="skillScopeLabel(skill)">{{ skillScopeLabel(skill) }}</span>
      <span class="skill-card__trust" :class="{ 'is-unverified': skill.publisher !== 'reachai' }">
        <el-icon aria-hidden="true"><CircleCheck v-if="skill.publisher === 'reachai'" /><Warning v-else /></el-icon>
        {{ skillPublisherTrustLabel(skill.publisher) }}
      </span>
      <StatusTag v-if="skill.latestVersionStatus" :label="skillStatusLabel(skill.latestVersionStatus)" :tone="skillStatusTone(skill.latestVersionStatus)" />
    </div>

    <footer class="skill-card__footer">
      <dl class="skill-card__versions">
        <div><dt>默认</dt><dd>{{ skill.defaultVersion || '未设置' }}</dd></div>
        <div><dt>最新</dt><dd>{{ skill.latestVersion || '—' }}</dd></div>
      </dl>
      <span class="skill-card__version-count">{{ skill.versionCount }} 个版本</span>
    </footer>
  </LiquidGlassCard>
</template>

<style scoped lang="scss">
.skill-card { display: flex; min-width: 0; flex-direction: column; gap: 12px; padding: 16px; }
.skill-card__header { display: flex; align-items: flex-start; gap: 10px; }
.skill-card__mark { flex: 0 0 22px; margin-top: 2px; color: var(--brand-on-surface); font-size: 22px; }
.skill-card__identity { flex: 1; min-width: 0; }
.skill-card__identity h3 { margin: 0; font-size: 16px; line-height: 1.4; }
.skill-card__open { display: flex; width: 100%; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 0; border: 0; border-radius: 4px; color: var(--text-primary); background: transparent; font: inherit; font-weight: 650; text-align: left; cursor: pointer; }
.skill-card__open > span { display: -webkit-box; overflow: hidden; overflow-wrap: anywhere; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.skill-card__open > .el-icon { flex: 0 0 14px; margin-top: 4px; color: var(--text-muted); font-size: 12px; }
.skill-card__open:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 5px; }
.skill-card__package { display: block; overflow: hidden; margin-top: 4px; color: var(--text-muted); font-family: var(--font-family-mono); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.skill-card__description { display: -webkit-box; min-height: 42px; overflow: hidden; margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.6; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.skill-card__context { display: flex; min-width: 0; align-items: center; gap: 8px; margin-top: auto; font-size: 11px; }
.skill-card__scope { overflow: hidden; color: var(--text-secondary); text-overflow: ellipsis; white-space: nowrap; }
.skill-card__trust { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 4px; color: var(--text-muted); }
.skill-card__trust.is-unverified { color: var(--status-warning); }
.skill-card__context :deep(.status-tag) { flex: 0 0 auto; margin-left: auto; font-size: 11px; }
.skill-card__footer { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; padding-top: 10px; border-top: 1px solid var(--border-divider); }
.skill-card__versions { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 0; }
.skill-card__versions > div { display: flex; align-items: baseline; gap: 6px; }
.skill-card__versions dt, .skill-card__version-count { color: var(--text-muted); font-size: 11px; }
.skill-card__versions dd { margin: 0; color: var(--text-primary); font-family: var(--font-family-mono); font-size: 11px; font-weight: 600; }
.skill-card__version-count { flex: 0 0 auto; }
@media (max-width: 390px) {
  .skill-card { padding: 16px; }
  .skill-card__context { flex-wrap: wrap; }
}
</style>
