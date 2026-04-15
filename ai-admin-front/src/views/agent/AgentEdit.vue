<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button @click="router.push('/agent')" :icon="ArrowLeft" text>返回</el-button>
        <h2>{{ isNew ? '新建 Agent' : `编辑 Agent — ${form.name}` }}</h2>
      </div>
      <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="140px"
      v-loading="pageLoading"
    >
      <!-- 基本信息 -->
      <el-card shadow="never" class="section-card">
        <template #header>基本信息</template>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" placeholder="Agent 名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="意图类型" prop="intentType">
              <el-select
                v-model="form.intentType"
                placeholder="选择或输入意图类型"
                style="width: 100%"
                filterable
                allow-create
              >
                <el-option
                  v-for="t in INTENT_TYPES"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="Agent 描述（同时用于意图识别候选列表）" />
        </el-form-item>
      </el-card>

      <!-- 模型与执行配置 -->
      <el-card shadow="never" class="section-card">
        <template #header>模型与执行</template>
        <el-row :gutter="24">
          <el-col :span="6">
            <el-form-item label="模型">
              <el-input v-model="form.modelName" placeholder="留空用默认" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最大步数">
              <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="Agent 类型">
              <el-radio-group v-model="form.type">
                <el-radio value="single">单 Agent</el-radio>
                <el-radio value="pipeline">Pipeline</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="触发方式">
              <el-select v-model="form.triggerMode" style="width: 100%">
                <el-option
                  v-for="m in TRIGGER_MODES"
                  :key="m.value"
                  :label="m.label"
                  :value="m.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="6">
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="多 Agent 模型">
              <el-switch v-model="form.useMultiAgentModel" />
              <el-tooltip content="Pipeline 子 Agent 应开启此项，使用 MultiAgentFormatter 模型" placement="top">
                <el-icon class="tip-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="输出 Schema">
              <el-input v-model="form.outputSchemaType" placeholder="如 ReviewResult，留空返回纯文本" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-card>

      <!-- AI 能力中台配置 -->
      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header-with-badge">
            AI 能力中台配置
            <el-tag size="small" type="info">新</el-tag>
          </div>
        </template>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="知识库组 ID">
              <el-input v-model="form.knowledgeBaseGroupId" placeholder="关联的知识库组（多库协同检索）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Prompt 模板 ID">
              <el-input v-model="form.promptTemplateId" placeholder="关联的 Prompt 模板（可覆盖 System Prompt）" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-card>

      <!-- System Prompt -->
      <el-card shadow="never" class="section-card">
        <template #header>System Prompt</template>
        <el-form-item label-width="0">
          <el-input
            v-model="form.systemPrompt"
            type="textarea"
            :rows="10"
            placeholder="输入 System Prompt，定义 Agent 的行为和角色..."
            class="prompt-editor"
          />
        </el-form-item>
      </el-card>

      <!-- Tool 配置 -->
      <el-card shadow="never" class="section-card">
        <template #header>Tool 配置</template>
        <el-form-item label="可用工具" label-width="100px">
          <div class="tool-select-area">
            <el-select
              v-model="form.tools"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              style="width: 100%"
              placeholder="选择已启用且对 Agent 可见的工具"
            >
              <el-option
                v-for="tool in availableTools"
                :key="tool.name"
                :label="tool.name"
                :value="tool.name"
              >
                <div class="tool-option">
                  <span>{{ tool.name }}</span>
                  <span class="tool-option-desc">{{ tool.description }}</span>
                </div>
              </el-option>
            </el-select>
            <div class="tool-hint">仅展示已启用且设置为 Agent 可见的工具。</div>
          </div>
        </el-form-item>
      </el-card>

      <!-- Pipeline 配置 -->
      <el-card v-if="form.type === 'pipeline'" shadow="never" class="section-card">
        <template #header>Pipeline 配置</template>
        <el-form-item label="子 Agent ID" label-width="120px">
          <div class="tag-input-area">
            <el-tag
              v-for="(aid, idx) in form.pipelineAgentIds"
              :key="idx"
              closable
              type="warning"
              @close="form.pipelineAgentIds.splice(idx, 1)"
              class="item-tag"
            >{{ aid }}</el-tag>
            <el-input
              v-if="showPipelineInput"
              v-model="newPipelineId"
              size="small"
              style="width: 200px"
              @keyup.enter="addPipelineId"
              @blur="addPipelineId"
              placeholder="Agent ID"
            />
            <el-button v-else size="small" @click="showPipelineInput = true">
              + 添加子 Agent
            </el-button>
          </div>
        </el-form-item>
      </el-card>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft, QuestionFilled } from '@element-plus/icons-vue'
