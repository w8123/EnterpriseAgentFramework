import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { baseParse } from '@vue/compiler-dom'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'

import {
  syntheticAgentEditConsumer,
  syntheticAppDialog,
  syntheticAppDrawer,
  syntheticCommonStatusTag,
  syntheticComponent,
  syntheticConsumer,
  syntheticDataTableShell,
  syntheticElementPlusMappings,
  syntheticFilterBar,
  syntheticGlassWorkbench,
  syntheticKnowledgeDetailConsumer,
  syntheticMcpOnboardingConsumer,
  syntheticMetricIconBg,
  syntheticMetricStrip,
  syntheticPageHeader,
  syntheticStatusTag,
  syntheticToolListConsumer,
  syntheticWizardDialog,
  syntheticWorkbenchPanel
} from './fixtures.mjs'
import {
  allTemplateElements,
  arraysEqual,
  canonicalTypeScriptNode,
  failures,
  findVariableDeclaration,
  formatCompilerError,
  functionDeclaration,
  importDeclaration,
  isNamedCall,
  objectPropertyInitializer,
  readNormalizedUtf8,
  root,
  typescriptNodes,
  unwrapExpression,
  validateAgentEditConsumer,
  validateAgentSupervisorWorkbenchConsumer,
  validateAppDialog,
  validateAppDrawer,
  validateCommonStatusTag,
  validateDataTableShell,
  validateElementPlusMappings,
  validateFilterBar,
  validateGlassWorkbench,
  validateKnowledgeDetailConsumer,
  validateMcpOnboardingConsumer,
  validateMetricIconBg,
  validateMetricStrip,
  validatePageHeader,
  validateRepresentativeConsumer,
  validateStatusTag,
  validateSyntheticComponent,
  validateToolListConsumer,
  validateWizardDialog,
  validateWorkbenchPanel
} from './validators.mjs'

let mutationProofCount = 0
let safeVariantProofCount = 0
let knowledgeHarnessProofCount = 0
let agentHarnessProofCount = 0
let mcpHarnessProofCount = 0

async function runMutationProof(name, mutate, expectedFailure) {
  const mutationFailures = await validateSyntheticComponent(
    mutate(syntheticComponent),
    `mutation:${name}`,
  )
  if (
    mutationFailures.length !== 1 ||
    mutationFailures[0] !== `mutation:${name} ${expectedFailure}`
  ) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

async function runContractMutationProof(name, source, validate, mutate, expectedFailure) {
  const label = `mutation:${name}`
  const mutationFailures = await validate(mutate(source), label)
  const expected = (Array.isArray(expectedFailure) ? expectedFailure : [expectedFailure])
    .map(message => `${label} ${message}`).sort()
  if (!arraysEqual([...mutationFailures].sort(), expected)) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

async function runPassingContractVariantProof(name, source, validate, mutate) {
  const label = `safe-variant:${name}`
  const variantFailures = await validate(mutate(source), label)
  if (variantFailures.length > 0) {
    failures.push(
      `safe variant proof ${name} must pass; got ${variantFailures.join(' | ')}`,
    )
    return
  }
  safeVariantProofCount += 1
  console.log(`Safe variant proof ${name}: pass`)
}

async function runToolConsumerMutationProof(name, mutate, expectedFailure) {
  const label = `mutation:${name}`
  const mutationFailures = await validateToolListConsumer(mutate(syntheticToolListConsumer), label)
  const expected = `${label} ${expectedFailure}`
  if (mutationFailures.length !== 1 || mutationFailures[0] !== expected) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

function withToolEmptyContract(source) {
  let result = source
  if (!result.includes('empty-description="暂无数据，请调整条件或先在后端注册 Tool"')) {
    result = result.replace(
      '      :empty="tools.length === 0"\n',
      '      :empty="tools.length === 0"\n      empty-description="暂无数据，请调整条件或先在后端注册 Tool"\n',
    )
  }
  if (!/<el-table\b[^>]*:data="tools"[^>]*empty-text=" "/s.test(result)) {
    result = result.replace(
      '<el-table :data="tools" stripe',
      '<el-table :data="tools" empty-text=" " stripe',
    )
  }
  return result
}

async function runToolHeadMutationProof(name, mutate, expectedFailure) {
  // The editable /tool product page is retired. Keep historical fixture helpers isolated
  // from current filesystem contracts until this legacy mutation block is removed wholesale.
  const retiredConsumerPath = resolve(root, 'src/views/tool/ToolList.vue')
  if (!existsSync(retiredConsumerPath)) return
  const label = `mutation:${name}`
  const actualSource = readNormalizedUtf8(retiredConsumerPath)
  const mutationFailures = await validateToolListConsumer(
    mutate(withToolEmptyContract(actualSource)),
    label,
    { compareHead: true },
  )
  const expectedMessages = Array.isArray(expectedFailure) ? expectedFailure : [expectedFailure]
  const expectedFailures = expectedMessages.map((message) => `${label} ${message}`)
  if (!arraysEqual(mutationFailures, expectedFailures)) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedMessages.join(' | ')}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedMessages.join(',')}`)
}

async function runToolHeadSafeVariantProof(name, mutate) {
  // The editable /tool product page is retired; there is no current consumer to compare to HEAD.
  const retiredConsumerPath = resolve(root, 'src/views/tool/ToolList.vue')
  if (!existsSync(retiredConsumerPath)) return
  const label = `safe-variant:${name}`
  const actualSource = readNormalizedUtf8(retiredConsumerPath)
  const variantFailures = await validateToolListConsumer(
    mutate(withToolEmptyContract(actualSource)),
    label,
    { compareHead: true },
  )
  if (variantFailures.length > 0) {
    failures.push(
      `safe variant proof ${name} must pass; got ${variantFailures.join(' | ')}`,
    )
    return
  }
  safeVariantProofCount += 1
  console.log(`Safe variant proof ${name}: pass`)
}

function knowledgeScriptMutationContext(source, label) {
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0 || !parsed.descriptor.scriptSetup) {
    throw new Error(`${label} requires a parseable script setup block`)
  }
  const content = parsed.descriptor.scriptSetup.content
  const contentStart = source.indexOf(content, parsed.descriptor.scriptSetup.loc.start.offset)
  if (contentStart < 0) throw new Error(`${label} could not locate script setup content`)
  const sourceFile = ts.createSourceFile(
    `${label}.ts`,
    content,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  if (sourceFile.parseDiagnostics.length > 0) {
    throw new Error(`${label} script setup TypeScript must parse before mutation`)
  }
  return { content, contentStart, sourceFile }
}

function mutateKnowledgeFunctionParameter(source, functionName, parameterSource) {
  const context = knowledgeScriptMutationContext(source, `mutation:${functionName}-parameter`)
  const declaration = functionDeclaration(context.sourceFile, functionName)
  if (!declaration?.body) throw new Error(`function ${functionName} is unavailable for mutation`)
  const signatureStart = declaration.getStart(context.sourceFile)
  const signatureEnd = declaration.body.getStart(context.sourceFile)
  const signature = context.content.slice(signatureStart, signatureEnd)
  const closeParen = signature.lastIndexOf(')')
  if (closeParen < 0) throw new Error(`function ${functionName} signature has no closing parenthesis`)
  const mutatedSignature = `${signature.slice(0, closeParen)}, ${parameterSource}${signature.slice(closeParen)}`
  return `${source.slice(0, context.contentStart + signatureStart)}${mutatedSignature}${source.slice(context.contentStart + signatureEnd)}`
}

function mutateKnowledgeFunctionAsyncModifier(source, functionName) {
  const context = knowledgeScriptMutationContext(source, `mutation:${functionName}-async-modifier`)
  const declaration = functionDeclaration(context.sourceFile, functionName)
  if (!declaration?.body) throw new Error(`function ${functionName} is unavailable for async mutation`)
  const signatureStart = declaration.getStart(context.sourceFile)
  const signatureEnd = declaration.body.getStart(context.sourceFile)
  const signature = context.content.slice(signatureStart, signatureEnd)
  const hasAsync = declaration.modifiers?.some(
    (modifier) => modifier.kind === ts.SyntaxKind.AsyncKeyword,
  )
  const mutatedSignature = hasAsync
    ? replaceRequired(signature, 'async ', '', `${functionName} async removal`)
    : replaceRequired(
        signature,
        `function ${functionName}`,
        `async function ${functionName}`,
        `${functionName} async addition`,
      )
  return `${source.slice(0, context.contentStart + signatureStart)}${mutatedSignature}${source.slice(context.contentStart + signatureEnd)}`
}

function mutateKnowledgeFunctionGenerator(source, functionName) {
  const context = knowledgeScriptMutationContext(source, `mutation:${functionName}-generator`)
  const declaration = functionDeclaration(context.sourceFile, functionName)
  if (!declaration?.body) throw new Error(`function ${functionName} is unavailable for generator mutation`)
  const signatureStart = declaration.getStart(context.sourceFile)
  const signatureEnd = declaration.body.getStart(context.sourceFile)
  const signature = context.content.slice(signatureStart, signatureEnd)
  const mutatedSignature = replaceRequired(
    signature,
    `function ${functionName}`,
    `function* ${functionName}`,
    `${functionName} generator mutation`,
  )
  return `${source.slice(0, context.contentStart + signatureStart)}${mutatedSignature}${source.slice(context.contentStart + signatureEnd)}`
}

function appendKnowledgeTopLevelStatement(source, statementSource) {
  const context = knowledgeScriptMutationContext(source, 'mutation:knowledge-top-level')
  const contentEnd = context.contentStart + context.content.length
  return `${source.slice(0, contentEnd)}\n${statementSource}\n${source.slice(contentEnd)}`
}

function knowledgeTopLevelVariableStatement(sourceFile, bindingName) {
  const matches = sourceFile.statements.filter(
    (statement) =>
      ts.isVariableStatement(statement) &&
      statement.declarationList.declarations.some(
        (declaration) => ts.isIdentifier(declaration.name) && declaration.name.text === bindingName,
      ),
  )
  return matches.length === 1 ? matches[0] : undefined
}

function mutateKnowledgeSwapVariableStatements(source, firstBinding, secondBinding) {
  const context = knowledgeScriptMutationContext(
    source,
    `mutation:${firstBinding}-${secondBinding}-variable-order`,
  )
  const first = knowledgeTopLevelVariableStatement(context.sourceFile, firstBinding)
  const second = knowledgeTopLevelVariableStatement(context.sourceFile, secondBinding)
  if (!first || !second) {
    throw new Error(`${firstBinding}/${secondBinding} variable statements are unavailable for mutation`)
  }
  const ordered = [first, second].sort(
    (left, right) => left.getStart(context.sourceFile) - right.getStart(context.sourceFile),
  )
  const leftStart = ordered[0].getStart(context.sourceFile)
  const leftEnd = ordered[0].getEnd()
  const rightStart = ordered[1].getStart(context.sourceFile)
  const rightEnd = ordered[1].getEnd()
  const leftSource = context.content.slice(leftStart, leftEnd)
  const rightSource = context.content.slice(rightStart, rightEnd)
  const absoluteLeftStart = context.contentStart + leftStart
  const absoluteLeftEnd = context.contentStart + leftEnd
  const absoluteRightStart = context.contentStart + rightStart
  const absoluteRightEnd = context.contentStart + rightEnd
  return `${source.slice(0, absoluteLeftStart)}${rightSource}${source.slice(absoluteLeftEnd, absoluteRightStart)}${leftSource}${source.slice(absoluteRightEnd)}`
}

function mutateKnowledgeVariableInitializer(source, bindingName, replacementSource) {
  return mutateKnowledgeScriptNode(
    source,
    `${bindingName}-variable-initializer`,
    (sourceFile) => {
      const statement = knowledgeTopLevelVariableStatement(sourceFile, bindingName)
      const matches = statement?.declarationList.declarations.filter(
        (declaration) => ts.isIdentifier(declaration.name) && declaration.name.text === bindingName,
      )
      return matches?.length === 1 ? matches[0].initializer : undefined
    },
    replacementSource,
  )
}

function knowledgeTemplateMutationContext(source, label) {
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0 || !parsed.descriptor.template) {
    throw new Error(`${label} requires a parseable template block`)
  }
  const content = parsed.descriptor.template.content
  const contentStart = source.indexOf(content, parsed.descriptor.template.loc.start.offset)
  if (contentStart < 0) throw new Error(`${label} could not locate template content`)
  const ast = baseParse(content)
  return { content, contentStart, ast }
}

function openingTagInsertionOffset(content, element) {
  let quote
  for (let index = element.loc.start.offset; index < element.loc.end.offset; index += 1) {
    const character = content[index]
    if (quote) {
      if (character === quote) quote = undefined
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
      continue
    }
    if (character === '>') {
      return content[index - 1] === '/' ? index - 1 : index
    }
  }
  throw new Error(`template element ${element.tag} has no opening-tag boundary`)
}

function mutateKnowledgeWrapperAttribute(source, tag, attributeSource, index = 0) {
  const context = knowledgeTemplateMutationContext(source, `mutation:${tag}-attribute`)
  const matches = allTemplateElements(context.ast).filter((element) => element.tag === tag)
  const element = matches[index]
  if (!element) throw new Error(`${tag}[${index}] is unavailable for attribute mutation`)
  const insertion = openingTagInsertionOffset(context.content, element)
  const absoluteInsertion = context.contentStart + insertion
  return `${source.slice(0, absoluteInsertion)} ${attributeSource}${source.slice(absoluteInsertion)}`
}

function replaceRequired(source, search, replacement, label) {
  const mutated = source.replace(search, replacement)
  if (mutated === source) throw new Error(`${label} could not locate its required source span`)
  return mutated
}

function insertKnowledgeTemplateBefore(source, tag, insertedSource, index = 0) {
  const context = knowledgeTemplateMutationContext(source, `mutation:${tag}-insert-before`)
  const matches = allTemplateElements(context.ast).filter((element) => element.tag === tag)
  const element = matches[index]
  if (!element) throw new Error(`${tag}[${index}] is unavailable for insertion mutation`)
  const absolute = context.contentStart + element.loc.start.offset
  return `${source.slice(0, absolute)}${insertedSource}${source.slice(absolute)}`
}

function mutateKnowledgeScriptNode(source, label, selectNode, replacementSource) {
  const context = knowledgeScriptMutationContext(source, `mutation:${label}`)
  const node = selectNode(context.sourceFile)
  if (!node) throw new Error(`${label} could not locate its TypeScript AST node`)
  const start = context.contentStart + node.getStart(context.sourceFile)
  const end = context.contentStart + node.getEnd()
  return `${source.slice(0, start)}${replacementSource}${source.slice(end)}`
}

function mutateKnowledgeWatchGuard(source) {
  return mutateKnowledgeScriptNode(
    source,
    'active-watch-guard',
    (sourceFile) => {
      const matches = typescriptNodes(
        sourceFile,
        (node) =>
          ts.isBinaryExpression(node) &&
          node.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken &&
          canonicalTypeScriptNode(node.left) === "tab === 'chunks'" &&
          canonicalTypeScriptNode(node.right) === 'chunks.value.length === 0',
      )
      return matches.length === 1 ? matches[0] : undefined
    },
    "tab === 'chunks'",
  )
}

function mutateKnowledgeTopLevelCallArgument(source, callName, replacementSource) {
  return mutateKnowledgeScriptNode(
    source,
    `${callName}-argument`,
    (sourceFile) => {
      const calls = sourceFile.statements
        .filter(ts.isExpressionStatement)
        .map((statement) => unwrapExpression(statement.expression))
        .filter((expression) => isNamedCall(expression, callName))
      const call = calls.length === 1 ? calls[0] : undefined
      return call?.arguments.length === 1 ? call.arguments[0] : undefined
    },
    replacementSource,
  )
}

function mutateKnowledgeApiImportName(source, importedName, replacementName) {
  return mutateKnowledgeScriptNode(
    source,
    `${importedName}-api-import`,
    (sourceFile) => {
      const declaration = importDeclaration(sourceFile, '@/api/knowledge')
      const bindings = declaration?.importClause?.namedBindings
      if (!bindings || !ts.isNamedImports(bindings)) return undefined
      const matches = bindings.elements.filter(
        (element) => (element.propertyName?.text ?? element.name.text) === importedName,
      )
      return matches.length === 1 ? matches[0].name : undefined
    },
    replacementName,
  )
}

function mutateKnowledgeFunctionStringLiteral(source, functionName, literal, replacementLiteral) {
  return mutateKnowledgeScriptNode(
    source,
    `${functionName}-string-literal`,
    (sourceFile) => {
      const declaration = functionDeclaration(sourceFile, functionName)
      const matches = declaration
        ? typescriptNodes(declaration, (node) => ts.isStringLiteral(node) && node.text === literal)
        : []
      return matches.length === 1 ? matches[0] : undefined
    },
    JSON.stringify(replacementLiteral),
  )
}

function mutateKnowledgeObjectInitializer(source, variableName, propertyName, replacementSource) {
  return mutateKnowledgeScriptNode(
    source,
    `${variableName}-${propertyName}-initializer`,
    (sourceFile) => {
      const declaration = findVariableDeclaration(sourceFile, variableName)
      const object = unwrapExpression(declaration?.initializer)
      return object && ts.isObjectLiteralExpression(object)
        ? objectPropertyInitializer(object, propertyName)
        : undefined
    },
    replacementSource,
  )
}

function mutateKnowledgeTemplateEventExpression(source, expressionSource, replacementSource) {
  const context = knowledgeTemplateMutationContext(source, `mutation:${expressionSource}-event`)
  const directives = allTemplateElements(context.ast)
    .flatMap((element) => element.props)
    .filter(
      (property) =>
        property.type === 7 &&
        property.name === 'on' &&
        property.exp?.content === expressionSource,
    )
  const eventDirective = directives.length === 1 ? directives[0] : undefined
  if (!eventDirective?.exp) {
    throw new Error(`${expressionSource} event expression is unavailable for mutation`)
  }
  const start = context.contentStart + eventDirective.exp.loc.start.offset
  const end = context.contentStart + eventDirective.exp.loc.end.offset
  return `${source.slice(0, start)}${replacementSource}${source.slice(end)}`
}

function requiredKnowledgeMutation(name, baseline, mutate) {
  const mutated = mutate(baseline)
  if (typeof mutated !== 'string' || mutated === baseline) {
    throw new Error(`${name} must change its baseline before validation`)
  }
  return mutated
}

async function runKnowledgeDetailMutationProof(
  name,
  baseline,
  validationOptions,
  mutate,
  expectedFailure,
) {
  const label = `mutation:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`mutation proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const mutationFailures = await validateKnowledgeDetailConsumer(
    mutated,
    label,
    validationOptions,
  )
  const expected = `${label} ${expectedFailure}`
  if (mutationFailures.length !== 1 || mutationFailures[0] !== expected) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

async function runKnowledgeDetailSafeVariantProof(name, baseline, validationOptions, mutate) {
  const label = `safe-variant:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`safe variant proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const variantFailures = await validateKnowledgeDetailConsumer(
    mutated,
    label,
    validationOptions,
  )
  if (variantFailures.length > 0) {
    failures.push(
      `safe variant proof ${name} must pass; got ${variantFailures.join(' | ')}`,
    )
    return
  }
  safeVariantProofCount += 1
  console.log(`Safe variant proof ${name}: pass`)
}

function runKnowledgeMutationHarnessProof() {
  try {
    requiredKnowledgeMutation(
      'knowledge-detail-no-op-self-test',
      syntheticKnowledgeDetailConsumer,
      (source) => source,
    )
    failures.push('KnowledgeDetail mutation harness must reject a no-op transform')
  } catch (error) {
    if (!formatCompilerError(error).includes('must change its baseline before validation')) {
      failures.push(`KnowledgeDetail mutation harness self-test failed unexpectedly: ${formatCompilerError(error)}`)
      return
    }
    knowledgeHarnessProofCount += 1
    console.log('Harness proof knowledge-detail-no-op-rejected: pass')
  }
}

function currentAgentEditSource() {
  return readNormalizedUtf8(resolve(root, 'src/views/agent/AgentEdit.vue'))
}

async function runAgentEditMutationProof(
  name,
  baseline,
  validationOptions,
  mutate,
  expectedFailure,
) {
  const label = `mutation:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`mutation proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const mutationFailures = await validateAgentEditConsumer(mutated, label, validationOptions)
  const expected = `${label} ${expectedFailure}`
  if (mutationFailures.length !== 1 || mutationFailures[0] !== expected) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

function runAgentMutationHarnessProof() {
  try {
    requiredKnowledgeMutation(
      'agent-edit-no-op-self-test',
      syntheticAgentEditConsumer,
      (source) => source,
    )
    failures.push('AgentEdit mutation harness must reject a no-op transform')
  } catch (error) {
    if (!formatCompilerError(error).includes('must change its baseline before validation')) {
      failures.push(
        `AgentEdit mutation harness self-test failed unexpectedly: ${formatCompilerError(error)}`,
      )
      return
    }
    agentHarnessProofCount += 1
    console.log('Harness proof agent-edit-no-op-rejected: pass')
  }
}

function currentMcpOnboardingSource() {
  const retiredConsumerPath = resolve(root, 'src/views/mcp/McpOnboarding.vue')
  return existsSync(retiredConsumerPath) ? readNormalizedUtf8(retiredConsumerPath) : null
}

async function runMcpOnboardingMutationProof(
  name,
  baseline,
  validationOptions,
  mutate,
  expectedFailure,
) {
  if (baseline == null) return
  const label = `mutation:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`mutation proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const mutationFailures = await validateMcpOnboardingConsumer(
    mutated,
    label,
    validationOptions,
  )
  const expected = `${label} ${expectedFailure}`
  if (mutationFailures.length !== 1 || mutationFailures[0] !== expected) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailure}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailure}`)
}

