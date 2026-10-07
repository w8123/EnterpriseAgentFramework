import { describe, expect, it } from 'vitest'
import type { ToolParameter } from '@/types/tool'
import {
  contextIsExecutable,
  diagnosticReasonLabel,
  invocationEditorShape,
  isOutcomeUnconfirmed,
  logicalInvocationParameters,
  maskInvocationInput,
  normalizeInvocationOutcome,
} from './businessMethodInvocation'

const hash = 'a'.repeat(64)

describe('business method trial invocation contract helpers', () => {
  it('builds an ephemeral logical tree from accepted dotted declarations without changing input binding', () => {
    const parameters: ToolParameter[] = [
      { name: 'request', type: 'object', description: '', required: true },
      { name: 'request.customer.id', type: 'string', description: '', required: true },
      { name: 'request.items', type: 'array', description: '', required: false },
    ]
    const roots = logicalInvocationParameters(parameters)
    expect(roots[0].name).toBe('request')
    expect(roots[0].children?.[0].name).toBe('customer')
    expect(roots[0].children?.[0].children?.[0].name).toBe('id')
    expect(invocationEditorShape(parameters)).toMatchObject({ singleDtoRootName: 'request' })
  })

  it('keeps nested arrays and objects typed while showing a separate sensitive projection', () => {
    const parameters: ToolParameter[] = [{
      name: 'request', type: 'object', description: '', required: true, children: [
        { name: 'token', type: 'string', description: '', required: true, metadata: { sensitive: true } },
        { name: 'items', type: 'array', description: '', required: false, children: [
          { name: 'id', type: 'integer', description: '', required: true },
        ] },
      ],
    }]
    const original = { request: { token: 'never-copy', items: [{ id: 0 }, { id: 2 }] } }
    const masked = maskInvocationInput(original, parameters)
    expect(masked).toEqual({ request: { token: '••••••', items: [{ id: 0 }, { id: 2 }] } })
    expect(original.request.items[0].id).toBe(0)
    expect(original.request.token).toBe('never-copy')
  })

  it('accepts only the real lifecycle combinations and keeps an unconfirmed POST separate', () => {
    const base = {
      contractVersion: 1,
      invocationId: '123e4567-e89b-42d3-a456-426614174000',
      runId: 7,
      traceId: 'trace-7',
      projectId: 8,
      projectCode: 'orders',
      qualifiedName: 'orders.lookup',
      identityMode: 'PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY',
      terminal: true,
    }
    for (const [status, dispatchStage, terminal] of [
      ['ACCEPTED', 'PERSISTED', false],
      ['DISPATCHING', 'DISPATCHING', false],
      ['SUCCEEDED', 'CONFIRMED', true],
      ['BUSINESS_FAILED', 'CONFIRMED', true],
      ['NOT_DISPATCHED', 'NOT_DISPATCHED', true],
      ['UNKNOWN', 'UNCONFIRMED', true],
    ]) {
      expect(normalizeInvocationOutcome({ ...base, status, dispatchStage, terminal })).toMatchObject({ status, dispatchStage, terminal })
    }
    expect(normalizeInvocationOutcome({ ...base, status: 'UNKNOWN', dispatchStage: 'CONFIRMED', terminal: false })).toBeNull()
    expect(isOutcomeUnconfirmed({
      success: false,
      code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED',
      invocationId: base.invocationId,
      status: 'UNKNOWN',
      terminal: false,
    }, base.invocationId)).toBe(true)
  })

  it('requires the accepted source hash before enabling an invocation', () => {
    expect(contextIsExecutable({
      contractVersion: 1, name: 'orders.lookup', assetType: 'BUSINESS_METHOD', projectId: 8, projectCode: 'orders',
      qualifiedName: 'orders.lookup', currentContractHash: hash, executionRevision: 'd'.repeat(64), acceptedContractHash: hash, sourceContractHash: hash,
      sourceAvailability: 'READY', enabled: true, credentialAvailable: true, businessIdentityRequired: false,
      executable: true, parameters: [],
    })).toBe(true)
    expect(contextIsExecutable({
      contractVersion: 1, name: 'orders.lookup', assetType: 'BUSINESS_METHOD', projectId: 8, projectCode: 'orders',
      qualifiedName: 'orders.lookup', currentContractHash: hash, executionRevision: 'd'.repeat(64), acceptedContractHash: 'b'.repeat(64), sourceContractHash: hash,
      sourceAvailability: 'READY', enabled: true, credentialAvailable: true, businessIdentityRequired: false,
      executable: true, parameters: [],
    })).toBe(false)
    expect(diagnosticReasonLabel('REQUIRED')).toBe('该字段为必填项')
  })
})
