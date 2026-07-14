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
  display: grid;
  min-width: 0;
  gap: var(--layout-page-gap);
  transition: gap var(--motion-duration-normal) ease;
}

.collapsible-header-region__summary {
  display: grid;
  min-width: 0;
  grid-template-rows: 1fr;
  opacity: 1;
  transform: translateY(0);
  transition:
    grid-template-rows var(--motion-duration-normal) ease,
    opacity var(--motion-duration-fast) ease,
    transform var(--motion-duration-normal) ease;
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
  transform: translateY(-8px);
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .collapsible-header-region,
  .collapsible-header-region__summary {
    transition: none;
  }
}
</style>
