<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { ModelInstanceDraft, FriendlyOptionFields } from '../modelCenterUi'
import {
  defaultPathForType,
  hasBaseUrlAuthorityChanged,
  mergeFriendlyOptions,
  modelTypeLabel,
  pathKeyForType,
  shouldShowDeepSeekThinking,
  splitFriendlyOptions,
} from '../modelCenterUi'

const props = withDefaults(
  defineProps<{
    draft: ModelInstanceDraft
    apiKey: string
    mode: 'create' | 'update'
    lockModelType?: boolean
    lockProvider?: boolean
    showEnableToggle?: boolean
    credentialRequired?: boolean
  }>(),
  {
    lockModelType: false,
    lockProvider: false,
    showEnableToggle: true,
    credentialRequired: true,
  },
)

const emit = defineEmits<{
  'update:draft': [value: ModelInstanceDraft]
  'update:apiKey': [value: string]
  runtimeChanged: []
}>()

const friendly = reactive<FriendlyOptionFields>({
  temperature: undefined,
  maxTokens: undefined,
  thinkingType: undefined,
  reasoningEffort: undefined,
  advancedJson: '{}',
})

const pathKey = computed(() => pathKeyForType(props.draft.modelType))
const pathLabel = computed(() => {
  switch (props.draft.modelType) {
    case 'EMBEDDING':
      return 'Embedding 请求路径'
    case 'RERANKER':
      return 'Rerank 请求路径'
    default:
      return '对话请求路径'
  }
})

const showThinking = computed(() =>
  shouldShowDeepSeekThinking(props.draft.provider, props.draft.modelName),
)

const apiKeyPlaceholder = computed(() => {
  if (props.mode === 'update' && props.draft.apiKeyConfigured) {
    return '已配置，留空保持不变'
  }
  return props.credentialRequired ? '请输入 API Key' : '可选'
})

const authorityChangedHint = computed(() => {
  if (props.mode !== 'update') return ''
  if (!hasBaseUrlAuthorityChanged(props.draft.originalBaseUrl, props.draft.connection.baseUrl)) {
    return ''
  }
  return '服务地址已变化，请重新填写 API Key'
})

function syncFriendlyFromDraft() {
  const next = splitFriendlyOptions(
    props.draft.modelType,
    props.draft.defaultOptions,
    props.draft.provider,
    props.draft.modelName,
  )
  friendly.temperature = next.temperature
  friendly.maxTokens = next.maxTokens
  friendly.thinkingType = next.thinkingType
  friendly.reasoningEffort = next.reasoningEffort
  friendly.advancedJson = next.advancedJson
}

watch(
  () => [props.draft.modelType, props.draft.provider, props.draft.modelName, props.draft.defaultOptions],
  () => syncFriendlyFromDraft(),
  { immediate: true, deep: true },
)

function patchDraft(partial: Partial<ModelInstanceDraft>) {
  emit('update:draft', { ...props.draft, ...partial })
  emit('runtimeChanged')
}

function patchConnection(partial: Record<string, string | undefined>) {
  const connection = { ...props.draft.connection, ...partial }
  const next: ModelInstanceDraft = {
    ...props.draft,
    connection,
  }
  if (
    props.mode === 'update' &&
    hasBaseUrlAuthorityChanged(props.draft.originalBaseUrl, connection.baseUrl)
  ) {
    next.apiKeyConfigured = false
    emit('update:apiKey', '')
  }
  emit('update:draft', next)
  emit('runtimeChanged')
}

function onModelTypeChange(value: ModelInstanceDraft['modelType']) {
  if (props.lockModelType) return
  const connection = {
    ...props.draft.connection,
    chatPath: undefined,
    embeddingPath: undefined,
    rerankPath: undefined,
    [pathKeyForType(value)]: defaultPathForType(value),
  }
  patchDraft({
    modelType: value,
    connection,
    defaultOptions: value === 'LLM' ? props.draft.defaultOptions : {},
  })
}

function commitFriendlyOptions() {
  try {
    const merged = mergeFriendlyOptions(
      props.draft.modelType,
      friendly,
      props.draft.provider,
      props.draft.modelName,
    )
    patchDraft({ defaultOptions: merged })
  } catch {
    // keep typing; validation happens on save/test
  }
}

function syncFriendlyAndEmitRuntime() {
  commitFriendlyOptions()
}

defineExpose({
  commitFriendlyOptions() {
    const merged = mergeFriendlyOptions(
      props.draft.modelType,
      friendly,
      props.draft.provider,
      props.draft.modelName,
    )
    const next = { ...props.draft, defaultOptions: merged }
    emit('update:draft', next)
    return next
  },
})
</script>

