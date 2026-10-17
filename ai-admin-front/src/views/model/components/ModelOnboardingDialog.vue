<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  createProviderAccount,
  createProviderAccountInstances,
  discoverProviderModelsDraft,
  getProviderAccountModels,
  getProviderAccounts,
  updateProviderAccount,
} from '@/api/model'
import type {
  ModelInstance,
  ModelProviderAccount,
  ModelType,
  ProviderAccountInstancesResult,
  ProviderModelsResult,
  UpstreamModel,
  UpstreamModelType,
} from '@/types/model'
import {
  modelTypeLabel,
  normalizeListPayload,
  readModelApiPayload,
  type ModelProviderGroup,
} from '../modelCenterUi'
import ModelProviderIcon from './ModelProviderIcon.vue'
import ModelTemplatePicker from './ModelTemplatePicker.vue'

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

type Step = 'provider' | 'account' | 'models'
type TypeFilter = UpstreamModelType | ''

interface SelectedProvider {
  provider: string
  displayName: string
  iconKey: string | null
  custom: boolean
  baseUrl: string
  authHeader: string
  authPrefix: string
}

interface CandidateRow {
  id: string
  created?: number | null
  inferredType: UpstreamModelType
  recommended: boolean
  manual: boolean
}

const STEP_ORDER: Step[] = ['provider', 'account', 'models']

const step = ref<Step>('provider')
const provider = ref<SelectedProvider | null>(null)

const accounts = ref<ModelProviderAccount[]>([])
const accountsLoading = ref(false)
const selectedAccountId = ref('')
const creatingAccount = ref(false)
const editingAccountId = ref('')
const accountForm = ref({ name: '', baseUrl: '', apiKey: '', authHeader: '', authPrefix: '' })
const accountBusy = ref(false)
const accountError = ref('')

const modelsLoading = ref(false)
const modelsResult = ref<ProviderModelsResult | null>(null)
const keyword = ref('')
const typeFilter = ref<TypeFilter>('')
const selected = ref<Record<string, ModelType>>({})
const manualRows = ref<CandidateRow[]>([])
const manualName = ref('')
const manualType = ref<ModelType>('LLM')
const saving = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const stepIndex = computed(() => STEP_ORDER.indexOf(step.value))

const dialogTitle = computed(() => {
  if (step.value === 'provider') return '选择厂商'
  if (step.value === 'account') return `${provider.value?.displayName || ''}账号`
  return '选择要接入的模型'
})

const dialogDescription = computed(() => {
  if (step.value === 'provider') return '先选厂商，再用该厂商的 API Key 获取它当前提供的模型。'
  if (step.value === 'account') return '同一厂商的服务地址和 API Key 只需填写一次，之后接入的模型都会复用。'
  return '列表来自厂商实时返回，勾选后一次接入；之后可在模型详情里单独测试和调整参数。'
})

const currentAccount = computed(() =>
  accounts.value.find((item) => item.id === selectedAccountId.value) || null,
)

const connectedKeys = computed(() => {
  const keys = new Set<string>()
  for (const item of modelsResult.value?.connected || []) keys.add(`${item.modelName}|${item.modelType}`)
  return keys
})

const recommendedNames = computed(() => new Set(modelsResult.value?.recommendedModelNames || []))

const candidateRows = computed<CandidateRow[]>(() => {
  const rows: CandidateRow[] = (modelsResult.value?.models || []).map((model: UpstreamModel) => ({
    id: model.id,
    created: model.created,
    inferredType: model.inferredType,
    recommended: recommendedNames.value.has(model.id),
    manual: false,
  }))
  const known = new Set(rows.map((row) => row.id))
  return [...manualRows.value.filter((row) => !known.has(row.id)), ...rows]
})

const visibleRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return candidateRows.value
    .filter((row) => !typeFilter.value || row.inferredType === typeFilter.value)
    .filter((row) => !query || row.id.toLowerCase().includes(query))
})

const typeCounts = computed(() => {
  const counts: Record<string, number> = { '': candidateRows.value.length }
  for (const row of candidateRows.value) counts[row.inferredType] = (counts[row.inferredType] || 0) + 1
  return counts
})

