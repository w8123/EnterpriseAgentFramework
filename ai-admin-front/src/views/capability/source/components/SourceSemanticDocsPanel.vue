<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close, Document, Search } from '@element-plus/icons-vue'
import type { SemanticDoc, SemanticDocIndex, SemanticSubjectType } from '@/types/semanticDoc'
import { useSelectedSemanticDocument } from '../composables/useSelectedSemanticDocument'
import CapabilitySemanticDocument from '../../components/CapabilitySemanticDocument.vue'
import { BUSINESS_METHOD_CATALOG_PATH, httpApiDetailLocation } from '../../businessCapabilityRoutes'
import { sourceGroupMembersLocation } from '../sourceOwnerRoutes'

const props = withDefaults(defineProps<{
  docs: SemanticDocIndex[]
  projectId: number
  projectDoc?: SemanticDocIndex | null
  loading?: boolean
  projectGenerating?: boolean
  generatingKeys?: string[]
  canWrite?: boolean
}>(), { projectDoc: null, loading: false, projectGenerating: false, generatingKeys: () => [], canWrite: true })
const emit = defineEmits<{ edit: [doc: SemanticDoc]; generate: [doc: SemanticDoc]; generateProject: [] }>()
const labels: Record<SemanticSubjectType, string> = { PROJECT: '项目', GROUP: '业务分类', BUSINESS_METHOD: 'Java 业务方法',
  HTTP_API: 'HTTP API', SOURCE_OBSERVATION: '来源观察' }
const subjectTypes = Object.keys(labels) as SemanticSubjectType[]
const statusLabels = { draft: '语义草稿', generated: 'AI 补充', edited: '人工编辑' }
interface DirectoryEntry { key: string; subjectType: SemanticSubjectType; subjectId: number; title: string; doc: SemanticDocIndex | null }
const route = useRoute()
const router = useRouter()
const projectKey = () => `PROJECT:${props.projectId}`
const selectedKey = ref(routeSelection() || projectKey())
const searchInput = ref<HTMLInputElement>()
const searchDraft = ref('')
const searchKeyword = ref('')
const composing = ref(false)
const documentMap = computed(() => {
  const values = new Map<string, SemanticDocIndex>()
  for (const doc of [props.projectDoc, ...props.docs]) {
    if (!doc || doc.projectId !== props.projectId || !subjectTypes.includes(doc.subjectType)
      || !Number.isSafeInteger(doc.subjectId) || doc.subjectId <= 0 || (doc.subjectType === 'PROJECT' && doc.subjectId !== props.projectId)) continue
    values.set(`${doc.subjectType}:${doc.subjectId}`, doc)
  }
  return values
})
const entries = computed<DirectoryEntry[]>(() => {
  const project = documentMap.value.get(projectKey()) || null
  return [{ key: projectKey(), subjectType: 'PROJECT', subjectId: props.projectId, title: project?.title || '项目业务说明', doc: project },
    ...[...documentMap.value.entries()].filter(([, doc]) => doc.subjectType !== 'PROJECT').map(([key, doc]) => ({
      key, subjectType: doc.subjectType, subjectId: doc.subjectId, title: doc.title || doc.subjectKey, doc,
    }))]
})
const directoryGroups = computed(() => subjectTypes.map(subjectType => ({ subjectType, label: labels[subjectType],
  entries: entries.value.filter(entry => entry.subjectType === subjectType && (!searchKeyword.value
    || [entry.title, entry.doc?.subjectKey, labels[subjectType]].some(value => value?.toLowerCase().includes(searchKeyword.value.toLowerCase())))),
})).filter(group => group.entries.length > 0))
const resolvedKey = computed(() => {
  if (!selectedKey.value.startsWith('SOURCE_OBSERVATION:')) return selectedKey.value
  const sourceId = Number(selectedKey.value.slice('SOURCE_OBSERVATION:'.length))
  const owners = [...documentMap.value.entries()].filter(([, doc]) =>
    (doc.subjectType === 'BUSINESS_METHOD' || doc.subjectType === 'HTTP_API') && doc.sourceObservationIds?.includes(sourceId))
  return owners.length === 1 ? owners[0][0] : selectedKey.value
})
const selectedEntry = computed(() => entries.value.find(entry => entry.key === resolvedKey.value) || null)
const selectedIndex = computed(() => documentMap.value.get(resolvedKey.value) || null)
const { document: selectedDoc, loading: documentLoading, error: documentError, refresh: refreshDocument } = useSelectedSemanticDocument(
  () => props.projectId, () => selectedIndex.value, () => props.loading)
