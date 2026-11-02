<template>
  <LiquidGlassCard class="memory-workspace" :source="props.source">
    <aside class="memory-tree" aria-label="记忆分组">
      <div class="memory-tree__heading">记忆空间</div>
      <button ref="groupNavigationToggle" type="button" class="memory-tree__mobile-toggle" :aria-expanded="groupNavigationOpen" :aria-controls="navigationId" aria-label="切换记忆分组" @click="groupNavigationOpen = !groupNavigationOpen">
        <el-icon aria-hidden="true"><FolderOpened /></el-icon>
        <span>{{ selectionTitle }}</span><small>切换分组</small>
        <el-icon aria-hidden="true" :class="{ 'is-open': groupNavigationOpen }"><ArrowDown /></el-icon>
      </button>
      <div :id="navigationId" class="memory-tree__navigation" :class="{ 'is-open': groupNavigationOpen }">
      <button
        type="button"
        class="memory-tree__row memory-tree__all"
        :class="{ active: !selection.scope }"
        :aria-current="!selection.scope ? 'true' : undefined"
        @click="select(null, null)"
      >
        <el-icon aria-hidden="true"><Collection /></el-icon>
        <span class="memory-tree__label">全部记忆</span><small>{{ tree.total }}</small>
      </button>

      <section v-for="layer in tree.layers" :key="layer.scope" class="memory-tree__layer">
        <div class="memory-tree__layer-head">
          <button
            type="button"
            class="memory-tree__row memory-tree__layer-name"
            :class="{ active: selection.scope === layer.scope && selection.groupId === null }"
            :aria-current="selection.scope === layer.scope && selection.groupId === null ? 'true' : undefined"
            @click="select(layer.scope, null)"
          >
            <el-icon aria-hidden="true"><component :is="layer.layer === 'PERSONAL' ? User : FolderOpened" /></el-icon>
            <span class="memory-tree__label" :title="layer.name">{{ layer.name }}</span><small>{{ layer.count }}</small>
          </button>
          <button type="button" class="memory-tree__icon-button" :aria-label="`在${layer.name}下新建分组`" title="新建分组" @click="openGroupCreate(layer, $event)">
            <el-icon aria-hidden="true"><Plus /></el-icon>
          </button>
        </div>
        <div
          v-for="group in layer.groups"
          :key="group.id"
          class="memory-tree__group"
          :class="{ active: selection.groupId === group.id }"
        >
          <button type="button" class="memory-tree__row" :aria-current="selection.groupId === group.id ? 'true' : undefined" @click="select(layer.scope, group.id)">
            <el-icon aria-hidden="true"><component :is="group.kind === 'PREFERENCE' ? Star : Document" /></el-icon>
            <span class="memory-tree__label" :title="group.name">{{ group.name }}</span>
            <em v-if="group.kind === 'PREFERENCE'" title="每次都会带给 AI">常用</em>
            <small>{{ group.count }}</small>
          </button>
          <LiquidGlassDropdown source="backdrop" class="memory-tree__group-menu" trigger="click" placement="bottom-end" @command="command => command === 'edit' ? openGroupEdit(group) : removeGroup(group)">
            <button type="button" class="memory-tree__icon-button" :aria-label="`管理分组：${group.name}`" @focus="rememberGroupTrigger" @click="rememberGroupTrigger">
              <el-icon aria-hidden="true"><MoreFilled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="edit" :icon="EditPen">编辑分组</el-dropdown-item>
                <el-dropdown-item command="delete" :icon="Delete">删除分组</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </LiquidGlassDropdown>
        </div>
      </section>

      <div v-if="tree.addableProjects.length" class="memory-tree__project">
        <LiquidGlassSelect
          v-model="projectToAdd"
          source="backdrop"
          placeholder="+ 添加项目记忆"
          dropdown-title="选择项目"
          aria-label="添加项目记忆"
          @change="addProject"
        >
          <el-option v-for="item in tree.addableProjects" :key="item.projectCode" :label="item.name" :value="item.projectCode" />
        </LiquidGlassSelect>
      </div>

      </div>
    </aside>

    <section class="memory-main" aria-label="记忆内容">
      <header class="memory-main__head">
        <div class="memory-main__heading">
          <div class="memory-main__title"><h2>{{ selectionTitle }}</h2><span class="memory-main__count">{{ page.total }} 条</span></div>
          <p class="memory-main__hint">{{ selectionHint }}</p>
        </div>
        <LiquidGlassButton source="backdrop" type="primary" :icon="Plus" @click="openCreate">新增记忆</LiquidGlassButton>
      </header>

      <form class="memory-main__search" role="search" aria-label="搜索记忆" @submit.prevent="loadMemories">
        <LiquidGlassInput
          v-model="keyword"
          source="backdrop"
          :prefix-icon="Search"
          clearable
          placeholder="搜索记忆的标题或内容"
          aria-label="搜索记忆"
          @clear="loadMemories"
        />
        <LiquidGlassButton source="backdrop" native-type="submit">搜索</LiquidGlassButton>
      </form>

      <div v-if="error && page.items.length" class="memory-main__error" role="alert">{{ error }}</div>

      <div v-loading="loading" class="memory-list" :class="{ 'memory-list--empty': !page.items.length }" :aria-busy="loading">
        <article v-for="item in page.items" :key="item.id" class="memory-row">
          <div class="memory-row__main">
            <div class="memory-row__heading">
              <strong v-if="item.title">{{ item.title }}</strong>
              <el-tag size="small" effect="plain">{{ placeLabel(item) }}</el-tag>
            </div>
            <p>{{ item.content }}</p>
            <small>{{ sourceLabel(item) }} · {{ formatTime(item.updatedAt) }}</small>
          </div>
          <div class="memory-row__actions">
            <el-button link type="primary" @click="openEdit(item)">编辑</el-button>
            <el-button link type="danger" @click="forget(item)">删除</el-button>
          </div>
        </article>
        <LiquidGlassEmptyState
          v-if="!loading && !page.items.length && loadError"
          compact
          :icon="Warning"
          title="记忆暂时无法加载"
          :description="loadError"
        >
          <LiquidGlassButton source="backdrop" :icon="RefreshRight" @click="reload">重新加载</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState
          v-else-if="!loading && !page.items.length && searchedKeyword"
          compact
          :icon="Search"
          title="没有找到相关记忆"
          :description="`没有与「${searchedKeyword}」匹配的内容，试试其他关键词。`"
        >
          <LiquidGlassButton source="backdrop" @click="clearSearch">清除搜索</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <div v-else-if="!loading && !page.items.length" class="memory-start">
          <LiquidGlassEmptyState
            compact
            :icon="Memo"
            :title="selectedGroup ? `在「${selectedGroup.name}」记下第一条` : '从一条记忆开始'"
            description="把偏好和常用资料留在这里，也可以在对话里对 AI 说“记住……”。"
          >
            <LiquidGlassButton source="backdrop" :icon="Plus" @click="openCreate">新增第一条记忆</LiquidGlassButton>
          </LiquidGlassEmptyState>
        </div>
      </div>

      <footer class="memory-main__foot">
        <span class="memory-main__privacy"><el-icon aria-hidden="true"><Lock /></el-icon><span>仅自己可见，不会授予 AI 任何权限。请勿存入密码或密钥。</span></span>
        <span class="memory-main__foot-actions">
          <LiquidGlassButton v-if="api.exportJson" source="backdrop" link :icon="Download" @click="downloadExport">导出</LiquidGlassButton>
          <LiquidGlassButton v-if="tree.total" source="backdrop" link type="danger" :loading="erasing" @click="eraseAll">清空全部</LiquidGlassButton>
        </span>
      </footer>
    </section>

    <AppDialog v-model="editorVisible" :title="editingId ? '编辑记忆' : '新增记忆'" width="560px">
      <el-form label-position="top">
        <el-form-item label="放在哪里" required>
          <LiquidGlassSelect v-model="form.groupId" source="backdrop" style="width: 100%" placeholder="选择分组" aria-label="记忆分组">
            <el-option-group v-for="layer in tree.layers" :key="layer.scope" :label="layer.name">
              <el-option v-for="group in layer.groups" :key="group.id" :label="`${layer.name} · ${group.name}`" :value="group.id" />
            </el-option-group>
          </LiquidGlassSelect>
        </el-form-item>
        <el-form-item label="标题（可选）"><el-input v-model="form.title" maxlength="256" /></el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="5" maxlength="16000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton source="backdrop" @click="editorVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton source="backdrop" type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDialog v-model="groupVisible" :title="groupForm.id ? '编辑分组' : `在「${groupForm.layerName}」下新建分组`" width="460px" @closed="restoreGroupFocus">
      <el-form label-position="top">
        <el-form-item label="分组名" required><el-input v-model="groupForm.name" maxlength="64" placeholder="例如：常用仓库、报表习惯" /></el-form-item>
        <el-form-item label="AI 什么时候用">
          <el-radio-group v-model="groupForm.kind">
            <el-radio value="PREFERENCE">每次都带上（适合偏好、习惯）</el-radio>
            <el-radio value="NOTE">相关时才带上（适合备注、资料）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton source="backdrop" @click="groupVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton source="backdrop" type="primary" :loading="saving" @click="saveGroup">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </LiquidGlassCard>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, useId } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Collection, Delete, Document, Download, EditPen, FolderOpened, Lock, Memo, MoreFilled, Plus, RefreshRight, Search, Star, User, Warning } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassDropdown,
  LiquidGlassEmptyState,
  LiquidGlassInput,
  LiquidGlassSelect,
  type LiquidGlassSource,
} from '@/components/common/liquid-glass'
import type {
  PersonalMemory,
  PersonalMemoryApi,
  PersonalMemoryGroup,
  PersonalMemoryGroupKind,
  PersonalMemoryLayerView,
  PersonalMemoryPage,
  PersonalMemoryTree,
} from '@/types/memory'

