// Optional protocol smoke test invoked by PlatformMcpCodexCompatibilityTest.
// The endpoint and credential are disposable fixture values, never real account data.
import { spawn, execFileSync } from 'node:child_process'
import { createInterface } from 'node:readline'
import { dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'

const endpoint = process.argv[2]
const token = process.env.REACHAI_FIXTURE_TOKEN
if (!/^http:\/\/127\.0\.0\.1:\d+\/mcp\/platform$/.test(endpoint ?? '') || !token) {
  throw new Error('A loopback fixture endpoint and fixture token are required')
}

// Resolve the installed CLI without invoking a shell or reading its configuration into output.
let executable = 'codex'
let executableArgs = []
if (process.platform === 'win32') {
  const wrapper = execFileSync('where.exe', ['codex.cmd'], { encoding: 'utf8' }).split(/\r?\n/)[0].trim()
  executable = process.execPath
  executableArgs = [join(dirname(wrapper), 'node_modules', '@openai', 'codex', 'bin', 'codex.js')]
}
const cli = (...args) => {
  try { return execFileSync(executable, [...executableArgs, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) }
  catch { throw new Error('Could not inspect the isolated Codex MCP configuration') }
}
const existing = JSON.parse(cli('mcp', 'list', '--json'))
const server = `reachai_smoke_${randomBytes(6).toString('hex')}`
const overrides = []
for (const entry of existing) {
  if (typeof entry.name !== 'string' || !/^[A-Za-z0-9_-]+$/.test(entry.name)) {
    throw new Error('Cannot safely isolate a local MCP server name')
  }
  // Some app-provided entries are not materialized in config.toml; supply a
  // harmless complete disabled transport rather than creating an invalid fragment.
  overrides.push('-c', `mcp_servers.${entry.name}={enabled=false,command="__disabled__"}`)
}
overrides.push('-c', `mcp_servers.${server}.url=${JSON.stringify(endpoint)}`)
overrides.push('-c', `mcp_servers.${server}.bearer_token_env_var="REACHAI_FIXTURE_TOKEN"`)
const checked = JSON.parse(cli('mcp', 'list', ...overrides, '--json'))
if (checked.some(entry => entry.enabled && entry.name !== server)
    || !checked.some(entry => entry.enabled && entry.name === server)) {
  throw new Error('Could not isolate the fixture MCP server')
}

const child = spawn(executable, [...executableArgs, 'app-server', ...overrides], {
  stdio: ['pipe', 'pipe', 'pipe'], env: process.env,
})
const pending = new Map()
let nextId = 0
let diagnostic = ''
child.stderr.setEncoding('utf8')
child.stderr.on('data', chunk => { diagnostic = (diagnostic + chunk).slice(-4000) })
child.on('exit', code => {
  for (const { reject } of pending.values()) reject(new Error(`Codex app-server exited (${code})`))
  pending.clear()
})
createInterface({ input: child.stdout }).on('line', line => {
  let message
  try { message = JSON.parse(line) } catch { return }
  const waiting = pending.get(message.id)
  if (!waiting) return
  pending.delete(message.id)
  clearTimeout(waiting.timer)
  if (message.error) waiting.reject(new Error(`${message.error.code}: ${message.error.message}`))
  else waiting.resolve(message.result)
})

function call(method, params) {
  const id = ++nextId
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)) }, 15000)
    pending.set(id, { resolve, reject, timer })
    child.stdin.write(`${JSON.stringify({ id, method, params })}\n`)
  })
}

