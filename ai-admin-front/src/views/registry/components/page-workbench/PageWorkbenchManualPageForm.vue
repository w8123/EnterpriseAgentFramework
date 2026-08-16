<script setup lang="ts">
import { reactive, watch } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { isBusinessPageUrl } from '@/utils/businessPageUrl'
import type {
  ManualProjectPageRequest,
  PageActionInput,
  ProjectPage,
  ProjectPageAction,
} from '@/types/pageWorkbench'

interface ManualActionForm {
  localId: number
  actionKey: string
  title: string
  description: string
  riskLevel: NonNullable<PageActionInput['riskLevel']>
  confirmRequired: boolean
  permissionKey: string
  implementationRef: string
  inputSchema: string
  outputSchema: string
  sampleArgs: string
}

const props = defineProps<{
  projectId: number
  page?: ProjectPage | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  submit: [request: ManualProjectPageRequest]
  cancel: []
}>()

const form = reactive({
  pageKey: '',
  moduleKey: '',
  moduleName: '',
  name: '',
  description: '',
  routePattern: '',
  businessPageUrl: '',
  componentPath: '',
  actions: [] as ManualActionForm[],
})
let actionSequence = 0

watch(
  () => props.page,
  (page) => reset(page || null),
  { immediate: true },
)

function reset(page: ProjectPage | null) {
  Object.assign(form, {
    pageKey: page?.pageKey || '',
    moduleKey: page?.moduleKey || '',
    moduleName: page?.moduleName || '',
    name: page?.name || '',
    description: page?.description || '',
    routePattern: page?.routePattern || '',
    businessPageUrl: page?.businessPageUrl || '',
    componentPath: page?.componentPath || '',
    actions: (page?.actions || [])
      .filter((action) => action.sourceType === 'MANUAL')
      .map(toActionForm),
  })
}

function toActionForm(action: ProjectPageAction): ManualActionForm {
  return {
    localId: ++actionSequence,
    actionKey: action.actionKey,
    title: action.title,
    description: action.description || '',
    riskLevel: normalizeRisk(action.riskLevel),
    confirmRequired: action.confirmRequired,
    permissionKey: action.permissionKey || '',
    implementationRef: action.implementationRef || '',
    inputSchema: prettyJson(action.inputSchema),
    outputSchema: prettyJson(action.outputSchema),
    sampleArgs: prettyJson(action.sampleArgs),
  }
}

function normalizeRisk(value?: string): ManualActionForm['riskLevel'] {
  if (value === 'WRITE' || value === 'IRREVERSIBLE' || value === 'PAGE_ACTION') return value
  return 'READ'
}

function addAction() {
  form.actions.push({
    localId: ++actionSequence,
    actionKey: '',
    title: '',
    description: '',
    riskLevel: 'READ',
    confirmRequired: false,
    permissionKey: '',
    implementationRef: '',
    inputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
    outputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
    sampleArgs: '{}',
  })
}

function removeAction(index: number) {
  form.actions.splice(index, 1)
}

function submit() {
  if (!form.pageKey.trim() || !form.name.trim()) {
    ElMessage.warning('请填写页面键和页面名称')
    return
  }
  if (form.businessPageUrl.trim() && !isBusinessPageUrl(form.businessPageUrl)) {
    ElMessage.warning('业务页面地址必须是可直接打开的 http:// 或 https:// 绝对地址')
    return
  }
  const actionKeys = new Set<string>()
  let actions: PageActionInput[]
  try {
    actions = form.actions.map((action, index) => {
      const label = `第 ${index + 1} 个页面操作`
      const actionKey = action.actionKey.trim()
      if (!actionKey || !action.title.trim()) {
        throw new Error(`${label}需要填写操作键和名称`)
      }
      if (actionKeys.has(actionKey)) {
        throw new Error(`页面操作键重复：${actionKey}`)
      }
      actionKeys.add(actionKey)
      return {
        actionKey,
        title: action.title.trim(),
        description: textOrUndefined(action.description),
        actionType: 'PAGE_ACTION',
        riskLevel: action.riskLevel,
        confirmRequired: action.confirmRequired,
        permissionKey: textOrUndefined(action.permissionKey),
        implementationRef: textOrUndefined(action.implementationRef),
        inputSchema: parseObject(action.inputSchema, `${label}的输入 Schema`),
        outputSchema: parseObject(action.outputSchema, `${label}的输出 Schema`),
        sampleArgs: parseObject(action.sampleArgs, `${label}的示例参数`),
      }
    })
  } catch (error) {
    ElMessage.warning((error as Error).message)
    return
  }

  emit('submit', {
    projectId: props.projectId,
    pageKey: form.pageKey.trim(),
    moduleKey: textOrUndefined(form.moduleKey),
    moduleName: textOrUndefined(form.moduleName),
    name: form.name.trim(),
    description: textOrUndefined(form.description),
    routePattern: textOrUndefined(form.routePattern),
    businessPageUrl: textOrUndefined(form.businessPageUrl),
    componentPath: textOrUndefined(form.componentPath),
    actions,
  })
}

