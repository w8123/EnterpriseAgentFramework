<script setup lang="ts">
import { computed } from 'vue'
import { Clock, Reading, Setting, Upload } from '@element-plus/icons-vue'
import type {
  DescriptionSource,
  ParamDescriptionSource,
  ScanProject,
  ScanSettings,
  SdkCapabilityScanResult,
} from '@/types/scanProject'

const props = defineProps<{
  visible: boolean
  project: ScanProject | null
  syncLoading: boolean
  syncResult: SdkCapabilityScanResult | null
  syncError: string | null
  rescanLoading: boolean
  scanSettingsForm: ScanSettings
  isOpenApiMode: boolean
  descriptionSourceLabels: Record<DescriptionSource, string>
  paramSourceLabels: Record<ParamDescriptionSource, string>
  allHttpMethods: readonly string[]
  scanSettingsSaving: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  scanSdk: []
  sdkGuide: []
  rescan: []
  setDescriptionSourceEnabled: [key: DescriptionSource, enabled: boolean]
  setParamDescriptionSourceEnabled: [key: ParamDescriptionSource, enabled: boolean]
  moveDescriptionOrder: [index: number, delta: number]
  moveParamOrder: [index: number, delta: number]
  saveScanSettings: []
}>()

type AddInterfaceTab = 'sdk' | 'aiCoding'

const activeTab = defineModel<AddInterfaceTab>('activeTab', { default: 'sdk' })
const scanSettingsVisible = defineModel<boolean>('settingsVisible', { default: false })

const dialogVisible = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

const resultMetrics = computed(() => [
  { label: '接口数', value: props.syncResult?.capabilityCount ?? 0 },
  { label: '实例', value: props.syncResult?.instanceId || '-' },
])

function trimTrailingSlash(value: string) {
  return value.replace(/\/+$/, '')
}

function normalizeContextPath(value: string | null | undefined) {
  const text = value?.trim()
  if (!text || text === '/') return ''
  return `/${text.replace(/^\/+|\/+$/g, '')}`
}

const sdkSyncTarget = computed(() => {
  if (props.syncResult?.targetUrl) return props.syncResult.targetUrl
  const baseUrl = props.project?.baseUrl?.trim()
  if (!baseUrl) return '/reachai/registry/capabilities/sync'
  return `${trimTrailingSlash(baseUrl)}${normalizeContextPath(props.project?.contextPath)}/reachai/registry/capabilities/sync`
})

const loopbackTarget = computed(() => {
  try {
    const host = new URL(sdkSyncTarget.value).hostname.toLowerCase()
    return host === 'localhost' || host === '127.0.0.1' || host === '::1'
  } catch {
    return false
  }
})

