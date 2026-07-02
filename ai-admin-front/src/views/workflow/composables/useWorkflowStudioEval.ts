import { ElMessage } from 'element-plus'
import { computed, ref, type Ref } from 'vue'
import { debugWorkflowRun } from '@/api/workflow'
import type { WorkflowDebugRunResult, WorkflowDebugStepResult, WorkflowStudioState } from '@/types/workflow'
import { stringifyDebugPayload } from '@/views/workflow/composables/useWorkflowStudioDebugRun'

export interface WorkflowEvalCase {
  id: string
  caseNo: string
  message: string
  inputParamsJson: string
  expectedText: string
  enabled: boolean
}

export interface WorkflowEvalResult {
  id: string
  roundNo: number
  caseNo: string
  runtimeSuccess: boolean
  assertionPassed: boolean
  elapsedMs: number
  answer: string
  errorMessage: string
}

export interface UseWorkflowStudioEvalDeps {
  studio: Ref<WorkflowStudioState | null>
  buildDebugBaseRequest: () => Record<string, unknown>
  parseOptionalObject: (value: string, label: string) => Record<string, unknown> | undefined
}

export function createDefaultEvalCases(): WorkflowEvalCase[] {
  return [
    {
      id: `eval-${Date.now()}-1`,
      caseNo: 'case-001',
      message: '查询订单1001是否可以退款',
      inputParamsJson: JSON.stringify({ question: '查询订单1001是否可以退款' }, null, 2),
      expectedText: '',
      enabled: true,
    },
    {
      id: `eval-${Date.now()}-2`,
      caseNo: 'case-002',
      message: '总结当前页面可执行的操作',
      inputParamsJson: JSON.stringify({ question: '总结当前页面可执行的操作' }, null, 2),
      expectedText: '',
      enabled: false,
    },
  ]
}

function debugStepOutput(step: WorkflowDebugStepResult) {
  return step.output ?? step.rawOutput ?? step.statePatch ?? step.uiRequest ?? step.artifact ?? null
}

function answerFromDebugRun(result: WorkflowDebugRunResult) {
  if (result.answer) return result.answer
  const outputStep = [...(result.steps || [])].reverse().find((step) => debugStepOutput(step) !== null)
  const output = outputStep ? debugStepOutput(outputStep) : result.finalState
  return stringifyDebugPayload(output)
}

function evalAssertionPassed(answer: string, expectedText: string) {
  const expected = expectedText.trim()
  if (!expected) return true
  return answer.toLowerCase().includes(expected.toLowerCase())
}

export function useWorkflowStudioEval({
  studio,
  buildDebugBaseRequest,
  parseOptionalObject,
}: UseWorkflowStudioEvalDeps) {
  const evalOpen = ref(false)
  const evalRunning = ref(false)
  const evalRepeatCount = ref(1)
  const evalRunName = ref('')
  const evalCases = ref<WorkflowEvalCase[]>(createDefaultEvalCases())
  const evalResults = ref<WorkflowEvalResult[]>([])

  const enabledEvalCases = computed(() => evalCases.value.filter((item) => item.enabled))

  const evalSummary = computed(() => {
    const rows = evalResults.value
    const total = rows.length
    if (!total) {
      return {
        assertionRate: 0,
        runtimeSuccessRate: 0,
        p95LatencyMs: 0,
        biasCount: 0,
      }
    }
    const sortedLatency = rows.map((row) => row.elapsedMs).sort((a, b) => a - b)
    const p95Index = Math.min(sortedLatency.length - 1, Math.ceil(sortedLatency.length * 0.95) - 1)
    return {
      assertionRate: rows.filter((row) => row.assertionPassed).length / total,
      runtimeSuccessRate: rows.filter((row) => row.runtimeSuccess).length / total,
      p95LatencyMs: sortedLatency[p95Index] || 0,
      biasCount: rows.filter((row) => row.runtimeSuccess && !row.assertionPassed).length,
    }
  })

  function openEvalDrawer() {
    evalOpen.value = true
  }

  function addEvalCase() {
    const nextIndex = evalCases.value.length + 1
    evalCases.value.push({
      id: `eval-${Date.now()}-${nextIndex}`,
      caseNo: `case-${String(nextIndex).padStart(3, '0')}`,
      message: '',
      inputParamsJson: '{}',
      expectedText: '',
      enabled: true,
    })
  }

  function removeEvalCase(id: string) {
    evalCases.value = evalCases.value.filter((item) => item.id !== id)
  }

  function resetEvalCases() {
    evalCases.value = createDefaultEvalCases()
    evalResults.value = []
  }

  function formatEvalRate(value: number) {
    return `${Math.round(value * 1000) / 10}%`
  }

  async function runWorkflowEval() {
    const cases = enabledEvalCases.value
    if (!cases.length) {
      ElMessage.warning('请至少启用一条评测用例')
      return
    }
    evalRunning.value = true
    evalResults.value = []
    evalRunName.value = `${studio.value?.name || 'Workflow'} 发布前评测`
    try {
      const results: WorkflowEvalResult[] = []
      const baseRequest = buildDebugBaseRequest()
      for (let roundNo = 1; roundNo <= evalRepeatCount.value; roundNo += 1) {
        for (const evalCase of cases) {
          const startedAt = Date.now()
          try {
            const { data } = await debugWorkflowRun({
              ...baseRequest,
              message: evalCase.message || undefined,
              inputParams: parseOptionalObject(evalCase.inputParamsJson || '{}', `${evalCase.caseNo} input params`),
              debugOptions: {
                evalMode: true,
                sandboxSideEffects: true,
              },
            })
            const answer = answerFromDebugRun(data)
            results.push({
              id: `${evalCase.id}-${roundNo}`,
              roundNo,
              caseNo: evalCase.caseNo,
              runtimeSuccess: data.success,
              assertionPassed: data.success && evalAssertionPassed(answer, evalCase.expectedText),
              elapsedMs: data.steps?.reduce((sum, step) => sum + (step.elapsedMs || 0), 0) || Date.now() - startedAt,
              answer,
              errorMessage: data.errorMessage || '',
            })
          } catch (err) {
            results.push({
              id: `${evalCase.id}-${roundNo}`,
              roundNo,
              caseNo: evalCase.caseNo,
              runtimeSuccess: false,
              assertionPassed: false,
              elapsedMs: Date.now() - startedAt,
              answer: '',
              errorMessage: (err as Error).message,
            })
          }
          evalResults.value = [...results]
        }
      }
      ElMessage[evalSummary.value.biasCount ? 'warning' : 'success'](
        evalSummary.value.biasCount ? '评测完成，存在结果偏差' : '评测完成，全部通过',
      )
    } finally {
      evalRunning.value = false
    }
  }

  return {
    evalOpen,
    evalRunning,
    evalRepeatCount,
    evalRunName,
    evalCases,
    evalResults,
    enabledEvalCases,
    evalSummary,
    openEvalDrawer,
    addEvalCase,
    removeEvalCase,
    resetEvalCases,
    formatEvalRate,
    runWorkflowEval,
  }
}
