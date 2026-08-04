<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    collapsed?: boolean
  }>(),
  {
    collapsed: false,
  },
)
</script>

<template>
  <section
    :class="['collapsible-header-region', { 'is-collapsed': props.collapsed }]"
    :data-collapsed="props.collapsed ? 'true' : 'false'"
  >
    <slot />
    <div
      v-if="$slots.summary"
      class="collapsible-header-region__summary"
      :aria-hidden="props.collapsed"
    >
      <div class="collapsible-header-region__summary-inner">
        <slot name="summary" />
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.collapsible-header-region {
  position: sticky;
  top: 0;
  z-index: 20;
  display: grid;
  min-width: 0;
  gap: var(--layout-page-gap);
  // Keep list content from flashing through the sticky chrome while collapsing.
  background: color-mix(in srgb, var(--surface-solid-page) 92%, transparent);
  backdrop-filter: blur(10px);
}

.collapsible-header-region__summary {
  display: grid;
  min-width: 0;
  grid-template-rows: 1fr;
  opacity: 1;
  overflow: hidden;
}

.collapsible-header-region__summary-inner {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.collapsible-header-region.is-collapsed {
  gap: 0;
}

.collapsible-header-region.is-collapsed .collapsible-header-region__summary {
  grid-template-rows: 0fr;
  opacity: 0;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .collapsible-header-region__summary {
    transition: none;
  }
}
</style>
