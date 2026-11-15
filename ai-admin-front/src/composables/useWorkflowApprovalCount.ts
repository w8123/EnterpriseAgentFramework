import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { countWorkflowApprovals } from '@/api/workflowApprovals'
import { platformSessionUser } from '@/auth/platformSession'

/** 侧边栏「待办与审核」角标：指派给我、还没处理的工作流审批数。 */
const count = ref(0)
let timer: ReturnType<typeof setInterval> | null = null
let users = 0
let inflight: Promise<void> | null = null

const POLL_MS = 60_000

export async function refreshWorkflowApprovalCount(): Promise<void> {
  if (!platformSessionUser.value) {
    count.value = 0
    return
  }
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const { data } = await countWorkflowApprovals()
      count.value = Math.max(0, Number(data?.count ?? 0) || 0)
    } catch {
      // 角标只是提示；接口暂时不可用时保持上一次的数字。
    } finally {
      inflight = null
    }
  })()
  return inflight
}

export function useWorkflowApprovalCount() {
  onMounted(() => {
    users += 1
    if (users === 1) {
      void refreshWorkflowApprovalCount()
      timer = setInterval(() => {
        if (typeof document === 'undefined' || document.visibilityState !== 'hidden') {
          void refreshWorkflowApprovalCount()
        }
      }, POLL_MS)
    }
  })
  onBeforeUnmount(() => {
    users = Math.max(0, users - 1)
    if (users === 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  })
  watch(() => platformSessionUser.value?.userId, () => { void refreshWorkflowApprovalCount() })
  return { count }
}