const isProject = computed(() => selectedKey.value === projectKey())
const selectedType = computed(() => {
  const type = resolvedKey.value.split(':')[0] as SemanticSubjectType
  return subjectTypes.includes(type) ? type : null
})
const selectedTitle = computed(() => selectedEntry.value?.title || `${selectedType.value ? labels[selectedType.value] : '所选业务'}说明`)
const generating = computed(() => isProject.value ? props.projectGenerating : props.generatingKeys.includes(resolvedKey.value))
const canGenerate = computed(() => props.canWrite && !props.loading && !documentLoading.value && !documentError.value
  && !generating.value && (isProject.value || !!selectedDoc.value))

function routeSelection() {
  const type = route.query.subjectType
  const id = route.query.subjectId
  if (route.query.projectId != null && route.query.projectId !== String(props.projectId)) return null
  if (type == null && id == null) return null
  if (typeof type !== 'string' || !subjectTypes.includes(type as SemanticSubjectType) || typeof id !== 'string' || !/^\d+$/.test(id)) return 'UNAVAILABLE'
  const subjectId = Number(id)
  if (!Number.isSafeInteger(subjectId) || subjectId <= 0 || (type === 'PROJECT' && subjectId !== props.projectId)) return 'UNAVAILABLE'
  return `${type}:${subjectId}`
}
watch(() => [route.query.subjectType, route.query.subjectId, route.query.projectId], () => {
  selectedKey.value = routeSelection() || projectKey()
}, { immediate: true })
watch(() => props.projectId, () => {
  selectedKey.value = projectKey()
  searchDraft.value = ''; searchKeyword.value = ''; composing.value = false
}, { flush: 'sync' })
function selectEntry(entry: DirectoryEntry) {
  selectedKey.value = entry.key
  void router.replace({ path: route.path, hash: route.hash,
    query: { ...route.query, subjectType: entry.subjectType, subjectId: String(entry.subjectId) } })
}
function updateSearch(event: Event) {
  searchDraft.value = (event.target as HTMLInputElement).value
  if (!composing.value && !(event as InputEvent).isComposing) searchKeyword.value = searchDraft.value.trim()
}
function finishSearch(event: CompositionEvent) { composing.value = false; updateSearch(event) }
function clearSearch() { searchDraft.value = ''; searchKeyword.value = ''; composing.value = false; searchInput.value?.focus() }
function editSelected() {
  if (props.canWrite && !props.loading && !generating.value && selectedDoc.value) emit('edit', selectedDoc.value)
}
function generateSelected() {
  if (!canGenerate.value) return
  if (isProject.value) emit('generateProject')
  else if (selectedDoc.value) emit('generate', selectedDoc.value)
}
function assetLocation(doc: SemanticDocIndex) {
  const query = { ...route.query, projectId: String(props.projectId), subjectType: doc.subjectType, subjectId: String(doc.subjectId), subjectKey: doc.subjectKey }
  if (doc.subjectType === 'GROUP') {
    return sourceGroupMembersLocation(props.projectId, route.params.category === 'http-apis' ? 'http-apis' : 'java-methods',
      { id: doc.subjectId, projectId: doc.projectId }, query)
  }
  return doc.subjectType === 'HTTP_API' ? httpApiDetailLocation(doc.subjectId, query)
    : doc.subjectType === 'BUSINESS_METHOD' ? { path: BUSINESS_METHOD_CATALOG_PATH, query } : null
}
</script>

