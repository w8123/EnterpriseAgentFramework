import { strict as assert } from 'node:assert'
import { mkdtemp, mkdir, rmdir, unlink, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { embedChatDevMiddleware } from './embed-chat-dev-middleware.mjs'

test('development SDK route serves browser assets and rejects missing or unsafe paths', async () => {
  const root = await mkdtemp(join(tmpdir(), 'reachai-sdk-route-'))
  await mkdir(join(root, 'assets'))
  await writeFile(join(root, 'reachai-chat-embed.umd.js'), 'window.testSdk = true')
  await writeFile(join(root, 'style.css'), 'body { color: red }')
  await writeFile(join(root, 'assets', 'picture.webp'), 'image')
  const middleware = embedChatDevMiddleware(root)
  const server = createServer((req, res) => middleware(req, res, () => { res.statusCode = 418; res.end('other route') }))
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  try {
    const script = await fetch(`${base}/sdk/embed-chat/reachai-chat-embed.umd.js`)
    assert.equal(script.status, 200)
    assert.match(script.headers.get('content-type'), /javascript/)
    assert.match(await script.text(), /testSdk/)
    const style = await fetch(`${base}/sdk/embed-chat/style.css`, { method: 'HEAD' })
    assert.equal(style.status, 200)
    assert.match(style.headers.get('content-type'), /text\/css/)
    assert.equal((await fetch(`${base}/sdk/embed-chat/assets/picture.webp`)).status, 200)
    assert.equal((await fetch(`${base}/sdk/embed-chat/missing.js`)).status, 404)
    assert.equal((await fetch(`${base}/sdk/embed-chat/%2e%2e%2fsecret.webp`)).status, 404)
    assert.equal((await fetch(`${base}/sdk/embed-chat/style.css`, { method: 'POST' })).status, 405)
    assert.equal((await fetch(`${base}/other`)).status, 418)
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
    await Promise.all([
      unlink(join(root, 'reachai-chat-embed.umd.js')),
      unlink(join(root, 'style.css')),
      unlink(join(root, 'assets', 'picture.webp')),
    ])
    await rmdir(join(root, 'assets'))
    await rmdir(root)
  }
})
