<script setup lang="ts">
import { computed } from 'vue'
import { a2aLabel, a2aTone } from '@/utils/a2aHub'

const props = defineProps<{ state?: string | null; effect?: 'dark' | 'light' | 'plain' }>()
const tone = computed(() => a2aTone(props.state))
</script>

<template>
  <el-tag :type="tone" :effect="props.effect ?? 'light'" size="small" round>
    {{ a2aLabel(props.state) }}
  </el-tag>
</template>
