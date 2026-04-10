<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Tool 管理</h2>
      <el-button type="primary" @click="fetchTools" :loading="loading">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="tools" v-loading="loading" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <h4>参数定义</h4>
              <el-table :data="row.parameters || []" size="small" border>
                <el-table-column prop="name" label="参数名" width="160" />
                <el-table-column prop="type" label="类型" width="100" />
                <el-table-column prop="description" label="描述" />
                <el-table-column prop="required" label="必填" width="80" align="center">
                  <template #default="{ row: param }">
                    <el-tag :type="param.required ? 'danger' : 'info'" size="small">
                      {{ param.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="工具名" width="200">
          <template #default="{ row }">
            <el-text type="primary" tag="b">{{ row.name }}</el-text>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" />
        <el-table-column label="参数数量" width="100" align="center">
          <template #default="{ row }">
            {{ (row.parameters || []).length }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTest(row)">测试</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && tools.length === 0" description="未获取到已注册 Tool（需后端提供 /api/tools 接口）" />
    </el-card>

    <!-- 测试弹窗 -->
    <el-dialog v-model="testDialogVisible" :title="`测试工具 — ${testingTool?.name}`" width="600px">
      <el-form v-if="testingTool" label-width="120px">
        <el-form-item
          v-for="param in testingTool.parameters"
          :key="param.name"
          :label="param.name"
          :required="param.required"
        >
          <el-input
            v-model="testArgs[param.name]"
            :placeholder="param.description || param.type"
          />
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
        <el-button type="primary" @click="handleTest" :loading="testRunning">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import type { ToolInfo, ToolTestResult } from '@/types/tool'
import { getTools, testTool } from '@/api/tool'

const tools = ref<ToolInfo[]>([])
const loading = ref(false)

const testDialogVisible = ref(false)
const testingTool = ref<ToolInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<ToolTestResult | null>(null)
const testRunning = ref(false)

async function fetchTools() {
  loading.value = true
  try {
    const { data } = await getTools()
    tools.value = Array.isArray(data) ? data : []
  } catch {
    tools.value = []
  } finally {
    loading.value = false
  }
}

function openTest(tool: ToolInfo) {
  testingTool.value = tool
  testResult.value = null
  Object.keys(testArgs).forEach((k) => delete testArgs[k])
  for (const p of tool.parameters || []) {
    testArgs[p.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingTool.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const args: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(testArgs)) {
      if (v !== '') args[k] = v
    }
    const { data } = await testTool(testingTool.value.name, args)
    testResult.value = data as unknown as ToolTestResult
  } catch (err: unknown) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (err as Error).message || '执行失败',
      durationMs: 0,
    }
  } finally {
    testRunning.value = false
  }
}

onMounted(fetchTools)
</script>

<style scoped lang="scss">
.expand-content {
  padding: 12px 20px;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

.param-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.test-result-area {
  margin-top: 12px;
}

.result-content {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  font-size: 13px;
  margin-top: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-duration {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}
</style>
