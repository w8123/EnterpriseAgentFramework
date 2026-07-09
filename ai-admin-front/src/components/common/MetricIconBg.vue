<template>
  <span class="metric-icon-bg" :class="`metric-icon-bg--${tone}`" aria-hidden="true">
    <slot v-if="$slots.default" />

    <svg
      v-else-if="iconKey === 'workflow-total'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M8.4 6.2h7.2" />
      <path d="M7.5 8.1 10.4 15" />
      <path d="M16.5 8.1 13.6 15" />
      <circle cx="6.2" cy="6.2" r="2.3" />
      <circle cx="17.8" cy="6.2" r="2.3" />
      <rect x="9.4" y="14.4" width="5.2" height="5.2" rx="1.5" />
    </svg>

    <svg
      v-else-if="iconKey === 'workflow-published'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <rect x="4.8" y="5.1" width="5.4" height="4.8" rx="1.4" />
      <rect x="13.8" y="5.1" width="5.4" height="4.8" rx="1.4" />
      <path d="M10.2 7.5h3.6" />
      <path d="M7.5 9.9v3.2a2 2 0 0 0 2 2h2.8" />
      <circle cx="16.6" cy="16.4" r="3.3" />
      <path d="m15.1 16.4 1 1 2-2.2" />
    </svg>

    <svg
      v-else-if="iconKey === 'workflow-sources'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <rect x="4.7" y="4.6" width="5.4" height="6.4" rx="1.3" />
      <path d="M6.6 6.8h1.7" />
      <path d="M6.6 8.9h1.2" />
      <path d="M16.9 4.5 20.2 6.4v3.9l-3.3 2-3.3-2V6.4l3.3-1.9Z" />
      <path d="M10.1 8h3.5" />
      <path d="M7.4 11v2.4a2 2 0 0 0 2 2h.7" />
      <path d="M16.9 12.3v1.1a2 2 0 0 1-2 2h-.9" />
      <rect x="9.6" y="15.1" width="4.8" height="4.4" rx="1.3" />
    </svg>

    <svg
      v-else-if="iconKey === 'workflow-manual'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <rect x="5" y="4.7" width="13.1" height="14.6" rx="2.2" />
      <path d="M8.2 8.2h4.2" />
      <path d="M8.2 11.3h2.7" />
      <circle cx="14.8" cy="9.6" r="1.2" />
      <path d="m12.4 16 4.1-4.1 2 2-4.1 4.1-2.4.5.4-2.5Z" />
    </svg>

    <svg
      v-else-if="iconKey === 'api-discovery'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M6.2 5.4h8.7a1.8 1.8 0 0 1 1.8 1.8v5.2" />
      <path d="M6.2 5.4v13.2h5.1" />
      <path d="M9 8.8h4.9" />
      <path d="M9 12h3.6" />
      <circle cx="16" cy="16" r="3.2" />
      <path d="m18.3 18.3 2.2 2.2" />
    </svg>

    <svg
      v-else-if="iconKey === 'ai-semantic'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M5.2 7.1h7.2" />
      <path d="M5.2 11.2h5.5" />
      <path d="M5.2 15.3h6.6" />
      <path d="M16.6 5.3l.7 1.6 1.6.7-1.6.7-.7 1.6-.7-1.6-1.6-.7 1.6-.7.7-1.6Z" />
      <circle cx="16.3" cy="16.2" r="2.5" />
      <path d="M18.5 17.6 20.2 19.3" />
    </svg>

    <svg
      v-else-if="iconKey === 'tool-publish'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M6.2 9.7 12 6.4l5.8 3.3-5.8 3.4-5.8-3.4Z" />
      <path d="M6.2 9.7v5.8l5.8 3.4 5.8-3.4V9.7" />
      <path d="M12 13.1v5.8" />
      <path d="M12 2.8v3.4" />
      <path d="m9.8 4.9 2.2-2.1 2.2 2.1" />
    </svg>

    <svg
      v-else-if="iconKey === 'agent-ready'"
      class="metric-icon-bg__svg metric-icon-bg__svg--agent-ready"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <rect x="5.5" y="8" width="11.2" height="9.2" rx="2.4" />
      <path d="M11.1 5.4v2.6" />
      <circle cx="11.1" cy="4.6" r="0.9" />
      <path d="M8.7 12h.1" />
      <path d="M13.4 12h.1" />
      <path d="M9.2 15h3.8" />
      <path d="M16.7 11.4h2.2" />
      <path d="M16.7 15h2.2" />
      <circle cx="20.1" cy="11.4" r="1.2" />
      <circle cx="20.1" cy="15" r="1.2" />
    </svg>

    <svg
      v-else-if="iconKey === 'project'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M4.5 11.4 12 5.2l7.5 6.2" />
      <path d="M6.6 10.4v8.1h10.8v-8.1" />
      <path d="M10 18.5v-4.8h4v4.8" />
    </svg>

    <svg
      v-else-if="iconKey === 'api'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <rect x="6" y="4.5" width="12" height="15" rx="2.2" />
      <path d="M9 8.5h6" />
      <path d="M9 12h6" />
      <path d="M9 15.5h3.8" />
    </svg>

    <svg
      v-else-if="iconKey === 'sdk'"
      class="metric-icon-bg__svg"
      viewBox="0 0 24 24"
      focusable="false"
    >
      <path d="M12 3.8 19 7.7v8.1l-7 4.4-7-4.4V7.7L12 3.8Z" />
      <path d="M5 7.8 12 12l7-4.2" />
      <path d="M12 12v8.2" />
      <text x="12" y="14.4" text-anchor="middle">SDK</text>
    </svg>

    <svg v-else class="metric-icon-bg__svg" viewBox="0 0 24 24" focusable="false">
      <path d="M8.5 5.5H6.8a1.3 1.3 0 0 0-1.3 1.3v1.7" />
      <path d="M15.5 5.5h1.7a1.3 1.3 0 0 1 1.3 1.3v1.7" />
      <path d="M18.5 15.5v1.7a1.3 1.3 0 0 1-1.3 1.3h-1.7" />
      <path d="M5.5 15.5v1.7a1.3 1.3 0 0 0 1.3 1.3h1.7" />
      <path d="M9 12h6" />
      <path d="M12 9v6" />
    </svg>
  </span>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    iconKey?: string
    tone?: string
  }>(),
  {
    iconKey: 'scan',
    tone: 'brand'
  }
)
</script>

