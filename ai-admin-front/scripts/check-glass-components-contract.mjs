import { existsSync, readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { baseParse, compile as compileDom } from '@vue/compiler-dom'
import { compileScript, compileStyleAsync, compileTemplate, parse } from '@vue/compiler-sfc'
import * as sass from 'sass'
import ts from 'typescript'

const root = process.cwd()
const failures = []

function readNormalizedUtf8(path) {
  return readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

const componentFiles = [
  'src/components/common/glassWorkbench.ts',
  'src/components/common/PageHeader.vue',
  'src/components/common/MetricStrip.vue',
  'src/components/common/WorkbenchPanel.vue',
  'src/components/common/FilterBar.vue',
  'src/components/common/DataTableShell.vue',
  'src/components/common/StatusTag.vue',
  'src/components/common/MetricIconBg.vue',
  'src/components/CommonStatusTag.vue',
  'src/components/common/AppDialog.vue',
  'src/components/common/WizardDialog.vue',
  'src/components/common/AppDrawer.vue',
]

const foundationTokenFiles = [
  'src/styles/tokens/_brand.scss',
  'src/styles/tokens/_semantic.scss',
  'src/styles/tokens/_density.scss',
]
const foundationCssVariables = new Set()
for (const relativePath of foundationTokenFiles) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) {
    failures.push(`${relativePath} foundation token source is missing`)
    continue
  }
  const source = readNormalizedUtf8(absolutePath)
  for (const match of source.matchAll(/(?:^|[;{])\s*(--[-a-zA-Z0-9_]+)\s*:/g)) {
    foundationCssVariables.add(match[1])
  }
}

function formatCompilerError(error) {
  if (typeof error === 'string') return error
  if (error instanceof Error) return error.message
  if (error && typeof error.message === 'string') return error.message
  return JSON.stringify(error)
}

function normalizeWhitespace(value) {
  return value.replace(/\s+/g, ' ').trim()
}

function compiledSelectorHeaders(css) {
  const selectors = []
  for (const match of css.matchAll(/([^{}]+)\{/g)) {
    const header = normalizeWhitespace(match[1])
    if (header && !header.startsWith('@')) selectors.push(header)
  }
  return selectors
}

function compiledDeclarations(css) {
  const declarations = []
  const pattern =
    /(?:^|[;{])\s*((?:--|[-a-zA-Z_])[-a-zA-Z0-9_]*)\s*:\s*([^;{}]+?)\s*(?=;)/g
  for (const match of css.matchAll(pattern)) {
    declarations.push({
      property: match[1].toLowerCase(),
      value: normalizeWhitespace(match[2]),
    })
  }
  return declarations
}

function forbiddenColorLiterals(value) {
  const withoutAllowedTokenRgb = value.replace(
    /rgb\(\s*var\(\s*(--[-a-zA-Z0-9_]+)\s*\)\s*\/\s*(?:\d*\.?\d+%?|var\(\s*--[-a-zA-Z0-9_]+\s*\))\s*\)/gi,
    (literal, variable) => (foundationCssVariables.has(variable) ? '' : literal),
  )
  const literals = [
    ...withoutAllowedTokenRgb.matchAll(/#[0-9a-f]{3,8}\b/gi),
    ...withoutAllowedTokenRgb.matchAll(/\b(?:rgb|rgba|hsl|hsla)\s*\([^;{}]*?\)/gi),
  ].map((match) => normalizeWhitespace(match[0]))
  return [...new Set(literals)]
}

function isTokenBoxShadow(value) {
  return /^var\(\s*--[-a-zA-Z0-9_]+\s*\)$/i.test(value)
}

function compileSassSyntax(style, label, localFailures) {
  if (style.lang !== 'scss' && style.lang !== 'sass') return
  try {
    sass.compileString(style.content, {
      syntax: style.lang === 'sass' ? 'indented' : 'scss',
      style: 'expanded',
    })
  } catch (error) {
    localFailures.push(`${label} Sass compile failed: ${formatCompilerError(error)}`)
  }
}

async function validateCommonSfc(source, label, options = {}) {
  const localFailures = []
  const allowedVariables = new Set(options.allowedVariables ?? [])
  const allowThemeSelectors = options.allowThemeSelectors === true
  const allowColorLiterals = options.allowColorLiterals === true
  const allowBackdropFilter = options.allowBackdropFilter === true
  const allowLiteralBoxShadow = options.allowLiteralBoxShadow === true
  let parsed
  try {
    parsed = parse(source, { filename: label })
  } catch (error) {
    localFailures.push(`${label} Vue parse failed: ${formatCompilerError(error)}`)
    return localFailures
  }

  for (const error of parsed.errors) {
    localFailures.push(`${label} Vue parse failed: ${formatCompilerError(error)}`)
  }
  if (parsed.errors.length > 0) return localFailures

  const { descriptor } = parsed
  if (descriptor.scriptSetup) {
    try {
      compileScript(descriptor, { id: `glass-component-${label}` })
    } catch (error) {
      localFailures.push(`${label} script compile failed: ${formatCompilerError(error)}`)
    }
  }

  if (descriptor.template) {
    const compiledTemplate = compileTemplate({
      source: descriptor.template.content,
      filename: label,
      id: `data-v-glass-component-${label}`,
      scoped: descriptor.styles.some((style) => style.scoped),
    })
    for (const error of compiledTemplate.errors) {
      localFailures.push(`${label} template compile failed: ${formatCompilerError(error)}`)
    }
  }

  for (const [index, style] of descriptor.styles.entries()) {
    const styleLabel = `${label} compiled style ${index + 1}`
    compileSassSyntax(style, styleLabel, localFailures)

    let compiled
    try {
      compiled = await compileStyleAsync({
        source: style.content,
        filename: label,
        id: `data-v-glass-component-${index + 1}`,
        scoped: style.scoped,
        preprocessLang: style.lang,
      })
    } catch (error) {
      localFailures.push(`${styleLabel} compile failed: ${formatCompilerError(error)}`)
      continue
    }

    for (const error of compiled.errors) {
      localFailures.push(`${styleLabel} compile failed: ${formatCompilerError(error)}`)
    }
    if (compiled.errors.length > 0) continue

    for (const selector of compiledSelectorHeaders(compiled.code)) {
      if (!allowThemeSelectors && /\[data-(?:theme|brand)\b/i.test(selector)) {
        localFailures.push(
          `${styleLabel} must not own page theme or brand selector ${selector}`,
        )
      }
    }

    const referencedVariables = new Set(
      [...compiled.code.matchAll(/var\(\s*(--[-a-zA-Z0-9_]+)/g)].map((match) => match[1]),
    )
    for (const variable of referencedVariables) {
      if (!foundationCssVariables.has(variable) && !allowedVariables.has(variable)) {
        localFailures.push(`${styleLabel} references undeclared CSS variable ${variable}`)
      }
    }

    for (const { property, value } of compiledDeclarations(compiled.code)) {
      if (!allowColorLiterals) {
        for (const literal of forbiddenColorLiterals(value)) {
          localFailures.push(`${styleLabel} must not contain color literal ${literal}`)
        }
      }
      if (
        !allowBackdropFilter &&
        (property === 'backdrop-filter' || property === '-webkit-backdrop-filter')
      ) {
        localFailures.push(`${styleLabel} must not declare ${property}`)
      }
      if (
        !allowLiteralBoxShadow &&
        (property === 'box-shadow' || property === '-webkit-box-shadow') &&
        !isTokenBoxShadow(value)
      ) {
        localFailures.push(`${styleLabel} must not contain literal ${property}: ${value}`)
      }
    }
  }

  return localFailures
}

function hasExportModifier(statement) {
  return statement.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword)
}

function exportedNames(statement) {
  if (!hasExportModifier(statement)) return []
  if (
    ts.isTypeAliasDeclaration(statement) ||
    ts.isFunctionDeclaration(statement) ||
    ts.isClassDeclaration(statement) ||
    ts.isInterfaceDeclaration(statement) ||
    ts.isEnumDeclaration(statement)
  ) {
    return statement.name ? [statement.name.text] : ['default']
  }
  if (ts.isVariableStatement(statement)) {
    return statement.declarationList.declarations.flatMap((declaration) =>
      ts.isIdentifier(declaration.name) ? [declaration.name.text] : [],
    )
  }
  return ['unsupported-export']
}

function typeAlias(sourceFile, name) {
  return sourceFile.statements.find(
    (statement) => ts.isTypeAliasDeclaration(statement) && statement.name.text === name,
  )
}

function interfaceDeclaration(sourceFile, name) {
  return sourceFile.statements.find(
    (statement) => ts.isInterfaceDeclaration(statement) && statement.name.text === name,
  )
}

function isTypeReference(node, name) {
  return Boolean(
    node &&
      ts.isTypeReferenceNode(node) &&
      ts.isIdentifier(node.typeName) &&
      node.typeName.text === name,
  )
}

function isNullTypeNode(node) {
  return Boolean(
    node && ts.isLiteralTypeNode(node) && node.literal.kind === ts.SyntaxKind.NullKeyword,
  )
}

function isStringOrNumberType(node) {
  return Boolean(
    node &&
      ts.isUnionTypeNode(node) &&
      node.types.length === 2 &&
      node.types[0].kind === ts.SyntaxKind.StringKeyword &&
      node.types[1].kind === ts.SyntaxKind.NumberKeyword,
  )
}

function validatesMetricStripItem(sourceFile) {
  const declaration = interfaceDeclaration(sourceFile, 'MetricStripItem')
  if (!declaration || !hasExportModifier(declaration) || declaration.members.length !== 6) return false
  const expected = [
    ['key', false, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['label', false, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['value', false, isStringOrNumberType],
    ['hint', true, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['tone', true, (node) => isTypeReference(node, 'MetricTone')],
    ['iconKey', true, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
  ]
  return expected.every(([name, optional, matches]) => {
    const member = declaration.members.find(
      (candidate) =>
        ts.isPropertySignature(candidate) && candidate.name && nodeName(candidate.name) === name,
    )
    return Boolean(
      member &&
        ts.isPropertySignature(member) &&
        Boolean(member.questionToken) === optional &&
        matches(member.type),
    )
  })
}

function validatesWizardStep(sourceFile) {
  const declaration = interfaceDeclaration(sourceFile, 'WizardStep')
  if (!declaration || !hasExportModifier(declaration) || declaration.members.length !== 5) return false
  const expected = [
    ['key', false, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['title', false, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['description', true, (node) => node?.kind === ts.SyntaxKind.StringKeyword],
    ['state', false, (node) => isTypeReference(node, 'WizardStepState')],
    ['disabled', true, (node) => node?.kind === ts.SyntaxKind.BooleanKeyword],
  ]
  return expected.every(([name, optional, matches]) => {
    const member = declaration.members.find(
      (candidate) =>
        ts.isPropertySignature(candidate) && candidate.name && nodeName(candidate.name) === name,
    )
    return Boolean(
      member &&
        ts.isPropertySignature(member) &&
        Boolean(member.questionToken) === optional &&
        matches(member.type),
    )
  })
}

function stringLiteralUnionValues(typeNode) {
  if (!typeNode || !ts.isUnionTypeNode(typeNode)) return null
  const values = []
  for (const member of typeNode.types) {
    if (!ts.isLiteralTypeNode(member) || !ts.isStringLiteral(member.literal)) return null
    values.push(member.literal.text)
  }
  return values
}

function arraysEqual(actual, expected) {
  return actual?.length === expected.length && actual.every((value, index) => value === expected[index])
}

async function validateGlassWorkbench(source, label) {
  const localFailures = []
  const sourceFile = ts.createSourceFile(label, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  for (const diagnostic of sourceFile.parseDiagnostics) {
    localFailures.push(`${label} TypeScript parse failed: ${formatCompilerError(diagnostic.messageText)}`)
  }
  if (sourceFile.parseDiagnostics.length > 0) return localFailures

  const actualExports = sourceFile.statements.flatMap(exportedNames).sort()
  const expectedExports = [
    'MetricStripItem',
    'MetricTone',
    'StatusTone',
    'WorkbenchDensity',
    'WizardStep',
    'WizardStepState',
    'densityClass',
  ].sort()
  if (!arraysEqual(actualExports, expectedExports)) {
    localFailures.push(
      `${label} must preserve the shared exports and add exactly WizardStepState and WizardStep`,
    )
  }

  if (!validatesMetricStripItem(sourceFile)) {
    localFailures.push(
      `${label} must export the exact MetricStripItem key/label/value/hint/tone/iconKey interface`,
    )
  }

  if (!validatesWizardStep(sourceFile)) {
    localFailures.push(
      `${label} must export the exact WizardStep key/title/description/state/disabled interface`,
    )
  }

  const workbenchDensity = typeAlias(sourceFile, 'WorkbenchDensity')
  if (
    !workbenchDensity ||
    !arraysEqual(stringLiteralUnionValues(workbenchDensity.type), [
      'compact',
      'comfortable',
      'spacious',
    ])
  ) {
    localFailures.push(
      `${label} must export WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'`,
    )
  }

  const statusTone = typeAlias(sourceFile, 'StatusTone')
  if (
    !statusTone ||
    !arraysEqual(stringLiteralUnionValues(statusTone.type), [
      'success',
      'warning',
      'danger',
      'info',
      'neutral',
    ])
  ) {
    localFailures.push(
      `${label} must export StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'`,
    )
  }


  const wizardStepState = typeAlias(sourceFile, 'WizardStepState')
  if (
    !wizardStepState ||
    !arraysEqual(stringLiteralUnionValues(wizardStepState.type), [
      'pending',
      'current',
      'complete',
      'error',
      'skipped',
    ])
  ) {
    localFailures.push(
      `${label} must export WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'`,
    )
  }

  const metricTone = typeAlias(sourceFile, 'MetricTone')
  const metricMembers = metricTone && ts.isUnionTypeNode(metricTone.type) ? metricTone.type.types : []
  if (
    metricMembers.length !== 2 ||
    !ts.isTypeReferenceNode(metricMembers[0]) ||
    !ts.isIdentifier(metricMembers[0].typeName) ||
    metricMembers[0].typeName.text !== 'StatusTone' ||
    !ts.isLiteralTypeNode(metricMembers[1]) ||
    !ts.isStringLiteral(metricMembers[1].literal) ||
    metricMembers[1].literal.text !== 'brand'
  ) {
    localFailures.push(`${label} must export MetricTone = StatusTone | 'brand'`)
  }

  const helper = sourceFile.statements.find(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === 'densityClass',
  )
  const helperParameter = helper?.parameters[0]
  const helperReturnType = helper?.type
  const helperReturn = helper?.body?.statements[0]
  if (
    !helper ||
    helper.parameters.length !== 1 ||
    !helperParameter ||
    !ts.isIdentifier(helperParameter.name) ||
    helperParameter.name.text !== 'density' ||
    !helperParameter.type ||
    !ts.isTypeReferenceNode(helperParameter.type) ||
    !ts.isIdentifier(helperParameter.type.typeName) ||
    helperParameter.type.typeName.text !== 'WorkbenchDensity' ||
    !helperReturnType ||
    !ts.isTemplateLiteralTypeNode(helperReturnType) ||
    helperReturnType.head.text !== 'density-' ||
    helperReturnType.templateSpans.length !== 1 ||
    !ts.isTypeReferenceNode(helperReturnType.templateSpans[0].type) ||
    !ts.isIdentifier(helperReturnType.templateSpans[0].type.typeName) ||
    helperReturnType.templateSpans[0].type.typeName.text !== 'WorkbenchDensity' ||
    helperReturnType.templateSpans[0].literal.text !== '' ||
    helper.body?.statements.length !== 1 ||
    !helperReturn ||
    !ts.isReturnStatement(helperReturn) ||
    !helperReturn.expression ||
    !ts.isTemplateExpression(helperReturn.expression) ||
    helperReturn.expression.head.text !== 'density-' ||
    helperReturn.expression.templateSpans.length !== 1 ||
    !ts.isIdentifier(helperReturn.expression.templateSpans[0].expression) ||
    helperReturn.expression.templateSpans[0].expression.text !== 'density' ||
    helperReturn.expression.templateSpans[0].literal.text !== ''
  ) {
    localFailures.push(
      `${label} densityClass must map WorkbenchDensity to \`density-${'${WorkbenchDensity}'}\``,
    )
    return localFailures
  }

  const transpiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    reportDiagnostics: true,
  })
  for (const diagnostic of transpiled.diagnostics ?? []) {
    if (diagnostic.category === ts.DiagnosticCategory.Error) {
      localFailures.push(
        `${label} TypeScript compile failed: ${formatCompilerError(diagnostic.messageText)}`,
      )
    }
  }
  if (localFailures.length > 0) return localFailures

  try {
    const moduleUrl = `data:text/javascript;base64,${Buffer.from(transpiled.outputText).toString('base64')}`
    const module = await import(moduleUrl)
    for (const density of ['compact', 'comfortable', 'spacious']) {
      const actual = module.densityClass(density)
      if (actual !== `density-${density}`) {
        localFailures.push(
          `${label} densityClass('${density}') must return 'density-${density}', got '${actual}'`,
        )
      }
    }
  } catch (error) {
    localFailures.push(`${label} densityClass behavior probe failed: ${formatCompilerError(error)}`)
  }

  return localFailures
}

function unwrapExpression(node) {
  let current = node
  while (
    current &&
    (ts.isParenthesizedExpression(current) ||
      ts.isAsExpression(current) ||
      ts.isTypeAssertionExpression(current) ||
      ts.isNonNullExpression(current) ||
      ts.isSatisfiesExpression(current))
  ) {
    current = current.expression
  }
  return current
}

function parseTypeScript(source, label, localFailures) {
  const sourceFile = ts.createSourceFile(label, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  for (const diagnostic of sourceFile.parseDiagnostics) {
    localFailures.push(`${label} TypeScript AST parse failed: ${formatCompilerError(diagnostic.messageText)}`)
  }
  return sourceFile
}

function findVariableDeclaration(sourceFile, name) {
  for (const statement of sourceFile.statements) {
    if (!ts.isVariableStatement(statement)) continue
    for (const declaration of statement.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name) && declaration.name.text === name) return declaration
    }
  }
  return undefined
}

function isNamedCall(node, name) {
  const expression = unwrapExpression(node)
  if (!expression) return false
  return (
    ts.isCallExpression(expression) &&
    ts.isIdentifier(unwrapExpression(expression.expression)) &&
    unwrapExpression(expression.expression).text === name
  )
}

function callExpression(node, name) {
  const expression = unwrapExpression(node)
  return isNamedCall(expression, name) ? expression : undefined
}

function nodeName(node) {
  return ts.isIdentifier(node) || ts.isStringLiteral(node) ? node.text : undefined
}

function propsContract(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'props')
  const defaultsCall = callExpression(declaration?.initializer, 'withDefaults')
  const definePropsCall = defaultsCall
    ? callExpression(defaultsCall.arguments[0], 'defineProps')
    : callExpression(declaration?.initializer, 'defineProps')
  const typeLiteral = definePropsCall?.typeArguments?.[0]
  const defaults = unwrapExpression(defaultsCall?.arguments[1])
  return {
    typeLiteral: typeLiteral && ts.isTypeLiteralNode(typeLiteral) ? typeLiteral : undefined,
    defaults: defaults && ts.isObjectLiteralExpression(defaults) ? defaults : undefined,
  }
}

function typeMember(typeLiteral, name) {
  return typeLiteral?.members.find(
    (member) => ts.isPropertySignature(member) && member.name && nodeName(member.name) === name,
  )
}

function typeNodeMatches(node, expected) {
  if (expected === 'string') return node?.kind === ts.SyntaxKind.StringKeyword
  if (expected === 'number') return node?.kind === ts.SyntaxKind.NumberKeyword
  if (expected === 'boolean') return node?.kind === ts.SyntaxKind.BooleanKeyword
  if (expected === 'string | number') return isStringOrNumberType(node)
  if (['WorkbenchDensity', 'StatusTone', 'MetricTone', 'WizardStep'].includes(expected)) {
    return isTypeReference(node, expected)
  }
  if (expected === 'MetricStripItem[]') {
    return Boolean(node && ts.isArrayTypeNode(node) && isTypeReference(node.elementType, 'MetricStripItem'))
  }
  if (expected === 'WizardStep[]') {
    return Boolean(node && ts.isArrayTypeNode(node) && isTypeReference(node.elementType, 'WizardStep'))
  }
  if (expected === 'number[]') {
    return Boolean(node && ts.isArrayTypeNode(node) && node.elementType.kind === ts.SyntaxKind.NumberKeyword)
  }
  if (expected === 'string | null') {
    return Boolean(
      node &&
        ts.isUnionTypeNode(node) &&
        node.types.length === 2 &&
        node.types[0].kind === ts.SyntaxKind.StringKeyword &&
        isNullTypeNode(node.types[1]),
    )
  }
  if (Array.isArray(expected)) return arraysEqual(stringLiteralUnionValues(node), expected)
  return false
}

function hasTypedProp(contract, name, expectedType, optional) {
  const member = typeMember(contract.typeLiteral, name)
  return Boolean(
    member && Boolean(member.questionToken) === optional && typeNodeMatches(member.type, expectedType),
  )
}

function hasExactTypedProps(contract, expected) {
  return Boolean(
    contract.typeLiteral &&
      contract.typeLiteral.members.length === expected.length &&
      expected.every(([name, type, optional]) => hasTypedProp(contract, name, type, optional)),
  )
}

function emitContract(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'emit')
  const defineEmitsCall = callExpression(declaration?.initializer, 'defineEmits')
  const typeLiteral = defineEmitsCall?.typeArguments?.[0]
  return typeLiteral && ts.isTypeLiteralNode(typeLiteral) ? typeLiteral : undefined
}

function tupleElementType(element) {
  return ts.isNamedTupleMember(element) ? element.type : element
}

function declaresExactEmits(sourceFile, expected) {
  const contract = emitContract(sourceFile)
  if (!contract || contract.members.length !== expected.length) return false
  return expected.every(([name, parameterTypes]) => {
    const member = typeMember(contract, name)
    return Boolean(
      member &&
        member.type &&
        ts.isTupleTypeNode(member.type) &&
        member.type.elements.length === parameterTypes.length &&
        member.type.elements.every((element, index) =>
          typeNodeMatches(tupleElementType(element), parameterTypes[index]),
        ),
    )
  })
}

function declaresInheritAttrsFalse(sourceFile) {
  const matches = sourceFile.statements.filter((statement) => {
    if (!ts.isExpressionStatement(statement)) return false
    const defineOptionsCall = callExpression(statement.expression, 'defineOptions')
    if (!defineOptionsCall || defineOptionsCall.arguments.length !== 1) return false
    const options = unwrapExpression(defineOptionsCall.arguments[0])
    if (!options || !ts.isObjectLiteralExpression(options) || options.properties.length !== 1) {
      return false
    }
    const inheritAttrs = options.properties[0]
    return Boolean(
      ts.isPropertyAssignment(inheritAttrs) &&
        nodeName(inheritAttrs.name) === 'inheritAttrs' &&
        unwrapExpression(inheritAttrs.initializer).kind === ts.SyntaxKind.FalseKeyword,
    )
  })
  return matches.length === 1
}

function defaultProperty(defaults, name) {
  return defaults?.properties.find(
    (property) => ts.isPropertyAssignment(property) && nodeName(property.name) === name,
  )
}

function defaultMatches(contract, name, expected) {
  const property = defaultProperty(contract.defaults, name)
  if (!property || !ts.isPropertyAssignment(property)) return false
  const initializer = unwrapExpression(property.initializer)
  if (typeof expected === 'string') {
    return ts.isStringLiteralLike(initializer) && initializer.text === expected
  }
  if (typeof expected === 'boolean') {
    return initializer.kind === (expected ? ts.SyntaxKind.TrueKeyword : ts.SyntaxKind.FalseKeyword)
  }
  return false
}

function declaresBackEmit(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'emit')
  const defineEmitsCall = callExpression(declaration?.initializer, 'defineEmits')
  const typeLiteral = defineEmitsCall?.typeArguments?.[0]
  if (!typeLiteral || !ts.isTypeLiteralNode(typeLiteral)) return false
  const backMember = typeMember(typeLiteral, 'back')
  return Boolean(
    backMember && backMember.type && ts.isTupleTypeNode(backMember.type) && backMember.type.elements.length === 0,
  )
}

function returnedExpression(node) {
  const expression = unwrapExpression(node)
  if (!expression || (!ts.isArrowFunction(expression) && !ts.isFunctionExpression(expression))) return undefined
  if (!ts.isBlock(expression.body)) return unwrapExpression(expression.body)
  if (expression.body.statements.length !== 1) return undefined
  const statement = expression.body.statements[0]
  return ts.isReturnStatement(statement) && statement.expression
    ? unwrapExpression(statement.expression)
    : undefined
}

function computedInitializer(sourceFile, name) {
  const declaration = findVariableDeclaration(sourceFile, name)
  const computedCall = callExpression(declaration?.initializer, 'computed')
  return computedCall ? returnedExpression(computedCall.arguments[0]) : undefined
}

function propertyChain(node) {
  const expression = unwrapExpression(node)
  if (!expression) return undefined
  if (ts.isIdentifier(expression)) return [expression.text]
  if (ts.isPropertyAccessExpression(expression)) {
    const parent = propertyChain(expression.expression)
    return parent ? [...parent, expression.name.text] : undefined
  }
  return undefined
}

function isPropertyChain(node, expected) {
  return arraysEqual(propertyChain(node), expected)
}

function isStringValue(node, expected) {
  const expression = unwrapExpression(node)
  if (!expression) return false
  return ts.isStringLiteralLike(expression) && expression.text === expected
}

function validatesSurfaceClass(sourceFile) {
  const body = computedInitializer(sourceFile, 'surfaceClass')
  if (!body || !ts.isConditionalExpression(body)) return false
  const condition = unwrapExpression(body.condition)
  return Boolean(
    ts.isBinaryExpression(condition) &&
      condition.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken &&
      isPropertyChain(condition.left, ['props', 'level']) &&
      isStringValue(condition.right, 'control') &&
      isStringValue(body.whenTrue, 'glass-surface-control') &&
      isStringValue(body.whenFalse, 'glass-surface-panel'),
  )
}

function templateAst(template, label, localFailures) {
  try {
    return baseParse(template)
  } catch (error) {
    localFailures.push(`${label} template AST parse failed: ${formatCompilerError(error)}`)
    return undefined
  }
}

function transformedTemplateAst(template, label, localFailures) {
  try {
    return compileDom(template).ast
  } catch (error) {
    localFailures.push(`${label} transformed template AST parse failed: ${formatCompilerError(error)}`)
    return undefined
  }
}

function transformedTemplateNodes(ast) {
  const nodes = []
  const visit = (node) => {
    if (!node) return
    nodes.push(node)
    for (const child of node.children ?? []) visit(child)
  }
  visit(ast)
  return nodes
}

function transformedRootElement(ast) {
  const roots = ast?.children?.filter((node) => node.type === 1) ?? []
  return roots.length === 1 ? roots[0] : undefined
}

function staticConditionState(content) {
  const expression = parseTemplateExpression(content)
  if (!expression) return 'unknown'
  if (
    expression.kind === ts.SyntaxKind.FalseKeyword ||
    expression.kind === ts.SyntaxKind.NullKeyword ||
    (ts.isIdentifier(expression) && expression.text === 'undefined') ||
    (ts.isNumericLiteral(expression) && Number(expression.text) === 0) ||
    (ts.isStringLiteralLike(expression) && expression.text.length === 0)
  ) {
    return 'false'
  }
  if (
    expression.kind === ts.SyntaxKind.TrueKeyword ||
    (ts.isNumericLiteral(expression) && Number(expression.text) !== 0) ||
    (ts.isStringLiteralLike(expression) && expression.text.length > 0)
  ) {
    return 'true'
  }
  return 'unknown'
}

function templateElementEntries(ast) {
  const entries = []
  const visitChildren = (children, parent, ancestorReachable) => {
    let chainActive = false
    let priorBranchesCanBeFalse = true
    for (const child of children ?? []) {
      if (child.type !== 1) {
        const ignorable = child.type === 3 || (child.type === 2 && child.content.trim() === '')
        if (!ignorable) {
          chainActive = false
          priorBranchesCanBeFalse = true
        }
        continue
      }

      const ifDirective = directive(child, 'if', undefined)
      const elseIfDirective = directive(child, 'else-if', undefined)
      const elseDirective = directive(child, 'else', undefined)
      let reachable = ancestorReachable

      if (ifDirective) {
        const state = staticConditionState(ifDirective.exp?.content)
        reachable = ancestorReachable && state !== 'false'
        chainActive = true
        priorBranchesCanBeFalse = state !== 'true'
      } else if (elseIfDirective && chainActive) {
        const state = staticConditionState(elseIfDirective.exp?.content)
        reachable = ancestorReachable && priorBranchesCanBeFalse && state !== 'false'
        priorBranchesCanBeFalse = priorBranchesCanBeFalse && state !== 'true'
      } else if (elseDirective && chainActive) {
        reachable = ancestorReachable && priorBranchesCanBeFalse
        chainActive = false
        priorBranchesCanBeFalse = true
      } else {
        chainActive = false
        priorBranchesCanBeFalse = true
      }

      const entry = { element: child, parent, reachable }
      entries.push(entry)
      visitChildren(child.children, child, reachable)
    }
  }

  visitChildren(ast?.children, undefined, true)
  return entries
}

function templateElements(ast) {
  return templateElementEntries(ast)
    .filter((entry) => entry.reachable)
    .map((entry) => entry.element)
}

function allTemplateElements(ast) {
  const elements = []
  const visit = (node) => {
    if (!node) return
    if (node.type === 1) elements.push(node)
    for (const child of node.children ?? []) visit(child)
  }
  visit(ast)
  return elements
}

function rootTemplateElement(ast) {
  const rootElements = templateElementEntries(ast).filter(
    (entry) => entry.reachable && !entry.parent,
  )
  return rootElements.length === 1 ? rootElements[0].element : undefined
}

function reachableDirectChild(ast, parent, className) {
  return templateElementEntries(ast).find(
    (entry) =>
      entry.reachable && entry.parent === parent && hasStaticClass(entry.element, className),
  )?.element
}

function staticAttribute(element, name) {
  return element?.props.find((property) => property.type === 6 && property.name === name)
}

function staticAttributeValue(element, name) {
  return staticAttribute(element, name)?.value?.content
}

function directive(element, name, argument) {
  return element?.props.find(
    (property) =>
      property.type === 7 &&
      property.name === name &&
      (argument === undefined
        ? !property.arg
        : property.arg?.isStatic && property.arg.content === argument),
  )
}

function hasStaticClass(element, className) {
  const value = staticAttribute(element, 'class')?.value?.content ?? ''
  return value.split(/\s+/).includes(className)
}

function parseTemplateExpression(expression) {
  if (!expression) return undefined
  const sourceFile = ts.createSourceFile(
    'template-expression.ts',
    `const __templateExpression = (${expression})`,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  if (sourceFile.parseDiagnostics.length > 0) return undefined
  const statement = sourceFile.statements[0]
  const declaration = ts.isVariableStatement(statement)
    ? statement.declarationList.declarations[0]
    : undefined
  return unwrapExpression(declaration?.initializer)
}

function directiveExpression(element, name, argument) {
  return parseTemplateExpression(directive(element, name, argument)?.exp?.content)
}

function hasNoDirectiveModifiers(element, name, argument) {
  const property = directive(element, name, argument)
  return Boolean(property && (property.modifiers?.length ?? 0) === 0)
}

function isIdentifierValue(node, expected) {
  const expression = unwrapExpression(node)
  return Boolean(expression && ts.isIdentifier(expression) && expression.text === expected)
}

function isNullishDefault(node, property, fallback) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken &&
      isPropertyChain(expression.left, property) &&
      isStringValue(expression.right, fallback),
  )
}

function interpolationProperties(element) {
  const properties = []
  const visit = (node) => {
    if (node?.type === 5) {
      const expression = parseTemplateExpression(node.content?.content)
      const chain = propertyChain(expression)
      if (chain) properties.push(chain)
    }
    for (const child of node?.children ?? []) visit(child)
  }
  visit(element)
  return properties
}

function hasInterpolationProperty(element, property) {
  return interpolationProperties(element).some((candidate) => arraysEqual(candidate, property))
}

function importDeclaration(sourceFile, moduleName) {
  return sourceFile.statements.find(
    (statement) =>
      ts.isImportDeclaration(statement) &&
      ts.isStringLiteral(statement.moduleSpecifier) &&
      statement.moduleSpecifier.text === moduleName,
  )
}

function importsDefault(sourceFile, localName, moduleName) {
  return importDeclaration(sourceFile, moduleName)?.importClause?.name?.text === localName
}

function importsNamed(sourceFile, names, moduleName) {
  const bindings = importDeclaration(sourceFile, moduleName)?.importClause?.namedBindings
  if (!bindings || !ts.isNamedImports(bindings)) return false
  const actual = bindings.elements
    .filter((element) => !element.isTypeOnly)
    .map((element) => element.name.text)
    .sort()
  return arraysEqual(actual, [...names].sort())
}

function callsNamedWithProperty(node, functionName, property) {
  const expression = unwrapExpression(node)
  return Boolean(
    isNamedCall(expression, functionName) &&
      expression.arguments.length === 1 &&
      isPropertyChain(expression.arguments[0], property),
  )
}

function computedTypeArgument(sourceFile, name) {
  const declaration = findVariableDeclaration(sourceFile, name)
  const computedCall = callExpression(declaration?.initializer, 'computed')
  return computedCall?.typeArguments?.[0]
}

function templateContainsBusinessEnum(ast) {
  const businessEnums = new Set(['ACTIVE', 'INACTIVE', 'DISABLED', 'DELETED'])
  const visit = (node) => {
    if (node?.type === 2 && businessEnums.has(node.content.trim())) return true
    if (node?.type === 6 && businessEnums.has(node.value?.content?.trim())) return true
    return (node?.children ?? []).some(visit)
  }
  return visit(ast)
}

function sourceContainsBusinessEnum(sourceFile) {
  const businessEnums = new Set(['ACTIVE', 'INACTIVE', 'DISABLED', 'DELETED'])
  let found = false
  const visit = (node) => {
    if (ts.isStringLiteralLike(node) && businessEnums.has(node.text)) found = true
    if (!found) ts.forEachChild(node, visit)
  }
  visit(sourceFile)
  return found
}

function isDensityClassCall(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    isNamedCall(expression, 'densityClass') &&
      expression.arguments.length === 1 &&
      isPropertyChain(expression.arguments[0], ['props', 'density']),
  )
}

function classArrayElements(element) {
  const expression = directiveExpression(element, 'bind', 'class')
  return expression && ts.isArrayLiteralExpression(expression)
    ? expression.elements.map(unwrapExpression)
    : []
}

function classArrayMatchesExactly(element, expectedMatchers) {
  const remaining = [...classArrayElements(element)]
  if (remaining.length !== expectedMatchers.length) return false
  for (const matches of expectedMatchers) {
    const matchIndex = remaining.findIndex(matches)
    if (matchIndex < 0) return false
    remaining.splice(matchIndex, 1)
  }
  return remaining.length === 0
}

function slotElements(ast) {
  return templateElements(ast).filter((element) => element.tagType === 2 && element.tag === 'slot')
}

function hasNamedSlot(ast, name) {
  return slotElements(ast).some(
    (slot) => staticAttribute(slot, 'name')?.value?.content === name,
  )
}

function hasDefaultSlot(ast) {
  return slotElements(ast).some((slot) => !staticAttribute(slot, 'name'))
}

function emitsBack(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    isNamedCall(expression, 'emit') &&
      expression.arguments.length === 1 &&
      isStringValue(expression.arguments[0], 'back'),
  )
}

function emitsNamed(node, eventName, argumentName) {
  const expression = unwrapExpression(node)
  if (!isNamedCall(expression, 'emit')) return false
  const expectedLength = argumentName === undefined ? 1 : 2
  return Boolean(
    expression.arguments.length === expectedLength &&
      isStringValue(expression.arguments[0], eventName) &&
      (argumentName === undefined || isIdentifierValue(expression.arguments[1], argumentName)),
  )
}

function hasDirectiveModifier(element, name, argument, modifier) {
  return Boolean(
    directive(element, name, argument)?.modifiers?.some((entry) => entry.content === modifier),
  )
}

function descendantElements(ast, ancestor) {
  const entries = templateElementEntries(ast)
  const parentByElement = new Map(entries.map((entry) => [entry.element, entry.parent]))
  return entries
    .filter((entry) => {
      if (!entry.reachable || entry.element === ancestor) return false
      let parent = entry.parent
      while (parent) {
        if (parent === ancestor) return true
        parent = parentByElement.get(parent)
      }
      return false
    })
    .map((entry) => entry.element)
}

function isSlotsProperty(node, name) {
  return isPropertyChain(node, ['$slots', name])
}

function isNegatedProperty(node, property) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isPrefixUnaryExpression(expression) &&
      expression.operator === ts.SyntaxKind.ExclamationToken &&
      isPropertyChain(expression.operand, property),
  )
}

function isEmptyVisibleCondition(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken &&
      isPropertyChain(expression.left, ['props', 'empty']) &&
      isNegatedProperty(expression.right, ['props', 'loading']),
  )
}

function isPositiveTotalCondition(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === ts.SyntaxKind.GreaterThanToken &&
      isPropertyChain(expression.left, ['props', 'total']) &&
      expression.right.kind === ts.SyntaxKind.NumericLiteral &&
      Number(expression.right.text) === 0,
  )
}

function isPaginationVisibleCondition(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === ts.SyntaxKind.BarBarToken &&
      isSlotsProperty(expression.left, 'pagination') &&
      isPositiveTotalCondition(expression.right),
  )
}

function functionForwardsEvents(sourceFile, functionName, eventNames) {
  const declaration = sourceFile.statements.find(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === functionName,
  )
  const parameter = declaration?.parameters[0]
  if (
    !declaration ||
    declaration.parameters.length !== 1 ||
    !parameter ||
    !ts.isIdentifier(parameter.name) ||
    parameter.type?.kind !== ts.SyntaxKind.NumberKeyword ||
    declaration.body?.statements.length !== eventNames.length
  ) {
    return false
  }
  const calls = declaration.body.statements.map((statement) =>
    ts.isExpressionStatement(statement) ? statement.expression : undefined,
  )
  return eventNames.every((eventName) =>
    calls.some((call) => emitsNamed(call, eventName, parameter.name.text)),
  )
}

function hasPageScopedElementPlusSkin(styles) {
  return compiledRules(styles).some(({ selector }) => /\.el-[a-z0-9_-]+/i.test(selector))
}

async function compiledStyleCodes(descriptor, label) {
  const codes = []
  for (const [index, style] of descriptor.styles.entries()) {
    try {
      const compiled = await compileStyleAsync({
        source: style.content,
        filename: label,
        id: `data-v-glass-component-detail-${index + 1}`,
        scoped: style.scoped,
        preprocessLang: style.lang,
      })
      if (compiled.errors.length === 0) codes.push(compiled.code)
    } catch {
      // validateCommonSfc owns compiler error reporting.
    }
  }
  return codes
}

function compiledRules(codes) {
  return codes.flatMap((code) =>
    [...code.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((match) => ({
      selector: normalizeWhitespace(match[1]),
      declarations: compiledDeclarations(`{${match[2]}}`),
    })),
  )
}

function rulesForClass(codes, className) {
  return compiledRules(codes).filter(({ selector }) => selector.includes(`.${className}`))
}

function hasClassDeclaration(codes, className, property, valuePattern) {
  return rulesForClass(codes, className).some(({ declarations }) =>
    declarations.some(
      (declaration) =>
        declaration.property === property && valuePattern.test(declaration.value),
    ),
  )
}

function hasAnyDeclaration(codes, properties) {
  return compiledRules(codes).some(({ declarations }) =>
    declarations.some(({ property }) => properties.includes(property)),
  )
}

function styleUsesVariable(codes, variable) {
  return codes.some((code) => code.includes(`var(${variable})`))
}

function validateRecipeOwnership(codes, label, localFailures) {
  if (
    codes
      .flatMap(compiledDeclarations)
      .some(({ property }) => property === 'box-shadow' || property === '-webkit-box-shadow')
  ) {
    localFailures.push(`${label} must leave box-shadow ownership to the glass recipe`)
  }
}

function hasSemanticFocusRule(codes) {
  for (const code of codes) {
    for (const match of code.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      if (
        !match[1].includes('.app-page-header__back') ||
        !match[1].includes(':focus-visible')
      ) {
        continue
      }
      const declarations = compiledDeclarations(`{${match[2]}}`)
      if (
        declarations.some(
          ({ property, value }) =>
            property === 'outline' &&
            /var\(\s*--(?:border-focus|brand-[-a-zA-Z0-9_]+)\s*\)/.test(value),
        )
      ) {
        return true
      }
    }
  }
  return false
}

async function validatePageHeader(source, label) {
  const localFailures = await validateCommonSfc(source, label, {
    allowedVariables: [
      '--layout-page-header-padding-block',
      '--layout-page-header-padding-inline',
      '--layout-page-header-compact-padding-block',
      '--layout-page-header-compact-padding-inline',
      '--layout-page-header-height-compact',
      '--layout-page-header-height-standard',
      '--layout-page-header-height-emphasis',
      '--layout-page-header-leading-size',
      '--layout-page-header-action-gap',
      '--layout-page-header-tag-gap',
      '--layout-page-header-art-opacity',
      '--page-header-material',
      '--page-header-material-position',
      '--page-header-domain-tone',
      '--page-header-domain-rgb',
    ],
    allowThemeSelectors: true,
    allowColorLiterals: true,
    allowBackdropFilter: true,
    allowLiteralBoxShadow: true,
  })
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const script = descriptor.scriptSetup?.content ?? ''
  const sourceFile = parseTypeScript(script, `${label} script setup`, localFailures)
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  for (const [name, type, optional] of [
    ['title', 'string', false],
    ['eyebrow', 'string', true],
    ['description', 'string', true],
    ['density', 'WorkbenchDensity', true],
    ['backLabel', 'string', true],
    ['showBack', 'boolean', true],
  ]) {
    if (!hasTypedProp(contract, name, type, optional)) {
      localFailures.push(`${label} must declare prop ${name}${optional ? '?' : ''}: ${type}`)
    }
  }
  if (!defaultMatches(contract, 'density', 'comfortable')) {
    localFailures.push(`${label} density must default to 'comfortable'`)
  }
  if (!defaultMatches(contract, 'backLabel', '返回')) {
    localFailures.push(`${label} backLabel must default to '返回'`)
  }
  if (!defaultMatches(contract, 'showBack', false)) {
    localFailures.push(`${label} showBack must default to false`)
  }
  for (const slot of ['leading', 'tags', 'meta', 'actions']) {
    if (!hasNamedSlot(ast, slot)) localFailures.push(`${label} must expose ${slot} slot`)
  }
  if (!declaresBackEmit(sourceFile)) localFailures.push(`${label} must declare back emit`)

  const rootClasses = classArrayElements(rootElement)
  const hasRootClass = rootClasses.some((entry) => isStringValue(entry, 'app-page-header'))
  if (!hasRootClass) {
    localFailures.push(`${label} root must include app-page-header`)
  }
  const recipeClasses = rootClasses
    .filter((entry) => ts.isStringLiteralLike(entry) && entry.text.startsWith('glass-surface-'))
    .map((entry) => entry.text)
  const hasPanelRecipe = arraysEqual(recipeClasses, ['glass-surface-panel'])
  if (!hasPanelRecipe) {
    localFailures.push(`${label} root must include glass-surface-panel recipe`)
  }
  const hasDensityClass = rootClasses.some(isDensityClassCall)
  if (!hasDensityClass) {
    localFailures.push(`${label} root must apply densityClass(props.density)`)
  }

  const backButton = elements.find(
    (element) => element.tag === 'button' && hasStaticClass(element, 'app-page-header__back'),
  )
  if (!backButton) {
    localFailures.push(`${label} back button must be reachable`)
  } else {
    const backCondition = directiveExpression(backButton, 'if', undefined)
    if (!isPropertyChain(backCondition, ['props', 'showBack'])) {
      localFailures.push(`${label} back button must render from props.showBack`)
    }
    if (!isPropertyChain(directiveExpression(backButton, 'bind', 'aria-label'), ['props', 'backLabel'])) {
      localFailures.push(`${label} back button must expose props.backLabel as its accessible label`)
    }
    if (!emitsBack(directiveExpression(backButton, 'on', 'click'))) {
      localFailures.push(`${label} back button must emit back on click`)
    }
  }
  if (!hasSemanticFocusRule(styles)) {
    localFailures.push(`${label} back button must have semantic :focus-visible treatment`)
  }
  if (
    !styleUsesVariable(styles, '--layout-page-header-padding-block') ||
    !styleUsesVariable(styles, '--layout-page-header-padding-inline') ||
    !styleUsesVariable(styles, '--section-gap')
  ) {
    localFailures.push(`${label} styles must consume layout header padding and section gap variables`)
  }
  return localFailures
}

async function validateWorkbenchPanel(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const script = descriptor.scriptSetup?.content ?? ''
  const sourceFile = parseTypeScript(script, `${label} script setup`, localFailures)
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  for (const [name, type] of [
    ['title', 'string'],
    ['description', 'string'],
    ['density', 'WorkbenchDensity'],
  ]) {
    if (!hasTypedProp(contract, name, type, true)) {
      localFailures.push(`${label} must declare prop ${name}?: ${type}`)
    }
  }
  if (!hasTypedProp(contract, 'level', ['panel', 'control'], true)) {
    localFailures.push(`${label} must declare prop level?: 'panel' | 'control'`)
  }
  if (!defaultMatches(contract, 'density', 'comfortable')) {
    localFailures.push(`${label} density must default to 'comfortable'`)
  }
  if (!defaultMatches(contract, 'level', 'panel')) {
    localFailures.push(`${label} level must default to 'panel'`)
  }
  for (const slot of ['header', 'actions', 'footer']) {
    if (!hasNamedSlot(ast, slot)) localFailures.push(`${label} must expose ${slot} slot`)
  }
  if (!hasDefaultSlot(ast)) localFailures.push(`${label} must expose default slot`)

  const rootClasses = classArrayElements(rootElement)
  const hasRootClass = rootClasses.some((entry) => isStringValue(entry, 'workbench-panel'))
  if (!hasRootClass) {
    localFailures.push(`${label} root must include workbench-panel`)
  }
  const surfaceClassEntries = rootClasses.filter(
    (entry) => ts.isIdentifier(entry) && entry.text === 'surfaceClass',
  )
  const literalRecipeEntry = rootClasses.some(
    (entry) => ts.isStringLiteralLike(entry) && entry.text.startsWith('glass-surface-'),
  )
  const hasSurfaceClass = surfaceClassEntries.length === 1
  if (surfaceClassEntries.length === 0) {
    localFailures.push(`${label} root must bind the exclusive surfaceClass recipe`)
  } else if (!hasSurfaceClass || literalRecipeEntry) {
    localFailures.push(`${label} root must bind only surfaceClass as its glass recipe`)
  }
  const hasDensityClass = rootClasses.some(isDensityClassCall)
  if (!hasDensityClass) {
    localFailures.push(`${label} root must apply densityClass(props.density)`)
  }
  if (
    hasRootClass &&
    hasSurfaceClass &&
    !literalRecipeEntry &&
    hasDensityClass &&
    !classArrayMatchesExactly(rootElement, [
      (entry) => isStringValue(entry, 'workbench-panel'),
      (entry) => ts.isIdentifier(entry) && entry.text === 'surfaceClass',
      isDensityClassCall,
    ])
  ) {
    localFailures.push(
      `${label} root class binding must contain only workbench-panel, surfaceClass, and densityClass(props.density)`,
    )
  }
  if (!validatesSurfaceClass(sourceFile)) {
    localFailures.push(
      `${label} surfaceClass must exclusively map control to glass-surface-control and panel to glass-surface-panel`,
    )
  }
  for (const element of ['header', 'body', 'footer']) {
    if (!reachableDirectChild(ast, rootElement, `workbench-panel__${element}`)) {
      localFailures.push(`${label} must expose coherent ${element} structure`)
    }
  }
  if (!elements.some((entry) => hasStaticClass(entry, 'workbench-panel__actions'))) {
    localFailures.push(`${label} must expose coherent actions structure`)
  }
  if (!styleUsesVariable(styles, '--panel-padding') || !styleUsesVariable(styles, '--section-gap')) {
    localFailures.push(`${label} styles must consume panel padding and section gap density variables`)
  }
  validateRecipeOwnership(styles, label, localFailures)
  return localFailures
}

async function validateFilterBar(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const entries = templateElementEntries(ast).filter((entry) => entry.reachable)
  const elements = entries.map((entry) => entry.element)
  const styles = await compiledStyleCodes(descriptor, label)

  const expectedProps = [
    ['density', 'WorkbenchDensity', true],
    ['loading', 'boolean', true],
    ['showReset', 'boolean', true],
    ['queryLabel', 'string', true],
    ['resetLabel', 'string', true],
  ]
  if (!hasExactTypedProps(contract, expectedProps)) {
    localFailures.push(`${label} must declare exactly density/loading/showReset/queryLabel/resetLabel props`)
  }
  for (const [name, expected] of [
    ['density', 'comfortable'],
    ['loading', false],
    ['showReset', true],
    ['queryLabel', '查询'],
    ['resetLabel', '重置'],
  ]) {
    if (!defaultMatches(contract, name, expected)) {
      localFailures.push(`${label} ${name} must use its shared default`)
    }
  }
  if (!declaresExactEmits(sourceFile, [['query', []], ['reset', []]])) {
    localFailures.push(`${label} must declare exactly query and reset emits`)
  }
  const slots = slotElements(ast)
  const slotNames = slots.map((slot) => staticAttributeValue(slot, 'name') ?? 'default').sort()
  if (!arraysEqual(slotNames, ['actions', 'default'])) {
    localFailures.push(`${label} must expose exactly default and actions slots`)
  }

  if (!rootElement || rootElement.tag !== 'form') {
    localFailures.push(`${label} root must be a semantic form`)
  } else {
    if (
      !classArrayMatchesExactly(rootElement, [
        (entry) => isStringValue(entry, 'filter-bar'),
        (entry) => isStringValue(entry, 'glass-surface-control'),
        isDensityClassCall,
      ])
    ) {
      localFailures.push(
        `${label} root must bind only filter-bar, glass-surface-control, and densityClass(props.density)`,
      )
    }
    if (
      !emitsNamed(directiveExpression(rootElement, 'on', 'submit'), 'query') ||
      !hasDirectiveModifier(rootElement, 'on', 'submit', 'prevent')
    ) {
      localFailures.push(`${label} form submit must prevent navigation and emit query`)
    }
  }

  const fields = reachableDirectChild(ast, rootElement, 'filter-bar__fields')
  const defaultSlot = slots.find((slot) => !staticAttribute(slot, 'name'))
  if (!fields || !entries.some((entry) => entry.element === defaultSlot && entry.parent === fields)) {
    localFailures.push(`${label} filter-bar__fields must directly own the default slot`)
  }

  const actionsSlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'actions')
  const actionButtons = actionsSlot
    ? descendantElements(ast, actionsSlot).filter((element) => element.tag.toLowerCase() === 'el-button')
    : []
  const primaryButtons = actionButtons.filter(
    (button) => staticAttributeValue(button, 'type') === 'primary',
  )
  if (actionButtons.length !== 2 || primaryButtons.length !== 1) {
    localFailures.push(`${label} default actions must contain Reset and Query with exactly one primary button`)
  } else {
    const queryButton = primaryButtons[0]
    const resetButton = actionButtons.find((button) => button !== queryButton)
    if (
      staticAttributeValue(queryButton, 'native-type') !== 'submit' ||
      !isPropertyChain(directiveExpression(queryButton, 'bind', 'loading'), ['props', 'loading']) ||
      !hasInterpolationProperty(queryButton, ['props', 'queryLabel'])
    ) {
      localFailures.push(`${label} primary Query action must submit, bind loading, and render queryLabel`)
    }
    if (
      !resetButton ||
      staticAttributeValue(resetButton, 'native-type') !== 'button' ||
      !isPropertyChain(directiveExpression(resetButton, 'if', undefined), ['props', 'showReset']) ||
      !emitsNamed(directiveExpression(resetButton, 'on', 'click'), 'reset') ||
      !hasInterpolationProperty(resetButton, ['props', 'resetLabel'])
    ) {
      localFailures.push(`${label} Reset action must follow showReset, emit reset, and render resetLabel`)
    }
  }
  if (hasPageScopedElementPlusSkin(styles)) {
    localFailures.push(`${label} must not contain page-scoped Element Plus skin`)
  }
  if (!styleUsesVariable(styles, '--panel-padding') || !styleUsesVariable(styles, '--section-gap')) {
    localFailures.push(`${label} styles must consume shared density variables`)
  }
  validateRecipeOwnership(styles, label, localFailures)
  return localFailures
}

async function validateDataTableShell(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  const expectedProps = [
    ['density', 'WorkbenchDensity', true],
    ['loading', 'boolean', true],
    ['empty', 'boolean', true],
    ['emptyDescription', 'string', true],
    ['currentPage', 'number', true],
    ['pageSize', 'number', true],
    ['total', 'number', true],
    ['pageSizes', 'number[]', true],
    ['paginationLayout', 'string', true],
  ]
  if (!hasExactTypedProps(contract, expectedProps)) {
    localFailures.push(`${label} must declare exactly the controlled table-shell props`)
  }
  if (!defaultMatches(contract, 'density', 'comfortable')) {
    localFailures.push(`${label} density must default to 'comfortable'`)
  }
  if (
    !declaresExactEmits(sourceFile, [
      ['update:currentPage', ['number']],
      ['update:pageSize', ['number']],
      ['pageChange', ['number']],
      ['sizeChange', ['number']],
    ])
  ) {
    localFailures.push(`${label} must declare exactly controlled pagination and semantic emits`)
  }
  if (!functionForwardsEvents(sourceFile, 'handlePageChange', ['update:currentPage', 'pageChange'])) {
    localFailures.push(`${label} handlePageChange must forward model and semantic page events`)
  }
  if (!functionForwardsEvents(sourceFile, 'handleSizeChange', ['update:pageSize', 'sizeChange'])) {
    localFailures.push(`${label} handleSizeChange must forward model and semantic size events`)
  }

  const slots = slotElements(ast)
  const slotNames = slots.map((slot) => staticAttributeValue(slot, 'name') ?? 'default').sort()
  if (!arraysEqual(slotNames, ['default', 'empty', 'pagination', 'toolbar'])) {
    localFailures.push(`${label} must expose exactly toolbar/default/empty/pagination slots`)
  }
  if (
    !rootElement ||
    !classArrayMatchesExactly(rootElement, [
      (entry) => isStringValue(entry, 'data-table-shell'),
      (entry) => isStringValue(entry, 'glass-surface-panel'),
      isDensityClassCall,
    ])
  ) {
    localFailures.push(
      `${label} root must bind only data-table-shell, one glass-surface-panel, and densityClass(props.density)`,
    )
  }
  if (!isPropertyChain(directiveExpression(rootElement, 'loading', undefined), ['props', 'loading'])) {
    localFailures.push(`${label} root must bind loading state`)
  }
  const recipeClasses = elements.flatMap((element) => [
    ...(staticAttributeValue(element, 'class')?.split(/\s+/) ?? []),
    ...classArrayElements(element)
      .filter((entry) => ts.isStringLiteralLike(entry))
      .map((entry) => entry.text),
  ]).filter((name) => name.startsWith('glass-surface-'))
  if (!arraysEqual(recipeClasses, ['glass-surface-panel'])) {
    localFailures.push(`${label} must own exactly one glass-surface-panel recipe`)
  }
  if (elements.some((element) => element.tag.toLowerCase() === 'el-table')) {
    localFailures.push(`${label} must never create an el-table`)
  }

  const toolbar = reachableDirectChild(ast, rootElement, 'data-table-shell__toolbar')
  const toolbarSlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'toolbar')
  if (
    !toolbar ||
    !isSlotsProperty(directiveExpression(toolbar, 'if', undefined), 'toolbar') ||
    !descendantElements(ast, toolbar).includes(toolbarSlot)
  ) {
    localFailures.push(`${label} toolbar region must own the conditional toolbar slot`)
  }
  const body = reachableDirectChild(ast, rootElement, 'data-table-shell__body')
  const defaultSlot = slots.find((slot) => !staticAttribute(slot, 'name'))
  if (!body || !descendantElements(ast, body).includes(defaultSlot)) {
    localFailures.push(`${label} body region must own the default table slot`)
  }

  const emptyRegion = reachableDirectChild(ast, rootElement, 'data-table-shell__empty')
  const emptySlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'empty')
  const emptyFallback = emptySlot
    ? descendantElements(ast, emptySlot).find((element) => element.tag.toLowerCase() === 'el-empty')
    : undefined
  if (
    !emptyRegion ||
    !isEmptyVisibleCondition(directiveExpression(emptyRegion, 'if', undefined)) ||
    !descendantElements(ast, emptyRegion).includes(emptySlot) ||
    !emptyFallback ||
    !isPropertyChain(directiveExpression(emptyFallback, 'bind', 'description'), ['props', 'emptyDescription'])
  ) {
    localFailures.push(`${label} empty region must render its slot/fallback only for empty && !loading`)
  }

  const paginationRegion = reachableDirectChild(ast, rootElement, 'data-table-shell__pagination')
  const paginationSlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'pagination')
  const paginations = elements.filter((element) => element.tag.toLowerCase() === 'el-pagination')
  const pagination = paginations.length === 1 ? paginations[0] : undefined
  if (
    !paginationRegion ||
    !isPaginationVisibleCondition(directiveExpression(paginationRegion, 'if', undefined)) ||
    !descendantElements(ast, paginationRegion).includes(paginationSlot) ||
    !pagination ||
    !descendantElements(ast, paginationSlot).includes(pagination)
  ) {
    localFailures.push(`${label} pagination slot must replace a total-gated default pagination`)
  } else {
    for (const [attribute, property] of [
      ['current-page', 'currentPage'],
      ['page-size', 'pageSize'],
      ['total', 'total'],
      ['page-sizes', 'pageSizes'],
      ['layout', 'paginationLayout'],
    ]) {
      if (!isPropertyChain(directiveExpression(pagination, 'bind', attribute), ['props', property])) {
        localFailures.push(`${label} default pagination must bind props.${property}`)
      }
    }
    if (
      !isIdentifierValue(directiveExpression(pagination, 'on', 'update:current-page'), 'handlePageChange') ||
      !isIdentifierValue(directiveExpression(pagination, 'on', 'update:page-size'), 'handleSizeChange')
    ) {
      localFailures.push(`${label} default pagination must forward controlled update events`)
    }
  }
  if (hasPageScopedElementPlusSkin(styles)) {
    localFailures.push(`${label} must not contain page-scoped Element Plus skin`)
  }
  if (!styleUsesVariable(styles, '--panel-padding') || !styleUsesVariable(styles, '--section-gap')) {
    localFailures.push(`${label} styles must consume shared density variables`)
  }
  validateRecipeOwnership(styles, label, localFailures)
  return localFailures
}

function hasHomegrownOverlayScript(sourceFile) {
  let found = false
  const visit = (node) => {
    if (found) return
    if (
      ts.isImportDeclaration(node) &&
      ts.isStringLiteral(node.moduleSpecifier) &&
      /(?:focus-trap|teleport|overlay)/i.test(node.moduleSpecifier.text)
    ) {
      found = true
      return
    }
    if (ts.isIdentifier(node) && /^(?:Teleport|ElFocusTrap|FocusTrap)$/.test(node.text)) {
      found = true
      return
    }
    if (
      ts.isPropertyAccessExpression(node) &&
      /^(?:addEventListener|removeEventListener)$/.test(node.name.text)
    ) {
      found = true
      return
    }
    if (isNamedCall(node, 'emit')) {
      found = true
      return
    }
    if (ts.isStringLiteralLike(node) && /^(?:Escape|Esc|keydown|keyup)$/.test(node.text)) {
      found = true
      return
    }
    ts.forEachChild(node, visit)
  }
  visit(sourceFile)
  return found
}

function hasOnlyAllowedOverlayScriptStatements(sourceFile) {
  const seen = new Set()
  for (const statement of sourceFile.statements) {
    if (ts.isExpressionStatement(statement) && callExpression(statement.expression, 'defineOptions')) {
      if (seen.has('defineOptions')) return false
      seen.add('defineOptions')
      continue
    }
    if (ts.isVariableStatement(statement) && statement.declarationList.declarations.length === 1) {
      const declaration = statement.declarationList.declarations[0]
      const name = ts.isIdentifier(declaration.name) ? declaration.name.text : undefined
      if ((name === 'props' || name === 'emit') && !seen.has(name)) {
        seen.add(name)
        continue
      }
    }
    return false
  }
  return true
}

function exactCompiledDeclarations(declarations, expected) {
  const entries = Object.entries(expected)
  return Boolean(
    declarations.length === entries.length &&
      entries.every(([property, value]) =>
        declarations.some(
          (declaration) => declaration.property === property && declaration.value === value,
        ),
      ),
  )
}

function hasPrivateOverlayStyle(codes, rootClass) {
  if (hasPageScopedElementPlusSkin(codes)) return true
  const rules = compiledRules(codes)
  const expectedRules = [
    {
      className: `${rootClass}__description`,
      declarations: {
        margin: '0',
        color: 'var(--text-secondary)',
        'line-height': '1.6',
      },
    },
    {
      className: `${rootClass}__divider`,
      declarations: {
        margin: 'var(--section-gap) 0',
        border: '0',
        'border-block-start': '1px solid var(--border-divider)',
      },
    },
  ]
  if (rules.length !== expectedRules.length) return true
  return expectedRules.some(({ className, declarations }) => {
    const expectedSelector = `.${className}[data-v-glass-component-detail-1]`
    const matches = rules.filter(({ selector }) => selector === expectedSelector)
    return (
      matches.length !== 1 ||
      !exactCompiledDeclarations(matches[0].declarations, declarations)
    )
  })
}

function hasOnlyAllowedOverlayNestedProps(elements, rootElement) {
  return elements.every((element) => {
    if (element === rootElement) return true
    return element.props.every((property) => {
      if (property.type === 6) {
        if (property.name.startsWith('data-')) return true
        if (property.name === 'class') return element.tag === 'p' || element.tag === 'hr'
        if (property.name === 'name') return element.tag === 'slot'
        return false
      }
      if (property.type !== 7) return false
      const isHeaderPropsSpread = Boolean(
        element.tag === 'slot' &&
          staticAttributeValue(element, 'name') === 'header' &&
          property.name === 'bind' &&
          !property.arg,
      )
      if ((property.modifiers?.length ?? 0) > 0 && !isHeaderPropsSpread) return false
      if (property.name === 'if') {
        return element.tag === 'template' || element.tag === 'p' || element.tag === 'hr'
      }
      if (property.name === 'slot') return element.tag === 'template'
      if (property.name === 'bind' && !property.arg) {
        return Boolean(
          element.tag === 'slot' &&
            staticAttributeValue(element, 'name') === 'header' &&
            isIdentifierValue(parseTemplateExpression(property.exp?.content), 'headerProps'),
        )
      }
      return false
    })
  })
}

function hasExactOverlayTemplateTopology(ast, rootElement, rootTag, rootClass) {
  const entries = templateElementEntries(ast)
  if (entries.length !== 8) return false
  const [
    rootEntry,
    headerProviderEntry,
    headerSlotEntry,
    descriptionEntry,
    dividerEntry,
    defaultSlotEntry,
    footerProviderEntry,
    footerSlotEntry,
  ] = entries
  return Boolean(
    rootEntry.element === rootElement &&
      !rootEntry.parent &&
      rootEntry.element.tag.toLowerCase() === rootTag &&
      headerProviderEntry.parent === rootElement &&
      headerProviderEntry.element.tag === 'template' &&
      directive(headerProviderEntry.element, 'slot', 'header') &&
      headerSlotEntry.parent === headerProviderEntry.element &&
      headerSlotEntry.element.tag === 'slot' &&
      staticAttributeValue(headerSlotEntry.element, 'name') === 'header' &&
      descriptionEntry.parent === rootElement &&
      descriptionEntry.element.tag === 'p' &&
      hasStaticClass(descriptionEntry.element, `${rootClass}__description`) &&
      dividerEntry.parent === rootElement &&
      dividerEntry.element.tag === 'hr' &&
      hasStaticClass(dividerEntry.element, `${rootClass}__divider`) &&
      defaultSlotEntry.parent === rootElement &&
      defaultSlotEntry.element.tag === 'slot' &&
      !staticAttribute(defaultSlotEntry.element, 'name') &&
      footerProviderEntry.parent === rootElement &&
      footerProviderEntry.element.tag === 'template' &&
      directive(footerProviderEntry.element, 'slot', 'footer') &&
      footerSlotEntry.parent === footerProviderEntry.element &&
      footerSlotEntry.element.tag === 'slot' &&
      staticAttributeValue(footerSlotEntry.element, 'name') === 'footer',
  )
}

function templateInterpolationEntries(ast) {
  const interpolations = []
  const visit = (node, parentElement) => {
    if (!node) return
    const nextParent = node.type === 1 ? node : parentElement
    if (node.type === 5) interpolations.push({ node, parent: parentElement })
    for (const child of node.children ?? []) visit(child, nextParent)
  }
  visit(ast, undefined)
  return interpolations
}

function hasExactOverlayInterpolation(ast, description) {
  const interpolations = templateInterpolationEntries(ast)
  if (interpolations.length !== 1 || interpolations[0].parent !== description) return false
  return isPropertyChain(
    parseTemplateExpression(interpolations[0].node.content?.content),
    ['props', 'description'],
  )
}

async function validateOverlayAdapter(source, label, options) {
  const { rootTag, rootClass, sizeProp } = options
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const entries = templateElementEntries(ast).filter((entry) => entry.reachable)
  const elements = entries.map((entry) => entry.element)
  const allElements = allTemplateElements(ast)
  const rootElement = rootTemplateElement(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  if (
    !hasExactTypedProps(contract, [
      ['modelValue', 'boolean', false],
      ['title', 'string', false],
      ['description', 'string', true],
      [sizeProp, 'string | number', true],
    ])
  ) {
    localFailures.push(
      `${label} must declare exactly modelValue/title/description/${sizeProp} props`,
    )
  }
  if (!declaresExactEmits(sourceFile, [['update:modelValue', ['boolean']]])) {
    localFailures.push(`${label} must declare only update:modelValue(boolean)`)
  }
  if (!declaresInheritAttrsFalse(sourceFile)) {
    localFailures.push(`${label} must call defineOptions({ inheritAttrs: false })`)
  }

  if (!rootElement || rootElement.tag.toLowerCase() !== rootTag) {
    localFailures.push(`${label} root must be ${rootTag}`)
  } else {
    if (
      !hasNoDirectiveModifiers(rootElement, 'bind', undefined) ||
      !isPropertyChain(directiveExpression(rootElement, 'bind', undefined), ['$attrs'])
    ) {
      localFailures.push(`${label} must bind $attrs directly to the Element Plus root`)
    }
    const transparentBindings = [
      ['model-value', 'modelValue'],
      ['title', 'title'],
      [sizeProp, sizeProp],
    ]
    if (
      !transparentBindings.every(([attribute, property]) =>
        Boolean(
          hasNoDirectiveModifiers(rootElement, 'bind', attribute) &&
            isPropertyChain(directiveExpression(rootElement, 'bind', attribute), [
              'props',
              property,
            ]),
        ),
      )
    ) {
      localFailures.push(`${label} must forward modelValue/title/${sizeProp} props directly`)
    }
    if (
      !hasNoDirectiveModifiers(rootElement, 'on', 'update:model-value') ||
      !emitsNamed(
          directiveExpression(rootElement, 'on', 'update:model-value'),
          'update:modelValue',
          '$event',
        )
    ) {
      localFailures.push(`${label} must forward the root model update directly`)
    }
  }

  const reachableSlots = slotElements(ast)
  const slotNames = reachableSlots
    .map((slot) => staticAttributeValue(slot, 'name') ?? 'default')
    .sort()
  const slotsAreExact = arraysEqual(slotNames, ['default', 'footer', 'header'])
  if (!slotsAreExact) {
    localFailures.push(`${label} must expose exactly header/default/footer slots`)
  }

  const entryFor = (element) => entries.find((entry) => entry.element === element)
  if (slotsAreExact) {
    const headerSlot = reachableSlots.find(
      (slot) => staticAttributeValue(slot, 'name') === 'header',
    )
    const headerProvider = entryFor(headerSlot)?.parent
    const headerProviderEntry = entryFor(headerProvider)
    if (
      !headerProvider ||
      headerProvider.tag !== 'template' ||
      headerProviderEntry?.parent !== rootElement ||
      !directive(headerProvider, 'slot', 'header') ||
      !hasNoDirectiveModifiers(headerProvider, 'slot', 'header') ||
      !isIdentifierValue(directiveExpression(headerProvider, 'slot', 'header'), 'headerProps') ||
      !isSlotsProperty(directiveExpression(headerProvider, 'if', undefined), 'header') ||
      !hasNoDirectiveModifiers(headerSlot, 'bind', undefined) ||
      !isIdentifierValue(directiveExpression(headerSlot, 'bind', undefined), 'headerProps')
    ) {
      localFailures.push(
        `${label} header slot must conditionally forward all Element Plus header props`,
      )
    }

    const defaultSlot = reachableSlots.find((slot) => !staticAttribute(slot, 'name'))
    if (entryFor(defaultSlot)?.parent !== rootElement) {
      localFailures.push(`${label} default slot must be forwarded directly to the root`)
    }

    const footerSlot = reachableSlots.find(
      (slot) => staticAttributeValue(slot, 'name') === 'footer',
    )
    const footerProvider = entryFor(footerSlot)?.parent
    const footerProviderEntry = entryFor(footerProvider)
    if (
      !footerProvider ||
      footerProvider.tag !== 'template' ||
      footerProviderEntry?.parent !== rootElement ||
      !directive(footerProvider, 'slot', 'footer') ||
      !isSlotsProperty(directiveExpression(footerProvider, 'if', undefined), 'footer')
    ) {
      localFailures.push(`${label} footer slot must be conditionally forwarded to the root`)
    }
  }

  const recipeClasses = elements
    .flatMap((element) => [
      ...(staticAttributeValue(element, 'class')?.split(/\s+/) ?? []),
      ...classArrayElements(element)
        .filter((entry) => ts.isStringLiteralLike(entry))
        .map((entry) => entry.text),
    ])
    .filter((name) => name.startsWith('glass-surface-'))
  const rootClasses = staticAttributeValue(rootElement, 'class')?.split(/\s+/) ?? []
  const ownsDynamicClassBinding = elements.some((element) =>
    Boolean(directive(element, 'bind', 'class')),
  )
  if (
    !rootClasses.includes(rootClass) ||
    ownsDynamicClassBinding ||
    !arraysEqual(recipeClasses, ['glass-surface-overlay'])
  ) {
    localFailures.push(`${label} root must own exactly one glass-surface-overlay recipe`)
  }

  const description = elements.find(
    (element) => element.tag === 'p' && hasStaticClass(element, `${rootClass}__description`),
  )
  const descriptionIsValid = Boolean(
    description &&
    entryFor(description)?.parent === rootElement &&
    isPropertyChain(directiveExpression(description, 'if', undefined), [
      'props',
      'description',
    ]) &&
    hasInterpolationProperty(description, ['props', 'description']),
  )
  if (!descriptionIsValid) {
    localFailures.push(`${label} must render semantic optional description text`)
  }
  const divider = elements.find(
    (element) => element.tag === 'hr' && hasStaticClass(element, `${rootClass}__divider`),
  )
  const dividerIsValid = Boolean(
    divider &&
    entryFor(divider)?.parent === rootElement &&
    isPropertyChain(directiveExpression(divider, 'if', undefined), [
      'props',
      'description',
    ]),
  )
  if (!dividerIsValid) {
    localFailures.push(`${label} must render a semantic description divider`)
  }

  const allowedTags = new Set(['el-dialog', 'el-drawer', 'template', 'slot', 'p', 'hr'])
  const ownsForbiddenElement = allElements.some(
    (element) => !allowedTags.has(element.tag.toLowerCase()),
  )
  const ownsId = allElements.some(
    (element) => staticAttribute(element, 'id') || directive(element, 'bind', 'id'),
  )
  const ownsCustomDirective = allElements.some((element) =>
    element.props.some(
      (property) =>
        property.type === 7 &&
        !['bind', 'on', 'if', 'else-if', 'else', 'slot'].includes(property.name),
    ),
  )
  const ownsNestedEvent = allElements.some(
    (element) =>
      element !== rootElement &&
      element.props.some((property) => property.type === 7 && property.name === 'on'),
  )
  const ownsInteractiveSemantics = allElements.some((element) => {
    const role = staticAttributeValue(element, 'role')
    return Boolean(
      staticAttribute(element, 'tabindex') ||
        directive(element, 'bind', 'tabindex') ||
        role === 'button' ||
        role === 'dialog',
    )
  })
  const ownsInlineStyle = allElements.some((element) =>
    Boolean(staticAttribute(element, 'style') || directive(element, 'bind', 'style')),
  )
  const ownsUnexpectedNestedProps = !hasOnlyAllowedOverlayNestedProps(
    allElements,
    rootElement,
  )
  const topologyEligible = Boolean(
    rootElement?.tag.toLowerCase() === rootTag &&
      slotsAreExact &&
      descriptionIsValid &&
      dividerIsValid,
  )
  const ownsUnexpectedTopology = Boolean(
    topologyEligible &&
      !hasExactOverlayTemplateTopology(ast, rootElement, rootTag, rootClass),
  )
  const ownsUnexpectedInterpolation = Boolean(
    descriptionIsValid && !hasExactOverlayInterpolation(ast, description),
  )
  const rootOwnsExtraBehavior = rootElement?.props.some((property) => {
    if (property.type === 6) return property.name !== 'class' && !property.name.startsWith('data-')
    if (property.type !== 7) return false
    if (property.name === 'on') {
      return !(
        property.arg?.isStatic &&
        property.arg.content === 'update:model-value'
      )
    }
    if (property.name === 'bind') {
      if (!property.arg) return !isPropertyChain(parseTemplateExpression(property.exp?.content), ['$attrs'])
      return !(
        property.arg.isStatic &&
        ['model-value', 'title', sizeProp].includes(property.arg.content)
      )
    }
    return true
  })
  if (
    ownsForbiddenElement ||
    ownsId ||
    ownsCustomDirective ||
    ownsNestedEvent ||
    ownsInteractiveSemantics ||
    ownsInlineStyle ||
    ownsUnexpectedNestedProps ||
    ownsUnexpectedTopology ||
    ownsUnexpectedInterpolation ||
    rootOwnsExtraBehavior ||
    hasHomegrownOverlayScript(sourceFile) ||
    !hasOnlyAllowedOverlayScriptStatements(sourceFile)
  ) {
    localFailures.push(
      `${label} must leave mask, teleport, Escape, focus trap, close, title IDs, and lifecycle behavior to Element Plus`,
    )
  }
  if (hasPrivateOverlayStyle(styles, rootClass)) {
    localFailures.push(
      `${label} must not own a fixed overlay, blur, shadow, Element Plus skin, or private glass recipe`,
    )
  }
  return localFailures
}

async function validateAppDialog(source, label) {
  return validateOverlayAdapter(source, label, {
    rootTag: 'el-dialog',
    rootClass: 'app-dialog',
    sizeProp: 'width',
  })
}

async function validateAppDrawer(source, label) {
  return validateOverlayAdapter(source, label, {
    rootTag: 'el-drawer',
    rootClass: 'app-drawer',
    sizeProp: 'size',
  })
}

function importsSymbol(sourceFile, moduleName, importedName, localName = importedName) {
  return sourceFile.statements.some((statement) => {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteral(statement.moduleSpecifier) ||
      statement.moduleSpecifier.text !== moduleName
    ) {
      return false
    }
    const bindings = statement.importClause?.namedBindings
    if (!bindings || !ts.isNamedImports(bindings)) return false
    return bindings.elements.some(
      (element) =>
        (element.propertyName?.text ?? element.name.text) === importedName &&
        element.name.text === localName,
    )
  })
}

function isNumberValue(node, expected) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression && ts.isNumericLiteral(expression) && Number(expression.text) === expected,
  )
}

function isUndefinedValue(node) {
  return isIdentifierValue(node, 'undefined')
}

function isBinary(node, operator, leftMatches, rightMatches) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === operator &&
      leftMatches(expression.left) &&
      rightMatches(expression.right),
  )
}

function isActiveStepLookup(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isElementAccessExpression(expression) &&
      isPropertyChain(expression.expression, ['props', 'steps']) &&
      isPropertyChain(expression.argumentExpression, ['props', 'activeStep']),
  )
}

function validatesActiveErrorStep(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'activeErrorStep')
  const computedCall = callExpression(declaration?.initializer, 'computed')
  const callback = unwrapExpression(computedCall?.arguments[0])
  if (
    !callback ||
    (!ts.isArrowFunction(callback) && !ts.isFunctionExpression(callback)) ||
    !ts.isBlock(callback.body) ||
    callback.body.statements.length !== 2
  ) {
    return false
  }
  const [stepStatement, returnStatement] = callback.body.statements
  if (
    !ts.isVariableStatement(stepStatement) ||
    stepStatement.declarationList.declarations.length !== 1 ||
    !ts.isReturnStatement(returnStatement) ||
    !returnStatement.expression
  ) {
    return false
  }
  const stepDeclaration = stepStatement.declarationList.declarations[0]
  const result = unwrapExpression(returnStatement.expression)
  const condition = result && ts.isConditionalExpression(result) ? unwrapExpression(result.condition) : undefined
  return Boolean(
    ts.isIdentifier(stepDeclaration.name) &&
      stepDeclaration.name.text === 'step' &&
      isActiveStepLookup(stepDeclaration.initializer) &&
      result &&
      ts.isConditionalExpression(result) &&
      isBinary(
        condition,
        ts.SyntaxKind.AmpersandAmpersandToken,
        (node) => isIdentifierValue(node, 'step'),
        (node) =>
          isBinary(
            node,
            ts.SyntaxKind.EqualsEqualsEqualsToken,
            (candidate) => isPropertyChain(candidate, ['step', 'state']),
            (candidate) => isStringValue(candidate, 'error'),
          ),
      ) &&
      isIdentifierValue(result.whenTrue, 'step') &&
      isUndefinedValue(result.whenFalse),
  )
}

function validatesWizardStepTone(sourceFile) {
  const toneDeclaration = findVariableDeclaration(sourceFile, 'stepToneByState')
  const satisfies = toneDeclaration?.initializer
  const toneType = satisfies && ts.isSatisfiesExpression(satisfies) ? satisfies.type : undefined
  const toneRecord =
    toneType &&
    ts.isTypeReferenceNode(toneType) &&
    ts.isIdentifier(toneType.typeName) &&
    toneType.typeName.text === 'Record'
      ? toneType.typeArguments
      : undefined
  const expectedToneMap = {
    pending: 'neutral',
    current: 'info',
    complete: 'success',
    error: 'danger',
    skipped: 'neutral',
  }
  if (
    !toneRecord ||
    toneRecord.length !== 2 ||
    !isTypeReference(toneRecord[0], 'WizardStepState') ||
    !isTypeReference(toneRecord[1], 'StatusTone') ||
    JSON.stringify(objectLiteralStringMap(sourceFile, 'stepToneByState')) !==
      JSON.stringify(expectedToneMap)
  ) {
    return false
  }

  const helper = sourceFile.statements.find(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === 'stepTone',
  )
  const parameter = helper?.parameters[0]
  const returnStatement = helper?.body?.statements[0]
  const returnExpression =
    returnStatement && ts.isReturnStatement(returnStatement)
      ? unwrapExpression(returnStatement.expression)
      : undefined
  return Boolean(
    helper &&
      helper.parameters.length === 1 &&
      parameter &&
      ts.isIdentifier(parameter.name) &&
      parameter.name.text === 'state' &&
      isTypeReference(parameter.type, 'WizardStepState') &&
      isTypeReference(helper.type, 'StatusTone') &&
      helper.body?.statements.length === 1 &&
      returnExpression &&
      ts.isElementAccessExpression(returnExpression) &&
      isIdentifierValue(returnExpression.expression, 'stepToneByState') &&
      isIdentifierValue(returnExpression.argumentExpression, 'state'),
  )
}

function isSpaciousDensityClassCall(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    isNamedCall(expression, 'densityClass') &&
      expression.arguments.length === 1 &&
      isStringValue(expression.arguments[0], 'spacious'),
  )
}

function isWizardStateClass(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isTemplateExpression(expression) &&
      expression.head.text === 'wizard-dialog__step--state-' &&
      expression.templateSpans.length === 1 &&
      isPropertyChain(expression.templateSpans[0].expression, ['step', 'state']) &&
      expression.templateSpans[0].literal.text === '',
  )
}

function isWizardToneClass(node) {
  const expression = unwrapExpression(node)
  const toneCall =
    expression && ts.isTemplateExpression(expression) && expression.templateSpans.length === 1
      ? callExpression(expression.templateSpans[0].expression, 'stepTone')
      : undefined
  return Boolean(
    expression &&
      ts.isTemplateExpression(expression) &&
      expression.head.text === 'wizard-dialog__step--tone-' &&
      expression.templateSpans.length === 1 &&
      toneCall &&
      toneCall.arguments.length === 1 &&
      isPropertyChain(toneCall.arguments[0], ['step', 'state']) &&
      expression.templateSpans[0].literal.text === '',
  )
}

function isActiveStepAriaCurrent(node) {
  const expression = unwrapExpression(node)
  if (!expression || !ts.isConditionalExpression(expression)) return false
  const matchesComparison =
    isBinary(
      expression.condition,
      ts.SyntaxKind.EqualsEqualsEqualsToken,
      (candidate) => isIdentifierValue(candidate, 'index'),
      (candidate) => isPropertyChain(candidate, ['props', 'activeStep']),
    ) ||
    isBinary(
      expression.condition,
      ts.SyntaxKind.EqualsEqualsEqualsToken,
      (candidate) => isPropertyChain(candidate, ['props', 'activeStep']),
      (candidate) => isIdentifierValue(candidate, 'index'),
    )
  return matchesComparison && isStringValue(expression.whenTrue, 'step') && isUndefinedValue(expression.whenFalse)
}

function isStepDisabledCondition(node) {
  return isBinary(
    node,
    ts.SyntaxKind.BarBarToken,
    (candidate) => isNegatedProperty(candidate, ['props', 'navigable']),
    (candidate) => isPropertyChain(candidate, ['step', 'disabled']),
  )
}

function isBackDisabledCondition(node) {
  return isBinary(
    node,
    ts.SyntaxKind.BarBarToken,
    (candidate) =>
      isBinary(
        candidate,
        ts.SyntaxKind.EqualsEqualsEqualsToken,
        (left) => isPropertyChain(left, ['props', 'activeStep']),
        (right) => isNumberValue(right, 0),
      ),
    (candidate) => isPropertyChain(candidate, ['props', 'loading']),
  )
}

function isNextDisabledCondition(node) {
  return isBinary(
    node,
    ts.SyntaxKind.BarBarToken,
    (candidate) => isNegatedProperty(candidate, ['props', 'canAdvance']),
    (candidate) => isPropertyChain(candidate, ['props', 'loading']),
  )
}

function isIntermediateStepCondition(node) {
  return isBinary(
    node,
    ts.SyntaxKind.LessThanToken,
    (candidate) => isPropertyChain(candidate, ['props', 'activeStep']),
    (candidate) =>
      isBinary(
        candidate,
        ts.SyntaxKind.MinusToken,
        (left) => isPropertyChain(left, ['props', 'steps', 'length']),
        (right) => isNumberValue(right, 1),
      ),
  )
}

function visibleStaticText(element) {
  const text = []
  const visit = (node) => {
    if (node?.type === 2 && node.content.trim()) text.push(node.content.trim())
    for (const child of node?.children ?? []) visit(child)
  }
  visit(element)
  return text.join(' ')
}

function resolvedHandlerFunction(sourceFile, node) {
  const expression = unwrapExpression(node)
  if (!expression || !ts.isIdentifier(expression)) return undefined
  const declaration = sourceFile.statements.find(
    (statement) =>
      ts.isFunctionDeclaration(statement) && statement.name?.text === expression.text,
  )
  if (declaration) return declaration
  const variable = findVariableDeclaration(sourceFile, expression.text)
  const initializer = unwrapExpression(variable?.initializer)
  return initializer && (ts.isArrowFunction(initializer) || ts.isFunctionExpression(initializer))
    ? initializer
    : undefined
}

function handlerEmitsNamed(sourceFile, node, eventName) {
  if (emitsNamed(node, eventName)) return true
  const handler = resolvedHandlerFunction(sourceFile, node)
  if (!handler || handler.parameters.length !== 0 || !handler.body || !ts.isBlock(handler.body)) {
    return false
  }
  return Boolean(
    handler.body.statements.length === 1 &&
      ts.isExpressionStatement(handler.body.statements[0]) &&
      emitsNamed(handler.body.statements[0].expression, eventName),
  )
}

function callsWizardModelClose(node) {
  const expression = unwrapExpression(node)
  if (!expression || !ts.isCallExpression(expression)) return false
  const callee = unwrapExpression(expression.expression)
  return Boolean(
    callee &&
      ts.isIdentifier(callee) &&
      (callee.text === 'emit' || callee.text === '$emit') &&
      expression.arguments.length === 2 &&
      isStringValue(expression.arguments[0], 'update:modelValue') &&
      unwrapExpression(expression.arguments[1])?.kind === ts.SyntaxKind.FalseKeyword,
  )
}

function containsWizardModelClose(node) {
  let found = false
  const visit = (candidate) => {
    if (found) return
    if (callsWizardModelClose(candidate)) {
      found = true
      return
    }
    ts.forEachChild(candidate, visit)
  }
  if (node) visit(node)
  return found
}

function handlerClosesWizard(sourceFile, node) {
  if (containsWizardModelClose(unwrapExpression(node))) return true
  const handler = resolvedHandlerFunction(sourceFile, node)
  return Boolean(handler && containsWizardModelClose(handler.body))
}

function hasWizardInternalNavigationOrOverlayScript(sourceFile) {
  let found = false
  const visit = (node) => {
    if (found) return
    if (
      ts.isImportDeclaration(node) &&
      ts.isStringLiteral(node.moduleSpecifier) &&
      /(?:focus-trap|teleport|overlay)/i.test(node.moduleSpecifier.text)
    ) {
      found = true
      return
    }
    if (
      ts.isIdentifier(node) &&
      /^(?:Teleport|ElDialog|ElDrawer|ElFocusTrap|FocusTrap)$/.test(node.text)
    ) {
      found = true
      return
    }
    if (
      ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(unwrapExpression(node.expression)) &&
      /^(?:addEventListener|removeEventListener)$/.test(
        unwrapExpression(node.expression).name.text,
      ) &&
      node.arguments.some(
        (argument) =>
          ts.isStringLiteralLike(unwrapExpression(argument)) &&
          /^(?:Escape|Esc|keydown|keyup|focus|blur|focusin|focusout)$/i.test(
            unwrapExpression(argument).text,
          ),
      )
    ) {
      found = true
      return
    }
    if (
      ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(unwrapExpression(node.expression)) &&
      /^(?:focus|blur)$/.test(unwrapExpression(node.expression).name.text)
    ) {
      found = true
      return
    }
    if (
      ts.isBinaryExpression(node) &&
      node.operatorToken.kind >= ts.SyntaxKind.FirstAssignment &&
      node.operatorToken.kind <= ts.SyntaxKind.LastAssignment &&
      (isPropertyChain(node.left, ['props', 'activeStep']) ||
        (ts.isPropertyAccessExpression(unwrapExpression(node.left)) &&
          /^(?:onkeydown|onkeyup|onfocus|onblur)$/.test(unwrapExpression(node.left).name.text)))
    ) {
      found = true
      return
    }
    if (
      (ts.isPrefixUnaryExpression(node) || ts.isPostfixUnaryExpression(node)) &&
      [ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator) &&
      isPropertyChain(node.operand, ['props', 'activeStep'])
    ) {
      found = true
      return
    }
    ts.forEachChild(node, visit)
  }
  visit(sourceFile)
  return found
}

function staticallyResolvedString(node) {
  const expression = unwrapExpression(node)
  if (!expression) return undefined
  if (ts.isStringLiteralLike(expression)) return expression.text
  if (
    ts.isBinaryExpression(expression) &&
    expression.operatorToken.kind === ts.SyntaxKind.PlusToken
  ) {
    const left = staticallyResolvedString(expression.left)
    const right = staticallyResolvedString(expression.right)
    return left !== undefined && right !== undefined ? `${left}${right}` : undefined
  }
  if (ts.isConditionalExpression(expression)) {
    if (expression.condition.kind === ts.SyntaxKind.TrueKeyword) {
      return staticallyResolvedString(expression.whenTrue)
    }
    if (expression.condition.kind === ts.SyntaxKind.FalseKeyword) {
      return staticallyResolvedString(expression.whenFalse)
    }
  }
  return undefined
}

function inlineStyleOwnsOverlayBehavior(element) {
  const dangerousStyleText =
    /(?:^|;)\s*(?:position\s*:\s*fixed\b|inset(?:-[a-z]+)?\s*:|z-index\s*:|(?:-webkit-)?backdrop-filter\s*:|box-shadow\s*:|filter\s*:)/i
  const staticStyle = staticAttributeValue(element, 'style')
  if (staticStyle && dangerousStyleText.test(staticStyle)) return true
  const boundStyle = directiveExpression(element, 'bind', 'style')
  const staticBoundStyle = staticallyResolvedString(boundStyle)
  if (staticBoundStyle && dangerousStyleText.test(staticBoundStyle)) return true
  const expression = unwrapExpression(boundStyle)
  if (!expression || !ts.isObjectLiteralExpression(expression)) return false
  return expression.properties.some((property) => {
    if (!ts.isPropertyAssignment(property)) return false
    const name = nodeName(property.name)
    if (!name) return false
    if (/^(?:inset|insetBlock|insetInline|zIndex|backdropFilter|WebkitBackdropFilter|boxShadow|filter)$/.test(name)) {
      return true
    }
    return name === 'position' && isStringValue(property.initializer, 'fixed')
  })
}

function wizardOwnsOverlayTemplateBehavior(allElements, sourceFile, ignoredCloseElements = new Set()) {
  const forbiddenTags = new Set(['el-dialog', 'el-drawer', 'teleport'])
  const forbiddenBindings = new Set([
    'append-to-body',
    'close-on-click-modal',
    'close-on-press-escape',
    'modal',
    'modal-class',
    'teleported',
    'trap-focus',
  ])
  return allElements.some((element) => {
    if (forbiddenTags.has(element.tag.toLowerCase())) return true
    if (element.tag.toLowerCase() === 'component') {
      const dynamicTarget =
        staticAttributeValue(element, 'is') ??
        staticallyResolvedString(directiveExpression(element, 'bind', 'is'))
      if (dynamicTarget && forbiddenTags.has(dynamicTarget.toLowerCase())) return true
    }
    if (staticAttributeValue(element, 'role') === 'dialog') return true
    if (inlineStyleOwnsOverlayBehavior(element)) return true
    return element.props.some((property) => {
      if (property.type !== 7) return false
      if (property.name === 'on') {
        if (
          property.arg?.isStatic &&
          /^(?:keydown|keyup|focus|blur|focusin|focusout)$/i.test(property.arg.content)
        ) {
          return true
        }
        if (property.modifiers?.some((modifier) => modifier.content === 'esc')) return true
        return Boolean(
          !ignoredCloseElements.has(element) &&
            handlerClosesWizard(sourceFile, parseTemplateExpression(property.exp?.content)),
        )
      }
      if (property.name === 'bind' && property.arg?.isStatic) {
        return forbiddenBindings.has(property.arg.content)
      }
      return /^(?:teleport|focus-lock)$/i.test(property.name)
    })
  })
}

function wizardOwnsPrivateGlassStyle(codes) {
  const forbiddenProperties = new Set([
    'background',
    'background-color',
    'background-image',
    'border',
    'box-shadow',
    '-webkit-box-shadow',
    'backdrop-filter',
    '-webkit-backdrop-filter',
    'filter',
  ])
  return ['wizard-dialog', 'wizard-dialog__rail'].some((className) => {
    const escaped = className.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const exactSelector = new RegExp(`^\\.${escaped}(?:\\[data-v-[^\\]]+\\])?$`)
    return compiledRules(codes).some(
      ({ selector, declarations }) =>
        exactSelector.test(selector) &&
        declarations.some(({ property }) => forbiddenProperties.has(property)),
    )
  })
}

function hasWizardCurrentBrandStyle(codes) {
  return rulesForClass(codes, 'wizard-dialog__step--state-current').some(
    ({ selector, declarations }) =>
      selector.includes('.wizard-dialog__step-button') &&
      declarations.some(
        ({ property, value }) => property === 'color' && value === 'var(--brand-active)',
      ) &&
      declarations.some(
        ({ property, value }) =>
          property === 'background' && value === 'var(--surface-glass-selected)',
      ) &&
      declarations.some(
        ({ property, value }) =>
          property === 'border' && value === '1px solid var(--border-focus)',
      ),
  )
}

async function validateWizardDialog(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const transformedAst = transformedTemplateAst(
    descriptor.template?.content ?? '',
    label,
    localFailures,
  )
  const entries = templateElementEntries(ast).filter((entry) => entry.reachable)
  const elements = entries.map((entry) => entry.element)
  const allElements = allTemplateElements(ast)
  const rootElement = rootTemplateElement(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  if (
    !hasExactTypedProps(contract, [
      ['modelValue', 'boolean', false],
      ['title', 'string', false],
      ['description', 'string', true],
      ['steps', 'WizardStep[]', false],
      ['activeStep', 'number', false],
      ['navigable', 'boolean', true],
      ['canAdvance', 'boolean', true],
      ['loading', 'boolean', true],
      ['finishLabel', 'string', true],
    ])
  ) {
    localFailures.push(`${label} must declare exactly the controlled WizardDialog props`)
  }
  for (const [name, expected] of [
    ['navigable', false],
    ['canAdvance', true],
    ['loading', false],
    ['finishLabel', '完成'],
  ]) {
    if (!defaultMatches(contract, name, expected)) {
      localFailures.push(`${label} ${name} must use its WizardDialog default`)
    }
  }
  if (
    !declaresExactEmits(sourceFile, [
      ['update:modelValue', ['boolean']],
      ['stepChange', ['number']],
      ['back', []],
      ['next', []],
      ['cancel', []],
      ['finish', []],
    ])
  ) {
    localFailures.push(`${label} must declare exactly the controlled wizard emits`)
  }
  if (
    !importsDefault(sourceFile, 'AppDialog', './AppDialog.vue') ||
    !importsSymbol(sourceFile, './glassWorkbench', 'densityClass') ||
    !importsSymbol(sourceFile, './glassWorkbench', 'StatusTone') ||
    !importsSymbol(sourceFile, './glassWorkbench', 'WizardStep') ||
    !importsSymbol(sourceFile, './glassWorkbench', 'WizardStepState')
  ) {
    localFailures.push(`${label} must consume AppDialog, densityClass, WizardStep, WizardStepState, and StatusTone`)
  }
  if (!validatesActiveErrorStep(sourceFile)) {
    localFailures.push(`${label} activeErrorStep must derive only the active error step`)
  }
  if (!validatesWizardStepTone(sourceFile)) {
    localFailures.push(`${label} step states must map through StatusTone semantic roles`)
  }

  if (!rootElement || rootElement.tag !== 'AppDialog') {
    localFailures.push(`${label} root must be the sole AppDialog overlay`)
  } else {
    for (const [attribute, property] of [
      ['model-value', 'modelValue'],
      ['title', 'title'],
      ['description', 'description'],
    ]) {
      if (
        !hasNoDirectiveModifiers(rootElement, 'bind', attribute) ||
        !isPropertyChain(directiveExpression(rootElement, 'bind', attribute), ['props', property])
      ) {
        localFailures.push(`${label} must forward props.${property} directly to AppDialog`)
      }
    }
    if (
      !hasNoDirectiveModifiers(rootElement, 'on', 'update:model-value') ||
      !emitsNamed(
        directiveExpression(rootElement, 'on', 'update:model-value'),
        'update:modelValue',
        '$event',
      )
    ) {
      localFailures.push(`${label} must forward AppDialog model updates directly`)
    }
  }

  const composition = elements.find(
    (element) =>
      hasStaticClass(element, 'wizard-dialog') ||
      classArrayElements(element).some((entry) => isStringValue(entry, 'wizard-dialog')),
  )
  const compositionClasses = classArrayElements(composition)
  if (
    !composition ||
    entries.find((entry) => entry.element === composition)?.parent !== rootElement ||
    !compositionClasses.some(isSpaciousDensityClassCall)
  ) {
    localFailures.push(`${label} composition must apply densityClass('spacious') directly under AppDialog`)
  }

  const slots = slotElements(ast)
  const slotNames = slots.map((slot) => staticAttributeValue(slot, 'name') ?? 'default').sort()
  if (!arraysEqual(slotNames, ['default', 'footer', 'summary'])) {
    localFailures.push(`${label} must expose exactly summary/default/footer slots`)
  }

  const rail = elements.find((element) => hasStaticClass(element, 'wizard-dialog__rail'))
  const summarySlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'summary')
  const recipeClasses = allElements
    .flatMap((element) => [
      ...(staticAttributeValue(element, 'class')?.split(/\s+/) ?? []),
      ...classArrayElements(element)
        .filter((entry) => ts.isStringLiteralLike(entry))
        .map((entry) => entry.text),
    ])
    .filter((name) => name.startsWith('glass-surface-'))
  if (
    !rail ||
    !hasStaticClass(rail, 'glass-surface-control') ||
    !summarySlot ||
    !descendantElements(ast, rail).includes(summarySlot) ||
    !arraysEqual(recipeClasses, ['glass-surface-control'])
  ) {
    localFailures.push(`${label} rail must own the summary slot and exactly one glass-surface-control recipe`)
  }

  const orderedLists = elements.filter((element) => element.tag === 'ol')
  const orderedList = orderedLists.length === 1 ? orderedLists[0] : undefined
  const transformedOrderedLists = transformedTemplateNodes(transformedAst).filter(
    (node) => node.type === 1 && node.tag === 'ol',
  )
  const transformedOrderedList =
    transformedOrderedLists.length === 1 ? transformedOrderedLists[0] : undefined
  const forNodes = transformedOrderedList?.children?.filter((node) => node.type === 11) ?? []
  const forNode = forNodes.length === 1 ? forNodes[0] : undefined
  const forSource = parseTemplateExpression(forNode?.source?.content)
  const transformedStepItem =
    forNode?.children?.length === 1 && forNode.children[0].type === 1
      ? forNode.children[0]
      : undefined
  const orderedDirectElements = orderedList
    ? entries
        .filter((entry) => entry.parent === orderedList)
        .map((entry) => entry.element)
    : []
  if (
    !orderedList ||
    staticAttributeValue(orderedList, 'aria-label') !== '步骤' ||
    !rail ||
    !descendantElements(ast, rail).includes(orderedList) ||
    !forNode ||
    forNode.valueAlias?.content !== 'step' ||
    forNode.keyAlias?.content !== 'index' ||
    !isPropertyChain(forSource, ['props', 'steps']) ||
    !transformedStepItem ||
    transformedStepItem.tag !== 'li' ||
    !isPropertyChain(directiveExpression(transformedStepItem, 'bind', 'key'), ['step', 'key']) ||
    orderedDirectElements.length !== 1 ||
    orderedDirectElements[0].tag !== 'li' ||
    !directive(orderedDirectElements[0], 'for', undefined)
  ) {
    localFailures.push(`${label} must render one keyed ordered item per props.steps entry`)
  }

  const stepItems = elements.filter((element) => hasStaticClass(element, 'wizard-dialog__step'))
  const stepItem = stepItems.length === 1 ? stepItems[0] : undefined
  if (
    !stepItem ||
    !isActiveStepAriaCurrent(directiveExpression(stepItem, 'bind', 'aria-current')) ||
    !classArrayMatchesExactly(stepItem, [isWizardStateClass, isWizardToneClass])
  ) {
    localFailures.push(`${label} step item must expose active aria-current and semantic state/tone classes`)
  }

  const stepButtons = elements.filter((element) => hasStaticClass(element, 'wizard-dialog__step-button'))
  const stepButton = stepButtons.length === 1 ? stepButtons[0] : undefined
  if (
    !stepButton ||
    staticAttributeValue(stepButton, 'type') !== 'button' ||
    !isStepDisabledCondition(directiveExpression(stepButton, 'bind', 'disabled')) ||
    !hasNoDirectiveModifiers(stepButton, 'on', 'click') ||
    !emitsNamed(directiveExpression(stepButton, 'on', 'click'), 'stepChange', 'index')
  ) {
    localFailures.push(`${label} step buttons must disable inaccessible steps and emit stepChange(index)`)
  }
  const stepTitle = elements.find((element) => hasStaticClass(element, 'wizard-dialog__step-title'))
  const stepDescription = elements.find((element) =>
    hasStaticClass(element, 'wizard-dialog__step-description'),
  )
  if (
    !stepItem ||
    !stepTitle ||
    !descendantElements(ast, stepItem).includes(stepTitle) ||
    !hasInterpolationProperty(stepTitle, ['step', 'title']) ||
    !stepDescription ||
    !isPropertyChain(directiveExpression(stepDescription, 'if', undefined), ['step', 'description']) ||
    !hasInterpolationProperty(stepDescription, ['step', 'description'])
  ) {
    localFailures.push(`${label} step controls must render each step title and optional description`)
  }

  const body = elements.find((element) => hasStaticClass(element, 'wizard-dialog__body'))
  const defaultSlot = slots.find((slot) => !staticAttribute(slot, 'name'))
  if (
    !body ||
    body.tag !== 'main' ||
    !defaultSlot ||
    !descendantElements(ast, body).includes(defaultSlot) ||
    !(
      hasClassDeclaration(styles, 'wizard-dialog__body', 'overflow', /^(?:auto|scroll)$/) ||
      hasClassDeclaration(styles, 'wizard-dialog__body', 'overflow-y', /^(?:auto|scroll)$/)
    )
  ) {
    localFailures.push(`${label} main body must own the default slot and a compiled scroll boundary`)
  }

  const liveRegion = elements.find((element) => hasStaticClass(element, 'wizard-dialog__live'))
  const validation = elements.find((element) => hasStaticClass(element, 'wizard-dialog__validation'))
  const validationTitle = elements.find((element) =>
    hasStaticClass(element, 'wizard-dialog__validation-title'),
  )
  const validationDescription = elements.find((element) =>
    hasStaticClass(element, 'wizard-dialog__validation-description'),
  )
  const liveInterpolationChains = liveRegion
    ? templateInterpolationEntries(liveRegion).map(({ node }) =>
        propertyChain(parseTemplateExpression(node.content?.content)),
      )
    : []
  if (
    !liveRegion ||
    staticAttributeValue(liveRegion, 'aria-live') !== 'polite' ||
    staticAttributeValue(liveRegion, 'aria-atomic') !== 'true' ||
    !body ||
    !descendantElements(ast, body).includes(liveRegion) ||
    !validation ||
    !isIdentifierValue(directiveExpression(validation, 'if', undefined), 'activeErrorStep') ||
    !validationTitle ||
    !hasInterpolationProperty(validationTitle, ['activeErrorStep', 'title']) ||
    !validationDescription ||
    !isPropertyChain(directiveExpression(validationDescription, 'if', undefined), [
      'activeErrorStep',
      'description',
    ]) ||
    !hasInterpolationProperty(validationDescription, ['activeErrorStep', 'description']) ||
    visibleStaticText(liveRegion) !== '' ||
    JSON.stringify(liveInterpolationChains) !==
      JSON.stringify([
        ['activeErrorStep', 'title'],
        ['activeErrorStep', 'description'],
      ])
  ) {
    localFailures.push(`${label} polite live region must announce only the active error step title/description`)
  }

  const entryFor = (element) => entries.find((entry) => entry.element === element)
  const footerSlot = slots.find((slot) => staticAttributeValue(slot, 'name') === 'footer')
  const footerProvider = entryFor(footerSlot)?.parent
  const defaultFooter = footerSlot
    ? descendantElements(ast, footerSlot).find((element) =>
        hasStaticClass(element, 'wizard-dialog__footer'),
      )
    : undefined
  if (
    !footerSlot ||
    !footerProvider ||
    footerProvider.tag !== 'template' ||
    entryFor(footerProvider)?.parent !== rootElement ||
    !directive(footerProvider, 'slot', 'footer') ||
    !defaultFooter ||
    entryFor(defaultFooter)?.parent !== footerSlot ||
    (body && descendantElements(ast, body).includes(defaultFooter))
  ) {
    localFailures.push(`${label} footer slot must replace a stable AppDialog footer outside the scroll body`)
  }

  const footerFallbackDirectElements = footerSlot
    ? entries
        .filter((entry) => entry.parent === footerSlot)
        .map((entry) => entry.element)
    : []
  const footerButtons = footerSlot
    ? descendantElements(ast, footerSlot).filter(
        (element) => element.tag.toLowerCase() === 'el-button',
      )
    : []
  const footerFallbackIsExact = Boolean(
    defaultFooter &&
      footerFallbackDirectElements.length === 1 &&
      footerFallbackDirectElements[0] === defaultFooter &&
      footerButtons.length === 4 &&
      footerButtons.every((button) => descendantElements(ast, defaultFooter).includes(button)),
  )
  if (!footerFallbackIsExact) {
    localFailures.push(
      `${label} default footer fallback must contain only the target footer container and its four actions`,
    )
  }
  const buttonFor = (eventName) =>
    footerButtons.filter((button) =>
      handlerEmitsNamed(
        sourceFile,
        directiveExpression(button, 'on', 'click'),
        eventName,
      ),
    )
  const cancelButtons = buttonFor('cancel')
  const backButtons = buttonFor('back')
  const nextButtons = buttonFor('next')
  const finishButtons = buttonFor('finish')
  const cancelButton = cancelButtons.length === 1 ? cancelButtons[0] : undefined
  const backButton = backButtons.length === 1 ? backButtons[0] : undefined
  const nextButton = nextButtons.length === 1 ? nextButtons[0] : undefined
  const finishButton = finishButtons.length === 1 ? finishButtons[0] : undefined
  if (
    footerFallbackIsExact &&
    (
    !cancelButton ||
    staticAttributeValue(cancelButton, 'type') === 'primary' ||
    staticAttributeValue(cancelButton, 'native-type') !== 'button' ||
    visibleStaticText(cancelButton) !== '取消' ||
    !backButton ||
    staticAttributeValue(backButton, 'type') === 'primary' ||
    staticAttributeValue(backButton, 'native-type') !== 'button' ||
    visibleStaticText(backButton) !== '上一步' ||
    !isBackDisabledCondition(directiveExpression(backButton, 'bind', 'disabled'))
    )
  ) {
    localFailures.push(`${label} default footer must contain only secondary Cancel and guarded Back actions`)
  }
  if (
    footerFallbackIsExact &&
    (
    !nextButton ||
    staticAttributeValue(nextButton, 'type') !== 'primary' ||
    staticAttributeValue(nextButton, 'native-type') !== 'button' ||
    visibleStaticText(nextButton) !== '下一步' ||
    !isIntermediateStepCondition(directiveExpression(nextButton, 'if', undefined)) ||
    !isNextDisabledCondition(directiveExpression(nextButton, 'bind', 'disabled')) ||
    !finishButton ||
    staticAttributeValue(finishButton, 'type') !== 'primary' ||
    staticAttributeValue(finishButton, 'native-type') !== 'button' ||
    !directive(finishButton, 'else', undefined) ||
    !isNegatedProperty(directiveExpression(finishButton, 'bind', 'disabled'), [
      'props',
      'canAdvance',
    ]) ||
    !isPropertyChain(directiveExpression(finishButton, 'bind', 'loading'), ['props', 'loading']) ||
    !hasInterpolationProperty(finishButton, ['props', 'finishLabel']) ||
    visibleStaticText(finishButton) !== ''
    )
  ) {
    localFailures.push(`${label} default footer must render mutually exclusive guarded primary Next or Finish`)
  }

  if (
    hasWizardInternalNavigationOrOverlayScript(sourceFile) ||
    wizardOwnsOverlayTemplateBehavior(allElements, sourceFile, new Set(footerButtons))
  ) {
    localFailures.push(`${label} must not own navigation state, close effects, mask, focus, Escape, or Teleport behavior`)
  }
  if (hasPageScopedElementPlusSkin(styles)) {
    localFailures.push(`${label} must not contain page-scoped Element Plus skin`)
  }
  if (wizardOwnsPrivateGlassStyle(styles)) {
    localFailures.push(`${label} must leave glass surface and overlay recipes to shared owners`)
  }
  if (!hasWizardCurrentBrandStyle(styles)) {
    localFailures.push(`${label} current step must use the brand-selected semantic surface`)
  }
  const requiredVariables = [
    '--panel-padding',
    '--section-gap',
    '--text-primary',
    '--text-secondary',
    '--text-muted',
    '--text-disabled',
    '--border-focus',
    ...['success', 'danger', 'info', 'neutral'].flatMap((tone) => [
      `--status-${tone}`,
      `--status-${tone}-soft`,
    ]),
  ]
  if (!requiredVariables.every((variable) => styleUsesVariable(styles, variable))) {
    localFailures.push(`${label} compiled styles must consume density, text, focus, and emitted StatusTone roles`)
  }
  for (const tone of ['success', 'danger', 'info', 'neutral']) {
    if (rulesForClass(styles, `wizard-dialog__step--tone-${tone}`).length === 0) {
      localFailures.push(`${label} compiled styles must expose semantic tone class ${tone}`)
    }
  }
  validateRecipeOwnership(styles, label, localFailures)
  return localFailures
}

const requiredElementPlusMappings = [
  {
    selector: '.el-table',
    declarations: {
      '--el-table-header-bg-color': 'var(--surface-solid-control)',
      '--el-table-header-text-color': 'var(--text-muted)',
      '--el-table-text-color': 'var(--text-secondary)',
      '--el-table-border-color': 'var(--border-subtle)',
    },
  },
  {
    selector: '.el-pagination',
    declarations: {
      '--el-fill-color-blank': 'var(--surface-solid-control)',
      '--el-pagination-button-color': 'var(--text-secondary)',
      '--el-pagination-button-disabled-color': 'var(--text-disabled)',
      '--el-pagination-button-disabled-bg-color': 'var(--surface-solid-disabled)',
      '--el-color-primary': 'var(--brand-primary)',
      '--el-border-color': 'var(--border-readable)',
    },
  },
  {
    selector: '.el-tabs',
    declarations: {
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-text-color-regular': 'var(--text-secondary)',
      '--el-border-color-light': 'var(--border-subtle)',
      '--el-color-primary': 'var(--brand-primary)',
    },
  },
  {
    selector: '.el-dialog',
    declarations: {
      '--el-dialog-bg-color': 'var(--surface-solid-overlay)',
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-border-color': 'var(--border-readable)',
    },
  },
  {
    selector: '.el-drawer',
    declarations: {
      '--el-drawer-bg-color': 'var(--surface-solid-overlay)',
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-border-color': 'var(--border-readable)',
    },
  },
]

const requiredElementPlusFocusSelector =
  '.el-pagination .btn-prev:focus-visible, .el-pagination .btn-next:focus-visible, .el-pagination .el-pager li:focus-visible, .el-tabs__item:focus-visible'

function validateElementPlusMappings(source, label) {
  const localFailures = []
  let css
  try {
    css = sass.compileString(source, { style: 'expanded' }).css
  } catch (error) {
    return [`${label} Sass compile failed: ${formatCompilerError(error)}`]
  }
  const rules = compiledRules([css])
  for (const { selector, declarations } of requiredElementPlusMappings) {
    const exactRules = rules.filter((rule) => rule.selector === selector)
    if (exactRules.length !== 1) {
      localFailures.push(`${label} must contain exactly one global ${selector} mapping`)
      continue
    }
    for (const [property, expectedValue] of Object.entries(declarations)) {
      const matches = exactRules[0].declarations.filter(
        (declaration) => declaration.property === property && declaration.value === expectedValue,
      )
      if (matches.length !== 1) {
        localFailures.push(`${label} ${selector} must map ${property} to ${expectedValue}`)
      }
    }
    for (const { value } of exactRules[0].declarations) {
      for (const literal of forbiddenColorLiterals(value)) {
        localFailures.push(`${label} ${selector} must not contain raw product color ${literal}`)
      }
    }
  }
  const focusRules = rules.filter(({ selector }) => selector === requiredElementPlusFocusSelector)
  if (focusRules.length !== 1) {
    localFailures.push(
      `${label} pagination focus mapping must explicitly target btn-prev and btn-next at Element Plus specificity`,
    )
  } else if (
    !focusRules[0].declarations.some(
      ({ property, value }) =>
        property === 'outline' && /^2px solid var\(--border-focus\)$/.test(value),
    )
  ) {
    localFailures.push(`${label} pagination and tabs must share the semantic focus-visible ring`)
  }
  return localFailures
}

function validatesStatusTagType(sourceFile) {
  const body = computedInitializer(sourceFile, 'tagType')
  if (!body || !ts.isConditionalExpression(body)) return false
  const condition = unwrapExpression(body.condition)
  return Boolean(
    condition &&
      ts.isBinaryExpression(condition) &&
      condition.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken &&
      isPropertyChain(condition.left, ['props', 'tone']) &&
      isStringValue(condition.right, 'neutral') &&
      isStringValue(body.whenTrue, 'info') &&
      isPropertyChain(body.whenFalse, ['props', 'tone']),
  )
}

function isToneClassTemplate(node, sourceProperty) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isTemplateExpression(expression) &&
      expression.head.text === 'status-tag--' &&
      expression.templateSpans.length === 1 &&
      isPropertyChain(expression.templateSpans[0].expression, sourceProperty) &&
      expression.templateSpans[0].literal.text === '',
  )
}

async function validateMetricStrip(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const transformedAst = transformedTemplateAst(
    descriptor.template?.content ?? '',
    label,
    localFailures,
  )
  const rootElement = rootTemplateElement(ast)
  const transformedRoot = transformedRootElement(transformedAst)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  for (const [name, type, optional] of [
    ['items', 'MetricStripItem[]', false],
    ['density', 'WorkbenchDensity', true],
    ['ariaLabel', 'string', true],
  ]) {
    if (!hasTypedProp(contract, name, type, optional)) {
      localFailures.push(`${label} must declare prop ${name}${optional ? '?' : ''}: ${type}`)
    }
  }
  if (!defaultMatches(contract, 'density', 'comfortable')) {
    localFailures.push(`${label} density must default to 'comfortable'`)
  }
  if (!defaultMatches(contract, 'ariaLabel', '指标概览')) {
    localFailures.push(`${label} ariaLabel must default to '指标概览'`)
  }
  if (!importsDefault(sourceFile, 'MetricIconBg', './MetricIconBg.vue')) {
    localFailures.push(`${label} must import MetricIconBg from ./MetricIconBg.vue`)
  }

  const rootClasses = classArrayElements(rootElement)
  const hasRootClass = rootClasses.some((entry) => isStringValue(entry, 'metric-strip'))
  const hasPanelRecipe = rootClasses.some((entry) => isStringValue(entry, 'glass-surface-panel'))
  const hasDensityClass = rootClasses.some(isDensityClassCall)
  if (!hasRootClass || !hasPanelRecipe || !hasDensityClass) {
    localFailures.push(
      `${label} root must bind metric-strip, glass-surface-panel, and densityClass(props.density)`,
    )
  } else if (
    !classArrayMatchesExactly(rootElement, [
      (entry) => isStringValue(entry, 'metric-strip'),
      (entry) => isStringValue(entry, 'glass-surface-panel'),
      isDensityClassCall,
    ])
  ) {
    localFailures.push(`${label} root class binding must contain only the shared metric strip recipe`)
  }
  if (staticAttributeValue(rootElement, 'role') !== 'list') {
    localFailures.push(`${label} root must render role="list"`)
  }
  if (
    !isPropertyChain(directiveExpression(rootElement, 'bind', 'aria-label'), [
      'props',
      'ariaLabel',
    ])
  ) {
    localFailures.push(`${label} root must bind props.ariaLabel to aria-label`)
  }

  const directForNodes = transformedRoot?.children?.filter((node) => node.type === 11) ?? []
  const forNode = directForNodes.length === 1 ? directForNodes[0] : undefined
  const forSource = parseTemplateExpression(forNode?.source?.content)
  const itemElement =
    forNode?.children?.length === 1 && forNode.children[0].type === 1
      ? forNode.children[0]
      : undefined
  if (
    !forNode ||
    forNode.valueAlias?.content !== 'item' ||
    !isPropertyChain(forSource, ['props', 'items']) ||
    !itemElement ||
    staticAttributeValue(itemElement, 'role') !== 'listitem' ||
    !isPropertyChain(directiveExpression(itemElement, 'bind', 'key'), ['item', 'key'])
  ) {
    localFailures.push(`${label} must render one direct keyed role="listitem" per props.items entry`)
  }

  const itemNodes = itemElement ? transformedTemplateNodes(itemElement) : []
  const metricIcons = itemNodes.filter((node) => node.type === 1 && node.tag === 'MetricIconBg')
  const metricIcon = metricIcons.length === 1 ? metricIcons[0] : undefined
  if (
    !metricIcon ||
    !isPropertyChain(directiveExpression(metricIcon, 'bind', 'icon-key'), ['item', 'iconKey']) ||
    !isNullishDefault(
      directiveExpression(metricIcon, 'bind', 'tone'),
      ['item', 'tone'],
      'brand',
    )
  ) {
    localFailures.push(`${label} each item must render MetricIconBg from item.iconKey and item.tone`)
  }

  for (const [className, property] of [
    ['metric-strip__label', 'label'],
    ['metric-strip__value', 'value'],
  ]) {
    const element = elements.find((candidate) => hasStaticClass(candidate, className))
    if (!element || !hasInterpolationProperty(element, ['item', property])) {
      localFailures.push(`${label} must render item.${property} in ${className}`)
    }
  }
  const hint = elements.find((candidate) => hasStaticClass(candidate, 'metric-strip__hint'))
  if (
    !hint ||
    !isPropertyChain(directiveExpression(hint, 'if', undefined), ['item', 'hint']) ||
    !hasInterpolationProperty(hint, ['item', 'hint'])
  ) {
    localFailures.push(`${label} must render optional item.hint`)
  }

  const itemHasGlassClass = elements.some((element) => {
    const classNames = staticAttributeValue(element, 'class')?.split(/\s+/) ?? []
    return hasStaticClass(element, 'metric-strip__item') && classNames.some((name) => name.startsWith('glass-surface-'))
  })
  const itemRules = rulesForClass(styles, 'metric-strip__item')
  const itemOwnsCardRecipe = itemRules.some(({ declarations }) =>
    declarations.some(({ property }) =>
      [
        'background',
        'background-color',
        'box-shadow',
        '-webkit-box-shadow',
        'backdrop-filter',
        '-webkit-backdrop-filter',
      ].includes(property),
    ),
  )
  if (itemHasGlassClass || itemOwnsCardRecipe) {
    localFailures.push(`${label} items must not own an independent shadow/card glass recipe`)
  }
  const hasDivider = compiledRules(styles).some(
    ({ selector, declarations }) =>
      selector.includes('.metric-strip__item + .metric-strip__item') &&
      declarations.some(
        ({ property, value }) =>
          ['border-inline-start', 'border-left'].includes(property) &&
          /var\(\s*--border-divider\s*\)/.test(value),
      ),
  )
  if (!hasDivider) localFailures.push(`${label} must render tokenized internal item dividers`)
  if (!styleUsesVariable(styles, '--panel-padding') || !styleUsesVariable(styles, '--section-gap')) {
    localFailures.push(`${label} styles must consume foundation density variables`)
  }
  const rootRadiusProperties = new Set([
    'border-radius',
    'border-top-left-radius',
    'border-top-right-radius',
    'border-bottom-right-radius',
    'border-bottom-left-radius',
    'border-start-start-radius',
    'border-start-end-radius',
    'border-end-start-radius',
    'border-end-end-radius',
  ])
  const rootRadiusDeclarations = compiledRules(styles)
    .filter(({ selector }) => /^\.metric-strip(?:\[data-v-[^\]]+\])?$/.test(selector))
    .flatMap(({ declarations }) =>
      declarations.filter(({ property }) => rootRadiusProperties.has(property)),
    )
  if (
    rootRadiusDeclarations.length !== 1 ||
    rootRadiusDeclarations[0].property !== 'border-radius' ||
    rootRadiusDeclarations[0].value !== 'var(--radius-lg)'
  ) {
    localFailures.push(`${label} root must own exactly border-radius: var(--radius-lg)`)
  }
  return localFailures
}

async function validateStatusTag(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!descriptor.scriptSetup || descriptor.scriptSetup.lang !== 'ts') {
    localFailures.push(`${label} must use <script setup lang="ts">`)
  }
  for (const [name, type, optional] of [
    ['label', 'string', false],
    ['tone', 'StatusTone', true],
    ['size', ['small', 'default', 'large'], true],
    ['pulse', 'boolean', true],
  ]) {
    if (!hasTypedProp(contract, name, type, optional)) {
      const renderedType = Array.isArray(type) ? type.map((value) => `'${value}'`).join(' | ') : type
      localFailures.push(`${label} must declare prop ${name}${optional ? '?' : ''}: ${renderedType}`)
    }
  }
  if (!defaultMatches(contract, 'tone', 'neutral')) {
    localFailures.push(`${label} tone must default to 'neutral'`)
  }
  if (!defaultMatches(contract, 'size', 'small')) {
    localFailures.push(`${label} size must default to 'small'`)
  }
  if (!defaultMatches(contract, 'pulse', false)) {
    localFailures.push(`${label} pulse must default to false`)
  }
  if (!validatesStatusTagType(sourceFile)) {
    localFailures.push(`${label} must map neutral to Element Plus info and preserve semantic tag types`)
  }

  if (!rootElement || rootElement.tag.toLowerCase() !== 'el-tag' || !hasStaticClass(rootElement, 'status-tag')) {
    localFailures.push(`${label} must render exactly one root el-tag.status-tag`)
  }
  if (!isIdentifierValue(directiveExpression(rootElement, 'bind', 'type'), 'tagType')) {
    localFailures.push(`${label} el-tag must bind the semantic tagType`)
  }
  if (!isPropertyChain(directiveExpression(rootElement, 'bind', 'size'), ['props', 'size'])) {
    localFailures.push(`${label} el-tag must bind props.size`)
  }
  if (!isToneClassTemplate(directiveExpression(rootElement, 'bind', 'class'), ['props', 'tone'])) {
    localFailures.push(`${label} el-tag must bind the semantic status-tag tone class`)
  }
  if (!hasInterpolationProperty(rootElement, ['props', 'label'])) {
    localFailures.push(`${label} el-tag must render props.label`)
  }
  const pulseMarkers = elements.filter((element) => hasStaticClass(element, 'status-tag__pulse'))
  const pulseMarker = pulseMarkers.length === 1 ? pulseMarkers[0] : undefined
  if (
    !pulseMarker ||
    !isPropertyChain(directiveExpression(pulseMarker, 'if', undefined), ['props', 'pulse']) ||
    staticAttributeValue(pulseMarker, 'aria-hidden') !== 'true'
  ) {
    localFailures.push(`${label} pulse marker must be aria-hidden and render only from props.pulse`)
  }
  if (sourceContainsBusinessEnum(sourceFile) || templateContainsBusinessEnum(ast)) {
    localFailures.push(`${label} must not embed business status enum text`)
  }
  if (elements.some((element) => (staticAttributeValue(element, 'class') ?? '').includes('glass-surface-'))) {
    localFailures.push(`${label} must not own a private glass recipe`)
  }
  if (hasAnyDeclaration(styles, ['box-shadow', '-webkit-box-shadow', 'backdrop-filter', '-webkit-backdrop-filter'])) {
    localFailures.push(`${label} styles must not own glass shadows or backdrop filters`)
  }
  const requiredStatusVariables = ['success', 'warning', 'danger', 'info', 'neutral'].flatMap(
    (tone) => [`--status-${tone}`, `--status-${tone}-soft`],
  )
  if (!requiredStatusVariables.every((variable) => styleUsesVariable(styles, variable))) {
    localFailures.push(`${label} styles must use all semantic status color roles`)
  }
  return localFailures
}

function objectLiteralStringMap(sourceFile, name) {
  const declaration = findVariableDeclaration(sourceFile, name)
  const initializer = unwrapExpression(declaration?.initializer)
  if (!initializer || !ts.isObjectLiteralExpression(initializer)) return undefined
  const entries = []
  for (const property of initializer.properties) {
    if (!ts.isPropertyAssignment(property)) return undefined
    const key = nodeName(property.name)
    const value = unwrapExpression(property.initializer)
    if (!key || !value || !ts.isStringLiteralLike(value)) return undefined
    entries.push([key, value.text])
  }
  return Object.fromEntries(entries)
}

function validatesSemanticTone(sourceFile) {
  const body = computedInitializer(sourceFile, 'semanticTone')
  if (!body || !ts.isBinaryExpression(body)) return false
  const left = unwrapExpression(body.left)
  return Boolean(
    body.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken &&
      left &&
      ts.isElementAccessExpression(left) &&
      isIdentifierValue(left.expression, 'toneAliases') &&
      isPropertyChain(left.argumentExpression, ['props', 'tone']) &&
      isStringValue(body.right, 'brand'),
  )
}

function isMetricToneClassTemplate(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isTemplateExpression(expression) &&
      expression.head.text === 'metric-icon-bg--' &&
      expression.templateSpans.length === 1 &&
      isIdentifierValue(expression.templateSpans[0].expression, 'semanticTone') &&
      expression.templateSpans[0].literal.text === '',
  )
}

async function validateMetricIconBg(source, label) {
  const localFailures = await validateCommonSfc(source, label, {
    allowedVariables: [
      '--metric-icon-bg-size',
      '--metric-icon-bg-radius',
      '--metric-icon-glyph-size',
      '--metric-icon-stroke-width',
    ],
  })
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!hasTypedProp(contract, 'iconKey', 'string', true) || !hasTypedProp(contract, 'tone', 'string', true)) {
    localFailures.push(`${label} must keep permissive optional string iconKey and tone props`)
  }
  if (!defaultMatches(contract, 'iconKey', 'scan') || !defaultMatches(contract, 'tone', 'brand')) {
    localFailures.push(`${label} must preserve scan/brand prop defaults`)
  }
  const actualToneAliases = objectLiteralStringMap(sourceFile, 'toneAliases')
  const expectedToneAliases = {
    brand: 'brand',
    success: 'success',
    warning: 'warning',
    danger: 'danger',
    info: 'info',
    neutral: 'neutral',
    green: 'success',
    orange: 'warning',
    muted: 'neutral',
  }
  if (JSON.stringify(actualToneAliases) !== JSON.stringify(expectedToneAliases)) {
    localFailures.push(`${label} must map semantic tones plus green/orange/muted compatibility aliases`)
  }
  if (!validatesSemanticTone(sourceFile)) {
    localFailures.push(`${label} semanticTone must normalize aliases and unknown strings to brand`)
  }
  if (
    !rootElement ||
    rootElement.tag !== 'span' ||
    !hasStaticClass(rootElement, 'metric-icon-bg') ||
    !isMetricToneClassTemplate(directiveExpression(rootElement, 'bind', 'class'))
  ) {
    localFailures.push(`${label} root must render the normalized semantic tone class`)
  }
  const slot = elements.find((element) => element.tagType === 2 && element.tag === 'slot')
  const hasSvgFallback = elements.some(
    (element) => element.tag === 'svg' && (directive(element, 'else-if', undefined) || directive(element, 'else', undefined)),
  )
  if (
    !slot ||
    !isPropertyChain(directiveExpression(slot, 'if', undefined), ['$slots', 'default']) ||
    !hasSvgFallback
  ) {
    localFailures.push(`${label} must preserve slot-first SVG fallback behavior`)
  }
  for (const tone of ['brand', 'success', 'warning', 'danger', 'info', 'neutral']) {
    if (rulesForClass(styles, `metric-icon-bg--${tone}`).length === 0) {
      localFailures.push(`${label} compiled styles must provide semantic class ${tone}`)
    }
  }
  const requiredVariables = [
    '--brand-active',
    '--surface-glass-control',
    '--surface-glass-selected',
    '--inner-highlight',
    ...['success', 'warning', 'danger', 'info', 'neutral'].flatMap((tone) => [
      `--status-${tone}`,
      `--status-${tone}-soft`,
    ]),
  ]
  if (!requiredVariables.every((variable) => styleUsesVariable(styles, variable))) {
    localFailures.push(`${label} styles must use the foundation brand/status/surface roles`)
  }
  for (const { property, value } of compiledRules(styles).flatMap(({ declarations }) => declarations)) {
    if (property === 'filter' && /drop-shadow\s*\(/i.test(value)) {
      localFailures.push(`${label} compiled styles must not contain literal drop-shadow filters`)
    }
  }
  return localFailures
}

async function validateCommonStatusTag(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const contract = propsContract(sourceFile)
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)

  if (
    contract.typeLiteral?.members.length !== 1 ||
    !hasTypedProp(contract, 'status', 'string | null', true)
  ) {
    localFailures.push(`${label} must keep the exact status?: string | null public prop`)
  }
  if (!importsDefault(sourceFile, 'StatusTag', './common/StatusTag.vue')) {
    localFailures.push(`${label} must import the shared StatusTag adapter target`)
  }
  if (
    !importsNamed(
      sourceFile,
      ['commonStatusTagType', 'formatCommonStatusLabel'],
      '@/utils/uiLabels',
    )
  ) {
    localFailures.push(`${label} must preserve the uiLabels compatibility helpers`)
  }
  const labelBody = computedInitializer(sourceFile, 'label')
  if (!callsNamedWithProperty(labelBody, 'formatCommonStatusLabel', ['props', 'status'])) {
    localFailures.push(`${label} label must derive from formatCommonStatusLabel(props.status)`)
  }
  const toneBody = computedInitializer(sourceFile, 'tone')
  if (
    !isTypeReference(computedTypeArgument(sourceFile, 'tone'), 'StatusTone') ||
    !callsNamedWithProperty(toneBody, 'commonStatusTagType', ['props', 'status'])
  ) {
    localFailures.push(`${label} tone must adapt commonStatusTagType(props.status) to StatusTone`)
  }
  if (
    !rootElement ||
    rootElement.tag !== 'StatusTag' ||
    !isIdentifierValue(directiveExpression(rootElement, 'bind', 'label'), 'label') ||
    !isIdentifierValue(directiveExpression(rootElement, 'bind', 'tone'), 'tone')
  ) {
    localFailures.push(`${label} must delegate rendering to StatusTag with label and tone`)
  }
  if (templateElements(ast).some((element) => element.tag.toLowerCase() === 'el-tag')) {
    localFailures.push(`${label} must not bypass StatusTag with a raw el-tag`)
  }
  return localFailures
}

async function validateSyntheticComponent(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures

  const ast = templateAst(parsed.descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const classNames = staticAttribute(rootElement, 'class')?.value?.content.split(/\s+/) ?? []
  if (!classNames.some((className) => className.startsWith('glass-surface-'))) {
    localFailures.push(`${label} must include a glass-surface-* class`)
  }
  if (!classNames.some((className) => ['density-compact', 'density-comfortable', 'density-spacious'].includes(className))) {
    localFailures.push(`${label} must include a density class`)
  }
  return localFailures
}

function validateRepresentativeConsumer(source, label) {
  const localFailures = []
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) {
    return [`${label} must import and render WorkbenchPanel instead of raw el-card`]
  }
  const sourceFile = parseTypeScript(
    parsed.descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const importsWorkbenchPanel = sourceFile.statements.some(
    (statement) =>
      ts.isImportDeclaration(statement) &&
      ts.isStringLiteral(statement.moduleSpecifier) &&
      statement.moduleSpecifier.text === '@/components/common/WorkbenchPanel.vue' &&
      statement.importClause?.name?.text === 'WorkbenchPanel',
  )
  const ast = templateAst(parsed.descriptor.template?.content ?? '', label, localFailures)
  const elements = templateElements(ast)
  const rendersWorkbenchPanel = elements.some((element) => element.tag === 'WorkbenchPanel')
  const rendersRawElementCard = elements.some((element) => element.tag.toLowerCase() === 'el-card')

  if (!importsWorkbenchPanel || !rendersWorkbenchPanel || rendersRawElementCard) {
    return [`${label} must import and render WorkbenchPanel instead of raw el-card`]
  }
  return localFailures
}

const toolListComponentImports = [
  ['PageHeader', '@/components/common/PageHeader.vue'],
  ['FilterBar', '@/components/common/FilterBar.vue'],
  ['DataTableShell', '@/components/common/DataTableShell.vue'],
  ['StatusTag', '@/components/common/StatusTag.vue'],
  ['WorkbenchPage', '@/components/common/WorkbenchPage.vue'],
  ['WizardDialog', '@/components/common/WizardDialog.vue'],
  ['AppDialog', '@/components/common/AppDialog.vue'],
]

function expressionCall(node, name, argumentProperty) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      isNamedCall(expression, name) &&
      expression.arguments.length === 1 &&
      isPropertyChain(expression.arguments[0], argumentProperty),
  )
}

function hasStaticBooleanAttribute(element, name) {
  const attribute = staticAttribute(element, name)
  return Boolean(attribute && !attribute.value)
}

function hasModelBinding(element, argument, property) {
  return Boolean(
    directive(element, 'model', argument) &&
      isPropertyChain(directiveExpression(element, 'model', argument), property),
  )
}

function isEmptyToolsExpression(node) {
  return isBinary(
    node,
    ts.SyntaxKind.EqualsEqualsEqualsToken,
    (candidate) => isPropertyChain(candidate, ['tools', 'length']),
    (candidate) => isNumberValue(candidate, 0),
  )
}

function isPageSizesExpression(node) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isArrayLiteralExpression(expression) &&
      arraysEqual(expression.elements.map((entry) => Number(unwrapExpression(entry).text)), [10, 20, 50, 100]),
  )
}

function isAssignment(node, operator, leftProperty, rightMatches) {
  const expression = unwrapExpression(node)
  return Boolean(
    expression &&
      ts.isBinaryExpression(expression) &&
      expression.operatorToken.kind === operator &&
      isPropertyChain(expression.left, leftProperty) &&
      rightMatches(expression.right),
  )
}

function functionDeclaration(sourceFile, name) {
  return sourceFile.statements.find(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === name,
  )
}

function hasFunctionReturnType(sourceFile, name, typeName) {
  return isTypeReference(functionDeclaration(sourceFile, name)?.type, typeName)
}

function objectPropertyInitializer(object, name) {
  const property = object?.properties.find(
    (candidate) => ts.isPropertyAssignment(candidate) && nodeName(candidate.name) === name,
  )
  return property && ts.isPropertyAssignment(property)
    ? unwrapExpression(property.initializer)
    : undefined
}

function validatesToolStepStateExpression(node) {
  const expression = unwrapExpression(node)
  const laterState = expression && ts.isConditionalExpression(expression)
    ? unwrapExpression(expression.whenFalse)
    : undefined
  return Boolean(
    expression &&
      ts.isConditionalExpression(expression) &&
      isBinary(
        expression.condition,
        ts.SyntaxKind.LessThanToken,
        (candidate) => isIdentifierValue(candidate, 'index'),
        (candidate) => isPropertyChain(candidate, ['activeToolStep', 'value']),
      ) &&
      isStringValue(expression.whenTrue, 'complete') &&
      laterState &&
      ts.isConditionalExpression(laterState) &&
      isBinary(
        laterState.condition,
        ts.SyntaxKind.EqualsEqualsEqualsToken,
        (candidate) => isIdentifierValue(candidate, 'index'),
        (candidate) => isPropertyChain(candidate, ['activeToolStep', 'value']),
      ) &&
      isStringValue(laterState.whenTrue, 'current') &&
      isStringValue(laterState.whenFalse, 'pending'),
  )
}

function validatesToolEditorStepsProjection(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'toolEditorStepsWithState')
  const computedCall = callExpression(declaration?.initializer, 'computed')
  const computedType = computedCall?.typeArguments?.[0]
  const projection = computedCall ? returnedExpression(computedCall.arguments[0]) : undefined
  const mapAccess = projection && ts.isCallExpression(projection)
    ? unwrapExpression(projection.expression)
    : undefined
  const mapCallback = projection && ts.isCallExpression(projection)
    ? unwrapExpression(projection.arguments[0])
    : undefined
  const mappedObject = mapCallback ? returnedExpression(mapCallback) : undefined
  return Boolean(
    computedType &&
      typeNodeMatches(computedType, 'WizardStep[]') &&
      projection &&
      ts.isCallExpression(projection) &&
      mapAccess &&
      ts.isPropertyAccessExpression(mapAccess) &&
      isIdentifierValue(mapAccess.expression, 'toolEditorSteps') &&
      mapAccess.name.text === 'map' &&
      projection.arguments.length === 1 &&
      mapCallback &&
      (ts.isArrowFunction(mapCallback) || ts.isFunctionExpression(mapCallback)) &&
      mapCallback.parameters.length === 2 &&
      ts.isIdentifier(mapCallback.parameters[0].name) &&
      mapCallback.parameters[0].name.text === 'step' &&
      ts.isIdentifier(mapCallback.parameters[1].name) &&
      mapCallback.parameters[1].name.text === 'index' &&
      mappedObject &&
      ts.isObjectLiteralExpression(mappedObject) &&
      mappedObject.properties.length === 4 &&
      isPropertyChain(objectPropertyInitializer(mappedObject, 'key'), ['step', 'key']) &&
      isPropertyChain(objectPropertyInitializer(mappedObject, 'title'), ['step', 'title']) &&
      isPropertyChain(objectPropertyInitializer(mappedObject, 'description'), ['step', 'desc']) &&
      validatesToolStepStateExpression(objectPropertyInitializer(mappedObject, 'state')),
  )
}

function isTrimmedFormField(node, field) {
  const expression = unwrapExpression(node)
  const callee = expression && ts.isCallExpression(expression)
    ? unwrapExpression(expression.expression)
    : undefined
  return Boolean(
    expression &&
      ts.isCallExpression(expression) &&
      expression.arguments.length === 0 &&
      callee &&
      ts.isPropertyAccessExpression(callee) &&
      isPropertyChain(callee.expression, ['form', field]) &&
      callee.name.text === 'trim',
  )
}

function validatesIdentityFields(node) {
  const expression = unwrapExpression(node)
  const booleanCall = callExpression(expression, 'Boolean')
  const fields = unwrapExpression(booleanCall?.arguments[0])
  const operands = []
  const collectOperands = (candidate) => {
    const current = unwrapExpression(candidate)
    if (
      current &&
      ts.isBinaryExpression(current) &&
      current.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken
    ) {
      collectOperands(current.left)
      collectOperands(current.right)
      return
    }
    operands.push(current)
  }
  collectOperands(fields)
  return Boolean(
    booleanCall &&
      booleanCall.arguments.length === 1 &&
      operands.length === 3 &&
      isTrimmedFormField(operands[0], 'title') &&
      isTrimmedFormField(operands[1], 'name') &&
      isTrimmedFormField(operands[2], 'description'),
  )
}

function validatesCanAdvanceToolStep(sourceFile) {
  const body = computedInitializer(sourceFile, 'canAdvanceToolStep')
  return isBinary(
    body,
    ts.SyntaxKind.BarBarToken,
    (candidate) =>
      isBinary(
        candidate,
        ts.SyntaxKind.ExclamationEqualsEqualsToken,
        (left) => isPropertyChain(left, ['activeToolStep', 'value']),
        (right) => isNumberValue(right, 0),
      ),
    validatesIdentityFields,
  )
}

function isElMessageWarning(node, expectedMessage) {
  const expression = unwrapExpression(node)
  const callee = expression && ts.isCallExpression(expression)
    ? unwrapExpression(expression.expression)
    : undefined
  return Boolean(
    expression &&
      ts.isCallExpression(expression) &&
      callee &&
      ts.isPropertyAccessExpression(callee) &&
      isIdentifierValue(callee.expression, 'ElMessage') &&
      callee.name.text === 'warning' &&
      expression.arguments.length === 1 &&
      isStringValue(expression.arguments[0], expectedMessage),
  )
}

function isClampedToolStepAdvance(node) {
  return isAssignment(
    node,
    ts.SyntaxKind.EqualsToken,
    ['activeToolStep', 'value'],
    (candidate) => {
      const expression = unwrapExpression(candidate)
      const callee = expression && ts.isCallExpression(expression)
        ? unwrapExpression(expression.expression)
        : undefined
      return Boolean(
        expression &&
          ts.isCallExpression(expression) &&
          callee &&
          ts.isPropertyAccessExpression(callee) &&
          isIdentifierValue(callee.expression, 'Math') &&
          callee.name.text === 'min' &&
          expression.arguments.length === 2 &&
          isBinary(
            expression.arguments[0],
            ts.SyntaxKind.PlusToken,
            (left) => isPropertyChain(left, ['activeToolStep', 'value']),
            (right) => isNumberValue(right, 1),
          ) &&
          isBinary(
            expression.arguments[1],
            ts.SyntaxKind.MinusToken,
            (left) => isPropertyChain(left, ['toolEditorSteps', 'length']),
            (right) => isNumberValue(right, 1),
          ),
      )
    },
  )
}

function validatesAdvanceToolStep(sourceFile) {
  const helper = functionDeclaration(sourceFile, 'advanceToolStep')
  if (!helper || helper.parameters.length !== 0 || helper.body?.statements.length !== 2) return false
  const [guard, advance] = helper.body.statements
  if (
    !ts.isIfStatement(guard) ||
    !isNegatedProperty(guard.expression, ['canAdvanceToolStep', 'value']) ||
    !ts.isBlock(guard.thenStatement) ||
    guard.thenStatement.statements.length !== 2
  ) {
    return false
  }
  const [warning, earlyReturn] = guard.thenStatement.statements
  return Boolean(
    ts.isExpressionStatement(warning) &&
      isElMessageWarning(warning.expression, '请填写工具名称、工具标识和描述') &&
      ts.isReturnStatement(earlyReturn) &&
      !earlyReturn.expression &&
      ts.isExpressionStatement(advance) &&
      isClampedToolStepAdvance(advance.expression),
  )
}

function importedApiStatements(sourceFile) {
  return sourceFile.statements
    .filter(
      (statement) =>
        ts.isImportDeclaration(statement) &&
        ts.isStringLiteral(statement.moduleSpecifier) &&
        statement.moduleSpecifier.text.startsWith('@/api/'),
    )
    .map((statement) => normalizeWhitespace(statement.getText(sourceFile)))
}

const canonicalTypeScriptPrinter = ts.createPrinter({
  newLine: ts.NewLineKind.LineFeed,
  removeComments: true,
})

function canonicalTypeScriptNode(node) {
  if (!node) return undefined
  return normalizeWhitespace(
    canonicalTypeScriptPrinter.printNode(
      ts.EmitHint.Unspecified,
      node,
      node.getSourceFile(),
    ),
  )
}

function functionBodyMap(sourceFile) {
  return new Map(
    sourceFile.statements
      .filter((statement) => ts.isFunctionDeclaration(statement) && statement.name && statement.body)
      .map((statement) => [
        statement.name.text,
        canonicalTypeScriptNode(statement.body),
      ]),
  )
}

function runtimeFunctionSignatureMap(sourceFile) {
  const signatures = new Map()
  for (const statement of sourceFile.statements) {
    if (!ts.isFunctionDeclaration(statement) || !statement.name || !statement.body) continue
    const transpiled = ts.transpileModule(canonicalTypeScriptNode(statement), {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ESNext,
      },
    }).outputText
    const runtimeSourceFile = ts.createSourceFile(
      `${statement.name.text}.runtime.js`,
      transpiled,
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.JS,
    )
    const runtimeFunction = runtimeSourceFile.statements.find(
      (candidate) =>
        ts.isFunctionDeclaration(candidate) && candidate.name?.text === statement.name.text,
    )
    signatures.set(statement.name.text, canonicalTypeScriptNode(runtimeFunction))
  }
  return signatures
}

function variableInitializerSequence(sourceFile) {
  const sequence = []
  for (const statement of sourceFile.statements) {
    if (!ts.isVariableStatement(statement)) continue
    const transpiled = ts.transpileModule(canonicalTypeScriptNode(statement), {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ESNext,
      },
    }).outputText
    const runtimeSourceFile = ts.createSourceFile(
      'knowledge-variable-runtime.js',
      transpiled,
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.JS,
    )
    for (const runtimeStatement of runtimeSourceFile.statements) {
      if (!ts.isVariableStatement(runtimeStatement)) continue
      const declarationKind = runtimeStatement.declarationList.flags & ts.NodeFlags.Const
        ? 'const'
        : runtimeStatement.declarationList.flags & ts.NodeFlags.Let
          ? 'let'
          : 'var'
      for (const declaration of runtimeStatement.declarationList.declarations) {
        const binding = canonicalTypeScriptNode(declaration.name)
        sequence.push({
          binding,
          bindingKind: ts.SyntaxKind[declaration.name.kind],
          declarationKind,
          initializer: canonicalTypeScriptNode(declaration.initializer) ?? null,
        })
      }
    }
  }
  return sequence
}

function variableInitializerMap(sourceFile) {
  const initializers = new Map()
  for (const { binding, ...initializer } of variableInitializerSequence(sourceFile)) {
    const entries = initializers.get(binding) ?? []
    entries.push(initializer)
    initializers.set(binding, entries)
  }
  return initializers
}

function topLevelLifecycleCalls(sourceFile) {
  return sourceFile.statements
    .filter((statement) => {
      if (!ts.isExpressionStatement(statement)) return false
      const expression = unwrapExpression(statement.expression)
      return isNamedCall(expression, 'onMounted') || isNamedCall(expression, 'watch')
    })
    .map((statement) => canonicalTypeScriptNode(statement.expression))
}

function topLevelRuntimeControlStatements(sourceFile) {
  return sourceFile.statements
    .filter(
      (statement) =>
        !ts.isImportDeclaration(statement) &&
        !ts.isVariableStatement(statement) &&
        !ts.isFunctionDeclaration(statement) &&
        !ts.isTypeAliasDeclaration(statement) &&
        !ts.isInterfaceDeclaration(statement),
    )
    .map((statement) => {
      const transpiled = ts.transpileModule(canonicalTypeScriptNode(statement), {
        compilerOptions: {
          module: ts.ModuleKind.ESNext,
          target: ts.ScriptTarget.ESNext,
        },
      }).outputText
      const runtimeSourceFile = ts.createSourceFile(
        'knowledge-top-level-runtime.js',
        transpiled,
        ts.ScriptTarget.Latest,
        true,
        ts.ScriptKind.JS,
      )
      return runtimeSourceFile.statements.map(canonicalTypeScriptNode)
    })
    .flat()
}

function canonicalTemplateArgument(argument) {
  if (!argument) return null
  if (argument.isStatic) return { static: argument.content }
  return {
    dynamic: canonicalTypeScriptNode(parseTemplateExpression(argument.content)),
  }
}

function canonicalTemplateProp(property, options, element) {
  if (options?.ignoreProp?.(element, property)) return undefined
  if (property.type === 6) {
    let value = property.value?.content ?? null
    if (property.name === 'class' && value) {
      value = value.split(/\s+/).filter(Boolean).sort().join(' ')
    }
    return { kind: 'attribute', name: property.name, value }
  }
  if (property.type !== 7) return undefined
  return {
    kind: 'directive',
    name: property.name,
    argument: canonicalTemplateArgument(property.arg),
    modifiers: (property.modifiers ?? []).map((modifier) => modifier.content).sort(),
    expression: canonicalTypeScriptNode(parseTemplateExpression(property.exp?.content)),
  }
}

function authorizedToolStatusRole(element) {
  if (!element || !['el-tag', 'StatusTag'].includes(element.tag)) return undefined
  const tone =
    directiveExpression(element, 'bind', 'tone') ??
    directiveExpression(element, 'bind', 'type')
  if (expressionCall(tone, 'sourceTagType', ['row', 'source'])) return 'source'
  if (
    expressionCall(tone, 'catalogHealthTagType', ['row', 'catalogLinkStatus'])
  ) {
    return 'catalog'
  }
  return undefined
}

function knowledgeStatusRoleFromExpression(node) {
  const canonical = canonicalTypeScriptNode(node) ?? ''
  if (canonical.includes('statusText(row.status)')) return 'file'
  if (canonical.includes('row.enabled')) return 'chunk'
  if (canonical.includes('row.directReturn')) return 'direct-return'
  return undefined
}

function authorizedKnowledgeStatusRole(element) {
  if (!element || !['el-tag', 'StatusTag'].includes(element.tag)) return undefined
  const boundLabel = directiveExpression(element, 'bind', 'label')
  const boundRole = knowledgeStatusRoleFromExpression(boundLabel)
  if (boundRole) return boundRole
  for (const { node } of templateInterpolationEntries(element)) {
    const role = knowledgeStatusRoleFromExpression(
      parseTemplateExpression(node.content?.content),
    )
    if (role) return role
  }
  return undefined
}

function canonicalTemplateNode(node, options = {}) {
  if (!node || node.type === 3) return undefined
  if (node.type === 2) {
    const text = normalizeWhitespace(node.content)
    return text ? { kind: 'text', text } : undefined
  }
  if (node.type === 5) {
    return {
      kind: 'interpolation',
      expression: canonicalTypeScriptNode(parseTemplateExpression(node.content?.content)),
    }
  }
  if (node.type === 1) {
    const statusRole = options.normalizeToolStatuses
      ? authorizedToolStatusRole(node)
      : undefined
    if (statusRole) return { kind: 'authorized-tool-status', role: statusRole }
    const knowledgeStatusRole = options.normalizeKnowledgeStatuses
      ? authorizedKnowledgeStatusRole(node)
      : undefined
    if (knowledgeStatusRole) {
      return { kind: 'authorized-knowledge-status', role: knowledgeStatusRole }
    }
    const props = node.props
      .map((property) => canonicalTemplateProp(property, options, node))
      .filter(Boolean)
      .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
    const children = (node.children ?? [])
      .map((child) => canonicalTemplateNode(child, options))
      .filter(Boolean)
    return { kind: 'element', tag: node.tag, props, children }
  }
  const children = (node.children ?? [])
    .map((child) => canonicalTemplateNode(child, options))
    .filter(Boolean)
  if (children.length > 0) return { kind: `node-${node.type}`, children }
  return { kind: `node-${node.type}`, content: normalizeWhitespace(node.loc?.source ?? '') }
}

function canonicalTemplateChildren(element, options = {}) {
  return (element?.children ?? [])
    .map((child) => canonicalTemplateNode(child, options))
    .filter(Boolean)
}

function isMainToolTable(element) {
  return Boolean(
    element?.tag.toLowerCase() === 'el-table' &&
      isIdentifierValue(directiveExpression(element, 'bind', 'data'), 'tools'),
  )
}

function canonicalMainToolTable(element) {
  return canonicalTemplateNode(element, {
    normalizeToolStatuses: true,
    ignoreProp: (candidate, property) =>
      candidate === element &&
      ((property.type === 7 && property.name === 'loading') ||
        (property.type === 6 && property.name === 'empty-text')),
  })
}

function hasModelProperty(element, property) {
  const model = directive(element, 'model', undefined)
  return Boolean(model && isPropertyChain(directiveExpression(element, 'model', undefined), property))
}

function canonicalFilterControls(ast) {
  return allTemplateElements(ast)
    .filter(
      (element) =>
        hasModelProperty(element, ['filters', 'keyword']) ||
        hasModelProperty(element, ['filters', 'source']) ||
        hasModelProperty(element, ['filters', 'enabled']),
    )
    .map((element) => canonicalTemplateNode(element))
}

function criticalToolListTemplateSemantics(ast) {
  const elements = allTemplateElements(ast)
  const mainTable = elements.find(isMainToolTable)
  const editorMain = elements.find((element) => hasStaticClass(element, 'tool-editor-main'))
  const testDialog = elements.find(
    (element) =>
      ['el-dialog', 'AppDialog'].includes(element.tag) &&
      hasModelBinding(element, undefined, ['testDialogVisible']),
  )
  return {
    filterControls: canonicalFilterControls(ast),
    mainTable: canonicalMainToolTable(mainTable),
    editorMain: canonicalTemplateNode(editorMain),
    testDialogChildren: canonicalTemplateChildren(testDialog),
  }
}

function toolListCurrentOperationalContract(ast) {
  const elements = allTemplateElements(ast)
  const mainTable = elements.find(isMainToolTable)
  const tableElements = mainTable ? descendantElements(ast, mainTable) : []
  const enabledSwitch = tableElements.find(
    (element) =>
      element.tag.toLowerCase() === 'el-switch' &&
      expressionMatchesSource(
        directiveExpression(element, 'on', 'change'),
        'handleEnabledChange(row, $event as boolean)',
      ),
  )
  const operationButtons = tableElements.filter(
    (element) => element.tag.toLowerCase() === 'button',
  )
  const hasOperation = (label, expression) =>
    operationButtons.some(
      (button) =>
        templateText(button) === label &&
        expressionMatchesSource(
          directiveExpression(button, 'on', 'click'),
          expression,
        ),
    )
  const editorMain = elements.find((element) => hasStaticClass(element, 'tool-editor-main'))
  const editorElements = editorMain ? descendantElements(ast, editorMain) : []
  const hasEditorModel = (field) =>
    editorElements.some((element) => hasModelBinding(element, undefined, ['form', field]))
  const parameterEditor = editorElements.find((element) => element.tag === 'ParameterTable')
  return Boolean(
    mainTable &&
      enabledSwitch &&
      hasOperation('编辑', 'openEditDialog(row)') &&
      hasOperation('测试', 'openTest(row)') &&
      hasOperation('删除', 'handleDelete(row)') &&
      editorMain &&
      hasEditorModel('title') &&
      hasEditorModel('name') &&
      hasEditorModel('description') &&
      hasEditorModel('enabled') &&
      parameterEditor &&
      hasModelBinding(parameterEditor, undefined, ['form', 'parameters']),
  )
}

let cachedHeadToolList

function headToolListSource(localFailures, label) {
  if (cachedHeadToolList !== undefined) return cachedHeadToolList
  try {
    cachedHeadToolList = execFileSync(
      'git',
      ['show', 'HEAD:ai-admin-front/src/views/tool/ToolList.vue'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    localFailures.push(`${label} must compare behavior with HEAD ToolList: ${formatCompilerError(error)}`)
    cachedHeadToolList = null
  }
  return cachedHeadToolList
}

function validateToolListHeadPreservation(sourceFile, ast, label, localFailures) {
  const headSource = headToolListSource(localFailures, label)
  if (!headSource) return
  const legacyToolTestArgumentLoop = [
    '    for (const [k, v] of Object.entries(testArgs)) {',
    "      if (v !== '') args[k] = v",
    '    }',
  ].join('\n')
  const typedToolTestArgumentLoop = [
    '    const parametersByName = new Map((testingTool.value.parameters || []).map((parameter) => [parameter.name, parameter]))',
    '    for (const [k, v] of Object.entries(testArgs)) {',
    "      if (v === '') continue",
    "      const parameterType = String(parametersByName.get(k)?.type || '').toLowerCase()",
    '      args[k] = parseToolTestArgument(v, parameterType, k)',
    '    }',
  ].join('\n')
  const approvedHeadSource = headSource.includes(legacyToolTestArgumentLoop)
    ? headSource.replace(legacyToolTestArgumentLoop, typedToolTestArgumentLoop)
    : headSource
  const headParsed = parse(approvedHeadSource, { filename: 'HEAD:ToolList.vue' })
  if (headParsed.errors.length > 0) {
    localFailures.push(`${label} HEAD ToolList baseline must remain parseable`)
    return
  }
  const headSourceFile = parseTypeScript(
    headParsed.descriptor.scriptSetup?.content ?? '',
    'HEAD:ToolList.vue script setup',
    localFailures,
  )
  if (
    JSON.stringify(importedApiStatements(sourceFile)) !==
    JSON.stringify(importedApiStatements(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD API imports exactly`)
  }

  const headBodies = functionBodyMap(headSourceFile)
  const currentBodies = functionBodyMap(sourceFile)
  const legacyBaseline =
    headBodies.has('syncProjectFromRoute') || headBodies.has('handleFlagChange')
  const approvedChangedFunctions = legacyBaseline
    ? new Set([
        'advanceToolStep',
        'applyForm',
        'buildListParams',
        'createEmptyForm',
        'handleDelete',
        'handleEnabledChange',
        'handleFlagChange',
        'handleSave',
        'syncProjectFromRoute',
        'toUpsertRequest',
      ])
    : new Set()
  const changedFunctions = [...headBodies.entries()]
    .filter(
      ([name, body]) =>
        currentBodies.get(name) !== body && !approvedChangedFunctions.has(name),
    )
    .map(([name]) => name)
  const addedFunctions = [...currentBodies.keys()]
    .filter((name) => !headBodies.has(name))
    .sort()
  if (changedFunctions.length > 0) {
    localFailures.push(
      `${label} must preserve HEAD function bodies; changed or missing: ${changedFunctions.join(', ')}`,
    )
  }
  const expectedAddedFunctions = legacyBaseline
    ? ['buildFormPayload', 'syncProjectScope']
    : []
  if (!arraysEqual(addedFunctions, expectedAddedFunctions)) {
    localFailures.push(`${label} must preserve the approved Tool function set`)
  }

  const headInitializers = variableInitializerMap(headSourceFile)
  const currentInitializers = variableInitializerMap(sourceFile)
  const approvedChangedInitializers = legacyBaseline
    ? new Set(['canAdvanceToolStep', 'formDialogTitle', 'toolEditorSteps'])
    : new Set()
  const changedInitializers = [...headInitializers.entries()]
    .filter(
      ([name, initializer]) =>
        JSON.stringify(currentInitializers.get(name)) !== JSON.stringify(initializer) &&
        !approvedChangedInitializers.has(name),
    )
    .map(([name]) => name)
  if (changedInitializers.length > 0) {
    localFailures.push(
      `${label} must preserve HEAD variable initializers; changed or missing: ${changedInitializers.join(', ')}`,
    )
  }
  const addedInitializerNames = [...currentInitializers.keys()]
    .filter((name) => !headInitializers.has(name))
    .sort()
  const expectedAddedInitializerNames = legacyBaseline ? ['scopeProjectId'] : []
  if (!arraysEqual(addedInitializerNames, expectedAddedInitializerNames)) {
    localFailures.push(`${label} must preserve the approved Tool variable initializer set`)
  }

  const currentLifecycle = topLevelLifecycleCalls(sourceFile)
  const expectedLifecycle = legacyBaseline
    ? [
        'onMounted(async () => { await loadScanProjects(); syncProjectScope(true); fetchTools(); })',
        'watch(() => projectStore.currentProjectId, () => { syncProjectScope(); pagination.current = 1; fetchTools(); })',
      ]
    : topLevelLifecycleCalls(headSourceFile)
  if (JSON.stringify(currentLifecycle) !== JSON.stringify(expectedLifecycle)) {
    localFailures.push(`${label} must preserve HEAD onMounted and watch call/callback semantics`)
  }

  const headTemplateAst = templateAst(
    headParsed.descriptor.template?.content ?? '',
    'HEAD:ToolList.vue',
    localFailures,
  )
  const expectedTemplate = criticalToolListTemplateSemantics(headTemplateAst)
  const actualTemplate = criticalToolListTemplateSemantics(ast)
  const preservedTemplateSections = legacyBaseline
    ? [['testDialogChildren', 'test dialog default and footer subtree semantics']]
    : [
        ['filterControls', 'filter control semantics'],
        ['mainTable', 'main Tool table subtree semantics'],
        ['editorMain', 'tool-editor-main four-panel subtree semantics'],
        ['testDialogChildren', 'test dialog default and footer subtree semantics'],
      ]
  for (const [name, message] of preservedTemplateSections) {
    if (JSON.stringify(actualTemplate[name]) !== JSON.stringify(expectedTemplate[name])) {
      localFailures.push(`${label} must preserve HEAD ${message}`)
    }
  }
}

function toolStatusTagsAreSemantic(statusTags) {
  const sourceTags = statusTags.filter((tag) =>
    isPropertyChain(directiveExpression(tag, 'bind', 'label'), ['row', 'source']),
  )
  const catalogTags = statusTags.filter((tag) =>
    expressionCall(
      directiveExpression(tag, 'bind', 'label'),
      'catalogHealthLabel',
      ['row', 'catalogLinkStatus'],
    ),
  )
  return Boolean(
    sourceTags.length >= 1 &&
      sourceTags.every((sourceTag) =>
        expressionCall(
          directiveExpression(sourceTag, 'bind', 'tone'),
          'sourceTagType',
          ['row', 'source'],
        ),
      ) &&
      catalogTags.length === 1 &&
      statusTags.length === sourceTags.length + catalogTags.length &&
      expressionCall(
        directiveExpression(catalogTags[0], 'bind', 'tone'),
        'catalogHealthTagType',
        ['row', 'catalogLinkStatus'],
      ),
  )
}

function toolListHasPrivateVisualRecipe(styles) {
  const forbiddenEverywhere = new Set([
    'box-shadow',
    '-webkit-box-shadow',
    'backdrop-filter',
    '-webkit-backdrop-filter',
    'filter',
  ])
  const privateSurfaceClasses = [
    'tool-editor',
    'tool-editor-summary',
    'tool-editor-panel',
    'control-card',
    'result-content',
    'endpoint-preview',
  ]
  return compiledRules(styles).some(({ selector, declarations }) => {
    const elementPlusVisualSkin = /\.el-[a-z0-9_-]+/i.test(selector) && declarations.some(
      ({ property }) =>
        property === 'color' ||
        property.startsWith('background') ||
        property.startsWith('border') ||
        forbiddenEverywhere.has(property),
    )
    const ownsSurfaceBorder = privateSurfaceClasses.some((className) =>
      selector.includes(`.${className}`),
    ) && declarations.some(({ property }) => property.startsWith('border'))
    const ownsBackground = declarations.some(
      ({ property, value }) => property.startsWith('background') && value !== 'transparent',
    )
    return Boolean(
      elementPlusVisualSkin ||
        ownsSurfaceBorder ||
        ownsBackground ||
        declarations.some(({ property }) => forbiddenEverywhere.has(property)),
    )
  })
}

function componentFooterSlot(ast, component) {
  return descendantElements(ast, component).some(
    (element) => element.tag === 'template' && directive(element, 'slot', 'footer'),
  )
}

async function validateToolListConsumer(source, label, options = {}) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const allElements = allTemplateElements(ast)
  const entries = templateElementEntries(ast).filter((entry) => entry.reachable)
  const styles = await compiledStyleCodes(descriptor, label)

  if (
    !toolListComponentImports.every(([name, moduleName]) =>
      importsDefault(sourceFile, name, moduleName),
    ) ||
    !importsSymbol(sourceFile, '@/components/common/glassWorkbench', 'StatusTone') ||
    !importsSymbol(sourceFile, '@/components/common/glassWorkbench', 'WizardStep')
  ) {
    localFailures.push(
      `${label} must directly import all seven shared components plus StatusTone and WizardStep`,
    )
  }

  const componentCounts = new Map(
    ['PageHeader', 'FilterBar', 'DataTableShell', 'WizardDialog', 'AppDialog', 'StatusTag'].map(
      (name) => [name, elements.filter((element) => element.tag === name).length],
    ),
  )
  if (
    componentCounts.get('PageHeader') !== 1 ||
    componentCounts.get('FilterBar') !== 1 ||
    componentCounts.get('DataTableShell') !== 1 ||
    componentCounts.get('WizardDialog') !== 1 ||
    componentCounts.get('AppDialog') !== 1
  ) {
    localFailures.push(`${label} must render PageHeader, FilterBar, DataTableShell, WizardDialog, and AppDialog once`)
  }

  if (!workbenchPageRootContract(rootElement, 'comfortable')) {
    localFailures.push(`${label} page root must use comfortable density`)
  }

  const pageHeader = elements.find((element) => element.tag === 'PageHeader')
  const pageHeaderActions = pageHeader
    ? descendantElements(ast, pageHeader).find(
        (element) => element.tag === 'template' && directive(element, 'slot', 'actions'),
      )
    : undefined
  const headerButtons = pageHeaderActions
    ? descendantElements(ast, pageHeaderActions).filter(
        (element) => element.tag.toLowerCase() === 'el-button',
      )
    : []
  if (
    !pageHeader ||
    staticAttributeValue(pageHeader, 'title') !== 'Tool 管理' ||
    staticAttributeValue(pageHeader, 'description') !==
      '管理平台可被 Agent 调用的 Tool、语义信息与运行开关' ||
    !pageHeaderActions ||
    headerButtons.length !== 2 ||
    !headerButtons.some((button) =>
      isIdentifierValue(directiveExpression(button, 'on', 'click'), 'openCreateDialog'),
    ) ||
    !headerButtons.some(
      (button) =>
        isIdentifierValue(directiveExpression(button, 'on', 'click'), 'onRefresh') &&
        isIdentifierValue(directiveExpression(button, 'bind', 'loading'), 'loading'),
    )
  ) {
    localFailures.push(`${label} PageHeader must own the exact title, description, create, and refresh actions`)
  }

  const filterBar = elements.find((element) => element.tag === 'FilterBar')
  const filterDescendants = filterBar ? descendantElements(ast, filterBar) : []
  const keywordInput = filterDescendants.find(
    (element) =>
      element.tag.toLowerCase() === 'el-input' &&
      hasModelBinding(element, undefined, ['filters', 'keyword']),
  )
  const sourceSelect = filterDescendants.find(
    (element) =>
      element.tag.toLowerCase() === 'el-select' &&
      hasModelBinding(element, undefined, ['filters', 'source']),
  )
  const enabledSelect = filterDescendants.find(
    (element) =>
      element.tag.toLowerCase() === 'el-select' &&
      hasModelBinding(element, undefined, ['filters', 'enabled']),
  )
  if (
    !filterBar ||
    !isIdentifierValue(directiveExpression(filterBar, 'bind', 'loading'), 'loading') ||
    !isIdentifierValue(directiveExpression(filterBar, 'on', 'query'), 'handleSearch') ||
    !isIdentifierValue(directiveExpression(filterBar, 'on', 'reset'), 'resetFilters') ||
    !keywordInput ||
    !sourceSelect ||
    !enabledSelect ||
    !hasDirectiveModifier(keywordInput, 'on', 'keyup', 'enter') ||
    filterDescendants.some(
      (element) => ['form', 'el-form'].includes(element.tag.toLowerCase()),
    ) ||
    filterDescendants.some((element) => element.tag.toLowerCase() === 'el-button')
  ) {
    localFailures.push(
      `${label} FilterBar must own query/reset without nested forms or duplicate actions while preserving all filters`,
    )
  }

  const tableShell = elements.find((element) => element.tag === 'DataTableShell')
  if (
    !tableShell ||
    !hasModelBinding(tableShell, 'current-page', ['pagination', 'current']) ||
    !hasModelBinding(tableShell, 'page-size', ['pagination', 'size']) ||
    staticAttributeValue(tableShell, 'density') !== 'compact' ||
    !isIdentifierValue(directiveExpression(tableShell, 'bind', 'loading'), 'loading') ||
    !isEmptyToolsExpression(directiveExpression(tableShell, 'bind', 'empty')) ||
    !isIdentifierValue(directiveExpression(tableShell, 'bind', 'total'), 'total') ||
    !isPageSizesExpression(directiveExpression(tableShell, 'bind', 'page-sizes')) ||
    !isIdentifierValue(directiveExpression(tableShell, 'on', 'page-change'), 'fetchTools') ||
    !isIdentifierValue(
      directiveExpression(tableShell, 'on', 'size-change'),
      'handlePageSizeChange',
    )
  ) {
    localFailures.push(`${label} DataTableShell must own compact loading/empty/pagination wiring`)
  }
  if (
    !tableShell ||
    staticAttributeValue(tableShell, 'empty-description') !==
      '暂无数据，请调整条件或先在后端注册 Tool'
  ) {
    localFailures.push(`${label} DataTableShell must preserve the actionable empty description`)
  }
  const mainToolTable = elements.find(isMainToolTable)
  if (
    !mainToolTable ||
    !tableShell ||
    !descendantElements(ast, tableShell).includes(mainToolTable) ||
    staticAttributeValue(mainToolTable, 'empty-text') !== ' '
  ) {
    localFailures.push(`${label} main el-table must suppress its private empty state for DataTableShell`)
  }
  if (
    options.compareHead &&
    (!toolListCurrentOperationalContract(ast) ||
      /\b(?:agentVisible|lightweightEnabled)\b/.test(
        `${descriptor.template?.content ?? ''}\n${descriptor.scriptSetup?.content ?? ''}`,
      ))
  ) {
    localFailures.push(
      `${label} must preserve the current Tool title, scope, operations, and runtime-control contract`,
    )
  }

  const statusTags = elements.filter((element) => element.tag === 'StatusTag')
  if (
    !toolStatusTagsAreSemantic(statusTags) ||
    !hasFunctionReturnType(sourceFile, 'sourceTagType', 'StatusTone') ||
    !hasFunctionReturnType(sourceFile, 'catalogHealthTagType', 'StatusTone')
  ) {
    localFailures.push(`${label} must render source and catalog state through semantic StatusTag mappings`)
  }

  const wizard = elements.find((element) => element.tag === 'WizardDialog')
  if (
    !wizard ||
    !hasModelBinding(wizard, undefined, ['formDialogVisible']) ||
    !isIdentifierValue(directiveExpression(wizard, 'bind', 'title'), 'formDialogTitle') ||
    !isIdentifierValue(
      directiveExpression(wizard, 'bind', 'steps'),
      'toolEditorStepsWithState',
    ) ||
    !isIdentifierValue(directiveExpression(wizard, 'bind', 'active-step'), 'activeToolStep') ||
    !isIdentifierValue(
      directiveExpression(wizard, 'bind', 'can-advance'),
      'canAdvanceToolStep',
    ) ||
    !isIdentifierValue(directiveExpression(wizard, 'bind', 'loading'), 'saving') ||
    staticAttributeValue(wizard, 'finish-label') !== '保存' ||
    staticAttributeValue(wizard, 'width') !== '1180px' ||
    staticAttributeValue(wizard, 'top') !== '3vh' ||
    !hasStaticBooleanAttribute(wizard, 'append-to-body') ||
    !isAssignment(
      directiveExpression(wizard, 'on', 'back'),
      ts.SyntaxKind.MinusEqualsToken,
      ['activeToolStep'],
      (candidate) => isNumberValue(candidate, 1),
    ) ||
    !isIdentifierValue(directiveExpression(wizard, 'on', 'next'), 'advanceToolStep') ||
    !isIdentifierValue(directiveExpression(wizard, 'on', 'finish'), 'handleSave') ||
    !isAssignment(
      directiveExpression(wizard, 'on', 'cancel'),
      ts.SyntaxKind.EqualsToken,
      ['formDialogVisible'],
      (candidate) => unwrapExpression(candidate)?.kind === ts.SyntaxKind.FalseKeyword,
    )
  ) {
    localFailures.push(
      `${label} WizardDialog must own controlled progression plus width/top/append/loading passthrough`,
    )
  }
  if (wizard && componentFooterSlot(ast, wizard)) {
    localFailures.push(`${label} must delegate mutually exclusive primary Next/Finish actions to WizardDialog`)
  }
  if (!validatesToolEditorStepsProjection(sourceFile)) {
    localFailures.push(`${label} toolEditorStepsWithState must project desc and complete/current/pending states`)
  }
  if (!validatesCanAdvanceToolStep(sourceFile) || !validatesAdvanceToolStep(sourceFile)) {
    localFailures.push(
      `${label} wizard identity progression must guard trimmed title, name, and description`,
    )
  }

  const appDialog = elements.find((element) => element.tag === 'AppDialog')
  const titleSource = directive(appDialog, 'bind', 'title')?.exp?.content
  if (
    !appDialog ||
    !hasModelBinding(appDialog, undefined, ['testDialogVisible']) ||
    normalizeWhitespace(titleSource ?? '') !==
      '`测试工具 — ${testingTool?.title || testingTool?.name}`' ||
    staticAttributeValue(appDialog, 'width') !== '600px' ||
    !hasStaticBooleanAttribute(appDialog, 'append-to-body') ||
    !componentFooterSlot(ast, appDialog)
  ) {
    localFailures.push(`${label} AppDialog must preserve the complete controlled test dialog`)
  }

  const legacyClasses = new Set(['page-header', 'tool-filter', 'pagination-wrap', 'tool-editor-rail'])
  const ownsLegacyClass = allElements.some((element) =>
    (staticAttributeValue(element, 'class')?.split(/\s+/) ?? []).some((className) =>
      legacyClasses.has(className),
    ),
  )
  const rawTags = new Set(['el-dialog', 'el-card', 'el-empty', 'el-pagination'])
  const ownsRawShell = allElements.some((element) => rawTags.has(element.tag.toLowerCase()))
  const ownsDuplicateLoading = allElements.some((element) => directive(element, 'loading', undefined))
  const compiledLegacySelector = compiledRules(styles).some(({ selector }) =>
    [...legacyClasses].some((className) => selector.includes(`.${className}`)),
  )
  if (ownsLegacyClass || compiledLegacySelector) {
    localFailures.push(`${label} must remove legacy page-header/filter/pagination/editor-rail shells`)
  }
  if (ownsRawShell || ownsDuplicateLoading) {
    localFailures.push(`${label} must not retain raw dialog/card/empty/pagination or duplicate loading shells`)
  }
  if (toolListHasPrivateVisualRecipe(styles)) {
    localFailures.push(`${label} must not own private surface, overlay, Element Plus, or backdrop recipes`)
  }

  if (options.compareHead) {
    validateToolListHeadPreservation(sourceFile, ast, label, localFailures)
  }
  return localFailures
}

const knowledgeDetailComponentImports = [
  ['PageHeader', '@/components/common/PageHeader.vue'],
  ['MetricStrip', '@/components/common/MetricStrip.vue'],
  ['WorkbenchPanel', '@/components/common/WorkbenchPanel.vue'],
  ['DataTableShell', '@/components/common/DataTableShell.vue'],
  ['StatusTag', '@/components/common/StatusTag.vue'],
  ['WorkbenchPage', '@/components/common/WorkbenchPage.vue'],
  ['AppDialog', '@/components/common/AppDialog.vue'],
]

const knowledgeTabContract = [
  ['运营概览', 'overview'],
  ['文件', 'files'],
  ['段落运营', 'chunks'],
  ['命中分析', 'hits'],
  ['检索策略', 'policy'],
  ['标签', 'tags'],
  ['问题映射', 'questions'],
]

const knowledgeTableContract = [
  ['dashboard?.hotChunks || []', '(dashboard?.hotChunks || []).length === 0'],
  ['dashboard?.recentHits || []', '(dashboard?.recentHits || []).length === 0'],
  [
    'dashboard?.lowConfidenceHits || []',
    '(dashboard?.lowConfidenceHits || []).length === 0',
  ],
  ['dashboard?.zeroHitChunks || []', '(dashboard?.zeroHitChunks || []).length === 0'],
  ['fileList', 'fileList.length === 0', 'filesLoading'],
  ['chunks', 'chunks.length === 0', 'chunksLoading'],
  ['hitLogs', 'hitLogs.length === 0', 'hitsLoading'],
  ['tagStats', 'tagStats.length === 0'],
  ['tags', 'tags.length === 0'],
  ['questions', 'questions.length === 0'],
]

function canonicalExpressionText(source) {
  return canonicalTypeScriptNode(parseTemplateExpression(source))
}

function expressionMatchesSource(node, source) {
  return canonicalTypeScriptNode(node) === canonicalExpressionText(source)
}

function task8WrapperPropKey(property) {
  if (property.type === 6) return `attribute:${property.name}`
  if (property.type !== 7 || (property.arg && !property.arg.isStatic)) return 'unsupported'
  const argument = property.arg?.content ?? ''
  const modifiers = (property.modifiers ?? []).map((modifier) => modifier.content).sort().join('.')
  return `directive:${property.name}:${argument}:${modifiers}`
}

function task8WrapperUsesOnly(element, allowedKeys) {
  const allowed = new Set(allowedKeys)
  return Boolean(element && element.props.every((property) => allowed.has(task8WrapperPropKey(property))))
}

function workbenchPageRootContract(element, density, fullHeight = false) {
  const layout = staticAttributeValue(element, 'layout')
  return Boolean(
    element?.tag === 'WorkbenchPage' &&
      staticAttributeValue(element, 'density') === density &&
      hasStaticBooleanAttribute(element, 'full-height') === fullHeight &&
      (layout === undefined || layout === 'list') &&
      task8WrapperUsesOnly(
        element,
        fullHeight
          ? ['attribute:density', 'attribute:full-height', 'attribute:layout']
          : ['attribute:density', 'attribute:layout'],
      ),
  )
}

function isRouterPushPath(node, expectedPath) {
  const expression = unwrapExpression(node)
  const callee = expression && ts.isCallExpression(expression)
    ? unwrapExpression(expression.expression)
    : undefined
  return Boolean(
    expression &&
      ts.isCallExpression(expression) &&
      expression.arguments.length === 1 &&
      isStringValue(expression.arguments[0], expectedPath) &&
      callee &&
      ts.isPropertyAccessExpression(callee) &&
      isIdentifierValue(callee.expression, 'router') &&
      callee.name.text === 'push',
  )
}

function validatesKnowledgeMetrics(sourceFile) {
  const declaration = findVariableDeclaration(sourceFile, 'knowledgeMetrics')
  const computedCall = callExpression(declaration?.initializer, 'computed')
  const typeArgument = computedCall?.typeArguments?.[0]
  const items = computedCall ? returnedExpression(computedCall.arguments[0]) : undefined
  if (
    !typeNodeMatches(typeArgument, 'MetricStripItem[]') ||
    !items ||
    !ts.isArrayLiteralExpression(items) ||
    items.elements.length !== 5
  ) {
    return false
  }
  const expected = [
    ['files', '文件', 'fileCount'],
    ['chunks', '段落', 'chunkCount'],
    ['active', '启用段落', 'activeChunkCount'],
    ['questions', '问题', 'questionCount'],
    ['hits', '命中', 'hitCount'],
  ]
  const semanticTones = new Set([
    'brand',
    'success',
    'warning',
    'danger',
    'info',
    'neutral',
  ])
  return items.elements.every((item, index) => {
    const object = unwrapExpression(item)
    if (!object || !ts.isObjectLiteralExpression(object) || object.properties.length !== 5) {
      return false
    }
    const [key, label, statsProperty] = expected[index]
    const iconKey = objectPropertyInitializer(object, 'iconKey')
    const tone = objectPropertyInitializer(object, 'tone')
    return Boolean(
      isStringValue(objectPropertyInitializer(object, 'key'), key) &&
        isStringValue(objectPropertyInitializer(object, 'label'), label) &&
        isPropertyChain(objectPropertyInitializer(object, 'value'), [
          'stats',
          'value',
          statsProperty,
        ]) &&
        iconKey &&
        ts.isStringLiteralLike(iconKey) &&
        iconKey.text.length > 0 &&
        tone &&
        ts.isStringLiteralLike(tone) &&
        semanticTones.has(tone.text)
    )
  })
}

function isKnowledgeTable(element) {
  return element?.tag.toLowerCase() === 'el-table'
}

function canonicalKnowledgeTable(element) {
  return canonicalTemplateNode(element, {
    normalizeKnowledgeStatuses: true,
    ignoreProp: (candidate, property) =>
      candidate === element &&
      ((property.type === 7 && property.name === 'loading') ||
        (property.type === 6 && property.name === 'empty-text')),
  })
}

function knowledgeHeaderActions(ast) {
  const elements = allTemplateElements(ast)
  const legacy = elements.find((element) => hasStaticClass(element, 'header-actions'))
  if (legacy) return canonicalTemplateChildren(legacy)
  const pageHeader = elements.find((element) => element.tag === 'PageHeader')
  const actions = pageHeader
    ? descendantElements(ast, pageHeader).find(
        (element) => element.tag === 'template' && directive(element, 'slot', 'actions'),
      )
    : undefined
  return canonicalTemplateChildren(actions)
}

function knowledgeDialogChildren(ast, modelName) {
  const dialog = allTemplateElements(ast).find(
    (element) =>
      ['el-dialog', 'AppDialog'].includes(element.tag) &&
      hasModelBinding(element, undefined, [modelName]),
  )
  return canonicalTemplateChildren(dialog)
}

function criticalKnowledgeTemplateSemantics(ast) {
  const elements = allTemplateElements(ast)
  return {
    headerActions: knowledgeHeaderActions(ast),
    toolbars: elements
      .filter((element) => hasStaticClass(element, 'toolbar'))
      .map((element) => canonicalTemplateNode(element)),
    tables: elements.filter(isKnowledgeTable).map(canonicalKnowledgeTable),
    forms: elements
      .filter((element) => element.tag.toLowerCase() === 'el-form')
      .map((element) => canonicalTemplateNode(element)),
    dialogChildren: [
      'tagDialogVisible',
      'batchTagDialogVisible',
      'questionDialogVisible',
    ].map((modelName) => knowledgeDialogChildren(ast, modelName)),
  }
}

let cachedHeadKnowledgeDetail

function headKnowledgeDetailSource(localFailures, label) {
  if (cachedHeadKnowledgeDetail !== undefined) return cachedHeadKnowledgeDetail
  try {
    cachedHeadKnowledgeDetail = execFileSync(
      'git',
      ['show', 'HEAD:ai-admin-front/src/views/KnowledgeDetail.vue'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    localFailures.push(
      `${label} must compare behavior with HEAD KnowledgeDetail: ${formatCompilerError(error)}`,
    )
    cachedHeadKnowledgeDetail = null
  }
  return cachedHeadKnowledgeDetail
}

function validateKnowledgeDetailHeadPreservation(sourceFile, ast, label, localFailures) {
  const headSource = headKnowledgeDetailSource(localFailures, label)
  if (!headSource) return
  const headParsed = parse(headSource, { filename: 'HEAD:KnowledgeDetail.vue' })
  if (headParsed.errors.length > 0) {
    localFailures.push(`${label} HEAD KnowledgeDetail baseline must remain parseable`)
    return
  }
  const headSourceFile = parseTypeScript(
    headParsed.descriptor.scriptSetup?.content ?? '',
    'HEAD:KnowledgeDetail.vue script setup',
    localFailures,
  )
  if (
    JSON.stringify(importedApiStatements(sourceFile)) !==
    JSON.stringify(importedApiStatements(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD knowledge API imports exactly`)
  }

  const headBodies = runtimeFunctionSignatureMap(headSourceFile)
  const currentBodies = runtimeFunctionSignatureMap(sourceFile)
  const changedFunctions = [...headBodies.entries()]
    .filter(([name, body]) => currentBodies.get(name) !== body)
    .map(([name]) => name)
  const addedFunctions = [...currentBodies.keys()]
    .filter((name) => !headBodies.has(name))
    .sort()
  if (changedFunctions.length > 0) {
    localFailures.push(
      `${label} must preserve HEAD runtime function signatures and bodies; changed or missing: ${changedFunctions.join(', ')}`,
    )
  }
  if (addedFunctions.length > 0) {
    localFailures.push(`${label} must not add KnowledgeDetail functions: ${addedFunctions.join(', ')}`)
  }

  const headInitializers = variableInitializerMap(headSourceFile)
  const currentInitializers = variableInitializerMap(sourceFile)
  const changedInitializers = [...headInitializers.entries()]
    .filter(
      ([name, initializer]) =>
        JSON.stringify(currentInitializers.get(name)) !== JSON.stringify(initializer),
    )
    .map(([name]) => name)
  if (changedInitializers.length > 0) {
    localFailures.push(
      `${label} must preserve HEAD variable initializers; changed or missing: ${changedInitializers.join(', ')}`,
    )
  }
  const addedInitializers = [...currentInitializers.entries()]
    .filter(([name]) => !headInitializers.has(name))
    .sort(([left], [right]) => left.localeCompare(right))
  const addedInitializerIsInvalid = addedInitializers.length > 0
  if (addedInitializerIsInvalid) {
    localFailures.push(`${label} may add only the knowledgeMetrics initializer`)
  }

  if (changedInitializers.length === 0 && !addedInitializerIsInvalid) {
    const headInitializerSequence = variableInitializerSequence(headSourceFile)
    const currentInitializerSequence = variableInitializerSequence(sourceFile)
    const knowledgeMetricsIndexes = currentInitializerSequence
      .map((entry, index) => ({ entry, index }))
      .filter(
        ({ entry }) =>
          entry.binding === 'knowledgeMetrics' && entry.bindingKind === 'Identifier',
      )
      .map(({ index }) => index)
    const knowledgeMetricsIndex = knowledgeMetricsIndexes[0]
    const previousEntry = currentInitializerSequence[knowledgeMetricsIndex - 1]
    if (
      knowledgeMetricsIndexes.length !== 1 ||
      previousEntry?.binding !== 'stats' ||
      previousEntry.bindingKind !== 'Identifier'
    ) {
      localFailures.push(
        `${label} must place knowledgeMetrics immediately after stats in the runtime variable initializer sequence`,
      )
    } else if (
      JSON.stringify(currentInitializerSequence) !== JSON.stringify(headInitializerSequence)
    ) {
      localFailures.push(`${label} must preserve HEAD runtime variable initializer sequence`)
    }
  }

  if (
    JSON.stringify(topLevelRuntimeControlStatements(sourceFile)) !==
    JSON.stringify(topLevelRuntimeControlStatements(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD top-level runtime statements and side-effect order`)
  }

  const headAst = templateAst(
    headParsed.descriptor.template?.content ?? '',
    'HEAD:KnowledgeDetail.vue',
    localFailures,
  )
  const expected = criticalKnowledgeTemplateSemantics(headAst)
  const actual = criticalKnowledgeTemplateSemantics(ast)
  for (const [name, message] of [
    ['headerActions', 'retrieval/import header action subtrees'],
    ['toolbars', 'all toolbar controls and handlers'],
    ['tables', 'all table columns, row templates, selections, and operations'],
    ['forms', 'policy and dialog form subtrees'],
    ['dialogChildren', 'all dialog default/footer subtrees and handlers'],
  ]) {
    if (JSON.stringify(actual[name]) !== JSON.stringify(expected[name])) {
      localFailures.push(`${label} must preserve HEAD ${message}`)
    }
  }
}

function knowledgeDetailHasPrivateVisualRecipe(styles) {
  const forbiddenVisualProperties = new Set([
    'box-shadow',
    '-webkit-box-shadow',
    'backdrop-filter',
    '-webkit-backdrop-filter',
    'filter',
  ])
  return compiledRules(styles).some(({ selector, declarations }) => {
    if (/\[data-(?:theme|brand)\b/i.test(selector) || /\.el-[a-z0-9_-]+/i.test(selector)) {
      return true
    }
    return declarations.some(({ property, value }) => {
      if (forbiddenVisualProperties.has(property) || property.startsWith('background')) return true
      if (property !== 'border' && !/^border-(?!radius(?:-|$))/.test(property)) return false
      return !(selector.includes('.colored-tag') && property === 'border' && value === '0')
    })
  })
}

function knowledgeStatusTagsAreSemantic(sourceFile, statusTags) {
  if (
    statusTags.length !== 4 ||
    !hasFunctionReturnType(sourceFile, 'statusTagType', 'StatusTone') ||
    !hasFunctionReturnType(sourceFile, 'scopeTagType', 'StatusTone')
  ) {
    return false
  }
  const expected = [
    ["kbInfo?.scope || 'WORKSPACE'", 'scopeTagType(kbInfo?.scope)'],
    ['statusText(row.status)', 'statusTagType(row.status)'],
    ["row.enabled === 0 ? '停用' : '启用'", "row.enabled === 0 ? 'info' : 'success'"],
    ["row.directReturn ? '是' : '否'", "row.directReturn ? 'success' : 'info'"],
  ]
  return expected.every(([labelSource, toneSource]) =>
    statusTags.some(
      (tag) =>
        expressionMatchesSource(directiveExpression(tag, 'bind', 'label'), labelSource) &&
        expressionMatchesSource(directiveExpression(tag, 'bind', 'tone'), toneSource),
    ),
  )
}

async function validateKnowledgeDetailConsumer(source, label, options = {}) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const allElements = allTemplateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (
    !knowledgeDetailComponentImports.every(([name, moduleName]) =>
      importsDefault(sourceFile, name, moduleName),
    ) ||
    !importsSymbol(
      sourceFile,
      '@/components/common/glassWorkbench',
      'MetricStripItem',
    ) ||
    !importsSymbol(sourceFile, '@/components/common/glassWorkbench', 'StatusTone')
  ) {
    localFailures.push(
      `${label} must directly import all seven KnowledgeDetail shared components plus MetricStripItem and StatusTone`,
    )
  }
  if (!importsNamed(sourceFile, ['Plus', 'Refresh', 'Search', 'Upload'], '@element-plus/icons-vue')) {
    localFailures.push(`${label} must preserve the four content action icons after shared back migration`)
  }

  if (!workbenchPageRootContract(rootElement, 'comfortable')) {
    localFailures.push(`${label} page root must use comfortable workbench density`)
  }

  const kbCode = findVariableDeclaration(sourceFile, 'kbCode')
  const kbCodeInitializer = kbCode?.initializer
  if (
    !kbCodeInitializer ||
    !ts.isAsExpression(kbCodeInitializer) ||
    kbCodeInitializer.type.kind !== ts.SyntaxKind.StringKeyword ||
    !isPropertyChain(kbCodeInitializer.expression, ['route', 'params', 'code'])
  ) {
    localFailures.push(`${label} must preserve const kbCode = route.params.code as string`)
  }

  const pageHeaders = elements.filter((element) => element.tag === 'PageHeader')
  const pageHeader = pageHeaders.length === 1 ? pageHeaders[0] : undefined
  if (
    !pageHeader ||
    !hasStaticBooleanAttribute(pageHeader, 'show-back') ||
    !expressionMatchesSource(
      directiveExpression(pageHeader, 'bind', 'title'),
      "kbInfo?.name || '知识库详情'",
    ) ||
    !isRouterPushPath(directiveExpression(pageHeader, 'on', 'back'), '/knowledge')
  ) {
    localFailures.push(
      `${label} PageHeader must preserve the title and show-back router.push('/knowledge') behavior`,
    )
  }
  if (
    !pageHeader ||
    !task8WrapperUsesOnly(pageHeader, [
      'attribute:variant',
      'attribute:domain',
      'directive:bind:title:',
      'attribute:show-back',
      'directive:on:back:',
    ])
  ) {
    localFailures.push(
      `${label} PageHeader must use only its Task 8 runtime directive and attribute allowlist`,
    )
  }

  const pageHeaderDescendants = pageHeader ? descendantElements(ast, pageHeader) : []
  const headerTags = pageHeaderDescendants.find(
    (element) => element.tag === 'template' && directive(element, 'slot', 'tags'),
  )
  const headerMeta = pageHeaderDescendants.find(
    (element) => element.tag === 'template' && directive(element, 'slot', 'meta'),
  )
  const codeTag = headerTags
    ? descendantElements(ast, headerTags).find(
        (element) =>
          element.tag.toLowerCase() === 'el-tag' &&
          hasInterpolationProperty(element, ['kbCode']),
      )
    : undefined
  const projectMeta = headerMeta
    ? descendantElements(ast, headerMeta).find(
        (element) =>
          element.tag === 'span' &&
          expressionMatchesSource(
            directiveExpression(element, 'if', undefined),
            'kbInfo?.projectCode',
          ) &&
          hasInterpolationProperty(element, ['kbInfo', 'projectCode']),
      )
    : undefined
  if (!headerTags || !headerMeta || !codeTag || !projectMeta) {
    localFailures.push(`${label} PageHeader tags/meta slots must preserve code, scope, and project metadata`)
  }

  const metricStrips = elements.filter((element) => element.tag === 'MetricStrip')
  if (
    metricStrips.length !== 1 ||
    !isIdentifierValue(
      directiveExpression(metricStrips[0], 'bind', 'items'),
      'knowledgeMetrics',
    ) ||
    !validatesKnowledgeMetrics(sourceFile)
  ) {
    localFailures.push(`${label} must render the exact five-item knowledgeMetrics MetricStrip`)
  }
  if (
    metricStrips.length !== 1 ||
    !task8WrapperUsesOnly(metricStrips[0], [
      'directive:bind:items:',
      'attribute:aria-label',
    ])
  ) {
    localFailures.push(
      `${label} MetricStrip must use only its Task 8 runtime directive and attribute allowlist`,
    )
  }

  const panels = elements.filter((element) => element.tag === 'WorkbenchPanel')
  const expectedPanelTitles = ['命中热度', '最近命中', '低置信命中', '零命中段落', '标签库']
  const panelsAreExact = Boolean(
    panels.length === expectedPanelTitles.length &&
      expectedPanelTitles.every(
        (title) => panels.filter((panel) => staticAttributeValue(panel, 'title') === title).length === 1,
      ) &&
      panels.every(
        (panel) => descendantElements(ast, panel).filter(isKnowledgeTable).length === 1,
      ),
  )
  if (!panelsAreExact) {
    localFailures.push(`${label} must render four overview panels plus the tag library WorkbenchPanel`)
  }
  if (
    panels.some(
      (panel) =>
        !task8WrapperUsesOnly(panel, [
          'attribute:title',
          'attribute:density',
        ]),
    )
  ) {
    localFailures.push(
      `${label} WorkbenchPanel must use only its Task 8 runtime directive and attribute allowlist`,
    )
  }

  const tabPanes = elements.filter((element) => element.tag.toLowerCase() === 'el-tab-pane')
  const actualTabs = tabPanes.map((pane) => [
    staticAttributeValue(pane, 'label'),
    staticAttributeValue(pane, 'name'),
  ])
  if (JSON.stringify(actualTabs) !== JSON.stringify(knowledgeTabContract)) {
    localFailures.push(`${label} must preserve all seven tab labels and names in order`)
  }

  const tableShells = elements.filter((element) => element.tag === 'DataTableShell')
  const tables = elements.filter(isKnowledgeTable)
  const tagPane = tabPanes.find((pane) => staticAttributeValue(pane, 'name') === 'tags')
  const tagLibraryPanel = panels.find(
    (panel) => staticAttributeValue(panel, 'title') === '标签库',
  )
  const tagListTable = tables.find((table) =>
    expressionMatchesSource(directiveExpression(table, 'bind', 'data'), 'tags'),
  )
  const tagListShell = tagListTable
    ? tableShells.find((shell) => descendantElements(ast, shell).includes(tagListTable))
    : undefined
  const tagPanelStacks = allElements.filter(
    (element) => element.tag === 'div' && hasStaticClass(element, 'tag-panel-stack'),
  )
  const tagPanelStack = tagPanelStacks[0]
  const tagPanelStackChildren = (tagPanelStack?.children ?? []).filter((child) => child.type === 1)
  const tagPanelStackRules = compiledRules(styles).filter(({ selector }) =>
    /^\.tag-panel-stack\[data-v-[-a-z0-9_]+\]$/i.test(selector),
  )
  const tagPanelStackDeclarations = tagPanelStackRules[0]?.declarations
    .map(({ property, value }) => `${property}:${value}`)
    .sort()
  const ownsLegacyTagPanelSelector = compiledRules(styles).some(({ selector }) =>
    selector.includes('.tag-library-panel'),
  )
  const ownsWorkbenchPanelOuterMargin = compiledRules(styles).some(
    ({ selector, declarations }) =>
      selector.includes('.workbench-panel') &&
      declarations.some(
        ({ property }) => property === 'margin' || property.startsWith('margin-'),
      ),
  )
  const tagPanelSpacingContract = Boolean(
    tagPane &&
      tagLibraryPanel &&
      tagListShell &&
      tagPanelStacks.length === 1 &&
      descendantElements(ast, tagPane).includes(tagPanelStack) &&
      arraysEqual(tagPanelStackChildren, [tagLibraryPanel, tagListShell]) &&
      task8WrapperUsesOnly(tagPanelStack, ['attribute:class']) &&
      tagPanelStackRules.length === 1 &&
      arraysEqual(tagPanelStackDeclarations, [
        'display:grid',
        'gap:var(--section-gap)',
      ]) &&
      !ownsLegacyTagPanelSelector &&
      !ownsWorkbenchPanelOuterMargin,
  )
  if (!tagPanelSpacingContract) {
    localFailures.push(
      `${label} tag library spacing must be owned by one semantic tag-panel-stack without WorkbenchPanel presentation class or margin`,
    )
  }
  let tableContractsValid = tableShells.length === 10 && tables.length === 10
  for (const [dataSource, emptySource, loadingName] of knowledgeTableContract) {
    const matchingTables = tables.filter((table) =>
      expressionMatchesSource(directiveExpression(table, 'bind', 'data'), dataSource),
    )
    const table = matchingTables.length === 1 ? matchingTables[0] : undefined
    const owningShells = table
      ? tableShells.filter((shell) => descendantElements(ast, shell).includes(table))
      : []
    const shell = owningShells.length === 1 ? owningShells[0] : undefined
    const shellLoading = directiveExpression(shell, 'bind', 'loading')
    const loadingIsValid = loadingName
      ? isIdentifierValue(shellLoading, loadingName)
      : shellLoading === undefined
    if (
      !table ||
      !shell ||
      descendantElements(ast, shell).filter(isKnowledgeTable).length !== 1 ||
      staticAttributeValue(shell, 'density') !== 'compact' ||
      !expressionMatchesSource(directiveExpression(shell, 'bind', 'empty'), emptySource) ||
      !loadingIsValid ||
      staticAttributeValue(table, 'empty-text') !== ' ' ||
      directive(table, 'loading', undefined)
    ) {
      tableContractsValid = false
    }
  }
  if (
    allElements.some((element) => element.tag.toLowerCase() === 'el-empty') ||
    tableShells.some(
      (shell) => descendantElements(ast, shell).filter(isKnowledgeTable).length !== 1,
    )
  ) {
    tableContractsValid = false
  }
  if (!tableContractsValid) {
    localFailures.push(
      `${label} all ten tables must use one compact DataTableShell with owned loading and a single suppressed empty state`,
    )
  }
  if (
    tableShells.some(
      (shell) =>
        !task8WrapperUsesOnly(shell, [
          'attribute:density',
          'directive:bind:empty:',
          'directive:bind:loading:',
        ]),
    )
  ) {
    localFailures.push(
      `${label} DataTableShell must use only its Task 8 runtime directive and attribute allowlist`,
    )
  }

  const statusTags = elements.filter((element) => element.tag === 'StatusTag')
  if (!knowledgeStatusTagsAreSemantic(sourceFile, statusTags)) {
    localFailures.push(`${label} scope, file, chunk, and direct-return states must use semantic StatusTag`)
  }

  const dialogContract = [
    ['tagDialogVisible', '新增标签', '460px'],
    ['batchTagDialogVisible', 'Batch Tags', '520px'],
    ['questionDialogVisible', '新增问题映射', '560px'],
  ]
  const appDialogs = elements.filter((element) => element.tag === 'AppDialog')
  const dialogsAreExact = Boolean(
    appDialogs.length === dialogContract.length &&
      dialogContract.every(([modelName, title, width]) => {
        const matches = appDialogs.filter((dialog) =>
          hasModelBinding(dialog, undefined, [modelName]),
        )
        const dialog = matches.length === 1 ? matches[0] : undefined
        return Boolean(
          dialog &&
            staticAttributeValue(dialog, 'title') === title &&
            staticAttributeValue(dialog, 'width') === width &&
            componentFooterSlot(ast, dialog),
        )
      }),
  )
  if (!dialogsAreExact) {
    localFailures.push(`${label} must render all three complete AppDialogs with preserved widths and footers`)
  }
  if (
    appDialogs.some(
      (dialog) =>
        !task8WrapperUsesOnly(dialog, [
          'directive:model::',
          'attribute:title',
          'attribute:width',
        ]),
    )
  ) {
    localFailures.push(
      `${label} AppDialog must use only HEAD-equivalent model/title/width runtime attributes`,
    )
  }

  const rawCardsOrDialogs = allElements.some((element) =>
    ['el-card', 'el-dialog'].includes(element.tag.toLowerCase()),
  )
  if (rawCardsOrDialogs) {
    localFailures.push(`${label} must not retain raw el-card or el-dialog`)
  }

  const legacyClasses = new Set([
    'page-header',
    'header-left',
    'header-actions',
    'subline',
    'stats-grid',
    'stat-tile',
    'tag-library-card',
  ])
  const ownsLegacyClass = allElements.some((element) =>
    (staticAttributeValue(element, 'class')?.split(/\s+/) ?? []).some((className) =>
      legacyClasses.has(className),
    ),
  )
  const ownsLegacySelector = compiledRules(styles).some(({ selector }) =>
    [...legacyClasses].some((className) => selector.includes(`.${className}`)),
  )
  if (ownsLegacyClass || ownsLegacySelector || knowledgeDetailHasPrivateVisualRecipe(styles)) {
    localFailures.push(
      `${label} must remove legacy/private page surfaces while preserving only content tag swatches and layout`,
    )
  }

  if (!options.skipHeadPreservation) {
    validateKnowledgeDetailHeadPreservation(sourceFile, ast, label, localFailures)
  }
  return localFailures
}

const syntheticKnowledgeDetailConsumer = `<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      :title="kbInfo?.name || '知识库详情'"
      show-back
      @back="router.push('/knowledge')"
    >
      <template #tags>
        <el-tag>{{ kbCode }}</el-tag>
        <StatusTag
          :label="kbInfo?.scope || 'WORKSPACE'"
          :tone="scopeTagType(kbInfo?.scope)"
        />
      </template>
      <template #meta>
        <span v-if="kbInfo?.projectCode">项目 {{ kbInfo.projectCode }}</span>
      </template>
    </PageHeader>

    <MetricStrip :items="knowledgeMetrics" aria-label="知识库运营指标" />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="运营概览" name="overview">
        <WorkbenchPanel title="命中热度" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.hotChunks || []).length === 0">
            <el-table :data="dashboard?.hotChunks || []" empty-text=" "><el-table-column prop="chunkIndex" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="最近命中" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.recentHits || []).length === 0">
            <el-table :data="dashboard?.recentHits || []" empty-text=" "><el-table-column prop="queryText" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="低置信命中" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.lowConfidenceHits || []).length === 0">
            <el-table :data="dashboard?.lowConfidenceHits || []" empty-text=" "><el-table-column prop="queryText" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="零命中段落" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.zeroHitChunks || []).length === 0">
            <el-table :data="dashboard?.zeroHitChunks || []" empty-text=" "><el-table-column prop="chunkIndex" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
      </el-tab-pane>

      <el-tab-pane label="文件" name="files">
        <DataTableShell density="compact" :empty="fileList.length === 0" :loading="filesLoading">
          <el-table :data="fileList" empty-text=" ">
            <el-table-column label="状态">
              <template #default="{ row }"><StatusTag :label="statusText(row.status)" :tone="statusTagType(row.status)" /></template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="段落运营" name="chunks">
        <DataTableShell density="compact" :empty="chunks.length === 0" :loading="chunksLoading">
          <el-table :data="chunks" empty-text=" ">
            <el-table-column label="状态">
              <template #default="{ row }">
                <StatusTag :label="row.enabled === 0 ? '停用' : '启用'" :tone="row.enabled === 0 ? 'info' : 'success'" />
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="命中分析" name="hits">
        <DataTableShell density="compact" :empty="hitLogs.length === 0" :loading="hitsLoading">
          <el-table :data="hitLogs" empty-text=" ">
            <el-table-column label="直接返回">
              <template #default="{ row }">
                <StatusTag :label="row.directReturn ? '是' : '否'" :tone="row.directReturn ? 'success' : 'info'" />
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="检索策略" name="policy" />

      <el-tab-pane label="标签" name="tags">
        <div class="tag-panel-stack">
          <WorkbenchPanel title="标签库" density="compact">
            <DataTableShell density="compact" :empty="tagStats.length === 0">
              <el-table :data="tagStats" empty-text=" "><el-table-column prop="tagKey" /></el-table>
            </DataTableShell>
          </WorkbenchPanel>
          <DataTableShell density="compact" :empty="tags.length === 0">
            <el-table :data="tags" empty-text=" "><el-table-column prop="tagValue" /></el-table>
          </DataTableShell>
        </div>
      </el-tab-pane>

      <el-tab-pane label="问题映射" name="questions">
        <DataTableShell density="compact" :empty="questions.length === 0">
          <el-table :data="questions" empty-text=" "><el-table-column prop="question" /></el-table>
        </DataTableShell>
      </el-tab-pane>
    </el-tabs>

    <AppDialog v-model="tagDialogVisible" title="新增标签" width="460px">
      <el-form />
      <template #footer><el-button @click="tagDialogVisible = false">取消</el-button></template>
    </AppDialog>
    <AppDialog v-model="batchTagDialogVisible" title="Batch Tags" width="520px">
      <el-form />
      <template #footer><el-button @click="batchTagDialogVisible = false">Cancel</el-button></template>
    </AppDialog>
    <AppDialog v-model="questionDialogVisible" title="新增问题映射" width="560px">
      <el-form />
      <template #footer><el-button @click="questionDialogVisible = false">取消</el-button></template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import type { MetricStripItem, StatusTone } from '@/components/common/glassWorkbench'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string
const activeTab = ref('overview')
const kbInfo = ref<any>(null)
const dashboard = ref<any>(null)
const fileList = ref<any[]>([])
const chunks = ref<any[]>([])
const hitLogs = ref<any[]>([])
const tagStats = ref<any[]>([])
const tags = ref<any[]>([])
const questions = ref<any[]>([])
const filesLoading = ref(false)
const chunksLoading = ref(false)
const hitsLoading = ref(false)
const tagDialogVisible = ref(false)
const batchTagDialogVisible = ref(false)
const questionDialogVisible = ref(false)
const stats = computed(() => ({ fileCount: 0, chunkCount: 0, activeChunkCount: 0, questionCount: 0, hitCount: 0 }))
const knowledgeMetrics = computed<MetricStripItem[]>(() => [
  { key: 'files', label: '文件', value: stats.value.fileCount, iconKey: 'workflow-sources', tone: 'brand' },
  { key: 'chunks', label: '段落', value: stats.value.chunkCount, iconKey: 'api-discovery', tone: 'info' },
  { key: 'active', label: '启用段落', value: stats.value.activeChunkCount, iconKey: 'agent-ready', tone: 'success' },
  { key: 'questions', label: '问题', value: stats.value.questionCount, iconKey: 'ai-semantic', tone: 'warning' },
  { key: 'hits', label: '命中', value: stats.value.hitCount, iconKey: 'scan', tone: 'brand' },
])

function statusText(status: number) {
  return status === 1 ? '完成' : '未知'
}
function statusTagType(status: number): StatusTone {
  return status === 1 ? 'success' : 'info'
}
function scopeTagType(scope?: string): StatusTone {
  return scope === 'SHARED' ? 'success' : 'info'
}
</script>

<style scoped lang="scss">
.tag-panel-stack {
  display: grid;
  gap: var(--section-gap);
}
</style>
`

const syntheticGlassWorkbench = `export type WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'
export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
export type MetricTone = StatusTone | 'brand'
export type WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'
export interface MetricStripItem {
  key: string
  label: string
  value: string | number
  hint?: string
  tone?: MetricTone
  iconKey?: string
}
export interface WizardStep {
  key: string
  title: string
  description?: string
  state: WizardStepState
  disabled?: boolean
}
export function densityClass(density: WorkbenchDensity): \`density-\${WorkbenchDensity}\` {
  return \`density-\${density}\`
}
`

const syntheticMetricStrip = `<script setup lang="ts">
import MetricIconBg from './MetricIconBg.vue'
import { densityClass, type MetricStripItem, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  items: MetricStripItem[]
  density?: WorkbenchDensity
  ariaLabel?: string
}>(), {
  density: 'comfortable',
  ariaLabel: '指标概览',
})
</script>

<template>
  <section
    :class="['metric-strip', 'glass-surface-panel', densityClass(props.density)]"
    role="list"
    :aria-label="props.ariaLabel"
  >
    <article
      v-for="item in props.items"
      :key="item.key"
      class="metric-strip__item"
      role="listitem"
    >
      <MetricIconBg :icon-key="item.iconKey" :tone="item.tone ?? 'brand'" />
      <div class="metric-strip__content">
        <span class="metric-strip__label">{{ item.label }}</span>
        <strong class="metric-strip__value">{{ item.value }}</strong>
        <small v-if="item.hint" class="metric-strip__hint">{{ item.hint }}</small>
      </div>
    </article>
  </section>
</template>

<style scoped lang="scss">
.metric-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: var(--section-gap);
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.metric-strip__item + .metric-strip__item {
  border-inline-start: 1px solid var(--border-divider);
}
</style>
`

const syntheticStatusTag = `<script setup lang="ts">
import { computed } from 'vue'
import type { StatusTone } from './glassWorkbench'

const props = withDefaults(defineProps<{
  label: string
  tone?: StatusTone
  size?: 'small' | 'default' | 'large'
  pulse?: boolean
}>(), {
  tone: 'neutral',
  size: 'small',
  pulse: false,
})
const tagType = computed(() => props.tone === 'neutral' ? 'info' : props.tone)
</script>

<template>
  <el-tag
    class="status-tag"
    :class="\`status-tag--\${props.tone}\`"
    :type="tagType"
    :size="props.size"
  >
    <span v-if="props.pulse" class="status-tag__pulse" aria-hidden="true" />
    {{ props.label }}
  </el-tag>
</template>

<style scoped lang="scss">
.status-tag--success {
  color: var(--status-success);
  background: var(--status-success-soft);
}
.status-tag--warning {
  color: var(--status-warning);
  background: var(--status-warning-soft);
}
.status-tag--danger {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}
.status-tag--info {
  color: var(--status-info);
  background: var(--status-info-soft);
}
.status-tag--neutral {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}
</style>
`

const syntheticMetricIconBg = `<script setup lang="ts">
import { computed } from 'vue'
import type { MetricTone } from './glassWorkbench'

const props = withDefaults(defineProps<{
  iconKey?: string
  tone?: string
}>(), {
  iconKey: 'scan',
  tone: 'brand',
})
const toneAliases = {
  brand: 'brand',
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  info: 'info',
  neutral: 'neutral',
  green: 'success',
  orange: 'warning',
  muted: 'neutral',
} satisfies Record<string, MetricTone>
const semanticTone = computed<MetricTone>(() => toneAliases[props.tone] ?? 'brand')
</script>

<template>
  <span class="metric-icon-bg" :class="\`metric-icon-bg--\${semanticTone}\`" aria-hidden="true">
    <slot v-if="$slots.default" />
    <svg v-else class="metric-icon-bg__svg" viewBox="0 0 24 24" focusable="false" />
  </span>
</template>

<style scoped lang="scss">
.metric-icon-bg {
  width: var(--metric-icon-bg-size, 48px);
  height: var(--metric-icon-bg-size, 48px);
  border-radius: var(--metric-icon-bg-radius, 14px);
  background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight);
}
.metric-icon-bg__svg {
  width: var(--metric-icon-glyph-size, 24px);
  stroke-width: var(--metric-icon-stroke-width, 1.55);
}
.metric-icon-bg--brand {
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}
.metric-icon-bg--success {
  color: var(--status-success);
  background: var(--status-success-soft);
}
.metric-icon-bg--warning {
  color: var(--status-warning);
  background: var(--status-warning-soft);
}
.metric-icon-bg--danger {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}
.metric-icon-bg--info {
  color: var(--status-info);
  background: var(--status-info-soft);
}
.metric-icon-bg--neutral {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}
</style>
`

const syntheticCommonStatusTag = `<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from './common/StatusTag.vue'
import type { StatusTone } from './common/glassWorkbench'
import { commonStatusTagType, formatCommonStatusLabel } from '@/utils/uiLabels'

const props = defineProps<{
  status?: string | null
}>()
const label = computed(() => formatCommonStatusLabel(props.status))
const tone = computed<StatusTone>(() => commonStatusTagType(props.status))
</script>

<template>
  <StatusTag :label="label" :tone="tone" />
</template>
`

const syntheticPageHeader = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  title: string
  eyebrow?: string
  description?: string
  density?: WorkbenchDensity
  backLabel?: string
  showBack?: boolean
}>(), {
  density: 'comfortable',
  backLabel: '返回',
  showBack: false,
})
const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <header :class="['app-page-header', 'glass-surface-panel', densityClass(props.density)]">
    <button
      v-if="props.showBack"
      class="app-page-header__back"
      :aria-label="props.backLabel"
      @click="emit('back')"
    >
      Back
    </button>
    <slot name="leading" />
    <slot name="tags" />
    <slot name="meta" />
    <slot name="actions" />
  </header>
</template>

<style scoped lang="scss">
.app-page-header {
  padding: var(--layout-page-header-padding-block) var(--layout-page-header-padding-inline);
  gap: var(--section-gap);
}

.app-page-header__back:focus-visible {
  outline: 2px solid var(--border-focus);
}
</style>
`

const syntheticWorkbenchPanel = `<script setup lang="ts">
import { computed } from 'vue'
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  title?: string
  description?: string
  density?: WorkbenchDensity
  level?: 'panel' | 'control'
}>(), {
  density: 'comfortable',
  level: 'panel',
})
const surfaceClass = computed(() => props.level === 'control' ? 'glass-surface-control' : 'glass-surface-panel')
</script>

<template>
  <section :class="['workbench-panel', surfaceClass, densityClass(props.density)]">
    <header class="workbench-panel__header">
      <slot name="header" />
      <div class="workbench-panel__actions"><slot name="actions" /></div>
    </header>
    <div class="workbench-panel__body"><slot /></div>
    <footer class="workbench-panel__footer"><slot name="footer" /></footer>
  </section>
</template>

<style scoped lang="scss">
.workbench-panel {
  padding: var(--panel-padding);
  gap: var(--section-gap);
}
</style>
`

const syntheticFilterBar = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  density?: WorkbenchDensity
  loading?: boolean
  showReset?: boolean
  queryLabel?: string
  resetLabel?: string
}>(), {
  density: 'comfortable',
  loading: false,
  showReset: true,
  queryLabel: '查询',
  resetLabel: '重置',
})
const emit = defineEmits<{ query: []; reset: [] }>()
</script>

<template>
  <form
    :class="['filter-bar', 'glass-surface-control', densityClass(props.density)]"
    @submit.prevent="emit('query')"
  >
    <div class="filter-bar__fields"><slot /></div>
    <div class="filter-bar__actions">
      <slot name="actions">
        <el-button
          v-if="props.showReset"
          native-type="button"
          @click="emit('reset')"
        >
          {{ props.resetLabel }}
        </el-button>
        <el-button type="primary" native-type="submit" :loading="props.loading">
          {{ props.queryLabel }}
        </el-button>
      </slot>
    </div>
  </form>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: var(--section-gap);
  padding: var(--panel-padding);
}
</style>
`

const syntheticDataTableShell = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  density?: WorkbenchDensity
  loading?: boolean
  empty?: boolean
  emptyDescription?: string
  currentPage?: number
  pageSize?: number
  total?: number
  pageSizes?: number[]
  paginationLayout?: string
}>(), {
  density: 'comfortable',
  loading: false,
  empty: false,
  emptyDescription: '暂无数据',
  currentPage: 1,
  pageSize: 20,
  total: 0,
  pageSizes: () => [10, 20, 50, 100],
  paginationLayout: 'total, sizes, prev, pager, next, jumper',
})
const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  pageChange: [page: number]
  sizeChange: [size: number]
}>()
function handlePageChange(page: number) {
  emit('update:currentPage', page)
  emit('pageChange', page)
}
function handleSizeChange(size: number) {
  emit('update:pageSize', size)
  emit('sizeChange', size)
}
</script>

<template>
  <section
    v-loading="props.loading"
    :class="['data-table-shell', 'glass-surface-panel', densityClass(props.density)]"
  >
    <div v-if="$slots.toolbar" class="data-table-shell__toolbar">
      <slot name="toolbar" />
    </div>
    <div class="data-table-shell__body"><slot /></div>
    <div v-if="props.empty && !props.loading" class="data-table-shell__empty">
      <slot name="empty"><el-empty :description="props.emptyDescription" /></slot>
    </div>
    <div
      v-if="$slots.pagination || props.total > 0"
      class="data-table-shell__pagination"
    >
      <slot name="pagination">
        <el-pagination
          :current-page="props.currentPage"
          :page-size="props.pageSize"
          :total="props.total"
          :page-sizes="props.pageSizes"
          :layout="props.paginationLayout"
          @update:current-page="handlePageChange"
          @update:page-size="handleSizeChange"
        />
      </slot>
    </div>
  </section>
</template>

<style scoped lang="scss">
.data-table-shell {
  display: flex;
  gap: var(--section-gap);
  padding: var(--panel-padding);
}
</style>
`

const syntheticAppDialog = `<script setup lang="ts">
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  modelValue: boolean
  title: string
  description?: string
  width?: string | number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-dialog
    v-bind="$attrs"
    class="app-dialog glass-surface-overlay"
    :model-value="props.modelValue"
    :title="props.title"
    :width="props.width"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="$slots.header" #header="headerProps">
      <slot name="header" v-bind="headerProps" />
    </template>
    <p v-if="props.description" class="app-dialog__description">
      {{ props.description }}
    </p>
    <hr v-if="props.description" class="app-dialog__divider" />
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.app-dialog__description {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.app-dialog__divider {
  margin: var(--section-gap) 0;
  border: 0;
  border-block-start: 1px solid var(--border-divider);
}
</style>
`

const syntheticWizardDialog = `<script setup lang="ts">
import { computed } from 'vue'

import AppDialog from './AppDialog.vue'
import {
  densityClass,
  type StatusTone,
  type WizardStep,
  type WizardStepState,
} from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    description?: string
    steps: WizardStep[]
    activeStep: number
    navigable?: boolean
    canAdvance?: boolean
    loading?: boolean
    finishLabel?: string
  }>(),
  {
    navigable: false,
    canAdvance: true,
    loading: false,
    finishLabel: '完成',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  stepChange: [index: number]
  back: []
  next: []
  cancel: []
  finish: []
}>()

const stepToneByState = {
  pending: 'neutral',
  current: 'info',
  complete: 'success',
  error: 'danger',
  skipped: 'neutral',
} satisfies Record<WizardStepState, StatusTone>

const activeErrorStep = computed(() => {
  const step = props.steps[props.activeStep]
  return step && step.state === 'error' ? step : undefined
})

function stepTone(state: WizardStepState): StatusTone {
  return stepToneByState[state]
}
</script>

<template>
  <AppDialog
    :model-value="props.modelValue"
    :title="props.title"
    :description="props.description"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div :class="['wizard-dialog', densityClass('spacious')]">
      <aside class="wizard-dialog__rail glass-surface-control">
        <div v-if="$slots.summary" class="wizard-dialog__summary">
          <slot name="summary" />
        </div>
        <ol class="wizard-dialog__steps" aria-label="步骤">
          <li
            v-for="(step, index) in props.steps"
            :key="step.key"
            class="wizard-dialog__step"
            :class="[
              \`wizard-dialog__step--state-\${step.state}\`,
              \`wizard-dialog__step--tone-\${stepTone(step.state)}\`,
            ]"
            :aria-current="index === props.activeStep ? 'step' : undefined"
          >
            <button
              class="wizard-dialog__step-button"
              type="button"
              :disabled="!props.navigable || step.disabled"
              @click="emit('stepChange', index)"
            >
              <span class="wizard-dialog__step-marker" aria-hidden="true">{{ index + 1 }}</span>
              <span class="wizard-dialog__step-copy">
                <strong class="wizard-dialog__step-title">{{ step.title }}</strong>
                <span v-if="step.description" class="wizard-dialog__step-description">
                  {{ step.description }}
                </span>
              </span>
            </button>
          </li>
        </ol>
      </aside>

      <main class="wizard-dialog__body">
        <div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">
          <div v-if="activeErrorStep" class="wizard-dialog__validation">
            <strong class="wizard-dialog__validation-title">{{ activeErrorStep.title }}</strong>
            <span
              v-if="activeErrorStep.description"
              class="wizard-dialog__validation-description"
            >
              {{ activeErrorStep.description }}
            </span>
          </div>
        </div>
        <slot />
      </main>
    </div>

    <template #footer>
      <slot name="footer">
        <div class="wizard-dialog__footer">
          <el-button native-type="button" @click="emit('cancel')">取消</el-button>
          <el-button
            native-type="button"
            :disabled="props.activeStep === 0 || props.loading"
            @click="emit('back')"
          >
            上一步
          </el-button>
          <el-button
            v-if="props.activeStep < props.steps.length - 1"
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance || props.loading"
            @click="emit('next')"
          >
            下一步
          </el-button>
          <el-button
            v-else
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance"
            :loading="props.loading"
            @click="emit('finish')"
          >
            {{ props.finishLabel }}
          </el-button>
        </div>
      </slot>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.wizard-dialog {
  display: grid;
  grid-template-columns: minmax(13rem, 18rem) minmax(0, 1fr);
  gap: var(--section-gap);
  min-width: 0;
  color: var(--text-primary);
}

.wizard-dialog__rail {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.wizard-dialog__summary {
  color: var(--text-secondary);
}

.wizard-dialog__steps {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.wizard-dialog__step-button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: calc(var(--section-gap) / 2);
  align-items: start;
  width: 100%;
  min-height: var(--control-height);
  padding: calc(var(--panel-padding) / 2);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.wizard-dialog__step-button:disabled {
  color: var(--text-disabled);
  cursor: not-allowed;
}

.wizard-dialog__step-button:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.wizard-dialog__step-marker {
  display: inline-grid;
  place-items: center;
  min-width: calc(var(--control-height) / 2);
  min-height: calc(var(--control-height) / 2);
  border: 1px solid currentColor;
  border-radius: var(--radius-xl);
}

.wizard-dialog__step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
}

.wizard-dialog__step-title,
.wizard-dialog__step-description {
  display: block;
}

.wizard-dialog__step-description {
  color: var(--text-muted);
  font-size: 0.8125rem;
  line-height: 1.45;
}

.wizard-dialog__step--tone-success .wizard-dialog__step-button {
  color: var(--status-success);
  background: var(--status-success-soft);
}

.wizard-dialog__step--tone-danger .wizard-dialog__step-button {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__step--tone-info .wizard-dialog__step-button {
  color: var(--status-info);
  background: var(--status-info-soft);
}

.wizard-dialog__step--tone-neutral .wizard-dialog__step-button {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}

.wizard-dialog__step--state-current .wizard-dialog__step-button {
  border: 1px solid var(--border-focus);
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.wizard-dialog__body {
  min-width: 0;
  max-height: min(70vh, 44rem);
  overflow-y: auto;
  padding-inline-end: calc(var(--panel-padding) / 4);
  color: var(--text-primary);
}

.wizard-dialog__live {
  min-height: 0;
}

.wizard-dialog__validation {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
  margin-bottom: var(--section-gap);
  padding: calc(var(--panel-padding) / 2);
  border: 1px solid var(--status-danger);
  border-radius: var(--radius-md);
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__validation-description {
  color: var(--text-secondary);
}

.wizard-dialog__footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: calc(var(--section-gap) / 2);
}

@media (max-width: 760px) {
  .wizard-dialog {
    grid-template-columns: 1fr;
  }
}
</style>
`

const syntheticAppDrawer = `<script setup lang="ts">
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  modelValue: boolean
  title: string
  description?: string
  size?: string | number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-drawer
    v-bind="$attrs"
    class="app-drawer glass-surface-overlay"
    :model-value="props.modelValue"
    :title="props.title"
    :size="props.size"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="$slots.header" #header="headerProps">
      <slot name="header" v-bind="headerProps" />
    </template>
    <p v-if="props.description" class="app-drawer__description">
      {{ props.description }}
    </p>
    <hr v-if="props.description" class="app-drawer__divider" />
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.app-drawer__description {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.app-drawer__divider {
  margin: var(--section-gap) 0;
  border: 0;
  border-block-start: 1px solid var(--border-divider);
}
</style>
`

const syntheticElementPlusMappings = `.el-table {
  --el-table-header-bg-color: var(--surface-solid-control);
  --el-table-header-text-color: var(--text-muted);
  --el-table-text-color: var(--text-secondary);
  --el-table-border-color: var(--border-subtle);
}

.el-pagination {
  --el-fill-color-blank: var(--surface-solid-control);
  --el-pagination-button-color: var(--text-secondary);
  --el-pagination-button-disabled-color: var(--text-disabled);
  --el-pagination-button-disabled-bg-color: var(--surface-solid-disabled);
  --el-color-primary: var(--brand-primary);
  --el-border-color: var(--border-readable);
}

.el-tabs {
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-border-color-light: var(--border-subtle);
  --el-color-primary: var(--brand-primary);
}

.el-dialog {
  --el-dialog-bg-color: var(--surface-solid-overlay);
  --el-text-color-primary: var(--text-primary);
  --el-border-color: var(--border-readable);
}

.el-drawer {
  --el-drawer-bg-color: var(--surface-solid-overlay);
  --el-text-color-primary: var(--text-primary);
  --el-border-color: var(--border-readable);
}

.el-pagination .btn-prev:focus-visible,
.el-pagination .btn-next:focus-visible,
.el-pagination .el-pager li:focus-visible,
.el-tabs__item:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}
`

const syntheticComponent = `<template>
  <section class="synthetic-panel glass-surface-panel density-comfortable">
    Contract baseline
  </section>
</template>

<style scoped lang="scss">
.synthetic-panel {
  color: var(--text-primary);
  background: rgb(var(--brand-primary-rgb) / 0.12);
  box-shadow: var(--shadow-panel);
}
</style>
`

const syntheticConsumer = `<script setup lang="ts">
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
</script>

<template>
  <WorkbenchPanel>Representative content</WorkbenchPanel>
</template>
`

const syntheticToolListConsumer = `<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      title="Tool 管理"
      description="管理平台可被 Agent 调用的 Tool、语义信息与运行开关"
    >
      <template #actions>
        <el-button type="primary" @click="openCreateDialog">新建 Tool</el-button>
        <el-button :loading="loading" @click="onRefresh">刷新</el-button>
      </template>
    </PageHeader>

    <FilterBar :loading="loading" @query="handleSearch" @reset="resetFilters">
      <el-form-item label="关键词">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="工具名或描述"
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="filters.source" clearable placeholder="全部" style="width: 130px">
          <el-option label="code" value="code" />
          <el-option label="scanner" value="scanner" />
          <el-option label="manual" value="manual" />
        </el-select>
      </el-form-item>
      <el-form-item label="启用">
        <el-select v-model="filters.enabled" clearable placeholder="全部" style="width: 120px">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
      </el-form-item>
    </FilterBar>

    <DataTableShell
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.size"
      density="compact"
      :loading="loading"
      :empty="tools.length === 0"
      empty-description="暂无数据，请调整条件或先在后端注册 Tool"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @page-change="fetchTools"
      @size-change="handlePageSizeChange"
    >
      <el-table :data="tools" empty-text=" " @expand-change="onToolExpandChange">
        <el-table-column label="来源">
          <template #default="{ row }">
            <StatusTag :label="row.source" :tone="sourceTagType(row.source)" />
          </template>
        </el-table-column>
        <el-table-column label="API 目录">
          <template #default="{ row }">
            <StatusTag
              :label="catalogHealthLabel(row.catalogLinkStatus)"
              :tone="catalogHealthTagType(row.catalogLinkStatus)"
            />
          </template>
        </el-table-column>
      </el-table>
    </DataTableShell>

    <WizardDialog
      v-model="formDialogVisible"
      :title="formDialogTitle"
      :steps="toolEditorStepsWithState"
      :active-step="activeToolStep"
      :can-advance="canAdvanceToolStep"
      :loading="saving"
      finish-label="保存"
      width="1180px"
      top="3vh"
      append-to-body
      @back="activeToolStep -= 1"
      @next="advanceToolStep"
      @finish="handleSave"
      @cancel="formDialogVisible = false"
    >
      <section class="tool-editor-main">
        <div class="tool-editor-summary">Summary</div>
        <el-alert title="Alert" />
        <el-form>
          <div v-show="activeToolStep === 0" class="tool-editor-panel">Identity</div>
          <div v-show="activeToolStep === 1" class="tool-editor-panel">Endpoint</div>
          <div v-show="activeToolStep === 2" class="tool-editor-panel"><ParameterTable /></div>
          <div v-show="activeToolStep === 3" class="tool-editor-panel">Release</div>
        </el-form>
      </section>
    </WizardDialog>

    <AppDialog
      v-model="testDialogVisible"
      :title="\`测试工具 — \${testingTool?.title || testingTool?.name}\`"
      width="600px"
      append-to-body
    >
      <el-form v-if="testingTool"><el-form-item><el-input /></el-form-item></el-form>
      <div v-if="testResult" class="test-result-area"><pre>{{ testResult }}</pre></div>
      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testRunning" @click="handleTest">执行</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WizardDialog from '@/components/common/WizardDialog.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type { StatusTone, WizardStep } from '@/components/common/glassWorkbench'

const loading = ref(false)
const saving = ref(false)
const tools = ref([])
const total = ref(0)
const pagination = reactive({ current: 1, size: 20 })
const filters = reactive({ keyword: '', source: undefined, enabled: undefined })
const formDialogVisible = ref(false)
const formDialogTitle = computed(() => '新建 Tool')
const activeToolStep = ref(0)
const form = reactive({ title: '', name: '', description: '' })
const toolEditorSteps = [
  { key: 'identity', title: '身份信息', desc: '名称、描述与项目' },
  { key: 'endpoint', title: '调用配置', desc: 'HTTP 地址与类型' },
  { key: 'parameters', title: '参数定义', desc: 'Agent 入参 Schema' },
  { key: 'release', title: '运行控制', desc: '启用与可见性' },
]
const toolEditorStepsWithState = computed<WizardStep[]>(() =>
  toolEditorSteps.map((step, index) => ({
    key: step.key,
    title: step.title,
    description: step.desc,
    state:
      index < activeToolStep.value
        ? 'complete'
        : index === activeToolStep.value
          ? 'current'
          : 'pending',
  })),
)
const canAdvanceToolStep = computed(
  () =>
    activeToolStep.value !== 0 ||
    Boolean(form.title.trim() && form.name.trim() && form.description.trim()),
)
const testDialogVisible = ref(false)
const testingTool = ref({ title: '演示工具', name: 'demo' })
const testResult = ref(null)
const testRunning = ref(false)

function sourceTagType(source: string): StatusTone {
  if (source === 'code') return 'success'
  if (source === 'scanner') return 'warning'
  return 'info'
}
function catalogHealthLabel(status: string) {
  return status
}
function catalogHealthTagType(status: string): StatusTone {
  if (status === 'IN_SYNC') return 'success'
  return 'info'
}
function advanceToolStep() {
  if (!canAdvanceToolStep.value) {
    ElMessage.warning('请填写工具名称、工具标识和描述')
    return
  }
  activeToolStep.value = Math.min(activeToolStep.value + 1, toolEditorSteps.length - 1)
}
function openCreateDialog() {}
function onRefresh() {}
function handleSearch() {}
function resetFilters() {}
function fetchTools() {}
function handlePageSizeChange() {}
function onToolExpandChange() {}
function handleSave() {}
function handleTest() {}
</script>

<style scoped lang="scss">
.tool-editor-main,
.tool-editor-panel {
  min-width: 0;
}
</style>
`

const agentEditComponentImports = [
  ['PageHeader', '@/components/common/PageHeader.vue'],
  ['WorkbenchPage', '@/components/common/WorkbenchPage.vue'],
  ['WorkbenchPanel', '@/components/common/WorkbenchPanel.vue'],
  ['StatusTag', '@/components/common/StatusTag.vue'],
]

const agentRuntimePresentationPropKeys = [
  'attribute:class',
  'attribute:style',
  'attribute:hidden',
  'attribute:width',
  'attribute:height',
  'directive:bind:class:',
  'directive:bind:style:',
  'directive:bind::',
]

const agentFormFieldContract = [
  ['名称', 'name', 'form.name'],
  ['keySlug', 'keySlug', 'form.keySlug'],
  ['入口类型', 'agentKind', 'form.agentKind'],
  ['可见性', null, 'form.visibility'],
  ['所属项目', null, 'form.projectId'],
  ['默认模型', null, 'form.modelInstanceId'],
  ['运行角色', null, 'form.allowedRoles'],
  ['描述', null, 'form.description'],
  ['System Prompt', null, 'form.systemPrompt'],
  ['入口配置', null, 'entryConfigText'],
]

function templateText(element) {
  const values = []
  const visit = (node) => {
    if (node?.type === 2) values.push(node.content)
    for (const child of node?.children ?? []) visit(child)
  }
  visit(element)
  return normalizeWhitespace(values.join(' '))
}

function templateSlot(ast, component, name) {
  return component
    ? descendantElements(ast, component).find(
        (element) => element.tag === 'template' && directive(element, 'slot', name),
      )
    : undefined
}

function canonicalElementProps(element) {
  return (element?.props ?? [])
    .map((property) => canonicalTemplateProp(property, undefined, element))
    .filter(Boolean)
    .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
}

function agentEditCondition(element) {
  return canonicalTypeScriptNode(directiveExpression(element, 'if', undefined))
}

function agentFormItems(ast) {
  const primaryPanel = allTemplateElements(ast).find(
    (element) =>
      element.tag === 'WorkbenchPanel' &&
      staticAttributeValue(element, 'title') === '身份与策略',
  )
  const scope = primaryPanel ? descendantElements(ast, primaryPanel) : allTemplateElements(ast)
  return scope.filter(
    (element) => element.tag.toLowerCase() === 'el-form-item',
  )
}

function agentFormFieldsMatchContract(ast) {
  const items = agentFormItems(ast)
  if (items.length !== agentFormFieldContract.length) return false
  return agentFormFieldContract.every(([label, prop, model], index) => {
    const item = items[index]
    const controls = descendantElements(ast, item).filter((element) =>
      ['el-input', 'el-select'].includes(element.tag.toLowerCase()),
    )
    return Boolean(
      staticAttributeValue(item, 'label') === label &&
        (staticAttributeValue(item, 'prop') ?? null) === prop &&
        controls.some((control) =>
          expressionMatchesSource(directiveExpression(control, 'model', undefined), model),
        ),
    )
  })
}

function agentEditFormVisibilityContract(ast, entries) {
  const allItems = agentFormItems(ast)
  const liveElements = new Set(templateElements(ast))
  const liveItems = allItems.filter((element) => liveElements.has(element))
  if (
    allItems.length !== agentFormFieldContract.length ||
    liveItems.length !== agentFormFieldContract.length
  ) {
    return false
  }

  const entryByElement = new Map(entries.map((entry) => [entry.element, entry]))
  if (allItems.some((item) => !entryByElement.get(item)?.reachable)) return false

  const primaryPanel = allTemplateElements(ast).find(
    (element) =>
      element.tag === 'WorkbenchPanel' &&
      staticAttributeValue(element, 'title') === '身份与策略',
  )
  const formGrids = (primaryPanel
    ? descendantElements(ast, primaryPanel)
    : allTemplateElements(ast)
  ).filter((element) => hasStaticClass(element, 'form-grid'))
  if (
    formGrids.length !== 1 ||
    !task8WrapperUsesOnly(formGrids[0], ['attribute:class']) ||
    !arraysEqual(
      (staticAttributeValue(formGrids[0], 'class')?.split(/\s+/).filter(Boolean) ?? []).sort(),
      ['form-grid', 'two'],
    )
  ) {
    return false
  }

  const protectedElements = new Set(formGrids)
  for (const [index, item] of allItems.entries()) {
    const allowedKeys = ['attribute:label', 'attribute:prop', 'attribute:class']
    if (!task8WrapperUsesOnly(item, allowedKeys)) return false
    const itemClasses = staticAttributeValue(item, 'class')?.split(/\s+/).filter(Boolean) ?? []
    const expectedClasses = index === 6 ? ['wide'] : []
    if (!arraysEqual(itemClasses.sort(), expectedClasses)) return false

    protectedElements.add(item)
    for (const descendant of descendantElements(ast, item)) protectedElements.add(descendant)
    let ancestor = entryByElement.get(item)?.parent
    while (ancestor) {
      protectedElements.add(ancestor)
      if (ancestor.tag.toLowerCase() === 'el-form') break
      ancestor = entryByElement.get(ancestor)?.parent
    }
  }

  const ownsVisibilityMutation = (element) =>
    (element?.props ?? []).some((property) => {
      if (property.type === 6) {
        return ['hidden', 'inert', 'aria-hidden'].includes(property.name)
      }
      if (property.type !== 7) return false
      if (['if', 'else-if', 'else', 'show'].includes(property.name)) return true
      return Boolean(
        property.name === 'bind' &&
          (!property.arg ||
            (property.arg.isStatic &&
              ['hidden', 'inert', 'aria-hidden'].includes(property.arg.content))),
      )
    })

  return ![...protectedElements].some(ownsVisibilityMutation)
}

function agentEditHeaderActions(ast) {
  const elements = allTemplateElements(ast)
  const legacy = elements.find((element) => hasStaticClass(element, 'header-actions'))
  if (legacy) return canonicalTemplateChildren(legacy)
  const pageHeader = elements.find((element) => element.tag === 'PageHeader')
  return canonicalTemplateChildren(templateSlot(ast, pageHeader, 'actions'))
}

function agentEditEnabledSwitch(ast) {
  return allTemplateElements(ast).find(
    (element) =>
      element.tag.toLowerCase() === 'el-switch' &&
      hasModelBinding(element, undefined, ['form', 'enabled']),
  )
}

function agentEditSecondaryButtons(ast) {
  const panel = allTemplateElements(ast).find(
    (element) =>
      ['WorkbenchPanel', 'section'].includes(element.tag) &&
      (staticAttributeValue(element, 'title') === '关联 Workflow' ||
        hasStaticClass(element, 'summary-panel')),
  )
  return panel
    ? descendantElements(ast, panel)
        .filter((element) => element.tag.toLowerCase() === 'el-button')
        .map((element) => canonicalTemplateNode(element))
    : []
}

function criticalAgentEditTemplateSemantics(ast) {
  const form = allTemplateElements(ast).find(
    (element) => element.tag.toLowerCase() === 'el-form',
  )
  return {
    formProps: canonicalElementProps(form),
    formItems: agentFormItems(ast).map((element) => canonicalTemplateNode(element)),
    headerActions: agentEditHeaderActions(ast),
    enabledSwitch: canonicalTemplateNode(agentEditEnabledSwitch(ast)),
    secondaryButtons: agentEditSecondaryButtons(ast),
  }
}

let cachedHeadAgentEdit

function headAgentEditSource(localFailures, label) {
  if (cachedHeadAgentEdit !== undefined) return cachedHeadAgentEdit
  try {
    cachedHeadAgentEdit = execFileSync(
      'git',
      ['show', 'HEAD:ai-admin-front/src/views/agent/AgentEdit.vue'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    localFailures.push(
      `${label} must compare behavior with HEAD AgentEdit: ${formatCompilerError(error)}`,
    )
    cachedHeadAgentEdit = null
  }
  return cachedHeadAgentEdit
}

function validateAgentEditHeadPreservation(
  sourceFile,
  ast,
  label,
  localFailures,
  structural,
) {
  const headSource = headAgentEditSource(localFailures, label)
  if (!headSource) return
  const headParsed = parse(headSource, { filename: 'HEAD:AgentEdit.vue' })
  if (headParsed.errors.length > 0) {
    localFailures.push(`${label} HEAD AgentEdit baseline must remain parseable`)
    return
  }
  const headSourceFile = parseTypeScript(
    headParsed.descriptor.scriptSetup?.content ?? '',
    'HEAD:AgentEdit.vue script setup',
    localFailures,
  )

  if (
    JSON.stringify(importedApiStatements(sourceFile)) !==
    JSON.stringify(importedApiStatements(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD AgentEdit API imports exactly`)
  }

  const headFunctions = runtimeFunctionSignatureMap(headSourceFile)
  const currentFunctions = runtimeFunctionSignatureMap(sourceFile)
  const changedFunctions = [...headFunctions.entries()]
    .filter(([name, signature]) => currentFunctions.get(name) !== signature)
    .map(([name]) => name)
  const addedFunctions = [...currentFunctions.keys()]
    .filter((name) => !headFunctions.has(name))
    .sort()
  if (changedFunctions.length > 0 || addedFunctions.length > 0) {
    localFailures.push(
      `${label} must preserve HEAD AgentEdit runtime functions exactly${
        changedFunctions.length ? `; changed or missing: ${changedFunctions.join(', ')}` : ''
      }${addedFunctions.length ? `; added: ${addedFunctions.join(', ')}` : ''}`,
    )
  }

  if (
    JSON.stringify([...variableInitializerMap(sourceFile).entries()]) !==
    JSON.stringify([...variableInitializerMap(headSourceFile).entries()])
  ) {
    localFailures.push(`${label} must preserve HEAD AgentEdit variable initializers exactly`)
  }

  if (
    JSON.stringify(topLevelRuntimeControlStatements(sourceFile)) !==
    JSON.stringify(topLevelRuntimeControlStatements(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD AgentEdit top-level runtime side-effect order`)
  }

  const headAst = templateAst(
    headParsed.descriptor.template?.content ?? '',
    'HEAD:AgentEdit.vue',
    localFailures,
  )
  const expected = criticalAgentEditTemplateSemantics(headAst)
  const actual = criticalAgentEditTemplateSemantics(ast)
  for (const [key, valid, message] of [
    ['formProps', structural.formShell, 'el-form ref/model/rules/label-width/loading contract'],
    ['formItems', structural.formFields, 'ordered form item attributes and subtrees'],
    ['headerActions', structural.headerActions, 'Copy/Workflow/Publish/Save action subtrees'],
    ['enabledSwitch', structural.primaryPanel, 'enabled switch attributes and model'],
    ['secondaryButtons', structural.secondaryPanel, 'edit-only Workflow action subtrees'],
  ]) {
    if (valid && JSON.stringify(actual[key]) !== JSON.stringify(expected[key])) {
      localFailures.push(`${label} must preserve HEAD AgentEdit ${message}`)
    }
  }
}

function agentEditHasPrivateVisualRecipe(descriptor, styles) {
  const forbiddenSelectors = [
    '.workbench-header',
    '.panel',
    '.summary-panel',
  ]
  const forbiddenProperties = new Set([
    'background',
    'background-color',
    'background-image',
    'box-shadow',
    '-webkit-box-shadow',
    'border',
    'border-color',
    'border-style',
    'border-width',
    'border-radius',
    'outline',
    'filter',
    'backdrop-filter',
    '-webkit-backdrop-filter',
    'opacity',
    'color',
    'font',
    'font-family',
    'font-size',
    'font-style',
    'font-weight',
    'line-height',
    'letter-spacing',
    'text-shadow',
  ])
  const ownsSourceEscape = descriptor.styles.some((style) =>
    /:deep\s*\(|:global\s*\(|::(?:before|after)\b/.test(style.content),
  )
  const scoped = '\\[data-v-[a-z0-9-]+\\]'
  const approvedSelectorPatterns = [
    new RegExp(`^\\.agent-workbench__main${scoped}$`, 'i'),
    new RegExp(`^\\.workflow-tool-select${scoped}$`, 'i'),
    new RegExp(`^\\.agent-workbench${scoped}$`, 'i'),
    new RegExp(`^\\.workbench-layout${scoped}$`, 'i'),
    new RegExp(`^\\.workbench-layout--with-aside${scoped}$`, 'i'),
    new RegExp(`^\\.form-grid${scoped}$`, 'i'),
    new RegExp(`^\\.form-grid\\.two${scoped}$`, 'i'),
    new RegExp(`^\\.wide${scoped}$`, 'i'),
    new RegExp(`^\\.summary-action${scoped}$`, 'i'),
    new RegExp(
      `^(?:\\.agent-workbench__aside${scoped}, \\.agent-workbench__main${scoped}, \\.form-grid${scoped}|\\.form-grid${scoped}, \\.agent-workbench__aside${scoped}, \\.agent-workbench__main${scoped})$`,
      'i',
    ),
    new RegExp(
      `^(?:\\.agent-workbench__aside${scoped}, \\.form-grid${scoped}|\\.form-grid${scoped}, \\.agent-workbench__aside${scoped})$`,
      'i',
    ),
  ]
  const selectorIsApproved = (selector) =>
    approvedSelectorPatterns.some((pattern) => pattern.test(selector))
  return Boolean(
    ownsSourceEscape ||
      compiledRules(styles).some(({ selector, declarations }) =>
        !selectorIsApproved(selector) ||
        selector.includes('.workbench-panel') ||
        selector.includes('.app-page-header') ||
        forbiddenSelectors.some((legacy) => selector.includes(legacy)) ||
        /\.el-[a-z0-9_-]+/i.test(selector) ||
        declarations.some(
          ({ property }) =>
            forbiddenProperties.has(property) ||
            property.startsWith('background-') ||
            /^border-(?!collapse\b|spacing\b)/.test(property),
        ),
      ),
  )
}

function agentEditOwnsRuntimePresentation(
  rootElement,
  allElements,
  pageHeaders,
  panels,
  statusTags,
  form,
  workbenchMain,
  asideEntries,
) {
  const ownsInlineStyle = (element) =>
    (element?.props ?? []).some(
      (property) =>
        (property.type === 6 && property.name === 'style') ||
        (property.type === 7 &&
          property.name === 'bind' &&
          property.arg?.isStatic &&
          property.arg.content === 'style'),
    )
  if (allElements.some(ownsInlineStyle)) return true

  const ownsPresentationAttribute = (element, allowClassBinding = false) =>
    (element?.props ?? []).some((property) => {
      if (property.type === 6) {
        return ['style', 'hidden', 'width', 'height'].includes(property.name)
      }
      return Boolean(
        property.type === 7 &&
          property.name === 'bind' &&
          (!property.arg ||
            (property.arg.isStatic &&
              (property.arg.content === 'style' ||
                (property.arg.content === 'class' && !allowClassBinding)))),
      )
    })

  if (ownsPresentationAttribute(rootElement)) return true

  const publicWrappers = [...pageHeaders, ...panels, ...statusTags]
  if (
    publicWrappers.some(
      (element) => ownsPresentationAttribute(element) || staticAttribute(element, 'class'),
    )
  ) {
    return true
  }

  if (
    form &&
    (ownsPresentationAttribute(form) || staticAttribute(form, 'class'))
  ) {
    return true
  }

  const mainClasses = staticAttributeValue(workbenchMain, 'class')?.split(/\s+/).filter(Boolean) ?? []
  if (
    ownsPresentationAttribute(workbenchMain, true) ||
    !arraysEqual(mainClasses, ['workbench-layout'])
  ) {
    return true
  }

  const aside = asideEntries[0]?.element
  const asideClasses = staticAttributeValue(aside, 'class')?.split(/\s+/).filter(Boolean) ?? []
  return Boolean(
    aside &&
      (ownsPresentationAttribute(aside) ||
        !arraysEqual(asideClasses, ['agent-workbench__aside']) ||
        directive(aside, 'bind', 'class')),
  )
}

function agentEditExactStyleOracle(styles) {
  if (styles.length !== 1) return false
  const records = []
  let valid = true

  const declarationRecord = (context, selector, body) => {
    const normalizedSelector = normalizeWhitespace(selector).replace(
      /\[data-v-[a-z0-9-]+\]/gi,
      '[scope]',
    )
    const declarations = compiledDeclarations(`{${body}}`)
      .map(({ property, value }) => `${property}:${value}`)
      .sort()
    records.push(`${context}|${normalizedSelector}|${declarations.join(';')}`)
  }

  const parseLevel = (css, context) => {
    let cursor = 0
    while (cursor < css.length) {
      while (/\s/.test(css[cursor] ?? '')) cursor += 1
      if (cursor >= css.length) break
      const open = css.indexOf('{', cursor)
      if (open < 0) {
        if (css.slice(cursor).trim()) valid = false
        break
      }
      const header = normalizeWhitespace(css.slice(cursor, open))
      let depth = 1
      let close = open + 1
      for (; close < css.length && depth > 0; close += 1) {
        if (css[close] === '{') depth += 1
        if (css[close] === '}') depth -= 1
      }
      if (depth !== 0) {
        valid = false
        return
      }
      const body = css.slice(open + 1, close - 1)
      if (header.startsWith('@media ')) {
        if (context !== 'root') {
          valid = false
          return
        }
        parseLevel(body, `media:${normalizeWhitespace(header.slice('@media '.length))}`)
      } else if (!header || header.startsWith('@') || body.includes('{')) {
        valid = false
        return
      } else {
        declarationRecord(context, header, body)
      }
      cursor = close
    }
  }

  parseLevel(styles[0], 'root')
  if (!valid) return false

  const expected = [
    'root|.workbench-layout[scope]|display:grid;gap:var(--section-gap);grid-template-columns:minmax(0, 1fr)',
    'root|.workbench-layout--with-aside[scope]|grid-template-columns:minmax(0, 1fr) 272px',
    'root|.agent-workbench__aside[scope], .agent-workbench__main[scope], .form-grid[scope]|min-width:0',
    'root|.agent-workbench__main[scope]|display:grid;gap:var(--section-gap)',
    'root|.form-grid[scope]|display:grid;gap:var(--section-gap)',
    'root|.form-grid.two[scope]|grid-template-columns:repeat(2, minmax(0, 1fr))',
    'root|.wide[scope]|grid-column:span 2',
    'root|.summary-action[scope]|margin-top:calc(var(--section-gap) / 2);width:100%',
    'root|.workflow-tool-select[scope]|width:100%',
    'media:(max-width: 1280px)|.workbench-layout--with-aside[scope]|grid-template-columns:minmax(0, 1fr)',
    'media:(max-width: 960px)|.form-grid.two[scope]|grid-template-columns:1fr',
    'media:(max-width: 960px)|.wide[scope]|grid-column:auto',
  ].sort()
  const legacyExpected = expected
    .filter(
      (record) =>
        !record.startsWith('root|.agent-workbench__main[scope]|') &&
        !record.startsWith('root|.workflow-tool-select[scope]|'),
    )
    .map((record) =>
      record ===
      'root|.agent-workbench__aside[scope], .agent-workbench__main[scope], .form-grid[scope]|min-width:0'
        ? 'root|.agent-workbench__aside[scope], .form-grid[scope]|min-width:0'
        : record,
    )
    .sort()
  const actual = records.sort()
  return arraysEqual(actual, expected) || arraysEqual(actual, legacyExpected)
}

function agentEditLayoutContract(styles) {
  const rules = compiledRules(styles)
  const css = styles.join('\n')
  const mediaBody = (maxWidth) => {
    const header = new RegExp(`@media\\s*\\(max-width:\\s*${maxWidth}\\)\\s*\\{`, 'i').exec(css)
    if (!header) return ''
    const bodyStart = header.index + header[0].length
    let depth = 1
    for (let index = bodyStart; index < css.length; index += 1) {
      if (css[index] === '{') depth += 1
      if (css[index] === '}') depth -= 1
      if (depth === 0) return css.slice(bodyStart, index)
    }
    return ''
  }
  const editMediaRules = compiledRules([mediaBody('1280px')])
  const formMediaRules = compiledRules([mediaBody('960px')])
  const rootRules = rules.filter(({ selector }) =>
    /^\.agent-workbench\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const baseLayoutRules = rules.filter(({ selector }) =>
    /^\.workbench-layout\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const splitRules = rules.filter(({ selector }) =>
    /^\.workbench-layout--with-aside\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const formGridRules = rules.filter(({ selector }) =>
    /^\.form-grid\.two\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const values = (matches, property) => matches.flatMap(({ declarations }) =>
    declarations.filter((declaration) => declaration.property === property).map(({ value }) => value),
  )
  const isReservationProperty = (property) =>
    property === 'gap' ||
    property === 'row-gap' ||
    property === 'column-gap' ||
    property === 'position' ||
    property === 'left' ||
    property === 'right' ||
    property === 'top' ||
    property === 'bottom' ||
    property === 'inset' ||
    property.startsWith('inset-') ||
    property === 'width' ||
    property === 'min-width' ||
    property === 'max-width' ||
    property === 'height' ||
    property === 'min-height' ||
    property === 'max-height' ||
    property === 'margin' ||
    property.startsWith('margin-') ||
    property === 'padding' ||
    property.startsWith('padding-') ||
    property === 'grid-template-columns' ||
    property === 'grid-template' ||
    property === 'grid' ||
    property.startsWith('grid-template-') ||
    property === 'grid-auto-columns' ||
    property === 'grid-auto-flow' ||
    property === 'grid-column' ||
    property === 'inline-size' ||
    property === 'min-inline-size' ||
    property === 'max-inline-size' ||
    property === 'block-size' ||
    property === 'min-block-size' ||
    property === 'max-block-size' ||
    property === 'flex-basis' ||
    property === 'transform' ||
    property === 'translate'
  const reservationSignature = (matches) =>
    matches.flatMap(({ declarations }) =>
      declarations
        .filter(({ property }) => isReservationProperty(property))
        .map(({ property, value }) => `${property}:${value}`),
    ).sort()
  const rootDeclarations = rootRules.flatMap(({ declarations }) => declarations)
  const editMediaSplitRules = editMediaRules.filter(({ selector }) =>
    /^\.workbench-layout--with-aside\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const formMediaGridRules = formMediaRules.filter(({ selector }) =>
    /^\.form-grid\.two\[data-v-[a-z0-9-]+\]$/i.test(selector),
  )
  const rootReservationContract = rootRules.length === 0
  const baseReservationContract = arraysEqual(
    reservationSignature(baseLayoutRules),
    ['gap:var(--section-gap)', 'grid-template-columns:minmax(0, 1fr)'].sort(),
  )
  const splitReservationContract = arraysEqual(
    reservationSignature(splitRules),
    [
      'grid-template-columns:minmax(0, 1fr)',
      'grid-template-columns:minmax(0, 1fr) 272px',
    ].sort(),
  )
  const ownsUnexpectedRootOrLayoutReservation = rules.some(({ selector, declarations }) => {
    if (
      /\.el-[a-z0-9_-]+/i.test(selector) ||
      /::(?:before|after)\b/.test(selector) ||
      selector.includes('.workbench-panel') ||
      selector.includes('.app-page-header')
    ) {
      return false
    }
    const targetsRoot = /\.agent-workbench(?=\[|[.#:\s>,+~]|$)/.test(selector)
    const targetsLayout = /\.workbench-layout(?=\[|[.#:\s>,+~]|$)/.test(selector)
    const approvedSelector =
      /^\.workbench-layout\[data-v-[a-z0-9-]+\]$/i.test(selector) ||
      /^\.workbench-layout--with-aside\[data-v-[a-z0-9-]+\]$/i.test(selector)
    return Boolean(
      (targetsRoot || targetsLayout) &&
        !approvedSelector &&
        declarations.some(({ property }) => isReservationProperty(property)),
    )
  })
  return {
    rootRhythm: rootRules.length === 0,
    baseSingleColumn:
      values(baseLayoutRules, 'display').includes('grid') &&
      arraysEqual(values(baseLayoutRules, 'grid-template-columns'), ['minmax(0, 1fr)']),
    editSplit:
      values(splitRules, 'grid-template-columns').includes('minmax(0, 1fr) 272px'),
    editCollapse:
      values(editMediaSplitRules, 'grid-template-columns').includes('minmax(0, 1fr)'),
    formTwoColumn:
      values(formGridRules, 'grid-template-columns').includes('repeat(2, minmax(0, 1fr))'),
    formCollapse:
      values(formMediaGridRules, 'grid-template-columns').includes('1fr'),
    closedWorldSpacing:
      rootReservationContract &&
      baseReservationContract &&
      splitReservationContract &&
      !ownsUnexpectedRootOrLayoutReservation,
    noRootVisualRecipe: rootRules.length === 0 && !rootDeclarations.some(
      ({ property }) =>
        property.startsWith('background') ||
        property.startsWith('border') ||
        property === 'box-shadow' ||
        property === 'border-radius',
    ),
  }
}

async function validateAgentSupervisorWorkbenchConsumer(source, label) {
  const localFailures = await validateCommonSfc(source, label)
  const required = [
    ['Agent Supervisor 工作台', 'must identify the page as the Agent Supervisor workbench'],
    ['title="Agent 身份与接入"', 'must separate stable Agent identity from runtime configuration'],
    ['title="Supervisor 运行配置"', 'must expose the versioned Supervisor runtime configuration'],
    ['await copyAgentConfigToDraft(', 'must support copying immutable config snapshots into a draft'],
    ['workflowTools', 'must manage Workflow-as-Tool entries as editable records'],
    ['class="workflow-tool-table"', 'must render the editable Workflow tool catalog'],
    ['Tool Name', 'must expose stable Supervisor tool names'],
    ['permissionKey', 'must expose Workflow tool permission keys'],
    ['配置版本', 'must expose Supervisor config version management'],
    ['<AppDrawer', 'must use the shared AppDrawer for config version management'],
    ['openDebug', 'must expose Agent Supervisor debugging'],
  ]
  for (const [needle, message] of required) {
    if (!source.includes(needle)) localFailures.push(`${label} ${message}`)
  }
  for (const [pattern, message] of [
    [/PAGE_ENTRY/, 'must not retain the retired PAGE_ENTRY semantic'],
    [/agentDefinitionId/, 'must use agentId as the only Agent execution identity'],
    [/selectedWorkflowIds\.value\.map/, 'must not reduce Workflow tools to a static id-only binding'],
  ]) {
    if (pattern.test(source)) localFailures.push(`${label} ${message}`)
  }
  return localFailures
}

async function validateAgentEditConsumer(source, label, options = {}) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const allElements = allTemplateElements(ast)
  const entries = templateElementEntries(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (
    !agentEditComponentImports.every(([name, moduleName]) =>
      importsDefault(sourceFile, name, moduleName),
    )
  ) {
    localFailures.push(`${label} must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and StatusTag`)
  }

  const pageHeaders = elements.filter((element) => element.tag === 'PageHeader')
  const panels = elements.filter((element) => element.tag === 'WorkbenchPanel')
  const statusTags = elements.filter((element) => element.tag === 'StatusTag')
  const expectedPanelCount = options.expectedPanelCount ?? 2
  const expectedStatusTagCount = options.expectedStatusTagCount ?? 2
  const expectedPanelLabel = expectedPanelCount === 2 ? 'two' : String(expectedPanelCount)
  const expectedStatusTagLabel =
    expectedStatusTagCount === 2 ? 'two' : String(expectedStatusTagCount)
  const liveWrapperCountsAreExact =
    pageHeaders.length === 1 &&
    panels.length === expectedPanelCount &&
    statusTags.length === expectedStatusTagCount
  if (!liveWrapperCountsAreExact) {
    localFailures.push(
      `${label} must render exactly one PageHeader, ${expectedPanelLabel} WorkbenchPanels, and ${expectedStatusTagLabel} StatusTags`,
    )
  }
  const wrapperTags = new Set(['PageHeader', 'WorkbenchPanel', 'StatusTag'])
  const allWrapperEntries = entries.filter((entry) => wrapperTags.has(entry.element.tag))
  const allPageHeaders = allElements.filter((element) => element.tag === 'PageHeader')
  const allPanels = allElements.filter((element) => element.tag === 'WorkbenchPanel')
  const allStatusTags = allElements.filter((element) => element.tag === 'StatusTag')
  const allWrapperTopologyIsExact = Boolean(
    allPageHeaders.length === 1 &&
      allPanels.length === expectedPanelCount &&
      allStatusTags.length === expectedStatusTagCount &&
      allWrapperEntries.every((entry) => entry.reachable),
  )
  if (liveWrapperCountsAreExact && !allWrapperTopologyIsExact) {
    localFailures.push(
      `${label} must not retain hidden, duplicate, or presentation-mutated Agent workbench wrappers`,
    )
  }

  if (!workbenchPageRootContract(rootElement, 'comfortable', true)) {
    localFailures.push(`${label} page root must use comfortable density`)
  }

  const pageHeader = pageHeaders[0]
  const titleExpression = directiveExpression(pageHeader, 'bind', 'title')
  const pageHeaderContract = Boolean(
    pageHeader &&
      staticAttributeValue(pageHeader, 'variant') === 'standard' &&
      staticAttributeValue(pageHeader, 'domain') === 'agent' &&
      hasStaticBooleanAttribute(pageHeader, 'show-back') &&
      staticAttributeValue(pageHeader, 'eyebrow') === 'Agent / Supervisor' &&
      staticAttributeValue(pageHeader, 'density') === 'comfortable' &&
      task8WrapperUsesOnly(pageHeader, [
        'attribute:variant',
        'attribute:domain',
        'directive:bind:title:',
        'attribute:eyebrow',
        'attribute:density',
        'attribute:show-back',
        'directive:on:back:',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      canonicalTypeScriptNode(titleExpression) ===
        "isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}`" &&
      isRouterPushPath(directiveExpression(pageHeader, 'on', 'back'), '/agent'),
  )
  if (pageHeaders.length === 1 && !pageHeaderContract) {
    localFailures.push(
      `${label} PageHeader must preserve show-back, back handler, title, eyebrow, and comfortable density`,
    )
  }

  const tagsSlot = templateSlot(ast, pageHeader, 'tags')
  const headerTags = tagsSlot
    ? descendantElements(ast, tagsSlot).filter((element) => element.tag === 'StatusTag')
    : []
  const kindTag = headerTags.find((tag) =>
    expressionMatchesSource(
      directiveExpression(tag, 'bind', 'label'),
      'agentKindLabel(form.agentKind)',
    ),
  )
  const enabledTag = headerTags.find((tag) =>
    expressionMatchesSource(
      directiveExpression(tag, 'bind', 'label'),
      "form.enabled !== false ? '启用' : '停用'",
    ),
  )
  const statusContract = Boolean(
    headerTags.length === 2 &&
      kindTag &&
      staticAttributeValue(kindTag, 'tone') === 'neutral' &&
      task8WrapperUsesOnly(kindTag, [
        'directive:bind:label:',
        'attribute:tone',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      enabledTag &&
      task8WrapperUsesOnly(enabledTag, [
        'directive:bind:label:',
        'directive:bind:tone:',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      expressionMatchesSource(
        directiveExpression(enabledTag, 'bind', 'tone'),
        "form.enabled !== false ? 'success' : 'info'",
      ),
  )
  if (pageHeaders.length === 1 && headerTags.length === 2 && !statusContract) {
    localFailures.push(
      `${label} PageHeader tags must map Agent kind and enabled state through semantic StatusTag`,
    )
  }

  const actionsSlot = templateSlot(ast, pageHeader, 'actions')
  const headerButtons = actionsSlot
    ? descendantElements(ast, actionsSlot).filter(
        (element) => element.tag.toLowerCase() === 'el-button',
      )
    : []
  const copyButton = headerButtons.find((button) =>
    isIdentifierValue(directiveExpression(button, 'on', 'click'), 'copyAgentIdentity'),
  )
  const workflowButton = headerButtons.find((button) =>
    isIdentifierValue(directiveExpression(button, 'on', 'click'), 'openWorkflows'),
  )
  const publishButton = headerButtons.find((button) =>
    isIdentifierValue(directiveExpression(button, 'on', 'click'), 'handlePublish'),
  )
  const saveButton = headerButtons.find((button) =>
    isIdentifierValue(directiveExpression(button, 'on', 'click'), 'handleSave'),
  )
  const noCondition = (button) =>
    !directive(button, 'if', undefined) && !directive(button, 'show', undefined)
  const headerActionsContract = Boolean(
    actionsSlot &&
      headerButtons.length === 4 &&
      copyButton &&
      noCondition(copyButton) &&
      task8WrapperUsesOnly(copyButton, [
        'directive:bind:icon:',
        'directive:bind:disabled:',
        'directive:on:click:',
      ]) &&
      isIdentifierValue(directiveExpression(copyButton, 'bind', 'icon'), 'DocumentCopy') &&
      expressionMatchesSource(
        directiveExpression(copyButton, 'bind', 'disabled'),
        '!canCopyAgentIdentity',
      ) &&
      templateText(copyButton) === '复制标识' &&
      workflowButton &&
      task8WrapperUsesOnly(workflowButton, [
        'directive:if::',
        'directive:bind:icon:',
        'directive:on:click:',
      ]) &&
      agentEditCondition(workflowButton) === '!isNew' &&
      isIdentifierValue(directiveExpression(workflowButton, 'bind', 'icon'), 'Share') &&
      templateText(workflowButton) === 'Workflow 列表' &&
      publishButton &&
      task8WrapperUsesOnly(publishButton, [
        'directive:if::',
        'attribute:type',
        'attribute:plain',
        'directive:bind:loading:',
        'directive:on:click:',
      ]) &&
      agentEditCondition(publishButton) === '!isNew' &&
      staticAttributeValue(publishButton, 'type') === 'success' &&
      isIdentifierValue(directiveExpression(publishButton, 'bind', 'loading'), 'publishing') &&
      templateText(publishButton) === '发布 Supervisor 配置' &&
      saveButton &&
      noCondition(saveButton) &&
      task8WrapperUsesOnly(saveButton, [
        'attribute:type',
        'directive:bind:loading:',
        'directive:on:click:',
      ]) &&
      staticAttributeValue(saveButton, 'type') === 'primary' &&
      isIdentifierValue(directiveExpression(saveButton, 'bind', 'loading'), 'saving') &&
      templateText(saveButton) === '保存',
  )
  if (pageHeaders.length === 1 && !headerActionsContract) {
    localFailures.push(
      `${label} PageHeader actions must preserve Copy, Workflow list, Supervisor publish, and Save controls exactly`,
    )
  }

  const forms = allElements.filter((element) => element.tag.toLowerCase() === 'el-form')
  const form = forms[0]
  const formShellContract = Boolean(
    forms.length === 1 &&
      staticAttributeValue(form, 'ref') === 'formRef' &&
      isIdentifierValue(directiveExpression(form, 'bind', 'model'), 'form') &&
      isIdentifierValue(directiveExpression(form, 'bind', 'rules'), 'rules') &&
      staticAttributeValue(form, 'label-width') === '108px' &&
      task8WrapperUsesOnly(form, [
        'attribute:ref',
        'directive:bind:model:',
        'directive:bind:rules:',
        'attribute:label-width',
        'directive:loading::',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      isIdentifierValue(directiveExpression(form, 'loading', undefined), 'pageLoading'),
  )
  if (!formShellContract) {
    localFailures.push(`${label} must preserve the exact existing el-form shell contract`)
  }

  const primaryPanel = panels.find(
    (panel) => staticAttributeValue(panel, 'title') === '身份与策略',
  )
  const primaryActions = templateSlot(ast, primaryPanel, 'actions')
  const enabledSwitch = primaryActions
    ? descendantElements(ast, primaryActions).find(
        (element) => element.tag.toLowerCase() === 'el-switch',
      )
    : undefined
  const primaryPanelContract = Boolean(
    primaryPanel &&
      task8WrapperUsesOnly(primaryPanel, [
        'attribute:title',
        'attribute:description',
        'attribute:density',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      staticAttributeValue(primaryPanel, 'density') === 'comfortable' &&
      staticAttributeValue(primaryPanel, 'description') ===
        '配置 Agent 入口身份、可见性与默认策略；图编排请在 Workflow Studio 完成。' &&
      primaryActions &&
      enabledSwitch &&
      hasModelBinding(enabledSwitch, undefined, ['form', 'enabled']) &&
      staticAttributeValue(enabledSwitch, 'active-text') === '启用' &&
      staticAttributeValue(enabledSwitch, 'inactive-text') === '停用',
  )
  if (panels.length === expectedPanelCount && !primaryPanelContract) {
    localFailures.push(
      `${label} primary WorkbenchPanel must own identity/strategy heading and enabled switch action`,
    )
  }

  const secondaryPanel = panels.find(
    (panel) => staticAttributeValue(panel, 'title') === 'Workflow 工具目录',
  )
  const asideEntries = entries.filter(
    (entry) => entry.element.tag.toLowerCase() === 'aside',
  )
  const asideEntry = asideEntries[0]
  const secondaryButtons = secondaryPanel
    ? descendantElements(ast, secondaryPanel).filter(
        (element) => element.tag.toLowerCase() === 'el-button',
      )
    : []
  const secondaryPanelContract = Boolean(
    secondaryPanel &&
      task8WrapperUsesOnly(secondaryPanel, [
        'attribute:title',
        'attribute:description',
        'attribute:density',
        ...agentRuntimePresentationPropKeys,
      ]) &&
      staticAttributeValue(secondaryPanel, 'density') === 'comfortable' &&
      staticAttributeValue(secondaryPanel, 'description') ===
        '当前选择会保存到 Agent 配置草稿，发布后成为 Supervisor 的动态工具白名单。' &&
      asideEntries.length === 1 &&
      asideEntry &&
      agentEditCondition(asideEntry.element) === '!isNew' &&
      descendantElements(ast, asideEntry.element).includes(secondaryPanel) &&
      secondaryButtons.length === 1 &&
      secondaryButtons.some(
        (button) =>
          isIdentifierValue(directiveExpression(button, 'on', 'click'), 'openWorkflows') &&
          templateText(button) === '打开 Workflow 列表',
      ),
  )
  if (panels.length === expectedPanelCount && !secondaryPanelContract) {
    localFailures.push(
      `${label} secondary WorkbenchPanel must be edit-only and preserve the Workflow catalog action`,
    )
  }

  const workbenchMain = allElements.find(
    (element) => element.tag.toLowerCase() === 'main' && hasStaticClass(element, 'workbench-layout'),
  )
  const mainClassBinding = normalizeWhitespace(
    directive(workbenchMain, 'bind', 'class')?.exp?.content ?? '',
  )
  const conditionalAsideContract = Boolean(
    workbenchMain &&
      mainClassBinding === "{ 'workbench-layout--with-aside': !isNew }" &&
      asideEntries.length === 1 &&
      agentEditCondition(asideEntry?.element) === '!isNew',
  )
  const layout = agentEditLayoutContract(styles)
  const privateVisualRecipe = agentEditHasPrivateVisualRecipe(descriptor, styles)
  const exactStyleOracle = agentEditExactStyleOracle(styles)
  const hasCommonStyleFailure = localFailures.some((failure) =>
    failure.startsWith(`${label} compiled style`),
  )
  if (
    secondaryPanelContract &&
    (!conditionalAsideContract || !layout.baseSingleColumn || !layout.editSplit)
  ) {
    localFailures.push(
      `${label} new mode must not render or reserve an unconditional second column`,
    )
  }
  if (
    layout.rootRhythm &&
    layout.baseSingleColumn &&
    layout.editSplit &&
    layout.editCollapse &&
    !layout.closedWorldSpacing
  ) {
    localFailures.push(
      `${label} page and base layout must not reserve side space outside the edit-only grid modifier`,
    )
  }

  const formFieldsContract = agentFormFieldsMatchContract(ast)
  if (!formFieldsContract) {
    localFailures.push(`${label} must preserve the complete ordered Agent form field contract`)
  }
  const formVisibilityContract = agentEditFormVisibilityContract(ast, entries)
  if (formShellContract && primaryPanelContract && !formVisibilityContract) {
    localFailures.push(
      `${label} form field layout containers and ancestry must remain visible and unconditional`,
    )
  }
  if (!layout.rootRhythm) {
    localFailures.push(`${label} page root must own token-based flex-column section rhythm`)
  }
  if (!layout.editCollapse || !layout.formTwoColumn || !layout.formCollapse) {
    localFailures.push(
      `${label} must preserve the responsive two-column form grid and mobile collapse`,
    )
  }
  if (
    layout.rootRhythm &&
    layout.baseSingleColumn &&
    layout.editSplit &&
    layout.editCollapse &&
    layout.formTwoColumn &&
    layout.formCollapse &&
    layout.closedWorldSpacing &&
    layout.noRootVisualRecipe &&
    !privateVisualRecipe &&
    !hasCommonStyleFailure &&
    !exactStyleOracle
  ) {
    localFailures.push(
      `${label} must keep the exact approved AgentEdit page style rule, declaration, and media oracle`,
    )
  }

  const legacyClasses = new Set(['workbench-header', 'panel', 'summary-panel'])
  const ownsLegacyTemplateClass = allElements.some((element) =>
    (staticAttributeValue(element, 'class')?.split(/\s+/) ?? []).some((className) =>
      legacyClasses.has(className),
    ),
  )
  const ownsRawStateTag = allElements.some(
    (element) => element.tag.toLowerCase() === 'el-tag',
  )
  if (
    agentEditOwnsRuntimePresentation(
      rootElement,
      allElements,
      allPageHeaders,
      allPanels,
      allStatusTags,
      form,
      workbenchMain,
      asideEntries,
    )
  ) {
    localFailures.push(
      `${label} must not own inline, runtime, or private presentation attributes on workbench wrappers`,
    )
  }
  if (
    ownsLegacyTemplateClass ||
    ownsRawStateTag ||
    !layout.noRootVisualRecipe ||
    privateVisualRecipe
  ) {
    localFailures.push(
      `${label} must remove legacy/private Agent page surfaces and typography recipes`,
    )
  }

  if (!options.skipHeadPreservation) {
    validateAgentEditHeadPreservation(sourceFile, ast, label, localFailures, {
      formShell: formShellContract,
      formFields: formFieldsContract,
      headerActions: headerActionsContract,
      primaryPanel: primaryPanelContract,
      secondaryPanel: secondaryPanelContract,
    })
  }
  return localFailures
}

const mcpOnboardingComponentImports = [
  ['PageHeader', '@/components/common/PageHeader.vue'],
  ['WorkbenchPage', '@/components/common/WorkbenchPage.vue'],
  ['WorkbenchPanel', '@/components/common/WorkbenchPanel.vue'],
  ['CodeSnippetBlock', '@/components/common/CodeSnippetBlock.vue'],
]

const mcpOnboardingStepTitles = [
  '① 服务端基础信息',
  '② Cursor 接入',
  '③ Claude Desktop 接入',
  '④ Dify / OpenClaw / 通用 HTTP MCP',
  '⑤ curl 自检',
]

const mcpOnboardingSnippetBindings = [
  'cursorExample',
  'claudeExample',
  'genericExample',
  'curlExample',
]

let cachedHeadMcpOnboarding

function headMcpOnboardingSource(localFailures, label) {
  if (cachedHeadMcpOnboarding !== undefined) return cachedHeadMcpOnboarding
  try {
    cachedHeadMcpOnboarding = execFileSync(
      'git',
      ['show', 'HEAD:ai-admin-front/src/views/mcp/McpOnboarding.vue'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    localFailures.push(
      `${label} must compare generated MCP strings with HEAD: ${formatCompilerError(error)}`,
    )
    cachedHeadMcpOnboarding = null
  }
  return cachedHeadMcpOnboarding
}

function rawVariableInitializers(sourceFile, names) {
  return names.map((name) => {
    const declaration = findVariableDeclaration(sourceFile, name)
    const raw = declaration?.initializer?.getText(sourceFile)
    return [name, raw == null ? null : raw.replace(/\r\n?/g, '\n')]
  })
}

function mcpNonPresentationImports(sourceFile) {
  const presentationModules = new Set(mcpOnboardingComponentImports.map(([, moduleName]) => moduleName))
  return sourceFile.statements
    .filter(ts.isImportDeclaration)
    .filter(
      (statement) =>
        ts.isStringLiteral(statement.moduleSpecifier) &&
        !presentationModules.has(statement.moduleSpecifier.text),
    )
    .map((statement) => canonicalTypeScriptNode(statement))
}

function mcpBusinessSnapshot(ast) {
  const elements = allTemplateElements(ast)
  const infoAlert = elements.find(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'info',
  )
  const warningAlert = elements.find(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'warning',
  )
  const descriptions = elements.find(
    (element) => element.tag.toLowerCase() === 'el-descriptions',
  )
  const prose = elements.filter((element) => element.tag.toLowerCase() === 'p')
  const options = {
    ignoreProp: (element, property) =>
      (property.type === 6 && property.name === 'style') ||
      (element.tag.toLowerCase() === 'code' && property.type === 6 && property.name === 'class'),
  }
  return {
    infoAlert: canonicalTemplateNode(infoAlert, options),
    descriptions: canonicalTemplateNode(descriptions, options),
    warningAlert: canonicalTemplateNode(warningAlert, options),
    prose: prose.map((element) => canonicalTemplateNode(element, options)),
  }
}

function mcpOnboardingBusinessContract(ast) {
  const elements = allTemplateElements(ast)
  const infoAlerts = elements.filter(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'info',
  )
  const warningAlerts = elements.filter(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'warning',
  )
  const infoAlert = infoAlerts[0]
  const warningAlert = warningAlerts[0]
  const infoContract = Boolean(
    infoAlerts.length === 1 &&
      task8WrapperUsesOnly(infoAlert, [
        'attribute:type',
        'attribute:show-icon',
        'directive:bind:closable:',
        'attribute:title',
        'attribute:description',
      ]) &&
      hasStaticBooleanAttribute(infoAlert, 'show-icon') &&
      canonicalTypeScriptNode(directiveExpression(infoAlert, 'bind', 'closable')) === 'false' &&
      staticAttributeValue(infoAlert, 'title') ===
        '一句话理解：把本仓的 Tool / 粗粒度能力通过 MCP 协议暴露，Cursor / Claude Desktop / Dify 等可一行配置接入' &&
      staticAttributeValue(infoAlert, 'description') ===
        '先在『MCP 暴露白名单』勾选要暴露的 Tool；再在『MCP Client』生成 API Key；最后把 Key 填到客户端配置即可。',
  )

  const descriptions = elements.filter(
    (element) => element.tag.toLowerCase() === 'el-descriptions',
  )
  const descriptionItems = descriptions.length === 1
    ? descendantElements(ast, descriptions[0]).filter(
        (element) => element.tag.toLowerCase() === 'el-descriptions-item',
      )
    : []
  const itemButtons = (item) =>
    descendantElements(ast, item).filter((element) => element.tag.toLowerCase() === 'el-button')
  const descriptionContract = Boolean(
    descriptions.length === 1 &&
      task8WrapperUsesOnly(descriptions[0], [
        'directive:bind:column:',
        'attribute:border',
        'attribute:size',
      ]) &&
      isNumberValue(directiveExpression(descriptions[0], 'bind', 'column'), 1) &&
      hasStaticBooleanAttribute(descriptions[0], 'border') &&
      staticAttributeValue(descriptions[0], 'size') === 'default' &&
      descriptionItems.length === 5 &&
      arraysEqual(
        descriptionItems.map((item) => staticAttributeValue(item, 'label')),
        ['协议', '协议版本', 'HTTP 端点', 'Manifest', '鉴权'],
      ) &&
      descriptionItems.every((item) => task8WrapperUsesOnly(item, ['attribute:label'])) &&
      templateText(descriptionItems[0]) === 'MCP (JSON-RPC 2.0)' &&
      templateText(descriptionItems[1]) === '2024-11-05' &&
      hasInterpolationProperty(descriptionItems[2], ['jsonrpcUrl']) &&
      itemButtons(descriptionItems[2]).length === 1 &&
      expressionMatchesSource(
        directiveExpression(itemButtons(descriptionItems[2])[0], 'on', 'click'),
        'copy(jsonrpcUrl)',
      ) &&
      hasInterpolationProperty(descriptionItems[3], ['manifestUrl']) &&
      itemButtons(descriptionItems[3]).length === 1 &&
      expressionMatchesSource(
        directiveExpression(itemButtons(descriptionItems[3])[0], 'on', 'click'),
        'copy(manifestUrl)',
      ) &&
      templateText(descriptionItems[4]) === 'Authorization: Bearer <API Key>',
  )

  const warningLinks = warningAlert
    ? descendantElements(ast, warningAlert).filter((element) => element.tag.toLowerCase() === 'a')
    : []
  const warningContract = Boolean(
    warningAlerts.length === 1 &&
      task8WrapperUsesOnly(warningAlert, [
        'attribute:type',
        'directive:bind:closable:',
        'attribute:show-icon',
      ]) &&
      hasStaticBooleanAttribute(warningAlert, 'show-icon') &&
      canonicalTypeScriptNode(directiveExpression(warningAlert, 'bind', 'closable')) === 'false' &&
      warningLinks.length === 1 &&
      staticAttributeValue(warningLinks[0], 'href') ===
        'https://github.com/modelcontextprotocol/servers' &&
      staticAttributeValue(warningLinks[0], 'target') === '_blank' &&
      templateText(warningLinks[0]) === 'mcp-proxy' &&
      templateText(warningAlert) ===
        'Claude Desktop 目前仅支持 stdio MCP；本仓暂不暴露 stdio，可借助 mcp-proxy 把 HTTP 端点桥接到 stdio。',
  )
  const prose = elements.filter((element) => element.tag.toLowerCase() === 'p')
  const proseContract = arraysEqual(
    prose.map((element) => templateText(element)),
    [
      '编辑 ~/.cursor/mcp.json ：',
      '编辑 claude_desktop_config.json ：',
      '直接配置远端 MCP 服务地址：',
    ],
  )
  return infoContract && descriptionContract && warningContract && proseContract
}

function validateMcpOnboardingHeadPreservation(
  sourceFile,
  ast,
  label,
  localFailures,
  structural,
) {
  const headSource = headMcpOnboardingSource(localFailures, label)
  if (!headSource) return
  const headParsed = parse(headSource, { filename: 'HEAD:McpOnboarding.vue' })
  if (headParsed.errors.length > 0) {
    localFailures.push(`${label} HEAD MCP onboarding baseline must remain parseable`)
    return
  }
  const headSourceFile = parseTypeScript(
    headParsed.descriptor.scriptSetup?.content ?? '',
    'HEAD:McpOnboarding.vue script setup',
    localFailures,
  )
  const initializerNames = [
    'origin',
    'jsonrpcUrl',
    'manifestUrl',
    'cursorExample',
    'claudeExample',
    'genericExample',
    'curlExample',
  ]
  const runtimeVariablesValid =
    JSON.stringify([...variableInitializerMap(sourceFile).entries()]) ===
      JSON.stringify([...variableInitializerMap(headSourceFile).entries()]) &&
    JSON.stringify(rawVariableInitializers(sourceFile, initializerNames)) ===
      JSON.stringify(rawVariableInitializers(headSourceFile, initializerNames))
  if (!runtimeVariablesValid) {
    localFailures.push(
      `${label} must preserve the exact MCP runtime variable set and generated initializer source`,
    )
  }
  const currentCopy = functionDeclaration(sourceFile, 'copy')
  const headCopy = functionDeclaration(headSourceFile, 'copy')
  const runtimeFunctionsValid = !(
    JSON.stringify([...runtimeFunctionSignatureMap(sourceFile).entries()]) !==
      JSON.stringify([...runtimeFunctionSignatureMap(headSourceFile).entries()]) ||
    !currentCopy ||
    !headCopy ||
    currentCopy.getText(sourceFile).replace(/\r\n?/g, '\n') !==
      headCopy.getText(headSourceFile).replace(/\r\n?/g, '\n')
  )
  if (!runtimeFunctionsValid) {
    localFailures.push(`${label} must preserve HEAD MCP copy(text: string) function exactly`)
  }

  if (
    structural.runtimeOrderValid &&
    runtimeVariablesValid &&
    runtimeFunctionsValid &&
    !arraysEqual(
      mcpRuntimeDeclarationSequence(sourceFile),
      mcpRuntimeDeclarationSequence(headSourceFile),
    )
  ) {
    localFailures.push(
      `${label} must preserve HEAD MCP top-level runtime declaration order after approved imports`,
    )
  }

  const nonPresentationImportsValid =
    JSON.stringify(mcpNonPresentationImports(sourceFile)) ===
    JSON.stringify(mcpNonPresentationImports(headSourceFile))
  const topLevelSideEffectsValid =
    JSON.stringify(topLevelRuntimeControlStatements(sourceFile)) ===
    JSON.stringify(topLevelRuntimeControlStatements(headSourceFile))
  if (structural.importsValid && (!nonPresentationImportsValid || !topLevelSideEffectsValid)) {
    localFailures.push(
      `${label} must preserve HEAD MCP non-presentation imports and top-level side-effect order`,
    )
  }

  if (
    structural.importsValid &&
    structural.importsBeforeRuntime &&
    structural.presentationSequenceValid &&
    nonPresentationImportsValid
  ) {
    const currentImportModules = sourceFile.statements
      .filter(ts.isImportDeclaration)
      .map((statement) =>
        ts.isStringLiteral(statement.moduleSpecifier)
          ? statement.moduleSpecifier.text
          : '<dynamic>',
      )
    const presentationModules = new Set(
      mcpOnboardingComponentImports.map(([, moduleName]) => moduleName),
    )
    const expectedImportModules = [
      ...headSourceFile.statements
        .filter(ts.isImportDeclaration)
        .map((statement) =>
          ts.isStringLiteral(statement.moduleSpecifier)
            ? statement.moduleSpecifier.text
            : '<dynamic>',
        )
        .filter((moduleName) => !presentationModules.has(moduleName)),
      ...presentationModules,
    ]
    if (!arraysEqual(currentImportModules, expectedImportModules)) {
      localFailures.push(
        `${label} must preserve exact MCP presentation import sequence after HEAD imports`,
      )
    }
  }

  if (structural.businessValid) {
    const headAst = templateAst(
      headParsed.descriptor.template?.content ?? '',
      'HEAD:McpOnboarding.vue',
      localFailures,
    )
    if (
      JSON.stringify(mcpBusinessSnapshot(ast)) !==
      JSON.stringify(mcpBusinessSnapshot(headAst))
    ) {
      localFailures.push(
        `${label} must preserve HEAD MCP service-description and alert subtrees exactly`,
      )
    }
  }
}

function mcpDirectElements(element) {
  return (element?.children ?? []).filter((child) => child.type === 1)
}

function mcpOnboardingExactStyleOracle(styles) {
  if (styles.length !== 1) return false
  const records = []
  let valid = true

  const recordRule = (context, selector, body) => {
    const normalizedSelector = normalizeWhitespace(selector).replace(
      /\[data-v-[a-z0-9-]+\]/gi,
      '[scope]',
    )
    const declarations = compiledDeclarations(`{${body}}`)
      .map(({ property, value }) => `${property}:${value}`)
      .sort()
    records.push(`${context}|${normalizedSelector}|${declarations.join(';')}`)
  }

  const parseLevel = (css, context) => {
    let cursor = 0
    while (cursor < css.length) {
      while (/\s/.test(css[cursor] ?? '')) cursor += 1
      if (cursor >= css.length) break
      const open = css.indexOf('{', cursor)
      if (open < 0) {
        if (css.slice(cursor).trim()) valid = false
        break
      }
      const header = normalizeWhitespace(css.slice(cursor, open))
      let depth = 1
      let close = open + 1
      for (; close < css.length && depth > 0; close += 1) {
        if (css[close] === '{') depth += 1
        if (css[close] === '}') depth -= 1
      }
      if (depth !== 0) {
        valid = false
        return
      }
      const body = css.slice(open + 1, close - 1)
      if (header.startsWith('@media ')) {
        if (context !== 'root') {
          valid = false
          return
        }
        parseLevel(body, `media:${normalizeWhitespace(header.slice('@media '.length))}`)
      } else if (!header || header.startsWith('@') || body.includes('{')) {
        valid = false
        return
      } else {
        recordRule(context, header, body)
      }
      cursor = close
    }
  }

  parseLevel(styles[0], 'root')
  if (!valid) return false
  const expected = [
    'root|.mcp-onboarding__columns[scope]|display:grid;gap:var(--section-gap);grid-template-columns:repeat(2, minmax(0, 1fr));min-width:0',
    'root|.mcp-onboarding__column[scope]|display:flex;flex-direction:column;gap:var(--section-gap);min-width:0',
    'root|.mcp-onboarding__inline-code[scope]|overflow-wrap:anywhere;word-break:break-word',
    'media:(max-width: 1080px)|.mcp-onboarding__columns[scope]|grid-template-columns:minmax(0, 1fr)',
  ].sort()
  return arraysEqual(records.sort(), expected)
}

function mcpOnboardingOwnsPrivateStyle(descriptor, styles) {
  const sourceOwnsPrivateRecipe = descriptor.styles.some((style) =>
    /\[data-theme|:global\s*\(|:deep\s*\(|(?:^|[\s,{])(?:pre|code)\s*\{|#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/im.test(
      style.content,
    ),
  )
  const compiledOwnsPrivateRecipe = compiledRules(styles).some(
    ({ selector, declarations }) =>
      /\.(?:app-page-header|workbench-panel|code-shell|code-panel|code-toolbar)\b/.test(selector) ||
      /\.el-[a-z0-9_-]+/i.test(selector) ||
      declarations.some(
        ({ property }) =>
          property.startsWith('background') ||
          property.startsWith('border') ||
          property === 'box-shadow' ||
          property === 'color' ||
          property.startsWith('font') ||
          property === 'line-height' ||
          property === 'text-shadow' ||
          property === 'filter' ||
          property.includes('backdrop-filter'),
      ),
  )
  return sourceOwnsPrivateRecipe || compiledOwnsPrivateRecipe
}

function mcpPresentationImportsExact(sourceFile) {
  return mcpOnboardingComponentImports.every(([localName, moduleName]) => {
    const matches = sourceFile.statements.filter(
      (statement) =>
        ts.isImportDeclaration(statement) &&
        ts.isStringLiteral(statement.moduleSpecifier) &&
        statement.moduleSpecifier.text === moduleName,
    )
    const clause = matches[0]?.importClause
    return Boolean(
      matches.length === 1 &&
        clause &&
        !clause.isTypeOnly &&
        clause.name?.text === localName &&
        !clause.namedBindings,
    )
  })
}

function mcpRuntimeDeclarationSequence(sourceFile) {
  return sourceFile.statements.flatMap((statement) => {
    if (ts.isVariableStatement(statement)) {
      return [
        `variables:${statement.declarationList.declarations
          .map((declaration) => declaration.name.getText(sourceFile))
          .join(',')}`,
      ]
    }
    if (ts.isFunctionDeclaration(statement)) {
      return [`function:${statement.name?.text ?? '<anonymous>'}`]
    }
    return []
  })
}

function mcpCopyFollowsAllRuntimeVariables(sourceFile) {
  const runtimeStatements = sourceFile.statements.filter(
    (statement) => ts.isVariableStatement(statement) || ts.isFunctionDeclaration(statement),
  )
  const copyIndex = runtimeStatements.findIndex(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === 'copy',
  )
  const lastVariableIndex = runtimeStatements.reduce(
    (lastIndex, statement, index) => (ts.isVariableStatement(statement) ? index : lastIndex),
    -1,
  )
  return copyIndex >= 0 && lastVariableIndex >= 0 && copyIndex > lastVariableIndex
}

function mcpRequiredContentAncestryContract(ast, rootElement, columns, columnElements, panels, snippets) {
  const elements = allTemplateElements(ast)
  const descriptions = elements.filter(
    (element) => element.tag.toLowerCase() === 'el-descriptions',
  )
  const prose = elements.filter((element) => element.tag.toLowerCase() === 'p')
  const inlineCodes = elements.filter((element) => element.tag.toLowerCase() === 'code')
  const expectedClasses = new Map([
    [rootElement, []],
    [columns[0], ['mcp-onboarding__columns']],
    ...columnElements.map((element) => [element, ['mcp-onboarding__column']]),
    ...inlineCodes.map((element) => [element, ['mcp-onboarding__inline-code']]),
  ])
  const classTopology = elements.every((element) => {
    const dynamicClass = directive(element, 'bind', 'class')
    if (dynamicClass) return false
    const actual = staticAttributeValue(element, 'class')?.split(/\s+/).filter(Boolean).sort() ?? []
    const expected = expectedClasses.get(element)?.slice().sort() ?? []
    return arraysEqual(actual, expected)
  })
  if (
    !classTopology ||
    descriptions.length !== 1 ||
    prose.length !== 3 ||
    panels.length !== 5 ||
    snippets.length !== 4
  ) {
    return false
  }

  const panelChildren = panels.map((panel) =>
    mcpDirectElements(panel).filter(
      (element) =>
        !(
          element.tag.toLowerCase() === 'el-alert' &&
          staticAttributeValue(element, 'type') === 'warning'
        ),
    ),
  )
  return Boolean(
    arraysEqual(panelChildren[0], [descriptions[0]]) &&
      panelChildren[1][0] === prose[0] &&
      panelChildren[1][1] === snippets[0] &&
      panelChildren[2][0] === prose[1] &&
      panelChildren[2][1] === snippets[1] &&
      panelChildren[3][0] === prose[2] &&
      panelChildren[3][1] === snippets[2] &&
      panelChildren[4][0] === snippets[3]
  )
}

function mcpCopyControlTopology(allElements, snippets) {
  const eventSignatures = allElements.flatMap((element) =>
    (element.props ?? [])
      .filter((property) => property.type === 7 && property.name === 'on')
      .map((property) => {
        const argument = property.arg?.isStatic ? property.arg.content : '<dynamic>'
        const modifiers = (property.modifiers ?? [])
          .map((modifier) => modifier.content)
          .sort()
          .join('.')
        const expression = canonicalTypeScriptNode(
          parseTemplateExpression(property.exp?.content),
        )
        return `${element.tag}|${argument}|${modifiers}|${expression ?? '<missing>'}`
      }),
  )
  const interactiveButtons = allElements.filter((element) =>
    ['button', 'el-button'].includes(element.tag.toLowerCase()),
  )
  const anchors = allElements.filter((element) => element.tag.toLowerCase() === 'a')
  const expectedEvents = [
    'el-button|click||copy(jsonrpcUrl)',
    'el-button|click||copy(manifestUrl)',
    ...Array.from({ length: 4 }, () => 'CodeSnippetBlock|copy||copy'),
  ].sort()
  return Boolean(
    arraysEqual(eventSignatures.sort(), expectedEvents) &&
      interactiveButtons.length === 2 &&
      interactiveButtons.every((element) => element.tag.toLowerCase() === 'el-button') &&
      anchors.length === 1 &&
      snippets.length === 4
  )
}

function mcpClaudeWarningAncestryContract(ast, panels, snippets) {
  const elements = allTemplateElements(ast)
  const prose = elements.filter((element) => element.tag.toLowerCase() === 'p')
  const warnings = elements.filter(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'warning',
  )
  const warning = warnings[0]
  const links = warning
    ? descendantElements(ast, warning).filter((element) => element.tag.toLowerCase() === 'a')
    : []
  return Boolean(
    panels.length === 5 &&
      snippets.length === 4 &&
      prose.length === 3 &&
      warnings.length === 1 &&
      arraysEqual(mcpDirectElements(panels[2]), [prose[1], snippets[1], warning]) &&
      arraysEqual(mcpDirectElements(panels[3]), [prose[2], snippets[2]]) &&
      links.length === 1 &&
      descendantElements(ast, warning).includes(links[0])
  )
}

function mcpExactTemplatePropTopology(
  ast,
  rootElement,
  pageHeaders,
  columns,
  columnElements,
  panels,
  snippets,
) {
  const elements = allTemplateElements(ast)
  const infoAlerts = elements.filter(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'info',
  )
  const warningAlerts = elements.filter(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'warning',
  )
  const descriptions = elements.filter(
    (element) => element.tag.toLowerCase() === 'el-descriptions',
  )
  const descriptionItems = elements.filter(
    (element) => element.tag.toLowerCase() === 'el-descriptions-item',
  )
  const prose = elements.filter((element) => element.tag.toLowerCase() === 'p')
  const inlineCodes = elements.filter((element) => element.tag.toLowerCase() === 'code')
  const endpointButtons = elements.filter((element) => element.tag.toLowerCase() === 'el-button')
  const anchors = elements.filter((element) => element.tag.toLowerCase() === 'a')
  return Boolean(
    workbenchPageRootContract(rootElement, 'spacious') &&
      pageHeaders.length === 1 &&
      task8WrapperUsesOnly(pageHeaders[0], [
        'attribute:variant',
        'attribute:domain',
        'attribute:eyebrow',
        'attribute:title',
        'attribute:description',
        'attribute:density',
      ]) &&
      columns.length === 1 &&
      task8WrapperUsesOnly(columns[0], ['attribute:class']) &&
      columnElements.length === 2 &&
      columnElements.every((element) => task8WrapperUsesOnly(element, ['attribute:class'])) &&
      panels.length === 5 &&
      panels.every((element) =>
        task8WrapperUsesOnly(element, ['attribute:title', 'attribute:density']),
      ) &&
      snippets.length === 4 &&
      snippets.every((element) =>
        task8WrapperUsesOnly(element, [
          'attribute:title',
          'directive:bind:code:',
          'directive:on:copy:',
        ]),
      ) &&
      infoAlerts.length === 1 &&
      task8WrapperUsesOnly(infoAlerts[0], [
        'attribute:type',
        'attribute:show-icon',
        'directive:bind:closable:',
        'attribute:title',
        'attribute:description',
      ]) &&
      descriptions.length === 1 &&
      task8WrapperUsesOnly(descriptions[0], [
        'directive:bind:column:',
        'attribute:border',
        'attribute:size',
      ]) &&
      descriptionItems.length === 5 &&
      descriptionItems.every((element) =>
        task8WrapperUsesOnly(element, ['attribute:label']),
      ) &&
      prose.length === 3 &&
      prose.every((element) => task8WrapperUsesOnly(element, [])) &&
      inlineCodes.length === 5 &&
      inlineCodes.every((element) =>
        task8WrapperUsesOnly(element, ['attribute:class']),
      ) &&
      endpointButtons.length === 2 &&
      endpointButtons.every((element) =>
        task8WrapperUsesOnly(element, [
          'attribute:size',
          'directive:bind:icon:',
          'attribute:link',
          'directive:on:click:',
        ]),
      ) &&
      warningAlerts.length === 1 &&
      task8WrapperUsesOnly(warningAlerts[0], [
        'attribute:type',
        'directive:bind:closable:',
        'attribute:show-icon',
      ]) &&
      anchors.length === 1 &&
      task8WrapperUsesOnly(anchors[0], ['attribute:href', 'attribute:target'])
  )
}

function mcpStyleBlockContract(descriptor) {
  if (descriptor.styles.length !== 1) return false
  const style = descriptor.styles[0]
  return Boolean(
    style.scoped === true &&
      style.lang === 'scss' &&
      !style.src &&
      !style.module &&
      arraysEqual(Object.keys(style.attrs ?? {}).sort(), ['lang', 'scoped']) &&
      style.attrs.lang === 'scss' &&
      style.attrs.scoped === true
  )
}

function mcpRawStyleOpeningTagContract(source) {
  const matches = [...source.matchAll(/<style\b([^>]*)>/gi)]
  if (matches.length !== 1) return false
  let ast
  try {
    ast = baseParse(`<style${matches[0][1]}></style>`)
  } catch {
    return false
  }
  const styleElement = ast.children?.filter((node) => node.type === 1)?.[0]
  if (!styleElement || styleElement.props.length !== 2) return false
  const scoped = styleElement.props.find(
    (property) => property.type === 6 && property.name === 'scoped',
  )
  const lang = styleElement.props.find(
    (property) => property.type === 6 && property.name === 'lang',
  )
  return Boolean(scoped && !scoped.value && lang?.value?.content === 'scss')
}

function mcpImportsPrecedeRuntime(sourceFile) {
  let runtimeSeen = false
  for (const statement of sourceFile.statements) {
    if (ts.isImportDeclaration(statement)) {
      if (runtimeSeen) return false
      continue
    }
    runtimeSeen = true
  }
  return true
}

let cachedMcpApprovedTemplateTopology

function mcpApprovedTemplateTopologyContract(ast) {
  if (cachedMcpApprovedTemplateTopology === undefined) {
    const baselineSfc = parse(syntheticMcpOnboardingConsumer, {
      filename: 'synthetic MCP onboarding approved topology',
    })
    const baselineAst = baseParse(baselineSfc.descriptor.template?.content ?? '')
    cachedMcpApprovedTemplateTopology = canonicalTemplateNode(
      rootTemplateElement(baselineAst),
    )
  }
  return (
    JSON.stringify(canonicalTemplateNode(rootTemplateElement(ast))) ===
    JSON.stringify(cachedMcpApprovedTemplateTopology)
  )
}

function mcpPresentationImportSequenceContract(sourceFile) {
  const imports = sourceFile.statements.filter(ts.isImportDeclaration)
  const presentationModules = mcpOnboardingComponentImports.map(([, moduleName]) => moduleName)
  const orderedPresentationModules = imports
    .map((statement) =>
      ts.isStringLiteral(statement.moduleSpecifier) ? statement.moduleSpecifier.text : '<dynamic>',
    )
    .filter((moduleName) => presentationModules.includes(moduleName))
  return arraysEqual(orderedPresentationModules, presentationModules)
}

async function validateMcpOnboardingConsumer(source, label, options = {}) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const allElements = allTemplateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  const componentImportsValid = mcpPresentationImportsExact(sourceFile)
  if (!componentImportsValid) {
    localFailures.push(
      `${label} must directly import PageHeader, WorkbenchPage, WorkbenchPanel, and CodeSnippetBlock`,
    )
  }

  const importsBeforeRuntime = mcpImportsPrecedeRuntime(sourceFile)
  if (!importsBeforeRuntime) {
    localFailures.push(`${label} must place every MCP import before the first runtime statement`)
  }
  const presentationImportSequenceContract = mcpPresentationImportSequenceContract(sourceFile)
  if (componentImportsValid && importsBeforeRuntime && !presentationImportSequenceContract) {
    localFailures.push(
      `${label} must preserve exact MCP presentation import sequence after HEAD imports`,
    )
  }

  const runtimeOrderContract = mcpCopyFollowsAllRuntimeVariables(sourceFile)
  if (!runtimeOrderContract) {
    localFailures.push(
      `${label} must preserve HEAD MCP top-level runtime declaration order after approved imports`,
    )
  }

  const styleBlockContract =
    mcpStyleBlockContract(descriptor) && mcpRawStyleOpeningTagContract(source)
  if (!styleBlockContract) {
    localFailures.push(
      `${label} must use exactly one scoped SCSS style block with no media, module, or src attributes`,
    )
  }

  const pageHeaders = elements.filter((element) => element.tag === 'PageHeader')
  const allPageHeaders = allElements.filter((element) => element.tag === 'PageHeader')
  const pageHeader = pageHeaders[0]
  const rootChildren = mcpDirectElements(rootElement)
  const infoAlert = rootChildren[1]
  const pageShellContract = Boolean(
    rootElement &&
      workbenchPageRootContract(rootElement, 'spacious') &&
      pageHeaders.length === 1 &&
      allPageHeaders.length === 1 &&
      rootChildren.length === 3 &&
      rootChildren[0] === pageHeader &&
      pageHeader &&
      staticAttributeValue(pageHeader, 'variant') === 'workbench' &&
      staticAttributeValue(pageHeader, 'domain') === 'platform' &&
      staticAttributeValue(pageHeader, 'eyebrow') === 'MCP Onboarding' &&
      staticAttributeValue(pageHeader, 'title') === 'MCP 接入向导' &&
      staticAttributeValue(pageHeader, 'description') ===
        '从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。' &&
      staticAttributeValue(pageHeader, 'density') === 'spacious' &&
      task8WrapperUsesOnly(pageHeader, [
        'attribute:variant',
        'attribute:domain',
        'attribute:eyebrow',
        'attribute:title',
        'attribute:description',
        'attribute:density',
      ]) &&
      infoAlert?.tag.toLowerCase() === 'el-alert' &&
      staticAttributeValue(infoAlert, 'type') === 'info',
  )
  if (!pageShellContract) {
    localFailures.push(
      `${label} must render the exact spacious MCP page root, PageHeader, and immediate info alert`,
    )
  }

  const columns = allElements.filter(
    (element) => element.tag === 'div' && hasStaticClass(element, 'mcp-onboarding__columns'),
  )
  const columnElements = allElements.filter(
    (element) => element.tag === 'div' && hasStaticClass(element, 'mcp-onboarding__column'),
  )
  const panels = elements.filter((element) => element.tag === 'WorkbenchPanel')
  const allPanels = allElements.filter((element) => element.tag === 'WorkbenchPanel')
  const columnChildren = columns.length === 1 ? mcpDirectElements(columns[0]) : []
  const leftPanels = columnElements.length === 2 ? mcpDirectElements(columnElements[0]) : []
  const rightPanels = columnElements.length === 2 ? mcpDirectElements(columnElements[1]) : []
  const panelContract = Boolean(
    panels.length === 5 &&
      allPanels.length === 5 &&
      columns.length === 1 &&
      rootChildren[2] === columns[0] &&
      columnElements.length === 2 &&
      columnChildren.length === 2 &&
      columnChildren[0] === columnElements[0] &&
      columnChildren[1] === columnElements[1] &&
      arraysEqual(leftPanels, panels.slice(0, 2)) &&
      arraysEqual(rightPanels, panels.slice(2)) &&
      arraysEqual(
        panels.map((panel) => staticAttributeValue(panel, 'title')),
        mcpOnboardingStepTitles,
      ) &&
      panels.every(
        (panel) =>
          staticAttributeValue(panel, 'density') === 'spacious' &&
          task8WrapperUsesOnly(panel, ['attribute:title', 'attribute:density']),
      ),
  )
  if (!panelContract) {
    localFailures.push(
      `${label} must render five ordered spacious panels with steps 1–2 left and 3–5 right`,
    )
  }

  const snippets = elements.filter((element) => element.tag === 'CodeSnippetBlock')
  const allSnippets = allElements.filter((element) => element.tag === 'CodeSnippetBlock')
  const snippetContract = Boolean(
    snippets.length === 4 &&
      allSnippets.length === 4 &&
      arraysEqual(
        snippets.map((snippet) =>
          canonicalTypeScriptNode(directiveExpression(snippet, 'bind', 'code')),
        ),
        mcpOnboardingSnippetBindings,
      ) &&
      snippets.every(
        (snippet) =>
          task8WrapperUsesOnly(snippet, [
            'attribute:title',
            'directive:bind:code:',
            'directive:on:copy:',
          ]) &&
          isIdentifierValue(directiveExpression(snippet, 'on', 'copy'), 'copy') &&
          !directive(snippet, 'bind', 'highlighted-code'),
      ) &&
      panels.slice(1).every(
        (panel, index) =>
          descendantElements(ast, panel).filter(
            (element) => element.tag === 'CodeSnippetBlock',
          ).length === 1 &&
          descendantElements(ast, panel).includes(snippets[index]),
      ),
  )
  if (!snippetContract) {
    localFailures.push(
      `${label} must render four one-to-one CodeSnippetBlocks with exact bindings and no page-owned snippet copy controls`,
    )
  }

  const businessContract = mcpOnboardingBusinessContract(ast)
  if (!businessContract) {
    localFailures.push(
      `${label} must preserve exact MCP alerts, warning link, prose, descriptions, and copy controls`,
    )
  }

  const entries = templateElementEntries(ast)
  const ownsVisibilityMutation = (element) =>
    (element?.props ?? []).some((property) => {
      if (property.type === 6) {
        return ['hidden', 'inert', 'aria-hidden'].includes(property.name)
      }
      if (property.type !== 7) return false
      if (['if', 'else-if', 'else', 'show'].includes(property.name)) return true
      return Boolean(
        property.name === 'bind' &&
          (!property.arg ||
            (property.arg.isStatic &&
              ['hidden', 'inert', 'aria-hidden'].includes(property.arg.content))),
      )
    })
  const visibilityContract = !entries.some(
    (entry) => !entry.reachable || ownsVisibilityMutation(entry.element),
  )
  if (!visibilityContract) {
    localFailures.push(
      `${label} must keep all MCP onboarding wrappers and business content visible and unconditional`,
    )
  }

  const ownsInlinePresentation = allElements.some((element) =>
    (element.props ?? []).some(
      (property) =>
        (property.type === 6 && property.name === 'style') ||
        (property.type === 7 &&
          property.name === 'bind' &&
          (!property.arg ||
            (property.arg.isStatic && ['style', 'class'].includes(property.arg.content)))),
    ),
  )
  if (ownsInlinePresentation) {
    localFailures.push(
      `${label} must not own inline presentation or runtime public-component overrides`,
    )
  }

  const ownsRawSurface = allElements.some((element) =>
    ['el-card', 'pre'].includes(element.tag.toLowerCase()),
  )
  if (ownsRawSurface) {
    localFailures.push(`${label} must not render raw el-card or pre code surfaces`)
  }

  const requiredContentAncestryContract = mcpRequiredContentAncestryContract(
    ast,
    rootElement,
    columns,
    columnElements,
    panels,
    snippets,
  )
  if (
    pageShellContract &&
    panelContract &&
    snippetContract &&
    businessContract &&
    visibilityContract &&
    !ownsInlinePresentation &&
    !ownsRawSurface &&
    !requiredContentAncestryContract
  ) {
    localFailures.push(
      `${label} must keep required MCP business content and snippets in the exact visible wrapper ancestry`,
    )
  }

  const copyControlContract = mcpCopyControlTopology(allElements, snippets)
  if (businessContract && snippetContract && !copyControlContract) {
    localFailures.push(
      `${label} must preserve exact MCP template event topology and forbid extra interactive copy controls`,
    )
  }

  const warningAncestryContract = mcpClaudeWarningAncestryContract(ast, panels, snippets)
  if (businessContract && panelContract && snippetContract && !warningAncestryContract) {
    localFailures.push(
      `${label} Claude warning and link must remain in panel 3 immediately after its CodeSnippetBlock`,
    )
  }

  const exactTemplatePropTopology = mcpExactTemplatePropTopology(
    ast,
    rootElement,
    pageHeaders,
    columns,
    columnElements,
    panels,
    snippets,
  )
  if (
    pageShellContract &&
    panelContract &&
    snippetContract &&
    businessContract &&
    visibilityContract &&
    !ownsInlinePresentation &&
    !ownsRawSurface &&
    requiredContentAncestryContract &&
    copyControlContract &&
    warningAncestryContract &&
    !exactTemplatePropTopology
  ) {
    localFailures.push(
      `${label} must keep exact MCP root, column, panel, snippet, and business-wrapper attributes and directives`,
    )
  }

  const approvedTemplateTopology = mcpApprovedTemplateTopologyContract(ast)
  if (
    pageShellContract &&
    panelContract &&
    snippetContract &&
    businessContract &&
    visibilityContract &&
    !ownsInlinePresentation &&
    !ownsRawSurface &&
    requiredContentAncestryContract &&
    copyControlContract &&
    warningAncestryContract &&
    exactTemplatePropTopology &&
    !approvedTemplateTopology
  ) {
    localFailures.push(
      `${label} must preserve exact MCP root, column, and five-panel direct-child topology`,
    )
  }

  const hasCommonStyleFailure = localFailures.some((failure) =>
    failure.startsWith(`${label} compiled style`),
  )
  if (mcpOnboardingOwnsPrivateStyle(descriptor, styles) && !hasCommonStyleFailure) {
    localFailures.push(
      `${label} must not own local theme, code-surface, typography, or public-component recipes`,
    )
  }
  if (localFailures.length === 0 && !mcpOnboardingExactStyleOracle(styles)) {
    localFailures.push(
      `${label} must keep the exact approved MCP layout selector, declaration, and media oracle`,
    )
  }

  if (!options.skipHeadPreservation) {
    validateMcpOnboardingHeadPreservation(sourceFile, ast, label, localFailures, {
      importsValid: componentImportsValid,
      importsBeforeRuntime,
      presentationSequenceValid: presentationImportSequenceContract,
      runtimeOrderValid: runtimeOrderContract,
      businessValid:
        businessContract &&
        visibilityContract &&
        !ownsInlinePresentation &&
        requiredContentAncestryContract &&
        copyControlContract &&
        warningAncestryContract &&
        exactTemplatePropTopology &&
        approvedTemplateTopology,
    })
  }
  return localFailures
}

const syntheticMcpOnboardingConsumer = `<template>
  <WorkbenchPage density="spacious">
    <PageHeader
      variant="workbench"
      domain="platform"
      eyebrow="MCP Onboarding"
      title="MCP 接入向导"
      description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"
      density="spacious"
    />
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="一句话理解：把本仓的 Tool / 粗粒度能力通过 MCP 协议暴露，Cursor / Claude Desktop / Dify 等可一行配置接入"
      description="先在『MCP 暴露白名单』勾选要暴露的 Tool；再在『MCP Client』生成 API Key；最后把 Key 填到客户端配置即可。"
    />
    <div class="mcp-onboarding__columns">
      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="① 服务端基础信息" density="spacious">
          <el-descriptions :column="1" border size="default">
            <el-descriptions-item label="协议">MCP (JSON-RPC 2.0)</el-descriptions-item>
            <el-descriptions-item label="协议版本">2024-11-05</el-descriptions-item>
            <el-descriptions-item label="HTTP 端点">
              <code class="mcp-onboarding__inline-code">{{ jsonrpcUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(jsonrpcUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Manifest">
              <code class="mcp-onboarding__inline-code">{{ manifestUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(manifestUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="鉴权">
              <code class="mcp-onboarding__inline-code">Authorization: Bearer &lt;API Key&gt;</code>
            </el-descriptions-item>
          </el-descriptions>
        </WorkbenchPanel>
        <WorkbenchPanel title="② Cursor 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>
          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />
        </WorkbenchPanel>
      </div>
      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="③ Claude Desktop 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">claude_desktop_config.json</code>：</p>
          <CodeSnippetBlock title="claude_desktop_config.json" :code="claudeExample" @copy="copy" />
          <el-alert type="warning" :closable="false" show-icon>
            Claude Desktop 目前仅支持 stdio MCP；本仓暂不暴露 stdio，可借助
            <a href="https://github.com/modelcontextprotocol/servers" target="_blank">mcp-proxy</a>
            把 HTTP 端点桥接到 stdio。
          </el-alert>
        </WorkbenchPanel>
        <WorkbenchPanel title="④ Dify / OpenClaw / 通用 HTTP MCP" density="spacious">
          <p>直接配置远端 MCP 服务地址：</p>
          <CodeSnippetBlock title="HTTP MCP" :code="genericExample" @copy="copy" />
        </WorkbenchPanel>
        <WorkbenchPanel title="⑤ curl 自检" density="spacious">
          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />
        </WorkbenchPanel>
      </div>
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
const jsonrpcUrl = ''
const manifestUrl = ''
const cursorExample = ''
const claudeExample = ''
const genericExample = ''
const curlExample = ''
const DocumentCopy = undefined
function copy(_text: string) {}
</script>

<style scoped lang="scss">
.mcp-onboarding__columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__column {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__inline-code {
  overflow-wrap: anywhere;
  word-break: break-word;
}

@media (max-width: 1080px) {
  .mcp-onboarding__columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
`

const toolRetrievalComponentImports = [
  ['PageHeader', '@/components/common/PageHeader.vue'],
  ['WorkbenchPage', '@/components/common/WorkbenchPage.vue'],
  ['WorkbenchPanel', '@/components/common/WorkbenchPanel.vue'],
  ['DataTableShell', '@/components/common/DataTableShell.vue'],
  ['StatusTag', '@/components/common/StatusTag.vue'],
  ['AppDialog', '@/components/common/AppDialog.vue'],
]

let cachedHeadToolRetrievalTest

function headToolRetrievalTestSource(localFailures, label) {
  if (cachedHeadToolRetrievalTest !== undefined) return cachedHeadToolRetrievalTest
  try {
    cachedHeadToolRetrievalTest = execFileSync(
      'git',
      ['show', 'HEAD:ai-admin-front/src/views/tool/ToolRetrievalTest.vue'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    localFailures.push(
      `${label} must compare Tool retrieval behavior with HEAD: ${formatCompilerError(error)}`,
    )
    cachedHeadToolRetrievalTest = null
  }
  return cachedHeadToolRetrievalTest
}

function toolRetrievalPresentationImportsExact(sourceFile) {
  return toolRetrievalComponentImports.every(([localName, moduleName]) => {
    const matches = sourceFile.statements.filter(
      (statement) =>
        ts.isImportDeclaration(statement) &&
        ts.isStringLiteral(statement.moduleSpecifier) &&
        statement.moduleSpecifier.text === moduleName,
    )
    const clause = matches[0]?.importClause
    return Boolean(
      matches.length === 1 &&
        clause &&
        !clause.isTypeOnly &&
        clause.name?.text === localName &&
        !clause.namedBindings,
    )
  })
}

function toolRetrievalNonPresentationImports(sourceFile) {
  const presentationModules = new Set(
    [
      ...toolRetrievalComponentImports.map(([, moduleName]) => moduleName),
      '@element-plus/icons-vue',
    ],
  )
  return sourceFile.statements
    .filter(ts.isImportDeclaration)
    .filter(
      (statement) =>
        ts.isStringLiteral(statement.moduleSpecifier) &&
        !presentationModules.has(statement.moduleSpecifier.text),
    )
    .map((statement) => canonicalTypeScriptNode(statement))
}

function toolRetrievalDirectElements(element) {
  return (element?.children ?? []).filter((child) => child.type === 1)
}

function toolRetrievalOwnsConditionalShell(element) {
  return (element?.props ?? []).some((property) => {
    if (property.type === 6) {
      return ['hidden', 'inert', 'aria-hidden'].includes(property.name)
    }
    if (property.type !== 7) return false
    if (['if', 'else-if', 'else', 'show'].includes(property.name)) return true
    return Boolean(
      property.name === 'bind' &&
        (!property.arg ||
          (property.arg.isStatic &&
            ['hidden', 'inert', 'aria-hidden', 'style', 'class'].includes(property.arg.content))),
    )
  })
}

function toolRetrievalOwnsPrivateStyle(descriptor, styles) {
  const sourceOwnsPrivateRecipe = descriptor.styles.some((style) =>
    /\[data-(?:theme|brand)|:global\s*\(|:deep\s*\(|#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/im.test(
      style.content,
    ),
  )
  const compiledOwnsPrivateRecipe = compiledRules(styles).some(
    ({ selector, declarations }) =>
      /\.(?:app-page-header|workbench-panel|data-table-shell|status-tag|app-dialog)\b/.test(
        selector,
      ) ||
      /\.el-[a-z0-9_-]+/i.test(selector) ||
      declarations.some(
        ({ property }) =>
          property.startsWith('background') ||
          property.startsWith('border') ||
          property === 'box-shadow' ||
          property === 'filter' ||
          property.includes('backdrop-filter'),
      ),
  )
  return sourceOwnsPrivateRecipe || compiledOwnsPrivateRecipe
}

function canonicalToolRetrievalBusinessNode(node) {
  if (!node || node.type === 3) return undefined
  if (node.type === 2) {
    const text = normalizeWhitespace(node.content)
    return text ? { kind: 'text', text } : undefined
  }
  if (node.type === 5) {
    return {
      kind: 'interpolation',
      expression: canonicalTypeScriptNode(parseTemplateExpression(node.content?.content)),
    }
  }
  if (node.type === 1) {
    const tag = node.tag.toLowerCase()
    if (tag === 'el-tag' || node.tag === 'StatusTag') {
      const scoreExpression =
        canonicalTypeScriptNode(directiveExpression(node, 'bind', 'type')) ??
        canonicalTypeScriptNode(directiveExpression(node, 'bind', 'tone')) ??
        ''
      if (scoreExpression.includes('scoreTag(row.score)')) {
        return { kind: 'authorized-tool-retrieval-score' }
      }
    }
    const props = (node.props ?? [])
      .filter((property) => {
        if (property.type === 6) {
          if (['style', 'class'].includes(property.name)) return false
          if (tag === 'el-table' && property.name === 'empty-text') return false
        }
        if (property.type === 7 && tag === 'el-table' && property.name === 'else') return false
        return true
      })
      .map((property) => canonicalTemplateProp(property, undefined, node))
      .filter(Boolean)
      .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
    const children = (node.children ?? [])
      .map(canonicalToolRetrievalBusinessNode)
      .filter(Boolean)
    return { kind: 'element', tag: node.tag, props, children }
  }
  const children = (node.children ?? [])
    .map(canonicalToolRetrievalBusinessNode)
    .filter(Boolean)
  return children.length > 0 ? { kind: `node-${node.type}`, children } : undefined
}

function toolRetrievalBusinessSnapshot(ast) {
  const elements = allTemplateElements(ast)
  const searchForm = elements.find(
    (element) => element.tag.toLowerCase() === 'el-form' && hasStaticClass(element, 'search-form'),
  )
  const resultTable = elements.find(
    (element) =>
      element.tag.toLowerCase() === 'el-table' &&
      isIdentifierValue(directiveExpression(element, 'bind', 'data'), 'candidates'),
  )
  const descriptions = elements.find(
    (element) => element.tag.toLowerCase() === 'el-descriptions',
  )
  const progress = elements.find((element) => element.tag.toLowerCase() === 'el-progress')
  const failureAlert = elements.find(
    (element) =>
      element.tag.toLowerCase() === 'el-alert' && staticAttributeValue(element, 'type') === 'error',
  )
  const dialog = elements.find((element) =>
    ['el-dialog', 'AppDialog'].includes(element.tag),
  )
  const dialogChildren = (dialog?.children ?? []).map(canonicalToolRetrievalBusinessNode).filter(Boolean)
  return {
    searchForm: canonicalToolRetrievalBusinessNode(searchForm),
    resultTable: canonicalToolRetrievalBusinessNode(resultTable),
    descriptions: canonicalToolRetrievalBusinessNode(descriptions),
    progress: canonicalToolRetrievalBusinessNode(progress),
    failureAlert: canonicalToolRetrievalBusinessNode(failureAlert),
    dialogChildren,
  }
}

let cachedToolRetrievalApprovedTemplate

function toolRetrievalApprovedTemplateContract(ast) {
  if (cachedToolRetrievalApprovedTemplate === undefined) {
    const baselineSfc = parse(syntheticToolRetrievalTestConsumer, {
      filename: 'synthetic Tool retrieval approved topology',
    })
    const baselineAst = baseParse(baselineSfc.descriptor.template?.content ?? '')
    cachedToolRetrievalApprovedTemplate = canonicalTemplateNode(
      rootTemplateElement(baselineAst),
    )
  }
  return (
    JSON.stringify(canonicalTemplateNode(rootTemplateElement(ast))) ===
    JSON.stringify(cachedToolRetrievalApprovedTemplate)
  )
}

let cachedToolRetrievalApprovedSections

function toolRetrievalApprovedSections() {
  if (cachedToolRetrievalApprovedSections !== undefined) {
    return cachedToolRetrievalApprovedSections
  }
  const baselineSfc = parse(syntheticToolRetrievalTestConsumer, {
    filename: 'synthetic Tool retrieval approved sections',
  })
  const ast = baseParse(baselineSfc.descriptor.template?.content ?? '')
  const elements = templateElements(ast)
  const panels = elements.filter((element) => element.tag === 'WorkbenchPanel')
  const searchForm = elements.find(
    (element) => element.tag.toLowerCase() === 'el-form' && hasStaticClass(element, 'search-form'),
  )
  const dialog = elements.find((element) => element.tag === 'AppDialog')
  cachedToolRetrievalApprovedSections = {
    search: canonicalTemplateNode(searchForm),
    results: canonicalTemplateNode(panels[1]),
    task: canonicalTemplateNode(panels[2]),
    dialog: canonicalTemplateNode(dialog),
  }
  return cachedToolRetrievalApprovedSections
}

function toolRetrievalExactStyleOracle(styles) {
  if (styles.length !== 1) return false
  const records = []
  let valid = true

  const recordRule = (context, selector, body) => {
    const normalizedSelector = normalizeWhitespace(selector).replace(
      /\[data-v-[a-z0-9-]+\]/gi,
      '[scope]',
    )
    const declarations = compiledDeclarations(`{${body}}`)
      .map(({ property, value }) => `${property}:${value}`)
      .sort()
    records.push(`${context}|${normalizedSelector}|${declarations.join(';')}`)
  }

  const parseLevel = (css, context) => {
    let cursor = 0
    while (cursor < css.length) {
      while (/\s/.test(css[cursor] ?? '')) cursor += 1
      if (cursor >= css.length) break
      const open = css.indexOf('{', cursor)
      if (open < 0) {
        if (css.slice(cursor).trim()) valid = false
        break
      }
      const header = normalizeWhitespace(css.slice(cursor, open))
      let depth = 1
      let close = open + 1
      for (; close < css.length && depth > 0; close += 1) {
        if (css[close] === '{') depth += 1
        if (css[close] === '}') depth -= 1
      }
      if (depth !== 0) {
        valid = false
        return
      }
      const body = css.slice(open + 1, close - 1)
      if (header.startsWith('@media ')) {
        if (context !== 'root') {
          valid = false
          return
        }
        parseLevel(body, `media:${normalizeWhitespace(header.slice('@media '.length))}`)
      } else if (!header || header.startsWith('@') || body.includes('{')) {
        valid = false
        return
      } else {
        recordRule(context, header, body)
      }
      cursor = close
    }
  }

  parseLevel(styles[0], 'root')
  if (!valid) return false
  const expected = [
    'root|.search-form[scope]|align-items:center;display:flex;flex-wrap:wrap;gap:calc(var(--section-gap) / 2) var(--section-gap)',
    'root|.search-form__query[scope]|width:min(420px, 100%)',
    'root|.search-form__score[scope]|width:160px',
    'root|.form-label[scope]|align-items:center;display:inline-flex;gap:4px',
    'root|.form-label__tip[scope]|color:var(--text-muted);cursor:help;font-size:0.875rem;outline:none',
    'root|.text-ellipsis[scope]|display:inline-block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap',
    'root|.task-state[scope]|display:flex;flex-direction:column;gap:var(--section-gap)',
    'root|.task-empty[scope]|color:var(--text-muted);line-height:1.6;margin:0',
    'root|.rebuild-dialog__control[scope]|width:100%',
    'media:(max-width: 720px)|.search-form__query[scope], .search-form__score[scope]|width:100%',
  ].sort()
  return arraysEqual(records.sort(), expected)
}

function validateToolRetrievalHeadScript(sourceFile, ast, label, localFailures) {
  const headSource = headToolRetrievalTestSource(localFailures, label)
  if (!headSource) return
  const approvedHeadSource = headSource
    .replace(/\r\n?/g, '\n')
    .replace(
      [
        '        <el-form-item label="仅 Agent 可见">',
        '          <el-switch v-model="form.agentVisibleOnly" />',
        '        </el-form-item>',
        '',
      ].join('\n'),
      '',
    )
    .replace(
      '          <el-table-column prop="toolName" label="Tool 名" min-width="220" />',
      [
        '          <el-table-column prop="toolTitle" label="工具名称" min-width="180">',
        '            <template #default="{ row }">{{ row.toolTitle || row.toolName }}</template>',
        '          </el-table-column>',
        '          <el-table-column prop="toolName" label="工具标识" min-width="220" />',
      ].join('\n'),
    )
    .replace('  agentVisibleOnly: false,\n', '')
    .replace('      agentVisibleOnly: form.agentVisibleOnly,\n', '')
  const parsed = parse(approvedHeadSource, { filename: 'HEAD:ToolRetrievalTest.vue' })
  if (parsed.errors.length > 0) {
    localFailures.push(`${label} HEAD Tool retrieval baseline must remain parseable`)
    return
  }
  const headSourceFile = parseTypeScript(
    parsed.descriptor.scriptSetup?.content ?? '',
    'HEAD:ToolRetrievalTest.vue script setup',
    localFailures,
  )
  if (
    JSON.stringify(toolRetrievalNonPresentationImports(sourceFile)) !==
    JSON.stringify(toolRetrievalNonPresentationImports(headSourceFile))
  ) {
    localFailures.push(`${label} must preserve HEAD Tool retrieval non-presentation/API imports`)
  }
  if (
    JSON.stringify(variableInitializerSequence(sourceFile)) !==
    JSON.stringify(variableInitializerSequence(headSourceFile))
  ) {
    localFailures.push(
      `${label} must preserve HEAD Tool retrieval runtime variables and initializers`,
    )
  }
  if (
    JSON.stringify([...runtimeFunctionSignatureMap(sourceFile).entries()]) !==
    JSON.stringify([...runtimeFunctionSignatureMap(headSourceFile).entries()])
  ) {
    localFailures.push(`${label} must preserve HEAD Tool retrieval runtime functions exactly`)
  }
  if (
    JSON.stringify(topLevelRuntimeControlStatements(sourceFile)) !==
    JSON.stringify(topLevelRuntimeControlStatements(headSourceFile))
  ) {
    localFailures.push(
      `${label} must preserve HEAD Tool retrieval lifecycle and top-level statement order`,
    )
  }
  const headAst = templateAst(
    parsed.descriptor.template?.content ?? '',
    'HEAD:ToolRetrievalTest.vue',
    localFailures,
  )
  if (
    JSON.stringify(toolRetrievalBusinessSnapshot(ast)) !==
    JSON.stringify(toolRetrievalBusinessSnapshot(headAst))
  ) {
    localFailures.push(
      `${label} must preserve HEAD Tool retrieval form, table, task, and dialog business subtrees`,
    )
  }
}

async function validateToolRetrievalTestConsumer(source, label, options = {}) {
  const localFailures = await validateCommonSfc(source, label)
  const parsed = parse(source, { filename: label })
  if (parsed.errors.length > 0) return localFailures
  const { descriptor } = parsed
  const sourceFile = parseTypeScript(
    descriptor.scriptSetup?.content ?? '',
    `${label} script setup`,
    localFailures,
  )
  const ast = templateAst(descriptor.template?.content ?? '', label, localFailures)
  const rootElement = rootTemplateElement(ast)
  const elements = templateElements(ast)
  const allElements = allTemplateElements(ast)
  const styles = await compiledStyleCodes(descriptor, label)

  if (!toolRetrievalPresentationImportsExact(sourceFile)) {
    localFailures.push(
      `${label} must directly import PageHeader, WorkbenchPage, WorkbenchPanel, DataTableShell, StatusTag, and AppDialog`,
    )
  }

  const rootChildren = toolRetrievalDirectElements(rootElement)
  const pageHeaders = elements.filter((element) => element.tag === 'PageHeader')
  const allPageHeaders = allElements.filter((element) => element.tag === 'PageHeader')
  const pageHeader = pageHeaders[0]
  const headerActions = templateSlot(ast, pageHeader, 'actions')
  const actionButtons = headerActions
    ? descendantElements(ast, headerActions).filter(
        (element) => element.tag.toLowerCase() === 'el-button',
      )
    : []
  const headerContract = Boolean(
    rootElement &&
      workbenchPageRootContract(rootElement, 'comfortable') &&
      pageHeaders.length === 1 &&
      allPageHeaders.length === 1 &&
      rootChildren[0] === pageHeader &&
      staticAttributeValue(pageHeader, 'variant') === 'standard' &&
      staticAttributeValue(pageHeader, 'domain') === 'tool' &&
      staticAttributeValue(pageHeader, 'eyebrow') === 'Retrieval Lab' &&
      staticAttributeValue(pageHeader, 'title') === 'Tool 检索测试' &&
      staticAttributeValue(pageHeader, 'description') ===
        '验证 Tool 语义召回、相似度阈值与向量索引状态，辅助定位能力检索质量。' &&
      staticAttributeValue(pageHeader, 'density') === 'comfortable' &&
      !hasStaticBooleanAttribute(pageHeader, 'compact') &&
      actionButtons.length === 1 &&
      staticAttributeValue(actionButtons[0], 'type') === 'warning' &&
      hasStaticBooleanAttribute(actionButtons[0], 'circle') &&
      staticAttributeValue(actionButtons[0], 'aria-label') === '重建向量索引' &&
      isIdentifierValue(
        directiveExpression(actionButtons[0], 'on', 'click'),
        'openRebuildDialog',
      ),
  )
  if (!headerContract) {
    localFailures.push(
      `${label} must render the exact comfortable Tool retrieval root and PageHeader action`,
    )
  }

  const panels = elements.filter((element) => element.tag === 'WorkbenchPanel')
  const allPanels = allElements.filter((element) => element.tag === 'WorkbenchPanel')
  const panelContract = Boolean(
    panels.length === 3 &&
      allPanels.length === 3 &&
      arraysEqual(rootChildren.slice(1, 4), panels) &&
      rootChildren.length === 5 &&
      panels.every(
        (panel) =>
          staticAttributeValue(panel, 'density') === 'comfortable' &&
          !toolRetrievalOwnsConditionalShell(panel),
      ) &&
      staticAttributeValue(panels[0], 'title') === '检索配置' &&
      expressionMatchesSource(
        directiveExpression(panels[1], 'bind', 'title'),
        '`召回结果（${candidates.length}）`',
      ) &&
      staticAttributeValue(panels[2], 'title') === '重建任务',
  )
  if (!panelContract) {
    localFailures.push(
      `${label} must render three direct, ordered, unconditional comfortable WorkbenchPanel shells`,
    )
  }

  const dataTableShells = elements.filter((element) => element.tag === 'DataTableShell')
  const resultShellContract = Boolean(
    dataTableShells.length === 1 &&
      panels[1] &&
      descendantElements(ast, panels[1]).includes(dataTableShells[0]) &&
      staticAttributeValue(dataTableShells[0], 'density') === 'compact' &&
      isIdentifierValue(directiveExpression(dataTableShells[0], 'bind', 'loading'), 'searching') &&
      expressionMatchesSource(
        directiveExpression(dataTableShells[0], 'bind', 'empty'),
        'candidates.length === 0',
      ) &&
      staticAttributeValue(dataTableShells[0], 'empty-description') === '无召回结果',
  )
  if (!resultShellContract) {
    localFailures.push(
      `${label} results panel must own one compact DataTableShell with stable loading and empty state`,
    )
  }

  const approvedSections = toolRetrievalApprovedSections()
  const searchForms = elements.filter(
    (element) => element.tag.toLowerCase() === 'el-form' && hasStaticClass(element, 'search-form'),
  )
  const exactSearchContract = Boolean(
    searchForms.length === 1 &&
      panels[0] &&
      descendantElements(ast, panels[0]).includes(searchForms[0]) &&
      JSON.stringify(canonicalTemplateNode(searchForms[0])) ===
        JSON.stringify(approvedSections.search),
  )
  if (!exactSearchContract) {
    localFailures.push(
      `${label} must preserve the exact five-item search form, three search triggers, controls, models, and props`,
    )
  }

  const exactResultsContract = Boolean(
    panels[1] &&
      JSON.stringify(canonicalTemplateNode(panels[1])) ===
        JSON.stringify(approvedSections.results),
  )
  if (!exactResultsContract) {
    localFailures.push(
      `${label} must preserve the exact compact results table, seven ordered columns, empty suppression, and score status`,
    )
  }

  const statusTags = elements.filter((element) => element.tag === 'StatusTag')
  const scoreStatus = statusTags.find((element) =>
    expressionMatchesSource(
      directiveExpression(element, 'bind', 'label'),
      'row.score.toFixed(4)',
    ),
  )
  const stageStatus = statusTags.find((element) =>
    expressionMatchesSource(directiveExpression(element, 'bind', 'label'), 'task.stage'),
  )
  if (!scoreStatus || !stageStatus || statusTags.length < 3) {
    localFailures.push(
      `${label} must use semantic StatusTag controls for score, task stage, and no-task state`,
    )
  }

  const exactTaskContract = Boolean(
    panels[2] &&
      JSON.stringify(canonicalTemplateNode(panels[2])) ===
        JSON.stringify(approvedSections.task),
  )
  if (!exactTaskContract) {
    localFailures.push(
      `${label} must preserve the exact fixed task shell, no-task state, nine descriptions, progress, and failure alert`,
    )
  }

  const dialogs = elements.filter((element) => element.tag === 'AppDialog')
  const allDialogs = allElements.filter((element) => element.tag === 'AppDialog')
  const dialog = dialogs[0]
  const dialogContract = Boolean(
    dialogs.length === 1 &&
      allDialogs.length === 1 &&
      rootChildren[4] === dialog &&
      isIdentifierValue(directiveExpression(dialog, 'model', undefined), 'rebuildDialogVisible') &&
      staticAttributeValue(dialog, 'title') === '选择向量索引模型' &&
      staticAttributeValue(dialog, 'width') === '480px' &&
      hasStaticBooleanAttribute(dialog, 'destroy-on-close') &&
      isIdentifierValue(
        directiveExpression(dialog, 'on', 'open'),
        'loadEmbeddingInstances',
      ),
  )
  if (!dialogContract) {
    localFailures.push(
      `${label} must render one direct AppDialog with model, title, width, destroy-on-close, and open passthrough`,
    )
  }

  const exactDialogContract = Boolean(
    dialog &&
      JSON.stringify(canonicalTemplateNode(dialog)) ===
        JSON.stringify(approvedSections.dialog),
  )
  if (!exactDialogContract) {
    localFailures.push(
      `${label} must preserve the exact AppDialog default and footer subtrees with provider filtering controls`,
    )
  }

  if (
    allElements.some((element) =>
      ['el-card', 'el-dialog', 'el-empty'].includes(element.tag.toLowerCase()),
    )
  ) {
    localFailures.push(`${label} must not render raw el-card, el-dialog, or page-owned el-empty surfaces`)
  }

  const ownsInlinePresentation = allElements.some((element) =>
    (element.props ?? []).some(
      (property) =>
        (property.type === 6 && property.name === 'style') ||
        (property.type === 7 &&
          property.name === 'bind' &&
          (!property.arg ||
            (property.arg.isStatic && ['style', 'class'].includes(property.arg.content)))),
    ),
  )
  if (ownsInlinePresentation) {
    localFailures.push(
      `${label} must not own inline styles or runtime class/style public-component overrides`,
    )
  }
  const styleBlockContract =
    mcpStyleBlockContract(descriptor) && mcpRawStyleOpeningTagContract(source)
  if (!styleBlockContract) {
    localFailures.push(
      `${label} must use exactly one scoped SCSS style block with no media, module, or src attributes`,
    )
  }
  if (toolRetrievalOwnsPrivateStyle(descriptor, styles)) {
    localFailures.push(
      `${label} must not own private glass, overlay, Element Plus, or shared-component recipes`,
    )
  }

  if (!toolRetrievalExactStyleOracle(styles)) {
    localFailures.push(
      `${label} must keep the exact approved Tool retrieval compiled selector, declaration, and media oracle`,
    )
  }

  if (!toolRetrievalApprovedTemplateContract(ast)) {
    localFailures.push(
      `${label} must preserve the exact normalized Tool retrieval template topology`,
    )
  }

  if (!options.skipHeadPreservation) {
    validateToolRetrievalHeadScript(sourceFile, ast, label, localFailures)
  }
  return localFailures
}

const syntheticToolRetrievalTestConsumer = `<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      variant="standard"
      domain="tool"
      eyebrow="Retrieval Lab"
      title="Tool 检索测试"
      description="验证 Tool 语义召回、相似度阈值与向量索引状态，辅助定位能力检索质量。"
      density="comfortable"
    >
      <template #actions>
        <el-tooltip content="重建向量索引" placement="top">
          <el-button
            circle
            type="warning"
            :icon="RefreshRight"
            aria-label="重建向量索引"
            @click="openRebuildDialog"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <WorkbenchPanel title="检索配置" density="comfortable">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="用户问题">
          <el-input
            v-model="form.query"
            class="search-form__query"
            placeholder="例如：帮我查询最近一周的工单数量"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="form.topK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="仅启用">
          <el-switch v-model="form.enabledOnly" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <span class="form-label">
              相似度下限
              <el-tooltip
                content="0=不过滤；清空后使用服务端 min-score"
                placement="top"
              >
                <el-icon class="form-label__tip" tabindex="0" aria-label="相似度下限说明">
                  <QuestionFilled />
                </el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number
            v-model="form.minScore"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            :value-on-clear="undefined"
            controls-position="right"
            class="search-form__score"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searching" @click="handleSearch">检索</el-button>
        </el-form-item>
      </el-form>
    </WorkbenchPanel>

    <WorkbenchPanel :title="\`召回结果（\${candidates.length}）\`" density="comfortable">
      <DataTableShell
        density="compact"
        :loading="searching"
        :empty="candidates.length === 0"
        empty-description="无召回结果"
      >
        <el-table :data="candidates" stripe empty-text=" ">
          <el-table-column label="#" type="index" width="60" />
          <el-table-column prop="toolTitle" label="工具名称" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.toolTitle || row.toolName }}</template>
          </el-table-column>
          <el-table-column prop="toolName" label="工具标识" min-width="220" show-overflow-tooltip />
          <el-table-column label="分数" width="100">
            <template #default="{ row }">
              <StatusTag
                :label="row.score.toFixed(4)"
                :tone="scoreTag(row.score) || 'neutral'"
              />
            </template>
          </el-table-column>
          <el-table-column prop="projectId" label="项目 ID" width="100" />
          <el-table-column prop="moduleId" label="模块 ID" width="100" />
          <el-table-column label="入库文本" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.text }}</span>
            </template>
          </el-table-column>
        </el-table>
      </DataTableShell>
    </WorkbenchPanel>

    <WorkbenchPanel title="重建任务" density="comfortable">
      <template #actions>
        <StatusTag
          v-if="task"
          :label="task.stage"
          :tone="stageTag(task.stage) || 'neutral'"
        />
        <StatusTag v-else label="暂无任务" tone="neutral" />
      </template>
      <div v-if="task" class="task-state">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="总数">{{ task.totalSteps }}</el-descriptions-item>
          <el-descriptions-item label="已完成">{{ task.completedSteps }}</el-descriptions-item>
          <el-descriptions-item label="成功">{{ task.successCount }}</el-descriptions-item>
          <el-descriptions-item label="跳过">{{ task.skippedCount }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ task.failedCount }}</el-descriptions-item>
          <el-descriptions-item label="向量模型实例">
            {{ task.embeddingModelInstanceId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="当前">{{ task.currentStep || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始">{{ task.startedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="结束">{{ task.finishedAt || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-progress
          v-if="task.stage === 'QUEUED' || task.stage === 'RUNNING'"
          :percentage="taskPercent"
          :text-inside="true"
          :stroke-width="18"
        />
        <el-alert
          v-if="task.stage === 'FAILED'"
          type="error"
          :title="\`重建失败：\${task.errorMessage || '未知错误'}\`"
          :closable="false"
          show-icon
        />
      </div>
      <p v-else class="task-empty">尚无重建任务</p>
    </WorkbenchPanel>

    <AppDialog
      v-model="rebuildDialogVisible"
      title="选择向量索引模型"
      width="480px"
      destroy-on-close
      @open="loadEmbeddingInstances"
    >
      <el-form label-width="120px">
        <el-form-item required>
          <template #label>
            <span class="form-label">
              模型厂商
              <el-tooltip content="请选择与 Milvus 集合维度一致、状态可用的 Embedding 实例所属厂商" placement="top">
                <el-icon class="form-label__tip" tabindex="0" aria-label="模型厂商说明">
                  <QuestionFilled />
                </el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select
            v-model="rebuildModelProvider"
            class="rebuild-dialog__control"
            placeholder="请选择厂商"
            filterable
            @change="handleRebuildProviderChange"
          >
            <el-option
              v-for="provider in embeddingProviderOptions"
              :key="provider"
              :label="provider"
              :value="provider"
            />
          </el-select>
        </el-form-item>
        <el-form-item required>
          <template #label>
            <span class="form-label">
              Embedding 实例
              <el-tooltip
                content="所选实例会写入 tool_retrieval_setting，供对话时的 Tool 语义召回共用"
                placement="top"
              >
                <el-icon class="form-label__tip" tabindex="0" aria-label="Embedding 实例说明">
                  <QuestionFilled />
                </el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-select
            v-model="rebuildModelInstanceId"
            class="rebuild-dialog__control"
            placeholder="请选择向量模型实例"
            filterable
            :disabled="!rebuildModelProvider"
          >
            <el-option
              v-for="item in filteredEmbeddingInstances"
              :key="item.id"
              :label="\`\${item.name} / \${item.modelName}\`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rebuildDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="rebuildStarting" @click="confirmRebuild">开始重建</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import AppDialog from '@/components/common/AppDialog.vue'
const form = { query: '', topK: 10, enabledOnly: false, minScore: undefined }
const searching = false
const candidates = [{ score: 0.8 }]
const task = { stage: 'DONE' }
const rebuildDialogVisible = false
const rebuildModelProvider = ''
const rebuildModelInstanceId = ''
const embeddingProviderOptions = ['demo']
const filteredEmbeddingInstances = [{ id: 1, name: 'demo', modelName: 'embedding' }]
const taskPercent = 100
const rebuildStarting = false
function handleSearch() {}
function openRebuildDialog() {}
function handleRebuildProviderChange() {}
function loadEmbeddingInstances() {}
function confirmRebuild() {}
function scoreTag(_score: number) { return 'success' }
function stageTag(_stage: string) { return 'success' }
</script>

<style scoped lang="scss">
.search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: calc(var(--section-gap) / 2) var(--section-gap);
}

.search-form__query {
  width: min(420px, 100%);
}

.search-form__score {
  width: 160px;
}

.form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.form-label__tip {
  color: var(--text-muted);
  cursor: help;
  font-size: 0.875rem;
  outline: none;
}

.text-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.task-state {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
}

.task-empty {
  margin: 0;
  color: var(--text-muted);
  line-height: 1.6;
}

.rebuild-dialog__control {
  width: 100%;
}

@media (max-width: 720px) {
  .search-form__query,
  .search-form__score {
    width: 100%;
  }
}
</style>
`

const syntheticAgentEditConsumer = `<template>
  <WorkbenchPage density="comfortable" full-height>
    <PageHeader
      variant="standard"
      domain="agent"
      :title="isNew ? '新建智能体' : \`编辑智能体 - \${form.name || agentId}\`"
      eyebrow="Agent / Supervisor"
      density="comfortable"
      show-back
      @back="router.push('/agent')"
    >
      <template #tags>
        <StatusTag :label="agentKindLabel(form.agentKind)" tone="neutral" />
        <StatusTag
          :label="form.enabled !== false ? '启用' : '停用'"
          :tone="form.enabled !== false ? 'success' : 'info'"
        />
      </template>
      <template #actions>
        <el-tooltip content="复制 keySlug 和 ID" placement="bottom">
          <span>
            <el-button :icon="DocumentCopy" :disabled="!canCopyAgentIdentity" @click="copyAgentIdentity">
              复制标识
            </el-button>
          </span>
        </el-tooltip>
        <el-button v-if="!isNew" :icon="Share" @click="openWorkflows">Workflow 列表</el-button>
        <el-button v-if="!isNew" type="success" plain :loading="publishing" @click="handlePublish">发布 Supervisor 配置</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </PageHeader>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="108px" v-loading="pageLoading">
      <main class="workbench-layout" :class="{ 'workbench-layout--with-aside': !isNew }">
        <WorkbenchPanel
          title="身份与策略"
          description="配置 Agent 入口身份、可见性与默认策略；图编排请在 Workflow Studio 完成。"
          density="comfortable"
        >
          <template #actions>
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </template>
          <div class="form-grid two">
            <el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="如：合同审核助手" /></el-form-item>
            <el-form-item label="keySlug" prop="keySlug"><el-input v-model="form.keySlug" placeholder="如 contract-review" :disabled="!isNew" /></el-form-item>
            <el-form-item label="入口类型" prop="agentKind"><el-select v-model="form.agentKind"><el-option v-for="item in agentKindOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item label="可见性"><el-select v-model="form.visibility"><el-option label="项目" value="PROJECT" /><el-option label="私有" value="PRIVATE" /><el-option label="公开" value="PUBLIC" /></el-select></el-form-item>
            <el-form-item label="所属项目"><el-select v-model="form.projectId" clearable filterable placeholder="平台级 / 全局" @change="handleProjectChange"><el-option v-for="project in scanProjects" :key="project.id" :label="projectOptionLabel(project)" :value="project.id" /></el-select></el-form-item>
            <el-form-item label="默认模型"><el-select v-model="form.modelInstanceId" clearable filterable placeholder="可选"><el-option v-for="item in llmModelInstances" :key="item.id" :label="\`\${item.name} (\${item.modelName})\`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="运行角色" class="wide"><el-select v-model="form.allowedRoles" multiple filterable allow-create default-first-option collapse-tags collapse-tags-tooltip placeholder="留空表示不限制业务角色" /></el-form-item>
          </div>
          <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话描述入口用途" /></el-form-item>
          <el-form-item label="System Prompt"><el-input v-model="form.systemPrompt" type="textarea" :rows="6" placeholder="定义入口默认角色与回答风格（Workflow 节点可覆盖）" /></el-form-item>
          <el-form-item label="入口配置"><el-input v-model="entryConfigText" type="textarea" :rows="5" placeholder='JSON，如 {"intentType":"GENERAL_CHAT"}' /></el-form-item>
        </WorkbenchPanel>

        <aside v-if="!isNew" class="agent-workbench__aside">
          <WorkbenchPanel
            title="Workflow 工具目录"
            description="当前选择会保存到 Agent 配置草稿，发布后成为 Supervisor 的动态工具白名单。"
            density="comfortable"
          >
            <el-button class="summary-action" type="primary" plain :icon="Share" @click="openWorkflows">打开 Workflow 列表</el-button>
          </WorkbenchPanel>
        </aside>
      </main>
    </el-form>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
const isNew = true
const agentId = 'new'
const form = { name: '', agentKind: 'PROJECT_ENTRY', enabled: true }
const router = { push: (_path: string) => undefined }
</script>

<style scoped lang="scss">
.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--section-gap);
}

.workbench-layout--with-aside {
  grid-template-columns: minmax(0, 1fr) 272px;
}

.agent-workbench__aside,
.form-grid {
  min-width: 0;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.wide {
  grid-column: span 2;
}

.summary-action {
  width: 100%;
  margin-top: calc(var(--section-gap) / 2);
}

@media (max-width: 1280px) {
  .workbench-layout--with-aside {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .form-grid.two {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }
}
</style>
`

let mutationProofCount = 0
let safeVariantProofCount = 0
let knowledgeHarnessProofCount = 0
let agentHarnessProofCount = 0
let mcpHarnessProofCount = 0
let toolRetrievalHarnessProofCount = 0

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

function currentKnowledgeDetailSource() {
  return readNormalizedUtf8(resolve(root, 'src/views/KnowledgeDetail.vue'))
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

function typescriptNodes(root, predicate) {
  const matches = []
  const visit = (node) => {
    if (predicate(node)) matches.push(node)
    ts.forEachChild(node, visit)
  }
  visit(root)
  return matches
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

function currentToolRetrievalTestSource() {
  return readNormalizedUtf8(resolve(root, 'src/views/tool/ToolRetrievalTest.vue'))
}

async function runToolRetrievalMutationProof(
  name,
  baseline,
  validationOptions,
  mutate,
  expectedFailures,
) {
  const label = `mutation:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`mutation proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const mutationFailures = await validateToolRetrievalTestConsumer(
    mutated,
    label,
    validationOptions,
  )
  const expected = (Array.isArray(expectedFailures) ? expectedFailures : [expectedFailures])
    .map((failure) => `${label} ${failure}`)
    .sort()
  const actual = [...mutationFailures].sort()
  if (!arraysEqual(actual, expected)) {
    failures.push(
      `mutation proof ${name} expected exactly "${expectedFailures}"; got ${mutationFailures.join(' | ') || '(none)'}`,
    )
    return
  }
  mutationProofCount += 1
  console.log(`Mutation proof ${name}: ${expectedFailures}`)
}

async function runToolRetrievalSafeVariantProof(name, baseline, validationOptions, mutate) {
  const label = `safe-variant:${name}`
  let mutated
  try {
    mutated = requiredKnowledgeMutation(name, baseline, mutate)
  } catch (error) {
    failures.push(`safe variant proof ${name} harness failure: ${formatCompilerError(error)}`)
    return
  }
  const variantFailures = await validateToolRetrievalTestConsumer(
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

function runToolRetrievalMutationHarnessProof() {
  try {
    requiredKnowledgeMutation(
      'tool-retrieval-no-op-self-test',
      syntheticToolRetrievalTestConsumer,
      (source) => source,
    )
    failures.push('Tool retrieval mutation harness must reject a no-op transform')
  } catch (error) {
    if (!formatCompilerError(error).includes('must change its baseline before validation')) {
      failures.push(
        `Tool retrieval mutation harness self-test failed unexpectedly: ${formatCompilerError(error)}`,
      )
      return
    }
    toolRetrievalHarnessProofCount += 1
    console.log('Harness proof tool-retrieval-no-op-rejected: pass')
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

  const actualKnowledgeDetailSource = currentKnowledgeDetailSource()
  const actualKnowledgeConsumerBaselineFailures = await validateKnowledgeDetailConsumer(
    actualKnowledgeDetailSource,
    'actual KnowledgeDetail consumer mutation baseline',
    { skipHeadPreservation: true },
  )
  if (actualKnowledgeConsumerBaselineFailures.length > 0) {
    failures.push(
      `actual KnowledgeDetail consumer mutation baseline must pass structural ownership checks: ${actualKnowledgeConsumerBaselineFailures.join(' | ')}`,
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

  const toolRetrievalBaselineFailures = await validateToolRetrievalTestConsumer(
    syntheticToolRetrievalTestConsumer,
    'synthetic Tool retrieval test consumer baseline',
    { skipHeadPreservation: true },
  )
  if (toolRetrievalBaselineFailures.length > 0) {
    failures.push(
      `synthetic Tool retrieval test consumer baseline must pass independently: ${toolRetrievalBaselineFailures.join(' | ')}`,
    )
    baselineFailed = true
  }

  const actualToolRetrievalSource = currentToolRetrievalTestSource()
  const actualToolRetrievalBaselineFailures = await validateToolRetrievalTestConsumer(
    actualToolRetrievalSource,
    'actual Tool retrieval test consumer mutation baseline',
  )
  if (actualToolRetrievalBaselineFailures.length > 0) {
    failures.push(
      `actual Tool retrieval test consumer mutation baseline must pass with HEAD preservation: ${actualToolRetrievalBaselineFailures.join(' | ')}`,
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
  runToolRetrievalMutationHarnessProof()

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
  const structuralToolRetrievalOptions = { skipHeadPreservation: true }

  const toolRetrievalTopologyFailure =
    'must preserve the exact normalized Tool retrieval template topology'
  const toolRetrievalSearchFailure =
    'must preserve the exact five-item search form, three search triggers, controls, models, and props'
  const toolRetrievalResultsFailure =
    'must preserve the exact compact results table, seven ordered columns, empty suppression, and score status'
  const toolRetrievalTaskFailure =
    'must preserve the exact fixed task shell, no-task state, nine descriptions, progress, and failure alert'
  const toolRetrievalDialogFailure =
    'must preserve the exact AppDialog default and footer subtrees with provider filtering controls'

  for (const [name, mutate, expectedFailures] of [
    [
      'tool-retrieval-missing-page-header-import',
      (source) => replaceRequired(
        source,
        "@/components/common/PageHeader.vue",
        "@/components/common/MissingPageHeader.vue",
        'Tool retrieval PageHeader import mutation',
      ),
      'must directly import PageHeader, WorkbenchPage, WorkbenchPanel, DataTableShell, StatusTag, and AppDialog',
    ],
    [
      'tool-retrieval-root-density',
      (source) => replaceRequired(
        source,
        '<WorkbenchPage density="comfortable">',
        '<WorkbenchPage density="compact">',
        'Tool retrieval root density mutation',
      ),
      [
        'must render the exact comfortable Tool retrieval root and PageHeader action',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-header-action',
      (source) => replaceRequired(
        source,
        '@click="openRebuildDialog"',
        '@click="confirmRebuild"',
        'Tool retrieval header action mutation',
      ),
      [
        'must render the exact comfortable Tool retrieval root and PageHeader action',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-conditional-panel-shell',
      (source) => replaceRequired(
        source,
        '<WorkbenchPanel title="检索配置" density="comfortable">',
        '<WorkbenchPanel v-if="form.query" title="检索配置" density="comfortable">',
        'Tool retrieval conditional panel mutation',
      ),
      [
        'must render three direct, ordered, unconditional comfortable WorkbenchPanel shells',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-search-submit',
      (source) => replaceRequired(
        source,
        ' @submit.prevent="handleSearch"',
        '',
        'Tool retrieval search submit mutation',
      ),
      [toolRetrievalSearchFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-search-enter',
      (source) => replaceRequired(
        source,
        '@keyup.enter="handleSearch"',
        '@keyup.enter="openRebuildDialog"',
        'Tool retrieval enter mutation',
      ),
      [toolRetrievalSearchFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-search-loading',
      (source) => replaceRequired(
        source,
        'type="primary" :loading="searching" @click="handleSearch"',
        'type="primary" @click="handleSearch"',
        'Tool retrieval search loading mutation',
      ),
      [toolRetrievalSearchFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-result-loading',
      (source) => replaceRequired(
        source,
        ':loading="searching"\n        :empty="candidates.length === 0"',
        ':loading="false"\n        :empty="candidates.length === 0"',
        'Tool retrieval result loading mutation',
      ),
      [
        'results panel must own one compact DataTableShell with stable loading and empty state',
        toolRetrievalResultsFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-table-empty-suppression',
      (source) => replaceRequired(
        source,
        ' stripe empty-text=" ">',
        ' stripe>',
        'Tool retrieval empty suppression mutation',
      ),
      [toolRetrievalResultsFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-score-tone',
      (source) => replaceRequired(
        source,
        ":tone=\"scoreTag(row.score) || 'neutral'\"",
        'tone="info"',
        'Tool retrieval score tone mutation',
      ),
      [toolRetrievalResultsFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-task-panel-condition',
      (source) => replaceRequired(
        source,
        '<WorkbenchPanel title="重建任务" density="comfortable">',
        '<WorkbenchPanel v-if="task" title="重建任务" density="comfortable">',
        'Tool retrieval task panel condition mutation',
      ),
      [
        'must render three direct, ordered, unconditional comfortable WorkbenchPanel shells',
        toolRetrievalTaskFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-stage-tone',
      (source) => replaceRequired(
        source,
        ":tone=\"stageTag(task.stage) || 'neutral'\"",
        'tone="info"',
        'Tool retrieval stage tone mutation',
      ),
      [toolRetrievalTaskFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-no-task-status',
      (source) => replaceRequired(
        source,
        '        <StatusTag v-else label="暂无任务" tone="neutral" />\n',
        '',
        'Tool retrieval no-task status mutation',
      ),
      [
        'must use semantic StatusTag controls for score, task stage, and no-task state',
        toolRetrievalTaskFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-task-description',
      (source) => replaceRequired(
        source,
        '          <el-descriptions-item label="结束">{{ task.finishedAt || \'-\' }}</el-descriptions-item>\n',
        '',
        'Tool retrieval task description mutation',
      ),
      [toolRetrievalTaskFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-task-percentage',
      (source) => replaceRequired(
        source,
        ':percentage="taskPercent"',
        ':percentage="0"',
        'Tool retrieval task percentage mutation',
      ),
      [toolRetrievalTaskFailure, toolRetrievalTopologyFailure],
    ],
    [
      'tool-retrieval-dialog-destroy',
      (source) => replaceRequired(
        source,
        '      destroy-on-close\n',
        '',
        'Tool retrieval dialog destroy mutation',
      ),
      [
        'must render one direct AppDialog with model, title, width, destroy-on-close, and open passthrough',
        toolRetrievalDialogFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-dialog-open',
      (source) => replaceRequired(
        source,
        '@open="loadEmbeddingInstances"',
        '@open="openRebuildDialog"',
        'Tool retrieval dialog open mutation',
      ),
      [
        'must render one direct AppDialog with model, title, width, destroy-on-close, and open passthrough',
        toolRetrievalDialogFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-inline-style',
      (source) => replaceRequired(
        source,
        'class="search-form__query"',
        'class="search-form__query" style="width: 420px"',
        'Tool retrieval inline style mutation',
      ),
      [
        toolRetrievalSearchFailure,
        'must not own inline styles or runtime class/style public-component overrides',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-page-empty',
      (source) => replaceRequired(
        source,
        '        <el-table :data="candidates" stripe empty-text=" ">',
        '        <el-empty description="无召回结果" />\n        <el-table :data="candidates" stripe empty-text=" ">',
        'Tool retrieval raw empty mutation',
      ),
      [
        toolRetrievalResultsFailure,
        'must not render raw el-card, el-dialog, or page-owned el-empty surfaces',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-private-background',
      (source) => replaceRequired(
        source,
        '  gap: var(--section-gap);\n}',
        '  gap: var(--section-gap);\n  background: var(--surface-glass-panel);\n}',
        'Tool retrieval private background mutation',
      ),
      [
        'must not own private glass, overlay, Element Plus, or shared-component recipes',
        'must keep the exact approved Tool retrieval compiled selector, declaration, and media oracle',
      ],
    ],
    [
      'tool-retrieval-side-reservation',
      (source) => replaceRequired(
        source,
        '  gap: var(--section-gap);\n}',
        '  gap: var(--section-gap);\n  margin-inline-start: 240px;\n}',
        'Tool retrieval side reservation mutation',
      ),
      'must keep the exact approved Tool retrieval compiled selector, declaration, and media oracle',
    ],
    [
      'tool-retrieval-hidden-root',
      (source) => replaceRequired(
        source,
        '<WorkbenchPage density="comfortable">',
        '<WorkbenchPage density="comfortable" hidden>',
        'Tool retrieval hidden root mutation',
      ),
      [
        'must render the exact comfortable Tool retrieval root and PageHeader action',
        toolRetrievalTopologyFailure,
      ],
    ],
  ]) {
    await runToolRetrievalMutationProof(
      name,
      syntheticToolRetrievalTestConsumer,
      structuralToolRetrievalOptions,
      mutate,
      expectedFailures,
    )
  }

  for (const [name, mutate, expectedFailures] of [
    [
      'tool-retrieval-raw-card',
      (source) => replaceRequired(
        source,
        '      <DataTableShell\n',
        '      <el-card />\n      <DataTableShell\n',
        'Tool retrieval raw card mutation',
      ),
      [
        toolRetrievalResultsFailure,
        'must not render raw el-card, el-dialog, or page-owned el-empty surfaces',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-raw-dialog',
      (source) =>
        replaceRequired(
          replaceRequired(
            source,
            '    <AppDialog\n',
            '    <el-dialog\n',
            'Tool retrieval raw dialog opening mutation',
          ),
          '    </AppDialog>',
          '    </el-dialog>',
          'Tool retrieval raw dialog closing mutation',
        ),
      [
        'must render one direct AppDialog with model, title, width, destroy-on-close, and open passthrough',
        toolRetrievalDialogFailure,
        'must not render raw el-card, el-dialog, or page-owned el-empty surfaces',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-task-v-show',
      (source) => replaceRequired(
        source,
        '<WorkbenchPanel title="重建任务" density="comfortable">',
        '<WorkbenchPanel v-show="task" title="重建任务" density="comfortable">',
        'Tool retrieval task v-show mutation',
      ),
      [
        'must render three direct, ordered, unconditional comfortable WorkbenchPanel shells',
        toolRetrievalTaskFailure,
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-runtime-class-override',
      (source) => replaceRequired(
        source,
        '    <PageHeader\n',
        '    <PageHeader\n      :class="form.query"\n',
        'Tool retrieval runtime class mutation',
      ),
      [
        'must not own inline styles or runtime class/style public-component overrides',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-public-panel-style',
      (source) => replaceRequired(
        source,
        '<WorkbenchPanel :title="\`召回结果（\${candidates.length}）\`" density="comfortable">',
        '<WorkbenchPanel :title="\`召回结果（\${candidates.length}）\`" density="comfortable" style="width: 100%">',
        'Tool retrieval public panel style mutation',
      ),
      [
        toolRetrievalResultsFailure,
        'must not own inline styles or runtime class/style public-component overrides',
        toolRetrievalTopologyFailure,
      ],
    ],
    [
      'tool-retrieval-duplicate-search-control',
      (source) => replaceRequired(
        source,
        '        <el-form-item>\n          <el-button type="primary"',
        '        <el-form-item><el-button @click="handleSearch">再次检索</el-button></el-form-item>\n        <el-form-item>\n          <el-button type="primary"',
        'Tool retrieval duplicate search mutation',
      ),
      [toolRetrievalSearchFailure, toolRetrievalTopologyFailure],
    ],
    ...[
      ['offscreen', '  position: fixed;\n  inset-inline-start: -9999px;'],
      ['zero-size', '  width: 0;\n  height: 0;'],
      ['cross-axis', '  align-items: flex-end;'],
    ].map(([variant, declarations]) => [
      `tool-retrieval-${variant}-style`,
      (source) => replaceRequired(
        source,
        '  gap: var(--section-gap);\n}',
        `  gap: var(--section-gap);\n${declarations}\n}`,
        `Tool retrieval ${variant} style mutation`,
      ),
      'must keep the exact approved Tool retrieval compiled selector, declaration, and media oracle',
    ]),
    [
      'tool-retrieval-theme-selector',
      (source) => replaceRequired(
        source,
        '</style>',
        '\n[data-theme="dark"] { color: var(--text-primary); }\n</style>',
        'Tool retrieval theme selector mutation',
      ),
      [
        'compiled style 1 must not own page theme or brand selector [data-theme=dark][data-v-glass-component-1]',
        'must not own private glass, overlay, Element Plus, or shared-component recipes',
        'must keep the exact approved Tool retrieval compiled selector, declaration, and media oracle',
      ],
    ],
  ]) {
    await runToolRetrievalMutationProof(
      name,
      syntheticToolRetrievalTestConsumer,
      structuralToolRetrievalOptions,
      mutate,
      expectedFailures,
    )
  }

  for (const [name, mutate, expectedFailures] of [
    [
      'tool-retrieval-actual-api-import',
      (source) => replaceRequired(
        source,
        'searchToolRetrieval,',
        'searchToolRetrievalChanged,',
        'Tool retrieval API import mutation',
      ),
      'must preserve HEAD Tool retrieval non-presentation/API imports',
    ],
    [
      'tool-retrieval-actual-form-initializer',
      (source) => replaceRequired(
        source,
        '  topK: 10,',
        '  topK: 20,',
        'Tool retrieval form initializer mutation',
      ),
      'must preserve HEAD Tool retrieval runtime variables and initializers',
    ],
    [
      'tool-retrieval-actual-poll-interval',
      (source) => replaceRequired(
        source,
        '  }, 1500)',
        '  }, 1000)',
        'Tool retrieval polling interval mutation',
      ),
      'must preserve HEAD Tool retrieval runtime functions exactly',
    ],
    [
      'tool-retrieval-actual-unmount-lifecycle',
      (source) => replaceRequired(
        source,
        'onUnmounted(stopPolling)',
        'onUnmounted(loadLatest)',
        'Tool retrieval unmount lifecycle mutation',
      ),
      'must preserve HEAD Tool retrieval lifecycle and top-level statement order',
    ],
    [
      'tool-retrieval-actual-query-placeholder',
      (source) => replaceRequired(
        source,
        'placeholder="例如：帮我查询最近一周的工单数量"',
        'placeholder="输入问题"',
        'Tool retrieval query placeholder mutation',
      ),
      [
        toolRetrievalSearchFailure,
        toolRetrievalTopologyFailure,
        'must preserve HEAD Tool retrieval form, table, task, and dialog business subtrees',
      ],
    ],
    [
      'tool-retrieval-actual-result-column',
      (source) => replaceRequired(
        source,
        'prop="projectId" label="项目 ID"',
        'prop="moduleId" label="项目 ID"',
        'Tool retrieval result column mutation',
      ),
      [
        toolRetrievalResultsFailure,
        toolRetrievalTopologyFailure,
        'must preserve HEAD Tool retrieval form, table, task, and dialog business subtrees',
      ],
    ],
    [
      'tool-retrieval-actual-provider-change',
      (source) => replaceRequired(
        source,
        '@change="handleRebuildProviderChange"',
        '@change="loadEmbeddingInstances"',
        'Tool retrieval provider handler mutation',
      ),
      [
        toolRetrievalDialogFailure,
        toolRetrievalTopologyFailure,
        'must preserve HEAD Tool retrieval form, table, task, and dialog business subtrees',
      ],
    ],
  ]) {
    await runToolRetrievalMutationProof(
      name,
      actualToolRetrievalSource,
      {},
      mutate,
      expectedFailures,
    )
  }

  for (const [name, mutate, expectedFailure] of [
    [
      'tool-retrieval-actual-task-percent',
      (source) => replaceRequired(
        source,
        'task.value.completedSteps / task.value.totalSteps',
        'task.value.failedCount / task.value.totalSteps',
        'Tool retrieval task percent initializer mutation',
      ),
      'must preserve HEAD Tool retrieval runtime variables and initializers',
    ],
    [
      'tool-retrieval-actual-stage-function',
      (source) => replaceRequired(
        source,
        "return 'warning'",
        "return 'info'",
        'Tool retrieval stage function mutation',
      ),
      'must preserve HEAD Tool retrieval runtime functions exactly',
    ],
    [
      'tool-retrieval-actual-score-function',
      (source) => replaceRequired(
        source,
        '  if (score >= 0.7)',
        '  if (score >= 0.8)',
        'Tool retrieval score function mutation',
      ),
      'must preserve HEAD Tool retrieval runtime functions exactly',
    ],
    [
      'tool-retrieval-actual-default-parameter',
      (source) => replaceRequired(
        source,
        'function startPolling(taskId: string)',
        "function startPolling(taskId: string = '')",
        'Tool retrieval default parameter mutation',
      ),
      'must preserve HEAD Tool retrieval runtime functions exactly',
    ],
    [
      'tool-retrieval-actual-lifecycle-order',
      (source) => {
        const mutated = source.replace(
          /onMounted\(loadLatest\)\r?\nonUnmounted\(stopPolling\)/,
          'onUnmounted(stopPolling)\nonMounted(loadLatest)',
        )
        if (mutated === source) {
          throw new Error('Tool retrieval lifecycle order mutation could not locate its required source span')
        }
        return mutated
      },
      'must preserve HEAD Tool retrieval lifecycle and top-level statement order',
    ],
  ]) {
    await runToolRetrievalMutationProof(
      name,
      actualToolRetrievalSource,
      {},
      mutate,
      expectedFailure,
    )
  }

  await runToolRetrievalSafeVariantProof(
    'tool-retrieval-attribute-and-declaration-reordering',
    actualToolRetrievalSource,
    {},
    (source) =>
      replaceRequired(
        replaceRequired(
          source,
          '      variant="standard"\n      domain="tool"',
          '      domain="tool"\n      variant="standard"',
          'Tool retrieval safe PageHeader attribute reorder',
        ),
        '  display: flex;\n  flex-direction: column;\n  gap: var(--section-gap);',
        '  gap: var(--section-gap);\n  display: flex;\n  flex-direction: column;',
        'Tool retrieval safe declaration reorder',
      ),
  )

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
    ['agent-supervisor-workbench-identity', 'title="Agent 身份与接入"', 'title="Agent 配置"', 'must separate stable Agent identity'],
    ['agent-supervisor-workbench-runtime', 'title="Supervisor 运行配置"', 'title="运行配置"', 'must expose the versioned Supervisor runtime configuration'],
    ['agent-supervisor-workbench-version-copy', 'await copyAgentConfigToDraft(', 'await copyLegacyConfig(', 'must support copying immutable config snapshots'],
    ['agent-supervisor-workbench-tool-records', 'class="workflow-tool-table"', 'class="workflow-binding-table"', 'must render the editable Workflow tool catalog'],
  ]) {
    const mutated = replaceRequired(actualAgentEditSource, needle, replacement, name)
    const mutationFailures = await validateAgentSupervisorWorkbenchConsumer(mutated, name)
    if (!mutationFailures.some((failure) => failure.includes(expectedFailure))) {
      failures.push(`${name} mutation must be rejected: ${expectedFailure}`)
    }
  }

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
    'must declare exactly density/loading/showReset/queryLabel/resetLabel props',
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
  await runContractMutationProof(
    'data-table-shell-prop-shape',
    syntheticDataTableShell,
    validateDataTableShell,
    (source) => source.replace('  pageSizes?: number[]\n', '  pageSizes?: string\n'),
    'must declare exactly the controlled table-shell props',
  )
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

await runMutationProofs()
console.log(`MUTATION_PROOFS=${mutationProofCount}`)
console.log(`SAFE_VARIANT_PROOFS=${safeVariantProofCount}`)
console.log(`KNOWLEDGE_HARNESS_PROOFS=${knowledgeHarnessProofCount}`)
console.log(`AGENT_HARNESS_PROOFS=${agentHarnessProofCount}`)
console.log(`MCP_HARNESS_PROOFS=${mcpHarnessProofCount}`)
console.log(`TOOL_RETRIEVAL_HARNESS_PROOFS=${toolRetrievalHarnessProofCount}`)

const elementPlusPath = resolve(root, 'src/styles/_element-plus.scss')
if (!existsSync(elementPlusPath)) {
  failures.push('src/styles/_element-plus.scss is missing')
} else {
  failures.push(
    ...validateElementPlusMappings(
      readNormalizedUtf8(elementPlusPath),
      'src/styles/_element-plus.scss',
    ),
  )
}

for (const relativePath of componentFiles) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) {
    if (relativePath.endsWith('glassWorkbench.ts')) {
      failures.push(`${relativePath} is missing; Task 2 type contracts unavailable`)
    } else if (relativePath.endsWith('PageHeader.vue')) {
      failures.push(`${relativePath} is missing; PageHeader component recipe unavailable`)
    } else if (relativePath.endsWith('WorkbenchPanel.vue')) {
      failures.push(`${relativePath} is missing; WorkbenchPanel component recipe unavailable`)
    } else if (relativePath.endsWith('FilterBar.vue')) {
      failures.push(`${relativePath} is missing; FilterBar component contract unavailable`)
    } else if (relativePath.endsWith('DataTableShell.vue')) {
      failures.push(`${relativePath} is missing; DataTableShell component contract unavailable`)
    } else if (relativePath.endsWith('WizardDialog.vue')) {
      failures.push(`${relativePath} is missing; WizardDialog progression contract unavailable`)
    } else {
      failures.push(`${relativePath} is missing`)
    }
    continue
  }
  const source = readNormalizedUtf8(absolutePath)
  if (relativePath.endsWith('glassWorkbench.ts')) {
    failures.push(...(await validateGlassWorkbench(source, relativePath)))
  } else if (relativePath.endsWith('PageHeader.vue')) {
    failures.push(...(await validatePageHeader(source, relativePath)))
  } else if (relativePath.endsWith('MetricStrip.vue')) {
    failures.push(...(await validateMetricStrip(source, relativePath)))
  } else if (relativePath.endsWith('WorkbenchPanel.vue')) {
    failures.push(...(await validateWorkbenchPanel(source, relativePath)))
  } else if (relativePath.endsWith('FilterBar.vue')) {
    failures.push(...(await validateFilterBar(source, relativePath)))
  } else if (relativePath.endsWith('DataTableShell.vue')) {
    failures.push(...(await validateDataTableShell(source, relativePath)))
  } else if (relativePath.endsWith('StatusTag.vue') && relativePath.includes('/common/')) {
    failures.push(...(await validateStatusTag(source, relativePath)))
  } else if (relativePath.endsWith('MetricIconBg.vue')) {
    failures.push(...(await validateMetricIconBg(source, relativePath)))
  } else if (relativePath.endsWith('CommonStatusTag.vue')) {
    failures.push(...(await validateCommonStatusTag(source, relativePath)))
  } else if (relativePath.endsWith('AppDialog.vue')) {
    failures.push(...(await validateAppDialog(source, relativePath)))
  } else if (relativePath.endsWith('WizardDialog.vue')) {
    failures.push(...(await validateWizardDialog(source, relativePath)))
  } else if (relativePath.endsWith('AppDrawer.vue')) {
    failures.push(...(await validateAppDrawer(source, relativePath)))
  } else if (relativePath.endsWith('.vue')) {
    failures.push(...(await validateCommonSfc(source, relativePath)))
  }
}

const toolListConsumerPath = resolve(root, 'src/views/tool/ToolList.vue')
if (existsSync(toolListConsumerPath)) {
  failures.push('src/views/tool/ToolList.vue must stay retired; generic Tool is not a product asset')
}

const knowledgeDetailConsumerPath = resolve(root, 'src/views/KnowledgeDetail.vue')
if (!existsSync(knowledgeDetailConsumerPath)) {
  failures.push('src/views/KnowledgeDetail.vue is missing; /knowledge/:code consumer contract unavailable')
} else {
  failures.push(
    ...(await validateKnowledgeDetailConsumer(
      readNormalizedUtf8(knowledgeDetailConsumerPath),
      'src/views/KnowledgeDetail.vue (/knowledge/:code)',
      { skipHeadPreservation: true },
    )),
  )
}

const agentEditConsumerPath = resolve(root, 'src/views/agent/AgentEdit.vue')
if (!existsSync(agentEditConsumerPath)) {
  failures.push('src/views/agent/AgentEdit.vue is missing; Agent form consumer contract unavailable')
} else {
  failures.push(
    ...(await validateAgentSupervisorWorkbenchConsumer(
      readNormalizedUtf8(agentEditConsumerPath),
      'src/views/agent/AgentEdit.vue (/agent/new/edit and /agent/:id/edit)',
    )),
  )
}

const mcpOnboardingConsumerPath = resolve(root, 'src/views/mcp/McpOnboarding.vue')
if (existsSync(mcpOnboardingConsumerPath)) {
  failures.push('src/views/mcp/McpOnboarding.vue must stay retired; onboarding now belongs to MCP publication detail')
}

const toolRetrievalTestConsumerPath = resolve(root, 'src/views/tool/ToolRetrievalTest.vue')
if (!existsSync(toolRetrievalTestConsumerPath)) {
  failures.push(
    'src/views/tool/ToolRetrievalTest.vue is missing; /tool/retrieval consumer contract unavailable',
  )
} else {
  failures.push(
    ...(await validateToolRetrievalTestConsumer(
      readNormalizedUtf8(toolRetrievalTestConsumerPath),
      'src/views/tool/ToolRetrievalTest.vue (/tool/retrieval)',
    )),
  )
}

if (failures.length > 0) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('ReachAI glass component contract is aligned.')
