<template>
  <LiquidGlassCard class="ai-setup" as="div" source="wallpaper">
    <template v-if="!prompt">
      <section :aria-labelledby="toolsTitleId">
        <h2 :id="toolsTitleId" ref="toolsHeading" tabindex="-1">选择你正在用的 AI 工具</h2>
        <div class="ai-setup__tools" :class="{ 'ai-setup__tools--compact': tools.length <= 3 }" role="radiogroup" :aria-labelledby="toolsTitleId">
          <label
            v-for="tool in tools"
            :key="tool.value"
            class="ai-setup__tool"
            :class="{ active: selectedTool.value === tool.value, 'is-locked': creating }"
            :data-tool-kind="tool.value"
          >
            <input
              type="radio"
              :name="radioName"
              :value="tool.value"
              :checked="selectedTool.value === tool.value"
              :disabled="creating"
              @change="selectTool(tool)"
            >
            <span class="ai-setup__tool-icon" aria-hidden="true">
              <span v-if="tool.monochrome" class="ai-setup__monochrome-icon" :style="{ maskImage: `url(${JSON.stringify(tool.icon)})` }" />
              <img v-else-if="tool.icon" :src="tool.icon" alt="" width="26" height="26">
              <el-icon v-else :size="22"><Connection /></el-icon>
            </span>
            <strong>{{ tool.label }}</strong>
            <el-icon v-if="selectedTool.value === tool.value" class="ai-setup__selected" aria-hidden="true"><Check /></el-icon>
          </label>
        </div>
      </section>

      <section v-if="!scopeSummary" class="ai-setup__required-scope">
        <h2>{{ scopeTitle }}</h2>
        <p v-if="scopeHint" class="ai-setup__hint">{{ scopeHint }}</p>
        <slot name="scope" />
      </section>
    </template>

    <section class="ai-setup__connection" :class="{ 'is-result': !!prompt }" :aria-labelledby="connectionTitleId">
      <div class="ai-setup__pair" aria-hidden="true">
        <span class="ai-setup__tool-icon">
          <span v-if="selectedTool.monochrome" class="ai-setup__monochrome-icon" :style="{ maskImage: `url(${JSON.stringify(selectedTool.icon)})` }" />
          <img v-else-if="selectedTool.icon" :src="selectedTool.icon" alt="" width="26" height="26">
          <el-icon v-else :size="22"><Connection /></el-icon>
        </span>
        <el-icon class="ai-setup__pair-link"><Connection /></el-icon>
        <img class="ai-setup__brand" src="/reachai-logo-tile.png" alt="" width="40" height="40">
      </div>

      <div class="ai-setup__connection-content">
        <div>
          <h2 :id="connectionTitleId" ref="connectionHeading" :tabindex="prompt ? -1 : undefined">
            {{ prompt ? `在 ${selectedTool.label} 中完成接入` : `连接 ${selectedTool.label}` }}
          </h2>
          <p class="ai-setup__hint">
            {{ prompt && !selectedTool.manual ? `复制提示词并粘贴给 ${selectedTool.label}，让它添加 ReachAI 连接。` : selectedTool.guide }}
          </p>
          <a v-if="selectedTool.docsUrl" class="ai-setup__docs" :href="selectedTool.docsUrl" target="_blank" rel="noopener noreferrer">
            查看 {{ selectedTool.label }} 接入文档 <el-icon aria-hidden="true"><TopRight /></el-icon>
          </a>
        </div>

        <template v-if="!prompt">
          <div class="ai-setup__controls">
            <LiquidGlassButton
              class="ai-setup__create"
              type="primary"
              source="backdrop"
              :loading="creating"
              :disabled="!canCreate || creating"
              :aria-busy="creating"
              :aria-describedby="!canCreate && disabledReason ? disabledReasonId : undefined"
              data-testid="get-install-prompt"
              @click="emit('create')"
            >{{ selectedTool.manual ? '生成接入配置' : '获取安装提示词' }}<el-icon v-if="!creating" aria-hidden="true"><ArrowRight /></el-icon></LiquidGlassButton>
            <details v-if="scopeSummary" class="ai-setup__scope">
              <summary class="ai-setup__disclosure">
                <el-icon aria-hidden="true"><Setting /></el-icon>
                {{ scopeTitle }} <span class="ai-setup__detail">· {{ scopeSummary }}</span>
                <el-icon class="ai-setup__chevron" aria-hidden="true"><ArrowDown /></el-icon>
              </summary>
              <div class="ai-setup__scope-content">
                <p v-if="scopeHint" class="ai-setup__hint">{{ scopeHint }}</p>
                <slot name="scope" />
              </div>
            </details>
          </div>
          <p v-if="!canCreate && disabledReason" :id="disabledReasonId" class="ai-setup__hint ai-setup__message">{{ disabledReason }}</p>
          <p v-if="error" class="ai-setup__error ai-setup__message" role="alert">{{ error }}</p>
        </template>

        <template v-else>
          <div class="ai-setup__actions">
            <LiquidGlassButton type="primary" source="backdrop" @click="copyPrompt">
              <el-icon aria-hidden="true"><CopyDocument /></el-icon>{{ selectedTool.manual ? '复制配置' : '复制提示词' }}
            </LiquidGlassButton>
            <LiquidGlassButton link source="backdrop" @click="finish">关闭接入信息</LiquidGlassButton>
          </div>
          <p v-if="copied" class="ai-setup__copied ai-setup__message" role="status">已复制，请到 {{ selectedTool.label }} 中继续。</p>
          <p v-if="promptCopyFailed" class="ai-setup__error ai-setup__message" role="alert">复制失败，请选中文本手动复制。</p>
          <p class="ai-setup__security ai-setup__message">
            <el-icon aria-hidden="true"><Lock /></el-icon>
            <span>内容含个人密钥，关闭后无法再次查看。请在关闭前复制，并妥善保管。</span>
          </p>
          <details ref="promptPreview" class="ai-setup__message">
            <summary class="ai-setup__disclosure">
              <el-icon aria-hidden="true"><Document /></el-icon>
              查看{{ materialName }}
              <el-icon class="ai-setup__chevron" aria-hidden="true"><ArrowDown /></el-icon>
            </summary>
            <pre class="ai-setup__prompt" data-testid="install-prompt" tabindex="0" :aria-label="materialName">{{ prompt }}</pre>
          </details>
        </template>
      </div>
    </section>

    <footer v-if="tryPhrases.length || $slots.footer" class="ai-setup__footer">
      <details v-if="tryPhrases.length" class="ai-setup__examples">
        <summary class="ai-setup__disclosure">
          <el-icon aria-hidden="true"><ChatLineRound /></el-icon>
          接入后，试着说
          <el-icon class="ai-setup__chevron" aria-hidden="true"><ArrowDown /></el-icon>
        </summary>
        <button v-for="phrase in tryPhrases" :key="phrase" type="button" :aria-label="`复制示例：${phrase}`" @click="copyPhrase(phrase)">
          <span>{{ phrase }}</span><el-icon aria-hidden="true"><Select v-if="copiedPhrase === phrase" /><CopyDocument v-else /></el-icon>
        </button>
        <p v-if="copiedPhrase" class="ai-setup__copied" role="status">示例已复制</p>
        <p v-if="phraseCopyFailed" class="ai-setup__error" role="alert">复制失败，请选中文本手动复制。</p>
      </details>
      <slot name="footer" />
    </footer>
  </LiquidGlassCard>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { ArrowDown, ArrowRight, ChatLineRound, Check, Connection, CopyDocument, Document, Lock, Select, Setting, TopRight } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassCard } from '@/components/common/liquid-glass'
