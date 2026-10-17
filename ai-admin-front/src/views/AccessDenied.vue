<template>
  <div class="access-denied-page">
    <el-result
      icon="warning"
      title="没有访问权限"
      sub-title="你当前的角色不能访问这个页面。如需开通，请联系平台管理员分配开发人员或运营人员角色。"
    >
      <template #extra>
        <el-button type="primary" @click="goToWorkspace">返回概览</el-button>
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
