import { spawnSync } from 'node:child_process'

const checks = [
  'scripts/check-backend-domain-dependencies.mjs',
  'scripts/check-internal-module-boundaries.mjs',
  'scripts/check-runtime-kernel-structure.mjs',
  'scripts/check-service-table-ownership.mjs',
  'scripts/check-internal-api-contracts.mjs',
  'scripts/check-physical-service-route-contracts.mjs',
  'scripts/check-frontend-public-api-routes.mjs',
  'scripts/check-workflow-studio-structure.mjs',
  'scripts/check-backend-boundary-naming.mjs',
  'scripts/check-documentation.mjs',
  'scripts/check-legacy-skill-contract.mjs',
  'scripts/check-ai-coding-secret-hygiene.mjs',
  'scripts/check-mojibake.mjs'
]

for (const check of checks) {
  console.log(`\n[architecture] ${check}`)
  const result = spawnSync(process.execPath, [check], {
    cwd: process.cwd(),
    stdio: 'inherit'
  })
  if (result.status !== 0) {
    console.error(`\narchitecture verification failed at ${check}`)
    process.exit(result.status || 1)
  }
}

console.log(`\narchitecture verification passed (${checks.length} checks)`)
