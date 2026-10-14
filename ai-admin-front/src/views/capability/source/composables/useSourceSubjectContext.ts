import { computed, onBeforeUnmount, ref, watch, type ComputedRef } from 'vue'
import type { LocationQuery } from 'vue-router'
import { getCapabilityEnrichment } from '@/api/semanticDoc'

export function useSourceSubjectContext(deps: { projectId: ComputedRef<number>; workspace: () => string;
  query: () => LocationQuery; canRead: () => boolean; contextKey: () => string }) {
  const active = computed(() => deps.workspace() === 'sources' && (deps.query().subjectType != null || deps.query().subjectId != null))
  const observationIds = ref<number[]>([])
  const title = ref('')
  const loading = ref(false)
  const error = ref('')
  let sequence = 0
  let disposed = false
  async function refresh() {
    const expected = ++sequence
    observationIds.value = []; title.value = ''; error.value = ''; loading.value = false
    if (!active.value || disposed) return
    if (!deps.canRead()) { error.value = '当前账号无权读取对应能力的来源。'; return }
    const query = deps.query(), type = query.subjectType, rawId = query.subjectId
    if ((query.projectId != null && query.projectId !== String(deps.projectId.value))
      || typeof rawId !== 'string' || !/^[1-9]\d*$/.test(rawId) || !Number.isSafeInteger(Number(rawId))
      || (type !== 'SOURCE_OBSERVATION' && type !== 'BUSINESS_METHOD' && type !== 'HTTP_API')) {
      error.value = '来源上下文身份不完整，请返回能力目录重新打开。'; return
    }
    if (type === 'SOURCE_OBSERVATION') { observationIds.value = [Number(rawId)]; title.value = '指定来源'; return }
    loading.value = true
    try {
      const { data } = await getCapabilityEnrichment(deps.projectId.value, type, Number(rawId))
      if (disposed || expected !== sequence) return
      if (data.projectId !== deps.projectId.value || data.subjectType !== type || data.subjectId !== Number(rawId)
        || !Array.isArray(data.sourceObservations) || data.sourceObservations.some(source => !Number.isSafeInteger(source.id) || source.id <= 0)) throw new Error('Invalid source owner')
      observationIds.value = [...new Set(data.sourceObservations.map(source => source.id))]
      title.value = data.title || data.subjectKey
    } catch {
      if (!disposed && expected === sequence) error.value = '对应能力的来源读取失败，请重试；当前不能确认来源关联。'
    } finally { if (!disposed && expected === sequence) loading.value = false }
  }
  watch(() => [deps.projectId.value, deps.workspace(), deps.query().projectId, deps.query().subjectType,
    deps.query().subjectId, deps.canRead(), deps.contextKey()], refresh, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; sequence++ })
  return { active, observationIds, title, loading, error, refresh }
}
