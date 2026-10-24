import { createHash } from 'node:crypto'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { compileStyleAsync, parse } from '@vue/compiler-sfc'
import * as sass from 'sass'
import * as ts from 'typescript'

const root = process.cwd()
const failures = []
let appSidebarReachableClassValues = []

const FORBIDDEN_LEGACY_SPACING_TOKENS = [
  '--reachai-workbench-breadcrumb-height',
  '--reachai-workbench-title-gap',
  '--reachai-workbench-page-padding',
  '--reachai-workbench-title-padding',
]

const EXPECTED_THEME_IMPORTS = [
  "@use './tokens/brand';",
  "@use './tokens/semantic';",
  "@use './tokens/density';",
  "@use './tokens/layout';",
  "@use './glass';",
  "@use './element-plus';",
  "@use './overlays';",
  "@use './components';",
]

function read(relativePath) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) {
    failures.push(`${relativePath} is missing`)
    return ''
  }
  return readFileSync(absolutePath, 'utf8')
}

function expectIncludes(source, needle, message) {
  if (!source.includes(needle)) failures.push(message)
}

function forbiddenLegacySpacingTokenFailures(source, label) {
  return FORBIDDEN_LEGACY_SPACING_TOKENS.filter((token) => source.includes(token)).map(
    (token) => `${label} must not use deprecated spacing token ${token}`,
  )
}

function themeImportOrderFailure(source) {
  const statements = stripComments(source)
    .split(/\r?\n/)
    .map((statement) => statement.trim())
    .filter(Boolean)
    .slice(0, EXPECTED_THEME_IMPORTS.length)
  return JSON.stringify(statements) === JSON.stringify(EXPECTED_THEME_IMPORTS)
    ? null
    : `theme.scss first ${EXPECTED_THEME_IMPORTS.length} statements must be ${EXPECTED_THEME_IMPORTS.join(' -> ')}; got ${statements.join(' -> ')}`
}

function runFinalSpacingMutationProofs() {
  const cleanSemantic = ':root { --reachai-workbench-title-height: var(--layout-page-header-height-emphasis); }'
  if (forbiddenLegacySpacingTokenFailures(cleanSemantic, 'synthetic semantic').length !== 0) {
    failures.push('final spacing mutation baseline unexpectedly rejects retained visual tokens')
  }
  for (const token of FORBIDDEN_LEGACY_SPACING_TOKENS) {
    const mutated = `${cleanSemantic}\n:root { ${token}: 1px; }`
    const mutationFailures = forbiddenLegacySpacingTokenFailures(mutated, 'synthetic semantic')
    if (mutationFailures.length !== 1 || !mutationFailures[0].includes(token)) {
      failures.push(`final spacing mutation proof did not reject ${token}`)
    }
  }

  const validTheme = EXPECTED_THEME_IMPORTS.join('\n')
  if (themeImportOrderFailure(validTheme) !== null) {
    failures.push('theme import mutation baseline unexpectedly rejects the canonical six imports')
  }
  for (const [name, mutatedTheme] of [
    ['missing layout import', validTheme.replace("@use './tokens/layout';\n", '')],
    [
      'misordered layout import',
      validTheme.replace(
        "@use './tokens/layout';\n@use './glass';",
        "@use './glass';\n@use './tokens/layout';",
      ),
    ],
  ]) {
    if (themeImportOrderFailure(mutatedTheme) === null) {
      failures.push(`theme import mutation proof did not reject ${name}`)
    }
  }
}

function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
}

function stripVueComments(source) {
  return source.replace(/<!--[\s\S]*?-->/g, '')
}

function extractVueBlocks(source, tagName) {
  const blocks = []
  const pattern = new RegExp(`<${tagName}\\b[^>]*>([\\s\\S]*?)<\\/${tagName}>`, 'gi')
  for (const match of source.matchAll(pattern)) blocks.push(match[1])
  return blocks
}

function extractSingleVueBlock(source, tagName, label) {
  const blocks = extractVueBlocks(source, tagName)
  if (blocks.length !== 1) {
    failures.push(`${label} must contain exactly one <${tagName}> block; got ${blocks.length}`)
  }
  return blocks[0] ?? ''
}

function extractVueTemplateBlock(source, label) {
  const withoutComments = stripVueComments(source)
  const tagPattern = /<\/?template\b[^>]*>/gi
  const blocks = []
  let depth = 0
  let contentStart = -1
  let match = null
  while ((match = tagPattern.exec(withoutComments)) !== null) {
    const closing = match[0].startsWith('</')
    if (!closing) {
      if (depth === 0) contentStart = tagPattern.lastIndex
      depth += 1
    } else if (depth === 0) {
      failures.push(`${label} contains an unmatched </template>`)
    } else {
      depth -= 1
      if (depth === 0) blocks.push(withoutComments.slice(contentStart, match.index))
    }
  }
  if (depth !== 0) failures.push(`${label} contains an unclosed <template>`)
  if (blocks.length !== 1) {
    failures.push(`${label} must contain exactly one root <template> block; got ${blocks.length}`)
  }
  return blocks[0] ?? ''
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function isTopLevelStatementStart(source, candidate) {
  let depth = 0
  let boundary = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < candidate; index += 1) {
    const character = source[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }

    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '{') {
      depth += 1
    } else if (character === '}') {
      depth -= 1
      if (depth === 0) boundary = index + 1
    } else if (character === ';' && depth === 0) {
      boundary = index + 1
    }
  }
  return depth === 0 && source.slice(boundary, candidate).trim() === ''
}

function extractBalancedBlock(source, marker, label) {
  let start = -1
  let searchFrom = 0
  while (start === -1) {
    const candidate = source.indexOf(marker, searchFrom)
    if (candidate === -1) break
    if (isTopLevelStatementStart(source, candidate)) {
      start = candidate
    } else {
      searchFrom = candidate + marker.length
    }
  }
  if (start === -1) {
    failures.push(`${label} marker ${marker} is missing`)
    return null
  }

  const markerBraceOffset = marker.indexOf('{')
  const openBrace =
    markerBraceOffset === -1
      ? source.indexOf('{', start + marker.length)
      : start + markerBraceOffset
  if (openBrace === -1) {
    failures.push(`${label} has no opening brace`)
    return null
  }

  let depth = 0
  let quote = null
  let escaped = false
  for (let index = openBrace; index < source.length; index += 1) {
    const character = source[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }

    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '{') {
      depth += 1
    } else if (character === '}') {
      depth -= 1
      if (depth === 0) {
        return {
          start,
          end: index + 1,
          header: source.slice(start, openBrace).trim(),
          body: source.slice(openBrace + 1, index),
        }
      }
    }
  }

  failures.push(`${label} has unbalanced braces`)
  return null
}

function declarationValues(source, property) {
  const pattern = new RegExp(
    `(?:^|[;{])\\s*${escapeRegExp(property)}\\s*:\\s*([^;{}]+?)\\s*;`,
    'g',
  )
  return [...source.matchAll(pattern)].map((match) => match[1].trim())
}

function expectExactDeclaration(source, property, value, label) {
  const values = declarationValues(source, property)
  if (values.length !== 1 || values[0] !== value) {
    failures.push(`${label} must declare ${property}: ${value}; exactly once`)
  }
}

function expectNoDeclaration(source, property, label) {
  if (declarationValues(source, property).length > 0) {
    failures.push(`${label} must not declare ${property}`)
  }
}

function expectExactDeclarations(source, declarations, label) {
  if (source === null) return
  for (const [property, value] of Object.entries(declarations)) {
    expectExactDeclaration(source, property, value, label)
  }
}

function normalizeWhitespace(value) {
  return value.replace(/\s+/g, ' ').trim()
}

function normalizedSha256(value) {
  const normalized = normalizeWhitespace(value)
  return createHash('sha256').update(normalized).digest('hex')
}

function balancedBlockSource(block) {
  return block === null ? null : `${block.header} {${block.body}}`
}

function enumerateTopLevelRules(source, label) {
  const stripped = stripComments(source)
  const rules = []
  const statements = []
  let statementStart = 0
  let ruleOpenBrace = -1
  let ruleHeader = null
  let braceDepth = 0
  let parenthesesDepth = 0
  let quote = null
  let escaped = false

  for (let index = 0; index < stripped.length; index += 1) {
    const character = stripped[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }

    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '#' && stripped[index + 1] === '{') {
      let interpolationDepth = 1
      let interpolationQuote = null
      let interpolationEscaped = false
      index += 2
      for (; index < stripped.length; index += 1) {
        const interpolationCharacter = stripped[index]
        if (interpolationQuote !== null) {
          if (interpolationEscaped) {
            interpolationEscaped = false
          } else if (interpolationCharacter === '\\') {
            interpolationEscaped = true
          } else if (interpolationCharacter === interpolationQuote) {
            interpolationQuote = null
          }
        } else if (interpolationCharacter === "'" || interpolationCharacter === '"') {
          interpolationQuote = interpolationCharacter
        } else if (interpolationCharacter === '{') {
          interpolationDepth += 1
        } else if (interpolationCharacter === '}') {
          interpolationDepth -= 1
          if (interpolationDepth === 0) break
        }
      }
      if (interpolationDepth !== 0) {
        failures.push(`${label} has an unbalanced Sass interpolation`)
      }
    } else if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      if (parenthesesDepth === 0) {
        failures.push(`${label} has an unmatched closing parenthesis`)
      } else {
        parenthesesDepth -= 1
      }
    } else if (character === '{' && parenthesesDepth === 0) {
      if (braceDepth === 0) {
        const header = normalizeWhitespace(stripped.slice(statementStart, index))
        if (parenthesesDepth !== 0) {
          failures.push(`${label} rule header ${header || '(empty)'} has unbalanced parentheses`)
        }
        if (!header) {
          failures.push(`${label} contains a top-level rule with an empty header`)
        }
        ruleHeader = header
        ruleOpenBrace = index
      }
      braceDepth += 1
    } else if (character === '}' && parenthesesDepth === 0) {
      if (braceDepth === 0) {
        failures.push(`${label} has an unmatched closing brace`)
        continue
      }
      braceDepth -= 1
      if (braceDepth === 0) {
        rules.push({
          header: ruleHeader,
          body: normalizeWhitespace(stripped.slice(ruleOpenBrace + 1, index)),
        })
        statementStart = index + 1
        ruleHeader = null
        ruleOpenBrace = -1
      }
    } else if (character === ';' && braceDepth === 0 && parenthesesDepth === 0) {
      const statement = normalizeWhitespace(stripped.slice(statementStart, index))
      if (statement) statements.push(statement)
      statementStart = index + 1
    }
  }

  if (quote !== null) failures.push(`${label} has an unterminated quote`)
  if (parenthesesDepth !== 0) failures.push(`${label} has unbalanced parentheses`)
  if (braceDepth !== 0) failures.push(`${label} has unbalanced braces`)

  return { rules, statements }
}

function splitSelectorBranches(selector, label) {
  const branches = []
  let start = 0
  let parenthesesDepth = 0
  let bracketDepth = 0
  let quote = null
  let escaped = false

  for (let index = 0; index < selector.length; index += 1) {
    const character = selector[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }

    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      parenthesesDepth -= 1
    } else if (character === '[') {
      bracketDepth += 1
    } else if (character === ']') {
      bracketDepth -= 1
    } else if (character === ',' && parenthesesDepth === 0 && bracketDepth === 0) {
      const branch = normalizeWhitespace(selector.slice(start, index))
      if (branch) branches.push(branch)
      start = index + 1
    }
  }

  if (quote !== null || parenthesesDepth !== 0 || bracketDepth !== 0) {
    failures.push(`${label} selector ${selector} is structurally unbalanced`)
  }
  const finalBranch = normalizeWhitespace(selector.slice(start))
  if (finalBranch) branches.push(finalBranch)
  if (branches.length === 0) failures.push(`${label} selector ${selector} has no branches`)
  return branches
}

function enumerateCompiledCssRules(source, label) {
  const { rules } = enumerateTopLevelRules(source, label)
  const compiledRules = []
  for (const rule of rules) {
    if (rule.header.startsWith('@')) {
      compiledRules.push(
        ...enumerateCompiledCssRules(rule.body, `${label} ${rule.header}`),
      )
    } else {
      compiledRules.push({ selector: rule.header, body: rule.body })
    }
  }
  return compiledRules
}

function directDeclarationEntries(source, label) {
  const { statements } = enumerateTopLevelRules(source, label)
  const declarations = []
  for (const statement of statements) {
    const match = /^([a-zA-Z-][a-zA-Z0-9-]*)\s*:\s*(.+)$/.exec(statement)
    if (match !== null) {
      const property = match[1].startsWith('--') ? match[1] : match[1].toLowerCase()
      declarations.push({ property, value: normalizeWhitespace(match[2]) })
    }
  }
  return declarations
}

function expectExactDirectDeclaration(declarations, property, value, label) {
  const values = declarations
    .filter((declaration) => declaration.property === property)
    .map((declaration) => declaration.value)
  if (values.length !== 1 || values[0] !== normalizeWhitespace(value)) {
    failures.push(`${label} must directly declare ${property}: ${value}; exactly once`)
  }
}

function isDarkThemeSelectorBranch(selector) {
  return /^\[data-theme\s*=\s*(['"])dark\1\]/.test(normalizeWhitespace(selector))
}

function compiledSelectorIsDark(selector) {
  return selectorCanMatchDark(selector, 'compiled dark selector')
}

function splitSelectorCompounds(selector, label) {
  const compounds = []
  let start = 0
  let parenthesesDepth = 0
  let bracketDepth = 0
  let quote = null
  let escaped = false

  const pushCompound = (end) => {
    const compound = normalizeWhitespace(selector.slice(start, end))
    if (compound) compounds.push(compound)
  }

  for (let index = 0; index < selector.length; index += 1) {
    const character = selector[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
      continue
    }
    if (character === '(') {
      parenthesesDepth += 1
      continue
    }
    if (character === ')') {
      parenthesesDepth -= 1
      continue
    }
    if (character === '[') {
      bracketDepth += 1
      continue
    }
    if (character === ']') {
      bracketDepth -= 1
      continue
    }
    if (parenthesesDepth !== 0 || bracketDepth !== 0) continue

    const columnCombinator = character === '|' && selector[index + 1] === '|'
    if (/\s/.test(character) || /[>+~]/.test(character) || columnCombinator) {
      pushCompound(index)
      if (columnCombinator) index += 1
      start = index + 1
    }
  }

  if (quote !== null || parenthesesDepth !== 0 || bracketDepth !== 0) {
    failures.push(`${label} selector ${selector} has unbalanced compound syntax`)
  }
  pushCompound(selector.length)
  return compounds
}

function decodeCssEscapes(value) {
  return value.replace(/\\([0-9a-fA-F]{1,6})(?:\r\n|[\t\n\f\r ])?/g, (_, hex) =>
    String.fromCodePoint(Number.parseInt(hex, 16)),
  ).replace(/\\([^\r\n\f])/g, '$1')
}

function parseAttributeSelector(attribute) {
  const match = /^([a-zA-Z_-][a-zA-Z0-9_-]*)\s*(~=|\|=|\^=|\$=|\*=|=)\s*(?:"((?:\\.|[^"])*)"|'((?:\\.|[^'])*)'|([^\s]+))\s*([is])?\s*$/i.exec(
    attribute.trim(),
  )
  if (match === null) return null

  const [, rawName, operator, doubleQuoted, singleQuoted, unquoted, flag] = match
  const rawValue = doubleQuoted ?? singleQuoted ?? unquoted ?? ''
  const caseInsensitive = flag?.toLowerCase() === 'i'
  return {
    name: decodeCssEscapes(rawName).toLowerCase(),
    operator,
    value: decodeCssEscapes(rawValue),
    caseInsensitive,
  }
}

