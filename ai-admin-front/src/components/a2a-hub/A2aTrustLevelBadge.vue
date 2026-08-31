<script setup lang="ts">
import { computed } from 'vue'
import { a2aLabel, a2aTone } from '@/utils/a2aHub'

const props = defineProps<{ level?: string | null }>()
const tone = computed(() => a2aTone(props.level))
</script>

<template>
  <el-tag :type="tone" effect="plain" size="small">{{ a2aLabel(props.level) }}</el-tag>
</template>
