/**
 * Pack @reachai/embed-chat into a versioned tarball and sync the deliverable
 * into reachai-control-service classpath resources for download + skill zip.
 *
 * Rebuild with: npm run build:sdk:pack
 */
import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, readdir, rename, writeFile } from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(root, '..')
const packageDir = resolve(root, 'dist-sdk', 'embed-chat')
const packageName = '@reachai/embed-chat'
const version = '1.0.0-SNAPSHOT'
const tarballFileName = 'reachai-embed-chat-1.0.0-SNAPSHOT.tgz'
const requiredFiles = [
  'package.json',
  'index.mjs',
  'index.cjs',
  'index.d.ts',
  'reachai-chat-embed.umd.js',
  'style.css',
]

async function ensureBuiltPackage() {
  const pkgJson = resolve(packageDir, 'package.json')
  try {
    await readFile(pkgJson)
  } catch {
    throw new Error('dist-sdk/embed-chat is missing. Run npm run build:sdk first (or use build:sdk:pack).')
  }
  for (const name of requiredFiles) {
    try {
      await readFile(resolve(packageDir, name))
    } catch {
      throw new Error(`Missing required SDK file before pack: ${name}`)
    }
  }
}

function runNpmPack() {
  const result = spawnSync('npm', ['pack', '--pack-destination', resolve(root, 'dist-sdk')], {
    cwd: packageDir,
    encoding: 'utf8',
    shell: true,
  })
  if (result.status !== 0) {
    throw new Error(`npm pack failed:\n${result.stdout || ''}\n${result.stderr || ''}`)
  }
  const lines = String(result.stdout || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  const produced = lines[lines.length - 1]
  if (!produced || !produced.endsWith('.tgz')) {
    throw new Error(`npm pack did not report a tarball name. stdout=${result.stdout}`)
  }
  return resolve(root, 'dist-sdk', basename(produced))
}

async function sha256File(filePath) {
  const bytes = await readFile(filePath)
  return createHash('sha256').update(bytes).digest('hex')
}

async function writeDeliverable(sourceTarball, integritySha256) {
  const distTarget = resolve(root, 'dist-sdk', tarballFileName)
  if (sourceTarball !== distTarget) {
    await rename(sourceTarball, distTarget).catch(async () => {
      await copyFile(sourceTarball, distTarget)
    })
  }

  const controlDir = resolve(
    repoRoot,
    'reachai-control-service',
    'src',
    'main',
    'resources',
    'ai-assist',
    'artifacts',
    'embed-chat',
  )
  await mkdir(controlDir, { recursive: true })
  const controlTarball = resolve(controlDir, tarballFileName)
  await copyFile(distTarget, controlTarball)
  await writeFile(resolve(controlDir, `${tarballFileName}.sha256`), `${integritySha256}\n`, 'utf8')
  await writeFile(resolve(root, 'dist-sdk', `${tarballFileName}.sha256`), `${integritySha256}\n`, 'utf8')

  const assetFiles = (await readdir(packageDir))
    .filter((name) => /\.(webp|png|jpe?g|gif|svg)$/i.test(name))
    .sort()
  const artifactPathWithinSkill = `reachai-onboarding/artifacts/${tarballFileName}`
  const installScriptWithinSkill = 'reachai-onboarding/scripts/install-embed-chat.mjs'
  const vendoredArtifactPath = `vendor/reachai/${tarballFileName}`
  const installWorkingDirectory = 'business-frontend-package-root'
  const installCommandTemplate =
    `node "{skillExtractDir}/${installScriptWithinSkill}" --business-frontend-dir "."`
  const manifest = {
    schema: 'reachai.embed-chat.artifact.v1',
    packageName,
    version,
    format: 'npm-tarball',
    filename: tarballFileName,
    integritySha256,
    downloadUrlTemplate: '/api/ai-assist/artifacts/embed-chat/{version}.tgz',
    artifactPathWithinSkill,
    installScriptWithinSkill,
    vendoredArtifactPath,
    installWorkingDirectory,
    installCommand: installCommandTemplate,
    installCommandTemplate,
    fallbackPolicy: 'skill-zip-tarball',
    requiredFiles: [...requiredFiles, ...assetFiles],
    rebuiltBy: 'ai-admin-front/scripts/pack-embed-chat.mjs',
  }
  await writeFile(resolve(controlDir, 'manifest-artifact.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  return { distTarget, controlTarball, manifest }
}

await ensureBuiltPackage()
const packed = runNpmPack()
const integritySha256 = await sha256File(packed)
const { distTarget, controlTarball, manifest } = await writeDeliverable(packed, integritySha256)
console.log(`[pack-embed-chat] package=${packageName}@${version}`)
console.log(`[pack-embed-chat] tarball=${distTarget}`)
console.log(`[pack-embed-chat] control=${controlTarball}`)
console.log(`[pack-embed-chat] sha256=${integritySha256}`)
console.log(`[pack-embed-chat] requiredFiles=${manifest.requiredFiles.join(', ')}`)
