# @reachai/embed-chat Quick Reference

This is the public browser SDK contract for ReachAI embedded chat. Install the
versioned tarball declared by `sdkArtifacts`; do not guess an npm registry URL.

## Minimal integration

```ts
import { createEafChat } from '@reachai/embed-chat'
import '@reachai/embed-chat/style.css'

const chat = await createEafChat({
  mount: '#reachai-chat',
  agentId: 'orders-page-copilot',
  apiBase: 'https://reachai.example.com',
  tokenProvider: async (context) => {
    const response = await fetch('/api/reachai/embed-token', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        pageKey: context?.pageKey,
        pageInstanceId: context?.pageInstanceId,
        route: context?.route,
        origin: context?.origin,
      }),
    })
    const payload = await response.json()
    const token = payload.data?.token || payload.token
    if (!token) throw new Error(payload.message || 'ReachAI embed token missing')
    return { token, expiresIn: payload.data?.expiresIn || payload.expiresIn }
  },
  page: { pageKey: 'orders.list', routePattern: location.pathname },
})
```

## Public API

| API | Purpose |
| --- | --- |
| `createEafChat(options)` | Mount one chat launcher and return an `EafChatClient`. |
| `EafChatOptions` | Requires `mount`, `agentId`, and `tokenProvider`; optionally configures `page`, `apiBase`, `embedPathPrefix`, theme, position and events. |
| `EafChatTokenProviderContext` | Supplies `pageKey`, `pageInstanceId`, `route`, `origin`, `reason`, `attempt`, `signal` and optional `sessionId`. Forward the page identity unchanged to the business Token Broker. |
| `createEafPageBridge(options)` | Registers approved business-page actions, owns the stable page instance identity, and accepts the optional formal `onNavigate` adapter for platform-approved SPA routing. |
| `EafChatClient` | `open`, `close`, `toggle`, `send`, `retry`, `rebindPage`, `registerPageCatalog`, `setContext`, `destroy`. |

### Exact core signatures

```ts
export function createEafChat(options: EafChatOptions): Promise<EafChatClient>

export type EafChatTokenReason =
  | 'initial' | 'send' | 'expiring' | 'unauthorized' | 'retry' | 'page-action' | 'page-navigation'

export interface EafChatTokenProviderContext {
  reason: EafChatTokenReason
  signal: AbortSignal
  attempt: number
  sessionId?: string
  pageKey?: string
  pageInstanceId: string
  route: string
  origin: string
}

export interface EafChatTokenResult {
  token: string
  expiresAt?: number // Unix epoch milliseconds
  expiresIn?: number // seconds
}

export type EafChatTokenProvider = (
  context?: EafChatTokenProviderContext,
) => Promise<string | EafChatTokenResult> | string | EafChatTokenResult

export interface EafChatOptions {
  agentId: string
  mount: string | HTMLElement
  tokenProvider: EafChatTokenProvider
  tokenTimeoutMs?: number
  bridge?: EafPageBridge
  page?: EafPageDescriptor
  apiBase?: string
  embedPathPrefix?: string
  stream?: boolean
  theme?: EafChatTheme
  locale?: 'zh-CN' | 'en-US' | string
  position?: 'inline' | 'bottom-right' | 'bottom-left'
  initialOpen?: boolean
  resizable?: boolean
  launcherDraggable?: boolean
  context?: Record<string, unknown>
  onEvent?: (event: EafChatEvent) => void
  onError?: (error: EafChatError) => void
  onStateChange?: (state: EafChatAuthState) => void
}

export interface EafChatClient {
  readonly bridge: EafPageBridge
  readonly sessionId: string | null
  open(): void
  close(): void
  toggle(): void
  send(message: string): Promise<EafChatMessageResponse>
  retry(): Promise<void>
  rebindPage(binding: { bridge: EafPageBridge; page: EafPageDescriptor }): Promise<void>
  registerPageCatalog(): Promise<void>
  setContext(context: Record<string, unknown>): void
  destroy(): void
}
```

Page Bridge signatures:

```ts
export function createEafPageBridge(options?: EafPageBridgeOptions): EafPageBridge

export interface EafPageBridgeOptions {
  pageInstanceId?: string
  route?: string
  confirmAction?: (request: PageActionRequest) => boolean | Promise<boolean>
  onNavigate?: (request: {
    requestId: string
    targetPageKey: string
    route?: string
    title?: string
  }) => void | Promise<void>
  actionTimeoutMs?: number
}

export interface EafPageBridge {
  readonly pageInstanceId: string
  readonly route?: string
  readonly registeredActions: string[]
  readonly actionDefinitions: EafPageActionDefinition[]
  registerAction(
    actionKey: string,
    handler: PageActionHandler,
    options?: PageActionRegisterOptions,
  ): () => void
  handleEvent(event: unknown): Promise<PageActionResult | null>
  onResult(listener: (result: PageActionResult) => void): () => void
  onActionDefinitionsChange(
    listener: (definitions: EafPageActionDefinition[]) => void,
  ): () => void
}
```

## SPA cross-route Page Actions

No business page should register or inspect the internal `__reachai.navigate`
action key. If a published Page Workflow targets another registered page,
provide `onNavigate` on every bridge. Validate the supplied page key and route
against the application's own routing registry, then navigate normally. In the
target page lifecycle, after its actions are registered, call
`chat.rebindPage({ bridge: targetBridge, page: targetPage })`. The SDK obtains
a fresh target-page token and calls the public session-rebind endpoint; it
preserves the same conversation/session and never exposes a session ID or
navigation request ID to application code.

```ts
const bridge = createEafPageBridge({
  route: location.pathname,
  onNavigate: async ({ targetPageKey, route }) => {
    assertKnownRoute(targetPageKey, route)
    await router.navigateByUrl(route!)
  },
})

// Target page, after bridge.registerAction(...) calls have completed:
await chat.rebindPage({ bridge: targetBridge, page: targetPage })
```

`rebindPage` is valid only after the platform requested a navigation. It is
not a general-purpose way to move an Embed session to an arbitrary page.

## Non-negotiable authentication boundary

- `/api/reachai/embed-token` uses the normal business login session and returns a short-lived ReachAI Embed Token.
- `/api/reachai/embed/**` forwards `Authorization: Bearer <embedToken>` unchanged. It must not be parsed as a business OAuth/JWT bearer token.
- Do not place an app secret, project AI Coding key, or business login token in browser configuration.
- Do not generate a fallback `pageInstanceId`: the SDK, Token Broker, Chat Session and Page Bridge must share the same identity.

## Lifecycle and errors

`createEafChat()` mounts a visible retryable shell before the first Token Broker
call completes. A broker failure is not a reason to hide the launcher. Destroy
the client before redirecting after business authentication is lost.

Use one global launcher in the authenticated application shell. Do not mount it
on login, logout, OAuth callback, silent-refresh or public pages.

## Verification

The package `index.d.ts` remains the machine-readable contract, but normal
integration should not require extracting the tarball to discover the core
surface above. Run the business front-end build after installation. Real E2E
requires either an existing authorized browser session or business-supplied
test authorization used by `reachai-doctor --mode e2e`; ReachAI does not mint
or emulate that business login.
