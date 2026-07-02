<script setup lang="ts">
import type { ScanModule } from '@/types/semanticDoc'

defineProps<{
  mergeSelectedModules: ScanModule[]
  mergeSourceModules: ScanModule[]
  docEditSaving: boolean
  mergeSaving: boolean
  renameSaving: boolean
}>()

const docEditVisible = defineModel<boolean>('docEditVisible', { required: true })
const docEditContent = defineModel<string>('docEditContent', { required: true })
const mergeDialogVisible = defineModel<boolean>('mergeDialogVisible', { required: true })
const mergeTargetId = defineModel<number | null>('mergeTargetId', { required: true })
const mergeDisplayName = defineModel<string>('mergeDisplayName', { required: true })
const renameDialogVisible = defineModel<boolean>('renameDialogVisible', { required: true })
const renameValue = defineModel<string>('renameValue', { required: true })

const emit = defineEmits<{
  submitDocEdit: []
  submitMerge: []
  submitRename: []
}>()
</script>

<template>
  <el-dialog v-model="docEditVisible" title="编辑 AI 文档（保存后标记为 edited，不会被重新生成覆盖，除非强制）" width="720px">
    <el-input v-model="docEditContent" type="textarea" :rows="18" placeholder="Markdown 内容" />
    <template #footer>
      <el-button @click="docEditVisible = false">取消</el-button>
      <el-button type="primary" :loading="docEditSaving" @click="emit('submitDocEdit')">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="mergeDialogVisible" title="合并模块" width="520px">
    <el-form label-width="100px">
      <el-form-item label="合并目标">
        <el-select v-model="mergeTargetId" style="width: 100%">
          <el-option
            v-for="m in mergeSelectedModules"
            :key="m.id"
            :value="m.id"
            :label="`${m.displayName}（${m.name}）`"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="合并后名称">
        <el-input v-model="mergeDisplayName" placeholder="可选，留空则沿用目标模块名" />
      </el-form-item>
      <el-form-item label="被合并">
        <div>
          <el-tag
            v-for="m in mergeSourceModules"
            :key="m.id"
            class="source-class-tag"
          >{{ m.displayName }}</el-tag>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="mergeDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="mergeSaving" @click="emit('submitMerge')">确认合并</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="renameDialogVisible" title="重命名模块" width="420px">
    <el-form label-width="100px">
      <el-form-item label="展示名">
        <el-input v-model="renameValue" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="renameDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="renameSaving" @click="emit('submitRename')">保存</el-button>
    </template>
  </el-dialog>
</template>
