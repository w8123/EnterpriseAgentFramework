<script setup lang="ts">
import { computed } from 'vue'
import { Clock, Connection, Document } from '@element-plus/icons-vue'

export type PageAccessCenterTab = 'pages' | 'activities' | 'online'

const props = defineProps<{
  modelValue: PageAccessCenterTab
  pageCount?: number
  activityCount?: number
  onlineCount?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PageAccessCenterTab]
}>()

const tabs = computed(() => [
  { key: 'pages' as const, label: '页面', count: props.pageCount, icon: Document },
  { key: 'activities' as const, label: '活动记录', count: props.activityCount, icon: Clock },
  { key: 'online' as const, label: '在线能力', count: props.onlineCount, icon: Connection },
])
</script>

<template>
  <nav class="page-access-tabs" role="tablist" aria-label="页面接入中心工作区">
    <button
      v-for="tab in tabs"
      :key="tab.key"
      :class="['page-access-tab', { 'is-active': modelValue === tab.key }]"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.key"
      @click="emit('update:modelValue', tab.key)"
    >
      <el-icon><component :is="tab.icon" /></el-icon>
      <span>{{ tab.label }}</span>
      <small v-if="tab.count != null">{{ tab.count }}</small>
    </button>
  </nav>
</template>
