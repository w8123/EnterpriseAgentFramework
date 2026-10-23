<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getEvalOpsReadiness } from '@/api/agentEval'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const emit = defineEmits<{ ready: [workerEnabled: boolean] }>()
const workerEnabled = ref<boolean>()

onMounted(async () => {
  try {
    const { data } = await getEvalOpsReadiness()
    workerEnabled.value = Boolean(data?.workerEnabled)
  } catch {
    // 取不到状态时不阻止操作，也不显示误导性的提示。
    workerEnabled.value = undefined
    return
  }
  emit('ready', workerEnabled.value)
})
</script>

<template>
  <LiquidGlassNotice
    v-if="workerEnabled === false"
    source="wallpaper"
    type="warning"
    title="评测执行器未开启，新发起的实验会一直排队、不会运行。"
    description="需要在 Runtime 启动参数里设置 REACHAI_EVAL_WORKER_ENABLED=true 后重启。数据集仍可正常维护。"
  />
</template>
