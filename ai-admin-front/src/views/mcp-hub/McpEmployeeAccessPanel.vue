<script setup lang="ts">
import { onMounted, reactive, ref, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { LiquidGlassButton, LiquidGlassNotice } from '@/components/common/liquid-glass'
import { getMcpEmployeeAccess, saveMcpEmployeeAccess } from '@/api/mcp'
import { getScanProjects } from '@/api/scanProject'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE } from '@/auth/platformAccess'
const { can } = usePlatformCan()
const canManage = computed(() => can(PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE))

const props = defineProps<{
  publicationId: number
  /** Only a published revision is offered to employees. */
  published: boolean
}>()

const loading = ref(false)
const saving = ref(false)
const saved = ref(false)
const projects = ref<Array<{ id: number; code: string; name: string }>>([])
const form = reactive({ enabled: false, projectCode: '', displayName: '' })

function extractErrorMessage(error: unknown): string {
  const anyError = error as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return anyError?.response?.data?.message
    || anyError?.response?.data?.detail
    || anyError?.message
    || '请求失败'
}

async function load() {
  loading.value = true
  try {
    const { data } = await getMcpEmployeeAccess(props.publicationId)
    form.enabled = Boolean(data?.enabled)
    form.projectCode = data?.projectCode ?? ''
    form.displayName = data?.displayName ?? ''
    saved.value = Boolean(data?.projectCode)
  } catch (error) {
    ElMessage.error(`员工使用设置加载失败：${extractErrorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  try {
    const { data } = await getScanProjects()
    projects.value = (data ?? [])
      .filter((project) => project.projectCode)
      .map((project) => ({ id: project.id, code: String(project.projectCode), name: project.name }))
  } catch {
    // The code can still be typed in by hand.
    projects.value = []
  }
}

function pickProject(code: string) {
  if (!form.displayName) {
    form.displayName = projects.value.find((project) => project.code === code)?.name ?? ''
  }
}

async function save() {
  if (!form.projectCode.trim()) {
    ElMessage.warning('请选择业务系统')
    return
  }
  saving.value = true
  try {
    const projectCode = form.projectCode.trim()
    const { data } = await saveMcpEmployeeAccess(props.publicationId, {
      // 调用权限里指定了项目的规则按项目 id 匹配，所以要一起保存。
      projectId: projects.value.find((project) => project.code === projectCode)?.id,
      projectCode,
      displayName: form.displayName.trim() || undefined,
      enabled: form.enabled,
    })
    form.enabled = data.enabled
    form.projectCode = data.projectCode ?? ''
    form.displayName = data.displayName ?? ''
    saved.value = true
    ElMessage.success(data.enabled ? '已开通，员工现在可以在对话面板里接入自己的 AI 工具' : '已保存，员工暂不可用')
  } catch (error) {
    ElMessage.error(`保存失败：${extractErrorMessage(error)}`)
  } finally {
    saving.value = false
  }
}

watch(() => props.publicationId, load)
onMounted(() => {
  void load()
  void loadProjects()
})
</script>

<template>
  <div class="employee-access" :aria-busy="loading">
    <p class="tab-hint">
      开通后，这个业务系统的员工在 AI 对话面板里点「在我的 AI 工具中使用」，勾选系统、复制一行内容，就能在自己的 Claude Code、Codex 里以本人身份办业务。
      员工能用哪些工具，仍由「调用权限」按业务系统角色控制；不可逆的工具不会开放给员工。
    </p>

    <LiquidGlassNotice
      v-if="!published"
      type="warning"
      source="backdrop"
      title="本发布还没有发布修订，开通后员工也看不到它；先发布再开通。"
    />

    <el-form label-position="top" class="employee-access__form" :disabled="!canManage">
      <el-form-item label="员工可用">
        <el-switch v-model="form.enabled" active-text="开通" inactive-text="关闭" />
      </el-form-item>
      <el-form-item label="业务系统" required>
        <el-select
          v-model="form.projectCode"
          filterable
          allow-create
          default-first-option
          placeholder="选择接入的业务系统"
          aria-label="业务系统"
          @change="pickProject"
        >
          <el-option v-for="project in projects" :key="project.code" :label="`${project.name}（${project.code}）`" :value="project.code" />
        </el-select>
        <small class="field-hint">一个业务系统只能对应一个发布；员工的身份和角色来自这个系统。</small>
      </el-form-item>
      <el-form-item label="员工看到的名称">
        <el-input v-model="form.displayName" maxlength="64" placeholder="例如 芋道 ERP" />
      </el-form-item>
    </el-form>

    <div class="employee-access__actions">
      <LiquidGlassButton v-if="canManage" type="primary" :loading="saving" @click="save">保存</LiquidGlassButton>
      <router-link v-if="saved" class="employee-access__link" to="/mcp-hub/employee-connections">查看员工连接</router-link>
    </div>
  </div>
</template>

<style scoped lang="scss">
.employee-access {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.tab-hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.employee-access__form {
  max-width: 520px;
}

.employee-access__form :deep(.el-select) {
  width: 100%;
}

.field-hint {
  display: block;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.employee-access__actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.employee-access__link {
  color: var(--color-primary, var(--el-color-primary));
  font-size: 13px;
}
</style>
