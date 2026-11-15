import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { delimiter, join, resolve } from 'node:path'
import http from 'node:http'
import https from 'node:https'

// This installer needs Node 18+, Maven and a JDK. It never invokes a shell on Unix.
const options = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  const name = process.argv[i]
  const value = process.argv[i + 1]
  if (!name?.startsWith('--') || !value || value.startsWith('--') || options.has(name)) {
    throw new Error('Expected unique --option value pairs')
  }
  options.set(name, value)
}
const allowed = new Set(['--group-id', '--artifact-id', '--version', '--jar-url', '--jar-sha256',
  '--pom-url', '--pom-sha256', '--maven-executable', '--maven-local-repository'])
for (const name of options.keys()) if (!allowed.has(name)) throw new Error(`Unknown option ${name}`)
function required(name, pattern) {
  const value = options.get(name)
  if (!value || !pattern.test(value)) throw new Error(`Invalid or missing ${name}`)
  return value
}
const coordinate = /^[A-Za-z0-9_.-]+$/
const groupId = required('--group-id', coordinate)
const artifactId = required('--artifact-id', coordinate)
const version = required('--version', coordinate)
const jarHash = required('--jar-sha256', /^[a-f0-9]{64}$/i).toLowerCase()
const pomHash = required('--pom-sha256', /^[a-f0-9]{64}$/i).toLowerCase()
function artifactUrl(name) {
  const url = new URL(required(name, /^https?:\/\//i))
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error(`Invalid ${name}`)
  }
  return url
}
const jarUrl = artifactUrl('--jar-url')
const pomUrl = artifactUrl('--pom-url')

async function download(url, redirects = 0) {
  if (redirects > 5) throw new Error('Too many artifact redirects')
  return new Promise((resolveDownload, reject) => {
    const request = (url.protocol === 'https:' ? https : http).get(url, (response) => {
      if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
        response.resume()
        try {
          const next = new URL(response.headers.location, url)
          if (!['http:', 'https:'].includes(next.protocol) || next.username || next.password
              || (url.protocol === 'https:' && next.protocol !== 'https:')) {
            throw new Error('Unsafe artifact redirect')
          }
          resolveDownload(download(next, redirects + 1))
        } catch (error) { reject(error) }
        return
      }
      if (response.statusCode !== 200) {
        response.resume()
        reject(new Error(`Artifact download failed (HTTP ${response.statusCode})`))
        return
      }
      const chunks = []
      let size = 0
      response.on('data', (chunk) => {
        size += chunk.length
        if (size > 100 * 1024 * 1024) response.destroy(new Error('Artifact exceeds 100 MiB'))
        else chunks.push(chunk)
      })
      response.on('end', () => resolveDownload(Buffer.concat(chunks)))
      response.on('error', reject)
    })
    request.setTimeout(60_000, () => request.destroy(new Error('Artifact download timed out')))
    request.on('error', reject)
  })
}
function verified(bytes, expected, label) {
  const actual = createHash('sha256').update(bytes).digest('hex')
  if (actual !== expected) throw new Error(`${label} SHA-256 mismatch for ${groupId}:${artifactId}:${version}`)
  return actual
}

const temporaryRoot = await mkdtemp(join(tmpdir(), 'reachai-java-sdk-'))
try {
  const [jar, pom] = await Promise.all([download(jarUrl), download(pomUrl)])
  const actualJar = verified(jar, jarHash, 'JAR')
  const actualPom = verified(pom, pomHash, 'POM')
  const jarPath = join(temporaryRoot, 'artifact.jar')
  const pomPath = join(temporaryRoot, 'artifact.pom')
  await Promise.all([writeFile(jarPath, jar), writeFile(pomPath, pom)])
  const args = ['install:install-file', `-Dfile=${jarPath}`, `-DpomFile=${pomPath}`, '-DgeneratePom=false']
  if (options.has('--maven-local-repository')) {
    args.push(`-Dmaven.repo.local=${resolve(options.get('--maven-local-repository'))}`)
  }
  let executable = options.get('--maven-executable') || (process.platform === 'win32' ? 'mvn.cmd' : 'mvn')
  // Windows batch files require cmd.exe. Reject metacharacters and expansion in every argument.
  const windows = process.platform === 'win32'
  if (windows && /(?:^|[\\/])mvn$/i.test(executable)) executable += '.cmd'
  if (windows && !/[\\/]/.test(executable)) {
    // Resolve with filesystem APIs so non-ASCII PATH entries do not pass through a console code page.
    const name = executable
    executable = (process.env.PATH || '').split(delimiter).map(entry => {
      const directory = entry.trim().replace(/^"(.*)"$/, '$1')
      return directory ? resolve(directory, name) : ''
    }).find(candidate => candidate && existsSync(candidate))
    if (!executable) throw new Error('Maven executable not found on PATH')
  }
  if (windows && [executable, ...args].some(value => /["%!?^&|<>\r\n]/.test(value))) {
    throw new Error('Maven command or path contains unsupported Windows shell characters')
  }
  const result = windows
    ? spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `"${[executable, ...args].map(value => `"${value}"`).join(' ')}"`],
      { cwd: temporaryRoot, stdio: 'inherit', windowsVerbatimArguments: true })
    : spawnSync(executable, args, { cwd: temporaryRoot, stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Maven install failed (exit ${result.status ?? 'unknown'})`)
  console.log(`[install-java-sdk] installed=${groupId}:${artifactId}:${version}`)
  console.log(`[install-java-sdk] jarSha256=${actualJar}`)
  console.log(`[install-java-sdk] pomSha256=${actualPom}`)
} finally {
  await rm(temporaryRoot, { recursive: true, force: true })
}
