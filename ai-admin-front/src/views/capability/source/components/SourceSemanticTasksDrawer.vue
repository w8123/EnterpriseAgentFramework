<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import type { ModelInstance } from '@/types/model'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

type AiGenerationMode = 'missing' | 'force'

withDefaults(defineProps<{
  semanticModelInstances: ModelInstance[]
  semanticModelInstancesLoading: boolean
  semanticModelInstancesLoadError: boolean
  batchStarting: boolean
  taskRunning: boolean
  taskPercent: number
  taskFailed: boolean
  taskFailedTitle: string
  taskStatusError: string
  canWrite?: boolean
}>(), { canWrite: true })

const visible = defineModel<boolean>('visible', { required: true })
const semanticModelInstanceId = defineModel<string>('semanticModelInstanceId', { required: true })
const aiGenerationMode = defineModel<AiGenerationMode>('aiGenerationMode', { required: true })

const emit = defineEmits<{
  startBatchGenerate: [force: boolean]
  saveAiGenerationSettings: []
  refreshModelInstances: []
  retryTaskStatus: []
}>()

function handleModelSelectVisible(opened: boolean) {
  if (opened) emit('refreshModelInstances')
}
</script>

<template>
  <AppDrawer
    v-model="visible"
    size="min(560px, 96vw)"
    title="批量补充说明"
    destroy-on-close
    append-to-body
  >
    <p class="ai-settings-hint">模型选择影响项目、业务分类、正式资产与来源观察的补充说明，以及敏感数据扫描。</p>
    <p v-if="!canWrite" class="ai-settings-hint" role="status">当前账号仅可阅读业务说明，可查看任务状态和保存本地模型设置。</p>
    <el-form novalidate label-width="120px" class="drawer-form" @submit.prevent>
      <el-form-item label="LLM 模型实例">
        <el-select
          v-model="semanticModelInstanceId"
          placeholder="LLM 模型实例"
          filterable
          class="semantic-model-select-wide"
          :loading="semanticModelInstancesLoading"
          @visible-change="handleModelSelectVisible"
        >
          <el-option
            v-for="item in semanticModelInstances"
            :key="item.id"
            :label="`${item.name} (${item.provider}/${item.modelName})`"
            :value="item.id"
          />
          <template #empty>
            <ModelSelectEmptyState
              model-type="LLM"
              :option-count="semanticModelInstances.length"
              :loading="semanticModelInstancesLoading"
              :load-error="semanticModelInstancesLoadError"
              @retry="emit('refreshModelInstances')"
            />
          </template>
        </el-select>
      </el-form-item>
      <el-form-item label="生成策略">
        <el-radio-group v-model="aiGenerationMode">
          <el-radio-button value="missing">仅补齐缺失</el-radio-button>
          <el-radio-button value="force">强制重生成</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <LiquidGlassNotice
      class="drawer-section-alert"
      type="info"
      title="强制重生成会覆盖已编辑内容；普通生成会尽量保留已编辑语义。"
      source="backdrop"
    />
    <el-progress
      v-if="taskRunning"
      :percentage="taskPercent"
      :text-inside="true"
      :stroke-width="18"
      class="task-progress"
    />
    <LiquidGlassNotice
      v-if="taskFailed"
      type="error"
      :title="taskFailedTitle"
      source="backdrop"
    />
    <LiquidGlassNotice
      v-if="taskStatusError"
      :title="taskStatusError"
      type="warning"
      source="backdrop"
    >
      <template #actions><el-button @click="emit('retryTaskStatus')">重试任务状态</el-button></template>
    </LiquidGlassNotice>
    <template #footer>
      <LiquidGlassButton @click="visible = false">取消</LiquidGlassButton>
      <LiquidGlassButton :loading="batchStarting" :disabled="!canWrite || taskRunning || !!taskStatusError" @click="canWrite && emit('startBatchGenerate', aiGenerationMode === 'force')">
        一键生成
      </LiquidGlassButton>
      <LiquidGlassButton type="primary" @click="emit('saveAiGenerationSettings')">保存设置</LiquidGlassButton>
    </template>
  </AppDrawer>
</template>
