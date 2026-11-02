import { describe, expect, it } from 'vitest'
import { knowledgeDependencyNotices } from './knowledgeDependencyNotices'

describe('knowledgeDependencyNotices', () => {
  it('stays quiet when Milvus and Docling are reachable', () => {
    expect(knowledgeDependencyNotices({
      milvus: { enabled: true, address: 'localhost:19530', reachable: true },
      docling: { enabled: true, address: 'http://localhost:5001', reachable: true },
    })).toEqual([])
  })

  it('explains what stops working and how to turn it on', () => {
    const notices = knowledgeDependencyNotices({
      milvus: { enabled: true, address: 'localhost:19530', reachable: false },
      docling: { enabled: false, address: 'http://localhost:5001', reachable: false },
    })
    expect(notices.map((notice) => notice.key)).toEqual(['milvus', 'docling'])
    expect(notices[0].title).toContain('不能入库和检索')
    expect(notices[0].description).toContain('MILVUS_HOST')
    expect(notices[1].description).toContain('REACHAI_DOCLING_ENABLED=true')
  })

  it('ignores a missing response', () => {
    expect(knowledgeDependencyNotices(null)).toEqual([])
  })
})