import { AI_TOOL_OPTIONS, type AiClientId, type AiToolKind, type AiToolOption } from './aiAccess'

const props = withDefaults(defineProps<{
  scopeTitle: string
  scopeHint?: string
  scopeSummary?: string
  tools?: AiToolOption[]
  /** Kept only in memory, immediately after creating a credential. */
  prompt?: string | null
  creating?: boolean
  canCreate?: boolean
  disabledReason?: string
  error?: string
  tryPhrases?: string[]
}>(), { scopeHint: '', scopeSummary: '', tools: () => AI_TOOL_OPTIONS, prompt: null, creating: false, canCreate: true, disabledReason: '', error: '', tryPhrases: () => [] })

const emit = defineEmits<{ (event: 'create'): void; (event: 'done'): void }>()
const toolKind = defineModel<AiToolKind>('toolKind', { required: true })
const clientId = defineModel<AiClientId>('clientId')
const selectedTool = computed(() => props.tools.find(tool => tool.value === clientId.value)
  ?? props.tools.find(tool => tool.value === toolKind.value) ?? props.tools[0]!)
const radioName = `ai-setup-tool-${useId()}`
const toolsTitleId = useId()
const connectionTitleId = useId()
const disabledReasonId = useId()
const materialName = computed(() => selectedTool.value.manual ? '接入配置' : '安装提示词')
const toolsHeading = ref<HTMLElement>()
const connectionHeading = ref<HTMLElement>()
const promptPreview = ref<HTMLDetailsElement>()
const copied = ref(false)
const promptCopyFailed = ref(false)
const copiedPhrase = ref('')
const phraseCopyFailed = ref(false)
let copyGeneration = 0

