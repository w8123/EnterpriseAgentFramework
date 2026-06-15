import { buildEafChatSessionPayload, createEafPageBridge } from './index'

const bridge = createEafPageBridge({
  pageInstanceId: 'page-001',
  route: '/orders/42',
})
bridge.registerAction('orders.refresh', async () => ({ ok: true }))

const payload = buildEafChatSessionPayload(bridge, {
  pageKey: 'orders.list',
  routePattern: '/orders/:id',
})

const pageKey: string = payload.pageKey
const pageInstanceId: string = payload.pageInstanceId
const route: string = payload.route
const bridgeActions: string[] = payload.bridgeActions
const sdkVersion: string = payload.sdkVersion

void pageKey
void pageInstanceId
void route
void bridgeActions
void sdkVersion
