import { execFileSync } from 'node:child_process'
import { readdir, stat } from 'node:fs/promises'
import path from 'node:path'

const SERVICE_PORTS = new Map([
  ['reachai-model-service', 18601],
  ['reachai-knowledge-service', 18602],
  ['reachai-capability-service', 18605],
  ['reachai-runtime-service', 18604],
  ['reachai-control-service', 18603]
])

const options = parseArgs(process.argv.slice(2))
if (options.help) {
  printHelp()
  process.exit(0)
}

const workspaceRoot = path.resolve(options.root || process.cwd())
const serviceNames = options.services.length > 0
  ? options.services
  : [...SERVICE_PORTS.keys()]
const processSnapshot = options.checkRunning
  ? readWindowsProcessSnapshot(serviceNames)
  : new Map()

const results = []
for (const serviceName of serviceNames) {
  results.push(await inspectService(serviceName))
}

const failures = results.filter(result => !result.ok)
for (const result of results) {
  const output = `${result.ok ? '[fresh]' : '[stale]'} ${result.service}: ${result.detail}`
  if (result.ok) {
    console.log(output)
  } else {
    console.error(output)
  }
}

if (failures.length > 0) {
  console.error(`local service artifact freshness check failed: ${failures.length} issue(s)`)
  process.exit(1)
}
console.log('local service artifact freshness check passed')

async function inspectService(serviceName) {
  if (!SERVICE_PORTS.has(serviceName)) {
    return failure(serviceName, 'unknown physical service')
  }
  const moduleRoot = path.join(workspaceRoot, serviceName)
  const sourceRoot = path.join(moduleRoot, 'src', 'main')
  const targetRoot = path.join(moduleRoot, 'target')
  const latestSource = await latestFile(sourceRoot)
  if (!latestSource) {
    return failure(serviceName, `no source or resource file found below ${relative(sourceRoot)}`)
  }
  const jar = await latestServiceJar(targetRoot, serviceName)
  if (!jar) {
    return failure(serviceName, `no deployable JAR found below ${relative(targetRoot)}`)
  }
  if (latestSource.mtimeMs > jar.mtimeMs) {
    return failure(
      serviceName,
      `artifact is older than source: jar=${formatTime(jar.mtimeMs)} source=${formatTime(latestSource.mtimeMs)} sourceFile=${relative(latestSource.path)}`
    )
  }
  if (!options.checkRunning) {
    return success(serviceName, `jar=${relative(jar.path)} built=${formatTime(jar.mtimeMs)}`)
  }

  const running = processSnapshot.get(serviceName)
  if (!running) {
    return failure(serviceName, `port ${SERVICE_PORTS.get(serviceName)} is not listening`)
  }
  const expectedJar = normalizeWindowsPath(jar.path)
  const commandLine = normalizeWindowsPath(running.commandLine || '')
  if (!commandLine.includes(expectedJar)) {
    return failure(
      serviceName,
      `listener pid=${running.pid} does not use ${relative(jar.path)}`
    )
  }
  const startedMs = Date.parse(running.startedAt)
  if (!Number.isFinite(startedMs)) {
    return failure(serviceName, `could not parse start time for listener pid=${running.pid}`)
  }
  if (startedMs < jar.mtimeMs) {
    return failure(
      serviceName,
      `listener pid=${running.pid} started at ${formatTime(startedMs)} before jar build ${formatTime(jar.mtimeMs)}`
    )
  }
  return success(
    serviceName,
    `pid=${running.pid} started=${formatTime(startedMs)} jar=${relative(jar.path)} built=${formatTime(jar.mtimeMs)}`
  )
}

async function latestServiceJar(targetRoot, serviceName) {
  let entries
  try {
    entries = await readdir(targetRoot, { withFileTypes: true })
  } catch {
    return null
  }
  const candidates = []
  for (const entry of entries) {
    if (!entry.isFile()
        || !entry.name.startsWith(`${serviceName}-`)
        || !entry.name.endsWith('.jar')
        || /(?:sources|javadoc|tests)\.jar$/i.test(entry.name)) {
      continue
    }
    const filePath = path.join(targetRoot, entry.name)
    const fileStat = await stat(filePath)
    candidates.push({ path: filePath, mtimeMs: fileStat.mtimeMs })
  }
  return candidates.sort((left, right) => right.mtimeMs - left.mtimeMs)[0] || null
}

