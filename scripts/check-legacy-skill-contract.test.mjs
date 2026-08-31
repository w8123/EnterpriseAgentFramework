import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { scanLegacySkillContract } from './check-legacy-skill-contract.mjs'

async function fixture(files) {
  const root = await mkdtemp(path.join(tmpdir(), 'reachai-legacy-skill-contract-'))
  for (const [relativePath, content] of Object.entries(files)) {
    const target = path.join(root, relativePath)
    await mkdir(path.dirname(target), { recursive: true })
    await writeFile(target, content, 'utf8')
  }
  return root
}

test('allows standard Agent Skill packages, A2A skills and the artifact route', async () => {
  const root = await fixture({
    'src/ai-assist/skills/demo/SKILL.md': '# Demo Skill\n',
    'src/A2aCard.java': 'String card = "{\\"skills\\":[]}";\n',
    'src/ArtifactController.java': 'String route = "/api/ai-assist/skills/demo";\n',
    'src/StandardSkillCatalog.java': 'String route = "/api/skills"; String contract = "SKILL.md";\n',
  })
  try {
    assert.deepEqual(scanLegacySkillContract(root, ['src']), [])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('rejects each retired self-invented Skill contract surface', async () => {
  const root = await fixture({
    'src/Legacy.java': `
      String module = "ai-skills-service";
      String table = "capability_draft";
      String config = "capabilityConfig";
      String node = "GraphNodeType.CAPABILITY";
      String kind = "kind='SKILL'";
      String route = "/api/skill-mining";
    `,
  })
  try {
    const rules = scanLegacySkillContract(root, ['src']).map(item => item.rule).sort()
    assert.deepEqual(rules, [
      'legacy-graph-config',
      'legacy-graph-node',
      'legacy-skill-kind',
      'retired-module',
      'retired-public-route',
      'retired-table',
    ])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
