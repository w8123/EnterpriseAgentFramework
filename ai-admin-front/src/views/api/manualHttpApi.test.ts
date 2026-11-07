import { describe, expect, it } from 'vitest'
import { agentScanPrompt, buildManualPayload, convertSwagger2, emptyManualDraft, normalizeApiPath, parseCurl,
  prepareOpenApiDocument, schemaFromJsonText, syncPathParameters, tokenizeShell } from './manualHttpApi'

describe('manualHttpApi', () => {
  it('parses a browser "copy as cURL" GET and drops the token value', () => {
    const result = parseCurl(`curl 'https://erp.example.com/admin-api/erp/stock/page?pageNo=1&warehouseId=12&keyword=a' \\
      -H 'Authorization: Bearer eyJhbGciOi.secret' \\
      -H 'tenant-id: 1' --compressed`, '/admin-api')
    expect(result.draft.httpMethod).toBe('GET')
    expect(result.draft.path).toBe('/erp/stock/page')
    expect(result.draft.authMode).toBe('BEARER')
    expect(result.draft.parameters.map(p => [p.name, p.type])).toEqual([['pageNo', 'integer'], ['warehouseId', 'integer'], ['keyword', 'string']])
    expect(result.droppedSecrets).toContain('Bearer 令牌')
    expect(result.origin).toBe('https://erp.example.com')
    expect(JSON.stringify(result.draft)).not.toContain('secret')
  })

  it('turns a JSON body into a schema and infers POST', () => {
    const result = parseCurl(`curl "http://localhost:48080/admin-api/erp/customer/create" -H "Content-Type: application/json" -H "X-Api-Key: abc" --data-raw "{\\"name\\":\\"张三\\",\\"age\\":3,\\"vip\\":true,\\"tags\\":[\\"a\\"]}"`)
    expect(result.draft.httpMethod).toBe('POST')
    expect(result.draft.sideEffect).toBe('WRITE')
    expect(result.draft.authMode).toBe('API_KEY_HEADER')
    expect(result.draft.authHeaderName).toBe('X-Api-Key')
    expect(JSON.parse(result.draft.bodyText)).toEqual({ type: 'object', properties: { name: { type: 'string' }, age: { type: 'integer' },
      vip: { type: 'boolean' }, tags: { type: 'array', items: { type: 'string' } } } })
    expect(result.draft.bodyText).not.toContain('张三')
  })

  it('rejects non-curl input, multipart forms and unclosed quotes', () => {
    expect(() => parseCurl('wget http://a')).toThrow('curl')
    expect(() => parseCurl("curl -F 'file=@a.png' http://a/upload")).toThrow('multipart')
    expect(() => tokenizeShell("curl 'http://a")).toThrow('引号')
  })

  it('normalizes paths and keeps path parameters in sync', () => {
    expect(normalizeApiPath('https://h/admin-api/users/:id/?x=1', '/admin-api')).toBe('/users/{id}')
    const params = syncPathParameters('/orders/{orderId}/items/{itemId}', [
      { name: 'old', location: 'PATH', type: 'string', required: true, description: '' },
      { name: 'q', location: 'QUERY', type: 'string', required: false, description: '' }])
    expect(params.map(p => `${p.location}:${p.name}`)).toEqual(['PATH:orderId', 'PATH:itemId', 'QUERY:q'])
  })

  it('builds the payload and validates the API key header', () => {
    const draft = { ...emptyManualDraft(), httpMethod: 'POST' as const, path: 'orders/{id}', bodyText: '{"type":"object","properties":{"a":{"type":"string"}}}' }
    const payload = buildManualPayload(draft)
    expect(payload.path).toBe('/orders/{id}')
    expect(payload.parameters).toEqual([{ name: 'id', location: 'PATH', type: 'string', required: true }])
    expect(payload.requestBody?.schema).toEqual({ type: 'object', properties: { a: { type: 'string' } } })
    expect(() => buildManualPayload({ ...draft, authMode: 'API_KEY_HEADER', authHeaderName: 'bad header' })).toThrow('请求头')
    // an example object that merely has a "type" field is still an example
    expect(schemaFromJsonText('{"type":"customer","name":"x"}')).toEqual({ type: 'object',
      properties: { type: { type: 'string' }, name: { type: 'string' } } })
  })

  it('converts Swagger 2.0 JSON into OpenAPI 3 for upload', () => {
    const swagger = { swagger: '2.0', info: { title: 'demo', version: '1' }, basePath: '/api', produces: ['application/json'],
      securityDefinitions: { key: { type: 'apiKey', name: 'X-Token', in: 'header' } },
      definitions: { User: { type: 'object', properties: { id: { type: 'integer' } } } },
      paths: {
        '/users/{id}': { get: { parameters: [{ name: 'id', in: 'path', type: 'integer', required: true }],
          responses: { 200: { description: 'ok', schema: { $ref: '#/definitions/User' } } } } },
        '/users': { post: { parameters: [{ name: 'body', in: 'body', required: true, schema: { $ref: '#/definitions/User' } }],
          responses: { 200: { description: 'ok' } } } },
        '/upload': { post: { consumes: ['multipart/form-data'], parameters: [{ name: 'f', in: 'formData', type: 'file' }], responses: {} } },
      } }
    const { openapi, skipped } = convertSwagger2(swagger)
    expect(skipped).toBe(1)
    expect(openapi.openapi).toBe('3.0.3')
    const paths = openapi.paths as Record<string, any>
    expect(Object.keys(paths)).toEqual(['/api/users/{id}', '/api/users'])
    expect(paths['/api/users/{id}'].get.parameters[0]).toEqual({ name: 'id', in: 'path', required: true, schema: { type: 'integer' } })
    expect(paths['/api/users/{id}'].get.responses[200].content['application/json'].schema.$ref).toBe('#/components/schemas/User')
    expect(paths['/api/users'].post.requestBody.content['application/json'].schema.$ref).toBe('#/components/schemas/User')
    expect((openapi.components as any).securitySchemes.key).toEqual({ type: 'apiKey', name: 'X-Token', in: 'header' })
    const prepared = prepareOpenApiDocument('api-docs.json', JSON.stringify(swagger))
    expect(prepared.specFile).toBe('api-docs.openapi3.json')
    expect(prepared.note).toContain('Swagger 2.0')
    expect(() => prepareOpenApiDocument('a.yaml', 'swagger: "2.0"\ninfo: {}')).toThrow('JSON')
    expect(prepareOpenApiDocument('a.yaml', 'openapi: 3.0.1\ninfo: {}').specContent).toContain('openapi')
    expect(() => prepareOpenApiDocument('a.json', '{"hello":1}')).toThrow('OpenAPI')
  })

  it('writes an agent prompt that names the MCP tool and the project', () => {
    const prompt = agentScanPrompt({ id: 7, name: '芋道 ERP', projectCode: 'yudao-erp' })
    expect(prompt).toContain('reachai_http_apis_upload')
    expect(prompt).toContain('项目编号 7')
    expect(prompt).toContain('yudao-erp')
  })
})
