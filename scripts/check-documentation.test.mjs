import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-documentation.mjs')
function write(root, name, text) {
  const target = path.join(root, name)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}
function runFixture(change = () => {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-documentation-'))
  try {
    write(root, 'scripts/check-documentation.mjs', fs.readFileSync(script, 'utf8'))
    write(root, 'pom.xml', '<project><modules></modules></project>')
    write(root, 'README.md', '# ReachAI\n')
    write(root, 'docs/README.md', '# 文档\n\n[当前契约](current.md)\n')
    write(root, 'docs/current.md', '# 当前契约\n')
    change(root)
    const result = spawnSync(process.execPath, ['scripts/check-documentation.mjs'], { cwd: root, encoding: 'utf8' })
    if (result.error) throw result.error
    return { status: result.status, output: result.stdout + result.stderr }
  } finally {
    const target = path.resolve(root)
    const relative = path.relative(path.resolve(os.tmpdir()), target)
    assert.ok(relative && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative))
    assert.ok(path.basename(target).startsWith('reachai-documentation-'))
    fs.rmSync(target, { recursive: true, force: true })
  }
}
test('单一入口可以读取当前核心文档', () => {
  const result = runFixture()
  assert.equal(result.status, 0, result.output)
})
test('跨层阅读链接无需每个目录重复索引', () => {
  const result = runFixture(root => {
    write(root, 'docs/current.md', '# 当前契约\n\n[详细接口](contracts/api.md)\n')
    write(root, 'docs/contracts/api.md', '# API\n')
  })
  assert.equal(result.status, 0, result.output)
})
test('当前契约拒绝退役源码符号', () => {
  const result = runFixture(root => write(root, 'docs/current.md', '# 当前\n\n`WorkflowReleaseValidationService`\n'))
  assert.equal(result.status, 1)
  assert.match(result.output, /稳定文档仍引用已重命名或不存在的源码符号/)
})
test('删除文档后的残留引用被拒绝', () => {
  const result = runFixture(root => write(root, 'docs/current.md', '# 当前\n\n[不存在](removed.md)\n'))
  assert.equal(result.status, 1)
  assert.match(result.output, /本地链接不存在: removed.md/)
})
test('文件名或其他目录同名链接不构成阅读入口', () => {
  const result = runFixture(root => {
    write(root, 'docs/README.md', '# 文档\n\ncurrent.md\n\n[其他](other/current.md)\n')
    write(root, 'docs/other/current.md', '# 其他\n')
  })
  assert.equal(result.status, 1)
  assert.match(result.output, /未从文档入口可达: docs\/current.md/)
})
test('核心文档不再引用本机历史执行日志', () => {
  const result = runFixture(root => write(root, 'docs/current.md', '# 当前\n\n[旧日志](../output/tasks/old/log.txt)\n'))
  assert.equal(result.status, 1)
  assert.match(result.output, /核心文档不得链接本机历史执行产物/)
})
test('当前契约拒绝退役或无前缀表名', () => {
  const result = runFixture(root => write(root, 'docs/current.md', '# 当前\n\n`tool_definition`\n'))
  assert.equal(result.status, 1)
  assert.match(result.output, /稳定入口仍引用退役\/无前缀表名/)
})
test('贡献指南和分发 Skill 的本地引用继续检查', () => {
  const result = runFixture(root => {
    write(root, 'CONTRIBUTING.md', '# 贡献\n\n[缺失](missing.md)\n')
    write(root, 'reachai-control-service/src/main/resources/ai-assist/skills/example/SKILL.md', '# Skill\n\n[缺失](missing.md)\n')
  })
  assert.equal(result.status, 1)
  assert.match(result.output, /CONTRIBUTING.md.*本地链接不存在/)
  assert.match(result.output, /SKILL.md.*本地链接不存在/)
})
test('维护文档不得绑定个人机器路径', () => {
  const result = runFixture(root => write(root, 'CONTRIBUTING.md', '# 贡献\n\nC:\\Users\\example\\maven\\bin\\mvn.cmd\n'))
  assert.equal(result.status, 1)
  assert.match(result.output, /维护文档不得包含个人机器用户目录/)
})
