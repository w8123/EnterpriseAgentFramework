import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import vm from 'node:vm'
import { compileString } from 'sass'
import ts from 'typescript'

const root = process.cwd()
const read = (path) => readFileSync(resolve(root, path), 'utf8')

const themeSource = read('src/composables/useTheme.ts')
const elementPlusSource = read('src/styles/_element-plus.scss')
const sdkSource = read('src/views/registry/styles/SdkAccessWizard.ai-coding.figma.scss')
const projectListSource = read('src/views/registry/RegistryProjectList.vue')
const failures = []

function expect(condition, message) {
  if (!condition) failures.push(message)
}

function expectEqual(actual, expected, message) {
  if (!Object.is(actual, expected)) {
    failures.push(`${message} (expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)})`)
  }
}

function expectIncludes(source, expected, message) {
  expect(source.includes(expected), message)
}

function proveThemeRuntimeBehavior(source) {
  const transpileResult = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
    },
    fileName: 'src/composables/useTheme.ts',
    reportDiagnostics: true,
  })
  const transpileErrors = (transpileResult.diagnostics ?? []).filter(
    (diagnostic) => diagnostic.category === ts.DiagnosticCategory.Error,
  )
  if (transpileErrors.length) {
    for (const diagnostic of transpileErrors) {
      failures.push(
        `useTheme.ts must transpile before its behavior is checked: ${ts.flattenDiagnosticMessageText(
          diagnostic.messageText,
          '\n',
        )}`,
      )
    }
    return
  }

  const attributes = new Map()
  const classes = new Set()
  const storage = new Map()
  const observerStates = []
  const pendingMutations = []
  let activeObserverCallbacks = 0
  let maximumObserverCallbackDepth = 0

  const documentElement = {
    classList: {
      add(value) {
        classes.add(value)
      },
      contains(value) {
        return classes.has(value)
      },
      remove(value) {
        classes.delete(value)
      },
    },
    getAttribute(name) {
      return attributes.get(name) ?? null
    },
    setAttribute(name, value) {
      const normalizedValue = String(value)
      attributes.set(name, normalizedValue)
      for (const state of observerStates) {
        if (
          state.target === documentElement &&
          state.options?.attributes === true &&
          (state.options.attributeFilter === undefined || state.options.attributeFilter.includes(name))
        ) {
          pendingMutations.push({
            state,
            record: { attributeName: name, target: documentElement, type: 'attributes' },
          })
        }
      }
    },
  }

  class MutationObserverMock {
    constructor(callback) {
      this.state = {
        callback,
        instance: this,
        observeCalls: 0,
        options: undefined,
        target: undefined,
      }
      observerStates.push(this.state)
    }

    disconnect() {
      this.state.target = undefined
    }

    observe(target, options) {
      this.state.observeCalls += 1
      this.state.target = target
      this.state.options = options
    }
  }

  function flushMutations(label) {
    let deliveryCount = 0
    let roundCount = 0

    while (pendingMutations.length) {
      roundCount += 1
      if (roundCount > 8) {
        failures.push(`${label} caused an unbounded data-theme observer loop`)
        pendingMutations.length = 0
        break
      }

      const batch = pendingMutations.splice(0)
      const recordsByObserver = new Map()
      for (const { state, record } of batch) {
        const records = recordsByObserver.get(state) ?? []
        records.push(record)
        recordsByObserver.set(state, records)
      }

      for (const [state, records] of recordsByObserver) {
        deliveryCount += 1
        activeObserverCallbacks += 1
        maximumObserverCallbackDepth = Math.max(maximumObserverCallbackDepth, activeObserverCallbacks)
        try {
          state.callback(records, state.instance)
        } finally {
          activeObserverCallbacks -= 1
        }
      }
    }

    expect(deliveryCount >= 1, `${label} must be delivered through the data-theme observer`)
    expect(deliveryCount <= 2, `${label} must settle without duplicate observer delivery loops`)
  }

  function ref(initialValue) {
    let value = initialValue
    const watchers = new Set()
    return {
      __watchers: watchers,
      get value() {
        return value
      },
      set value(nextValue) {
        if (Object.is(nextValue, value)) return
        value = nextValue
        for (const watcher of watchers) watcher(nextValue)
      },
    }
  }

  function watch(sourceRef, callback) {
    if (!(sourceRef?.__watchers instanceof Set)) {
      throw new TypeError('watch mock only accepts refs created by the ref mock')
    }
    sourceRef.__watchers.add(callback)
    return () => sourceRef.__watchers.delete(callback)
  }

  const localStorage = {
    getItem(key) {
      return storage.get(key) ?? null
    },
    setItem(key, value) {
      storage.set(key, String(value))
    },
  }

  const module = { exports: {} }
  const context = vm.createContext({
    MutationObserver: MutationObserverMock,
    console,
    document: { documentElement },
    exports: module.exports,
    localStorage,
    module,
    require(specifier) {
      if (specifier === 'vue') return { ref, watch }
      throw new Error(`Unexpected useTheme runtime dependency: ${specifier}`)
    },
  })

  try {
    new vm.Script(transpileResult.outputText, { filename: 'src/composables/useTheme.ts' }).runInContext(
      context,
    )
  } catch (error) {
    failures.push(`useTheme.ts must execute in the compatibility harness: ${error.stack ?? error}`)
    return
  }

  const useTheme = module.exports.useTheme
  if (typeof useTheme !== 'function') {
    failures.push('useTheme.ts must export an executable useTheme function')
    return
  }

  const firstConsumer = useTheme()
  const secondConsumer = useTheme()
  expect(firstConsumer.theme === secondConsumer.theme, 'all useTheme consumers must share one theme ref')
  expectEqual(observerStates.length, 1, 'the module must create exactly one data-theme observer')
  if (observerStates.length !== 1) return

  const [themeObserver] = observerStates
  expectEqual(themeObserver.observeCalls, 1, 'the data-theme observer must be registered exactly once')
  expect(themeObserver.target === documentElement, 'the data-theme observer must target document.documentElement')
  expectEqual(themeObserver.options?.attributes, true, 'the data-theme observer must watch attributes')
  expectEqual(
    JSON.stringify(themeObserver.options?.attributeFilter),
    JSON.stringify(['data-theme']),
    'the observer must filter exclusively to data-theme',
  )

  expectEqual(firstConsumer.theme?.value, 'light', 'theme ref must initialize to light')
  expectEqual(documentElement.getAttribute('data-theme'), 'light', 'initial DOM theme must be light')
  expect(!documentElement.classList.contains('dark'), 'initial html classList must not contain dark')
  expectEqual(localStorage.getItem('theme'), 'light', 'initial persisted theme must be light')

  documentElement.setAttribute('data-theme', 'dark')
  flushMutations('external dark theme mutation')
  expectEqual(firstConsumer.theme.value, 'dark', 'external dark DOM theme must update the shared ref')
  expect(documentElement.classList.contains('dark'), 'dark DOM theme must add html.dark')
  expectEqual(localStorage.getItem('theme'), 'dark', 'dark DOM theme must persist theme=dark')

  documentElement.setAttribute('data-theme', 'light')
  flushMutations('external light theme mutation')
  expectEqual(firstConsumer.theme.value, 'light', 'external light DOM theme must update the shared ref')
  expect(!documentElement.classList.contains('dark'), 'light DOM theme must remove html.dark')
  expectEqual(localStorage.getItem('theme'), 'light', 'light DOM theme must persist theme=light')

  documentElement.setAttribute('data-theme', 'unknown-theme')
  flushMutations('unknown theme mutation')
  expectEqual(firstConsumer.theme.value, 'light', 'unknown DOM theme values must not pollute the shared ref')
  expect(!documentElement.classList.contains('dark'), 'unknown DOM values must not fabricate html.dark')
  expectEqual(localStorage.getItem('theme'), 'light', 'unknown DOM values must not overwrite persisted theme')
  expectEqual(maximumObserverCallbackDepth, 1, 'observer callbacks must not recurse synchronously')
  expectEqual(pendingMutations.length, 0, 'all data-theme mutations must settle')
}

