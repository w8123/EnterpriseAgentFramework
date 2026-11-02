<template>
  <section class="shared-memory" aria-labelledby="shared-memory-title">
    <header class="shared-memory__header">
      <div>
        <h2 id="shared-memory-title">关联账号的个人记忆</h2>
        <p>可信绑定后，可在这里查看和管理关联账号的个人记忆。项目记忆仍留在原系统。</p>
      </div>
      <LiquidGlassButton source="backdrop" :loading="loadingSystems" :disabled="saving" @click="loadSystems">刷新</LiquidGlassButton>
    </header>

    <p v-if="error && !editing" class="shared-memory__error" role="alert">{{ error }}</p>
    <p v-if="loadingSystems" class="shared-memory__muted" role="status">正在加载关联账号…</p>
    <p v-else-if="!systems.length && !error" class="shared-memory__muted">当前没有可查看的关联账号。</p>
    <div v-if="systems.length" class="shared-memory__content">
      <nav class="shared-memory__systems" aria-label="选择关联账号">
        <button v-for="system in systems" :key="system.linkId" type="button"
          :aria-pressed="selectedId === system.linkId" :disabled="saving"
          @click="selectSystem(system.linkId)">{{ system.projectCode }} · {{ system.accountHint }}</button>
      </nav>
      <LiquidGlassButton v-if="identityApi && selectedId !== null" source="backdrop" :disabled="saving" @click="unlink">解除当前账号关联</LiquidGlassButton>
      <form class="shared-memory__search" role="search" @submit.prevent="search">
        <LiquidGlassInput v-model="keyword" source="backdrop" aria-label="搜索关联账号的记忆" placeholder="搜索标题或内容" />
        <LiquidGlassButton source="backdrop" native-type="submit" :disabled="loading">搜索</LiquidGlassButton>
      </form>
      <p v-if="loading" class="shared-memory__muted" role="status">正在加载记忆…</p>
      <p v-else-if="!items.length && !error" class="shared-memory__muted">{{ searched ? '没有找到相关记忆。' : '这个账号还没有个人记忆。' }}</p>
      <article v-for="item in items" :key="item.id" class="shared-memory__item">
        <div><strong v-if="item.title">{{ item.title }}</strong><p>{{ item.content }}</p></div>
        <div class="shared-memory__actions">
          <LiquidGlassButton source="backdrop" link :disabled="saving" @click="openEdit(item)">编辑</LiquidGlassButton>
          <LiquidGlassButton source="backdrop" link type="danger" :disabled="saving" @click="forget(item)">删除</LiquidGlassButton>
        </div>
      </article>
      <footer class="shared-memory__footer">
        <span>共 {{ total }} 条</span>
        <LiquidGlassButton source="backdrop" :disabled="loading || offset === 0" @click="changePage(-1)">上一页</LiquidGlassButton>
        <LiquidGlassButton source="backdrop" :disabled="loading || offset + pageSize >= total" @click="changePage(1)">下一页</LiquidGlassButton>
      </footer>
    </div>

    <AppDialog v-model="editing" title="编辑关联账号的个人记忆" width="560px">
      <p v-if="error" class="shared-memory__error" role="alert">{{ error }}</p>
      <el-form label-position="top">
        <el-form-item label="标题（可选）"><el-input v-model="form.title" maxlength="256" /></el-form-item>
        <el-form-item label="内容" required><el-input v-model="form.content" type="textarea" :rows="5" maxlength="16000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton source="backdrop" :disabled="saving" @click="editing = false">取消</LiquidGlassButton>
        <LiquidGlassButton source="backdrop" type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      </template>
    </AppDialog>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassInput } from '@/components/common/liquid-glass'
import type { PersonalMemory, SharedMemorySystem, SharedPersonalMemoryApi } from '@/types/memory'

const props = defineProps<{ api: SharedPersonalMemoryApi; identityApi?: { revoke: (linkId: number) => Promise<boolean> } }>()
const systems = ref<SharedMemorySystem[]>([])
const selectedId = ref<number | null>(null)
const items = ref<PersonalMemory[]>([])
const total = ref(0)
const offset = ref(0)
const pageSize = 20
const keyword = ref('')
const searched = ref('')
const error = ref('')
const loadingSystems = ref(false)
const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ title: '', content: '' })
let generation = 0
let systemsGeneration = 0
let disposed = false

function messageOf(failure: unknown) {
  return failure instanceof Error && failure.message ? failure.message : '操作失败，请稍后重试'
}

async function loadSystems() {
  const systemsRequest = ++systemsGeneration
  const request = ++generation
  loadingSystems.value = true
  loading.value = false
  error.value = ''
  try {
    const result = await props.api.systems()
    if (disposed || request !== generation) return
    systems.value = result
    const next = result.find(system => system.linkId === selectedId.value)?.linkId ?? result[0]?.linkId ?? null
    if (next !== selectedId.value) { editing.value = false; editingId.value = null }
    selectedId.value = next
    items.value = []
    total.value = 0
    offset.value = 0
    if (next !== null) void loadPage()
  } catch (failure) {
    if (disposed || request !== generation) return
    systems.value = []; selectedId.value = null; items.value = []; total.value = 0
    error.value = messageOf(failure)
  } finally {
    if (!disposed && systemsRequest === systemsGeneration) loadingSystems.value = false
  }
}

