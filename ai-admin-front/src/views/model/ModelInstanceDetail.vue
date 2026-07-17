<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  EditPen,
  Refresh,
  Switch,
  Warning,
} from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import HeaderMetaList from '@/components/common/HeaderMetaList.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import {
  archiveModelInstance,
  getModelInstance,
  testModelInstance,
  testModelInstanceDraft,
  updateModelInstance,
} from '@/api/model'
import type { ModelInstance, ModelInstanceTestResult, ModelTemplate } from '@/types/model'
import ModelInstanceForm from './components/ModelInstanceForm.vue'
import ModelProviderIcon from './components/ModelProviderIcon.vue'
import ModelTemplatePicker from './components/ModelTemplatePicker.vue'
import ModelTestResultPanel from './components/ModelTestResultPanel.vue'
import {
  ARCHIVE_CONFIRM_MESSAGE,
  EMBEDDING_REPLACE_WARNING,
  applyCustomUpstreamToDraft,
  applyTemplateToDraft,
  buildDraftTestRequest,
  buildStatusUpdateRequest,
  buildUpdateRequest,
  draftFromInstance,
  extractBaseUrlAuthority,
  formatDateTime,
  formatLatency,
  hasConfiguredApiKey,
  modelTypeLabel,
  pathKeyForType,
  providerDisplayName,
  runtimeStatusLabel,
  runtimeStatusTone,
  testStatusLabel,
  testStatusTone,
  type ModelInstanceDraft,
  readModelApiPayload,
  validateDraftForSave,
} from './modelCenterUi'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const loadError = ref('')
const instance = ref<ModelInstance | null>(null)
const editing = ref(false)
const draft = ref<ModelInstanceDraft | null>(null)
const editSnapshot = ref<ModelInstanceDraft | null>(null)
const apiKey = ref('')
const dirty = ref(false)
const saving = ref(false)
const testing = ref(false)
const toggling = ref(false)
const archiving = ref(false)
const replaceVisible = ref(false)
const customReplaceVisible = ref(false)
const customReplace = ref({ provider: '', modelName: '', baseUrl: '', path: '' })
const draftTestResult = ref<ModelInstanceTestResult | null>(null)
const draftTestStale = ref(false)
const formRef = ref<InstanceType<typeof ModelInstanceForm> | null>(null)

const archived = computed(() => instance.value?.status === 'ARCHIVED')
const instanceId = computed(() => String(route.params.id || ''))

const headerMeta = computed(() => {
  if (!instance.value) return []
  return [
    { key: 'provider', label: '供应商', value: providerDisplayName(instance.value.provider) },
    { key: 'modelName', label: '上游模型', value: instance.value.modelName },
    { key: 'updatedAt', label: '更新时间', value: formatDateTime(instance.value.updatedAt) },
  ]
})

const pathKey = computed(() => (instance.value ? pathKeyForType(instance.value.modelType) : 'chatPath'))
const pathValue = computed(() => {
  if (!instance.value) return '-'
  return String(instance.value.connection?.[pathKey.value] || '-')
})

const defaultOptionsJson = computed(() =>
  JSON.stringify(instance.value?.defaultOptions || {}, null, 2),
)

async function loadDetail() {
  if (!instanceId.value) {
    loadError.value = '缺少模型实例 ID'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getModelInstance(instanceId.value)
    instance.value = readModelApiPayload<ModelInstance>(data)
    if (!editing.value) {
      draft.value = null
      editSnapshot.value = null
      apiKey.value = ''
      dirty.value = false
      draftTestResult.value = null
      draftTestStale.value = false
    }
  } catch (err) {
    instance.value = null
    loadError.value = err instanceof Error ? err.message : '加载模型详情失败'
  } finally {
    loading.value = false
  }
}

function enterEdit() {
  if (!instance.value || archived.value) return
  const next = draftFromInstance(instance.value)
  draft.value = next
  editSnapshot.value = structuredClone(next)
  apiKey.value = ''
  editing.value = true
  dirty.value = false
  draftTestResult.value = null
  draftTestStale.value = false
}

function cancelEdit() {
  draft.value = editSnapshot.value ? structuredClone(editSnapshot.value) : null
  apiKey.value = ''
  editing.value = false
  dirty.value = false
  draftTestResult.value = null
  draftTestStale.value = false
}

function markDirty() {
  dirty.value = true
  if (draftTestResult.value) draftTestStale.value = true
}

function syncFormDraft(): ModelInstanceDraft | null {
  if (!draft.value) return null
  return formRef.value?.commitFriendlyOptions() || draft.value
}

