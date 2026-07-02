<script setup lang="ts">
import { scanSensitiveTypeLabel } from '@/utils/scanProjectToolExport'
import type { ProjectToolInfo } from '@/types/scanProject'
import type { SemanticDoc } from '@/types/semanticDoc'
import type { ToolParameter } from '@/types/tool'
import type { ToolModuleGroup } from '@/views/scan/composables/useScanProjectSummary'

interface ParameterRow extends ToolParameter {
  _key: string
  children?: ParameterRow[]
}

type ToolLinkTagType = 'success' | 'warning' | 'danger' | 'info'
type ToolFlagField = 'agentVisible' | 'lightweightEnabled'

defineProps<{
  loading: boolean
  tools: ProjectToolInfo[]
  visibleToolModuleGroups: ToolModuleGroup[]
  hiddenModuleGroupCount: number
  toolDocMap: Record<number, SemanticDoc>
  sensitiveScanStarting: boolean
  sensitiveTaskPolling: boolean
  exportScanToolsExcelLoading: boolean
  batchModulePromoteLoading: Record<string, boolean>
  toolDetailLoading: Record<number, boolean>
  rescanSourceLoading: Record<number, boolean>
  promoteLoading: Record<number, boolean>
  pushToGlobalLoading: Record<number, boolean>
  unpromoteLoading: Record<number, boolean>
  parameterRows: (parameters: ToolParameter[] | null | undefined, prefix?: string) => ParameterRow[]
  renderMd: (content: string | null | undefined) => string
  scanToolRowClassName: (data: { row: ProjectToolInfo }) => string
  toolParameterCount: (row: ProjectToolInfo) => number
  toolDocSummary: (tool: ProjectToolInfo) => string
  sensitiveCellTooltip: (row: ProjectToolInfo) => string
  toolLinkLabel: (row: ProjectToolInfo) => string
  toolLinkTagType: (row: ProjectToolInfo) => ToolLinkTagType
}>()

const interfaceCollapseActive = defineModel<string[]>('interfaceCollapseActive', { required: true })

const emit = defineEmits<{
  startSensitiveDataScan: []
  exportExcel: []
  batchToggle: [enabled: boolean]
  promoteModuleToGlobal: [group: ToolModuleGroup]
  toolExpandChange: [row: ProjectToolInfo, expanded: boolean]
  enabledChange: [row: ProjectToolInfo, enabled: boolean]
  flagChange: [row: ProjectToolInfo, field: ToolFlagField, value: boolean]
  openDiff: [row: ProjectToolInfo]
  openEdit: [row: ProjectToolInfo]
  rescanFromSource: [row: ProjectToolInfo]
  openTest: [row: ProjectToolInfo]
  promoteToGlobal: [row: ProjectToolInfo]
  pushToGlobal: [row: ProjectToolInfo]
  unpromoteFromGlobal: [row: ProjectToolInfo]
  regenerateTool: [row: ProjectToolInfo]
  openEditDoc: [doc: SemanticDoc]
  showMoreGroups: []
}>()

function onToolExpandChange(row: ProjectToolInfo, expandedRows: ProjectToolInfo[]) {
  const expanded = expandedRows.some((item) => item.scanToolId === row.scanToolId)
  emit('toolExpandChange', row, expanded)
}
</script>

