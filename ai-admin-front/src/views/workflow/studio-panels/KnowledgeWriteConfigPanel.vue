<template>
  <div class="node-specific-panel">
    <ConfigSection title="写入内容" description="把一段文字写进同一项目的知识库；同一次运行重试不会重复写">
      <el-form-item label="知识库">
        <el-select
          :model-value="config.knowledgeBaseCode || undefined"
          filterable
          placeholder="选择目标知识库"
          style="width: 100%"
          @update:model-value="write({ knowledgeBaseCode: String($event || '') })"
        >
          <el-option
            v-for="item in knowledgeOptions"
            :key="item.code || item.id"
            :label="item.name || item.code"
            :value="item.code || item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="标题">
        <el-input
          :model-value="config.titleExpression"
          placeholder="例如：客户 {{ params.name }} 的处理记录"
          @update:model-value="write({ titleExpression: String($event || '') })"
        />
      </el-form-item>
      <el-form-item label="内容">
        <el-input
          :model-value="config.contentExpression"
          type="textarea"
          :rows="3"
          placeholder="变量路径或模板，例如 lastOutput / {{ nodeOutput.llm_1 }}"
          @update:model-value="write({ contentExpression: String($event || '') })"
        />
      </el-form-item>
      <div class="hint">这是写操作：发布给员工 AI 工具时按写操作处理；评测运行不会真的写入。</div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, KnowledgeWriteNodeConfig } from '@/types/studio'
import type { KnowledgeBase } from '@/types/knowledge'

const props = defineProps<{ data: CanvasNodeData; knowledgeOptions: KnowledgeBase[] }>()

const config = computed<KnowledgeWriteNodeConfig>(() => props.data.knowledgeWriteConfig || {
  knowledgeBaseCode: '',
  titleExpression: '工作流写入',
  contentExpression: 'lastOutput',
  tags: [],
  mode: 'draft',
})

function write(patch: Partial<KnowledgeWriteNodeConfig>) {
  props.data.knowledgeWriteConfig = { ...config.value, ...patch }
}
</script>
