import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import type { ChunkConfig, ChunkItem, ExtraParams, ChunkStrategyType } from '@/types/import'

export const useImportStore = defineStore('import', () => {
  /** 当前选中的知识库编码 */
  const knowledgeBaseCode = ref('')

  /** 上传的文件 */
  const file = ref<File | null>(null)
  const fileName = ref('')
  const fileStatus = ref<'idle' | 'uploaded' | 'previewing' | 'importing' | 'done' | 'error'>('idle')

  /** 切分策略配置 */
  const chunkConfig = reactive<ChunkConfig>({
    chunkStrategy: 'fixed_length',
    chunkSize: 500,
    chunkOverlap: 50,
  })

  /** Chunk 预览结果 */
  const chunkPreview = ref<ChunkItem[]>([])
  const totalChunks = ref(0)
  const previewLoading = ref(false)

  /** 高级参数 */
  const extraParams = reactive<ExtraParams>({
    enableOcr: false,
    tags: [],
    deptId: '',
    overwrite: false,
  })

  /** 入库状态 */
  const importLoading = ref(false)

  function setFile(f: File | null) {
    file.value = f
    fileName.value = f?.name || ''
    fileStatus.value = f ? 'uploaded' : 'idle'
    chunkPreview.value = []
    totalChunks.value = 0
  }

  function setChunkStrategy(strategy: ChunkStrategyType) {
    chunkConfig.chunkStrategy = strategy
  }

  function setPreviewResult(chunks: ChunkItem[], total: number) {
    chunkPreview.value = chunks
    totalChunks.value = total
  }

  function reset() {
    knowledgeBaseCode.value = ''
    file.value = null
    fileName.value = ''
    fileStatus.value = 'idle'
    chunkConfig.chunkStrategy = 'fixed_length'
    chunkConfig.chunkSize = 500
    chunkConfig.chunkOverlap = 50
    chunkPreview.value = []
    totalChunks.value = 0
    previewLoading.value = false
    importLoading.value = false
    extraParams.enableOcr = false
    extraParams.tags = []
    extraParams.deptId = ''
    extraParams.overwrite = false
  }

  return {
    knowledgeBaseCode,
    file,
    fileName,
    fileStatus,
    chunkConfig,
    chunkPreview,
    totalChunks,
    previewLoading,
    extraParams,
    importLoading,
    setFile,
    setChunkStrategy,
    setPreviewResult,
    reset,
  }
})
