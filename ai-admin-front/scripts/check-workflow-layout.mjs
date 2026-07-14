import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

execFileSync('npx tsx src/utils/workflowAutoLayout.check.ts', {
  cwd: projectRoot,
  stdio: 'inherit',
  shell: true,
})
