import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const check = path.resolve('scripts/check-runtime-kernel-structure.mjs')
const handlers = [
  ['USER_INPUT', 'deterministicNodeHandlers', 'executeUserInput', 'RuntimeDeterministicNodeHandlers.java'],
  ['IF_ELSE', 'deterministicNodeHandlers', 'executeCondition', 'RuntimeDeterministicNodeHandlers.java'],
  ['ANSWER', 'deterministicNodeHandlers', 'executeAnswer', 'RuntimeDeterministicNodeHandlers.java'],
  ['VARIABLE_ASSIGN', 'deterministicNodeHandlers', 'executeVariableAssign', 'RuntimeDeterministicNodeHandlers.java'],
  ['TEMPLATE', 'deterministicNodeHandlers', 'executeTemplate', 'RuntimeDeterministicNodeHandlers.java'],
  ['VARIABLE_AGGREGATOR', 'deterministicNodeHandlers', 'executeVariableAggregator', 'RuntimeDeterministicNodeHandlers.java'],
  ['INTENT_CLASSIFIER', 'modelNodeHandlers', 'executeIntentClassifier', 'RuntimeModelNodeHandlers.java'],
  ['PARAMETER_EXTRACT', 'modelNodeHandlers', 'executeParameterExtract', 'RuntimeModelNodeHandlers.java'],
  ['LLM', 'modelNodeHandlers', 'executeLlm', 'RuntimeModelNodeHandlers.java'],
  ['KNOWLEDGE_RETRIEVAL', 'ioNodeHandlers', 'executeKnowledgeRetrieval', 'RuntimeIoNodeHandlers.java'],
  ['HTTP_REQUEST', 'ioNodeHandlers', 'executeHttpRequest', 'RuntimeIoNodeHandlers.java'],
  ['TOOL', 'actionNodeHandlers', 'executeTool', 'RuntimeActionNodeHandlers.java'],
  ['PAGE_ACTION', 'actionNodeHandlers', 'executePageAction', 'RuntimeActionNodeHandlers.java'],
  ['INTERACTION', 'actionNodeHandlers', 'executeInteraction', 'RuntimeActionNodeHandlers.java'],
  ['LOOP', 'loopNodeHandler', 'executeLoop', 'RuntimeLoopNodeHandler.java']
]

function fixture(extraEngine = '', registrationOverride = null) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-kernel-structure-'))
  const registrations = handlers.map(([type, field, method]) =>
    registrationOverride?.type === type
      ? registrationOverride.text
      : `.register("${type}", ${field}::${method})`).join('\n')
  fs.writeFileSync(path.join(root, 'RuntimeGraphSpecExecutionEngine.java'),
    `class RuntimeGraphSpecExecutionEngine {\n${registrations}\n${extraEngine}\n}\n`, 'utf8')
  const methodsByFile = new Map()
  for (const [, , method, file] of handlers) {
    methodsByFile.set(file, `${methodsByFile.get(file) || ''} void ${method}() {}\n`)
  }
  for (const [file, methods] of methodsByFile) {
    fs.writeFileSync(path.join(root, file), `class Handler {\n${methods}}\n`, 'utf8')
  }
  return root
}

function run(root) {
  return spawnSync(process.execPath, [check], {
    encoding: 'utf8',
    env: {...process.env, REACHAI_RUNTIME_KERNEL_ROOT: root}
  })
}

let root = fixture()
let result = run(root)
assert.strictEqual(result.status, 0, result.stderr || result.stdout)

root = fixture('', {type: 'LLM', text: '.register("LLM", (node, execution) -> executeLlm(node))'})
result = run(root)
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /LLM must delegate directly/)

root = fixture('private RuntimeGraphSpecExecutionResult executeTool() { return null; }')
result = run(root)
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /leaf node implementation leaked back/)

root = fixture(`${'// filler\n'.repeat(1501)}`)
result = run(root)
assert.notStrictEqual(result.status, 0)
assert.match(result.stderr, /limit is 1500/)

console.log('runtime kernel structure checker tests passed')
