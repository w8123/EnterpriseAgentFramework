import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-internal-module-boundaries.mjs')

function write(root, relative, text) {
  const target = path.join(root, relative)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

function policy(root, overrides = {}) {
  const value = {
    schema: 'reachai.internal-module-boundaries.v1',
    services: [{
      name: 'control',
      root: 'reachai-control-service/src/main/java',
      packagePrefix: 'com.enterprise.ai.control',
      modules: ['(root)', 'a2a', 'aicoding', 'context', 'managed', 'pageworkbench'],
      allowedReciprocalPairs: [],
      forbiddenEdges: ['managed->aicoding'],
      allowedCrossModulePersistenceImports: [],
      ...overrides
    }]
  }
  write(root, 'module-policy.json', `${JSON.stringify(value, null, 2)}\n`)
  return path.join(root, 'module-policy.json')
}

function java(root, module, name, imports = '') {
  const packageName = module.replaceAll('/', '.')
  write(root,
    `reachai-control-service/src/main/java/com/enterprise/ai/control/${module}/${name}.java`,
    `package com.enterprise.ai.control.${packageName};\n${imports}\nclass ${name} {}\n`)
}

function run(root, policyPath) {
  return spawnSync(process.execPath, [scriptPath], {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...process.env,
      REACHAI_INTERNAL_MODULE_BOUNDARY_POLICY: policyPath
    }
  })
}

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-ok-'))
java(allowedRoot, 'managed', 'ManagedPort')
java(allowedRoot, 'aicoding', 'AiCodingProjector',
  'import com.enterprise.ai.control.managed.ManagedPort;')
let result = run(allowedRoot, policy(allowedRoot))
assert.strictEqual(result.status, 0, result.stderr || result.stdout)

const reverseRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-reverse-'))
java(reverseRoot, 'aicoding', 'AiCodingService')
java(reverseRoot, 'managed', 'BadManagedService',
  'import com.enterprise.ai.control.aicoding.AiCodingService;')
result = run(reverseRoot, policy(reverseRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /forbidden module dependency managed->aicoding/)

const cycleRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-cycle-'))
java(cycleRoot, 'a2a', 'A2aService',
  'import com.enterprise.ai.control.context.ContextService;')
java(cycleRoot, 'context', 'ContextService',
  'import com.enterprise.ai.control.a2a.A2aService;')
result = run(cycleRoot, policy(cycleRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /new reciprocal module dependency/)

const persistenceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-db-'))
java(persistenceRoot, 'aicoding/persistence', 'TaskEntity')
java(persistenceRoot, 'pageworkbench', 'BadWorkbenchService',
  'import com.enterprise.ai.control.aicoding.persistence.TaskEntity;')
result = run(persistenceRoot, policy(persistenceRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /cross-module persistence import is forbidden/)

const unknownRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-unknown-'))
java(unknownRoot, 'surprise', 'SurpriseService')
result = run(unknownRoot, policy(unknownRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /undeclared control module surprise/)

const staleRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-stale-'))
java(staleRoot, 'a2a', 'A2aService',
  'import com.enterprise.ai.control.context.ContextService;')
java(staleRoot, 'context', 'ContextService')
result = run(staleRoot, policy(staleRoot, {
  allowedReciprocalPairs: ['a2a|context']
}))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /stale reciprocal allowance/)

const longCycleRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-long-cycle-'))
java(longCycleRoot, 'a2a', 'A2aService', 'import com.enterprise.ai.control.context.ContextService;')
java(longCycleRoot, 'context', 'ContextService', 'import com.enterprise.ai.control.pageworkbench.Workbench;')
java(longCycleRoot, 'pageworkbench', 'Workbench', 'import com.enterprise.ai.control.a2a.A2aService;')
result = run(longCycleRoot, policy(longCycleRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /new cyclic module dependency/)
const cycleEdges = ['a2a->context', 'context->pageworkbench', 'pageworkbench->a2a']
result = run(longCycleRoot, policy(longCycleRoot, { allowedCycleEdges: cycleEdges }))
assert.strictEqual(result.status, 0, result.stderr)
// A new edge inside an already accepted SCC must still fail.
java(longCycleRoot, 'pageworkbench', 'Workbench',
  'import com.enterprise.ai.control.a2a.A2aService;\nimport com.enterprise.ai.control.context.ContextService;')
result = run(longCycleRoot, policy(longCycleRoot, { allowedCycleEdges: cycleEdges }))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /new cyclic module dependency.*pageworkbench->context/)
java(longCycleRoot, 'pageworkbench', 'Workbench')
result = run(longCycleRoot, policy(longCycleRoot, { allowedCycleEdges: cycleEdges }))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /stale cycle allowance/)

const flatRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-module-boundary-flat-'))
write(flatRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/TaskEntity.java',
  'package com.enterprise.ai.control.aicoding;\n@TableName("task")\nclass TaskEntity {}\n')
write(flatRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/TaskMapper.java',
  'package com.enterprise.ai.control.aicoding;\ninterface TaskMapper extends BaseMapper<TaskEntity> {}\n')
java(flatRoot, 'pageworkbench', 'Workbench', 'import com.enterprise.ai.control.aicoding.TaskMapper;')
result = run(flatRoot, policy(flatRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /cross-module persistence import.*TaskMapper/)
java(flatRoot, 'pageworkbench', 'Workbench', 'import com.enterprise.ai.control.aicoding.TaskEntity;')
result = run(flatRoot, policy(flatRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /cross-module persistence import.*TaskEntity/)
java(flatRoot, 'pageworkbench', 'Workbench', 'import com.enterprise.ai.control.aicoding.*;')
result = run(flatRoot, policy(flatRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /cross-module persistence import.*TaskMapper/)
java(flatRoot, 'pageworkbench', 'Workbench', 'com.enterprise.ai.control.aicoding.TaskEntity explicit;')
result = run(flatRoot, policy(flatRoot))
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /cross-module persistence import.*TaskEntity/)
java(flatRoot, 'pageworkbench', 'Workbench',
  '// com.enterprise.ai.control.aicoding.TaskEntity ignored;\nString example = "com.enterprise.ai.control.aicoding.TaskMapper";')
result = run(flatRoot, policy(flatRoot))
assert.strictEqual(result.status, 0, result.stderr)
console.log('internal module boundary regression tests passed (long cycles, cyclic edge growth, flat persistence, wildcard/FQN and comment exclusion)')