function attributeSelectorMatchesValue(attribute, expectedName, actualValue) {
  const parsed = parseAttributeSelector(attribute)
  if (parsed === null || parsed.name !== expectedName) return false

  const normalize = (value) => parsed.caseInsensitive ? value.toLowerCase() : value
  const actual = normalize(actualValue)
  const expected = normalize(parsed.value)
  if (parsed.operator === '=') return actual === expected
  if (parsed.operator === '~=') return actual.split(/\s+/).includes(expected)
  if (parsed.operator === '|=') return actual === expected || actual.startsWith(`${expected}-`)
  if (parsed.operator === '^=') return actual.startsWith(expected)
  if (parsed.operator === '$=') return actual.endsWith(expected)
  return parsed.operator === '*=' && actual.includes(expected)
}

function classAttributeTargetsAppSidebar(attribute) {
  return appSidebarReachableClassValues.some((classValue) =>
    attributeSelectorMatchesValue(attribute, 'class', classValue),
  )
}

function cssClassTokenAt(source, dotIndex) {
  const match = /^((?:\\[0-9a-fA-F]{1,6}(?:\r\n|[\t\n\f\r ])?|\\[^\r\n\f]|[a-zA-Z0-9_-])+)/.exec(
    source.slice(dotIndex + 1),
  )
  return match === null ? null : decodeCssEscapes(match[1])
}

function unwrapTypeScriptExpression(expression) {
  let current = expression
  while (
    ts.isParenthesizedExpression(current) ||
    ts.isAsExpression(current) ||
    ts.isTypeAssertionExpression(current) ||
    ts.isNonNullExpression(current) ||
    ts.isSatisfiesExpression(current)
  ) {
    current = current.expression
  }
  return current
}

function staticObjectPropertyName(name) {
  if (
    ts.isIdentifier(name) ||
    ts.isStringLiteral(name) ||
    ts.isNumericLiteral(name) ||
    ts.isNoSubstitutionTemplateLiteral(name)
  ) {
    return name.text
  }
  if (ts.isComputedPropertyName(name)) {
    const expression = unwrapTypeScriptExpression(name.expression)
    if (
      ts.isStringLiteral(expression) ||
      ts.isNumericLiteral(expression) ||
      ts.isNoSubstitutionTemplateLiteral(expression)
    ) {
      return expression.text
    }
  }
  return null
}

function parseObjectClassBindingKeys(expression, label) {
  const sourceFile = ts.createSourceFile(
    'app-sidebar-class-binding.ts',
    `const __classBinding = (${expression})`,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  )
  const diagnostics = sourceFile.parseDiagnostics ?? []
  if (diagnostics.length > 0) {
    failures.push(
      `${label} is not valid TypeScript: ${diagnostics.map((diagnostic) => diagnostic.messageText).join('; ')}`,
    )
    return null
  }

  const declaration = sourceFile.statements[0]?.declarationList?.declarations?.[0]
  const initializer = declaration?.initializer === undefined
    ? null
    : unwrapTypeScriptExpression(declaration.initializer)
  if (initializer === null || !ts.isObjectLiteralExpression(initializer)) {
    failures.push(`${label} must be a statically enumerable object-form :class binding`)
    return null
  }

  const keys = []
  for (const property of initializer.properties) {
    if (ts.isSpreadAssignment(property)) {
      failures.push(`${label} must not contain spread properties`)
      return null
    }
    if (!ts.isPropertyAssignment(property) && !ts.isShorthandPropertyAssignment(property)) {
      failures.push(`${label} contains an unsupported object member ${property.getText(sourceFile)}`)
      return null
    }
    const key = staticObjectPropertyName(property.name)
    if (key === null) {
      failures.push(`${label} contains a non-static class key ${property.name.getText(sourceFile)}`)
      return null
    }
    const tokens = key.split(/\s+/).filter(Boolean)
    if (tokens.length === 0) {
      failures.push(`${label} contains an empty class key`)
      return null
    }
    keys.push(tokens)
  }
  return keys
}

function extractRootClassBindingExpressions(attributes, label) {
  const markerCount = [
    ...attributes.matchAll(/(?:^|\s)(?::class|v-bind:class)\b/g),
  ].length
  const expressions = [
    ...attributes.matchAll(
      /(?:^|\s)(?::class|v-bind:class)\s*=\s*(["'])((?:\\.|(?!\1)[\s\S])*)\1/g,
    ),
  ].map((match) => match[2])
  if (markerCount !== expressions.length) {
    failures.push(`${label} contains an unquoted or unparseable dynamic class binding`)
  }
  if (/(?:^|\s)v-bind\s*=|(?:^|\s)(?::|v-bind:)\[/.test(attributes)) {
    failures.push(`${label} contains a dynamic attribute binding that may alter class ownership`)
  }
  return expressions
}

function enumerateReachableClassValues(staticTokens, dynamicClassKeys, label) {
  if (dynamicClassKeys.length > 12) {
    failures.push(
      `${label} has ${dynamicClassKeys.length} dynamic class keys; maximum statically enumerable keys is 12`,
    )
    return [staticTokens.join(' ')]
  }

  let states = [staticTokens]
  for (const keyTokens of dynamicClassKeys) {
    states = states.flatMap((tokens) => [tokens, [...tokens, ...keyTokens]])
  }
  return [...new Set(states.map((tokens) => [...new Set(tokens)].join(' ')))]
}

function findMatchingBracket(source, openIndex) {
  let quote = null
  let escaped = false
  for (let index = openIndex + 1; index < source.length; index += 1) {
    const character = source[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
    } else if (character === "'" || character === '"') {
      quote = character
    } else if (character === ']') {
      return index
    }
  }
  return -1
}

function compoundHasDirectAppSidebar(compound) {
  let parenthesesDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < compound.length; index += 1) {
    const character = compound[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      parenthesesDepth -= 1
    } else if (character === '[' && parenthesesDepth === 0) {
      const closeIndex = findMatchingBracket(compound, index)
      if (closeIndex === -1) return false
      if (classAttributeTargetsAppSidebar(compound.slice(index + 1, closeIndex))) {
        return true
      }
      index = closeIndex
    } else if (parenthesesDepth === 0 && character === '.' && cssClassTokenAt(compound, index) === 'app-sidebar') {
      return true
    }
  }
  return false
}

function compoundHasPositiveDarkCondition(compound) {
  let parenthesesDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < compound.length; index += 1) {
    const character = compound[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      parenthesesDepth -= 1
    } else if (character === '[' && parenthesesDepth === 0) {
      const closeIndex = findMatchingBracket(compound, index)
      if (closeIndex === -1) return false
      const attribute = compound.slice(index + 1, closeIndex)
      if (
        attributeSelectorMatchesValue(attribute, 'data-theme', 'dark') ||
        attributeSelectorMatchesValue(attribute, 'class', 'dark')
      ) {
        return true
      }
      index = closeIndex
    } else if (
      parenthesesDepth === 0 &&
      character === '.' &&
      cssClassTokenAt(compound, index) === 'dark'
    ) {
      return true
    }
  }
  return false
}

function topLevelPseudoElements(compound) {
  const pseudoElements = []
  let parenthesesDepth = 0
  let bracketDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < compound.length; index += 1) {
    const character = compound[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      parenthesesDepth -= 1
    } else if (character === '[') {
      bracketDepth += 1
    } else if (character === ']') {
      bracketDepth -= 1
    } else if (
      parenthesesDepth === 0 &&
      bracketDepth === 0 &&
      compound.startsWith('::', index)
    ) {
      const match = /^::([a-zA-Z-]+)/.exec(compound.slice(index))
      if (match !== null) pseudoElements.push(match[1])
    }
  }
  return pseudoElements
}

function findMatchingParenthesis(source, openIndex, label) {
  let depth = 0
  let quote = null
  let escaped = false
  for (let index = openIndex; index < source.length; index += 1) {
    const character = source[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
    } else if (character === '(') {
      depth += 1
    } else if (character === ')') {
      depth -= 1
      if (depth === 0) return index
    }
  }
  failures.push(`${label} selector function has an unmatched parenthesis in ${source}`)
  return source.length - 1
}

function topLevelIsWhereAlternatives(compound, label) {
  const alternatives = []
  let parenthesesDepth = 0
  let bracketDepth = 0
  let quote = null
  let escaped = false
  for (let index = 0; index < compound.length; index += 1) {
    const character = compound[index]
    if (quote !== null) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
      continue
    }
    if (character === '[') {
      bracketDepth += 1
      continue
    }
    if (character === ']') {
      bracketDepth -= 1
      continue
    }
    if (bracketDepth !== 0) continue

    if (parenthesesDepth === 0) {
      const pseudoMatch = /^:(is|where)\(/.exec(compound.slice(index))
      if (pseudoMatch !== null) {
        const openIndex = index + pseudoMatch[0].length - 1
        const closeIndex = findMatchingParenthesis(compound, openIndex, label)
        alternatives.push(
          splitSelectorBranches(compound.slice(openIndex + 1, closeIndex), label),
        )
        index = closeIndex
        continue
      }
    }

    if (character === '(') {
      parenthesesDepth += 1
    } else if (character === ')') {
      parenthesesDepth -= 1
    }
  }
  return alternatives
}

function selectorCanMatchDark(selector, label, recursionDepth = 0) {
  if (recursionDepth > 32) {
    failures.push(`${label} selector nesting exceeds 32 levels: ${selector}`)
    return false
  }
  for (const compound of splitSelectorCompounds(selector, label)) {
    if (compoundHasPositiveDarkCondition(compound)) return true
    for (const alternatives of topLevelIsWhereAlternatives(compound, label)) {
      if (
        alternatives.some((alternative) =>
          selectorCanMatchDark(alternative, label, recursionDepth + 1),
        )
      ) {
        return true
      }
    }
  }
  return false
}

function classifyCompiledCompound(compound, label, recursionDepth) {
  const kinds = new Set()
  const nestedKinds = new Set()
  for (const alternatives of topLevelIsWhereAlternatives(compound, label)) {
    for (const alternative of alternatives) {
      for (const kind of classifyCompiledSelectorBranch(
        alternative,
        label,
        recursionDepth + 1,
      )) {
        nestedKinds.add(kind)
      }
    }
  }

  const directRoot = compoundHasDirectAppSidebar(compound)
  const pseudoElements = topLevelPseudoElements(compound)
  const ambientPseudo = pseudoElements.some((pseudo) => pseudo === 'before' || pseudo === 'after')
  const unsupportedPseudo = pseudoElements.some(
    (pseudo) => pseudo !== 'before' && pseudo !== 'after',
  )
  if ((directRoot || nestedKinds.has('root')) && !unsupportedPseudo) {
    kinds.add(ambientPseudo ? 'ambient-pseudo' : 'root')
  }
  if (nestedKinds.has('ambient-pseudo') && !unsupportedPseudo) kinds.add('ambient-pseudo')
  if (nestedKinds.has('descendant')) kinds.add('descendant')
  return kinds
}

function classifyCompiledSelectorBranch(selector, label, recursionDepth = 0) {
  if (recursionDepth > 32) {
    failures.push(`${label} selector nesting exceeds 32 levels: ${selector}`)
    return new Set()
  }
  const compounds = splitSelectorCompounds(selector, label)
  const kinds = new Set()
  compounds.forEach((compound, index) => {
    const compoundKinds = classifyCompiledCompound(compound, label, recursionDepth)
    if (index < compounds.length - 1) {
      if (compoundKinds.size > 0) kinds.add('descendant')
    } else {
      for (const kind of compoundKinds) kinds.add(kind)
    }
  })
  return kinds
}

function formatCompilerError(error) {
  if (typeof error === 'string') return error
  if (error instanceof Error) return error.message
  if (error && typeof error.message === 'string') return error.message
  return JSON.stringify(error)
}

// AppSidebar can be targeted by any authored global or scoped style. The closed
// candidate set is every CSS, SCSS, Sass, and Vue source under src; generated
// output and dependencies live outside src and are intentionally excluded.
function collectAppSidebarOwnershipSourceFiles(directory) {
  const files = []
  const entries = readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    left.name.localeCompare(right.name),
  )
  for (const entry of entries) {
    const absolutePath = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      files.push(...collectAppSidebarOwnershipSourceFiles(absolutePath))
      continue
    }
    const extension = /\.(css|scss|sass|vue)$/.exec(entry.name)?.[1]
    if (extension === undefined) continue
    const source = readFileSync(absolutePath, 'utf8')
    files.push({
      absolutePath,
      label: relative(root, absolutePath).replaceAll('\\', '/'),
      source,
      type: extension,
    })
  }
  return files
}

async function compileAppSidebarOwnershipDocuments() {
  const documents = []
  for (const file of collectAppSidebarOwnershipSourceFiles(resolve(root, 'src'))) {
    if (file.type === 'css') {
      documents.push({ label: file.label, css: file.source })
      continue
    }

    if (file.type === 'scss' || file.type === 'sass') {
      try {
        documents.push({
          label: file.label,
          css: sass.compile(file.absolutePath, { style: 'expanded' }).css,
        })
      } catch (error) {
        failures.push(`${file.label} Sass compile failed: ${formatCompilerError(error)}`)
      }
      continue
    }

    const parsed = parse(file.source, { filename: file.absolutePath })
    for (const error of parsed.errors) {
      failures.push(`${file.label} Vue parse failed: ${formatCompilerError(error)}`)
    }
    if (parsed.errors.length > 0) continue

    for (const [index, style] of parsed.descriptor.styles.entries()) {
      try {
        const compiled = await compileStyleAsync({
          source: style.content,
          filename: file.absolutePath,
          id: `data-v-glass-contract-${normalizedSha256(`${file.label}:${index}`).slice(0, 8)}`,
          scoped: style.scoped,
          preprocessLang: style.lang,
          modules: Boolean(style.module),
        })
        for (const error of compiled.errors) {
          failures.push(
            `${file.label} <style ${index + 1}> compile failed: ${formatCompilerError(error)}`,
          )
        }
        if (compiled.errors.length === 0) {
          documents.push({ label: `${file.label} <style ${index + 1}>`, css: compiled.code })
        }
      } catch (error) {
        failures.push(
          `${file.label} <style ${index + 1}> compile failed: ${formatCompilerError(error)}`,
        )
      }
    }
  }
  return documents
}

