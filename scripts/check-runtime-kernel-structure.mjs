import fs from 'node:fs'
import path from 'node:path'

const defaultKernelRoot = path.resolve(
  'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/kernel')
const kernelRoot = path.resolve(process.env.REACHAI_RUNTIME_KERNEL_ROOT || defaultKernelRoot)
const engineFile = path.join(kernelRoot, 'RuntimeGraphSpecExecutionEngine.java')
const maxEngineLines = 1500
const maxHandlerLines = 800

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

const failures = []

function read(file) {
  if (!fs.existsSync(file)) {
    failures.push(`missing Runtime kernel file: ${path.relative(process.cwd(), file)}`)
    return ''
  }
  return fs.readFileSync(file, 'utf8').replaceAll('\r\n', '\n')
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const engine = read(engineFile)
const engineLines = engine ? engine.split('\n').length : 0
if (engineLines > maxEngineLines) {
  failures.push(`RuntimeGraphSpecExecutionEngine has ${engineLines} lines; limit is ${maxEngineLines}`)
}

const registrations = [...engine.matchAll(/\.register\(\s*"([A-Z_]+)"\s*,/g)].map(match => match[1])
const expectedTypes = handlers.map(([type]) => type)
const missingTypes = expectedTypes.filter(type => !registrations.includes(type))
const unexpectedTypes = registrations.filter(type => !expectedTypes.includes(type))
if (missingTypes.length || unexpectedTypes.length || registrations.length !== expectedTypes.length) {
  failures.push(`Runtime handler registry mismatch; missing=${missingTypes.join(',') || '-'}, `
    + `unexpected=${unexpectedTypes.join(',') || '-'}, count=${registrations.length}`)
}

const loadedHandlers = new Map()
for (const [type, field, method, fileName] of handlers) {
  const registration = new RegExp(
    `\\.register\\(\\s*"${escapeRegExp(type)}"\\s*,\\s*${escapeRegExp(field)}::${escapeRegExp(method)}\\s*\\)`)
  if (!registration.test(engine)) {
    failures.push(`${type} must delegate directly to ${field}::${method}`)
  }
  const file = path.join(kernelRoot, fileName)
  const source = loadedHandlers.has(file) ? loadedHandlers.get(file) : read(file)
  loadedHandlers.set(file, source)
  if (source && !new RegExp(`\\b${escapeRegExp(method)}\\s*\\(`).test(source)) {
    failures.push(`${fileName} is missing ${method}`)
  }
  const engineLeafImplementation = new RegExp(
    `private\\s+RuntimeGraphSpecExecutionResult\\s+${escapeRegExp(method)}\\s*\\(`)
  if (engineLeafImplementation.test(engine)) {
    failures.push(`leaf node implementation leaked back into RuntimeGraphSpecExecutionEngine: ${method}`)
  }
}

for (const [file, source] of loadedHandlers) {
  if (!source) continue
  const lines = source.split('\n').length
  if (lines > maxHandlerLines) {
    failures.push(`${path.basename(file)} has ${lines} lines; handler-family limit is ${maxHandlerLines}`)
  }
}

if (failures.length) {
  console.error('runtime kernel structure check failed:')
  failures.forEach(failure => console.error(`- ${failure}`))
  process.exit(1)
}

console.log(`runtime kernel structure check passed `
  + `(engine=${engineLines}/${maxEngineLines} lines, handlers=${handlers.length})`)