<template>
  <section class="semantic-workbench glass-surface-panel" aria-label="业务说明">
    <header class="semantic-workbench__toolbar"><h2>业务说明</h2><div class="semantic-workbench__operations"><slot name="toolbar" /></div></header>
    <p class="semantic-workbench__hint">AI 与人工补充用于理解业务，保留来源、模型和契约修订；不会覆盖业务系统声明或赋予调用权限。</p>
    <div class="semantic-workbench__layout">
      <aside class="semantic-directory" aria-label="说明目录">
        <div class="semantic-directory__search glass-surface-control"><el-icon aria-hidden="true"><Search /></el-icon>
          <input ref="searchInput" type="search" :value="searchDraft" aria-label="搜索业务说明" placeholder="搜索说明名称或标识"
            @input="updateSearch" @compositionstart="composing = true" @compositionend="finishSearch" />
          <button v-if="searchDraft" type="button" class="semantic-search-clear" aria-label="清除说明搜索" @click="clearSearch"><el-icon aria-hidden="true"><Close /></el-icon></button>
        </div>
        <div class="semantic-directory__list">
          <section v-for="group in directoryGroups" :key="group.subjectType" class="semantic-directory__group">
            <h3>{{ group.label }}<span>{{ group.entries.length }}</span></h3>
            <ul><li v-for="entry in group.entries" :key="entry.key">
              <button type="button" class="semantic-directory-item" :aria-label="`阅读${entry.title}`" :aria-current="resolvedKey === entry.key ? 'true' : undefined"
                :title="entry.title" :disabled="loading" @click="selectEntry(entry)">
                <el-icon aria-hidden="true"><Document /></el-icon><span class="semantic-directory-item__copy"><strong>{{ entry.title }}</strong><small>{{ entry.doc ? statusLabels[entry.doc.status] : '未补充' }}</small></span>
              </button>
            </li></ul>
          </section>
          <p v-if="!directoryGroups.length" class="semantic-directory__empty" role="status">没有匹配的说明，试试其他关键词。</p>
        </div>
      </aside>
      <section class="semantic-reader" aria-label="当前业务说明" :aria-busy="loading || documentLoading">
        <header class="semantic-reader__header"><div class="semantic-reader__heading"><h3>{{ selectedTitle }}</h3>
          <p class="semantic-doc-meta">{{ selectedType ? labels[selectedType] : '所选说明' }}<template v-if="selectedIndex"> · {{ statusLabels[selectedIndex.status] }}<span v-if="selectedIndex.modelName"> · {{ selectedIndex.modelName }}</span><span v-if="selectedIndex.basisContractHash" :title="selectedIndex.basisContractHash"> · 契约 {{ selectedIndex.basisContractHash.slice(0, 12) }}</span></template><span v-else> · 未补充</span></p>
        </div><div class="semantic-doc-actions">
          <router-link v-if="selectedIndex && assetLocation(selectedIndex)" :to="assetLocation(selectedIndex)!">{{ selectedIndex.subjectType === 'GROUP' ? '查看分类成员' : '查看所属资产' }}</router-link>
          <el-button :disabled="!canWrite || loading || documentLoading || generating || !selectedDoc" @click="editSelected">编辑说明</el-button>
          <el-button type="primary" :loading="generating" :disabled="!canGenerate" @click="generateSelected">{{ isProject && !selectedDoc ? '生成项目说明' : '重新生成' }}</el-button>
        </div></header>
        <p v-if="!canWrite" class="semantic-reader__permission" role="status">当前账号仅可阅读说明，无法编辑或生成。</p>
        <div class="semantic-reader__body" tabindex="0" role="region" aria-label="文档阅读内容，可滚动">
          <p v-if="loading || documentLoading" class="semantic-reader__state" role="status">正在读取业务说明…</p>
          <el-alert v-else-if="documentError" :title="documentError" type="error" :closable="false" show-icon><template #default><el-button @click="refreshDocument">重新读取正文</el-button></template></el-alert>
          <CapabilitySemanticDocument v-else-if="selectedDoc?.contentMd?.trim()" :content-md="selectedDoc.contentMd" />
          <div v-else class="semantic-reader__state" role="status"><el-icon aria-hidden="true"><Document /></el-icon>
            <p>{{ isProject && !selectedDoc ? '当前项目尚未补充业务说明' : selectedDoc ? '当前说明尚未填写正文，可编辑或重新生成。' : '这份说明当前不可用，请从目录选择或刷新。' }}</p>
            <span v-if="isProject && !selectedDoc">可生成项目说明，或通过批量生成补充资产与分类说明。</span>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped lang="scss">
