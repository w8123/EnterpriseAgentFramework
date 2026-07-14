<template>
  <StatusTag :label="label" :tone="tone" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from './common/StatusTag.vue'
import type { StatusTone } from './common/glassWorkbench'
import { commonStatusTagType, formatCommonStatusLabel } from '@/utils/uiLabels'

const props = defineProps<{
  status?: string | null
}>()

const label = computed(() => formatCommonStatusLabel(props.status))
const tone = computed<StatusTone>(() => commonStatusTagType(props.status))
</script>
