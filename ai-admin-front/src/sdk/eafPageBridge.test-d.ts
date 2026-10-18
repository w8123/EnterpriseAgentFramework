import { createEafChat, createEafPageBridge, type EafChatOptions } from './index'

async function example() {
  const bridge = createEafPageBridge({ pageInstanceId: 'page-001' })
  bridge.registerAction('qmssmp.teamArchive.search', async (args) => args, {
    title: '班组档案查询',
    description: '在当前业务页面填入筛选条件并触发查询',
    inputSchema: {
      type: 'object',
      properties: {
        teamName: { type: 'string' },
      },
      required: [],
    },
    outputSchema: {
      type: 'object',
      properties: {
        total: { type: 'number' },
        records: { type: 'array' },
      },
    },
    sampleArgs: { teamName: '一班' },
    allowedAgentIds: ['team-archive-assistant'],
    metadata: { source: 'type-test' },
  })
  const registeredActions: string[] = bridge.registeredActions
  registeredActions.includes('qmssmp.teamArchive.search')
  const stopWatchingActions = bridge.onActionDefinitionsChange((definitions) => {
    definitions.map((definition) => definition.actionKey)
  })
  stopWatchingActions()

  const guardedBridge = createEafPageBridge({
    pageInstanceId: 'page-confirm',
    confirmAction: async (request) => request.actionKey === 'qmssmp.teamArchive.search',
  })
  guardedBridge.registerAction('qmssmp.teamArchive.search', async (args) => args)

  await bridge.handleEvent({
    type: 'page.action.requested',
    requestId: 'req-1',
    target: { pageInstanceId: 'page-001' },
    actionKey: 'qmssmp.teamArchive.search',
    args: { teamName: '一班' },
  })

  await bridge.handleEvent({
    type: 'page.action.requested',
    requestId: 'req-2',
    target: { pageInstanceId: 'page-002' },
    actionKey: 'qmssmp.teamArchive.search',
  })

  const chatOptions: EafChatOptions = {
    agentId: 'team-archive-assistant',
    mount: document.createElement('div'),
    tokenProvider: async () => 'token',
    bridge,
    pageRegistry: {
      registerOnStart: true,
    },
    page: {
      pageKey: 'teamArchive.list',
      name: '班组档案列表',
      routePattern: '/team-build/depart-management',
      origin: 'http://localhost:9200',
      metadata: { framework: 'angular' },
    },
  }
  const chat = await createEafChat(chatOptions)
  await chat.registerPageCatalog()
}

void example
