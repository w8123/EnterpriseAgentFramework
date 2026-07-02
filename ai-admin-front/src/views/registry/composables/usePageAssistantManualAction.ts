import { reactive, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  declarePageActionCatalog,
  type PageActionManualDeclarePayload,
  type PageRegistryView,
} from '@/api/embedOps'
import { parseJsonObject } from '../pageAssistantWizardUtils'

interface UsePageAssistantManualActionDeps {
  projectCode: ComputedRef<string>
  selectedPageKey: Ref<string>
  selectedPageIdentity: Ref<string>
  focusedStep: Ref<string>
  loadAll: () => Promise<void>
  pageIdentity: (page: PageRegistryView) => string
}

export function usePageAssistantManualAction(deps: UsePageAssistantManualActionDeps) {
  const manualDialogVisible = ref(false)
  const manualSubmitting = ref(false)
  const manualForm = reactive({
    pageKey: '',
    pageName: '',
    routePattern: '',
    actionKey: '',
    title: '',
    description: '',
    confirmRequired: false,
    inputSchemaText: '{\n  "type": "object",\n  "properties": {}\n}',
    sampleArgsText: '{}',
  })

  async function submitManualAction() {
    try {
      const payload: PageActionManualDeclarePayload = {
        projectCode: deps.projectCode.value,
        appId: deps.projectCode.value,
        pageKey: manualForm.pageKey.trim(),
        pageName: manualForm.pageName.trim(),
        routePattern: manualForm.routePattern.trim(),
        actionKey: manualForm.actionKey.trim(),
        title: manualForm.title.trim(),
        description: manualForm.description.trim(),
        confirmRequired: manualForm.confirmRequired,
        inputSchema: parseJsonObject(manualForm.inputSchemaText, 'inputSchema'),
        outputSchema: { type: 'object' },
        sampleArgs: parseJsonObject(manualForm.sampleArgsText, 'sampleArgs'),
        allowedAgentIds: [],
        status: 'ACTIVE',
      }
      if (!payload.pageKey || !payload.actionKey) {
        ElMessage.warning('请填写 pageKey 和 actionKey')
        return
      }
      manualSubmitting.value = true
      const { data } = await declarePageActionCatalog(payload)
      ElMessage.success('页面动作草案已保存')
      manualDialogVisible.value = false
      await deps.loadAll()
      deps.selectedPageKey.value = data.page.pageKey
      deps.selectedPageIdentity.value = deps.pageIdentity(data.page)
      deps.focusedStep.value = 'action'
    } catch (error) {
      ElMessage.error((error as Error).message || '保存页面动作草案失败')
    } finally {
      manualSubmitting.value = false
    }
  }

  return {
    manualDialogVisible,
    manualSubmitting,
    manualForm,
    submitManualAction,
  }
}
