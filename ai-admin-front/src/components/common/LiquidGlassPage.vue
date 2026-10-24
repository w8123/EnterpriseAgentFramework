<script setup lang="ts">
import WorkbenchPage from './WorkbenchPage.vue'

defineProps<{ scrollable?: boolean }>()
</script>

<template>
  <WorkbenchPage class="liquid-glass-page" :class="{ 'liquid-glass-page--scrollable': scrollable }" layout="list"><slot /></WorkbenchPage>
</template>

<style scoped lang="scss">
.liquid-glass-page {
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
}

.liquid-glass-page--scrollable {
  overflow-y: auto;
}

.liquid-glass-page > :deep(.collapsible-header-region),
.liquid-glass-page > :deep(.app-page-header) {
  flex: 0 0 auto;
}

@media (max-width: 760px) {
  .liquid-glass-page {
    overflow-y: auto;
  }

  .liquid-glass-page :deep(.app-page-header__eyebrow) {
    display: none;
  }

  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline'])) {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px var(--layout-control-gap);
    padding: 12px;
  }

  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline']) .metric-strip__item + .metric-strip__item) {
    padding: 0;
    border: 0;
  }

  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline']) .metric-icon-bg),
  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline']) .metric-strip__hint) {
    display: none;
  }

  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline']) .metric-strip__label) {
    font-size: 11px;
  }

  .liquid-glass-page :deep(.metric-strip:not([data-presentation='inline']) .metric-strip__value) {
    font-size: 18px;
  }

  .liquid-glass-page :deep(.liquid-glass-table) {
    flex: 1 0 280px;
    min-height: 280px;
  }
}
</style>
