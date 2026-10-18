<template>
  <el-card class="detail-card workbench-list-surface" shadow="never">
    <template #header>
      <div class="table-header">
        <div class="section-title">
          <span class="title-mark" />
          <span>业务索引（{{ indexes.length }}）</span>
        </div>
        <el-button link type="primary" @click="router.push('/biz-index')">管理业务索引</el-button>
      </div>
    </template>
    <p class="biz-index-hint">
      业务索引由业务系统通过 SDK 按项目同步（如商品、订单的可检索文本），用于在 Workflow 结果里回源业务记录。
      目前 Agent 还不能直接检索业务索引；制度、手册等文档请放到「知识库」。
    </p>
    <el-table v-loading="loading" :data="indexes" row-key="indexCode" empty-text="这个项目还没有同步业务索引">
      <el-table-column label="索引" min-width="180">
        <template #default="{ row }">
          <el-button type="primary" link @click="router.push(`/biz-index/${row.indexCode}`)">{{ row.indexName || row.indexCode }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="indexCode" label="编码" min-width="140" />
      <el-table-column prop="recordCount" label="记录" width="90" align="center" />
      <el-table-column prop="status" label="状态" width="100" align="center" />
      <el-table-column prop="updateTime" label="最近更新" width="180" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getBizIndexList } from '@/api/bizIndex'
import type { BizIndex } from '@/types/bizIndex'

const props = defineProps<{
  projectCode: string
}>()

const router = useRouter()
const indexes = ref<BizIndex[]>([])
const loading = ref(false)

async function load() {
  if (!props.projectCode) return
  loading.value = true
  try {
    const { data } = await getBizIndexList({ errorFeedback: 'local' })
    indexes.value = (Array.isArray(data) ? data : [])
      .filter((index) => (index.projectCode || '').toLowerCase() === props.projectCode.toLowerCase())
  } catch {
    indexes.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.projectCode, load)
onMounted(load)
</script>

<style scoped lang="scss">
.biz-index-hint {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
