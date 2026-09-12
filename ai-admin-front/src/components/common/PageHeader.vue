<script setup lang="ts">
import { computed, useId, useSlots, watchEffect } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

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
      },
    ]"
    :aria-labelledby="titleId"
    :data-collapsed="props.collapsed ? 'true' : 'false'"
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
        <h1 :id="titleId" class="app-page-header__title">{{ props.title }}</h1>
        <div
          v-if="$slots.tags"
          class="app-page-header__tags"
          :aria-hidden="props.collapsed"
          :inert="props.collapsed"
        >
          <slot name="tags" />
        </div>
      </div>

      <p
        v-if="props.description"
        class="app-page-header__description"
        :aria-hidden="props.collapsed"
      >
        {{ props.description }}
      </p>
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
      <div v-if="hasDock" class="app-page-header__action-dock">
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
  height: var(--layout-page-header-height-standard);
  // Progressive enhancement for responsive layouts whose expanded height is auto.
  // Unsupported browsers keep the fixed-height transition used on wider screens.
  interpolate-size: allow-keywords;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--section-gap);
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
      rgb(255 255 255 / 0.98) 0%,
      rgb(255 255 255 / 0.92) 28%,
      rgb(255 255 255 / 0.6) 48%,
      rgb(255 255 255 / 0.15) 72%,
      rgb(255 255 255 / 0.05) 100%
    ),
    linear-gradient(180deg, rgb(255 255 255 / 0.24), rgb(var(--page-header-domain-rgb) / 0.05));
}

.app-page-header--overview,
.app-page-header--workbench {
  height: var(--layout-page-header-height-emphasis);
}

.app-page-header--entity {
  height: var(--layout-page-header-height-standard);
}

.app-page-header--standard.is-compact {
  height: var(--layout-page-header-height-compact);
  padding-block: var(--layout-page-header-compact-padding-block);
  padding-inline: var(--layout-page-header-compact-padding-inline);
}

.app-page-header--height-compact {
  height: var(--layout-page-header-height-compact);
  padding-block: var(--layout-page-header-compact-padding-block);
  padding-inline: var(--layout-page-header-compact-padding-inline);
}

.app-page-header--height-standard {
  height: var(--layout-page-header-height-standard);
}

.app-page-header--height-emphasis {
  height: var(--layout-page-header-height-emphasis);
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
.app-page-header__tags,
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
  border: 1px solid rgb(255 255 255 / 0.9);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.7);
  color: var(--page-header-domain-tone);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.96),
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
  border: 1px solid rgb(255 255 255 / 0.9);
  border-radius: var(--radius-md);
  background: rgb(255 255 255 / 0.82);
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
  font-size: 1.75rem;
}

.app-page-header__tags,
.app-page-header__meta {
  min-width: 0;
  flex-wrap: wrap;
  gap: var(--layout-page-header-tag-gap);
}

.app-page-header__tags {
  max-width: min(44rem, 52vw);
  max-height: 4rem;
  overflow: hidden;
  opacity: 1;
  transform: translateY(0);
  transition:
    max-width var(--motion-duration-normal) var(--motion-easing-standard),
    max-height var(--motion-duration-normal) var(--motion-easing-standard),
    opacity var(--motion-duration-fast) ease,
    transform var(--motion-duration-normal) var(--motion-easing-standard);
}

.app-page-header__tags :deep(.el-tag) {
  min-height: 24px;
  padding-inline: 11px;
  border-color: rgb(255 255 255 / 0.72);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.58);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.86);
  font-weight: 650;
}

.app-page-header__description {
  display: -webkit-box;
  max-width: 72ch;
  max-height: 3rem;
  margin-top: 5px;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 0.875rem;
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
  margin-top: 8px;
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
.app-page-header.is-collapsed .app-page-header__tags,
.app-page-header.is-collapsed .app-page-header__description,
.app-page-header.is-collapsed .app-page-header__meta {
  max-height: 0;
  margin-top: 0;
  margin-bottom: 0;
  opacity: 0;
  transform: translateY(-6px);
  pointer-events: none;
}

.app-page-header.is-collapsed .app-page-header__tags {
  max-width: 0;
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
  padding: 5px;
  border: 1px solid rgb(255 255 255 / 0.88);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.58);
  box-shadow:
    0 14px 28px -20px rgb(15 23 42 / 0.48),
    inset 0 1px 0 rgb(255 255 255 / 0.96);
  backdrop-filter: blur(14px) saturate(0.78);
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
  min-height: 40px;
  border-color: rgb(255 255 255 / 0.92);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.86);
  color: #42516a;
  box-shadow:
    0 9px 18px -14px rgb(15 23 42 / 0.38),
    inset 0 1px 0 rgb(255 255 255 / 0.98);
  font-weight: 700;
}

.app-page-header__actions :deep(.el-button:hover) {
  border-color: rgb(var(--brand-primary-rgb) / 0.45);
  color: var(--brand-active);
  transform: translateY(-1px);
}

.app-page-header__actions :deep(.el-button--primary) {
  border-color: rgb(255 255 255 / 0.84) !important;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--brand-active) 76%, #0f172a 24%),
    color-mix(in srgb, var(--brand-primary) 72%, #0f172a 28%)
  ) !important;
  color: #fff !important;
  box-shadow:
    0 0 0 1px rgb(255 255 255 / 0.42),
    0 13px 26px -13px rgb(15 23 42 / 0.58),
    inset 0 1px 0 rgb(255 255 255 / 0.28) !important;
}

.app-page-header__actions :deep(.el-button.is-circle) {
  width: 40px;
  padding: 0;
  border-radius: 10px;
}

[data-theme='dark'] .app-page-header::after {
  background:
    linear-gradient(
      90deg,
      rgb(8 18 28 / 0.97) 0%,
      rgb(8 18 28 / 0.88) 30%,
      rgb(8 18 28 / 0.58) 52%,
      rgb(8 18 28 / 0.18) 78%,
      transparent 100%
    ),
    linear-gradient(180deg, rgb(8 18 28 / 0.12), rgb(var(--page-header-domain-rgb) / 0.12));
}

[data-theme='dark'] .app-page-header__tags :deep(.el-tag) {
  border-color: color-mix(in srgb, var(--border-readable) 72%, transparent);
  background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--text-primary) 10%, transparent);
}

[data-theme='dark'] .app-page-header__tags :deep(.el-tag--primary) {
  color: color-mix(in srgb, var(--brand-primary) 70%, var(--text-primary));
}

[data-theme='dark'] .app-page-header__tags :deep(.el-tag--info) {
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .app-page-header {
    height: auto;
    min-height: var(--layout-page-header-height-standard);
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
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
    justify-content: space-between;
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-page-header,
  .app-page-header__eyebrow,
  .app-page-header__tags,
  .app-page-header__description,
  .app-page-header__meta {
    transition: none;
  }
}

@media (max-width: 760px) {
  .app-page-header {
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