<template>
  <el-form class="model-instance-form" label-position="top" @submit.prevent>
    <section class="model-instance-form__section">
      <header class="model-instance-form__section-header">
        <span>1</span>
        <div>
          <h4>基本信息</h4>
          <p>设置平台内的显示名称及对应的上游模型。</p>
        </div>
      </header>
      <div class="model-instance-form__grid">
        <el-form-item label="接入名称" required>
          <el-input
            :model-value="draft.name"
            maxlength="80"
            placeholder="例如：生产 GPT / 知识库向量"
            @update:model-value="patchDraft({ name: $event })"
          />
        </el-form-item>

        <el-form-item label="供应商" required>
          <el-input
            :model-value="draft.provider"
            :disabled="lockProvider"
            placeholder="例如 openai / deepseek / custom"
            @update:model-value="patchDraft({ provider: $event })"
          />
        </el-form-item>

        <el-form-item label="模型类型" required>
          <el-select
            :model-value="draft.modelType"
            :disabled="lockModelType"
            style="width: 100%"
            @update:model-value="onModelTypeChange"
          >
            <el-option label="大语言模型" value="LLM" />
            <el-option label="向量模型" value="EMBEDDING" />
            <el-option label="重排模型" value="RERANKER" />
          </el-select>
          <div v-if="lockModelType" class="field-hint">模型类型创建后不可改变（{{ modelTypeLabel(draft.modelType) }}）</div>
        </el-form-item>

        <el-form-item label="上游模型名称" required>
          <el-input
            :model-value="draft.modelName"
            placeholder="供应商侧的 modelName 或部署名"
            @update:model-value="patchDraft({ modelName: $event })"
          />
        </el-form-item>
      </div>
    </section>

    <section class="model-instance-form__section">
      <header class="model-instance-form__section-header">
        <span>2</span>
        <div>
          <h4>连接与认证</h4>
          <p>配置上游 API 地址、请求路径和访问凭证。</p>
        </div>
      </header>
      <div class="model-instance-form__grid">
        <el-form-item label="Base URL" required class="span-2">
          <el-input
            :model-value="draft.connection.baseUrl"
            placeholder="https://api.example.com/v1"
            @update:model-value="patchConnection({ baseUrl: $event })"
          />
          <div v-if="authorityChangedHint" class="field-hint is-warning">{{ authorityChangedHint }}</div>
        </el-form-item>

        <el-form-item :label="pathLabel" required>
          <el-input
            :model-value="String(draft.connection[pathKey] || '')"
            :placeholder="defaultPathForType(draft.modelType)"
            @update:model-value="patchConnection({ [pathKey]: $event })"
          />
        </el-form-item>

        <el-form-item label="API Key" :required="mode === 'create' ? credentialRequired : Boolean(authorityChangedHint)">
          <el-input
            :model-value="apiKey"
            type="password"
            show-password
            :placeholder="apiKeyPlaceholder"
            autocomplete="new-password"
            @update:model-value="emit('update:apiKey', $event); emit('runtimeChanged')"
          />
          <div v-if="mode === 'update' && draft.apiKeyConfigured && !authorityChangedHint" class="field-hint">
            当前已配置密钥；留空则保持不变
          </div>
        </el-form-item>

        <el-form-item label="认证 Header">
          <el-input
            :model-value="draft.connection.authHeader || ''"
            placeholder="Authorization"
            @update:model-value="patchConnection({ authHeader: $event })"
          />
        </el-form-item>

        <el-form-item label="认证前缀">
          <el-input
            :model-value="draft.connection.authPrefix || ''"
            placeholder="Bearer"
            @update:model-value="patchConnection({ authPrefix: $event })"
          />
        </el-form-item>
      </div>
    </section>

    <section class="model-instance-form__section">
      <header class="model-instance-form__section-header">
        <span>3</span>
        <div>
          <h4>启用与备注</h4>
          <p>决定接入后的初始状态，并补充便于团队识别的说明。</p>
        </div>
      </header>
      <div class="model-instance-form__grid">
        <el-form-item v-if="showEnableToggle && mode === 'create'" label="启用状态" class="span-2">
          <div class="model-instance-form__status-control">
            <el-switch
              :model-value="draft.status === 'ACTIVE'"
              @update:model-value="patchDraft({ status: $event ? 'ACTIVE' : 'DISABLED' })"
            />
            <div>
              <strong>{{ draft.status === 'ACTIVE' ? '创建后立即启用' : '创建后暂不启用' }}</strong>
              <span>{{ draft.status === 'ACTIVE' ? '保存成功后即可被平台调用' : '稍后可在模型详情中手动启用' }}</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="备注" class="span-2">
          <el-input
            :model-value="draft.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填，例如：生产环境主模型"
            @update:model-value="patchDraft({ remark: $event })"
          />
        </el-form-item>
      </div>
    </section>

    <section v-if="draft.modelType === 'LLM'" class="model-instance-form__section model-instance-form__section--parameters">
      <header class="model-instance-form__section-header">
        <span>4</span>
        <div>
          <h4>默认模型参数</h4>
          <p>作为调用该模型时的默认值，业务请求仍可按权限覆盖。</p>
        </div>
      </header>
      <div class="model-instance-form__grid">
        <el-form-item label="随机性（temperature）">
          <el-input-number
            v-model="friendly.temperature"
            :min="0"
            :max="2"
            :step="0.1"
            controls-position="right"
            style="width: 100%"
            @change="syncFriendlyAndEmitRuntime"
          />
        </el-form-item>
        <el-form-item label="最大 Token 数（max_tokens）">
          <el-input-number
            v-model="friendly.maxTokens"
            :min="1"
            :step="1"
            controls-position="right"
            style="width: 100%"
            @change="syncFriendlyAndEmitRuntime"
          />
        </el-form-item>
        <template v-if="showThinking">
          <el-form-item label="思考模式（thinking）">
            <el-select
              v-model="friendly.thinkingType"
              clearable
              placeholder="可选"
              style="width: 100%"
              @change="syncFriendlyAndEmitRuntime"
            >
              <el-option label="开启（enabled）" value="enabled" />
              <el-option label="关闭（disabled）" value="disabled" />
            </el-select>
          </el-form-item>
          <el-form-item label="推理强度（reasoning_effort）">
            <el-select
              v-model="friendly.reasoningEffort"
              clearable
              placeholder="可选"
              style="width: 100%"
              @change="syncFriendlyAndEmitRuntime"
            >
              <el-option label="高（high）" value="high" />
              <el-option label="最高（max）" value="max" />
            </el-select>
          </el-form-item>
        </template>
      </div>
    </section>

    <section class="model-instance-form__section">
      <header class="model-instance-form__section-header">
        <span>{{ draft.modelType === 'LLM' ? '5' : '4' }}</span>
        <div>
          <h4>高级模型参数（JSON）</h4>
          <p>仅在需要传入供应商特有参数时填写。</p>
        </div>
      </header>
      <el-input
        v-model="friendly.advancedJson"
        type="textarea"
        :rows="5"
        placeholder="{}"
        @change="syncFriendlyAndEmitRuntime"
      />
      <p class="field-hint">
        上方常用参数会覆盖 JSON 中的同名键。禁止写入连接字段以及 model、messages、stream、tools 等受保护根字段。
      </p>
    </section>
  </el-form>
