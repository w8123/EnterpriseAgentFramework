/** 与后端 CapabilityScanProjectCatalogService.MAX_OPENAPI_SPEC_CHARS 一致 */
export const MAX_OPENAPI_DOCUMENT_CHARS = 5 * 1024 * 1024
export const OPENAPI_DOCUMENT_ACCEPT = '.json,.yaml,.yml'

export interface OpenApiDocumentUpload {
  specFile: string
  specContent: string
}

/** 读取用户选择的 OpenAPI 文档；文档内容随项目一起保存，平台不再读取服务器目录。 */
export async function readOpenApiDocumentFile(file: File): Promise<OpenApiDocumentUpload> {
  if (!/\.(json|ya?ml)$/i.test(file.name)) {
    throw new Error('请选择 .json、.yaml 或 .yml 格式的 OpenAPI 文档')
  }
  if (file.size > MAX_OPENAPI_DOCUMENT_CHARS) {
    throw new Error('OpenAPI 文档不能超过 5MB')
  }
  const specContent = await file.text()
  if (!specContent.trim()) {
    throw new Error('OpenAPI 文档内容为空')
  }
  if (specContent.length > MAX_OPENAPI_DOCUMENT_CHARS) {
    throw new Error('OpenAPI 文档不能超过 5MB')
  }
  return { specFile: file.name, specContent }
}
