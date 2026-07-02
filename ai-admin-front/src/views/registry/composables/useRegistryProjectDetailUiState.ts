import { computed, reactive, ref } from 'vue'
import type { ScanProjectUpsertRequest } from '@/types/scanProject'
import { emptyRegistryProjectEditForm } from '@/views/registry/registryProjectDetailViewModel'

export function useRegistryProjectDetailUiState() {
  const editDialogVisible = ref(false)
  const editSaving = ref(false)
  const deleteLoading = ref(false)
  const purgingOffline = ref(false)
  const editAccessLockedToSdk = ref(false)

  const editForm = reactive<ScanProjectUpsertRequest>(emptyRegistryProjectEditForm())
  const editCredentialForm = reactive({
    appKey: '',
    appSecret: '',
  })

  const isEditingSdkProject = computed(() => editForm.projectKind === 'REGISTERED')

  return {
    editDialogVisible,
    editSaving,
    deleteLoading,
    purgingOffline,
    editAccessLockedToSdk,
    editForm,
    editCredentialForm,
    isEditingSdkProject,
  }
}
