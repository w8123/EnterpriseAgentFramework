<template>
  <div class="node-specific-panel">
    <ConfigSection title="检索来源" description="选择知识库与检索内容">
      <el-form-item label="知识库">
        <el-select v-model="config.knowledgeBaseCodes" multiple filterable placeholder="选择知识库" style="width: 100%">
          <el-option
            v-for="kb in knowledgeOptions"
            :key="kb.code"
            :label="`${kb.name} / ${kb.code}`"
            :value="kb.code"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="查询表达式">
        <el-input v-model="config.query" placeholder="例如：用户输入 / 上游输出 / params.question" />
      </el-form-item>
    </ConfigSection>
    <ConfigSection
      title="检索参数"
      description="默认沿用知识库设置，按需覆盖"
      collapsible
      :default-open="!followKnowledgeBase"
    >
      <el-form-item label="检索设置">
        <el-switch
          :model-value="followKnowledgeBase"
          active-text="使用知识库设置"
          @update:model-value="setFollowKnowledgeBase(Boolean($event))"
        />
      </el-form-item>
      <template v-if="!followKnowledgeBase">
        <el-form-item label="返回数量">
          <el-input-number v-model="config.topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="相似度">
          <el-slider v-model="config.similarityThreshold" :min="0" :max="1" :step="0.01" show-input />
        </el-form-item>
        <el-form-item label="检索模式">
          <el-select v-model="config.searchMode" style="width: 100%">
            <el-option label="混合（向量 + 关键词）" value="hybrid" />
            <el-option label="向量" value="vector" />
            <el-option label="关键词" value="keyword" />
          </el-select>
        </el-form-item>
        <el-form-item label="重排">
          <el-switch v-model="config.rerankEnabled" />
        </el-form-item>
      </template>
    </ConfigSection>
    <ConfigSection title="无结果时怎么处理" description="决定是否要求检索证据与分支连线">
      <el-form-item label="证据策略">
        <el-select v-model="config.evidencePolicy" style="width: 100%">
          <el-option label="必须有证据（推荐）" value="REQUIRED" />
          <el-option label="允许通用知识回退" value="OPTIONAL" />
        </el-select>
      </el-form-item>
      <LiquidGlassNotice
        :title="evidencePolicyHint"
        :type="config.evidencePolicy === 'REQUIRED' ? 'warning' : 'info'"
        source="backdrop"
      />
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, watch } from 'vue'
import type { CanvasNodeData, KnowledgeNodeConfig } from '@/types/studio'
import type { KnowledgeBase } from '@/types/knowledge'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
  knowledgeOptions: KnowledgeBase[]
}>()

const config = computed<KnowledgeNodeConfig>(() => {
  props.data.knowledgeConfig ||= {
    knowledgeBaseCodes: [],
    query: 'input',
    evidencePolicy: 'REQUIRED',
  }
  const cfg = props.data.knowledgeConfig as KnowledgeNodeConfig & {
    directReturnEnabled?: boolean
    directReturnThreshold?: number
  }
  if (!['REQUIRED', 'OPTIONAL'].includes(cfg.evidencePolicy)) {
    // Existing canvas snapshots had no policy and must retain their old linear behavior.
    cfg.evidencePolicy = 'OPTIONAL'
  }
  delete cfg.directReturnEnabled
  delete cfg.directReturnThreshold
  return cfg
})

const followKnowledgeBase = computed(() => config.value.topK == null
  && config.value.similarityThreshold == null
  && !config.value.searchMode
  && config.value.rerankEnabled == null)

function setFollowKnowledgeBase(follow: boolean) {
  const cfg = config.value
  if (follow) {
    delete cfg.topK
    delete cfg.similarityThreshold
    delete cfg.searchMode
    delete cfg.rerankEnabled
    return
  }
  cfg.topK = 5
  cfg.similarityThreshold = 0.5
  cfg.searchMode = 'hybrid'
  cfg.rerankEnabled = true
}

const evidencePolicyHint = computed(() => config.value.evidencePolicy === 'REQUIRED'
  ? '必须连接“有证据”和“无证据”两个分支；无证据分支应使用固定回复，禁止继续交给 LLM 凭空生成。'
  : '仅在业务明确允许通用模型知识回退时使用；无证据时仍沿普通连线继续。')

watch(
  () => props.data.knowledgeConfig,
  (value) => {
    if (!value) return
    const cfg = value as KnowledgeNodeConfig & {
      directReturnEnabled?: boolean
      directReturnThreshold?: number
    }
    delete cfg.directReturnEnabled
    delete cfg.directReturnThreshold
  },
  { deep: true },
)
</script>