async function latestFile(root) {
  let entries
  try {
    entries = await readdir(root, { withFileTypes: true })
  } catch {
    return null
  }
  let latest = null
  for (const entry of entries) {
    const entryPath = path.join(root, entry.name)
    if (entry.isDirectory()) {
      latest = newer(latest, await latestFile(entryPath))
    } else if (entry.isFile()) {
      const fileStat = await stat(entryPath)
      latest = newer(latest, { path: entryPath, mtimeMs: fileStat.mtimeMs })
    }
  }
  return latest
}

function readWindowsProcessSnapshot(serviceNamesToRead) {
  if (process.platform !== 'win32') {
    throw new Error('--check-running currently supports Windows local processes only')
  }
  const requested = serviceNamesToRead.map(serviceName => ({
    serviceName,
    port: SERVICE_PORTS.get(serviceName)
  }))
  const ports = requested.map(item => item.port).join(',')
  const command = [
    `$ports=@(${ports})`,
    '$rows=@()',
    "$listeners=netstat -ano -p tcp | Select-String 'LISTENING' | ForEach-Object { $_.Line.Trim() }",
    'foreach($port in $ports){',
    "  $line=$listeners | Where-Object { $_ -match ('[:]' + $port + '\\s') } | Select-Object -First 1",
    '  if(-not $line){continue}',
    "  $parts=$line -split '\\s+'",
    '  $processId=[int]$parts[-1]',
    '  $process=Get-Process -Id $processId -ErrorAction SilentlyContinue',
    '  $cim=Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processId) -ErrorAction SilentlyContinue',
    '  if($process){$rows += [pscustomobject]@{port=$port;pid=$processId;startedAt=$process.StartTime.ToString("o");commandLine=$cim.CommandLine}}',
    '}',
    'ConvertTo-Json -InputObject @($rows) -Compress'
  ].join('; ')
  const output = execFileSync('powershell.exe', ['-NoProfile', '-Command', command], {
    encoding: 'utf8',
    windowsHide: true
  }).trim()
  const rows = output ? JSON.parse(output) : []
  const byPort = new Map(rows.map(row => [Number(row.port), row]))
  return new Map(requested
    .filter(item => byPort.has(item.port))
    .map(item => [item.serviceName, byPort.get(item.port)]))
}

function parseArgs(argv) {
  const parsed = {
    root: null,
    services: [],
    checkRunning: false,
    help: false
  }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--help' || arg === '-h') {
      parsed.help = true
    } else if (arg === '--check-running') {
      parsed.checkRunning = true
    } else if (arg === '--root') {
      parsed.root = requiredValue(argv, ++index, arg)
    } else if (arg === '--services') {
      parsed.services = requiredValue(argv, ++index, arg)
        .split(',')
        .map(value => value.trim())
        .filter(Boolean)
    } else {
      throw new Error(`unknown argument: ${arg}`)
    }
  }
  return parsed
}

function requiredValue(argv, index, flag) {
  const value = argv[index]
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} requires a value`)
  }
  return value
}

function newer(left, right) {
  if (!left) return right
  if (!right) return left
  return right.mtimeMs > left.mtimeMs ? right : left
}

function success(service, detail) {
  return { ok: true, service, detail }
}

function failure(service, detail) {
  return { ok: false, service, detail }
}

function relative(filePath) {
  return path.relative(workspaceRoot, filePath) || '.'
}

function normalizeWindowsPath(value) {
  return String(value).replaceAll('/', '\\').toLowerCase()
}

function formatTime(value) {
  return new Date(value).toISOString()
}

function printHelp() {
  console.log(`Usage: node scripts/check-local-service-artifact-freshness.mjs [options]

Checks that each local physical service JAR is newer than every src/main file.
On Windows, --check-running also verifies the expected JAR command line and
requires the listener process to have started after the JAR was built.

Options:
  --root <path>         Workspace root (defaults to cwd)
  --services <csv>     Restrict the physical service modules
  --check-running      Check fixed local ports 18601-18605 and process start time
  --help               Show this help`)
}