const props = defineProps<{ api: PersonalMemoryApi; source?: LiquidGlassSource }>()
const emit = defineEmits<{ (event: 'changed', total: number): void }>()
const api = props.api
const navigationId = useId()
const groupNavigationOpen = ref(false)
const groupNavigationToggle = shallowRef<HTMLButtonElement | null>(null)
const groupReturnFocus = shallowRef<HTMLButtonElement | null>(null)

const tree = reactive<PersonalMemoryTree>({ layers: [], addableProjects: [], total: 0 })
const page = reactive<PersonalMemoryPage>({ items: [], total: 0, limit: 100, offset: 0 })
const selection = reactive<{ scope: string | null; groupId: number | null }>({ scope: null, groupId: null })
const keyword = ref('')
const searchedKeyword = ref('')
const loading = ref(true)
const error = ref('')
const loadError = ref('')
const saving = ref(false)
const erasing = ref(false)
const projectToAdd = ref('')
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<{ groupId: number | null; title: string; content: string }>({ groupId: null, title: '', content: '' })
const groupVisible = ref(false)
const groupForm = reactive<{ id: number | null; projectCode: string | null; layerName: string; name: string; kind: PersonalMemoryGroupKind }>({
  id: null, projectCode: null, layerName: '', name: '', kind: 'NOTE',
})

