import { createEafPageBridge } from './eafPageBridge'

async function example() {
  const bridge = createEafPageBridge({ pageInstanceId: 'page-001' })
  bridge.registerAction('team.openDetail', async (args) => args)
  const registeredActions: string[] = bridge.registeredActions
  registeredActions.includes('team.openDetail')

  const guardedBridge = createEafPageBridge({
    pageInstanceId: 'page-confirm',
    confirmAction: async (request) => request.actionKey === 'team.openDetail',
  })
  guardedBridge.registerAction('team.openDetail', async (args) => args)

  await bridge.handleEvent({
    type: 'page.action.requested',
    requestId: 'req-1',
    target: { pageInstanceId: 'page-001' },
    actionKey: 'team.openDetail',
    args: { id: 1 },
  })

  await bridge.handleEvent({
    type: 'page.action.requested',
    requestId: 'req-2',
    target: { pageInstanceId: 'page-002' },
    actionKey: 'team.openDetail',
  })
}

void example
