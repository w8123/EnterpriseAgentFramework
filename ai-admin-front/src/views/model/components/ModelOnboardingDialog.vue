<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  createModelInstance,
  createModelInstanceFromTemplate,
  testModelInstanceDraft,
} from '@/api/model'
import type { ModelInstance, ModelInstanceTestResult, ModelTemplate, ModelType } from '@/types/model'
import {
  buildCreateRequest,
  buildDraftTestRequest,
  buildFromTemplateRequest,
  draftFromCustomOpenAi,
  draftFromTemplate,
  draftRuntimeFingerprint,
  isApiKeyRequired,
  type ModelInstanceDraft,
  readModelApiPayload,
  validateDraftForSave,
} from '../modelCenterUi'
import ModelInstanceForm from './ModelInstanceForm.vue'
import ModelTemplatePicker from './ModelTemplatePicker.vue'
import ModelTestResultPanel from './ModelTestResultPanel.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  initialModelType?: ModelType | ''
}>(), {
  initialModelType: '',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: [instance: ModelInstance]
}>()

const step = ref(0)
const draft = ref<ModelInstanceDraft>(draftFromCustomOpenAi('LLM'))
const selectedTemplate = ref<ModelTemplate | null>(null)
const apiKey = ref('')
const testing = ref(false)
const saving = ref(false)
const testResult = ref<ModelInstanceTestResult | null>(null)
const testStale = ref(false)
const lastSuccessfulFingerprint = ref('')
const formRef = ref<InstanceType<typeof ModelInstanceForm> | null>(null)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const dialogTitle = computed(() =>
  step.value === 0 ? '选择厂商与模型' : '配置模型连接',
)

const dialogDescription = computed(() =>
  step.value === 0
    ? '从模型目录中选择，或接入任意 OpenAI 兼容模型。'
    : '填写上游服务地址、认证信息和默认调用参数。',
)

function resetDialogScroll() {
  nextTick(() => {
    const body = document.querySelector<HTMLElement>('.model-onboarding-dialog__body')
    if (body) body.scrollTop = 0
  })
}

function resetState() {
  step.value = 0
  draft.value = draftFromCustomOpenAi(props.initialModelType || 'LLM')
  selectedTemplate.value = null
  apiKey.value = ''
  testing.value = false
  saving.value = false
  testResult.value = null
  testStale.value = false
  lastSuccessfulFingerprint.value = ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetState()
      resetDialogScroll()
    }
  },
)

function markTestStale() {
  if (testResult.value) testStale.value = true
  lastSuccessfulFingerprint.value = ''
}

function handleSelectTemplate(template: ModelTemplate) {
  selectedTemplate.value = template
  draft.value = draftFromTemplate(template)
  apiKey.value = ''
  testResult.value = null
  testStale.value = false
  lastSuccessfulFingerprint.value = ''
  step.value = 1
  resetDialogScroll()
}

function handleSelectCustom() {
  selectedTemplate.value = null
  draft.value = draftFromCustomOpenAi(props.initialModelType || 'LLM')
  apiKey.value = ''
  testResult.value = null
  testStale.value = false
  lastSuccessfulFingerprint.value = ''
  step.value = 1
  resetDialogScroll()
}

function handlePrevious() {
  step.value = 0
  resetDialogScroll()
}

function syncFormDraft(): ModelInstanceDraft {
  return formRef.value?.commitFriendlyOptions() || draft.value
}