const selectedLayer = computed(() => tree.layers.find(layer => layer.scope === selection.scope) || null)
const selectedGroup = computed(() => selectedLayer.value?.groups.find(group => group.id === selection.groupId) || null)
const selectionTitle = computed(() => selectedGroup.value?.name || selectedLayer.value?.name || '全部记忆')
const selectionHint = computed(() => {
  if (selectedGroup.value) {
    return selectedGroup.value.kind === 'PREFERENCE'
      ? `「${selectedGroup.value.name}」里的记忆每次都会带给 AI。`
      : `「${selectedGroup.value.name}」里的记忆在相关时才会带给 AI。`
  }
  if (selectedLayer.value) {
    return selectedLayer.value.layer === 'PERSONAL'
      ? '个人记忆跟着你走，在所有项目里都会用到。'
      : `只有 AI 在操作「${selectedLayer.value.name}」时才会用到这里的记忆。`
  }
  return '个人记忆在所有地方都会用到；项目记忆只在 AI 操作对应项目时才用到。'
})

async function guarded<T>(action: () => Promise<T>): Promise<T | undefined> {
  error.value = ''
  try {
    return await action()
  } catch (failure) {
    error.value = messageOf(failure)
    return undefined
  }
}

function messageOf(failure: unknown) {
  const response = (failure as { response?: { data?: { message?: string; reason?: string } } })?.response?.data
  if (response?.message) return response.message
  return failure instanceof Error && failure.message ? failure.message : '操作失败，请稍后重试'
}

