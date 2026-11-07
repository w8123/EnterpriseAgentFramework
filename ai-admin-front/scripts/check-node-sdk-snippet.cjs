/* Verify the real wizard template against the built Node SDK without platform credentials. */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const root = path.resolve(__dirname, '../..')
const file = path.join(root, 'ai-admin-front/src/views/registry/composables/useSdkAccessWizardNodeSnippets.ts')
const source = fs.readFileSync(file, 'utf8')
const match = source.match(/return `([\s\S]+?)`\s*\n/)
if (!match) throw new Error('Node wizard template not found')
let snippet = match[1]
for (const [name, value] of Object.entries({ registry: 'http://127.0.0.1:28603', projectCode: 'snippet-test', projectName: '测试项目', baseUrl: 'http://127.0.0.1:18810' })) {
  const variable = '${' + name + '}'
  if (!snippet.includes(variable)) throw new Error('Missing template variable: ' + name)
  snippet = snippet.replaceAll(variable, JSON.stringify(value))
}
if (snippet.includes('${')) throw new Error('Unexpected template variable')
const marker = 'const server = app.listen'
if (!snippet.includes(marker)) throw new Error('Listener marker not found')
snippet = snippet.slice(0, snippet.indexOf(marker))
const localRequire = (name) => {
  if (name === '@reachai/node-sdk') return require(path.join(root, 'reachai-node/dist'))
  if (name === 'express') return require(path.join(root, 'reachai-node/node_modules/express'))
  throw new Error('Unexpected import: ' + name)
}
const fakeProcess = { env: { REACHAI_ENROLLMENT_TOKEN: 'test-only', REACHAI_BUSINESS_ORIGIN: 'http://127.0.0.1:18810', REACHAI_CREDENTIAL_FILE: path.join(require('node:os').tmpdir(), 'reachai-not-created'), REACHAI_CREDENTIAL_KEY: crypto.randomBytes(32).toString('base64') } }
const { app } = new Function('require', 'process', snippet + '\nreturn {app, reach}') (localRequire, fakeProcess)
const server = app.listen(0, '127.0.0.1', async () => {
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/reachai/registry/capabilities/sync`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })
    if (response.status !== 401) throw new Error('Unsigned callback did not return 401')
    console.log('Node wizard template registered a typed capability and rejects an unsigned callback')
  } catch (error) { console.error(error); process.exitCode = 1 } finally { server.close() }
})
