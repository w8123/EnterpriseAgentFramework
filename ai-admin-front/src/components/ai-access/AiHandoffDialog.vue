<template>
  <AppDialog
    :model-value="modelValue"
    :title="`交给我的 AI · ${scenario?.title ?? ''}`"
    width="760px"
    class="ai-handoff-dialog"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="!scenario" class="ai-handoff__empty">找不到这个功能的提示词。</div>
    <template v-else>
      <section class="ai-handoff__section" aria-label="AI 工具连接">
        <header class="ai-handoff__head">
          <span class="ai-handoff__step" aria-hidden="true">1</span>
          <div>
            <h3>连接我的 AI 工具</h3>
            <p>所有功能共用这一个连接，接一次就行。</p>
          </div>
        </header>

        <p v-if="status.loading.value && !status.loaded.value" class="ai-handoff__muted">正在检查连接…</p>
        <p v-else-if="status.error.value" class="ai-handoff__error" role="alert">{{ status.error.value }}</p>
        <div v-else-if="status.connected.value && !reconnecting" class="ai-handoff__connected" data-testid="ai-handoff-connected">
          <el-icon aria-hidden="true"><CircleCheckFilled /></el-icon>
          <span>已连接 <strong>{{ toolName(activeConnection?.toolKind) }}</strong>（{{ activeConnection?.label }}）<template v-if="activeConnection?.lastUsedAt">，最近使用 {{ formatAccessTime(activeConnection.lastUsedAt) }}</template></span>
          <LiquidGlassButton link type="primary" @click="reconnecting = true">换一个工具或重新接入</LiquidGlassButton>
        </div>
        <div v-else class="ai-handoff__connect">
          <div v-if="!installPrompt" class="ai-handoff__tools" role="radiogroup" aria-label="选择 AI 工具">
            <label v-for="tool in tools" :key="tool.value" class="ai-handoff__tool" :class="{ active: selectedToolId === tool.value }">
              <input v-model="selectedToolId" type="radio" :value="tool.value" name="ai-handoff-tool">
              <img v-if="tool.icon && !tool.monochrome" :src="tool.icon" alt="" width="20" height="20">
              <span>{{ tool.label }}</span>
            </label>
          </div>
          <LiquidGlassButton v-if="!installPrompt" type="primary" :loading="connecting" data-testid="ai-handoff-connect" @click="connect">获取安装提示词</LiquidGlassButton>
          <p v-if="connectError" class="ai-handoff__error" role="alert">{{ connectError }}</p>
          <template v-if="installPrompt">
            <p class="ai-handoff__muted">安装提示词含个人密钥，只显示这一次。下面「一起复制」会把接入和要做的事合成一段，粘贴一次就行。</p>
          </template>
          <p class="ai-handoff__muted">也可以去 <RouterLink to="/me/ai-connections">AI 工具接入</RouterLink> 页管理全部连接。</p>
        </div>
      </section>

      <section class="ai-handoff__section" aria-label="要交给 AI 的事">
        <header class="ai-handoff__head">
          <span class="ai-handoff__step" aria-hidden="true">2</span>
          <div>
            <h3>{{ scenario.title }}</h3>
            <p>{{ scenario.summary }}</p>
          </div>
        </header>
        <LiquidGlassNotice
          v-if="missing.length"
          type="warning"
          source="backdrop"
          :title="`还缺：${missing.map(item => item.label).join('、')}`"
          :description="missingHint || '请先在页面上完成前一步，再打开这里。'"
        />
        <pre v-else class="ai-handoff__prompt" tabindex="0" data-testid="ai-handoff-prompt" aria-label="提示词">{{ combinedPrompt }}</pre>
        <div class="ai-handoff__actions">
          <LiquidGlassButton type="primary" :disabled="!!missing.length" data-testid="ai-handoff-copy" @click="copy">
            <el-icon aria-hidden="true"><Select v-if="copied" /><CopyDocument v-else /></el-icon>
            {{ copied ? '已复制' : installPrompt ? '一起复制（接入 + 任务）' : '复制提示词' }}
          </LiquidGlassButton>
          <LiquidGlassButton @click="emit('update:modelValue', false)">关闭</LiquidGlassButton>
        </div>
        <p v-if="copied" class="ai-handoff__muted" role="status">已复制，粘贴给你的 AI 工具即可。</p>
        <p v-if="copyError" class="ai-handoff__error" role="alert">{{ copyError }}</p>
        <slot name="fallback" />
      </section>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { CircleCheckFilled, CopyDocument, Select } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassNotice } from '@/components/common/liquid-glass'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import { formatAccessTime, PLATFORM_AI_TOOL_OPTIONS, type AiClientId } from './aiAccess'
