<template>
  <el-form :model="form" label-width="120px" class="knowledge-settings-tab">
    <WorkbenchPanel title="基础信息" density="compact">
      <el-form-item label="名称">{{ kb?.name || '-' }}</el-form-item>
      <el-form-item label="编码">{{ kb?.code || kbCode }}</el-form-item>
      <el-form-item label="说明">{{ kb?.description || '暂无说明' }}</el-form-item>
      <el-form-item label="向量模型">{{ modelName(kb?.embeddingModelInstanceId) }}</el-form-item>
      <el-form-item label="回答模型">{{ modelName(kb?.llmModelInstanceId) }}</el-form-item>
      <el-form-item label="重排模型">{{ kb?.rerankModelInstanceId ? modelName(kb.rerankModelInstanceId) : '未配置' }}</el-form-item>
      <div class="panel-hint">名称、说明和模型在「知识库」列表里点「编辑」修改。</div>
    </WorkbenchPanel>

    <WorkbenchPanel title="谁能检索" density="compact">
      <el-form-item label="检索范围">
        <el-radio-group v-model="form.accessMode">
          <el-radio value="ALL_CALLERS">所有调用方</el-radio>
          <el-radio value="FILE_GRANTS">仅授权文件</el-radio>
        </el-radio-group>
        <div class="field-hint">{{ accessModeHint(form.accessMode) }}</div>
      </el-form-item>
    </WorkbenchPanel>

    <WorkbenchPanel title="切分" density="compact">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="切分方式">
            <el-select v-model="form.splitType" style="width: 100%">
              <el-option label="固定长度" value="FIXED" />
              <el-option label="按段落" value="PARAGRAPH" />
              <el-option label="按语义" value="SEMANTIC" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="段落长度（字）">
            <el-input-number v-model="form.chunkSize" :min="100" :max="4000" :step="100" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="前后重叠（字）">
            <el-input-number v-model="form.chunkOverlap" :min="0" :max="1000" :step="10" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <div class="panel-hint">Markdown 和 Word 总是先按标题切；改动只对之后上传或重新解析的文件生效。</div>
    </WorkbenchPanel>

    <WorkbenchPanel title="检索" density="compact">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="检索方式">
            <el-segmented v-model="form.searchMode" :options="searchModeOptions" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="返回条数">
            <el-input-number v-model="form.topK" :min="1" :max="20" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="重排序">
            <el-switch v-model="form.rerankEnabled" :disabled="!kb?.rerankModelInstanceId" />
            <div v-if="!kb?.rerankModelInstanceId" class="field-hint">先给知识库配置重排模型才能开启。</div>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="相似度下限">
            <el-slider v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" show-input />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="语义权重">
            <el-slider v-model="form.vectorWeight" :min="0" :max="1" :step="0.05" show-input />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="关键词权重">
            <el-slider v-model="form.keywordWeight" :min="0" :max="1" :step="0.05" show-input />
          </el-form-item>
        </el-col>
      </el-row>
      <div class="panel-hint">Agent 对话、Workflow 知识节点（没有单独设置时）和「检索测试」都使用这里的设置。低于相似度下限的段落不会返回。</div>
    </WorkbenchPanel>

    <div class="form-footer">
      <el-button type="primary" :loading="saving" @click="handleSave">保存设置</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { updateKbConfig } from '@/api/knowledge'
import type { KbConfig, KnowledgeBase } from '@/types/knowledge'
import { accessModeHint } from './knowledgeDetailModel'

const props = defineProps<{
  kbCode: string
  kb: KnowledgeBase | null
  modelName: (id?: string | null) => string
}>()

const emit = defineEmits<{
  saved: []
}>()

const searchModeOptions = [{ label: '混合', value: 'hybrid' }, { label: '语义', value: 'vector' }, { label: '关键词', value: 'keyword' }]
const saving = ref(false)
const form = reactive<KbConfig>({
  accessMode: 'ALL_CALLERS',
  splitType: 'FIXED',
  chunkSize: 500,
  chunkOverlap: 50,
  searchMode: 'hybrid',
  topK: 5,
  similarityThreshold: 0.5,
  rerankEnabled: false,
  vectorWeight: 0.7,
  keywordWeight: 0.3,
})

watch(() => props.kb, (kb) => {
  if (!kb) return
  Object.assign(form, {
    accessMode: kb.accessMode || 'ALL_CALLERS',
    splitType: kb.splitType || 'FIXED',
    chunkSize: kb.chunkSize || 500,
    chunkOverlap: kb.chunkOverlap ?? 50,
    searchMode: kb.searchMode || 'hybrid',
    topK: kb.topK || 5,
    similarityThreshold: kb.similarityThreshold ?? 0.5,
    rerankEnabled: kb.rerankEnabled ?? false,
    vectorWeight: kb.vectorWeight ?? 0.7,
    keywordWeight: kb.keywordWeight ?? 0.3,
  })
}, { immediate: true })

async function handleSave() {
  saving.value = true
  try {
    await updateKbConfig(props.kbCode, { ...form })
    ElMessage.success('设置已保存')
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped lang="scss">
.knowledge-settings-tab {
  display: grid;
  gap: 14px;
  max-width: 1180px;
}

.field-hint,
.panel-hint {
  width: 100%;
  font-size: 12px;
  color: var(--text-muted);
}

.form-footer {
  display: flex;
  justify-content: flex-start;
}
</style>
