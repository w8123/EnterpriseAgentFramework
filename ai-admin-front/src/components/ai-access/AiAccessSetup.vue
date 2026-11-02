<template>
  <div class="ai-setup">
    <LiquidGlassCard class="ai-setup__workspace" source="wallpaper">
      <section class="ai-setup__section" :aria-labelledby="toolsTitleId">
        <div class="ai-setup__section-heading">
          <span class="ai-setup__step-number" aria-hidden="true">1</span>
          <div>
            <h2 :id="toolsTitleId">选择 AI 工具</h2>
            <p class="ai-setup__hint">用你习惯的工具，连接 ReachAI 的能力。</p>
          </div>
        </div>
        <div class="ai-setup__tools" role="radiogroup" aria-label="AI 工具">
          <label
            v-for="tool in tools"
            :key="tool.value"
            class="ai-setup__tool"
            :class="{ active: selectedTool.value === tool.value, 'is-locked': locked }"
            :data-tool-kind="tool.value"
          >
            <input
              type="radio"
              :name="radioName"
              :value="tool.value"
              :checked="selectedTool.value === tool.value"
              :disabled="locked"
              @change="selectTool(tool)"
            >
            <span class="ai-setup__tool-icon" aria-hidden="true">
              <span v-if="tool.monochrome" class="ai-setup__monochrome-icon" :style="{ maskImage: `url(${JSON.stringify(tool.icon)})` }" />
              <img v-else-if="tool.icon" :src="tool.icon" alt="" width="32" height="32">
              <el-icon v-else :size="28"><Connection /></el-icon>
            </span>
            <strong>{{ tool.label }}</strong>
            <small>{{ tool.hint }}</small>
            <el-icon v-if="selectedTool.value === tool.value" class="ai-setup__selected" aria-hidden="true"><Check /></el-icon>
          </label>
        </div>
      </section>

      <section class="ai-setup__section ai-setup__scope">
        <div class="ai-setup__section-heading">
          <span class="ai-setup__step-number" aria-hidden="true">2</span>
          <div>
            <h2>{{ scopeTitle }}</h2>
            <p v-if="scopeHint" class="ai-setup__hint">{{ scopeHint }}</p>
          </div>
        </div>
        <slot name="scope" />
      </section>
    </LiquidGlassCard>

    <div class="ai-setup__aside">
      <LiquidGlassCard class="ai-setup__guide" source="wallpaper">
        <header class="ai-setup__guide-heading">
          <span class="ai-setup__guide-icon" aria-hidden="true">
            <span v-if="selectedTool.monochrome" class="ai-setup__monochrome-icon" :style="{ maskImage: `url(${JSON.stringify(selectedTool.icon)})` }" />
            <img v-else-if="selectedTool.icon" :src="selectedTool.icon" alt="" width="32" height="32">
            <el-icon v-else :size="28"><Connection /></el-icon>
          </span>
          <div>
            <span class="ai-setup__eyebrow">{{ prompt ? '凭据已创建' : '接入指引' }}</span>
            <h2>连接 {{ selectedTool.label }}</h2>
          </div>
          <span class="ai-setup__method">{{ selectedTool.manual ? '手动配置' : '安装提示词' }}</span>
        </header>

        <template v-if="!prompt">
          <p class="ai-setup__guide-description">{{ selectedTool.guide }}</p>
          <ol class="ai-setup__checklist">
            <li :class="{ 'is-ready': canCreate }">
              <span><el-icon v-if="canCreate" aria-hidden="true"><Check /></el-icon><template v-else>1</template></span>
              <div><strong>确认连接信息</strong><small>选择工具，设置凭据与有效期</small></div>
            </li>
            <li>
              <span>2</span>
              <div><strong>{{ selectedTool.manual ? '生成并复制配置' : '获取安装提示词' }}</strong><small>创建一条仅属于你的访问凭据</small></div>
            </li>
            <li>
              <span>3</span>
              <div><strong>在 {{ selectedTool.label }} 中接入</strong><small>加载工具列表，发起第一次查询</small></div>
            </li>
          </ol>
          <a v-if="selectedTool.docsUrl" class="ai-setup__docs" :href="selectedTool.docsUrl" target="_blank" rel="noopener noreferrer">
            查看 {{ selectedTool.label }} 接入文档 <el-icon aria-hidden="true"><TopRight /></el-icon>
          </a>
          <LiquidGlassButton
            class="ai-setup__create"
            type="primary"
            :loading="creating"
            :disabled="!canCreate || creating"
            data-testid="get-install-prompt"
            @click="emit('create')"
          >{{ selectedTool.manual ? '生成接入配置' : '获取安装提示词' }}<el-icon v-if="!creating" aria-hidden="true"><ArrowRight /></el-icon></LiquidGlassButton>
          <p v-if="!canCreate && disabledReason" class="ai-setup__disabled-reason">{{ disabledReason }}</p>
        </template>

        <template v-else>
          <p class="ai-setup__guide-description">
            {{ selectedTool.manual ? '按下面的说明在客户端填写配置，连接后加载工具列表。' : `复制下面的提示词，粘贴给 ${selectedTool.label} 完成配置。` }}
          </p>
          <div class="ai-setup__prompt-header"><el-icon aria-hidden="true"><Document /></el-icon>{{ selectedTool.manual ? '接入配置' : '安装提示词' }}<span>仅本次显示</span></div>
          <pre class="ai-setup__prompt" data-testid="install-prompt" tabindex="0" :aria-label="selectedTool.manual ? '接入配置' : '安装提示词'">{{ prompt }}</pre>
          <div class="ai-setup__actions">
            <LiquidGlassButton type="primary" @click="copyPrompt"><el-icon aria-hidden="true"><Select v-if="copied" /><CopyDocument v-else /></el-icon>{{ copied ? '已复制' : selectedTool.manual ? '复制配置' : '复制提示词' }}</LiquidGlassButton>
            <LiquidGlassButton @click="finish">完成</LiquidGlassButton>
          </div>
          <p v-if="copied" class="ai-setup__copied" role="status">已复制，请到 {{ selectedTool.label }} 中继续。</p>
        </template>
        <p v-if="error || copyError" class="ai-setup__error" role="alert">{{ error || copyError }}</p>
        <p class="ai-setup__security"><el-icon aria-hidden="true"><Lock /></el-icon><span>配置包含个人密钥，仅显示一次。请保存在自己的工具中，勿分享给他人。</span></p>
      <section v-if="tryPhrases.length" class="ai-setup__examples" aria-label="接入后可以试试">
        <h3><el-icon aria-hidden="true"><ChatLineRound /></el-icon>接入后，试着说</h3>
        <button v-for="phrase in tryPhrases" :key="phrase" type="button" :aria-label="`复制示例：${phrase}`" @click="copyPhrase(phrase)">
          <span>{{ phrase }}</span><el-icon aria-hidden="true"><Select v-if="copiedPhrase === phrase" /><CopyDocument v-else /></el-icon>
        </button>
        <p v-if="copiedPhrase" class="ai-setup__copied" role="status">示例已复制</p>
      </section>
      </LiquidGlassCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { ArrowRight, ChatLineRound, Check, Connection, CopyDocument, Document, Lock, Select, TopRight } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassCard } from '@/components/common/liquid-glass'
