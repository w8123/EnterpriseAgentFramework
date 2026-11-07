/** Build the exact Node backend package delivered by Control; no other SDKs are touched. */
import { createHash } from 'node:crypto'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, readdirSync, cpSync, chmodSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { gunzipSync } from 'node:zlib'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = join(root, 'reachai-node')
const out = join(root, 'reachai-control-service/src/main/resources/ai-assist/artifacts/node-sdk')
const file = 'reachai-node-sdk-1.0.0.tgz'
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex')
// npm's tar stream is reproducible, but different zlib releases can encode it
// differently. Use deterministic DEFLATE stored blocks and a fixed gzip header.
function canonicalGzip(data) {
  const chunks = [Buffer.from([0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 0xff])]
  let offset = 0
  do {
    const size = Math.min(65535, data.length - offset)
    const last = offset + size === data.length
    const header = Buffer.alloc(5)
    header[0] = last ? 1 : 0
    header.writeUInt16LE(size, 1)
    header.writeUInt16LE(0xffff ^ size, 3)
    chunks.push(header, data.subarray(offset, offset + size))
    offset += size
    if (last) break
  } while (true)
  let crc = 0xffffffff
  for (const value of data) {
    crc ^= value
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
  }
  const trailer = Buffer.alloc(8)
  trailer.writeUInt32LE((crc ^ 0xffffffff) >>> 0, 0)
  trailer.writeUInt32LE(data.length >>> 0, 4)
  chunks.push(trailer)
  return Buffer.concat(chunks)
}
function tree(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return tree(path)
    if (!entry.isFile()) throw new Error(`unexpected SDK source entry: ${path}`)
    return [path]
  })
}
function inputs() {
  return ['package.json', 'package-lock.json', 'tsconfig.json', 'README.md', 'LICENSE'].map((name) => join(source, name))
    .concat(tree(join(source, 'src'))).sort()
}
function sourceDigest() {
  const digest = createHash('sha256')
  for (const path of inputs()) {
    const data = readFileSync(path)
    if (data.includes(Buffer.from('\r\n'))) throw new Error(`SDK source must use LF line endings: ${path}`)
    digest.update(`${relative(source, path).replaceAll('\\', '/')}\0${sha(data)}\0${data.length}\n`)
  }
  const script = Buffer.from(readFileSync(fileURLToPath(import.meta.url), 'utf8').replaceAll('\r\n', '\n'))
  digest.update(`@pack-script\0${sha(script)}\0${script.length}\n`)
  return digest.digest('hex')
}
function check() {
  const bytes = readFileSync(join(out, file))
  if (sha(bytes) !== readFileSync(join(out, `${file}.sha256`), 'utf8').trim()) throw new Error('Node SDK archive digest mismatch')
  if (sourceDigest() !== readFileSync(join(out, `${file}.source.sha256`), 'utf8').trim()) throw new Error('Node SDK packaged source is stale')
  console.log(`Node SDK archive ${sha(bytes)} source ${sourceDigest()}`)
}
if (process.argv.length === 3 && process.argv[2] === '--check') {
  check()
} else if (process.argv.length === 2) {
  const before = sourceDigest()
  const base = resolve(tmpdir())
  const temp = mkdtempSync(join(base, 'reachai-node-pack-'))
  try {
    for (const path of inputs()) {
      const target = join(temp, relative(source, path))
      mkdirSync(dirname(target), { recursive: true })
      cpSync(path, target)
      // A Windows checkout mounted in Linux may report all source files as executable.
      // Package scripts/data have fixed non-executable modes across platforms.
      chmodSync(target, 0o644)
    }
    function run(args) {
      // These arguments are fixed by this script; no project or user input is passed to cmd.
      const command = process.platform === 'win32' ? 'cmd.exe' : 'npm'
      const argv = process.platform === 'win32' ? ['/d', '/s', '/c', 'npm.cmd', ...args] : args
      const result = spawnSync(command, argv, { cwd: temp, stdio: 'inherit', env: { ...process.env, npm_config_audit: 'false', npm_config_fund: 'false' } })
      if (result.error) throw result.error
      if (result.status !== 0) throw new Error(`npm ${args.join(' ')} failed`)
    }
    run(['ci', '--ignore-scripts'])
    run(['run', 'build'])
    run(['pack', '--ignore-scripts'])
    if (before !== sourceDigest()) throw new Error('SDK source changed during packaging')
    const bytes = canonicalGzip(gunzipSync(readFileSync(join(temp, file))))
    mkdirSync(out, { recursive: true })
    writeFileSync(join(out, file), bytes)
    writeFileSync(join(out, `${file}.sha256`), `${sha(bytes)}\n`)
    writeFileSync(join(out, `${file}.source.sha256`), `${before}\n`)
    check()
  } finally {
    // Only remove the directory just created by mkdtemp, under the known temporary root.
    if (!resolve(temp).startsWith(base + sep) || !resolve(temp).slice(base.length + 1).startsWith('reachai-node-pack-')) throw new Error('unsafe temporary path')
    rmSync(temp, { recursive: true, force: true })
  }
} else {
  throw new Error('usage: node scripts/pack-node-sdk-artifacts.mjs [--check]')
}