function expectClosedWorldTopLevelRules(rules, allowedHeaders, label) {
  const allowed = new Set(allowedHeaders)
  const counts = new Map(allowedHeaders.map((header) => [header, 0]))

  for (const { header } of rules) {
    if (!allowed.has(header)) {
      failures.push(`${label} contains unexpected top-level rule ${header}`)
      continue
    }
    counts.set(header, counts.get(header) + 1)
  }

  for (const header of allowedHeaders) {
    const count = counts.get(header)
    if (count !== 1) {
      failures.push(`${label} top-level rule ${header} must appear exactly once; got ${count}`)
    }
  }
}

function expectExactTopLevelStatements(statements, expectedStatements, label) {
  if (statements.length !== expectedStatements.length) {
    failures.push(
      `${label} must contain exactly ${expectedStatements.length} top-level statements; got ${statements.length}: ${statements.join(' -> ') || '(none)'}`,
    )
    return
  }

  if (JSON.stringify(statements) !== JSON.stringify(expectedStatements)) {
    failures.push(
      `${label} top-level statements must be ${expectedStatements.join(' -> ')}; got ${statements.join(' -> ')}`,
    )
  }
}

function topLevelRuleBody(rules, header) {
  return rules.find((rule) => rule.header === header)?.body ?? null
}

function expectExactDeclarationOracle(source, declarations, label) {
  if (source === null) return

  if (/[{}]/.test(source)) {
    failures.push(`${label} must contain declarations only, without nested rules`)
  }

  const actualProperties = source
    .split(';')
    .map((statement) => statement.trim())
    .filter(Boolean)
    .map((statement) => {
      const match = /^([a-zA-Z-][a-zA-Z0-9-]*)\s*:/.exec(statement)
      if (match === null) {
        failures.push(`${label} contains unexpected non-declaration statement ${statement}`)
        return statement
      }
      return match[1]
    })
  const expectedProperties = Object.keys(declarations)
  const sortedActual = [...actualProperties].sort()
  const sortedExpected = [...expectedProperties].sort()

  if (JSON.stringify(sortedActual) !== JSON.stringify(sortedExpected)) {
    failures.push(
      `${label} declaration properties must be exactly ${sortedExpected.join(', ')}; got ${sortedActual.join(', ')}`,
    )
  }

  expectExactDeclarations(source, declarations, label)
}

function expectExactGlassSelectors(block, expected, label) {
  if (block === null) return
  const actual = [...block.header.matchAll(/\.glass-surface-[a-z-]+/g)].map(
    (match) => match[0],
  )
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    failures.push(`${label} must contain only ${expected.join(', ')}; got ${actual.join(', ')}`)
  }
}

function expectSassCompiles(relativePath) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) return
  try {
    sass.compile(absolutePath, { style: 'compressed' })
  } catch (error) {
    failures.push(`${relativePath} does not compile: ${error.message}`)
  }
}

