<template>
  <component
    :is="collapsible ? 'details' : 'section'"
    class="config-section"
    :class="{ 'is-collapsible': collapsible }"
    :open="collapsible ? defaultOpen : undefined"
  >
    <component :is="collapsible ? 'summary' : 'header'" class="config-section-heading">
      <div class="config-section-title">
        <strong>{{ title }}</strong>
        <span v-if="description">{{ description }}</span>
      </div>
      <span v-if="count !== undefined" class="config-section-count">{{ count }}</span>
      <el-icon v-if="collapsible" class="config-section-chevron"><ArrowRight /></el-icon>
    </component>
    <div class="config-section-body"><slot /></div>
  </component>
</template>

<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'

withDefaults(defineProps<{
  title: string
  description?: string
  count?: string | number
  collapsible?: boolean
  defaultOpen?: boolean
}>(), { collapsible: false, defaultOpen: false })
</script>
