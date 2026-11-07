import { createHash } from 'node:crypto'
import { mkdir, readFile, readdir, unlink, writeFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { canonicalizeNuget } from './canonicalize-nuget.mjs'

// Build the SDK first; Control consumes the exact resulting package and checksum.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const name = 'ReachAI.AspNetCore.1.0.0.nupkg'
const sourceRoot = resolve(root, 'reachai-dotnet', 'ReachAI.AspNetCore')
const source = resolve(sourceRoot, 'bin', 'Release', 'reachai-package', name)
const output = resolve(root, 'reachai-control-service', 'src', 'main', 'resources',
  'ai-assist', 'artifacts', 'dotnet-sdk')
const digestPath = resolve(output, `${name}.sha256`)
const sourceDigestPath = resolve(output, `${name}.source.sha256`)
const packagePath = resolve(output, name)
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

// Build-relevant project files form one stable source identity across Windows/Unix checkouts.
// Output directories and informational README files are deliberately excluded.
async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    if (entry.isDirectory() && !['bin', 'obj', '.git'].includes(entry.name)) {
      files.push(...await sourceFiles(resolve(directory, entry.name)))
    } else if (entry.isFile() && /\.(cs|csproj|props|targets|json|resx)$/i.test(entry.name)) {
      files.push(resolve(directory, entry.name))
    }
  }
  return files
}

async function currentSourceDigest() {
  const files = (await sourceFiles(sourceRoot))
    .map(path => ({ path, name: relative(sourceRoot, path).replaceAll('\\', '/') }))
    .sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  if (!files.length) throw new Error('C# SDK source is missing')
  const digest = createHash('sha256')
  for (const file of files) {
    const contents = await readFile(file.path, 'utf8')
    if (contents.includes('\r\n')) throw new Error(`C# SDK source must use LF line endings: ${file.name}`)
    const bytes = Buffer.from(contents, 'utf8')
    digest.update(file.name).update('\0').update(sha256(bytes)).update('\0').update(String(bytes.length)).update('\n')
  }
  // Packaging flags affect the published binary too; record this script alongside SDK source.
  const scriptBytes = Buffer.from((await readFile(fileURLToPath(import.meta.url), 'utf8'))
    .replaceAll('\r\n', '\n'), 'utf8')
  digest.update('@pack-script').update('\0').update(sha256(scriptBytes)).update('\0')
    .update(String(scriptBytes.length)).update('\n')
  const canonicalizer = Buffer.from((await readFile(resolve(root, 'scripts', 'canonicalize-nuget.mjs'), 'utf8'))
    .replaceAll('\r\n', '\n'), 'utf8')
  digest.update('@nuget-canonicalizer').update('\0').update(sha256(canonicalizer)).update('\0')
    .update(String(canonicalizer.length)).update('\n')
  const sdkVersionBytes = Buffer.from((await readFile(resolve(root, 'reachai-dotnet', 'global.json'), 'utf8'))
    .replaceAll('\r\n', '\n'), 'utf8')
  digest.update('@global-json').update('\0').update(sha256(sdkVersionBytes)).update('\0')
    .update(String(sdkVersionBytes.length)).update('\n')
  return digest.digest('hex')
}

function requirePackage(bytes, path) {
  if (bytes.length < 100 || bytes[0] !== 0x50 || bytes[1] !== 0x4b ||
      !bytes.includes(Buffer.from('ReachAI.AspNetCore.nuspec'))) {
    throw new Error(`Expected a built ReachAI.AspNetCore NuGet package: ${path}`)
  }
}

const sourceDigest = await currentSourceDigest()
if (process.argv.slice(2).length === 1 && process.argv[2] === '--check') {
  const bytes = await readFile(packagePath)
  requirePackage(bytes, packagePath)
  const storedPackageDigest = (await readFile(digestPath, 'utf8')).trim()
  const storedSourceDigest = (await readFile(sourceDigestPath, 'utf8')).trim()
  if (storedPackageDigest !== sha256(bytes)) throw new Error('C# SDK package digest mismatch')
  if (storedSourceDigest !== sourceDigest) throw new Error('C# SDK packaged source is stale; rebuild and repack it')
  console.log(`[check-dotnet-sdk-artifacts] package=${storedPackageDigest} source=${sourceDigest}`)
} else if (process.argv.length === 2) {
  const project = resolve(sourceRoot, 'ReachAI.AspNetCore.csproj')
  const projectXml = await readFile(project, 'utf8')
  if (!/<PackageId>\s*ReachAI\.AspNetCore\s*<\/PackageId>/.test(projectXml) ||
      !/<Version>\s*1\.0\.0\s*<\/Version>/.test(projectXml)) {
    throw new Error('SDK package/version changed; update the distribution contract before packing')
  }
  // Remove only this generated package so a successful pack cannot reuse old output.
  await unlink(source).catch(error => { if (error.code !== 'ENOENT') throw error })
  const executable = process.env.REACHAI_DOTNET_EXECUTABLE || 'dotnet'
  const version = spawnSync(executable, ['--version'], { cwd: sourceRoot, encoding: 'utf8' })
  if (version.error) throw version.error
  if (version.status !== 0 || version.stdout.trim() !== '10.0.403') {
    throw new Error('The release package requires the pinned .NET SDK 10.0.403')
  }
  const flags = ['-p:Deterministic=true', '-p:DeterministicTimestamp=2026-01-01T00:00:00Z',
    '-p:SuppressImplicitGitSourceLink=true', '-p:EnableSourceControlManagerQueries=false',
    '-p:IncludeSourceRevisionInInformationalVersion=false', '-p:DebugType=none', '-p:DebugSymbols=false',
    `-p:PathMap=${sourceRoot}=/_/src`]
  const build = spawnSync(executable, ['build', project, '-c', 'Release', '--no-incremental', ...flags],
    { cwd: sourceRoot, stdio: 'inherit' })
  if (build.error) throw build.error
  if (build.status !== 0) throw new Error(`dotnet build failed: ${build.status}`)
  const pack = spawnSync(executable, ['pack', project, '-c', 'Release', '--no-build', '-o', dirname(source), ...flags],
    { cwd: sourceRoot, stdio: 'inherit' })
  if (pack.error) throw pack.error
  if (pack.status !== 0) throw new Error(`dotnet pack failed: ${pack.status}`)
  if (await currentSourceDigest() !== sourceDigest) throw new Error('C# SDK source changed during packaging; retry')
  const originalBytes = await readFile(source)
  requirePackage(originalBytes, source)
  const bytes = canonicalizeNuget(originalBytes)
  await mkdir(output, { recursive: true })
  await writeFile(packagePath, bytes)
  await writeFile(digestPath, `${sha256(bytes)}\n`, 'utf8')
  await writeFile(sourceDigestPath, `${sourceDigest}\n`, 'utf8')
  console.log(`[pack-dotnet-sdk-artifacts] ReachAI.AspNetCore:1.0.0 package=${sha256(bytes)} source=${sourceDigest}`)
} else {
  throw new Error('Usage: node scripts/pack-dotnet-sdk-artifacts.mjs [--check]')
}