.semantic-workbench { min-width: 0; overflow: hidden; border-radius: var(--radius-lg); }
.semantic-workbench__toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: var(--density-compact-panel-padding); }
.semantic-workbench__toolbar h2 { margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 600; }
.semantic-workbench__operations { min-width: 0; }
.semantic-workbench__hint { margin: 0; padding: 0 var(--density-compact-panel-padding) 12px; color: var(--text-muted); font-size: 12px; line-height: 1.6; }
.semantic-workbench__layout { display: grid; grid-template-columns: minmax(220px, 260px) minmax(0, 1fr); border-top: 1px solid var(--border-divider); }
.semantic-directory { min-width: 0; padding: var(--density-compact-panel-padding); border-right: 1px solid var(--border-divider); background: rgb(var(--brand-primary-rgb) / 0.025); }
.semantic-directory__search { display: flex; align-items: center; gap: 8px; box-sizing: border-box; height: var(--project-list-control-height, var(--density-compact-control-height)); padding-inline: 10px; border-radius: var(--radius-md); color: var(--text-muted); }
.semantic-directory__search:focus-within { border-color: var(--border-focus); outline: 2px solid var(--border-focus); outline-offset: 2px; }
.semantic-directory__search input { width: 100%; min-width: 0; padding: 0; border: 0; outline: 0; background: transparent; color: var(--text-primary); font: inherit; font-size: 12px; }
.semantic-directory__search input::placeholder { color: var(--text-muted); }
.semantic-directory__search input::-webkit-search-cancel-button { appearance: none; }
.semantic-search-clear { display: grid; flex-shrink: 0; place-items: center; width: 24px; height: 24px; padding: 0; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--text-secondary); cursor: pointer; }
.semantic-directory__list { max-height: min(65vh, 680px); overflow-y: auto; margin-top: 12px; }
.semantic-directory__group + .semantic-directory__group { margin-top: 14px; }
.semantic-directory__group h3 { display: flex; justify-content: space-between; gap: 8px; margin: 0 0 6px; color: var(--text-muted); font-size: 11px; font-weight: 500; }
.semantic-directory__group ul { margin: 0; padding: 0; list-style: none; }
.semantic-directory-item { display: flex; align-items: flex-start; gap: 8px; width: 100%; padding: 9px 10px; border: 1px solid transparent; border-radius: var(--radius-md); background: transparent; color: var(--text-secondary); text-align: left; cursor: pointer; font: inherit; }
.semantic-directory-item > .el-icon { flex-shrink: 0; margin-top: 2px; color: var(--text-muted); }
.semantic-directory-item:hover { background: var(--surface-glass-control); }
.semantic-directory-item[aria-current="true"] { border-color: var(--border-subtle); background: var(--surface-glass-selected); color: var(--brand-active); box-shadow: var(--inner-highlight); }
.semantic-directory-item:disabled { cursor: wait; }
.semantic-directory-item:focus-visible, .semantic-search-clear:focus-visible { outline: 2px solid var(--border-focus); outline-offset: -2px; }
.semantic-directory-item__copy { min-width: 0; }
.semantic-directory-item strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; line-height: 1.5; }
.semantic-directory-item small { display: block; margin-top: 3px; color: var(--text-muted); font-size: 11px; line-height: 1.5; }
.semantic-directory__empty { color: var(--text-muted); font-size: 12px; line-height: 1.7; }
.semantic-reader { min-width: 0; padding: var(--density-compact-panel-padding); }
.semantic-reader__header { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; margin-bottom: 18px; }
.semantic-reader__heading { min-width: 0; flex: 1 1 240px; }
.semantic-reader__heading h3 { margin: 0; color: var(--text-primary); font-size: 16px; font-weight: 600; line-height: 1.5; overflow-wrap: anywhere; }
.semantic-doc-meta { margin: 5px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.semantic-doc-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.semantic-doc-actions :deep(.el-button) { margin: 0; height: var(--project-list-control-height, var(--density-compact-control-height)); border-radius: var(--radius-md); }
.semantic-doc-actions a { color: var(--brand-active); cursor: pointer; font-size: 12px; text-decoration: none; }
.semantic-doc-actions a:hover { text-decoration: underline; }
.semantic-doc-actions a:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.semantic-reader__permission { margin: 0 0 12px; color: var(--text-muted); font-size: 12px; }
.semantic-reader__body { min-width: 0; max-height: min(62vh, 640px); overflow-y: auto; }
.semantic-reader__body:focus-visible { outline: 2px solid var(--border-focus); outline-offset: -2px; }
.semantic-reader__state { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; min-height: 140px; padding: 20px 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.semantic-reader__state > .el-icon { color: var(--text-muted); font-size: 24px; }
.semantic-reader__state p { margin: 0; color: var(--text-primary); font-weight: 500; }
.semantic-reader__state span { color: var(--text-muted); font-size: 12px; }
@media (max-width: 960px) {
  .semantic-workbench__layout { grid-template-columns: minmax(0, 1fr); }
  .semantic-directory { border-right: 0; border-bottom: 1px solid var(--border-divider); }
  .semantic-directory__list, .semantic-reader__body { max-height: none; overflow: visible; }
}
@media (max-width: 640px) {
  .semantic-workbench__operations { width: 100%; }
  .semantic-reader__heading { flex-basis: 100%; }
  .semantic-doc-actions { width: 100%; }
}
</style>