<style scoped lang="scss">
.metric-icon-bg {
  position: relative;
  display: inline-grid;
  place-items: center;
  flex: 0 0 var(--metric-icon-bg-size, 48px);
  width: var(--metric-icon-bg-size, 48px);
  height: var(--metric-icon-bg-size, 48px);
  color: var(--brand-active);
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.28);
  border-radius: var(--metric-icon-bg-radius, 14px);
  background:
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.96) 0%, rgba(255, 255, 255, 0) 36%),
    radial-gradient(circle at 72% 86%, rgb(var(--brand-primary-rgb) / 0.24) 0%, rgb(var(--brand-primary-rgb) / 0) 42%),
    linear-gradient(90deg, #ffffff 0%, rgb(var(--brand-selected-rgb) / 0.92) 58%, #ffffff 100%);
  box-shadow:
    0 8px 18px -8px rgb(var(--brand-primary-rgb) / 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    inset 0 0 8px rgb(var(--brand-primary-rgb) / 0.2);
}

.metric-icon-bg__svg {
  width: var(--metric-icon-glyph-size, 24px);
  height: var(--metric-icon-glyph-size, 24px);
  fill: none;
  overflow: visible;
  stroke: currentColor;
  stroke-width: var(--metric-icon-stroke-width, 1.55);
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: drop-shadow(0 2px 4px rgb(var(--brand-primary-rgb) / 0.14));
}

.metric-icon-bg__svg--agent-ready {
  transform: translateY(1.2px) scale(1.08);
  transform-origin: center;
}

.metric-icon-bg--green {
  color: #16a34a;
  border-color: rgba(34, 197, 94, 0.28);
  background:
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.96) 0%, rgba(255, 255, 255, 0) 36%),
    radial-gradient(circle at 72% 86%, rgba(34, 197, 94, 0.22) 0%, rgba(34, 197, 94, 0) 42%),
    linear-gradient(90deg, #ffffff 0%, rgba(240, 253, 244, 0.94) 58%, #ffffff 100%);
  box-shadow:
    0 8px 18px -8px rgba(34, 197, 94, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    inset 0 0 8px rgba(34, 197, 94, 0.18);
}

.metric-icon-bg--info {
  color: #0284c7;
  border-color: rgba(14, 165, 233, 0.28);
  background:
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.96) 0%, rgba(255, 255, 255, 0) 36%),
    radial-gradient(circle at 72% 86%, rgba(14, 165, 233, 0.22) 0%, rgba(14, 165, 233, 0) 42%),
    linear-gradient(90deg, #ffffff 0%, rgba(240, 249, 255, 0.94) 58%, #ffffff 100%);
  box-shadow:
    0 8px 18px -8px rgba(14, 165, 233, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    inset 0 0 8px rgba(14, 165, 233, 0.18);
}

.metric-icon-bg--orange {
  color: #ea580c;
  border-color: rgba(249, 115, 22, 0.28);
  background:
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.96) 0%, rgba(255, 255, 255, 0) 36%),
    radial-gradient(circle at 72% 86%, rgba(249, 115, 22, 0.2) 0%, rgba(249, 115, 22, 0) 42%),
    linear-gradient(90deg, #ffffff 0%, rgba(255, 247, 237, 0.94) 58%, #ffffff 100%);
  box-shadow:
    0 8px 18px -8px rgba(249, 115, 22, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    inset 0 0 8px rgba(249, 115, 22, 0.16);
}

.metric-icon-bg__svg text {
  fill: currentColor;
  font-size: 3.5px;
  font-weight: 800;
  letter-spacing: 0;
  stroke: none;
}

.metric-icon-bg :deep(.el-icon) {
  color: inherit;
  font-size: var(--metric-icon-glyph-size, 24px);
  line-height: 1;
  filter: drop-shadow(0 2px 4px rgb(var(--brand-primary-rgb) / 0.14));
}

.metric-icon-bg :deep(.el-icon svg) {
  width: 1em;
  height: 1em;
}
</style>
