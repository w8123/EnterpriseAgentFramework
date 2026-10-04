<template>
  <div class="node-specific-panel">
    <el-divider>能力调用</el-divider>
    <el-form-item label="引用能力">
      <div class="reference-row">
        <el-input :model-value="config.ref" readonly aria-label="当前资产引用" placeholder="请选择所属目录中的业务方法或 API" />
        <el-button v-if="data.kind === 'tool'" plain @click="openBusinessMethodPicker">选择业务方法</el-button>
        <el-button v-if="data.kind === 'tool'" plain @click="openHttpApiPicker">选择 API</el-button>
      </div>
    </el-form-item>
    <el-alert v-if="selectedTool?.sourceAvailability === 'LEGACY_SCAN_TOOL_RETIRED'"
      title="扫描人工调用投影已退场。请到所属项目 API 目录核对来源、接纳和连接，再点“选择 API”重新选择并显式发布。旧引用保持不变。"
      type="warning" :closable="false" show-icon />
    <section v-if="selectedBusinessMethod || businessMethodDetailError" class="business-method-summary" aria-live="polite">
      <template v-if="selectedBusinessMethod">
        <div class="business-method-summary-head">
          <div>
            <strong>业务方法</strong>
            <span>{{ selectedBusinessMethod.title || selectedBusinessMethod.name }}</span>
          </div>
          <el-tag size="small" type="success" effect="plain">来源 {{ selectedBusinessMethod.sourceAvailability || 'READY' }}</el-tag>
        </div>
        <dl>
          <div><dt>标识</dt><dd><code>{{ selectedBusinessMethod.qualifiedName }}</code></dd></div>
          <div><dt>项目</dt><dd>{{ selectedBusinessMethod.projectCode || projectCode || '-' }}</dd></div>
          <div><dt>副作用</dt><dd>{{ businessMethodSideEffectLabel(selectedBusinessMethod.sideEffect) }}</dd></div>
          <div><dt>返回</dt><dd>{{ selectedBusinessMethod.responseType || '未声明返回类型' }}</dd></div>
        </dl>
        <p v-if="selectedBusinessMethod.description">{{ selectedBusinessMethod.description }}</p>
      </template>
      <el-alert
        v-if="businessMethodDetailError"
        :title="businessMethodDetailError"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button text type="primary" @click="retryBusinessMethodDetail">重试读取摘要</el-button>
        </template>
      </el-alert>
    </section>
    <section v-if="config.httpApiAssetId" class="business-method-summary" aria-live="polite">
      <template v-if="selectedHttpApi">
        <div class="business-method-summary-head">
          <div><strong>API</strong><span>{{ selectedHttpApi.summary.httpMethod }} {{ selectedHttpApi.summary.routeTemplate }}</span></div>
          <el-tag size="small" :type="httpApiSelectedReason ? 'danger' : 'success'" effect="plain">
            {{ httpApiSelectedReason ? '当前不可选' : '作者可配置' }}
          </el-tag>
        </div>
        <dl>
          <div><dt>项目</dt><dd>{{ selectedHttpApi.summary.projectCode }} / {{ selectedHttpApi.summary.environment }}</dd></div>
          <div><dt>来源</dt><dd>{{ selectedHttpApi.summary.sourceKinds.join('、') }}</dd></div>
          <div><dt>标识</dt><dd><code>{{ selectedHttpApi.summary.qualifiedName }}</code></dd></div>
          <div><dt>契约</dt><dd>{{ selectedHttpApi.summary.sourceStatus }} · 来源{{ selectedHttpApi.summary.sourceConfirmed ? '已确认' : '待确认' }}</dd></div>
        </dl>
        <p>配置只保存 API 标识与映射；发布时由服务端固定来源、连接及凭据修订。</p>
        <p>API 请求使用服务端受控超时；写入后超时按结果未确认处理，不自动重发。</p>
        <el-alert v-if="selectedHttpApi.summary.httpMethod === 'POST'" type="warning" :closable="false" show-icon
          title="此 API 会写入业务数据。仅已发布 Workflow 可执行；Studio 两种调试入口均不调用写 API。"
          description="Agent 须通过既有写操作确认；已授权 MCP 客户端的显式新运行会发起一次写入。结果未确认时请先核对原运行，勿直接重发。" />
      </template>
      <el-alert v-if="httpApiSelectedReason" :title="httpApiSelectedReason" type="warning" :closable="false" show-icon>
        <template #default><el-button text type="primary" @click="retryHttpApiDetail">重试读取 API</el-button></template>
      </el-alert>
      <p v-else-if="httpApiSelectedLoading">正在核对 API 来源及连接…</p>
    </section>
    <el-form-item v-if="!config.httpApiAssetId" label="凭据引用">
      <CredentialSelect
        v-model="config.credentialRef"
        :credentials="credentialOptions"
        :project-id="projectId"
        :project-code="projectCode"
        @created="$emit('credentialCreated', $event)"
      />
    </el-form-item>
    <el-form-item v-if="isRequestTool && !config.httpApiAssetId" label="最大请求时间">
      <el-input-number
        v-model="maxRequestSeconds"
        :min="1"
        :max="1800"
        :step="10"
        controls-position="right"
      />
      <span class="timeout-unit">秒</span>
    </el-form-item>
    <el-form-item label="参数映射">
      <template v-if="config.httpApiAssetId">
        <div v-if="selectedHttpApi" class="business-method-mappings">
          <div class="field-table-head"><strong>API path / query / JSON body</strong><span>必填项显式映射；可选缺省不补默认值，不回退 lastOutput。</span></div>
          <div v-for="target in httpApiInputTargets" :key="target.key" class="business-method-mapping-row">
            <div class="business-method-target">
              <strong>{{ target.name }}</strong><span>{{ target.location }} · {{ target.type }}</span>
              <em>{{ target.required ? '必填' : '可选' }}</em>
              <small v-if="target.sensitive">敏感字段：选择输入变量，不在图中保存秘密常量。</small>
            </div>
            <el-select :model-value="businessMappingDisplay(target.key)" filterable allow-create
              :disabled="!!httpApiSelectedReason"
              placeholder="选择 Workflow 输入或前序节点输出"
              @change="(value: string) => updateHttpApiMapping(target.key, value)">
              <el-option-group v-for="group in httpApiVariableGroups" :key="group.group" :label="group.group">
                <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value" />
              </el-option-group>
            </el-select>
            <p v-if="httpApiMappingIssue(target.key, target.required)" class="business-method-mapping-error" role="alert">
              {{ httpApiMappingIssue(target.key, target.required) }}
            </p>
          </div>
        </div>
        <el-alert v-else title="正在核对已保存 API；引用与映射保持原样。" type="info" :closable="false" />
      </template>
      <template v-else-if="selectedBusinessMethod">
        <div class="business-method-mappings">
          <div class="field-table-head">
            <div>
              <strong>逐参数来源</strong>
              <span>只映射顶层参数；DTO、Map 和数组整体传入。</span>
            </div>
            <el-button size="small" text type="primary" @click="fillMissingMappings">补齐映射</el-button>
          </div>
          <div v-if="businessMethodInputTargets.length" class="business-method-mapping-rows">
            <div v-for="target in businessMethodInputTargets" :key="target.name" class="business-method-mapping-row">
              <div class="business-method-target">
                <strong>{{ target.name }}</strong>
                <span>{{ target.type }}</span>
                <em>{{ target.required ? '必填' : '可选' }}</em>
                <small v-if="target.children.length">含 {{ target.children.length }} 个来源声明字段，仅映射根对象</small>
                <small v-else-if="target.description">{{ target.description }}</small>
              </div>
              <el-select
                :model-value="businessMappingDisplay(target.name)"
                filterable
                allow-create
                placeholder="选择 params、nodeOutput、var、sys 或高级表达式"
                @change="(value: string) => updateBusinessMethodMapping(target.name, value)"
              >
                <el-option-group v-for="group in businessMethodVariableGroups" :key="group.group" :label="group.group">
                  <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value">
                    <div class="variable-option">
                      <strong>{{ option.label }}</strong>
                      <span>{{ option.value }}</span>
                      <em v-if="option.description">{{ option.description }}</em>
                    </div>
                  </el-option>
                </el-option-group>
              </el-select>
              <p v-if="businessMappingIssue(target.name, target.required)" class="business-method-mapping-error" role="alert">
                {{ businessMappingIssue(target.name, target.required) }}
              </p>
            </div>
          </div>
          <div v-else class="business-method-zero-args">
            此业务方法无需输入参数。保存时会写入 Runtime 已支持的 <code>args={}</code>，不会回退为通用 input。
          </div>
          <el-alert
            v-if="mappingReconciliationNotice"
            :title="mappingReconciliationNotice"
            type="info"
            :closable="false"
            show-icon
          />
        </div>
      </template>
      <el-input
        v-else
        :model-value="formatMap(config.inputMapping)"
        type="textarea"
        :rows="6"
        placeholder="customerId = params.customerId"
        @update:model-value="updateGenericInputMapping($event)"
      />
    </el-form-item>
    <el-form-item label="映射备注">
      <el-input v-model="config.mappingNote" type="textarea" :rows="2" />
    </el-form-item>
    <div v-if="!selectedBusinessMethod && !selectedHttpApi && paramSourceHints.length" class="param-hints">
      <div class="field-table-head">
        <strong>参数来源提示</strong>
      </div>
      <div v-for="hint in paramSourceHints" :key="`${hint.targetPath}-${hint.sourceApi}-${hint.sourcePath}`" class="param-hint-row">
        <div>
          <strong>{{ hint.targetPath }}</strong>
          <span>{{ hint.sourceApi }}.{{ hint.sourcePath }}</span>
        </div>
        <el-tag v-if="hint.confidence !== null" size="small">{{ Math.round((hint.confidence || 0) * 100) }}%</el-tag>
        <el-button size="small" text type="primary" @click="applyHint(hint)">应用</el-button>
      </div>
    </div>
    <div v-if="config.httpApiAssetId && selectedHttpApi" class="tool-params business-method-output-hints">
      <div class="field-table-head"><strong>响应与下游引用</strong></div>
      <p>{{ httpApiOutputFields.length ? `已声明 ${httpApiOutputFields.length} 个 JSON 字段。` : '没有可证明的响应字段；下游仅提供根对象。' }}</p>
      <div class="business-method-output-candidates">
        <code>nodeOutput.{{ nodeId }}</code>
        <code>var.{{ data.outputAlias || 'tool_output' }}</code>
        <code v-for="field in httpApiOutputFields" :key="field">nodeOutput.{{ nodeId }}.{{ field }}</code>
      </div>
    </div>
    <div v-else-if="selectedBusinessMethod" class="tool-params business-method-output-hints">
      <div class="field-table-head">
        <strong>返回与下游引用</strong>
      </div>
      <p v-if="businessMethodOutputFields.length">
        已声明 {{ businessMethodOutputFields.length }} 个返回字段；下游可选择节点输出根对象、业务别名和声明字段路径。
      </p>
      <p v-else-if="businessMethodHasScalarTrialReturn(selectedBusinessMethod.responseType)">
        来源声明标量返回。只读试运行在下游选择“只读试运行返回值（标量）”；已发布执行使用节点输出根值。这里不推断业务返回字段。
      </p>
      <p v-else>
        {{ selectedBusinessMethod.responseType
          ? '来源仅声明返回类型，尚无可选字段；下游仅提供根对象。'
          : '来源未声明返回类型或字段；下游仅提供根对象。' }}
      </p>
      <div class="business-method-output-candidates">
        <code v-for="candidate in businessMethodOutputCandidates" :key="candidate.value">{{ candidate.value }}</code>
      </div>
    </div>
    <div v-else-if="selectedTool?.parameters?.length" class="tool-params">
      <div class="field-table-head">
        <strong>工具参数</strong>
        <el-button size="small" text type="primary" @click="fillMissingMappings">补齐映射</el-button>
      </div>
      <div v-for="param in selectedTool.parameters" :key="param.name" class="tool-param-row">
        <strong>{{ param.name }}</strong>
        <span>{{ param.type }}</span>
        <em>{{ param.required ? '必填' : '可选' }}</em>
      </div>
    </div>

    <el-dialog v-model="httpApiDialogOpen" title="选择 API" width="min(920px, 96vw)" append-to-body destroy-on-close>
      <div class="business-method-picker-toolbar">
        <el-input v-model="httpApiKeyword" clearable :prefix-icon="Search" aria-label="搜索 API"
          placeholder="搜索路径或 API 标识" @clear="clearHttpApiSearch" @keyup.enter="submitHttpApiSearch" />
        <el-button :icon="Search" type="primary" @click="submitHttpApiSearch">查询</el-button>
      </div>
      <p class="business-method-picker-scope">当前范围：{{ projectCode || projectId || '-' }} / {{ httpApiEnvironment || '待确认' }}。支持只读 GET 与内部 POST + WRITE 平铺 JSON；选择前核验来源、契约及连接。写 API 不用于 Studio 调试。</p>
      <el-alert v-if="httpApiListStatus === 'error'" :title="httpApiListError" type="error" :closable="false" show-icon>
        <template #default><el-button text type="primary" @click="retryHttpApiList">重试</el-button></template>
      </el-alert>
      <el-table v-loading="httpApiListStatus === 'loading'" :data="httpApiRows" row-key="summary.id" height="420" stripe>
        <el-table-column label="API" min-width="270">
          <template #default="{ row }"><div class="project-api-name">
            <strong>{{ row.summary.httpMethod }} {{ row.summary.routeTemplate }}</strong>
            <code>{{ row.summary.qualifiedName }}</code>
            <span>{{ row.summary.projectCode }} / {{ row.summary.environment }}</span>
            <span class="http-api-mobile-state">{{ row.summary.sourceKinds.join('、') }} · {{ row.reason || '来源及连接已核验' }}</span>
          </div></template>
        </el-table-column>
        <el-table-column label="来源 / 输入" min-width="150">
          <template #default="{ row }"><div class="project-api-name">
            <strong>{{ row.summary.sourceKinds.join('、') }}</strong>
            <span>{{ buildHttpApiInputTargets(row.detail?.acceptedContract).length }} 个输入 · {{ row.detail?.acceptedContract?.sideEffect || '未声明副作用' }}</span>
          </div></template>
        </el-table-column>
        <el-table-column label="可用性" min-width="180">
          <template #default="{ row }"><el-tag size="small" :type="row.reason ? 'warning' : 'success'" effect="plain">
            {{ row.reason || '来源及连接已核验' }}
          </el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button size="small" type="primary" text :disabled="!!row.reason" @click="selectHttpApi(row)">选择</el-button></template>
        </el-table-column>
        <template #empty><el-empty v-if="httpApiListStatus === 'empty'" :description="httpApiSubmittedKeyword ? '没有匹配的 API' : '当前范围没有可展示的 API'" /></template>
      </el-table>
      <div class="business-method-picker-footer"><span>共 {{ httpApiTotal }} 项；不可用项需按提示处理。</span>
        <el-pagination v-model:current-page="httpApiCurrentPage" :page-size="httpApiPageSize" layout="total, prev, pager, next"
          :total="httpApiTotal" @current-change="changeHttpApiPage" /></div>
    </el-dialog>


    <el-dialog v-model="businessMethodDialogOpen" title="选择业务方法" width="920px" append-to-body destroy-on-close>
      <div class="business-method-picker-toolbar">
        <el-input
          v-model="businessMethodKeyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索业务方法名称、标识或用途"
          aria-label="搜索业务方法"
          @clear="clearBusinessMethodSearch"
          @keyup.enter="submitBusinessMethodSearch"
        />
        <el-button :icon="Search" type="primary" @click="submitBusinessMethodSearch">查询</el-button>
      </div>
      <p class="business-method-picker-scope">当前项目：{{ projectCode || projectId || '-' }}。仅可选择已接纳、已启用且来源 READY 的业务方法。</p>
      <el-alert
        v-if="businessMethodListStatus === 'error'"
        :title="businessMethodListError"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default><el-button text type="primary" @click="retryBusinessMethodList">重试</el-button></template>
      </el-alert>
      <el-alert
        v-else-if="!businessMethodHasProjectScope"
        title="当前 Workflow 未绑定项目，不能跨项目选择业务方法。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="businessMethodInvalidRows.length"
        :title="`目录返回 ${businessMethodInvalidRows.length} 条不满足当前选择条件的数据，已禁止选择。`"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-table
        v-loading="businessMethodListStatus === 'loading'"
        :data="businessMethodRows"
        row-key="qualifiedName"
        height="420"
        stripe
      >
        <el-table-column label="业务方法" min-width="250">
          <template #default="{ row }">
            <div class="project-api-name">
              <strong>{{ row.title || row.name }}</strong>
              <code>{{ row.qualifiedName || row.name }}</code>
              <span>{{ row.description || '未声明用途摘要' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="输入 / 返回" min-width="150">
          <template #default="{ row }">
            <div class="project-api-name">
              <strong>{{ businessMethodInputCount(row) }} 个输入</strong>
              <span>{{ row.responseType || '未声明返回类型' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="副作用 / 来源" min-width="150">
          <template #default="{ row }">
            <div class="project-api-name">
              <strong>{{ businessMethodSideEffectLabel(row.sideEffect) }}</strong>
              <span>{{ row.sourceAvailability || '未声明来源状态' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="isBusinessMethodSelectable(row) ? 'success' : 'danger'" effect="plain">
              {{ isBusinessMethodSelectable(row) ? '可选择' : '数据不一致' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text :disabled="!isBusinessMethodSelectable(row)" @click="selectBusinessMethod(row)">选择</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="businessMethodListStatus !== 'loading'" :description="businessMethodEmptyDescription" />
        </template>
      </el-table>
      <div class="business-method-picker-footer">
        <span>共 {{ businessMethodTotal }} 项；搜索仅在显式查询后提交。</span>
        <el-pagination
          v-model:current-page="businessMethodCurrentPage"
          :page-size="businessMethodPageSize"
          layout="total, prev, pager, next"
          :total="businessMethodTotal"
          @current-change="changeBusinessMethodPage"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import type { CanvasNodeData, StudioPort, StudioVariableOption, ToolNodeConfig } from '@/types/studio'
import type { ToolInfo } from '@/types/tool'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import {
  businessMethodInputTargets as buildBusinessMethodInputTargets,
  businessMethodMappingIssue as getBusinessMethodMappingIssue,
  businessMethodOutputCandidates as buildBusinessMethodOutputCandidates,
  businessMethodOutputFields as buildBusinessMethodOutputFields,
  businessMethodOutputPorts,
  businessMethodHasScalarTrialReturn,
  businessMethodSideEffectLabel,
  applyBusinessMethodSelection,
  isSelectableBusinessMethod,
  reconcileBusinessMethodInputMapping,
} from '@/views/workflow/businessMethodWorkflow'
import { useWorkflowBusinessMethodPicker } from '@/views/workflow/composables/useWorkflowBusinessMethodPicker'
import { useWorkflowHttpApiPicker, type HttpApiCandidate } from '@/views/workflow/composables/useWorkflowHttpApiPicker'
import { applyHttpApiSelection, httpApiInputTargets as buildHttpApiInputTargets,
  httpApiOutputFields as buildHttpApiOutputFields, httpApiOutputPorts } from '@/views/workflow/httpApiWorkflow'
import { formatMap, parseMap } from './panelUtils'
import CredentialSelect from './CredentialSelect.vue'

const props = defineProps<{
  data: CanvasNodeData
  options: ToolInfo[]
  credentialOptions: WorkflowCredential[]
  paramSourceHints: ApiGraphParamSourceHint[]
  projectId?: number | null
  projectCode?: string | null
  nodeId?: string
  variableOptions?: Array<string | StudioVariableOption>
  requestScopeKey?: string | null
}>()
defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
}>()

const config = computed<ToolNodeConfig>(() => {
  props.data.toolConfig ||= {
    ref: '',
    qualifiedName: null,
    projectCode: null,
    credentialRef: '',
    maxRequestTimeMs: 180000,
    inputMapping: {},
    argumentSource: 'inputMapping',
  }
  return props.data.toolConfig
})
const selectedTool = computed(() => props.options.find((item) => item.qualifiedName === config.value.ref || item.name === config.value.ref) as ToolInfo | undefined)
const isRequestTool = computed(() => {
  if (props.data.kind !== 'tool') return false
  const tool = selectedTool.value
  return !tool || tool.source !== 'code' || !!tool.httpMethod || !!tool.endpointPath
})
const maxRequestSeconds = computed({
  get: () => Math.round((config.value.maxRequestTimeMs || 180000) / 1000),
  set: (value: number) => {
    config.value.maxRequestTimeMs = Math.max(1000, Math.min(1800000, Math.round(value || 180) * 1000))
  },
})
const businessMethodProjectId = computed(() => props.projectId)
const businessMethodProjectCode = computed(() => props.projectCode)
const businessMethodNodeId = computed(() => props.nodeId)
const businessMethodRequestScope = computed(() => props.requestScopeKey)
const businessMethodPicker = useWorkflowBusinessMethodPicker({
  projectId: businessMethodProjectId,
  projectCode: businessMethodProjectCode,
  nodeId: businessMethodNodeId,
  requestScopeKey: businessMethodRequestScope,
})
const {
  dialogOpen: businessMethodDialogOpen,
  keyword: businessMethodKeyword,
  currentPage: businessMethodCurrentPage,
  pageSize: businessMethodPageSize,
  rows: businessMethodRows,
  total: businessMethodTotal,
  listStatus: businessMethodListStatus,
  listError: businessMethodListError,
  invalidRows: businessMethodInvalidRows,
  hasProjectScope: businessMethodHasProjectScope,
  emptyDescription: businessMethodEmptyDescription,
  selectedSummary: selectedBusinessMethod,
  detailError: businessMethodDetailError,
} = businessMethodPicker
const httpApiPicker = useWorkflowHttpApiPicker({
  projectId: businessMethodProjectId,
  projectCode: businessMethodProjectCode,
  nodeId: businessMethodNodeId,
  requestScopeKey: businessMethodRequestScope,
  selectedAssetId: computed(() => config.value.httpApiAssetId),
})
const {
  dialogOpen: httpApiDialogOpen, keyword: httpApiKeyword, submittedKeyword: httpApiSubmittedKeyword,
  currentPage: httpApiCurrentPage, pageSize: httpApiPageSize, rows: httpApiRows,
  total: httpApiTotal, environment: httpApiEnvironment, status: httpApiListStatus,
  error: httpApiListError, selectedDetail: httpApiSelectedDetail,
  selectedReason: httpApiDetailReason, selectedLoading: httpApiSelectedLoading,
} = httpApiPicker
const httpApiSelectedReason = computed(() => {
  if (httpApiDetailReason.value) return httpApiDetailReason.value
  const detail = httpApiSelectedDetail.value
  if (detail && detail.summary.qualifiedName !== config.value.ref) {
    return '已保存 API 标识与目录不一致；已有引用和映射未被修改。'
  }
  return ''
})
const selectedHttpApi = computed(() => {
  const detail = httpApiSelectedDetail.value
  return detail && detail.summary.id === config.value.httpApiAssetId
    && detail.summary.qualifiedName === config.value.ref ? detail : null
})
const httpApiInputTargets = computed(() => buildHttpApiInputTargets(selectedHttpApi.value?.acceptedContract))
const httpApiOutputFields = computed(() => buildHttpApiOutputFields(selectedHttpApi.value?.acceptedContract))
const httpApiVariableGroups = computed(() => {
  const values = new Map<string, StudioVariableOption>()
  const add = (item: StudioVariableOption) => {
    if (!item.value || values.has(item.value) || item.value === 'lastOutput') return
    if (props.nodeId && (item.nodeId === props.nodeId || item.value.startsWith(`nodeOutput.${props.nodeId}`))) return
    values.set(item.value, item)
  }
  for (const target of httpApiInputTargets.value) add({ value: `params.${target.name}`,
    label: `用户输入 · ${target.name}`, group: '用户输入' })
  for (const item of props.variableOptions || []) add(normalizeVariableOption(item))
  const groups = ['用户输入', '节点输出', '业务变量', '系统变量', '高级表达式']
  return groups.map((group) => ({ group, options: [...values.values()].filter((item) => item.group === group) }))
    .filter((group) => group.options.length)
})
const mappingReconciliationNotice = ref('')
const businessMethodInputTargets = computed(() => selectedBusinessMethod.value
  ? buildBusinessMethodInputTargets(selectedBusinessMethod.value.parameters)
  : [])
const businessMethodOutputFields = computed(() => selectedBusinessMethod.value
  ? buildBusinessMethodOutputFields(selectedBusinessMethod.value.parameters)
  : [])
const businessMethodOutputCandidates = computed(() => selectedBusinessMethod.value
  ? buildBusinessMethodOutputCandidates(
    props.nodeId || 'tool',
    props.data.outputAlias || 'tool_output',
    selectedBusinessMethod.value.parameters,
    selectedBusinessMethod.value.responseType,
  )
  : [])
const businessMethodVariableOptions = computed<StudioVariableOption[]>(() => {
  const options = new Map<string, StudioVariableOption>()
  const add = (option: StudioVariableOption) => {
    if (!option.value || options.has(option.value)) return
    if (props.nodeId && (option.nodeId === props.nodeId || option.value.startsWith(`nodeOutput.${props.nodeId}`))) return
    options.set(option.value, option)
  }
  add({ value: 'params', label: '用户输入 · 全部参数', group: '用户输入', description: '入口参数对象' })
  for (const target of businessMethodInputTargets.value) {
    add({ value: `params.${target.name}`, label: `用户输入 · ${target.name}`, group: '用户输入', description: `默认来源 params.${target.name}` })
  }
  for (const item of props.variableOptions || []) add(normalizeVariableOption(item))
  return [...options.values()]
})
const businessMethodVariableGroups = computed(() => {
  const groups = ['用户输入', '系统变量', '节点输出', '业务变量', '运行态变量', '高级表达式']
  return groups.map((group) => ({
    group,
    options: businessMethodVariableOptions.value.filter((option) => option.group === group),
  })).filter((group) => group.options.length)
})
watch(
  () => [
    config.value.ref || '',
    config.value.qualifiedName || '',
    config.value.httpApiAssetId || '',
    props.projectId || '',
    props.projectCode || '',
    props.nodeId || '',
    props.requestScopeKey || '',
  ].join('\u0000'),
  () => {
    if (config.value.httpApiAssetId) businessMethodPicker.invalidateDetailState(true)
    else void businessMethodPicker.loadSelectedDetail(config.value.ref)
  },
  { immediate: true },
)

watch(selectedBusinessMethod, (method) => {
  if (method) syncBusinessMethodCanvasContract(method)
})

watch([selectedHttpApi, httpApiSelectedReason], ([detail, reason]) => {
  if (detail && !reason) syncHttpApiCanvasContract(detail)
})

watch(
  () => props.data.outputAlias,
  () => {
    if (selectedBusinessMethod.value) syncBusinessMethodCanvasContract(selectedBusinessMethod.value)
    if (selectedHttpApi.value && !httpApiSelectedReason.value) syncHttpApiCanvasContract(selectedHttpApi.value)
  },
)


function openBusinessMethodPicker() {
  if (!props.projectId) {
    ElMessage.warning('请先在项目上下文中打开 Workflow Studio')
    return
  }
  businessMethodPicker.openDialog()
}

function submitBusinessMethodSearch(event?: KeyboardEvent) {
  businessMethodPicker.submitSearch(event)
}

function clearBusinessMethodSearch() {
  businessMethodPicker.clearSearch()
}

function changeBusinessMethodPage(page: number) {
  businessMethodPicker.changePage(page)
}

function retryBusinessMethodList() {
  businessMethodPicker.retryList()
}

function retryBusinessMethodDetail() {
  businessMethodPicker.retrySelectedDetail()
}

function isBusinessMethodSelectable(row: ToolInfo) {
  return isSelectableBusinessMethod(row, props.projectId)
}

function businessMethodInputCount(row: ToolInfo) {
  return buildBusinessMethodInputTargets(row.parameters).length
}

function selectBusinessMethod(row: ToolInfo) {
  if (!businessMethodPicker.adoptCandidate(row)) {
    ElMessage.error('返回的业务方法不满足当前项目、已接纳、启用或来源可用条件。')
    return
  }
  const { reconciliation: result } = applyBusinessMethodSelection(config.value, row, props.projectCode)
  config.value.httpApiAssetId = null
  props.data.label = row.title || row.name
  props.data.description = row.description || props.data.description || ''
  syncBusinessMethodCanvasContract(row)
  mappingReconciliationNotice.value = `已保留 ${result.preserved} 项同名映射，补齐 ${result.added} 项，移除 ${result.removed} 项不再声明的映射。`
  businessMethodPicker.closeDialog()
  ElMessage.success('已写入业务方法节点配置')
}


function fillMissingMappings() {
  if (selectedHttpApi.value) return
  if (selectedBusinessMethod.value) {
    const result = reconcileBusinessMethodInputMapping(config.value.inputMapping, businessMethodInputTargets.value)
    config.value.inputMapping = result.mapping
    config.value.argumentSource = businessMethodInputTargets.value.length ? 'inputMapping' : 'args'
    if (!businessMethodInputTargets.value.length) config.value.inputMapping = {}
    syncBusinessMethodCanvasContract(selectedBusinessMethod.value)
    mappingReconciliationNotice.value = `已保留 ${result.preserved} 项现有映射，补齐 ${result.added} 项。`
    return
  }
  const mapping = { ...(config.value.inputMapping || {}) }
  for (const param of selectedTool.value?.parameters || []) {
    if (!mapping[param.name]) mapping[param.name] = param.name
  }
  config.value.inputMapping = mapping
}

function applyHint(hint: ApiGraphParamSourceHint) {
  const mapping = { ...(config.value.inputMapping || {}) }
  mapping[hint.targetPath] = `${hint.sourceApi}.${hint.sourcePath}`
  config.value.inputMapping = mapping
  if (!config.value.mappingNote) {
    config.value.mappingNote = '参数来源由接口图谱关系生成。'
  }
}

function openHttpApiPicker() {
  if (!props.projectId) {
    ElMessage.warning('请先在项目上下文中打开 Workflow Studio')
    return
  }
  httpApiDialogOpen.value = true
}

function submitHttpApiSearch(event?: KeyboardEvent) { httpApiPicker.submitSearch(event) }
function clearHttpApiSearch() { httpApiPicker.clearSearch() }
function changeHttpApiPage(page: number) { httpApiPicker.changePage(page) }
function retryHttpApiList() { void httpApiPicker.loadList() }
function retryHttpApiDetail() { void httpApiPicker.loadSelectedDetail() }

async function selectHttpApi(candidate: HttpApiCandidate) {
  const detail = await httpApiPicker.selectCandidate(candidate)
  if (!detail) {
    ElMessage.warning('API 来源或连接已变化，请查看当前候选状态并重试。')
    return
  }
  businessMethodPicker.invalidateDetailState(true)
  const targets = applyHttpApiSelection(config.value, detail)
  props.data.label = `${detail.summary.httpMethod} ${detail.summary.routeTemplate}`
  if (!props.data.outputAlias) props.data.outputAlias = 'tool_output'
  props.data.inputs = targets.map((target) => ({ id: target.key, name: target.name,
    type: studioPortType(target.type), required: target.required, source: '' }))
  props.data.outputs = httpApiOutputPorts(props.data.outputAlias, detail.acceptedContract)
  httpApiPicker.closeDialog()
  ElMessage.success('已写入 API 配置；保存并发布后可通过授权入口执行')
}

function updateGenericInputMapping(value: string) {
  config.value.inputMapping = parseMap(value)
  config.value.argumentSource ||= 'inputMapping'
}

function updateBusinessMethodMapping(target: string, value: string) {
  config.value.inputMapping = {
    ...(config.value.inputMapping || {}),
    [target]: value,
  }
  config.value.argumentSource = 'inputMapping'
  syncBusinessMethodCanvasContract(selectedBusinessMethod.value)
}

function updateHttpApiMapping(target: string, value: string) {
  const declaration = httpApiInputTargets.value.find((field) => field.key === target)
  if (declaration?.sensitive && value && !/^(params|nodeOutput|var|sys)\.[A-Za-z0-9_.-]+$/.test(value)) {
    ElMessage.warning('敏感 API 字段请选择输入变量；不要把秘密常量保存到 Workflow 图。')
    return
  }
  config.value.inputMapping = { ...(config.value.inputMapping || {}), [target]: value }
  config.value.argumentSource = 'inputMapping'
  if (selectedHttpApi.value) syncHttpApiCanvasContract(selectedHttpApi.value)
}

function httpApiMappingIssue(target: string, required: boolean) {
  const value = config.value.inputMapping?.[target]
  if (required && (typeof value !== 'string' || !value.trim())) return '必填 API 参数尚未映射。'
  if (value === 'lastOutput' || value === 'previousOutput') return 'API 参数须显式选择输入或前序节点字段。'
  return getBusinessMethodMappingIssue(target, value, required,
    httpApiVariableGroups.value.flatMap((group) => group.options.map((option) => option.value)))
}

function syncHttpApiCanvasContract(detail: NonNullable<typeof selectedHttpApi.value>) {
  const targets = buildHttpApiInputTargets(detail.acceptedContract)
  const nextInputs = targets.map((target) => ({ id: target.key, name: target.name,
    type: studioPortType(target.type), required: target.required,
    source: businessMappingDisplay(target.key) }))
  const nextOutputs = httpApiOutputPorts(props.data.outputAlias || 'tool_output', detail.acceptedContract)
  if (!sameStudioPorts(props.data.inputs || [], nextInputs)) props.data.inputs = nextInputs
  if (!sameStudioPorts(props.data.outputs || [], nextOutputs)) props.data.outputs = nextOutputs
}

function businessMappingDisplay(target: string) {
  const value = config.value.inputMapping?.[target]
  if (typeof value === 'string') return value
  if (value === undefined || value === null) return ''
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function businessMappingIssue(target: string, required: boolean) {
  return getBusinessMethodMappingIssue(
    target,
    config.value.inputMapping?.[target],
    required,
    businessMethodVariableOptions.value.map((option) => option.value),
  )
}

function syncBusinessMethodCanvasContract(method: ToolInfo | null | undefined) {
  if (!method) return
  const targets = buildBusinessMethodInputTargets(method.parameters)
  if (!props.data.outputAlias) props.data.outputAlias = 'tool_output'
  if (!targets.length) {
    if (Object.keys(config.value.inputMapping || {}).length) config.value.inputMapping = {}
    if (config.value.argumentSource !== 'args') config.value.argumentSource = 'args'
  }
  const nextInputs = targets.map((target) => ({
    id: target.name,
    name: target.name,
    type: studioPortType(target.type),
    required: target.required,
    source: businessMappingDisplay(target.name),
  }))
  const nextOutputs = businessMethodOutputPorts(props.data.outputAlias || 'tool_output', method.parameters, method.responseType)
  if (!sameStudioPorts(props.data.inputs || [], nextInputs)) props.data.inputs = nextInputs
  if (!sameStudioPorts(props.data.outputs || [], nextOutputs)) props.data.outputs = nextOutputs
}

function sameStudioPorts(current: StudioPort[], next: StudioPort[]) {
  return current.length === next.length && current.every((port, index) => {
    const candidate = next[index]
    return Boolean(candidate)
      && port.id === candidate.id
      && port.name === candidate.name
      && port.type === candidate.type
      && port.required === candidate.required
      // Vue Flow normalizes omitted optional strings to empty strings while
      // the GraphSpec serializer omits them. Treat those representations as
      // equal so reopening a saved business-method node does not create a
      // spurious canvas mutation (and therefore a false dirty draft).
      && (port.schema ?? '') === (candidate.schema ?? '')
      && (port.source ?? '') === (candidate.source ?? '')
  })
}

function studioPortType(type: string): StudioPort['type'] {
  const normalized = String(type || '').trim().toLowerCase()
  if (['number', 'decimal', 'double', 'float', 'bigdecimal'].includes(normalized)) return 'number'
  if (['integer', 'int', 'long', 'short', 'byte'].includes(normalized)) return 'integer'
  if (['boolean', 'bool'].includes(normalized)) return 'boolean'
  if (['array', 'list', 'set', 'collection'].includes(normalized)) return 'array'
  if (['object', 'map', 'json', 'jsonobject'].includes(normalized)) return 'object'
  return 'any'
}

function normalizeVariableOption(item: string | StudioVariableOption): StudioVariableOption {
  if (typeof item !== 'string') return item
  if (item.startsWith('params')) {
    return { value: item, label: item === 'params' ? '用户输入 · 全部参数' : `用户输入 · ${item.slice('params.'.length)}`, group: '用户输入', description: item }
  }
  if (item.startsWith('sys.')) return { value: item, label: `系统变量 · ${item.slice('sys.'.length)}`, group: '系统变量', description: item }
  if (item.startsWith('nodeOutput.')) return { value: item, label: `节点输出 · ${item.slice('nodeOutput.'.length)}`, group: '节点输出', description: item }
  if (item.startsWith('var.')) return { value: item, label: `业务别名 · ${item.slice('var.'.length)}`, group: '业务变量', description: item }
  if (['input', 'answer', 'lastOutput', 'lastRoute', 'lastSuccess', 'lastError'].includes(item)) {
    return { value: item, label: `运行态变量 · ${item}`, group: '运行态变量', description: item }
  }
  return { value: item, label: `高级表达式 · ${item}`, group: '高级表达式', description: item }
}

</script>

<style scoped>
.timeout-unit {
  margin-left: 8px;
  color: var(--text-secondary);
}

.reference-row {
  display: flex;
  flex-wrap: wrap;
  width: 100%;
  gap: 8px;
  min-width: 0;
}

.reference-row .el-select {
  flex: 1 1 240px;
  min-width: 0;
}

.reference-row .el-button {
  flex: 0 0 auto;
}

.project-api-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 160px auto auto;
  gap: 8px;
  margin-bottom: 12px;
}

.project-api-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.project-api-name span {
  color: var(--text-secondary);
  font-size: 12px;
}

.project-api-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}

.business-method-summary,
.business-method-mappings,
.business-method-output-hints {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.business-method-summary {
  margin: -2px 0 12px;
}

.business-method-summary-head,
.business-method-picker-toolbar,
.business-method-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.business-method-summary-head > div,
.business-method-target,
.business-method-picker-scope,
.business-method-output-hints p {
  min-width: 0;
}

.business-method-summary-head strong,
.business-method-summary-head span {
  margin-right: 8px;
}

.business-method-summary dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin: 0;
}

.business-method-summary dl > div {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 6px;
}

.business-method-summary dt,
.business-method-summary dd,
.business-method-summary p,
.business-method-output-hints p,
.business-method-picker-scope {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.business-method-summary dd {
  min-width: 0;
  overflow-wrap: anywhere;
}

.business-method-mapping-rows {
  display: grid;
  gap: 8px;
}

.business-method-mapping-row {
  display: grid;
  grid-template-columns: minmax(160px, 0.72fr) minmax(260px, 1.28fr);
  gap: 8px 12px;
  align-items: center;
  min-width: 0;
}

.business-method-mapping-row > * {
  min-width: 0;
}

.business-method-target {
  display: grid;
  grid-template-columns: auto auto auto;
  align-items: center;
  gap: 4px 8px;
  overflow-wrap: anywhere;
}

.business-method-target span,
.business-method-target small,
.business-method-target em,
.business-method-mapping-error,
.business-method-zero-args,
.business-method-picker-scope {
  color: var(--text-secondary);
  font-size: 12px;
  font-style: normal;
}

.business-method-target small,
.business-method-mapping-error {
  grid-column: 1 / -1;
}

.business-method-mapping-error {
  grid-column: 2;
  margin: -3px 0 0;
  color: var(--el-color-danger);
}

.business-method-zero-args {
  padding: 10px;
  border-radius: 6px;
  background: var(--el-fill-color);
}

.business-method-output-candidates {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.business-method-output-candidates code {
  max-width: 100%;
  overflow-wrap: anywhere;
  padding: 3px 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.business-method-picker-toolbar {
  margin-bottom: 10px;
}

.business-method-picker-toolbar .el-input {
  flex: 1;
}

.business-method-picker-scope {
  margin-bottom: 10px;
}

.business-method-picker-footer {
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}

.http-api-mobile-state {
  display: none;
}

@media (max-width: 720px) {
  .http-api-mobile-state {
    display: block;
    overflow-wrap: anywhere;
  }
  .reference-row,
  .business-method-picker-toolbar,
  .business-method-picker-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .reference-row .el-select {
    flex-basis: auto;
  }

  .reference-row .el-button {
    margin-left: 0;
    white-space: normal;
  }

  .business-method-summary dl,
  .business-method-mapping-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .business-method-mapping-error {
    grid-column: 1;
  }
}
</style>
