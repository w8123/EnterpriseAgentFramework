import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

// Use the frontend's declared TypeScript dependency, including when this
// script is invoked from a different working directory.
const frontendRequire = createRequire(new URL('../ai-admin-front/package.json', import.meta.url))
const ts = frontendRequire('typescript')
const { parse: parseSfc } = frontendRequire('@vue/compiler-sfc')
const { parse: parseTemplate, NodeTypes } = frontendRequire('@vue/compiler-dom')

const HTTP_METHODS = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options'])

function stripVueMarkup(text) {
  const blank = (value) => value.replace(/[^\r\n]/g, ' ')
  let result = ''
  let cursor = 0
  const scripts = /<script\b[^>]*>([\s\S]*?)<\/script\s*>/gi
  for (const match of text.matchAll(scripts)) {
    const bodyOffset = match[0].indexOf('>') + 1
    result += blank(text.slice(cursor, match.index + bodyOffset))
    result += match[1]
    result += blank(match[0].slice(bodyOffset + match[1].length))
    cursor = match.index + match[0].length
  }
  return result + blank(text.slice(cursor))
}

function imports(source) {
  const clients = new Map()
  for (const statement of source.statements) {
    if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue
    const name = statement.moduleSpecifier.text
    if (!/(?:^|\/)request$/.test(name) || !statement.importClause) continue
    const clause = statement.importClause
    if (clause.name) clients.set(clause.name.text, { name: 'knowledge', base: '/ai' })
    const bindings = clause.namedBindings
    if (!bindings || !ts.isNamedImports(bindings)) continue
    for (const item of bindings.elements) {
      const imported = item.propertyName?.text ?? item.name.text
      if (imported === 'controlRequest') clients.set(item.name.text, { name: 'control', base: '' })
      if (imported === 'modelRequest') clients.set(item.name.text, { name: 'model', base: '/model' })
    }
  }
  return clients
}

function topLevelBindings(source) {
  const constants = new Map()
  const helpers = new Map()
  for (const statement of source.statements) {
    if (ts.isVariableStatement(statement)) {
      if (!(statement.declarationList.flags & ts.NodeFlags.Const)) continue
      for (const declaration of statement.declarationList.declarations) {
        if (ts.isIdentifier(declaration.name) && declaration.initializer) {
          if (ts.isArrowFunction(declaration.initializer)) {
            const helper = declaration.initializer
            if (!ts.isBlock(helper.body)
                || (helper.body.statements.length === 1 && ts.isReturnStatement(helper.body.statements[0])
                    && helper.body.statements[0].expression)) {
              helpers.set(declaration.name.text, helper)
            }
          } else {
            constants.set(declaration.name.text, declaration.initializer)
          }
        }
      }
    } else if (ts.isFunctionDeclaration(statement) && statement.name && statement.body) {
      const returns = statement.body.statements.filter(ts.isReturnStatement)
      if (statement.body.statements.length === 1 && returns.length === 1 && returns[0].expression) {
        helpers.set(statement.name.text, statement)
      }
    }
  }
  return { constants, helpers }
}

function functionParameters(node) {
  const parameters = new Set()
  for (let current = node.parent; current; current = current.parent) {
    if (!ts.isFunctionLike(current)) continue
    for (const parameter of current.parameters) {
      if (ts.isIdentifier(parameter.name)) parameters.add(parameter.name.text)
    }
  }
  return parameters
}

