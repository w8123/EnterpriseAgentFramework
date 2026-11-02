<template>
  <section class="memory-identity-admin" aria-labelledby="memory-link-title">
    <h3 id="memory-link-title">个人记忆跨系统关联</h3>
    <p>管理员核实两个账号属于同一员工后，可代为关联。关联后双方可查看、编辑和删除彼此的个人记忆；项目记忆不共享。</p>
    <label for="memory-link-source">当前用户的业务账号</label>
    <select id="memory-link-source" v-model.number="sourceId" :disabled="busy">
      <option :value="0">选择业务账号</option>
      <option v-for="identity in activeIdentities" :key="identity.id" :value="identity.id">{{ identity.appId }} · {{ identity.externalUserId }}</option>
    </select>
    <p v-if="!activeIdentities.length">当前用户没有可关联的有效业务账号。</p>
    <div v-if="sourceId" class="memory-identity-admin__links">
      <h4>当前账号的关联</h4>
      <p v-if="loadingLinks" role="status">正在加载关联…</p>
      <p v-else-if="!links.length && !linkError">暂无关联。</p>
      <p v-if="linkError" role="alert">{{ linkError }}</p>
      <ul v-if="links.length">
        <li v-for="link in links" :key="link.id">
          <span>{{ link.peerAppId }} · {{ link.peerExternalUserId }}（{{ link.verificationMethod === 'SELF' ? '员工确认' : '管理员核验' }}）</span>
          <LiquidGlassButton source="backdrop" link type="danger" :disabled="busy" @click="revoke(link)">解除</LiquidGlassButton>
        </li>
      </ul>
      <div class="memory-identity-admin__paging">
        <LiquidGlassButton source="backdrop" :disabled="busy || loadingLinks || offset === 0" @click="page(-1)">上一页</LiquidGlassButton>
        <LiquidGlassButton source="backdrop" :disabled="busy || loadingLinks || !hasMore" @click="page(1)">下一页</LiquidGlassButton>
      </div>
    </div>
    <form class="memory-identity-admin__form" @submit.prevent="searchUsers">
      <label for="memory-link-search">查找另一个业务用户</label>
      <div class="memory-identity-admin__row">
        <LiquidGlassInput id="memory-link-search" v-model="keyword" source="backdrop" placeholder="姓名、统一用户 ID、邮箱或手机号" :disabled="busy" />
        <LiquidGlassButton source="backdrop" native-type="submit" :loading="searching" :disabled="busy || !keyword.trim()">查找</LiquidGlassButton>
      </div>
      <p v-if="searched && !users.length && !searchError">没有找到用户，请调整搜索条件。</p>
      <p v-if="moreUsers">仅显示前 20 位，请细化搜索条件。</p>
      <p v-if="searchError" role="alert">{{ searchError }}</p>
      <label v-if="users.length" for="memory-link-user">目标业务用户</label>
      <select v-if="users.length" id="memory-link-user" v-model.number="targetUserId" :disabled="busy">
        <option :value="0">选择用户</option>
        <option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName || user.globalUserId }} · {{ user.globalUserId }}</option>
      </select>
      <label v-if="targetUserId" for="memory-link-target">目标业务账号</label>
      <select v-if="targetUserId" id="memory-link-target" v-model.number="targetId" :disabled="busy || loadingTargets">
        <option :value="0">{{ loadingTargets ? '正在加载…' : '选择业务账号' }}</option>
        <option v-for="identity in targetIdentities" :key="identity.id" :value="identity.id">{{ identity.appId }} · {{ identity.externalUserId }}</option>
      </select>
      <p v-if="targetError" role="alert">{{ targetError }}</p>
    </form>
    <div class="memory-identity-admin__evidence">
      <label for="memory-link-evidence">核验或解除依据（4–512 字）</label>
      <textarea id="memory-link-evidence" v-model="evidence" maxlength="512" :disabled="busy" placeholder="记录已核实的依据，请勿填写密码或其他密钥" />
      <LiquidGlassButton source="backdrop" type="primary" :loading="busy" :disabled="!canCreate" @click="create">关联账号</LiquidGlassButton>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { LiquidGlassButton, LiquidGlassInput } from '@/components/common/liquid-glass'
