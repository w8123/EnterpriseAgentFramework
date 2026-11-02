<template>
  <div v-if="notices.length" class="knowledge-dependency-notice">
    <LiquidGlassNotice
      v-for="notice in notices"
      :key="notice.key"
      type="warning"
      source="wallpaper"
      :title="notice.title"
      :description="notice.description"
      :data-testid="`knowledge-dependency-${notice.key}`"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getKnowledgeDependencies } from '@/api/knowledge'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { knowledgeDependencyNotices, type KnowledgeDependencyNoticeItem } from './knowledgeDependencyNotices'

const notices = ref<KnowledgeDependencyNoticeItem[]>([])

onMounted(async () => {
  try {
    notices.value = knowledgeDependencyNotices((await getKnowledgeDependencies()).data)
  } catch {
    // 读取失败不影响知识库页面本身，体检页会给出完整检查。
  }
})
</script>

<style scoped lang="scss">
.knowledge-dependency-notice {
  display: grid;
  gap: 8px;
}
</style>
