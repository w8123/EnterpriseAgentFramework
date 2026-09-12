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
  const cleanSemantic = ':root { --reachai-workbench-title-height: 120px; }'
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
    '--page-header-material': "url('/page-header-materials/tech-purple.webp')",
    '--page-header-material-position': 'center',
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
    '--page-header-material': "url('/page-header-materials/metro-green.webp')",
    '--page-header-material-position': 'center',
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
    '--page-header-material': "url('/page-header-materials/aurora-cyan.webp')",
    '--page-header-material-position': 'center',
  },
  'nebula-violet': {
    '--brand-primary': '#7c3aed',
    '--brand-hover': '#ec4899',
    '--brand-active': '#6d28d9',
    '--brand-disabled': '#c4b5fd',
    '--brand-selected': '#f3e8ff',
    '--brand-primary-rgb': '124 58 237',
    '--brand-hover-rgb': '236 72 153',
    '--brand-active-rgb': '109 40 217',
    '--brand-selected-rgb': '243 232 255',
    '--page-header-material': "url('/page-header-materials/nebula-violet.webp')",
    '--page-header-material-position': 'center',
  },
  'coral-rose': {
    '--brand-primary': '#db2777',
    '--brand-hover': '#f472b6',
    '--brand-active': '#be185d',
    '--brand-disabled': '#f9a8d4',
    '--brand-selected': '#fce7f3',
    '--brand-primary-rgb': '219 39 119',
    '--brand-hover-rgb': '244 114 182',
    '--brand-active-rgb': '190 24 93',
    '--brand-selected-rgb': '252 231 243',
    '--page-header-material': "url('/page-header-materials/coral-rose.webp')",
    '--page-header-material-position': 'center',
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
    '--page-header-material': "url('/page-header-materials/solar-gold.webp')",
    '--page-header-material-position': 'center',
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
    '--page-header-material': "url('/page-header-materials/deep-ocean.webp')",
    '--page-header-material-position': 'center',
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
      'color-scheme': 'light',
      '--surface-page-canvas': '#eaf4fb',
      '--surface-page-background':
        'radial-gradient(720px 520px at 54% -12%, rgb(var(--brand-primary-rgb) / 0.16), transparent 70%), radial-gradient(660px 560px at 82% 24%, rgb(var(--brand-hover-rgb) / 0.13), transparent 72%), radial-gradient(760px 420px at 50% 62%, rgb(255 255 255 / 0.42), transparent 74%), linear-gradient(150deg, #dceefa 0%, #f5fbff 50%, #e8f7f2 100%)',
      '--surface-solid-page': '#edf6fb',
      '--surface-solid-panel': 'rgb(255 255 255 / 0.82)',
      '--surface-solid-control': 'rgb(255 255 255 / 0.76)',
      '--surface-solid-overlay': 'rgb(250 253 255 / 0.94)',
      '--surface-solid-disabled': '#eef2f6',
      '--surface-glass-shell':
        'linear-gradient(145deg, rgb(255 255 255 / 0.72), rgb(var(--brand-selected-rgb) / 0.42))',
      '--surface-glass-panel':
        'linear-gradient(145deg, rgb(255 255 255 / 0.78), rgb(239 248 253 / 0.54))',
      '--surface-glass-control':
        'linear-gradient(145deg, rgb(255 255 255 / 0.82), rgb(238 247 252 / 0.62))',
      '--surface-glass-overlay':
        'linear-gradient(145deg, rgb(255 255 255 / 0.92), rgb(238 247 252 / 0.78))',
      '--surface-glass-selected':
        'linear-gradient(145deg, rgb(255 255 255 / 0.76), rgb(var(--brand-selected-rgb) / 0.76))',
      '--surface-glass-disabled':
        'linear-gradient(145deg, rgb(248 250 252 / 0.62), rgb(226 232 240 / 0.46))',
      '--surface-fallback-shell': '#eef5fb',
      '--surface-fallback-panel': '#f7fbfd',
      '--surface-fallback-control': '#f8fbfd',
      '--surface-fallback-overlay': '#f7fbfd',
      '--border-subtle': 'rgb(174 205 230 / 0.42)',
      '--border-readable': 'rgb(162 194 221 / 0.7)',
      '--border-focus': 'rgb(var(--brand-primary-rgb) / 0.72)',
      '--border-divider': 'rgb(176 204 226 / 0.42)',
      '--inner-highlight': 'inset 0 1px 0 rgb(255 255 255 / 0.82)',
      '--shadow-shell': '0 24px 64px rgb(37 72 122 / 0.12), var(--inner-highlight)',
      '--shadow-panel': '0 14px 38px rgb(37 72 122 / 0.09), var(--inner-highlight)',
      '--shadow-overlay': '0 28px 80px rgb(24 45 78 / 0.2), var(--inner-highlight)',
      '--shadow-active':
        '0 10px 30px rgb(var(--brand-primary-rgb) / 0.2), var(--inner-highlight)',
      '--shadow-danger': '0 10px 30px rgb(225 29 72 / 0.18)',
      '--glass-blur-shell': '28px',
      '--glass-blur-panel': '22px',
      '--glass-blur-overlay': '32px',
      '--glass-saturation': '1.18',
      '--text-primary': '#14233d',
      '--text-secondary': '#465a74',
      '--text-muted': '#71839c',
      '--text-disabled': '#a4b1c3',
      '--text-inverse': '#ffffff',
      '--text-link': 'var(--brand-active)',
      '--status-success': '#169b6b',
      '--status-warning': '#d97706',
      '--status-danger': '#d92d4f',
      '--status-info': '#367fbd',
      '--status-neutral': '#64748b',
      '--status-success-soft': 'rgb(22 155 107 / 0.12)',
      '--status-warning-soft': 'rgb(217 119 6 / 0.12)',
      '--status-danger-soft': 'rgb(217 45 79 / 0.12)',
      '--status-info-soft': 'rgb(54 127 189 / 0.12)',
      '--status-neutral-soft': 'rgb(100 116 139 / 0.12)',
      '--radius-sm': '6px',
      '--radius-md': '10px',
      '--radius-lg': '16px',
      '--radius-xl': '20px',
      '--motion-duration-fast': '150ms',
      '--motion-duration-normal': '240ms',
      '--motion-duration-slow': '400ms',
      '--motion-easing-standard': 'cubic-bezier(0.4, 0, 0.2, 1)',
    },
  },
  {
    selector: "[data-theme='dark']",
    declarations: {
      'color-scheme': 'dark',
      '--surface-page-canvas': '#08121c',
      '--surface-page-background':
        'radial-gradient(720px 520px at 54% -12%, rgb(var(--brand-primary-rgb) / 0.22), transparent 70%), radial-gradient(660px 560px at 82% 24%, rgb(var(--brand-hover-rgb) / 0.14), transparent 72%), linear-gradient(150deg, #07111d 0%, #0a1422 50%, #071a1a 100%)',
      '--surface-solid-page': '#0b1622',
      '--surface-solid-panel': 'rgb(16 27 42 / 0.9)',
      '--surface-solid-control': 'rgb(20 32 49 / 0.88)',
      '--surface-solid-overlay': 'rgb(15 25 39 / 0.96)',
      '--surface-solid-disabled': '#1a2839',
      '--surface-glass-shell':
        'linear-gradient(145deg, rgb(24 37 56 / 0.9), rgb(10 22 34 / 0.84))',
      '--surface-glass-panel':
        'linear-gradient(145deg, rgb(24 37 56 / 0.82), rgb(12 24 38 / 0.74))',
      '--surface-glass-control':
        'linear-gradient(145deg, rgb(30 44 64 / 0.84), rgb(17 30 47 / 0.78))',
      '--surface-glass-overlay':
        'linear-gradient(145deg, rgb(25 39 58 / 0.94), rgb(12 23 36 / 0.9))',
      '--surface-glass-selected':
        'linear-gradient(145deg, rgb(var(--brand-primary-rgb) / 0.24), rgb(var(--brand-active-rgb) / 0.16))',
      '--surface-glass-disabled':
        'linear-gradient(145deg, rgb(36 49 66 / 0.54), rgb(18 29 43 / 0.52))',
      '--surface-fallback-shell': '#111e2d',
      '--surface-fallback-panel': '#142235',
      '--surface-fallback-control': '#18283b',
      '--surface-fallback-overlay': '#111e2d',
      '--border-subtle': 'rgb(148 163 184 / 0.16)',
      '--border-readable': 'rgb(148 163 184 / 0.28)',
      '--border-focus':
        'rgb(var(--brand-disabled-rgb, var(--brand-primary-rgb)) / 0.8)',
      '--border-divider': 'rgb(148 163 184 / 0.16)',
      '--inner-highlight': 'inset 0 1px 0 rgb(255 255 255 / 0.06)',
      '--shadow-shell': '0 24px 64px rgb(0 0 0 / 0.42), var(--inner-highlight)',
      '--shadow-panel': '0 14px 38px rgb(0 0 0 / 0.32), var(--inner-highlight)',
      '--shadow-overlay': '0 28px 80px rgb(0 0 0 / 0.56), var(--inner-highlight)',
      '--shadow-active':
        '0 10px 30px rgb(var(--brand-primary-rgb) / 0.26), var(--inner-highlight)',
      '--shadow-danger': '0 10px 30px rgb(244 63 94 / 0.2)',
      '--glass-blur-shell': '26px',
      '--glass-blur-panel': '20px',
      '--glass-blur-overlay': '30px',
      '--glass-saturation': '1.08',
      '--text-primary': '#e8eef8',
      '--text-secondary': '#b7c3d4',
      '--text-muted': '#8291a8',
      '--text-disabled': '#526178',
      '--text-inverse': '#08121c',
      '--text-link': 'var(--brand-disabled)',
      '--status-success': '#45c995',
      '--status-warning': '#f6ad3c',
      '--status-danger': '#fb7185',
      '--status-info': '#72b6e8',
      '--status-neutral': '#94a3b8',
      '--status-success-soft': 'rgb(69 201 149 / 0.16)',
      '--status-warning-soft': 'rgb(246 173 60 / 0.16)',
      '--status-danger-soft': 'rgb(251 113 133 / 0.16)',
      '--status-info-soft': 'rgb(114 182 232 / 0.16)',
      '--status-neutral-soft': 'rgb(148 163 184 / 0.16)',
    },
  },
  {
    selector: ':root, [data-theme]',
    declarations: {
      '--brand-page-bg': 'var(--surface-page-background)',
      '--brand-page-frame-border': 'var(--border-subtle)',
      '--brand-glass-bg': 'var(--surface-glass-shell)',
      '--brand-glass-card-bg': 'var(--surface-glass-panel)',
      '--brand-soft-bg': 'rgb(var(--brand-selected-rgb) / 0.68)',
      '--brand-decorative-grid':
        'linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)), linear-gradient(rgb(var(--brand-primary-rgb) / 0.14) 1px, transparent 1px), linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.12) 1px, transparent 1px)',
      '--bg-primary': 'var(--surface-solid-page)',
      '--bg-secondary': 'var(--surface-solid-panel)',
      '--bg-tertiary': 'var(--surface-solid-control)',
      '--bg-card': 'var(--surface-solid-panel)',
      '--bg-card-hover': 'var(--surface-solid-control)',
      '--border-glass': 'var(--border-subtle)',
      '--border-glass-hover': 'var(--border-readable)',
      '--accent-gradient': 'var(--brand-primary-gradient)',
      '--accent-color': 'var(--brand-primary)',
      '--accent-light': 'rgb(var(--brand-primary-rgb) / 0.12)',
      '--success-color': 'var(--status-success)',
      '--warning-color': 'var(--status-warning)',
      '--danger-color': 'var(--status-danger)',
      '--info-color': 'var(--status-info)',
      '--glow-primary': '0 0 20px rgb(var(--brand-primary-rgb) / 0.24)',
      '--glow-card': '0 0 30px rgb(var(--brand-primary-rgb) / 0.08)',
      '--shadow-card': 'var(--shadow-panel)',
      '--shadow-card-hover': 'var(--shadow-active)',
      '--transition-fast': 'var(--motion-duration-fast) ease',
      '--transition-normal': 'var(--motion-duration-normal) ease',
      '--transition-slow':
        'var(--motion-duration-slow) var(--motion-easing-standard)',
      '--reachai-workbench-title-height': '120px',
      '--reachai-workbench-title-radius': 'var(--radius-lg)',
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
      '--density-compact-panel-padding': '16px',
      '--density-comfortable-control-height': '38px',
      '--density-comfortable-row-height': '48px',
      '--density-comfortable-section-gap': '20px',
      '--density-comfortable-panel-padding': '24px',
      '--density-spacious-control-height': '44px',
      '--density-spacious-row-height': '56px',
      '--density-spacious-section-gap': '28px',
      '--density-spacious-panel-padding': '32px',
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
  '.glass-surface-control',
  '.glass-surface-overlay',
]
const fallbackTokens = {
  '.glass-surface-shell': '--surface-fallback-shell',
  '.glass-surface-panel': '--surface-fallback-panel',
  '.glass-surface-control': '--surface-fallback-control',
  '.glass-surface-overlay': '--surface-fallback-overlay',
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
for (const token of glassTokenUses) {
  if (!new RegExp(`${escapeRegExp(token)}\\s*:`).test(semantic)) {
    failures.push(`glass token ${token} is not defined in src/styles/tokens/_semantic.scss`)
  }
}

expectSassCompiles('src/styles/_glass.scss')

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
  '--el-mask-color': 'rgb(8 18 28 / 0.58)',
  '--el-mask-color-extra-light': 'rgb(8 18 28 / 0.26)',
  '--el-box-shadow': 'var(--shadow-panel)',
  '--el-box-shadow-light': 'var(--shadow-panel)',
  '--el-box-shadow-lighter': 'var(--inner-highlight)',
  '--el-box-shadow-dark': 'var(--shadow-overlay)',
  '--el-disabled-bg-color': 'var(--surface-solid-disabled)',
  '--el-disabled-text-color': 'var(--text-disabled)',
  '--el-disabled-border-color': 'var(--border-subtle)',
  '--el-overlay-color': 'rgb(8 18 28 / 0.58)',
  '--el-overlay-color-light': 'rgb(8 18 28 / 0.38)',
  '--el-overlay-color-lighter': 'rgb(8 18 28 / 0.22)',
  '--el-menu-bg-color': 'transparent',
  '--el-menu-text-color': 'var(--text-secondary)',
  '--el-menu-active-color': 'var(--text-primary)',
  '--el-menu-hover-bg-color': 'rgb(var(--brand-primary-rgb) / 0.08)',
  '--el-menu-hover-text-color': 'var(--text-primary)',
  '--el-component-size': 'var(--control-height)',
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

const componentElementPlusMappings = [
  {
    selector: '.el-table',
    label: 'Element Plus table mapping',
    declarations: {
      '--el-table-bg-color': 'transparent',
      '--el-table-tr-bg-color': 'transparent',
      '--el-table-header-bg-color': 'var(--surface-solid-control)',
      '--el-table-row-hover-bg-color': 'rgb(var(--brand-primary-rgb) / 0.06)',
      '--el-table-header-text-color': 'var(--text-muted)',
      '--el-table-text-color': 'var(--text-secondary)',
      '--el-table-border-color': 'var(--border-subtle)',
      '--el-table-current-row-bg-color': 'rgb(var(--brand-primary-rgb) / 0.08)',
      '--el-table-fixed-box-shadow': 'var(--shadow-panel)',
    },
  },
  {
    selector: '.el-pagination',
    label: 'Element Plus pagination mapping',
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
      '--el-card-bg-color': 'var(--surface-solid-panel)',
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
      '--el-tag-text-color': 'var(--brand-active)',
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
        'color-mix(in srgb, var(--surface-solid-page) 78%, transparent)',
    },
  },
]

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

const allowedThemeHeaders = ['[data-theme="dark"]', '[data-theme="light"]']
const expectedThemeStatements = [
  "@use './tokens/brand'",
  "@use './tokens/semantic'",
  "@use './tokens/density'",
  "@use './tokens/layout'",
  "@use './glass'",
  "@use './element-plus'",
  "@use './overlays'",
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

const legacyThemeBlocks = [
  {
    selector: '[data-theme="dark"]',
    label: 'legacy dark component block',
    hash: '4b0e612110d5b1bb9ae6e7abc6699c8ccdc26f7726963edf13be728d48e98a25',
  },
  {
    selector: '[data-theme="light"]',
    label: 'legacy light component block',
    hash: 'a6c0a5b5d103e6a09041b69cb5e565a8854c0a3298922080a1008a409fbb9555',
  },
]
for (const { selector, label, hash } of legacyThemeBlocks) {
  const block = extractBalancedBlock(themeWithoutComments, `${selector} {`, label)
  const blockSource = balancedBlockSource(block)
  if (blockSource !== null && normalizedSha256(blockSource) !== hash) {
    failures.push(`${label} differs from the original normalized SHA-256 ${hash}`)
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
    'background-size': 'var(--brand-page-bg-size, auto)',
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
  '--sb-parent-active-text': 'var(--brand-active)',
  '--sb-child-active-bg': 'rgb(var(--brand-primary-rgb) / 0.12)',
  '--sb-child-active-text': 'var(--brand-active)',
  '--sb-active-icon': 'var(--brand-primary)',
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

const sidebarDarkRuleOracle = [
  {
    header:
      "[data-theme='dark'] .sidebar-collapse-button, [data-theme='dark'] .sidebar-project-select :deep(.el-select__wrapper), [data-theme='dark'] .sidebar-footer-popover",
    hash: 'eaac34b4488182dc56d9199afa0f691fea4835ad1a8de6e7e12a33a2916f6197',
  },
  {
    header: "[data-theme='dark'] .sidebar-project-select",
    hash: 'f125c843591caf8264826fb274f35e90e13ed7ee5a3881229fcd340ad30a7d37',
  },
  {
    header: "[data-theme='dark'] .sidebar-project-pill",
    hash: 'dcd4222943cef93f3169aa15374d6a1682cb76185a1e979493eb7a661fd3c0d2',
  },
  {
    header: "[data-theme='dark'] .footer-entry, [data-theme='dark'] .footer-tab",
    hash: '5f1c07c593533e7154d77234d2206ad5607b31988f7e5db41710015370095b79',
  },
  {
    header: "[data-theme='dark'] .brand-choice",
    hash: '7c179758643dd42f758741bcb3d8254f6a040c6ceade4d2c6b520a7a0088af99',
  },
  {
    header:
      "[data-theme='dark'] .sidebar-menu :deep(.el-sub-menu .el-menu-item.is-active)",
    hash: 'cc654ded31126189f870439be3244ca8e4ed7de78946aa4e26e2763948f16526',
  },
]
const sidebarDarkRules = sidebarRules.filter((rule) =>
  splitSelectorBranches(rule.header, 'AppSidebar dark compatibility').some((branch) =>
    isDarkThemeSelectorBranch(branch),
  ),
)
expectClosedWorldTopLevelRules(
  sidebarDarkRules,
  sidebarDarkRuleOracle.map(({ header }) => header),
  'AppSidebar retained dark compatibility',
)
for (const { header, hash } of sidebarDarkRuleOracle) {
  const matchingRules = sidebarDarkRules.filter((rule) => rule.header === header)
  if (
    matchingRules.length === 1 &&
    normalizedSha256(`${matchingRules[0].header} { ${matchingRules[0].body} }`) !== hash
  ) {
    failures.push(`AppSidebar retained dark rule ${header} differs from HEAD oracle ${hash}`)
  }
}
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
