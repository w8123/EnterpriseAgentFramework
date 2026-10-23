<script setup lang="ts">
import { computed, useId, useSlots, watchEffect } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { densityClass, type WorkbenchDensity } from './glassWorkbench'
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import PageHeaderTags from './PageHeaderTags.vue'

export type PageHeaderVariant = 'overview' | 'entity' | 'workbench' | 'standard'
export type PageHeaderHeightPreset = 'compact' | 'standard' | 'emphasis'
export type PageHeaderDomain =
  | 'project'
  | 'agent'
  | 'workflow'
  | 'tool'
  | 'knowledge'
  | 'governance'
  | 'platform'

const props = withDefaults(
  defineProps<{
    title: string
    eyebrow?: string
    description?: string
    density?: WorkbenchDensity
    variant?: PageHeaderVariant
    heightPreset?: PageHeaderHeightPreset
    domain?: PageHeaderDomain
    compact?: boolean
    collapsed?: boolean
    artwork?: boolean
    presentation?: 'panel' | 'plain'
    actionPresentation?: 'dock' | 'inline'
    backLabel?: string
    showBack?: boolean
  }>(),
  {
    density: 'comfortable',
    variant: 'standard',
    domain: 'platform',
    compact: false,
    collapsed: false,
    artwork: true,
    presentation: 'panel',
    actionPresentation: 'dock',
    backLabel: '返回',
    showBack: false,
  },
)

const emit = defineEmits<{ back: [] }>()
const slots = useSlots()
const titleId = useId()

const hasDock = computed(() => Boolean(slots.actions || (!props.collapsed && slots.mode)))
const hasTrailing = computed(() => Boolean((!props.collapsed && slots.summary) || hasDock.value))

if (import.meta.env.DEV) {
  watchEffect(() => {
    if (props.compact && props.variant !== 'standard') {
      console.warn('[PageHeader] compact 仅适用于 standard 标题栏。')
    }
    if (slots.summary && props.variant !== 'overview') {
      console.warn('[PageHeader] summary 插槽仅适用于 overview 标题栏。')
    }
    if (slots.mode && props.variant !== 'workbench') {
      console.warn('[PageHeader] mode 插槽仅适用于 workbench 标题栏。')
    }
  })
}
</script>

<template>
  <header
    :class="[
      'app-page-header',
      'glass-surface-panel',
      densityClass(props.density),
      `app-page-header--${props.variant}`,
      `app-page-header--${props.domain}`,
      props.heightPreset ? `app-page-header--height-${props.heightPreset}` : undefined,
      {
        'is-compact': props.compact,
        'is-collapsed': props.collapsed,
        'has-artwork': props.artwork,
        'has-leading': props.showBack || Boolean($slots.leading),
        'has-trailing': hasTrailing,
      },
    ]"
    :aria-labelledby="titleId"
    :data-collapsed="props.collapsed ? 'true' : 'false'"
    :data-presentation="props.presentation"
    :data-action-presentation="props.actionPresentation"
  >
    <div v-if="props.showBack || $slots.leading" class="app-page-header__leading">
      <button
        v-if="props.showBack"
        class="app-page-header__back"
        type="button"
        :aria-label="props.backLabel"
        :title="props.backLabel"
        @click="emit('back')"
      >
        <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
      </button>
      <slot name="leading" />
    </div>

    <div class="app-page-header__main">
      <p
        v-if="props.eyebrow"
        class="app-page-header__eyebrow"
        :aria-hidden="props.collapsed"
      >
        {{ props.eyebrow }}
      </p>

      <div class="app-page-header__title-row">
        <h1 :id="titleId" class="app-page-header__title" :title="props.title">{{ props.title }}</h1>
      </div>

      <p
        v-if="props.description"
        class="app-page-header__description"
        :aria-hidden="props.collapsed"
      >
        {{ props.description }}
      </p>
      <PageHeaderTags
        v-if="$slots.tags"
        class="app-page-header__tags"
        :collapsed="props.collapsed"
        :source="props.presentation === 'plain' ? 'wallpaper' : 'backdrop'"
      >
        <slot name="tags" />
      </PageHeaderTags>
      <div
        v-if="$slots.meta"
        class="app-page-header__meta"
        :aria-hidden="props.collapsed"
        :inert="props.collapsed"
      >
        <slot name="meta" />
      </div>
    </div>

    <div v-if="hasTrailing" class="app-page-header__trailing">
      <div v-if="$slots.summary && !props.collapsed" class="app-page-header__summary">
        <slot name="summary" />
      </div>
      <div
        v-if="hasDock"
        class="app-page-header__action-dock"
        :class="{ 'glass-surface-shell liquid-glass-host': props.presentation !== 'plain' && props.actionPresentation === 'dock' }"
      >
        <LiquidGlassLayer v-if="props.presentation !== 'plain' && props.actionPresentation === 'dock'" :bezel="16" :depth="24" />
        <div v-if="$slots.mode && !props.collapsed" class="app-page-header__mode">
          <slot name="mode" />
        </div>
        <div v-if="$slots.actions" class="app-page-header__actions">
          <slot name="actions" />
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-page-header {
  --page-header-domain-tone: var(--brand-primary);
  --page-header-domain-rgb: var(--brand-primary-rgb);
  position: relative;
  isolation: isolate;
  display: grid;
  width: 100%;
  min-width: 0;
  height: auto;
  min-height: var(--layout-page-header-height-standard);
  // Expanded headers grow for descriptions and metadata; collapse keeps its preset.
  // Supporting browsers interpolate between natural and collapsed heights.
  interpolate-size: allow-keywords;
  grid-template-columns: minmax(0, 1fr);
  align-items: center;
  gap: var(--layout-section-gap);
  overflow: hidden;
  padding: var(--layout-page-header-padding-block) var(--layout-page-header-padding-inline);
  border-radius: var(--radius-xl);
  color: var(--text-primary);
  background: var(--surface-glass-panel);
  transition:
    height var(--motion-duration-normal) var(--motion-easing-standard),
    min-height var(--motion-duration-normal) var(--motion-easing-standard),
    padding var(--motion-duration-normal) var(--motion-easing-standard);
}