const selectedCount = computed(() => Object.keys(selected.value).length)

function resetState() {
  step.value = 'provider'
  provider.value = null
  accounts.value = []
  selectedAccountId.value = ''
  creatingAccount.value = false
  accountError.value = ''
  modelsResult.value = null
  keyword.value = ''
  typeFilter.value = props.initialModelType || ''
  selected.value = {}
  manualRows.value = []
  manualName.value = ''
  manualType.value = props.initialModelType || 'LLM'
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) resetState()
  },
)

function scrollTop() {
  nextTick(() => {
    const body = document.querySelector<HTMLElement>('.model-onboarding-dialog__body')
    if (body) body.scrollTop = 0
  })
}

function goTo(next: Step) {
  step.value = next
  scrollTop()
}

function handleSelectProvider(group: ModelProviderGroup) {
  const defaults = group.templates.find((item) => item.modelType === 'LLM')?.connectionDefaults
    || group.templates[0]?.connectionDefaults
    || {}
  provider.value = {
    provider: group.provider,
    displayName: group.displayName,
    iconKey: group.iconKey,
    custom: false,
    baseUrl: String(defaults.baseUrl || ''),
    authHeader: String(defaults.authHeader || ''),
    authPrefix: defaults.authPrefix === undefined ? '' : String(defaults.authPrefix),
  }
  void openAccountStep()
}

function handleSelectCustom() {
  provider.value = {
    provider: 'custom',
    displayName: '自定义 OpenAI 兼容服务',
    iconKey: null,
    custom: true,
    baseUrl: '',
    authHeader: '',
    authPrefix: '',
  }
  void openAccountStep()
}

async function openAccountStep() {
  if (!provider.value) return
  goTo('account')
  accountsLoading.value = true
  accountError.value = ''
  try {
    const { data } = await getProviderAccounts(provider.value.provider)
    accounts.value = normalizeListPayload<ModelProviderAccount>(readModelApiPayload(data))
  } catch (err) {
    accounts.value = []
    accountError.value = err instanceof Error ? err.message : '读取厂商账号失败'
  } finally {
    accountsLoading.value = false
  }
  selectedAccountId.value = accounts.value[0]?.id || ''
  if (accounts.value.length) {
    creatingAccount.value = false
  } else {
    startNewAccount()
  }
}

function startNewAccount() {
  const current = provider.value
  creatingAccount.value = true
  editingAccountId.value = ''
  accountError.value = ''
  accountForm.value = {
    name: accounts.value.length ? `${current?.displayName || ''}账号 ${accounts.value.length + 1}` : `${current?.displayName || ''}账号`,
    baseUrl: current?.baseUrl || '',
    apiKey: '',
    authHeader: current?.authHeader || '',
    authPrefix: current?.authPrefix || '',
  }
}

function startEditAccount(account: ModelProviderAccount) {
  creatingAccount.value = true
  editingAccountId.value = account.id
  accountError.value = ''
  accountForm.value = {
    name: account.name,
    baseUrl: String(account.connection.baseUrl || ''),
    apiKey: '',
    authHeader: String(account.connection.authHeader || ''),
    authPrefix: account.connection.authPrefix === undefined ? '' : String(account.connection.authPrefix),
  }
}

function cancelAccountForm() {
  creatingAccount.value = false
  editingAccountId.value = ''
  accountError.value = ''
}

/** 修改已有账号：Key 留空表示不变（回传掩码，服务端保留原值）。 */
async function saveEditedAccount() {
  const form = accountForm.value
  if (!form.name.trim()) return ElMessage.warning('请填写账号名称')
  if (!form.baseUrl.trim()) return ElMessage.warning('请填写 Base URL')
  const account = accounts.value.find((item) => item.id === editingAccountId.value)
  const connection: Record<string, string> = accountConnection()
  if (!connection.apiKey && account?.apiKeyConfigured) connection.apiKey = String(account.connection.apiKey || '')
  accountBusy.value = true
  accountError.value = ''
  try {
    const { data } = await updateProviderAccount(editingAccountId.value, { name: form.name.trim(), connection })
    const updated = readModelApiPayload<ModelProviderAccount>(data)
    accounts.value = accounts.value.map((item) => (item.id === updated.id ? updated : item))
    selectedAccountId.value = updated.id
    cancelAccountForm()
    ElMessage.success('账号已更新，使用它的模型会立即用新的地址和 Key')
  } catch (err) {
    accountError.value = err instanceof Error ? err.message : '保存账号失败'
  } finally {
    accountBusy.value = false
  }
}