import {
  findAiPromptScenario, missingAiPromptVariables, renderAiPrompt, type AiPromptContext,
} from './aiPromptScenarios'
import { useAiConnectionStatus } from './useAiConnectionStatus'

const props = withDefaults(defineProps<{
  modelValue: boolean
  scenario: string
  context?: AiPromptContext
  missingHint?: string
}>(), { context: () => ({}), missingHint: '' })

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const tools = PLATFORM_AI_TOOL_OPTIONS
const status = useAiConnectionStatus()
const scenario = computed(() => findAiPromptScenario(props.scenario))
const activeConnection = computed(() => status.active.value[0])
const selectedToolId = ref<AiClientId>('CLAUDE_CODE')
const reconnecting = ref(false)
const connecting = ref(false)
const connectError = ref('')
const installPrompt = ref('')
const copied = ref(false)
const copyError = ref('')

const missing = computed(() => scenario.value ? missingAiPromptVariables(scenario.value, props.context) : [])
const taskPrompt = computed(() => scenario.value && !missing.value.length ? renderAiPrompt(scenario.value, props.context) : '')
const combinedPrompt = computed(() => installPrompt.value
  ? `${installPrompt.value}\n\n---\n\n接好之后，接着做下面这件事：\n\n${taskPrompt.value}`
  : taskPrompt.value)

watch(() => props.modelValue, (open) => {
  if (!open) {
    // 密钥只留在这一次打开的弹窗里。
    installPrompt.value = ''
    reconnecting.value = false
    copied.value = false
    copyError.value = ''
    connectError.value = ''
    return
  }
  void status.refresh(true)
}, { immediate: true })

watch(combinedPrompt, () => { copied.value = false })

function toolName(kind?: string | null) {
  return tools.find(tool => tool.value === kind)?.label ?? 'AI 工具'
}

async function connect() {
  if (connecting.value) return
  const tool = tools.find(item => item.value === selectedToolId.value) ?? tools[0]!
  connecting.value = true
  connectError.value = ''
  try {
    const { installPrompt: prompt } = await status.connect(tool)
    installPrompt.value = prompt
  } catch {
    connectError.value = '创建连接失败，请检查网络后重试。'
  } finally {
    connecting.value = false
  }
}

async function copy() {
  copyError.value = ''
  const result = await copyAiCodingText(combinedPrompt.value)
  copied.value = result.copied
  if (!result.copied) copyError.value = '复制失败，请选中上面的文字手动复制。'
}
</script>

<style scoped lang="scss">
.ai-handoff__section { display: flex; flex-direction: column; gap: 12px; padding: 4px 0 18px; }
.ai-handoff__section + .ai-handoff__section { border-top: 1px solid var(--border-divider); padding-top: 18px; }
.ai-handoff__head { display: flex; align-items: flex-start; gap: 10px; }
.ai-handoff__head h3 { margin: 0; font-size: 15px; font-weight: 650; }
.ai-handoff__head p { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.ai-handoff__step {
  display: grid; flex: 0 0 24px; height: 24px; place-items: center;
  border: 1px solid var(--border-divider); border-radius: var(--radius-pill);
  color: var(--brand-on-surface); font-family: var(--font-family-mono); font-size: 12px;
}
.ai-handoff__connected { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--text-primary); font-size: 13px; }
.ai-handoff__connected > .el-icon { color: var(--color-success, #16a34a); }
.ai-handoff__connect { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
.ai-handoff__tools { display: flex; flex-wrap: wrap; gap: 8px; }
.ai-handoff__tool {
  display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; cursor: pointer;
  border: 1px solid var(--border-divider); border-radius: var(--radius-pill); font-size: 13px;
  input { position: absolute; opacity: 0; pointer-events: none; }
  &.active { border-color: var(--brand-on-surface); color: var(--brand-on-surface); }
  &:focus-within { outline: 2px solid var(--brand-on-surface); outline-offset: 2px; }
}
.ai-handoff__prompt {
  max-height: 320px; margin: 0; padding: 14px; overflow: auto; white-space: pre-wrap; word-break: break-word;
  border: 1px solid var(--border-divider); border-radius: var(--radius-md, 10px);
  background: var(--surface-solid-opaque); color: var(--text-primary);
  font-family: var(--font-family-mono); font-size: 12px; line-height: 1.7;
}
.ai-handoff__actions { display: flex; flex-wrap: wrap; gap: 10px; }
.ai-handoff__muted { margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.ai-handoff__error { margin: 0; color: var(--color-danger, #dc2626); font-size: 12px; }
</style>
