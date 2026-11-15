import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { delimiter, join, resolve } from 'node:path'
import { test } from 'node:test'

const installer = resolve('reachai-control-service/src/main/resources/ai-assist/skills/reachai-onboarding/scripts/install-java-sdk.mjs')
const jar = Buffer.from('fixture jar bytes')
const pom = Buffer.from('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>sdk</artifactId><version>1.0</version></project>')
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
function run(args, env) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, [installer, ...args], { env: { ...process.env, ...env } })
    let output = ''
    child.stdout.on('data', chunk => { output += chunk })
    child.stderr.on('data', chunk => { output += chunk })
    child.on('error', reject)
    child.on('close', code => resolveRun({ code, output }))
  })
}

test('installer verifies both downloads before invoking Maven and rejects tampering', async () => {
  const root = await mkdtemp(join(tmpdir(), 'reachai 测试 sdk '))
  const marker = join(root, 'maven-called.txt')
  const win = process.platform === 'win32'
  const fakeMaven = join(root, win ? 'mvn.cmd' : 'mvn')
  await writeFile(fakeMaven, win
    ? '@echo off\r\necho invoked>"%REACHAI_TEST_MARKER%"\r\nexit /b 0\r\n'
    : '#!/bin/sh\nprintf invoked > "$REACHAI_TEST_MARKER"\n', { mode: 0o755 })
  const server = createServer((request, response) => {
    if (request.url === '/jar') response.end(jar)
    else if (request.url === '/pom') response.end(pom)
    else if (request.url === '/redirect') { response.writeHead(302, { location: '/jar' }); response.end() }
    else { response.writeHead(404); response.end() }
  })
  await new Promise(resolveListen => server.listen(0, '127.0.0.1', resolveListen))
  const base = `http://127.0.0.1:${server.address().port}`
  const args = ['--group-id', 'fixture', '--artifact-id', 'sdk', '--version', '1.0',
    '--jar-url', `${base}/redirect`, '--jar-sha256', hash(jar), '--pom-url', `${base}/pom`,
    '--pom-sha256', hash(pom), '--maven-executable', fakeMaven,
    '--maven-local-repository', join(root, 'local repo')]
  try {
    const ok = await run(args, { REACHAI_TEST_MARKER: marker })
    assert.equal(ok.code, 0, ok.output)
    assert.match(await readFile(marker, 'utf8'), /invoked/)
    await rm(marker)
    const pathArgs = [...args]
    pathArgs[pathArgs.indexOf('--maven-executable') + 1] = win ? 'mvn.cmd' : 'mvn'
    const viaPath = await run(pathArgs, { REACHAI_TEST_MARKER: marker, PATH: `${root}${delimiter}${process.env.PATH}` })
    assert.equal(viaPath.code, 0, viaPath.output)
    assert.match(await readFile(marker, 'utf8'), /invoked/)
    await rm(marker)
    const bad = [...args]
    bad[bad.indexOf('--pom-sha256') + 1] = '0'.repeat(64)
    const rejected = await run(bad, { REACHAI_TEST_MARKER: marker })
    assert.notEqual(rejected.code, 0)
    assert.match(rejected.output, /POM SHA-256 mismatch/)
    await assert.rejects(readFile(marker), { code: 'ENOENT' })
    bad[bad.indexOf('--pom-sha256') + 1] = hash(pom)
    bad[bad.indexOf('--jar-sha256') + 1] = '0'.repeat(64)
    const badJar = await run(bad, { REACHAI_TEST_MARKER: marker })
    assert.notEqual(badJar.code, 0)
    assert.match(badJar.output, /JAR SHA-256 mismatch/)
    await assert.rejects(readFile(marker), { code: 'ENOENT' })
    bad[bad.indexOf('--jar-sha256') + 1] = 'missing'
    const invalid = await run(bad, { REACHAI_TEST_MARKER: marker })
    assert.notEqual(invalid.code, 0)
    assert.match(invalid.output, /Invalid or missing --jar-sha256/)
  } finally {
    await new Promise(resolveClose => server.close(resolveClose))
    await rm(root, { recursive: true, force: true })
  }
})

test('real Maven installs the verified SDK into an isolated repository',
  { skip: !process.env.REACHAI_TEST_REAL_MAVEN }, async () => {
    const root = await mkdtemp(join(tmpdir(), 'reachai real maven '))
    const actualJar = await readFile(resolve('reachai-capability-sdk/target/reachai-capability-sdk-1.0.0-SNAPSHOT.jar'))
    const actualPom = await readFile(resolve('reachai-control-service/src/main/resources/ai-assist/artifacts/java-sdk/reachai-capability-sdk-1.0.0-SNAPSHOT.pom'))
    const server = createServer((request, response) => response.end(request.url === '/jar' ? actualJar : actualPom))
    await new Promise(resolveListen => server.listen(0, '127.0.0.1', resolveListen))
    const base = `http://127.0.0.1:${server.address().port}`
    const repo = join(root, 'isolated repository')
    try {
      const result = await run(['--group-id', 'com.enterprise.ai', '--artifact-id', 'reachai-capability-sdk',
        '--version', '1.0.0-SNAPSHOT', '--jar-url', `${base}/jar`, '--jar-sha256', hash(actualJar),
        '--pom-url', `${base}/pom`, '--pom-sha256', hash(actualPom), '--maven-executable', process.env.REACHAI_TEST_REAL_MAVEN,
        '--maven-local-repository', repo], {})
      assert.equal(result.code, 0, result.output)
      const installed = join(repo, 'com/enterprise/ai/reachai-capability-sdk/1.0.0-SNAPSHOT')
      assert.equal(hash(await readFile(join(installed, 'reachai-capability-sdk-1.0.0-SNAPSHOT.jar'))), hash(actualJar))
      assert.equal(hash(await readFile(join(installed, 'reachai-capability-sdk-1.0.0-SNAPSHOT.pom'))), hash(actualPom))
    } finally {
      await new Promise(resolveClose => server.close(resolveClose))
      await rm(root, { recursive: true, force: true })
    }
  })