watch(() => props.prompt, (prompt) => {
  copyGeneration += 1
  copied.value = false
  promptCopyFailed.value = false
  if (prompt) connectionHeading.value?.focus()
}, { flush: 'post' })

function selectTool(tool: AiToolOption) {
  if (props.creating || props.prompt) return
  clientId.value = tool.value
  toolKind.value = tool.kind
}

async function copyPrompt() {
  const value = props.prompt
  if (!value) return
  const generation = ++copyGeneration
  try {
    await navigator.clipboard.writeText(value)
    if (generation !== copyGeneration) return
    copied.value = true
    promptCopyFailed.value = false
  } catch {
    if (generation !== copyGeneration) return
    copied.value = false
    promptCopyFailed.value = true
    if (promptPreview.value) promptPreview.value.open = true
  }
}

async function copyPhrase(phrase: string) {
  try {
    await navigator.clipboard.writeText(phrase)
    copiedPhrase.value = phrase
    phraseCopyFailed.value = false
  } catch {
    copiedPhrase.value = ''
    phraseCopyFailed.value = true
  }
}

async function finish() {
  copyGeneration += 1
  emit('done')
  await nextTick()
  toolsHeading.value?.focus()
}
</script>

<style scoped lang="scss">
.ai-setup {
  box-sizing: border-box;
  max-inline-size: 70rem;
  padding: clamp(12px, 2vw, 28px);
  container-type: inline-size;
}

.ai-setup h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  line-height: 1.5;
}
.ai-setup h2:focus { outline: none; }
.ai-setup__hint { margin: 4px 0 0; color: var(--text-secondary); font-size: 14px; line-height: 1.6; }
.ai-setup__tools {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--layout-control-gap);
  margin-block-start: var(--layout-panel-padding);
}
.ai-setup__tools--compact {
  max-inline-size: 36rem;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.ai-setup__tool {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 88px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 4px;
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  text-align: center;
  cursor: pointer;
  transition: border-color var(--motion-duration-fast), background-color var(--motion-duration-fast);

  input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  strong { font-size: 14px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; text-wrap: balance; }
  &:hover:not(.is-locked) { background: var(--surface-reading-hover); }
  &.active {
    border-color: color-mix(in srgb, var(--brand-on-surface) 48%, transparent);
    background: var(--surface-reading-current);
  }
  &:focus-within { outline: 2px solid var(--border-focus); outline-offset: 3px; }
  &.is-locked { cursor: default; }
  &.is-locked:not(.active) { opacity: 0.55; }
}
.ai-setup__tool-icon, .ai-setup__brand {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: var(--radius-md);
}
.ai-setup__tool-icon {
  display: grid;
  place-items: center;
  border: 1px solid var(--border-divider);
  background: var(--surface-solid-opaque);
  color: var(--text-secondary);

  img { display: block; object-fit: contain; }
}
.ai-setup__monochrome-icon { width: 26px; height: 26px; background: currentColor; color: var(--text-primary); mask-position: center; mask-size: contain; mask-repeat: no-repeat; }
.ai-setup__selected { position: absolute; inset: 8px 8px auto auto; color: var(--brand-on-surface); font-size: 14px; }

.ai-setup__required-scope { margin-top: var(--layout-panel-padding); }
.ai-setup__connection {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  margin-block-start: var(--layout-panel-padding);
  padding: clamp(12px, 2.2cqi, 24px);
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-reading-stripe);
}
.ai-setup__connection.is-result { margin-block-start: 0; }
.ai-setup__connection h2 { font-size: 19px; }
.ai-setup__pair { display: flex; align-items: center; gap: 6px; }
.ai-setup__pair-link { color: var(--brand-on-surface); }
.ai-setup__connection-content { min-width: 0; }

