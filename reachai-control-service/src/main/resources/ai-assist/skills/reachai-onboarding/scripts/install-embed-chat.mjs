import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

function readArgument(name, fallback) {
  const index = process.argv.indexOf(name)
  if (index < 0) return fallback
  const value = process.argv[index + 1]
  if (!value || value.startsWith('--')) {
    throw new Error(`${name} requires a value`)
  }
  return value
}

function assertInside(parent, target, label) {
  const rel = relative(parent, target)
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error(`${label} must resolve below the business frontend directory`)
  }
}

async function sha256(filePath) {
  const bytes = await readFile(filePath)
  return createHash('sha256').update(bytes).digest('hex')
}

async function sriSha512(filePath) {
  const bytes = await readFile(filePath)
  return `sha512-${createHash('sha512').update(bytes).digest('base64')}`
}

function jsonFormatting(source) {
  return {
    indent: source.match(/^[\t ]+(?=")/m)?.[0] || '  ',
    newline: source.includes('\r\n') ? '\r\n' : '\n',
  }
}

function stringifyJson(value, formatting) {
  const json = JSON.stringify(value, null, formatting.indent) + '\n'
  return formatting.newline === '\n'
    ? json
    : json.replaceAll('\n', formatting.newline)
}

function setDependency(packageJson, packageName, spec) {
  const devDependency = Object.prototype.hasOwnProperty.call(
    packageJson.devDependencies || {},
    packageName,
  )
  if (devDependency) {
    packageJson.devDependencies[packageName] = spec
    return true
  }
  packageJson.dependencies ||= {}
  packageJson.dependencies[packageName] = spec
  return false
}

function updatePackageLock(
  packageLock,
  packageName,
  spec,
  version,
  integrity,
  devDependency,
) {
  const lockfileVersion = Number(packageLock.lockfileVersion || 1)
  if (![1, 2, 3].includes(lockfileVersion)) {
    throw new Error(
      `Unsupported package-lock.json lockfileVersion=${packageLock.lockfileVersion}`,
    )
  }

  if (lockfileVersion === 1) {
    packageLock.dependencies ||= {}
    packageLock.dependencies[packageName] = {
      version: spec,
      integrity,
      ...(devDependency ? { dev: true } : {}),
    }
    return
  }

  packageLock.packages ||= {}
  packageLock.packages[''] ||= {}
  const rootSection = devDependency ? 'devDependencies' : 'dependencies'
  const oppositeRootSection = devDependency ? 'dependencies' : 'devDependencies'
  packageLock.packages[''][rootSection] ||= {}
  packageLock.packages[''][rootSection][packageName] = spec
  if (packageLock.packages[''][oppositeRootSection]) {
    delete packageLock.packages[''][oppositeRootSection][packageName]
  }
  packageLock.packages[`node_modules/${packageName}`] = {
    version,
    resolved: spec,
    integrity,
    ...(devDependency ? { dev: true } : {}),
  }

  if (lockfileVersion === 2) {
    packageLock.dependencies ||= {}
    packageLock.dependencies[packageName] = {
      version,
      resolved: spec,
      integrity,
      ...(devDependency ? { dev: true } : {}),
    }
  }
}

function installVendoredTarball(
  businessFrontendDir,
  tarballPath,
  packageName,
) {
  const installedPackageDir = resolve(
    businessFrontendDir,
    'node_modules',
    packageName,
  )
  assertInside(
    businessFrontendDir,
    installedPackageDir,
    'installed package directory',
  )

  const list = spawnSync('tar', ['-tzf', tarballPath], {
    encoding: 'utf8',
    shell: false,
  })
  if (list.status !== 0) {
    throw new Error(
      `Unable to inspect the bundled npm tarball:\n${list.stderr || ''}`,
    )
  }
  const unsafeEntry = String(list.stdout || '')
    .split(/\r?\n/)
    .filter(Boolean)
    .find(entry => {
      const normalized = entry.replaceAll('\\', '/')
      return !normalized.startsWith('package/')
        || normalized.split('/').includes('..')
    })
  if (unsafeEntry) {
    throw new Error(`Bundled npm tarball contains an unsafe path: ${unsafeEntry}`)
  }

  return { installedPackageDir }
}

const scriptDir = dirname(fileURLToPath(import.meta.url))
const skillRoot = resolve(scriptDir, '..')
const manifestPath = resolve(skillRoot, 'artifacts', 'manifest-artifact.json')
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
const businessFrontendDir = resolve(
  readArgument('--business-frontend-dir', process.cwd()),
)
const packageJsonPath = resolve(businessFrontendDir, 'package.json')
const packageJsonSource = await readFile(packageJsonPath, 'utf8')
const packageJsonFormatting = jsonFormatting(packageJsonSource)
const packageJson = JSON.parse(packageJsonSource)
const packageLockPath = resolve(businessFrontendDir, 'package-lock.json')
let packageLockSource
try {
  packageLockSource = await readFile(packageLockPath, 'utf8')
} catch (error) {
  if (error?.code !== 'ENOENT') throw error
}

const sourceTarball = resolve(skillRoot, 'artifacts', manifest.filename)
const vendoredRelativePath = String(manifest.vendoredArtifactPath || '')
if (!vendoredRelativePath) {
  throw new Error('Artifact manifest is missing vendoredArtifactPath')
}
const vendoredTarball = resolve(businessFrontendDir, vendoredRelativePath)
assertInside(businessFrontendDir, vendoredTarball, 'vendoredArtifactPath')

const sourceSha = await sha256(sourceTarball)
if (sourceSha !== manifest.integritySha256) {
  throw new Error(
    `Skill artifact SHA-256 mismatch: expected=${manifest.integritySha256} actual=${sourceSha}`,
  )
}

await mkdir(dirname(vendoredTarball), { recursive: true })
await copyFile(sourceTarball, vendoredTarball)
const vendoredSha = await sha256(vendoredTarball)
if (vendoredSha !== manifest.integritySha256) {
  throw new Error(
    `Vendored artifact SHA-256 mismatch: expected=${manifest.integritySha256} actual=${vendoredSha}`,
  )
}

const dependencySpec = `file:${vendoredRelativePath.replaceAll('\\', '/')}`
const { installedPackageDir } = installVendoredTarball(
  businessFrontendDir,
  vendoredTarball,
  manifest.packageName,
)
await rm(installedPackageDir, { recursive: true, force: true })
await mkdir(installedPackageDir, { recursive: true })
const extract = spawnSync('tar', [
  '-xzf',
  vendoredTarball,
  '-C',
  installedPackageDir,
  '--strip-components=1',
], {
  encoding: 'utf8',
  shell: false,
})
if (extract.status !== 0) {
  throw new Error(
    `Bundled npm tarball extraction failed:\n${extract.stderr || ''}`,
  )
}

const devDependency = setDependency(
  packageJson,
  manifest.packageName,
  dependencySpec,
)
await writeFile(
  packageJsonPath,
  stringifyJson(packageJson, packageJsonFormatting),
  'utf8',
)

if (packageLockSource !== undefined) {
  const packageLockFormatting = jsonFormatting(packageLockSource)
  const packageLock = JSON.parse(packageLockSource)
  updatePackageLock(
    packageLock,
    manifest.packageName,
    dependencySpec,
    manifest.version,
    await sriSha512(vendoredTarball),
    devDependency,
  )
  await writeFile(
    packageLockPath,
    stringifyJson(packageLock, packageLockFormatting),
    'utf8',
  )
}

const dependency = packageJson.dependencies?.[manifest.packageName]
  || packageJson.devDependencies?.[manifest.packageName]
if (!dependency || !String(dependency).includes(vendoredRelativePath.replaceAll('\\', '/'))) {
  throw new Error(
    `${manifest.packageName} dependency was not recorded with the stable vendored path`,
  )
}
if (String(dependency).includes(skillRoot.replaceAll('\\', '/'))) {
  throw new Error('package.json must not retain the temporary Skill extract path')
}

const installedPackageJson = JSON.parse(await readFile(
  resolve(installedPackageDir, 'package.json'),
  'utf8',
))
if (installedPackageJson.version !== manifest.version) {
  throw new Error(
    `${manifest.packageName} installed version mismatch: expected=${manifest.version} actual=${installedPackageJson.version}`,
  )
}

console.log(`[install-embed-chat] package=${manifest.packageName}@${manifest.version}`)
console.log(`[install-embed-chat] vendored=${vendoredRelativePath}`)
console.log(`[install-embed-chat] sha256=${vendoredSha}`)
console.log('[install-embed-chat] lifecycleScripts=disabled lockfileFormat=preserved')
console.log('[install-embed-chat] PASS stable repo-local install')
