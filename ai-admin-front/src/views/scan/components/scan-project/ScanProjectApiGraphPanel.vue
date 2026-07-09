<script setup lang="ts">
import { defineAsyncComponent } from 'vue'

const ApiGraphCanvas = defineAsyncComponent(() => import('@/views/scan/ApiGraphCanvas.vue'))

defineProps<{
  projectId: number
  apiGraphMounted: boolean
  panelExpanded: boolean
}>()
</script>

<template>
  <section class="scan-detail-top-item api-graph-card">
    <div class="scan-detail-card-header api-graph-header">
      <span>接口图谱</span>
      <el-tag size="small" type="info" class="api-graph-tag">手动连线 + 数据模型共享自动生成</el-tag>
    </div>
    <div class="scan-detail-card-content">
      <p class="api-graph-hint">
        三色边语义：<span class="legend-blue">蓝-请求引用</span> ·
        <span class="legend-green">绿-响应引用</span> ·
        <span class="legend-purple">紫虚线-数据模型共享（自动）</span>。
        扫描完成后会自动生成紫色虚线；蓝/绿引用关系需要运营开启「连线模式」后手动连线。
      </p>
      <ApiGraphCanvas
        v-if="apiGraphMounted"
        :project-id="projectId"
        :panel-expanded="panelExpanded"
      />
      <el-empty
        v-else
        description="切换到接口图谱后将加载图谱"
        :image-size="80"
      />
    </div>
  </section>
</template>
