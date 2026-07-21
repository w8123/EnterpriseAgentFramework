/**
 * Offline synthetic Skill-zip smoke for @reachai/embed-chat clean consumers.
 *
 * This script builds a synthetic zip that mirrors Control's skill layout
 * (reachai-onboarding/artifacts/<tarball>) using classpath artifact bytes +
 * manifest-artifact.json fields. It does NOT invent sdk-artifact-contract.json
 * entries that are absent from the real Control Skill zip.
 *
 * Real Control Skill zip path + SHA consistency is covered by
 * ControlAiAssistSkillControllerTest (Maven).
 */
import { createHash } from 'node:crypto'
import {
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  rm,
  writeFile,
} from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(root, '..')
const controlArtifactDir = resolve(
  repoRoot,
  'reachai-control-service',
  'src',
  'main',
  'resources',
  'ai-assist',
  'artifacts',
  'embed-chat',
)
const controlTarballPath = resolve(controlArtifactDir, 'reachai-embed-chat-1.0.0-SNAPSHOT.tgz')
const controlManifestPath = resolve(controlArtifactDir, 'manifest-artifact.json')

function run(cmd, args, cwd) {
  const result = spawnSync(cmd, args, { cwd, encoding: 'utf8', shell: true })
  if (result.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} failed:\n${result.stdout || ''}\n${result.stderr || ''}`)
  }
  return result
}

function runPython(code, args = []) {
  const result = spawnSync('python', ['-c', code, ...args], { encoding: 'utf8' })
  if (result.status !== 0) {
    throw new Error(`python failed:\n${result.stdout || ''}\n${result.stderr || ''}`)
  }
  return result
}

async function sha256File(filePath) {
  const bytes = await readFile(filePath)
  return createHash('sha256').update(bytes).digest('hex')
}

const artifactManifest = JSON.parse(await readFile(controlManifestPath, 'utf8'))
const requiredFields = [
  'packageName',
  'version',
  'filename',
  'integritySha256',
  'artifactPathWithinSkill',
  'installWorkingDirectory',
  'installCommandTemplate',
  'fallbackPolicy',
]
for (const field of requiredFields) {
  if (!artifactManifest[field]) {
    throw new Error(`manifest-artifact.json missing ${field}`)
  }
}

const expectedSha = String(artifactManifest.integritySha256).trim()
const actualSha = await sha256File(controlTarballPath)
if (expectedSha !== actualSha) {
  throw new Error(`SHA-256 mismatch: expected=${expectedSha} actual=${actualSha}`)
}

const workRoot = await mkdtemp(resolve(tmpdir(), 'reachai-embed-offline-synthetic-smoke-'))
const skillZipPath = resolve(workRoot, 'offline-synthetic-reachai-onboarding.zip')
const skillExtractDir = resolve(workRoot, 'skill-extract-outside-business')
const businessFrontendDir = resolve(workRoot, 'business-frontend-app')

try {
  await mkdir(skillExtractDir, { recursive: true })
  await mkdir(businessFrontendDir, { recursive: true })

  // Offline synthetic smoke: zip layout matches Control skill (tarball path only; no fake contract json).
  runPython(
    `
import zipfile, sys
zip_path, tarball_path, artifact_path = sys.argv[1:4]
with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write(tarball_path, artifact_path.replace('\\\\', '/'))
    zf.writestr('reachai-onboarding/SKILL.md', '# offline synthetic smoke\\n')
print('ok')
`.trim(),
    [skillZipPath, controlTarballPath, artifactManifest.artifactPathWithinSkill],
  )

  runPython(
    `
import zipfile, sys
zip_path, extract_dir = sys.argv[1:3]
with zipfile.ZipFile(zip_path, 'r') as zf:
    zf.extractall(extract_dir)
print('ok')
`.trim(),
    [skillZipPath, skillExtractDir],
  )

  const artifactRel = artifactManifest.artifactPathWithinSkill
  const absoluteTarballFromManifest = resolve(skillExtractDir, artifactRel)
  await readFile(absoluteTarballFromManifest)

  if (absoluteTarballFromManifest === controlTarballPath) {
    throw new Error('Smoke incorrectly resolved to control-service classpath tarball path')
  }
  if (!String(absoluteTarballFromManifest).startsWith(String(skillExtractDir))) {
    throw new Error('Resolved tarball is not under skill extract directory')
  }

  const extractedTarballSha = await sha256File(absoluteTarballFromManifest)
  if (extractedTarballSha !== expectedSha) {
    throw new Error(`Extracted skill tarball SHA mismatch: ${extractedTarballSha} != ${expectedSha}`)
  }

  if (artifactManifest.installWorkingDirectory !== 'business-frontend-package-root') {
    throw new Error(`Unexpected installWorkingDirectory: ${artifactManifest.installWorkingDirectory}`)
  }

  const installTemplate = artifactManifest.installCommandTemplate || artifactManifest.installCommand || ''
  if (!installTemplate.includes('{skillExtractDir}')) {
    throw new Error('installCommandTemplate must include {skillExtractDir} placeholder')
  }
  const installCommand = installTemplate.replaceAll('{skillExtractDir}', skillExtractDir.replaceAll('\\', '/'))
  const match = installCommand.match(/^npm\s+install\s+"?(.+?)"?\s*$/i)
  if (!match) {
    throw new Error(`Cannot parse install command: ${installCommand}`)
  }
  const normalizedInstallTarget = resolve(match[1])
  if (normalizedInstallTarget === controlTarballPath) {
    throw new Error('Install target must not be the known control-service absolute tarball path')
  }
  if (normalizedInstallTarget !== absoluteTarballFromManifest) {
    throw new Error(
      `Install target mismatch: command=${normalizedInstallTarget} manifest=${absoluteTarballFromManifest}`,
    )
  }

  await writeFile(
    resolve(businessFrontendDir, 'package.json'),
    JSON.stringify(
      {
        name: 'business-frontend-embed-smoke',
        private: true,
        type: 'module',
      },
      null,
      2,
    ),
    'utf8',
  )
  await writeFile(
    resolve(businessFrontendDir, 'tsconfig.json'),
    JSON.stringify(
      {
        compilerOptions: {
          module: 'ESNext',
          moduleResolution: 'Bundler',
          target: 'ES2020',
          lib: ['ES2020', 'DOM'],
          strict: true,
          skipLibCheck: false,
          types: [],
          noEmit: true,
          // Clean consumer: resolve only installed package, never business-repo sources.
          paths: {},
          baseUrl: '.',
        },
        include: ['smoke-import.ts'],
      },
      null,
      2,
    ),
    'utf8',
  )
  await writeFile(
    resolve(businessFrontendDir, 'smoke-import.ts'),
    `import {
  buildEafChatSessionPayload,
  createEafChat,
  createEafPageBridge,
  resolveEafChatEmbedApiRoot,
  resolveEafChatPlatformBase,
  type EafChatOptions,
  type EafPageBridge,
  type EafPageDescriptor,
} from '@reachai/embed-chat'
import '@reachai/embed-chat/style.css'

const bridge: EafPageBridge = createEafPageBridge({
  pageInstanceId: 'page-1',
  route: '/orders',
})
const unregister = bridge.registerAction('refresh', async () => ({ ok: true }), {
  title: 'Refresh',
  confirmRequired: false,
})
void unregister
void bridge.handleEvent({
  type: 'page.action.requested',
  requestId: 'r1',
  actionKey: 'refresh',
  args: {},
})
void bridge.onResult(() => {})
void bridge.onActionDefinitionsChange(() => {})

const page: EafPageDescriptor = { pageKey: 'orders.list', name: 'Orders' }
const payload = buildEafChatSessionPayload(bridge, page)
const embedRoot = resolveEafChatEmbedApiRoot('http://localhost:18603')
const platformBase = resolveEafChatPlatformBase('http://localhost:18603/api/embed')

const options: EafChatOptions = {
  agentId: 'demo-agent',
  mount: '#app',
  tokenProvider: async () => 'token',
  bridge,
  apiBase: embedRoot || platformBase,
}
export const chatFactory: typeof createEafChat = createEafChat
export type SmokeOptions = EafChatOptions
export const smokePayload = payload
export const smokeOptions = options

// Obsolete bridge methods must NOT exist on the public type.
// @ts-expect-error obsolete register must not be declared
bridge.register('x', async () => ({}))
// @ts-expect-error obsolete unregister must not be declared
bridge.unregister('x')
// @ts-expect-error obsolete list must not be declared
bridge.list()
// @ts-expect-error obsolete execute must not be declared
bridge.execute({ requestId: 'r', actionKey: 'x', type: 'page.action.requested' })
// @ts-expect-error obsolete destroy must not be declared
bridge.destroy()
`,
    'utf8',
  )

  run('npm', ['install', normalizedInstallTarget], businessFrontendDir)

  const pkgJson = JSON.parse(await readFile(resolve(businessFrontendDir, 'package.json'), 'utf8'))
  const depVersion =
    pkgJson.dependencies?.['@reachai/embed-chat'] || pkgJson.devDependencies?.['@reachai/embed-chat']
  if (!depVersion) {
    throw new Error('business package.json missing @reachai/embed-chat after install')
  }

  const pkgRoot = resolve(businessFrontendDir, 'node_modules', '@reachai', 'embed-chat')
  const pkg = JSON.parse(await readFile(resolve(pkgRoot, 'package.json'), 'utf8'))
  if (pkg.name !== artifactManifest.packageName) {
    throw new Error(`Unexpected package name: ${pkg.name}`)
  }
  for (const file of ['index.mjs', 'index.cjs', 'index.d.ts', 'style.css', 'reachai-chat-embed.umd.js']) {
    await readFile(resolve(pkgRoot, file))
  }

  // Acceptance object: installed tarball index.d.ts (not repo src/sdk).
  const installedDts = await readFile(resolve(pkgRoot, 'index.d.ts'), 'utf8')
  for (const token of [
    'readonly pageInstanceId',
    'registerAction(',
    'handleEvent(',
    'onResult(',
    'onActionDefinitionsChange(',
    'buildEafChatSessionPayload(bridge: EafPageBridge, page?: EafPageDescriptor)',
    'resolveEafChatEmbedApiRoot',
    'resolveEafChatPlatformBase',
  ]) {
    if (!installedDts.includes(token)) {
      throw new Error(`Installed index.d.ts missing: ${token}`)
    }
  }
  const bridgeMatch = installedDts.match(/export interface EafPageBridge \{([\s\S]*?)\n\}/)
  if (!bridgeMatch) {
    throw new Error('Installed index.d.ts missing EafPageBridge')
  }
  const bridgeDts = bridgeMatch[1]
  for (const token of ['unregister(', 'destroy(', 'list(', 'execute(']) {
    if (bridgeDts.includes(token)) {
      throw new Error(`Installed EafPageBridge still declares obsolete API: ${token}`)
    }
  }
  if (/\bregister\s*\(/.test(bridgeDts.replace(/registerAction\s*\(/g, ''))) {
    throw new Error('Installed EafPageBridge still declares obsolete register()')
  }

  const tscBin = resolve(root, 'node_modules', 'typescript', 'bin', 'tsc')
  const tscResult = run('node', [tscBin, '-p', businessFrontendDir], businessFrontendDir)
  console.log('[verify-embed-chat-tarball] clean-consumer tsc=ok')
  if (tscResult.stdout) console.log(tscResult.stdout.trim())

  const mod = await import(pathToFileURL(resolve(pkgRoot, 'index.mjs')).href)
  if (typeof mod.createEafChat !== 'function') {
    throw new Error('createEafChat export missing after install')
  }
  if (typeof mod.createEafPageBridge !== 'function') {
    throw new Error('createEafPageBridge export missing after install')
  }
  if (typeof mod.buildEafChatSessionPayload !== 'function') {
    throw new Error('buildEafChatSessionPayload export missing after install')
  }
  if (typeof mod.resolveEafChatEmbedApiRoot !== 'function') {
    throw new Error('resolveEafChatEmbedApiRoot export missing after install')
  }
  if (typeof mod.resolveEafChatPlatformBase !== 'function') {
    throw new Error('resolveEafChatPlatformBase export missing after install')
  }

  const bridge = mod.createEafPageBridge({ pageInstanceId: 'runtime-1', route: '/orders' })
  if (typeof bridge.registerAction !== 'function') {
    throw new Error('runtime bridge.registerAction must be a function')
  }
  if (bridge.register !== undefined) {
    throw new Error('runtime bridge.register must be undefined')
  }
  if (bridge.unregister !== undefined || bridge.list !== undefined || bridge.execute !== undefined || bridge.destroy !== undefined) {
    throw new Error('runtime bridge must not expose obsolete register/unregister/list/execute/destroy')
  }
  bridge.registerAction('refresh', async () => ({ ok: true }))
  const payload = mod.buildEafChatSessionPayload(bridge, { pageKey: 'orders.list' })
  for (const key of ['pageInstanceId', 'route', 'bridgeActions', 'sdkVersion']) {
    if (!(key in payload)) {
      throw new Error(`buildEafChatSessionPayload missing flat field: ${key}`)
    }
  }
  if (payload.pageInstanceId !== 'runtime-1') {
    throw new Error(`unexpected pageInstanceId: ${payload.pageInstanceId}`)
  }
  if (!Array.isArray(payload.bridgeActions) || !payload.bridgeActions.includes('refresh')) {
    throw new Error(`bridgeActions missing refresh: ${JSON.stringify(payload.bridgeActions)}`)
  }
  if (payload.sdkVersion !== pkg.version) {
    throw new Error(`session sdkVersion ${payload.sdkVersion} does not match package version ${pkg.version}`)
  }

  await readFile(resolve(pkgRoot, 'style.css'), 'utf8')
  if (!(pkg.exports || {})['./style.css']) {
    throw new Error('package.json exports missing ./style.css')
  }

  const assets = (await readdir(pkgRoot)).filter((name) => /\.(webp|png|jpe?g|gif|svg)$/i.test(name))
  console.log(`[verify-embed-chat-tarball] mode=offline-synthetic-skill-zip`)
  console.log(`[verify-embed-chat-tarball] sha256=${actualSha}`)
  console.log(`[verify-embed-chat-tarball] skillArtifactPath=${artifactRel}`)
  console.log(`[verify-embed-chat-tarball] installTarget=${normalizedInstallTarget}`)
  console.log(`[verify-embed-chat-tarball] runtime exports ok; bridge.registerAction=function; bridge.register=undefined`)
  console.log(`[verify-embed-chat-tarball] session payload keys=${Object.keys(payload).join(',')}`)
  console.log(`[verify-embed-chat-tarball] createEafChat=ok tsc=ok style=ok assets=${assets.length}`)
  console.log('[verify-embed-chat-tarball] PASS offline-synthetic-skill-zip-install')
} finally {
  await rm(workRoot, { recursive: true, force: true })
}
