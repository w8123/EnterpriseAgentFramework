import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-service-table-ownership.mjs')

function write(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

function writeEntity(root, service, packageName, className, tableName) {
  write(root, `${service}/src/main/java/${packageName.replaceAll('.', '/')}/${className}.java`, `
package ${packageName};

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("${tableName}")
class ${className} {
}
`)
}

const missingDocRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-missing-doc-'))
writeEntity(missingDocRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')

const missingDocResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingDocRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingDocResult.status, 0, missingDocResult.stderr || missingDocResult.stdout)
assert.match(missingDocResult.stderr, /missing service table ownership doc/)

const missingRowRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-missing-row-'))
writeEntity(missingRowRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
write(missingRowRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
`)

const missingRowResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingRowRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingRowResult.status, 0, missingRowResult.stderr || missingRowResult.stdout)
assert.match(missingRowResult.stderr, /missing ownership row/)
assert.match(missingRowResult.stderr, /control_thing/)

const undocumentedSharedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-undocumented-shared-'))
writeEntity(undocumentedSharedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'SharedThingEntity', 'shared_thing')
writeEntity(undocumentedSharedRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeSharedThingEntity', 'shared_thing')
write(undocumentedSharedRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`shared_thing\` | \`reachai-control-service\` | - | Missing runtime exception |
`)

const undocumentedSharedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: undocumentedSharedRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(undocumentedSharedResult.status, 0, undocumentedSharedResult.stderr || undocumentedSharedResult.stdout)
assert.match(undocumentedSharedResult.stderr, /undocumented non-owner table access/)
assert.match(undocumentedSharedResult.stderr, /reachai-runtime-service -> shared_thing/)

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-allowed-'))
writeEntity(allowedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
writeEntity(allowedRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeSharedThingEntity', 'shared_thing')
writeEntity(allowedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'SharedThingEntity', 'shared_thing')
write(allowedRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`control_thing\` | \`reachai-control-service\` | - | Control-only table |
| \`shared_thing\` | \`reachai-control-service\` | \`reachai-runtime-service\` read | Runtime reads the project binding snapshot |
`)

const allowedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: allowedRoot,
  encoding: 'utf8'
})

assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)

const staleAdditionalAccessRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-stale-additional-'))
writeEntity(staleAdditionalAccessRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
write(staleAdditionalAccessRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`control_thing\` | \`reachai-control-service\` | \`reachai-runtime-service\` read | Stale exception |
`)

const staleAdditionalAccessResult = spawnSync(process.execPath, [scriptPath], {
  cwd: staleAdditionalAccessRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(staleAdditionalAccessResult.status, 0, staleAdditionalAccessResult.stderr || staleAdditionalAccessResult.stdout)
assert.match(staleAdditionalAccessResult.stderr, /stale additional direct access/)
assert.match(staleAdditionalAccessResult.stderr, /control_thing/)

const crossServiceMapperImportRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-cross-mapper-'))
write(crossServiceMapperImportRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/BadControlService.java', `
package com.enterprise.ai.control;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;

class BadControlService {
    private RuntimeWorkflowDefinitionMapper mapper;
}
`)
write(crossServiceMapperImportRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
`)

const crossServiceMapperImportResult = spawnSync(process.execPath, [scriptPath], {
  cwd: crossServiceMapperImportRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(crossServiceMapperImportResult.status, 0, crossServiceMapperImportResult.stderr || crossServiceMapperImportResult.stdout)
assert.match(crossServiceMapperImportResult.stderr, /cross-service Mapper\/Entity import/)
assert.match(crossServiceMapperImportResult.stderr, /reachai-control-service -> reachai-runtime-service/)
