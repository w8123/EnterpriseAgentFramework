import { ref } from 'vue'
import { getModelInstances } from '@/api/model'
import { canLoadModelCatalog } from '@/auth/modelCatalogAccess'
import type { ModelInstance } from '@/types/model'

/** Resolves model instance ids to readable names; unknown ids fall back to the id itself. */
export function useModelNames() {
  const instances = ref<ModelInstance[]>([])

  async function loadModelNames() {
    if (!canLoadModelCatalog()) return
    try {
      const { data } = await getModelInstances({ includeArchived: true })
      // The model API wraps the list as { code, data }.
      const list: unknown = Array.isArray(data) ? data : (data as { data?: unknown } | null)?.data
      instances.value = Array.isArray(list) ? list as ModelInstance[] : []
    } catch {
      instances.value = []
    }
  }

  function modelName(id?: string | null): string {
    if (!id) return '未配置'
    const found = instances.value.find((item) => item.id === id)
    return found ? `${found.name}（${found.modelName}）` : id
  }

  return { loadModelNames, modelName }
}
