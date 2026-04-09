import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { BizIndex } from '@/types/bizIndex'
import { getBizIndexList } from '@/api/bizIndex'

export const useBizIndexStore = defineStore('bizIndex', () => {
  const bizIndexList = ref<BizIndex[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      const { data } = await getBizIndexList()
      bizIndexList.value = data.data || []
    } finally {
      loading.value = false
    }
  }

  function findByCode(code: string): BizIndex | undefined {
    return bizIndexList.value.find((idx) => idx.indexCode === code)
  }

  return {
    bizIndexList,
    loading,
    fetchList,
    findByCode,
  }
})
