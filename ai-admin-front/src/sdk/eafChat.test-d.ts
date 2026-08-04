import {
  buildEafChatSessionPayload,
  createEafChat,
  createEafPageBridge,
  resolveEafChatEmbedApiRoot,
  resolveEafChatPlatformBase,
  EAF_CHAT_THEME_PRESETS,
  resolveEafChatThemePrimary,
  type EafChatOptions,
  type EafChatAuthState,
  type EafChatTokenProviderContext,
  type EafChatThemePreset,
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
  tokenTimeoutMs: 10_000,
  tokenProvider: (context?: EafChatTokenProviderContext) => {
    const signal: AbortSignal | undefined = context?.signal
    const tokenPageKey: string | undefined = context?.pageKey
    const tokenPageInstanceId: string | undefined = context?.pageInstanceId
    const tokenRoute: string | undefined = context?.route
    const tokenOrigin: string | undefined = context?.origin
    void signal
    void tokenPageKey
    void tokenPageInstanceId
    void tokenRoute
    void tokenOrigin
    return { token: 'token', expiresIn: 600 }
  },
  onStateChange: (state: EafChatAuthState) => {
    const status: 'loading' | 'ready' | 'error' = state.status
    void status
  },
  apiBase: 'https://gateway.example.com',
  embedPathPrefix: '/api/reachai/embed',
  position: 'bottom-right',
  initialOpen: false,
  resizable: true,
  launcherDraggable: true,
  theme: {
    preset: 'metro-green',
    brandName: 'ReachAI',
  },
}

const preset: EafChatThemePreset = 'solar-gold'
const resolved = resolveEafChatThemePrimary({ preset: 'metro-green' })
const metroPrimary: string = EAF_CHAT_THEME_PRESETS['metro-green'].primary
const chatPromise = createEafChat(splitGatewayOptions)
void chatPromise.then((chat) => chat.retry())

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
void preset
void resolved
void metroPrimary
void chatPromise
