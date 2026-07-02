<template>
  <div class="node-specific-panel">
    <el-divider>知识写入</el-divider>
    <el-form-item label="知识库">
      <el-select v-model="config.knowledgeBaseCode" filterable placeholder="选择目标知识库" style="width: 100%">
        <el-option v-for="item in knowledgeOptions" :key="item.code || item.id" :label="item.name || item.code" :value="item.code || item.id" />
      </el-select>
    </el-form-item>
    <el-form-item label="标题表达式">
      <el-input v-model="config.titleExpression" placeholder="const:工作流写入 / params.title" />
    </el-form-item>
    <el-form-item label="内容表达式">
      <el-input v-model="config.contentExpression" placeholder="lastOutput / answer / nodeOutput.xxx" />
    </el-form-item>
    <el-form-item label="标签">
      <el-input :model-value="config.tags.join(', ')" placeholder="workflow, faq" @update:model-value="config.tags = parseList($event)" />
    </el-form-item>
    <el-form-item label="写入模式">
      <el-segmented v-model="config.mode" :options="modeOptions" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, KnowledgeWriteNodeConfig } from '@/types/studio'
import type { KnowledgeBase } from '@/types/knowledge'

const props = defineProps<{ data: CanvasNodeData; knowledgeOptions: KnowledgeBase[] }>()

const modeOptions = [
  { label: '草稿', value: 'draft' },
  { label: '发布', value: 'publish' },
]

const config = computed<KnowledgeWriteNodeConfig>(() => {
  props.data.knowledgeWriteConfig ||= {
    knowledgeBaseCode: '',
    titleExpression: 'const:工作流写入',
    contentExpression: 'lastOutput',
    tags: [],
    mode: 'draft',
  }
  return props.data.knowledgeWriteConfig
})

function parseList(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}
</script>
