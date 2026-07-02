<script setup lang="ts">
export interface ManualActionForm {
  pageKey: string
  pageName: string
  routePattern: string
  actionKey: string
  title: string
  description: string
  confirmRequired: boolean
  inputSchemaText: string
  sampleArgsText: string
}

defineProps<{
  visible: boolean
  submitting: boolean
  form: ManualActionForm
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submit: []
}>()
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="手工声明页面动作"
    width="720px"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <el-form label-position="top">
      <div class="manual-grid">
        <el-form-item label="pageKey">
          <el-input v-model="form.pageKey" placeholder="teamArchive.list" />
        </el-form-item>
        <el-form-item label="页面名称">
          <el-input v-model="form.pageName" placeholder="班组档案" />
        </el-form-item>
        <el-form-item label="路由">
          <el-input v-model="form.routePattern" placeholder="/teams/archive" />
        </el-form-item>
        <el-form-item label="actionKey">
          <el-input v-model="form.actionKey" placeholder="qmssmp.teamArchive.search" />
        </el-form-item>
        <el-form-item label="动作标题">
          <el-input v-model="form.title" placeholder="查询班组档案" />
        </el-form-item>
        <el-form-item label="二次确认">
          <el-switch v-model="form.confirmRequired" />
        </el-form-item>
      </div>
      <el-form-item label="动作描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="inputSchema JSON">
        <el-input v-model="form.inputSchemaText" type="textarea" :rows="5" />
      </el-form-item>
      <el-form-item label="sampleArgs JSON">
        <el-input v-model="form.sampleArgsText" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="emit('submit')">保存动作草案</el-button>
    </template>
  </el-dialog>
</template>