</template>

<style scoped lang="scss">
.model-instance-form {
  display: grid;
  gap: 14px;
}

.model-instance-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 18px;
}

.span-2 {
  grid-column: 1 / -1;
}

.model-instance-form__section {
  min-width: 0;
  padding: 18px 20px 4px;
  border: 1px solid var(--border-glass);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-panel) 88%, transparent);
  box-shadow: 0 8px 24px rgb(37 72 122 / 0.05);
}

.model-instance-form__section--parameters {
  border-color: color-mix(in srgb, var(--el-color-primary) 20%, var(--border-glass));
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.045), transparent 46%),
    color-mix(in srgb, var(--surface-solid-panel) 90%, transparent);
}

.model-instance-form__section-header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
}

.model-instance-form__section-header > span {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: color-mix(in srgb, var(--el-color-primary) 12%, var(--surface-solid-control));
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 800;
}

.model-instance-form__section-header h4,
.model-instance-form__section-header p {
  margin: 0;
}

.model-instance-form__section-header h4 {
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.35;
}

.model-instance-form__section-header p {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.model-instance-form__status-control {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-glass);
  border-radius: 10px;
  background: var(--surface-solid-control);
}

.model-instance-form__status-control > div {
  display: grid;
  gap: 2px;
}

.model-instance-form__status-control strong {
  color: var(--text-primary);
  font-size: 13px;
}

.model-instance-form__status-control span {
  color: var(--text-muted);
  font-size: 12px;
}

.field-hint {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.field-hint.is-warning {
  color: var(--status-warning);
}

.model-instance-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.model-instance-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.3;
}

.model-instance-form :deep(.el-input__wrapper),
.model-instance-form :deep(.el-select__wrapper) {
  min-height: 38px;
}

.model-instance-form :deep(.el-textarea__inner) {
  line-height: 1.6;
}

@media (max-width: 720px) {
  .model-instance-form__grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: auto;
  }

  .model-instance-form__section {
    padding-inline: 14px;
  }
}
</style>