.app-page-header.has-trailing { grid-template-columns: minmax(0, 1fr) auto; }
.app-page-header.has-leading { grid-template-columns: auto minmax(0, 1fr); }
.app-page-header.has-leading.has-trailing { grid-template-columns: auto minmax(0, 1fr) auto; }

.app-page-header::before,
.app-page-header::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -2;
  border-radius: inherit;
  pointer-events: none;
}

.app-page-header::before {
  background-image: var(--page-header-material);
  background-position: var(--page-header-material-position, center);
  background-size: cover;
  opacity: 0;
  transition: opacity var(--motion-duration-normal) ease;
}

.app-page-header.has-artwork::before {
  opacity: var(--layout-page-header-art-opacity);
}

.app-page-header::after {
  z-index: -1;
  background:
    linear-gradient(
      90deg,
      color-mix(in srgb, var(--surface-solid-opaque) 96%, transparent) 0%,
      color-mix(in srgb, var(--surface-solid-opaque) 88%, transparent) 28%,
      color-mix(in srgb, var(--surface-solid-opaque) 58%, transparent) 48%,
      color-mix(in srgb, var(--surface-solid-opaque) 16%, transparent) 72%,
      transparent 100%
    ),
    linear-gradient(180deg, transparent, rgb(var(--page-header-domain-rgb) / 0.06));
}

.app-page-header--overview,
.app-page-header--workbench {
  min-height: var(--layout-page-header-height-emphasis);
}

.app-page-header--entity {
  min-height: var(--layout-page-header-height-standard);
}

.app-page-header--standard.is-compact {
  min-height: var(--layout-page-header-height-compact);
  padding-block: var(--layout-page-header-compact-padding-block);
  padding-inline: var(--layout-page-header-compact-padding-inline);
}

.app-page-header--height-compact {
  min-height: var(--layout-page-header-height-compact);
  padding-block: var(--layout-page-header-compact-padding-block);
  padding-inline: var(--layout-page-header-compact-padding-inline);
}

.app-page-header--height-standard {
  min-height: var(--layout-page-header-height-standard);
}

.app-page-header--height-emphasis {
  min-height: var(--layout-page-header-height-emphasis);
}

.app-page-header.is-collapsed,
.app-page-header--overview.is-collapsed,
.app-page-header--workbench.is-collapsed,
.app-page-header--entity.is-collapsed,
.app-page-header--standard.is-collapsed {
  height: var(--layout-page-header-height-compact);
  min-height: var(--layout-page-header-height-compact);
  padding-block: var(--layout-page-header-compact-padding-block);
  padding-inline: var(--layout-page-header-compact-padding-inline);
}

.app-page-header--project {
  --page-header-domain-tone: var(--status-success);
  --page-header-domain-rgb: 22 155 107;
}

.app-page-header--workflow {
  --page-header-domain-tone: var(--status-info);
  --page-header-domain-rgb: 54 127 189;
}

.app-page-header--tool,
.app-page-header--knowledge {
  --page-header-domain-tone: color-mix(in srgb, var(--status-success) 55%, var(--status-info));
  --page-header-domain-rgb: var(--brand-primary-rgb);
}

.app-page-header--governance {
  --page-header-domain-tone: var(--status-warning);
  --page-header-domain-rgb: 217 119 6;
}

