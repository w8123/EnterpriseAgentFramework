<template>
  <AppDialog
    :model-value="modelValue"
    title="交给我的 AI"
    width="680px"
    class="ai-handoff-dialog"
    align-center
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="!scenario" class="ai-handoff__empty">找不到这个功能的提示词。</div>
    <div v-else class="ai-handoff">
      <section class="ai-handoff__connection" aria-label="第一步：连接 AI 工具">
        <div class="ai-handoff__head">
          <span class="ai-handoff__step" aria-hidden="true">1</span>
          <div>
            <h3>先连接你的 AI 工具</h3>
            <p>接入一次，所有功能共用。已接入可直接开始。</p>
          </div>
        </div>
        <RouterLink v-slot="{ href, navigate }" to="/me/ai-connections" custom>
          <LiquidGlassButton
            tag="a"
            :href="href"
            type="primary"
            source="backdrop"
            class="ai-handoff__connect-link"
            data-testid="ai-handoff-connect"
            @click="navigateToConnections($event, navigate)"
          >
            前往 AI 工具接入
            <el-icon aria-hidden="true"><Right /></el-icon>
          </LiquidGlassButton>
        </RouterLink>
      </section>

      <section class="ai-handoff__task" :aria-label="examples.length ? '第二步：描述你的需求' : '第二步：交接任务'">
        <header class="ai-handoff__head">
          <span class="ai-handoff__step" aria-hidden="true">2</span>
          <div>
            <h3>{{ scenario.title }}</h3>
            <p>{{ examples.length ? '在已连接的 AI 工具里描述你的需求，例如：' : scenario.summary }}</p>
          </div>
        </header>

        <ul v-if="examples.length" class="ai-handoff__examples" aria-label="提示词示例">
          <li v-for="item in examples" :key="item.title" data-testid="ai-handoff-example">{{ item.description }}</li>
        </ul>
        <div v-else class="ai-handoff__task-content">
          <LiquidGlassNotice
            v-if="missing.length"
            type="warning"
            source="backdrop"
            :title="`还缺：${missing.map(item => item.label).join('、')}`"
            :description="missingHint || '请先在页面上完成前一步，再打开这里。'"
          />
          <div class="ai-handoff__task-actions">
            <LiquidGlassButton
              size="small"
              source="backdrop"
              class="ai-handoff__copy"
              :disabled="!!missing.length || copying"
              :icon="copied ? Select : CopyDocument"
              :aria-busy="copying"
              data-testid="ai-handoff-copy"
              @click="copy"
            >{{ copied ? '已复制' : '复制任务说明' }}</LiquidGlassButton>
            <LiquidGlassButton
              v-if="!missing.length"
              link
              size="small"
              class="ai-handoff__preview-toggle"
              :aria-expanded="previewOpen"
              :aria-controls="previewId"
              @click="previewOpen = !previewOpen"
            >{{ previewOpen ? '收起任务说明' : '查看任务说明' }}</LiquidGlassButton>
          </div>
          <pre
            v-if="previewOpen && !missing.length"
            :id="previewId"
            class="ai-handoff__prompt"
            tabindex="0"
            data-testid="ai-handoff-prompt"
            aria-label="完整任务说明"
          >{{ taskPrompt }}</pre>
          <p v-if="copied" class="ai-handoff__feedback" role="status">已复制任务说明，粘贴给你的 AI 工具即可。</p>
          <p v-if="copyError" class="ai-handoff__error" role="alert">{{ copyError }}</p>
        </div>
        <slot name="fallback" />
      </section>
    </div>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { CopyDocument, Right, Select } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassNotice } from '@/components/common/liquid-glass'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import {
  findAiPromptScenario, missingAiPromptVariables, renderAiPrompt, type AiPromptContext,
} from './aiPromptScenarios'

