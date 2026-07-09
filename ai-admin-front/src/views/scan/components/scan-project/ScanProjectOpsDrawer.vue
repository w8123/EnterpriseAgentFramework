<script setup lang="ts">
defineProps<{
  rebuildEmbeddingLoading: boolean
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  rebuildEmbeddings: []
}>()
</script>

<template>
  <el-drawer
    v-model="visible"
    size="520px"
    title="维护动作"
    destroy-on-close
    append-to-body
  >
    <div class="ops-action-list">
      <div class="ops-action-item">
        <div>
          <strong>重建向量索引</strong>
          <span>重建当前项目接口语义检索索引。</span>
        </div>
        <el-button type="info" plain :loading="rebuildEmbeddingLoading" @click="emit('rebuildEmbeddings')">
          重建向量索引
        </el-button>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-drawer>
</template>
