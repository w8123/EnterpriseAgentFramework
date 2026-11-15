<template>
  <div class="external-connection">
    <div class="external-connection__grid">
      <el-form-item label="外部系统" required>
        <el-select
          :model-value="modelValue.provider"
          :disabled="providerLocked"
          placeholder="选择外部知识库"
          :loading="providersLoading"
          @update:model-value="changeProvider"
        >
          <el-option v-for="item in providers" :key="item.type" :label="item.name" :value="item.type" />
        </el-select>
      </el-form-item>
      <el-form-item label="服务地址" required>
        <el-input
          :model-value="modelValue.baseUrl"
          :placeholder="baseUrlPlaceholder"
          @update:model-value="patch({ baseUrl: $event })"
        />
      </el-form-item>
      <el-form-item v-if="currentProvider?.requiresClientId" label="Client ID" required>
        <el-input
          :model-value="modelValue.clientId"
          placeholder="在 ima.qq.com/agent-interface 申请"
          autocomplete="off"
          @update:model-value="patch({ clientId: $event })"
        />
      </el-form-item>
      <el-form-item label="API Key" :required="!apiKeyConfigured">
        <el-input
          :model-value="modelValue.apiKey"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="apiKeyConfigured ? '已保存，留空表示不修改' : apiKeyPlaceholder"
          @update:model-value="patch({ apiKey: $event })"
        />
      </el-form-item>
    </div>

    <div class="external-connection__hint">{{ providerHint }}</div>

    <div class="external-connection__datasets">
      <div class="external-connection__datasets-head">
        <span>要检索的远端知识库</span>
        <el-button size="small" :loading="testing" :disabled="!modelValue.provider" @click="runTest">
          {{ remoteDatasets.length ? '重新读取' : '测试连接并读取' }}
        </el-button>
      </div>
      <el-alert v-if="testError" :title="testError" type="error" :closable="false" show-icon />
      <el-alert v-else-if="testedOk && remoteDatasets.length === 0" title="连接成功，但这个账号下没有可读的知识库。" type="warning" :closable="false" show-icon />
      <el-checkbox-group v-if="checkboxOptions.length" :model-value="selectedIds" class="external-connection__list" @update:model-value="selectIds">
        <el-checkbox v-for="item in checkboxOptions" :key="item.id" :value="item.id">
          {{ item.name }}<small v-if="item.name !== item.id" class="external-connection__id">{{ item.id }}</small>
        </el-checkbox>
      </el-checkbox-group>
      <div v-else-if="!testError && !testedOk" class="external-connection__empty">
        填好地址和密钥后点「测试连接并读取」，再勾选要检索的库（最多 20 个）。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getExternalKnowledgeProviders, testExternalKnowledge } from '@/api/knowledge'
import type { ExternalKnowledgeConnectionForm, ExternalKnowledgeDataset, ExternalKnowledgeProvider } from '@/types/knowledge'

const props = defineProps<{
  modelValue: ExternalKnowledgeConnectionForm
  /** 已保存的知识库：测试时可沿用它的密钥。 */
  kbCode?: string
  apiKeyConfigured?: boolean
  providerLocked?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ExternalKnowledgeConnectionForm]
}>()

const FALLBACK_PROVIDERS: ExternalKnowledgeProvider[] = [
  { type: 'RAGFLOW', name: 'RAGFlow', defaultBaseUrl: null, requiresClientId: false },
  { type: 'IMA', name: '腾讯 ima', defaultBaseUrl: 'https://ima.qq.com', requiresClientId: true },
  { type: 'DIFY', name: 'Dify 知识库', defaultBaseUrl: null, requiresClientId: false },
]
const providers = ref<ExternalKnowledgeProvider[]>(FALLBACK_PROVIDERS)
const providersLoading = ref(false)
const remoteDatasets = ref<ExternalKnowledgeDataset[]>([])
const testing = ref(false)
const testError = ref('')
const testedOk = ref(false)

