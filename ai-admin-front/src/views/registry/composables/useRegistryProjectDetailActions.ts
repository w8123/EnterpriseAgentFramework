import { ElMessage, ElMessageBox } from 'element-plus'
import { onScopeDispose, watch, type Ref } from 'vue'
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
import { platformSessionId } from '@/auth/platformSession'
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

  let deletionContextGeneration = 0
  watch(() => [platformSessionId.value, deps.project.value?.id, deps.project.value?.projectCode] as const,
    () => { deletionContextGeneration++; deps.deleteLoading.value = false }, { flush: 'sync' })
  onScopeDispose(() => { deletionContextGeneration++ })
  const deletionIsCurrent = (generation: number) =>
    generation === deletionContextGeneration && Boolean(platformSessionId.value)

  async function ensureProjectDeletionAllowed(projectId: number, generation: number): Promise<boolean> {
    if (!deletionIsCurrent(generation)) return false
    try {
      const { data } = await getScanProjectOperationBlockers(projectId, 'DELETE')
      if (!deletionIsCurrent(generation)) return false
      if (!data.blocked) return true
      await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return false
    } catch {
      if (!deletionIsCurrent(generation)) return false
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
    // The credential is deliberately write-only: project detail returns only the
    // configured flag, never the stored key/secret. Keep both inputs empty so a
    // plain project edit cannot accidentally replace the credential.
    deps.editCredentialForm.appKey = ''
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
    const hasStoredRegistryCredential = Boolean(p.registryCredentialConfigured)
    const hasCredentialInput = Boolean(credentialAppKey || credentialAppSecret)
    if (deps.isEditingSdkProject.value) {
      if (!hasStoredRegistryCredential && (!credentialAppKey || !credentialAppSecret)) {
        ElMessage.warning('请填写 App Key 和 App Secret')
        return
      }
      if (hasCredentialInput && (!credentialAppKey || !credentialAppSecret)) {
        ElMessage.warning('更新对接凭据时请同时填写 App Key 和 App Secret')
        return
      }
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
    const generation = deletionContextGeneration
    if (!(await ensureProjectDeletionAllowed(p.id, generation))) return
    try {
      await ElMessageBox.confirm(
        `确认删除项目「${p.name}」吗？将删除项目及关联扫描、模块与语义数据。仍有业务方法、API 资产或受保护引用的项目无法删除。`,
        '删除确认',
        { type: 'warning' },
      )
    } catch {
      return
    }
    if (!deletionIsCurrent(generation)) return
    deps.deleteLoading.value = true
    try {
      await deleteScanProject(p.id)
      if (!deletionIsCurrent(generation)) return
      ElMessage.success('已删除')
      projectStore.clearCurrentProject(p.id)
      await router.push('/registry/projects')
    } catch (error) {
      if (!deletionIsCurrent(generation)) return
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
      if (deletionIsCurrent(generation)) deps.deleteLoading.value = false
    }
  }

  return {
    purgeOfflineInstances,
    setInstanceStatus,
    openEditDialog,
    saveEditProject,
    handleDeleteProject,
  }
}
