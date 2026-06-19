import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const studioSource = readFileSync(join(projectRoot, 'src/utils/studio.ts'), 'utf8')

assert.doesNotMatch(
  studioSource,
  /configVersion === 2 \? node\.data : defaults/,
  'ensureNodeV2 must not discard node.data when configVersion is missing',
)
assert.match(studioSource, /configVersion: 2 as const/)

execFileSync('npx tsx src/utils/studioCanvasNodeData.check.ts', {
  cwd: projectRoot,
  stdio: 'inherit',
  shell: true,
})

console.log('studio canvas node data assertions passed')
