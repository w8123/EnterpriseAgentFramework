/** Compile an independently installed npm consumer against Express 4 or 5 types. */
import { mkdtempSync, mkdirSync, cpSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const version = process.argv[2]
if (!['4', '5'].includes(version) || process.argv.length !== 3) throw new Error('Expected Express major version 4 or 5')
const base = resolve(tmpdir())
const temp = mkdtempSync(join(base, 'reachai-node-consumer-'))
const app = join(temp, 'consumer')
mkdirSync(app)
function npm(args, cwd) {
  const cmd = process.platform === 'win32' ? 'cmd.exe' : 'npm'
  const argv = process.platform === 'win32' ? ['/d', '/s', '/c', 'npm.cmd', ...args] : args
  const result = spawnSync(cmd, argv, { cwd, stdio: 'inherit', env: { ...process.env, npm_config_audit: 'false', npm_config_fund: 'false' } })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`npm ${args.join(' ')} failed`)
}
try {
  // Install the exact distribution served by Control, including its gzip
  // normalization, instead of silently testing a newly packed archive.
  const checked = spawnSync(process.execPath, [join(root, 'scripts/pack-node-sdk-artifacts.mjs'), '--check'], { cwd: root, stdio: 'inherit' })
  if (checked.error) throw checked.error
  if (checked.status !== 0) throw new Error('Node SDK distribution is stale or invalid')
  cpSync(join(root, 'reachai-control-service/src/main/resources/ai-assist/artifacts/node-sdk/reachai-node-sdk-1.0.0.tgz'), join(app, 'sdk.tgz'))
  writeFileSync(join(app, 'package.json'), '{"name":"sdk-consumer-check","version":"1.0.0","private":true}\n')
  writeFileSync(join(app, 'consumer.ts'), `import express from 'express'\nimport { ReachAI, createReachRouter } from '@reachai/node-sdk'\nconst app = express()\nconst reach = new ReachAI({registryUrl: 'http://127.0.0.1:1', projectCode: 'test', baseUrl: 'http://127.0.0.1:2'})\nreach.markEmptyInventoryComplete()\napp.use(createReachRouter(reach))\n`)
  npm(['install', '--no-save', '--package-lock=false', '--ignore-scripts', '--no-audit', '--no-fund', `express@${version}`, `@types/express@${version}`, '@types/node@20', 'typescript@5.7.3', './sdk.tgz'], app)
  const tsc = join(app, 'node_modules/typescript/bin/tsc')
  const result = spawnSync(process.execPath, [tsc, '--noEmit', '--strict', '--target', 'ES2022', '--module', 'commonjs', '--esModuleInterop', '--moduleResolution', 'node', 'consumer.ts'], { cwd: app, stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Express ${version} TypeScript consumer did not compile`)
  console.log(`Express ${version} TypeScript consumer compiled against installed SDK`)
} finally {
  if (!resolve(temp).startsWith(base + sep) || !resolve(temp).slice(base.length + 1).startsWith('reachai-node-consumer-')) throw new Error('unsafe temporary path')
  rmSync(temp, { recursive: true, force: true })
}
