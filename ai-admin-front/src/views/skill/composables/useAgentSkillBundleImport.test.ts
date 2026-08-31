import { describe, expect, it, vi } from 'vitest'
import type { AgentSkillBundleCandidate, AgentSkillBundleDiscovery } from '@/types/skill'
import {
  AGENT_SKILL_BUNDLE_DISCOVERY_ERROR,
  useAgentSkillBundleImport,
} from './useAgentSkillBundleImport'

function candidate(
  name: string,
  sourceRoot = `skills/${name}`,
  selectable = true,
): AgentSkillBundleCandidate {
  return {
    sourceRoot,
    selectable,
    name: selectable ? name : null,
    description: selectable ? `${name} description` : null,
    hasScripts: false,
    fileCount: selectable ? 2 : 0,
    warnings: [],
    errorCode: selectable ? null : 'SKILL_PACKAGE_INVALID',
    errorMessage: selectable ? null : 'invalid candidate',
  }
}

function discovery(
  candidates: AgentSkillBundleCandidate[],
  bundleSourceSha256 = 'a'.repeat(64),
): AgentSkillBundleDiscovery {
  return {
    schema: 'reachai.agent-skill-bundle-discovery.v1',
    bundleSourceSha256,
    archiveFileCount: candidates.reduce((total, value) => total + value.fileCount, 0),
    candidateCount: candidates.length,
    multiSkill: candidates.length > 1,
    candidates,
  }
}

function upload(name: string) {
  return new File(['PK'], name, { type: 'application/zip' })
}

describe('useAgentSkillBundleImport', () => {
  it('auto-selects the only valid Skill in a single-Skill package', async () => {
    const result = discovery([candidate('playwright')])
    const discoverer = vi.fn().mockResolvedValue({ data: result })
    const state = useAgentSkillBundleImport(discoverer)

    expect(await state.selectFile(upload('playwright.zip'))).toBe(true)

    expect(state.bundleDiscovery.value).toBe(result)
    expect(state.selectedCandidateRoot.value).toBe('skills/playwright')
    expect(state.selectedBundleCandidate.value?.name).toBe('playwright')
    expect(state.canSubmitImport.value).toBe(true)
  })

  it('requires an explicit valid selection for a multi-Skill bundle', async () => {
    const result = discovery([
      candidate('one'),
      candidate('broken', 'skills/broken', false),
      candidate('two'),
    ])
    const state = useAgentSkillBundleImport(vi.fn().mockResolvedValue({ data: result }))

    await state.selectFile(upload('market-repository.zip'))

    expect(state.selectedCandidateRoot.value).toBeNull()
    expect(state.canSubmitImport.value).toBe(false)
    state.selectedCandidateRoot.value = 'skills/broken'
    expect(state.selectedBundleCandidate.value).toBeNull()
    expect(state.canSubmitImport.value).toBe(false)
    state.selectedCandidateRoot.value = 'skills/two'
    expect(state.selectedBundleCandidate.value?.name).toBe('two')
    expect(state.canSubmitImport.value).toBe(true)
  })

  it('does not enable import when the only candidate is invalid', async () => {
    const result = discovery([candidate('broken', 'skills/broken', false)])
    const state = useAgentSkillBundleImport(vi.fn().mockResolvedValue({ data: result }))

    await state.selectFile(upload('broken.zip'))

    expect(state.bundleDiscovery.value).toBe(result)
    expect(state.selectedCandidateRoot.value).toBeNull()
    expect(state.canSubmitImport.value).toBe(false)
  })

  it('ignores a late response from a previously selected file', async () => {
    const pending: Array<{
      resolve: (value: { data: AgentSkillBundleDiscovery }) => void
    }> = []
    const discoverer = vi.fn(() => new Promise<{ data: AgentSkillBundleDiscovery }>(resolve => {
      pending.push({ resolve })
    }))
    const state = useAgentSkillBundleImport(discoverer)
    const firstFile = upload('first.zip')
    const secondFile = upload('second.zip')

    const firstRequest = state.selectFile(firstFile)
    const secondRequest = state.selectFile(secondFile)
    const secondDiscovery = discovery([candidate('second')], '2'.repeat(64))
    pending[1].resolve({ data: secondDiscovery })
    await secondRequest
    pending[0].resolve({ data: discovery([candidate('first')], '1'.repeat(64)) })
    await firstRequest

    expect(state.importFile.value).toBe(secondFile)
    expect(state.bundleDiscovery.value).toBe(secondDiscovery)
    expect(state.selectedBundleCandidate.value?.name).toBe('second')
    expect(state.discoveryLoading.value).toBe(false)
  })

  it('reports discovery failure and can retry the same file safely', async () => {
    const recovered = discovery([candidate('recovered')])
    const discoverer = vi.fn()
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce({ data: recovered })
    const state = useAgentSkillBundleImport(discoverer)
    const file = upload('retry.zip')

    expect(await state.selectFile(file)).toBe(false)
    expect(state.discoveryError.value).toBe(AGENT_SKILL_BUNDLE_DISCOVERY_ERROR)
    expect(state.canSubmitImport.value).toBe(false)
    expect(await state.retry()).toBe(true)
    expect(state.discoveryError.value).toBe('')
    expect(state.selectedBundleCandidate.value?.name).toBe('recovered')
    expect(state.canSubmitImport.value).toBe(true)
  })

  it('reset invalidates an in-flight discovery response', async () => {
    let resolveRequest!: (value: { data: AgentSkillBundleDiscovery }) => void
    const discoverer = vi.fn(() => new Promise<{ data: AgentSkillBundleDiscovery }>(resolve => {
      resolveRequest = resolve
    }))
    const state = useAgentSkillBundleImport(discoverer)
    const request = state.selectFile(upload('pending.zip'))

    state.reset()
    resolveRequest({ data: discovery([candidate('late')]) })
    await request

    expect(state.importFile.value).toBeNull()
    expect(state.bundleDiscovery.value).toBeNull()
    expect(state.selectedCandidateRoot.value).toBeNull()
    expect(state.discoveryLoading.value).toBe(false)
  })
})