import { AI_TOOL_OPTIONS, type AiClientId, type AiToolKind, type AiToolOption } from './aiAccess'

const props = withDefaults(defineProps<{
  scopeTitle: string
  scopeHint?: string
  tools?: AiToolOption[]
  /** Kept only in memory, immediately after creating a credential. */
  prompt?: string | null
  creating?: boolean
  canCreate?: boolean
  disabledReason?: string
  error?: string
  tryPhrases?: string[]
}>(), { scopeHint: '', tools: () => AI_TOOL_OPTIONS, prompt: null, creating: false, canCreate: true, disabledReason: '', error: '', tryPhrases: () => [] })

const emit = defineEmits<{ (event: 'create'): void; (event: 'done'): void }>()
const toolKind = defineModel<AiToolKind>('toolKind', { required: true })
const clientId = defineModel<AiClientId>('clientId')
const selectedTool = computed(() => props.tools.find(tool => tool.value === clientId.value)
  ?? props.tools.find(tool => tool.value === toolKind.value) ?? props.tools[0]!)
const radioName = `ai-setup-tool-${useId()}`
const toolsTitleId = useId()
const locked = computed(() => !!props.prompt || props.creating)
const copied = ref(false)
const copyError = ref('')
const copiedPhrase = ref('')
let copyGeneration = 0

