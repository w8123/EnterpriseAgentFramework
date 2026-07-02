import { computed, reactive, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  updateScanProjectAuthSettings,
  updateScanProjectScanSettings,
} from '@/api/scanProject'
import type {
  DescriptionSource,
  ParamDescriptionSource,
  ScanProject,
  ScanSettings,
} from '@/types/scanProject'
import { getDefaultScanSettings } from '@/types/scanProject'
import { formatProjectKindLabel } from '@/utils/projectLabels'

export interface UseScanProjectSettingsDeps {
  projectId: Readonly<Ref<number>>
  project: Ref<ScanProject | null>
  refreshAll: () => Promise<void>
}

export function useScanProjectSettings(deps: UseScanProjectSettingsDeps) {
  const scanSettingsDrawerVisible = ref(false)
  const authDrawerVisible = ref(false)
  const aiSettingsDrawerVisible = ref(false)
  const modelGenerateDrawerVisible = ref(false)
  const scanRulesDrawerVisible = ref(false)
  const opsDrawerVisible = ref(false)

  const authSaving = ref(false)
  const authForm = reactive({
    authType: 'none' as 'none' | 'api_key',
    authApiKeyIn: 'header' as 'header' | 'query',
    authApiKeyName: '',
    authApiKeyValue: '',
  })

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

  const isOpenApiMode = computed(() => deps.project.value?.scanType === 'openapi')

  const lastScannedDisplay = computed(() => {
    const t = deps.project.value?.lastScannedAt
    if (!t) return ''
    const d = new Date(t)
    if (Number.isNaN(d.getTime())) return t
    return d.toLocaleString()
  })

  const projectAccessLabel = computed(() => formatProjectKindLabel(deps.project.value?.projectKind || 'SCAN'))

  function buildSourceEnabledMap(
    order: DescriptionSource[],
    fromApi: ScanSettings['descriptionSourceEnabled'] | undefined,
  ): ScanSettings['descriptionSourceEnabled'] {
    const enabled: ScanSettings['descriptionSourceEnabled'] = {}
    for (const key of order) {
      enabled[key] = fromApi?.[key] !== false
    }
    return enabled
  }

  function buildParamSourceEnabledMap(
    order: ParamDescriptionSource[],
    fromApi: ScanSettings['paramDescriptionSourceEnabled'] | undefined,
  ): ScanSettings['paramDescriptionSourceEnabled'] {
    const enabled: ScanSettings['paramDescriptionSourceEnabled'] = {}
    for (const key of order) {
      enabled[key] = fromApi?.[key] !== false
    }
    return enabled
  }

  function syncScanSettingsFormFromProject() {
    const project = deps.project.value
    if (!project) return
    const settings = project.scanSettings
    const defaults = getDefaultScanSettings()
    if (!settings) {
      Object.assign(scanSettingsForm, defaults)
      return
    }
    const defaultFlags = settings.defaultFlags ?? defaults.defaultFlags
    const descriptionOrder = settings.descriptionSourceOrder?.length
      ? ([...settings.descriptionSourceOrder] as DescriptionSource[])
      : ([...defaults.descriptionSourceOrder] as DescriptionSource[])
    const paramOrder = settings.paramDescriptionSourceOrder?.length
      ? ([...settings.paramDescriptionSourceOrder] as ParamDescriptionSource[])
      : ([...defaults.paramDescriptionSourceOrder] as ParamDescriptionSource[])
    Object.assign(scanSettingsForm, {
      descriptionSourceOrder: descriptionOrder,
      paramDescriptionSourceOrder: paramOrder,
      descriptionSourceEnabled: buildSourceEnabledMap(descriptionOrder, settings.descriptionSourceEnabled),
      paramDescriptionSourceEnabled: buildParamSourceEnabledMap(
        paramOrder,
        settings.paramDescriptionSourceEnabled,
      ),
      onlyRestController: settings.onlyRestController ?? defaults.onlyRestController,
      httpMethodWhitelist: settings.httpMethodWhitelist != null ? [...settings.httpMethodWhitelist] : [],
      classIncludeRegex: settings.classIncludeRegex ?? '',
      classExcludeRegex: settings.classExcludeRegex ?? '',
      skipDeprecated: settings.skipDeprecated ?? false,
      defaultFlags: { ...defaults.defaultFlags, ...defaultFlags },
      incrementalMode: settings.incrementalMode ?? defaults.incrementalMode,
    } as ScanSettings)
  }

  function syncAuthFormFromProject() {
    const project = deps.project.value
    if (!project) return
    authForm.authType = project.authType === 'api_key' ? 'api_key' : 'none'
    authForm.authApiKeyIn = project.authApiKeyIn === 'query' ? 'query' : 'header'
    authForm.authApiKeyName = project.authApiKeyName ?? ''
    authForm.authApiKeyValue = project.authApiKeyValue ?? ''
  }

  function setDescriptionSourceEnabled(key: DescriptionSource, value: boolean) {
    scanSettingsForm.descriptionSourceEnabled[key] = value
  }

  function setParamDescriptionSourceEnabled(key: ParamDescriptionSource, value: boolean) {
    scanSettingsForm.paramDescriptionSourceEnabled[key] = value
  }

  function moveDescriptionOrder(index: number, direction: number) {
    const list = scanSettingsForm.descriptionSourceOrder
    const nextIndex = index + direction
    if (nextIndex < 0 || nextIndex >= list.length) return
    const current = list[index]
    list[index] = list[nextIndex]
    list[nextIndex] = current
  }

  function moveParamOrder(index: number, direction: number) {
    const list = scanSettingsForm.paramDescriptionSourceOrder
    const nextIndex = index + direction
    if (nextIndex < 0 || nextIndex >= list.length) return
    const current = list[index]
    list[index] = list[nextIndex]
    list[nextIndex] = current
  }

  async function handleSaveScanSettings() {
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
      const { data } = await updateScanProjectScanSettings(deps.projectId.value, payload)
      if (data) {
        deps.project.value = data
        syncScanSettingsFormFromProject()
      }
      ElMessage.success(
        deps.project.value?.projectKind === 'REGISTERED' || deps.project.value?.projectKind === 'HYBRID'
          ? '扫描设置已保存。SDK 下次同步能力时将按新规则解析；已关联全局 Tool 的接口请在目录中使用「更新到Tool」。'
          : '扫描设置已保存',
      )
      scanSettingsDrawerVisible.value = false
      scanRulesDrawerVisible.value = false
      opsDrawerVisible.value = false
    } catch (error) {
      ElMessage.error((error as Error).message || '保存失败')
    } finally {
      scanSettingsSaving.value = false
    }
  }

  async function saveAuthSettings() {
    if (authForm.authType === 'api_key') {
      if (!authForm.authApiKeyName.trim()) {
        ElMessage.warning('请填写参数名 (Key)')
        return
      }
      if (!authForm.authApiKeyValue.trim()) {
        ElMessage.warning('请填写参数值 (Value)')
        return
      }
    }
    authSaving.value = true
    try {
      await updateScanProjectAuthSettings(deps.projectId.value, {
        authType: authForm.authType,
        authApiKeyIn: authForm.authType === 'api_key' ? authForm.authApiKeyIn : null,
        authApiKeyName: authForm.authType === 'api_key' ? authForm.authApiKeyName.trim() : null,
        authApiKeyValue: authForm.authType === 'api_key' ? authForm.authApiKeyValue : null,
      })
      ElMessage.success('鉴权设置已保存')
      authDrawerVisible.value = false
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '保存失败')
    } finally {
      authSaving.value = false
    }
  }

  return {
    scanSettingsDrawerVisible,
    authDrawerVisible,
    aiSettingsDrawerVisible,
    modelGenerateDrawerVisible,
    scanRulesDrawerVisible,
    opsDrawerVisible,
    authSaving,
    authForm,
    scanSettingsForm,
    scanSettingsSaving,
    descriptionSourceLabels,
    paramSourceLabels,
    allHttpMethods,
    isOpenApiMode,
    lastScannedDisplay,
    projectAccessLabel,
    syncScanSettingsFormFromProject,
    syncAuthFormFromProject,
    setDescriptionSourceEnabled,
    setParamDescriptionSourceEnabled,
    moveDescriptionOrder,
    moveParamOrder,
    handleSaveScanSettings,
    saveAuthSettings,
  }
}
