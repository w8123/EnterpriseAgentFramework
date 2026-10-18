<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { platformSessionUser } from '@/auth/platformSession'
import { activeRunCenterTab, visibleRunCenterTabs } from '../runCenterTabs'

const route = useRoute()
const tabs = computed(() => visibleRunCenterTabs(platformSessionUser.value?.permissions))
const active = computed(() => activeRunCenterTab(route.path))
</script>

<template>
  <nav v-if="tabs.length > 1" class="run-center-nav" aria-label="运行中心页签">
    <router-link
      v-for="tab in tabs"
      :key="tab.key"
      :to="tab.path"
      class="run-center-nav__item"
      :class="{ 'is-active': active === tab.key }"
      :aria-current="active === tab.key ? 'page' : undefined"
    >
      {{ tab.label }}
    </router-link>
  </nav>
</template>

<style scoped lang="scss">
.run-center-nav {
  display: inline-flex;
  max-width: 100%;
  gap: 4px;
  margin-top: 6px;
  padding: 3px;
  overflow-x: auto;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control, transparent);
  scrollbar-width: none;
}

.run-center-nav::-webkit-scrollbar {
  display: none;
}

.run-center-nav__item {
  padding: 4px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm, 6px);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  transition: background var(--motion-duration-fast) ease, color var(--motion-duration-fast) ease;
}

.run-center-nav__item:hover,
.run-center-nav__item.is-active {
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.run-center-nav__item.is-active {
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
}
</style>
