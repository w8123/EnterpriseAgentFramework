import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-physical-service-route-contracts.mjs')

function writeFile(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

const missingRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-'))
writeFile(missingRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/client/runtime/RuntimeProxyClient.java', `
package com.enterprise.ai.control.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient(name = "reachai-runtime-proxy", url = "\${services.runtime-service.url:http://localhost:18604}")
interface RuntimeProxyClient {
    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/execute")
    Object executeAgent(Object body);
}
`)
writeFile(missingRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/RuntimeHealthController.java', `
package com.enterprise.ai.runtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeHealthController {
    @GetMapping("/internal/runtime/health")
    Object health() { return null; }
}
`)

const missingResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingResult.status, 0, missingResult.stderr || missingResult.stdout)
assert.match(missingResult.stderr, /control RuntimeProxyClient route must exist in reachai-runtime-service/)
assert.match(missingResult.stderr, /POST \/api\/agent\/execute/)

const missingControlRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-control-'))
writeFile(missingControlRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/client/runtime/RuntimeProxyClient.java', `
package com.enterprise.ai.control.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient(name = "reachai-runtime-proxy", url = "\${services.runtime-service.url:http://localhost:18604}")
interface RuntimeProxyClient {
    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/execute")
    Object executeAgent(Object body);
}
`)
writeFile(missingControlRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/RuntimeAgentController.java', `
package com.enterprise.ai.runtime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeAgentController {
    @PostMapping("/api/agent/execute")
    Object executeAgent(Object body) { return null; }
}
`)

const missingControlResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingControlRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingControlResult.status, 0, missingControlResult.stderr || missingControlResult.stdout)
assert.match(missingControlResult.stderr, /control RuntimeProxyClient public route must be exposed by reachai-control-service/)
assert.match(missingControlResult.stderr, /POST \/api\/agent\/execute/)

const missingAgentRouteRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-agent-route-'))
writeFile(missingAgentRouteRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/ToolController.java', `
package com.enterprise.ai.agent.capability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ToolController {
    @GetMapping("/api/tools")
    Object listTools() { return null; }
}
`)

const missingAgentRouteResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingAgentRouteRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingAgentRouteResult.status, 0, missingAgentRouteResult.stderr || missingAgentRouteResult.stdout)
assert.match(missingAgentRouteResult.stderr, /ai-agent-service public route must be owned by reachai-control-service/)
assert.match(missingAgentRouteResult.stderr, /GET \/api\/tools/)

const forbiddenControlGenericFallbackRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-control-generic-'))
writeFile(forbiddenControlGenericFallbackRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/LegacyAgentCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LegacyAgentCompatibilityProxyController {
    @RequestMapping({"/api/{*path}", "/embed/{*path}", "/gateway/{*path}", "/mcp/{*path}", "/a2a/{*path}"})
    Object proxy() { return null; }
}
`)

const forbiddenControlGenericFallbackResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenControlGenericFallbackRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenControlGenericFallbackResult.status, 0, forbiddenControlGenericFallbackResult.stderr || forbiddenControlGenericFallbackResult.stdout)
assert.match(forbiddenControlGenericFallbackResult.stderr, /control generic legacy catch-all must stay retired/)
assert.match(forbiddenControlGenericFallbackResult.stderr, /LegacyAgentCompatibilityProxyController\.java/)

const forbiddenPlatformControlProxyRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-platform-proxy-'))
writeFile(forbiddenPlatformControlProxyRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/PlatformControlCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformControlCompatibilityProxyController {
    @RequestMapping({"/api/embed/{*path}", "/mcp/{*path}", "/a2a/{*path}", "/gateway"})
    Object proxy() { return null; }
}
`)

const forbiddenPlatformControlProxyResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenPlatformControlProxyRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenPlatformControlProxyResult.status, 0, forbiddenPlatformControlProxyResult.stderr || forbiddenPlatformControlProxyResult.stdout)
assert.match(forbiddenPlatformControlProxyResult.stderr, /platform-control retired-route proxy must stay deleted/)
assert.match(forbiddenPlatformControlProxyResult.stderr, /PlatformControlCompatibilityProxyController\.java/)

const missingCapabilityOwnerRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-cap-owner-'))
writeFile(missingCapabilityOwnerRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/ToolController.java', `
package com.enterprise.ai.agent.capability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ToolController {
    @GetMapping("/api/tools")
    Object listTools() { return null; }
}
`)
writeFile(missingCapabilityOwnerRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/CapabilityCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityCompatibilityProxyController {
    @RequestMapping({"/api/tools", "/api/tools/{*path}"})
    Object proxy() { return null; }
}
`)

const missingCapabilityOwnerResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingCapabilityOwnerRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingCapabilityOwnerResult.status, 0, missingCapabilityOwnerResult.stderr || missingCapabilityOwnerResult.stdout)
assert.match(missingCapabilityOwnerResult.stderr, /capability public route must be owned by reachai-capability-service/)
assert.match(missingCapabilityOwnerResult.stderr, /GET \/api\/tools/)

const missingMigratedCapabilityRouteRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-migrated-cap-'))
writeFile(missingMigratedCapabilityRouteRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/ApiGraphController.java', `
package com.enterprise.ai.agent.capability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/api-graph/projects/{projectId}")
class ApiGraphController {
    @GetMapping("/snapshot")
    Object snapshot() { return null; }
}
`)
writeFile(missingMigratedCapabilityRouteRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/CapabilityCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityCompatibilityProxyController {
    @RequestMapping({"/api/api-graph", "/api/api-graph/{*path}"})
    Object proxy() { return null; }
}
`)
writeFile(missingMigratedCapabilityRouteRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/compat/CapabilityLegacyCompatibilityProxyController.java', `
package com.enterprise.ai.capability.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityLegacyCompatibilityProxyController {
    @RequestMapping({"/api/api-graph", "/api/api-graph/{*path}"})
    Object proxy() { return null; }
}
`)

const missingMigratedCapabilityRouteResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingMigratedCapabilityRouteRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingMigratedCapabilityRouteResult.status, 0, missingMigratedCapabilityRouteResult.stderr || missingMigratedCapabilityRouteResult.stdout)
assert.match(missingMigratedCapabilityRouteResult.stderr, /migrated Capability route must have a real implementation/)
assert.match(missingMigratedCapabilityRouteResult.stderr, /GET \/api\/api-graph\/projects\/\{projectId\}\/snapshot/)

const forbiddenCapabilityProxyRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-capability-proxy-'))
writeFile(forbiddenCapabilityProxyRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/compat/CapabilityLegacyCompatibilityProxyController.java', `
package com.enterprise.ai.capability.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityLegacyCompatibilityProxyController {
    @RequestMapping({"/api/skill-mining", "/api/skill-mining/{*path}", "/api/capability-mining", "/api/capability-mining/{*path}"})
    Object proxy() { return null; }
}
`)

const forbiddenCapabilityProxyResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenCapabilityProxyRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenCapabilityProxyResult.status, 0, forbiddenCapabilityProxyResult.stderr || forbiddenCapabilityProxyResult.stdout)
assert.match(forbiddenCapabilityProxyResult.stderr, /capability retired-route proxy must stay deleted/)
assert.match(forbiddenCapabilityProxyResult.stderr, /CapabilityLegacyCompatibilityProxyController\.java/)

const missingMigratedCapabilityWithoutLegacyAgentRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-migrated-cap-no-agent-'))
writeFile(missingMigratedCapabilityWithoutLegacyAgentRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/CapabilityCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityCompatibilityProxyController {
    @RequestMapping({"/api/api-graph", "/api/api-graph/{*path}"})
    Object proxy() { return null; }
}
`)
writeFile(missingMigratedCapabilityWithoutLegacyAgentRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/compat/CapabilityLegacyCompatibilityProxyController.java', `
package com.enterprise.ai.capability.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityLegacyCompatibilityProxyController {
    @RequestMapping({"/api/api-graph", "/api/api-graph/{*path}"})
    Object proxy() { return null; }
}
`)

const missingMigratedCapabilityWithoutLegacyAgentResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingMigratedCapabilityWithoutLegacyAgentRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingMigratedCapabilityWithoutLegacyAgentResult.status, 0, missingMigratedCapabilityWithoutLegacyAgentResult.stderr || missingMigratedCapabilityWithoutLegacyAgentResult.stdout)
assert.match(missingMigratedCapabilityWithoutLegacyAgentResult.stderr, /migrated Capability route must have a real implementation/)
assert.match(missingMigratedCapabilityWithoutLegacyAgentResult.stderr, /GET \/api\/api-graph\/projects\/\{projectId\}\/snapshot/)

const missingPlatformOwnerRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-missing-platform-owner-'))
writeFile(missingPlatformOwnerRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/PlatformAuthController.java', `
package com.enterprise.ai.agent.platform;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformAuthController {
    @GetMapping("/api/platform/auth/me")
    Object currentUser() { return null; }
}
`)
writeFile(missingPlatformOwnerRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/RuntimeCompatibilityController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeCompatibilityController {
    @RequestMapping({"/api/agents", "/api/agents/{*path}"})
    Object proxy() { return null; }
}
`)

const missingPlatformOwnerResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingPlatformOwnerRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingPlatformOwnerResult.status, 0, missingPlatformOwnerResult.stderr || missingPlatformOwnerResult.stdout)
assert.match(missingPlatformOwnerResult.stderr, /platform-control public route must be explicitly owned by reachai-control-service/)
assert.match(missingPlatformOwnerResult.stderr, /GET \/api\/platform\/auth\/me/)

const forbiddenRuntimeLegacyBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-runtime-legacy-'))
writeFile(forbiddenRuntimeLegacyBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeChatCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeChatCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object chat(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy chat");
    }
}
`)

const forbiddenRuntimeLegacyBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeLegacyBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeLegacyBridgeResult.status, 0, forbiddenRuntimeLegacyBridgeResult.stderr || forbiddenRuntimeLegacyBridgeResult.stdout)
assert.match(forbiddenRuntimeLegacyBridgeResult.stderr, /runtime legacy proxy gateway must not be used by physical service/)
assert.match(forbiddenRuntimeLegacyBridgeResult.stderr, /RuntimeChatCompatibilityController\.java/)

const forbiddenRuntimeEditDraftBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-edit-draft-'))
writeFile(forbiddenRuntimeEditDraftBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeWorkflowCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object editWorkflowStudioDraft(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy edit draft");
    }
}
`)

const forbiddenRuntimeEditDraftBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeEditDraftBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeEditDraftBridgeResult.status, 0, forbiddenRuntimeEditDraftBridgeResult.stderr || forbiddenRuntimeEditDraftBridgeResult.stdout)
assert.match(forbiddenRuntimeEditDraftBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeEditDraftBridgeResult.stderr, /editWorkflowStudioDraft/)

const forbiddenRuntimeGenerateDraftBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-generate-draft-'))
writeFile(forbiddenRuntimeGenerateDraftBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeWorkflowCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object generateWorkflowStudioDraft(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy generate draft");
    }
}
`)

const forbiddenRuntimeGenerateDraftBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeGenerateDraftBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeGenerateDraftBridgeResult.status, 0, forbiddenRuntimeGenerateDraftBridgeResult.stderr || forbiddenRuntimeGenerateDraftBridgeResult.stdout)
assert.match(forbiddenRuntimeGenerateDraftBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeGenerateDraftBridgeResult.stderr, /generateWorkflowStudioDraft/)

const forbiddenRuntimeDebugBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-debug-workflow-'))
writeFile(forbiddenRuntimeDebugBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeWorkflowCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object debugWorkflowRun(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy debug run");
    }
}
`)

const forbiddenRuntimeDebugBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeDebugBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeDebugBridgeResult.status, 0, forbiddenRuntimeDebugBridgeResult.stderr || forbiddenRuntimeDebugBridgeResult.stdout)
assert.match(forbiddenRuntimeDebugBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeDebugBridgeResult.stderr, /debugWorkflowRun/)

const forbiddenRuntimeDebugSessionBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-debug-session-'))
writeFile(forbiddenRuntimeDebugSessionBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeDebugSessionCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeDebugSessionCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object create(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy debug session");
    }
}
`)

const forbiddenRuntimeDebugSessionBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeDebugSessionBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeDebugSessionBridgeResult.status, 0, forbiddenRuntimeDebugSessionBridgeResult.stderr || forbiddenRuntimeDebugSessionBridgeResult.stdout)
assert.match(forbiddenRuntimeDebugSessionBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeDebugSessionBridgeResult.stderr, /RuntimeDebugSessionCompatibilityController\.java/)

const forbiddenRuntimeHumanApprovalBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-human-approval-'))
writeFile(forbiddenRuntimeHumanApprovalBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeAgentInteractionCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeAgentInteractionCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object submitHumanApproval(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy human approval");
    }
}
`)

const forbiddenRuntimeHumanApprovalBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeHumanApprovalBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeHumanApprovalBridgeResult.status, 0, forbiddenRuntimeHumanApprovalBridgeResult.stderr || forbiddenRuntimeHumanApprovalBridgeResult.stdout)
assert.match(forbiddenRuntimeHumanApprovalBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeHumanApprovalBridgeResult.stderr, /RuntimeAgentInteractionCompatibilityController\.java/)

const forbiddenRuntimeRunOpsReplayBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-runops-replay-'))
writeFile(forbiddenRuntimeRunOpsReplayBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimePublicCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimePublicCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object runOpsReplay(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy runops replay");
    }
}
`)

const forbiddenRuntimeRunOpsReplayBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeRunOpsReplayBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeRunOpsReplayBridgeResult.status, 0, forbiddenRuntimeRunOpsReplayBridgeResult.stderr || forbiddenRuntimeRunOpsReplayBridgeResult.stdout)
assert.match(forbiddenRuntimeRunOpsReplayBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeRunOpsReplayBridgeResult.stderr, /runOpsReplay/)

const forbiddenRuntimeWorkflowAiCodingBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-ai-coding-'))
writeFile(forbiddenRuntimeWorkflowAiCodingBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowAiCodingCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimeWorkflowAiCodingCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object patch(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy workflow ai-coding patch");
    }
}
`)

const forbiddenRuntimeWorkflowAiCodingBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeWorkflowAiCodingBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeWorkflowAiCodingBridgeResult.status, 0, forbiddenRuntimeWorkflowAiCodingBridgeResult.stderr || forbiddenRuntimeWorkflowAiCodingBridgeResult.stdout)
assert.match(forbiddenRuntimeWorkflowAiCodingBridgeResult.stderr, /migrated Runtime route must not use legacy bridge/)
assert.match(forbiddenRuntimeWorkflowAiCodingBridgeResult.stderr, /RuntimeWorkflowAiCodingCompatibilityController\.java/)

const forbiddenRuntimeUnexpectedBridgeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-unexpected-runtime-bridge-'))
writeFile(forbiddenRuntimeUnexpectedBridgeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimePublicCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

class RuntimePublicCompatibilityController {
    private RuntimeLegacyProxyGateway legacyProxyGateway;

    Object executeAgent(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "legacy execute");
    }

    Object unexpectedReplayFallback(Object request, Object servletRequest) {
        return legacyProxyGateway.proxy(request, servletRequest, "unexpected replay fallback");
    }
}
`)

const forbiddenRuntimeUnexpectedBridgeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRuntimeUnexpectedBridgeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenRuntimeUnexpectedBridgeResult.status, 0, forbiddenRuntimeUnexpectedBridgeResult.stderr || forbiddenRuntimeUnexpectedBridgeResult.stdout)
assert.match(forbiddenRuntimeUnexpectedBridgeResult.stderr, /runtime legacy bridge method must be on explicit migration backlog/)
assert.match(forbiddenRuntimeUnexpectedBridgeResult.stderr, /unexpectedReplayFallback/)

const forbiddenLegacyDefaultConfigRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-legacy-default-'))
writeFile(forbiddenLegacyDefaultConfigRoot, 'reachai-runtime-service/src/main/resources/application.yml', `
services:
  legacy-agent-service:
    url: \${LEGACY_AGENT_SERVICE_URL:http://localhost:18606}
`)
writeFile(forbiddenLegacyDefaultConfigRoot, 'deploy/k8s/reachai-runtime-service.yml', `
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: reachai-runtime-service
          env:
            - name: LEGACY_AGENT_SERVICE_URL
              value: http://ai-agent-service:18606
`)

const forbiddenLegacyDefaultConfigResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenLegacyDefaultConfigRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenLegacyDefaultConfigResult.status, 0, forbiddenLegacyDefaultConfigResult.stderr || forbiddenLegacyDefaultConfigResult.stdout)
assert.match(forbiddenLegacyDefaultConfigResult.stderr, /physical service config must not default to ai-agent-service:18606/)
assert.match(forbiddenLegacyDefaultConfigResult.stderr, /physical service deployment must not inject LEGACY_AGENT_SERVICE_URL by default/)

const forbiddenLegacyDeploymentRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-legacy-deploy-'))
writeFile(forbiddenLegacyDeploymentRoot, 'deploy/k8s/ai-agent-service.yml', `
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-agent-service
`)

const forbiddenLegacyDeploymentResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenLegacyDeploymentRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenLegacyDeploymentResult.status, 0, forbiddenLegacyDeploymentResult.stderr || forbiddenLegacyDeploymentResult.stdout)
assert.match(forbiddenLegacyDeploymentResult.stderr, /default deployment must not include legacy ai-agent-service/)

const forbiddenLegacyKnowledgeModelDeploymentRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-legacy-knowledge-model-deploy-'))
writeFile(forbiddenLegacyKnowledgeModelDeploymentRoot, 'deploy/k8s/ai-skills-service.yml', `
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-skills-service
`)
writeFile(forbiddenLegacyKnowledgeModelDeploymentRoot, 'deploy/k8s/ai-model-service.yml', `
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-model-service
`)
writeFile(forbiddenLegacyKnowledgeModelDeploymentRoot, 'deploy/Dockerfile.skills-service', `
FROM eclipse-temurin:17-jre
`)
writeFile(forbiddenLegacyKnowledgeModelDeploymentRoot, 'deploy/Dockerfile.model-service', `
FROM eclipse-temurin:17-jre
`)
writeFile(forbiddenLegacyKnowledgeModelDeploymentRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenLegacyKnowledgeModelDeploymentResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenLegacyKnowledgeModelDeploymentRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenLegacyKnowledgeModelDeploymentResult.status, 0, forbiddenLegacyKnowledgeModelDeploymentResult.stderr || forbiddenLegacyKnowledgeModelDeploymentResult.stdout)
assert.match(forbiddenLegacyKnowledgeModelDeploymentResult.stderr, /default deployment must not include legacy knowledge\/model service artifacts/)
assert.match(forbiddenLegacyKnowledgeModelDeploymentResult.stderr, /deploy\/k8s\/ai-skills-service.yml/)
assert.match(forbiddenLegacyKnowledgeModelDeploymentResult.stderr, /deploy\/k8s\/ai-model-service.yml/)
assert.match(forbiddenLegacyKnowledgeModelDeploymentResult.stderr, /deploy\/Dockerfile.skills-service/)
assert.match(forbiddenLegacyKnowledgeModelDeploymentResult.stderr, /deploy\/Dockerfile.model-service/)

const forbiddenFrontendLegacyControlClientRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-frontend-legacy-control-'))
writeFile(forbiddenFrontendLegacyControlClientRoot, 'ai-admin-front/src/api/request.ts', `
export const agentRequest = createInstance('')
`)
writeFile(forbiddenFrontendLegacyControlClientRoot, 'ai-admin-front/.env.development', `
VITE_AI_AGENT_SERVICE_URL=http://localhost:18603
`)
writeFile(forbiddenFrontendLegacyControlClientRoot, 'ai-admin-front/src/views/registry/RegistryProjectDetail.vue', `
throw new Error('Platform Control route is no longer proxied to ai-agent-service; migrate it into reachai-control-service')
`)
writeFile(forbiddenFrontendLegacyControlClientRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenFrontendLegacyControlClientResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenFrontendLegacyControlClientRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenFrontendLegacyControlClientResult.status, 0, forbiddenFrontendLegacyControlClientResult.stderr || forbiddenFrontendLegacyControlClientResult.stdout)
assert.match(forbiddenFrontendLegacyControlClientResult.stderr, /frontend public API client must use Control service naming/)
assert.match(forbiddenFrontendLegacyControlClientResult.stderr, /agentRequest/)
assert.match(forbiddenFrontendLegacyControlClientResult.stderr, /VITE_AI_AGENT_SERVICE_URL/)
assert.match(forbiddenFrontendLegacyControlClientResult.stderr, /no longer proxied/)

const forbiddenBackendLegacyProxyNarrativeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-backend-legacy-narrative-'))
writeFile(forbiddenBackendLegacyProxyNarrativeRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimePublicCompatibilityController.java', `
package com.enterprise.ai.runtime.compat;

/**
 * Real Runtime query implementations live beside this class; legacy execution paths proxy only during migration.
 */
class RuntimePublicCompatibilityController {
}
`)
writeFile(forbiddenBackendLegacyProxyNarrativeRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenBackendLegacyProxyNarrativeResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenBackendLegacyProxyNarrativeRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenBackendLegacyProxyNarrativeResult.status, 0, forbiddenBackendLegacyProxyNarrativeResult.stderr || forbiddenBackendLegacyProxyNarrativeResult.stdout)
assert.match(forbiddenBackendLegacyProxyNarrativeResult.stderr, /backend source must not describe physical services as legacy agent proxy/)
assert.match(forbiddenBackendLegacyProxyNarrativeResult.stderr, /legacy execution paths proxy only during migration/)

const forbiddenMainlineDocLegacyDisabledRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-doc-legacy-disabled-'))
writeFile(forbiddenMainlineDocLegacyDisabledRoot, 'docs/README.md', `
Unmigrated public routes must return \`LEGACY_AGENT_SERVICE_DISABLED\` until they are moved.
`)
writeFile(forbiddenMainlineDocLegacyDisabledRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenMainlineDocLegacyDisabledResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenMainlineDocLegacyDisabledRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenMainlineDocLegacyDisabledResult.status, 0, forbiddenMainlineDocLegacyDisabledResult.stderr || forbiddenMainlineDocLegacyDisabledResult.stdout)
assert.match(forbiddenMainlineDocLegacyDisabledResult.stderr, /mainline docs must not present legacy disabled responses as current route behavior/)
assert.match(forbiddenMainlineDocLegacyDisabledResult.stderr, /must return `LEGACY_AGENT_SERVICE_DISABLED`/)

