import type { AgentEntry } from '@/types/agent'

export type AgentListActionId = 'edit' | 'bindings' | 'studio' | 'delete'
export type AgentActionButtonType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export interface AgentListAction<Id extends AgentListActionId = AgentListActionId> {
  id: Id
  label: string
  buttonType: AgentActionButtonType
}

const ACTIONS: { [Id in AgentListActionId]: AgentListAction<Id> } = {
  edit: { id: 'edit', label: '编辑', buttonType: 'primary' },
  bindings: { id: 'bindings', label: '绑定', buttonType: 'info' },
  studio: { id: 'studio', label: '画布', buttonType: 'warning' },
  delete: { id: 'delete', label: '删除', buttonType: 'danger' },
}

const ACTION_IDS: AgentListActionId[] = ['edit', 'bindings', 'studio', 'delete']

export function agentListActions(
  _agent?: Pick<AgentEntry, 'id'>,
): AgentListAction[] {
  return ACTION_IDS.map((id) => ACTIONS[id])
}
