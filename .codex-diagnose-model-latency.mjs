const endpoint = 'http://127.0.0.1:18601/model/chat/stream/events'

const cases = [
  { name: 'configured-defaults', options: undefined },
  { name: 'low-reasoning', options: { reasoning_effort: 'low', temperature: 0.1, max_tokens: 256 } },
]

for (const testCase of cases) {
  const started = performance.now()
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      modelInstanceId: 'seed-deepseek-v4-pro',
      messages: [{ role: 'user', content: '你好' }],
      options: testCase.options,
    }),
  })
  const headersAt = performance.now()
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const eventCounts = {}
  const milestones = {}
  let buffer = ''

  const acceptFrame = (frame) => {
    const data = frame
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (!data) return
    const event = JSON.parse(data)
    const type = event.type || 'unknown'
    eventCounts[type] = (eventCounts[type] || 0) + 1
    if (milestones[type] === undefined) milestones[type] = Math.round(performance.now() - started)
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
    name: testCase.name,
    status: response.status,
    responseHeadersMs: Math.round(headersAt - started),
    totalMs: Math.round(performance.now() - started),
    milestones,
    eventCounts,
  }))
}