.app-page-header__leading,
.app-page-header__title-row,
.app-page-header__meta,
.app-page-header__trailing,
.app-page-header__summary,
.app-page-header__action-dock,
.app-page-header__mode,
.app-page-header__actions {
  display: flex;
  align-items: center;
}

.app-page-header__leading {
  position: relative;
  z-index: 1;
  gap: calc(var(--section-gap) / 2);
}

.app-page-header__leading :deep(.app-page-header__entity-mark) {
  display: inline-flex;
  width: var(--layout-page-header-leading-size);
  height: var(--layout-page-header-leading-size);
  flex: 0 0 var(--layout-page-header-leading-size);
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-control);
  color: var(--page-header-domain-tone);
  box-shadow:
    var(--glass-edge-highlight),
    0 12px 24px -18px rgb(var(--page-header-domain-rgb) / 0.65);
  font-size: 1.5rem;
}

.app-page-header__main {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.app-page-header__back {
  display: inline-flex;
  width: var(--control-height);
  height: var(--control-height);
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  box-shadow: var(--inner-highlight);
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.app-page-header__back:hover {
  border-color: var(--page-header-domain-tone);
  color: var(--page-header-domain-tone);
  transform: translateY(-1px);
}

.app-page-header__back:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.app-page-header__eyebrow,
.app-page-header__title,
.app-page-header__description {
  margin: 0;
}

.app-page-header__eyebrow {
  max-height: 1.5rem;
  margin-bottom: 6px;
  overflow: hidden;
  color: var(--page-header-domain-tone);
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.06em;
  opacity: 1;
  transform: translateY(0);
  transition:
    max-height var(--motion-duration-normal) var(--motion-easing-standard),
    margin var(--motion-duration-normal) var(--motion-easing-standard),
    opacity var(--motion-duration-fast) ease,
    transform var(--motion-duration-normal) var(--motion-easing-standard);
  text-transform: uppercase;
}

.app-page-header__title-row {
  min-width: 0;
  gap: var(--layout-page-header-tag-gap);
}

.app-page-header:not(.app-page-header--entity) .app-page-header__title-row::before {
  content: '';
  width: 4px;
  height: 28px;
  flex: 0 0 4px;
  margin-right: 10px;
  border-radius: 999px;
  background: linear-gradient(180deg, var(--page-header-domain-tone), var(--brand-hover));
  box-shadow: 0 8px 18px -10px rgb(var(--page-header-domain-rgb) / 0.7);
}

.app-page-header__title {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 1.5rem;
  font-weight: 760;
  line-height: 1.25;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-page-header--overview .app-page-header__title,
.app-page-header--workbench .app-page-header__title {
  font-size: 1.5rem;
}

.app-page-header__meta {
  min-width: 0;
  flex-wrap: wrap;
  gap: var(--layout-page-header-tag-gap);
}

.app-page-header__description {
  display: -webkit-box;
  max-width: 72ch;
  max-height: 3rem;
  margin-top: var(--layout-page-header-description-gap);
  overflow: hidden;
  color: var(--text-secondary);
  font-size: var(--layout-page-header-description-size);
  line-height: 1.5;
  opacity: 1;
  transform: translateY(0);
  transition:
    max-height var(--motion-duration-normal) var(--motion-easing-standard),
    margin var(--motion-duration-normal) var(--motion-easing-standard),
    opacity var(--motion-duration-fast) ease,
    transform var(--motion-duration-normal) var(--motion-easing-standard);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.app-page-header__meta {
  max-height: 4rem;
  margin-top: var(--layout-page-header-meta-gap);
  overflow: hidden;
  color: var(--text-muted);
  font-size: 0.8125rem;
  opacity: 1;
  transform: translateY(0);
  transition:
    max-height var(--motion-duration-normal) var(--motion-easing-standard),
    margin var(--motion-duration-normal) var(--motion-easing-standard),
    opacity var(--motion-duration-fast) ease,
    transform var(--motion-duration-normal) var(--motion-easing-standard);
}

.app-page-header.is-collapsed .app-page-header__eyebrow,
.app-page-header.is-collapsed .app-page-header__description,
.app-page-header.is-collapsed .app-page-header__meta {
  max-height: 0;
  margin-top: 0;
  margin-bottom: 0;
  opacity: 0;
  transform: translateY(-6px);
  pointer-events: none;
}

.app-page-header__trailing {
  position: relative;
  z-index: 1;
  min-width: 0;
  justify-content: flex-end;
  gap: 12px;
}

.app-page-header__summary {
  gap: 10px;
}

.app-page-header__action-dock {
  max-width: 100%;
  justify-content: flex-end;
  gap: var(--layout-page-header-action-gap);
  position: relative;
  padding: 5px;
  border-radius: var(--radius-lg);
}

.app-page-header__mode,
.app-page-header__actions {
  min-width: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--layout-page-header-action-gap);
}

.app-page-header__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.app-page-header__actions :deep(.el-button) {
  min-height: var(--layout-page-header-action-height);
  border-radius: var(--radius-md);
  font-weight: 650;
}

.app-page-header__actions :deep(.el-button.is-circle) {
  width: 40px;
  padding: 0;
  border-radius: var(--radius-md);
}

.app-page-header[data-presentation='plain'] {
  // 列布局页面内容较高时，plain 页头没有固定高度，不能被压缩裁掉副行。
  flex-shrink: 0;
  height: auto;
  min-height: 0;
  padding: 0;
  align-items: start;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.app-page-header[data-presentation='plain']::before,
.app-page-header[data-presentation='plain']::after { display: none; }

.app-page-header[data-presentation='plain'] .app-page-header__title {
  font-size: var(--layout-page-header-title-size);
  line-height: var(--layout-page-header-title-line-height);
  color: var(--text-on-wallpaper);
  text-shadow: var(--text-on-wallpaper-shadow);
}

.app-page-header[data-presentation='plain'] .app-page-header__description,
.app-page-header[data-presentation='plain'] .app-page-header__meta,
.app-page-header[data-presentation='plain'] .app-page-header__eyebrow {
  color: var(--text-on-wallpaper-secondary);
  text-shadow: var(--text-on-wallpaper-shadow);
}

.app-page-header[data-presentation='plain'] .app-page-header__action-dock {
  padding: 5px;
  border: 0;
  background: var(--surface-solid-control);
  box-shadow: none;
}

.app-page-header[data-action-presentation='inline'] .app-page-header__action-dock {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.app-page-header[data-presentation='plain']:not(.app-page-header--entity) {
  .app-page-header__title-row {
    gap: calc(var(--layout-page-header-title-indent) - var(--layout-page-header-marker-width));
  }

  .app-page-header__title-row::before {
    width: var(--layout-page-header-marker-width);
    height: var(--layout-page-header-title-line-height);
    flex-basis: var(--layout-page-header-marker-width);
    margin-right: 0;
  }

  .app-page-header__description,
  .app-page-header__tags,
  .app-page-header__meta {
    margin-inline-start: var(--layout-page-header-title-indent);
  }
}

.app-page-header[data-presentation='plain'] .app-page-header__trailing {
  align-items: start;
  flex-wrap: wrap;
  gap: var(--layout-page-header-action-gap);
}

@media (max-width: 900px) {
  .app-page-header,
  .app-page-header.has-trailing {
    height: auto;
    min-height: var(--layout-page-header-height-standard);
    grid-template-columns: minmax(0, 1fr);
    align-items: center;
  }

  .app-page-header.has-leading,
  .app-page-header.has-leading.has-trailing {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .app-page-header--overview,
  .app-page-header--workbench {
    min-height: var(--layout-page-header-height-emphasis);
  }

  .app-page-header--standard.is-compact {
    min-height: var(--layout-page-header-height-compact);
  }

  .app-page-header--height-compact {
    min-height: var(--layout-page-header-height-compact);
  }

  .app-page-header--height-standard {
    min-height: var(--layout-page-header-height-standard);
  }

  .app-page-header--height-emphasis {
    min-height: var(--layout-page-header-height-emphasis);
  }

  .app-page-header.is-collapsed {
    min-height: var(--layout-page-header-height-compact);
  }

  .app-page-header__trailing {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-page-header,
  .app-page-header__eyebrow,
  .app-page-header__description,
  .app-page-header__meta {
    transition: none;
  }
}

@media (max-width: 760px) {
  .app-page-header,
  .app-page-header.has-trailing,
  .app-page-header.has-leading,
  .app-page-header.has-leading.has-trailing {
    grid-template-columns: minmax(0, 1fr);
  }

  .app-page-header__leading {
    grid-row: 1;
  }

  .app-page-header__leading + .app-page-header__main {
    grid-row: 2;
  }

  .app-page-header__title {
    display: -webkit-box;
    overflow: hidden;
    text-overflow: initial;
    white-space: normal;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .app-page-header__title-row,
  .app-page-header__trailing {
    flex-wrap: wrap;
  }

  .app-page-header__trailing,
  .app-page-header__action-dock,
  .app-page-header__actions,
  .app-page-header__mode {
    width: 100%;
  }

  .app-page-header__action-dock,
  .app-page-header__actions,
  .app-page-header__mode {
    justify-content: flex-start;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .app-page-header__action-dock {
    background: var(--surface-fallback-overlay);
    backdrop-filter: none;
  }
}
</style>
