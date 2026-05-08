<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <el-button link @click="router.back()">返回</el-button>
        <h2>{{ project?.name || projectCode }}</h2>
        <p>注册项目概览、实例心跳与业务系统接入配置。</p>
      </div>
      <div class="actions">
        <el-button @click="refresh">刷新</el-button>
        <el-button type="primary" @click="setCurrentProject" :disabled="!project">设为当前项目</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>项目概览</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="项目编码">{{ project?.projectCode || projectCode }}</el-descriptions-item>
            <el-descriptions-item label="项目形态">
              <el-tag>{{ project?.projectKind || 'REGISTERED' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="环境">{{ project?.environment || '-' }}</el-descriptions-item>
            <el-descriptions-item label="可见性">
              <el-tag>{{ project?.visibility || 'PRIVATE' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">{{ project?.owner || '-' }}</el-descriptions-item>
            <el-descriptions-item label="能力数">{{ project?.toolCount ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="Base URL" :span="2">{{ project?.baseUrl || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Context Path" :span="2">{{ project?.contextPath || '-' }}</el-descriptions-item>
          </el-descriptions>
          <div class="quick-actions">
            <el-button type="primary" @click="goApiCatalog" :disabled="!project?.id">API 目录与 Tool 关联</el-button>
            <el-button @click="goCapabilitySync">能力变更评审</el-button>
            <el-button @click="goCapability('/tool')">查看 Tool</el-button>
            <el-button @click="goCapability('/skill')">查看 Skill</el-button>
            <el-button @click="goCapability('/agent')">查看 Agent</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="config-example-header">
              <span>业务系统配置示例</span>
              <el-button type="primary" link :icon="DocumentCopy" @click="copyConfigSnippet">复制</el-button>
            </div>
          </template>
          <el-input
            :model-value="configSnippet"
            type="textarea"
            :rows="13"
            readonly
            resize="none"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="project?.id" class="section-card scan-settings-registry" shadow="never">
      <template #header>
        <span>接口与参数说明来源（SDK 与离线扫描共用）</span>
      </template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="registry-scan-alert"
        title="说明"
        description="此处保存的配置写入项目 scan_settings。业务系统 SDK 启动/心跳前会拉取其中顺序（运行时不会使用 Javadoc 源）。离线 Controller 扫描仍可使用 Javadoc 相关项。"
      />
      <el-form label-width="180px" class="scan-settings-form-registry" @submit.prevent>
        <el-form-item label="接口说明来源（上→下优先）">
          <div class="order-list">
            <div v-for="(k, i) in scanSettingsForm.descriptionSourceOrder" :key="k" class="order-item">
              <span class="order-label">
                <el-tooltip
                  v-if="k === 'JAVADOC'"
                  content="SDK 运行时不读取；仅离线源码扫描使用。"
                  placement="top"
                >
                  <span>{{ descriptionSourceLabels[k] || k }}</span>
                </el-tooltip>
                <template v-else>{{ descriptionSourceLabels[k] || k }}</template>
              </span>
              <el-switch
                :model-value="scanSettingsForm.descriptionSourceEnabled[k] !== false"
                class="order-source-switch"
                size="small"
                @update:model-value="(v: boolean) => setDescriptionSourceEnabled(k, v)"
                @click.stop
              />
              <el-button-group>
                <el-button size="small" :disabled="i === 0" @click="moveDescriptionOrder(i, -1)">上移</el-button>
                <el-button
                  size="small"
                  :disabled="i === scanSettingsForm.descriptionSourceOrder.length - 1"
                  @click="moveDescriptionOrder(i, 1)"
                >下移</el-button>
              </el-button-group>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="参数说明来源（上→下优先）">
          <div class="order-list">
            <div v-for="(k, i) in scanSettingsForm.paramDescriptionSourceOrder" :key="k" class="order-item">
              <span class="order-label">
                <el-tooltip
                  v-if="k === 'JAVADOC_PARAM'"
                  content="SDK 运行时不读取；仅离线源码扫描使用。"
                  placement="top"
                >
                  <span>{{ paramSourceLabels[k] || k }}</span>
                </el-tooltip>
                <template v-else>{{ paramSourceLabels[k] || k }}</template>
              </span>
              <el-switch
                :model-value="scanSettingsForm.paramDescriptionSourceEnabled[k] !== false"
                class="order-source-switch"
                size="small"
                @update:model-value="(v: boolean) => setParamDescriptionSourceEnabled(k, v)"
                @click.stop
              />
              <el-button-group>
                <el-button size="small" :disabled="i === 0" @click="moveParamOrder(i, -1)">上移</el-button>
                <el-button
                  size="small"
                  :disabled="i === scanSettingsForm.paramDescriptionSourceOrder.length - 1"
                  @click="moveParamOrder(i, 1)"
                >下移</el-button>
              </el-button-group>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="仅 @RestController">
          <el-switch v-model="scanSettingsForm.onlyRestController" />
        </el-form-item>
        <el-form-item label="HTTP 方法白名单">
          <el-select
            v-model="scanSettingsForm.httpMethodWhitelist"
            multiple
            clearable
            filterable
            class="http-method-select-registry"
            placeholder="留空=全部"
          >
            <el-option v-for="m in allHttpMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="类名包含正则">
          <el-input v-model="scanSettingsForm.classIncludeRegex" clearable placeholder="留空=不限制" />
        </el-form-item>
        <el-form-item label="类名排除正则">
          <el-input v-model="scanSettingsForm.classExcludeRegex" clearable placeholder="留空=不排除" />
        </el-form-item>
        <el-form-item label="跳过 deprecated 接口">
          <el-switch v-model="scanSettingsForm.skipDeprecated" />
        </el-form-item>
        <el-form-item label="新发现接口默认">
          <div class="switch-group-registry">
            <el-switch v-model="scanSettingsForm.defaultFlags.enabled" />
            <span>启用</span>
            <el-switch v-model="scanSettingsForm.defaultFlags.agentVisible" />
            <span>Agent 可见</span>
            <el-switch v-model="scanSettingsForm.defaultFlags.lightweightEnabled" />
            <span>轻量调用</span>
          </div>
        </el-form-item>
        <el-form-item label="增量扫描">
          <el-radio-group v-model="scanSettingsForm.incrementalMode" class="incr-radio-registry">
            <el-radio-button label="OFF">关闭</el-radio-button>
            <el-radio-button label="MTIME">仅变更文件</el-radio-button>
            <el-radio-button label="GIT_DIFF">Git 差异</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="scanSettingsSaving" @click="handleSaveScanSettings">保存设置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="section-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>实例心跳</span>
          <el-button link type="primary" @click="loadInstances">刷新实例</el-button>
        </div>
      </template>
      <el-table v-loading="loadingInstances" :data="instances" row-key="id">
        <el-table-column prop="instanceId" label="实例 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="Host" min-width="160" />
        <el-table-column prop="port" label="端口" width="90" />
        <el-table-column prop="appVersion" label="应用版本" width="130" />
        <el-table-column prop="sdkVersion" label="SDK 版本" width="130" />
        <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DocumentCopy } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getScanProjects, getScanProjectDetail, updateScanProjectScanSettings } from '@/api/scanProject'
import { listRegistryProjectInstances } from '@/api/registry'
import type {
  DescriptionSource,
  ParamDescriptionSource,
  ScanProject,
  ScanSettings,
} from '@/types/scanProject'
import { getDefaultScanSettings } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const loadingInstances = ref(false)

const scanSettingsForm = reactive<ScanSettings>(getDefaultScanSettings())
const scanSettingsSaving = ref(false)

const descriptionSourceLabels: Record<DescriptionSource, string> = {
  JAVADOC: 'Javadoc',
  SWAGGER_API_OPERATION: 'Swagger @ApiOperation',
  OPENAPI_OPERATION: 'OpenAPI @Operation',
  METHOD_NAME: '方法名兜底',
}

const paramSourceLabels: Record<ParamDescriptionSource, string> = {
  JAVADOC_PARAM: 'Javadoc @param',
  SCHEMA_ANNO: '@Schema / 模型',
  PARAMETER_ANNO: '@Parameter 等',
  FIELD_NAME: '形参/字段名',
}

const allHttpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'] as const

/** 与 ai-agent-service 实际监听地址一致，由 VITE_AI_AGENT_SERVICE_URL 注入，默认本地 8603 */
const agentServiceBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_AI_AGENT_SERVICE_URL?.trim()
  const fallback = 'http://localhost:8603'
  if (!raw) return fallback
  return raw.replace(/\/$/, '')
})

const exampleBusinessBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_EXAMPLE_BUSINESS_BASE_URL?.trim()
  if (raw) return raw.replace(/\/$/, '')
  return 'http://127.0.0.1:8611'
})

const configSnippet = computed(() => `eaf:
  registry:
    enabled: true
    url: ${agentServiceBaseUrl.value}
  project:
    code: ${project.value?.projectCode || projectCode.value}
    name: ${project.value?.name || ''}
    base-url: ${project.value?.baseUrl || exampleBusinessBaseUrl.value}
    context-path: ${project.value?.contextPath || ''}
    environment: ${project.value?.environment || 'dev'}
    owner: ${project.value?.owner || 'your-team'}
    visibility: ${project.value?.visibility || 'PRIVATE'}
  capability:
    scan-controller: true
    sync-on-startup: true`)

async function copyConfigSnippet() {
  try {
    await navigator.clipboard.writeText(configSnippet.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

onMounted(refresh)

async function refresh() {
  const { data } = await getScanProjects()
  const found =
    data.find((item) => item.projectCode === projectCode.value || String(item.id) === projectCode.value) || null
  projectStore.projects = data
  if (found?.id) {
    try {
      const { data: detail } = await getScanProjectDetail(found.id)
      project.value = detail
      syncScanSettingsFormFromProject()
    } catch {
      project.value = found
      syncScanSettingsFormFromProject()
    }
  } else {
    project.value = found
  }
  await loadInstances()
}

function syncScanSettingsFormFromProject() {
  const p = project.value
  if (!p) return
  const s = p.scanSettings
  const b = getDefaultScanSettings()
  if (!s) {
    Object.assign(scanSettingsForm, b)
    return
  }
  const df = s.defaultFlags ?? b.defaultFlags
  const dOrder = s.descriptionSourceOrder?.length
    ? ([...s.descriptionSourceOrder] as DescriptionSource[])
    : ([...b.descriptionSourceOrder] as DescriptionSource[])
  const pOrder = s.paramDescriptionSourceOrder?.length
    ? ([...s.paramDescriptionSourceOrder] as ParamDescriptionSource[])
    : ([...b.paramDescriptionSourceOrder] as ParamDescriptionSource[])
  Object.assign(scanSettingsForm, {
    descriptionSourceOrder: dOrder,
    paramDescriptionSourceOrder: pOrder,
    descriptionSourceEnabled: buildSourceEnabledMap(dOrder, s.descriptionSourceEnabled),
    paramDescriptionSourceEnabled: buildParamSourceEnabledMap(pOrder, s.paramDescriptionSourceEnabled),
    onlyRestController: s.onlyRestController ?? b.onlyRestController,
    httpMethodWhitelist: s.httpMethodWhitelist != null ? [...s.httpMethodWhitelist] : [],
    classIncludeRegex: s.classIncludeRegex ?? '',
    classExcludeRegex: s.classExcludeRegex ?? '',
    skipDeprecated: s.skipDeprecated ?? false,
    defaultFlags: { ...b.defaultFlags, ...df },
    incrementalMode: s.incrementalMode ?? b.incrementalMode,
  } as ScanSettings)
}

function buildSourceEnabledMap(
  order: DescriptionSource[],
  fromApi: ScanSettings['descriptionSourceEnabled'] | undefined,
): ScanSettings['descriptionSourceEnabled'] {
  const o: ScanSettings['descriptionSourceEnabled'] = {}
  for (const k of order) {
    o[k] = fromApi?.[k] !== false
  }
  return o
}

function buildParamSourceEnabledMap(
  order: ParamDescriptionSource[],
  fromApi: ScanSettings['paramDescriptionSourceEnabled'] | undefined,
): ScanSettings['paramDescriptionSourceEnabled'] {
  const o: ScanSettings['paramDescriptionSourceEnabled'] = {}
  for (const k of order) {
    o[k] = fromApi?.[k] !== false
  }
  return o
}

function setDescriptionSourceEnabled(k: DescriptionSource, v: boolean) {
  scanSettingsForm.descriptionSourceEnabled[k] = v
}

function setParamDescriptionSourceEnabled(k: ParamDescriptionSource, v: boolean) {
  scanSettingsForm.paramDescriptionSourceEnabled[k] = v
}

function moveDescriptionOrder(i: number, d: number) {
  const arr = scanSettingsForm.descriptionSourceOrder
  const j = i + d
  if (j < 0 || j >= arr.length) return
  const tmp = arr[i]!
  arr[i] = arr[j]!
  arr[j] = tmp
}

function moveParamOrder(i: number, d: number) {
  const arr = scanSettingsForm.paramDescriptionSourceOrder
  const j = i + d
  if (j < 0 || j >= arr.length) return
  const tmp = arr[i]!
  arr[i] = arr[j]!
  arr[j] = tmp
}

async function handleSaveScanSettings() {
  if (!project.value?.id) return
  const payload: ScanSettings = {
    descriptionSourceOrder: [...scanSettingsForm.descriptionSourceOrder],
    paramDescriptionSourceOrder: [...scanSettingsForm.paramDescriptionSourceOrder],
    descriptionSourceEnabled: { ...scanSettingsForm.descriptionSourceEnabled },
    paramDescriptionSourceEnabled: { ...scanSettingsForm.paramDescriptionSourceEnabled },
    onlyRestController: scanSettingsForm.onlyRestController,
    httpMethodWhitelist: [...scanSettingsForm.httpMethodWhitelist],
    classIncludeRegex: scanSettingsForm.classIncludeRegex?.trim() ?? '',
    classExcludeRegex: scanSettingsForm.classExcludeRegex?.trim() ?? '',
    skipDeprecated: scanSettingsForm.skipDeprecated,
    defaultFlags: { ...scanSettingsForm.defaultFlags },
    incrementalMode: scanSettingsForm.incrementalMode,
  }
  scanSettingsSaving.value = true
  try {
    const { data } = await updateScanProjectScanSettings(project.value.id, payload)
    if (data) {
      project.value = data
      syncScanSettingsFormFromProject()
    }
    ElMessage.success('扫描设置已保存')
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
  } finally {
    scanSettingsSaving.value = false
  }
}

async function loadInstances() {
  if (!projectCode.value) return
  loadingInstances.value = true
  try {
    const { data } = await listRegistryProjectInstances(projectCode.value)
    instances.value = data
  } finally {
    loadingInstances.value = false
  }
}

function setCurrentProject() {
  if (!project.value) return
  projectStore.setCurrentProject(project.value.id)
  ElMessage.success(`已切换到项目：${project.value.name}`)
}

function goCapability(path: string) {
  if (project.value) {
    projectStore.setCurrentProject(project.value.id)
    router.push({ path, query: { projectId: project.value.id } })
  }
}

function goApiCatalog() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'ScanProjectDetail', params: { id: String(project.value.id) } })
}

function goCapabilitySync() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'CapabilitySyncDebug' })
}
</script>

<style scoped lang="scss">
.page-container {
  padding: 24px;
}

.page-header,
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header {
  margin-bottom: 16px;

  h2 {
    margin: 4px 0 6px;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
  }
}

.actions,
.quick-actions {
  display: flex;
  gap: 8px;
}

.quick-actions {
  margin-top: 16px;
}

.section-card {
  margin-top: 16px;
}

.config-example-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.registry-scan-alert {
  margin-bottom: 16px;
}

.scan-settings-form-registry {
  max-width: 800px;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: 640px;
}

.order-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.order-label {
  flex: 1;
  min-width: 0;
  font-size: 14px;
}

.order-source-switch {
  flex-shrink: 0;
}

.http-method-select-registry {
  min-width: 280px;
}

.incr-radio-registry {
  flex-wrap: wrap;
}

.switch-group-registry {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.scan-settings-registry {
  margin-top: 16px;
}
</style>
