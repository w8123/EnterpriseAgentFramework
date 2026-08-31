<template>
  <div class="access-denied-page">
    <el-result
      icon="warning"
      title="没有访问权限"
      sub-title="当前平台角色不能访问这个工作区或资源。你的登录会话仍然有效。"
    >
      <template #extra>
        <el-button type="primary" @click="goToWorkspace">返回可用工作台</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goToWorkspace() {
  router.push('/dashboard')
}
</script>

<style scoped>
.access-denied-page {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: var(--space-8, 32px);
}
</style>