function renderExpression(node, bindings, parameters, values = new Map(), seen = new Set()) {
  if (!node) return { value: '{?missing}', unresolved: true }
  if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isNonNullExpression(node)) {
    return renderExpression(node.expression, bindings, parameters, values, seen)
  }
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
    return { value: node.text, unresolved: false }
  }
  if (ts.isIdentifier(node)) {
    if (values.has(node.text)) return values.get(node.text)
    if (parameters.has(node.text)) return { value: `{${node.text}}`, unresolved: false }
    if (bindings.constants.has(node.text) && !seen.has(node.text)) {
      const next = new Set(seen).add(node.text)
      return renderExpression(bindings.constants.get(node.text), bindings, parameters, values, next)
    }
    return { value: `{?${node.text}}`, unresolved: true }
  }
  if (ts.isTemplateExpression(node)) {
    let value = node.head.text
    let unresolved = false
    for (const span of node.templateSpans) {
      const part = renderExpression(span.expression, bindings, parameters, values, seen)
      value += part.value + span.literal.text
      unresolved ||= part.unresolved
    }
    return { value, unresolved }
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const left = renderExpression(node.left, bindings, parameters, values, seen)
    const right = renderExpression(node.right, bindings, parameters, values, seen)
    return { value: left.value + right.value, unresolved: left.unresolved || right.unresolved }
  }
  if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
    if (node.expression.text === 'encodeURIComponent' && node.arguments.length === 1) {
      return renderExpression(node.arguments[0], bindings, parameters, values, seen)
    }
    const helperName = node.expression.text
    const helper = bindings.helpers.get(helperName)
    if (helper && !seen.has(`helper:${helperName}`)) {
      const nextValues = new Map(values)
      for (const [index, parameter] of helper.parameters.entries()) {
        if (ts.isIdentifier(parameter.name)) {
          nextValues.set(parameter.name.text,
            renderExpression(node.arguments[index], bindings, parameters, values, seen))
        }
      }
      const expression = ts.isBlock(helper.body) ? helper.body.statements[0].expression : helper.body
      return renderExpression(expression, bindings, parameters, nextValues, new Set(seen).add(`helper:${helperName}`))
    }
  }
  return { value: `{?${node.getText().replace(/\s+/g, ' ').slice(0, 80)}}`, unresolved: true }
}

function fetchMethod(call) {
  if (call.arguments.length < 2) return 'GET'
  const options = call.arguments[1]
  if (!ts.isObjectLiteralExpression(options)) return 'UNKNOWN'
  let method = 'GET'
  for (const property of options.properties) {
    // A spread can replace a previously supplied method. A following explicit
    // method can still make the final request unambiguous.
    if (ts.isSpreadAssignment(property)) method = 'UNKNOWN'
    if (ts.isPropertyAssignment(property) && property.name.getText() === 'method') {
      method = ts.isStringLiteral(property.initializer) ? property.initializer.text.toUpperCase() : 'UNKNOWN'
    }
  }
  return method
}

function functionName(node) {
  for (let current = node.parent; current; current = current.parent) {
    if ((ts.isFunctionDeclaration(current) || ts.isMethodDeclaration(current)) && current.name) {
      return current.name.getText()
    }
    if (ts.isVariableDeclaration(current) && ts.isIdentifier(current.name)
        && current.initializer && (ts.isArrowFunction(current.initializer)
          || ts.isFunctionExpression(current.initializer))) return current.name.text
  }
  return null
}

function surface(file) {
  if (file.startsWith('ai-admin-front/src/sdk/')) return 'embedded-sdk'
  if (file === 'ai-admin-front/src/views/EmployeePortal.vue') return 'employee-portal'
  return 'admin-frontend'
}

