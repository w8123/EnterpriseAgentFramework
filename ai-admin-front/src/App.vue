<template>
  <router-view v-slot="{ Component, route }">
    <transition name="page" mode="out-in">
      <component :is="Component" :key="viewKey(route)" />
    </transition>
  </router-view>
</template>

<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'

// Nested workbenches retain their shell and focus while the active panel changes.
const viewKey = (route: RouteLocationNormalizedLoaded) =>
  typeof route.meta.rootViewKey === 'string' ? route.meta.rootViewKey : route.path
</script>

<style>
/* Page transition */
.page-enter-active,
.page-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
