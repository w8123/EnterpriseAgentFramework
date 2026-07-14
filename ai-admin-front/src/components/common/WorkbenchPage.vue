<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

type WorkbenchPageLayout = 'flow' | 'list'

const props = withDefaults(
  defineProps<{
    density?: WorkbenchDensity
    fullHeight?: boolean
    layout?: WorkbenchPageLayout
  }>(),
  {
    density: 'comfortable',
    fullHeight: false,
    layout: 'flow',
  },
)
</script>

<template>
  <main
    :class="[
      'workbench-page',
      densityClass(props.density),
      {
        'workbench-page--full-height': props.fullHeight,
        'workbench-page--list': props.layout === 'list',
      },
    ]"
  >
    <slot />
  </main>
</template>

<style scoped lang="scss">
.workbench-page {
  display: flex;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
  padding: var(--layout-page-start) var(--layout-content-inline) var(--layout-page-end);
}

.workbench-page--full-height {
  min-height: 100%;
}

.workbench-page--list {
  padding-bottom: var(--layout-list-page-end);
}

</style>
