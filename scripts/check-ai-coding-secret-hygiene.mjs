import { execFileSync } from 'node:child_process'
import { readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const secretPatterns = [
  {
    key: 'activation-code',
    pattern: /rhc_[A-Za-z0-9_-]{24,}/g,
  },
  {
    key: 'bearer-token',
    pattern: /Bearer\s+[A-Za-z0-9._~-]{40,}/gi,
  },
  {
    key: 'task-token-literal',
    pattern: /["']?taskToken["']?\s*[:=]\s*["']eyJ[A-Za-z0-9._-]{30,}/gi,
  },
]

if (
  process.argv[1]
  && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  checkRepository()
}

export function findSecretFindings(relativePath, text) {
  const findings = []
  for (const { key, pattern } of secretPatterns) {
    pattern.lastIndex = 0
    for (const match of text.matchAll(pattern)) {
      findings.push({
        key,
        relativePath,
        line: lineNumberAt(text, match.index || 0),
      })
    }
  }
  return findings
}

function checkRepository() {
  const findings = []
  for (const relativePath of repositoryFiles()) {
    const absolutePath = resolve(repositoryRoot, relativePath)
    let bytes
    try {
      if (statSync(absolutePath).size > MAX_TEXT_FILE_BYTES) continue
      bytes = readFileSync(absolutePath)
    } catch {
      // Deleted worktree entries are returned by git ls-files and need no scan.
      continue
    }
    if (bytes.includes(0)) continue

    findings.push(
      ...findSecretFindings(relativePath, bytes.toString('utf8')),
    )
  }

  if (findings.length) {
    console.error('AI Coding secret hygiene check failed:')
    for (const finding of findings) {
      console.error(
        `- ${finding.relativePath}:${finding.line} [${finding.key}]`,
      )
    }
    console.error('Secret values are intentionally not printed.')
    process.exitCode = 1
    return
  }

  console.log('AI Coding secret hygiene check passed.')
}

function repositoryFiles() {
  const output = execFileSync(
    'git',
    ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      maxBuffer: 20 * 1024 * 1024,
    },
  )
  return [...new Set(output.split('\0').filter(Boolean))]
}

function lineNumberAt(text, index) {
  let line = 1
  for (let offset = 0; offset < index; offset += 1) {
    if (text.charCodeAt(offset) === 10) line += 1
  }
  return line
}
