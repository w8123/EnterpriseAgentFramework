<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Provider 管理</h2>
      <el-button type="primary" @click="fetchProviders" :loading="loading">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <el-row :gutter="16" v-loading="loading">
      <el-col :span="8" v-for="provider in providers" :key="provider.name">
        <el-card shadow="hover" class="provider-card">
          <template #header>
            <div class="provider-header">
              <div class="provider-name">
                <el-icon :size="20"><Coin /></el-icon>
                <span>{{ provider.name }}</span>
              </div>
              <el-tag
                :type="testResults[provider.name]?.success ? 'success' : testResults[provider.name]?.success === false ? 'danger' : 'info'"
                size="small"
              >
                {{ testResults[provider.name]?.success ? '连通' : testResults[provider.name]?.success === false ? '异常' : '未测试' }}
              </el-tag>
            </div>
          </template>
          <div class="provider-models">
            <p class="models-label">可用模型：</p>
            <el-tag
              v-for="model in provider.models"
              :key="model"
              size="small"
              class="model-tag"
            >{{ model }}</el-tag>
            <el-empty v-if="!provider.models?.length" description="无可用模型" :image-size="40" />
          </div>
          <div v-if="testResults[provider.name]" class="test-result">
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="延迟">
                {{ testResults[provider.name].latencyMs }}ms
              </el-descriptions-item>
              <el-descriptions-item label="消息">
                {{ testResults[provider.name].message }}
              </el-descriptions-item>
            </el-descriptions>
          </div>
          <div class="provider-actions">
            <el-button
              type="primary"
              size="small"
              @click="handleTest(provider.name)"
              :loading="testing[provider.name]"
            >连通性测试</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && providers.length === 0" description="未获取到 Provider 信息" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Coin } from '@element-plus/icons-vue'
import type { ProviderInfo, ProviderTestResult } from '@/types/model'
import { getProviders, testProvider } from '@/api/model'

const providers = ref<ProviderInfo[]>([])
const loading = ref(false)
const testing = reactive<Record<string, boolean>>({})
const testResults = reactive<Record<string, ProviderTestResult>>({})

async function fetchProviders() {
  loading.value = true
  try {
    const { data } = await getProviders()
    providers.value = data?.data ?? (Array.isArray(data) ? data : [])
  } catch {
    providers.value = []
  } finally {
    loading.value = false
  }
}

async function handleTest(name: string) {
  testing[name] = true
  const startTime = Date.now()
  try {
    const { data } = await testProvider(name)
    testResults[name] = {
      provider: name,
      success: true,
      message: '连接正常',
      latencyMs: Date.now() - startTime,
    }
    ElMessage.success(`${name} 连通性测试通过`)
  } catch (err: unknown) {
    testResults[name] = {
      provider: name,
      success: false,
      message: (err as Error).message || '连接失败',
      latencyMs: Date.now() - startTime,
    }
    ElMessage.error(`${name} 连通性测试失败`)
  } finally {
    testing[name] = false
  }
}

onMounted(fetchProviders)
</script>

<style scoped lang="scss">
.provider-card {
  margin-bottom: 16px;
}

.provider-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.provider-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
}

.models-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.model-tag {
  margin-right: 6px;
  margin-bottom: 6px;
}

.test-result {
  margin-top: 12px;
}

.provider-actions {
  margin-top: 16px;
  text-align: right;
}
</style>
