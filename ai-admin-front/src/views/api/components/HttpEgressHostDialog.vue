<template>
  <AppDialog :model-value="modelValue" title="内网地址白名单" width="560px" destroy-on-close
    description="业务系统在内网或和 ReachAI 装在同一台机器上时，把它的主机加到这里，ReachAI 才会去调用。加入后马上生效，不用重启。"
    @update:model-value="emit('update:modelValue', $event)">
    <div v-if="loading" class="egress-muted">正在读取…</div>
    <p v-else-if="loadError" class="egress-error" role="alert">{{ loadError }}
      <el-button link type="primary" @click="load">重新读取</el-button>
    </p>
    <template v-else>
      <p v-if="data?.allowPrivateNetworks" class="egress-muted">服务器配置已经整体放开了内网地址（192.168.x.x 这类）；本机地址（127.0.0.1、localhost）仍要在这里单独加。</p>
      <form class="egress-add" @submit.prevent="add">
        <el-input v-model="host" placeholder="主机名或 IP，例如 erp.corp.local 或 192.168.1.20" aria-label="主机名或 IP" />
        <el-input v-model="note" maxlength="200" placeholder="备注：这是哪个系统（可不填）" aria-label="备注" />
        <el-button type="primary" native-type="submit" :loading="adding" :disabled="!host.trim()">加入</el-button>
      </form>
      <p v-if="actionError" class="egress-error" role="alert">{{ actionError }}</p>
      <ul v-if="data && (data.hosts.length || data.environmentHosts.length)" class="egress-list">
        <li v-for="item in data.hosts" :key="item.id">
          <div>
            <strong>{{ item.host }}</strong>
            <small v-if="item.note">{{ item.note }}</small>
          </div>
          <el-button link type="danger" :loading="removing === item.id" @click="remove(item)">移除</el-button>
        </li>
        <li v-for="item in data.environmentHosts" :key="`env-${item}`">
          <div>
            <strong>{{ item }}</strong>
            <small>写在服务器配置里，页面上不能移除</small>
          </div>
        </li>
      </ul>
      <p v-else class="egress-muted">还没有加入任何内网地址。</p>
      <p class="egress-muted">只放行你信任的业务系统主机。云平台内部地址（169.254.x.x）任何时候都不能加。</p>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { addHttpEgressHost, listHttpEgressHosts, removeHttpEgressHost, type HttpEgressHost,
  type HttpEgressHostList } from '@/api/httpEgressHost'

const props = defineProps<{ modelValue: boolean; suggestedHost?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; changed: [] }>()

const data = ref<HttpEgressHostList | null>(null)
const loading = ref(false)
const loadError = ref('')
const actionError = ref('')
const host = ref('')
const note = ref('')
const adding = ref(false)
const removing = ref<number | null>(null)

function message(error: unknown) {
  const failure = error as { response?: { data?: { message?: string } } }
  return failure?.response?.data?.message || '请稍后再试'
}

async function load() {
  loading.value = true; loadError.value = ''
  try {
    const response = await listHttpEgressHosts()
    data.value = { hosts: response.data?.hosts || [], allowPrivateNetworks: Boolean(response.data?.allowPrivateNetworks),
      environmentHosts: response.data?.environmentHosts || [] }
  } catch (error) {
    loadError.value = `读取白名单失败：${message(error)}`
  } finally { loading.value = false }
}

async function add() {
  if (!host.value.trim() || adding.value) return
  adding.value = true; actionError.value = ''
  try {
    await addHttpEgressHost(host.value.trim(), note.value.trim())
    ElMessage.success('已加入白名单，马上生效')
    host.value = ''; note.value = ''
    emit('changed')
    await load()
  } catch (error) {
    actionError.value = `没能加入：${message(error)}`
  } finally { adding.value = false }
}

async function remove(item: HttpEgressHost) {
  removing.value = item.id; actionError.value = ''
  try {
    await removeHttpEgressHost(item.id)
    emit('changed')
    await load()
  } catch (error) {
    actionError.value = `没能移除：${message(error)}`
  } finally { removing.value = null }
}

watch(() => props.modelValue, open => {
  if (!open) return
  host.value = props.suggestedHost || ''
  note.value = ''; actionError.value = ''
  void load()
}, { immediate: true })
</script>

<style scoped>
.egress-muted { margin: 0 0 12px; font-size: 13px; line-height: 1.6; color: var(--text-secondary); }
.egress-error { margin: 0 0 12px; font-size: 13px; color: var(--el-color-danger); }
.egress-add { display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; margin-bottom: 12px; }
.egress-list { list-style: none; margin: 0 0 12px; padding: 0; }
.egress-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter); }
.egress-list strong { display: block; font-size: 14px; word-break: break-all; }
.egress-list small { color: var(--text-secondary); }
@media (max-width: 640px) { .egress-add { grid-template-columns: 1fr; } }
</style>