const brand = stripComments(read('src/styles/tokens/_brand.scss'))
const brandModes = {
  'tech-purple': {
    '--brand-primary': '#6366f1',
    '--brand-hover': '#8b5cf6',
    '--brand-active': '#4f46e5',
    '--brand-disabled': '#a5b4fc',
    '--brand-selected': '#ede9fe',
    '--brand-primary-rgb': '99 102 241',
    '--brand-hover-rgb': '139 92 246',
    '--brand-active-rgb': '79 70 229',
    '--brand-selected-rgb': '237 233 254',
    '--theme-wallpaper-image': "url('/theme-wallpapers/lavender-light.svg')",
    '--theme-wallpaper-position': 'center',
    '--wallpaper-fallback': '#eeebf7',
    '--theme-header-artwork': "url('/page-header-materials/tech-purple.webp')",
    '--wallpaper-ink': '#24213b',
    '--wallpaper-ink-muted': '#514c68',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.42',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'metro-green': {
    '--brand-primary': '#0b7a59',
    '--brand-hover': '#2d9375',
    '--brand-active': '#096247',
    '--brand-disabled': '#71c6ac',
    '--brand-selected': '#e3f7f2',
    '--brand-primary-rgb': '11 122 89',
    '--brand-hover-rgb': '45 147 117',
    '--brand-active-rgb': '9 98 71',
    '--brand-selected-rgb': '227 247 242',
    '--theme-wallpaper-image': "url('/theme-wallpapers/sage-folds.webp')",
    '--theme-wallpaper-position': 'center 58%',
    '--wallpaper-fallback': '#d8d8c9',
    '--theme-header-artwork': "url('/page-header-materials/metro-green.webp')",
    '--wallpaper-ink': '#172033',
    '--wallpaper-ink-muted': '#3e4a5c',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.42',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'aurora-cyan': {
    '--brand-primary': '#0891b2',
    '--brand-hover': '#22d3ee',
    '--brand-active': '#0e7490',
    '--brand-disabled': '#a5f3fc',
    '--brand-selected': '#ecfeff',
    '--brand-primary-rgb': '8 145 178',
    '--brand-hover-rgb': '34 211 238',
    '--brand-active-rgb': '14 116 144',
    '--brand-selected-rgb': '236 254 255',
    '--theme-wallpaper-image': "url('/theme-wallpapers/ice-blue.webp')",
    '--theme-wallpaper-position': 'center 42%',
    '--wallpaper-fallback': '#e4edf8',
    '--theme-header-artwork': "url('/page-header-materials/aurora-cyan.webp')",
    '--wallpaper-ink': '#172033',
    '--wallpaper-ink-muted': '#3e4a5c',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.42',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'nebula-violet': {
    '--brand-primary': '#526171',
    '--brand-hover': '#657487',
    '--brand-active': '#3d4b5c',
    '--brand-disabled': '#b0bac5',
    '--brand-selected': '#e7ecf1',
    '--brand-primary-rgb': '82 97 113',
    '--brand-hover-rgb': '101 116 135',
    '--brand-active-rgb': '61 75 92',
    '--brand-selected-rgb': '231 236 241',
    '--theme-wallpaper-image': "url('/theme-wallpapers/silver-light.svg')",
    '--theme-wallpaper-position': 'center',
    '--wallpaper-fallback': '#e9edf0',
    '--theme-header-artwork': "url('/page-header-materials/nebula-violet.webp')",
    '--theme-header-artwork-filter': 'grayscale(1)',
    '--wallpaper-ink': '#26313b',
    '--wallpaper-ink-muted': '#4c5966',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.38',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'coral-rose': {
    '--brand-primary': '#bd533b',
    '--brand-hover': '#b94b36',
    '--brand-active': '#a0402d',
    '--brand-disabled': '#eab19e',
    '--brand-selected': '#fbe5dc',
    '--brand-primary-rgb': '189 83 59',
    '--brand-hover-rgb': '185 75 54',
    '--brand-active-rgb': '160 64 45',
    '--brand-selected-rgb': '251 229 220',
    '--theme-wallpaper-image': "url('/theme-wallpapers/coral-light.svg')",
    '--theme-wallpaper-position': 'center',
    '--wallpaper-fallback': '#f8e8df',
    '--theme-header-artwork': "url('/page-header-materials/coral-rose.webp')",
    '--wallpaper-ink': '#3e2b26',
    '--wallpaper-ink-muted': '#6a5047',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.4',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'solar-gold': {
    '--brand-primary': '#b45309',
    '--brand-hover': '#f59e0b',
    '--brand-active': '#92400e',
    '--brand-disabled': '#fcd34d',
    '--brand-selected': '#fef3c7',
    '--brand-primary-rgb': '180 83 9',
    '--brand-hover-rgb': '245 158 11',
    '--brand-active-rgb': '146 64 14',
    '--brand-selected-rgb': '254 243 199',
    '--theme-wallpaper-image': "url('/theme-wallpapers/gold-curves.webp')",
    '--theme-wallpaper-position': 'center 48%',
    '--wallpaper-fallback': '#f3d692',
    '--theme-header-artwork': "url('/page-header-materials/solar-gold.webp')",
    '--wallpaper-ink': '#172033',
    '--wallpaper-ink-muted': '#3e4a5c',
    '--wallpaper-ink-shadow': 'none',
    '--frost-veil-alpha': '0.42',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
  'deep-ocean': {
    '--brand-primary': '#1d4ed8',
    '--brand-hover': '#06b6d4',
    '--brand-active': '#1e40af',
    '--brand-disabled': '#93c5fd',
    '--brand-selected': '#dbeafe',
    '--brand-primary-rgb': '29 78 216',
    '--brand-hover-rgb': '6 182 212',
    '--brand-active-rgb': '30 64 175',
    '--brand-selected-rgb': '219 234 254',
    '--theme-wallpaper-image': "url('/theme-wallpapers/deep-blue.webp')",
    '--theme-wallpaper-position': 'center 52%',
    '--wallpaper-fallback': '#111a35',
    '--theme-header-artwork': "url('/page-header-materials/deep-ocean.webp')",
    '--wallpaper-ink': '#f7f9ff',
    '--wallpaper-ink-muted': '#dce4f0',
    '--wallpaper-ink-shadow': '0 1px 4px rgb(0 0 0 / 0.4)',
    '--frost-veil-alpha': '0.62',
    '--page-header-material': 'var(--surface-page-background)',
    '--page-header-material-position': 'var(--theme-wallpaper-position)',
  },
}

const brandRuleOracles = [
  {
    selector: ":root, [data-brand='tech-purple']",
    declarations: brandModes['tech-purple'],
  },
  ...Object.entries(brandModes)
    .filter(([mode]) => mode !== 'tech-purple')
    .map(([mode, declarations]) => ({
      selector: `[data-brand='${mode}']`,
      declarations,
    })),
  {
    selector: ':root, [data-brand]',
    declarations: {
      '--brand-selected-bg': 'var(--brand-selected)',
      '--brand-primary-gradient':
        'linear-gradient(135deg, var(--brand-primary), var(--brand-hover))',
    },
  },  // 深色主题下的选中底（主色 24% 混入深色不透明底）
  {
    selector: "[data-theme='dark'], [data-theme='dark'][data-brand='tech-purple']",
    declarations: { '--brand-selected': '#2e3156', '--brand-selected-rgb': '46 49 86' },
  },
  {
    selector: "[data-theme='dark']:is(:not([data-brand]), [data-brand='tech-purple'])",
    declarations: {
      '--theme-wallpaper-image': "url('/theme-wallpapers/lavender-dark.svg')",
      '--wallpaper-fallback': '#1b192b',
      '--wallpaper-ink': '#f0eef9',
      '--wallpaper-ink-muted': '#c9c4dd',
    },
  },
  {
    selector: "[data-theme='dark'][data-brand='metro-green']",
    declarations: { '--brand-selected': '#193631', '--brand-selected-rgb': '25 54 49' },
  },
  {
    selector: "[data-theme='dark'][data-brand='aurora-cyan']",
    declarations: { '--brand-selected': '#183b47', '--brand-selected-rgb': '24 59 71' },
  },
  {
    selector: "[data-theme='dark'][data-brand='nebula-violet']",
    declarations: {
      '--brand-selected': '#303944',
      '--brand-selected-rgb': '48 57 68',
      '--theme-wallpaper-image': "url('/theme-wallpapers/silver-dark.svg')",
      '--wallpaper-fallback': '#1b2025',
      '--wallpaper-ink': '#edf1f5',
      '--wallpaper-ink-muted': '#c2cbd3',
    },
  },
  {
    selector: "[data-theme='dark'][data-brand='coral-rose']",
    declarations: {
      '--brand-selected': '#4b302a',
      '--brand-selected-rgb': '75 48 42',
      '--theme-wallpaper-image': "url('/theme-wallpapers/coral-dark.svg')",
      '--wallpaper-fallback': '#291e1b',
      '--wallpaper-ink': '#fbefe8',
      '--wallpaper-ink-muted': '#dfc7bc',
    },
  },
  {
    selector: "[data-theme='dark'][data-brand='solar-gold']",
    declarations: { '--brand-selected': '#412c1e', '--brand-selected-rgb': '65 44 30' },
  },
  {
    selector: "[data-theme='dark'][data-brand='deep-ocean']",
    declarations: { '--brand-selected': '#1d2b50', '--brand-selected-rgb': '29 43 80' },
  },
]
const { rules: brandRules, statements: brandStatements } = enumerateTopLevelRules(
  brand,
  'src/styles/tokens/_brand.scss',
)
expectExactTopLevelStatements(brandStatements, [], 'src/styles/tokens/_brand.scss')
expectClosedWorldTopLevelRules(
  brandRules,
  brandRuleOracles.map(({ selector }) => selector),
  'src/styles/tokens/_brand.scss',
)
for (const { selector, declarations } of brandRuleOracles) {
  expectExactDeclarationOracle(
    topLevelRuleBody(brandRules, selector),
    declarations,
    `brand selector ${selector}`,
  )
}

expectSassCompiles('src/styles/tokens/_brand.scss')

const semantic = stripComments(read('src/styles/tokens/_semantic.scss'))
const density = read('src/styles/tokens/_density.scss')

const semanticRuleOracles = [
  {
    selector: ":root, [data-theme='light']",
    declarations: {
      "color-scheme": "light",
      "--surface-page-canvas": "#eef1f6",
      "--surface-page-background": "var(--theme-wallpaper-image), linear-gradient(var(--wallpaper-fallback), var(--wallpaper-fallback))",
      "--surface-solid-page": "#f2f4f8",
      "--workbench-header-artwork-opacity": "0.92",
      "--surface-solid-panel": "rgb(255 255 255 / 0.9)",
      "--surface-solid-control": "rgb(255 255 255 / 0.84)",
      "--surface-solid-overlay": "rgb(253 253 255 / 0.96)",
      "--surface-solid-disabled": "#eef0f4",
      "--surface-solid-opaque": "#fbfcfd",
      "--surface-solid-opaque-raised": "#f5f7fa",
      "--surface-hover": "rgb(15 23 42 / 0.04)",
      "--surface-pressed": "rgb(15 23 42 / 0.07)",
      "--surface-glass-shell": "linear-gradient(145deg, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.14)), rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.04)) 65%, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.1)))",
      "--surface-glass-navigation": "linear-gradient(145deg, rgb(255 255 255 / 0.18), rgb(255 255 255 / 0.07) 65%, rgb(255 255 255 / 0.13))",
      "--surface-glass-panel": "linear-gradient(160deg, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.1)), rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) - 0.04)))",
      "--surface-glass-control": "linear-gradient(160deg, rgb(255 255 255 / 0.88), rgb(255 255 255 / 0.8))",
      "--surface-glass-overlay": "linear-gradient(145deg, rgb(255 255 255 / 0.98), rgb(246 249 255 / 0.96) 60%, rgb(255 255 255 / 0.97))",
      "--surface-glass-modal": "linear-gradient(145deg, rgb(250 252 249 / 0.68), rgb(235 240 236 / 0.5) 60%, rgb(247 250 246 / 0.62))",
      "--surface-glass-node": "linear-gradient(145deg, rgb(255 255 255 / 0.98), rgb(241 246 253 / 0.94) 58%, rgb(228 235 245 / 0.97))",
      "--glass-node-rim": "inset 0 0 0 1px rgb(255 255 255 / 0.62), inset 0 2px 2px -1px rgb(255 255 255 / 0.98), inset 1px 0 2px rgb(255 255 255 / 0.48), inset 0 -2px 3px -1px rgb(15 23 42 / 0.18)",
      "--shadow-glass-node": "0 2px 5px rgb(15 23 42 / 0.08), 0 16px 32px -12px rgb(15 23 42 / 0.26)",
      "--surface-glass-selected": "linear-gradient(160deg, rgb(255 255 255 / 0.72), rgb(var(--brand-selected-rgb) / 0.82))",
      "--surface-glass-lens": "linear-gradient(165deg, rgb(255 255 255 / 0.24), rgb(255 255 255 / 0.045) 48%, rgb(255 255 255 / 0.12))",
      "--border-glass-lens": "rgb(255 255 255 / 0.7)",
      "--shadow-glass-lens": "0 2px 2px -1px rgb(15 23 42 / 0.22), 0 7px 12px -5px rgb(15 23 42 / 0.28), inset 0 1px 1px rgb(255 255 255 / 0.96), inset 0 -1px 1px rgb(15 23 42 / 0.22), inset 1px 0 1px rgb(255 255 255 / 0.75), inset -1px 0 1px rgb(255 255 255 / 0.32)",
      "--glass-lens-rim": "linear-gradient(125deg, rgb(255 255 255 / 0.98) 12%, rgb(var(--brand-hover-rgb) / 0.58) 35%, rgb(255 255 255 / 0.2) 50%, rgb(var(--brand-selected-rgb) / 0.84) 70%, rgb(255 255 255 / 0.94) 90%)",
      "--surface-glass-disabled": "linear-gradient(160deg, rgb(248 250 252 / 0.62), rgb(226 232 240 / 0.46))",
      "--surface-glass-frost": "linear-gradient(150deg, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.16)), rgb(255 255 255 / var(--frost-veil-alpha, 0.5)) 55%, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.08)))",
      "--shadow-glass-frost": "0 1px 1px rgb(15 23 42 / 0.04), 0 14px 30px -18px rgb(15 23 42 / 0.22), inset 0 1px 0 rgb(255 255 255 / 0.9), inset 0 -1px 0 rgb(255 255 255 / 0.28)",
      "--glass-blur-frost": "22px",
      "--glass-droplet-highlight": "radial-gradient(120% 90% at 32% -18%, rgb(255 255 255 / 0.85), rgb(255 255 255 / 0.18) 46%, transparent 62%)",
      "--glass-droplet-caustic": "radial-gradient(70% 120% at 72% 118%, rgb(var(--brand-hover-rgb) / 0.34), transparent 64%)",
      "--surface-glass-reading": "linear-gradient(170deg, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.14)), rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) + 0.06)))",
      "--surface-reading-header": "rgb(255 255 255 / 0.34)",
      "--surface-reading-stripe": "rgb(15 23 42 / 0.025)",
      "--surface-reading-hover": "rgb(var(--brand-primary-rgb) / 0.07)",
      "--surface-reading-current": "rgb(var(--brand-primary-rgb) / 0.12)",
      "--surface-reading-sticky": "rgb(250 251 253 / 0.95)",
      "--glass-rim-double": "inset 0 0 0 1px rgb(255 255 255 / 0.32), inset 0 1px 0 rgb(255 255 255 / 0.92), inset 0 -1px 0 rgb(255 255 255 / 0.24)",
      "--surface-glass-inset": "linear-gradient(160deg, rgb(255 255 255 / 0.46), rgb(255 255 255 / 0.22))",
      "--surface-glass-canvas": "linear-gradient(170deg, rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) - 0.02)), rgb(255 255 255 / calc(var(--frost-veil-alpha, 0.5) - 0.12)))",
      "--shadow-glass-inset": "0 10px 24px -18px rgb(15 23 42 / 0.28), var(--glass-rim-double)",
      "--surface-fallback-shell": "#f4f6f9",
      "--surface-fallback-panel": "#fbfcfd",
      "--surface-fallback-control": "#ffffff",
      "--surface-fallback-overlay": "#fdfdfe",
      "--border-subtle": "rgb(15 23 42 / 0.08)",
      "--border-readable": "rgb(15 23 42 / 0.15)",
      "--border-focus": "rgb(var(--brand-primary-rgb) / 0.72)",
      "--border-divider": "rgb(15 23 42 / 0.07)",
      "--border-glass-edge": "rgb(255 255 255 / 0.72)",
      "--focus-ring": "0 0 0 3px rgb(var(--brand-primary-rgb) / 0.18)",
      "--inner-highlight": "inset 0 1px 0 rgb(255 255 255 / 0.85)",
      "--glass-edge-highlight": "inset 0 1px 0 rgb(255 255 255 / 0.92), inset 0 -1px 0 rgb(255 255 255 / 0.3), inset 1px 0 0 rgb(255 255 255 / 0.42), inset -1px 0 0 rgb(255 255 255 / 0.42)",
      "--glass-sheen": "inset 0 18px 26px -22px rgb(255 255 255 / 0.95)",
      "--shadow-shell": "0 1px 1px rgb(15 23 42 / 0.04), 0 14px 36px -10px rgb(15 23 42 / 0.16), var(--glass-edge-highlight), var(--glass-sheen)",
      "--shadow-panel": "0 1px 2px rgb(15 23 42 / 0.04), 0 10px 28px -14px rgb(15 23 42 / 0.14), var(--inner-highlight)",
      "--shadow-overlay": "0 2px 6px rgb(15 23 42 / 0.06), 0 28px 72px -16px rgb(15 23 42 / 0.3), var(--glass-edge-highlight)",
      "--shadow-active": "0 8px 22px -6px rgb(var(--brand-primary-rgb) / 0.42), var(--inner-highlight)",
      "--shadow-danger": "0 8px 22px -6px rgb(200 35 63 / 0.32)",
      "--glass-blur-shell": "16px",
      "--glass-blur-panel": "18px",
      "--glass-blur-overlay": "28px",
      "--glass-blur-refract": "1px",
      "--glass-saturation": "1.2",
      "--glass-specular-opacity": "0.9",
      "--text-primary": "#172033",
      "--text-secondary": "#3e4a5c",
      "--text-muted": "#657185",
      "--text-disabled": "#a2abb9",
      "--text-inverse": "#ffffff",
      "--text-on-brand": "#ffffff",
      "--text-link": "var(--brand-active)",
      "--brand-on-surface": "var(--brand-active)",
      "--color-data-accent": "var(--brand-primary)",
      "--status-success": "#0f8a5f",
      "--status-warning": "#b45f06",
      "--status-danger": "#c8233f",
      "--status-info": "#2f6fb0",
      "--status-neutral": "#5b6676",
      "--status-success-soft": "rgb(15 138 95 / 0.11)",
      "--status-warning-soft": "rgb(180 95 6 / 0.11)",
      "--status-danger-soft": "rgb(200 35 63 / 0.1)",
      "--status-info-soft": "rgb(47 111 176 / 0.1)",
      "--status-neutral-soft": "rgb(91 102 118 / 0.1)",
      "--code-surface": "#f4f6f9",
      "--code-text": "#24324a",
      "--radius-sm": "6px",
      "--radius-md": "10px",
      "--radius-lg": "16px",
      "--radius-xl": "20px",
      "--radius-pill": "999px",
      "--font-family-sans": "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei UI', 'Microsoft YaHei', 'Noto Sans CJK SC', 'Helvetica Neue', Arial, sans-serif",
      "--font-family-mono": "'JetBrains Mono', 'SFMono-Regular', 'Cascadia Mono', Menlo, Consolas, 'Liberation Mono', monospace",
      "--motion-duration-fast": "140ms",
      "--motion-duration-normal": "220ms",
      "--motion-duration-slow": "320ms",
      "--motion-easing-standard": "cubic-bezier(0.4, 0, 0.2, 1)",
      "--motion-easing-emphasized": "cubic-bezier(0.2, 0.9, 0.3, 1.12)",
    },
  },
  {
    selector: "[data-theme='dark']",
    declarations: {
      "color-scheme": "dark",
      "--surface-page-canvas": "#111317",
      "--surface-page-background": "var(--theme-wallpaper-image), linear-gradient(var(--wallpaper-fallback), var(--wallpaper-fallback))",
      "--surface-solid-page": "#131519",
      "--workbench-header-artwork-opacity": "0.4",
      "--surface-solid-panel": "rgb(31 34 40 / 0.94)",
      "--surface-solid-control": "rgb(42 45 52 / 0.9)",
      "--surface-solid-overlay": "rgb(33 36 42 / 0.97)",
      "--surface-solid-disabled": "#24272d",
      "--surface-solid-opaque": "#1d2025",
      "--surface-solid-opaque-raised": "#262a30",
      "--surface-hover": "rgb(255 255 255 / 0.05)",
      "--surface-pressed": "rgb(255 255 255 / 0.09)",
      "--surface-glass-shell": "linear-gradient(145deg, rgb(40 46 61 / 0.72), rgb(19 25 38 / 0.64))",
      "--surface-glass-navigation": "linear-gradient(145deg, rgb(19 25 38 / 0.12), rgb(19 25 38 / 0.04))",
      "--surface-glass-panel": "linear-gradient(160deg, rgb(38 41 48 / 0.74), rgb(28 31 36 / 0.64))",
      "--surface-glass-control": "linear-gradient(160deg, rgb(48 52 59 / 0.9), rgb(38 41 47 / 0.84))",
      "--surface-glass-overlay": "linear-gradient(145deg, rgb(34 39 52 / 0.98), rgb(19 25 38 / 0.96) 60%, rgb(28 33 45 / 0.97))",
      "--surface-glass-modal": "linear-gradient(145deg, rgb(38 44 54 / 0.8), rgb(20 27 39 / 0.68) 60%, rgb(32 39 50 / 0.76))",
      "--surface-glass-node": "linear-gradient(145deg, rgb(57 65 82 / 0.98), rgb(32 39 53 / 0.96) 58%, rgb(24 31 43 / 0.98))",
      "--glass-node-rim": "inset 0 0 0 1px rgb(255 255 255 / 0.13), inset 0 2px 2px -1px rgb(255 255 255 / 0.3), inset 1px 0 2px rgb(255 255 255 / 0.12), inset 0 -2px 3px -1px rgb(0 0 0 / 0.48)",
      "--shadow-glass-node": "0 2px 5px rgb(0 0 0 / 0.25), 0 18px 36px -12px rgb(0 0 0 / 0.48)",
      "--surface-glass-selected": "linear-gradient(160deg, rgb(var(--brand-primary-rgb) / 0.3), rgb(var(--brand-active-rgb) / 0.2))",
      "--surface-glass-lens": "linear-gradient(165deg, rgb(255 255 255 / 0.18), rgb(255 255 255 / 0.02) 48%, rgb(255 255 255 / 0.08))",
      "--border-glass-lens": "rgb(255 255 255 / 0.5)",
      "--shadow-glass-lens": "0 2px 3px -1px rgb(0 0 0 / 0.42), 0 8px 14px -5px rgb(0 0 0 / 0.5), inset 0 1px 1px rgb(255 255 255 / 0.76), inset 0 -1px 1px rgb(0 0 0 / 0.34), inset 1px 0 1px rgb(255 255 255 / 0.4), inset -1px 0 1px rgb(255 255 255 / 0.24)",
      "--glass-lens-rim": "linear-gradient(125deg, rgb(255 255 255 / 0.86) 12%, rgb(var(--brand-hover-rgb) / 0.62) 35%, rgb(255 255 255 / 0.16) 50%, rgb(var(--brand-selected-rgb) / 0.76) 70%, rgb(255 255 255 / 0.82) 90%)",
      "--surface-glass-disabled": "linear-gradient(160deg, rgb(44 47 54 / 0.54), rgb(30 33 38 / 0.52))",
      "--surface-glass-frost": "linear-gradient(150deg, rgb(40 44 52 / 0.62), rgb(28 31 37 / 0.48) 55%, rgb(34 37 44 / 0.56))",
      "--shadow-glass-frost": "0 1px 1px rgb(0 0 0 / 0.24), 0 16px 34px -18px rgb(0 0 0 / 0.56), inset 0 1px 0 rgb(255 255 255 / 0.16), inset 0 -1px 0 rgb(255 255 255 / 0.04)",
      "--glass-blur-frost": "22px",
      "--glass-droplet-highlight": "radial-gradient(120% 90% at 32% -18%, rgb(255 255 255 / 0.5), rgb(255 255 255 / 0.1) 46%, transparent 62%)",
      "--glass-droplet-caustic": "radial-gradient(70% 120% at 72% 118%, rgb(var(--brand-hover-rgb) / 0.4), transparent 64%)",
      "--surface-glass-reading": "linear-gradient(170deg, rgb(32 35 41 / 0.76), rgb(26 29 34 / 0.68))",
      "--surface-reading-header": "rgb(255 255 255 / 0.04)",
      "--surface-reading-stripe": "rgb(255 255 255 / 0.025)",
      "--surface-reading-hover": "rgb(var(--brand-primary-rgb) / 0.14)",
      "--surface-reading-current": "rgb(var(--brand-primary-rgb) / 0.2)",
      "--surface-reading-sticky": "rgb(31 34 40 / 0.96)",
      "--glass-rim-double": "inset 0 0 0 1px rgb(255 255 255 / 0.06), inset 0 1px 0 rgb(255 255 255 / 0.16), inset 0 -1px 0 rgb(255 255 255 / 0.04)",
      "--surface-glass-inset": "linear-gradient(160deg, rgb(255 255 255 / 0.07), rgb(255 255 255 / 0.03))",
      "--surface-glass-canvas": "linear-gradient(170deg, rgb(22 25 30 / 0.72), rgb(18 20 25 / 0.62))",
      "--shadow-glass-inset": "0 12px 26px -18px rgb(0 0 0 / 0.5), var(--glass-rim-double)",
      "--surface-fallback-shell": "#1e2126",
      "--surface-fallback-panel": "#1d2025",
      "--surface-fallback-control": "#2a2d34",
      "--surface-fallback-overlay": "#23262c",
      "--border-subtle": "rgb(255 255 255 / 0.08)",
      "--border-readable": "rgb(255 255 255 / 0.15)",
      "--border-focus": "color-mix(in srgb, var(--brand-primary) 62%, white)",
      "--border-divider": "rgb(255 255 255 / 0.07)",
      "--border-glass-edge": "rgb(255 255 255 / 0.14)",
      "--focus-ring": "0 0 0 3px rgb(var(--brand-primary-rgb) / 0.32)",
      "--inner-highlight": "inset 0 1px 0 rgb(255 255 255 / 0.08)",
      "--glass-edge-highlight": "inset 0 1px 0 rgb(255 255 255 / 0.14), inset 0 -1px 0 rgb(255 255 255 / 0.03), inset 1px 0 0 rgb(255 255 255 / 0.05), inset -1px 0 0 rgb(255 255 255 / 0.05)",
      "--glass-sheen": "inset 0 18px 26px -24px rgb(255 255 255 / 0.18)",
      "--shadow-shell": "0 1px 1px rgb(0 0 0 / 0.3), 0 16px 40px -10px rgb(0 0 0 / 0.55), var(--glass-edge-highlight), var(--glass-sheen)",
      "--shadow-panel": "0 1px 2px rgb(0 0 0 / 0.28), 0 12px 32px -14px rgb(0 0 0 / 0.5), var(--inner-highlight)",
      "--shadow-overlay": "0 2px 6px rgb(0 0 0 / 0.3), 0 30px 80px -14px rgb(0 0 0 / 0.66), var(--glass-edge-highlight)",
      "--shadow-active": "0 8px 22px -6px rgb(var(--brand-primary-rgb) / 0.5), var(--inner-highlight)",
      "--shadow-danger": "0 8px 22px -6px rgb(255 107 129 / 0.3)",
      "--glass-blur-shell": "16px",
      "--glass-blur-panel": "18px",
      "--glass-blur-overlay": "28px",
      "--glass-blur-refract": "1.5px",
      "--glass-saturation": "1.15",
      "--glass-specular-opacity": "0.85",
      "--text-primary": "#eef0f4",
      "--text-secondary": "#c3c9d3",
      "--text-muted": "#949dab",
      "--text-disabled": "#5f6774",
      "--text-inverse": "#111317",
      "--text-on-brand": "#ffffff",
      "--text-link": "color-mix(in srgb, var(--brand-primary) 58%, white)",
      "--brand-on-surface": "color-mix(in srgb, var(--brand-primary) 58%, white)",
      "--color-data-accent": "var(--brand-on-surface)",
      "--status-success": "#3fcf8e",
      "--status-warning": "#f5a524",
      "--status-danger": "#ff6b81",
      "--status-info": "#6cb4ff",
      "--status-neutral": "#9aa4b2",
      "--status-success-soft": "rgb(63 207 142 / 0.15)",
      "--status-warning-soft": "rgb(245 165 36 / 0.15)",
      "--status-danger-soft": "rgb(255 107 129 / 0.15)",
      "--status-info-soft": "rgb(108 180 255 / 0.15)",
      "--status-neutral-soft": "rgb(154 164 178 / 0.15)",
      "--code-surface": "#17191d",
      "--code-text": "#d7dce4",
    },
  },
  {
    selector: ":root, [data-theme]",
    declarations: {
      "--text-on-wallpaper": "var(--wallpaper-ink)",
      "--text-on-wallpaper-secondary": "var(--wallpaper-ink-muted)",
      "--text-on-wallpaper-shadow": "var(--wallpaper-ink-shadow)",
      "--glass-tint": "var(--surface-glass-shell)",
      "--glass-control-radius": "var(--radius-pill)",
      "--glass-control-border": "transparent",
      "--glass-control-primary-ink": "initial",
      "--glass-control-hover-transform": "translateY(-1px)",
      "--glass-decoration-display": "block",
      "--glass-dropdown-clip": "initial",
      "--glass-dropdown-filter": "drop-shadow(0 10px 14px var(--border-subtle))",
      "--glass-table-sticky": "initial",
      "--workbench-control-hover-shadow": "initial",
      "--workbench-toolbar-surface": "transparent",
      "--workbench-toolbar-border": "0",
      "--workbench-toolbar-padding": "0px",
      "--workbench-toolbar-inset": "initial",
      "--workbench-table-heading-ink": "initial",
      "--workbench-header-artwork": "var(--theme-header-artwork)",
      "--surface-glass-action": "linear-gradient(155deg, rgb(var(--brand-primary-rgb) / 0.42), rgb(var(--brand-primary-rgb) / 0.22) 58%, rgb(var(--brand-hover-rgb) / 0.34))",
      "--shadow-glass-navigation-selection": "0 8px 18px -8px rgb(15 23 42 / 0.38), 0 1px 2px rgb(15 23 42 / 0.12), inset 0 1.5px 1px rgb(255 255 255 / 0.85), inset 0 -3px 6px -2px rgb(var(--brand-hover-rgb) / 0.42)",
      "--surface-glass-navigation-selection": "radial-gradient(85% 70% at 28% -8%, rgb(255 255 255 / 0.78), rgb(255 255 255 / 0.12) 52%, transparent 70%), var(--glass-droplet-caustic), linear-gradient(rgb(255 255 255 / calc((var(--frost-veil-alpha, 0.42) - 0.42) * 0.4)), rgb(255 255 255 / calc((var(--frost-veil-alpha, 0.42) - 0.42) * 0.4)))",
      "--border-glass-navigation-selection": "linear-gradient(112deg, rgb(255 255 255 / 0.96) 0%, rgb(255 255 255 / 0.28) 24%, transparent 42%, transparent 62%, rgb(var(--brand-hover-rgb) / 0.55) 84%, rgb(255 255 255 / 0.9) 100%)",
      "--liquid-specular-light": "none",
      "--liquid-specular-dark": "none",
      "--brand-page-bg": "var(--surface-page-background)",
      "--brand-page-frame-border": "var(--border-subtle)",
      "--brand-glass-bg": "var(--surface-glass-shell)",
      "--brand-glass-card-bg": "var(--surface-glass-panel)",
      "--brand-soft-bg": "rgb(var(--brand-primary-rgb) / 0.1)",
      "--brand-decorative-grid": "linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)), linear-gradient(rgb(var(--brand-primary-rgb) / 0.14) 1px, transparent 1px), linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.12) 1px, transparent 1px)",
      "--bg-primary": "var(--surface-solid-page)",
      "--bg-secondary": "var(--surface-solid-panel)",
      "--bg-tertiary": "var(--surface-solid-control)",
      "--bg-card": "var(--surface-solid-panel)",
      "--bg-card-hover": "var(--surface-solid-control)",
      "--border-glass": "var(--border-subtle)",
      "--border-glass-hover": "var(--border-readable)",
      "--accent-gradient": "var(--brand-primary-gradient)",
      "--accent-color": "var(--brand-primary)",
      "--accent-light": "rgb(var(--brand-primary-rgb) / 0.12)",
      "--success-color": "var(--status-success)",
      "--warning-color": "var(--status-warning)",
      "--danger-color": "var(--status-danger)",
      "--info-color": "var(--status-info)",
      "--glow-primary": "0 0 20px rgb(var(--brand-primary-rgb) / 0.24)",
      "--glow-card": "0 0 30px rgb(var(--brand-primary-rgb) / 0.08)",
      "--shadow-card": "var(--shadow-panel)",
      "--shadow-card-hover": "var(--shadow-active)",
      "--transition-fast": "var(--motion-duration-fast) ease",
      "--transition-normal": "var(--motion-duration-normal) ease",
      "--transition-slow": "var(--motion-duration-slow) var(--motion-easing-standard)",
      "--reachai-workbench-title-height": "var(--layout-page-header-height-emphasis)",
      "--reachai-workbench-title-radius": "var(--radius-lg)",
    },
  },
  {
    selector: ":root:not([data-theme='dark'])[data-brand='deep-ocean']:not([data-reduce-transparency='true']) .glass-workspace-adaptive",
    declarations: {
      'color-scheme': 'dark',
      '--surface-solid-panel': 'rgb(22 26 40 / 0.96)',
      '--surface-solid-control': 'rgb(40 46 61 / 0.9)',
      '--surface-solid-opaque': '#252c3c',
      '--surface-solid-opaque-raised': '#1c2130',
      '--surface-solid-disabled': 'rgb(255 255 255 / 0.05)',
      '--surface-hover': 'rgb(255 255 255 / 0.06)',
      '--surface-glass-panel': 'linear-gradient(160deg, rgb(255 255 255 / 0.1), rgb(255 255 255 / 0.035)), linear-gradient(160deg, rgb(12 18 35 / 0.56), rgb(12 18 35 / 0.44))',
      '--surface-glass-frost': 'linear-gradient(150deg, rgb(255 255 255 / 0.09), rgb(255 255 255 / 0.025)), linear-gradient(150deg, rgb(12 18 35 / 0.48), rgb(12 18 35 / 0.36))',
      '--surface-glass-control': 'linear-gradient(160deg, rgb(255 255 255 / 0.14), rgb(255 255 255 / 0.07))',
      '--surface-glass-inset': 'linear-gradient(160deg, rgb(255 255 255 / 0.07), rgb(255 255 255 / 0.025))',
      '--surface-glass-selected': 'var(--surface-glass-control)',
      '--surface-glass-lens': 'linear-gradient(165deg, rgb(255 255 255 / 0.1), rgb(255 255 255 / 0.025) 48%, rgb(255 255 255 / 0.065)), linear-gradient(165deg, rgb(12 18 35 / 0.24), rgb(12 18 35 / 0.3))',
      '--surface-glass-action': 'linear-gradient(155deg, rgb(var(--brand-primary-rgb) / 0.24), rgb(var(--brand-primary-rgb) / 0.14) 58%, rgb(var(--brand-hover-rgb) / 0.2)), linear-gradient(155deg, rgb(12 18 35 / 0.22), rgb(12 18 35 / 0.3))',
      '--surface-glass-reading': 'var(--surface-glass-frost)',
      '--surface-reading-header': 'rgb(255 255 255 / 0.045)',
      '--surface-reading-stripe': 'rgb(255 255 255 / 0.02)',
      '--surface-reading-hover': 'rgb(255 255 255 / 0.055)',
      '--surface-reading-current': 'rgb(var(--brand-primary-rgb) / 0.18)',
      '--surface-reading-sticky': '#1b2030',
      '--surface-fallback-panel': '#1b2030',
      '--surface-fallback-control': '#252c3c',
      '--border-subtle': 'rgb(255 255 255 / 0.12)',
      '--border-divider': 'rgb(255 255 255 / 0.1)',
      '--border-glass-edge': 'rgb(255 255 255 / 0.2)',
      '--border-glass-lens': 'rgb(255 255 255 / 0.32)',
      '--border-focus': 'color-mix(in srgb, var(--brand-primary) 58%, white)',
      '--inner-highlight': 'inset 0 1px 0 rgb(255 255 255 / 0.14)',
      '--shadow-panel': '0 12px 30px -18px rgb(0 0 0 / 0.48), var(--inner-highlight)',
      '--shadow-glass-frost': '0 16px 32px -20px rgb(0 0 0 / 0.48), inset 0 1px 0 rgb(255 255 255 / 0.18)',
      '--glass-rim-double': 'inset 0 0 0 1px rgb(255 255 255 / 0.06), inset 0 1px 0 rgb(255 255 255 / 0.16), inset 0 -1px 0 rgb(255 255 255 / 0.04)',
      '--shadow-glass-inset': '0 12px 26px -18px rgb(0 0 0 / 0.5), var(--glass-rim-double)',
      '--shadow-glass-lens': '0 2px 3px -1px rgb(0 0 0 / 0.32), 0 7px 12px -5px rgb(0 0 0 / 0.38), inset 0 1px 1px rgb(255 255 255 / 0.34), inset 0 -1px 1px rgb(0 0 0 / 0.24), inset 1px 0 1px rgb(255 255 255 / 0.16), inset -1px 0 1px rgb(255 255 255 / 0.08)',
      '--glass-lens-rim': 'linear-gradient(125deg, rgb(255 255 255 / 0.5) 12%, rgb(var(--brand-hover-rgb) / 0.2) 35%, rgb(255 255 255 / 0.06) 50%, rgb(var(--brand-selected-rgb) / 0.24) 70%, rgb(255 255 255 / 0.38) 90%)',
      '--glass-droplet-highlight': 'radial-gradient(120% 90% at 32% -18%, rgb(255 255 255 / 0.24), rgb(255 255 255 / 0.04) 46%, transparent 62%)',
      '--glass-droplet-caustic': 'radial-gradient(70% 120% at 72% 118%, rgb(var(--brand-hover-rgb) / 0.16), transparent 64%)',
      '--glass-specular-opacity': '0.58',
      '--glass-saturation': '1',
      '--text-primary': '#f7f9ff',
      '--text-secondary': '#dce4f0',
      '--text-muted': '#b7c1d3',
      '--brand-on-surface': 'color-mix(in srgb, var(--brand-primary) 42%, white)',
      '--color-data-accent': 'var(--brand-on-surface)',
      '--status-success': '#3fcf8e',
      '--status-warning': '#f5a524',
      '--status-danger': '#ff6b81',
      '--status-info': '#83bfff',
      '--code-surface': 'rgb(10 14 26 / 0.55)',
      '--code-text': '#d7dce4',
      '--status-neutral': '#9aa4b2',
      '--status-success-soft': 'rgb(63 207 142 / 0.15)',
      '--status-warning-soft': 'rgb(245 165 36 / 0.15)',
      '--status-danger-soft': 'rgb(255 107 129 / 0.15)',
    },
  },
]
const { rules: semanticRules, statements: semanticStatements } = enumerateTopLevelRules(
  semantic,
  'src/styles/tokens/_semantic.scss',
)
expectExactTopLevelStatements(semanticStatements, [], 'src/styles/tokens/_semantic.scss')
expectClosedWorldTopLevelRules(
  semanticRules,
  semanticRuleOracles.map(({ selector }) => selector),
  'src/styles/tokens/_semantic.scss',
)
for (const { selector, declarations } of semanticRuleOracles) {
  expectExactDeclarationOracle(
    topLevelRuleBody(semanticRules, selector),
    declarations,
    `semantic selector ${selector}`,
  )
}
failures.push(
  ...forbiddenLegacySpacingTokenFailures(semantic, 'src/styles/tokens/_semantic.scss'),
)

const densityRuleOracles = [
  {
    selector: ':root',
    declarations: {
      '--density-compact-control-height': '32px',
      '--density-compact-row-height': '40px',
      '--density-compact-section-gap': '12px',
      '--density-compact-panel-padding': '12px',
      '--density-compact-table-cell-padding-y': '4px',
      '--density-comfortable-control-height': '38px',
      '--density-comfortable-row-height': '48px',
      '--density-comfortable-section-gap': '16px',
      '--density-comfortable-panel-padding': '16px',
      '--density-comfortable-table-cell-padding-y': '8px',
      '--density-spacious-control-height': '44px',
      '--density-spacious-row-height': '56px',
      '--density-spacious-section-gap': '20px',
      '--density-spacious-panel-padding': '24px',
      '--density-spacious-table-cell-padding-y': '13px',
    },
  },
  {
    selector: ":root, [data-density='comfortable'], .density-comfortable",
    declarations: {
      '--control-height': 'var(--density-comfortable-control-height)',
      '--row-height': 'var(--density-comfortable-row-height)',
      '--section-gap': 'var(--density-comfortable-section-gap)',
      '--panel-padding': 'var(--density-comfortable-panel-padding)',
    },
  },
  {
    selector: "[data-density='compact'], .density-compact",
    declarations: {
      '--control-height': 'var(--density-compact-control-height)',
      '--row-height': 'var(--density-compact-row-height)',
      '--section-gap': 'var(--density-compact-section-gap)',
      '--panel-padding': 'var(--density-compact-panel-padding)',
    },
  },
  {
    selector: "[data-density='spacious'], .density-spacious",
    declarations: {
      '--control-height': 'var(--density-spacious-control-height)',
      '--row-height': 'var(--density-spacious-row-height)',
      '--section-gap': 'var(--density-spacious-section-gap)',
      '--panel-padding': 'var(--density-spacious-panel-padding)',
    },
  },
  {
    selector: "[data-density='compact'] .density-comfortable",
    declarations: {
      '--control-height': 'var(--density-compact-control-height)',
      '--row-height': 'var(--density-compact-row-height)',
      '--section-gap': 'var(--density-compact-section-gap)',
      '--panel-padding': 'var(--density-compact-panel-padding)',
    },
  },
  {
    selector: "[data-density='spacious'] .density-comfortable",
    declarations: {
      '--control-height': 'var(--density-spacious-control-height)',
      '--row-height': 'var(--density-spacious-row-height)',
      '--section-gap': 'var(--density-spacious-section-gap)',
      '--panel-padding': 'var(--density-spacious-panel-padding)',
    },
  },
]
const { rules: densityRules, statements: densityStatements } = enumerateTopLevelRules(
  density,
  'src/styles/tokens/_density.scss',
)
expectExactTopLevelStatements(densityStatements, [], 'src/styles/tokens/_density.scss')
expectClosedWorldTopLevelRules(
  densityRules,
  densityRuleOracles.map(({ selector }) => selector),
  'src/styles/tokens/_density.scss',
)
for (const { selector, declarations } of densityRuleOracles) {
  expectExactDeclarationOracle(
    topLevelRuleBody(densityRules, selector),
    declarations,
    `density selector ${selector}`,
  )
}

expectSassCompiles('src/styles/tokens/_semantic.scss')
expectSassCompiles('src/styles/tokens/_density.scss')

const glass = stripComments(read('src/styles/_glass.scss'))
const glassRecipes = [
  '.glass-surface-shell',
  '.glass-surface-panel',
  '.glass-surface-frost',
  '.glass-surface-control',
  '.glass-surface-overlay',
  '.glass-surface-node',
  '.glass-surface-lens',
  '.glass-surface-navigation',
]
const fallbackTokens = {
  '.glass-surface-shell': '--surface-fallback-shell',
  '.glass-surface-panel': '--surface-fallback-panel',
  '.glass-surface-frost': '--surface-fallback-panel',
  '.glass-surface-control': '--surface-fallback-control',
  '.glass-surface-overlay': '--surface-fallback-overlay',
  '.glass-surface-node': '--surface-fallback-panel',
  '.glass-surface-lens': '--surface-fallback-control',
  '.glass-surface-navigation': '--surface-fallback-shell',
}

const defaultGroup = extractBalancedBlock(glass, '.glass-surface-shell,', 'glass default group')
expectExactGlassSelectors(
  defaultGroup,
  ['.glass-surface-shell', '.glass-surface-panel', '.glass-surface-overlay'],
  'glass default group',
)
if (defaultGroup !== null) {
  expectExactDeclaration(
    defaultGroup.body,
    'border',
    '1px solid var(--border-subtle)',
    'glass default group',
  )
  for (const property of ['-webkit-backdrop-filter', 'backdrop-filter']) {
    expectExactDeclaration(
      defaultGroup.body,
      property,
      'blur(var(--glass-blur-panel)) saturate(var(--glass-saturation))',
      'glass default group',
    )
  }
}

const defaultRecipeBlocks = new Map()
for (const recipe of glassRecipes) {
  defaultRecipeBlocks.set(
    recipe,
    extractBalancedBlock(glass, `${recipe} {`, `glass recipe ${recipe}`),
  )
}

const control = defaultRecipeBlocks.get('.glass-surface-control')
if (control !== null) {
  expectExactDeclaration(
    control.body,
    'border',
    '1px solid var(--border-readable)',
    'glass control recipe',
  )
  expectExactDeclaration(
    control.body,
    'background',
    'var(--surface-glass-control)',
    'glass control recipe',
  )
  expectExactDeclaration(
    control.body,
    'box-shadow',
    'var(--inner-highlight)',
    'glass control recipe',
  )
  expectNoDeclaration(control.body, '-webkit-backdrop-filter', 'glass control recipe')
  expectNoDeclaration(control.body, 'backdrop-filter', 'glass control recipe')
}

const fallbackBlocks = [
  {
    marker: '@supports not',
    label: 'unsupported backdrop fallback',
    disableBackdrop: false,
  },
  {
    marker: "[data-reduce-transparency='true']",
    label: 'explicit reduced-transparency fallback',
    disableBackdrop: true,
  },
  {
    marker: '@media (prefers-reduced-transparency: reduce)',
    label: 'reduced-transparency media fallback',
    disableBackdrop: true,
  },
].map((fallback) => ({
  ...fallback,
  block: extractBalancedBlock(glass, fallback.marker, fallback.label),
}))

const defaultRulesEnd = Math.max(
  defaultGroup?.end ?? -1,
  ...[...defaultRecipeBlocks.values()].map((block) => block?.end ?? -1),
)

for (const { label, disableBackdrop, block } of fallbackBlocks) {
  if (block === null) continue
  if (block.start <= defaultRulesEnd) {
    failures.push(`${label} must appear after all default glass rules`)
  }

  for (const recipe of glassRecipes) {
    const recipeFallback = extractBalancedBlock(block.body, `${recipe} {`, `${label} ${recipe}`)
    if (recipeFallback !== null) {
      expectExactDeclaration(
        recipeFallback.body,
        'background',
        `var(${fallbackTokens[recipe]})`,
        `${label} ${recipe}`,
      )
    }
  }

  if (disableBackdrop) {
    const disabledGroup = extractBalancedBlock(block.body, '.glass-surface-shell,', `${label} group`)
    expectExactGlassSelectors(disabledGroup, glassRecipes, `${label} group`)
    if (disabledGroup !== null) {
      expectExactDeclaration(
        disabledGroup.body,
        '-webkit-backdrop-filter',
        'none',
        `${label} group`,
      )
      expectExactDeclaration(
        disabledGroup.body,
        'backdrop-filter',
        'none',
        `${label} group`,
      )
    }
  }
}

const reducedMotion = extractBalancedBlock(
  glass,
  '@media (prefers-reduced-motion: reduce)',
  'reduced-motion media fallback',
)
if (reducedMotion !== null) {
  if (reducedMotion.start <= defaultRulesEnd) {
    failures.push('reduced-motion media fallback must appear after all default glass rules')
  }
  const reducedMotionRoot = extractBalancedBlock(
    reducedMotion.body,
    ':root',
    'reduced-motion root override',
  )
  if (reducedMotionRoot !== null) {
    for (const token of [
      '--motion-duration-fast',
      '--motion-duration-normal',
      '--motion-duration-slow',
    ]) {
      expectExactDeclaration(reducedMotionRoot.body, token, '0.01ms', 'reduced-motion root override')
    }
  }
}

const glassTokenUses = new Set(
  [...glass.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)].map((match) => match[1]),
)
if (!glassTokenUses.has('--surface-fallback-control')) {
  failures.push('glass recipes must consume --surface-fallback-control')
}
const sharedGlassTokenSources = [semantic, brand, stripComments(read('src/styles/tokens/_layout.scss'))]
for (const token of glassTokenUses) {
  if (!sharedGlassTokenSources.some((source) => new RegExp(`${escapeRegExp(token)}\\s*:`).test(source))) {
    failures.push(`glass token ${token} is not defined in shared semantic, brand, or layout tokens`)
  }
}