async function runDraftTest(): Promise<ModelInstanceTestResult | null> {
  const current = syncFormDraft()
  draft.value = current
  const error = validateDraftForSave(current, apiKey.value, {
    mode: 'create',
    credentialSchema: selectedTemplate.value?.credentialSchema,
  })
  if (error) {
    ElMessage.warning(error)
    return null
  }

  testing.value = true
  try {
    const payload = buildDraftTestRequest(current, apiKey.value)
    const { data } = await testModelInstanceDraft(payload)
    const result = readModelApiPayload<ModelInstanceTestResult>(data)
    testResult.value = result
    testStale.value = false
    if (result.success) {
      lastSuccessfulFingerprint.value = draftRuntimeFingerprint(current, apiKey.value)
      ElMessage.success('草稿测试通过')
    } else {
      lastSuccessfulFingerprint.value = ''
      ElMessage.warning(result.message || '草稿测试未通过')
    }
    return result
  } catch (err) {
    testResult.value = null
    lastSuccessfulFingerprint.value = ''
    ElMessage.error(err instanceof Error ? err.message : '测试接口异常')
    throw err
  } finally {
    testing.value = false
  }
}

async function handleTest() {
  try {
    await runDraftTest()
  } catch {
    // message already shown
  }
}

async function createInstance(): Promise<ModelInstance> {
  const current = syncFormDraft()
  draft.value = current
  if (selectedTemplate.value?.id) {
    const payload = buildFromTemplateRequest(current, apiKey.value)
    const { data } = await createModelInstanceFromTemplate(selectedTemplate.value.id, payload)
    return readModelApiPayload<ModelInstance>(data)
  }
  const payload = buildCreateRequest(current, apiKey.value)
  const { data } = await createModelInstance(payload)
  return readModelApiPayload<ModelInstance>(data)
}

async function handleSave(options?: { skipTest?: boolean }) {
  const current = syncFormDraft()
  draft.value = current
  const error = validateDraftForSave(current, apiKey.value, {
    mode: 'create',
    credentialSchema: selectedTemplate.value?.credentialSchema,
  })
  if (error) {
    ElMessage.warning(error)
    return
  }

  const fingerprint = draftRuntimeFingerprint(current, apiKey.value)
  const alreadyPassed = !testStale.value && lastSuccessfulFingerprint.value === fingerprint

  if (!options?.skipTest && !alreadyPassed) {
    let result: ModelInstanceTestResult | null = null
    try {
      result = await runDraftTest()
    } catch {
      return
    }
    if (!result) return
    if (!result.success) {
      try {
        await ElMessageBox.confirm(
          `${result.message || '草稿测试未通过'}。仍要保存该模型吗？`,
          '测试未通过',
          {
            type: 'warning',
            confirmButtonText: '仍然保存',
            cancelButtonText: '返回修改',
          },
        )
      } catch {
        return
      }
      // 仍然保存：不再重复测试
      return handleSave({ skipTest: true })
    }
  }

  saving.value = true
  try {
    const created = await createInstance()
    ElMessage.success('模型已接入')
    emit('created', created)
    visible.value = false
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const credentialRequired = computed(() =>
  isApiKeyRequired(draft.value, selectedTemplate.value?.credentialSchema),
)
</script>

<template>
  <AppDialog
    v-model="visible"
    :title="dialogTitle"
    width="960px"
    destroy-on-close
    class="model-onboarding-dialog"
    body-class="model-onboarding-dialog__body"
  >
    <template #header>
      <div class="model-onboarding-dialog__heading">
        <div class="model-onboarding-dialog__eyebrow">模型接入 · 步骤 {{ step + 1 }} / 2</div>
        <h2>{{ dialogTitle }}</h2>
        <p>{{ dialogDescription }}</p>
        <div class="model-onboarding-dialog__progress" aria-hidden="true">
          <span class="is-complete" />
          <span :class="{ 'is-complete': step === 1 }" />
        </div>
      </div>
    </template>

    <ModelTemplatePicker
      v-if="step === 0"
      :model-type="initialModelType"
      :lock-model-type="Boolean(initialModelType)"
      @select-template="handleSelectTemplate"
      @select-custom="handleSelectCustom"
    />

    <div v-else class="model-onboarding-dialog__step2">
      <div class="model-onboarding-dialog__selection">
        <span>{{ selectedTemplate ? '目录模型' : '自定义接入' }}</span>
        <div>
          <strong>{{ selectedTemplate?.name || 'OpenAI 兼容模型' }}</strong>
          <p v-if="selectedTemplate">供应商与模型类型已由目录锁定，其余配置可按部署环境调整。</p>
          <p v-else>请按上游服务的 OpenAI 兼容协议填写连接信息。</p>
        </div>
      </div>
      <ModelInstanceForm
        ref="formRef"
        :draft="draft"
        :api-key="apiKey"
        mode="create"
        :lock-model-type="Boolean(selectedTemplate)"
        :lock-provider="Boolean(selectedTemplate)"
        :credential-required="credentialRequired"
        @update:draft="draft = $event"
        @update:api-key="apiKey = $event"
        @runtime-changed="markTestStale"
      />
      <ModelTestResultPanel :result="testResult" :stale="testStale" />
    </div>

    <template #footer>
      <div class="model-onboarding-dialog__footer">
        <div class="model-onboarding-dialog__footer-group">
          <el-button @click="visible = false">取消</el-button>
          <el-button v-if="step === 1" @click="handlePrevious">上一步</el-button>
        </div>
        <div v-if="step === 1" class="model-onboarding-dialog__footer-group">
          <el-button :loading="testing" @click="handleTest">测试连接</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave()">保存接入</el-button>
        </div>
      </div>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.model-onboarding-dialog__heading {
  min-width: 0;
  padding-right: 48px;
}

.model-onboarding-dialog__eyebrow {
  margin-bottom: 5px;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.12em;
}

.model-onboarding-dialog__heading h2,
.model-onboarding-dialog__heading p,
.model-onboarding-dialog__selection p {
  margin: 0;
}

.model-onboarding-dialog__heading h2 {
  color: var(--text-primary);
  font-size: 20px;
  line-height: 1.35;
}

.model-onboarding-dialog__heading p {
  margin-top: 5px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.model-onboarding-dialog__progress {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin-top: 14px;
}

.model-onboarding-dialog__progress span {
  height: 3px;
  border-radius: 999px;
  background: var(--border-glass);
  transition: background 0.2s ease;
}

.model-onboarding-dialog__progress span.is-complete {
  background: var(--brand-primary-gradient);
}

.model-onboarding-dialog__step2 {
  display: grid;
  gap: 16px;
}

.model-onboarding-dialog__selection {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 24%, var(--border-glass));
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-color-primary) 7%, var(--surface-solid-control));
}

