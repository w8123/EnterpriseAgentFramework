import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-backend-domain-dependencies.mjs')

function writeFile(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

function writeMinimalServices(root) {
  writeFile(root, 'reachai-control-service/src/main/java/com/enterprise/ai/control/ControlOk.java', `
package com.enterprise.ai.control;

class ControlOk {}
`)
  writeFile(root, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/RuntimeOk.java', `
package com.enterprise.ai.runtime;

import com.enterprise.ai.agent.graph.GraphSpec;

class RuntimeOk {
    private GraphSpec graphSpec;
}
`)
  writeFile(root, 'reachai-runtime-service/src/main/java/com/enterprise/ai/agent/graph/GraphSpec.java', `
package com.enterprise.ai.agent.graph;

public class GraphSpec {}
`)
  writeFile(root, 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/CapabilityOk.java', `
package com.enterprise.ai.capability;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.RegistryContracts;

class CapabilityOk {
    private ScanProjectEntity project;
    private RegistryContracts contracts;
}
`)
  writeFile(root, 'reachai-capability-service/src/main/java/com/enterprise/ai/agent/capability/catalog/scan/ScanProjectEntity.java', `
package com.enterprise.ai.agent.capability.catalog.scan;

public class ScanProjectEntity {}
`)
  writeFile(root, 'reachai-capability-service/src/main/java/com/enterprise/ai/agent/registry/RegistryContracts.java', `
package com.enterprise.ai.agent.registry;

public class RegistryContracts {}
`)
  writeFile(root, 'reachai-knowledge-service/src/main/java/com/enterprise/ai/service/KnowledgeOk.java', `
package com.enterprise.ai.service;

class KnowledgeOk {}
`)
  writeFile(root, 'reachai-model-service/src/main/java/com/enterprise/ai/model/ModelOk.java', `
package com.enterprise.ai.model;

class ModelOk {}
`)
}

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-domain-deps-allowed-'))
writeMinimalServices(allowedRoot)
const allowedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: allowedRoot,
  encoding: 'utf8'
})

assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)

const forbiddenRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-domain-deps-forbidden-'))
writeMinimalServices(forbiddenRoot)
writeFile(forbiddenRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/BadControl.java', `
package com.enterprise.ai.control;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;

class BadControl {
    private RuntimeAgentExecutionService service;
}
`)
writeFile(forbiddenRoot, 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/BadRuntime.java', `
package com.enterprise.ai.runtime;

import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogService;

class BadRuntime {
    private CapabilityToolCatalogService service;
}
`)
writeFile(forbiddenRoot, 'reachai-model-service/src/main/java/com/enterprise/ai/model/BadModel.java', `
package com.enterprise.ai.model;

import com.enterprise.ai.control.platform.PlatformEmbedTokenService;

class BadModel {
    private PlatformEmbedTokenService service;
}
`)
writeFile(forbiddenRoot, 'reachai-knowledge-service/src/main/java/com/enterprise/ai/service/BadKnowledge.java', `
package com.enterprise.ai.service;

import com.enterprise.ai.agent.agent.AgentWorkflow;

class BadKnowledge {
    private AgentWorkflow workflow;
}
`)
writeFile(forbiddenRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/BadLegacyMarker.java', `
package com.enterprise.ai.control;

class BadLegacyMarker {
    private static final String CODE = "LEGACY_AGENT_SERVICE_DISABLED";
}
`)
writeFile(forbiddenRoot, 'ai-agent-service/src/main/java/com/enterprise/ai/agent/AiAgentServiceApplication.java', `
package com.enterprise.ai.agent;

class AiAgentServiceApplication {}
`)

const forbiddenResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenResult.status, 0, forbiddenResult.stderr || forbiddenResult.stdout)
assert.match(forbiddenResult.stderr, /imports runtime implementation package/)
assert.match(forbiddenResult.stderr, /imports capability implementation package/)
assert.match(forbiddenResult.stderr, /imports control implementation package/)
assert.match(forbiddenResult.stderr, /imports retired agent package/)
assert.match(forbiddenResult.stderr, /retired legacy proxy marker/)
assert.match(forbiddenResult.stderr, /retired service source root/)
