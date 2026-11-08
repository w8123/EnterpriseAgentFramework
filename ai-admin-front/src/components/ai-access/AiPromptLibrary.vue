<template>
  <div class="ai-prompt-library">
    <p class="ai-prompt-library__intro">{{ intro }}</p>
    <div class="ai-prompt-library__filters">
      <LiquidGlassInput v-model="keyword" source="backdrop" clearable placeholder="搜索提示词" aria-label="搜索提示词" />
    </div>
    <p v-if="!groups.length" class="ai-prompt-library__empty">没有匹配的提示词。</p>
    <section v-for="group in groups" :key="group.category" class="ai-prompt-library__group" :aria-label="group.label">
      <h3>{{ group.label }}</h3>
      <div class="ai-prompt-library__grid">
        <article v-for="item in group.scenarios" :key="item.key" class="ai-prompt-library__card" :data-testid="`ai-prompt-${item.key}`">
          <header>
            <strong>{{ item.title }}</strong>
            <p>{{ item.summary }}</p>
          </header>
          <template v-if="pageOnly(item)">
            <p class="ai-prompt-library__hint">这个提示词需要在对应页面里打开（会带上任务编号和一次性领取码）。</p>
          </template>
          <template v-else>
            <div v-if="editableVariables(item).length" class="ai-prompt-library__vars">
              <label v-for="variable in editableVariables(item)" :key="variable.name">
                <span>{{ variable.label }}<em v-if="variable.required">*</em></span>
                <LiquidGlassInput v-model="values[variable.name]" source="backdrop" size="small" :aria-label="variable.label" :placeholder="variable.required ? '必填' : '可选'" />
              </label>
            </div>
            <div class="ai-prompt-library__actions">
              <LiquidGlassButton size="small" type="primary" :disabled="missing(item)" @click="copy(item)">
                <el-icon aria-hidden="true"><Select v-if="copiedKey === item.key" /><CopyDocument v-else /></el-icon>
                {{ copiedKey === item.key ? '已复制' : '复制' }}
              </LiquidGlassButton>
              <LiquidGlassButton size="small" link @click="previewKey = previewKey === item.key ? '' : item.key">{{ previewKey === item.key ? '收起' : '预览' }}</LiquidGlassButton>
            </div>
            <pre v-if="previewKey === item.key" class="ai-prompt-library__preview" tabindex="0">{{ renderAiPrompt(item, values) }}</pre>
          </template>
        </article>
      </div>
    </section>
    <p v-if="copyError" class="ai-prompt-library__error" role="alert">{{ copyError }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { CopyDocument, Select } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassInput } from '@/components/common/liquid-glass'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import {
  AI_PROMPT_SCENARIOS, groupAiPromptScenarios, missingAiPromptVariables, renderAiPrompt,
  type AiPromptAudience, type AiPromptScenario,
} from './aiPromptScenarios'

/** 只能从页面上带出来的变量（一次性领取码等），提示词库里不让手填。 */
const PAGE_ONLY_VARIABLES = new Set(['taskId', 'claimCode'])

const props = withDefaults(defineProps<{
  connection: 'platform' | 'employee'
  audience?: AiPromptAudience[]
  intro?: string
  initialValues?: Record<string, string>
}>(), {
  audience: () => [],
  intro: '这些提示词都用你已经接好的那一个 AI 工具连接。复制后粘贴给 Codex、Claude Code 等即可；功能页面上的「交给我的 AI」按钮会自动填好项目和任务。',
  initialValues: () => ({}),
})

const keyword = ref('')
const values = reactive<Record<string, string>>({ ...props.initialValues })
const copiedKey = ref('')
const previewKey = ref('')
const copyError = ref('')

const visible = computed(() => AI_PROMPT_SCENARIOS.filter(item => item.connection === props.connection
  && (!props.audience.length || item.audience.some(role => props.audience.includes(role)))
  && (!keyword.value.trim() || `${item.title}${item.summary}`.includes(keyword.value.trim()))))
const groups = computed(() => groupAiPromptScenarios(visible.value))

function pageOnly(item: AiPromptScenario) {
  return item.variables.some(variable => variable.required && PAGE_ONLY_VARIABLES.has(variable.name))
}

function editableVariables(item: AiPromptScenario) {
  return item.variables.filter(variable => !PAGE_ONLY_VARIABLES.has(variable.name))
}

function missing(item: AiPromptScenario) {
  return missingAiPromptVariables(item, values).length > 0
}

async function copy(item: AiPromptScenario) {
  copyError.value = ''
  const result = await copyAiCodingText(renderAiPrompt(item, values))
  copiedKey.value = result.copied ? item.key : ''
  if (!result.copied) {
    previewKey.value = item.key
    copyError.value = '复制失败，请在预览里选中文字手动复制。'
  }
}
</script>

<style scoped lang="scss">
.ai-prompt-library { display: flex; flex-direction: column; gap: 18px; padding: 20px; color: var(--text-primary); }
.ai-prompt-library__intro { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.ai-prompt-library__filters { max-width: 320px; }
.ai-prompt-library__group h3 { margin: 0 0 10px; font-size: 14px; font-weight: 650; }
.ai-prompt-library__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.ai-prompt-library__card {
  display: flex; flex-direction: column; gap: 10px; padding: 14px;
  border: 1px solid var(--border-divider); border-radius: var(--radius-md, 10px);
  header strong { font-size: 14px; }
  header p { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
}
.ai-prompt-library__vars { display: grid; gap: 8px; }
.ai-prompt-library__vars label { display: flex; flex-direction: column; gap: 4px; }
.ai-prompt-library__vars span { color: var(--text-secondary); font-size: 12px; }
.ai-prompt-library__vars em { color: var(--color-danger, #dc2626); font-style: normal; margin-left: 2px; }
.ai-prompt-library__actions { display: flex; align-items: center; gap: 8px; margin-top: auto; }
.ai-prompt-library__hint, .ai-prompt-library__empty { margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.ai-prompt-library__preview {
  max-height: 260px; margin: 0; padding: 10px; overflow: auto; white-space: pre-wrap; word-break: break-word;
  border: 1px solid var(--border-divider); border-radius: 8px; background: var(--surface-solid-opaque);
  font-family: var(--font-family-mono); font-size: 12px; line-height: 1.6;
}
.ai-prompt-library__error { margin: 0; color: var(--color-danger, #dc2626); font-size: 12px; }
@media (max-width: 640px) { .ai-prompt-library { padding: 14px; } .ai-prompt-library__filters { max-width: none; } }
</style>
