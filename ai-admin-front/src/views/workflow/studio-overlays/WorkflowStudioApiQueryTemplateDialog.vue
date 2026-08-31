<template>
  <el-dialog v-model="open" title="从项目接口生成查询流程" width="900px" class="api-query-template-dialog">
    <div class="api-query-template-body">
      <el-alert
        type="info"
        :closable="false"
        title="选择一个已纳入能力目录的项目接口，系统会生成交互收集、页面查询动作、执行调用和结果展示节点。"
      />
      <div class="api-query-template-toolbar">
        <el-input
          v-model="filters.keyword"
          :prefix-icon="Search"
          clearable
          placeholder="搜索接口名称、路径、描述"
          @keyup.enter="$emit('reload')"
        />
        <el-select v-model="filters.toolLinkStatus" clearable placeholder="能力纳管状态">
          <el-option label="已纳管" value="LINKED" />
          <el-option label="未纳管" value="NOT_LINKED" />
          <el-option label="能力执行定义缺失" value="GLOBAL_MISSING" />
        </el-select>
        <el-input v-model="actionKey" placeholder="page.search.applyFilters" />
        <el-button type="primary" @click="$emit('reload')">查询</el-button>
      </div>
      <el-table
        v-loading="loading"
        :data="tools"
        row-key="scanToolId"
        :row-class-name="rowClassName"
        height="420"
        stripe
        empty-text="暂无项目接口"
      >
        <el-table-column label="接口" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-template-cell">
              <strong>{{ row.name }}</strong>
              <span>{{ row.httpMethod || '-' }} {{ row.endpointPath || row.sourceLocation || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="项目 / 模块" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-template-cell">
              <strong>{{ row.projectCode || projectCode || '-' }}</strong>
              <span>{{ row.moduleDisplayName || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="参数" width="80" align="center">
          <template #default="{ row }">{{ row.parameterCount || row.parameters?.length || 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="selectable(row) ? 'success' : 'info'" effect="plain">
              {{ statusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              text
              :disabled="!available || !selectable(row)"
              @click="$emit('generate', row)"
            >
              生成流程
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="api-query-template-footer">
        <span>页面动作 actionKey 由业务前端 SDK 启动时用项目 key/secret 自动上报，例如班组档案页可注册为 teamArchive.search。</span>
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.pageSize"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="$emit('page-change')"
        />
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import type { ProjectToolInfo } from '@/types/scanProject'

interface ApiQueryTemplateFilters {
  keyword: string
  toolLinkStatus: string
  page: number
  pageSize: number
}

defineProps<{
  loading: boolean
  tools: ProjectToolInfo[]
  total: number
  filters: ApiQueryTemplateFilters
  available: boolean
  projectCode?: string | null
  rowClassName: (data: { row: ProjectToolInfo }) => string
  selectable: (tool: ProjectToolInfo) => boolean
  statusLabel: (tool: ProjectToolInfo) => string
}>()

defineEmits<{
  (event: 'reload'): void
  (event: 'page-change'): void
  (event: 'generate', tool: ProjectToolInfo): void
}>()

const open = defineModel<boolean>('open', { required: true })
const actionKey = defineModel<string>('actionKey', { required: true })
</script>

<style scoped lang="scss">
.api-query-template-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 160px 180px auto;
  gap: 10px;
  margin: 12px 0;
}

.api-template-cell {
  display: grid;
  gap: 2px;
}

.api-template-cell span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.api-query-template-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
