<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import { Back } from '@element-plus/icons-vue'

const CapabilityGraphCanvas = defineAsyncComponent(() => import('@/views/capability/source/graph/CapabilityGraphCanvas.vue'))

defineProps<{
  projectId: number
  apiGraphMounted: boolean
  panelExpanded: boolean
  canWrite?: boolean
}>()
const emit = defineEmits<{ 'return-to-catalog': [] }>()
</script>

<template>
  <section class="source-graph-panel glass-surface-panel" aria-label="契约关联分析工作台">
      <CapabilityGraphCanvas
        v-if="apiGraphMounted"
        :project-id="projectId"
        :panel-expanded="panelExpanded"
        :can-write="canWrite"
      >
        <template #navigation><el-button text :icon="Back" @click="emit('return-to-catalog')">返回能力目录</el-button></template>
      </CapabilityGraphCanvas>
      <el-empty
        v-else
        description="打开关联分析后将加载关系"
        :image-size="80"
      />
  </section>
</template>

<style scoped lang="scss">
.source-graph-panel { display: flex; min-width: 0; flex-direction: column; border: 1px solid var(--border-readable); border-radius: var(--radius-lg); }
@media (min-width: 961px) {
  .source-graph-panel { flex: 1; min-height: 0; }
}
</style>
