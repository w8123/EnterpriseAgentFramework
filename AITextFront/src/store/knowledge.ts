import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { KnowledgeBase } from '@/types/knowledge'
import { getKnowledgeList } from '@/api/knowledge'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const knowledgeList = ref<KnowledgeBase[]>([])
  const currentKnowledge = ref<KnowledgeBase | null>(null)
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      const { data } = await getKnowledgeList()
      knowledgeList.value = data.data || []
    } finally {
      loading.value = false
    }
  }

  function setCurrent(kb: KnowledgeBase | null) {
    currentKnowledge.value = kb
  }

  function findByCode(code: string): KnowledgeBase | undefined {
    return knowledgeList.value.find((kb) => kb.code === code)
  }

  return {
    knowledgeList,
    currentKnowledge,
    loading,
    fetchList,
    setCurrent,
    findByCode,
  }
})