async function handleDraftTest() {
  const current = syncFormDraft()
  if (!current || !instance.value) return
  draft.value = current
  const error = validateDraftForSave(current, apiKey.value, { mode: 'update' })
  if (error) {
    ElMessage.warning(error)
    return
  }
  testing.value = true
  try {
    const payload = buildDraftTestRequest(current, apiKey.value, { instanceId: instance.value.id })
    const { data } = await testModelInstanceDraft(payload)
    const result = readModelApiPayload<ModelInstanceTestResult>(data)
    draftTestResult.value = result
    draftTestStale.value = false
    if (result.success) ElMessage.success('草稿测试通过')
    else ElMessage.warning(result.message || '草稿测试未通过')
  } catch (err) {
    draftTestResult.value = null
    ElMessage.error(err instanceof Error ? err.message : '测试接口异常')
  } finally {
    testing.value = false
  }
}

async function handleSavedTest() {
  if (!instance.value || archived.value) return
  testing.value = true
  try {
    const { data } = await testModelInstance(instance.value.id)
    const result = readModelApiPayload<ModelInstanceTestResult>(data)
    if (result.success) {
      ElMessage.success('测试通过')
    } else {
      ElMessage.warning(result.message || '测试失败')
    }
    await loadDetail()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '测试失败')
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  const current = syncFormDraft()
  if (!current || !instance.value) return
  draft.value = current
  const error = validateDraftForSave(current, apiKey.value, { mode: 'update' })
  if (error) {
    ElMessage.warning(error)
    return
  }
  saving.value = true
  try {
    const payload = buildUpdateRequest(current, apiKey.value)
    const { data } = await updateModelInstance(instance.value.id, payload)
    instance.value = readModelApiPayload<ModelInstance>(data)
    editing.value = false
    dirty.value = false
    draft.value = null
    editSnapshot.value = null
    apiKey.value = ''
    draftTestResult.value = null
    draftTestStale.value = false
    ElMessage.success('配置已保存')
    await loadDetail()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleToggle() {
  if (!instance.value || archived.value) return
  const nextStatus = instance.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (nextStatus === 'ACTIVE' && instance.value.lastTestStatus !== 'SUCCESS') {
    try {
      await ElMessageBox.confirm(
        `当前测试状态为「${testStatusLabel(instance.value.lastTestStatus)}」，仍要启用吗？启用只表示运行开关，不代表测试通过。`,
        '启用确认',
        { type: 'warning', confirmButtonText: '仍然启用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  toggling.value = true
  try {
    const { data } = await updateModelInstance(
      instance.value.id,
      buildStatusUpdateRequest(instance.value, nextStatus),
    )
    instance.value = readModelApiPayload<ModelInstance>(data)
    ElMessage.success(nextStatus === 'ACTIVE' ? '已启用' : '已停用')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '更新状态失败')
  } finally {
    toggling.value = false
  }
}

async function handleArchive() {
  if (!instance.value || archived.value) return
  try {
    await ElMessageBox.confirm(ARCHIVE_CONFIRM_MESSAGE, '归档模型', {
      type: 'warning',
      confirmButtonText: '确认归档',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  archiving.value = true
  try {
    await archiveModelInstance(instance.value.id)
    ElMessage.success('模型已归档')
    router.push({ name: 'ModelInstances' })
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '归档失败')
  } finally {
    archiving.value = false
  }
}

function ensureEditingDraft() {
  if (!instance.value) return
  if (!editing.value || !draft.value) {
    enterEdit()
  }
}

async function handleSelectReplaceTemplate(template: ModelTemplate) {
  ensureEditingDraft()
  if (!draft.value) return
  if (template.modelType === 'EMBEDDING' || draft.value.modelType === 'EMBEDDING') {
    await ElMessageBox.alert(EMBEDDING_REPLACE_WARNING, '向量模型更换提示', { type: 'warning' })
  }
  draft.value = applyTemplateToDraft(draft.value, template)
  apiKey.value = ''
  replaceVisible.value = false
  markDirty()
  ElMessage.success('已应用新的上游模型到编辑草稿，请检查连接配置后保存')
}

async function handleSelectCustomReplace() {
  ensureEditingDraft()
  if (!draft.value) return
  customReplace.value = {
    provider: draft.value.provider,
    modelName: draft.value.modelName,
    baseUrl: draft.value.connection.baseUrl || '',
    path: String(draft.value.connection[pathKeyForType(draft.value.modelType)] || ''),
  }
  replaceVisible.value = false
  customReplaceVisible.value = true
}

async function confirmCustomReplace() {
  if (!draft.value) return
  if (draft.value.modelType === 'EMBEDDING') {
    await ElMessageBox.alert(EMBEDDING_REPLACE_WARNING, '向量模型更换提示', { type: 'warning' })
  }
  draft.value = applyCustomUpstreamToDraft(draft.value, customReplace.value)
  apiKey.value = ''
  customReplaceVisible.value = false
  markDirty()
}

function beforeUnloadHandler(event: BeforeUnloadEvent) {
  if (!dirty.value || !editing.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (!dirty.value || !editing.value) return true
  try {
    await ElMessageBox.confirm('有未保存的修改，确定离开吗？', '离开确认', {
      type: 'warning',
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
    })
    return true
  } catch {
    return false
  }
})

watch(instanceId, () => {
  editing.value = false
  dirty.value = false
  loadDetail()
})

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnloadHandler)
  loadDetail()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnloadHandler)
})
</script>

<template>
  <WorkbenchPage class="model-detail" density="comfortable">
    <div v-if="loading && !instance" class="model-detail__loading">
      <el-skeleton animated :rows="8" />
    </div>

    <div v-else-if="loadError" class="model-detail__error">
      <el-result icon="warning" title="无法加载模型详情" :sub-title="loadError">
        <template #extra>
          <el-button @click="router.push({ name: 'ModelInstances' })">返回模型中心</el-button>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else-if="instance">
      <PageHeader
        variant="entity"
        domain="platform"
        :title="instance.name"
        show-back
        back-label="返回模型中心"
        @back="router.push({ name: 'ModelInstances' })"
      >
        <template #tags>
          <StatusTag :label="modelTypeLabel(instance.modelType)" tone="info" />
          <StatusTag
            :label="runtimeStatusLabel(instance.status)"
            :tone="runtimeStatusTone(instance.status)"
          />
          <StatusTag
            :label="testStatusLabel(instance.lastTestStatus)"
            :tone="testStatusTone(instance.lastTestStatus)"
          />
        </template>
        <template #meta>
          <HeaderMetaList :items="headerMeta" />
        </template>
        <template #actions>
          <el-button :icon="Refresh" :loading="loading" @click="loadDetail">刷新</el-button>
          <el-button
            v-if="!archived && !editing"
            :loading="testing"
            @click="handleSavedTest"
          >
            测试
          </el-button>
          <el-button
            v-if="!archived && !editing"
            :loading="toggling"
            :icon="Switch"
            @click="handleToggle"
          >
            {{ instance.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
          <el-button
            v-if="!archived && !editing"
            type="primary"
            :icon="EditPen"
            @click="enterEdit"
          >
            编辑配置
          </el-button>
          <el-button
            v-if="!archived"
            plain
            @click="replaceVisible = true"
          >
            更换上游模型
          </el-button>
        </template>
      </PageHeader>

      <div v-if="!editing" class="model-detail__panels">
        <WorkbenchPanel title="基本信息" density="compact">
          <div class="info-grid">
            <div class="info-row span-2 brand-row">
              <ModelProviderIcon :provider="instance.provider" :size="44" />
              <div>
                <strong>{{ instance.name }}</strong>
                <p>{{ providerDisplayName(instance.provider) }} · {{ instance.modelName }}</p>
              </div>
            </div>
            <div class="info-row"><span>实例 ID</span><code>{{ instance.id }}</code></div>
            <div class="info-row"><span>模型类型</span><em>{{ modelTypeLabel(instance.modelType) }}</em></div>
            <div class="info-row"><span>协议</span><em>{{ instance.protocol }}</em></div>
            <div class="info-row"><span>备注</span><em>{{ instance.remark || '-' }}</em></div>
            <div class="info-row"><span>创建时间</span><em>{{ formatDateTime(instance.createdAt) }}</em></div>
            <div class="info-row"><span>更新时间</span><em>{{ formatDateTime(instance.updatedAt) }}</em></div>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel title="连接配置" density="compact">
          <div class="info-grid">
            <div class="info-row span-2">
              <span>Base URL</span>
              <em>{{ instance.connection?.baseUrl || '-' }}</em>
            </div>
            <div class="info-row">
              <span>Authority</span>
              <em>{{ extractBaseUrlAuthority(instance.connection?.baseUrl) }}</em>
            </div>
            <div class="info-row">
              <span>{{ pathKey }}</span>
              <em>{{ pathValue }}</em>
            </div>
            <div class="info-row"><span>authHeader</span><em>{{ instance.connection?.authHeader || '-' }}</em></div>
            <div class="info-row"><span>authPrefix</span><em>{{ instance.connection?.authPrefix || '-' }}</em></div>
            <div class="info-row">
              <span>API Key</span>
              <em>{{ hasConfiguredApiKey(instance.connection) ? '已配置' : '未配置' }}</em>
            </div>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel title="默认模型参数" density="compact">
          <pre class="json-block">{{ defaultOptionsJson }}</pre>
        </WorkbenchPanel>

        <WorkbenchPanel title="最近测试" density="compact">
          <div class="info-grid">
            <div class="info-row">
              <span>测试状态</span>
              <StatusTag
                :label="testStatusLabel(instance.lastTestStatus)"
                :tone="testStatusTone(instance.lastTestStatus)"
              />
            </div>
            <div class="info-row"><span>测试时间</span><em>{{ formatDateTime(instance.lastTestAt) }}</em></div>
            <div class="info-row"><span>耗时</span><em>{{ formatLatency(instance.lastTestLatencyMs) }}</em></div>
            <div class="info-row span-2">
              <span>错误信息</span>
              <em>{{ instance.lastTestError || '-' }}</em>
            </div>
          </div>
          <div v-if="!archived" class="panel-actions">
            <el-button :loading="testing" @click="handleSavedTest">测试当前已保存配置</el-button>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel v-if="!archived" title="危险操作" density="compact">
          <el-alert type="warning" :closable="false" show-icon :title="ARCHIVE_CONFIRM_MESSAGE" />
          <div class="panel-actions">
            <el-button type="danger" :icon="Warning" :loading="archiving" @click="handleArchive">
              归档模型
            </el-button>
          </div>
        </WorkbenchPanel>
      </div>

      <div v-else-if="draft" class="model-detail__edit">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="编辑模式下可修改名称、modelName、连接配置与默认参数。模型类型不可变；供应商请通过「更换上游模型」调整。"
        />
        <WorkbenchPanel title="编辑配置" density="compact">
          <ModelInstanceForm
            ref="formRef"
            :draft="draft"
            :api-key="apiKey"
            mode="update"
            lock-model-type
            lock-provider
            :show-enable-toggle="false"
            @update:draft="draft = $event; markDirty()"
            @update:api-key="apiKey = $event; markDirty()"
            @runtime-changed="markDirty"
          />
        </WorkbenchPanel>
        <ModelTestResultPanel :result="draftTestResult" :stale="draftTestStale" />
        <div class="model-detail__sticky-actions">
          <el-button @click="cancelEdit">取消编辑</el-button>
          <el-button :loading="testing" @click="handleDraftTest">测试当前配置</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">保存更改</el-button>
        </div>
      </div>
    </template>

    <AppDialog
      v-model="replaceVisible"
      title="更换上游模型"
      description="选择新的上游模型。实例 ID 和模型类型保持不变，选择后请检查连接配置并重新测试。"
      width="960px"
      destroy-on-close
    >
      <ModelTemplatePicker
        v-if="instance"
        :model-type="instance.modelType"
        lock-model-type
        @select-template="handleSelectReplaceTemplate"
        @select-custom="handleSelectCustomReplace"
      />
    </AppDialog>

    <AppDialog
      v-model="customReplaceVisible"
      title="自定义同类型上游模型"
      width="640px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="供应商" required>
          <el-input v-model="customReplace.provider" />
        </el-form-item>
        <el-form-item label="上游 modelName" required>
          <el-input v-model="customReplace.modelName" />
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="customReplace.baseUrl" />
        </el-form-item>
        <el-form-item label="接口 Path">
          <el-input v-model="customReplace.path" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customReplaceVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmCustomReplace">应用到草稿</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.model-detail__loading,
.model-detail__error {
  padding: 24px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
}

.model-detail__panels,
.model-detail__edit {
  display: grid;
  gap: 16px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
}

.info-row {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.info-row.span-2 {
  grid-column: 1 / -1;
}

.info-row span {
  color: var(--text-muted);
  font-size: 12px;
}

.info-row em,
.info-row code,
.info-row strong,
.brand-row p {
  margin: 0;
  color: var(--text-primary);
  font-style: normal;
  word-break: break-word;
}

.brand-row {
  display: flex;
  gap: 12px;
  align-items: center;
}

.brand-row p {
  margin-top: 4px;
  color: var(--text-secondary);
}

.json-block {
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.panel-actions,
.model-detail__sticky-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.model-detail__sticky-actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--surface-glass-panel) 92%, transparent);
  backdrop-filter: blur(8px);
}

@media (max-width: 860px) {
  .info-grid {
    grid-template-columns: 1fr;
  }

  .info-row.span-2 {
    grid-column: auto;
  }
}
</style>
