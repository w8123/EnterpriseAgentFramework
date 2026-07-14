<script setup lang="ts">
const props = withDefaults(defineProps<{
  title: string
  description?: string
  meta?: string | number
}>(), {
  description: '',
  meta: '',
})
</script>

<template>
  <div class="glass-section-header">
    <span v-if="$slots.icon" class="glass-section-header__icon" aria-hidden="true">
      <slot name="icon" />
    </span>
    <div class="glass-section-header__copy">
      <strong>{{ props.title }}</strong>
      <span v-if="props.description">{{ props.description }}</span>
    </div>
    <small v-if="props.meta !== ''">{{ props.meta }}</small>
  </div>
</template>

<style scoped lang="scss">
.glass-section-header {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.glass-section-header__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  flex: 0 0 36px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 10px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  box-shadow: var(--inner-highlight);
}

.glass-section-header__icon :deep(.el-icon) {
  font-size: 17px;
}

.glass-section-header__copy {
  min-width: 0;
  flex: 1;
}

.glass-section-header strong {
  display: block;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 720;
}

.glass-section-header__copy > span {
  display: block;
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.glass-section-header > small {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 12px;
}
</style>
