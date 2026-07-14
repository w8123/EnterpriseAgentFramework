import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Agent } from '@/types/agent'
import { listAgents } from '@/api/workflow'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<Agent[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      const { data } = await listAgents()
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
