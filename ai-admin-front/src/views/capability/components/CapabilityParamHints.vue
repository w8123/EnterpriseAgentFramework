<script setup lang="ts">
import { computed } from 'vue'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import type { CapabilityEnrichment } from '@/types/semanticDoc'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{ projectId: number; subjectType: 'BUSINESS_METHOD' | 'HTTP_API'; subjectId: number;
  graphState: CapabilityEnrichment['graphState']; contractHash: string | null; hints?: ApiGraphParamSourceHint[] }>()
const emit = defineEmits<{ retry: [] }>()
const valid = computed(() => Array.isArray(props.hints) && props.hints.every(hint => !!hint && [hint.targetPath,
  hint.targetField, hint.targetApi, hint.sourcePath, hint.sourceField, hint.sourceApi].every(value => typeof value === 'string')))
const hints = computed(() => valid.value ? props.hints! : [])
</script>

<template>
  <section class="capability-param-hints" aria-label="编排参考：已确认参数来源">
    <h4>编排参考 · 已确认参数来源</h4>
    <p class="hint-explanation">这些出参到入参关系供编排时参考，不代表已被流程使用。实际引用请查看“使用位置”；这里不会自动修改 Workflow 或版本引用。</p>
    <p v-if="graphState === 'UNACCEPTED'">当前契约尚未接纳，参数来源提示暂不可用。</p>
    <p v-else-if="graphState === 'NOT_BUILT'">当前资产尚未建立关系图谱。</p>
    <p v-else-if="graphState !== 'CURRENT' && graphState !== 'STALE'" role="alert">当前图谱状态无法确认，请重新加载业务说明与关系。</p>
    <template v-else>
      <LiquidGlassNotice
        v-if="graphState === 'STALE'"
        title="图谱或已确认关系基于此前契约；请重建图谱并重新核对关系。过期提示已排除。"
        type="warning"
        source="backdrop"
      />
      <div v-if="!valid" role="alert"><p>已确认参数来源读取失败，当前无法判断是否有可用提示。</p><el-button @click="emit('retry')">重新加载参数来源</el-button></div>
      <ul v-else-if="hints.length">
        <li v-for="(hint, index) in hints" :key="index">
          <strong>{{ hint.targetField || hint.targetPath }}</strong>
          <span>来自 {{ hint.sourceApi }} 的 {{ hint.sourceField || hint.sourcePath }}</span>
          <small>{{ hint.sourcePath }} → {{ hint.targetPath }}</small>
        </li>
      </ul>
      <p v-else-if="graphState === 'CURRENT'">当前契约尚无可用的已确认参数来源。</p>
      <p v-else>当前未取得仍有效的参数来源提示；请先核对图谱修订。</p>
    </template>
  </section>
</template>

<style scoped lang="scss">
.capability-param-hints { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-divider); overflow-wrap: anywhere; }
.capability-param-hints h4 { margin: 0 0 8px; font-size: 13px; }
.capability-param-hints p { color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.capability-param-hints ul { list-style: none; padding: 0; }
.capability-param-hints li { display: grid; gap: 4px; padding: 8px 0; font-size: 12px; }
.capability-param-hints small { color: var(--text-muted); }
</style>