const currentProvider = computed(() => providers.value.find(item => item.type === props.modelValue.provider))
const selectedIds = computed(() => props.modelValue.datasets.map(item => item.id))
/** 远端列表加上已选但这次没读到的库，避免一次读取失败就把已选的库丢掉。 */
const checkboxOptions = computed(() => {
  const merged = [...remoteDatasets.value]
  props.modelValue.datasets.forEach(item => {
    if (!merged.some(existing => existing.id === item.id)) merged.push(item)
  })
  return merged
})

const baseUrlPlaceholder = computed(() => {
  switch (props.modelValue.provider) {
    case 'RAGFLOW': return '例如 http://10.0.0.5:9380 或 https://ragflow.example.com'
    case 'IMA': return 'https://ima.qq.com'
    case 'DIFY': return '例如 https://api.dify.ai/v1 或 http://10.0.0.6/v1'
    default: return '外部系统的访问地址'
  }
})
const apiKeyPlaceholder = computed(() => {
  switch (props.modelValue.provider) {
    case 'RAGFLOW': return 'RAGFlow 右上角头像 → API → 创建 API Key'
    case 'IMA': return '在 ima.qq.com/agent-interface 申请'
    case 'DIFY': return 'Dify 知识库 → API → 知识库 API 密钥'
    default: return '外部系统的 API Key'
  }
})
const providerHint = computed(() => {
  switch (props.modelValue.provider) {
    case 'RAGFLOW': return '检索时调用 RAGFlow 的 /api/v1/retrieval，使用 RAGFlow 里配置的切分和向量模型。地址在内网也可以，只要知识服务能访问到。'
    case 'IMA': return 'ima 的检索接口只返回标题和命中片段，不返回相似度，结果按 ima 自己的排序给分。绑定的是申请密钥的那个 ima 账号能看到的知识库。'
    case 'DIFY': return '检索时调用 Dify 知识库的 retrieve 接口，使用每个知识库在 Dify 里的检索设置。'
    default: return '外部知识库的内容留在原系统里维护，ReachAI 检索时实时调用，结果和本地知识库一起排序。'
  }
})

function patch(change: Partial<ExternalKnowledgeConnectionForm>) {
  emit('update:modelValue', { ...props.modelValue, ...change })
}

function changeProvider(provider: string) {
  const next = providers.value.find(item => item.type === provider)
  remoteDatasets.value = []
  testedOk.value = false
  testError.value = ''
  patch({ provider, baseUrl: next?.defaultBaseUrl || '', clientId: '', apiKey: '', datasets: [] })
}

function selectIds(ids: Array<string | number | boolean>) {
  const wanted = new Set(ids.map(String))
  patch({ datasets: checkboxOptions.value.filter(item => wanted.has(item.id)) })
}

async function runTest() {
  testing.value = true
  testError.value = ''
  testedOk.value = false
  try {
    const { data } = await testExternalKnowledge({ ...props.modelValue, kbCode: props.kbCode })
    remoteDatasets.value = Array.isArray(data) ? (data as unknown as ExternalKnowledgeDataset[]) : []
    testedOk.value = true
    // 只有一个库时直接选上，省一步。
    if (remoteDatasets.value.length === 1 && props.modelValue.datasets.length === 0) {
      patch({ datasets: [...remoteDatasets.value] })
    }
  } catch (error: unknown) {
    testError.value = error instanceof Error && error.message ? error.message : '连接失败'
  } finally {
    testing.value = false
  }
}

onMounted(async () => {
  providersLoading.value = true
  try {
    const { data } = await getExternalKnowledgeProviders()
    if (Array.isArray(data) && data.length) providers.value = data as unknown as ExternalKnowledgeProvider[]
  } catch {
    // 读不到时用内置列表。
  } finally {
    providersLoading.value = false
  }
})
</script>

<style scoped lang="scss">
.external-connection {
  display: grid;
  gap: 10px;
}

.external-connection__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 0 16px;
}

.external-connection__hint,
.external-connection__empty {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.6;
}

.external-connection__datasets {
  display: grid;
  gap: 8px;
}

.external-connection__datasets-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  font-weight: 600;
}

.external-connection__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 4px 12px;
  max-height: 220px;
  overflow: auto;
}

.external-connection__id {
  margin-left: 6px;
  color: var(--text-muted);
  font-size: 11px;
}
</style>
