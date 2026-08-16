import { describe, expect, it } from 'vitest'
import type { UiRequestV1 } from '../core/conversationTypes'
import { buildListCardPresentation } from './listCardPresentation'

function requestWithEnabled(enabled: number | boolean): UiRequestV1 {
  return {
    schemaVersion: '1.0',
    interactionId: 'card-status-test',
    component: 'list_card',
    data: [{ id: 'team-111', teamName: '111', enabled }],
    schema: {
      titleField: 'teamName',
      status: {
        field: 'enabled',
        valueMap: {
          true: '已启用',
          false: '已停用',
        },
        toneMap: {
          true: 'success',
          false: 'warning',
        },
      },
    },
  }
}

describe('listCardPresentation', () => {
  it('maps numeric 1/0 business flags through boolean status contracts', () => {
    expect(buildListCardPresentation(requestWithEnabled(1)).items[0]?.status).toEqual({
      label: '已启用',
      tone: 'success',
    })
    expect(buildListCardPresentation(requestWithEnabled(0)).items[0]?.status).toEqual({
      label: '已停用',
      tone: 'warning',
    })
  })

  it('maps boolean values through numeric status contracts', () => {
    const request = requestWithEnabled(true)
    request.schema = {
      ...request.schema,
      status: {
        field: 'enabled',
        valueMap: { 1: '在线', 0: '离线' },
        toneMap: { 1: 'success', 0: 'danger' },
      },
    }

    expect(buildListCardPresentation(request).items[0]?.status).toEqual({
      label: '在线',
      tone: 'success',
    })
  })
})