async function loadTree() {
  const data = await guarded(() => api.tree())
  if (!data) {
    loadError.value = error.value
    return false
  }
  loadError.value = ''
  Object.assign(tree, data)
  emit('changed', data.total)
  if (selection.scope && !selectedLayer.value) select(null, null, false)
  else if (selection.groupId && !selectedGroup.value) select(selection.scope, null, false)
  return true
}

async function loadMemories() {
  loading.value = true
  const query = keyword.value.trim()
  const data = await guarded(() => api.list({
    scope: selection.scope || undefined,
    groupId: selection.groupId ?? undefined,
    keyword: query || undefined,
    limit: 100,
  }))
  if (data) {
    Object.assign(page, data)
    searchedKeyword.value = query
    loadError.value = ''
  } else {
    loadError.value = error.value
  }
  loading.value = false
}

async function reload() {
  loading.value = true
  if (!await loadTree()) {
    loading.value = false
    return
  }
  await loadMemories()
}

function clearSearch() {
  keyword.value = ''
  loadMemories()
}

function select(scope: string | null, groupId: number | null, load = true) {
  groupNavigationOpen.value = false
  selection.scope = scope
  selection.groupId = groupId
  if (load) loadMemories()
}

function defaultGroupId() {
  if (selectedGroup.value) return selectedGroup.value.id
  const layer = selectedLayer.value || tree.layers[0]
  return layer?.groups.find(group => group.kind === 'NOTE')?.id ?? layer?.groups[0]?.id ?? null
}

function openCreate() {
  editingId.value = null
  form.groupId = defaultGroupId()
  form.title = ''
  form.content = ''
  editorVisible.value = true
}

function openEdit(item: PersonalMemory) {
  editingId.value = item.id
  form.groupId = item.groupId ?? defaultGroupId()
  form.title = item.title || ''
  form.content = item.content
  editorVisible.value = true
}

async function save() {
  if (!form.content.trim()) return ElMessage.warning('请输入记忆内容')
  if (!form.groupId) return ElMessage.warning('请选择分组')
  saving.value = true
  const done = await guarded(async () => {
    if (editingId.value) {
      await api.update(editingId.value, { title: form.title, content: form.content, groupId: form.groupId! })
    } else {
      await api.remember({
        title: form.title || undefined,
        content: form.content,
        groupId: form.groupId!,
        clientRequestId: crypto.randomUUID(),
      })
    }
    return true
  })
  saving.value = false
  if (!done) return ElMessage.error(error.value)
  ElMessage.success('已保存')
  editorVisible.value = false
  await reload()
}

