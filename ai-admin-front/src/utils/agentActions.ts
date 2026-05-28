import type { AgentDefinition, AgentRuntimeType } from '@/types/agent'

export type AgentListActionId = 'edit' | 'studio' | 'debug' | 'versions' | 'delete'
export type AgentActionButtonType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export interface AgentListAction<Id extends AgentListActionId = AgentListActionId> {
  id: Id
  label: string
  buttonType: AgentActionButtonType
}

type RuntimeActionIds<Runtime> = Runtime extends 'LANGGRAPH4J'
  ? 'studio'
  : Runtime extends 'AGENTSCOPE' | undefined | null
    ? 'debug'
    : never

export type AgentListActionIdFor<Runtime> = 'edit' | RuntimeActionIds<Runtime> | 'versions' | 'delete'

const DEFAULT_RUNTIME_TYPE: AgentRuntimeType = 'AGENTSCOPE'

const ACTIONS: { [Id in AgentListActionId]: AgentListAction<Id> } = {
  edit: { id: 'edit', label: '编辑', buttonType: 'primary' },
  studio: { id: 'studio', label: '画布', buttonType: 'warning' },
  debug: { id: 'debug', label: '调试', buttonType: 'success' },
  versions: { id: 'versions', label: '版本', buttonType: 'info' },
  delete: { id: 'delete', label: '删除', buttonType: 'danger' },
}

const RUNTIME_SPECIFIC_ACTIONS: Record<AgentRuntimeType, readonly AgentListActionId[]> = {
  AGENTSCOPE: ['debug'],
  LANGGRAPH4J: ['studio'],
  OPENAI_AGENTS: [],
  CURSOR_CODE_AGENT: [],
}

function runtimeTypeOf(agent: Pick<AgentDefinition, 'runtimeType'>) {
  return agent.runtimeType || DEFAULT_RUNTIME_TYPE
}

export function agentListActions<Agent extends Pick<AgentDefinition, 'runtimeType'>>(
  agent: Agent,
): AgentListAction<AgentListActionIdFor<Agent['runtimeType']>>[] {
  const ids = [
    'edit',
    ...RUNTIME_SPECIFIC_ACTIONS[runtimeTypeOf(agent)],
    'versions',
    'delete',
  ] as AgentListActionId[]

  return ids.map((id) => ACTIONS[id]) as AgentListAction<AgentListActionIdFor<Agent['runtimeType']>>[]
}
