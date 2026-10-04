<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    detail?: string
    tone?: 'empty' | 'error' | 'disabled'
  }>(),
  {
    detail: '',
    tone: 'empty',
  },
)

defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="dashboard-panel-state" :class="`is-${tone}`" :role="tone === 'error' ? 'alert' : undefined">
    <span class="dashboard-panel-state__signal" aria-hidden="true">
      <span />
    </span>
    <strong>{{ title }}</strong>
    <p v-if="detail">{{ detail }}</p>
    <slot />
    <button v-if="tone === 'error'" type="button" @click="$emit('retry')">重新加载</button>
  </div>
</template>

<style scoped lang="scss">
.dashboard-panel-state {
  min-height: 150px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  padding: 24px;
  text-align: center;
  color: var(--ops-text-muted);
}

.dashboard-panel-state__signal {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--ops-blue) 36%, transparent);
  border-radius: 50%;
  box-shadow: 0 0 22px color-mix(in srgb, var(--ops-blue) 12%, transparent);

  span {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--ops-cyan);
    box-shadow: 0 0 12px var(--ops-cyan);
  }
}

.dashboard-panel-state strong {
  color: var(--ops-text);
  font-size: 14px;
}

.dashboard-panel-state p {
  max-width: 440px;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.dashboard-panel-state button {
  margin-top: 3px;
  padding: 5px 12px;
  color: var(--ops-cyan);
  background: var(--ops-active-background);
  border: 1px solid color-mix(in srgb, var(--ops-blue) 28%, transparent);
  border-radius: 5px;
  cursor: pointer;
}

.dashboard-panel-state.is-error .dashboard-panel-state__signal {
  border-color: var(--ops-danger-border);
  box-shadow: 0 0 22px color-mix(in srgb, var(--ops-danger) 12%, transparent);

  span {
    background: var(--ops-danger);
    box-shadow: 0 0 12px var(--ops-danger);
  }
}

.dashboard-panel-state.is-disabled .dashboard-panel-state__signal {
  filter: grayscale(0.8);
  opacity: 0.7;
}
</style>
