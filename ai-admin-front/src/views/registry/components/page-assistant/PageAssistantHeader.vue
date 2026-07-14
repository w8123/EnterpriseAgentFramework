<script setup lang="ts">
import { computed } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'

const props = defineProps<{
  projectName?: string
  projectCode?: string
}>()

const subtitle = computed(() => {
  const name = props.projectName || props.projectCode || '当前项目'
  const code = props.projectCode && props.projectCode !== name ? ` · ${props.projectCode}` : ''
  return `${name}${code} · 页面助手`
})
</script>

<template>
  <PageHeader variant="workbench" domain="project" title="创建页面助手">
    <template #meta>
      <span>{{ subtitle }}</span>
    </template>
  </PageHeader>
</template>
