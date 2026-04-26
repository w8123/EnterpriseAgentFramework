<template>
  <div>
    <p v-if="message">{{ message }}</p>
    <el-input v-model="text" type="textarea" :rows="2" placeholder="请输入" />
    <div style="margin-top: 8px">
      <el-button type="primary" @click="onOk">确定</el-button>
      <el-button @click="$emit('cancel')">取消</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  message?: string
  answerKey?: string
}>()

const emit = defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
}>()

const text = ref('')

function onOk() {
  const key = props.answerKey || 'answer'
  emit('submit', { [key]: text.value })
}
</script>