import { INTENT_TYPES, TRIGGER_MODES } from '@/types/agent'
import type { AgentForm } from '@/types/agent'
import { getAgent, createAgent, updateAgent } from '@/api/agent'
import { getTools } from '@/api/tool'
import type { ToolInfo } from '@/types/tool'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const toolOptions = ref<ToolInfo[]>([])

const form = reactive<AgentForm>({
  name: '',
  description: '',
  intentType: 'GENERAL_CHAT',
  systemPrompt: '',
  tools: [],
  modelName: '',
  maxSteps: 5,
  enabled: true,
  type: 'single',
  pipelineAgentIds: [],
  knowledgeBaseGroupId: '',
  promptTemplateId: '',
  outputSchemaType: '',
  triggerMode: 'all',
  useMultiAgentModel: false,
  extra: {},
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入 Agent 名称', trigger: 'blur' }],
}

const availableTools = computed(() =>
  toolOptions.value.filter((tool) => tool.enabled && tool.agentVisible),
)

const showPipelineInput = ref(false)
const newPipelineId = ref('')

function addPipelineId() {
  const id = newPipelineId.value.trim()
  if (id && !form.pipelineAgentIds.includes(id)) {
    form.pipelineAgentIds.push(id)
  }
  newPipelineId.value = ''
  showPipelineInput.value = false
}

async function loadAgent() {
  if (isNew) return
  pageLoading.value = true
  try {
    const { data } = await getAgent(agentId)
    Object.assign(form, {
      name: data.name,
      description: data.description || '',
      intentType: data.intentType || '',
      systemPrompt: data.systemPrompt || '',
      tools: data.tools || [],
      modelName: data.modelName || '',
      maxSteps: data.maxSteps || 5,
      enabled: data.enabled ?? true,
      type: data.type || 'single',
      pipelineAgentIds: data.pipelineAgentIds || [],
      knowledgeBaseGroupId: data.knowledgeBaseGroupId || '',
      promptTemplateId: data.promptTemplateId || '',
      outputSchemaType: data.outputSchemaType || '',
      triggerMode: data.triggerMode || 'all',
      useMultiAgentModel: data.useMultiAgentModel ?? false,
      extra: data.extra || {},
    })
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
  }
}

async function loadToolOptions() {
  try {
    const { data } = await getTools()
    toolOptions.value = Array.isArray(data) ? data : []
  } catch {
    toolOptions.value = []
    ElMessage.error('加载 Tool 选项失败')
  }
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (isNew) {
      await createAgent(form)
      ElMessage.success('创建成功')
    } else {
      await updateAgent(agentId, form)
      ElMessage.success('保存成功')
    }
    router.push('/agent')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadAgent()
  loadToolOptions()
})
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-card {
  margin-bottom: 16px;
}

.card-header-with-badge {
  display: flex;
  align-items: center;
  gap: 8px;
}

.prompt-editor {
  :deep(textarea) {
    font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
    font-size: 13px;
    line-height: 1.6;
  }
}

.tag-input-area {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.item-tag {
  margin: 0;
}

.tool-select-area {
  width: 100%;
}

.tool-option {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.tool-option-desc,
.tool-hint {
  color: #909399;
  font-size: 12px;
}

.tip-icon {
  margin-left: 6px;
  color: #909399;
  cursor: help;
}
</style>
