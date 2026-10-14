import { onBeforeUnmount, ref, watch } from 'vue'
import { getSubjectSemanticDoc } from '@/api/semanticDoc'
import type { SemanticDoc, SemanticDocIndex } from '@/types/semanticDoc'

/** One visible reader owns one body request. Selection and refresh invalidate old responses. */
export function useSelectedSemanticDocument(projectId: () => number, metadata: () => SemanticDocIndex | null | undefined,
  indexLoading: () => boolean = () => false) {
  const document = ref<SemanticDoc | null>(null)
  const loading = ref(false)
  const error = ref('')
  let sequence = 0, disposed = false
  async function refresh() {
    const expected = ++sequence
    document.value = null; error.value = ''; loading.value = false
    const index = metadata(), owner = projectId()
    if (disposed || indexLoading() || !index) return
    loading.value = true
    try {
      const { data } = await getSubjectSemanticDoc(owner, index.subjectType, index.subjectId)
      if (disposed || expected !== sequence || owner !== projectId()) return
      if (!data || data.projectId !== owner || data.subjectType !== index.subjectType || data.subjectId !== index.subjectId
        || typeof data.subjectKey !== 'string' || (data.contentMd != null && typeof data.contentMd !== 'string')) {
        throw new Error('Invalid semantic document owner')
      }
      document.value = data
    } catch {
      if (!disposed && expected === sequence) error.value = '说明正文读取失败，请重新读取；当前无法确认正文内容。'
    } finally { if (!disposed && expected === sequence) loading.value = false }
  }
  watch(() => [projectId(), metadata(), indexLoading()], () => { void refresh() }, { immediate: true })
  onBeforeUnmount(() => { disposed = true; sequence++ })
  return { document, loading, error, refresh }
}
