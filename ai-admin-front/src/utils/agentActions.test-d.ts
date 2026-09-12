import { agentListActions } from './agentActions'

const ids = agentListActions({ id: 'demo' }).map((action) => action.id)
ids.includes('edit')
ids.includes('debug')
ids.includes('eval')
ids.includes('runops')
ids.includes('delete')