.ai-setup__controls {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  align-items: start;
  gap: var(--layout-section-gap);
  margin-top: var(--layout-section-gap);
}
.ai-setup__create { justify-self: start; }
.ai-setup__create .el-icon { margin-inline-start: 6px; }
.ai-setup__scope { min-width: 0; }
.ai-setup__scope-content { display: grid; max-inline-size: 36rem; gap: var(--layout-control-gap); margin-block-start: 4px; }
.ai-setup__scope-content .ai-setup__hint { margin: 0; }

.ai-setup__disclosure {
  display: flex;
  width: fit-content;
  min-height: 30px;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
  list-style: none;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
}
.ai-setup__disclosure::-webkit-details-marker { display: none; }
.ai-setup__disclosure:hover { background: var(--surface-reading-hover); color: var(--brand-on-surface); }
.ai-setup__scope .ai-setup__disclosure { min-height: var(--control-height); }
.ai-setup__detail { color: var(--text-muted); }
.ai-setup__chevron { font-size: 11px; transition: transform var(--motion-duration-fast); }
details[open] > summary .ai-setup__chevron { transform: rotate(180deg); }

.ai-setup__actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: var(--layout-panel-padding); }
.ai-setup__actions .el-icon { margin-right: 6px; }
.ai-setup__security { display: flex; gap: 6px; margin: 12px 0 4px; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.ai-setup__security .el-icon { flex-shrink: 0; margin-top: 3px; }
.ai-setup__prompt {
  max-height: 330px;
  margin: 6px 0 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);
  background: var(--code-surface);
  color: var(--code-text);
  font: 12px / 1.65 var(--font-family-mono);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.ai-setup__error { margin: 12px 0 0; color: var(--status-danger); font-size: 12px; line-height: 1.6; }
.ai-setup__copied { margin: 10px 0 0; color: var(--status-success); font-size: 12px; }

.ai-setup__docs { display: inline-flex; align-items: center; gap: 4px; margin-top: 8px; color: var(--brand-on-surface); font-size: 12px; line-height: 2; text-decoration: none; }
.ai-setup__docs:hover { text-decoration: underline; }
.ai-setup__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 6px 16px;
  margin-block-start: var(--layout-section-gap);
}
.ai-setup__examples { min-width: 0; color: var(--text-secondary); }
.ai-setup__examples button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border: 0; border-bottom: 1px solid var(--border-divider); background: transparent; color: inherit; font: inherit; font-size: 12px; line-height: 1.7; text-align: left; cursor: pointer; }
.ai-setup__examples button:hover { color: var(--brand-on-surface); }
.ai-setup__examples button .el-icon { flex-shrink: 0; color: inherit; opacity: 0.7; }
.ai-setup :is(summary, a, pre, .ai-setup__examples button):focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }

@container (min-width: 840px) {
  .ai-setup__connection-content {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 23rem;
    align-items: start;
    gap: 6px 24px;
  }
  .ai-setup__controls, .ai-setup__actions { margin-block-start: 0; }
  .ai-setup__message { grid-column: 1 / -1; }
}
@container (max-width: 740px) {
  .ai-setup__tools { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .ai-setup__tools--compact { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@container (max-width: 500px) {
  .ai-setup__tools { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px; }
  .ai-setup__tool { min-height: 66px; gap: 4px; padding-block: 6px; }
  .ai-setup__tool strong { font-size: 12px; }
  .ai-setup__tool-icon, .ai-setup__brand { width: 32px; height: 32px; }
  .ai-setup__tool-icon :is(img, .ai-setup__monochrome-icon) { width: 20px; height: 20px; }
  .ai-setup__selected { inset: 5px 5px auto auto; font-size: 12px; }
  .ai-setup__connection { grid-template-columns: minmax(0, 1fr); gap: 8px; }
  .ai-setup__controls { grid-template-columns: minmax(0, 1fr); gap: 4px; }
  .ai-setup__create { width: 100%; }
}
@container (220px < width <= 500px) {
  .ai-setup__tools:not(.ai-setup__tools--compact) .ai-setup__tool[data-tool-kind='OTHER'] {
    grid-column: 1 / -1;
    min-height: 44px;
    flex-direction: row;
    justify-content: flex-start;
    gap: 8px;
    padding-inline: 8px;
  }
}
@container (max-width: 220px) {
  .ai-setup__tools { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (prefers-reduced-motion: reduce) { .ai-setup__tool, .ai-setup__chevron { transition: none; } }
@media (forced-colors: active) { .ai-setup__tool.active { border-color: Highlight; } .ai-setup__selected { color: Highlight; } }
</style>
