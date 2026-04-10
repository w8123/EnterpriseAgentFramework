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
      label-width="120px"
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
              <el-select v-model="form.intentType" placeholder="选择意图类型" style="width: 100%">
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
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="Agent 描述" />
        </el-form-item>
      </el-card>

      <!-- 模型与执行配置 -->
      <el-card shadow="never" class="section-card">
        <template #header>模型与执行</template>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item label="模型">
              <el-input v-model="form.modelName" placeholder="如 qwen-max，留空用默认" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="最大步数">
              <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="类型">
              <el-radio-group v-model="form.type">
                <el-radio value="single">单 Agent</el-radio>
                <el-radio value="pipeline">Pipeline</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
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
          <div class="tool-input-area">
            <el-tag
              v-for="(tool, idx) in form.tools"
              :key="idx"
              closable
              @close="form.tools.splice(idx, 1)"
              class="tool-tag"
            >{{ tool }}</el-tag>
            <el-input
              v-if="showToolInput"
              ref="toolInputRef"
              v-model="newToolName"
              size="small"
              style="width: 160px"
              @keyup.enter="addTool"
              @blur="addTool"
              placeholder="工具名称"
            />
            <el-button v-else size="small" @click="showToolInput = true">
              + 添加工具
            </el-button>
          </div>
        </el-form-item>
      </el-card>

      <!-- Pipeline 配置 -->
      <el-card v-if="form.type === 'pipeline'" shadow="never" class="section-card">
        <template #header>Pipeline 配置</template>
        <el-form-item label="子 Agent ID" label-width="120px">
          <div class="tool-input-area">
            <el-tag
              v-for="(aid, idx) in form.pipelineAgentIds"
              :key="idx"
              closable
              type="warning"
              @close="form.pipelineAgentIds.splice(idx, 1)"
              class="tool-tag"
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
import { ref, reactive, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { INTENT_TYPES } from '@/types/agent'
import type { AgentForm } from '@/types/agent'
import { getAgent, createAgent, updateAgent } from '@/api/agent'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)

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
  extra: {},
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入 Agent 名称', trigger: 'blur' }],
  intentType: [{ required: true, message: '请选择意图类型', trigger: 'change' }],
}

// Tool tag input
const showToolInput = ref(false)
const newToolName = ref('')
const toolInputRef = ref()

function addTool() {
  const name = newToolName.value.trim()
  if (name && !form.tools.includes(name)) {
    form.tools.push(name)
  }
  newToolName.value = ''
  showToolInput.value = false
}

// Pipeline input
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
      intentType: data.intentType,
      systemPrompt: data.systemPrompt || '',
      tools: data.tools || [],
      modelName: data.modelName || '',
      maxSteps: data.maxSteps || 5,
      enabled: data.enabled ?? true,
      type: data.type || 'single',
      pipelineAgentIds: data.pipelineAgentIds || [],
      extra: data.extra || {},
    })
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
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
  nextTick(() => toolInputRef.value?.focus?.())
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

.prompt-editor {
  :deep(textarea) {
    font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
    font-size: 13px;
    line-height: 1.6;
  }
}

.tool-input-area {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.tool-tag {
  margin: 0;
}
</style>
