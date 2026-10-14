<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { platformSessionId } from '@/auth/platformSession'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import SourceWorkbenchPanel from './SourceWorkbenchPanel.vue'
import type { BusinessCapabilityWorkspace } from '../businessCapabilityRoutes'

const route = useRoute()
const scope = usePageProjectScope()
const projectId = computed(() => scope?.canLoadData.value ? scope.requestParams.value?.projectId ?? null : null)
const contextKey = computed(() => `${platformSessionId.value}|${projectId.value ?? ''}`)
const workspace = computed(() => route.params.workspace as BusinessCapabilityWorkspace)
</script>

<template>
  <ProjectScopeState v-if="!projectId" title="请选择一个项目"
    :message="scope?.feedbackMessage.value || '请从左侧当前范围选择项目后继续接入与变更或整理能力。'"
    :status="scope?.status.value === 'blocked' ? 'blocked' : 'pending'"
    :can-retry="scope?.recoveryAction.value === 'retry-catalog' || scope?.recoveryAction.value === 'retry-normalization'"
    :retrying="scope?.isCatalogLoading.value" @retry="scope?.retryScope()" />
  <SourceWorkbenchPanel v-else :key="contextKey" :project-id="projectId" :workspace="workspace" />
</template>
