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
const controlInstallerPath = resolve(
  repoRoot,
  'reachai-control-service',
  'src',
  'main',
  'resources',
  'ai-assist',
  'skills',
  'reachai-onboarding',
  'scripts',
  'install-embed-chat.mjs',
)

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
  'installScriptWithinSkill',
  'vendoredArtifactPath',
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
const devBusinessFrontendDir = resolve(workRoot, 'business-frontend-dev-dependency-app')

try {
  await mkdir(skillExtractDir, { recursive: true })
  await mkdir(businessFrontendDir, { recursive: true })
  await mkdir(devBusinessFrontendDir, { recursive: true })

  // Offline synthetic smoke: zip layout matches the real Control Skill deliverable.
  runPython(
    `
import zipfile, sys
zip_path, tarball_path, manifest_path, installer_path, artifact_path, installer_rel = sys.argv[1:7]
with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write(tarball_path, artifact_path.replace('\\\\', '/'))
    zf.write(manifest_path, 'reachai-onboarding/artifacts/manifest-artifact.json')
    zf.write(installer_path, installer_rel.replace('\\\\', '/'))
    zf.writestr('reachai-onboarding/SKILL.md', '# offline synthetic smoke\\n')
print('ok')
`.trim(),
    [
      skillZipPath,
      controlTarballPath,
      controlManifestPath,
      controlInstallerPath,
      artifactManifest.artifactPathWithinSkill,
      artifactManifest.installScriptWithinSkill,
    ],
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
  if (!installTemplate.includes('{skillExtractDir}')
      || !installTemplate.includes(artifactManifest.installScriptWithinSkill)) {
    throw new Error('installCommandTemplate must invoke the Skill-bundled installer')
  }
  const normalizedInstallTarget = resolve(
    businessFrontendDir,
    artifactManifest.vendoredArtifactPath,
  )
  const postinstallMarker = resolve(businessFrontendDir, 'postinstall-ran.txt')
  const existingLockDependency = {
    version: '1.2.3',
    resolved: 'https://registry.example.invalid/existing-package-1.2.3.tgz',
    integrity: 'sha512-existing-fixture',
  }

  await writeFile(
    resolve(businessFrontendDir, 'package.json'),
    JSON.stringify(
      {
        name: 'business-frontend-embed-smoke',
        version: '1.0.0',
        private: true,
        type: 'module',
        scripts: {
          postinstall: 'node -e "require(\'node:fs\').writeFileSync(\'postinstall-ran.txt\', \'unsafe\')"',
        },
        dependencies: {
          'existing-package': '1.2.3',
        },
      },
      null,
      2,
    ) + '\n',
    'utf8',
  )
  const packageLockFixture = JSON.stringify(
    {
      name: 'business-frontend-embed-smoke',
      version: '1.0.0',
      lockfileVersion: 1,
      requires: true,
      dependencies: {
        'existing-package': existingLockDependency,
      },
    },
    null,
    2,
  ).replaceAll('\n', '\r\n') + '\r\n'
  await writeFile(
    resolve(businessFrontendDir, 'package-lock.json'),
    packageLockFixture,
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
  type EafChatTokenProviderContext,
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
  tokenProvider: async (tokenContext?: EafChatTokenProviderContext) => {
    const pageInstanceId: string | undefined = tokenContext?.pageInstanceId
    const route: string | undefined = tokenContext?.route
    const origin: string | undefined = tokenContext?.origin
    void pageInstanceId
    void route
    void origin
    return 'token'
  },
  bridge,
  apiBase: embedRoot || platformBase,
  position: 'bottom-right',
  initialOpen: false,
  resizable: true,
  launcherDraggable: true,
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

  const extractedInstaller = resolve(
    skillExtractDir,
    artifactManifest.installScriptWithinSkill,
  )
  run(
    'node',
    [extractedInstaller, '--business-frontend-dir', businessFrontendDir],
    businessFrontendDir,
  )
  const vendoredTarballSha = await sha256File(normalizedInstallTarget)
  if (vendoredTarballSha !== expectedSha) {
    throw new Error(
      `Vendored tarball SHA mismatch: ${vendoredTarballSha} != ${expectedSha}`,
    )
  }

  const pkgJson = JSON.parse(await readFile(resolve(businessFrontendDir, 'package.json'), 'utf8'))
  const depVersion =
    pkgJson.dependencies?.['@reachai/embed-chat'] || pkgJson.devDependencies?.['@reachai/embed-chat']
  if (!depVersion) {
    throw new Error('business package.json missing @reachai/embed-chat after install')
  }
  const normalizedDependency = String(depVersion).replaceAll('\\', '/')
  if (!normalizedDependency.includes(artifactManifest.vendoredArtifactPath)) {
    throw new Error(`dependency is not repo-relative: ${depVersion}`)
  }
  if (normalizedDependency.includes(workRoot.replaceAll('\\', '/'))) {
    throw new Error(`dependency leaked temporary Skill path: ${depVersion}`)
  }

  const packageLockSource = await readFile(
    resolve(businessFrontendDir, 'package-lock.json'),
    'utf8',
  )
  const packageLock = JSON.parse(packageLockSource)
  if (packageLock.lockfileVersion !== 1) {
    throw new Error(`installer changed lockfileVersion: ${packageLock.lockfileVersion}`)
  }
  if (JSON.stringify(packageLock.dependencies?.['existing-package'])
      !== JSON.stringify(existingLockDependency)) {
    throw new Error('installer changed an unrelated package-lock dependency')
  }
  const lockedEmbed = packageLock.dependencies?.['@reachai/embed-chat']
  if (lockedEmbed?.version !== normalizedDependency
      || !String(lockedEmbed?.integrity || '').startsWith('sha512-')) {
    throw new Error('package-lock v1 missing stable vendored dependency and integrity')
  }
  if (packageLockSource.replaceAll('\r\n', '').includes('\n')) {
    throw new Error('installer changed package-lock CRLF formatting')
  }
  try {
    await readFile(postinstallMarker)
    throw new Error('installer executed the business application postinstall lifecycle')
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error
  }

  await writeFile(
    resolve(devBusinessFrontendDir, 'package.json'),
    JSON.stringify(
      {
        name: 'business-frontend-dev-dependency-smoke',
        version: '1.0.0',
        private: true,
        devDependencies: {
          '@reachai/embed-chat': 'file:legacy-local-artifact.tgz',
        },
      },
      null,
      2,
    ) + '\n',
    'utf8',
  )
  await writeFile(
    resolve(devBusinessFrontendDir, 'package-lock.json'),
    JSON.stringify(
      {
        name: 'business-frontend-dev-dependency-smoke',
        version: '1.0.0',
        lockfileVersion: 3,
        requires: true,
        packages: {
          '': {
            name: 'business-frontend-dev-dependency-smoke',
            version: '1.0.0',
            devDependencies: {
              '@reachai/embed-chat': 'file:legacy-local-artifact.tgz',
            },
          },
          'node_modules/existing-dev-package': {
            version: '1.2.3',
            dev: true,
          },
        },
      },
      null,
      2,
    ) + '\n',
    'utf8',
  )
  run(
    'node',
    [extractedInstaller, '--business-frontend-dir', devBusinessFrontendDir],
    devBusinessFrontendDir,
  )
  const devPackageJson = JSON.parse(await readFile(
    resolve(devBusinessFrontendDir, 'package.json'),
    'utf8',
  ))
  if (devPackageJson.dependencies?.['@reachai/embed-chat']
      || !devPackageJson.devDependencies?.['@reachai/embed-chat']) {
    throw new Error('installer moved an existing devDependency into dependencies')
  }
  const devPackageLock = JSON.parse(await readFile(
    resolve(devBusinessFrontendDir, 'package-lock.json'),
    'utf8',
  ))
  const devRoot = devPackageLock.packages?.['']
  const devInstalled = devPackageLock.packages?.['node_modules/@reachai/embed-chat']
  if (devPackageLock.lockfileVersion !== 3
      || devRoot?.dependencies?.['@reachai/embed-chat']
      || !devRoot?.devDependencies?.['@reachai/embed-chat']
      || devInstalled?.dev !== true
      || devPackageLock.packages?.['node_modules/existing-dev-package']?.version !== '1.2.3') {
    throw new Error('package-lock v3 did not preserve devDependency semantics')
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
    "export type EafChatPosition = 'inline' | 'bottom-right' | 'bottom-left'",
    'export interface EafChatTokenProviderContext',
    'pageInstanceId: string;',
    'route: string;',
    'origin: string;',
    'resizable?: boolean',
    'launcherDraggable?: boolean',
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

  const installedStyle = await readFile(resolve(pkgRoot, 'style.css'), 'utf8')
  if (!(pkg.exports || {})['./style.css']) {
    throw new Error('package.json exports missing ./style.css')
  }
  for (const assetUrl of ['./agent-prism.webp', './conversation-atmosphere.webp']) {
    if (!installedStyle.includes(assetUrl)) {
      throw new Error(`Installed style.css missing packaged asset URL: ${assetUrl}`)
    }
  }
  if (installedStyle.includes('data:image/')) {
    throw new Error('Installed style.css must not inline image assets as data URLs')
  }
  for (const unsupportedCss of ['@container', ':has(']) {
    if (installedStyle.includes(unsupportedCss)) {
      throw new Error(
        `Installed style.css contains ${unsupportedCss}, which breaks legacy enterprise CSS optimizers`,
      )
    }
  }

  const assets = (await readdir(pkgRoot)).filter((name) => /\.(webp|png|jpe?g|gif|svg)$/i.test(name))
  for (const assetName of ['agent-prism.webp', 'conversation-atmosphere.webp']) {
    if (!assets.includes(assetName)) {
      throw new Error(`Installed package missing asset: ${assetName}`)
    }
  }
  console.log(`[verify-embed-chat-tarball] mode=offline-synthetic-skill-zip`)
  console.log(`[verify-embed-chat-tarball] sha256=${actualSha}`)
  console.log(`[verify-embed-chat-tarball] skillArtifactPath=${artifactRel}`)
  console.log(`[verify-embed-chat-tarball] vendoredInstallTarget=${normalizedInstallTarget}`)
  console.log(`[verify-embed-chat-tarball] runtime exports ok; bridge.registerAction=function; bridge.register=undefined`)
  console.log('[verify-embed-chat-tarball] lockfile=v1-preserved lifecycleScripts=not-run')
  console.log(`[verify-embed-chat-tarball] session payload keys=${Object.keys(payload).join(',')}`)
  console.log(`[verify-embed-chat-tarball] createEafChat=ok tsc=ok style=ok assets=${assets.length}`)
  console.log('[verify-embed-chat-tarball] PASS offline-synthetic-skill-zip-install')
} finally {
  await rm(workRoot, { recursive: true, force: true })
}
