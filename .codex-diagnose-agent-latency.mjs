const started = performance.now()
const response = await fetch('http://127.0.0.1:18604/api/runtime/agents/execute/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    agentId: 'bzjs8-page-copilot',
    message: '你好',
    sessionId: `latency-diagnosis-${Date.now()}`,
    entryType: 'DEBUG',
  }),
})
const headersAt = performance.now()
const reader = response.body.getReader()
const decoder = new TextDecoder()
const eventCounts = {}
const milestones = {}
let publicContentLength = 0
let completionMetadata = {}
let buffer = ''

const acceptFrame = (frame) => {
  const eventName = frame
    .split('\n')
    .find((line) => line.startsWith('event:'))
    ?.slice(6)
    .trim() || 'comment'
  const data = frame
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n')
  eventCounts[eventName] = (eventCounts[eventName] || 0) + 1
  if (milestones[eventName] === undefined) milestones[eventName] = Math.round(performance.now() - started)
  if (!data) return
  const payload = JSON.parse(data)
  if (eventName === 'message.delta') publicContentLength += String(payload.text || '').length
  if (eventName === 'execution.completed') {
    const metadata = payload.metadata || {}
    completionMetadata = Object.fromEntries([
      'traceId',
      'streamMode',
      'tokenStreaming',
      'fallbackUsed',
      'fallbackReason',
      'contentDeltaCount',
      'upstreamContentDeltaCount',
      'reasoningDeltaCount',
      'reasoningLength',
      'toolCallDeltaCount',
      'planCount',
      'replanCount',
      'workflowCallCount',
    ].filter((key) => metadata[key] !== undefined).map((key) => [key, metadata[key]]))
  }
}

while (true) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true }).replaceAll('\r\n', '\n')
  let boundary
  while ((boundary = buffer.indexOf('\n\n')) >= 0) {
    acceptFrame(buffer.slice(0, boundary))
    buffer = buffer.slice(boundary + 2)
  }
}
if (buffer.trim()) acceptFrame(buffer)

console.log(JSON.stringify({
  status: response.status,
  responseHeadersMs: Math.round(headersAt - started),
  totalMs: Math.round(performance.now() - started),
  milestones,
  eventCounts,
  publicContentLength,
  completionMetadata,
}))
