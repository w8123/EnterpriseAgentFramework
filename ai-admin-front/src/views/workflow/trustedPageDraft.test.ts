import { describe, expect, it } from 'vitest'
import type { PageActionReviewPreview } from '@/api/pageActionEffectReview'
import { workflowCanvasToSaveRequest, workflowStudioToCanvas } from '@/utils/workflowStudio'
import { buildTrustedPageDraft } from './trustedPageDraft'

const preview: PageActionReviewPreview = {
  projectCode: 'mall', pageKey: 'profile', actionKey: 'fill', routePattern: '/personal',
  actionTitle: '填写个人资料', actionDescription: '仅填写，不保存', implementationVersion: null,
  inputSchema: { type: 'object', properties: {
    nickName: { type: 'string', minLength: 2, maxLength: 50 },
    introduceSign: { type: 'string', maxLength: 100 }, address: { type: 'string', minLength: 10 },
  }, minProperties: 1, additionalProperties: false },
  riskLevel: 'WRITE', confirmRequired: true, catalogHash: 'hash', currentRevision: 1,
  approval: { approvalId: 1, revision: 1, effect: 'PAGE_LOCAL_DRAFT', targetAlias: '个人资料',
    implementationVersion: 'v1', fieldLabels: { nickName: '昵称', introduceSign: '签名', address: '地址' },
    catalogHash: 'hash', active: true },
}

describe('reviewed page draft Studio round trip', () => {
  it('retains exact approved schema, fixed boolean and mappings through a canvas save', () => {
    const graph = buildTrustedPageDraft(preview, 'mall', { warehouse: 'north' })
    const studio = { workflowId: 'wf', projectCode: 'mall', workflowKind: 'PAGE_ASSISTANT',
      graphSpecJson: JSON.stringify(graph), status: 'DRAFT', revision: 'rev' }
    const snapshot = workflowStudioToCanvas(studio)
    const saved = JSON.parse(workflowCanvasToSaveRequest(studio, snapshot).graphSpecJson)
    expect(saved.inputSchema).toEqual(preview.inputSchema)
    expect(saved.env).toEqual({ warehouse: 'north', reachaiTrustedPageDraft: true })
    expect(saved.nodes[1].config.inputMapping).toEqual({ nickName: 'params.nickName',
      introduceSign: 'params.introduceSign', address: 'params.address' })
    expect(saved.edges[0].condition).toBeUndefined()
    snapshot.edges.forEach((edge) => { if (edge.condition === 'always') edge.label = undefined })
    snapshot.nodes[0].position.x += 50
    const reopened = workflowCanvasToSaveRequest(studio, snapshot)
    expect(JSON.parse(reopened.graphSpecJson)).toEqual(saved)
    const reloaded = { ...studio, graphSpecJson: reopened.graphSpecJson, canvasJson: reopened.canvasJson }
    expect(JSON.parse(workflowCanvasToSaveRequest(reloaded, workflowStudioToCanvas(reloaded)).graphSpecJson)).toEqual(saved)
  })

  it('rejects semantic canvas changes instead of dropping them', () => {
    const graph = buildTrustedPageDraft(preview, 'mall', {})
    const studio = { workflowId: 'wf', projectCode: 'mall', graphSpecJson: JSON.stringify(graph), status: 'DRAFT' }
    const snapshot = workflowStudioToCanvas(studio)
    const action = snapshot.nodes.find((node) => node.data.kind === 'pageAction')!
    action.data.pageActionConfig!.actionKey = 'other'
    expect(() => workflowCanvasToSaveRequest(studio, snapshot)).toThrow('不能直接修改节点或连线')
  })

  it('does not construct from revoked, mismatched, or cross-project approval', () => {
    expect(() => buildTrustedPageDraft({ ...preview, approval: null }, 'mall', {})).toThrow('审核已失效')
    expect(() => buildTrustedPageDraft(preview, 'other', {})).toThrow('审核已失效')
    expect(() => buildTrustedPageDraft({ ...preview, catalogHash: 'changed' }, 'mall', {})).toThrow('审核已失效')
  })
  it('rejects ambiguous routes and unsupported old schema before replacing the canvas', () => {
    for (const routePattern of ['//outside.example/x', '/%2foutside', '/users/:id', '/\\outside']) {
      expect(() => buildTrustedPageDraft({ ...preview, routePattern }, 'mall', {})).toThrow()
    }
    expect(() => buildTrustedPageDraft({ ...preview, inputSchema: { type: 'object', properties: {
      'postal-code': { type: 'string' },
    } } }, 'mall', {})).toThrow('字段格式不受支持')
    expect(() => buildTrustedPageDraft({ ...preview, inputSchema: { type: 'object', properties: {
      name: { type: 'string', enum: ['a'] },
    } } }, 'mall', {})).toThrow('字段格式不受支持')
  })
})
