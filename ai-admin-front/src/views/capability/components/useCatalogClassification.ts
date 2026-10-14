import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { platformSessionId } from '@/auth/platformSession'
import { getCatalogClassificationIndex } from '@/api/semanticDoc'
import type { CatalogClassificationIndex, CatalogClassificationSubjectType } from '@/types/semanticDoc'
import { validateClassificationIndex } from './catalogClassification'

export function useCatalogClassification(projectId: Readonly<Ref<number | null>>, ready: Readonly<Ref<boolean>>,
  subjectType: CatalogClassificationSubjectType, session: Readonly<Ref<string | number>> = platformSessionId) {
  const index = ref<CatalogClassificationIndex | null>(null), loading = ref(false), error = ref('')
  let sequence = 0, disposed = false
  async function refresh() {
    const current = ++sequence, id = projectId.value
    index.value = null; error.value = ''; loading.value = false
    if (disposed || !ready.value || !id) return
    loading.value = true
    try {
      const { data } = await getCatalogClassificationIndex(id, subjectType)
      if (disposed || current !== sequence) return
      if (!validateClassificationIndex(data, id, subjectType)) throw new Error('Invalid classification owner index')
      index.value = data
    } catch {
      if (!disposed && current === sequence) error.value = '业务分类读取失败；能力仍可查看，请重试分类。'
    } finally { if (current === sequence) loading.value = false }
  }
  watch([projectId, ready, session], () => { void refresh() }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; sequence += 1 })
  return { index, loading, error, refresh }
}