function accountConnection() {
  const form = accountForm.value
  const connection: Record<string, string> = { baseUrl: form.baseUrl.trim() }
  if (form.apiKey.trim()) connection.apiKey = form.apiKey.trim()
  if (form.authHeader.trim()) connection.authHeader = form.authHeader.trim()
  if (form.authPrefix.trim()) connection.authPrefix = form.authPrefix
  return connection
}

/** 先用填写的 Key 拉一次模型列表，成功才保存账号，避免存下一个用不了的 Key。 */
async function verifyAndCreateAccount() {
  const form = accountForm.value
  const providerKey = provider.value?.provider || 'custom'
  if (!form.name.trim()) return ElMessage.warning('请填写账号名称')
  if (!form.baseUrl.trim()) return ElMessage.warning('请填写 Base URL')
  if (!provider.value?.custom && !form.apiKey.trim()) return ElMessage.warning('请填写 API Key')
  accountBusy.value = true
  accountError.value = ''
  try {
    const { data } = await discoverProviderModelsDraft({ provider: providerKey, connection: accountConnection() })
    const result = readModelApiPayload<ProviderModelsResult>(data)
    if (!result.success) {
      accountError.value = result.message || '获取模型列表失败，请检查地址和 Key'
      return
    }
    const created = await createProviderAccount({
      name: form.name.trim(),
      provider: providerKey,
      connection: accountConnection(),
    })
    const account = readModelApiPayload<ModelProviderAccount>(created.data)
    accounts.value = [...accounts.value, account]
    selectedAccountId.value = account.id
    creatingAccount.value = false
    form.apiKey = ''
    applyModelsResult(result)
    goTo('models')
  } catch (err) {
    accountError.value = err instanceof Error ? err.message : '保存账号失败'
  } finally {
    accountBusy.value = false
  }
}

async function continueWithAccount() {
  if (!currentAccount.value) return ElMessage.warning('请选择账号')
  goTo('models')
  await loadAccountModels()
}

async function loadAccountModels() {
  if (!currentAccount.value) return
  modelsLoading.value = true
  try {
    const { data } = await getProviderAccountModels(currentAccount.value.id)
    applyModelsResult(readModelApiPayload<ProviderModelsResult>(data))
  } catch (err) {
    modelsResult.value = {
      success: false,
      message: err instanceof Error ? err.message : '获取模型列表失败',
      total: 0,
      models: [],
      recommendedModelNames: [],
      connected: [],
    }
  } finally {
    modelsLoading.value = false
  }
}

function applyModelsResult(result: ProviderModelsResult) {
  modelsResult.value = result
  selected.value = {}
  if (typeFilter.value && !result.models.some((model) => model.inferredType === typeFilter.value)) {
    typeFilter.value = ''
  }
}

function rowType(row: CandidateRow): ModelType {
  return selected.value[row.id] || (row.inferredType === 'OTHER' ? 'LLM' : row.inferredType)
}

function isConnected(row: CandidateRow) {
  return connectedKeys.value.has(`${row.id}|${rowType(row)}`)
}

function toggleRow(row: CandidateRow, checked: boolean) {
  const next = { ...selected.value }
  if (checked) next[row.id] = rowType(row)
  else delete next[row.id]
  selected.value = next
}

function setRowType(row: CandidateRow, type: ModelType) {
  if (!selected.value[row.id]) return
  selected.value = { ...selected.value, [row.id]: type }
}

