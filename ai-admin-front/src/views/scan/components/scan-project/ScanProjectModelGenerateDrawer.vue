<script setup lang="ts">
import type { ModelInstance } from '@/types/model'

type AiGenerationMode = 'missing' | 'force'

defineProps<{
  semanticModelInstances: ModelInstance[]
  batchStarting: boolean
  taskRunning: boolean
  taskPercent: number
  taskFailed: boolean
  taskFailedTitle: string
}>()

const visible = defineModel<boolean>('visible', { required: true })
const semanticModelInstanceId = defineModel<string>('semanticModelInstanceId', { required: true })
const aiGenerationMode = defineModel<AiGenerationMode>('aiGenerationMode', { required: true })

const emit = defineEmits<{
  startBatchGenerate: [force: boolean]
  saveAiGenerationSettings: []
}>()
</script>

<template>
  <el-drawer
    v-model="visible"
    size="560px"
    title="模型与生成"
    destroy-on-close
    append-to-body
  >
    <p class="ai-settings-hint">模型选择会影响项目级摘要、模块列表、接口单条 AI 文档以及敏感数据扫描。</p>
    <el-form label-width="120px" class="drawer-form" @submit.prevent>
      <el-form-item label="LLM 模型实例">
        <el-select
          v-model="semanticModelInstanceId"
          placeholder="LLM 模型实例"
          filterable
          class="semantic-model-select-wide"
        >
          <el-option
            v-for="item in semanticModelInstances"
            :key="item.id"
            :label="`${item.name} (${item.provider}/${item.modelName})`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="生成策略">
        <el-radio-group v-model="aiGenerationMode">
          <el-radio-button label="missing">仅补齐缺失</el-radio-button>
          <el-radio-button label="force">强制重生成</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <el-alert
      class="drawer-section-alert"
      type="info"
      :closable="false"
      show-icon
      title="强制重生成会覆盖已编辑内容；普通生成会尽量保留已编辑语义。"
    />
    <el-progress
      v-if="taskRunning"
      :percentage="taskPercent"
      :text-inside="true"
      :stroke-width="18"
      class="task-progress"
    />
    <el-alert
      v-if="taskFailed"
      type="error"
      :title="taskFailedTitle"
      :closable="false"
      show-icon
    />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="batchStarting" @click="emit('startBatchGenerate', aiGenerationMode === 'force')">
        一键生成
      </el-button>
      <el-button type="primary" @click="emit('saveAiGenerationSettings')">保存设置</el-button>
    </template>
  </el-drawer>
</template>
