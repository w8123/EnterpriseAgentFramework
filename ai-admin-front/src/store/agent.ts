import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AgentDefinition } from '@/types/agent'
import { getAgentList } from '@/api/agent'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<AgentDefinition[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      const { data } = await getAgentList()
      agents.value = Array.isArray(data) ? data : []
    } catch {
      agents.value = []
    } finally {
      loading.value = false
    }
  }

  function findById(id: string) {
    return agents.value.find((a) => a.id === id)
  }

  return { agents, loading, fetchList, findById }
})
