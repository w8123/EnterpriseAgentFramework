import { computed, onBeforeUnmount, reactive, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  updateScanProjectScanSettings,
} from '@/api/scanProject'
import type {
  DescriptionSource,
  ParamDescriptionSource,
  ScanProject,
  ScanSettings,
} from '@/types/scanProject'
import { getDefaultScanSettings } from '@/types/scanProject'

export interface UseSourceSettingsDeps {
  projectId: Readonly<Ref<number>>
  project: Ref<ScanProject | null>
}

export function useSourceSettings(deps: UseSourceSettingsDeps) {
  let disposed = false
  onBeforeUnmount(() => { disposed = true })
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
      defaultFlags: {
        enabled: defaultFlags.enabled ?? defaults.defaultFlags.enabled,
      },
      incrementalMode: settings.incrementalMode ?? defaults.incrementalMode,
    } as ScanSettings)
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
    if (disposed || scanSettingsSaving.value) return
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
      defaultFlags: { enabled: scanSettingsForm.defaultFlags.enabled },
      incrementalMode: scanSettingsForm.incrementalMode,
    }
    scanSettingsSaving.value = true
    try {
      const { data } = await updateScanProjectScanSettings(deps.projectId.value, payload)
      if (disposed) return
      if (!data || data.id !== deps.projectId.value) throw new Error('Invalid project settings owner')
      deps.project.value = data
      syncScanSettingsFormFromProject()
      ElMessage.success(
        deps.project.value?.projectKind === 'REGISTERED' || deps.project.value?.projectKind === 'HYBRID'
          ? '扫描设置已保存。SDK 下次同步来源时将按新规则解析；请在业务方法/API 目录核对变化并显式接纳。'
          : '扫描设置已保存',
      )
    } catch {
      if (!disposed) ElMessage.error('扫描设置保存失败，请重试；当前输入已保留')
    } finally {
      scanSettingsSaving.value = false
    }
  }

  return {
    scanSettingsForm,
    scanSettingsSaving,
    descriptionSourceLabels,
    paramSourceLabels,
    allHttpMethods,
    isOpenApiMode,
    syncScanSettingsFormFromProject,
    setDescriptionSourceEnabled,
    setParamDescriptionSourceEnabled,
    moveDescriptionOrder,
    moveParamOrder,
    handleSaveScanSettings,
  }
}