async function forget(item: PersonalMemory) {
  try {
    await ElMessageBox.confirm('删除后 AI 立即不再使用这条记忆，且无法恢复。是否继续？', '删除记忆', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  const done = await guarded(async () => { await api.forget(item.id); return true })
  if (!done) return ElMessage.error(error.value)
  ElMessage.success('已删除')
  await reload()
}

function rememberGroupTrigger(event: Event) {
  groupReturnFocus.value = event.currentTarget as HTMLButtonElement
}

function restoreGroupFocus() {
  const target = groupReturnFocus.value?.getClientRects().length ? groupReturnFocus.value : groupNavigationToggle.value
  if (target?.getClientRects().length) {
    target.focus()
  }
  groupReturnFocus.value = null
}

function openGroupCreate(layer: PersonalMemoryLayerView, event: Event) {
  rememberGroupTrigger(event)
  Object.assign(groupForm, { id: null, projectCode: layer.projectCode || null, layerName: layer.name, name: '', kind: 'NOTE' })
  groupVisible.value = true
}

function openGroupEdit(group: PersonalMemoryGroup) {
  Object.assign(groupForm, { id: group.id, projectCode: null, layerName: '', name: group.name, kind: group.kind })
  groupVisible.value = true
}

async function saveGroup() {
  if (!groupForm.name.trim()) return ElMessage.warning('请填写分组名')
  saving.value = true
  const saved = await guarded(() => groupForm.id
    ? api.updateGroup(groupForm.id, { name: groupForm.name, kind: groupForm.kind })
    : api.createGroup({ projectCode: groupForm.projectCode, name: groupForm.name, kind: groupForm.kind }))
  saving.value = false
  if (!saved) return ElMessage.error(error.value)
  groupVisible.value = false
  await loadTree()
  if (!groupForm.id) select(groupForm.projectCode ? `project:${groupForm.projectCode}` : 'personal', saved.id)
  else await loadMemories()
}

async function removeGroup(group: PersonalMemoryGroup) {
  if (group.count > 0) {
    return ElMessage.warning(`分组里还有 ${group.count} 条记忆，请先删除或移到其他分组`)
  }
  try {
    await ElMessageBox.confirm(`删除分组「${group.name}」？`, '删除分组', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  const done = await guarded(async () => { await api.deleteGroup(group.id); return true })
  if (!done) return ElMessage.error(error.value)
  if (selection.groupId === group.id) select(selection.scope, null, false)
  await reload()
}

async function addProject(projectCode: string) {
  if (!projectCode) return
  const layer = await guarded(() => api.addProject(projectCode))
  projectToAdd.value = ''
  if (!layer) return
  await loadTree()
  select(layer.scope, null)
}

async function eraseAll() {
  try {
    await ElMessageBox.prompt('这会永久删除你的全部记忆和分组。请输入“全部删除”继续。', '清空全部记忆', {
      confirmButtonText: '永久清空',
      cancelButtonText: '取消',
      type: 'warning',
      inputPlaceholder: '全部删除',
      inputValidator: value => value === '全部删除' || '请输入“全部删除”',
    })
  } catch { return }
  erasing.value = true
  const result = await guarded(() => api.eraseAll({ confirmation: 'ERASE_ALL_PERSONAL_MEMORIES' }))
  erasing.value = false
  if (!result) return
  ElMessage.success(`已清空 ${result.memoriesErased} 条记忆`)
  select(null, null, false)
  await reload()
}

async function downloadExport() {
  if (!api.exportJson) return
  const blob = await guarded(() => api.exportJson!())
  if (!blob) return
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = 'reachai-personal-memory.json'; anchor.click()
  URL.revokeObjectURL(url)
}

function placeLabel(item: PersonalMemory) {
  const layer = item.layer === 'PROJECT' ? (item.layerName || item.projectCode || '项目') : '个人记忆'
  return item.groupName ? `${layer} · ${item.groupName}` : layer
}

const sourceLabels: Record<string, string> = {
  MANUAL: '手动添加',
  CONVERSATION: '对话中记住',
  CANDIDATE: '对话中记住',
  AI_TOOL: 'AI 工具记录',
}

function sourceLabel(item: PersonalMemory) {
  const label = sourceLabels[item.sourceType] || item.sourceType
  return item.sourceAgentId ? `${label}（${item.sourceAgentId}）` : label
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

onMounted(reload)

defineExpose({ reload })
</script>

<style scoped lang="scss">
.memory-workspace.liquid-glass-card {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  min-height: clamp(500px, calc(100dvh - 220px), 760px);
  padding: 0;
}

.memory-tree {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 20px 12px;
  border-right: 1px solid var(--border-divider);
  border-radius: var(--radius-xl) 0 0 var(--radius-xl);
  background: var(--surface-reading-header);
}
.memory-tree__heading {
  padding: 0 10px 10px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
}
.memory-tree__mobile-toggle { display: none; }
.memory-tree__navigation { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 6px; }
.memory-tree__row {
  display: flex;
  position: relative;
  min-width: 0;
  min-height: 40px;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.memory-tree__row > .el-icon { flex-shrink: 0; font-size: 17px; }
.memory-tree__label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.memory-tree__row:hover, .memory-tree__icon-button:hover { background: var(--surface-reading-hover); }
.memory-tree__row small {
  flex-shrink: 0;
  color: var(--text-muted);
  font-family: var(--font-family-mono);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.memory-tree__row em {
  flex-shrink: 0;
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--status-neutral-soft);
  color: var(--text-muted);
  font-size: 10px;
  font-style: normal;
  font-weight: 400;
}
.memory-tree__all.active, .memory-tree__layer-name.active, .memory-tree__group.active {
  background: var(--surface-reading-current);
  color: var(--brand-on-surface);
  font-weight: 600;
}
.memory-tree__group.active > .memory-tree__row { color: inherit; font-weight: inherit; }
.memory-tree__all.active::before, .memory-tree__layer-name.active::before, .memory-tree__group.active::before {
  position: absolute;
  inset: 12px auto 12px 0;
  width: 3px;
  border-radius: var(--radius-pill);
  background: var(--brand-on-surface);
  content: '';
}
.memory-tree__layer {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--border-divider);
}
.memory-tree__layer-head { display: flex; min-width: 0; align-items: center; gap: 2px; }
.memory-tree__layer-name { color: var(--text-primary); font-weight: 600; }
.memory-tree__icon-button {
  display: inline-flex;
  flex-shrink: 0;
  width: 28px;
  height: 32px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-size: 15px;
  cursor: pointer;
}
.memory-tree__icon-button:hover { color: var(--brand-on-surface); }
.memory-tree__row:focus-visible, .memory-tree__icon-button:focus-visible, .memory-tree__mobile-toggle:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}
.memory-tree__group {
  display: flex;
  position: relative;
  min-width: 0;
  align-items: center;
  margin-left: 10px;
  border-radius: var(--radius-md);
}
.memory-tree__group-menu { flex-shrink: 0; margin-right: 2px; }
.memory-tree__group-menu .memory-tree__icon-button { color: var(--text-muted); }
.memory-tree__project { padding-top: 12px; }
.memory-tree__project > * { width: 100%; }

.memory-main { display: flex; min-width: 0; flex-direction: column; gap: 20px; padding: 24px; border-radius: 0 var(--radius-xl) var(--radius-xl) 0; background: var(--surface-glass-reading); }
.memory-main__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.memory-main__heading { min-width: 0; }
.memory-main__title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.memory-main__title h2 { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; line-height: 28px; overflow-wrap: anywhere; }
.memory-main__count {
  padding: 2px 8px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-pill);
  color: var(--text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.memory-main__hint { max-width: 68ch; margin: 6px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.memory-main__search { display: flex; align-items: center; gap: 8px; width: min(100%, 520px); }
.memory-main__search > :first-child { flex: 1; min-width: 0; }
.memory-main__error { color: var(--status-danger); font-size: 13px; overflow-wrap: anywhere; }
.memory-list { display: flex; flex: 1; min-width: 0; min-height: 160px; flex-direction: column; }
.memory-list--empty { display: grid; align-items: center; }
.memory-list :deep(.liquid-empty-state) { box-sizing: border-box; }
.memory-row { display: flex; justify-content: space-between; gap: 20px; padding: 20px 4px; border-top: 1px solid var(--border-divider); }
.memory-row:first-child { border-top: 0; padding-top: 4px; }
.memory-row__main { min-width: 0; }
.memory-row__heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.memory-row__heading strong { color: var(--text-primary); font-size: 14px; overflow-wrap: anywhere; }
.memory-row__heading :deep(.el-tag) { font-size: 10px; }
.memory-row__main p { margin: 10px 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.memory-row__main small { color: var(--text-muted); font-size: 11px; }
.memory-row__actions { display: flex; flex-shrink: 0; align-items: flex-start; gap: 8px; }
.memory-main__foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 16px; border-top: 1px solid var(--border-divider); color: var(--text-muted); font-size: 11px; }
.memory-main__privacy { display: flex; align-items: flex-start; gap: 6px; line-height: 1.8; }
.memory-main__privacy > .el-icon { flex-shrink: 0; margin-top: 3px; font-size: 14px; }
.memory-main__foot-actions { display: flex; flex-shrink: 0; align-items: center; gap: 12px; }
.memory-main__foot-actions :deep(.el-button) { font-size: 12px; }

.memory-start { width: 100%; padding-block: 24px; }
.memory-start :deep(.liquid-empty-state__icon) { width: 56px; height: 56px; margin-bottom: 8px; border-radius: var(--radius-lg); font-size: 28px; }
.memory-start :deep(.liquid-empty-state h3) { font-size: 18px; font-weight: 600; }
.memory-start :deep(.liquid-empty-state p) { max-width: 38ch; color: var(--text-muted); font-size: 12px; }

@media (max-width: 1100px) {
  .memory-workspace.liquid-glass-card { grid-template-columns: 208px minmax(0, 1fr); }
  .memory-main { gap: 16px; padding: 20px; }
  .memory-main__head, .memory-main__foot { flex-wrap: wrap; }
}
@media (max-width: 760px) {
  .memory-workspace.liquid-glass-card { grid-template-columns: 1fr; min-height: auto; }
  .memory-tree { padding: 12px; border-right: 0; border-bottom: 1px solid var(--border-divider); border-radius: var(--radius-xl) var(--radius-xl) 0 0; }
  .memory-tree__heading { display: none; }
  .memory-tree__mobile-toggle { display: flex; min-width: 0; align-items: center; gap: 8px; width: 100%; min-height: 32px; padding: 4px 2px; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--text-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
  .memory-tree__mobile-toggle > span { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
  .memory-tree__mobile-toggle > small { color: var(--text-muted); font-size: 10px; white-space: nowrap; }
  .memory-tree__mobile-toggle > .el-icon { flex-shrink: 0; }
  .memory-tree__mobile-toggle > .el-icon.is-open { transform: rotate(180deg); }
  .memory-tree__navigation { display: none; }
  .memory-tree__navigation.is-open { display: flex; max-height: 320px; overflow-y: auto; }
  .memory-tree__layer { margin-top: 4px; padding-top: 6px; }
  .memory-main { padding: var(--layout-panel-padding); border-radius: 0 0 var(--radius-xl) var(--radius-xl); }
  .memory-main__head { gap: 12px; }
  .memory-main__hint { font-size: 11px; }
  .memory-start { padding-block: 12px; }
  .memory-start :deep(.liquid-empty-state) { width: 100%; padding-inline: 0; }
  .memory-row { flex-direction: column; gap: 8px; }
  .memory-main__foot-actions { margin-left: auto; }
}
@media (forced-colors: active) {
  .memory-tree__row[aria-current='true'] { outline: 2px solid Highlight; outline-offset: -2px; color: Highlight; }
  .memory-tree__icon-button:focus-visible, .memory-tree__row:focus-visible, .memory-tree__mobile-toggle:focus-visible { outline-color: Highlight; }
}
</style>
