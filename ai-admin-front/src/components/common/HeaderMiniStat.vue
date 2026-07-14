<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    hint?: string
  }>(),
  {
    hint: '',
  },
)
</script>

<template>
  <div class="header-mini-stat">
    <span class="header-mini-stat__label">{{ label }}</span>
    <strong class="header-mini-stat__value">{{ value }}</strong>
    <small v-if="hint" class="header-mini-stat__hint">{{ hint }}</small>
  </div>
</template>

<style scoped lang="scss">
.header-mini-stat {
  display: grid;
  min-width: 84px;
  min-height: 56px;
  align-content: center;
  gap: 2px;
  padding: 8px 12px;
  border: 1px solid rgb(255 255 255 / 0.84);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.72);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.94);
  color: var(--text-primary);
}

.header-mini-stat__label,
.header-mini-stat__hint {
  color: var(--text-muted);
  font-size: 0.6875rem;
  line-height: 1.2;
}

.header-mini-stat__value {
  font-size: 1.125rem;
  line-height: 1.2;
}

.header-mini-stat__hint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
