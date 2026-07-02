<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'

defineEmits<{
  back: []
}>()
</script>

<template>
  <header class="page-header">
    <div>
      <el-button link :icon="ArrowLeft" @click="$emit('back')">返回项目详情</el-button>
      <h1>创建页面助手</h1>
    </div>
  </header>
</template>
