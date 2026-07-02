import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const scanRoots = [
  'ai-admin-front/src',
  'ai-admin-front/scripts'
]
let failures = 0

const forbiddenRoutes = [
  {
    pattern: /(?<!@)\/api\/agent(?:\/|$|\?|#|['"`])/,
    target: '/api/runtime/**, /api/workflows/**, /api/capability-mining/**',
  },
  {
    pattern: /(?<!@)\/api\/agent\/studio(?:\/|$|\?|#|['"`])/,
    target: '/api/workflows/studio'
  },
  {
    pattern: /(?<!@)\/api\/agent\/execute(?:\/|$|\?|#|['"`])/,
    target: '/api/runtime/agents/execute'
  },
  {
    pattern: /(?<!@)\/api\/agent\/evals(?:\/|$|\?|#|['"`])/,
    target: '/api/runtime/evals'
  },
  {
    pattern: /(?<!@)\/api\/agent\/interactions(?:\/|$|\?|#|['"`])/,
    target: '/api/runtime/interactions'
  },
  {
    pattern: /(?<!@)\/api\/agent\/workflow-credentials(?:\/|$|\?|#|['"`])/,
    target: '/api/workflows/credentials'
  },
  {
    pattern: /(?<!@)\/api\/skill-mining(?:\/|$|\?|#|['"`])/,
    target: '/api/capability-mining'
  },
  {
    pattern: /(?<!@)\/api\/platform\/embed\/pages\/catalog(?:\/|$|\?|#|['"`])/,
    target: '/api/platform/embed/pages'
  }
]

function walk(rel) {
  const abs = path.join(root, rel)
  if (!fs.existsSync(abs)) {
    return []
  }
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(rel, entry.name).replaceAll('\\', '/')
    if (entry.isDirectory()) {
      return walk(child)
    }
    return entry.isFile() && /\.(ts|vue|mjs)$/.test(entry.name) ? [child] : []
  })
}

function report(file, lineNumber, text, target) {
  failures += 1
  console.error(`${file}:${lineNumber}: replace frontend route with ${target}`)
  console.error(`  ${text.trim()}`)
}

for (const scanRoot of scanRoots) {
  for (const file of walk(scanRoot)) {
    const text = fs.readFileSync(path.join(root, file), 'utf8')
    const lines = text.split(/\r?\n/)
    for (const [index, line] of lines.entries()) {
      for (const { pattern, target } of forbiddenRoutes) {
        pattern.lastIndex = 0
        if (pattern.test(line)) {
          report(file, index + 1, line, target)
        }
      }
    }
  }
}

if (failures > 0) {
  console.error(`frontend public API route check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('frontend public API route check passed')
