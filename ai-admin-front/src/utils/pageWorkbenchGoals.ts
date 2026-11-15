import type { AiCodingTask } from '@/types/aiCodingTask'
import { aiCodingExecutionIsTerminal } from '@/utils/aiCodingPresentation'

function belongsToPage(task: AiCodingTask, pageKey: string) {
  return task.targets.some((target) => target.targetRole === 'PRIMARY'
    && target.targetType === 'PAGE' && target.targetKey === pageKey)
}

export function getPageImplementationTask(tasks: AiCodingTask[], pageKey: string) {
  return tasks.filter((task) => task.taskKind === 'CODE_IMPLEMENTATION' && belongsToPage(task, pageKey))
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0] || null
}

export function getPageActiveTasks(tasks: AiCodingTask[], pageKey: string) {
  return tasks.filter((task) => belongsToPage(task, pageKey)
    && !aiCodingExecutionIsTerminal(task.executionStatus))
}
