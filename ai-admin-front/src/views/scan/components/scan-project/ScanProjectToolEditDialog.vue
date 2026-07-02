<script setup lang="ts">
import type { ProjectToolInfo } from '@/types/scanProject'
import type { ToolTestResult, ToolUpsertRequest } from '@/types/tool'

defineProps<{
  form: ToolUpsertRequest
  httpMethods: string[]
  parameterLocations: string[]
  testingTool: ProjectToolInfo | null
  testArgs: Record<string, string>
  testResult: ToolTestResult | null
  saving: boolean
  testRunning: boolean
}>()

const formDialogVisible = defineModel<boolean>('formDialogVisible', { required: true })
const testDialogVisible = defineModel<boolean>('testDialogVisible', { required: true })

const emit = defineEmits<{
  save: []
  test: []
  addParameter: []
  removeParameter: [index: number]
}>()
</script>

<template>
  <el-dialog v-model="formDialogVisible" :title="form.name ? `编辑 Tool - ${form.name}` : '编辑 Tool'" width="760px">
    <el-form label-width="120px">
      <el-form-item label="工具名">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="来源">
            <el-input :model-value="form.source" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="HTTP 方法">
            <el-select v-model="form.httpMethod" style="width: 100%">
              <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="请求体类型">
            <el-input v-model="form.requestBodyType" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Base URL">
            <el-input v-model="form.baseUrl" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Context Path">
            <el-input v-model="form.contextPath" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Endpoint Path">
            <el-input v-model="form.endpointPath" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="响应类型">
            <el-input v-model="form.responseType" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="来源定位">
        <el-input v-model="form.sourceLocation" />
      </el-form-item>
      <el-form-item label="参数定义">
        <div class="parameter-editor">
          <el-table :data="form.parameters" size="small" border>
            <el-table-column label="参数名" min-width="120">
              <template #default="{ row }">
                <el-input v-model="row.name" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-input v-model="row.type" />
              </template>
            </el-table-column>
            <el-table-column label="位置" width="120">
              <template #default="{ row }">
                <el-select v-model="row.location" style="width: 100%">
                  <el-option v-for="location in parameterLocations" :key="location" :label="location" :value="location" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="描述" min-width="180">
              <template #default="{ row }">
                <el-input v-model="row.description" />
              </template>
            </el-table-column>
            <el-table-column label="必填" width="80" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.required" />
              </template>
            </el-table-column>
            <el-table-column width="80" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="emit('removeParameter', $index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button class="add-parameter-button" @click="emit('addParameter')">+ 添加参数</el-button>
        </div>
      </el-form-item>
      <el-form-item label="运行控制">
        <div class="switch-group">
          <el-switch v-model="form.enabled" />
          <span>启用</span>
          <el-switch v-model="form.agentVisible" />
          <span>Agent 可见</span>
          <el-switch v-model="form.lightweightEnabled" />
          <span>轻量调用可见</span>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="formDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('save')">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="testDialogVisible" :title="`测试工具 - ${testingTool?.name}`" width="600px">
    <el-form v-if="testingTool" label-width="120px">
      <el-form-item v-for="param in testingTool.parameters" :key="param.name" :label="param.name" :required="param.required">
        <el-input v-model="testArgs[param.name]" :placeholder="param.description || param.type" />
        <div class="param-hint">{{ param.description }} ({{ param.type }})</div>
      </el-form-item>
    </el-form>

    <div v-if="testResult" class="test-result-area">
      <el-divider content-position="left">执行结果</el-divider>
      <el-alert
        :type="testResult.success ? 'success' : 'error'"
        :title="testResult.success ? '执行成功' : '执行失败'"
        :description="testResult.errorMessage || ''"
        :closable="false"
        show-icon
      />
      <pre v-if="testResult.result" class="result-content">{{ testResult.result }}</pre>
      <p class="result-duration">耗时：{{ testResult.durationMs }}ms</p>
    </div>

    <template #footer>
      <el-button @click="testDialogVisible = false">关闭</el-button>
      <el-button type="primary" :loading="testRunning" @click="emit('test')">执行</el-button>
    </template>
  </el-dialog>
</template>
