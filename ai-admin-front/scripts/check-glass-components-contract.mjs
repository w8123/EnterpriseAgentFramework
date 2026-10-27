import { existsSync } from 'node:fs'
import { resolve } from 'node:path'

import {
  componentFiles,
  failures,
  readNormalizedUtf8,
  root,
  validateAgentSupervisorWorkbenchConsumer,
  validateAppDialog,
  validateAppDrawer,
  validateCommonSfc,
  validateCommonStatusTag,
  validateDataTableShell,
  validateElementPlusMappings,
  validateFilterBar,
  validateGlassWorkbench,
  validateMetricIconBg,
  validateMetricStrip,
  validatePageHeader,
  validateStatusTag,
  validateWizardDialog,
  validateWorkbenchPanel
} from './glass-component-contract/validators.mjs'
import {
  runMutationProofs,
  mutationProofCount,
  safeVariantProofCount,
  knowledgeHarnessProofCount,
  agentHarnessProofCount,
  mcpHarnessProofCount
} from './glass-component-contract/proofs.mjs'

await runMutationProofs()
console.log(`MUTATION_PROOFS=${mutationProofCount}`)
console.log(`SAFE_VARIANT_PROOFS=${safeVariantProofCount}`)
console.log(`KNOWLEDGE_HARNESS_PROOFS=${knowledgeHarnessProofCount}`)
console.log(`AGENT_HARNESS_PROOFS=${agentHarnessProofCount}`)
console.log(`MCP_HARNESS_PROOFS=${mcpHarnessProofCount}`)

const elementPlusPath = resolve(root, 'src/styles/_element-plus.scss')
if (!existsSync(elementPlusPath)) {
  failures.push('src/styles/_element-plus.scss is missing')
} else {
  failures.push(
    ...validateElementPlusMappings(
      readNormalizedUtf8(elementPlusPath),
      'src/styles/_element-plus.scss',
    ),
  )
}

for (const relativePath of componentFiles) {
  const absolutePath = resolve(root, relativePath)
  if (!existsSync(absolutePath)) {
    if (relativePath.endsWith('glassWorkbench.ts')) {
      failures.push(`${relativePath} is missing; Task 2 type contracts unavailable`)
    } else if (relativePath.endsWith('PageHeader.vue')) {
      failures.push(`${relativePath} is missing; PageHeader component recipe unavailable`)
    } else if (relativePath.endsWith('WorkbenchPanel.vue')) {
      failures.push(`${relativePath} is missing; WorkbenchPanel component recipe unavailable`)
    } else if (relativePath.endsWith('FilterBar.vue')) {
      failures.push(`${relativePath} is missing; FilterBar component contract unavailable`)
    } else if (relativePath.endsWith('DataTableShell.vue')) {
      failures.push(`${relativePath} is missing; DataTableShell component contract unavailable`)
    } else if (relativePath.endsWith('WizardDialog.vue')) {
      failures.push(`${relativePath} is missing; WizardDialog progression contract unavailable`)
    } else {
      failures.push(`${relativePath} is missing`)
    }
    continue
  }
  const source = readNormalizedUtf8(absolutePath)
  if (relativePath.endsWith('glassWorkbench.ts')) {
    failures.push(...(await validateGlassWorkbench(source, relativePath)))
  } else if (relativePath.endsWith('PageHeader.vue')) {
    failures.push(...(await validatePageHeader(source, relativePath)))
  } else if (relativePath.endsWith('MetricStrip.vue')) {
    failures.push(...(await validateMetricStrip(source, relativePath)))
  } else if (relativePath.endsWith('WorkbenchPanel.vue')) {
    failures.push(...(await validateWorkbenchPanel(source, relativePath)))
  } else if (relativePath.endsWith('FilterBar.vue')) {
    failures.push(...(await validateFilterBar(source, relativePath)))
  } else if (relativePath.endsWith('DataTableShell.vue')) {
    failures.push(...(await validateDataTableShell(source, relativePath)))
  } else if (relativePath.endsWith('StatusTag.vue') && relativePath.includes('/common/')) {
    failures.push(...(await validateStatusTag(source, relativePath)))
  } else if (relativePath.endsWith('MetricIconBg.vue')) {
    failures.push(...(await validateMetricIconBg(source, relativePath)))
  } else if (relativePath.endsWith('CommonStatusTag.vue')) {
    failures.push(...(await validateCommonStatusTag(source, relativePath)))
  } else if (relativePath.endsWith('AppDialog.vue')) {
    failures.push(...(await validateAppDialog(source, relativePath)))
  } else if (relativePath.endsWith('WizardDialog.vue')) {
    failures.push(...(await validateWizardDialog(source, relativePath)))
  } else if (relativePath.endsWith('AppDrawer.vue')) {
    failures.push(...(await validateAppDrawer(source, relativePath)))
  } else if (relativePath.endsWith('.vue')) {
    failures.push(...(await validateCommonSfc(source, relativePath)))
  }
}

const toolListConsumerPath = resolve(root, 'src/views/tool/ToolList.vue')
if (existsSync(toolListConsumerPath)) {
  failures.push('src/views/tool/ToolList.vue must stay retired; generic Tool is not a product asset')
}

// src/views/KnowledgeDetail.vue was rebuilt in the 2026-10-18 knowledge cleanup (files / retrieval
// test / hit analysis / settings); the frozen glass-migration snapshot of the old seven-tab page no
// longer applies to it. The synthetic KnowledgeDetail harness above still guards the validator itself.

const agentEditConsumerPath = resolve(root, 'src/views/agent/AgentEdit.vue')
if (!existsSync(agentEditConsumerPath)) {
  failures.push('src/views/agent/AgentEdit.vue is missing; Agent form consumer contract unavailable')
} else {
  failures.push(
    ...(await validateAgentSupervisorWorkbenchConsumer(
      readNormalizedUtf8(agentEditConsumerPath),
      'src/views/agent/AgentEdit.vue (/agent/new/edit and /agent/:id/edit)',
    )),
  )
}

const mcpOnboardingConsumerPath = resolve(root, 'src/views/mcp/McpOnboarding.vue')
if (existsSync(mcpOnboardingConsumerPath)) {
  failures.push('src/views/mcp/McpOnboarding.vue must stay retired; onboarding now belongs to MCP publication detail')
}

const toolRetrievalTestConsumerPath = resolve(root, 'src/views/tool/ToolRetrievalTest.vue')
if (existsSync(toolRetrievalTestConsumerPath)) {
  failures.push('src/views/tool/ToolRetrievalTest.vue must stay retired; the 实验室 retrieval page was removed')
}

if (failures.length > 0) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('ReachAI glass component contract is aligned.')