const forbiddenMainlineDocRetiredBridgeBacklogRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-doc-retired-bridge-'))
writeFile(forbiddenMainlineDocRetiredBridgeBacklogRoot, 'docs/architecture/physical-services-and-startup.md', `
Control still uses a generic legacy fallback for unowned public routes.
Runtime routes are still pending bridge implementations.
Physical services may keep disabled public routes until later migration.
`)
writeFile(forbiddenMainlineDocRetiredBridgeBacklogRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenMainlineDocRetiredBridgeBacklogResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenMainlineDocRetiredBridgeBacklogRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenMainlineDocRetiredBridgeBacklogResult.status, 0, forbiddenMainlineDocRetiredBridgeBacklogResult.stderr || forbiddenMainlineDocRetiredBridgeBacklogResult.stdout)
assert.match(forbiddenMainlineDocRetiredBridgeBacklogResult.stderr, /mainline docs must not describe retired bridge backlogs as current work/)
assert.match(forbiddenMainlineDocRetiredBridgeBacklogResult.stderr, /generic legacy fallback/)
assert.match(forbiddenMainlineDocRetiredBridgeBacklogResult.stderr, /pending bridge/)
assert.match(forbiddenMainlineDocRetiredBridgeBacklogResult.stderr, /disabled public routes/)

const forbiddenMainlineDocDisabledRouteVocabularyRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-doc-disabled-vocab-'))
writeFile(forbiddenMainlineDocDisabledRouteVocabularyRoot, 'docs/README.md', `
Move every disabled route to a local implementation later.
`)
writeFile(forbiddenMainlineDocDisabledRouteVocabularyRoot, 'docs/architecture/legacy-retirement.md', `
Do not keep a disabled response fallback.
`)
writeFile(forbiddenMainlineDocDisabledRouteVocabularyRoot, 'docs/architecture/physical-split-route-ownership.md', `
Remaining Runtime disabled-route backlog: none.
`)
writeFile(forbiddenMainlineDocDisabledRouteVocabularyRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenMainlineDocDisabledRouteVocabularyResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenMainlineDocDisabledRouteVocabularyRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenMainlineDocDisabledRouteVocabularyResult.status, 0, forbiddenMainlineDocDisabledRouteVocabularyResult.stderr || forbiddenMainlineDocDisabledRouteVocabularyResult.stdout)
assert.match(forbiddenMainlineDocDisabledRouteVocabularyResult.stderr, /mainline docs must not use disabled route vocabulary for retired public routes/)
assert.match(forbiddenMainlineDocDisabledRouteVocabularyResult.stderr, /disabled route/)
assert.match(forbiddenMainlineDocDisabledRouteVocabularyResult.stderr, /disabled response fallback/)
assert.match(forbiddenMainlineDocDisabledRouteVocabularyResult.stderr, /disabled-route backlog/)

