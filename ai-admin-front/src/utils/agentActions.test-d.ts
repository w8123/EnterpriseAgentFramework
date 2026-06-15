import type { AgentEntry } from '@/types/agent'
import { agentListActions } from './agentActions'

const ids = agentListActions({ id: 'demo' }).map((action) => action.id)
ids.includes('edit')
ids.includes('bindings')
ids.includes('studio')
ids.includes('delete')