import { createPersonalMemoryIdentityLink, listBusinessUserIdentities, listBusinessUsers, listPersonalMemoryIdentityLinks, revokePersonalMemoryIdentityLink } from '@/api/platformIdentity'
import type { BusinessUserView, ExternalIdentityView, PersonalMemoryAdminLink } from '@/api/platformIdentity'

const props = defineProps<{ user: BusinessUserView; identities: ExternalIdentityView[] }>()
const activeIdentities = computed(() => props.identities.filter(item => item.status === 'ACTIVE'))
const sourceId = ref(0); const keyword = ref(''); const targetUserId = ref(0); const targetId = ref(0)
const users = ref<BusinessUserView[]>([]); const targetIdentities = ref<ExternalIdentityView[]>([])
const links = ref<PersonalMemoryAdminLink[]>([]); const evidence = ref(''); const offset = ref(0)
const hasMore = ref(false); const moreUsers = ref(false); const searched = ref(false)
const loadingLinks = ref(false); const loadingTargets = ref(false); const searching = ref(false); const busy = ref(false)
const error = ref(''); const notice = ref(''); const linkError = ref(''); const searchError = ref(''); const targetError = ref('')
let linkGeneration = 0; let searchGeneration = 0; let targetGeneration = 0; let targetSelectionGeneration = 0; let evidenceGeneration = 0; let disposed = false
const canCreate = computed(() => !!sourceId.value && !!targetId.value && sourceId.value !== targetId.value && evidence.value.trim().length >= 4 && !busy.value)
function failure(value: unknown) { return value instanceof Error && value.message ? value.message : '操作失败，请稍后重试' }
watch(sourceId, () => { offset.value = 0; void loadLinks() }, { flush: 'sync' })
watch(targetId, () => { targetSelectionGeneration++ }, { flush: 'sync' })
watch(evidence, () => { evidenceGeneration++ }, { flush: 'sync' })
watch(targetUserId, async id => {
  const seq = ++targetGeneration; targetId.value = 0; targetIdentities.value = []; targetError.value = ''; loadingTargets.value = !!id
  if (!id) return
  try {
    const { data } = await listBusinessUserIdentities(id)
    if (!disposed && seq === targetGeneration) targetIdentities.value = (data ?? []).filter(item => item.status === 'ACTIVE')
  } catch (value) { if (!disposed && seq === targetGeneration) targetError.value = failure(value) }
  finally { if (!disposed && seq === targetGeneration) loadingTargets.value = false }
})
async function loadLinks() {
  const seq = ++linkGeneration; const binding = sourceId.value
  links.value = []; hasMore.value = false; linkError.value = ''; loadingLinks.value = !!binding
  if (!binding) return
  try {
    const { data } = await listPersonalMemoryIdentityLinks(binding, offset.value)
    if (!disposed && seq === linkGeneration) { links.value = data.items; hasMore.value = data.hasMore }
  } catch (value) { if (!disposed && seq === linkGeneration) linkError.value = failure(value) }
  finally { if (!disposed && seq === linkGeneration) loadingLinks.value = false }
}
function page(delta: number) { offset.value = Math.max(0, offset.value + delta * 50); void loadLinks() }
async function searchUsers() {
  const query = keyword.value.trim(); if (!query || busy.value || searching.value) return
  const seq = ++searchGeneration; searching.value = true; searched.value = false; users.value = []; targetUserId.value = 0; searchError.value = ''; moreUsers.value = false
  try {
    const { data } = await listBusinessUsers({ current: 1, size: 20, tenantId: props.user.tenantId, keyword: query, status: 'ACTIVE' })
    if (!disposed && seq === searchGeneration) { users.value = data.records; moreUsers.value = data.total > data.records.length; searched.value = true }
  } catch (value) { if (!disposed && seq === searchGeneration) searchError.value = failure(value) }
  finally { if (!disposed && seq === searchGeneration) searching.value = false }
}
async function create() {
  if (!canCreate.value) return
  const source = activeIdentities.value.find(item => item.id === sourceId.value)
  const target = targetIdentities.value.find(item => item.id === targetId.value)
  if (!source || !target) return
  if (source.appId.toLowerCase() === target.appId.toLowerCase()) { error.value = '请选择不同业务系统的账号'; return }
  const selectedSource = sourceId.value; const selectedTarget = targetId.value; const note = evidence.value.trim()
  const sourceVersion = linkGeneration; const targetVersion = targetGeneration; const selectionVersion = targetSelectionGeneration; const noteVersion = evidenceGeneration
  try { await ElMessageBox.confirm(`确认 ${source.appId} · ${source.externalUserId} 与 ${target.appId} · ${target.externalUserId} 属于同一员工，并共享个人记忆？`, '确认关联', { type: 'warning', confirmButtonText: '确认关联', cancelButtonText: '取消' }) } catch { return }
  if (disposed || busy.value || linkGeneration !== sourceVersion || targetGeneration !== targetVersion || targetSelectionGeneration !== selectionVersion || evidenceGeneration !== noteVersion || sourceId.value !== selectedSource || targetId.value !== selectedTarget || evidence.value.trim() !== note) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await createPersonalMemoryIdentityLink(selectedSource, selectedTarget, note); if (!disposed) { evidence.value = ''; notice.value = '已关联账号'; await loadLinks() } }
  catch (value) { if (!disposed) error.value = failure(value) }
  finally { if (!disposed) busy.value = false }
}
async function revoke(link: PersonalMemoryAdminLink) {
  const selectedSource = sourceId.value; const note = evidence.value.trim(); const sourceVersion = linkGeneration; const noteVersion = evidenceGeneration
  if (note.length < 4) { error.value = '请先填写至少 4 字的解除依据'; return }
  try { await ElMessageBox.confirm(`确认解除与 ${link.peerAppId} · ${link.peerExternalUserId} 的关联？双方记忆仍保留在各自系统。`, '确认解除', { type: 'warning', confirmButtonText: '解除关联', cancelButtonText: '取消' }) } catch { return }
  if (disposed || busy.value || linkGeneration !== sourceVersion || evidenceGeneration !== noteVersion || sourceId.value !== selectedSource || evidence.value.trim() !== note) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await revokePersonalMemoryIdentityLink(link.id, note); if (!disposed) { evidence.value = ''; notice.value = '已解除关联'; await loadLinks() } }
  catch (value) { if (!disposed) error.value = failure(value) }
  finally { if (!disposed) busy.value = false }
}
onBeforeUnmount(() => { disposed = true; linkGeneration++; searchGeneration++; targetGeneration++ })
</script>

<style scoped>
.memory-identity-admin { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--border-glass-edge); }
.memory-identity-admin h3, .memory-identity-admin h4 { margin: 0 0 8px; color: var(--text-primary); }
.memory-identity-admin p, .memory-identity-admin label { color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.memory-identity-admin label { display: block; margin: 12px 0 5px; }
.memory-identity-admin select, .memory-identity-admin textarea { box-sizing: border-box; width: 100%; padding: 8px; border: 1px solid var(--border-glass-edge); border-radius: 8px; color: var(--text-primary); background: var(--surface-glass, transparent); }
.memory-identity-admin textarea { min-height: 65px; resize: vertical; }
.memory-identity-admin__row, .memory-identity-admin__paging { display: flex; gap: 8px; align-items: center; }
.memory-identity-admin__row :deep(.el-input) { flex: 1; min-width: 0; }
.memory-identity-admin__links { margin-top: 16px; }
.memory-identity-admin__links ul { list-style: none; padding: 0; }
.memory-identity-admin__links li { display: flex; justify-content: space-between; align-items: center; gap: 8px; overflow-wrap: anywhere; }
.memory-identity-admin__evidence button { margin-top: 8px; }
.memory-identity-admin [role="alert"] { color: var(--el-color-danger); }
</style>