watch(() => props.prompt, () => { copyGeneration += 1; copied.value = false; copyError.value = '' })

function selectTool(tool: AiToolOption) {
  if (locked.value) return
  clientId.value = tool.value
  toolKind.value = tool.kind
  copyError.value = ''
}

async function copyPrompt() {
  const value = props.prompt
  if (!value) return
  const generation = ++copyGeneration
  try {
    await navigator.clipboard.writeText(value)
    if (generation !== copyGeneration) return
    copied.value = true
    copyError.value = ''
  } catch {
    if (generation !== copyGeneration) return
    copied.value = false
    copyError.value = '复制失败，请选中文本手动复制。'
  }
}

async function copyPhrase(phrase: string) {
  try {
    await navigator.clipboard.writeText(phrase)
    copiedPhrase.value = phrase
    copyError.value = ''
  } catch {
    copiedPhrase.value = ''
    copyError.value = '复制失败，请选中文本手动复制。'
  }
}

function finish() {
  copyGeneration += 1
  copied.value = false
  copyError.value = ''
  emit('done')
}
</script>

<style scoped lang="scss">
.ai-setup {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(320px, 1fr);
  align-items: start;
  gap: 20px;
  color: var(--text-primary);
}

.ai-setup__workspace, .ai-setup__guide { padding: 24px; }
.ai-setup__section { display: flex; min-width: 0; flex-direction: column; gap: 22px; }
.ai-setup__section-heading { display: flex; align-items: flex-start; gap: 12px; }
.ai-setup__section-heading h2, .ai-setup__guide-heading h2 { margin: 0; font-size: 16px; font-weight: 650; line-height: 1.5; }
.ai-setup__step-number {
  display: grid; flex: 0 0 28px; height: 28px; place-items: center;
  border: 1px solid var(--border-divider); border-radius: var(--radius-pill);
  color: var(--brand-on-surface); font-family: var(--font-family-mono); font-size: 12px;
}
.ai-setup__hint { margin: 5px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
.ai-setup__tools { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.ai-setup__tool {
  position: relative; display: flex; min-width: 0; flex-direction: column; align-items: center; justify-content: center;
  gap: 3px; padding: 11px 4px 9px; border: 1px solid var(--border-readable); border-radius: var(--radius-md);
  background: var(--surface-reading-stripe); cursor: pointer;
  transition: border-color var(--motion-duration-fast), background-color var(--motion-duration-fast);
  input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  strong { color: var(--text-primary); font-size: 12px; font-weight: 600; line-height: 1.5; text-align: center; overflow-wrap: anywhere; }
  small { color: var(--text-muted); font-size: 11px; }
  &:hover:not(.is-locked) { border-color: var(--brand-on-surface); background: var(--surface-reading-hover); }
  &.active { border-color: var(--brand-on-surface); background: var(--surface-reading-current); }
  &:focus-within { outline: 2px solid var(--border-focus); outline-offset: 3px; }
  &.is-locked { cursor: default; }
  &.is-locked:not(.active) { opacity: 0.55; }
}
.ai-setup__tool-icon, .ai-setup__guide-icon {
  display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; border-radius: var(--radius-md);
  background: var(--surface-solid-opaque); color: var(--text-secondary);
  img { display: block; width: 24px; height: 24px; object-fit: contain; }
}
.ai-setup__tool-icon { margin-bottom: 5px; }
.ai-setup__monochrome-icon { width: 24px; height: 24px; background: currentColor; color: var(--text-primary); mask-position: center; mask-size: contain; mask-repeat: no-repeat; }
.ai-setup__selected { position: absolute; top: 7px; right: 7px; color: var(--brand-on-surface); font-size: 12px; }
.ai-setup__scope { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--border-divider); }
.ai-setup__aside { display: flex; min-width: 0; flex-direction: column; gap: 20px; }
.ai-setup__guide-heading { display: flex; align-items: center; gap: 12px; }
.ai-setup__guide-heading > div { min-width: 0; flex: 1; }
.ai-setup__eyebrow { display: block; margin-bottom: 2px; color: var(--text-muted); font-size: 11px; }
.ai-setup__method { flex: 0 0 auto; padding: 3px 7px; border: 1px solid var(--border-divider); border-radius: var(--radius-sm); color: var(--text-secondary); font-size: 11px; }
.ai-setup__guide-description { margin: 20px 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; }
.ai-setup__checklist {
  display: flex; flex-direction: column; gap: 22px; margin: 24px 0; padding: 0; list-style: none;
  li { position: relative; display: flex; align-items: center; gap: 12px; }
  li:not(:last-child)::after { position: absolute; top: 31px; bottom: -16px; left: 13px; border-left: 1px solid var(--border-divider); content: ''; }
  li > span { display: grid; width: 28px; height: 28px; flex: 0 0 28px; place-items: center; border: 1px solid var(--border-readable); border-radius: var(--radius-pill); color: var(--text-muted); font-family: var(--font-family-mono); font-size: 11px; }
  strong { display: block; color: var(--text-primary); font-size: 13px; font-weight: 600; }
  small { display: block; margin-top: 4px; color: var(--text-muted); font-size: 12px; line-height: 1.5; }
  .is-ready > span { border-color: var(--status-success); background: var(--status-success-soft); color: var(--status-success); }
}
.ai-setup__docs { display: inline-flex; align-items: center; gap: 4px; margin-bottom: 18px; color: var(--brand-on-surface); font-size: 12px; text-decoration: none; }
.ai-setup__docs:hover { text-decoration: underline; }
.ai-setup__create { width: 100%; gap: 8px; }
.ai-setup__disabled-reason { margin: 10px 0 0; color: var(--text-muted); font-size: 12px; text-align: center; }
.ai-setup__security { display: flex; align-items: flex-start; gap: 7px; margin: 18px 0 0; padding-top: 16px; border-top: 1px solid var(--border-divider); color: var(--text-muted); font-size: 11px; line-height: 1.8; }
.ai-setup__security .el-icon { flex: 0 0 auto; margin-top: 4px; }
.ai-setup__prompt-header { display: flex; align-items: center; gap: 6px; padding: 9px 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-sm) var(--radius-sm) 0 0; color: var(--text-secondary); background: var(--surface-reading-header); font-size: 11px; }
.ai-setup__prompt-header span { margin-left: auto; color: var(--status-warning); }
.ai-setup__prompt { max-height: 330px; margin: 0; padding: 14px; overflow: auto; border: 1px solid var(--border-divider); border-top: 0; border-radius: 0 0 var(--radius-sm) var(--radius-sm); background: var(--code-surface); color: var(--code-text); font-family: var(--font-family-mono); font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-setup__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.ai-setup__actions .el-icon { margin-right: 6px; }
.ai-setup__error { margin: 12px 0 0; color: var(--status-danger); font-size: 12px; line-height: 1.6; }
.ai-setup__copied { margin: 10px 0 0; color: var(--status-success); font-size: 12px; }
.ai-setup__examples { padding: 20px 0 0; color: var(--text-secondary); }
.ai-setup__examples h3 { display: flex; align-items: center; gap: 7px; margin: 0 0 8px; color: inherit; font-size: 12px; font-weight: 600; }
.ai-setup__examples button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border: 0; border-bottom: 1px solid var(--border-divider); background: transparent; color: inherit; font: inherit; font-size: 12px; line-height: 1.7; text-align: left; cursor: pointer; }
.ai-setup__examples button:hover { color: var(--brand-on-surface); }
.ai-setup__examples button .el-icon { flex-shrink: 0; color: inherit; opacity: 0.7; }
.ai-setup__examples button:focus-visible, .ai-setup__docs:focus-visible, .ai-setup__prompt:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
@media (max-width: 1150px) { .ai-setup { grid-template-columns: minmax(0, 1fr) minmax(300px, 0.85fr); gap: 16px; } .ai-setup__workspace, .ai-setup__guide { padding: 20px; } }
@media (max-width: 960px) { .ai-setup { grid-template-columns: minmax(0, 1fr); } .ai-setup__aside { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 0.7fr); align-items: start; gap: 20px; } }
@media (max-width: 640px) { .ai-setup__workspace, .ai-setup__guide { padding: 16px; } .ai-setup__tools { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ai-setup__aside { display: flex; } .ai-setup__method { margin-left: auto; } .ai-setup__scope { margin-top: 22px; padding-top: 20px; } }
@media (prefers-reduced-motion: reduce) { .ai-setup__tool { transition: none; } }
@media (forced-colors: active) { .ai-setup__tool.active { border: 2px solid Highlight; } .ai-setup__selected { color: Highlight; } }
</style>