<template>
  <el-collapse-item class="scan-detail-top-item merged-tools-card" name="tools">
    <template #title>
      <div class="tools-header">
        <span>API 接口目录与 AI 语义</span>
        <div class="tools-actions" @click.stop>
          <el-button
            size="small"
            type="warning"
            plain
            :loading="sensitiveScanStarting || sensitiveTaskPolling"
            @click="emit('startSensitiveDataScan')"
          >
            扫描敏感数据
          </el-button>
          <el-button
            size="small"
            :disabled="!tools.length"
            :loading="exportScanToolsExcelLoading"
            @click="emit('exportExcel')"
          >
            导出 EXCEL
          </el-button>
          <el-button size="small" @click="emit('batchToggle', false)">全部禁用</el-button>
          <el-button size="small" type="primary" @click="emit('batchToggle', true)">全部启用</el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="tools-table-wrap">
      <el-empty v-if="!loading && tools.length === 0" description="暂无接口记录：离线项目请先扫描；SDK 接入项目在业务系统同步能力后将出现在此" />
      <el-collapse
        v-else-if="tools.length > 0"
        v-model="interfaceCollapseActive"
        class="tool-groups-collapse"
      >
        <el-collapse-item v-for="g in visibleToolModuleGroups" :key="g.key" :name="g.key">
          <template #title>
            <div class="module-collapse-title">
              <div class="module-collapse-title__text">
                <span class="collapse-module-title">{{ g.label }}</span>
                <el-tag size="small" type="info" class="module-tool-count">{{ g.tools.length }} 个接口</el-tag>
              </div>
              <el-button
                v-if="g.tools.length > 0 && g.tools.some((t) => !t.globalToolDefinitionId && !t.removedFromSource)"
                type="primary"
                size="small"
                :loading="batchModulePromoteLoading[g.key] ?? false"
                @click.stop="emit('promoteModuleToGlobal', g)"
              >
                添加为 Tool
              </el-button>
            </div>
          </template>
          <el-table
            :data="g.tools"
            stripe
            class="nested-tools-table merged-interface-table"
            :row-class-name="scanToolRowClassName"
            @expand-change="onToolExpandChange"
          >
            <el-table-column type="expand" width="44">
              <template #default="{ row }">
                <div class="expand-content expand-merged" v-loading="toolDetailLoading[row.scanToolId] ?? false">
                  <h4>参数定义</h4>
                  <el-table
                    :data="parameterRows(row.parameters)"
                    size="small"
                    border
                    row-key="_key"
                    :tree-props="{ children: 'children' }"
                    default-expand-all
                  >
                    <el-table-column prop="name" label="参数名" min-width="200" />
                    <el-table-column prop="type" label="类型" width="160" />
                    <el-table-column prop="location" label="位置" width="100">
                      <template #default="{ row: param }">
                        {{ param.location || '-' }}
                      </template>
                    </el-table-column>
                    <el-table-column prop="description" label="描述" />
                    <el-table-column prop="required" label="必填" width="80" align="center">
                      <template #default="{ row: param }">
                        <el-tag :type="param.required ? 'danger' : 'info'" size="small">
                          {{ param.required ? '是' : '否' }}
                        </el-tag>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div class="tool-meta">
                    <div><b>HTTP：</b>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</div>
                    <div><b>Base URL：</b>{{ row.baseUrl || '-' }}</div>
                    <div><b>来源定位：</b>{{ row.sourceLocation || '-' }}</div>
                    <div><b>请求体类型：</b>{{ row.requestBodyType || '-' }}</div>
                    <div><b>响应类型：</b>{{ row.responseType || '-' }}</div>
                  </div>
                  <h4 class="expand-ai-heading">敏感数据</h4>
                  <div
                    v-if="row.sensitiveData && (row.sensitiveData.types?.length || row.sensitiveData.summary)"
                    class="expand-sensitive"
                  >
                    <div v-if="row.sensitiveData.types?.length" class="sensitive-tags">
                      <el-tag
                        v-for="t in row.sensitiveData.types"
                        :key="t"
                        size="small"
                        type="warning"
                        class="sensitive-tag"
                      >
                        {{ scanSensitiveTypeLabel(t) }}
                      </el-tag>
                    </div>
                    <p v-if="row.sensitiveData.summary" class="expand-sensitive-summary">{{ row.sensitiveData.summary }}</p>
                    <p v-if="row.sensitiveData.scannedAt" class="expand-sensitive-meta">扫描时间：{{ row.sensitiveData.scannedAt }}</p>
                  </div>
                  <el-empty v-else description="尚未扫描或暂无结果" :image-size="48" />
                  <h4 class="expand-ai-heading">AI 语义文档</h4>
                  <div v-if="toolDocMap[row.scanToolId]" class="markdown-body expand-md" v-html="renderMd(toolDocMap[row.scanToolId].contentMd)" />
                  <el-empty v-else description="该接口还没有 AI 描述" :image-size="60" />
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="工具名" min-width="180" />
            <el-table-column label="端点" min-width="200">
              <template #default="{ row }">
                <span>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column label="参数数" width="78" align="center">
              <template #default="{ row }">
                {{ toolParameterCount(row) }}
              </template>
            </el-table-column>
            <el-table-column label="AI 描述" min-width="200">
              <template #default="{ row }">
                <div class="ai-desc-ellipsis">
                  {{ toolDocSummary(row) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="AI 状态" width="96" align="center">
              <template #default="{ row }">
                <el-tag v-if="toolDocMap[row.scanToolId]" :type="toolDocMap[row.scanToolId].status === 'edited' ? 'success' : 'info'" size="small">
                  {{ toolDocMap[row.scanToolId].status }}
                </el-tag>
                <el-tag v-else size="small" type="warning">未生成</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="敏感数据" min-width="200">
              <template #default="{ row }">
                <template
                  v-if="!row.sensitiveData || (!row.sensitiveData.types?.length && !row.sensitiveData.summary)"
                >
                  <span class="sensitive-cell-empty">-</span>
                </template>
                <el-tooltip v-else :content="sensitiveCellTooltip(row)" placement="top" :show-after="300">
                  <div class="sensitive-cell">
                    <template v-if="row.sensitiveData.types?.length">
                      <el-tag
                        v-for="t in row.sensitiveData.types"
                        :key="t"
                        size="small"
                        type="warning"
                        class="sensitive-tag"
                      >
                        {{ scanSensitiveTypeLabel(t) }}
                      </el-tag>
                    </template>
                    <span v-else class="sensitive-cell-summary">{{ row.sensitiveData.summary }}</span>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="78" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  :disabled="row.removedFromSource"
                  @change="emit('enabledChange', row, $event as boolean)"
                />
              </template>
            </el-table-column>
            <el-table-column label="Agent 可见" width="96" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.agentVisible"
                  :disabled="row.removedFromSource"
                  @change="emit('flagChange', row, 'agentVisible', $event as boolean)"
                />
              </template>
            </el-table-column>
            <el-table-column label="轻量调用" width="96" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.lightweightEnabled"
                  :disabled="row.removedFromSource"
                  @change="emit('flagChange', row, 'lightweightEnabled', $event as boolean)"
                />
              </template>
            </el-table-column>
            <el-table-column label="Tool 关联" min-width="150">
              <template #default="{ row }">
                <div class="tool-link-cell">
                  <el-tag :type="toolLinkTagType(row)" size="small">{{ toolLinkLabel(row) }}</el-tag>
                  <el-tag v-if="row.sdkCapabilityReviewPending" size="small" type="warning" class="sdk-pending-tag">
                    SDK 评审
                  </el-tag>
                  <el-button
                    v-if="(row.toolSyncDiffFields?.length || 0) > 0"
                    link
                    type="primary"
                    size="small"
                    @click="emit('openDiff', row)"
                  >
                    差异
                  </el-button>
                </div>
                <div v-if="row.toolLinkMessage" class="tool-link-hint">{{ row.toolLinkMessage }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="520" fixed="right" align="right" header-align="right">
              <template #default="{ row }">
                <div class="merged-ops-wrap">
                  <el-button link type="primary" size="small" :disabled="row.removedFromSource" @click="emit('openEdit', row)">编辑</el-button>
                  <el-tooltip
                    effect="dark"
                    content="从源码或 OpenAPI 重新解析并更新本行（保留工具名与开关；已挂全局 Tool 时请再点「更新到Tool」同步）"
                    placement="top"
                  >
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="row.removedFromSource"
                      :loading="rescanSourceLoading[row.scanToolId]"
                      @click="emit('rescanFromSource', row)"
                    >
                      扫描更新
                    </el-button>
                  </el-tooltip>
                  <el-button link type="primary" size="small" :disabled="row.removedFromSource" @click="emit('openTest', row)">测试</el-button>
                  <el-button
                    v-if="!row.globalToolDefinitionId && !row.removedFromSource"
                    link
                    type="success"
                    size="small"
                    :loading="promoteLoading[row.scanToolId]"
                    @click="emit('promoteToGlobal', row)"
                  >
                    添加为 Tool
                  </el-button>
                  <el-button
                    v-if="row.globalToolDefinitionId && row.globalToolOutOfSync"
                    link
                    type="warning"
                    size="small"
                    :loading="pushToGlobalLoading[row.scanToolId]"
                    @click="emit('pushToGlobal', row)"
                  >
                    更新到Tool
                  </el-button>
                  <el-button
                    v-if="row.globalToolDefinitionId"
                    link
                    type="danger"
                    size="small"
                    :loading="unpromoteLoading[row.scanToolId]"
                    @click="emit('unpromoteFromGlobal', row)"
                  >
                    从Tool中下架
                  </el-button>
                  <span class="merged-ops-sep" aria-hidden="true" />
                  <el-button link size="small" type="primary" @click="emit('regenerateTool', row)">重新生成 AI</el-button>
                  <el-button link size="small" :disabled="!toolDocMap[row.scanToolId]" @click="emit('openEditDoc', toolDocMap[row.scanToolId])">编辑 AI 文档</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <div v-if="hiddenModuleGroupCount > 0" class="tool-groups-load-more">
        <span>还有 {{ hiddenModuleGroupCount }} 个模块未显示</span>
        <el-button size="small" @click="emit('showMoreGroups')">加载更多模块</el-button>
      </div>
    </div>
  </el-collapse-item>
</template>
