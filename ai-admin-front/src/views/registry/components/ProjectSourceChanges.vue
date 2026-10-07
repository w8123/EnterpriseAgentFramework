<template>
  <section class="project-source-changes glass-surface-panel" aria-label="项目来源变化">
    <header class="project-source-changes__header">
      <GlassSectionHeader title="来源变化" description="可信 SDK 同步按项目策略自动接纳声明；这里只处理需要确认的例外变化，并保留来源与处理记录。" />
        <el-button v-if="canDiagnose" @click="openDiagnostics">同步诊断</el-button>
    </header>
    <CapabilityReviewPanel v-if="canRead && platformSessionId" :key="contextKey" :project-code="project.projectCode || ''" />
    <el-alert v-else title="当前账号无权读取本项目的来源变化。" type="warning" :closable="false" show-icon />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_READ, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import GlassSectionHeader from '@/components/common/GlassSectionHeader.vue'
import CapabilityReviewPanel from './CapabilityReviewPanel.vue'
import type { ScanProject } from '@/types/scanProject'

const props = defineProps<{ project: ScanProject }>()
const router = useRouter()
const canRead = computed(() => hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_READ, 'PROJECT', null, props.project.projectCode))
const canDiagnose = computed(() => hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WRITE, 'PROJECT', null, props.project.projectCode))
const contextGeneration = ref(0)
watch(() => `${platformSessionId.value}|${props.project.id}|${props.project.projectCode}|${canRead.value}`,
  () => { contextGeneration.value++ }, { flush: 'sync' })
const contextKey = computed(() => `${contextGeneration.value}|${platformSessionId.value}|${props.project.id}|${props.project.projectCode}`)
function openDiagnostics() {
  void router.push({ name: 'RegistrySyncDiagnostics', params: { projectCode: props.project.projectCode } })
}
</script>

<style scoped lang="scss">
.project-source-changes { min-width: 0; padding: 20px 24px; border-radius: var(--radius-lg); }
.project-source-changes__header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.project-source-changes :deep(.capability-review-panel) { padding: 16px 0 0; }
</style>
