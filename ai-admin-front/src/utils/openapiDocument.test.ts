import { describe, expect, it } from 'vitest'
import { MAX_OPENAPI_DOCUMENT_CHARS, readOpenApiDocumentFile } from './openapiDocument'

describe('readOpenApiDocumentFile', () => {
  it('reads yaml and json documents with their file name', async () => {
    const yaml = new File(['openapi: 3.0.3\npaths: {}\n'], 'orders.yaml')
    await expect(readOpenApiDocumentFile(yaml)).resolves.toEqual({
      specFile: 'orders.yaml',
      specContent: 'openapi: 3.0.3\npaths: {}\n',
    })
    const json = new File(['{"openapi":"3.0.3"}'], 'orders.json')
    await expect(readOpenApiDocumentFile(json)).resolves.toMatchObject({ specFile: 'orders.json' })
  })

  it('rejects other formats, empty files and oversized files', async () => {
    await expect(readOpenApiDocumentFile(new File(['x'], 'Orders.java'))).rejects.toThrow('.json')
    await expect(readOpenApiDocumentFile(new File(['  '], 'empty.yml'))).rejects.toThrow('为空')
    const big = new File(['a'.repeat(MAX_OPENAPI_DOCUMENT_CHARS + 1)], 'big.yaml')
    await expect(readOpenApiDocumentFile(big)).rejects.toThrow('5MB')
  })
})
