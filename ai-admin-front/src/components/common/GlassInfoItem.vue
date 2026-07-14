<script setup lang="ts">
const props = withDefaults(defineProps<{
  label: string
  value: string
  muted?: boolean
  compact?: boolean
}>(), {
  muted: false,
  compact: false,
})
</script>

<template>
  <article class="glass-info-item" :class="{ 'is-compact': props.compact }">
    <span v-if="$slots.leading" class="glass-info-item__leading" aria-hidden="true">
      <slot name="leading" />
    </span>
    <div class="glass-info-item__copy">
      <span>{{ props.label }}</span>
      <code :class="{ 'is-muted': props.muted }" :title="props.value">{{ props.value }}</code>
    </div>
    <div v-if="$slots.trailing" class="glass-info-item__trailing">
      <slot name="trailing" />
    </div>
  </article>
</template>

<style scoped lang="scss">
.glass-info-item {
  display: flex;
  min-width: 0;
  min-height: 68px;
  align-items: center;
  gap: 11px;
  padding: 11px 10px 11px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight);
  -webkit-backdrop-filter: blur(16px) saturate(var(--glass-saturation));
  backdrop-filter: blur(16px) saturate(var(--glass-saturation));
}

.glass-info-item.is-compact {
  min-height: 56px;
  gap: 10px;
  padding: 8px 9px 8px 11px;
}

.glass-info-item__leading {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  flex: 0 0 34px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 10px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  box-shadow: var(--inner-highlight);
}

.glass-info-item__copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 4px;
}

.glass-info-item.is-compact .glass-info-item__copy {
  gap: 3px;
}

.glass-info-item__copy > span {
  color: var(--text-muted);
  font-size: 11px;
}

.glass-info-item__copy > code {
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: 0;
  color: var(--text-primary);
  background: transparent;
  box-shadow: none;
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
  font-size: 12px;
  font-weight: 620;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.glass-info-item__copy > code.is-muted {
  color: var(--text-disabled);
  font-family: inherit;
  font-weight: 520;
}

.glass-info-item__trailing {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
}

[data-reduce-transparency="true"] .glass-info-item {
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

@media (prefers-reduced-transparency: reduce) {
  .glass-info-item {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}
</style>
