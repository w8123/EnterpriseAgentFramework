<script setup lang="ts">
import { computed } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import AppDialog from '@/components/common/AppDialog.vue'
import CapabilitySemanticDocument from './CapabilitySemanticDocument.vue'
import { useCatalogClassificationEditor, type ClassificationEditorProps } from '../composables/useCatalogClassificationEditor'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'
const props = defineProps<ClassificationEditorProps>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; updated: []; 'busy-change': [value: boolean]; 'close-auto-focus': [] }>()
const { enrichment, loading, error, mutationError, status, busy, editing, draft, expectedUpdatedAt, conflict, renameValue,
  assignGroups, assignmentValid, assignmentReady, mixedAssignment, selectedSubjects, loadAssignments, clearAssignments, generationOpen, models, modelId,
  modelsLoading, modelsError, leaveOpen, canWrite, groups, validGroup, title, load, startEdit, saveDescription,
  prepareGeneration, generate, saveName, assign, createName, createValid, createGroup, requestClose, requestLeave, cancelLeave, discardLeave } = useCatalogClassificationEditor(props,
    (event, value) => { if (event === 'updated') emit('updated'); else if (event === 'busy-change') emit('busy-change', !!value); else emit('update:visible', !!value) })
const open = computed({ get: () => props.visible, set: value => { if (!value) requestClose() } })
const leaveDialogOpen = computed({ get: () => leaveOpen.value, set: value => { if (!value) cancelLeave() } })
async function beforeClose(done: () => void) { if (await requestClose()) done() }
defineExpose({ requestClose })
onBeforeRouteLeave(requestLeave)
onBeforeRouteUpdate(requestLeave)
</script>
<template>
  <AppDialog v-model="open" :title="title" width="720px" :before-close="beforeClose" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy"
    @close-auto-focus="emit('close-auto-focus')">
    <div class="classification-editor">
      <p v-if="group && validGroup" class="classification-context">{{ group.displayName || group.name }}</p>
      <details v-if="group && validGroup" class="classification-source">
        <summary>分类来源</summary>
        <p v-if="!group.name.startsWith('category:')">初始标识：{{ group.name }}</p>
        <p v-if="group.sourceClasses.length">来源类：{{ group.sourceClasses.join('、') }}</p>
        <p>来源线索用于初始归类，可按业务用途整理展示名称与成员。</p>
      </details>
      <p v-if="!canWrite" role="status">当前账号仅可阅读业务分类，无法保存修改。</p>
      <template v-if="mode === 'description'">
        <div class="classification-actions">
          <el-button v-if="canWrite && !editing" :disabled="busy || loading || !enrichment || !!error" @click="prepareGeneration">{{ enrichment?.semanticDoc ? '重新生成说明' : '生成说明' }}</el-button>
          <el-button v-if="canWrite && enrichment?.semanticDoc && !editing" :disabled="busy || loading || !!error" @click="startEdit">编辑说明</el-button>
          <el-button :disabled="busy || loading" @click="load">重读最新说明</el-button>
        </div>
        <el-form v-if="generationOpen" novalidate label-position="top" @submit.prevent="generate">
          <p>选择生成模型。重新生成会替换当前分类说明，请先核对人工整理的内容。</p>
          <el-form-item label="生成模型"><el-select v-model="modelId" aria-label="生成模型" :loading="modelsLoading" :disabled="busy" placeholder="请选择模型">
            <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
          </el-select></el-form-item>
          <p v-if="modelsError" role="alert">{{ modelsError }} <el-button link @click="prepareGeneration">重试模型列表</el-button></p>
          <p v-else-if="!modelsLoading && !models.length" role="status">没有可用的语言模型，请先在模型中心配置。</p>
          <el-button type="primary" :disabled="busy || modelsLoading || !models.some(model => model.id === modelId)" :loading="busy" @click="generate">确认生成说明</el-button>
          <el-button :disabled="busy" @click="generationOpen = false">取消生成</el-button>
        </el-form>
        <el-form v-if="editing" novalidate label-position="top" @submit.prevent="saveDescription">
          <el-form-item label="分类说明内容（Markdown）"><el-input v-model="draft" type="textarea" :rows="12" resize="none" aria-label="分类说明内容" :disabled="busy || !canWrite" /></el-form-item>
          <p v-if="!expectedUpdatedAt" role="alert">缺少说明修订，请重读最新说明后再保存。</p>
          <el-button type="primary" :disabled="busy || loading || conflict || !expectedUpdatedAt || !canWrite" :loading="busy" @click="saveDescription">保存说明</el-button>
        </el-form>
        <p v-if="loading" role="status">正在读取分类说明…</p>
        <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重新加载</el-button></div>
        <template v-else-if="enrichment?.semanticDoc">
          <p class="classification-provenance">{{ enrichment.semanticDoc.status === 'edited' ? '人工编辑的分类说明' : 'AI 补充的分类说明' }}<span v-if="enrichment.semanticDoc.modelName"> · {{ enrichment.semanticDoc.modelName }}</span></p>
          <LiquidGlassNotice
            v-if="enrichment.semanticStale"
            title="分类成员已经变化，请核对当前成员后更新说明。"
            type="warning"
            source="backdrop"
          />
          <CapabilitySemanticDocument :content-md="enrichment.semanticDoc.contentMd" />
        </template>
        <p v-else-if="enrichment" class="classification-provenance">尚未补充分类说明。</p>
      </template>
      <el-form v-else-if="mode === 'create'" novalidate label-position="top" :disabled="!canWrite || busy" @submit.prevent="createGroup">
        <el-form-item label="分类名称"><el-input v-model="createName" aria-label="分类名称" maxlength="128" placeholder="例如：人员管理" /></el-form-item>
        <p>分类由当前项目的 Java 业务方法和 HTTP API 共用。新增后可选择能力进行归类。</p>
      </el-form>
      <el-form v-else-if="mode === 'rename'" novalidate label-position="top" :disabled="!canWrite || busy" @submit.prevent="saveName">
        <el-form-item label="分类名称"><el-input v-model="renameValue" aria-label="分类名称" maxlength="255" /></el-form-item>
        <p v-if="!validGroup" role="alert">分类不属于当前项目，请重新打开当前项目的分类。</p>
      </el-form>
      <el-form v-else-if="mode === 'assign'" novalidate label-position="top" :disabled="!canWrite || busy" @submit.prevent="assign">
        <p>{{ selectedSubjects.length > 1 ? `已选 ${selectedSubjects.length} 项能力` : selectedSubjects[0]?.title || '当前能力' }}</p>
        <p v-if="mixedAssignment" role="status">所选能力当前分类不同。保存后，全部所选能力将使用下方分类。</p>
        <p>可选择多个分类，输入新名称并按回车创建。取消全部选择后保存为未分类；后续同步保留人工选择。</p>
        <p v-if="loading" role="status">正在读取当前分类…</p>
        <p v-if="error" role="alert">{{ error }}</p>
        <el-form-item label="业务分类">
          <el-select v-model="assignGroups" multiple filterable allow-create default-first-option clearable
            aria-label="业务分类" placeholder="选择分类或输入新名称" :multiple-limit="30" :disabled="loading || !assignmentReady">
            <el-option v-for="item in groups" :key="item.id" :label="item.displayName || item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-button :disabled="busy || loading" @click="loadAssignments(true)">重读当前归属</el-button>
        <el-button v-if="selectedSubjects.length > 1" :disabled="busy || loading || !assignmentReady" @click="clearAssignments">全部设为未分类</el-button>
      </el-form>
      <p v-if="mutationError" role="alert">{{ mutationError }}</p>
      <p v-if="status" role="status">{{ status }}</p>
    </div>
    <template #footer>
      <LiquidGlassButton :disabled="busy" @click="requestClose">关闭</LiquidGlassButton>
      <LiquidGlassButton v-if="mode === 'create' && canWrite" type="primary" :disabled="busy || !createValid" :loading="busy" @click="createGroup">新增分类</LiquidGlassButton>
      <LiquidGlassButton v-if="mode === 'rename' && canWrite" type="primary" :disabled="busy || !validGroup || !renameValue.trim()" :loading="busy" @click="saveName">保存名称</LiquidGlassButton>
      <LiquidGlassButton v-if="mode === 'assign' && canWrite" type="primary" :disabled="busy || !assignmentValid" :loading="busy" @click="assign">保存归类</LiquidGlassButton>
    </template>
  </AppDialog>
  <AppDialog v-model="leaveDialogOpen" title="还有未保存的分类整理" description="离开会丢弃当前输入。保留编辑可以继续核对和保存。" width="440px">
    <template #footer><LiquidGlassButton @click="cancelLeave">保留编辑</LiquidGlassButton><LiquidGlassButton type="danger" @click="discardLeave">放弃输入并离开</LiquidGlassButton></template>
  </AppDialog>
</template>

<style scoped lang="scss">
.classification-editor { min-width: 0; overflow-wrap: anywhere; }
.classification-editor p { line-height: 1.65; }
.classification-context { margin: 0 0 16px; color: var(--text-primary); font-weight: 600; }
.classification-source { margin-bottom: 16px; color: var(--text-secondary); font-size: 12px; }
.classification-source summary { cursor: pointer; }
.classification-source summary:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.classification-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.classification-actions .el-button + .el-button { margin-left: 0; }
.classification-editor .el-select { width: 100%; }
.classification-provenance { color: var(--text-secondary); font-size: 12px; }
.classification-provenance { padding-top: 16px; border-top: 1px solid var(--border-divider); }
</style>
