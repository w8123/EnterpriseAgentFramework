<template>
  <el-card class="detail-card capability-review-card workbench-list-surface" shadow="never">
    <template #header>
      <div class="review-header">
        <div>
          <div class="section-title">
            <span class="title-mark" />
            <span>能力同步与变更</span>
          </div>
          <p>查看 SDK 上报形成的快照与字段差异；待评审项可应用或忽略，已应用变更可按规则回滚。</p>
        </div>
        <el-button :icon="Refresh" :loading="loadingSnapshots" @click="loadSnapshots">
          刷新列表
        </el-button>
      </div>
    </template>

    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      :title="loadError"
    />

    <el-empty v-else-if="!loadingSnapshots && snapshots.length === 0" description="尚无 SDK 能力快照" />

    <template v-else>
      <div class="snapshot-strip">
        <button
          v-for="snapshot in snapshots"
          :key="snapshot.id"
          type="button"
          class="snapshot-chip"
          :class="{ active: snapshot.id === selectedSnapshotId }"
          @click="selectSnapshot(snapshot.id)"
        >
          <strong>{{ formatTimestamp(snapshot.createdAt) }}</strong>
          <span>{{ snapshot.added }} 新增 · {{ snapshot.changed }} 变更 · {{ snapshot.deleted }} 停止上报</span>
          <el-tag size="small" :type="snapshotStatusTone(snapshot)">
            {{ snapshotStatusLabel(snapshot) }}
          </el-tag>
        </button>
      </div>

      <el-table
        v-loading="loadingItems"
        :data="items"
        row-key="id"
        class="review-table"
      >
        <el-table-column prop="name" label="能力" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <small>{{ row.qualifiedName }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="changeType" label="变更" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="changeTypeTone(row.changeType)">
              {{ changeTypeLabel(row.changeType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段差异" min-width="300">
          <template #default="{ row }">
            <div v-if="fieldDiffs(row).length" class="field-diff-list">
              <span v-for="diff in fieldDiffs(row)" :key="diff.field">
                <b>{{ diff.field }}</b>
                <code>{{ displayValue(diff.oldValue) }}</code>
                <i>→</i>
                <code>{{ displayValue(diff.newValue) }}</code>
              </span>
            </div>
            <span v-else class="muted">{{ row.changeType === 'DELETED' ? 'SDK 已不再上报' : '无字段变化' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewStatus" label="评审状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="reviewStatusTone(row)">
              {{ reviewStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <template v-if="row.reviewStatus === 'PENDING'">
              <el-button size="small" type="primary" :loading="actingId === row.id" @click="review(row, 'APPLY')">
                应用
              </el-button>
              <el-button size="small" :loading="actingId === row.id" @click="review(row, 'IGNORE')">
                忽略
              </el-button>
            </template>
            <el-button
              v-else-if="row.reviewStatus === 'APPLIED' && row.changeType !== 'UNCHANGED' && row.rollbackAvailable"
              size="small"
              type="warning"
              plain
              :loading="actingId === row.id"
              @click="rollback(row)"
            >
              回滚
            </el-button>
            <span v-else class="muted">无需操作</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listCapabilityDiffItems,
  listCapabilitySnapshots,
  reviewCapabilityDiffItem,
  rollbackCapabilityDiffItem,
} from '@/api/registry'
import type { CapabilityDiffReviewItem, CapabilitySnapshot } from '@/types/registry'

interface FieldDiff {
  field: string
  oldValue?: unknown
  newValue?: unknown
}

const props = defineProps<{ projectCode: string }>()

const snapshots = ref<CapabilitySnapshot[]>([])
const items = ref<CapabilityDiffReviewItem[]>([])
const selectedSnapshotId = ref<number>()
const loadingSnapshots = ref(false)
const loadingItems = ref(false)
const actingId = ref<number>()
const loadError = ref('')

async function loadSnapshots() {
  if (!props.projectCode) return
  loadingSnapshots.value = true
  loadError.value = ''
  try {
    const { data } = await listCapabilitySnapshots(props.projectCode)
    snapshots.value = data
    const selectedStillExists = snapshots.value.some((item) => item.id === selectedSnapshotId.value)
    selectedSnapshotId.value = selectedStillExists ? selectedSnapshotId.value : snapshots.value[0]?.id
    if (selectedSnapshotId.value) await loadItems(selectedSnapshotId.value)
    else items.value = []
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '能力快照加载失败'
  } finally {
    loadingSnapshots.value = false
  }
}

async function loadItems(snapshotId: number) {
  loadingItems.value = true
  loadError.value = ''
  try {
    const { data } = await listCapabilityDiffItems(snapshotId)
    items.value = data
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '能力差异加载失败'
  } finally {
    loadingItems.value = false
  }
}

async function selectSnapshot(snapshotId: number) {
  selectedSnapshotId.value = snapshotId
  await loadItems(snapshotId)
}

async function review(item: CapabilityDiffReviewItem, action: 'APPLY' | 'IGNORE') {
  const actionLabel = action === 'APPLY' ? '应用' : '忽略'
  try {
    const { value } = await ElMessageBox.prompt(
      `${actionLabel}后会记录评审人和原因。`,
      `${actionLabel}能力变更：${item.name}`,
      {
        confirmButtonText: actionLabel,
        cancelButtonText: '取消',
        inputPlaceholder: '填写评审说明（可选）',
      },
    )
    actingId.value = item.id
    await reviewCapabilityDiffItem(item.id, { action, note: value || undefined })
    await reloadSelected()
    ElMessage.success(`能力变更已${actionLabel}`)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : `${actionLabel}失败`)
  } finally {
    actingId.value = undefined
  }
}

async function rollback(item: CapabilityDiffReviewItem) {
  try {
    const { value } = await ElMessageBox.prompt(
      '回滚会恢复此评审应用前的扫描目录与可执行 Tool 状态；如果已有更新的已应用变更，服务端会拒绝覆盖。',
      `回滚能力变更：${item.name}`,
      {
        type: 'warning',
        confirmButtonText: '确认回滚',
        cancelButtonText: '取消',
        inputPlaceholder: '填写回滚原因（建议填写）',
      },
    )
    actingId.value = item.id
    await rollbackCapabilityDiffItem(item.id, { note: value || undefined })
    await reloadSelected()
    ElMessage.success('能力变更已回滚')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '回滚失败')
  } finally {
    actingId.value = undefined
  }
}

async function reloadSelected() {
  if (selectedSnapshotId.value) await loadItems(selectedSnapshotId.value)
}

function fieldDiffs(item: CapabilityDiffReviewItem): FieldDiff[] {
  try {
    const parsed = JSON.parse(item.fieldDiffJson || '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '∅'
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.length > 80 ? `${text.slice(0, 77)}…` : text
}

function formatTimestamp(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '未知时间'
}

function snapshotHasChanges(snapshot: CapabilitySnapshot) {
  return snapshot.added + snapshot.changed + snapshot.deleted > 0
}

function snapshotStatusLabel(snapshot: CapabilitySnapshot) {
  if (!snapshotHasChanges(snapshot)) return '无变化'
  return { PENDING: '待评审', APPLIED: '已应用', PARTIAL: '部分处理', IGNORED: '已忽略' }[snapshot.status] || snapshot.status
}

function snapshotStatusTone(snapshot: CapabilitySnapshot) {
  if (!snapshotHasChanges(snapshot) || snapshot.status === 'IGNORED') return 'info'
  if (snapshot.status === 'APPLIED') return 'success'
  return 'warning'
}

function changeTypeLabel(type: CapabilityDiffReviewItem['changeType']) {
  return { ADDED: '新增', CHANGED: '变更', UNCHANGED: '无变化', DELETED: '停止上报' }[type]
}

function changeTypeTone(type: CapabilityDiffReviewItem['changeType']) {
  if (type === 'ADDED') return 'success'
  if (type === 'DELETED') return 'danger'
  if (type === 'CHANGED') return 'warning'
  return 'info'
}

function reviewStatusLabel(item: CapabilityDiffReviewItem) {
  if (item.changeType === 'UNCHANGED') return '无需评审'
  return { PENDING: '待评审', APPLIED: '已应用', IGNORED: '已忽略', ROLLED_BACK: '已回滚' }[item.reviewStatus]
}

function reviewStatusTone(item: CapabilityDiffReviewItem) {
  if (item.changeType === 'UNCHANGED') return 'info'
  if (item.reviewStatus === 'APPLIED') return 'success'
  if (item.reviewStatus === 'ROLLED_BACK') return 'warning'
  if (item.reviewStatus === 'IGNORED') return 'info'
  return 'warning'
}

watch(() => props.projectCode, loadSnapshots)
onMounted(loadSnapshots)

defineExpose({ loadSnapshots })
</script>

<style scoped lang="scss">
.review-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.review-header p {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.title-mark {
  width: 3px;
  height: 16px;
  border-radius: 4px;
  background: var(--el-color-primary);
}

.snapshot-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.snapshot-chip {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 12px;
  padding: 12px 14px;
  text-align: left;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  cursor: pointer;
}

.snapshot-chip.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.snapshot-chip span:not(.el-tag) {
  grid-column: 1 / -1;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.review-table strong,
.review-table small {
  display: block;
}

.review-table small,
.muted {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.field-diff-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-diff-list span {
  display: grid;
  grid-template-columns: minmax(70px, auto) minmax(60px, 1fr) auto minmax(60px, 1fr);
  align-items: center;
  gap: 6px;
}

.field-diff-list code {
  overflow: hidden;
  padding: 2px 5px;
  color: var(--el-text-color-regular);
  text-overflow: ellipsis;
  white-space: nowrap;
  background: var(--el-fill-color);
  border-radius: 4px;
}
</style>
