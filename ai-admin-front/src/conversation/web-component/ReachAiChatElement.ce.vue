<template>
  <EmbedChatHost
    v-if="ready"
    :api-base="apiBase"
    :token-provider="tokenProvider"
    :bridge="bridge"
    :page="page"
    :context="context"
    :placeholder="placeholder"
    :on-unauthorized="onUnauthorized"
  />
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createEafPageBridge, type EafPageBridge } from '../../sdk/eafPageBridge'
import EmbedChatHost from '../../sdk/EmbedChatHost.vue'
import type { EafPageDescriptor } from '../../sdk/embedSession'

const props = withDefaults(defineProps<{
  apiBase?: string
  token?: string
  pageKey?: string
  route?: string
  placeholder?: string
}>(), {
  apiBase: '/api/embed',
  token: '',
  placeholder: '输入消息',
})

const ready = ref(false)
const bridge = ref<EafPageBridge>(createEafPageBridge())
const page = ref<EafPageDescriptor | undefined>(undefined)
const context = ref<Record<string, unknown>>({})

function tokenProvider() {
  return props.token
}

async function onUnauthorized() {
  return props.token
}

onMounted(() => {
  bridge.value = createEafPageBridge({
    route: props.route || (typeof location !== 'undefined' ? location.pathname : '/'),
  })
  if (props.pageKey) {
    page.value = { pageKey: props.pageKey }
  }
  ready.value = true
})
</script>