function parseTopLevelCssRules(css) {
  const rules = []
  let cursor = 0

  function skipWhitespaceAndComments() {
    while (cursor < css.length) {
      if (/\s/.test(css[cursor])) {
        cursor += 1
      } else if (css.startsWith('/*', cursor)) {
        const commentEnd = css.indexOf('*/', cursor + 2)
        cursor = commentEnd === -1 ? css.length : commentEnd + 2
      } else {
        break
      }
    }
  }

  while (cursor < css.length) {
    skipWhitespaceAndComments()
    if (cursor >= css.length) break

    const selectorStart = cursor
    let quote = null
    let escaped = false
    while (cursor < css.length) {
      const character = css[cursor]
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (quote) {
        if (character === quote) quote = null
      } else if (character === '"' || character === "'") {
        quote = character
      } else if (character === '{') {
        break
      }
      cursor += 1
    }
    if (cursor >= css.length) break

    const selector = css.slice(selectorStart, cursor).trim()
    cursor += 1
    const bodyStart = cursor
    let depth = 1
    quote = null
    escaped = false
    while (cursor < css.length && depth > 0) {
      const character = css[cursor]
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (quote) {
        if (character === quote) quote = null
      } else if (character === '"' || character === "'") {
        quote = character
      } else if (css.startsWith('/*', cursor)) {
        const commentEnd = css.indexOf('*/', cursor + 2)
        cursor = commentEnd === -1 ? css.length : commentEnd + 2
        continue
      } else if (character === '{') {
        depth += 1
      } else if (character === '}') {
        depth -= 1
      }
      cursor += 1
    }

    if (depth !== 0) throw new Error(`Unclosed compiled CSS rule: ${selector}`)
    rules.push({ selector, body: css.slice(bodyStart, cursor - 1) })
  }

  return rules
}

