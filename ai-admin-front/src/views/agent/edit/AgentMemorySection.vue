<template>
  <WorkbenchPanel
    v-show="isNew || activeConfigSection === 'memory'"
    id="agent-config-memory"
    class="memory-panel"
    title="记忆"
    density="compact"
  >
    <el-form label-position="top" class="memory-policy-form">
      <el-form-item label="会话记忆">
        <el-switch v-model="memoryPolicy.sessionMemoryEnabled" active-text="开启" inactive-text="关闭" />
        <p class="memory-policy-form__hint">开启后，同一对话里 Agent 记得前几轮说过的内容；关闭后每轮独立回答。</p>
      </el-form-item>
      <el-form-item label="上下文窗口（最近消息条数）">
        <el-input-number
          v-model="memoryPolicy.sessionMemoryWindow"
          :min="4"
          :max="100"
          :step="2"
          :disabled="!memoryPolicy.sessionMemoryEnabled"
          controls-position="right"
        />
        <p class="memory-policy-form__hint">越大越能联系上文，但每轮消耗的模型 token 越多。</p>
      </el-form-item>
      <el-form-item label="读取个人记忆">
        <el-switch v-model="memoryPolicy.personalMemoryRead" active-text="读取" inactive-text="不读取" />
        <p class="memory-policy-form__hint">个人记忆属于用户本人，在所有 Agent 间共享。开启后，回答前会带上与问题相关的用户偏好和事实。</p>
      </el-form-item>
      <el-form-item label="写入个人记忆">
        <el-select v-model="memoryPolicy.personalMemoryWrite" class="memory-policy-form__select">
          <el-option label="关闭" value="OFF" />
          <el-option label="仅用户明确要求（如“记住……”）" value="EXPLICIT" />
          <el-option label="明确要求 + 自动候选（需用户确认）" value="EXPLICIT_AND_CANDIDATE" />
        </el-select>
        <p class="memory-policy-form__hint">自动候选不会直接生效，需用户确认后才会被召回。</p>
      </el-form-item>
    </el-form>
  </WorkbenchPanel>
</template>

<script setup lang="ts">
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { useAgentEditorContext } from './useAgentEditor'

const {
  isNew,
  activeConfigSection,
  memoryPolicy,
} = useAgentEditorContext()
</script>

<style scoped lang="scss">
.memory-policy-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 4px 24px;
  max-width: 960px;
}

.memory-policy-form :deep(.el-form-item__content) {
  flex-direction: column;
  align-items: flex-start;
}

.memory-policy-form__hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}

.memory-policy-form__select {
  width: 100%;
  max-width: 360px;
}
</style>
