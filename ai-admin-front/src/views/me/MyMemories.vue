<template>
  <WorkbenchPage class="personal-memory-page" layout="list">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="agent"
      title="我的记忆"
      description="记下偏好与工作资料，让 AI 更懂你的习惯。"
    />

    <MemoryWorkspace class="workbench-list-surface" :api="myMemoryApi" source="wallpaper" />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import MemoryWorkspace from '@/components/memory/MemoryWorkspace.vue'
import { myMemoryApi } from '@/api/memory'
</script>
