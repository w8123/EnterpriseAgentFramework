import type { KnowledgeDependencyState } from '@/api/knowledge'

export interface KnowledgeDependencyNoticeItem {
  key: 'milvus' | 'docling'
  title: string
  description: string
}

/** 把 Milvus / Docling 的连通情况翻译成「哪些功能暂时用不了、怎么开」。 */
export function knowledgeDependencyNotices(
  dependencies: Partial<Record<'milvus' | 'docling', KnowledgeDependencyState>> | null | undefined,
): KnowledgeDependencyNoticeItem[] {
  const notices: KnowledgeDependencyNoticeItem[] = []
  const milvus = dependencies?.milvus
  if (milvus && !milvus.reachable) {
    notices.push({
      key: 'milvus',
      title: '向量库 Milvus 还没连上，知识库暂时不能入库和检索',
      description: `当前地址 ${milvus.address}。本机开发可运行 docker compose -f deploy/docker-compose.infra.yml --profile milvus up -d；`
        + '已有 Milvus 就在 reachai.env 里填写 MILVUS_HOST / MILVUS_PORT，然后重启知识库服务。',
    })
  }
  const docling = dependencies?.docling
  if (docling && (!docling.enabled || !docling.reachable)) {
    notices.push({
      key: 'docling',
      title: docling.enabled
        ? '文档解析服务 Docling 还没连上，目前只能导入 txt / md / csv'
        : '文档解析服务 Docling 未开启，目前只能导入 txt / md / csv',
      description: docling.enabled
        ? `当前地址 ${docling.address}。需要导入 PDF、Word、PPT、Excel 时，本机可运行 docker compose -f deploy/docker-compose.infra.yml --profile docling up -d，`
          + '或在 reachai.env 里填写 REACHAI_DOCLING_BASE_URL 后重启知识库服务。'
        : '需要导入 PDF、Word、PPT、Excel 时，请开启 Docling（REACHAI_DOCLING_ENABLED=true）并填写 REACHAI_DOCLING_BASE_URL。',
    })
  }
  return notices
}
