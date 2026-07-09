import {
  buildEafChatSessionPayload,
  createEafPageBridge,
  resolveEafChatEmbedApiRoot,
  resolveEafChatPlatformBase,
  type EafChatOptions,
} from './index'

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
const directEmbedApiRoot: string = resolveEafChatEmbedApiRoot('http://localhost:18603')
const proxyEmbedApiRoot: string = resolveEafChatEmbedApiRoot('/api/reachai/embed')
const splitEmbedApiRoot: string = resolveEafChatEmbedApiRoot('https://gateway.example.com', '/api/reachai/embed')
const platformBase: string = resolveEafChatPlatformBase('https://gateway.example.com/api/reachai/embed')

const splitGatewayOptions: EafChatOptions = {
  agentId: 'orders-page-copilot',
  mount: document.createElement('div'),
  tokenProvider: () => 'token',
  apiBase: 'https://gateway.example.com',
  embedPathPrefix: '/api/reachai/embed',
}

void pageKey
void pageInstanceId
void route
void bridgeActions
void sdkVersion
void directEmbedApiRoot
void proxyEmbedApiRoot
void splitEmbedApiRoot
void platformBase
void splitGatewayOptions