async function loadPage() {
  const linkId = selectedId.value
  if (linkId === null) return
  const request = ++generation
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const result = await props.api.list(linkId, { keyword: searched.value || undefined, limit: pageSize, offset: offset.value })
    if (disposed || request !== generation || selectedId.value !== linkId) return
    items.value = result.items; total.value = result.total
  } catch (failure) {
    if (disposed || request !== generation) return
    items.value = []; total.value = 0; error.value = messageOf(failure)
  } finally {
    if (!disposed && request === generation) loading.value = false
  }
}

function selectSystem(id: number) {
  if (saving.value || id === selectedId.value) return
  selectedId.value = id; offset.value = 0; total.value = 0; keyword.value = ''; searched.value = ''; editing.value = false
  void loadPage()
}
function search() { searched.value = keyword.value.trim(); offset.value = 0; void loadPage() }
function changePage(delta: number) { offset.value = Math.max(0, offset.value + delta * pageSize); void loadPage() }
function openEdit(item: PersonalMemory) {
  editingId.value = item.id; form.title = item.title || ''; form.content = item.content; error.value = ''; editing.value = true
}

async function save() {
  const linkId = selectedId.value; const id = editingId.value
  if (linkId === null || id === null || saving.value) return
  if (!form.content.trim()) { ElMessage.warning('请输入记忆内容'); return }
  saving.value = true; error.value = ''
  try {
    await props.api.update(linkId, id, { title: form.title, content: form.content })
    if (disposed || selectedId.value !== linkId) return
    editing.value = false; ElMessage.success('已保存'); await loadPage()
  } catch (failure) { if (!disposed) error.value = messageOf(failure) }
  finally { if (!disposed) saving.value = false }
}

async function forget(item: PersonalMemory) {
  const linkId = selectedId.value
  if (linkId === null || saving.value) return
  try { await ElMessageBox.confirm('删除后关联账号中的这条记忆也会被永久删除。是否继续？', '删除共享记忆', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  if (disposed || selectedId.value !== linkId || saving.value) return
  saving.value = true; error.value = ''
  try {
    await props.api.forget(linkId, item.id)
    if (disposed || selectedId.value !== linkId) return
    if (offset.value > 0 && items.value.length === 1) offset.value = Math.max(0, offset.value - pageSize)
    ElMessage.success('已删除'); await loadPage()
  } catch (failure) { if (!disposed) error.value = messageOf(failure) }
  finally { if (!disposed) saving.value = false }
}

async function unlink() {
  const linkId = selectedId.value
  if (linkId === null || !props.identityApi || saving.value) return
  try { await ElMessageBox.confirm('解除后双方不再共享个人记忆，已有记忆仍保留在各自系统。是否继续？', '解除账号关联', { confirmButtonText: '解除关联', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  if (disposed || selectedId.value !== linkId || saving.value) return
  saving.value = true; error.value = ''
  try {
    await props.identityApi.revoke(linkId)
    if (disposed) return
    editing.value = false; editingId.value = null; ElMessage.success('已解除关联'); await loadSystems()
  } catch (failure) { if (!disposed) error.value = messageOf(failure) }
  finally { if (!disposed) saving.value = false }
}

onMounted(loadSystems)
onBeforeUnmount(() => { disposed = true; generation++ })
defineExpose({ loadSystems })
</script>

<style scoped lang="scss">
.shared-memory { margin-top: 20px; padding: 20px; border-top: 1px solid var(--border-glass-edge); }
.shared-memory__header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.shared-memory__header h2 { margin: 0; color: var(--text-primary); font-size: 16px; }
.shared-memory__header p, .shared-memory__muted { color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.shared-memory__systems, .shared-memory__search, .shared-memory__footer, .shared-memory__actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.shared-memory__systems { margin: 12px 0; }
.shared-memory__systems button { border: 1px solid var(--border-glass-edge); border-radius: 8px; padding: 7px 12px; background: transparent; color: var(--text-primary); cursor: pointer; }
.shared-memory__systems button[aria-pressed="true"] { color: var(--brand-on-surface, var(--brand-primary)); border-color: var(--brand-primary); }
.shared-memory__systems button:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 2px; }
.shared-memory__search { margin: 12px 0; }
.shared-memory__search :deep(.el-input) { min-width: 180px; flex: 1; }
.shared-memory__item { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border-glass-edge); overflow-wrap: anywhere; }
.shared-memory__item p { margin: 6px 0 0; white-space: pre-wrap; color: var(--text-primary); }
.shared-memory__actions { flex-shrink: 0; align-self: flex-start; }
.shared-memory__footer { justify-content: flex-end; margin-top: 12px; color: var(--text-secondary); font-size: 13px; }
.shared-memory__error { color: var(--el-color-danger); }
@media (max-width: 600px) { .shared-memory { padding: 16px 0; } .shared-memory__item { flex-wrap: wrap; } }
</style>
