import type { IncomingMessage, ServerResponse } from 'node:http'

export function embedChatDevMiddleware(
  sdkDir: string,
): (request: IncomingMessage, response: ServerResponse, next: () => void) => void
