<script setup lang="ts" generic="Row extends { id?: number; assetId?: number }">
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { computed, ref, watch } from 'vue'
import { platformSessionId } from '@/auth/platformSession'
import { ArrowDown, ArrowRight, Document, MoreFilled } from '@element-plus/icons-vue'
import type { CatalogClassificationGroup, CatalogClassificationIndex } from '@/types/semanticDoc'
import { buildClassificationSections, classificationMemberships, classificationNavigationGroups } from './catalogClassification'

const props = withDefaults(defineProps<{ rows: Row[]; identity: (row: Row) => number;
  index: CatalogClassificationIndex | null; loading: boolean; error: string; selected: string;
  allProjects?: boolean; canWrite?: boolean; adjusting?: boolean; searchKey?: string; layout?: 'grouped' | 'flat'; showNavigation?: boolean }>(),
  { allProjects: false, canWrite: false, adjusting: false, searchKey: '', layout: 'grouped', showNavigation: true })
const emit = defineEmits<{ select: [value: string]; retry: [];
  create: [];
  assign: [rows: Row[]];
  edit: [group: CatalogClassificationGroup, mode: 'description' | 'rename'] }>()
const sections = computed(() => buildClassificationSections(props.rows, props.index, props.selected, props.identity))
const visibleGroups = computed(() => classificationNavigationGroups(props.index, props.selected, canAdjust.value))
const many = computed(() => visibleGroups.value.length > 5)
const canAdjust = computed(() => props.adjusting && props.canWrite && !props.allProjects)
const selectedIds = ref<number[]>([])
const selectedRows = computed(() => props.rows.filter(row => selectedIds.value.includes(props.identity(row))))
const allSelected = computed(() => props.rows.length > 0 && selectedRows.value.length === props.rows.length)
function toggleRow(row: Row, checked: boolean) {
  if (!canAdjust.value) return
  const id = props.identity(row)
  selectedIds.value = checked ? [...new Set([...selectedIds.value, id])] : selectedIds.value.filter(value => value !== id)
}
function selectPage(checked: boolean) {
  if (!canAdjust.value) return
  selectedIds.value = checked ? props.rows.map(props.identity) : []
}
watch([() => props.rows, () => props.canWrite, () => props.adjusting, () => props.allProjects,
  () => props.searchKey, platformSessionId], () => { selectedIds.value = [] }, { flush: 'sync' })
const collapsed = ref<string[]>([])
let currentProjectId = props.index?.projectId
watch(() => props.index?.projectId, projectId => {
  // Index refreshes pass through null; only a different project resets the user's disclosure state.
  if (projectId === undefined) return
  if (projectId !== currentProjectId) collapsed.value = []
  currentProjectId = projectId
})
// Parents supply committed conditions only, excluding draft input and pagination.
watch(() => props.searchKey, () => { collapsed.value = [] })
watch(() => props.allProjects, () => { collapsed.value = [] })
function toggle(key: string) { collapsed.value = collapsed.value.includes(key) ? collapsed.value.filter(value => value !== key) : [...collapsed.value, key] }
function membershipsFor(row: Row) { return classificationMemberships(props.index, props.identity(row)) }
function isSelected(row: Row) { return selectedIds.value.includes(props.identity(row)) }
function editGroup(group: CatalogClassificationGroup, command: string) {
  if (command === 'description' || (command === 'rename' && canAdjust.value)) emit('edit', group, command)
}
const selectedGroup = computed(() => props.index?.groups.find(group => String(group.id) === props.selected))
const selectionMissing = computed(() => !!props.selected && props.selected !== 'unclassified' && !!props.index && !selectedGroup.value)
</script>

