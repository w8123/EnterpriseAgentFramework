<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { approvePageActionEffect, previewPageActionEffect, revokePageActionEffect } from '@/api/pageActionEffectReview'
import type { PageActionEffect, PageActionReviewPreview } from '@/api/pageActionEffectReview'

const props = defineProps<{ modelValue: boolean; projectCode: string; pageKey: string; actionKey: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const preview = ref<PageActionReviewPreview | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const effect = ref<PageActionEffect | ''>('')
const targetAlias = ref('')
const implementationVersion = ref('')
const fieldLabels = ref<Record<string, string>>(Object.create(null))
const accepted = ref(false)
const revokeConfirmed = ref(false)
let generation = 0

const schemaFields = computed(() => {
  const schema = preview.value?.inputSchema
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return null
  if ((schema as Record<string, unknown>).type !== 'object') return null
  const properties = (schema as Record<string, unknown>).properties
  if (properties == null) return []
  if (typeof properties !== 'object' || Array.isArray(properties)) return null
  if (!Object.values(properties).every((property) => property && typeof property === 'object'
    && !Array.isArray(property) && ['string', 'number', 'integer', 'boolean'].includes(String((property as Record<string, unknown>).type)))) return null
  return Object.keys(properties)
})
const labelsValid = computed(() => {
  if (!schemaFields.value) return false
  const labels = schemaFields.value.map((key) => (fieldLabels.value[key] || '').trim())
  const comparable = labels.map((label) => label.normalize('NFKC').toLowerCase())
  return labels.every((label) => label.length >= 2 && label.length <= 40)
    && comparable.every((label, index) => comparable.every((other, otherIndex) => index === otherIndex || !label.includes(other)))
})
const validVersion = computed(() => {
  const version = implementationVersion.value.trim()
  return version.length > 0 && version.length <= 160 && !/[\u0000-\u001f\u007f-\u009f]/.test(version)
})
const canApprove = computed(() => !!preview.value && validVersion.value
  && labelsValid.value
  && (effect.value !== 'PAGE_LOCAL_DRAFT' || !!schemaFields.value?.length)
  && !!effect.value && targetAlias.value.trim().length >= 2 && targetAlias.value.trim().length <= 80
  && accepted.value && !saving.value && !loading.value)
const schemaText = computed(() => JSON.stringify(preview.value?.inputSchema ?? {}, null, 2))

function errorText(e: unknown, write = false) {
  const status = (e as { response?: { status?: number } })?.response?.status
  if (status === 401) return '登录会话已失效，请重新登录后查看。'
  if (status === 403) return '没有此项目的页面动作审核权限，请联系项目管理员（需要工作流发布及嵌入管理权限）。'
  if (status === 409) return '页面动作或审核状态已变化，请重新加载并审核当前版本。'
  return write ? '请求结果未能确认。请重新加载状态后再决定是否操作，系统不会自动重试。' : '无法加载审核信息，请检查网络后重试。'
}
function resetForm() {
  effect.value = ''
  targetAlias.value = ''
  implementationVersion.value = ''
  fieldLabels.value = Object.create(null)
  accepted.value = false
  revokeConfirmed.value = false
}
async function load() {
  const request = ++generation
  preview.value = null
  error.value = ''
  notice.value = ''
  resetForm()
  if (!props.modelValue || !props.projectCode || !props.pageKey || !props.actionKey) return
  loading.value = true
  try {
    const response = await previewPageActionEffect(props.projectCode, props.pageKey, props.actionKey)
    if (request === generation) preview.value = response.data
  } catch (e) {
    if (request === generation) error.value = errorText(e)
  } finally {
    if (request === generation) loading.value = false
  }
}
watch(() => [props.modelValue, props.projectCode, props.pageKey, props.actionKey], () => {
  loading.value = false
  void load()
}, { immediate: true })

async function approve() {
  const p = preview.value
  if (!p || !canApprove.value || !effect.value || !schemaFields.value) return
  const request = generation
  saving.value = true
  try {
    await approvePageActionEffect(props.projectCode, props.pageKey, props.actionKey, {
      effect: effect.value, expectedCatalogHash: p.catalogHash,
      expectedRevision: p.currentRevision,
      implementationVersion: implementationVersion.value.trim(),
      fieldLabels: Object.fromEntries(schemaFields.value.map((key) => [key, fieldLabels.value[key]!.trim()])),
      targetAlias: targetAlias.value.trim(), responsibilityAccepted: true,
    })
    if (request === generation) {
      await load()
      if (preview.value) notice.value = '审核已保存，请核对当前审核状态。'
    }
  } catch (e) {
    if (request === generation) { preview.value = null; error.value = errorText(e, true) }
  } finally { saving.value = false }
}
async function revoke() {
  if (!preview.value?.approval || !revokeConfirmed.value || saving.value) return
  const request = generation
  saving.value = true
  try {
    await revokePageActionEffect(props.projectCode, props.pageKey, props.actionKey, preview.value.approval)
    if (request === generation) {
      await load()
      if (preview.value) notice.value = '撤销请求已完成，请核对当前审核状态。'
    }
  } catch (e) {
    if (request === generation) { preview.value = null; error.value = errorText(e, true) }
  } finally { saving.value = false }
}
</script>

<template>
  <AppDialog :model-value="modelValue" title="审核页面操作效果" width="min(760px, calc(100vw - 32px))"
    append-to-body :close-on-click-modal="!saving" @update:model-value="emit('update:modelValue', $event)">
    <div v-loading="loading" class="effect-review">
      <p class="effect-review__intro">确认这个版本的业务效果，帮助平台决定执行时需要的保护。</p>
      <el-alert type="warning" :closable="false" show-icon title="这是一项受信业务方的声明，不是对浏览器代码的证明。请先检查实现及自动保存行为；不确定时不要审核。" />
      <el-alert v-if="error" class="effect-review__message" type="error" :closable="false" :title="error" show-icon />
      <el-alert v-if="notice" class="effect-review__message" type="success" :closable="false" :title="notice" show-icon />
      <template v-if="preview">
        <section class="effect-review__section" aria-label="当前操作契约">
          <div class="effect-review__heading"><h3>{{ preview.actionTitle || preview.actionKey }}</h3><el-tag :type="preview.approval ? 'success' : 'warning'">{{ preview.approval ? '已审核' : '未审核或已失效 · UNKNOWN' }}</el-tag></div>
          <p>{{ preview.actionDescription || '未提供操作说明' }}</p>
          <dl>
            <div><dt>项目 / 页面 / 操作</dt><dd>{{ preview.projectCode }} / {{ preview.pageKey }} / {{ preview.actionKey }}</dd></div>
            <div><dt>目标路由</dt><dd>{{ preview.routePattern || '未声明' }}</dd></div>
            <div><dt>目录观测版本</dt><dd>{{ preview.implementationVersion || '未提供' }}</dd></div>
            <div><dt>目录风险及确认</dt><dd>{{ preview.riskLevel }} · {{ preview.confirmRequired ? '声明需要用户确认' : '未声明确认' }}；实际确认要求以运行时为准</dd></div>
          </dl>
          <details><summary>查看输入契约</summary><pre>{{ schemaText }}</pre></details>
          <p v-if="preview.approval">当前声明：{{ preview.approval.effect === 'PAGE_LOCAL_DRAFT' ? '仅填写页面草稿' : '可能持久化业务数据' }}；业务目标：{{ preview.approval.targetAlias }}；声明版本：{{ preview.approval.implementationVersion }}；审核修订 {{ preview.approval.revision }}。</p>
          <dl v-if="preview.approval?.fieldLabels"><div v-for="(label, key) in preview.approval.fieldLabels" :key="key"><dt>{{ key }}</dt><dd>{{ label }}</dd></div></dl>
          <p>审核不等于已接入对话。还需发布并绑定符合要求的 Workflow；输入契约或流程不兼容时，平台仍会拒绝执行。</p>
          <p>动作版本或范围变化后，原审核失效，需要重新查看并审核。</p>
        </section>
        <section class="effect-review__section" aria-label="审核声明">
          <h3>{{ preview.approval ? '重新审核当前版本' : '声明操作效果' }}</h3>
          <label for="effect-version">实施版本（由审核人明确填写，1–160 字）</label>
          <el-input id="effect-version" v-model="implementationVersion" maxlength="160" show-word-limit placeholder="例如：profile-form-v1" :disabled="saving" />
          <el-text v-if="implementationVersion && !validVersion" type="danger" role="alert">版本不能为空，且不能包含控制字符。</el-text>
          <p>填写已核对实现的版本标识。目录观测值与此声明均不能证明浏览器实际代码。</p>
          <div v-if="schemaFields === null" role="alert">输入契约不受支持：仅支持对象中的顶层文本、数字或布尔字段。请先修正页面动作契约。</div>
          <template v-else-if="schemaFields.length">
            <strong>逐项确认输入字段名称</strong>
            <p>每项填写用户对话中会使用的字段名称，例如“昵称”“地址”。名称需为 2–40 字，不能重复或互相包含；当前匹配要求用户原话包含该名称。</p>
            <div v-for="(key, index) in schemaFields" :key="key" class="effect-review__field">
              <label :for="`effect-field-${index}`">{{ key }}</label>
              <el-input :id="`effect-field-${index}`" v-model="fieldLabels[key]" maxlength="40" show-word-limit placeholder="填写业务名称" :disabled="saving" />
            </div>
            <el-text v-if="Object.values(fieldLabels).some(Boolean) && !labelsValid" type="danger" role="alert">请完整填写字段名称，名称需为 2–40 字且不能重复或互相包含。</el-text>
          </template>
          <p v-else>当前输入契约没有属性字段，不能审核为页面草稿；如可能写入业务数据，可以声明持久化效果。</p>
          <el-radio-group v-model="effect" :disabled="saving">
            <el-radio value="PAGE_LOCAL_DRAFT" :disabled="!schemaFields?.length">仅填写页面草稿</el-radio>
            <el-radio value="PERSISTENT">可能持久化业务数据</el-radio>
          </el-radio-group>
          <p>页面填写若会自动保存、提交或触发其他持久化操作，应选择“可能持久化业务数据”。</p>
          <label for="effect-target">业务目标（2–80 字）</label>
          <el-input id="effect-target" v-model="targetAlias" maxlength="80" show-word-limit placeholder="例如：个人信息" :disabled="saving" />
          <p>填写用户对话中会直接说出的目标短语，例如“个人信息”。当前匹配要求原话包含该短语，不会自动识别同义词。</p>
          <el-checkbox v-model="accepted" :disabled="saving">我已核对动作、目标和实现版本，并对这项业务声明负责。</el-checkbox>
          <div><el-button type="primary" :disabled="!canApprove" :loading="saving" @click="approve">确认审核</el-button></div>
        </section>
        <section v-if="preview.approval" class="effect-review__section" aria-label="撤销审核">
          <h3>撤销审核</h3><p>撤销后该操作的效果视为未知；如需继续使用声明，须重新审核。</p>
          <el-checkbox v-model="revokeConfirmed" :disabled="saving">确认撤销此操作当前的审核</el-checkbox>
          <div><el-button type="danger" plain :disabled="!revokeConfirmed || saving" :loading="saving" @click="revoke">撤销审核</el-button></div>
        </section>
      </template>
      <el-button v-if="error" :disabled="loading || saving" @click="load">重新加载审核信息</el-button>
    </div>
  </AppDialog>
</template>

<style scoped>
.effect-review { display: grid; gap: 16px; }
.effect-review__intro { margin: 0; color: var(--el-text-color-regular); }
.effect-review__section { display: grid; gap: 10px; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 12px; }
.effect-review__section h3, .effect-review__section p { margin: 0; }
.effect-review__section p { color: var(--el-text-color-regular); line-height: 1.6; }
.effect-review__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
dl { display: grid; gap: 8px; margin: 0; }
dl div { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; }
dt { color: var(--el-text-color-secondary); }
dd { margin: 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow: auto; }
.effect-review__field { display: grid; gap: 6px; }
@media (max-width: 560px) { dl div { grid-template-columns: 1fr; gap: 2px; } .effect-review__heading { align-items: flex-start; flex-direction: column; } }
</style>