try {
  await call('initialize', { clientInfo: { name: 'reachai-mcp-smoke', version: '1.0', title: null },
    capabilities: { experimentalApi: true, requestAttestation: false } })
  child.stdin.write(`${JSON.stringify({ method: 'initialized' })}\n`)
  const started = await call('thread/start', { cwd: process.cwd(), ephemeral: true })
  const threadId = started.thread.id
  let found = false
  for (let attempt = 0; attempt < 20 && !found; attempt++) {
    const status = await call('mcpServerStatus/list', { threadId, detail: 'toolsAndAuthOnly' })
    const entry = status.data.find(item => item.name === server)
    found = Boolean(entry?.tools?.reachai_platform_profile && entry?.tools?.reachai_workflow_create_draft
      && entry?.tools?.reachai_business_method_invoke_read_only && entry?.tools?.reachai_business_index_search
      && entry?.tools?.reachai_agent_create && entry?.tools?.reachai_agent_config_versions && entry?.tools?.reachai_agent_workflow_freshness
      && entry?.tools?.reachai_agent_draft_context && entry?.tools?.reachai_agent_save_draft
      && entry?.tools?.reachai_agent_copy_version_to_draft
      && entry?.tools?.reachai_workflow_trial_read_only && entry?.tools?.reachai_workflow_trial_status
      && entry?.tools?.reachai_agent_request_publish && entry?.tools?.reachai_agent_execute_approved_publish
      && entry?.tools?.reachai_platform_approvals_list && entry?.tools?.reachai_workflow_request_publish
      && entry?.tools?.reachai_workflow_execute_approved_publish && entry?.tools?.reachai_workflow_versions
      && entry?.tools?.reachai_models_list && entry?.tools?.reachai_model_get && entry?.tools?.reachai_skills_list
      && entry?.tools?.reachai_skill_get && entry?.tools?.reachai_skill_access && entry?.tools?.reachai_skill_version_get
      && entry?.tools?.reachai_skill_bindable_versions && entry?.tools?.reachai_skill_reviews && entry?.tools?.reachai_skill_file_preview
      && entry?.tools?.reachai_knowledge_bases_list && entry?.tools?.reachai_knowledge_files && entry?.tools?.reachai_knowledge_stats
      && entry?.tools?.reachai_knowledge_dashboard && entry?.tools?.reachai_knowledge_chunks && entry?.tools?.reachai_knowledge_hit_logs
      && entry?.tools?.reachai_knowledge_file_chunks && entry?.tools?.reachai_knowledge_dependencies
      && entry?.tools?.reachai_knowledge_settings_context && entry?.tools?.reachai_knowledge_settings_preview
      && entry?.tools?.reachai_knowledge_settings_request_save && entry?.tools?.reachai_knowledge_settings_execute_approved_save)
    if (!found) await new Promise(resolve => setTimeout(resolve, 250))
  }
  if (!found) throw new Error('Codex did not discover the fixture platform tool')
  const result = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_platform_profile', arguments: {},
  })
  if (result.isError || result.structuredContent?.username !== 'fixture-user') {
    throw new Error('Codex platform profile tool did not return the expected fixture identity')
  }
  const models = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_models_list', arguments: { projectCode: 'orders', modelType: 'LLM' },
  })
  const settingsContext = await call('mcpServer/tool/call', { threadId, server, tool: 'reachai_knowledge_settings_context', arguments: { kbCode: 'fixture-kb' } })
  if (settingsContext.isError || settingsContext.structuredContent?.data?.revision !== 'a'.repeat(64)) throw new Error('Codex fixture Knowledge settings context failed')
  const settingsArgs = { kbCode: 'fixture-kb', baseRevision: 'a'.repeat(64), changes: { topK: 9, accessMode: 'ALL_CALLERS' } }
  const settingsPreview = await call('mcpServer/tool/call', { threadId, server, tool: 'reachai_knowledge_settings_preview', arguments: settingsArgs })
  if (settingsPreview.isError || settingsPreview.structuredContent?.data?.before?.topK !== 5
    || settingsPreview.structuredContent?.data?.after?.topK !== 9) throw new Error('Codex fixture Knowledge settings preview failed')
  const settingsApproval = await call('mcpServer/tool/call', { threadId, server, tool: 'reachai_knowledge_settings_request_save',
    arguments: { ...settingsArgs, invocationId: '00000000-0000-0000-0000-000000000005' } })
  if (settingsApproval.isError || settingsApproval.structuredContent?.data?.status !== 'PENDING'
    || settingsApproval.structuredContent?.data?.id !== 'fixture-settings') throw new Error('Codex fixture Knowledge settings approval failed')
  const knowledgeBases = await call('mcpServer/tool/call', { threadId, server, tool: 'reachai_knowledge_bases_list', arguments: {} })
  if (knowledgeBases.isError || knowledgeBases.structuredContent?.data?.items?.[0]?.code !== 'fixture-kb') {
    throw new Error('Codex fixture Knowledge catalog call failed')
  }
  const fileChunks = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_knowledge_file_chunks', arguments: { fileId: 'fixture-file' },
  })
  if (fileChunks.isError || fileChunks.structuredContent?.data?.items?.[0]?.content !== 'Fixture paragraph') {
    throw new Error('Codex fixture Knowledge paragraph call failed')
  }
  if (models.isError || models.structuredContent?.data?.items?.[0]?.id !== 'fixture-model') {
    throw new Error('Codex fixture model catalog call failed')
  }
  const bindable = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_skill_bindable_versions', arguments: { agentProjectCode: 'orders' },
  })
  if (bindable.isError || bindable.structuredContent?.data?.items?.[0]?.versionId !== 2) {
    throw new Error('Codex fixture bindable Skill catalog call failed')
  }
  const skillFile = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_skill_file_preview', arguments: { skillId: 1, versionId: 2, path: 'SKILL.md' },
  })
  if (skillFile.isError || skillFile.structuredContent?.data?.content !== '# Fixture package') {
    throw new Error('Codex fixture Skill file preview failed')
  }
  const draft = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_workflow_create_draft', arguments: { name: 'Fixture', keySlug: 'fixture-draft' },
  })
  if (draft.isError || draft.structuredContent?.data?.id !== 'fixture-draft') {
    throw new Error('Codex fixture draft tool call failed')
  }
  const trial = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_business_method_invoke_read_only', arguments: {
      qualifiedName: 'fixture.lookup', invocationId: '00000000-0000-0000-0000-000000000001',
      expectedContractHash: 'a'.repeat(64), expectedExecutionRevision: 'b'.repeat(64), input: { id: 'fixture' },
    },
  })
  if (trial.isError || trial.structuredContent?.data?.status !== 'SUCCEEDED') {
    throw new Error('Codex fixture business trial tool call failed')
  }
  const workflowTrialId = '00000000-0000-0000-0000-000000000002'
  const workflowTrial = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_workflow_trial_read_only', arguments: {
      workflowId: 'fixture-draft', trialId: workflowTrialId, expectedRevision: 'a'.repeat(64), inputParams: {},
    },
  })
  if (workflowTrial.isError || workflowTrial.structuredContent?.data?.success !== true) {
    throw new Error('Codex fixture Workflow trial call failed')
  }
  const workflowStatus = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_workflow_trial_status', arguments: { workflowId: 'fixture-draft', trialId: workflowTrialId },
  })
  if (workflowStatus.isError || workflowStatus.structuredContent?.data?.status !== 'SUCCEEDED') {
    throw new Error('Codex fixture Workflow trial status call failed')
  }
  const agentId = '1234567890abcdef1234567890abcdef'
  const agent = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_create', arguments: { agentId, name: 'Fixture Agent', keySlug: 'fixture-agent' },
  })
  if (agent.isError || agent.structuredContent?.data?.id !== agentId) {
    throw new Error('Codex fixture Agent creation tool call failed')
  }
  const versions = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_config_versions', arguments: { agentId },
  })
  if (versions.isError || versions.structuredContent?.data?.[0]?.status !== 'DRAFT') {
    throw new Error('Codex fixture Agent configuration read failed')
  }
  const freshness = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_workflow_freshness', arguments: { agentId },
  })
  if (freshness.isError || freshness.structuredContent?.data?.staleCount !== 1) {
    throw new Error('Codex fixture Agent Workflow freshness call failed')
  }
  const context = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_draft_context', arguments: { agentId },
  })
  if (context.isError || context.structuredContent?.data?.revision !== 'c'.repeat(64)) {
    throw new Error('Codex fixture Agent draft context call failed')
  }
  const saved = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_save_draft', arguments: {
      agentId, baseRevision: context.structuredContent.data.revision, changes: { systemPrompt: '协作配置' },
    },
  })
  if (saved.isError || saved.structuredContent?.data?.base?.systemPrompt !== '协作配置') {
    throw new Error('Codex fixture Agent draft save call failed')
  }
  const copied = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_copy_version_to_draft', arguments: {
      agentId, configVersionId: 4, baseRevision: saved.structuredContent.data.revision,
    },
  })
  if (copied.isError || copied.structuredContent?.data?.base?.status !== 'DRAFT') {
    throw new Error('Codex fixture Agent version copy call failed')
  }
  const publication = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_agent_request_publish', arguments: {
      agentId, configVersionId: 4, baseRevision: copied.structuredContent.data.revision,
      invocationId: '00000000-0000-0000-0000-000000000003',
    },
  })
  if (publication.isError || publication.structuredContent?.data?.status !== 'PENDING') {
    throw new Error('Codex fixture Agent publication request failed')
  }
  const recovered = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_platform_approvals_list', arguments: { invocationId: '00000000-0000-0000-0000-000000000003' },
  })
  if (recovered.isError || recovered.structuredContent?.data?.[0]?.id !== publication.structuredContent.data.id) {
    throw new Error('Codex fixture approval recovery failed')
  }
  const approval = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_platform_approval_status', arguments: { approvalId: publication.structuredContent.data.id },
  })
  if (approval.isError || approval.structuredContent?.data?.status !== 'PENDING') {
    throw new Error('Codex fixture Agent publication approval status failed')
  }
  const workflowPublication = await call('mcpServer/tool/call', {
    threadId, server, tool: 'reachai_workflow_request_publish', arguments: { workflowId: 'fixture-draft',
      baseRevision: '2026-10-29T10:00:00', version: 'v1', invocationId: '00000000-0000-0000-0000-000000000004' },
  })
  if (workflowPublication.isError || workflowPublication.structuredContent?.data?.status !== 'PENDING') {
    throw new Error('Codex fixture Workflow publication request failed')
  }
  process.stdout.write('Codex MCP initialization, discovery and fixture tool calls passed.\n')
} catch (error) {
  // No request bodies, credentials, or inherited server configuration are printed.
  process.stderr.write(`Codex MCP smoke test failed: ${error.message}\n`)
  if (diagnostic.includes('MCP_PROTOCOL_VERSION_UNSUPPORTED')) {
    process.stderr.write('The installed client rejected the negotiated MCP protocol version.\n')
  }
  process.exitCode = 1
} finally {
  child.stdin.end()
  if (process.platform === 'win32' && child.pid) {
    // The npm wrapper starts the native app-server as a child process.
    try { execFileSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }) } catch { child.kill() }
  } else {
    child.kill()
  }
  child.stdout.destroy()
  child.stderr.destroy()
}