const sdkSyncContractDescription = computed(() =>
  `ReachAI 服务端将 POST ${sdkSyncTarget.value}。请确保该地址从 ReachAI 所在网络可达，并让业务登录/JWT与 CSRF 放行该路径；Starter 仍会校验 X-ReachAI-* 注册签名。`,
)
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    width="820px"
    class="add-interface-dialog"
    append-to-body
    destroy-on-close
  >
    <template #header>
      <div class="add-interface-dialog-header">
        <div class="add-interface-dialog-title-group">
          <div class="add-interface-dialog-title-copy">
            <span class="add-interface-dialog-title">添加接口</span>
            <span class="add-interface-dialog-subtitle">同步 SDK 或导入扫描结果，沉淀为当前项目接口目录</span>
          </div>
          <el-tag v-if="project?.projectCode" class="add-interface-project-tag" effect="plain">
            {{ project.projectCode }}
          </el-tag>
        </div>
        <div class="add-interface-dialog-actions">
          <el-button
            class="add-interface-settings-button"
            :class="{ 'is-active': scanSettingsVisible }"
            :icon="Setting"
            size="small"
            @click="scanSettingsVisible = !scanSettingsVisible"
          >
            设置
          </el-button>
        </div>
      </div>
    </template>

    <section v-if="scanSettingsVisible" class="scan-rules-panel add-interface-settings-panel">
      <div class="scan-rules-title">
        <strong>扫描解析设置</strong>
        <span>用于控制扫描时如何解析接口说明、参数说明和默认开关，不属于接口来源。</span>
      </div>
      <el-alert
        v-if="project?.projectKind === 'REGISTERED' || project?.projectKind === 'HYBRID'"
        class="scan-settings-registry-alert"
        type="success"
        :closable="false"
        show-icon
        title="SDK 接入项目"
        description="此处配置保存在 scan_settings，业务系统 SDK 下次同步来源时按此解析说明与参数。契约变化请到业务方法/API 目录核对并显式接纳，无需人工复制 Tool。"
      />
      <el-alert
        v-if="isOpenApiMode"
        class="scan-settings-mode-alert"
        type="info"
        :closable="false"
        show-icon
        title="当前为 OpenAPI/Auto-OpenAPI 方式：描述来源、类名正则等仅对 Controller 代码扫描有效。"
      />
      <el-form label-position="top" class="scan-settings-form drawer-form" @submit.prevent>
        <el-form-item
          label="接口说明来源"
          class="settings-form-section settings-order-section"
          :class="{ 'is-disabled-form-item': isOpenApiMode }"
        >
          <div v-if="!isOpenApiMode" class="order-list">
            <div v-for="(k, i) in scanSettingsForm.descriptionSourceOrder" :key="k" class="order-item">
              <span class="order-item-index">{{ i + 1 }}</span>
              <span class="order-label">{{ descriptionSourceLabels[k] || k }}</span>
              <div class="order-controls">
                <el-switch
                  :model-value="scanSettingsForm.descriptionSourceEnabled[k] !== false"
                  class="order-source-switch"
                  size="small"
                  inline-prompt
                  active-text="启用"
                  inactive-text="停用"
                  :disabled="isOpenApiMode"
                  @update:model-value="(v: boolean) => emit('setDescriptionSourceEnabled', k, v)"
                />
                <el-button-group class="order-move-actions">
                  <el-button size="small" :disabled="i === 0" @click="emit('moveDescriptionOrder', i, -1)">上移</el-button>
                  <el-button
                    size="small"
                    :disabled="i === scanSettingsForm.descriptionSourceOrder.length - 1"
                    @click="emit('moveDescriptionOrder', i, 1)"
                  >下移</el-button>
                </el-button-group>
              </div>
            </div>
          </div>
          <span v-else class="el-text is-secondary">OpenAPI 扫描从规范读取 summary/description，无需本项</span>
        </el-form-item>
        <el-form-item
          label="参数说明来源"
          class="settings-form-section settings-order-section"
          :class="{ 'is-disabled-form-item': isOpenApiMode }"
        >
          <div v-if="!isOpenApiMode" class="order-list">
            <div
              v-for="(k, i) in scanSettingsForm.paramDescriptionSourceOrder"
              :key="k"
              class="order-item"
            >
              <span class="order-item-index">{{ i + 1 }}</span>
              <span class="order-label">{{ paramSourceLabels[k] || k }}</span>
              <div class="order-controls">
                <el-switch
                  :model-value="scanSettingsForm.paramDescriptionSourceEnabled[k] !== false"
                  class="order-source-switch"
                  size="small"
                  inline-prompt
                  active-text="启用"
                  inactive-text="停用"
                  :disabled="isOpenApiMode"
                  @update:model-value="(v: boolean) => emit('setParamDescriptionSourceEnabled', k, v)"
                />
                <el-button-group class="order-move-actions">
                  <el-button size="small" :disabled="i === 0" @click="emit('moveParamOrder', i, -1)">上移</el-button>
                  <el-button
                    size="small"
                    :disabled="i === scanSettingsForm.paramDescriptionSourceOrder.length - 1"
                    @click="emit('moveParamOrder', i, 1)"
                  >下移</el-button>
                </el-button-group>
              </div>
            </div>
          </div>
          <span v-else class="el-text is-secondary">此扫描方式不解析 Controller 形参与 DTO，无需配置</span>
        </el-form-item>
        <el-form-item
          label="仅 @RestController"
          class="settings-form-section settings-switch-section"
          :class="{ 'is-disabled-form-item': isOpenApiMode }"
        >
          <el-switch v-model="scanSettingsForm.onlyRestController" :disabled="isOpenApiMode" />
        </el-form-item>
        <el-form-item label="HTTP 白名单" class="settings-form-section">
          <el-select
            v-model="scanSettingsForm.httpMethodWhitelist"
            multiple
            clearable
            filterable
            class="http-method-select"
            placeholder="留空=全部，OpenAPI/Controller 均会过滤"
          >
            <el-option v-for="m in allHttpMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="跳过 deprecated" class="settings-form-section settings-switch-section">
          <div class="settings-inline-control">
            <el-switch v-model="scanSettingsForm.skipDeprecated" />
            <span class="el-text is-secondary inline-hint">Controller：@Deprecated/注释；OpenAPI：operation.deprecated</span>
          </div>
        </el-form-item>
        <el-form-item
          label="类名包含正则"
          class="settings-form-section"
          :class="{ 'is-disabled-form-item': isOpenApiMode }"
        >
          <el-input v-model="scanSettingsForm.classIncludeRegex" clearable :disabled="isOpenApiMode" placeholder="例如 .*\.controller\..*" />
        </el-form-item>
        <el-form-item
          label="类名排除正则"
          class="settings-form-section"
          :class="{ 'is-disabled-form-item': isOpenApiMode }"
        >
          <el-input v-model="scanSettingsForm.classExcludeRegex" clearable :disabled="isOpenApiMode" placeholder="留空=不排除" />
        </el-form-item>
        <el-form-item label="新接口默认开关" class="settings-form-section settings-full-section">
          <div class="switch-group settings-switch-group">
            <label class="settings-switch-pill">
              <el-switch v-model="scanSettingsForm.defaultFlags.enabled" />
              <span>启用</span>
            </label>
          </div>
        </el-form-item>
        <el-form-item label="增量扫描" class="settings-form-section settings-full-section">
          <el-radio-group v-model="scanSettingsForm.incrementalMode" class="incr-radio">
            <el-radio-button value="OFF">关闭</el-radio-button>
            <el-radio-button value="MTIME">仅变更文件</el-radio-button>
            <el-radio-button value="GIT_DIFF">Git 差异</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div class="scan-rules-actions">
        <el-button
          type="warning"
          plain
          :disabled="project?.projectKind === 'REGISTERED'"
          :loading="rescanLoading"
          @click="emit('rescan')"
        >
          重新扫描
        </el-button>
        <el-button type="primary" :loading="scanSettingsSaving" @click="emit('saveScanSettings')">
          保存扫描设置
        </el-button>
      </div>
    </section>

    <el-tabs v-if="!scanSettingsVisible" v-model="activeTab" class="source-import-tabs">
      <el-tab-pane label="SDK 同步" name="sdk">
        <section class="source-panel sdk-import-panel">
          <div class="source-panel-main">
            <div class="source-panel-copy">
              <h4>同步在线业务系统</h4>
              <p>通过 ReachAI SDK 拉取最新接口，写入当前 API 目录。</p>
            </div>
          </div>

          <div class="sdk-import-actions">
            <el-button
              type="primary"
              :icon="Upload"
              :loading="syncLoading"
              @click="emit('scanSdk')"
            >
              扫描并同步接口
            </el-button>
            <el-button :icon="Reading" @click="emit('sdkGuide')">
              SDK 接入指引
            </el-button>
          </div>

          <el-alert
            class="sdk-sync-contract-alert"
            type="info"
            :closable="false"
            show-icon
            title="ReachAI 将主动调用业务系统"
            :description="sdkSyncContractDescription"
          />

          <el-alert
            v-if="loopbackTarget"
            class="sdk-sync-loopback-alert"
            type="warning"
            :closable="false"
            show-icon
            title="当前回调地址使用 localhost"
            description="只有 ReachAI 与业务系统运行在同一主机或共享网络命名空间时才可达；分机、容器或集群部署请改为 ReachAI 实际可访问的域名或 IP。"
          />

          <el-alert
            v-if="syncError"
            class="sdk-sync-error-alert"
            type="error"
            :closable="false"
            show-icon
            title="最近一次同步失败"
            :description="syncError"
          />

          <div v-if="syncResult" class="sdk-import-result">
            <div class="result-title">
              <el-icon><Clock /></el-icon>
              <span>最近一次扫描结果</span>
              <small>{{ syncResult.targetUrl }}</small>
            </div>
            <div class="result-metrics">
              <div v-for="metric in resultMetrics" :key="metric.label" class="result-metric">
                <strong>{{ metric.value }}</strong>
                <span>{{ metric.label }}</span>
              </div>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="AI Coding 扫描" name="aiCoding">
        <section class="source-panel ai-coding-placeholder">
          <div class="source-panel-main">
            <div class="source-panel-copy">
              <h4>导入代码扫描结果</h4>
              <p>从代码仓库或 AI Coding 结果导入接口候选。</p>
            </div>
          </div>
          <el-tag effect="plain" type="info">待接入</el-tag>
        </section>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>
