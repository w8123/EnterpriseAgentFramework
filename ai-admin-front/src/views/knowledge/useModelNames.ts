import { ref } from 'vue'
import { getModelInstances } from '@/api/model'
import type { ModelInstance } from '@/types/model'

/** Resolves model instance ids to readable names; unknown ids fall back to the id itself. */
export function useModelNames() {
  const instances = ref<ModelInstance[]>([])

  async function loadModelNames() {
    try {
      const { data } = await getModelInstances({ includeArchived: true })
      instances.value = Array.isArray(data) ? data : []
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