expectSassCompiles('src/styles/_glass.scss')

// Check the emitted fallback, not only mixin source: the global switch must
// remove lens decoration from existing public controls as well as the shell.
const materialCss = sass.compile(resolve(root, 'src/styles/_glass.scss'), { style: 'expanded' }).css
const solidMaterial = extractBalancedBlock(materialCss, "[data-reduce-transparency=true] {", 'compiled solid material')
if (solidMaterial !== null) {
  for (const [property, value] of Object.entries({
    '--surface-glass-control': 'var(--surface-fallback-control)',
    '--surface-glass-panel': 'var(--surface-fallback-panel)',
    '--surface-glass-modal': 'var(--surface-fallback-overlay)',
    '--glass-control-radius': 'var(--radius-md)',
    '--glass-control-primary-ink': 'var(--text-on-brand)',
    '--glass-decoration-display': 'none',
    '--glass-dropdown-clip': 'none',
    '--glass-dropdown-filter': 'none',
    '--shadow-glass-lens': 'none',
    '--shadow-glass-navigation-selection': 'none',
    '--glass-rim-double': '0 0 0 transparent',
    '--inner-highlight': '0 0 0 transparent',
    '--glass-table-sticky': 'var(--surface-fallback-panel)',
    '--workbench-toolbar-padding': '10px',
    '--workbench-table-heading-ink': 'var(--brand-on-surface)',
    '--workbench-table-inset': 'var(--layout-panel-padding)',
    '--workbench-metric-icon-size': '44px',
  })) {
    expectExactDeclaration(solidMaterial.body, property, value, 'compiled solid material')
  }
}
const solidSelection = extractBalancedBlock(materialCss, '[data-reduce-transparency=true] .glass-surface-selection {', 'compiled solid selection')
if (solidSelection !== null) {
  expectExactDeclaration(solidSelection.body, 'background', 'var(--surface-reading-current)', 'compiled solid selection')
  expectExactDeclaration(solidSelection.body, 'box-shadow', 'none', 'compiled solid selection')
}

