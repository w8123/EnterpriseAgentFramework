import { ElMessage, ElMessageBox } from 'element-plus'
import type { ComputedRef, Ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  deleteScanProject,
  getScanProjectOperationBlockers,
  updateScanProject,
  updateScanProjectRegistryCredential,
} from '@/api/scanProject'
import {
  purgeRegistryProjectOfflineInstances,
  updateRegistryProjectInstanceStatus,
} from '@/api/registry'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import { useProjectStore } from '@/store/project'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'

export interface UseRegistryProjectDetailActionsDeps {
  project: Ref<ScanProject | null>
  projectCode: Ref<string>
  offlineInstanceCount: Readonly<Ref<number>>
  refresh: () => Promise<void>
  loadInstances: () => Promise<void>
  editDialogVisible: Ref<boolean>
  editSaving: Ref<boolean>
  deleteLoading: Ref<boolean>
  purgingOffline: Ref<boolean>
  editAccessLockedToSdk: Ref<boolean>
  editForm: ScanProjectUpsertRequest
  editCredentialForm: { appKey: string; appSecret: string }
  isEditingSdkProject: Readonly<Ref<boolean>>
}

export function useRegistryProjectDetailActions(deps: UseRegistryProjectDetailActionsDeps) {
  const router = useRouter()
  const projectStore = useProjectStore()

  async function ensureScanOperationAllowed(): Promise<boolean> {
    if (!deps.project.value?.id) return false
    try {
      const { data } = await getScanProjectOperationBlockers(deps.project.value.id)
      if (!data.blocked) return true
      await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return false
    } catch {
      ElMessage.error('检查引用关系失败')
      return false
    }
  }

  async function purgeOfflineInstances() {
    if (!deps.projectCode.value || deps.offlineInstanceCount.value === 0) return
    try {
      await ElMessageBox.confirm(
        `将删除 ${deps.offlineInstanceCount.value} 条离线/心跳超时状态的实例心跳记录。是否继续？`,
        '清理离线实例',
        { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    deps.purgingOffline.value = true
    try {
      const { data } = await purgeRegistryProjectOfflineInstances(deps.projectCode.value, 0)
      ElMessage.success(`已清理 ${data.removed} 条离线实例`)
      await deps.loadInstances()
    } catch (error) {
      ElMessage.error((error as Error).message || '清理失败')
    } finally {
      deps.purgingOffline.value = false
    }
  }

  async function setInstanceStatus(instance: ProjectInstance, status: ProjectInstance['status']) {
    try {
      await updateRegistryProjectInstanceStatus(deps.projectCode.value, {
        instanceId: instance.instanceId,
        status,
      })
      ElMessage.success(status === 'DISABLED' ? '实例已禁用' : '实例已解除禁用')
      await deps.loadInstances()
    } catch (error) {
      ElMessage.error((error as Error).message || '实例状态更新失败')
    }
  }

  function openEditDialog() {
    const p = deps.project.value
    if (!p?.id) return
    const projectKind = p.projectKind || 'REGISTERED'
    deps.editAccessLockedToSdk.value = projectKind === 'REGISTERED'
    deps.editForm.name = p.name
    deps.editForm.projectCode = p.projectCode ?? ''
    deps.editForm.projectKind = projectKind
    deps.editForm.environment = p.environment || 'dev'
    deps.editForm.owner = p.owner ?? ''
    deps.editForm.visibility = p.visibility || 'PRIVATE'
    deps.editForm.baseUrl = p.baseUrl
    deps.editForm.contextPath = p.contextPath || ''
    deps.editForm.scanPath = p.scanPath || ''
    deps.editForm.scanType = projectKind === 'REGISTERED' ? 'auto' : p.scanType || 'openapi'
    deps.editForm.specFile = p.specFile ?? ''
    deps.editCredentialForm.appKey = p.registryAppKey || ''
    deps.editCredentialForm.appSecret = ''
    deps.editDialogVisible.value = true
  }

  async function saveEditProject() {
    const p = deps.project.value
    if (!p?.id) return
    if (!deps.editForm.name.trim() || !deps.editForm.baseUrl.trim()) {
      ElMessage.warning(deps.isEditingSdkProject.value ? '请填写项目名称与 Base URL' : '请填写项目名称与项目域名')
      return
    }
    if (deps.isEditingSdkProject.value && !deps.editForm.projectCode?.trim()) {
      ElMessage.warning('请填写项目编码')
      return
    }
    if (!deps.isEditingSdkProject.value && deps.editForm.projectKind !== 'REGISTERED' && !deps.editForm.scanPath.trim()) {
      ElMessage.warning('非纯 SDK 项目请填写扫描路径')
      return
    }
    const credentialAppKey = deps.editCredentialForm.appKey.trim()
    const credentialAppSecret = deps.editCredentialForm.appSecret.trim()
    if (deps.isEditingSdkProject.value && (credentialAppKey || credentialAppSecret) && (!credentialAppKey || !credentialAppSecret)) {
      ElMessage.warning('更新接入凭据时请同时填写 App Key 和 App Secret')
      return
    }
    deps.editSaving.value = true
    try {
      const payload: ScanProjectUpsertRequest = {
        ...deps.editForm,
        projectKind: deps.editAccessLockedToSdk.value ? 'REGISTERED' : deps.editForm.projectKind,
        scanType: deps.isEditingSdkProject.value ? 'auto' : deps.editForm.scanType,
        scanPath: deps.isEditingSdkProject.value ? '' : deps.editForm.scanPath,
        specFile: !deps.isEditingSdkProject.value && deps.editForm.scanType === 'openapi' ? deps.editForm.specFile || null : null,
        contextPath: deps.isEditingSdkProject.value ? '' : deps.editForm.contextPath || '',
        owner: deps.editForm.owner || '',
      }
      const { data } = await updateScanProject(p.id, payload)
      if (deps.isEditingSdkProject.value && credentialAppKey && credentialAppSecret) {
        await updateScanProjectRegistryCredential(p.id, {
          appKey: credentialAppKey,
          appSecret: credentialAppSecret,
        })
      }
      ElMessage.success('项目已更新')
      deps.editDialogVisible.value = false
      const routeCode = deps.projectCode.value
      const newCode = (data.projectCode || '').trim()
      if (newCode && newCode !== routeCode) {
        await router.replace(`/registry/projects/${encodeURIComponent(newCode)}`)
      }
      await deps.refresh()
    } catch (error) {
      ElMessage.error((error as Error).message || '保存失败')
    } finally {
      deps.editSaving.value = false
    }
  }

  async function handleDeleteProject() {
    const p = deps.project.value
    if (!p?.id) return
    if (!(await ensureScanOperationAllowed())) return
    try {
      await ElMessageBox.confirm(
        `确认删除项目「${p.name}」吗？将删除关联扫描行、挂到本项目的全局 Tool / 粗粒度能力、模块与语义数据等（若仍存在引用则被阻止）。`,
        '删除确认',
        { type: 'warning' },
      )
    } catch {
      return
    }
    deps.deleteLoading.value = true
    try {
      await deleteScanProject(p.id)
      ElMessage.success('已删除')
      if (projectStore.currentProjectId === p.id) {
        projectStore.setCurrentProject(null)
      }
      await router.push('/registry/projects')
    } catch (error) {
      const blockers = parseScanProjectBlockersFromError(error)
      if (blockers?.blocked) {
        await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
          type: 'warning',
          confirmButtonText: '知道了',
        })
        return
      }
      ElMessage.error((error as Error).message || '删除失败')
    } finally {
      deps.deleteLoading.value = false
    }
  }

  function setCurrentProject() {
    if (!deps.project.value) return
    projectStore.setCurrentProject(deps.project.value.id)
    ElMessage.success(`已切换到项目：${deps.project.value.name}`)
  }

  return {
    purgeOfflineInstances,
    setInstanceStatus,
    openEditDialog,
    saveEditProject,
    handleDeleteProject,
    setCurrentProject,
  }
}
