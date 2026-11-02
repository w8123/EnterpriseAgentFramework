<template>
  <section class="identity-binding" aria-labelledby="identity-binding-title">
    <h3 id="identity-binding-title">关联其他业务系统的账号</h3>
    <p>在一个系统生成一次性关联码，再进入另一个系统的个人页输入。关联后，两边的个人记忆可以互相查看、编辑和删除；项目记忆不共享。</p>
    <div class="identity-binding__actions">
      <LiquidGlassButton source="backdrop" :loading="busy" @click="issue">生成关联码</LiquidGlassButton>
      <LiquidGlassButton source="backdrop" :disabled="!code" @click="clearCode">隐藏关联码</LiquidGlassButton>
    </div>
    <div v-if="code" class="identity-binding__code">
      <p>请仅在本人另一个业务系统的个人页使用，关联码仅能使用一次，有效期至 {{ expiresLabel }}。</p>
      <textarea readonly aria-label="一次性关联码" :value="code" @focus="($event.target as HTMLTextAreaElement).select()" />
    </div>
    <form class="identity-binding__confirm" @submit.prevent="confirm">
      <label for="identity-link-code">另一个系统生成的关联码</label>
      <LiquidGlassInput id="identity-link-code" v-model="input" source="backdrop" autocomplete="off" placeholder="粘贴一次性关联码" />
      <LiquidGlassButton source="backdrop" native-type="submit" type="primary" :loading="busy" :disabled="!input.trim()">确认关联</LiquidGlassButton>
    </form>
    <p v-if="message" class="identity-binding__message" role="status">{{ message }}</p>
    <p v-if="error" class="identity-binding__error" role="alert">{{ error }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { LiquidGlassButton, LiquidGlassInput } from '@/components/common/liquid-glass'

const props = defineProps<{ api: { issue: () => Promise<{ code: string; expiresAt: string }>; confirm: (code: string) => Promise<number> } }>()
const emit = defineEmits<{ linked: [] }>()
const code = ref('')
const input = ref('')
const expires = ref('')
const busy = ref(false)
const message = ref('')
const error = ref('')
const expiresLabel = computed(() => new Date(expires.value).toLocaleString())
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
function clearCode() { code.value = ''; expires.value = ''; if (timer) clearTimeout(timer); timer = undefined }
function failure(value: unknown) { return value instanceof Error && value.message ? value.message : '操作失败，请稍后重试' }
async function issue() {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''; clearCode()
  try {
    const result = await props.api.issue()
    if (disposed) return
    const delay = Date.parse(result.expiresAt) - Date.now()
    if (!result.code || !Number.isFinite(delay) || delay <= 0) throw new Error('关联码已过期，请重新生成')
    code.value = result.code; expires.value = result.expiresAt
    timer = setTimeout(() => { clearCode(); message.value = '关联码已过期，请重新生成' }, delay)
  } catch (value) { if (!disposed) error.value = failure(value) }
  finally { if (!disposed) busy.value = false }
}
async function confirm() {
  if (busy.value || !input.value.trim()) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await props.api.confirm(input.value.trim())
    if (disposed) return
    input.value = ''; clearCode(); message.value = '关联成功，可以刷新查看关联账号的个人记忆。'; emit('linked')
  } catch (value) { if (!disposed) error.value = failure(value) }
  finally { if (!disposed) busy.value = false }
}
onBeforeUnmount(() => { disposed = true; clearCode(); input.value = '' })
</script>

<style scoped>
.identity-binding { padding: 16px 0; border-bottom: 1px solid var(--border-glass-edge); }
.identity-binding h3 { margin: 0; font-size: 15px; color: var(--text-primary); }
.identity-binding p, .identity-binding label { color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.identity-binding__actions, .identity-binding__confirm { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.identity-binding__confirm { margin-top: 16px; }
.identity-binding__confirm label { flex-basis: 100%; }
.identity-binding__confirm :deep(.el-input) { flex: 1; min-width: 200px; }
.identity-binding__code textarea { box-sizing: border-box; width: 100%; min-height: 60px; resize: vertical; color: var(--text-primary); background: transparent; border: 1px solid var(--border-glass-edge); border-radius: 8px; padding: 8px; }
.identity-binding__error { color: var(--el-color-danger) !important; }
</style>
