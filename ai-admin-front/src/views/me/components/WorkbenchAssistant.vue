<template>
  <div class="workbench-assistant" :class="{ 'has-conversation': started }">
    <div v-if="!started" class="workbench-assistant__welcome">
      <div class="workbench-assistant__heading">
        <span class="workbench-assistant__symbol" aria-hidden="true"><ChatDotRound /></span>
        <h2>今天要办什么？</h2>
        <p>在{{ systemName }}里查数据、办业务，直接说就好。</p>
      </div>
      <p v-if="error" class="workbench-assistant__error" role="alert">{{ error }}</p>
      <div class="eaf-chat workbench-assistant__composer" :data-theme="theme">
        <ConversationComposer ref="composer" :disabled="sending || expired" :placeholder="`说说要办的事，让 AI 帮你处理${systemName}的业务`" @send="send" />
      </div>
      <div class="workbench-assistant__suggestions" aria-label="试试这样问">
        <button type="button" :disabled="sending || expired" @click="fill('我在这个系统里可以请你帮忙办哪些事？')">我能办什么 <ArrowRight aria-hidden="true" /></button>
        <button v-for="suggestion in suggestions" :key="suggestion.label" type="button" :disabled="sending || expired" :title="suggestion.label" @click="fill(suggestion.prompt)">{{ suggestion.label }} <ArrowRight aria-hidden="true" /></button>
      </div>
    </div>
    <EmployeeAssistant v-if="started" ref="assistant" :get-token="getToken" class="workbench-assistant__chat" @busy="hostBusy = $event" @completed="$emit('completed')" @expired="$emit('expired')" />
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ArrowRight, ChatDotRound } from '@element-plus/icons-vue'
import ConversationComposer from '@/conversation/components/ConversationComposer.vue'
import EmployeeAssistant from '@/views/portal/EmployeeAssistant.vue'
import { useTheme } from '@/composables/useTheme'
import '@/sdk/style.css'

const props = defineProps<{
  systemName: string
  getToken: () => Promise<string>
  suggestions: { label: string; prompt: string }[]
  expired?: boolean
}>()
const emit = defineEmits<{ busy: [value: boolean]; completed: []; expired: [] }>()
const started = ref(false)
const sending = ref(false)
const hostBusy = ref(false)
watch([sending, hostBusy], () => emit('busy', sending.value || hostBusy.value))
const error = ref('')
const { theme } = useTheme()
const composer = ref<InstanceType<typeof ConversationComposer> | null>(null)
const assistant = ref<InstanceType<typeof EmployeeAssistant> | null>(null)

function fill(text: string) {
  composer.value?.setValue(text)
  composer.value?.focus()
}

async function send(text: string) {
  if (sending.value || props.expired) return
  sending.value = true
  error.value = ''
  started.value = true
  await nextTick()
  try {
    await assistant.value!.sendMessage(text)
  } catch (failure) {
    const status = failure && typeof failure === 'object' && 'httpStatus' in failure ? failure.httpStatus : undefined
    if (status === 401) emit('expired')
    // Before a session exists the shared host has no message to retain: restore the exact draft.
    if (status === 401 || status === 409 || (failure instanceof Error && failure.name === 'EafChatTokenError')) {
      started.value = false
      error.value = failure instanceof Error ? failure.message : '暂时无法连接 AI 助手，请重试。'
      await nextTick()
      fill(text)
    }
  } finally {
    sending.value = false
  }
}
</script>

<style scoped lang="scss">
.workbench-assistant { min-width: 0; }
.workbench-assistant__welcome { padding: 38px 24px 24px; }
.workbench-assistant__heading { text-align: center; margin-bottom: 28px; }
.workbench-assistant__symbol { display: inline-flex; width: 40px; height: 40px; color: var(--brand-on-surface); margin-bottom: 12px; }
.workbench-assistant h2 { margin: 0 0 10px; font-size: clamp(24px, 2.6vw, 34px); font-weight: 650; letter-spacing: -0.025em; color: var(--text-primary); }
.workbench-assistant p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--text-secondary); }
.workbench-assistant__composer { border: 1px solid var(--border-readable); border-radius: var(--radius-xl); overflow: hidden; }
.workbench-assistant__composer :deep(.reachai-composer) { border-top: 0; }
.workbench-assistant__composer :deep(textarea) { min-height: 90px; font-size: 15px; }
.workbench-assistant__suggestions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.workbench-assistant__suggestions button { display: inline-flex; align-items: center; gap: 7px; max-width: 240px; padding: 7px 11px; border: 1px solid var(--border-divider); border-radius: var(--radius-pill); color: var(--text-secondary); background: var(--surface-solid-control); font: inherit; font-size: 12px; cursor: pointer; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.workbench-assistant__suggestions svg { width: 13px; height: 13px; flex: 0 0 auto; }
.workbench-assistant__suggestions button:hover { color: var(--brand-on-surface); border-color: var(--border-focus); }
.workbench-assistant__suggestions button:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
.workbench-assistant__suggestions button:active { background: var(--surface-pressed); }
.workbench-assistant__suggestions button:disabled { opacity: 0.5; cursor: not-allowed; }
.workbench-assistant .workbench-assistant__error { color: var(--status-danger); margin: 0 0 12px; }
.workbench-assistant.has-conversation { height: clamp(460px, 65vh, 760px); }
.workbench-assistant__chat { height: 100%; min-height: 0; border-radius: 0; }
@media (max-width: 640px) {
  .workbench-assistant__welcome { padding: 28px 12px 18px; }
  .workbench-assistant__suggestions { justify-content: flex-start; }
}
</style>
