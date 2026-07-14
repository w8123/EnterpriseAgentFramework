import type { Agent } from '@/types/agent'

export type AgentListActionId = 'edit' | 'debug' | 'eval' | 'runops' | 'delete'
export type AgentActionButtonType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export interface AgentListAction<Id extends AgentListActionId = AgentListActionId> {
  id: Id
  label: string
  buttonType: AgentActionButtonType
}

const ACTIONS: { [Id in AgentListActionId]: AgentListAction<Id> } = {
  edit: { id: 'edit', label: '编辑', buttonType: 'primary' },
  debug: { id: 'debug', label: '调试', buttonType: 'success' },
  eval: { id: 'eval', label: '评测', buttonType: 'info' },
  runops: { id: 'runops', label: 'RunOps', buttonType: 'warning' },
  delete: { id: 'delete', label: '删除', buttonType: 'danger' },
}

const ACTION_IDS: AgentListActionId[] = ['edit', 'debug', 'eval', 'runops', 'delete']

export function agentListActions(
  _agent?: Pick<Agent, 'id'>,
): AgentListAction[] {
  return ACTION_IDS.map((id) => ACTIONS[id])
}
