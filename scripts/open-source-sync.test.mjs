import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const scriptsRoot = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.dirname(scriptsRoot)
const policy = JSON.parse(fs.readFileSync(path.join(scriptsRoot, 'open-source-policy.json'), 'utf8'))
const git = (root, args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim()
function write(root, name, text) {
  const target = path.join(root, name)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}
function init(root) {
  fs.mkdirSync(root, { recursive: true })
  git(root, ['init', '-q', '-b', 'main'])
  git(root, ['config', 'user.name', 'Fixture'])
  git(root, ['config', 'user.email', 'fixture@example.invalid'])
  git(root, ['config', 'core.autocrlf', 'false'])
  git(root, ['config', 'commit.gpgsign', 'false'])
  write(root, 'README.md', '# Public project\n')
  write(root, '.gitignore', '/reachai-website/\n')
  commit(root, 'base')
}
function commit(root, message) {
  git(root, ['add', '-A'])
  git(root, ['commit', '-qm', message])
}
function run(root, name, args = [], input) {
  const result = spawnSync(process.execPath, [path.join(scriptsRoot, name), ...args], { cwd: root, encoding: 'utf8', input })
  if (result.error) throw result.error
  return { status: result.status, output: result.stdout + result.stderr }
}
function fixture(work) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-open-source-test-'))
  try { work(root) } finally {
    const relative = path.relative(os.tmpdir(), root)
    assert.ok(relative && !relative.startsWith('..') && !path.isAbsolute(relative))
    assert.ok(path.basename(root).startsWith('reachai-open-source-test-'))
    fs.rmSync(root, { recursive: true, force: true })
  }
}
function syncFixture(root) {
  const target = path.join(root, 'public')
  const source = path.join(root, 'development')
  init(target)
  init(source)
  git(target, ['remote', 'add', 'origin', `https://github.com/${policy.publicRepository}.git`])
  for (const checkout of [source, target]) {
    for (const name of policy.requiredFiles) write(checkout, name, fs.readFileSync(path.join(repoRoot, name), 'utf8'))
    commit(checkout, 'sync rules')
  }
  write(source, '.gitignore', '*.log\n')
  write(source, 'README.md', '# Updated project\n')
  write(source, 'docs/new.md', '# New documentation\n')
  write(source, 'reachai-website/index.html', 'private website source\n')
  commit(source, 'development includes website')
  return { source, target, args: ['--source', source, '--ref', 'main'] }
}

test('ignored website files on disk are allowed; staged website files are blocked', () => fixture(root => {
  init(root)
  write(root, 'reachai-website/index.html', 'local copy\n')
  assert.equal(run(root, 'check-open-source.mjs').status, 0)
  git(root, ['add', '-f', 'reachai-website/index.html'])
  const result = run(root, 'check-open-source.mjs')
  assert.equal(result.status, 1)
  assert.match(result.output, /暂存区.*禁止开源/)
}))

test('deleting the website does not hide an earlier website commit', () => fixture(root => {
  init(root)
  write(root, 'reachai-website/index.html', 'private website source\n')
  git(root, ['add', '-f', 'reachai-website/index.html'])
  git(root, ['commit', '-qm', 'website in history'])
  git(root, ['rm', '-q', 'reachai-website/index.html'])
  git(root, ['commit', '-qm', 'remove website'])
  const result = run(root, 'check-open-source.mjs')
  assert.equal(result.status, 1)
  assert.match(result.output, /提交历史包含禁止开源/)
}))

test('case variations and a file at the excluded directory path are blocked', () => fixture(root => {
  init(root)
  write(root, 'REACHAI-WEBSITE', 'website moved to a file\n')
  git(root, ['add', '-f', 'REACHAI-WEBSITE'])
  assert.equal(run(root, 'check-open-source.mjs').status, 1)
}))