const forbiddenMainlineDocUnmigratedDisabledResponseRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-forbidden-doc-unmigrated-disabled-response-'))
writeFile(forbiddenMainlineDocUnmigratedDisabledResponseRoot, 'README.md', `
Unmigrated public routes should return disabled responses instead of discovering the old service.
`)
writeFile(forbiddenMainlineDocUnmigratedDisabledResponseRoot, 'docs/architecture/physical-services-and-startup.md', `
尚未迁完的兼容路径应返回禁用响应。
`)
writeFile(forbiddenMainlineDocUnmigratedDisabledResponseRoot, 'docs/README.md', `
未迁完时返回明确退场/绂佺敤鍝嶅簲。
`)
writeFile(forbiddenMainlineDocUnmigratedDisabledResponseRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/TestCapabilityController.java', `
package com.enterprise.ai.capability;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestCapabilityController {
    @RequestMapping("/api/{*path}")
    Object anyCapabilityRoute() { return null; }
}
`)

const forbiddenMainlineDocUnmigratedDisabledResponseResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenMainlineDocUnmigratedDisabledResponseRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenMainlineDocUnmigratedDisabledResponseResult.status, 0, forbiddenMainlineDocUnmigratedDisabledResponseResult.stderr || forbiddenMainlineDocUnmigratedDisabledResponseResult.stdout)
assert.match(forbiddenMainlineDocUnmigratedDisabledResponseResult.stderr, /mainline docs must not present unmigrated public routes as disabled responses/)
assert.match(forbiddenMainlineDocUnmigratedDisabledResponseResult.stderr, /should return disabled responses/)
assert.match(forbiddenMainlineDocUnmigratedDisabledResponseResult.stderr, /应返回禁用响应/)
assert.match(forbiddenMainlineDocUnmigratedDisabledResponseResult.stderr, /绂佺敤鍝嶅簲/)

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-route-contracts-allowed-'))
writeFile(allowedRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/client/runtime/RuntimeProxyClient.java', `
package com.enterprise.ai.control.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient(name = "reachai-runtime-proxy", url = "\${services.runtime-service.url:http://localhost:18604}")
interface RuntimeProxyClient {
    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/execute")
    Object executeAgent(Object body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/debug-sessions/{sessionId}/submit")
    Object submitDebugSession(Object body);
}
`)
writeFile(allowedRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/RuntimeAgentController.java', `
package com.enterprise.ai.runtime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeAgentController {
    @PostMapping("/api/agent/execute")
    Object executeAgent(Object body) { return null; }

    @RequestMapping({"/api/runtime/debug-sessions", "/api/runtime/debug-sessions/{*path}"})
    Object debugSession(Object body) { return null; }
}
`)
writeFile(allowedRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/RuntimeCompatibilityController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeCompatibilityController {
    @PostMapping("/api/agent/execute")
    Object executeAgent(Object body) { return null; }

    @PostMapping("/api/runtime/debug-sessions/{sessionId}/submit")
    Object submitDebugSession(Object body) { return null; }
}
`)
writeFile(allowedRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/ToolController.java', `
package com.enterprise.ai.agent.capability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ToolController {
    @GetMapping("/api/tools")
    Object listTools() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/CapabilityCompatibilityProxyController.java', `
package com.enterprise.ai.control.compat;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CapabilityCompatibilityProxyController {
    @RequestMapping({"/api/tools", "/api/tools/{*path}"})
    Object proxy() { return null; }
}
`)
writeFile(allowedRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/PlatformAuthController.java', `
package com.enterprise.ai.agent.platform;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformAuthController {
    @GetMapping("/api/platform/auth/me")
    Object currentUser() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/identity/PlatformIdentityController.java', `
package com.enterprise.ai.control.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformIdentityController {
    @GetMapping("/api/platform/auth/me")
    Object currentUser() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/mining/CapabilityMiningController.java', `
package com.enterprise.ai.capability.catalog.mining;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/skill-mining", "/api/capability-mining"})
class CapabilityMiningController {
    @GetMapping("/precheck")
    Object precheck() { return null; }

    @PostMapping("/drafts/generate")
    Object generate() { return null; }

    @GetMapping("/drafts")
    Object drafts() { return null; }

    @PostMapping("/drafts/{id}/status")
    Object status(@PathVariable Long id) { return null; }

    @PostMapping("/drafts/{id}/publish")
    Object publish(@PathVariable Long id) { return null; }

    @PostMapping("/drafts/from-trace")
    Object fromTrace() { return null; }

    @PostMapping("/drafts/from-canvas")
    Object fromCanvas() { return null; }

    @PostMapping("/demo-traces/generate")
    Object demoGenerate() { return null; }

    @PostMapping("/demo-traces/clear")
    Object demoClear() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/scan/CapabilityScanProjectCatalogController.java', `
package com.enterprise.ai.capability.catalog.scan;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-projects")
class CapabilityScanProjectCatalogController {
    @GetMapping
    Object list() { return null; }

    @PostMapping
    Object create() { return null; }

    @GetMapping("/{id}")
    Object detail() { return null; }

    @PutMapping("/{id}")
    Object update() { return null; }

    @PatchMapping("/{id}/auth-settings")
    Object updateAuthSettings() { return null; }

    @PatchMapping("/{id}/registry-credential")
    Object updateRegistryCredential() { return null; }

    @PostMapping("/{id}/sdk-access-check")
    Object checkSdkAccess() { return null; }

    @PatchMapping("/{id}/scan-settings")
    Object updateScanSettings() { return null; }

    @DeleteMapping("/{id}")
    Object delete() { return null; }

    @PostMapping("/{id}/scan")
    Object scan() { return null; }

    @PostMapping("/{id}/rescan")
    Object rescan() { return null; }

    @PostMapping("/{id}/sensitive-data/scan")
    Object startSensitiveDataScan() { return null; }

    @GetMapping("/{id}/sensitive-data/status")
    Object sensitiveDataScanStatus() { return null; }

    @GetMapping("/{id}/tools")
    Object tools() { return null; }

    @PostMapping("/{projectId}/tools/reconcile")
    Object reconcileTools() { return null; }

    @GetMapping("/{projectId}/scan-tools/{scanToolId}")
    Object tool() { return null; }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/rescan-from-source")
    Object rescanTool() { return null; }

    @PutMapping("/{projectId}/scan-tools/{scanToolId}")
    Object updateTool() { return null; }

    @PutMapping("/{projectId}/scan-tools/{scanToolId}/toggle")
    Object toggleTool() { return null; }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/test")
    Object testTool() { return null; }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/promote-to-tool")
    Object promoteTool() { return null; }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/unpromote-from-global")
    Object unpromoteTool() { return null; }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/push-to-global-tool")
    Object pushToolToGlobal() { return null; }

    @PostMapping("/{projectId}/scan-tools/promote-by-module")
    Object promoteModuleTools() { return null; }

    @GetMapping("/{id}/diff-summary")
    Object diffSummary() { return null; }

    @GetMapping("/{id}/operation-blockers")
    Object operationBlockers() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/tool/CapabilityToolCatalogController.java', `
package com.enterprise.ai.capability.catalog.tool;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
class CapabilityToolCatalogController {
    @GetMapping
    Object list() { return null; }

    @PostMapping
    Object create() { return null; }

    @GetMapping("/{name}")
    Object get() { return null; }

    @PutMapping("/{name}")
    Object update() { return null; }

    @DeleteMapping("/{name}")
    Object delete() { return null; }

    @PutMapping("/{name}/toggle")
    Object toggle() { return null; }

    @PostMapping("/{name}/test")
    Object test() { return null; }

    @GetMapping("/{name}/metrics")
    Object metrics() { return null; }

    @GetMapping("/pending-interactions/admin-test")
    Object pending() { return null; }

    @DeleteMapping("/pending-interactions/admin-test/{interactionId}")
    Object cancelPending() { return null; }

    @PostMapping("/pending-interactions/admin-test/cancel-all")
    Object cancelAllPending() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/retrieval/CapabilityToolRetrievalController.java', `
package com.enterprise.ai.capability.catalog.retrieval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tool-retrieval")
class CapabilityToolRetrievalController {
    @PostMapping("/search")
    Object search() { return null; }

    @PostMapping("/rebuild")
    Object rebuild() { return null; }

    @GetMapping("/rebuild/status")
    Object status() { return null; }

    @GetMapping("/health")
    Object health() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/composition/CapabilityCompositionCatalogController.java', `
package com.enterprise.ai.capability.catalog.composition;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compositions")
class CapabilityCompositionCatalogController {
    @GetMapping
    Object list() { return null; }

    @PostMapping
    Object create() { return null; }

    @GetMapping("/{name}")
    Object get() { return null; }

    @PutMapping("/{name}")
    Object update() { return null; }

    @DeleteMapping("/{name}")
    Object delete() { return null; }

    @PutMapping("/{name}/toggle")
    Object toggle() { return null; }

    @PostMapping("/{name}/test")
    Object test() { return null; }

    @PostMapping("/{name}/test/resume")
    Object testResume() { return null; }

    @GetMapping("/{name}/metrics")
    Object metrics() { return null; }

    @GetMapping("/pending-interactions/admin-test")
    Object pending() { return null; }

    @DeleteMapping("/pending-interactions/admin-test/{interactionId}")
    Object cancelPending() { return null; }

    @PostMapping("/pending-interactions/admin-test/cancel-all")
    Object cancelAllPending() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/semantic/CapabilitySemanticCatalogController.java', `
package com.enterprise.ai.capability.catalog.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class CapabilitySemanticCatalogController {
    @GetMapping("/semantic-docs")
    Object query() { return null; }

    @GetMapping("/scan-projects/{id}/semantic-docs")
    Object docs() { return null; }

    @PostMapping("/scan-projects/{id}/semantic/generate")
    Object batchGenerate() { return null; }

    @GetMapping("/scan-projects/{id}/semantic/status")
    Object batchStatus() { return null; }

    @PostMapping("/scan-projects/{id}/semantic/generate-project")
    Object generateProject() { return null; }

    @GetMapping("/scan-projects/{id}/modules")
    Object modules() { return null; }

    @PutMapping("/scan-modules/{id}")
    Object rename() { return null; }

    @PostMapping("/scan-modules/{id}/semantic/generate")
    Object generateModule() { return null; }

    @PostMapping("/scan-modules/merge")
    Object merge() { return null; }

    @PostMapping("/tools/{name}/semantic/generate")
    Object generateTool() { return null; }

    @PostMapping("/scan-projects/{projectId}/scan-tools/{scanToolId}/semantic/generate")
    Object generateScanTool() { return null; }

    @PutMapping("/semantic-docs/{id}")
    Object edit() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/CapabilityKernelController.java', `
package com.enterprise.ai.capability.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capabilities")
class CapabilityKernelController {
    @GetMapping
    Object listModules() { return null; }

    @PostMapping
    Object saveModule() { return null; }

    @GetMapping("/{code}/tools")
    Object listTools() { return null; }

    @PostMapping("/{code}/tools")
    Object saveTool() { return null; }

    @GetMapping("/{code}/compositions")
    Object listCompositions() { return null; }

    @PostMapping("/{code}/compositions")
    Object saveComposition() { return null; }

    @GetMapping("/{code}/interactions")
    Object listInteractions() { return null; }

    @PostMapping("/{code}/interactions")
    Object saveInteraction() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/DomainController.java', `
package com.enterprise.ai.capability.catalog;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/domains")
class DomainController {
    @GetMapping
    Object list() { return null; }

    @PostMapping
    Object create() { return null; }

    @PutMapping("/{id}")
    Object update() { return null; }

    @DeleteMapping("/{id}")
    Object delete() { return null; }

    @GetMapping("/{code}/assignments")
    Object listAssignments() { return null; }

    @PostMapping("/{code}/assignments")
    Object grantBatch() { return null; }

    @DeleteMapping("/assignments/{id}")
    Object deleteAssignment() { return null; }

    @PostMapping("/classify")
    Object classify() { return null; }

    @GetMapping("/coverage")
    Object coverage() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/ApiAssetController.java', `
package com.enterprise.ai.capability.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/api-assets")
class ApiAssetController {
    @GetMapping
    Object list() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/registry/CapabilityRegistryCompatibilityController.java', `
package com.enterprise.ai.capability.registry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registry")
class CapabilityRegistryCompatibilityController {
    @PostMapping("/projects/register")
    Object registerProject() { return null; }

    @GetMapping("/projects/{projectCode}/capability-description-settings")
    Object settings() { return null; }

    @GetMapping("/projects/{projectCode}/instances")
    Object instances() { return null; }

    @PostMapping("/projects/{projectCode}/instances/heartbeat")
    Object heartbeat() { return null; }

    @PostMapping("/projects/{projectCode}/instances/offline")
    Object offline() { return null; }

    @PostMapping("/projects/{projectCode}/instances/purge-offline")
    Object purgeOffline() { return null; }

    @PostMapping("/projects/{projectCode}/instances/status")
    Object status() { return null; }

    @PostMapping("/projects/{projectCode}/instances/governance-policy")
    Object governancePolicy() { return null; }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    Object sync() { return null; }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    Object diff() { return null; }

    @PostMapping("/projects/{projectCode}/capabilities/apply")
    Object apply() { return null; }

    @GetMapping("/projects/{projectCode}/capability-snapshots")
    Object snapshots() { return null; }

    @GetMapping("/capability-snapshots/{snapshotId}/diff-items")
    Object diffItems() { return null; }

    @PostMapping("/capability-diff-items/{diffItemId}/review")
    Object review() { return null; }
}
`)
writeFile(allowedRoot, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/graph/CapabilityApiGraphSnapshotController.java', `
package com.enterprise.ai.capability.catalog.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/api-graph/projects/{projectId}")
class CapabilityApiGraphSnapshotController {
    @GetMapping("/snapshot")
    Object snapshot() { return null; }

    @GetMapping("/candidates")
    Object candidates() { return null; }

    @PostMapping("/candidates/{edgeId}/confirm")
    Object confirm() { return null; }

    @PostMapping("/candidates/{edgeId}/reject")
    Object reject() { return null; }

    @GetMapping("/tools/{toolName}/param-hints")
    Object paramHints() { return null; }

    @PostMapping("/infer")
    Object infer() { return null; }

    @PostMapping("/infer/request-response")
    Object inferRequestResponse() { return null; }

    @PostMapping("/regenerate")
    Object regenerate() { return null; }

    @PostMapping("/rebuild")
    Object rebuild() { return null; }

    @PostMapping("/edges")
    Object upsertEdge() { return null; }

    @DeleteMapping("/edges/{edgeId}")
    Object deleteEdge() { return null; }

    @PutMapping("/layout")
    Object saveLayout() { return null; }
}
`)

const allowedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: allowedRoot,
  encoding: 'utf8'
})

assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)
