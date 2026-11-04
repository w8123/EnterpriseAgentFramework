/** Preserve the browser-facing Vite origin when proxying Control API requests. */
export function forwardDevOrigin(proxyRequest, request) {
  const host = request.headers.host
  if (host) proxyRequest.setHeader('X-Forwarded-Host', host)
  else proxyRequest.removeHeader('X-Forwarded-Host')
  proxyRequest.setHeader('X-Forwarded-Proto', request.socket.encrypted ? 'https' : 'http')
}
