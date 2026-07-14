<template>
  <div class="app-page-background" :class="{ 'is-local': local }" aria-hidden="true" />
</template>

<script setup lang="ts">
defineProps<{
  local?: boolean
}>()
</script>

<style scoped lang="scss">
.app-page-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: var(--surface-page-background);
  background-size: var(--brand-page-bg-size, auto);
  box-shadow: inset 0 0 0 1px var(--border-subtle), var(--inner-highlight);
  transition:
    background var(--motion-duration-normal) ease,
    box-shadow var(--motion-duration-normal) ease;
}

.app-page-background.is-local {
  border-radius: inherit;
}
</style>
