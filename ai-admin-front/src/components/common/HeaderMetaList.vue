<script setup lang="ts">
export interface HeaderMetaItem {
  key: string
  label: string
  value: string | number
  tone?: 'default' | 'success' | 'warning' | 'danger' | 'info'
}

defineProps<{
  items: HeaderMetaItem[]
}>()
</script>

<template>
  <dl class="header-meta-list">
    <div v-for="item in items" :key="item.key" class="header-meta-list__item">
      <dt>{{ item.label }}：</dt>
      <dd :class="item.tone ? `is-${item.tone}` : undefined">
        <i v-if="item.tone && item.tone !== 'default'" aria-hidden="true" />
        {{ item.value }}
      </dd>
    </div>
  </dl>
</template>

<style scoped lang="scss">
.header-meta-list {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 18px;
  margin: 0;
}

.header-meta-list__item {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  color: var(--text-muted);
}

.header-meta-list dt,
.header-meta-list dd {
  margin: 0;
}

.header-meta-list dd {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-primary);
  font-weight: 700;
}

.header-meta-list dd i {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 3px currentColor;
  opacity: 0.72;
}

.header-meta-list dd.is-success { color: var(--status-success); }
.header-meta-list dd.is-warning { color: var(--status-warning); }
.header-meta-list dd.is-danger { color: var(--status-danger); }
.header-meta-list dd.is-info { color: var(--status-info); }
</style>
