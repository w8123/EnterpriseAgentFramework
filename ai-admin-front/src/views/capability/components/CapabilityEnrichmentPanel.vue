<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { useRoute, useRouter, type LocationQueryRaw, type RouteLocationRaw } from 'vue-router'
import { withProjectScopeQuery } from '@/utils/projectScope'
import { editSubjectSemanticDoc, generateSubjectSemanticDoc, getCapabilityEnrichment } from '@/api/semanticDoc'
import { getModelInstances } from '@/api/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH, businessCapabilityWorkspaceLocation } from '../businessCapabilityRoutes'
import type { CapabilityEnrichment, SemanticDoc } from '@/types/semanticDoc'
import type { ModelInstance } from '@/types/model'
import CapabilityParamHints from './CapabilityParamHints.vue'
import CapabilitySemanticDocument from './CapabilitySemanticDocument.vue'

const props = defineProps<{ projectId: number; projectCode?: string; subjectType: 'BUSINESS_METHOD' | 'HTTP_API'; subjectId: number; subjectKey?: string;
  navigate?: (location: RouteLocationRaw) => void }>()
const emit = defineEmits<{ 'busy-change': [value: boolean]; 'dirty-change': [value: boolean] }>()
const router = useRouter()
const route = useRoute()
const enrichment = ref<CapabilityEnrichment | null>(null)
const loading = ref(false)
const error = ref('')
const editing = ref(false)
const draft = ref('')
const expectedUpdatedAt = ref<string | null>(null)
const saving = ref(false)
const generating = ref(false)
const mutationError = ref('')
const conflict = ref(false)
const status = ref('')
const generationOpen = ref(false)
const models = ref<ModelInstance[]>([])
const modelsLoading = ref(false)
const modelsError = ref('')
const modelId = ref('')
const busy = computed(() => saving.value || generating.value)
const canWrite = computed(() => !!props.projectCode && hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, props.projectCode))
let sequence = 0
const category = computed(() => props.subjectType === 'HTTP_API' ? 'http-apis' : 'java-methods')
const location = (workspace: 'sources' | 'groups' | 'semantics' | 'graph') => {
  const query = withProjectScopeQuery(route.query, { kind: 'project',
    project: { id: props.projectId, projectCode: props.projectCode || null, name: '' } }) as LocationQueryRaw
  delete query.subjectKey
  return businessCapabilityWorkspaceLocation(category.value, workspace, { ...query,
    subjectType: props.subjectType, subjectId: String(props.subjectId), ...(props.subjectKey ? { subjectKey: props.subjectKey } : {}) })
}
function go(workspace: 'sources' | 'groups' | 'semantics' | 'graph') {
  if (busy.value) return
  if (props.navigate) props.navigate(location(workspace))
  else void router.push(location(workspace))
}
function openClassification(groupId: number) {
  if (busy.value || !Number.isSafeInteger(groupId) || groupId <= 0) return
  const isApi = props.subjectType === 'HTTP_API'
  const query = withProjectScopeQuery(route.query, { kind: 'project',
    project: { id: props.projectId, projectCode: props.projectCode || null, name: '' } }) as LocationQueryRaw
  const target = { path: isApi ? HTTP_API_CATALOG_PATH : BUSINESS_METHOD_CATALOG_PATH,
    query: { ...query,
      subjectType: undefined, subjectId: undefined, subjectKey: undefined, methodDetail: undefined, methodAssetId: undefined,
      classificationAction: undefined, classificationId: undefined,
      [isApi ? 'apiGroupId' : 'methodGroupId']: String(groupId), [isApi ? 'apiPage' : 'methodPage']: '1' } }
  if (props.navigate) props.navigate(target)
  else void router.push(target)
}
function validDocument(doc: SemanticDoc) {
  return doc.projectId === props.projectId && doc.subjectType === props.subjectType && doc.subjectId === props.subjectId
    && (!props.subjectKey || doc.subjectKey === props.subjectKey)
}
async function read(current: number) {
  const { data } = await getCapabilityEnrichment(props.projectId, props.subjectType, props.subjectId)
  if (current !== sequence) return false
  if (data.projectId !== props.projectId || data.subjectType !== props.subjectType || data.subjectId !== props.subjectId
    || (props.subjectKey && data.subjectKey !== props.subjectKey)
    || (data.semanticDoc && !validDocument(data.semanticDoc))) throw new Error('Asset identity mismatch')
  enrichment.value = data
  error.value = ''
  return true
}
async function load() {
  if (busy.value || loading.value) return
  const current = ++sequence
  error.value = ''; loading.value = true
  try {
    if (await read(current) && editing.value) {
      expectedUpdatedAt.value = enrichment.value?.semanticDoc?.updatedAt || null
      conflict.value = false
      status.value = '已重读最新说明；你的输入已保留，请与下方最新正文核对后保存。'
    }
  } catch { if (current === sequence) error.value = '附属说明与关系读取失败，当前无法判断语义、分组或敏感扫描结果。' }
  finally { if (current === sequence) loading.value = false }
}
function edit() {
  if (!canWrite.value || busy.value || loading.value || error.value) return
  draft.value = enrichment.value?.semanticDoc?.contentMd || ''
  expectedUpdatedAt.value = enrichment.value?.semanticDoc?.updatedAt || null
  editing.value = true; generationOpen.value = false; mutationError.value = ''; conflict.value = false; status.value = ''
}
function cancelEdit() { if (!busy.value) { editing.value = false; draft.value = ''; mutationError.value = ''; conflict.value = false; status.value = '' } }
async function save() {
  if (!canWrite.value || !editing.value || busy.value || loading.value || conflict.value || !expectedUpdatedAt.value) return
  const current = sequence
  saving.value = true; mutationError.value = ''; status.value = ''
  try {
    const { data } = await editSubjectSemanticDoc(props.projectId, props.subjectType, props.subjectId,
      { contentMd: draft.value, expectedUpdatedAt: expectedUpdatedAt.value })
    if (current !== sequence) return
    if (!validDocument(data)) throw new Error('Invalid semantic owner')
    if (await read(current)) { editing.value = false; draft.value = ''; status.value = '业务说明已保存并重读。' }
  } catch (cause) {
    if (current !== sequence) return
    conflict.value = (cause as { response?: { status?: number } })?.response?.status === 409
    mutationError.value = conflict.value ? '说明已被更新；你的输入已保留。请重读最新说明并核对后保存。' : '说明保存或回读失败；输入已保留，请重读最新说明确认结果。'
  } finally { if (current === sequence) saving.value = false }
}
async function prepareGeneration() {
  if (!canWrite.value || busy.value || editing.value || modelsLoading.value || error.value) return
  generationOpen.value = true; modelsLoading.value = true; modelsError.value = ''
  const current = sequence
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    if (current !== sequence) return
    models.value = normalizeActiveModelInstances(data, 'LLM')
    if (!models.value.some(item => item.id === modelId.value)) modelId.value = ''
  } catch { if (current === sequence) modelsError.value = '模型列表读取失败，请重试。' }
  finally { if (current === sequence) modelsLoading.value = false }
}
async function generate() {
  if (!canWrite.value || busy.value || editing.value || loading.value || !models.value.some(item => item.id === modelId.value)) return
  const current = sequence
  generating.value = true; mutationError.value = ''; status.value = ''
  try {
    const { data } = await generateSubjectSemanticDoc(props.projectId, props.subjectType, props.subjectId, true, { modelInstanceId: modelId.value })
    if (current !== sequence) return
    if (!validDocument(data)) throw new Error('Invalid semantic owner')
    if (await read(current)) { generationOpen.value = false; status.value = '业务说明已生成并重读。' }
  } catch { if (current === sequence) mutationError.value = '生成或回读失败，请重读说明确认当前结果后再试。' }
  finally { if (current === sequence) generating.value = false }
}
watch(busy, value => emit('busy-change', value), { flush: 'sync' })
watch(() => editing.value && draft.value !== (enrichment.value?.semanticDoc?.contentMd || ''), value => emit('dirty-change', value), { flush: 'sync' })
watch([() => props.projectId, () => props.projectCode, () => props.subjectType, () => props.subjectId, () => props.subjectKey,
  platformSessionId, () => platformSessionUser.value?.userId, canWrite], () => {
  ++sequence; enrichment.value = null; loading.value = false; saving.value = false; generating.value = false
  editing.value = false; draft.value = ''; expectedUpdatedAt.value = null; conflict.value = false; mutationError.value = ''; status.value = ''
  generationOpen.value = false; models.value = []; modelsLoading.value = false; modelId.value = ''
  void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { sequence += 1; emit('busy-change', false); emit('dirty-change', false) })
</script>

<template>
  <section class="capability-enrichment" aria-label="业务说明与关系">
    <header class="enrichment-header"><div class="enrichment-heading"><h3>业务说明</h3>
      <p v-if="enrichment?.semanticDoc" class="enrichment-provenance">{{ enrichment.semanticDoc.status === 'edited' ? '人工编辑的补充说明' : 'AI 补充说明' }}
        <span v-if="enrichment.semanticDoc.modelName"> · {{ enrichment.semanticDoc.modelName }}</span></p>
    </div><nav aria-label="相关事实">
      <el-button link type="primary" :disabled="busy" @click="go('sources')">查看来源</el-button>
      <el-button v-if="enrichment?.graphAvailable" link type="primary" :disabled="busy" @click="go('graph')">关联分析</el-button>
    </nav></header>
    <div class="enrichment-actions">
      <el-button v-if="canWrite && !editing" :disabled="busy || loading || !enrichment || !!error" @click="prepareGeneration">{{ enrichment?.semanticDoc ? '重新生成说明' : '生成说明' }}</el-button>
      <el-button v-if="canWrite && enrichment?.semanticDoc && !editing" :disabled="busy || loading || !!error" @click="edit">编辑说明</el-button>
      <el-button :disabled="busy || loading" @click="load">重读最新说明</el-button>
    </div>
    <el-form v-if="generationOpen" class="enrichment-generation" novalidate label-position="top" @submit.prevent="generate">
      <p>选择生成模型；重新生成会替换当前补充说明，调用契约保持不变。</p>
      <el-form-item label="生成模型"><el-select v-model="modelId" aria-label="生成模型" :loading="modelsLoading" :disabled="busy" placeholder="请选择模型">
        <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
      </el-select></el-form-item>
      <p v-if="modelsError" role="alert">{{ modelsError }} <el-button link @click="prepareGeneration">重试模型列表</el-button></p>
      <p v-else-if="!modelsLoading && !models.length" role="status">没有可用的语言模型，请先在模型中心配置。</p>
      <el-button type="primary" native-type="submit" :disabled="busy || !modelId" :loading="generating">确认生成说明</el-button>
      <el-button :disabled="busy" @click="generationOpen = false">取消生成</el-button>
    </el-form>
    <el-form v-if="editing" class="enrichment-editor" novalidate label-position="top" @submit.prevent="save">
      <el-form-item label="业务说明内容（Markdown）"><el-input v-model="draft" type="textarea" :rows="12" resize="none" aria-label="业务说明内容" :disabled="busy || !canWrite" /></el-form-item>
      <p v-if="!expectedUpdatedAt" role="alert">缺少说明修订，不能保存。请重读最新说明。</p>
      <el-button type="primary" native-type="submit" :loading="saving" :disabled="busy || loading || conflict || !expectedUpdatedAt || !canWrite">保存说明</el-button>
      <el-button :disabled="busy" @click="cancelEdit">取消编辑</el-button>
    </el-form>
    <p v-if="mutationError" role="alert">{{ mutationError }}</p>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="loading" role="status">正在读取当前资产的说明与来源关系…</p>
    <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重新加载</el-button></div>
    <template v-else-if="enrichment">
      <template v-if="enrichment.semanticDoc">
        <el-alert v-if="enrichment.semanticStale" title="补充说明基于此前契约，请核对当前输入与返回后重新生成或编辑。"
          type="warning" :closable="false" show-icon />
        <CapabilitySemanticDocument :content-md="enrichment.semanticDoc.contentMd" />
      </template>
      <p v-else class="enrichment-provenance">尚未补充业务说明；来源声明与输入输出契约仍为调用依据。</p>
      <div v-if="enrichment.groups.length" class="enrichment-groups">
        <span>业务分类</span><el-button v-for="group in enrichment.groups" :key="group.id" link type="primary" :disabled="busy"
          @click="openClassification(group.id)">{{ group.displayName || group.name }}</el-button>
      </div>
      <ul v-if="enrichment.sourceObservations.some(source => source.sensitiveData?.types?.length || source.sensitiveData?.summary)" class="enrichment-sensitive">
        <li v-for="source in enrichment.sourceObservations.filter(item => item.sensitiveData?.types?.length || item.sensitiveData?.summary)" :key="source.id">
          <strong>来源敏感扫描 · {{ source.title }}</strong><p>{{ source.sensitiveData?.summary || source.sensitiveData?.types?.join('、') }}</p>
          <small v-if="source.sensitiveData?.scannedAt">{{ source.sensitiveData.scannedAt }}</small>
        </li>
      </ul>
      <CapabilityParamHints :project-id="projectId" :subject-type="subjectType" :subject-id="subjectId"
        :graph-state="enrichment.graphState" :contract-hash="enrichment.contractHash" :hints="enrichment.paramHints" @retry="load" />
    </template>
  </section>
</template>

<style scoped lang="scss">
.capability-enrichment { padding: 18px 0; overflow-wrap: anywhere; min-width: 0; }
.enrichment-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; padding-bottom: 14px; margin-bottom: 16px; border-bottom: 1px solid var(--border-divider); }
.enrichment-heading h3 { margin: 0; font-size: 14px; color: var(--text-primary); }
.capability-enrichment nav, .enrichment-groups { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.capability-enrichment a { color: var(--brand-active); text-decoration: none; cursor: pointer; }
.capability-enrichment a:hover { text-decoration: underline; }
.capability-enrichment a:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 3px; }
.enrichment-provenance { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.enrichment-groups { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border-divider); font-size: 12px; color: var(--text-secondary); }
.enrichment-sensitive { list-style: none; padding: 0; }
.enrichment-sensitive li { padding: 10px 0; }
.enrichment-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.enrichment-actions .el-button + .el-button { margin-left: 0; }
.enrichment-editor, .enrichment-generation { margin: 16px 0; }
.enrichment-generation p { color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
</style>
