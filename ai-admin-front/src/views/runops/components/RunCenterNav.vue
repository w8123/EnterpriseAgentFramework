<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { platformSessionUser } from '@/auth/platformSession'
import { LiquidGlassNav } from '@/components/common/liquid-glass'
import { activeRunCenterTab, visibleRunCenterTabs } from '../runCenterTabs'

const route = useRoute()
const tabs = computed(() => visibleRunCenterTabs(platformSessionUser.value?.permissions))
const active = computed(() => activeRunCenterTab(route.path))
</script>

<template>
  <LiquidGlassNav v-if="tabs.length > 1" source="wallpaper" class="run-center-nav" aria-label="运行中心页签">
    <router-link
      v-for="tab in tabs"
      :key="tab.key"
      :to="tab.path"
      :class="{ 'is-active': active === tab.key }"
      :aria-current="active === tab.key ? 'page' : undefined"
    >
      {{ tab.label }}
    </router-link>
  </LiquidGlassNav>
</template>

<style scoped lang="scss">
.run-center-nav {
  margin-top: 6px;
}
</style>
