<script setup lang="ts">
import type {
  DescriptionSource,
  ParamDescriptionSource,
  ScanProject,
  ScanSettings,
} from '@/types/scanProject'

defineProps<{
  project: ScanProject | null
  scanSettingsForm: ScanSettings
  isOpenApiMode: boolean
  descriptionSourceLabels: Record<DescriptionSource, string>
  paramSourceLabels: Record<ParamDescriptionSource, string>
  allHttpMethods: readonly string[]
  scanSettingsSaving: boolean
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  setDescriptionSourceEnabled: [key: DescriptionSource, enabled: boolean]
  setParamDescriptionSourceEnabled: [key: ParamDescriptionSource, enabled: boolean]
  moveDescriptionOrder: [index: number, delta: number]
  moveParamOrder: [index: number, delta: number]
  saveScanSettings: []
}>()
</script>

<template>
  <el-drawer
    v-model="visible"
    size="680px"
    title="扫描解析规则"
    destroy-on-close
    append-to-body
  >
    <el-alert
      v-if="project?.projectKind === 'REGISTERED' || project?.projectKind === 'HYBRID'"
      class="scan-settings-registry-alert"
      type="success"
      :closable="false"
      show-icon
      title="SDK / 注册中心项目"
      description="此处配置保存在 scan_settings，业务系统 SDK 下次同步接口能力时按此解析说明与参数。已关联全局 Tool 的接口请在目录中使用「更新到Tool」。"
    />
    <el-alert
      v-if="isOpenApiMode"
      class="scan-settings-mode-alert"
      type="info"
      :closable="false"
      show-icon
      title="当前为 OpenAPI/Auto-OpenAPI 方式：描述来源、类名正则等仅对 Controller 代码扫描有效。"
    />
    <el-form label-width="160px" class="scan-settings-form drawer-form" @submit.prevent>
      <el-form-item label="接口说明来源" :class="{ 'is-disabled-form-item': isOpenApiMode }">
        <div v-if="!isOpenApiMode" class="order-list">
          <div v-for="(k, i) in scanSettingsForm.descriptionSourceOrder" :key="k" class="order-item">
            <span class="order-label">{{ descriptionSourceLabels[k] || k }}</span>
            <el-switch
              :model-value="scanSettingsForm.descriptionSourceEnabled[k] !== false"
              class="order-source-switch"
              size="small"
              :disabled="isOpenApiMode"
              @update:model-value="(v: boolean) => emit('setDescriptionSourceEnabled', k, v)"
            />
            <el-button-group>
              <el-button size="small" :disabled="i === 0" @click="emit('moveDescriptionOrder', i, -1)">上移</el-button>
              <el-button
                size="small"
                :disabled="i === scanSettingsForm.descriptionSourceOrder.length - 1"
                @click="emit('moveDescriptionOrder', i, 1)"
              >下移</el-button>
            </el-button-group>
          </div>
        </div>
        <span v-else class="el-text is-secondary">OpenAPI 扫描从规范读取 summary/description，无需本项</span>
      </el-form-item>
      <el-form-item label="参数说明来源" :class="{ 'is-disabled-form-item': isOpenApiMode }">
        <div v-if="!isOpenApiMode" class="order-list">
          <div
            v-for="(k, i) in scanSettingsForm.paramDescriptionSourceOrder"
            :key="k"
            class="order-item"
          >
            <span class="order-label">{{ paramSourceLabels[k] || k }}</span>
            <el-switch
              :model-value="scanSettingsForm.paramDescriptionSourceEnabled[k] !== false"
              class="order-source-switch"
              size="small"
              :disabled="isOpenApiMode"
              @update:model-value="(v: boolean) => emit('setParamDescriptionSourceEnabled', k, v)"
            />
            <el-button-group>
              <el-button size="small" :disabled="i === 0" @click="emit('moveParamOrder', i, -1)">上移</el-button>
              <el-button
                size="small"
                :disabled="i === scanSettingsForm.paramDescriptionSourceOrder.length - 1"
                @click="emit('moveParamOrder', i, 1)"
              >下移</el-button>
            </el-button-group>
          </div>
        </div>
        <span v-else class="el-text is-secondary">此扫描方式不解析 Controller 形参与 DTO，无需配置</span>
      </el-form-item>
      <el-form-item label="仅 @RestController" :class="{ 'is-disabled-form-item': isOpenApiMode }">
        <el-switch v-model="scanSettingsForm.onlyRestController" :disabled="isOpenApiMode" />
      </el-form-item>
      <el-form-item label="HTTP 白名单">
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
      <el-form-item label="跳过 deprecated">
        <el-switch v-model="scanSettingsForm.skipDeprecated" />
        <span class="el-text is-secondary inline-hint">Controller：@Deprecated/注释；OpenAPI：operation.deprecated</span>
      </el-form-item>
      <el-form-item label="类名包含正则" :class="{ 'is-disabled-form-item': isOpenApiMode }">
        <el-input v-model="scanSettingsForm.classIncludeRegex" clearable :disabled="isOpenApiMode" placeholder="例如 .*\.controller\..*" />
      </el-form-item>
      <el-form-item label="类名排除正则" :class="{ 'is-disabled-form-item': isOpenApiMode }">
        <el-input v-model="scanSettingsForm.classExcludeRegex" clearable :disabled="isOpenApiMode" placeholder="留空=不排除" />
      </el-form-item>
      <el-form-item label="新接口默认开关">
        <div class="switch-group">
          <el-switch v-model="scanSettingsForm.defaultFlags.enabled" />
          <span>启用</span>
          <el-switch v-model="scanSettingsForm.defaultFlags.agentVisible" />
          <span>Agent 可见</span>
          <el-switch v-model="scanSettingsForm.defaultFlags.lightweightEnabled" />
          <span>轻量调用</span>
        </div>
      </el-form-item>
      <el-form-item label="增量扫描">
        <el-radio-group v-model="scanSettingsForm.incrementalMode" class="incr-radio">
          <el-radio-button label="OFF">关闭</el-radio-button>
          <el-radio-button label="MTIME">仅变更文件</el-radio-button>
          <el-radio-button label="GIT_DIFF">Git 差异</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="scanSettingsSaving" @click="emit('saveScanSettings')">保存扫描设置</el-button>
    </template>
  </el-drawer>
</template>
