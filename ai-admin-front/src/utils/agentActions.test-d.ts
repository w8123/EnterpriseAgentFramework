import type { AgentDefinition } from '@/types/agent'
import { agentListActions } from './agentActions'

const agentScopeIds = agentListActions({ runtimeType: 'AGENTSCOPE' }).map((action) => action.id)
agentScopeIds.includes('debug')
// @ts-expect-error AgentScope agents should not expose the workflow canvas action.
agentScopeIds.includes('studio')

const langGraphIds = agentListActions({ runtimeType: 'LANGGRAPH4J' }).map((action) => action.id)
langGraphIds.includes('studio')
// @ts-expect-error LangGraph agents should not expose the legacy debug action.
langGraphIds.includes('debug')

const defaultIds = agentListActions({} as Pick<AgentDefinition, 'runtimeType'>).map((action) => action.id)
defaultIds.includes('edit')
defaultIds.includes('versions')
defaultIds.includes('delete')
