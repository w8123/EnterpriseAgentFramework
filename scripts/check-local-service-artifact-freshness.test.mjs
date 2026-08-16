import assert from 'node:assert'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, rm, utimes, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-local-service-artifact-freshness.mjs')
const baseTime = 1_700_000_000_000

await withFixture(async fixture => {
  await fixture.createSourceAndJar({ sourceTime: baseTime, jarTime: baseTime + 1_000 })
  const result = await fixture.run()
  assert.strictEqual(result.status, 0, result.stderr || result.stdout)
  assert.match(result.stdout, /\[fresh\] reachai-control-service/)
  assert.match(result.stdout, /freshness check passed/)
})

await withFixture(async fixture => {
  await fixture.createSourceAndJar({ sourceTime: baseTime + 2_000, jarTime: baseTime + 1_000 })
  const result = await fixture.run()
  assert.notStrictEqual(result.status, 0, result.stdout)
  assert.match(result.stderr, /\[stale\] reachai-control-service/)
  assert.match(result.stderr, /artifact is older than source/)
})

await withFixture(async fixture => {
  await fixture.createSource({ sourceTime: baseTime })
  const result = await fixture.run()
  assert.notStrictEqual(result.status, 0, result.stdout)
  assert.match(result.stderr, /no deployable JAR found/)
})

async function withFixture(run) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-artifact-freshness-'))
  const serviceName = 'reachai-control-service'
  const sourcePath = path.join(root, serviceName, 'src', 'main', 'java', 'Example.java')
  const jarPath = path.join(root, serviceName, 'target', `${serviceName}-1.0.0-SNAPSHOT.jar`)
  const fixture = {
    async createSource({ sourceTime }) {
      await createTimedFile(sourcePath, 'final class Example {}\n', sourceTime)
    },
    async createSourceAndJar({ sourceTime, jarTime }) {
      await createTimedFile(sourcePath, 'final class Example {}\n', sourceTime)
      await createTimedFile(jarPath, 'synthetic jar', jarTime)
    },
    run() {
      return runScript(root, serviceName)
    }
  }
  try {
    await run(fixture)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

async function createTimedFile(filePath, content, mtimeMs) {
  await mkdir(path.dirname(filePath), { recursive: true })
  await writeFile(filePath, content, 'utf8')
  const timestamp = new Date(mtimeMs)
  await utimes(filePath, timestamp, timestamp)
}

function runScript(root, serviceName) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, [
      scriptPath,
      '--root', root,
      '--services', serviceName
    ], {
      cwd: path.resolve('.'),
      env: process.env
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', chunk => {
      stdout += chunk.toString()
    })
    child.stderr.on('data', chunk => {
      stderr += chunk.toString()
    })
    child.on('close', status => {
      resolve({ status, stdout, stderr })
    })
  })
}