function addManualModel() {
  const name = manualName.value.trim()
  if (!name) return
  if (!candidateRows.value.some((row) => row.id === name)) {
    manualRows.value = [{ id: name, inferredType: manualType.value, recommended: false, manual: true }, ...manualRows.value]
  }
  selected.value = { ...selected.value, [name]: manualType.value }
  manualName.value = ''
}

function formatCreated(created?: number | null) {
  if (!created) return ''
  const date = new Date(created * 1000)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10)
}

function typeTagLabel(type: UpstreamModelType) {
  return type === 'OTHER' ? '其他' : modelTypeLabel(type)
}

async function handleCreate() {
  if (!currentAccount.value || !selectedCount.value) return
  saving.value = true
  try {
    const { data } = await createProviderAccountInstances(currentAccount.value.id, {
      models: Object.entries(selected.value).map(([modelName, modelType]) => ({ modelName, modelType })),
    })
    const result = readModelApiPayload<ProviderAccountInstancesResult>(data)
    if (result.created.length) {
      const skipped = result.skipped.length ? `，${result.skipped.length} 个未接入` : ''
      ElMessage.success(`已接入 ${result.created.length} 个模型${skipped}`)
      emit('created', result.created[0])
      visible.value = false
    } else {
      ElMessage.warning(result.skipped.map((item) => `${item.modelName}：${item.reason}`).join('；') || '没有接入任何模型')
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '接入失败')
  } finally {
    saving.value = false
  }
}

