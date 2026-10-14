<template>
  <section class="project-source-changes" :class="{ 'glass-surface-panel': presentation === 'panel', 'project-source-changes--compact': density === 'compact', 'project-source-changes--inline': presentation === 'inline' }" aria-label="项目来源变化">
    <header v-if="presentation !== 'inline'" class="project-source-changes__header">
      <GlassSectionHeader title="来源变化" :description="density === 'compact'
        ? '可信 SDK 声明自动处理，只需确认例外变化。'
        : '可信 SDK 同步按项目策略自动接纳声明；这里只处理需要确认的例外变化，并保留来源与处理记录。'" />
    </header>
    <CapabilityReviewPanel v-if="canRead && platformSessionId" :key="contextKey" ref="reviewPanelRef" :project-code="project.projectCode || ''" :density="density" :presentation="presentation === 'inline' ? 'status' : 'panel'" />
    <el-alert v-else title="当前账号无权读取本项目的来源变化。" type="warning" :closable="false" show-icon />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_READ } from '@/auth/platformAccess'
import GlassSectionHeader from '@/components/common/GlassSectionHeader.vue'
import CapabilityReviewPanel from './CapabilityReviewPanel.vue'
import type { ScanProject } from '@/types/scanProject'

const props = withDefaults(defineProps<{ project: ScanProject; density?: 'comfortable' | 'compact'; presentation?: 'panel' | 'inline' }>(), { density: 'comfortable', presentation: 'panel' })
const reviewPanelRef = ref<InstanceType<typeof CapabilityReviewPanel> | null>(null)
const canRead = computed(() => hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_READ, 'PROJECT', null, props.project.projectCode))
const contextGeneration = ref(0)
watch(() => `${platformSessionId.value}|${props.project.id}|${props.project.projectCode}|${canRead.value}`,
  () => { contextGeneration.value++ }, { flush: 'sync' })
const contextKey = computed(() => `${contextGeneration.value}|${platformSessionId.value}|${props.project.id}|${props.project.projectCode}`)
async function refresh() { await reviewPanelRef.value?.refresh() }
defineExpose({ refresh })
</script>

<style scoped lang="scss">
.project-source-changes { min-width: 0; padding: 20px 24px; border-radius: var(--radius-lg); }
.project-source-changes__header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.project-source-changes :deep(.capability-review-panel) { padding: 16px 0 0; }
.project-source-changes--compact { padding: var(--density-compact-panel-padding); }
.project-source-changes--compact :deep(.capability-review-panel) { padding-top: var(--density-compact-section-gap); }
.project-source-changes--inline { padding: 0; border-radius: 0; }
.project-source-changes--inline :deep(.capability-review-panel) { padding: 0; }
</style>