const props = withDefaults(defineProps<{
  modelValue: boolean
  scenario: string
  context?: AiPromptContext
  missingHint?: string
}>(), { context: () => ({}), missingHint: '' })

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const scenario = computed(() => findAiPromptScenario(props.scenario))
const examples = computed(() => scenario.value?.examples ?? [])
const missing = computed(() => scenario.value ? missingAiPromptVariables(scenario.value, props.context) : [])
const taskPrompt = computed(() => scenario.value && !examples.value.length && !missing.value.length
  ? renderAiPrompt(scenario.value, props.context) : '')

const previewId = useId()
const previewOpen = ref(false)
const copied = ref(false)
const copying = ref(false)
const copyError = ref('')
let revision = 0

watch(() => [props.modelValue, props.scenario, props.context], () => {
  revision += 1
  previewOpen.value = false
  copied.value = false
  copying.value = false
  copyError.value = ''
}, { deep: true })

function navigateToConnections(event: MouseEvent, navigate: (event?: MouseEvent) => unknown) {
  // 保留链接的新标签页等原生行为；当前页跳转时关闭弹窗。
  if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) {
    emit('update:modelValue', false)
  }
  void navigate(event)
}

async function copy() {
  if (missing.value.length || !props.modelValue || copying.value || !taskPrompt.value) return
  const currentRevision = revision
  copying.value = true
  copied.value = false
  copyError.value = ''
  const result = await copyAiCodingText(taskPrompt.value)
  if (currentRevision !== revision) return
  copying.value = false
  if (result.copied) {
    copied.value = true
  } else {
    previewOpen.value = true
    copyError.value = '复制失败，请在展开的任务说明中选中文字手动复制。'
  }
}
</script>

<style scoped lang="scss">
.ai-handoff { display: flex; flex-direction: column; gap: 24px; color: var(--text-primary); }
.ai-handoff__connection { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; }
.ai-handoff__connection .ai-handoff__head { flex: 1 1 280px; }
.ai-handoff__head { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.ai-handoff__head h3 { margin: 0; font-size: 15px; font-weight: 650; line-height: 24px; }
.ai-handoff__head p { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
.ai-handoff__step {
  display: grid; flex: 0 0 24px; height: 24px; place-items: center;
  border: 1px solid var(--border-divider); border-radius: var(--radius-pill);
  color: var(--brand-on-surface); font-family: var(--font-family-mono); font-size: 12px;
}
.ai-handoff__connect-link { flex-shrink: 0; }
.ai-handoff__connect-link :deep(.el-button) { text-decoration: none; }
.ai-handoff__connect-link .el-icon { margin-inline-start: 6px; }
.ai-handoff__task { display: flex; flex-direction: column; gap: 10px; padding-top: 24px; border-top: 1px solid var(--border-divider); }
.ai-handoff__examples {
  display: grid; gap: 6px; margin: 0 0 0 34px; padding-inline-start: 16px;
  color: var(--text-secondary); font-size: 12px; font-weight: 400; line-height: 1.8;
  li { padding-inline-start: 2px; overflow-wrap: anywhere; }
}
.ai-handoff__task-content { display: flex; flex-direction: column; gap: 12px; min-width: 0; margin-inline-start: 34px; }
.ai-handoff__task-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.ai-handoff__copy { inline-size: 120px; }
.ai-handoff__copy :deep(.el-button) { min-inline-size: 0; inline-size: 100%; }
.ai-handoff__prompt {
  box-sizing: border-box; width: 100%; max-height: 240px; margin: 0; padding: 12px;
  overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere;
  border: 1px solid var(--border-divider); border-radius: var(--radius-sm);
  background: var(--surface-solid-opaque); color: var(--text-primary);
  font-family: var(--font-family-mono); font-size: 12px; line-height: 1.7;
}
.ai-handoff__feedback, .ai-handoff__empty { margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
.ai-handoff__error { margin: 0; color: var(--status-danger); font-size: 12px; line-height: 1.7; }
@media (max-width: 540px) {
  .ai-handoff { gap: 20px; }
  .ai-handoff__task { padding-top: 20px; }
  .ai-handoff__connect-link { width: 100%; }
}
</style>