function parseDeclarations(body) {
  const declarations = new Map()
  let start = 0
  let parentheses = 0
  let quote = null
  let escaped = false

  function consume(end) {
    const declaration = body.slice(start, end).trim()
    start = end + 1
    if (!declaration) return
    const colon = declaration.indexOf(':')
    if (colon === -1) throw new Error(`Invalid compiled CSS declaration: ${declaration}`)
    const property = declaration.slice(0, colon).trim()
    const value = declaration.slice(colon + 1).trim()
    if (declarations.has(property)) throw new Error(`Duplicate compiled CSS declaration: ${property}`)
    declarations.set(property, value)
  }

  for (let cursor = 0; cursor < body.length; cursor += 1) {
    const character = body[cursor]
    if (escaped) {
      escaped = false
    } else if (character === '\\') {
      escaped = true
    } else if (quote) {
      if (character === quote) quote = null
    } else if (character === '"' || character === "'") {
      quote = character
    } else if (character === '(') {
      parentheses += 1
    } else if (character === ')') {
      parentheses -= 1
    } else if (character === ';' && parentheses === 0) {
      consume(cursor)
    }
  }
  consume(body.length)
  return declarations
}

function normalizeSelector(selector) {
  return selector.replace(/["']/g, '').replace(/\s+/g, '')
}

function normalizeValue(value) {
  return value.replace(/\s+/g, ' ').trim()
}

function proveCompiledElementPlusDarkContract(source) {
  let compiledCss
  try {
    compiledCss = compileString(source, { style: 'expanded' }).css
  } catch (error) {
    failures.push(`_element-plus.scss must compile before its dark contract is checked: ${error.message}`)
    return
  }

  let rules
  try {
    rules = parseTopLevelCssRules(compiledCss)
  } catch (error) {
    failures.push(`compiled _element-plus.scss must be parseable: ${error.message}`)
    return
  }

  const baseRule = rules.find((rule) => {
    const selectors = rule.selector.split(',').map(normalizeSelector)
    return selectors.length === 2 && selectors.includes(':root') && selectors.includes('html[data-theme=dark]')
  })
  const darkRule = rules.find((rule) => normalizeSelector(rule.selector) === 'html[data-theme=dark]')
  if (!baseRule) failures.push('compiled Element Plus aliases must apply to both :root and html[data-theme=dark]')
  if (!darkRule) failures.push('compiled Element Plus dark derivatives must use html[data-theme=dark] specificity')
  if (!baseRule || !darkRule) return

  let baseDeclarations
  let darkDeclarations
  try {
    baseDeclarations = parseDeclarations(baseRule.body)
    darkDeclarations = parseDeclarations(darkRule.body)
  } catch (error) {
    failures.push(`compiled Element Plus declarations must be parseable: ${error.message}`)
    return
  }

  for (const [property, expectedValue] of [
    ['--el-color-primary', 'var(--brand-primary)'],
    ['--el-bg-color', 'var(--surface-solid-panel)'],
    ['--el-bg-color-overlay', 'var(--surface-solid-overlay)'],
    ['--el-bg-color-page', 'var(--surface-solid-page)'],
    ['--el-text-color-primary', 'var(--text-primary)'],
    ['--el-border-color', 'var(--border-readable)'],
    ['--el-disabled-bg-color', 'var(--surface-solid-disabled)'],
  ]) {
    expectEqual(
      normalizeValue(baseDeclarations.get(property) ?? ''),
      normalizeValue(expectedValue),
      `compiled Element Plus base alias ${property} must preserve its semantic token`,
    )
  }

  for (const [property, expectedValue] of [
    [
      '--el-color-primary-light-3',
      'color-mix(in srgb, var(--brand-primary) 68%, var(--surface-solid-page))',
    ],
    ['--el-color-primary-dark-2', 'color-mix(in srgb, var(--brand-primary) 82%, white)'],
    [
      '--el-color-success-light-3',
      'color-mix(in srgb, var(--status-success) 64%, var(--surface-solid-page))',
    ],
    [
      '--el-color-warning-light-3',
      'color-mix(in srgb, var(--status-warning) 64%, var(--surface-solid-page))',
    ],
    [
      '--el-color-danger-light-3',
      'color-mix(in srgb, var(--status-danger) 64%, var(--surface-solid-page))',
    ],
    [
      '--el-color-info-light-3',
      'color-mix(in srgb, var(--status-info) 64%, var(--surface-solid-page))',
    ],
  ]) {
    expectEqual(
      normalizeValue(darkDeclarations.get(property) ?? ''),
      normalizeValue(expectedValue),
      `compiled Element Plus dark derivative ${property} must preserve its dark semantic mix`,
    )
  }
}

function expectCompiledDeclarations(rules, selector, expectedDeclarations, label) {
  const rule = rules.find((candidate) => normalizeSelector(candidate.selector) === normalizeSelector(selector))
  if (!rule) {
    failures.push(`${label} must compile selector ${selector}`)
    return
  }

  let declarations
  try {
    declarations = parseDeclarations(rule.body)
  } catch (error) {
    failures.push(`${label} declarations must be parseable: ${error.message}`)
    return
  }

  for (const [property, expectedValue] of expectedDeclarations) {
    expectEqual(
      normalizeValue(declarations.get(property) ?? ''),
      normalizeValue(expectedValue),
      `${label} ${property} must preserve its theme-aware token`,
    )
  }
}

function proveCompiledSdkContract(source) {
  let rules
  try {
    const compiledCss = compileString(source, { style: 'expanded' }).css
    rules = parseTopLevelCssRules(compiledCss)
  } catch (error) {
    failures.push(`SDK access SCSS must compile and parse before its dark contract is checked: ${error.message}`)
    return
  }

  for (const [selector, declarations] of [
    [
      '.sdk-access-page .step-progress.access-progress--refined .access-progress span',
      [['color', 'var(--sdk-muted) !important']],
    ],
    [
      '.sdk-access-page .step-progress.access-progress--refined .access-progress-track',
      [['background', 'var(--sdk-track-bg)']],
    ],
    [
      '.sdk-access-page .step-progress.access-progress--refined .progress-step.active',
      [['background', 'var(--sdk-step-active-bg) !important']],
    ],
    [
      '.sdk-access-page .step-progress.access-progress--refined .step-number',
      [
        ['background', 'var(--sdk-input-bg) !important'],
        ['color', 'var(--sdk-muted) !important'],
      ],
    ],
    [
      '.sdk-access-page .step-progress.access-progress--refined .step-copy strong',
      [['color', 'var(--sdk-text) !important']],
    ],
    [
      '.sdk-access-page .step-progress.access-progress--refined .step-copy small',
      [['color', 'var(--sdk-subtle) !important']],
    ],
  ]) {
    expectCompiledDeclarations(rules, selector, declarations, 'compiled SDK refined progress')
  }
}

function proveCompiledProjectListContract(source) {
  const styleStartTag = '<style scoped lang="scss">'
  const styleStart = source.indexOf(styleStartTag)
  const styleEnd = source.indexOf('</style>', styleStart + styleStartTag.length)
  if (styleStart === -1 || styleEnd === -1) {
    failures.push('project list scoped SCSS block is missing')
    return
  }

  let rules
  try {
    const scss = source.slice(styleStart + styleStartTag.length, styleEnd)
    const compiledCss = compileString(scss, { style: 'expanded' }).css
    rules = parseTopLevelCssRules(compiledCss)
  } catch (error) {
    failures.push(`project list SCSS must compile and parse before its dark contract is checked: ${error.message}`)
    return
  }

  expectCompiledDeclarations(
    rules,
    '.registry-project-page.is-dark .project-table-shell',
    [['background', 'var(--surface-solid-panel)']],
    'compiled project list dark table shell',
  )
  expectCompiledDeclarations(
    rules,
    '.registry-project-page.is-dark .table-footer',
    [['background', 'color-mix(in srgb, var(--surface-solid-panel) 72%, transparent)']],
    'compiled project list dark footer',
  )
}

proveThemeRuntimeBehavior(themeSource)
proveCompiledElementPlusDarkContract(elementPlusSource)
proveCompiledSdkContract(sdkSource)
proveCompiledProjectListContract(projectListSource)

const projectListDarkStart = projectListSource.indexOf('.registry-project-page.is-dark {')
const projectListDarkEnd = projectListSource.indexOf('@media (max-width: 1200px)', projectListDarkStart)
if (projectListDarkStart === -1 || projectListDarkEnd === -1) {
  failures.push('project list dark compatibility block is missing')
} else {
  const projectListDark = projectListSource.slice(projectListDarkStart, projectListDarkEnd)
  expectIncludes(
    projectListDark,
    'background: var(--surface-solid-panel);',
    'project list dark table shell must replace its hardcoded light surface',
  )
  expectIncludes(
    projectListDark,
    'background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);',
    'project list dark footer must replace its hardcoded light surface',
  )
}

const refinedMarker = '/* Shared refined access progress:'
const refinedStart = sdkSource.indexOf(refinedMarker)
const refinedEnd = sdkSource.indexOf('/* AI Coding keeps the same progress-card height', refinedStart)
if (refinedStart === -1 || refinedEnd === -1) {
  failures.push('SDK refined progress contract boundaries are missing')
} else {
  const refined = sdkSource.slice(refinedStart, refinedEnd)
  for (const [expected, message] of [
    ['color: var(--sdk-muted) !important;', 'refined progress labels must use the local muted token'],
    ['background: var(--sdk-track-bg);', 'refined progress track must use the local track token'],
    ['background: var(--sdk-step-active-bg) !important;', 'refined active step must use the local active-surface token'],
    ['background: var(--sdk-input-bg) !important;', 'refined step number must use a theme-aware local surface token'],
    ['color: var(--sdk-text) !important;', 'refined step titles must use the local text token'],
    ['color: var(--sdk-subtle) !important;', 'refined step descriptions must use the local subtle token'],
  ]) {
    expectIncludes(refined, expected, message)
  }

  for (const [forbidden, message] of [
    ['color: #1d2a44 !important;', 'refined step title still hardcodes a light-theme ink'],
    ['color: #647891 !important;', 'refined step description still hardcodes a light-theme ink'],
    ['background: rgba(255, 255, 255, 0.72);', 'refined progress track still hardcodes a light surface'],
    ['background: rgba(255, 255, 255, 0.74) !important;', 'refined step number still hardcodes a light surface'],
    ['linear-gradient(120deg, rgba(255, 255, 255, 0.76)', 'refined active step still hardcodes a light surface'],
  ]) {
    if (refined.includes(forbidden)) failures.push(message)
  }
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Dark theme compatibility contract passed.')
