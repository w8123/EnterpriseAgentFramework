<template>
  <div class="node-specific-panel">
    <el-divider>知识检索</el-divider>
    <el-form-item label="知识库">
      <el-select v-model="config.knowledgeBaseCodes" multiple filterable placeholder="选择知识库" style="width: 100%">
        <el-option v-for="kb in knowledgeOptions" :key="kb.code" :label="`${kb.name} / ${kb.code}`" :value="kb.code" />
      </el-select>
    </el-form-item>
    <el-form-item label="查询表达式">
      <el-input v-model="config.query" placeholder="例如：用户输入 / 上游输出 / params.question" />
    </el-form-item>
    <el-form-item label="返回数量">
      <el-input-number v-model="config.topK" :min="1" :max="50" />
    </el-form-item>
    <el-form-item label="相似度">
      <el-slider v-model="config.similarityThreshold" :min="0" :max="1" :step="0.01" show-input />
    </el-form-item>
    <el-form-item label="检索模式">
      <el-select v-model="config.searchMode" style="width: 100%">
        <el-option label="hybrid" value="hybrid" />
        <el-option label="vector" value="vector" />
        <el-option label="keyword" value="keyword" />
      </el-select>
    </el-form-item>
    <el-form-item label="重排">
      <el-switch v-model="config.rerankEnabled" />
    </el-form-item>
    <el-form-item label="直接返回">
      <el-switch v-model="config.directReturnEnabled" />
    </el-form-item>
    <el-form-item label="直出阈值">
      <el-slider v-model="config.directReturnThreshold" :min="0" :max="1" :step="0.01" show-input />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, KnowledgeNodeConfig } from '@/types/studio'
import type { KnowledgeBase } from '@/types/knowledge'

const props = defineProps<{
  data: CanvasNodeData
  knowledgeOptions: KnowledgeBase[]
}>()

const config = computed<KnowledgeNodeConfig>(() => {
  props.data.knowledgeConfig ||= {
    knowledgeBaseCodes: [],
    query: 'input',
    topK: 5,
    similarityThreshold: 0.5,
    searchMode: 'hybrid',
    rerankEnabled: true,
    directReturnEnabled: false,
    directReturnThreshold: 0.85,
  }
  return props.data.knowledgeConfig
})
</script>