<template>
  <div class="catalog-classification" :class="{ 'catalog-classification--many': many }">
    <template v-if="allProjects">
      <slot :rows="rows" :memberships-for="membershipsFor" :selected-ids="selectedIds" :is-selected="isSelected" :toggle-row="toggleRow" />
    </template>
    <template v-else>
      <LiquidGlassNotice
        v-if="error"
        type="warning"
        :title="error"
        class="classification-load-state"
        source="backdrop"
      >
        <template #actions><el-button link type="primary" aria-label="重新读取业务分类" @click="emit('retry')">重试分类</el-button></template>
      </LiquidGlassNotice>
      <p v-else-if="loading" class="classification-scope-hint" role="status">正在读取业务分类；能力列表仍可查看。</p>
      <p v-else-if="selectionMissing" class="classification-scope-hint" role="status">所选业务分类已不可用，请查看全部分类或重试。</p>
      <div v-if="index && showNavigation" :class="many ? 'classification-navigation' : 'classification-overview'" aria-label="业务分类，数量为项目全量">
        <span class="classification-navigation__label">业务分类</span>
        <button type="button" :class="{ 'is-selected': !selected }" :aria-pressed="!selected" data-classification="" @click="emit('select', '')">全部能力 <span>{{ index.memberships.length }}</span></button>
        <button v-for="group in visibleGroups" :key="group.id" type="button" :data-classification="group.id"
          :class="{ 'is-selected': selected === String(group.id) }" :aria-pressed="selected === String(group.id)" @click="emit('select', String(group.id))">
          {{ group.displayName || group.name }} <span>{{ group.memberCount }}</span>
        </button>
        <button type="button" data-classification="unclassified" :class="{ 'is-selected': selected === 'unclassified' }"
          :aria-pressed="selected === 'unclassified'" @click="emit('select', 'unclassified')">未分类 <span>{{ index.ungroupedCount }}</span></button>
      </div>
      <div v-if="canAdjust" class="classification-selection" aria-label="分类调整操作">
        <el-checkbox v-if="rows.length" :model-value="allSelected" :indeterminate="selectedRows.length > 0 && !allSelected"
          aria-label="选择本页全部能力" @change="selectPage(!!$event)">选择本页</el-checkbox>
        <span v-if="rows.length" role="status">已选 {{ selectedRows.length }} 项</span>
        <el-button v-if="rows.length" size="small" :disabled="!selectedRows.length || loading || !index" @click="emit('assign', selectedRows)">批量归类</el-button>
        <el-button class="classification-create" size="small" :disabled="loading || !index" @click="emit('create')">新增分类</el-button>
      </div>
      <div v-if="layout === 'flat'" class="classification-results classification-results--flat">
        <slot :rows="rows" :memberships-for="membershipsFor" :selected-ids="selectedIds" :is-selected="isSelected" :toggle-row="toggleRow" />
      </div>
      <div v-else class="classification-results">
        <section v-for="section in sections" :key="section.key" class="classification-section" :data-classification-section="section.key">
          <header class="classification-section__header">
            <button type="button" class="classification-section__toggle" :aria-expanded="!collapsed.includes(section.key)"
              :aria-label="`${collapsed.includes(section.key) ? '展开' : '收起'}${section.title}分类`" @click="toggle(section.key)">
              <el-icon><ArrowRight v-if="collapsed.includes(section.key)" /><ArrowDown v-else /></el-icon>
              <strong>{{ section.title }}</strong>
            </button>
            <span class="classification-section__count">本页 {{ section.rows.length }} 项<span v-if="section.memberCount !== undefined && section.memberCount !== section.rows.length"> · 分类共 {{ section.memberCount }} 项</span></span>
            <div class="classification-section__actions">
              <el-button v-if="section.group && selected !== section.key" text size="small" :aria-label="`仅查看${section.title}分类`"
                @click="emit('select', section.key)">查看此分类</el-button>
              <el-tooltip v-if="section.group && !canAdjust" content="查看分类说明" :trigger="['hover', 'focus']" placement="top">
                <el-button text size="small" class="classification-description-action" :aria-label="`查看${section.title}分类说明`"
                  :icon="Document" @click="editGroup(section.group!, 'description')" />
              </el-tooltip>
              <el-dropdown v-else-if="section.group" trigger="click" @command="editGroup(section.group!, $event)">
                <el-button text size="small" :aria-label="`整理${section.title}分类`" :icon="MoreFilled" />
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="description">查看分类说明</el-dropdown-item>
                  <el-dropdown-item command="rename">重命名分类</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
              <el-button v-else-if="section.key === 'pending' && !loading" text size="small" @click="emit('retry')">更新分类</el-button>
            </div>
          </header>
          <p v-if="section.key === 'pending'" class="classification-pending-hint">这些能力的分类暂未确认；更新分类后可查看归属。</p>
          <slot v-if="!collapsed.includes(section.key)" :rows="section.rows" :memberships-for="membershipsFor" :selected-ids="selectedIds" :is-selected="isSelected" :toggle-row="toggleRow" :section="section" />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.catalog-classification { display: grid; gap: 8px; min-width: 0; }
.classification-scope-hint, .classification-pending-hint { margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.classification-pending-hint { padding: 8px 0; }
.classification-overview, .classification-navigation { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; min-width: 0; }
.classification-navigation__label { color: var(--text-primary); font-size: 12px; font-weight: 600; margin-right: 6px; }
.classification-overview > button, .classification-navigation > button { display: flex; align-items: center; gap: 8px; border: 1px solid transparent; border-radius: var(--radius-sm); background: transparent; color: var(--text-secondary); padding: 6px 10px; font: inherit; font-size: 12px; cursor: pointer; text-align: left; }
.classification-overview > button:hover, .classification-navigation > button:hover { color: var(--text-primary); background: rgb(var(--brand-primary-rgb) / 0.04); }
.classification-overview > button span, .classification-navigation > button span { color: var(--text-muted); font-variant-numeric: tabular-nums; }
.classification-overview > button.is-selected, .classification-navigation > button.is-selected { color: var(--brand-active); border-color: var(--brand-active); background: rgb(var(--brand-primary-rgb) / 0.06); }
.classification-selection { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; color: var(--text-secondary); font-size: 12px; }
.classification-selection .classification-create { margin-left: auto; }
.classification-results { display: grid; gap: 8px; min-width: 0; }
.classification-section { min-width: 0; }
.classification-section__header { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--border-divider); }
.classification-section__toggle { display: inline-flex; align-items: center; gap: 7px; padding: 0; border: 0; background: transparent; color: var(--text-primary); cursor: pointer; font-size: 13px; line-height: 1.5; }
.classification-section__count { color: var(--text-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.classification-section__actions { display: flex; align-items: center; margin-left: auto; gap: 4px; }
.classification-section__actions :deep(.classification-description-action) { min-width: 32px; height: 32px; padding: 0; }
.catalog-classification button:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
@media (max-width: 760px) {
  .classification-navigation { flex-wrap: nowrap; overflow-x: auto; padding-bottom: 6px; }
  .classification-navigation > button, .classification-navigation__label { flex: 0 0 auto; white-space: nowrap; }
  .classification-section__actions { margin-left: 0; }
}
</style>
