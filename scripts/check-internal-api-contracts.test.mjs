import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-internal-api-contracts.mjs')

function write(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

function run(root) {
  return spawnSync(process.execPath, [scriptPath], {
    cwd: root,
    encoding: 'utf8'
  })
}

const missingDocRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-internal-contract-missing-doc-'))
write(missingDocRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/internal/RuntimeHealthController.java', `
package com.enterprise.ai.runtime.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeHealthController {
    @GetMapping("/internal/runtime/health")
    Object health() { return null; }
}
`)

const missingDocResult = run(missingDocRoot)
assert.notStrictEqual(missingDocResult.status, 0, missingDocResult.stderr || missingDocResult.stdout)
assert.match(missingDocResult.stderr, /missing internal API contract doc/)

const undocumentedEndpointRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-internal-contract-undocumented-'))
write(undocumentedEndpointRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/internal/RuntimeHealthController.java', `
package com.enterprise.ai.runtime.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeHealthController {
    @GetMapping("/internal/runtime/health")
    Object health() { return null; }
}
`)
write(undocumentedEndpointRoot, 'docs/architecture/internal-api-contracts.md', `
# Internal API Contracts

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
`)

const undocumentedEndpointResult = run(undocumentedEndpointRoot)
assert.notStrictEqual(undocumentedEndpointResult.status, 0, undocumentedEndpointResult.stderr || undocumentedEndpointResult.stdout)
assert.match(undocumentedEndpointResult.stderr, /undocumented internal API/)
assert.match(undocumentedEndpointResult.stderr, /GET \/internal\/runtime\/health/)

const frontendLeakRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-internal-contract-frontend-leak-'))
write(frontendLeakRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/internal/RuntimeHealthController.java', `
package com.enterprise.ai.runtime.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeHealthController {
    @GetMapping("/internal/runtime/health")
    Object health() { return null; }
}
`)
write(frontendLeakRoot, 'docs/architecture/internal-api-contracts.md', `
# Internal API Contracts

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| \`GET /internal/runtime/health\` | \`reachai-runtime-service\` | \`reachai-control-service\` | Runtime health probe for Control aggregation. | No |
`)
write(frontendLeakRoot, 'ai-admin-front/src/api/runtime.ts', `
export function health() {
  return fetch('/internal/runtime/health')
}
`)

const frontendLeakResult = run(frontendLeakRoot)
assert.notStrictEqual(frontendLeakResult.status, 0, frontendLeakResult.stderr || frontendLeakResult.stdout)
assert.match(frontendLeakResult.stderr, /frontend must not call internal service API/)
assert.match(frontendLeakResult.stderr, /ai-admin-front\/src\/api\/runtime\.ts/)

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-internal-contract-allowed-'))
write(allowedRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/internal/RuntimeToolCallLogInternalController.java', `
package com.enterprise.ai.runtime.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runtime/tool-call-logs")
class RuntimeToolCallLogInternalController {
    @GetMapping("/recent")
    Object recent() { return null; }
}
`)
write(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/client/runtime/CapabilityRuntimeTraceClient.java', `
package com.enterprise.ai.capability.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "reachai-runtime-trace", url = "\${services.runtime-service.url:http://localhost:18604}")
interface CapabilityRuntimeTraceClient {
    @GetMapping("/internal/runtime/tool-call-logs/recent")
    Object recent();
}
`)
write(allowedRoot, 'docs/architecture/internal-api-contracts.md', `
# Internal API Contracts

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| \`GET /internal/runtime/tool-call-logs/recent\` | \`reachai-runtime-service\` | \`reachai-capability-service\` | Capability reads recent Runtime tool-call logs through Runtime owner boundary. | No |
`)
write(allowedRoot, 'ai-admin-front/src/api/control.ts', `
export function publicHealth() {
  return fetch('/api/internal-services/health')
}
`)

const allowedResult = run(allowedRoot)
assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)