function parseObject(value: string, label: string): Record<string, unknown> {
  let parsed: unknown
  try {
    parsed = JSON.parse(value || '{}')
  } catch {
    throw new Error(`${label}不是有效 JSON`)
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error(`${label}必须是 JSON 对象`)
  }
  return parsed as Record<string, unknown>
}

function prettyJson(value: unknown) {
  return JSON.stringify(value && typeof value === 'object' ? value : {}, null, 2)
}

function textOrUndefined(value: string) {
  return value.trim() || undefined
}

</script>

<template>
  <section class="page-source-pane page-source-pane--form manual-page-editor">
    <el-form class="page-source-form" label-position="top">
      <div class="form-grid form-grid--two">
        <el-form-item label="页面键" required>
          <el-input
            v-model="form.pageKey"
            :disabled="Boolean(page)"
            placeholder="例如 order.detail"
          />
        </el-form-item>
        <el-form-item label="页面名称" required>
          <el-input v-model="form.name" placeholder="例如 订单详情" />
        </el-form-item>
        <el-form-item label="模块键">
          <el-input v-model="form.moduleKey" placeholder="例如 order" />
        </el-form-item>
        <el-form-item label="模块名称">
          <el-input v-model="form.moduleName" placeholder="例如 订单管理" />
        </el-form-item>
        <el-form-item label="路由">
          <el-input v-model="form.routePattern" placeholder="/orders/:id" />
        </el-form-item>
        <el-form-item label="业务页面地址">
          <el-input
            v-model="form.businessPageUrl"
            placeholder="http://localhost:9200/orders/123"
          />
          <small>浏览器可直接打开的完整地址；不要填写后端 API / 网关 Base URL。</small>
        </el-form-item>
        <el-form-item label="入口组件">
          <el-input v-model="form.componentPath" placeholder="src/views/order/Detail.vue" />
        </el-form-item>
      </div>
      <el-form-item label="页面说明">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          placeholder="这个页面解决什么业务问题"
        />
      </el-form-item>

      <div class="manual-action-heading">
        <div>
          <strong>手动声明的页面操作</strong>
          <small>只维护手动添加的操作；SDK 同步和 AI 扫描结果不会被覆盖。</small>
        </div>
        <el-button :icon="Plus" @click="addAction">添加操作</el-button>
      </div>

      <div v-if="form.actions.length" class="manual-action-list">
        <article
          v-for="(action, index) in form.actions"
          :key="action.localId"
          class="manual-action-editor"
        >
          <header>
            <strong>{{ action.title || `页面操作 ${index + 1}` }}</strong>
            <el-button
              text
              type="danger"
              :icon="Delete"
              @click="removeAction(index)"
            >
              移除
            </el-button>
          </header>
          <div class="form-grid form-grid--two">
            <el-form-item label="操作键" required>
              <el-input v-model="action.actionKey" placeholder="例如 getPageState" />
            </el-form-item>
            <el-form-item label="操作名称" required>
              <el-input v-model="action.title" placeholder="例如 读取页面状态" />
            </el-form-item>
            <el-form-item label="风险级别">
              <el-select v-model="action.riskLevel">
                <el-option label="只读" value="READ" />
                <el-option label="写入" value="WRITE" />
                <el-option label="页面操作" value="PAGE_ACTION" />
                <el-option label="不可逆" value="IRREVERSIBLE" />
              </el-select>
            </el-form-item>
            <el-form-item label="权限键">
              <el-input v-model="action.permissionKey" placeholder="可选" />
            </el-form-item>
          </div>
          <el-form-item label="操作说明">
            <el-input v-model="action.description" placeholder="说明动作边界和可见结果" />
          </el-form-item>
          <div class="manual-action-options">
            <el-switch v-model="action.confirmRequired" />
            <span>执行前需要用户确认</span>
          </div>
          <el-form-item label="业务系统实现位置">
            <el-input
              v-model="action.implementationRef"
              placeholder="例如 src/views/order/Detail.vue#registerAction"
            />
          </el-form-item>
          <el-collapse>
            <el-collapse-item title="输入、输出与示例参数" name="contract">
              <div class="manual-action-contracts">
                <el-form-item label="输入 Schema">
                  <el-input v-model="action.inputSchema" type="textarea" :rows="6" />
                </el-form-item>
                <el-form-item label="输出 Schema">
                  <el-input v-model="action.outputSchema" type="textarea" :rows="6" />
                </el-form-item>
                <el-form-item label="示例参数">
                  <el-input v-model="action.sampleArgs" type="textarea" :rows="4" />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </article>
      </div>
      <div v-else class="manual-action-empty">
        暂无手动操作。页面可先保存，操作也可以稍后补充。
      </div>
    </el-form>

    <footer class="page-source-pane__footer">
      <p>{{ page ? '保存后立即更新当前页面地图。' : '适合动态路由、外部页面或少量特殊页面。' }}</p>
      <div class="page-source-pane__actions">
        <el-button @click="emit('cancel')">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ page ? '保存页面和操作' : '添加到页面地图' }}
        </el-button>
      </div>
    </footer>
  </section>
</template>