function handlePrevious() {
  if (step.value === 'models') goTo('account')
  else goTo('provider')
}
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
        <div class="model-onboarding-dialog__eyebrow">模型接入 · 步骤 {{ stepIndex + 1 }} / 3</div>
        <h2>{{ dialogTitle }}</h2>
        <p>{{ dialogDescription }}</p>
        <div class="model-onboarding-dialog__progress" aria-hidden="true">
          <span v-for="(item, index) in STEP_ORDER" :key="item" :class="{ 'is-complete': index <= stepIndex }" />
        </div>
      </div>
    </template>

    <ModelTemplatePicker
      v-if="step === 'provider'"
      provider-only
      :model-type="initialModelType"
      :lock-model-type="Boolean(initialModelType)"
      @select-provider="handleSelectProvider"
      @select-custom="handleSelectCustom"
    />

    <div v-else-if="step === 'account'" v-loading="accountsLoading" class="onboarding-account">
      <div v-if="provider" class="onboarding-provider">
        <ModelProviderIcon :provider="provider.provider" :icon-key="provider.iconKey" :size="32" />
        <strong>{{ provider.displayName }}</strong>
      </div>

      <template v-if="!creatingAccount && accounts.length">
        <div class="onboarding-account__list" role="radiogroup" aria-label="选择账号">
          <div
            v-for="account in accounts"
            :key="account.id"
            class="onboarding-account__item"
            :class="{ 'is-active': account.id === selectedAccountId }"
            role="radio"
            tabindex="0"
            :aria-checked="account.id === selectedAccountId"
            @click="selectedAccountId = account.id"
            @keydown.enter="selectedAccountId = account.id"
          >
            <strong>{{ account.name }}</strong>
            <span>{{ account.connection.baseUrl }}</span>
            <span>Key {{ account.apiKeyConfigured ? account.connection.apiKey : '未配置' }} · 已接入 {{ account.instanceCount }} 个模型</span>
            <el-button
              class="onboarding-account__edit"
              link
              type="primary"
              size="small"
              @click.stop="startEditAccount(account)"
            >
              修改地址或 Key
            </el-button>
          </div>
        </div>
        <el-button text type="primary" :icon="Plus" @click="startNewAccount">使用另一个 Key</el-button>
      </template>

      <el-form v-else class="onboarding-account__form" label-position="top" @submit.prevent>
        <el-form-item label="账号名称" required>
          <el-input v-model="accountForm.name" maxlength="80" placeholder="例如：百炼生产账号" />
        </el-form-item>
        <el-form-item label="Base URL" required class="span-2">
          <el-input v-model="accountForm.baseUrl" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item label="API Key" :required="!provider?.custom && !editingAccountId" class="span-2">
          <el-input
            v-model="accountForm.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingAccountId ? '留空保持不变' : provider?.custom ? '可选' : '在厂商控制台创建的 API Key'"
            @keyup.enter="editingAccountId ? saveEditedAccount() : verifyAndCreateAccount()"
          />
        </el-form-item>
        <el-form-item label="认证 Header">
          <el-input v-model="accountForm.authHeader" placeholder="Authorization" />
        </el-form-item>
        <el-form-item label="认证前缀">
          <el-input v-model="accountForm.authPrefix" placeholder="Bearer " />
        </el-form-item>
        <div v-if="accounts.length" class="span-2">
          <el-button text @click="cancelAccountForm">返回选择已有账号</el-button>
        </div>
      </el-form>

      <el-alert v-if="accountError" type="error" :closable="false" show-icon :title="accountError" />
    </div>

    <div v-else class="onboarding-models">
      <div class="onboarding-models__toolbar">
        <div class="onboarding-models__account">
          <ModelProviderIcon v-if="provider" :provider="provider.provider" :icon-key="provider.iconKey" :size="28" />
          <div>
            <strong>{{ currentAccount?.name }}</strong>
            <span v-if="modelsResult?.success">厂商返回 {{ modelsResult.total }} 个可用模型</span>
          </div>
        </div>
        <el-button :icon="Refresh" :loading="modelsLoading" @click="loadAccountModels">刷新</el-button>
      </div>

      <el-alert
        v-if="modelsResult && !modelsResult.success"
        type="warning"
        :closable="false"
        show-icon
        :title="modelsResult.message"
        description="仍可在下方手动填写模型名称接入。"
      />

      <div class="onboarding-models__filters">
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索模型名称" />
        <div class="onboarding-models__pills" role="tablist" aria-label="模型类型">
          <button
            v-for="item in (['', 'LLM', 'EMBEDDING', 'RERANKER', 'OTHER'] as TypeFilter[])"
            :key="item || 'all'"
            type="button"
            class="onboarding-models__pill"
            :class="{ 'is-active': typeFilter === item }"
            @click="typeFilter = item"
          >
            {{ item ? typeTagLabel(item) : '全部' }} {{ typeCounts[item] || 0 }}
          </button>
        </div>
      </div>

      <div v-loading="modelsLoading" class="onboarding-models__list" role="list">
        <div
          v-for="row in visibleRows"
          :key="row.id"
          class="onboarding-models__row"
          :class="{ 'is-selected': Boolean(selected[row.id]), 'is-disabled': isConnected(row) }"
          role="listitem"
        >
          <el-checkbox
            :model-value="Boolean(selected[row.id])"
            :disabled="isConnected(row)"
            @update:model-value="toggleRow(row, Boolean($event))"
          />
          <div class="onboarding-models__name">
            <strong>{{ row.id }}</strong>
            <span v-if="row.recommended" class="onboarding-models__badge is-recommended">目录推荐</span>
            <span v-if="row.manual" class="onboarding-models__badge">手动添加</span>
            <span v-if="isConnected(row)" class="onboarding-models__badge">已接入</span>
          </div>
          <span class="onboarding-models__date">{{ formatCreated(row.created) }}</span>
          <el-select
            v-if="selected[row.id]"
            :model-value="selected[row.id]"
            size="small"
            class="onboarding-models__type"
            @update:model-value="setRowType(row, $event)"
          >
            <el-option label="大语言模型" value="LLM" />
            <el-option label="向量模型" value="EMBEDDING" />
            <el-option label="重排模型" value="RERANKER" />
          </el-select>
          <span v-else class="onboarding-models__type-tag">{{ typeTagLabel(row.inferredType) }}</span>
        </div>
        <el-empty
          v-if="!modelsLoading && !visibleRows.length"
          :image-size="64"
          :description="keyword ? '没有匹配的模型' : '没有可显示的模型'"
        />
      </div>

      <div class="onboarding-models__manual">
        <el-input v-model="manualName" placeholder="列表里没有？手动填写模型名称" @keyup.enter="addManualModel" />
        <el-select v-model="manualType" class="onboarding-models__type">
          <el-option label="大语言模型" value="LLM" />
          <el-option label="向量模型" value="EMBEDDING" />
          <el-option label="重排模型" value="RERANKER" />
        </el-select>
        <el-button :disabled="!manualName.trim()" @click="addManualModel">添加</el-button>
      </div>
    </div>

    <template #footer>
      <div class="model-onboarding-dialog__footer">
        <div class="model-onboarding-dialog__footer-group">
          <el-button @click="visible = false">取消</el-button>
          <el-button v-if="step !== 'provider'" @click="handlePrevious">上一步</el-button>
        </div>
        <div class="model-onboarding-dialog__footer-group">
          <template v-if="step === 'account'">
            <el-button v-if="editingAccountId" type="primary" :loading="accountBusy" @click="saveEditedAccount">
              保存账号
            </el-button>
            <el-button
              v-else-if="creatingAccount || !accounts.length"
              type="primary"
              :loading="accountBusy"
              @click="verifyAndCreateAccount"
            >
              验证并获取模型
            </el-button>
            <el-button v-else type="primary" :disabled="!selectedAccountId" @click="continueWithAccount">
              获取模型列表
            </el-button>
          </template>
          <el-button
            v-if="step === 'models'"
            type="primary"
            :loading="saving"
            :disabled="!selectedCount"
            @click="handleCreate"
          >
            接入所选{{ selectedCount ? `（${selectedCount}）` : '' }}
          </el-button>
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
.model-onboarding-dialog__heading p {
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
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.onboarding-account,
.onboarding-models {
  display: grid;
  gap: 14px;
}

.onboarding-provider,
.onboarding-models__account {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
}

.onboarding-models__account div {
  display: grid;
  gap: 2px;
}

.onboarding-models__account span {
  color: var(--text-muted);
  font-size: 12px;
}

.onboarding-account__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}