.model-onboarding-dialog__selection > span {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary) 13%, var(--surface-solid-control));
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
}

.model-onboarding-dialog__selection strong {
  color: var(--text-primary);
  font-size: 13px;
}

.model-onboarding-dialog__selection p {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.model-onboarding-dialog__footer {
  display: flex;
  width: 100%;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.model-onboarding-dialog__footer-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

:global(.model-onboarding-dialog.el-dialog .el-dialog__header) {
  padding: 22px 28px 18px;
  background:
    radial-gradient(circle at 82% 10%, rgb(var(--brand-primary-rgb) / 0.13), transparent 34%),
    linear-gradient(135deg, rgb(var(--brand-selected-rgb) / 0.1), transparent 62%);
}

:global(.model-onboarding-dialog.el-dialog .el-dialog__body) {
  max-block-size: min(72vh, 760px);
  padding: 20px 28px 24px;
  background: color-mix(in srgb, var(--surface-solid-page) 44%, transparent);
}

:global(.model-onboarding-dialog.el-dialog .el-dialog__footer) {
  padding: 14px 28px;
  background: color-mix(in srgb, var(--surface-solid-overlay) 92%, transparent);
}

@media (max-width: 720px) {
  .model-onboarding-dialog__footer,
  .model-onboarding-dialog__footer-group {
    width: 100%;
  }

  .model-onboarding-dialog__footer-group :deep(.el-button) {
    flex: 1;
  }

  :global(.model-onboarding-dialog.el-dialog .el-dialog__header),
  :global(.model-onboarding-dialog.el-dialog .el-dialog__body),
  :global(.model-onboarding-dialog.el-dialog .el-dialog__footer) {
    padding-inline: 18px;
  }
}
</style>
