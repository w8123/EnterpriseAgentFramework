<template>
  <section class="project-biz-index-card" aria-label="业务索引">
    <div class="biz-index-header">
      <div class="biz-index-heading">
        <h2 class="biz-index-title">
          业务索引
          <span class="biz-index-count">{{ indexes.length }}</span>
        </h2>
        <p class="biz-index-hint">
          通过 SDK 同步业务索引，用于 Workflow 结果回源。
        </p>
      </div>
      <LiquidGlassButton source="wallpaper" aria-label="管理业务索引" @click="router.push('/biz-index')">管理</LiquidGlassButton>
    </div>
    <LiquidGlassTable :show-pagination="false" :loading="loading">
      <el-table :data="indexes" row-key="indexCode" empty-text="这个项目还没有同步业务索引">
        <el-table-column label="索引" min-width="180">
          <template #default="{ row }">
            <el-button type="primary" link @click="router.push(`/biz-index/${row.indexCode}`)">{{ row.indexName || row.indexCode }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="indexCode" label="编码" min-width="140" />
        <el-table-column prop="recordCount" label="记录" width="90" align="center" />
        <el-table-column prop="status" label="状态" width="100" align="center" />
        <el-table-column prop="updateTime" label="最近更新" width="180" :formatter="formatTableDateTime" />
      </el-table>
    </LiquidGlassTable>
  </section>
</template>

<script setup lang="ts">
import { formatTableDateTime } from '@/utils/dateTime'
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getBizIndexList } from '@/api/bizIndex'
import { LiquidGlassButton, LiquidGlassTable } from '@/components/common/liquid-glass'
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
.project-biz-index-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.biz-index-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--layout-control-gap);
}

.biz-index-heading {
  flex: 1 1 320px;
  min-width: 0;
}

.biz-index-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--text-on-wallpaper);
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
}

.biz-index-count {
  min-width: 22px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.biz-index-hint {
  max-width: 760px;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-on-wallpaper-secondary);
}
</style>