for (const file of ['src/components/common/LiquidGlassSelect.vue', 'src/components/common/LiquidGlassFilterBar.vue']) {
  if (/\.(?:el-input__wrapper|el-select__wrapper)\b/.test(stripComments(read(file)))) {
    failures.push(`${file}: native field decoration belongs to LiquidGlassControl.vue`)
  }
}
if (/\.liquid-glass-(?:control|input|select)[^{]*\.(?:el-input__wrapper|el-select__wrapper)\b/.test(stripComments(read('src/styles/_components.scss')))) {
  failures.push('src/styles/_components.scss: do not duplicate LiquidGlassControl native field decoration')
}

const elementPlus = read('src/styles/_element-plus.scss')
const elementPlusWithoutComments = stripComments(elementPlus)
const theme = read('src/styles/theme.scss')
const themeWithoutComments = stripComments(theme)

const rootElementPlusMappings = {
  '--el-color-primary': 'var(--brand-primary)',
  '--el-color-primary-light-3': 'var(--brand-hover)',
  '--el-color-primary-light-5': 'var(--brand-disabled)',
  '--el-color-primary-light-7': 'rgb(var(--brand-selected-rgb) / 0.82)',
  '--el-color-primary-light-8': 'rgb(var(--brand-selected-rgb) / 0.9)',
  '--el-color-primary-light-9': 'var(--brand-selected)',
  '--el-color-primary-dark-2': 'var(--brand-active)',
  '--el-color-success': 'var(--status-success)',
  '--el-color-success-light-3': 'color-mix(in srgb, var(--status-success) 70%, white)',
  '--el-color-success-light-5': 'color-mix(in srgb, var(--status-success) 48%, white)',
  '--el-color-success-light-7': 'color-mix(in srgb, var(--status-success) 28%, white)',
  '--el-color-success-light-8': 'color-mix(in srgb, var(--status-success) 18%, white)',
  '--el-color-success-light-9': 'color-mix(in srgb, var(--status-success) 10%, white)',
  '--el-color-success-dark-2': 'color-mix(in srgb, var(--status-success) 82%, black)',
  '--el-color-warning': 'var(--status-warning)',
  '--el-color-warning-light-3': 'color-mix(in srgb, var(--status-warning) 70%, white)',
  '--el-color-warning-light-5': 'color-mix(in srgb, var(--status-warning) 48%, white)',
  '--el-color-warning-light-7': 'color-mix(in srgb, var(--status-warning) 28%, white)',
  '--el-color-warning-light-8': 'color-mix(in srgb, var(--status-warning) 18%, white)',
  '--el-color-warning-light-9': 'color-mix(in srgb, var(--status-warning) 10%, white)',
  '--el-color-warning-dark-2': 'color-mix(in srgb, var(--status-warning) 82%, black)',
  '--el-color-danger': 'var(--status-danger)',
  '--el-color-danger-light-3': 'color-mix(in srgb, var(--status-danger) 70%, white)',
  '--el-color-danger-light-5': 'color-mix(in srgb, var(--status-danger) 48%, white)',
  '--el-color-danger-light-7': 'color-mix(in srgb, var(--status-danger) 28%, white)',
  '--el-color-danger-light-8': 'color-mix(in srgb, var(--status-danger) 18%, white)',
  '--el-color-danger-light-9': 'color-mix(in srgb, var(--status-danger) 10%, white)',
  '--el-color-danger-dark-2': 'color-mix(in srgb, var(--status-danger) 82%, black)',
  '--el-color-error': 'var(--status-danger)',
  '--el-color-error-light-3': 'var(--el-color-danger-light-3)',
  '--el-color-error-light-5': 'var(--el-color-danger-light-5)',
  '--el-color-error-light-7': 'var(--el-color-danger-light-7)',
  '--el-color-error-light-8': 'var(--el-color-danger-light-8)',
  '--el-color-error-light-9': 'var(--el-color-danger-light-9)',
  '--el-color-error-dark-2': 'var(--el-color-danger-dark-2)',
  '--el-color-info': 'var(--status-info)',
  '--el-color-info-light-3': 'color-mix(in srgb, var(--status-info) 70%, white)',
  '--el-color-info-light-5': 'color-mix(in srgb, var(--status-info) 48%, white)',
  '--el-color-info-light-7': 'color-mix(in srgb, var(--status-info) 28%, white)',
  '--el-color-info-light-8': 'color-mix(in srgb, var(--status-info) 18%, white)',
  '--el-color-info-light-9': 'color-mix(in srgb, var(--status-info) 10%, white)',
  '--el-color-info-dark-2': 'color-mix(in srgb, var(--status-info) 82%, black)',
  '--el-bg-color': 'var(--surface-solid-panel)',
  '--el-bg-color-overlay': 'var(--surface-solid-overlay)',
  '--el-bg-color-page': 'var(--surface-solid-page)',
  '--el-text-color-primary': 'var(--text-primary)',
  '--el-text-color-regular': 'var(--text-secondary)',
  '--el-text-color-secondary': 'var(--text-muted)',
  '--el-text-color-placeholder': 'var(--text-muted)',
  '--el-text-color-disabled': 'var(--text-disabled)',
  '--el-border-color': 'var(--border-readable)',
  '--el-border-color-light': 'var(--border-subtle)',
  '--el-border-color-lighter': 'var(--border-subtle)',
  '--el-border-color-extra-light': 'var(--border-subtle)',
  '--el-border-color-dark': 'var(--border-readable)',
  '--el-fill-color': 'var(--surface-solid-control)',
  '--el-fill-color-light': 'var(--surface-solid-control)',
  '--el-fill-color-lighter': 'var(--surface-solid-panel)',
  '--el-fill-color-extra-light': 'var(--surface-solid-panel)',
  '--el-fill-color-dark': 'var(--surface-solid-control)',
  '--el-fill-color-blank': 'var(--surface-solid-panel)',
  '--el-mask-color': 'color-mix(in srgb, var(--surface-solid-page) 70%, transparent)',
  '--el-mask-color-extra-light': 'color-mix(in srgb, var(--surface-solid-page) 36%, transparent)',
  '--el-box-shadow': 'var(--shadow-panel)',
  '--el-box-shadow-light': 'var(--shadow-panel)',
  '--el-box-shadow-lighter': 'var(--inner-highlight)',
  '--el-box-shadow-dark': 'var(--shadow-overlay)',
  '--el-disabled-bg-color': 'var(--surface-solid-disabled)',
  '--el-disabled-text-color': 'var(--text-disabled)',
  '--el-disabled-border-color': 'var(--border-subtle)',
  '--el-overlay-color': 'rgb(15 18 24 / 0.26)',
  '--el-overlay-color-light': 'rgb(15 18 24 / 0.3)',
  '--el-overlay-color-lighter': 'rgb(15 18 24 / 0.18)',
  '--el-menu-bg-color': 'transparent',
  '--el-menu-text-color': 'var(--text-secondary)',
  '--el-menu-active-color': 'var(--text-primary)',
  '--el-menu-hover-bg-color': 'rgb(var(--brand-primary-rgb) / 0.08)',
  '--el-menu-hover-text-color': 'var(--text-primary)',
  '--el-component-size': 'var(--control-height)',
  '--el-font-family': 'var(--font-family-sans)',
  '--el-border-radius-base': 'var(--radius-md)',
  '--el-border-radius-small': 'var(--radius-sm)',
  '--el-border-radius-round': 'var(--radius-pill)',
  '--el-transition-duration': 'var(--motion-duration-normal)',
  '--el-transition-duration-fast': 'var(--motion-duration-fast)',
}

const darkElementPlusMappings = {
  '--el-color-primary-light-3':
    'color-mix(in srgb, var(--brand-primary) 68%, var(--surface-solid-page))',
  '--el-color-primary-light-5':
    'color-mix(in srgb, var(--brand-primary) 50%, var(--surface-solid-page))',
  '--el-color-primary-light-7':
    'color-mix(in srgb, var(--brand-primary) 34%, var(--surface-solid-page))',
  '--el-color-primary-light-8':
    'color-mix(in srgb, var(--brand-primary) 24%, var(--surface-solid-page))',
  '--el-color-primary-light-9':
    'color-mix(in srgb, var(--brand-primary) 16%, var(--surface-solid-page))',
  '--el-color-primary-dark-2': 'color-mix(in srgb, var(--brand-primary) 82%, white)',
  '--el-color-success-light-3':
    'color-mix(in srgb, var(--status-success) 64%, var(--surface-solid-page))',
  '--el-color-success-light-5':
    'color-mix(in srgb, var(--status-success) 48%, var(--surface-solid-page))',
  '--el-color-success-light-7':
    'color-mix(in srgb, var(--status-success) 32%, var(--surface-solid-page))',
  '--el-color-success-light-8':
    'color-mix(in srgb, var(--status-success) 24%, var(--surface-solid-page))',
  '--el-color-success-light-9':
    'color-mix(in srgb, var(--status-success) 16%, var(--surface-solid-page))',
  '--el-color-success-dark-2': 'color-mix(in srgb, var(--status-success) 82%, white)',
  '--el-color-warning-light-3':
    'color-mix(in srgb, var(--status-warning) 64%, var(--surface-solid-page))',
  '--el-color-warning-light-5':
    'color-mix(in srgb, var(--status-warning) 48%, var(--surface-solid-page))',
  '--el-color-warning-light-7':
    'color-mix(in srgb, var(--status-warning) 32%, var(--surface-solid-page))',
  '--el-color-warning-light-8':
    'color-mix(in srgb, var(--status-warning) 24%, var(--surface-solid-page))',
  '--el-color-warning-light-9':
    'color-mix(in srgb, var(--status-warning) 16%, var(--surface-solid-page))',
  '--el-color-warning-dark-2': 'color-mix(in srgb, var(--status-warning) 82%, white)',
  '--el-color-danger-light-3':
    'color-mix(in srgb, var(--status-danger) 64%, var(--surface-solid-page))',
  '--el-color-danger-light-5':
    'color-mix(in srgb, var(--status-danger) 48%, var(--surface-solid-page))',
  '--el-color-danger-light-7':
    'color-mix(in srgb, var(--status-danger) 32%, var(--surface-solid-page))',
  '--el-color-danger-light-8':
    'color-mix(in srgb, var(--status-danger) 24%, var(--surface-solid-page))',
  '--el-color-danger-light-9':
    'color-mix(in srgb, var(--status-danger) 16%, var(--surface-solid-page))',
  '--el-color-danger-dark-2': 'color-mix(in srgb, var(--status-danger) 82%, white)',
  '--el-color-info-light-3':
    'color-mix(in srgb, var(--status-info) 64%, var(--surface-solid-page))',
  '--el-color-info-light-5':
    'color-mix(in srgb, var(--status-info) 48%, var(--surface-solid-page))',
  '--el-color-info-light-7':
    'color-mix(in srgb, var(--status-info) 32%, var(--surface-solid-page))',
  '--el-color-info-light-8':
    'color-mix(in srgb, var(--status-info) 24%, var(--surface-solid-page))',
  '--el-color-info-light-9':
    'color-mix(in srgb, var(--status-info) 16%, var(--surface-solid-page))',
  '--el-color-info-dark-2': 'color-mix(in srgb, var(--status-info) 82%, white)',
}

const appDrawerFocusMappings = [
  {
    selector: '.app-drawer.el-drawer .el-drawer__close-btn:focus-visible, .app-drawer.el-drawer .el-link:focus-visible',
    label: 'AppDrawer close and link focus mapping',
    declarations: { outline: '2px solid var(--border-focus)', 'outline-offset': '2px' },
  },
  {
    selector: '.app-drawer.el-drawer .el-link:focus-visible',
    label: 'AppDrawer clipped link focus mapping',
    declarations: { 'outline-offset': '-2px' },
  },
]

const componentElementPlusMappings = [
  {
    selector: '.el-table',
    label: 'Element Plus table mapping',
    declarations: {
      '--el-table-bg-color': 'transparent',
      '--el-table-tr-bg-color': 'transparent',
      '--el-table-expanded-cell-bg-color': 'transparent',
      '--el-table-header-bg-color': 'var(--surface-reading-header)',
      '--el-table-row-hover-bg-color': 'var(--surface-reading-hover)',
      '--el-table-current-row-bg-color': 'var(--surface-reading-current)',
      '--el-table-header-text-color': 'var(--text-muted)',
      '--el-table-text-color': 'var(--text-secondary)',
      '--el-table-border-color': 'var(--border-divider)',
      '--el-table-fixed-box-shadow': 'var(--shadow-panel)',
    },
  },
  {
    selector: '.el-pagination',
    label: 'Element Plus pagination mapping',
    declarations: {
      'flex-wrap': 'wrap',
      'row-gap': 'var(--layout-control-gap)',
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
    label: 'Element Plus tabs mapping',
    declarations: {
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-text-color-regular': 'var(--text-secondary)',
      '--el-border-color-light': 'var(--border-subtle)',
      '--el-color-primary': 'var(--brand-primary)',
    },
  },
  {
    selector: '.el-card',
    label: 'Element Plus card mapping',
    declarations: {
      '--el-card-padding': 'var(--layout-panel-padding)',
      '--el-card-bg-color': 'var(--surface-glass-frost)',
      '--el-card-border-color': 'var(--border-subtle)',
    },
  },
  {
    selector: '.el-dialog',
    label: 'Element Plus dialog mapping',
    declarations: {
      '--el-dialog-bg-color': 'var(--surface-solid-overlay)',
      '--el-dialog-border-radius': 'var(--radius-lg)',
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-border-color': 'var(--border-readable)',
    },
  },
  {
    selector: '.el-input, .el-textarea',
    label: 'Element Plus input mapping',
    declarations: {
      '--el-input-bg-color': 'var(--surface-solid-control)',
      '--el-input-hover-border-color': 'rgb(var(--brand-primary-rgb) / 0.48)',
      '--el-input-focus-border-color': 'var(--brand-primary)',
      '--el-input-text-color': 'var(--text-primary)',
      '--el-input-placeholder-color': 'var(--text-muted)',
      '--el-input-border-color': 'var(--border-readable)',
    },
  },
  {
    selector: '.el-select',
    label: 'Element Plus select mapping',
    declarations: {
      '--el-border-color-hover': 'rgb(var(--brand-primary-rgb) / 0.48)',
      '--el-fill-color-light': 'var(--surface-solid-disabled)',
    },
  },
  {
    selector: '.el-tag',
    label: 'Element Plus tag mapping',
    declarations: {
      '--el-tag-bg-color': 'rgb(var(--brand-primary-rgb) / 0.1)',
      '--el-tag-border-color': 'rgb(var(--brand-primary-rgb) / 0.2)',
      '--el-tag-text-color': 'var(--brand-on-surface)',
    },
  },
  {
    selector: '.el-popover.el-popper',
    label: 'Element Plus popover mapping',
    declarations: {
      '--el-popover-bg-color': 'var(--surface-solid-overlay)',
      '--el-popover-border-color': 'var(--border-readable)',
    },
  },
  {
    selector: '.el-drawer',
    label: 'Element Plus drawer mapping',
    declarations: {
      '--el-drawer-bg-color': 'var(--surface-solid-overlay)',
      '--el-text-color-primary': 'var(--text-primary)',
      '--el-border-color': 'var(--border-readable)',
    },
  },
  {
    selector:
      '.el-pagination .btn-prev:focus-visible, .el-pagination .btn-next:focus-visible, .el-pagination .el-pager li:focus-visible, .el-tabs__item:focus-visible',
    label: 'Element Plus pagination and tabs focus mapping',
    declarations: {
      outline: '2px solid var(--border-focus)',
      'outline-offset': '2px',
    },
  },
  {
    selector: '.el-loading-mask',
    label: 'Element Plus loading mask mapping',
    declarations: {
      'background-color':
        'color-mix(in srgb, var(--surface-solid-panel) 6%, transparent)',
    },
  },
  ...appDrawerFocusMappings,
]

function appDrawerFocusContractFailures(source) {
  const start = failures.length
  const { rules, statements } = enumerateTopLevelRules(source, 'AppDrawer focus proof')
  expectExactTopLevelStatements(statements, [], 'AppDrawer focus proof')
  expectClosedWorldTopLevelRules(rules, [
    ":root, html[data-theme='dark']", "html[data-theme='dark']",
    ...componentElementPlusMappings.map(({ selector }) => selector),
  ], 'AppDrawer focus proof')
  for (const { selector, label, declarations } of appDrawerFocusMappings) {
    expectExactDeclarationOracle(topLevelRuleBody(rules, selector), declarations, label)
  }
  return failures.splice(start)
}

function runAppDrawerFocusProofs(source) {
  const baseline = appDrawerFocusContractFailures(source)
  if (baseline.length) {
    failures.push(`AppDrawer focus positive proof failed: ${baseline.join(' | ')}`)
    return
  }
  console.log('POSITIVE_PROOF app-drawer-current-focus=1')
  const closeRule = /\.app-drawer\.el-drawer \.el-drawer__close-btn:focus-visible,\s*\.app-drawer\.el-drawer \.el-link:focus-visible\s*\{[^}]*\}/
  const linkRule = /\.app-drawer\.el-drawer \.el-link:focus-visible\s*\{\s*outline-offset:\s*-2px;\s*\}/
  const mutateClose = transform => source.replace(closeRule, transform)
  const mutants = [
    ['missing-close-focus', source.replace(closeRule, '')],
    ['missing-clipped-link-focus', source.replace(linkRule, '')],
    ['wrong-close-offset', mutateClose(rule => rule.replace('outline-offset: 2px', 'outline-offset: 0'))],
    ['wrong-link-offset', source.replace(linkRule, rule => rule.replace('-2px', '2px'))],
    ['hard-coded-focus-color', mutateClose(rule => rule.replace('var(--border-focus)', '#123456'))],
    ['wrong-focus-selector', mutateClose(rule => rule.replace('.app-drawer.el-drawer', '.app-drawer'))],
    ['extra-focus-rule', `${source}\n.app-drawer.el-drawer .rogue:focus-visible { outline: none; }`],
    ['duplicate-focus-color', mutateClose(rule => rule.replace('outline-offset:', 'outline: none; outline-offset:'))],
  ]
  for (const [name, mutated] of mutants) {
    if (mutated === source || appDrawerFocusContractFailures(mutated).length === 0) {
      failures.push(`AppDrawer focus mutation ${name} must change the source and be rejected`)
    } else console.log(`MUTATION_PROOF app-drawer-${name}=1`)
  }
  const safe = mutateClose(rule => rule.replace('outline:', '/* focus token is canonical */\n  outline:'))
  const safeFailures = appDrawerFocusContractFailures(safe)
  if (safe === source || safeFailures.length) failures.push(`AppDrawer focus safe comment proof failed: ${safeFailures.join(' | ')}`)
  else console.log('SAFE_PROOF app-drawer-focus-comment=1')
}

const allowedElementPlusHeaders = [
  ":root, html[data-theme='dark']",
  "html[data-theme='dark']",
  ...componentElementPlusMappings.map(({ selector }) => selector),
]
const { rules: elementPlusRules, statements: elementPlusStatements } = enumerateTopLevelRules(
  elementPlus,
  'src/styles/_element-plus.scss',
)
expectExactTopLevelStatements(
  elementPlusStatements,
  [],
  'src/styles/_element-plus.scss',
)
expectClosedWorldTopLevelRules(
  elementPlusRules,
  allowedElementPlusHeaders,
  'src/styles/_element-plus.scss',
)

const rootElementPlusBlock = topLevelRuleBody(
  elementPlusRules,
  ":root, html[data-theme='dark']",
)
expectExactDeclarationOracle(
  rootElementPlusBlock,
  rootElementPlusMappings,
  'Element Plus root mapping',
)

const darkElementPlusBlock = topLevelRuleBody(elementPlusRules, "html[data-theme='dark']")
expectExactDeclarationOracle(
  darkElementPlusBlock,
  darkElementPlusMappings,
  'dark Element Plus mapping',
)

for (const { selector, label, declarations } of componentElementPlusMappings) {
  const block = topLevelRuleBody(elementPlusRules, selector)
  expectExactDeclarationOracle(block, declarations, label)
}

if (elementPlusWithoutComments.includes('--el-select-border-color-hover')) {
  failures.push('Element Plus mapping must not contain dead --el-select-border-color-hover')
}

const importOrderFailure = themeImportOrderFailure(theme)
if (importOrderFailure !== null) failures.push(importOrderFailure)

// Legacy light/dark component blocks were replaced by mode-agnostic recipes in _components.scss.
const allowedThemeHeaders = []
const expectedThemeStatements = [
  "@use './tokens/brand'",
  "@use './tokens/semantic'",
  "@use './tokens/density'",
  "@use './tokens/layout'",
  "@use './glass'",
  "@use './element-plus'",
  "@use './overlays'",
  "@use './components'",
]
const { rules: themeRules, statements: themeStatements } = enumerateTopLevelRules(
  theme,
  'src/styles/theme.scss',
)
expectExactTopLevelStatements(
  themeStatements,
  expectedThemeStatements,
  'src/styles/theme.scss',
)
expectClosedWorldTopLevelRules(themeRules, allowedThemeHeaders, 'src/styles/theme.scss')

for (const legacyHeader of ['[data-theme="dark"] {', '[data-theme="light"] {']) {
  if (themeWithoutComments.includes(legacyHeader)) {
    failures.push(`theme.scss must not reintroduce legacy ${legacyHeader} component block`)
  }
}

if (/\[data-brand(?:\s*=|\s*\])/.test(themeWithoutComments)) {
  failures.push('theme.scss still contains extracted data-brand palette selectors')
}

const extractedThemeDeclarations = new Set([
  '--bg-primary',
  '--bg-secondary',
  '--bg-tertiary',
  '--bg-card',
  '--bg-card-hover',
  '--border-glass',
  '--border-glass-hover',
  '--accent-gradient',
  '--accent-color',
  '--accent-light',
  '--success-color',
  '--warning-color',
  '--danger-color',
  '--info-color',
  '--text-primary',
  '--text-secondary',
  '--text-muted',
  '--glow-primary',
  '--glow-card',
  '--shadow-card',
  '--shadow-card-hover',
  '--radius-sm',
  '--radius-md',
  '--radius-lg',
  '--radius-xl',
  '--reachai-workbench-breadcrumb-height',
  '--reachai-workbench-title-gap',
  '--reachai-workbench-page-padding',
  '--reachai-workbench-title-height',
  '--reachai-workbench-title-padding',
  '--reachai-workbench-title-radius',
  '--transition-fast',
  '--transition-normal',
  '--transition-slow',
  '--brand-primary',
  '--brand-hover',
  '--brand-active',
  '--brand-disabled',
  '--brand-selected-bg',
  '--brand-primary-rgb',
  '--brand-hover-rgb',
  '--brand-active-rgb',
  '--brand-selected-rgb',
  '--brand-page-bg',
  '--brand-page-bg-size',
  '--brand-page-frame-border',
  '--brand-glass-bg',
  '--brand-glass-card-bg',
  '--brand-soft-bg',
  '--brand-decorative-grid',
  '--brand-primary-gradient',
  '--el-tooltip-bg-color',
  '--el-loading-spinner-color',
  '--el-loading-bg-color',
  '--el-select-border-color-hover',
  ...Object.keys(rootElementPlusMappings),
  ...componentElementPlusMappings.flatMap(({ declarations }) =>
    Object.keys(declarations).filter((property) => property.startsWith('--')),
  ),
])
for (const declaration of extractedThemeDeclarations) {
  expectNoDeclaration(themeWithoutComments, declaration, 'theme.scss')
}

runAppDrawerFocusProofs(elementPlus)
failures.push(...forbiddenLegacySpacingTokenFailures(theme, 'src/styles/theme.scss'))
runFinalSpacingMutationProofs()

expectSassCompiles('src/styles/_element-plus.scss')
expectSassCompiles('src/styles/theme.scss')

const pageBackground = read('src/components/common/AppPageBackground.vue')
const sidebar = read('src/components/common/AppSidebar.vue')

const sidebarTemplate = extractVueTemplateBlock(sidebar, 'AppSidebar.vue').trim()
const sidebarRootTag = /^<([a-zA-Z][a-zA-Z0-9-]*)\b([\s\S]*?)>/.exec(sidebarTemplate)
if (sidebarRootTag === null) {
  failures.push('AppSidebar template root element is missing')
} else {
  const [, tagName, attributes] = sidebarRootTag
  if (tagName !== 'nav') failures.push(`AppSidebar template root must be nav; got ${tagName}`)
  const staticClass = /(?:^|\s)class\s*=\s*(["'])(.*?)\1/s.exec(attributes)
  if (staticClass === null) {
    failures.push('AppSidebar root nav must have a static class attribute')
  } else {
    const staticClassTokens = [...new Set(staticClass[2].split(/\s+/).filter(Boolean))]
    const classTokens = new Set(staticClassTokens)
    for (const classToken of ['app-sidebar', 'glass-surface-shell']) {
      if (!classTokens.has(classToken)) {
        failures.push(`AppSidebar root nav static class must contain ${classToken}`)
      }
    }
    const dynamicClassKeys = []
    for (const [index, expression] of extractRootClassBindingExpressions(
      attributes,
      'AppSidebar root nav',
    ).entries()) {
      const keys = parseObjectClassBindingKeys(
        expression,
        `AppSidebar root nav dynamic class binding ${index + 1}`,
      )
      if (keys !== null) dynamicClassKeys.push(...keys)
    }
    appSidebarReachableClassValues = enumerateReachableClassValues(
      staticClassTokens,
      dynamicClassKeys,
      'AppSidebar root nav',
    )
  }
}

const pageBackgroundStyle = extractSingleVueBlock(
  pageBackground,
  'style',
  'AppPageBackground.vue',
)
const pageBackgroundStyleWithoutComments = stripComments(pageBackgroundStyle)
const {
  rules: pageBackgroundRules,
  statements: pageBackgroundStatements,
} = enumerateTopLevelRules(pageBackgroundStyleWithoutComments, 'AppPageBackground.vue scoped style')
expectExactTopLevelStatements(
  pageBackgroundStatements,
  [],
  'AppPageBackground.vue scoped style',
)
expectClosedWorldTopLevelRules(
  pageBackgroundRules,
  ['.app-page-background', '.app-page-background.is-local'],
  'AppPageBackground.vue scoped style',
)
expectExactDeclarationOracle(
  topLevelRuleBody(pageBackgroundRules, '.app-page-background'),
  {
    position: 'absolute',
    inset: '0',
    'z-index': '0',
    overflow: 'hidden',
    'pointer-events': 'none',
    background: 'var(--surface-page-background)',
    'background-size': 'cover',
    'background-position': 'var(--theme-wallpaper-position, center)',
    'background-repeat': 'no-repeat',
    'box-shadow': 'inset 0 0 0 1px var(--border-subtle), var(--inner-highlight)',
    transition:
      'background var(--motion-duration-normal) ease, box-shadow var(--motion-duration-normal) ease',
  },
  'AppPageBackground semantic canvas',
)
expectExactDeclarationOracle(
  topLevelRuleBody(pageBackgroundRules, '.app-page-background.is-local'),
  { 'border-radius': 'inherit' },
  'AppPageBackground local radius',
)
for (const forbiddenSelector of [':global(', '[data-theme', '[data-brand']) {
  if (pageBackgroundStyleWithoutComments.includes(forbiddenSelector)) {
    failures.push(`AppPageBackground scoped style must not contain ${forbiddenSelector}`)
  }
}

const sidebarStyle = extractSingleVueBlock(sidebar, 'style', 'AppSidebar.vue')
const { rules: sidebarRules } = enumerateTopLevelRules(
  sidebarStyle,
  'AppSidebar.vue scoped style',
)
const sidebarRootRules = sidebarRules.filter((rule) => rule.header === '.app-sidebar')
if (sidebarRootRules.length !== 1) {
  failures.push(
    `AppSidebar.vue scoped style top-level .app-sidebar must appear exactly once; got ${sidebarRootRules.length}`,
  )
}
const sidebarRootDeclarations = directDeclarationEntries(
  sidebarRootRules[0]?.body ?? '',
  'AppSidebar root block',
)
function isForbiddenSidebarRootSurfaceProperty(property) {
  return (
    property === 'background' ||
    property.startsWith('background-') ||
    property === 'border' ||
    property.startsWith('border-') ||
    property === 'box-shadow' ||
    property === '-webkit-box-shadow' ||
    property === 'filter' ||
    property === '-webkit-filter' ||
    property === 'backdrop-filter' ||
    property === '-webkit-backdrop-filter' ||
    property === 'opacity' ||
    property === 'mix-blend-mode' ||
    property === 'mask' ||
    property.startsWith('mask-') ||
    property === '-webkit-mask' ||
    property.startsWith('-webkit-mask-')
  )
}

const appSidebarInternalCustomProperties = {
  '--sb-radius': 'var(--radius-lg)',
  '--sb-title': 'var(--text-primary)',
  '--sb-subtitle': 'var(--text-muted)',
  '--sb-caption': 'var(--text-muted)',
  '--sb-text': 'var(--text-secondary)',
  '--sb-child': 'var(--text-muted)',
  '--sb-icon': 'var(--text-muted)',
  '--sb-divider': 'var(--border-divider)',
  '--sb-hover-bg': 'rgb(var(--brand-primary-rgb) / 0.08)',
  '--sb-parent-active-bg': 'rgb(var(--brand-primary-rgb) / 0.1)',
  '--sb-parent-active-text': 'var(--brand-on-surface)',
  '--sb-child-active-bg': 'rgb(var(--brand-primary-rgb) / 0.12)',
  '--sb-child-active-text': 'var(--brand-on-surface)',
  '--sb-active-icon': 'var(--brand-on-surface)',
  '--sb-rail': 'var(--brand-primary)',
}

function isCanonicalAppSidebarRootRule(documentLabel, selector) {
  return (
    documentLabel === 'src/components/common/AppSidebar.vue <style 1>' &&
    /^\.app-sidebar\[data-v-glass-contract-[0-9a-f]{8}\]$/.test(selector)
  )
}

function isCanonicalAppSidebarRadiusDeclaration(documentLabel, selector, declaration) {
  return (
    isCanonicalAppSidebarRootRule(documentLabel, selector) &&
    declaration.property === 'border-radius' &&
    declaration.value === 'var(--sb-radius)'
  )
}

function isCanonicalAppSidebarInternalCustomProperty(
  documentLabel,
  selector,
  declaration,
) {
  return (
    isCanonicalAppSidebarRootRule(documentLabel, selector) &&
    Object.hasOwn(appSidebarInternalCustomProperties, declaration.property) &&
    declaration.value === appSidebarInternalCustomProperties[declaration.property]
  )
}

for (const { property } of sidebarRootDeclarations) {
  if (property.startsWith('--') && !Object.hasOwn(appSidebarInternalCustomProperties, property)) {
    failures.push(`AppSidebar root block must not own custom property ${property}`)
  }
  if (isForbiddenSidebarRootSurfaceProperty(property) && property !== 'border-radius') {
    failures.push(`AppSidebar root block must not directly declare ${property}`)
  }
}
for (const [property, value] of Object.entries(appSidebarInternalCustomProperties)) {
  expectExactDirectDeclaration(sidebarRootDeclarations, property, value, 'AppSidebar root block')
}
expectExactDirectDeclaration(
  sidebarRootDeclarations,
  'border-radius',
  'var(--sb-radius)',
  'AppSidebar root block',
)

// AppSidebar consumes theme-aware semantic tokens only; dark-only override rules are no longer needed.
const sidebarDarkRules = sidebarRules.filter((rule) =>
  splitSelectorBranches(rule.header, 'AppSidebar dark compatibility').some((branch) =>
    isDarkThemeSelectorBranch(branch),
  ),
)
expectClosedWorldTopLevelRules(sidebarDarkRules, [], 'AppSidebar retained dark compatibility')
for (const document of await compileAppSidebarOwnershipDocuments()) {
  for (const rule of enumerateCompiledCssRules(document.css, `${document.label} compiled CSS`)) {
    const declarations = directDeclarationEntries(
      rule.body,
      `${document.label} compiled selector ${rule.selector}`,
    )
    for (const selector of splitSelectorBranches(
      rule.selector,
      `${document.label} compiled CSS`,
    )) {
      const targetKinds = classifyCompiledSelectorBranch(
        selector,
        `${document.label} compiled CSS`,
      )
      const darkTargetKind = compiledSelectorIsDark(selector)
        ? targetKinds.has('root')
          ? 'root'
          : targetKinds.has('ambient-pseudo')
            ? 'ambient-pseudo'
            : null
        : null
      for (const declaration of declarations) {
        const { property } = declaration
        if (darkTargetKind !== null) {
          failures.push(
            `${document.label}: compiled dark selector ${selector} target ${darkTargetKind} must not directly declare property ${property}`,
          )
        } else if (
          (targetKinds.has('root') || targetKinds.has('ambient-pseudo')) &&
          property.startsWith('--') &&
          !isCanonicalAppSidebarInternalCustomProperty(
            document.label,
            selector,
            declaration,
          )
        ) {
          failures.push(
            `${document.label}: compiled selector ${selector} must not declare AppSidebar root/ambient custom property ${property}`,
          )
        } else if (
          targetKinds.has('root') &&
          isForbiddenSidebarRootSurfaceProperty(property) &&
          !isCanonicalAppSidebarRadiusDeclaration(document.label, selector, declaration)
        ) {
          failures.push(
            `${document.label}: compiled selector ${selector} must not declare AppSidebar root surface property ${property}`,
          )
        }
      }
    }
  }
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('ReachAI glass theme contract is aligned.')
