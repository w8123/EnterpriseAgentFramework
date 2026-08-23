#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const ACTIVE_ROOTS = [
  'pom.xml',
  'sql/initV2.sql',
  'deploy',
  'ai-admin-front/src',
  'ai-common/src/main',
  'ai-runtime-contract/src/main',
  'reachai-control-service/src/main',
  'reachai-runtime-service/src/main',
  'reachai-capability-service/src/main',
  'reachai-knowledge-service/src/main',
  'reachai-model-service/src/main',
  'reachai-capability-sdk/src/main',
  'reachai-spring-boot2-starter/src/main',
]

const TEXT_EXTENSIONS = new Set([
  '.cmd', '.css', '.html', '.java', '.js', '.json', '.jsx', '.md', '.mjs',
  '.properties', '.ps1', '.scss', '.sql', '.ts', '.tsx', '.vue', '.xml', '.yaml', '.yml',
])

const RULES = [
  {
    id: 'retired-module',
    pattern: /\bai-skills-service\b/i,
    message: 'retired ai-skills-service identity is present in an active artifact',
  },
  {
    id: 'retired-table',
    pattern: /\b(?:capability_draft|capability_eval_snapshot|runtime_skill_interaction)\b/i,
    message: 'retired self-invented Skill table is present in an active artifact',
  },
  {
    id: 'legacy-graph-config',
    pattern: /\bcapabilityConfig\b/,
    message: 'legacy GraphSpec capabilityConfig key is present',
  },
  {
    id: 'legacy-graph-node',
    pattern: /(?:GraphNodeType\s*\.\s*CAPABILITY|["']type["']\s*[:=]\s*["']CAPABILITY["'])/,
    message: 'legacy GraphSpec CAPABILITY node is present',
  },
  {
    id: 'legacy-skill-kind',
    pattern: /(?:\bkind\s*=\s*["']SKILL["']|["']kind["']\s*:\s*["']SKILL["'])/i,
    message: 'retired kind=SKILL business asset is present',
  },
  {
    id: 'retired-public-route',
    pattern: /\/api\/(?:skill-mining|capability-mining|compositions)(?:\/|["'`]|$)|\/api\/skills(?:\/|["'`]|$)|\/api\/market\/skills\/submit(?:\/|["'`]|$)/i,
    message: 'retired Skill catalog/mining public route is present',
  },
]

function normalized(relativePath) {
  return relativePath.replace(/\\/g, '/')
}

function collectFiles(root, candidate, result) {
  if (!fs.existsSync(candidate)) return
  const stat = fs.statSync(candidate)
  if (stat.isFile()) {
    const extension = path.extname(candidate).toLowerCase()
    if (TEXT_EXTENSIONS.has(extension) || path.basename(candidate) === 'pom.xml') {
      result.push(candidate)
    }
    return
  }
  for (const entry of fs.readdirSync(candidate, { withFileTypes: true })) {
    if (entry.name === 'target' || entry.name === 'node_modules' || entry.name === '.git') continue
    collectFiles(root, path.join(candidate, entry.name), result)
  }
}

export function scanLegacySkillContract(rootDirectory = process.cwd(), roots = ACTIVE_ROOTS) {
  const root = path.resolve(rootDirectory)
  const files = []
  for (const relativePath of roots) collectFiles(root, path.join(root, relativePath), files)
  const failures = []
  for (const file of files) {
    const relativePath = normalized(path.relative(root, file))
    const text = fs.readFileSync(file, 'utf8')
    for (const rule of RULES) {
      const match = rule.pattern.exec(text)
      if (!match) continue
      const line = text.slice(0, match.index).split(/\r?\n/).length
      failures.push({ rule: rule.id, file: relativePath, line, message: rule.message })
    }
  }
  return failures
}

export function run(rootDirectory = process.cwd()) {
  const failures = scanLegacySkillContract(rootDirectory)
  if (failures.length > 0) {
    console.error('ReachAI legacy Skill contract check failed:')
    for (const failure of failures) {
      console.error(`- [${failure.rule}] ${failure.file}:${failure.line} ${failure.message}`)
    }
    return 1
  }
  console.log('ReachAI legacy Skill contract check passed.')
  return 0
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  process.exitCode = run()
}