async function runMcpOnboardingSafeVariantProof(name, baseline, validationOptions, mutate) {
  if (baseline == null) return
  const label = `safe-variant:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`safe variant proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const variantFailures = await validateMcpOnboardingConsumer(
    mutated,
    label,
    validationOptions,
  )
  if (variantFailures.length > 0) {
    failures.push(
      `safe variant proof ${name} must pass; got ${variantFailures.join(' | ')}`,
    )
    return
  }
  safeVariantProofCount += 1
  console.log(`Safe variant proof ${name}: pass`)
}

function runMcpMutationHarnessProof() {
  try {
    requiredKnowledgeMutation(
      'mcp-onboarding-no-op-self-test',
      syntheticMcpOnboardingConsumer,
      (source) => source,
    )
    failures.push('MCP onboarding mutation harness must reject a no-op transform')
  } catch (error) {
    if (!formatCompilerError(error).includes('must change its baseline before validation')) {
      failures.push(
        `MCP onboarding mutation harness self-test failed unexpectedly: ${formatCompilerError(error)}`,
      )
      return
    }
    mcpHarnessProofCount += 1
    console.log('Harness proof mcp-onboarding-no-op-rejected: pass')
  }
}

function moveMcpWarningToPanelFour(source) {
  const warning = source.match(
    /\s*<el-alert type="warning" :closable="false" show-icon>[\s\S]*?<\/el-alert>/,
  )?.[0]
  if (!warning) throw new Error('MCP Claude warning is unavailable for ancestry mutation')
  const withoutWarning = source.replace(warning, '')
  const marker = '<WorkbenchPanel title="④ Dify / OpenClaw / 通用 HTTP MCP" density="spacious">'
  return replaceRequired(
    withoutWarning,
    marker,
    `${marker}${warning}`,
    'MCP Claude warning panel ownership mutation',
  )
}

function moveMcpCopyBeforeRuntimeDeclarations(source) {
  const context = knowledgeScriptMutationContext(source, 'mutation:mcp-copy-runtime-order')
  const copyFunction = functionDeclaration(context.sourceFile, 'copy')
  const firstVariable = context.sourceFile.statements.find(ts.isVariableStatement)
  if (!copyFunction || !firstVariable) {
    throw new Error('MCP copy function or runtime variable is unavailable for order mutation')
  }
  const functionStart = copyFunction.getStart(context.sourceFile)
  const functionEnd = copyFunction.getEnd()
  const variableStart = firstVariable.getStart(context.sourceFile)
  if (functionStart <= variableStart) {
    throw new Error('MCP copy function must initially follow runtime variables')
  }
  const copySource = context.content.slice(functionStart, functionEnd)
  const prefix = source.slice(0, context.contentStart + variableStart)
  const between = source.slice(
    context.contentStart + variableStart,
    context.contentStart + functionStart,
  )
  const suffix = source.slice(context.contentStart + functionEnd)
  return `${prefix}${copySource}\n${between}${suffix}`
}

function moveMcpPresentationImportAfterFirstVariable(source) {
  const context = knowledgeScriptMutationContext(source, 'mutation:mcp-presentation-import-order')
  const pageHeaderImport = importDeclaration(
    context.sourceFile,
    '@/components/common/PageHeader.vue',
  )
  const firstVariable = context.sourceFile.statements.find(ts.isVariableStatement)
  if (!pageHeaderImport || !firstVariable) {
    throw new Error('MCP PageHeader import or first runtime variable is unavailable for order mutation')
  }
  const importStart = pageHeaderImport.getStart(context.sourceFile)
  const importEnd = pageHeaderImport.getEnd()
  const variableEnd = firstVariable.getEnd()
  if (importStart >= variableEnd) {
    throw new Error('MCP PageHeader import must initially precede runtime variables')
  }
  const importSource = context.content.slice(importStart, importEnd)
  const prefix = source.slice(0, context.contentStart + importStart)
  const throughVariable = source.slice(
    context.contentStart + importEnd,
    context.contentStart + variableEnd,
  )
  const suffix = source.slice(context.contentStart + variableEnd)
  return `${prefix}${throughVariable}\n${importSource}${suffix}`
}

