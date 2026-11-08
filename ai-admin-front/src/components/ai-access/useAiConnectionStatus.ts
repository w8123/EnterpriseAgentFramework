import { computed, ref } from 'vue'
import {
  createPlatformAiConnection,
  listPlatformAiConnections,
  type CreatedPlatformAiConnection,
  type PlatformAiConnection,
} from '@/api/platformAiConnections'
import { platformAiSetup } from '@/views/me/platformAiSetup'
import { buildInstallPrompt, type AiToolOption } from './aiAccess'

/**
 * 当前账号的 AI 工具连接状态，所有「交给我的 AI」弹窗共用一份。
 * 列表只缓存在内存里；新建的连接密钥只在创建后立刻拼进安装提示词，不保存。
 */
const connections = ref<PlatformAiConnection[]>([])
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
let pending: Promise<void> | null = null

export function useAiConnectionStatus() {
  const active = computed(() => connections.value
    .filter(item => item.status === 'ACTIVE')
    .sort((a, b) => String(b.lastUsedAt ?? b.createdAt).localeCompare(String(a.lastUsedAt ?? a.createdAt))))
  const connected = computed(() => active.value.length > 0)

  async function refresh(force = false) {
    if (pending && !force) return pending
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    pending = (async () => {
      try {
        const { data } = await listPlatformAiConnections()
        connections.value = data
        loaded.value = true
      } catch {
        error.value = '连接状态加载失败，请稍后重试。'
      } finally {
        loading.value = false
        pending = null
      }
    })()
    return pending
  }

  /** 新建一条连接，返回安装提示词（含一次性密钥，只在内存里）。 */
  async function connect(tool: AiToolOption, ttlDays = 30): Promise<{ created: CreatedPlatformAiConnection; installPrompt: string }> {
    const { data } = await createPlatformAiConnection({ label: `我的 ${tool.label}`, toolKind: tool.kind, ttlDays })
    connections.value = [data.connection, ...connections.value.filter(item => item.id !== data.connection.id)]
    loaded.value = true
    const setup = platformAiSetup(data.connection.toolKind, window.location.origin, data.connection.id, tool.label)
    const installPrompt = buildInstallPrompt({
      toolKind: data.connection.toolKind,
      toolLabel: tool.label,
      manual: tool.manual,
      target: 'ReachAI 平台',
      configHint: setup.title,
      configContent: setup.content,
      secretVariable: setup.variable,
      secret: data.token,
    })
    return { created: data, installPrompt }
  }

  return { connections, active, connected, loaded, loading, error, refresh, connect }
}

/** 测试用：清空模块级缓存。 */
export function resetAiConnectionStatusForTest() {
  connections.value = []
  loaded.value = false
  loading.value = false
  error.value = ''
  pending = null
}
