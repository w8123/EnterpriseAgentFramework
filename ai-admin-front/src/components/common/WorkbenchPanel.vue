<script setup lang="ts">
import { computed } from 'vue'

import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    title?: string
    description?: string
    density?: WorkbenchDensity
    level?: 'panel' | 'control'
  }>(),
  {
    density: 'comfortable',
    level: 'panel',
  },
)

const surfaceClass = computed(() =>
  props.level === 'control' ? 'glass-surface-control' : 'glass-surface-panel',
)
</script>

<template>
  <section :class="['workbench-panel', surfaceClass, densityClass(props.density)]">
    <header
      v-if="$slots.header || props.title || props.description || $slots.actions"
      class="workbench-panel__header"
    >
      <div class="workbench-panel__heading">
        <slot name="header">
          <h2 v-if="props.title" class="workbench-panel__title">{{ props.title }}</h2>
          <p v-if="props.description" class="workbench-panel__description">
            {{ props.description }}
          </p>
        </slot>
      </div>
      <div v-if="$slots.actions" class="workbench-panel__actions">
        <slot name="actions" />
      </div>
    </header>

    <div class="workbench-panel__body">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="workbench-panel__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped lang="scss">
.workbench-panel {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
  color: var(--text-primary);
}

.workbench-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--section-gap);
}

.workbench-panel__heading,
.workbench-panel__body {
  min-width: 0;
}

.workbench-panel__heading {
  flex: 1;
}

.workbench-panel__title,
.workbench-panel__description {
  margin: 0;
}

.workbench-panel__title {
  font-size: 1rem;
  line-height: 1.4;
}

.workbench-panel__description {
  margin-top: calc(var(--section-gap) / 3);
  color: var(--text-secondary);
  font-size: 0.875rem;
  line-height: 1.55;
}

.workbench-panel__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: calc(var(--section-gap) / 2);
}

.workbench-panel__footer {
  padding-top: calc(var(--section-gap) / 2);
  border-top: 1px solid var(--border-divider);
  color: var(--text-secondary);
}

@media (max-width: 640px) {
  .workbench-panel__header {
    flex-direction: column;
  }

  .workbench-panel__actions {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