async function runMutationProofs() {
  let baselineFailed = false
  const baselineFailures = await validateSyntheticComponent(
    syntheticComponent,
    'synthetic component baseline',
  )
  if (baselineFailures.length > 0) {
    failures.push(`synthetic component baseline must pass: ${baselineFailures.join(' | ')}`)
    baselineFailed = true
  }

  const consumerBaselineFailures = validateRepresentativeConsumer(
    syntheticConsumer,
    'synthetic consumer baseline',
  )
  if (consumerBaselineFailures.length > 0) {
    failures.push(`synthetic consumer baseline must pass: ${consumerBaselineFailures.join(' | ')}`)
    baselineFailed = true
  }

  const toolConsumerBaselineFailures = await validateToolListConsumer(
    syntheticToolListConsumer,
    'synthetic ToolList consumer baseline',
  )
  if (toolConsumerBaselineFailures.length > 0) {
    failures.push(
      `synthetic ToolList consumer baseline must pass: ${toolConsumerBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }

  const knowledgeConsumerBaselineFailures = await validateKnowledgeDetailConsumer(
    syntheticKnowledgeDetailConsumer,
    'synthetic KnowledgeDetail consumer baseline',
    { skipHeadPreservation: true },
  )
  if (knowledgeConsumerBaselineFailures.length > 0) {
    failures.push(
      `synthetic KnowledgeDetail consumer baseline must pass independently: ${knowledgeConsumerBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }

  const agentConsumerBaselineFailures = await validateAgentEditConsumer(
    syntheticAgentEditConsumer,
    'synthetic AgentEdit consumer baseline',
    { skipHeadPreservation: true },
  )
  if (agentConsumerBaselineFailures.length > 0) {
    failures.push(
      `synthetic AgentEdit consumer baseline must pass independently: ${agentConsumerBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }

  const mcpOnboardingBaselineFailures = await validateMcpOnboardingConsumer(
    syntheticMcpOnboardingConsumer,
    'synthetic MCP onboarding consumer baseline',
    { skipHeadPreservation: true },
  )
  if (mcpOnboardingBaselineFailures.length > 0) {
    failures.push(
      `synthetic MCP onboarding consumer baseline must pass independently: ${mcpOnboardingBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }


  const actualAgentEditSource = currentAgentEditSource()
  const actualAgentConsumerBaselineFailures = await validateAgentSupervisorWorkbenchConsumer(
    actualAgentEditSource,
    'actual AgentEdit consumer mutation baseline',
  )
  if (actualAgentConsumerBaselineFailures.length > 0) {
    failures.push(
      `actual AgentEdit consumer mutation baseline must pass against the Supervisor architecture contract: ${actualAgentConsumerBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }

  const actualMcpOnboardingSource = currentMcpOnboardingSource()
  if (actualMcpOnboardingSource != null) {
    const actualMcpOnboardingBaselineFailures = await validateMcpOnboardingConsumer(
      actualMcpOnboardingSource,
      'actual MCP onboarding consumer mutation baseline',
    )
    if (actualMcpOnboardingBaselineFailures.length > 0) {
      failures.push(
        `actual MCP onboarding consumer mutation baseline must pass with HEAD preservation: ${actualMcpOnboardingBaselineFailures.join(' | ')}`,
      )
      baselineFailed = true
    }
  }

  runKnowledgeMutationHarnessProof()
  runAgentMutationHarnessProof()
  runMcpMutationHarnessProof()

  for (const [name, source, validate] of [
    ['glass workbench', syntheticGlassWorkbench, validateGlassWorkbench],
    ['PageHeader', syntheticPageHeader, validatePageHeader],
    ['WorkbenchPanel', syntheticWorkbenchPanel, validateWorkbenchPanel],
    ['FilterBar', syntheticFilterBar, validateFilterBar],
    ['DataTableShell', syntheticDataTableShell, validateDataTableShell],
    ['AppDialog', syntheticAppDialog, validateAppDialog],
    ['WizardDialog', syntheticWizardDialog, validateWizardDialog],
    ['AppDrawer', syntheticAppDrawer, validateAppDrawer],
    ['Element Plus Task 4 mappings', syntheticElementPlusMappings, validateElementPlusMappings],
    ['MetricStrip', syntheticMetricStrip, validateMetricStrip],
    ['StatusTag', syntheticStatusTag, validateStatusTag],
    ['MetricIconBg', syntheticMetricIconBg, validateMetricIconBg],
    ['CommonStatusTag', syntheticCommonStatusTag, validateCommonStatusTag],
  ]) {
    const baselineFailures = await validate(source, `synthetic ${name} baseline`)
    if (baselineFailures.length > 0) {
      failures.push(`synthetic ${name} baseline must pass: ${baselineFailures.join(' | ')}`)
      baselineFailed = true
    }
  }

  await runPassingContractVariantProof(
    'wizard-live-region-safe-id',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        'class="wizard-dialog__live"',
        'id="wizard-dialog-validation-status" class="wizard-dialog__live"',
      ),
  )
  await runPassingContractVariantProof(
    'wizard-cancel-forwarding-helper',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source
        .replace(
          'function stepTone(state: WizardStepState): StatusTone {',
          "function forwardCancel() {\n  emit('cancel')\n}\n\nfunction stepTone(state: WizardStepState): StatusTone {",
        )
        .replace("@click=\"emit('cancel')\"", '@click="forwardCancel"'),
  )
  await runToolHeadSafeVariantProof(
    'tool-head-attribute-reordering',
    (source) =>
      source
        .replace(
          '<el-table :data="tools" empty-text=" " stripe @expand-change="onToolExpandChange">',
          '<el-table @expand-change="onToolExpandChange" stripe empty-text=" " :data="tools">',
        )
        .replace(
          '<el-button type="primary" @click="handleTest" :loading="testRunning">执行</el-button>',
          '<el-button :loading="testRunning" @click="handleTest" type="primary">执行</el-button>',
        )
        .replace(
          '      finish-label="保存"\n      width="1180px"\n      top="3vh"\n      append-to-body\n',
          '      append-to-body\n      top="3vh"\n      width="1180px"\n      finish-label="保存"\n',
        ),
  )

  if (baselineFailed) return

  const structuralAgentOptions = { skipHeadPreservation: true }
  const headAgentOptions = {
    skipHeadPreservation: true,
    expectedPanelCount: 3,
    expectedStatusTagCount: 3,
  }
  const structuralMcpOptions = { skipHeadPreservation: true }
  for (const [name, mutate, expectedFailure] of [
    [
      'mcp-missing-page-header-import',
      (source) => replaceRequired(
        source,
        "@/components/common/PageHeader.vue",
        "@/components/common/MissingPageHeader.vue",
        'MCP PageHeader import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and CodeSnippetBlock',
    ],
    [
      'mcp-wrong-page-header-render',
      (source) => source.replace('<PageHeader\n', '<MissingPageHeader\n'),
      'must render the exact spacious MCP page root, PageHeader, and immediate info alert',
    ],
    [
      'mcp-root-density',
      (source) => source.replace('density="spacious"', 'density="comfortable"'),
      'must render the exact spacious MCP page root, PageHeader, and immediate info alert',
    ],
    [
      'mcp-header-density',
      (source) => source.replace(
        'description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"\n      density="spacious"',
        'description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"\n      density="comfortable"',
      ),
      'must render the exact spacious MCP page root, PageHeader, and immediate info alert',
    ],
    [
      'mcp-panel-density',
      (source) => source.replace(
        '<WorkbenchPanel title="① 服务端基础信息" density="spacious">',
        '<WorkbenchPanel title="① 服务端基础信息" density="comfortable">',
      ),
      'must render five ordered spacious panels with steps 1–2 left and 3–5 right',
    ],
    [
      'mcp-step-title-order',
      (source) => source.replace('② Cursor 接入', '④ Cursor 接入'),
      'must render five ordered spacious panels with steps 1–2 left and 3–5 right',
    ],
    [
      'mcp-column-ownership',
      (source) => source.replace(
        '<div class="mcp-onboarding__column">',
        '<div class="mcp-onboarding__wrong-column">',
      ),
      'must render five ordered spacious panels with steps 1–2 left and 3–5 right',
    ],
    [
      'mcp-missing-snippet',
      (source) => source.replace(
        '          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n',
        '',
      ),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-duplicate-snippet',
      (source) => source.replace(
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <CodeSnippetBlock title="duplicate" :code="cursorExample" @copy="copy" />',
      ),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-wrong-snippet-binding',
      (source) => source.replace(':code="genericExample"', ':code="cursorExample"'),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-highlighted-snippet',
      (source) => source.replace(
        ':code="genericExample" @copy="copy"',
        ':code="genericExample" :highlighted-code="genericExample" @copy="copy"',
      ),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-wrong-snippet-copy-handler',
      (source) => source.replace(':code="curlExample" @copy="copy"', ':code="curlExample" @copy="noop"'),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-duplicate-page-copy-control',
      (source) => source.replace(
        '<CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
        '<CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />\n          <el-button @click="copy(curlExample)">复制</el-button>',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-raw-pre',
      (source) => source.replace(
        '<CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
        '<pre>{{ curlExample }}</pre>\n          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
      ),
      'must not render raw el-card or pre code surfaces',
    ],
    [
      'mcp-raw-card',
      (source) => source.replace(
        '<CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
        '<el-card />\n          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
      ),
      'must not render raw el-card or pre code surfaces',
    ],
    [
      'mcp-info-alert-copy',
      (source) => source.replace('一句话理解：把本仓的 Tool', '快速理解：把本仓的 Tool'),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-warning-type',
      (source) => source.replace('<el-alert type="warning"', '<el-alert type="info"'),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-warning-link-target',
      (source) => source.replace('target="_blank"', 'target="_self"'),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-description-copy-handler',
      (source) => source.replace('@click="copy(manifestUrl)"', '@click="copy(jsonrpcUrl)"'),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-hidden-prose',
      (source) => source.replace(
        '<p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
        '<p hidden>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
      ),
      'must keep all MCP onboarding wrappers and business content visible and unconditional',
    ],
    [
      'mcp-v-show-prose',
      (source) => source.replace(
        '<p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
        '<p v-show="true">编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
      ),
      'must keep all MCP onboarding wrappers and business content visible and unconditional',
    ],
    [
      'mcp-inline-prose-style',
      (source) => source.replace(
        '<p>直接配置远端 MCP 服务地址：</p>',
        '<p style="margin: 0">直接配置远端 MCP 服务地址：</p>',
      ),
      'must not own inline presentation or runtime public-component overrides',
    ],
    [
      'mcp-local-theme-selector',
      (source) => source.replace(
        '</style>',
        '\n:global([data-theme="light"]) { .mcp-onboarding { padding: 0; } }\n</style>',
      ),
      'compiled style 1 must not own page theme or brand selector [data-theme="light"]',
    ],
    [
      'mcp-literal-color',
      (source) => source.replace('  word-break: break-word;', '  word-break: break-word;\n  color: #fff;'),
      'compiled style 1 must not contain color literal #fff',
    ],
    [
      'mcp-private-token-background',
      (source) => source.replace(
        '  min-width: 0;\n}\n\n.mcp-onboarding__column',
        '  min-width: 0;\n  background: var(--surface-glass-panel);\n}\n\n.mcp-onboarding__column',
      ),
      'must not own local theme, code-surface, typography, or public-component recipes',
    ],
    [
      'mcp-public-code-override',
      (source) => source.replace('</style>', '\n.code-shell { min-width: 0; }\n</style>'),
      'must not own local theme, code-surface, typography, or public-component recipes',
    ],
    [
      'mcp-two-column-layout',
      (source) => source.replace(
        'grid-template-columns: repeat(2, minmax(0, 1fr));',
        'grid-template-columns: minmax(0, 1fr);',
      ),
      'must keep the exact approved MCP layout selector, declaration, and media oracle',
    ],
    [
      'mcp-responsive-collapse',
      (source) => source.replace(
        'grid-template-columns: minmax(0, 1fr);\n  }\n}',
        'grid-template-columns: repeat(2, minmax(0, 1fr));\n  }\n}',
      ),
      'must keep the exact approved MCP layout selector, declaration, and media oracle',
    ],
    [
      'mcp-no-overflow-min-width',
      (source) => source.replace(
        '.mcp-onboarding__column {\n  display: flex;\n  flex-direction: column;\n  gap: var(--section-gap);\n  min-width: 0;',
        '.mcp-onboarding__column {\n  display: flex;\n  flex-direction: column;\n  gap: var(--section-gap);',
      ),
      'must keep the exact approved MCP layout selector, declaration, and media oracle',
    ],
    [
      'mcp-side-space-reservation',
      (source) => source.replace(
        '  grid-template-columns: repeat(2, minmax(0, 1fr));\n  gap: var(--section-gap);',
        '  grid-template-columns: repeat(2, minmax(0, 1fr));\n  width: calc(100% - 272px);\n  gap: var(--section-gap);',
      ),
      'must keep the exact approved MCP layout selector, declaration, and media oracle',
    ],
    [
      'mcp-hidden-ancestor-wrapper',
      (source) => replaceRequired(
        source,
        '          <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>\n          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '          <div class="hidden">\n            <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>\n            <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          </div>',
        'MCP hidden ancestor wrapper mutation',
      ),
      'must keep required MCP business content and snippets in the exact visible wrapper ancestry',
    ],
    [
      'mcp-native-duplicate-copy-control',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <button @click="copy(cursorExample)">复制配置</button>',
        'MCP native duplicate copy mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-claude-warning-wrong-panel',
      (source) => moveMcpWarningToPanelFour(source),
      'Claude warning and link must remain in panel 3 immediately after its CodeSnippetBlock',
    ],
    [
      'mcp-style-media-attribute',
      (source) => replaceRequired(
        source,
        '<style scoped lang="scss">',
        '<style scoped lang="scss" media="print">',
        'MCP style media mutation',
      ),
      'must use exactly one scoped SCSS style block with no media, module, or src attributes',
    ],
    [
      'mcp-runtime-declaration-order',
      (source) => moveMcpCopyBeforeRuntimeDeclarations(source),
      'must preserve HEAD MCP top-level runtime declaration order after approved imports',
    ],
    [
      'mcp-native-clipboard-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <button @click="navigator.clipboard.writeText(cursorExample)">复制</button>',
        'MCP native clipboard event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-element-plus-clipboard-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <el-button @click="navigator.clipboard.writeText(cursorExample)">复制</el-button>',
        'MCP Element Plus clipboard event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-anchor-copy-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <a @click="copy(cursorExample)">复制</a>',
        'MCP anchor copy event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    ...['popover', 'align="right"', 'dir="rtl"'].map((attributeSource) => [
      `mcp-column-attribute-${attributeSource.split('=')[0]}`,
      (source) => replaceRequired(
        source,
        '<div class="mcp-onboarding__column">',
        `<div class="mcp-onboarding__column" ${attributeSource}>`,
        `MCP column ${attributeSource} mutation`,
      ),
      'must keep exact MCP root, column, panel, snippet, and business-wrapper attributes and directives',
    ]),
    [
      'mcp-style-dynamic-media-attribute',
      (source) => replaceRequired(
        source,
        '<style scoped lang="scss">',
        '<style scoped lang="scss" :media="print">',
        'MCP dynamic style media mutation',
      ),
      'must use exactly one scoped SCSS style block with no media, module, or src attributes',
    ],
    ...[
      ['span', '<span>额外内容</span>'],
      ['input', '<input type="button" value="复制" />'],
      ['details', '<details><summary>更多</summary><span>额外内容</span></details>'],
    ].map(([variant, extraContent]) => [
      `mcp-extra-direct-content-${variant}`,
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        `<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          ${extraContent}`,
        `MCP extra direct ${variant} mutation`,
      ),
      'must preserve exact MCP root, column, and five-panel direct-child topology',
    ]),
  ]) {
    await runMcpOnboardingMutationProof(
      name,
      syntheticMcpOnboardingConsumer,
      structuralMcpOptions,
      mutate,
      expectedFailure,
    )
  }

  for (const [name, mutate, expectedFailure] of [
    [
      'mcp-actual-info-alert-copy',
      (source) => replaceRequired(
        source,
        '一句话理解：把本仓的 Tool',
        '快速理解：把本仓的 Tool',
        'MCP info alert copy mutation',
      ),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-actual-warning-link',
      (source) => replaceRequired(
        source,
        'https://github.com/modelcontextprotocol/servers',
        'https://github.com/modelcontextprotocol/specification',
        'MCP warning link mutation',
      ),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-actual-duplicate-snippet-copy-control',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <el-button @click="copy(cursorExample)">复制配置</el-button>',
        'MCP duplicate snippet copy mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-actual-hidden-business-content',
      (source) => replaceRequired(
        source,
        '<p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
        '<p hidden>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>',
        'MCP hidden business content mutation',
      ),
      'must keep all MCP onboarding wrappers and business content visible and unconditional',
    ],
    [
      'mcp-actual-added-runtime-state',
      (source) => replaceRequired(
        source,
        '\nfunction copy(text: string) {',
        '\nconst onboardingRuntimeState = true\n\nfunction copy(text: string) {',
        'MCP added runtime state mutation',
      ),
      'must preserve the exact MCP runtime variable set and generated initializer source',
    ],
    [
      'mcp-actual-added-top-level-side-effect',
      (source) => appendKnowledgeTopLevelStatement(source, 'void origin'),
      'must preserve HEAD MCP non-presentation imports and top-level side-effect order',
    ],
    [
      'mcp-actual-missing-snippet-import',
      (source) => replaceRequired(
        source,
        "@/components/common/CodeSnippetBlock.vue",
        "@/components/common/MissingCodeSnippetBlock.vue",
        'actual MCP snippet import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and CodeSnippetBlock',
    ],
    [
      'mcp-actual-step-order',
      (source) => replaceRequired(source, '⑤ curl 自检', '④ curl 自检', 'actual MCP step mutation'),
      'must render five ordered spacious panels with steps 1–2 left and 3–5 right',
    ],
    [
      'mcp-actual-snippet-binding',
      (source) => replaceRequired(
        source,
        ':code="genericExample"',
        ':code="cursorExample"',
        'actual MCP snippet binding mutation',
      ),
      'must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls',
    ],
    [
      'mcp-actual-raw-pre',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
        '<pre>{{ curlExample }}</pre>\n          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />',
        'actual MCP raw pre mutation',
      ),
      'must not render raw el-card or pre code surfaces',
    ],
    [
      'mcp-actual-warning-target',
      (source) => replaceRequired(source, 'target="_blank"', 'target="_self"', 'actual MCP target mutation'),
      'must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls',
    ],
    [
      'mcp-actual-generated-whitespace',
      (source) => replaceRequired(
        source,
        '      "headers": { "Authorization": "Bearer YOUR_API_KEY" }',
        '       "headers": { "Authorization": "Bearer YOUR_API_KEY" }',
        'actual MCP generated whitespace mutation',
      ),
      'must preserve the exact MCP runtime variable set and generated initializer source',
    ],
    [
      'mcp-actual-endpoint-initializer',
      (source) => replaceRequired(
        source,
        '`${origin}/mcp/jsonrpc`',
        '`${origin}/mcp/rpc`',
        'actual MCP endpoint initializer mutation',
      ),
      'must preserve the exact MCP runtime variable set and generated initializer source',
    ],
    [
      'mcp-actual-copy-function',
      (source) => replaceRequired(
        source,
        "ElMessage.success('已复制')",
        "ElMessage.success('复制成功')",
        'actual MCP copy function mutation',
      ),
      'must preserve HEAD MCP copy(text: string) function exactly',
    ],
    [
      'mcp-actual-non-presentation-import',
      (source) => replaceRequired(
        source,
        "from '@element-plus/icons-vue'",
        "from '@element-plus/icons-vue-missing'",
        'actual MCP non-presentation import mutation',
      ),
      'must preserve HEAD MCP non-presentation imports and top-level side-effect order',
    ],
    [
      'mcp-actual-inline-style',
      (source) => replaceRequired(
        source,
        '<p>直接配置远端 MCP 服务地址：</p>',
        '<p style="margin: 0">直接配置远端 MCP 服务地址：</p>',
        'actual MCP inline style mutation',
      ),
      'must not own inline presentation or runtime public-component overrides',
    ],
    [
      'mcp-actual-public-code-override',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.code-panel { min-width: 0; }\n</style>',
        'actual MCP public code override mutation',
      ),
      'must not own local theme, code-surface, typography, or public-component recipes',
    ],
    [
      'mcp-actual-style-oracle',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: repeat(2, minmax(0, 1fr));\n  gap: var(--section-gap);',
        '  grid-template-columns: repeat(2, minmax(0, 1fr));\n  align-items: stretch;\n  gap: var(--section-gap);',
        'actual MCP style oracle mutation',
      ),
      'must keep the exact approved MCP layout selector, declaration, and media oracle',
    ],
    [
      'mcp-actual-hidden-ancestor-wrapper',
      (source) => replaceRequired(
        source,
        '          <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>\n          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '          <div class="hidden">\n            <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>\n            <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          </div>',
        'actual MCP hidden ancestor wrapper mutation',
      ),
      'must keep required MCP business content and snippets in the exact visible wrapper ancestry',
    ],
    [
      'mcp-actual-native-duplicate-copy-control',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <button @click="copy(cursorExample)">复制配置</button>',
        'actual MCP native duplicate copy mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-actual-claude-warning-wrong-panel',
      (source) => moveMcpWarningToPanelFour(source),
      'Claude warning and link must remain in panel 3 immediately after its CodeSnippetBlock',
    ],
    [
      'mcp-actual-style-media-attribute',
      (source) => replaceRequired(
        source,
        '<style scoped lang="scss">',
        '<style scoped lang="scss" media="print">',
        'actual MCP style media mutation',
      ),
      'must use exactly one scoped SCSS style block with no media, module, or src attributes',
    ],
    [
      'mcp-actual-runtime-declaration-order',
      (source) => moveMcpCopyBeforeRuntimeDeclarations(source),
      'must preserve HEAD MCP top-level runtime declaration order after approved imports',
    ],
    [
      'mcp-actual-native-clipboard-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <button @click="navigator.clipboard.writeText(cursorExample)">复制</button>',
        'actual MCP native clipboard event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-actual-element-plus-clipboard-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <el-button @click="navigator.clipboard.writeText(cursorExample)">复制</el-button>',
        'actual MCP Element Plus clipboard event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    [
      'mcp-actual-anchor-copy-event',
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          <a @click="copy(cursorExample)">复制</a>',
        'actual MCP anchor copy event mutation',
      ),
      'must preserve exact MCP template event topology and forbid extra interactive copy controls',
    ],
    ...['popover', 'align="right"', 'dir="rtl"'].map((attributeSource) => [
      `mcp-actual-column-attribute-${attributeSource.split('=')[0]}`,
      (source) => replaceRequired(
        source,
        '<div class="mcp-onboarding__column">',
        `<div class="mcp-onboarding__column" ${attributeSource}>`,
        `actual MCP column ${attributeSource} mutation`,
      ),
      'must keep exact MCP root, column, panel, snippet, and business-wrapper attributes and directives',
    ]),
    [
      'mcp-actual-style-dynamic-media-attribute',
      (source) => replaceRequired(
        source,
        '<style scoped lang="scss">',
        '<style scoped lang="scss" :media="print">',
        'actual MCP dynamic style media mutation',
      ),
      'must use exactly one scoped SCSS style block with no media, module, or src attributes',
    ],
    [
      'mcp-actual-presentation-import-after-runtime',
      (source) => moveMcpPresentationImportAfterFirstVariable(source),
      'must place every MCP import before the first runtime statement',
    ],
    ...[
      ['span', '<span>额外内容</span>'],
      ['input', '<input type="button" value="复制" />'],
      ['details', '<details><summary>更多</summary><span>额外内容</span></details>'],
    ].map(([variant, extraContent]) => [
      `mcp-actual-extra-direct-content-${variant}`,
      (source) => replaceRequired(
        source,
        '<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />',
        `<CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />\n          ${extraContent}`,
        `actual MCP extra direct ${variant} mutation`,
      ),
      'must preserve exact MCP root, column, and five-panel direct-child topology',
    ]),
    [
      'mcp-actual-presentation-import-sequence',
      (source) => replaceRequired(
        source,
        "import PageHeader from '@/components/common/PageHeader.vue'\nimport WorkbenchPage from '@/components/common/WorkbenchPage.vue'\nimport WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'\nimport CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'",
        "import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'\nimport PageHeader from '@/components/common/PageHeader.vue'\nimport WorkbenchPage from '@/components/common/WorkbenchPage.vue'\nimport WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'",
        'actual MCP presentation import sequence mutation',
      ),
      'must preserve exact MCP presentation import sequence after HEAD imports',
    ],
  ]) {
    await runMcpOnboardingMutationProof(
      name,
      actualMcpOnboardingSource,
      {},
      mutate,
      expectedFailure,
    )
  }

  await runMcpOnboardingSafeVariantProof(
    'mcp-actual-attribute-and-declaration-reordering',
    actualMcpOnboardingSource,
    {},
    (source) =>
      replaceRequired(
        replaceRequired(
          source,
          '<PageHeader\n      variant="workbench"\n      domain="platform"\n      eyebrow="MCP Onboarding"\n      title="MCP 接入向导"\n      description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"\n      density="spacious"\n    />',
          '<PageHeader\n      domain="platform"\n      eyebrow="MCP Onboarding"\n      variant="workbench"\n      density="spacious"\n      description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"\n      title="MCP 接入向导"\n    />',
          'MCP safe PageHeader attribute reorder',
        ),
        '  gap: var(--section-gap);\n  min-width: 0;\n}',
        '  min-width: 0;\n  gap: var(--section-gap);\n}',
        'MCP safe columns declaration reorder',
      ),
  )

  for (const [name, mutate, expectedFailure] of [
    [
      'agent-edit-missing-page-header-import',
      (source) => replaceRequired(
        source,
        "@/components/common/PageHeader.vue",
        "@/components/common/MissingPageHeader.vue",
        'AgentEdit PageHeader import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and StatusTag',
    ],
    [
      'agent-edit-wrong-page-header-render',
      (source) => replaceRequired(
        replaceRequired(source, '<PageHeader\n', '<BrokenPageHeader\n', 'AgentEdit PageHeader opening mutation'),
        '</PageHeader>',
        '</BrokenPageHeader>',
        'AgentEdit PageHeader closing mutation',
      ),
      'must render exactly one PageHeader, two WorkbenchPanels, and two StatusTags',
    ],
    [
      'agent-edit-missing-show-back',
      (source) => replaceRequired(source, '      show-back\n', '', 'AgentEdit show-back mutation'),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-wrong-back-handler',
      (source) => replaceRequired(
        source,
        '@back="router.push(\'/agent\')"',
        '@back="router.push(\'/tool\')"',
        'AgentEdit back handler mutation',
      ),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-wrong-title',
      (source) => replaceRequired(
        source,
        "isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}`",
        "isNew ? '创建入口' : `修改入口 - ${form.name || agentId}`",
        'AgentEdit title mutation',
      ),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-wrong-eyebrow',
      (source) => replaceRequired(
        source,
        'eyebrow="Agent / Supervisor"',
        'eyebrow="Agent"',
        'AgentEdit eyebrow mutation',
      ),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-wrong-header-density',
      (source) => replaceRequired(
        source,
        '      density="comfortable"\n      show-back',
        '      density="compact"\n      show-back',
        'AgentEdit header density mutation',
      ),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-kind-status-tone',
      (source) => replaceRequired(
        source,
        ':label="agentKindLabel(form.agentKind)" tone="neutral"',
        ':label="agentKindLabel(form.agentKind)" tone="success"',
        'AgentEdit kind tone mutation',
      ),
      'PageHeader tags must map Agent kind and enabled state through semantic StatusTag',
    ],
    [
      'agent-edit-enabled-status-label',
      (source) => replaceRequired(
        source,
        ":label=\"form.enabled !== false ? '启用' : '停用'\"",
        ":label=\"form.enabled !== false ? '在线' : '离线'\"",
        'AgentEdit enabled label mutation',
      ),
      'PageHeader tags must map Agent kind and enabled state through semantic StatusTag',
    ],
    [
      'agent-edit-enabled-status-tone',
      (source) => replaceRequired(
        source,
        ":tone=\"form.enabled !== false ? 'success' : 'info'\"",
        ":tone=\"form.enabled !== false ? 'warning' : 'danger'\"",
        'AgentEdit enabled tone mutation',
      ),
      'PageHeader tags must map Agent kind and enabled state through semantic StatusTag',
    ],
    [
      'agent-edit-copy-disabled-binding',
      (source) => replaceRequired(
        source,
        ':disabled="!canCopyAgentIdentity"',
        ':disabled="isNew"',
        'AgentEdit copy disabled mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-copy-handler',
      (source) => replaceRequired(
        source,
        '@click="copyAgentIdentity"',
        '@click="openWorkflows"',
        'AgentEdit copy handler mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-workflow-condition',
      (source) => replaceRequired(
        source,
        '<el-button v-if="!isNew" :icon="Share" @click="openWorkflows">Workflow 列表</el-button>',
        '<el-button :icon="Share" @click="openWorkflows">Workflow 列表</el-button>',
        'AgentEdit Workflow condition mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-publish-condition',
      (source) => replaceRequired(
        source,
        '<el-button v-if="!isNew" type="success" plain :loading="publishing" @click="handlePublish">发布 Supervisor 配置</el-button>',
        '<el-button v-if="isNew" type="success" plain :loading="publishing" @click="handlePublish">发布 Supervisor 配置</el-button>',
        'AgentEdit publish condition mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-save-loading',
      (source) => replaceRequired(
        source,
        '<el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>',
        '<el-button type="primary" @click="handleSave">保存</el-button>',
        'AgentEdit save loading mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-form-shell',
      (source) => replaceRequired(
        source,
        'label-width="108px"',
        'label-width="120px"',
        'AgentEdit form shell mutation',
      ),
      'must preserve the exact existing el-form shell contract',
    ],
    [
      'agent-edit-primary-panel-title',
      (source) => replaceRequired(
        source,
        '          title="身份与策略"',
        '          title="基础信息"',
        'AgentEdit primary panel title mutation',
      ),
      'primary WorkbenchPanel must own identity/strategy heading and enabled switch action',
    ],
    [
      'agent-edit-primary-panel-description',
      (source) => replaceRequired(
        source,
        'description="配置 Agent 入口身份、可见性与默认策略；图编排请在 Workflow Studio 完成。"',
        'description="配置入口。"',
        'AgentEdit primary panel description mutation',
      ),
      'primary WorkbenchPanel must own identity/strategy heading and enabled switch action',
    ],
    [
      'agent-edit-primary-panel-actions-slot',
      (source) => replaceRequired(
        source,
        '<template #actions>\n            <el-switch',
        '<template #footer>\n            <el-switch',
        'AgentEdit primary actions slot mutation',
      ),
      'primary WorkbenchPanel must own identity/strategy heading and enabled switch action',
    ],
    [
      'agent-edit-enabled-switch-model',
      (source) => replaceRequired(
        source,
        '<el-switch v-model="form.enabled"',
        '<el-switch v-model="form.visibility"',
        'AgentEdit enabled switch model mutation',
      ),
      'primary WorkbenchPanel must own identity/strategy heading and enabled switch action',
    ],
    [
      'agent-edit-secondary-panel-condition',
      (source) => replaceRequired(
        source,
        '<aside v-if="!isNew" class="agent-workbench__aside">',
        '<aside class="agent-workbench__aside">',
        'AgentEdit secondary condition mutation',
      ),
      'secondary WorkbenchPanel must be edit-only and preserve the Workflow catalog action',
    ],
    [
      'agent-edit-secondary-action-handler',
      (source) => replaceRequired(
        source,
        '@click="openWorkflows">打开 Workflow 列表',
        '@click="handlePublish">打开 Workflow 列表',
        'AgentEdit secondary action mutation',
      ),
      'secondary WorkbenchPanel must be edit-only and preserve the Workflow catalog action',
    ],
    [
      'agent-edit-new-mode-fixed-column',
      (source) => replaceRequired(
        source,
        '.workbench-layout {\n  display: grid;\n  grid-template-columns: minmax(0, 1fr);',
        '.workbench-layout {\n  display: grid;\n  grid-template-columns: minmax(0, 1fr) 272px;',
        'AgentEdit new-mode fixed column mutation',
      ),
      'new mode must not render or reserve an unconditional second column',
    ],
    [
      'agent-edit-edit-column-modifier',
      (source) => replaceRequired(
        source,
        ":class=\"{ 'workbench-layout--with-aside': !isNew }\"",
        ":class=\"{ 'workbench-layout--with-aside': isNew }\"",
        'AgentEdit edit column modifier mutation',
      ),
      'new mode must not render or reserve an unconditional second column',
    ],
    [
      'agent-edit-missing-edit-collapse',
      (source) => replaceRequired(
        source,
        '@media (max-width: 1280px) {\n  .workbench-layout--with-aside {\n    grid-template-columns: minmax(0, 1fr);\n  }\n}',
        '@media (max-width: 1280px) {\n  .workbench-layout--with-aside {\n    min-width: 0;\n  }\n}',
        'AgentEdit edit collapse mutation',
      ),
      'must preserve the responsive two-column form grid and mobile collapse',
    ],
    [
      'agent-edit-missing-form-two-column',
      (source) => replaceRequired(
        source,
        '.form-grid.two {\n  grid-template-columns: repeat(2, minmax(0, 1fr));\n}',
        '.form-grid.two {\n  grid-template-columns: 1fr;\n}',
        'AgentEdit form columns mutation',
      ),
      'must preserve the responsive two-column form grid and mobile collapse',
    ],
    [
      'agent-edit-form-field-model',
      (source) => replaceRequired(
        source,
        'v-model="form.description"',
        'v-model="form.name"',
        'AgentEdit form field mutation',
      ),
      'must preserve the complete ordered Agent form field contract',
    ],
    [
      'agent-edit-root-rhythm',
      (source) => replaceRequired(
        source,
        '<WorkbenchPage density="comfortable" full-height>',
        '<WorkbenchPage density="comfortable">',
        'AgentEdit root rhythm mutation',
      ),
      'page root must use comfortable density',
    ],
    [
      'agent-edit-private-token-background',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  background: var(--surface-glass-panel);',
        'AgentEdit token background mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-radius',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  border-radius: var(--radius-lg);',
        'AgentEdit radius mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-opacity',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  opacity: 0.98;',
        'AgentEdit opacity mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-deep-skin',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.agent-workbench:deep(.el-input) { width: 100%; }\n</style>',
        'AgentEdit deep skin mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-legacy-template-surface',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-header { min-width: 0; }\n</style>',
        'AgentEdit legacy template class mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
  ]) {
    await runAgentEditMutationProof(
      name,
      syntheticAgentEditConsumer,
      structuralAgentOptions,
      mutate,
      expectedFailure,
    )
  }

  for (const [name, mutate, expectedFailure] of [
    [
      'agent-edit-missing-workbench-panel-import',
      (source) => replaceRequired(
        source,
        '@/components/common/WorkbenchPanel.vue',
        '@/components/common/MissingWorkbenchPanel.vue',
        'AgentEdit WorkbenchPanel import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and StatusTag',
    ],
    [
      'agent-edit-missing-status-tag-import',
      (source) => replaceRequired(
        source,
        '@/components/common/StatusTag.vue',
        '@/components/common/MissingStatusTag.vue',
        'AgentEdit StatusTag import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and StatusTag',
    ],
    [
      'agent-edit-wrong-primary-panel-render',
      (source) => replaceRequired(
        replaceRequired(
          source,
          '<WorkbenchPanel\n          title="身份与策略"',
          '<BrokenWorkbenchPanel\n          title="身份与策略"',
          'AgentEdit primary panel opening mutation',
        ),
        '</WorkbenchPanel>',
        '</BrokenWorkbenchPanel>',
        'AgentEdit primary panel closing mutation',
      ),
      'must render exactly one PageHeader, two WorkbenchPanels, and two StatusTags',
    ],
    [
      'agent-edit-wrong-status-tag-render',
      (source) => replaceRequired(
        source,
        '<StatusTag :label="agentKindLabel(form.agentKind)" tone="neutral" />',
        '<BrokenStatusTag :label="agentKindLabel(form.agentKind)" tone="neutral" />',
        'AgentEdit StatusTag render mutation',
      ),
      'must render exactly one PageHeader, two WorkbenchPanels, and two StatusTags',
    ],
    [
      'agent-edit-root-density',
      (source) => replaceRequired(
        source,
        '<WorkbenchPage density="comfortable" full-height>',
        '<WorkbenchPage density="compact" full-height>',
        'AgentEdit root density mutation',
      ),
      'page root must use comfortable density',
    ],
    [
      'agent-edit-page-header-extra-runtime-prop',
      (source) => replaceRequired(
        source,
        '      show-back\n',
        '      show-back\n      v-show="!pageLoading"\n',
        'AgentEdit PageHeader runtime prop mutation',
      ),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-primary-panel-control-level',
      (source) => replaceRequired(
        source,
        '          title="身份与策略"',
        '          title="身份与策略"\n          level="control"',
        'AgentEdit primary panel level mutation',
      ),
      'primary WorkbenchPanel must own identity/strategy heading and enabled switch action',
    ],
    [
      'agent-edit-secondary-panel-title',
      (source) => replaceRequired(
        source,
        '            title="Workflow 工具目录"',
        '            title="Workflow"',
        'AgentEdit secondary panel title mutation',
      ),
      'secondary WorkbenchPanel must be edit-only and preserve the Workflow catalog action',
    ],
    [
      'agent-edit-copy-render-condition',
      (source) => replaceRequired(
        source,
        '<el-button :icon="DocumentCopy" :disabled="!canCopyAgentIdentity"',
        '<el-button v-if="!isNew" :icon="DocumentCopy" :disabled="!canCopyAgentIdentity"',
        'AgentEdit copy render condition mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-workflow-handler',
      (source) => replaceRequired(
        source,
        '@click="openWorkflows">Workflow 列表',
        '@click="handlePublish">Workflow 列表',
        'AgentEdit Workflow handler mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-publish-handler',
      (source) => replaceRequired(
        source,
        '@click="handlePublish">发布 Supervisor 配置',
        '@click="openWorkflows">发布 Supervisor 配置',
        'AgentEdit publish handler mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-save-render-condition',
      (source) => replaceRequired(
        source,
        '<el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>',
        '<el-button v-if="!isNew" type="primary" :loading="saving" @click="handleSave">保存</el-button>',
        'AgentEdit save condition mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-save-type',
      (source) => replaceRequired(
        source,
        '<el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>',
        '<el-button type="success" :loading="saving" @click="handleSave">保存</el-button>',
        'AgentEdit save type mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-save-handler',
      (source) => replaceRequired(
        source,
        '@click="handleSave">保存',
        '@click="openWorkflows">保存',
        'AgentEdit save handler mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-form-extra-runtime-prop',
      (source) => replaceRequired(
        source,
        'label-width="108px" v-loading="pageLoading"',
        'label-width="108px" v-loading="pageLoading" v-show="!saving"',
        'AgentEdit form runtime prop mutation',
      ),
      'must preserve the exact existing el-form shell contract',
    ],
    [
      'agent-edit-missing-mobile-form-collapse',
      (source) => replaceRequired(
        source,
        '@media (max-width: 960px) {\n  .form-grid.two {\n    grid-template-columns: 1fr;\n  }',
        '@media (max-width: 960px) {\n  .form-grid.two {\n    min-width: 0;\n  }',
        'AgentEdit mobile form collapse mutation',
      ),
      'must preserve the responsive two-column form grid and mobile collapse',
    ],
    [
      'agent-edit-private-border',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  border: 1px solid var(--border-readable);',
        'AgentEdit border mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-root-padding-token',
      (source) => replaceRequired(
        source,
        '<WorkbenchPage density="comfortable" full-height>',
        '<WorkbenchPage class="agent-workbench" density="comfortable" full-height>',
        'AgentEdit root padding mutation',
      ),
      'page root must use comfortable density',
    ],
    [
      'agent-edit-private-token-shadow',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  box-shadow: var(--inner-highlight);',
        'AgentEdit shadow mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-filter',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  filter: saturate(1.01);',
        'AgentEdit filter mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-pseudo-element',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.agent-workbench::before { content: ""; }\n</style>',
        'AgentEdit pseudo mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-private-global-selector',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.agent-workbench:global(.agent-private) { width: 100%; }\n</style>',
        'AgentEdit global mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-hard-coded-color',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  --agent-private-color: #fff;',
        'AgentEdit hard-coded color mutation',
      ),
      'compiled style 1 must not contain color literal #fff',
    ],
    [
      'agent-edit-hidden-duplicate-page-header',
      (source) => replaceRequired(
        source,
        '  </WorkbenchPage>\n</template>',
        '    <PageHeader v-if="false" title="Hidden" eyebrow="Hidden" density="comfortable" show-back @back="router.push(\'/agent\')" />\n  </WorkbenchPage>\n</template>',
        'AgentEdit hidden duplicate PageHeader mutation',
      ),
      'must not retain hidden, duplicate, or presentation-mutated Agent workbench wrappers',
    ],
    [
      'agent-edit-root-side-padding-reservation',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  padding-right: 272px;',
        'AgentEdit root side reservation mutation',
      ),
      'page and base layout must not reserve side space outside the edit-only grid modifier',
    ],
    [
      'agent-edit-root-inline-private-style',
      (source) => replaceRequired(
        source,
        '    <PageHeader\n',
        '    <PageHeader\n      style="background: #fff; border: 1px solid red"\n',
        'AgentEdit root inline style mutation',
      ),
      'must not own inline, runtime, or private presentation attributes on workbench wrappers',
    ],
    [
      'agent-edit-root-side-margin-reservation',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  margin-right: 272px;',
        'AgentEdit root margin reservation mutation',
      ),
      'page and base layout must not reserve side space outside the edit-only grid modifier',
    ],
    [
      'agent-edit-root-private-runtime-class',
      (source) => replaceRequired(
        source,
        '    <PageHeader\n',
        '    <PageHeader\n      :class="agentPrivateSurface"\n',
        'AgentEdit root private class mutation',
      ),
      'must not own inline, runtime, or private presentation attributes on workbench wrappers',
    ],
    [
      'agent-edit-page-header-hidden-attribute',
      (source) => replaceRequired(
        source,
        '      show-back\n',
        '      show-back\n      hidden\n',
        'AgentEdit PageHeader hidden attribute mutation',
      ),
      'must not own inline, runtime, or private presentation attributes on workbench wrappers',
    ],
    [
      'agent-edit-form-grid-hidden',
      (source) => replaceRequired(
        source,
        '<div class="form-grid two">',
        '<div class="form-grid two" hidden>',
        'AgentEdit form grid hidden mutation',
      ),
      'form field layout containers and ancestry must remain visible and unconditional',
    ],
    [
      'agent-edit-form-grid-v-show',
      (source) => replaceRequired(
        source,
        '<div class="form-grid two">',
        '<div class="form-grid two" v-show="false">',
        'AgentEdit form grid v-show mutation',
      ),
      'form field layout containers and ancestry must remain visible and unconditional',
    ],
    [
      'agent-edit-public-workbench-width',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-panel { width: calc(100% - 272px); }\n</style>',
        'AgentEdit WorkbenchPanel width mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-public-workbench-logical-margin',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-panel { margin-inline-end: 272px; }\n</style>',
        'AgentEdit WorkbenchPanel logical margin mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-public-workbench-selector-list',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-panel,\n.form-grid { width: calc(100% - 272px); }\n</style>',
        'AgentEdit WorkbenchPanel selector-list mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-exact-style-root-align-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  align-items: flex-start;',
        'AgentEdit root align-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-exact-style-layout-justify-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n}',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  justify-items: start;\n}',
        'AgentEdit layout justify-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-exact-style-layout-place-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n}',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  place-items: start;\n}',
        'AgentEdit layout place-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-exact-style-harmless-declaration',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  isolation: isolate;',
        'AgentEdit harmless declaration mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
  ]) {
    await runAgentEditMutationProof(
      name,
      syntheticAgentEditConsumer,
      structuralAgentOptions,
      mutate,
      expectedFailure,
    )
  }

  // The legacy AgentEdit mutation oracle described the retired id-only Workflow binding UI.
  // Keep its synthetic proof above, but do not apply it to the Supervisor V2 workbench.
  if (actualAgentEditSource.includes('title="身份与策略"')) {
  for (const [name, mutate, expectedFailure] of [
    [
      'agent-edit-actual-hidden-duplicate-page-header',
      (source) => source.replace(
        /  <\/WorkbenchPage>\r?\n<\/template>/,
        '    <PageHeader v-if="false" title="Hidden" eyebrow="Hidden" density="comfortable" show-back @back="router.push(\'/agent\')" />\n  </WorkbenchPage>\n</template>',
      ),
      'must not retain hidden, duplicate, or presentation-mutated Agent workbench wrappers',
    ],
    [
      'agent-edit-actual-root-side-padding-reservation',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  padding-right: 272px;',
        'actual AgentEdit root side reservation mutation',
      ),
      'page and base layout must not reserve side space outside the edit-only grid modifier',
    ],
    [
      'agent-edit-actual-root-inline-private-style',
      (source) => replaceRequired(
        source,
        '    <PageHeader\n',
        '    <PageHeader\n      :style="{ background: \'#fff\', border: \'1px solid red\' }"\n',
        'actual AgentEdit root dynamic style mutation',
      ),
      'must not own inline, runtime, or private presentation attributes on workbench wrappers',
    ],
    [
      'agent-edit-actual-base-width-reservation',
      (source) => source.replace(
        /  gap: var\(--section-gap\);\r?\n}\r?\n\r?\n\.workbench-layout--with-aside/,
        '  gap: var(--section-gap);\n  width: calc(100% - 272px);\n}\n\n.workbench-layout--with-aside',
      ),
      'page and base layout must not reserve side space outside the edit-only grid modifier',
    ],
    [
      'agent-edit-actual-page-header-inline-style',
      (source) => source.replace(
        /      show-back\r?\n/,
        '      show-back\n      style="background: #fff"\n',
      ),
      'must not own inline, runtime, or private presentation attributes on workbench wrappers',
    ],
    [
      'agent-edit-actual-form-grid-hidden',
      (source) => replaceRequired(
        source,
        '<div class="form-grid two">',
        '<div class="form-grid two" hidden>',
        'actual AgentEdit form grid hidden mutation',
      ),
      'form field layout containers and ancestry must remain visible and unconditional',
    ],
    [
      'agent-edit-actual-form-grid-v-show',
      (source) => replaceRequired(
        source,
        '<div class="form-grid two">',
        '<div class="form-grid two" v-show="false">',
        'actual AgentEdit form grid v-show mutation',
      ),
      'form field layout containers and ancestry must remain visible and unconditional',
    ],
    [
      'agent-edit-actual-public-workbench-width',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-panel { width: calc(100% - 272px); }\n</style>',
        'actual AgentEdit WorkbenchPanel width mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-actual-public-workbench-logical-margin',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.workbench-panel { margin-inline-end: 272px; }\n</style>',
        'actual AgentEdit WorkbenchPanel logical margin mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-actual-public-header-nested-selector',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n.agent-workbench {\n  .app-page-header {\n    margin-inline-end: 272px;\n  }\n}\n</style>',
        'actual AgentEdit nested PageHeader selector mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
    [
      'agent-edit-actual-exact-style-root-align-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  align-items: flex-start;',
        'actual AgentEdit root align-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-actual-exact-style-layout-justify-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n}',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  justify-items: start;\n}',
        'actual AgentEdit layout justify-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-actual-exact-style-layout-place-items',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n}',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  place-items: start;\n}',
        'actual AgentEdit layout place-items mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-actual-exact-style-harmless-declaration',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  isolation: isolate;',
        'actual AgentEdit harmless declaration mutation',
      ),
      'must keep the exact approved AgentEdit page style rule, declaration, and media oracle',
    ],
    [
      'agent-edit-actual-show-back',
      (source) => replaceRequired(source, '      show-back\n', '', 'actual AgentEdit show-back mutation'),
      'PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density',
    ],
    [
      'agent-edit-actual-kind-status',
      (source) => replaceRequired(
        source,
        ':label="agentKindLabel(form.agentKind)" tone="neutral"',
        ':label="agentKindLabel(form.agentKind)" tone="success"',
        'actual AgentEdit kind status mutation',
      ),
      'PageHeader tags must map Agent kind and enabled state through semantic StatusTag',
    ],
    [
      'agent-edit-actual-workflow-condition',
      (source) => replaceRequired(
        source,
        '<el-button v-if="!isNew" :icon="Share" @click="openWorkflows">Workflow 列表</el-button>',
        '<el-button :icon="Share" @click="openWorkflows">Workflow 列表</el-button>',
        'actual AgentEdit Workflow condition mutation',
      ),
      'PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly',
    ],
    [
      'agent-edit-actual-secondary-condition',
      (source) => replaceRequired(
        source,
        '<aside v-if="!isNew" class="agent-workbench__aside">',
        '<aside class="agent-workbench__aside">',
        'actual AgentEdit secondary condition mutation',
      ),
      'secondary WorkbenchPanel must be edit-only and preserve the Workflow catalog action',
    ],
    [
      'agent-edit-actual-fixed-new-column',
      (source) => replaceRequired(
        source,
        '.workbench-layout {\n  display: grid;\n  grid-template-columns: minmax(0, 1fr);',
        '.workbench-layout {\n  display: grid;\n  grid-template-columns: minmax(0, 1fr) 272px;',
        'actual AgentEdit new column mutation',
      ),
      'new mode must not render or reserve an unconditional second column',
    ],
    [
      'agent-edit-actual-form-model',
      (source) => replaceRequired(
        source,
        'v-model="form.description"',
        'v-model="form.name"',
        'actual AgentEdit form model mutation',
      ),
      'must preserve the complete ordered Agent form field contract',
    ],
    [
      'agent-edit-actual-private-background',
      (source) => replaceRequired(
        source,
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);',
        '  grid-template-columns: minmax(0, 1fr);\n  gap: var(--section-gap);\n  background: var(--surface-glass-panel);',
        'actual AgentEdit private background mutation',
      ),
      'must remove legacy/private Agent page surfaces and typography recipes',
    ],
  ]) {
    await runAgentEditMutationProof(
      name,
      actualAgentEditSource,
      headAgentOptions,
      mutate,
      expectedFailure,
    )
  }

  await runPassingContractVariantProof(
    'agent-edit-actual-attribute-reordering',
    actualAgentEditSource,
    (source, label) => validateAgentEditConsumer(source, label, headAgentOptions),
    (source) => replaceRequired(
      replaceRequired(
        source,
        "      :title=\"isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}`\"\n      eyebrow=\"Agent / Supervisor\"\n      density=\"comfortable\"\n      show-back\n      @back=\"router.push('/agent')\"",
        "      show-back\n      density=\"comfortable\"\n      eyebrow=\"Agent / Supervisor\"\n      @back=\"router.push('/agent')\"\n      :title=\"isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}`\"",
        'AgentEdit PageHeader attribute reorder',
      ),
      '<el-form ref="formRef" :model="form" :rules="rules" label-width="108px" v-loading="pageLoading">',
      '<el-form v-loading="pageLoading" label-width="108px" :rules="rules" :model="form" ref="formRef">',
      'AgentEdit form attribute reorder',
    ),
  )
  }

  for (const [name, needle, replacement, expectedFailure] of [
    ['agent-supervisor-workbench-identity', 'v-model="form.keySlug"', 'v-model="form.legacySlug"', 'must separate stable Agent identity'],
    ['agent-supervisor-workbench-runtime', 'v-model="supervisor.modelInstanceId"', 'v-model="form.modelInstanceId"', 'must expose the versioned Supervisor runtime configuration'],
    ['agent-supervisor-workbench-version-copy', 'await copyAgentConfigToDraft(', 'await copyLegacyConfig(', 'must support copying immutable config snapshots'],
    ['agent-supervisor-workbench-tool-records', 'v-for="(row, index) in workflowTools"', 'v-for="(row, index) in selectedWorkflowIds"', 'must render the editable Workflow tool catalog'],
    ['agent-supervisor-workbench-tool-name', 'v-model="row.toolName"', 'v-model="row.workflowName"', 'must expose stable Supervisor tool names'],
    ['agent-supervisor-workbench-permission', 'v-model="row.permissionKey"', 'v-model="row.descriptionOverride"', 'must expose Workflow tool permission keys'],
    ['agent-supervisor-workbench-versions', ':data="configVersions"', ':data="workflowTools"', 'must expose Supervisor config version management'],
    ['agent-supervisor-workbench-id-only', 'tools: workflowTools.value.map((tool, priority) => ({ ...tool, priority }))', 'tools: selectedWorkflowIds.value.map((workflowId) => ({ workflowId }))', 'must not reduce Workflow tools to a static id-only binding'],
    ['agent-supervisor-workbench-unstable-name', 'toolName: workflowToolName(workflow)', 'toolName: workflow.name', 'must expose stable Supervisor tool names'],
    ['agent-supervisor-workbench-disabled-tool-editor', 'v-model="row.toolName"', 'disabled v-model="row.toolName"', 'must expose stable Supervisor tool names'],
    ['agent-supervisor-workbench-display-name-key', "workflow.keySlug || workflow.id || 'workflow'", "workflow.name || workflow.id || 'workflow'", 'must expose stable Supervisor tool names'],
    ['agent-supervisor-workbench-disabled-permission-editor', 'v-model="row.permissionKey"', 'disabled v-model="row.permissionKey"', 'must expose Workflow tool permission keys'],
    ['agent-supervisor-workbench-retired-entry', "runtimeType: 'AGENTSCOPE'", "runtimeType: 'PAGE_ENTRY'", 'must not retain the retired PAGE_ENTRY semantic'],
    ['agent-supervisor-workbench-second-identity', 'const agentId = route.params.id as string', 'const agentDefinitionId = route.params.id as string\nconst agentId = agentDefinitionId', 'must use agentId as the only Agent execution identity'],
  ]) {
    const mutated = replaceRequired(actualAgentEditSource, needle, replacement, name)
    const mutationFailures = await validateAgentSupervisorWorkbenchConsumer(mutated, name)
    if (!mutationFailures.some((failure) => failure.includes(expectedFailure))) {
      failures.push(`${name} mutation must be rejected: ${expectedFailure}`)
    } else {
      mutationProofCount += 1
      console.log(`Mutation proof ${name}: ${expectedFailure}`)
    }
  }
  await runPassingContractVariantProof(
    'agent-supervisor-current-heading-copy', actualAgentEditSource, validateAgentSupervisorWorkbenchConsumer,
    source => replaceRequired(source, 'title="基本信息"', 'title="身份与使用范围"', 'current Agent heading safe variant'),
  )
  await runPassingContractVariantProof(
    'agent-supervisor-current-attribute-order', actualAgentEditSource, validateAgentSupervisorWorkbenchConsumer,
    source => replaceRequired(source, 'v-model="row.toolName" placeholder="通常无需修改"',
      'placeholder="通常无需修改" v-model="row.toolName"', 'current Agent attribute safe variant'),
  )

  const structuralKnowledgeOptions = { skipHeadPreservation: true }

  for (const [name, tag, attributeSource, expectedFailure] of [
    [
      'knowledge-detail-page-header-v-show',
      'PageHeader',
      'v-show="false"',
      'PageHeader must use only its Task 8 runtime directive and attribute allowlist',
    ],
    [
      'knowledge-detail-metric-strip-v-show',
      'MetricStrip',
      'v-show="false"',
      'MetricStrip must use only its Task 8 runtime directive and attribute allowlist',
    ],
    [
      'knowledge-detail-workbench-panel-v-show',
      'WorkbenchPanel',
      'v-show="false"',
      'WorkbenchPanel must use only its Task 8 runtime directive and attribute allowlist',
    ],
    [
      'knowledge-detail-data-table-shell-v-show',
      'DataTableShell',
      'v-show="false"',
      'DataTableShell must use only its Task 8 runtime directive and attribute allowlist',
    ],
    [
      'knowledge-detail-app-dialog-v-show',
      'AppDialog',
      'v-show="false"',
      'AppDialog must use only HEAD-equivalent model/title/width runtime attributes',
    ],
    [
      'knowledge-detail-app-dialog-description',
      'AppDialog',
      'description="额外说明"',
      'AppDialog must use only HEAD-equivalent model/title/width runtime attributes',
    ],
    [
      'knowledge-detail-app-dialog-close-on-click-modal',
      'AppDialog',
      ':close-on-click-modal="false"',
      'AppDialog must use only HEAD-equivalent model/title/width runtime attributes',
    ],
  ]) {
    await runKnowledgeDetailMutationProof(
      name,
      syntheticKnowledgeDetailConsumer,
      structuralKnowledgeOptions,
      (source) => mutateKnowledgeWrapperAttribute(source, tag, attributeSource),
      expectedFailure,
    )
  }

  await runKnowledgeDetailSafeVariantProof(
    'knowledge-detail-attribute-reordering',
    syntheticKnowledgeDetailConsumer,
    structuralKnowledgeOptions,
    (source) => {
      let result = replaceRequired(
        source,
        '      :title="kbInfo?.name || \'知识库详情\'"\n      show-back\n      @back="router.push(\'/knowledge\')"',
        '      @back="router.push(\'/knowledge\')"\n      show-back\n      :title="kbInfo?.name || \'知识库详情\'"',
        'KnowledgeDetail PageHeader attribute reorder',
      )
      result = replaceRequired(
        result,
        '<DataTableShell density="compact" :empty="fileList.length === 0" :loading="filesLoading">',
        '<DataTableShell :loading="filesLoading" :empty="fileList.length === 0" density="compact">',
        'KnowledgeDetail DataTableShell attribute reorder',
      )
      return replaceRequired(
        result,
        '<AppDialog v-model="tagDialogVisible" title="新增标签" width="460px">',
        '<AppDialog width="460px" title="新增标签" v-model="tagDialogVisible">',
        'KnowledgeDetail AppDialog attribute reorder',
      )
    },
  )

  for (const [name, mutate, expectedFailure] of [
    [
      'knowledge-detail-missing-show-back',
      (source) => replaceRequired(source, '      show-back\n', '', 'missing show-back mutation'),
      "PageHeader must preserve the title and show-back router.push('/knowledge') behavior",
    ],
    [
      'knowledge-detail-wrong-back-handler',
      (source) =>
        replaceRequired(
          source,
          "@back=\"router.push('/knowledge')\"",
          "@back=\"router.push('/retrieval')\"",
          'wrong back handler mutation',
        ),
      "PageHeader must preserve the title and show-back router.push('/knowledge') behavior",
    ],
    [
      'knowledge-detail-wrong-metric-key',
      (source) => replaceRequired(source, "key: 'files'", "key: 'documents'", 'metric key mutation'),
      'must render the exact five-item knowledgeMetrics MetricStrip',
    ],
    [
      'knowledge-detail-raw-card',
      (source) => insertKnowledgeTemplateBefore(source, 'el-tabs', '<el-card v-if="false" />\n    '),
      'must not retain raw el-card or el-dialog',
    ],
    [
      'knowledge-detail-raw-dialog',
      (source) => insertKnowledgeTemplateBefore(source, 'AppDialog', '<el-dialog v-if="false" />\n    '),
      'must not retain raw el-card or el-dialog',
    ],
    [
      'knowledge-detail-missing-table-shell',
      (source) => {
        let result = replaceRequired(
          source,
          '<DataTableShell density="compact" :empty="(dashboard?.hotChunks || []).length === 0">',
          '<div density="compact" :empty="(dashboard?.hotChunks || []).length === 0">',
          'missing table shell opening mutation',
        )
        return replaceRequired(
          result,
          '</DataTableShell>',
          '</div>',
          'missing table shell closing mutation',
        )
      },
      'all ten tables must use one compact DataTableShell with owned loading and a single suppressed empty state',
    ],
    [
      'knowledge-detail-table-duplicate-loading',
      (source) =>
        replaceRequired(
          source,
          '<el-table :data="dashboard?.hotChunks || []"',
          '<el-table v-loading="filesLoading" :data="dashboard?.hotChunks || []"',
          'duplicate table loading mutation',
        ),
      'all ten tables must use one compact DataTableShell with owned loading and a single suppressed empty state',
    ],
    [
      'knowledge-detail-table-empty-suppression',
      (source) => replaceRequired(source, ' empty-text=" "', '', 'table empty suppression mutation'),
      'all ten tables must use one compact DataTableShell with owned loading and a single suppressed empty state',
    ],
    [
      'knowledge-detail-non-semantic-file-status',
      (source) =>
        replaceRequired(
          source,
          '<StatusTag :label="statusText(row.status)" :tone="statusTagType(row.status)" />',
          '<el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>',
          'file status mutation',
        ),
      'scope, file, chunk, and direct-return states must use semantic StatusTag',
    ],
    [
      'knowledge-detail-changed-tab-name',
      (source) =>
        replaceRequired(
          source,
          'label="段落运营" name="chunks"',
          'label="段落管理" name="chunks"',
          'tab name mutation',
        ),
      'must preserve all seven tab labels and names in order',
    ],
    [
      'knowledge-detail-dialog-width',
      (source) =>
        replaceRequired(
          source,
          '<AppDialog v-model="tagDialogVisible" title="新增标签" width="460px">',
          '<AppDialog v-model="tagDialogVisible" title="新增标签">',
          'dialog width mutation',
        ),
      'must render all three complete AppDialogs with preserved widths and footers',
    ],
    [
      'knowledge-detail-workbench-panel-presentation-class',
      (source) =>
        replaceRequired(
          source,
          '<WorkbenchPanel title="标签库" density="compact">',
          '<WorkbenchPanel title="标签库" density="compact" class="tag-library-panel">',
          'WorkbenchPanel presentation class mutation',
        ),
      'WorkbenchPanel must use only its Task 8 runtime directive and attribute allowlist',
    ],
    [
      'knowledge-detail-legacy-tag-panel-margin',
      (source) =>
        replaceRequired(
          source,
          '</style>',
          '\n.tag-library-panel { margin-bottom: var(--section-gap); }\n</style>',
          'legacy tag panel margin mutation',
        ),
      'tag library spacing must be owned by one semantic tag-panel-stack without WorkbenchPanel presentation class or margin',
    ],
    [
      'knowledge-detail-workbench-panel-outer-margin',
      (source) =>
        replaceRequired(
          source,
          '</style>',
          '\n.tag-panel-stack > .workbench-panel { margin-bottom: var(--section-gap); }\n</style>',
          'WorkbenchPanel outer margin mutation',
        ),
      'tag library spacing must be owned by one semantic tag-panel-stack without WorkbenchPanel presentation class or margin',
    ],
    [
      'knowledge-detail-root-section-gap',
      (source) =>
        replaceRequired(
          source,
          '<WorkbenchPage density="comfortable">',
          '<WorkbenchPage density="compact">',
          'KnowledgeDetail root section gap mutation',
        ),
      'page root must use comfortable workbench density',
    ],
    [
      'knowledge-detail-root-extra-margin',
      (source) =>
        replaceRequired(
          source,
          '<WorkbenchPage density="comfortable">',
          '<WorkbenchPage class="page-container" density="comfortable">',
          'KnowledgeDetail root extra margin mutation',
        ),
      'page root must use comfortable workbench density',
    ],
    [
      'knowledge-detail-root-deep-descendant',
      (source) =>
        replaceRequired(
          source,
          '<WorkbenchPage density="comfortable">',
          '<WorkbenchPage density="comfortable" style="margin-top: var(--section-gap)">',
          'KnowledgeDetail root deep descendant mutation',
        ),
      'page root must use comfortable workbench density',
    ],
  ]) {
    await runKnowledgeDetailMutationProof(
      name,
      syntheticKnowledgeDetailConsumer,
      structuralKnowledgeOptions,
      mutate,
      expectedFailure,
    )
  }

  await runToolConsumerMutationProof(
    'tool-consumer-missing-import',
    (source) => source.replace("import PageHeader from '@/components/common/PageHeader.vue'\n", ''),
    'must directly import all seven shared components plus StatusTone and WizardStep',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-raw-dialog',
    (source) => source.replace('    <WizardDialog\n', '    <el-dialog v-if="false" />\n    <WizardDialog\n'),
    'must not retain raw dialog/card/empty/pagination or duplicate loading shells',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-raw-card',
    (source) =>
      source
        .replace('    <DataTableShell\n', '    <el-card>\n    <DataTableShell\n')
        .replace('    </DataTableShell>\n', '    </DataTableShell>\n    </el-card>\n'),
    'must not retain raw dialog/card/empty/pagination or duplicate loading shells',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-legacy-filter-shell',
    (source) => source.replace('<FilterBar :loading=', '<FilterBar class="tool-filter" :loading='),
    'must remove legacy page-header/filter/pagination/editor-rail shells',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-nested-filter-form',
    (source) =>
      source
        .replace('      <el-form-item label="关键词">', '      <el-form>\n      <el-form-item label="关键词">')
        .replace('    </FilterBar>', '      </el-form>\n    </FilterBar>'),
    'FilterBar must own query/reset without nested forms or duplicate actions while preserving all filters',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-pagination-wiring',
    (source) => source.replace(
      'v-model:current-page="pagination.current"',
      'v-model:current-page="pagination.size"',
    ),
    'DataTableShell must own compact loading/empty/pagination wiring',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-empty-description',
    (source) => source.replace(
      '      empty-description="暂无数据，请调整条件或先在后端注册 Tool"\n',
      '',
    ),
    'DataTableShell must preserve the actionable empty description',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-remove-table-empty-suppression',
    (source) => source.replace(' empty-text=" "', ''),
    'main el-table must suppress its private empty state for DataTableShell',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-break-table-empty-suppression',
    (source) => source.replace('empty-text=" "', 'empty-text="暂无数据"'),
    'main el-table must suppress its private empty state for DataTableShell',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-non-semantic-source-tag',
    (source) => source.replace(
      '<StatusTag :label="row.source" :tone="sourceTagType(row.source)" />',
      '<el-tag :type="sourceTagType(row.source)">{{ row.source }}</el-tag>',
    ),
    'must render source and catalog state through semantic StatusTag mappings',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-simultaneous-next-save',
    (source) => source.replace(
      '    </WizardDialog>',
      `      <template #footer>
        <el-button type="primary">下一步</el-button>
        <el-button type="primary">保存</el-button>
      </template>
    </WizardDialog>`,
    ),
    'must delegate mutually exclusive primary Next/Finish actions to WizardDialog',
  )
  await runToolConsumerMutationProof(
    'tool-consumer-missing-identity-guard',
    (source) => source.replace(
      [
        '() =>',
        '    activeToolStep.value !== 0 ||',
        '    Boolean(form.title.trim() && form.name.trim() && form.description.trim()),',
      ].join('\n'),
      '() => true,',
    ),
    'wizard identity progression must guard trimmed title, name, and description',
  )
  for (const [name, removedAttribute] of [
    ['tool-consumer-wizard-width', '      width="1180px"\n'],
    ['tool-consumer-wizard-top', '      top="3vh"\n'],
    ['tool-consumer-wizard-append', '      append-to-body\n'],
    ['tool-consumer-wizard-loading', '      :loading="saving"\n'],
  ]) {
    await runToolConsumerMutationProof(
      name,
      (source) => source.replace(removedAttribute, ''),
      'WizardDialog must own controlled progression plus width/top/append/loading passthrough',
    )
  }
  await runToolConsumerMutationProof(
    'tool-consumer-private-surface-recipe',
    (source) => source.replace(
      '.tool-editor-main,',
      '.tool-editor-summary { background: var(--surface-solid-control); }\n\n.tool-editor-main,',
    ),
    'must not own private surface, overlay, Element Plus, or backdrop recipes',
  )
  await runToolHeadMutationProof(
    'tool-head-switch-handler',
    (source) => source.replace(
      '@change="handleEnabledChange(row, $event as boolean)"',
      '@change="handleEnabledChange(row, !$event as boolean)"',
    ),
    [
      'must preserve the current Tool title, scope, operations, and runtime-control contract',
      'must preserve HEAD main Tool table subtree semantics',
    ],
  )
  await runToolHeadMutationProof(
    'tool-head-table-action-handler',
    (source) => source.replace(
      '@click.stop="openEditDialog(row)"',
      '@click.stop="openTest(row)"',
    ),
    [
      'must preserve the current Tool title, scope, operations, and runtime-control contract',
      'must preserve HEAD main Tool table subtree semantics',
    ],
  )
  await runToolHeadMutationProof(
    'tool-head-test-footer-handler',
    (source) => source.replace('@click="handleTest"', '@click="handleSearch"'),
    'must preserve HEAD test dialog default and footer subtree semantics',
  )
  await runToolHeadMutationProof(
    'tool-head-test-footer-loading',
    (source) => source.replace(':loading="testRunning"', ':loading="loading"'),
    'must preserve HEAD test dialog default and footer subtree semantics',
  )
  await runToolHeadMutationProof(
    'tool-head-computed-initializer',
    (source) => source.replace(
      'const isEditMode = computed(() => editingName.value !== null)',
      'const isEditMode = computed(() => editingName.value === null)',
    ),
    'must preserve HEAD variable initializers; changed or missing: isEditMode',
  )
  await runToolHeadMutationProof(
    'tool-head-unauthorized-added-initializer',
    (source) => source.replace(
      /const route = useRoute\(\)\r?\n/,
      'const route = useRoute()\nconst unapprovedStartupFetch = fetchTools()\n',
    ),
    'must preserve the approved Tool variable initializer set',
  )
  await runToolHeadMutationProof(
    'tool-head-on-mounted-callback',
    (source) => source.replace(
      /  syncProjectScope\(true\)\r?\n  fetchTools\(\)/,
      '  syncProjectScope(true)\n  pagination.current = 1\n  fetchTools()',
    ),
    'must preserve HEAD onMounted and watch call/callback semantics',
  )
  await runToolHeadMutationProof(
    'tool-head-watch-callback',
    (source) => source.replace(
      /  \(\) => \{\r?\n    syncProjectScope\(\)\r?\n    pagination\.current = 1\r?\n    fetchTools\(\)\r?\n  \},\r?\n\)/,
      '  () => {\n    syncProjectScope()\n    pagination.current = 2\n    fetchTools()\n  },\n)',
    ),
    'must preserve HEAD onMounted and watch call/callback semantics',
  )

  await runMutationProof(
    'remove-glass-surface',
    (source) => source.replace(' glass-surface-panel', ''),
    'must include a glass-surface-* class',
  )
  await runMutationProof(
    'hard-coded-color',
    (source) => source.replace('var(--text-primary)', '#111827'),
    'compiled style 1 must not contain color literal #111827',
  )
  await runMutationProof(
    'backdrop-filter',
    (source) =>
      source.replace(
        'color: var(--text-primary);',
        'color: var(--text-primary);\n  backdrop-filter: blur(20px);',
      ),
    'compiled style 1 must not declare backdrop-filter',
  )
  await runMutationProof(
    'remove-density',
    (source) => source.replace(' density-comfortable', ''),
    'must include a density class',
  )
  await runMutationProof(
    'data-glass-surface',
    (source) =>
      source
        .replace(' glass-surface-panel', '')
        .replace('<section class=', '<section data-recipe="glass-surface-panel" class='),
    'must include a glass-surface-* class',
  )

  const rawElementCardConsumer = syntheticConsumer
    .replace(
      "import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'",
      "import { ElCard } from 'element-plus'",
    )
    .replace('<WorkbenchPanel>', '<el-card>')
    .replace('</WorkbenchPanel>', '</el-card>')
  const consumerMutationFailures = validateRepresentativeConsumer(
    rawElementCardConsumer,
    'mutation:raw-el-card',
  )
  const expectedConsumerFailure =
    'mutation:raw-el-card must import and render WorkbenchPanel instead of raw el-card'
  if (
    consumerMutationFailures.length !== 1 ||
    consumerMutationFailures[0] !== expectedConsumerFailure
  ) {
    failures.push(
      `mutation proof raw-el-card expected exactly "${expectedConsumerFailure}"; got ${consumerMutationFailures.join(' | ') || '(none)'}`,
    )
  } else {
    mutationProofCount += 1
    console.log(
      'Mutation proof raw-el-card: must import and render WorkbenchPanel instead of raw el-card',
    )
  }

  const commentedConsumer = syntheticConsumer.replace(
    '<WorkbenchPanel>Representative content</WorkbenchPanel>',
    '<!-- <WorkbenchPanel>Representative content</WorkbenchPanel> -->',
  )
  const commentedConsumerFailures = validateRepresentativeConsumer(
    commentedConsumer,
    'mutation:commented-workbench-panel',
  )
  const expectedCommentedConsumerFailure =
    'mutation:commented-workbench-panel must import and render WorkbenchPanel instead of raw el-card'
  if (
    commentedConsumerFailures.length !== 1 ||
    commentedConsumerFailures[0] !== expectedCommentedConsumerFailure
  ) {
    failures.push(
      `mutation proof commented-workbench-panel expected exactly "must import and render WorkbenchPanel instead of raw el-card"; got ${commentedConsumerFailures.join(' | ') || '(none)'}`,
    )
  } else {
    mutationProofCount += 1
    console.log(
      'Mutation proof commented-workbench-panel: must import and render WorkbenchPanel instead of raw el-card',
    )
  }

  await runContractMutationProof(
    'remove-density-type-member',
    syntheticGlassWorkbench,
    validateGlassWorkbench,
    (source) => source.replace(" | 'spacious'", ''),
    "must export WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'",
  )
  await runContractMutationProof(
    'change-density-helper',
    syntheticGlassWorkbench,
    validateGlassWorkbench,
    (source) => source.replace('return `density-${density}`', 'return `size-${density}`'),
    'densityClass must map WorkbenchDensity to `density-${WorkbenchDensity}`',
  )
  await runContractMutationProof(
    'page-header-remove-prop',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('  title: string\n', ''),
    'must declare prop title: string',
  )
  await runContractMutationProof(
    'page-header-remove-slot',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace(' name="leading"', ''),
    'must expose leading slot',
  )
  await runContractMutationProof(
    'page-header-remove-emit',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('const emit = defineEmits<{ back: [] }>()', 'const emit = () => undefined'),
    'must declare back emit',
  )
  await runContractMutationProof(
    'page-header-remove-root-recipe',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace("'glass-surface-panel', ", ''),
    'root must include glass-surface-panel recipe',
  )
  await runContractMutationProof(
    'page-header-remove-density-binding',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace(', densityClass(props.density)', ''),
    'root must apply densityClass(props.density)',
  )
  await runContractMutationProof(
    'workbench-panel-remove-prop',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) => source.replace("  level?: 'panel' | 'control'\n", ''),
    "must declare prop level?: 'panel' | 'control'",
  )
  await runContractMutationProof(
    'workbench-panel-remove-slot',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) => source.replace(' name="footer"', ''),
    'must expose footer slot',
  )
  await runContractMutationProof(
    'workbench-panel-remove-root-recipe',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) => source.replace("'glass-surface-control'", "'glass-surface-panel'"),
    'surfaceClass must exclusively map control to glass-surface-control and panel to glass-surface-panel',
  )
  await runContractMutationProof(
    'workbench-panel-remove-density-binding',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) => source.replace(', densityClass(props.density)', ''),
    'root must apply densityClass(props.density)',
  )
  await runContractMutationProof(
    'page-header-commented-prop',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('  title: string\n', '  /*\n  title: string\n  */\n'),
    'must declare prop title: string',
  )
  await runContractMutationProof(
    'page-header-commented-slot',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('<slot name="leading" />', '<!-- <slot name="leading" /> -->'),
    'must expose leading slot',
  )
  await runContractMutationProof(
    'page-header-commented-emit',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source.replace(
        'const emit = defineEmits<{ back: [] }>()',
        'const emit = () => undefined\n/* defineEmits<{ back: [] }>() */',
      ),
    'must declare back emit',
  )
  await runContractMutationProof(
    'page-header-commented-click',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source
        .replace('      @click="emit(\'back\')"\n', '')
        .replace('      Back', '      <!-- @click="emit(\'back\')" -->\n      Back'),
    'back button must emit back on click',
  )
  await runContractMutationProof(
    'page-header-data-root-recipe',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source
        .replace("'glass-surface-panel', ", '')
        .replace('<header :class=', '<header data-recipe="glass-surface-panel" :class='),
    'root must include glass-surface-panel recipe',
  )
  await runContractMutationProof(
    'page-header-data-density-binding',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source
        .replace(', densityClass(props.density)', '')
        .replace('<header :class=', '<header data-density="densityClass(props.density)" :class='),
    'root must apply densityClass(props.density)',
  )
  await runContractMutationProof(
    'workbench-panel-dead-recipe-ternary',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        "const surfaceClass = computed(() => props.level === 'control' ? 'glass-surface-control' : 'glass-surface-panel')",
        "const surfaceClass = computed(() => 'glass-surface-panel')\nconst deadRecipe = false ? (props.level === 'control' ? 'glass-surface-control' : 'glass-surface-panel') : ''",
      ),
    'surfaceClass must exclusively map control to glass-surface-control and panel to glass-surface-panel',
  )
  await runContractMutationProof(
    'workbench-panel-dual-root-recipes',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        "['workbench-panel', surfaceClass, densityClass(props.density)]",
        "['workbench-panel', 'glass-surface-panel', 'glass-surface-control', surfaceClass, densityClass(props.density)]",
      ),
    'root must bind only surfaceClass as its glass recipe',
  )
  await runContractMutationProof(
    'workbench-panel-data-footer-structure',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        'class="workbench-panel__footer"',
        'data-contract="workbench-panel__footer"',
      ),
    'must expose coherent footer structure',
  )
  await runContractMutationProof(
    'page-header-private-css-variable',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source.replace(
        'padding: var(--layout-page-header-padding-block) var(--layout-page-header-padding-inline);',
        'padding: var(--layout-page-header-padding-block) var(--layout-page-header-padding-inline);\n  color: var(--page-header-private-text);',
      ),
    'compiled style 1 references undeclared CSS variable --page-header-private-text',
  )
  await runContractMutationProof(
    'workbench-panel-private-css-variable',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        'padding: var(--panel-padding);',
        'padding: var(--panel-padding);\n  color: var(--workbench-panel-private-text);',
      ),
    'compiled style 1 references undeclared CSS variable --workbench-panel-private-text',
  )
  await runContractMutationProof(
    'page-header-remove-show-back-prop',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('  showBack?: boolean\n', ''),
    'must declare prop showBack?: boolean',
  )
  await runContractMutationProof(
    'page-header-remove-show-back-default',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('  showBack: false,\n', ''),
    'showBack must default to false',
  )
  await runContractMutationProof(
    'page-header-ignore-show-back-condition',
    syntheticPageHeader,
    validatePageHeader,
    (source) => source.replace('v-if="props.showBack"', 'v-if="true"'),
    'back button must render from props.showBack',
  )
  await runContractMutationProof(
    'workbench-panel-extra-root-class-object',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        'densityClass(props.density)]',
        "densityClass(props.density), { 'glass-surface-control': true }]",
      ),
    'root class binding must contain only workbench-panel, surfaceClass, and densityClass(props.density)',
  )
  await runContractMutationProof(
    'page-header-unreachable-leading-slot',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source.replace(
        '<slot name="leading" />',
        '<template v-if="false"><slot name="leading" /></template>',
      ),
    'must expose leading slot',
  )
  await runContractMutationProof(
    'page-header-unreachable-back-button',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source
        .replace('    <button\n', '    <template v-if="false">\n    <button\n')
        .replace('    </button>\n', '    </button>\n    </template>\n'),
    'back button must be reachable',
  )
  await runContractMutationProof(
    'page-header-unreachable-conditional-slot',
    syntheticPageHeader,
    validatePageHeader,
    (source) =>
      source.replace(
        '<slot name="tags" />',
        '<template v-if="true" /><template v-else-if="true"><slot name="tags" /></template>',
      ),
    'must expose tags slot',
  )
  await runContractMutationProof(
    'workbench-panel-unreachable-header',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        '<header class="workbench-panel__header">',
        '<header><div v-if="undefined" class="workbench-panel__header" />',
      ),
    'must expose coherent header structure',
  )
  await runContractMutationProof(
    'workbench-panel-unreachable-body',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        '<div class="workbench-panel__body"><slot /></div>',
        '<div><div v-if="0" class="workbench-panel__body" /><slot /></div>',
      ),
    'must expose coherent body structure',
  )
  await runContractMutationProof(
    'workbench-panel-unreachable-footer',
    syntheticWorkbenchPanel,
    validateWorkbenchPanel,
    (source) =>
      source.replace(
        '<footer class="workbench-panel__footer">',
        '<footer><div v-if="null" class="workbench-panel__footer" />',
      ),
    'must expose coherent footer structure',
  )
  await runContractMutationProof(
    'filter-bar-prop-shape',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace('  loading?: boolean\n', '  loading?: string\n'),
    'must declare exactly density/presentation/loading/showReset/queryLabel/resetLabel props',
  )
  await runContractMutationProof(
    'filter-bar-default-slot-ownership',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace(
      '<div class="filter-bar__fields"><slot /></div>',
      '<div class="filter-bar__fields" /><slot />',
    ),
    'filter-bar__fields must directly own the default slot',
  )
  await runContractMutationProof(
    'filter-bar-submit-navigation',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace('@submit.prevent="emit(\'query\')"', '@submit="emit(\'query\')"'),
    'form submit must prevent navigation and emit query',
  )
  await runContractMutationProof(
    'filter-bar-wrong-surface',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace("'glass-surface-control'", "'glass-surface-panel'"),
    'root must bind only filter-bar, glass-surface-control, and densityClass(props.density)',
  )
  await runContractMutationProof(
    'filter-bar-two-primary-actions',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace('          native-type="button"', '          type="primary"\n          native-type="button"'),
    'default actions must contain Reset and Query with exactly one primary button',
  )
  await runContractMutationProof(
    'filter-bar-page-scoped-element-skin',
    syntheticFilterBar,
    validateFilterBar,
    (source) => source.replace(
      '</style>',
      ':deep(.el-button) { color: var(--text-primary); }\n</style>',
    ),
    'must not contain page-scoped Element Plus skin',
  )
  for (const [name, source, validate, alternate, propFailure, expectedUnion] of [
    ['page-header', syntheticPageHeader, validatePageHeader, 'plain', 'must declare prop presentation?: panel,plain', "'panel' | 'plain'"],
    ['filter-bar', syntheticFilterBar, validateFilterBar, 'inline', 'must declare exactly density/presentation/loading/showReset/queryLabel/resetLabel props', "'panel' | 'inline'"],
    ['metric-strip', syntheticMetricStrip, validateMetricStrip, 'inline', 'must declare prop presentation?: panel,inline', "'panel' | 'inline'"],
  ]) {
    await runPassingContractVariantProof(
      `${name}-presentation-expression-parentheses`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        ':data-presentation="props.presentation"',
        ':data-presentation="(props.presentation)"',
        `${name} presentation safe expression variant`,
      ),
    )
    await runContractMutationProof(
      `${name}-presentation-widened-type`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        `presentation?: ${expectedUnion}`,
        `presentation?: ${expectedUnion} | 'hidden'`,
        `${name} presentation widened type mutation`,
      ),
      propFailure,
    )
    await runContractMutationProof(
      `${name}-presentation-wrong-default`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        "presentation: 'panel'",
        `presentation: '${alternate}'`,
        `${name} presentation default mutation`,
      ),
      name === 'filter-bar' ? 'presentation must use its shared default' : "presentation must default to 'panel'",
    )
    await runContractMutationProof(
      `${name}-presentation-static-root`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        ':data-presentation="props.presentation"',
        'data-presentation="panel"',
        `${name} presentation binding mutation`,
      ),
      'root must bind props.presentation to data-presentation',
    )
  }
  for (const [name, source, validate, className] of [
    ['filter-bar', syntheticFilterBar, validateFilterBar, 'filter-bar'],
    ['metric-strip', syntheticMetricStrip, validateMetricStrip, 'metric-strip'],
  ]) {
    const inlineSelector = `.${className}[data-presentation='inline']`
    await runPassingContractVariantProof(
      `${name}-inline-reset-selector-double-quotes`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        inlineSelector,
        `.${className}[data-presentation="inline"]`,
        `${name} inline reset selector safe quote variant`,
      ),
    )
    for (const [suffix, replacement] of [
      ['default-root', `.${className}`],
      ['panel-root', `.${className}[data-presentation='panel']`],
      ['descendant', `${inlineSelector} .${className}__content`],
    ]) {
      await runContractMutationProof(
        `${name}-inline-reset-${suffix}`,
        source,
        validate,
        (candidate) => replaceRequired(candidate, inlineSelector, replacement, `${name} reset ${suffix} mutation`),
        [
          'compiled style 1 must not contain literal box-shadow: none',
          ...(name === 'filter-bar'
            ? ['must leave box-shadow ownership to the glass recipe']
            : [
                'compiled style 1 must not declare backdrop-filter',
                ...(suffix === 'default-root' ? ['root must own exactly border-radius: var(--radius-lg)'] : []),
              ]),
        ],
      )
    }
    await runContractMutationProof(
      `${name}-inline-reset-restored-shadow`,
      source,
      validate,
      (candidate) => replaceRequired(
        candidate,
        '  box-shadow: none;',
        '  box-shadow: 0 1px 4px currentColor;',
        `${name} inline reset shadow mutation`,
      ),
      [
        'compiled style 1 must not contain literal box-shadow: 0 1px 4px currentColor',
        ...(name === 'filter-bar' ? ['must leave box-shadow ownership to the glass recipe'] : []),
      ],
    )
  }
  await runContractMutationProof(
    'metric-strip-inline-reset-restored-backdrop-filter',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => replaceRequired(
      source,
      '  backdrop-filter: none;',
      '  backdrop-filter: blur(12px);',
      'MetricStrip inline reset backdrop mutation',
    ),
    'compiled style 1 must not declare backdrop-filter',
  )
  await runPassingContractVariantProof(
    'metric-strip-inline-icon-safe-condition',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => replaceRequired(
      source,
      '<MetricIconBg :icon-key=',
      '<MetricIconBg v-if="props.presentation !== \'inline\'" :icon-key=',
      'MetricStrip inline icon condition safe variant',
    ),
  )
  for (const [name, condition] of [
    ['hidden-icon', 'false'],
    ['reversed-icon', "props.presentation === 'inline'"],
    ['unrelated-icon', "props.density !== 'compact'"],
  ]) {
    await runContractMutationProof(
      `metric-strip-inline-${name}`,
      syntheticMetricStrip,
      validateMetricStrip,
      (source) => replaceRequired(
        source,
        '<MetricIconBg :icon-key=',
        `<MetricIconBg v-if="${condition}" :icon-key=`,
        `MetricStrip inline ${name} mutation`,
      ),
      'each item must render MetricIconBg from item.iconKey and item.tone',
    )
  }
  await runContractMutationProof(
    'data-table-shell-prop-shape',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('  pageSizes?: number[]\n', '  pageSizes?: string\n'),
    'must declare exactly the controlled table-shell props',
  )
  await runContractMutationProof('data-table-shell-persistent-body-default', syntheticDataTableShell, validateDataTableShell,
    source => replaceRequired(source, 'preserveEmptyBody: false', 'preserveEmptyBody: true', 'persistent body default'),
    'persistent empty body must default to false')
  await runContractMutationProof('data-table-shell-persistent-body-binding', syntheticDataTableShell, validateDataTableShell,
    source => replaceRequired(source, "{ 'data-table-shell--preserve-empty-body': props.preserveEmptyBody }", "{ 'data-table-shell--preserve-empty-body': props.empty }", 'persistent body binding'),
    'root must bind only data-table-shell, one glass-surface-panel, densityClass(props.density), and is-empty for empty && !loading')
  await runContractMutationProof(
    'data-table-shell-raw-table',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('<div class="data-table-shell__body"><slot /></div>', '<div class="data-table-shell__body"><slot /><el-table /></div>'),
    'must never create an el-table',
  )
  await runContractMutationProof(
    'data-table-shell-loading-empty',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('v-if="props.empty && !props.loading"', 'v-if="props.empty"'),
    'empty region must render its slot/fallback only for empty && !loading',
  )
  await runContractMutationProof(
    'data-table-shell-pagination-gate',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('v-if="$slots.pagination || props.total > 0"', 'v-if="props.total > 0"'),
    'pagination slot must replace a total-gated default pagination',
  )
  await runContractMutationProof(
    'data-table-shell-page-forwarding',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace("emit('pageChange', page)", "emit('sizeChange', page)"),
    'handlePageChange must forward model and semantic page events',
  )
  await runContractMutationProof(
    'data-table-shell-extra-glass-recipe',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('</section>', '<span class="glass-surface-control" /></section>'),
    'must own exactly one glass-surface-panel recipe',
  )
  for (const [name, needle, replacement] of [
    ['data-table-shell-extra-root-class', "'data-table-shell',", "'data-table-shell', 'rogue-class',"],
    ['data-table-shell-duplicate-root-recipe', "'glass-surface-panel',", "'glass-surface-panel', 'glass-surface-panel',"],
    ['data-table-shell-wrong-empty-class', "{ 'is-empty': props.empty && !props.loading }", "{ 'is-empty': props.empty }"],
    ['data-table-shell-loading-empty-class', "{ 'is-empty': props.empty && !props.loading }", "{ 'is-empty': props.empty && props.loading }"],
  ]) {
    await runContractMutationProof(name, syntheticDataTableShell, validateDataTableShell,
      source => replaceRequired(source, needle, replacement, name),
      name === 'data-table-shell-duplicate-root-recipe'
        ? ['root must bind only data-table-shell, one glass-surface-panel, densityClass(props.density), and is-empty for empty && !loading', 'must own exactly one glass-surface-panel recipe']
        : 'root must bind only data-table-shell, one glass-surface-panel, densityClass(props.density), and is-empty for empty && !loading')
  }
  await runPassingContractVariantProof('data-table-shell-conditional-class-order', syntheticDataTableShell, validateDataTableShell,
    source => replaceRequired(source, "densityClass(props.density), { 'is-empty': props.empty && !props.loading }",
      "{ 'is-empty': props.empty && !props.loading }, densityClass(props.density)", 'DataTableShell safe conditional class reorder'))
  await runContractMutationProof(
    'app-dialog-prop-shape',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('  width?: string | number\n', '  width?: string\n'),
    'must declare exactly modelValue/title/description/width props',
  )
  await runContractMutationProof(
    'app-drawer-prop-shape',
    syntheticAppDrawer,
    validateAppDrawer,
    (source) => source.replace('  size?: string | number\n', '  size?: number\n'),
    'must declare exactly modelValue/title/description/size props',
  )
  await runContractMutationProof(
    'overlay-exact-emits',
    syntheticAppDialog,
    validateAppDialog,
    (source) =>
      source.replace(
        "  'update:modelValue': [value: boolean]\n",
        "  'update:modelValue': [value: boolean]\n  open: []\n",
      ),
    'must declare only update:modelValue(boolean)',
  )
  await runContractMutationProof(
    'overlay-inherit-attrs',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('defineOptions({ inheritAttrs: false })', 'defineOptions({ inheritAttrs: true })'),
    'must call defineOptions({ inheritAttrs: false })',
  )
  await runContractMutationProof(
    'overlay-commented-inherit-attrs',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('defineOptions({ inheritAttrs: false })', '/* defineOptions({ inheritAttrs: false }) */'),
    'must call defineOptions({ inheritAttrs: false })',
  )
  await runContractMutationProof(
    'app-dialog-root-kind',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('<el-dialog', '<el-drawer').replace('</el-dialog>', '</el-drawer>'),
    'root must be el-dialog',
  )
  await runContractMutationProof(
    'app-drawer-root-kind',
    syntheticAppDrawer,
    validateAppDrawer,
    (source) => source.replace('<el-drawer', '<el-dialog').replace('</el-drawer>', '</el-dialog>'),
    'root must be el-drawer',
  )
  await runContractMutationProof(
    'overlay-data-attrs-forwarding',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('    v-bind="$attrs"', '    data-attrs-contract="$attrs"'),
    'must bind $attrs directly to the Element Plus root',
  )
  await runContractMutationProof(
    'overlay-attrs-spread-modifier',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('v-bind="$attrs"', 'v-bind.prop="$attrs"'),
    'must bind $attrs directly to the Element Plus root',
  )
  await runContractMutationProof(
    'overlay-direct-model-update',
    syntheticAppDialog,
    validateAppDialog,
    (source) =>
      source.replace(
        "@update:model-value=\"emit('update:modelValue', $event)\"",
        "@update:model-value=\"emit('update:modelValue', Boolean($event))\"",
      ),
    'must forward the root model update directly',
  )
  await runContractMutationProof(
    'overlay-model-update-once-modifier',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('@update:model-value=', '@update:model-value.once='),
    'must forward the root model update directly',
  )
  await runContractMutationProof(
    'overlay-model-update-stop-modifier',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('@update:model-value=', '@update:model-value.stop='),
    'must forward the root model update directly',
  )
  await runContractMutationProof(
    'overlay-title-attr-modifier',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace(':title="props.title"', ':title.attr="props.title"'),
    'must forward modelValue/title/width props directly',
  )
  await runContractMutationProof(
    'overlay-dead-source-model-update',
    syntheticAppDialog,
    validateAppDialog,
    (source) =>
      source
        .replace(
          'defineOptions({ inheritAttrs: false })',
          '/* dead update:modelValue source text */\ndefineOptions({ inheritAttrs: false })',
        )
        .replace("    @update:model-value=\"emit('update:modelValue', $event)\"\n", ''),
    'must forward the root model update directly',
  )
  await runContractMutationProof(
    'overlay-header-slot-props',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('name="header" v-bind="headerProps"', 'name="header" data-props="headerProps"'),
    'header slot must conditionally forward all Element Plus header props',
  )
  await runContractMutationProof(
    'overlay-header-slot-spread-modifier',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('v-bind="headerProps"', 'v-bind.prop="headerProps"'),
    'header slot must conditionally forward all Element Plus header props',
  )
  await runContractMutationProof(
    'overlay-exact-slots',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('<slot name="footer" />', '<slot name="after" />'),
    'must expose exactly header/default/footer slots',
  )
  await runContractMutationProof(
    'overlay-unreachable-header-slot',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('v-if="$slots.header" #header', 'v-if="false" #header'),
    'must expose exactly header/default/footer slots',
  )
  await runContractMutationProof(
    'overlay-extra-glass-recipe',
    syntheticAppDialog,
    validateAppDialog,
    (source) =>
      source.replace(
        'class="app-dialog glass-surface-overlay"',
        'class="app-dialog glass-surface-overlay glass-surface-control"',
      ),
    'root must own exactly one glass-surface-overlay recipe',
  )
  await runContractMutationProof(
    'overlay-semantic-description',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('      {{ props.description }}\n', ''),
    'must render semantic optional description text',
  )
  await runContractMutationProof(
    'overlay-semantic-divider',
    syntheticAppDialog,
    validateAppDialog,
    (source) => source.replace('    <hr v-if="props.description" class="app-dialog__divider" />\n', ''),
    'must render a semantic description divider',
  )
  for (const [name, mutate] of [
    [
      'overlay-homegrown-teleport',
      (source) => source.replace('    <slot />', '    <Teleport /><slot />'),
    ],
    [
      'overlay-homegrown-close-button',
      (source) => source.replace('    <slot />', '    <button type="button">Close</button><slot />'),
    ],
    [
      'overlay-homegrown-escape',
      (source) => source.replace('    :width="props.width"', '    :width="props.width"\n    @keydown.esc="emit(\'update:modelValue\', false)"'),
    ],
    [
      'overlay-homegrown-mask',
      (source) => source.replace('    :width="props.width"', '    :width="props.width"\n    :modal="false"'),
    ],
    [
      'overlay-homegrown-title-id',
      (source) => source.replace('class="app-dialog__description"', 'id="custom-overlay-title" class="app-dialog__description"'),
    ],
    [
      'overlay-lifecycle-re-emission',
      (source) =>
        source.replace(
          '</script>',
          "\n;(emit as any)('open')\n</script>",
        ),
    ],
  ]) {
    await runContractMutationProof(
      name,
      syntheticAppDialog,
      validateAppDialog,
      mutate,
      'must leave mask, teleport, Escape, focus trap, close, title IDs, and lifecycle behavior to Element Plus',
    )
  }
  for (const [name, mutate] of [
    [
      'overlay-root-focus-lock-directive',
      (source) => source.replace('    v-bind="$attrs"', '    v-bind="$attrs"\n    v-focus-lock'),
    ],
    [
      'overlay-inner-escape-handler',
      (source) =>
        source.replace(
          'class="app-dialog__description"',
          'class="app-dialog__description" tabindex="0" @keydown.esc="$emit(\'update:modelValue\', false)"',
        ),
    ],
    [
      'overlay-inner-close-control',
      (source) =>
        source.replace(
          'class="app-dialog__description"',
          'class="app-dialog__description" role="button" @click="$emit(\'update:modelValue\', false)"',
        ),
    ],
    [
      'overlay-inline-fixed-mask',
      (source) =>
        source.replace(
          'class="app-dialog__description"',
          'class="app-dialog__description" style="position: fixed; inset: 0; background: var(--surface-solid-overlay)"',
        ),
    ],
    [
      'overlay-nested-object-style-bind',
      (source) =>
        source.replace(
          'class="app-dialog__description"',
          'class="app-dialog__description" v-bind="{ style: { position: \'fixed\', inset: 0, background: \'var(--surface-solid-overlay)\' } }"',
        ),
    ],
    [
      'overlay-nested-object-event-bind',
      (source) =>
        source.replace(
          'class="app-dialog__description"',
          'class="app-dialog__description" v-bind="{ onKeydown: (event) => event.keyCode === 27 && $emit(\'update:modelValue\', false) }"',
        ),
    ],
    [
      'overlay-script-focus-trap',
      (source) =>
        source.replace(
          '</script>',
          '\ndocument.onkeydown = (event) => { if (event.keyCode === 9) { event.preventDefault(); document.body.focus() } }\n</script>',
        ),
    ],
    [
      'overlay-lifecycle-interpolation',
      (source) => source.replace('    <slot />', "    {{ $emit('open') }}\n    <slot />"),
    ],
    [
      'overlay-focus-trap-interpolation',
      (source) =>
        source.replace(
          '    <slot />',
          "    {{ (document.onkeydown = (event) => event.keyCode === 9 && document.body.focus(), '') }}\n    <slot />",
        ),
    ],
    [
      'overlay-extra-side-effect-element',
      (source) =>
        source.replace(
          '    <slot />',
          '    <p v-if="(sideEffect(), false)" class="app-dialog__description">Hidden</p>\n    <slot />',
        ),
    ],
    [
      'overlay-extra-nested-drawer',
      (source) => source.replace('    <slot />', '    <el-drawer />\n    <slot />'),
    ],
  ]) {
    await runContractMutationProof(
      name,
      syntheticAppDialog,
      validateAppDialog,
      mutate,
      'must leave mask, teleport, Escape, focus trap, close, title IDs, and lifecycle behavior to Element Plus',
    )
  }
  for (const [name, mutate] of [
    [
      'overlay-private-fixed-layer',
      (source) => source.replace('  margin: 0;', '  margin: 0;\n  position: fixed;'),
    ],
    [
      'overlay-private-token-shadow',
      (source) => source.replace('  margin: 0;', '  margin: 0;\n  box-shadow: var(--shadow-overlay);'),
    ],
    [
      'overlay-page-scoped-element-skin',
      (source) => source.replace('</style>', ':deep(.el-dialog) { color: var(--text-primary); }\n</style>'),
    ],
    [
      'overlay-private-root-surface-recipe',
      (source) =>
        source.replace(
          '</style>',
          '.app-dialog { background: var(--surface-solid-overlay); border: 1px solid var(--border-readable); }\n</style>',
        ),
    ],
    [
      'overlay-private-root-background-image',
      (source) =>
        source.replace(
          '</style>',
          '.app-dialog { background-image: linear-gradient(var(--surface-solid-overlay), var(--surface-solid-control)); }\n</style>',
        ),
    ],
    [
      'overlay-private-root-element-variable',
      (source) =>
        source.replace(
          '</style>',
          '.app-dialog { --el-dialog-bg-color: var(--surface-solid-overlay); }\n</style>',
        ),
    ],
    [
      'overlay-private-root-drop-shadow',
      (source) =>
        source.replace(
          '</style>',
          '.app-dialog { filter: drop-shadow(0 2px 4px var(--border-readable)); }\n</style>',
        ),
    ],
    [
      'overlay-selector-list-element-skin',
      (source) =>
        source.replace(
          '.app-dialog__description {',
          '.app-dialog__description, :deep(.el-dialog) {',
        ),
    ],
  ]) {
    await runContractMutationProof(
      name,
      syntheticAppDialog,
      validateAppDialog,
      mutate,
      'must not own a fixed overlay, blur, shadow, Element Plus skin, or private glass recipe',
    )
  }
  await runContractMutationProof(
    'wizard-step-state-union',
    syntheticGlassWorkbench,
    validateGlassWorkbench,
    (source) => source.replace(" | 'skipped'", ''),
    "must export WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'",
  )
  await runContractMutationProof(
    'wizard-step-interface-shape',
    syntheticGlassWorkbench,
    validateGlassWorkbench,
    (source) => source.replace('  disabled?: boolean\n', ''),
    'must export the exact WizardStep key/title/description/state/disabled interface',
  )
  await runContractMutationProof(
    'wizard-prop-shape',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('    activeStep: number\n', '    activeStep: string\n'),
    'must declare exactly the controlled WizardDialog props',
  )
  await runContractMutationProof(
    'wizard-default-navigable',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('    navigable: false,', '    navigable: true,'),
    'navigable must use its WizardDialog default',
  )
  await runContractMutationProof(
    'wizard-exact-emits',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('  finish: []\n', '  finish: []\n  save: []\n'),
    'must declare exactly the controlled wizard emits',
  )
  await runContractMutationProof(
    'wizard-commented-app-dialog-root',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source
        .replace('  <AppDialog\n', '  <!-- <AppDialog /> -->\n  <AppShell\n')
        .replace('  </AppDialog>\n', '  </AppShell>\n'),
    'root must be the sole AppDialog overlay',
  )
  await runContractMutationProof(
    'wizard-direct-model-update',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        "@update:model-value=\"emit('update:modelValue', $event)\"",
        "@update:model-value=\"emit('update:modelValue', Boolean($event))\"",
      ),
    'must forward AppDialog model updates directly',
  )
  await runContractMutationProof(
    'wizard-spacious-density',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace("densityClass('spacious')", "densityClass('comfortable')"),
    "composition must apply densityClass('spacious') directly under AppDialog",
  )
  await runContractMutationProof(
    'wizard-summary-rail-ownership',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source
        .replace(
          `        <div v-if="$slots.summary" class="wizard-dialog__summary">
          <slot name="summary" />
        </div>
`,
          '',
        )
        .replace('      </aside>', '      </aside>\n      <slot name="summary" />'),
    'rail must own the summary slot and exactly one glass-surface-control recipe',
  )
  await runContractMutationProof(
    'wizard-ordered-rail-aria-data',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('aria-label="步骤"', 'data-aria-label="步骤"'),
    'must render one keyed ordered item per props.steps entry',
  )
  await runContractMutationProof(
    'wizard-ordered-items-source',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('v-for="(step, index) in props.steps"', 'v-for="(step, index) in []"'),
    'must render one keyed ordered item per props.steps entry',
  )
  await runContractMutationProof(
    'wizard-ordered-items-extra-li',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('        </ol>', '          <li>Extra step</li>\n        </ol>'),
    'must render one keyed ordered item per props.steps entry',
  )
  await runContractMutationProof(
    'wizard-active-aria-data',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        ':aria-current="index === props.activeStep ? \'step\' : undefined"',
        'data-aria-current="index === props.activeStep ? \'step\' : undefined"',
      ),
    'step item must expose active aria-current and semantic state/tone classes',
  )
  await runContractMutationProof(
    'wizard-state-class-data',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        "              `wizard-dialog__step--state-${step.state}`,\n",
        '              /* data-state owns no class */\n',
      ),
    'step item must expose active aria-current and semantic state/tone classes',
  )
  await runContractMutationProof(
    'wizard-step-disabled-guard',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(':disabled="!props.navigable || step.disabled"', ':disabled="step.disabled"'),
    'step buttons must disable inaccessible steps and emit stepChange(index)',
  )
  await runContractMutationProof(
    'wizard-commented-step-click',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source
        .replace(
          '            <button\n',
          '            <!-- @click="emit(\'stepChange\', index)" -->\n            <button\n',
        )
        .replace('              @click="emit(\'stepChange\', index)"\n', ''),
    'step buttons must disable inaccessible steps and emit stepChange(index)',
  )
  await runContractMutationProof(
    'wizard-status-tone-map',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace("  error: 'danger',", "  error: 'warning',"),
    'step states must map through StatusTone semantic roles',
  )
  await runContractMutationProof(
    'wizard-current-brand-selection',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        `.wizard-dialog__step--state-current .wizard-dialog__step-button {
  border: 1px solid var(--border-focus);
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

`,
        '',
      ),
    'current step must use the brand-selected semantic surface',
  )
  await runContractMutationProof(
    'wizard-active-error-derivation',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace("step.state === 'error'", "step.state === 'current'"),
    'activeErrorStep must derive only the active error step',
  )
  await runContractMutationProof(
    'wizard-live-region-data-attribute',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('aria-live="polite"', 'data-live="polite"'),
    'polite live region must announce only the active error step title/description',
  )
  await runContractMutationProof(
    'wizard-live-region-invented-copy',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      '<div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">',
      '<div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">Validation failed',
    ),
    'polite live region must announce only the active error step title/description',
  )
  await runContractMutationProof(
    'wizard-live-region-extra-interpolation',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        '<div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">',
        '<div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">{{ props.title }}',
      ),
    'polite live region must announce only the active error step title/description',
  )
  await runContractMutationProof(
    'wizard-body-scroll-boundary',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('  overflow-y: auto;', '  overflow-y: hidden;'),
    'main body must own the default slot and a compiled scroll boundary',
  )
  await runContractMutationProof(
    'wizard-footer-slot-topology',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('<template #footer>', '<template #wizard-footer>'),
    'footer slot must replace a stable AppDialog footer outside the scroll body',
  )
  await runContractMutationProof(
    'wizard-footer-sibling-save',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        '        </div>\n      </slot>',
        '        </div>\n        <el-button type="primary" native-type="button" @click="emit(\'finish\')">保存</el-button>\n      </slot>',
      ),
    'default footer fallback must contain only the target footer container and its four actions',
  )
  await runContractMutationProof(
    'wizard-back-loading-guard',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      ':disabled="props.activeStep === 0 || props.loading"',
      ':disabled="props.activeStep === 0"',
    ),
    'default footer must contain only secondary Cancel and guarded Back actions',
  )
  await runContractMutationProof(
    'wizard-next-advance-guard',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      ':disabled="!props.canAdvance || props.loading"',
      ':disabled="!props.canAdvance"',
    ),
    'default footer must render mutually exclusive guarded primary Next or Finish',
  )
  await runContractMutationProof(
    'wizard-next-finish-simultaneous',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('            v-else\n            type="primary"', '            type="primary"'),
    'default footer must render mutually exclusive guarded primary Next or Finish',
  )
  await runContractMutationProof(
    'wizard-built-in-save-action',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('{{ props.finishLabel }}', '保存'),
    'default footer must render mutually exclusive guarded primary Next or Finish',
  )
  await runContractMutationProof(
    'wizard-cancel-close-effect',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace("@click=\"emit('cancel')\"", "@click=\"emit('update:modelValue', false)\""),
    'default footer must contain only secondary Cancel and guarded Back actions',
  )
  await runContractMutationProof(
    'wizard-internal-navigation-state',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      'function stepTone(state: WizardStepState): StatusTone {',
      'function mutateActiveStep() {\n  props.activeStep += 1\n}\n\nfunction stepTone(state: WizardStepState): StatusTone {',
    ),
    'must not own navigation state, close effects, mask, focus, Escape, or Teleport behavior',
  )
  for (const [name, mutate] of [
    [
      'wizard-unreachable-raw-dialog',
      (source) => source.replace(
        '    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
        '    <el-dialog v-if="false" />\n    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
      ),
    ],
    [
      'wizard-unreachable-teleport',
      (source) => source.replace(
        '    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
        '    <Teleport v-if="false" />\n    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
      ),
    ],
  ]) {
    await runContractMutationProof(
      name,
      syntheticWizardDialog,
      validateWizardDialog,
      mutate,
      'must not own navigation state, close effects, mask, focus, Escape, or Teleport behavior',
    )
  }
  await runContractMutationProof(
    'wizard-static-dynamic-dialog',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) =>
      source.replace(
        '    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
        '    <component is="el-dialog" />\n    <div :class="[\'wizard-dialog\', densityClass(\'spacious\')]">',
      ),
    'must not own navigation state, close effects, mask, focus, Escape, or Teleport behavior',
  )
  await runContractMutationProof(
    'wizard-page-scoped-element-skin',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('</style>', ':deep(.el-button) { color: var(--text-primary); }\n</style>'),
    'must not contain page-scoped Element Plus skin',
  )
  await runContractMutationProof(
    'wizard-private-rail-recipe',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      '  border-radius: var(--radius-lg);',
      '  border-radius: var(--radius-lg);\n  background: var(--surface-solid-control);',
    ),
    'must leave glass surface and overlay recipes to shared owners',
  )
  await runContractMutationProof(
    'wizard-raw-product-color',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace('  color: var(--text-secondary);', '  color: #111827;'),
    'compiled style 1 must not contain color literal #111827',
  )
  await runContractMutationProof(
    'wizard-extra-overlay-recipe',
    syntheticWizardDialog,
    validateWizardDialog,
    (source) => source.replace(
      'class="wizard-dialog__rail glass-surface-control"',
      'class="wizard-dialog__rail glass-surface-control glass-surface-overlay"',
    ),
    'rail must own the summary slot and exactly one glass-surface-control recipe',
  )
  await runContractMutationProof(
    'element-plus-pagination-mapping',
    syntheticElementPlusMappings,
    validateElementPlusMappings,
    (source) => source.replace(
      `.el-pagination {
  --el-fill-color-blank: var(--surface-solid-control);
  --el-pagination-button-color: var(--text-secondary);
  --el-pagination-button-disabled-color: var(--text-disabled);
  --el-pagination-button-disabled-bg-color: var(--surface-solid-disabled);
  --el-color-primary: var(--brand-primary);
  --el-border-color: var(--border-readable);
}

`,
      '',
    ),
    'must contain exactly one global .el-pagination mapping',
  )
  await runContractMutationProof(
    'element-plus-tabs-text-role',
    syntheticElementPlusMappings,
    validateElementPlusMappings,
    (source) => source.replace(
      '--el-text-color-regular: var(--text-secondary);',
      '--el-text-color-regular: var(--text-muted);',
    ),
    '.el-tabs must map --el-text-color-regular to var(--text-secondary)',
  )
  await runContractMutationProof(
    'element-plus-shared-focus-role',
    syntheticElementPlusMappings,
    validateElementPlusMappings,
    (source) => source.replace(
      'outline: 2px solid var(--border-focus);',
      'outline: 2px solid var(--brand-primary);',
    ),
    'pagination and tabs must share the semantic focus-visible ring',
  )
  await runContractMutationProof(
    'element-plus-low-specificity-pagination-focus',
    syntheticElementPlusMappings,
    validateElementPlusMappings,
    (source) => source.replace(
      '.el-pagination .btn-prev:focus-visible,\n.el-pagination .btn-next:focus-visible,',
      '.el-pagination button:focus-visible,',
    ),
    'pagination focus mapping must explicitly target btn-prev and btn-next at Element Plus specificity',
  )
  await runContractMutationProof(
    'metric-strip-item-missing-interface-member',
    syntheticGlassWorkbench,
    validateGlassWorkbench,
    (source) => source.replace('  hint?: string\n', ''),
    'must export the exact MetricStripItem key/label/value/hint/tone/iconKey interface',
  )
  await runContractMutationProof(
    'metric-strip-missing-icon',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => source.replace(
      '      <MetricIconBg :icon-key="item.iconKey" :tone="item.tone ?? \'brand\'" />\n',
      '',
    ),
    'each item must render MetricIconBg from item.iconKey and item.tone',
  )
  await runContractMutationProof(
    'metric-strip-independent-item-shadow',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => source.replace(
      '  border-inline-start: 1px solid var(--border-divider);',
      '  border-inline-start: 1px solid var(--border-divider);\n  box-shadow: var(--shadow-panel);',
    ),
    'items must not own an independent shadow/card glass recipe',
  )
  await runContractMutationProof(
    'metric-strip-missing-aria-label',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => source.replace('    :aria-label="props.ariaLabel"\n', ''),
    'root must bind props.ariaLabel to aria-label',
  )
  await runContractMutationProof(
    'metric-strip-wrong-root-radius',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) => source.replace(
      '  border-radius: var(--radius-lg);',
      '  border-radius: var(--radius-md);',
    ),
    'root must own exactly border-radius: var(--radius-lg)',
  )
  await runContractMutationProof(
    'metric-strip-root-logical-radius-longhand',
    syntheticMetricStrip,
    validateMetricStrip,
    (source) =>
      replaceRequired(
        source,
        '  border-radius: var(--radius-lg);',
        '  border-radius: var(--radius-lg);\n  border-start-start-radius: 0;',
        'metric-strip-root-logical-radius-longhand',
      ),
    'root must own exactly border-radius: var(--radius-lg)',
  )
  await runContractMutationProof(
    'status-tag-business-enum',
    syntheticStatusTag,
    validateStatusTag,
    (source) => source.replace('    {{ props.label }}', '    ACTIVE {{ props.label }}'),
    'must not embed business status enum text',
  )
  await runContractMutationProof(
    'status-tag-neutral-mapping-removal',
    syntheticStatusTag,
    validateStatusTag,
    (source) => source.replace("props.tone === 'neutral' ? 'info' : props.tone", 'props.tone'),
    'must map neutral to Element Plus info and preserve semantic tag types',
  )
  await runContractMutationProof(
    'common-status-tag-adapter-bypass',
    syntheticCommonStatusTag,
    validateCommonStatusTag,
    (source) => source.replace('commonStatusTagType(props.status)', "'info'"),
    'tone must adapt commonStatusTagType(props.status) to StatusTone',
  )
  await runContractMutationProof(
    'metric-icon-hard-coded-shadow',
    syntheticMetricIconBg,
    validateMetricIconBg,
    (source) => source.replace(
      'box-shadow: var(--inner-highlight);',
      'box-shadow: var(--inner-highlight), 0 2px 4px var(--status-info);',
    ),
    'compiled style 1 must not contain literal box-shadow: var(--inner-highlight), 0 2px 4px var(--status-info)',
  )
}

export {
  runMutationProofs,
  mutationProofCount,
  safeVariantProofCount,
  knowledgeHarnessProofCount,
  agentHarnessProofCount,
  mcpHarnessProofCount
}