.onboarding-account__item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: 12px;
  background: var(--surface-glass-control);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
}

.onboarding-account__item span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.onboarding-account__edit {
  justify-self: start;
  margin-top: 2px;
}

.onboarding-account__item.is-active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 8%, var(--surface-solid-control));
}

.onboarding-account__form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.span-2 {
  grid-column: span 2;
}

.onboarding-models__toolbar,
.onboarding-models__filters,
.onboarding-models__manual {
  display: flex;
  align-items: center;
  gap: 10px;
}

.onboarding-models__toolbar {
  justify-content: space-between;
}

.onboarding-models__filters > .el-input {
  flex: 0 1 280px;
}

.onboarding-models__pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.onboarding-models__pill {
  padding: 5px 10px;
  border: 1px solid var(--border-glass);
  border-radius: 999px;
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.onboarding-models__pill.is-active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.onboarding-models__list {
  display: grid;
  max-height: 46vh;
  min-height: 120px;
  overflow-y: auto;
  border: 1px solid var(--border-glass);
  border-radius: 12px;
}

.onboarding-models__row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 92px 128px;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-glass);
}

.onboarding-models__row:last-child {
  border-bottom: none;
}

.onboarding-models__row.is-selected {
  background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);
}

.onboarding-models__row.is-disabled {
  opacity: 0.6;
}

.onboarding-models__name {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.onboarding-models__name strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.onboarding-models__badge {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--surface-glass-control);
  color: var(--text-muted);
  font-size: 11px;
}

.onboarding-models__badge.is-recommended {
  background: color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  color: var(--el-color-primary);
}

.onboarding-models__date,
.onboarding-models__type-tag {
  color: var(--text-muted);
  font-size: 12px;
}

.onboarding-models__type {
  width: 128px;
}

.onboarding-models__manual > .el-input {
  flex: 1;
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
  .onboarding-account__form {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: auto;
  }

  .onboarding-models__filters,
  .onboarding-models__manual {
    flex-wrap: wrap;
  }

  .onboarding-models__row {
    grid-template-columns: auto minmax(0, 1fr) 110px;
  }

  .onboarding-models__date {
    display: none;
  }

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