/** Extract request sites, not claims that a request is usable or AI-accessible. */
export function collectRequestSites(file, text) {
  const sourceText = file.endsWith('.vue') ? stripVueMarkup(text) : text
  const source = ts.createSourceFile(file, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const clients = imports(source)
  const bindings = topLevelBindings(source)
  const sites = []

  function visit(node) {
    if (ts.isCallExpression(node)) {
      let client
      let method
      if (ts.isPropertyAccessExpression(node.expression)
          && ts.isIdentifier(node.expression.expression)
          && clients.has(node.expression.expression.text)
          && HTTP_METHODS.has(node.expression.name.text)) {
        client = clients.get(node.expression.expression.text)
        method = node.expression.name.text.toUpperCase()
      } else if (ts.isIdentifier(node.expression) && node.expression.text === 'fetch') {
        client = { name: 'fetch', base: '' }
        method = fetchMethod(node)
      }
      if (client) {
        const rendered = renderExpression(node.arguments[0], bindings, functionParameters(node))
        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1
        sites.push({
          file, line, function: functionName(node), surface: surface(file), client: client.name,
          method, path: client.base + rendered.value,
          pathResolved: !rendered.unresolved, methodResolved: method !== 'UNKNOWN',
        })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  return sites
}

/** Browser-local state is a separate collaboration surface from server APIs. */
export function collectStorageSites(file, text) {
  const sourceText = file.endsWith('.vue') ? stripVueMarkup(text) : text
  const source = ts.createSourceFile(file, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const bindings = topLevelBindings(source)
  const sites = []
  function visit(node) {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
      const method = node.expression.name.text
      const target = node.expression.expression
      const storage = ts.isIdentifier(target) ? target.text
        : ts.isPropertyAccessExpression(target) ? target.name.text : ''
      if (['localStorage', 'sessionStorage'].includes(storage)
          && ['getItem', 'setItem', 'removeItem', 'clear'].includes(method)) {
        const key = method === 'clear' ? { value: null, unresolved: false }
          : renderExpression(node.arguments[0], bindings, functionParameters(node))
        sites.push({ file, line: source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1,
          function: functionName(node), surface: surface(file), storage, method,
          key: key.value, keyResolved: !key.unresolved })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  return sites
}

/** Vue event bindings are leads for a task inventory, not proof of user-facing functionality. */
export function collectVueEventSites(file, text) {
  if (!file.endsWith('.vue')) return []
  const { descriptor } = parseSfc(text, { filename: file })
  if (!descriptor.template) return []
  const template = descriptor.template
  const ast = parseTemplate(template.content)
  const sites = []
  function visit(node) {
    if (node.type === NodeTypes.ELEMENT) {
      for (const property of node.props) {
        if (property.type !== NodeTypes.DIRECTIVE || property.name !== 'on') continue
        sites.push({ file, line: template.loc.start.line + property.loc.start.line - 1,
          surface: surface(file), element: node.tag,
          event: property.arg?.isStatic ? property.arg.content : '[dynamic]',
          expression: property.exp?.content ?? null })
      }
    }
    if (node.children) node.children.forEach(visit)
  }
  visit(ast)
  return sites
}

function walk(root, relative) {
  const folder = path.join(root, relative)
  if (!fs.existsSync(folder)) return []
  return fs.readdirSync(folder, { withFileTypes: true }).flatMap((entry) => {
    const child = path.posix.join(relative, entry.name)
    if (entry.isDirectory()) return walk(root, child)
    if (!entry.isFile() || !/\.(?:ts|vue)$/.test(entry.name)
        || /(?:\.test|\.spec|\.d)\.ts$/.test(entry.name)) return []
    return [child]
  })
}

export function buildInventory(root) {
  const files = walk(root, 'ai-admin-front/src')
  const sources = files.map((file) => ({ file, text: fs.readFileSync(path.join(root, file), 'utf8') }))
  const sites = sources.flatMap(({ file, text }) => collectRequestSites(file, text))
  const storageSites = sources.flatMap(({ file, text }) => collectStorageSites(file, text))
  const vueEventSites = sources.flatMap(({ file, text }) => collectVueEventSites(file, text))
  const counts = (key) => Object.fromEntries(
    [...sites.reduce((map, site) => map.set(site[key], (map.get(site[key]) ?? 0) + 1), new Map()).entries()]
      .sort(([a], [b]) => String(a).localeCompare(String(b))),
  )
  return {
    scope: 'Static frontend request, browser storage and Vue event sites; not a complete task list or proof of runtime/AI access.',
    filesScanned: files.length,
    requestSites: sites.length,
    unresolvedPaths: sites.filter((site) => !site.pathResolved).length,
    unresolvedMethods: sites.filter((site) => !site.methodResolved).length,
    browserStorageSites: storageSites.length,
    vueEventSites: vueEventSites.length,
    bySurface: counts('surface'),
    byMethod: counts('method'),
    sites,
    storageSites,
    events: vueEventSites,
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const inventory = buildInventory(process.cwd())
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify(inventory, null, 2))
  } else {
    const { sites, storageSites, events, ...summary } = inventory
    console.log(JSON.stringify(summary, null, 2))
    console.log('Use --json to inspect each request site and unresolved expression.')
  }
}
