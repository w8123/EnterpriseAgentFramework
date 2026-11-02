const labels: Record<string, string> = {
  name: '名称', description: '说明', embeddingModelInstanceId: '向量模型', llmModelInstanceId: '回答模型', rerankModelInstanceId: '重排模型',
  accessMode: '谁能检索', chunkSize: '段落长度', chunkOverlap: '前后重叠', splitType: '切分方式', searchMode: '检索方式', topK: '返回条数',
  similarityThreshold: '相似度下限', rerankEnabled: '重排序', vectorWeight: '语义权重', keywordWeight: '关键词权重',
}
const fields = Object.keys(labels)
function record(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value) }
function complete(value: unknown): value is Record<string, unknown> {
  return record(value) && typeof value.name === 'string' && Object.keys(value).length === fields.length
    && fields.every(field => Object.prototype.hasOwnProperty.call(value, field))
    && Object.values(value).every(item => item === null || typeof item === 'string' || typeof item === 'boolean' || typeof item === 'number' && Number.isFinite(item))
}
export function knowledgeSettingsReview(args: Record<string, unknown>, subject: string, invocationId: string) {
  if (args.contractVersion !== 1 || typeof args.kbCode !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(args.kbCode)
    || subject !== `KNOWLEDGE:${args.kbCode}` || args.invocationId !== invocationId
    || typeof args.baseRevision !== 'string' || !/^[a-f0-9]{64}$/.test(args.baseRevision)
    || !Number.isSafeInteger(args.knowledgeBaseId) || Number(args.knowledgeBaseId) <= 0
    || !Object.prototype.hasOwnProperty.call(args, 'dimension') || args.dimension !== null && (!Number.isSafeInteger(args.dimension) || Number(args.dimension) <= 0)
    || !complete(args.before) || !complete(args.after) || !record(args.changes) || !Object.keys(args.changes).length
    || !record(args.resource) || !['SHARED', 'WORKSPACE', 'PROJECT'].includes(String(args.resource.scope))
    || typeof args.resource.workspaceId !== 'string' || !args.resource.workspaceId.trim()
    || args.resource.scope === 'PROJECT' && (typeof args.resource.projectCode !== 'string' || !args.resource.projectCode.trim())) return null
  const before = args.before, after = args.after, changes = args.changes
  if (Object.keys(changes).some(field => !fields.includes(field) || changes[field] !== after[field])
    || fields.some(field => !Object.prototype.hasOwnProperty.call(changes, field) && before[field] !== after[field])) return null
  return {
    name: before.name,
    resource: args.resource,
    expandsAccess: before.accessMode === 'FILE_GRANTS' && after.accessMode === 'ALL_CALLERS',
    changes: fields.filter(field => Object.prototype.hasOwnProperty.call(changes, field)).map(field => ({
      field, label: labels[field], before: settingValue(field, before[field]), after: settingValue(field, after[field]),
    })),
  }
}
function settingValue(field: string, value: unknown): string {
  if (field === 'accessMode') return value === 'FILE_GRANTS' ? '仅授权文件' : value === 'ALL_CALLERS' ? '所有调用方' : value === null ? '所有调用方（默认）' : String(value)
  if (value === null || value === '') return field === 'description' ? '未填写' : '未设置'
  if (typeof value === 'boolean') return value ? '开启' : '关闭'
  return String(value)
}