test('pre-push checks all refs, including annotated tags; deletion-only updates are allowed', () => fixture(root => {
  init(root)
  const clean = git(root, ['rev-parse', 'HEAD'])
  write(root, 'reachai-website/index.html', 'private website source\n')
  git(root, ['add', '-f', 'reachai-website/index.html'])
  git(root, ['commit', '-qm', 'website'])
  git(root, ['-c', 'tag.gpgsign=false', 'tag', '-a', 'website-tag', '-m', 'website'])
  const badTag = git(root, ['rev-parse', 'website-tag'])
  const zero = '0'.repeat(40)
  const updates = `refs/heads/public ${clean} refs/heads/public ${zero}\nrefs/tags/website-tag ${badTag} refs/tags/website-tag ${zero}\n`
  const blocked = run(root, 'check-open-source.mjs', ['--pre-push', 'origin', 'unused'], updates)
  assert.equal(blocked.status, 1)
  const deletion = run(root, 'check-open-source.mjs', ['--pre-push', 'origin', 'unused'], `(delete) ${zero} refs/heads/old ${clean}\n`)
  assert.equal(deletion.status, 0, deletion.output)
}))

test('snapshot sync previews then stages public files without website content or ancestry', () => fixture(root => {
  const { source, target, args } = syncFixture(root)
  write(target, 'reachai-website/local.txt', 'keep local ignored copy\n')
  write(source, 'README.md', '# Uncommitted development work\n')
  const before = git(target, ['rev-parse', 'HEAD'])
  const preview = run(target, 'sync-open-source.mjs', args)
  assert.equal(preview.status, 0, preview.output)
  assert.equal(git(target, ['status', '--porcelain=v1']), '')
  assert.equal(fs.readFileSync(path.join(target, 'README.md'), 'utf8'), '# Public project\n')
  const applied = run(target, 'sync-open-source.mjs', [...args, '--apply'])
  assert.equal(applied.status, 0, applied.output)
  assert.equal(fs.readFileSync(path.join(target, 'README.md'), 'utf8'), '# Updated project\n')
  assert.equal(fs.readFileSync(path.join(source, 'README.md'), 'utf8'), '# Uncommitted development work\n')
  assert.equal(fs.readFileSync(path.join(target, 'reachai-website/local.txt'), 'utf8'), 'keep local ignored copy\n')
  assert.equal(fs.existsSync(path.join(target, 'reachai-website/index.html')), false)
  assert.equal(git(target, ['ls-files', '--', 'reachai-website']), '')
  assert.match(fs.readFileSync(path.join(target, '.gitignore'), 'utf8'), /\/reachai-website\/\n$/)
  assert.match(git(target, ['ls-files', '--stage', '--', '.githooks/pre-push']), /^100755 /)
  assert.equal(git(target, ['rev-parse', 'HEAD']), before)
  assert.equal(run(target, 'check-open-source.mjs').status, 0)
  commit(target, 'filtered snapshot')
  assert.equal(run(target, 'check-open-source.mjs').status, 0)
  assert.equal(git(target, ['rev-list', '--count', 'HEAD']), '3')
}))

test('sync rejects a dirty target and preserves its work', () => fixture(root => {
  const { target, args } = syncFixture(root)
  write(target, 'README.md', '# Pending public change\n')
  const result = run(target, 'sync-open-source.mjs', [...args, '--apply'])
  assert.equal(result.status, 1)
  assert.match(result.output, /目标库有未提交文件/)
  assert.equal(fs.readFileSync(path.join(target, 'README.md'), 'utf8'), '# Pending public change\n')
}))

test('sync refuses sources that would remove publication safeguards', () => fixture(root => {
  const { source, target, args } = syncFixture(root)
  git(source, ['rm', '-q', '.githooks/pre-push'])
  git(source, ['commit', '-qm', 'remove safeguard'])
  const result = run(target, 'sync-open-source.mjs', [...args, '--apply'])
  assert.equal(result.status, 1)
  assert.match(result.output, /源修订缺少同步规则/)
  assert.equal(git(target, ['status', '--porcelain=v1']), '')
}))
